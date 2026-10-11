package cc.ecisr.mmm.fluid;

/** Integrates a PLIC plane over the same open micro boxes and shared apertures as pressure. */
final class LiquidFluxGeometry {
	private final FlipCutCellGeometry cut;
	private final FluidDomain domain;
	LiquidFluxGeometry(FlipCutCellGeometry cut) { this.cut = cut; domain = null; }
	LiquidFluxGeometry(FluidDomain domain) { this.domain = domain; cut = domain.geometry.cutCells; }

	FlipPressurePhaseGeometry transportPlane() { return domain == null ? null : domain.pressurePhaseGeometry; }

	cc.ecisr.mmm.units.SignedDyadic[] quantizationBudget(double dtOverH) {
		return domain == null ? null : GranularQuantizationError.budget(domain, (float) (dtOverH * domain.config.cellSize()));
	}

	boolean connected(int left, int right, int axis) {
		if (domain == null) return aperture(left, right, axis) > 0;
		int x = right % domain.sizeX, y = right / domain.sizeX % domain.sizeY, z = right / (domain.sizeX * domain.sizeY);
		return switch (axis) {
			case 0 -> cut.xFaceOpen(x, y, z) > 0;
			case 1 -> cut.yFaceOpen(x, y, z) > 0;
			default -> cut.zFaceOpen(x, y, z) > 0;
		};
	}

	double capacity(int cell) { return domain == null ? cut.openCellVolume(cell) : domain.effectiveOpenCellVolume(cell); }
	double finalCapacity(int cell) {
		return domain != null && domain.granularState != null && domain.granularState.parcels.count() > 0
				? domain.granularNextCoupling.effectiveOpenCellVolume(cell, cut.openCellVolume(cell)) : capacity(cell);
	}
	private double poreScale(int cell) { return cut.openCellVolume(cell) > 0 ? capacity(cell) / cut.openCellVolume(cell) : 0; }

	double aperture(int left, int right, int axis) {
		double pore = Math.min(poreScale(left), poreScale(right));
		if (cut.mask(left) == 0 && cut.mask(right) == 0) return pore;
		int open = 0;
		for (int a = 0; a < 2; a++) for (int b = 0; b < 2; b++)
			if (openFace(left, right, axis, a, b)) open++;
		return 0.25 * open * pore;
	}

	boolean partialFace(int left, int right) { return cut.mask(left) != 0 || cut.mask(right) != 0; }

	double offset(int cell, float nx, float ny, float nz, double volume) {
		if (capacity(cell) == 0) return Math.min(0.0, nx) + Math.min(0.0, ny) + Math.min(0.0, nz);
		volume /= poreScale(cell);
		if (cut.mask(cell) == 0) return FlipFreeSurfaceGeometry.solvePlaneOffset((double) nx, ny, nz, volume);
		double lower = Math.min(0.0, nx) + Math.min(0.0, ny) + Math.min(0.0, nz);
		double upper = Math.max(0.0, nx) + Math.max(0.0, ny) + Math.max(0.0, nz);
		for (int i = 0; i < 64; i++) {
			double middle = 0.5 * (lower + upper);
			if (middle == lower || middle == upper) break;
			if (integral(cell, nx, ny, nz, middle, -1, 1, true, -1) < volume) lower = middle; else upper = middle;
		}
		return 0.5 * (lower + upper);
	}

	double swept(int cell, int neighbor, int axis, double width, boolean positive, double nx, double ny, double nz, double offset) {
		double pore = Math.min(poreScale(cell), poreScale(neighbor));
		if (cut.mask(cell) == 0 && cut.mask(neighbor) == 0) {
			double off = offset;
			if (positive) off -= (axis == 0 ? nx : axis == 1 ? ny : nz) * (1 - width);
			if (axis == 0) nx *= width; else if (axis == 1) ny *= width; else nz *= width;
			return pore * width * FlipFreeSurfaceGeometry.unitCubeLiquidFraction(nx, ny, nz, off);
		}
		return pore * integral(cell, nx, ny, nz, offset, axis, width, positive, neighbor);
	}

	/** Volume and three initial-position moments, relative to the donor centre, in the same swept open boxes. */
	void sweptMoments(int cell, int neighbor, int axis, double width, boolean positive,
			double nx, double ny, double nz, double offset, double[] result) {
		double volume = 0, mx = 0, my = 0, mz = 0;
		boolean whole = cut.mask(cell) == 0 && cut.mask(neighbor) == 0;
		for (int slot = 0; slot < (whole ? 1 : 8); slot++) {
			int x = slot & 1, y = slot >>> 1 & 1, z = slot >>> 2 & 1;
			if (!whole && (!cut.microCellOpen(cell, x, y, z) || !cut.microCellOpen(neighbor,
					axis == 0 ? (positive ? 0 : 1) : x, axis == 1 ? (positive ? 0 : 1) : y,
					axis == 2 ? (positive ? 0 : 1) : z))) continue;
			double ox = whole ? 0 : .5 * x, oy = whole ? 0 : .5 * y, oz = whole ? 0 : .5 * z;
			double ex = whole ? 1 : .5, ey = ex, ez = ex;
			double lo = Math.max(axis == 0 ? ox : axis == 1 ? oy : oz, positive ? 1 - width : 0);
			double hi = Math.min((axis == 0 ? ox + ex : axis == 1 ? oy + ey : oz + ez), positive ? 1 : width);
			if (hi <= lo) continue;
			double span = whole ? width : hi - lo;
			if (axis == 0) { ox = lo; ex = span; } else if (axis == 1) { oy = lo; ey = span; } else { oz = lo; ez = span; }
			unitCubeMoments(nx * ex, ny * ey, nz * ez, offset - nx * ox - ny * oy - nz * oz, result);
			double box = ex * ey * ez;
			volume += box * result[0]; mx += box * (ox * result[0] + ex * result[1]);
			my += box * (oy * result[0] + ey * result[2]); mz += box * (oz * result[0] + ez * result[3]);
		}
		double pore = Math.min(poreScale(cell), poreScale(neighbor));
		result[0] = pore * volume; result[1] = pore * (mx - .5 * volume);
		result[2] = pore * (my - .5 * volume); result[3] = pore * (mz - .5 * volume);
	}

	/** Stable moments of a clipped unit cube, using the same sorted-coefficient CDF as its volume. */
	static void unitCubeMoments(double nx, double ny, double nz, double offset, double[] result) {
		double volume = FlipFreeSurfaceGeometry.unitCubeLiquidFraction(nx, ny, nz, offset);
		result[0] = volume;
		if (volume == 0 || volume == 1) { result[1] = result[2] = result[3] = .5 * volume; return; }
		double a = Math.abs(nx), b = Math.abs(ny), c = Math.abs(nz);
		double t = offset + (nx < 0 ? a : 0) + (ny < 0 ? b : 0) + (nz < 0 ? c : 0), sum = a + b + c;
		int ia = 1, ib = 2, ic = 3;
		if (a > b) { double swap = a; a = b; b = swap; int i = ia; ia = ib; ib = i; }
		if (b > c) { double swap = b; b = c; c = swap; int i = ib; ib = ic; ic = i; }
		if (a > b) { double swap = a; a = b; b = swap; int i = ia; ia = ib; ib = i; }
		boolean complement = t > .5 * sum; if (complement) t = sum - t;
		double q = rectangleMoment(a, b, t, 0), previous = rectangleMoment(a, b, t - c, 0);
		double smallVolume = (q - previous) / c;
		result[ia] = (rectangleMoment(a, b, t, 1) - rectangleMoment(a, b, t - c, 1)) / c;
		result[ib] = (rectangleMoment(a, b, t, 2) - rectangleMoment(a, b, t - c, 2)) / c;
		result[ic] = (rectangleMoment(a, b, t, 3) - rectangleMoment(a, b, t - c, 3) - 2 * c * previous) / (2 * c * c);
		if (complement) for (int i = 1; i < 4; i++) result[i] += .5 - smallVolume;
		if (nx < 0) result[1] = volume - result[1];
		if (ny < 0) result[2] = volume - result[2];
		if (nz < 0) result[3] = volume - result[3];
	}

	/** Integrals of w+, x*w+, y*w+, and w+ squared, with w=t-a*x-b*y and 0<=a<=b. */
	private static double rectangleMoment(double a, double b, double t, int component) {
		if (t <= 0) return 0;
		if (b == 0) return component == 3 ? t * t : component == 0 ? t : .5 * t;
		if (a == 0 && t <= b) {
			double q = t * t / (2 * b), r = t * t * t / (3 * b);
			return component == 0 ? q : component == 1 ? .5 * q : component == 2 ? r / (2 * b) : r;
		}
		if (component == 0 && a > 0) return FlipFreeSurfaceGeometry.rectangleIntegral(a, b, t);
		if (a > 0 && t <= a) {
			double q = t * t * t / (6 * a * b);
			return component == 1 ? q * t / (4 * a) : component == 2 ? q * t / (4 * b) : .5 * q * t;
		}
		if (a > 0 && t <= b) {
			double p = t - a, r = (p * p * (p + 1.5 * a) + a * a * (p + .25 * a)) / (3 * b);
			return component == 1 ? (p * p / 2 + a * p / 3 + a * a / 12) / (2 * b) : component == 2 ? r / (2 * b) : r;
		}
		double sum = a + b, centered = t - .5 * sum;
		double full = component == 0 ? centered : component == 1 ? t / 2 - a / 3 - b / 4
				: component == 2 ? t / 2 - a / 4 - b / 3 : centered * centered + (a * a + b * b) / 12;
		if (t >= sum) return full;
		double remaining = sum - t;
		return component == 3 ? full - rectangleMoment(a, b, remaining, 3)
				: full + rectangleMoment(a, b, remaining, 0) - rectangleMoment(a, b, remaining, component);
	}

	private double integral(int cell, double nx, double ny, double nz, double offset, int axis, double width, boolean positive, int neighbor) {
		double sum = 0;
		for (int slot = 0; slot < 8; slot++) {
			int[] micro = {slot & 1, slot >>> 1 & 1, slot >>> 2 & 1};
			if (!cut.microCellOpen(cell, micro[0], micro[1], micro[2])) continue;
			if (axis >= 0 && neighbor >= 0) {
				int old = micro[axis]; micro[axis] = positive ? 0 : 1;
				boolean open = cut.microCellOpen(neighbor, micro[0], micro[1], micro[2]); micro[axis] = old;
				if (!open) continue;
			}
			double[] origin = {0.5 * micro[0], 0.5 * micro[1], 0.5 * micro[2]}, extent = {0.5, 0.5, 0.5};
			if (axis >= 0) {
				double lo = Math.max(origin[axis], positive ? 1 - width : 0);
				double hi = Math.min(origin[axis] + 0.5, positive ? 1 : width);
				if (hi <= lo) continue;
				origin[axis] = lo; extent[axis] = hi - lo;
			}
			double off = offset - nx * origin[0] - ny * origin[1] - nz * origin[2];
			sum += extent[0] * extent[1] * extent[2] * FlipFreeSurfaceGeometry.unitCubeLiquidFraction(
					nx * extent[0], ny * extent[1], nz * extent[2], off);
		}
		return sum;
	}

	private boolean openFace(int left, int right, int axis, int a, int b) {
		return switch (axis) {
			case 0 -> cut.microCellOpen(left, 1, a, b) && cut.microCellOpen(right, 0, a, b);
			case 1 -> cut.microCellOpen(left, a, 1, b) && cut.microCellOpen(right, a, 0, b);
			default -> cut.microCellOpen(left, a, b, 1) && cut.microCellOpen(right, a, b, 0);
		};
	}
}

package cc.ecisr.mmm.fluid;

/** Integrates a PLIC plane over the same open micro boxes and shared apertures as pressure. */
final class LiquidFluxGeometry {
	private final FlipCutCellGeometry cut;
	private final FluidDomain domain;
	LiquidFluxGeometry(FlipCutCellGeometry cut) { this.cut = cut; domain = null; }
	LiquidFluxGeometry(FluidDomain domain) { this.domain = domain; cut = domain.geometry.cutCells; }

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

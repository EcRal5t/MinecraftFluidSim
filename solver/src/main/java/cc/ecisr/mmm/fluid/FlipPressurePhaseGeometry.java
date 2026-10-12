package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.granular.GranularCouplingFields;
import cc.ecisr.mmm.chemistry.ExactRational;
import cc.ecisr.mmm.units.SignedDyadic;
import java.math.BigInteger;

/** PLIC reconstructed from current liquid inventory, shared by pressure and cell transport. */
final class FlipPressurePhaseGeometry {

	private final int sizeX;
	private final int sizeY;
	private final int sizeZ;
	private final double[] fraction;
	private final float[] normalX;
	private final float[] normalY;
	private final float[] normalZ;
	private final double[] planeOffset;
	private final double[] faceX = new double[8], faceY = new double[8];
	private final double[] clippedX = new double[8], clippedY = new double[8];

	FlipPressurePhaseGeometry(int sizeX, int sizeY, int sizeZ) {
		if (sizeX < 2 || sizeY < 2 || sizeZ < 2) {
			throw new IllegalArgumentException("Pressure phase grid is too small");
		}
		this.sizeX = sizeX;
		this.sizeY = sizeY;
		this.sizeZ = sizeZ;
		int cellCount = Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ);
		this.fraction = new double[cellCount];
		this.normalX = new float[cellCount];
		this.normalY = new float[cellCount];
		this.normalZ = new float[cellCount];
		this.planeOffset = new double[cellCount];
	}

	boolean matchesShape(int x, int y, int z) { return sizeX == x && sizeY == y && sizeZ == z; }

	void rebuild(
			boolean[] solid, double[] reconstructedVolume, FlipCutCellGeometry cutCells,
			GranularCouplingFields granular, boolean[] pressureFluid
	) {
		rebuild(solid, reconstructedVolume, cutCells, granular, pressureFluid, null, null, 0);
	}

	void rebuild(
			boolean[] solid, double[] reconstructedVolume, FlipCutCellGeometry cutCells,
			GranularCouplingFields granular, boolean[] pressureFluid,
			MarkerGridIndex markers, LiquidParticleStore particles, float cellSize
	) {
		rebuild(solid, reconstructedVolume, cutCells, granular, pressureFluid, markers, particles, cellSize, null);
	}

	/** Reconstructs the same full grid from actual future volume and position moments in one edited cell. */
	void rebuild(
			boolean[] solid, double[] reconstructedVolume, FlipCutCellGeometry cutCells,
			GranularCouplingFields granular, boolean[] pressureFluid,
			MarkerGridIndex markers, LiquidParticleStore particles, float cellSize, PhaseLiquidGeometryEdit edit
	) {
		if (solid.length != fraction.length || reconstructedVolume.length != fraction.length
				|| pressureFluid.length != fraction.length || edit != null && (edit.cell() >= fraction.length || markers == null)
				|| markers != null && (particles == null || !(cellSize > 0) || !Float.isFinite(cellSize))) {
			throw new IllegalArgumentException("Pressure phase arrays disagree with the cell count");
		}
		for (int cell = 0; cell < fraction.length; cell++) {
			float geometricOpen = cutCells.openCellVolume(cell);
			float openVolume = granular == null ? geometricOpen
					: granular.effectiveOpenCellVolume(cell, geometricOpen);
			double liquid = edit != null && cell == edit.cell() ? edit.volume().toDouble() : reconstructedVolume[cell];
			fraction[cell] = solid[cell] || openVolume <= 0.0F ? 0.0F : clamp(liquid / openVolume, 0.0, 1.0);
			pressureFluid[cell] = false;
			normalX[cell] = normalY[cell] = normalZ[cell] = 0.0F; planeOffset[cell] = 0;
		}
		var integration = new LiquidFluxGeometry(cutCells);
		for (int z = 0; z < sizeZ; z++) for (int y = 0; y < sizeY; y++) for (int x = 0; x < sizeX; x++) {
			int cell = cellIndex(x, y, z);
			if (solid[cell] || !(fraction[cell] > 0.0F) || fraction[cell] >= 1) continue;
			double center = fraction[cell];
			float nx = (float) -(sampleFraction(x + 1, y, z, center, solid)
					- sampleFraction(x - 1, y, z, center, solid));
			float ny = (float) -(sampleFraction(x, y + 1, z, center, solid)
					- sampleFraction(x, y - 1, z, center, solid));
			float nz = (float) -(sampleFraction(x, y, z + 1, center, solid)
					- sampleFraction(x, y, z - 1, center, solid));
			if (markers != null && momentNormal(cell, x, y, z, cutCells, markers, particles, cellSize,
					edit != null && cell == edit.cell() ? edit : null)) {
				nx = normalX[cell]; ny = normalY[cell]; nz = normalZ[cell];
			}
			double lengthSquared = (double) nx * nx + (double) ny * ny + (double) nz * nz;
			if (lengthSquared == 0) {
				nx = airDirection(x + 1, y, z, solid) - airDirection(x - 1, y, z, solid);
				ny = airDirection(x, y + 1, z, solid) - airDirection(x, y - 1, z, solid);
				nz = airDirection(x, y, z + 1, solid) - airDirection(x, y, z - 1, solid);
				lengthSquared = (double) nx * nx + (double) ny * ny + (double) nz * nz;
			}
			if (lengthSquared == 0) ny = 1.0F;
			else {
				double inverseLength = 1.0 / Math.sqrt(lengthSquared);
				nx = (float) (nx * inverseLength);
				ny = (float) (ny * inverseLength);
				nz = (float) (nz * inverseLength);
			}
			normalX[cell] = nx;
			normalY[cell] = ny;
			normalZ[cell] = nz;
			planeOffset[cell] = integration.offset(cell, nx, ny, nz, center * cutCells.openCellVolume(cell));
		}
		for (int cell = 0; cell < fraction.length; cell++) {
			if (solid[cell] || !(fraction[cell] > 0.5F)) continue;
			if (fraction[cell] >= 1) pressureFluid[cell] = true;
			else pressureFluid[cell] = planeOffset[cell]
					> 0.5 * ((double) normalX[cell] + normalY[cell] + normalZ[cell]);
		}
	}

	/** Monotone moment estimate: a centred phase retains the fraction-gradient direction. */
	private boolean momentNormal(
			int cell, int x, int y, int z, FlipCutCellGeometry cut,
			MarkerGridIndex markers, LiquidParticleStore particles, float cellSize, PhaseLiquidGeometryEdit edit
	) {
		SignedDyadic volume = edit == null ? SignedDyadic.ZERO : edit.volume();
		SignedDyadic[] first = edit == null ? new SignedDyadic[] {SignedDyadic.ZERO, SignedDyadic.ZERO, SignedDyadic.ZERO}
				: new SignedDyadic[] {edit.firstMomentX(), edit.firstMomentY(), edit.firstMomentZ()};
		if (edit == null) for (int p = markers.cellMarkerHead[cell]; p >= 0; p = markers.particleCellNext[p]) {
			SignedDyadic weight = SignedDyadic.of(particles.volume(p));
			volume = volume.add(weight);
			first[0] = first[0].add(weight.multiply(SignedDyadic.of(particles.positionX(p))));
			first[1] = first[1].add(weight.multiply(SignedDyadic.of(particles.positionY(p))));
			first[2] = first[2].add(weight.multiply(SignedDyadic.of(particles.positionZ(p))));
		}
		if (volume.signum() == 0) return false;
		SignedDyadic metricVolume = volume.multiply(SignedDyadic.of(cellSize));
		double mx = centeredMoment(first[0], metricVolume, x);
		double my = centeredMoment(first[1], metricVolume, y);
		double mz = centeredMoment(first[2], metricVolume, z);
		int count = 8, lowX = 4, lowY = 4, lowZ = 4;
		if (cut.mask(cell) != 0) {
			count = lowX = lowY = lowZ = 0;
			for (int a = 0; a < 2; a++) for (int b = 0; b < 2; b++) for (int c = 0; c < 2; c++) {
				if (!cut.microCellOpen(cell, a, b, c)) continue;
				count++; lowX += 1 - a; lowY += 1 - b; lowZ += 1 - c;
			}
		}
		if (count == 0) return false;
		double nx = momentDirection(mx, lowX, count);
		double ny = momentDirection(my, lowY, count);
		double nz = momentDirection(mz, lowZ, count);
		double scale = Math.max(Math.abs(nx), Math.max(Math.abs(ny), Math.abs(nz)));
		if (scale == 0) return false;
		if (Double.isInfinite(scale)) {
			nx = Double.isInfinite(nx) ? Math.signum(nx) : 0;
			ny = Double.isInfinite(ny) ? Math.signum(ny) : 0;
			nz = Double.isInfinite(nz) ? Math.signum(nz) : 0;
		} else { nx /= scale; ny /= scale; nz /= scale; }
		normalX[cell] = (float) nx; normalY[cell] = (float) ny; normalZ[cell] = (float) nz;
		return true;
	}

	private static double centeredMoment(SignedDyadic first, SignedDyadic metricVolume, int coordinate) {
		SignedDyadic centered = first.subtract(metricVolume.multiply(SignedDyadic.of(coordinate + .5)));
		int shift = centered.exponent() - metricVolume.exponent();
		BigInteger numerator = centered.significand(), denominator = metricVolume.significand();
		return (shift >= 0 ? new ExactRational(numerator.shiftLeft(shift), denominator)
				: new ExactRational(numerator, denominator.shiftLeft(-shift))).toDouble();
	}

	private static double momentDirection(double centre, int lowCount, int openCount) {
		double lower = lowCount == 0 ? 0 : -.5, upper = lowCount == openCount ? 0 : .5;
		double openCentre = .25 * (openCount - 2.0 * lowCount) / openCount;
		if (centre <= lower) return Double.POSITIVE_INFINITY;
		if (centre >= upper) return Double.NEGATIVE_INFINITY;
		return (openCentre - centre) / ((upper - centre) * (centre - lower));
	}

	float liquidFraction(int cell) { return (float) fraction[cell]; }
	float planeNormal(int cell, int axis) { return axis == 0 ? normalX[cell] : axis == 1 ? normalY[cell] : normalZ[cell]; }
	double planeOffset(int cell) { return planeOffset[cell]; }

	/** Area occupied by both PLICs on their shared open face; left is the negative-axis cell. */
	double sharedLiquidFaceArea(int left, int right, int axis, FlipCutCellGeometry cut) {
		if (!(fraction[left] > 0) || !(fraction[right] > 0)) return 0;
		if (cut.mask(left) == 0 && cut.mask(right) == 0) return wetRectangle(left, right, axis, 0, 0, 1);
		double area = 0;
		for (int a = 0; a < 2; a++) for (int b = 0; b < 2; b++) {
			boolean open = switch (axis) {
				case 0 -> cut.microCellOpen(left, 1, a, b) && cut.microCellOpen(right, 0, a, b);
				case 1 -> cut.microCellOpen(left, b, 1, a) && cut.microCellOpen(right, b, 0, a);
				default -> cut.microCellOpen(left, a, b, 1) && cut.microCellOpen(right, a, b, 0);
			};
			if (open) area += wetRectangle(left, right, axis, .5 * a, .5 * b, .5);
		}
		return area;
	}

	private double wetRectangle(int left, int right, int axis, double x, double y, double span) {
		faceX[0] = faceX[3] = x; faceX[1] = faceX[2] = x + span;
		faceY[0] = faceY[1] = y; faceY[2] = faceY[3] = y + span;
		int tangent = (axis + 1) % 3, other = (axis + 2) % 3;
		int count = clipFace(4, planeNormal(left, tangent), planeNormal(left, other),
				planeOffset[left] - planeNormal(left, axis));
		count = clipFace(count, planeNormal(right, tangent), planeNormal(right, other), planeOffset[right]);
		double twiceArea = 0;
		for (int i = 1; i + 1 < count; i++) twiceArea += (faceX[i] - faceX[0]) * (faceY[i + 1] - faceY[0])
				- (faceY[i] - faceY[0]) * (faceX[i + 1] - faceX[0]);
		return .5 * Math.abs(twiceArea);
	}

	private int clipFace(int count, double nx, double ny, double offset) {
		if (count == 0) return 0;
		int next = 0, previous = count - 1;
		double previousDistance = nx * faceX[previous] + ny * faceY[previous] - offset;
		for (int current = 0; current < count; current++) {
			double distance = nx * faceX[current] + ny * faceY[current] - offset;
			if ((distance <= 0) != (previousDistance <= 0)) {
				double t = previousDistance / (previousDistance - distance);
				clippedX[next] = faceX[previous] + t * (faceX[current] - faceX[previous]);
				clippedY[next++] = faceY[previous] + t * (faceY[current] - faceY[previous]);
			}
			if (distance <= 0) { clippedX[next] = faceX[current]; clippedY[next++] = faceY[current]; }
			previous = current; previousDistance = distance;
		}
		System.arraycopy(clippedX, 0, faceX, 0, next); System.arraycopy(clippedY, 0, faceY, 0, next);
		return next;
	}

	/** Copied local face polygon; tangential coordinates use cyclic axis order. */
	double[][] wetFacePolygon(int cell, int axis, double coordinate, double tangent, double other, double span) {
		if (!(fraction[cell] > 0)) return new double[2][0];
		rectangle(tangent, other, span);
		int count = fraction[cell] >= 1 ? 4 : clipFace(4, planeNormal(cell,(axis+1)%3), planeNormal(cell,(axis+2)%3),
				planeOffset[cell] - coordinate * planeNormal(cell,axis));
		return polygon(count);
	}

	/** Both positive liquid profiles on one shared open microface. */
	double[][] sharedWetPolygon(int left, int right, int axis, double tangent, double other, double span) {
		if (!(fraction[left] > 0) || !(fraction[right] > 0)) return new double[2][0];
		rectangle(tangent, other, span);
		int count = fraction[left] >= 1 ? 4 : clipFace(4, planeNormal(left,(axis+1)%3),planeNormal(left,(axis+2)%3),planeOffset[left]-planeNormal(left,axis));
		if(fraction[right]<1)count=clipFace(count,planeNormal(right,(axis+1)%3),planeNormal(right,(axis+2)%3),planeOffset[right]);
		return polygon(count);
	}

	/** Owner liquid intersected with the peer's dry half-space, retaining actual gas-facing geometry. */
	double[][] exposedWetPolygon(int owner, int peer, int axis, int sign, double tangent, double other, double span) {
		if (!(fraction[owner] > 0) || peer>=0 && fraction[peer]>=1) return new double[2][0];
		rectangle(tangent,other,span);
		int count=fraction[owner]>=1?4:clipFace(4,planeNormal(owner,(axis+1)%3),planeNormal(owner,(axis+2)%3),
				planeOffset[owner]-(sign>0?1:0)*planeNormal(owner,axis));
		if(peer>=0 && fraction[peer]>0)count=clipFace(count,-planeNormal(peer,(axis+1)%3),-planeNormal(peer,(axis+2)%3),
				-planeOffset[peer]+(sign>0?0:1)*planeNormal(peer,axis));
		return polygon(count);
	}

	double liquidFractionExact(int cell) { return fraction[cell]; }
	private void rectangle(double x,double y,double span) {
		faceX[0]=faceX[3]=x;faceX[1]=faceX[2]=x+span;
		faceY[0]=faceY[1]=y;faceY[2]=faceY[3]=y+span;
	}
	private double[][] polygon(int count) {
		return new double[][]{java.util.Arrays.copyOf(faceX,count),java.util.Arrays.copyOf(faceY,count)};
	}

	float interfaceTheta(int fluid, int directionX, int directionY, int directionZ) {
		double distance;
		if (fraction[fluid] >= 1) distance = 0.5;
		else {
			float outward = normalX[fluid] * directionX + normalY[fluid] * directionY
					+ normalZ[fluid] * directionZ;
			if (!(outward > 0.0F)) return 1.0F;
			double centerProjection = 0.5 * ((double) normalX[fluid] + normalY[fluid] + normalZ[fluid]);
			distance = (planeOffset[fluid] - centerProjection) / outward;
		}
		int x = fluid % sizeX;
		int yz = fluid / sizeX;
		int y = yz % sizeY;
		int z = yz / sizeY;
		int nx = x + directionX, ny = y + directionY, nz = z + directionZ;
		if (inside(nx, ny, nz)) distance += fraction[cellIndex(nx, ny, nz)];
		if (!(distance > 0.0F)) {
			throw new IllegalStateException("Pressure-cell centre does not lie inside its reconstructed liquid phase");
		}
		return (float) Math.min(distance, 1);
	}

	private double sampleFraction(int x, int y, int z, double fallback, boolean[] solid) {
		if (!inside(x, y, z)) return fallback;
		int cell = cellIndex(x, y, z);
		return solid[cell] ? fallback : fraction[cell];
	}

	private int airDirection(int x, int y, int z, boolean[] solid) {
		if (!inside(x, y, z)) return 0;
		int cell = cellIndex(x, y, z);
		return !solid[cell] && fraction[cell] <= 0.5F ? 1 : 0;
	}

	private boolean inside(int x, int y, int z) {
		return x >= 0 && x < sizeX && y >= 0 && y < sizeY && z >= 0 && z < sizeZ;
	}

	private int cellIndex(int x, int y, int z) {
		return (z * sizeY + y) * sizeX + x;
	}

	private static double clamp(double value, double minimum, double maximum) {
		return Math.max(minimum, Math.min(maximum, value));
	}
}

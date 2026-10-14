package mmm.fluid;

import mmm.chemistry.ExactRational;
import mmm.units.SignedDyadic;

/** Immutable point samples of the same actual-mass cell field used by wet-face transfer. */
final class CellAffineVelocityField {
	private final CellAffineVelocity basis;
	private final double[][] coefficients;
	record FaceTrace(int velocityBlock, int support, double[] q) { }

	CellAffineVelocityField(CellAffineVelocity basis, double[][] coefficients) {
		if (basis == null || coefficients == null || coefficients.length != 3)
			throw new IllegalArgumentException("Missing cell velocity field");
		this.basis = basis;
		this.coefficients = new double[3][];
		for (int axis = 0; axis < 3; axis++) {
			if (coefficients[axis] == null || coefficients[axis].length != 4 * basis.blockCount())
				throw new IllegalArgumentException("Cell velocity field shape mismatch");
			this.coefficients[axis] = coefficients[axis].clone();
			for (double value : this.coefficients[axis]) if (!Double.isFinite(value))
				throw new IllegalArgumentException("Nonfinite cell velocity coefficient");
		}
	}

	boolean hasCell(int cell) { return cell >= 0 && cell < basis.physicalCellCount() && basis.blockCountForCell(cell) > 0; }
	int blockCount() { return basis.blockCount(); }
	/** A uniform complete-component field, or null when spatial or wall modes contribute. */
	double[] constantVelocity(int cell) {
		if (!hasCell(cell) || basis.blockCountForCell(cell) != 1) return null;
		int block = basis.blockForCell(cell, 0);
		if (basis.blockSupport(block) != 255) return null;
		var result = new double[3];
		for (int axis = 0; axis < 3; axis++) {
			int start = 4 * block;
			for (int i = 1; i < 4; i++) if (coefficients[axis][start + i] != 0) return null;
			var shape = basis.normalShape(block, axis);
			if (coefficients[axis][start] != 0 && (shape[1] != 0 || shape[2] != 0)) return null;
			result[axis] = shape[0] * coefficients[axis][start];
		}
		return result;
	}
	/** Volume mean in m/s on captured wet support; t=(x-cellCentre)/h and all integrals are cell-normalized. */
	double[] meanWetVelocity(LiquidPressureGeometry geometry, int cell, int support) {
		if (geometry == null || !hasCell(cell)) throw new IllegalArgumentException("Wet velocity mean requires a captured cell field");
		var volume = geometry.supportAffineSecondMoments(cell, support,
				new ExactRational[]{ExactRational.ONE, ExactRational.ZERO, ExactRational.ZERO, ExactRational.ZERO})[0];
		if (volume.signum() <= 0) throw new IllegalArgumentException("Wet velocity mean requires positive captured volume");
		var integral = new ExactRational[]{ExactRational.ZERO, ExactRational.ZERO, ExactRational.ZERO}; int covered = 0;
		for (int i = 0; i < basis.blockCountForCell(cell); i++) {
			int block = basis.blockForCell(cell, i), selected = support & basis.blockSupport(block);
			if (selected == 0) continue;
			if (!basis.hasRepresentableBasis(block)) throw new FluidDomain.PressureSolveBlockedException("Wet velocity block has no representable source field");
			covered |= selected;
			for (int axis = 0; axis < 3; axis++) {
				var affine = new ExactRational[4]; affine[0] = exact(coefficients[axis][4 * block]);
				for (int a = 0; a < 3; a++) {
					affine[a + 1] = exact(coefficients[axis][4 * block + a + 1]);
					affine[0] = affine[0].add(affine[a + 1].multiply(basis.normalizedCenterOffsetExact(block, a)));
				}
				var moments = geometry.supportAffineSecondMoments(cell, selected, affine);
				var shape = basis.normalShape(block, axis);
				int squared = axis == 0 ? 4 : axis == 1 ? 7 : 9;
				integral[axis] = integral[axis].add(exact(shape[0]).multiply(moments[0]))
						.add(exact(shape[1]).multiply(moments[axis + 1])).add(exact(shape[2]).multiply(moments[squared]));
			}
		}
		if ((geometry.wetMicroMask(cell) & support & ~covered) != 0)
			throw new FluidDomain.PressureSolveBlockedException("Wet velocity mean has uncovered physical support at cell " + cell);
		var inverse = new ExactRational(volume.denominator(), volume.numerator()); var result = new double[3];
		for (int axis = 0; axis < 3; axis++) {
			result[axis] = integral[axis].multiply(inverse).toDouble();
			if (!Double.isFinite(result[axis])) throw new FluidDomain.PressureSolveBlockedException("Unrepresentable wet velocity mean");
		}
		return result;
	}
	private static ExactRational exact(double value) { return PlicComponentGeometry.ratio(SignedDyadic.of(value), SignedDyadic.of(1)); }
	boolean hasPoint(int cell, double x, double y, double z) { return hasCell(cell) && basis.blockForPoint(cell, x, y, z) >= 0; }
	FaceTrace[] faceTraces(int cell, int axis, boolean positive, int sizeX, int sizeY) {
		if (!hasCell(cell) || axis < 0 || axis > 2) throw new IllegalArgumentException("Missing affine normal traces");
		int faceSupport=switch(axis) { case 0->0xaa; case 1->0xcc; default->0xf0; };
		if(!positive)faceSupport^=255;
		int count=0;
		for(int i=0;i<basis.blockCountForCell(cell);i++)if((basis.blockSupport(basis.blockForCell(cell,i))&faceSupport)!=0)count++;
		var result = new FaceTrace[count]; int next=0;
		for (int i = 0; i < basis.blockCountForCell(cell); i++) {
			int block = basis.blockForCell(cell, i);
			if((basis.blockSupport(block)&faceSupport)==0)continue;
			double[] q = new double[3];
			faceTrace(block, axis, positive, sizeX, sizeY, q);
			result[next++] = new FaceTrace(block, basis.blockSupport(block), q);
		}
		return result;
	}
	/** Normal trace in this donor's [0,1] coordinates: intercept, first tangent slope, second tangent slope. */
	private void faceTrace(int block, int axis, boolean positive, int sizeX, int sizeY, double[] result) {
		if (result == null || result.length < 3 || axis < 0 || axis > 2 || basis.blockMarkerCount(block) == 0)
			throw new IllegalArgumentException("Missing affine normal trace");
		int cell = basis.physicalCell(block);
		int[] coordinate = {cell % sizeX, cell / sizeX % sizeY, cell / (sizeX * sizeY)};
		double intercept = coefficients[axis][4 * block];
		for (int a = 0; a < 3; a++) {
			double local = coordinate[a] + (a == axis && positive ? 1 : 0) - basis.center(block, a) / basis.cellSize();
			intercept = Math.fma(coefficients[axis][4 * block + a + 1], local, intercept);
		}
		double shape = basis.shapeValue(block, axis, (coordinate[axis] + (positive ? 1 : 0)) * basis.cellSize());
		result[0] = shape * intercept;
		result[1] = shape * coefficients[axis][4 * block + (axis + 1) % 3 + 1];
		result[2] = shape * coefficients[axis][4 * block + (axis + 2) % 3 + 1];
	}
	double value(int axis, int cell, double x, double y, double z) {
		int block = basis.blockForPoint(cell, x, y, z);
		if (block < 0) throw new FluidDomain.PressureSolveBlockedException("Liquid point has no actual component velocity support at cell " + cell);
		return basis.value(coefficients, axis, block, x, y, z);
	}
	FluidDomain.VelocitySample sample(int cell, double x, double y, double z) {
		double vx = value(0, cell, x, y, z), vy = value(1, cell, x, y, z), vz = value(2, cell, x, y, z);
		if (!Float.isFinite((float) vx) || !Float.isFinite((float) vy) || !Float.isFinite((float) vz))
			throw new IllegalStateException("Unrepresentable resolved liquid velocity sample");
		return new FluidDomain.VelocitySample((float) vx, (float) vy, (float) vz);
	}
}

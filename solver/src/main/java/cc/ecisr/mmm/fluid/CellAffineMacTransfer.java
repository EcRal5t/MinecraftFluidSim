package cc.ecisr.mmm.fluid;

import java.util.Arrays;

/** Fixed wet-face means/moments of the cell affine field, with the same load adjoint. */
final class CellAffineMacTransfer {
	private final int cellCount;
	private final int[][] cells;
	private final double[][] area, mean, moments;
	private final LiquidPressureGeometry.SurfaceMoments[][] surfaces;
	static CellAffineMacTransfer capture(FluidDomain domain, CellAffineVelocity basis, LiquidPressureGeometry geometry) {
		return new CellAffineMacTransfer(domain, basis, geometry);
	}
	private CellAffineMacTransfer(FluidDomain domain, CellAffineVelocity basis, LiquidPressureGeometry geometry) {
		cellCount = basis.cellCount(); cells = new int[3][]; area = new double[3][]; mean = new double[3][]; moments = new double[3][];
		surfaces = new LiquidPressureGeometry.SurfaceMoments[3][];
		for (int axis = 0; axis < 3; axis++) {
			int count = axis == 0 ? domain.mac.velocityX.length : axis == 1 ? domain.mac.velocityY.length : domain.mac.velocityZ.length;
			cells[axis] = new int[2 * count]; Arrays.fill(cells[axis], -1);
			area[axis] = new double[count]; mean[axis] = new double[8 * count]; moments[axis] = new double[24 * count];
			surfaces[axis] = new LiquidPressureGeometry.SurfaceMoments[count]; Arrays.fill(surfaces[axis], LiquidPressureGeometry.SurfaceMoments.EMPTY);
		}
		for (var face : geometry.sharedFaces()) {
			int f = faceIndex(domain, face.plusCell(), face.axis());
			requireSource(basis, geometry, face.minusCell()); requireSource(basis, geometry, face.plusCell());
			surfaces[face.axis()][f] = surfaces[face.axis()][f].add(face.moments());
		}
		for (int cell = 0; cell < cellCount; cell++) {
			var snapshot = geometry.cellSnapshot(cell);
			if (basis.cellMarkerCount(cell) > 0 && snapshot == null) throw blocked("Liquid markers lack a positive spatial profile at cell " + cell);
			if (snapshot == null) continue;
			requireSource(basis, geometry, cell);
			for (var facet : snapshot.facets()) if (facet.kind() == LiquidPressureGeometry.FacetKind.GAS) {
				int f = sideFaceIndex(domain, facet.cell(), facet.axis(), facet.sign());
				surfaces[facet.axis()][f] = surfaces[facet.axis()][f].add(facet.moments());
			}
		}
		for (int a = 0; a < 3; a++) for (int f = 0; f < faceCount(a); f++) area[a][f] = surfaces[a][f].area();
		for (var face : geometry.sharedFaces()) {
			int f = faceIndex(domain, face.plusCell(), face.axis());
			add(basis, face.axis(), f, face.minusCell(), face.moments(), .5);
			add(basis, face.axis(), f, face.plusCell(), face.moments(), .5);
		}
		for (int cell = 0; cell < cellCount; cell++) if (geometry.cellSnapshot(cell) != null) {
			for (var facet : geometry.cellSnapshot(cell).facets()) if (facet.kind() == LiquidPressureGeometry.FacetKind.GAS)
				add(basis, facet.axis(), sideFaceIndex(domain, cell, facet.axis(), facet.sign()), cell, facet.moments(), 1);
		}
	}

	private void add(CellAffineVelocity basis, int axis, int face, int cell, LiquidPressureGeometry.SurfaceMoments part, double scale) {
		int slot = cells[axis][2 * face] == cell || cells[axis][2 * face] < 0 ? 0 : 1;
		if (cells[axis][2 * face + slot] >= 0 && cells[axis][2 * face + slot] != cell) throw new IllegalStateException("Wet face has more than two cell fields");
		cells[axis][2 * face + slot] = cell;
		int base = face * 8 + slot * 4; double h = basis.cellSize(), a = part.area();
		mean[axis][base] += scale * a / area[axis][face];
		moments[axis][face * 24 + slot * 12] += scale * a;
		for (int j = 0; j < 3; j++) {
			double distance = part.centroid().component(j) - basis.center(cell, j);
			mean[axis][base + j + 1] += scale * a * distance / (h * area[axis][face]);
			moments[axis][face * 24 + slot * 12 + j + 1] += scale * a * distance / h;
		}
		for (int tangent = 0; tangent < 2; tangent++) {
			int t = (axis + tangent + 1) % 3, start = face * 24 + slot * 12 + (tangent + 1) * 4;
			double offset = part.centroid().component(t) - surfaces[axis][face].centroid().component(t);
			moments[axis][start] += scale * a * offset / h;
			for (int j = 0; j < 3; j++) moments[axis][start + j + 1] += scale
					* (part.centralSecondMoment().component(t, j) + a * offset * (part.centroid().component(j) - basis.center(cell, j))) / (h * h);
		}
	}
	int faceCount(int axis) { return area[axis].length; }
	double wetArea(int axis, int face) { return area[axis][face]; }
	LiquidPressureGeometry.SurfaceMoments surface(int axis, int face) { return surfaces[axis][face]; }
	void apply(double[][] cellCoefficients, double[][] faceVelocity) {
		checkShape(cellCoefficients, 4 * cellCount); checkFaces(faceVelocity, 1); checkAlias(cellCoefficients, faceVelocity);
		for (int axis = 0; axis < 3; axis++) {
			Arrays.fill(faceVelocity[axis], 0);
			for (int f = 0; f < faceCount(axis); f++) for (int slot = 0; slot < 2; slot++) {
				int cell = cells[axis][2 * f + slot]; if (cell < 0) continue;
				for (int term = 0; term < 4; term++) faceVelocity[axis][f] = Math.fma(mean[axis][8 * f + 4 * slot + term], cellCoefficients[axis][4 * cell + term], faceVelocity[axis][f]);
			}
		}
	}
	void transpose(double[][] faceLoad, double[][] cellLoad) {
		checkFaces(faceLoad, 1); checkShape(cellLoad, 4 * cellCount); checkAlias(faceLoad, cellLoad);
		for (double[] axis : cellLoad) Arrays.fill(axis, 0);
		for (int axis = 0; axis < 3; axis++) for (int f = 0; f < faceCount(axis); f++) for (int slot = 0; slot < 2; slot++) {
			int cell = cells[axis][2 * f + slot]; if (cell < 0) continue;
			for (int term = 0; term < 4; term++) cellLoad[axis][4 * cell + term] = Math.fma(mean[axis][8 * f + 4 * slot + term], faceLoad[axis][f], cellLoad[axis][4 * cell + term]);
		}
	}
	/** Integral normal velocity tested by 1 and the two aggregate-face-centred coordinates divided by h. */
	void applyMoments(double[][] cellCoefficients, double[][] faceMoments) {
		checkShape(cellCoefficients, 4 * cellCount); checkFaces(faceMoments, 3); checkAlias(cellCoefficients, faceMoments);
		for (int axis = 0; axis < 3; axis++) {
			Arrays.fill(faceMoments[axis], 0);
			for (int f = 0; f < faceCount(axis); f++) for (int slot = 0; slot < 2; slot++) {
				int cell = cells[axis][2 * f + slot]; if (cell < 0) continue;
				for (int test = 0; test < 3; test++) for (int term = 0; term < 4; term++) faceMoments[axis][3 * f + test] = Math.fma(moments[axis][24 * f + 12 * slot + 4 * test + term], cellCoefficients[axis][4 * cell + term], faceMoments[axis][3 * f + test]);
			}
		}
	}
	private static void requireSource(CellAffineVelocity basis, LiquidPressureGeometry geometry, int cell) {
		if (geometry.cellSnapshot(cell) == null || basis.cellMarkerCount(cell) == 0 || !basis.hasRepresentableBasis(cell))
			throw blocked("Wet velocity trace lacks actual marker inertia at cell " + cell);
	}
	private static int faceIndex(FluidDomain d, int plus, int axis) {
		int x = plus % d.sizeX, y = plus / d.sizeX % d.sizeY, z = plus / (d.sizeX * d.sizeY);
		return axis == 0 ? d.xFaceIndex(x, y, z) : axis == 1 ? d.yFaceIndex(x, y, z) : d.zFaceIndex(x, y, z);
	}
	private static int sideFaceIndex(FluidDomain d, int cell, int axis, int sign) {
		int x = cell % d.sizeX, y = cell / d.sizeX % d.sizeY, z = cell / (d.sizeX * d.sizeY);
		return axis == 0 ? d.xFaceIndex(x + (sign > 0 ? 1 : 0), y, z) : axis == 1 ? d.yFaceIndex(x, y + (sign > 0 ? 1 : 0), z) : d.zFaceIndex(x, y, z + (sign > 0 ? 1 : 0));
	}
	private void checkFaces(double[][] vectors, int stride) { if (vectors.length != 3) throw new IllegalArgumentException("Expected three wet-face axes"); for (int a = 0; a < 3; a++) if (vectors[a].length != stride * faceCount(a)) throw new IllegalArgumentException("Wet-face shape mismatch"); checkDistinct(vectors); }
	private static void checkShape(double[][] vectors, int count) { if (vectors.length != 3) throw new IllegalArgumentException("Expected three cell axes"); for (double[] axis : vectors) if (axis.length != count) throw new IllegalArgumentException("Cell coefficient shape mismatch"); checkDistinct(vectors); }
	private static void checkDistinct(double[][] vectors) { if (vectors[0] == vectors[1] || vectors[0] == vectors[2] || vectors[1] == vectors[2]) throw new IllegalArgumentException("Aliased wet-field axes"); }
	private static void checkAlias(double[][] first, double[][] second) { for (double[] a : first) for (double[] b : second) if (a == b) throw new IllegalArgumentException("Aliased wet-field vectors"); }
	private static FluidDomain.PressureSolveBlockedException blocked(String message) { return new FluidDomain.PressureSolveBlockedException(message); }
}

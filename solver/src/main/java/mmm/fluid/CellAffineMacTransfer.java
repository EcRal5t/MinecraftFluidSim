package mmm.fluid;

import java.util.Arrays;
import java.util.ArrayList;

/** Fixed wet-face means/moments of the cell affine field, with the same load adjoint. */
final class CellAffineMacTransfer {
	private final int blockCount;
	private final int[][] starts, blocks;
	private final double[][] area, mean, moments;
	private final LiquidPressureGeometry.SurfaceMoments[][] surfaces;
	private static final class Entry {
		final int block;
		final double[] mean = new double[4], moments = new double[12];
		Entry(int block) { this.block = block; }
	}
	static CellAffineMacTransfer capture(FluidDomain domain, CellAffineVelocity basis, LiquidPressureGeometry geometry) {
		return new CellAffineMacTransfer(domain, basis, geometry);
	}
	@SuppressWarnings("unchecked")
	private CellAffineMacTransfer(FluidDomain domain, CellAffineVelocity basis, LiquidPressureGeometry geometry) {
		blockCount = basis.blockCount(); starts = new int[3][]; blocks = new int[3][];
		area = new double[3][]; mean = new double[3][]; moments = new double[3][];
		var pending = (ArrayList<Entry>[][]) new ArrayList<?>[3][];
		surfaces = new LiquidPressureGeometry.SurfaceMoments[3][];
		for (int axis = 0; axis < 3; axis++) {
			int count = axis == 0 ? domain.mac.velocityX.length : axis == 1 ? domain.mac.velocityY.length : domain.mac.velocityZ.length;
			starts[axis] = new int[count + 1]; pending[axis] = (ArrayList<Entry>[]) new ArrayList<?>[count];
			area[axis] = new double[count];
			surfaces[axis] = new LiquidPressureGeometry.SurfaceMoments[count]; Arrays.fill(surfaces[axis], LiquidPressureGeometry.SurfaceMoments.EMPTY);
		}
		for (var face : geometry.sharedFaces()) {
			int f = faceIndex(domain, face.plusCell(), face.axis());
			surfaces[face.axis()][f] = surfaces[face.axis()][f].add(face.moments());
		}
		for (int cell = 0; cell < domain.cellCount; cell++) {
			var snapshot = geometry.cellSnapshot(cell);
			if (basis.blockMarkerCount(cell) > 0 && snapshot == null) throw blocked("Liquid markers lack a positive spatial profile at cell " + cell);
			if (snapshot == null) continue;
			for (var facet : snapshot.facets()) if (facet.kind() == LiquidPressureGeometry.FacetKind.GAS) {
				int f = sideFaceIndex(domain, facet.cell(), facet.axis(), facet.sign());
				surfaces[facet.axis()][f] = surfaces[facet.axis()][f].add(facet.moments());
			}
		}
		for (int a = 0; a < 3; a++) for (int f = 0; f < faceCount(a); f++) area[a][f] = surfaces[a][f].area();
		for (var face : geometry.sharedFaces()) {
			int f = faceIndex(domain, face.plusCell(), face.axis());
			int minus = basis.blockForMicro(face.minusCell(), face.minusMicro());
			int plus = basis.blockForMicro(face.plusCell(), face.plusMicro());
			requireSource(basis, geometry, minus); requireSource(basis, geometry, plus);
			add(pending, basis, face.axis(), f, minus, face.moments(), .5);
			add(pending, basis, face.axis(), f, plus, face.moments(), .5);
		}
		for (int cell = 0; cell < domain.cellCount; cell++) if (geometry.cellSnapshot(cell) != null) {
			for (var facet : geometry.cellSnapshot(cell).facets()) if (facet.kind() == LiquidPressureGeometry.FacetKind.GAS) {
				int block = basis.blockForMicro(cell, facet.microKey() / 6); requireSource(basis, geometry, block);
				add(pending, basis, facet.axis(), sideFaceIndex(domain, cell, facet.axis(), facet.sign()), block, facet.moments(), 1);
			}
		}
		for (int axis = 0; axis < 3; axis++) {
			for (int face = 0; face < faceCount(axis); face++) starts[axis][face + 1] = starts[axis][face]
					+ (pending[axis][face] == null ? 0 : pending[axis][face].size());
			int count = starts[axis][faceCount(axis)];
			blocks[axis] = new int[count]; mean[axis] = new double[4 * count]; moments[axis] = new double[12 * count];
			for (int face = 0; face < faceCount(axis); face++) if (pending[axis][face] != null) {
				int index = starts[axis][face];
				for (var entry : pending[axis][face]) {
					blocks[axis][index] = entry.block;
					System.arraycopy(entry.mean, 0, mean[axis], 4 * index, 4);
					System.arraycopy(entry.moments, 0, moments[axis], 12 * index, 12); index++;
				}
			}
		}
	}

	private void add(ArrayList<Entry>[][] pending, CellAffineVelocity basis, int axis, int face, int block,
	                 LiquidPressureGeometry.SurfaceMoments part, double scale) {
		scale *= basis.shapeValue(block, axis, part.centroid().component(axis));
		if (pending[axis][face] == null) pending[axis][face] = new ArrayList<>();
		Entry selected = null;
		for (var entry : pending[axis][face]) if (entry.block == block) { selected = entry; break; }
		if (selected == null) { selected = new Entry(block); pending[axis][face].add(selected); }
		double h = basis.cellSize(), a = part.area();
		selected.mean[0] += scale * a / area[axis][face];
		selected.moments[0] += scale * a;
		for (int j = 0; j < 3; j++) {
			double distance = part.centroid().component(j) - basis.center(block, j);
			selected.mean[j + 1] += scale * a * distance / (h * area[axis][face]);
			selected.moments[j + 1] += scale * a * distance / h;
		}
		for (int tangent = 0; tangent < 2; tangent++) {
			int t = (axis + tangent + 1) % 3, start = (tangent + 1) * 4;
			double offset = part.centroid().component(t) - surfaces[axis][face].centroid().component(t);
			selected.moments[start] += scale * a * offset / h;
			for (int j = 0; j < 3; j++) selected.moments[start + j + 1] += scale
					* (part.centralSecondMoment().component(t, j) + a * offset * (part.centroid().component(j) - basis.center(block, j))) / (h * h);
		}
	}
	int faceCount(int axis) { return area[axis].length; }
	double wetArea(int axis, int face) { return area[axis][face]; }
	LiquidPressureGeometry.SurfaceMoments surface(int axis, int face) { return surfaces[axis][face]; }
	void apply(double[][] cellCoefficients, double[][] faceVelocity) {
		checkShape(cellCoefficients, 4 * blockCount); checkFaces(faceVelocity, 1); checkAlias(cellCoefficients, faceVelocity);
		for (int axis = 0; axis < 3; axis++) {
			Arrays.fill(faceVelocity[axis], 0);
			for (int f = 0; f < faceCount(axis); f++) for (int entry = starts[axis][f]; entry < starts[axis][f + 1]; entry++) {
				int block = blocks[axis][entry];
				for (int term = 0; term < 4; term++) faceVelocity[axis][f] = Math.fma(mean[axis][4 * entry + term], cellCoefficients[axis][4 * block + term], faceVelocity[axis][f]);
			}
		}
	}
	void transpose(double[][] faceLoad, double[][] cellLoad) {
		checkFaces(faceLoad, 1); checkShape(cellLoad, 4 * blockCount); checkAlias(faceLoad, cellLoad);
		for (double[] axis : cellLoad) Arrays.fill(axis, 0);
		for (int axis = 0; axis < 3; axis++) for (int f = 0; f < faceCount(axis); f++) for (int entry = starts[axis][f]; entry < starts[axis][f + 1]; entry++) {
			int block = blocks[axis][entry];
			for (int term = 0; term < 4; term++) cellLoad[axis][4 * block + term] = Math.fma(mean[axis][4 * entry + term], faceLoad[axis][f], cellLoad[axis][4 * block + term]);
		}
	}
	/** Integral normal velocity tested by 1 and the two aggregate-face-centred coordinates divided by h. */
	void applyMoments(double[][] cellCoefficients, double[][] faceMoments) {
		checkShape(cellCoefficients, 4 * blockCount); checkFaces(faceMoments, 3); checkAlias(cellCoefficients, faceMoments);
		for (int axis = 0; axis < 3; axis++) {
			Arrays.fill(faceMoments[axis], 0);
			for (int f = 0; f < faceCount(axis); f++) for (int entry = starts[axis][f]; entry < starts[axis][f + 1]; entry++) {
				int block = blocks[axis][entry];
				for (int test = 0; test < 3; test++) for (int term = 0; term < 4; term++) faceMoments[axis][3 * f + test] = Math.fma(moments[axis][12 * entry + 4 * test + term], cellCoefficients[axis][4 * block + term], faceMoments[axis][3 * f + test]);
			}
		}
	}
	private static void requireSource(CellAffineVelocity basis, LiquidPressureGeometry geometry, int block) {
		if (block < 0 || geometry.componentSnapshot(basis.physicalCell(block), basis.blockSupport(block)) == null
				|| basis.blockMarkerCount(block) == 0 || !basis.hasRepresentableBasis(block))
			throw blocked("Wet velocity trace lacks actual marker inertia at block " + block);
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

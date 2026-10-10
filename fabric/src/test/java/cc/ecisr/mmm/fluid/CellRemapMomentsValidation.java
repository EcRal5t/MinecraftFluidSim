package cc.ecisr.mmm.fluid;

import java.util.ArrayList;

/** Binary32 publication and support checks for the bounded common-translation rule. */
public final class CellRemapMomentsValidation {
	private CellRemapMomentsValidation() { }

	public static void main(String[] args) {
		wholeCellUpperEndpointUsesActualClassifier();
		unchangedTensorReportsAllStorageAxes();
		cutMicroBoxUpperEndpointUsesActualClassifier();
		System.out.println("Cell remap moment publication validation passed");
	}

	private static FluidDomain domain() {
		return new FluidDomain(6, 6, 6,
				new FluidSolverConfig(0.1F, 1000, 0, 0.05F, 200, 1e-5F, 0.45F, 0.002F, 0.8F), 64);
	}

	private static void wholeCellUpperEndpointUsesActualClassifier() {
		var domain = domain(); int cell = domain.cellIndex(4, 0, 0);
		double[] first = {-0.25, 0, 0}, second = {0.25, 0, 0};
		var offsets = new ArrayList<double[]>(); offsets.add(first); offsets.add(second);
		double[] masses = {0.5, 0.5}, target = {1, 0, 0}, projection = new double[3], storage = new double[3];
		check(CellRemapMoments.fit(domain, cell, offsets, masses, target, projection, storage), "Projected rule did not translate");
		check(first[0] == -0.25 && second[0] == 0.25, "Translation mutated shared input offsets");
		check(offsets.get(1)[0] - offsets.get(0)[0] == 0.5, "Common translation changed point spacing");
		check(projection[0] < 0, "Unreachable upper target was not reported");
		checkPublished(domain, cell, offsets, masses, target, projection, storage);
	}

	private static void unchangedTensorReportsAllStorageAxes() {
		var domain = domain(); int cell = domain.cellIndex(3, 3, 3);
		var offsets = new ArrayList<double[]>(); double[] masses = new double[8];
		for (int p = 0; p < 8; p++) {
			offsets.add(new double[] {(p & 1) == 0 ? -0.25 : 0.25,
					(p & 2) == 0 ? -0.25 : 0.25, (p & 4) == 0 ? -0.25 : 0.25});
			masses[p] = 0.125;
		}
		var original = new ArrayList<>(offsets);
		double[] target = new double[3], projection = new double[3], storage = new double[3];
		check(!CellRemapMoments.fit(domain, cell, offsets, masses, target, projection, storage), "Unchanged tensor lost its fast path");
		for (int p = 0; p < 8; p++) {
			check(offsets.get(p) == original.get(p), "Audit replaced an unchanged tensor point");
			for (int a = 0; a < 3; a++) check(Math.abs(offsets.get(p)[a]) == 0.25, "Audit quantized an unchanged tensor offset");
		}
		for (int a = 0; a < 3; a++) check(storage[a] != 0 && projection[a] == 0, "Unmoved axis concealed publication roundoff");
		checkPublished(domain, cell, offsets, masses, target, projection, storage);
	}

	private static void cutMicroBoxUpperEndpointUsesActualClassifier() {
		var domain = domain(); int cell = domain.cellIndex(2, 0, 0);
		domain.geometry.cutCells.replaceMask(cell, 0xFE);
		var offsets = new ArrayList<double[]>(); offsets.add(new double[] {-0.25, -0.25, -0.25});
		double[] masses = {1}, target = {1, -0.25, -0.25}, projection = new double[3], storage = new double[3];
		CellRemapMoments.fit(domain, cell, offsets, masses, target, projection, storage);
		float x = (float) ((2.5 + offsets.getFirst()[0]) * domain.config.cellSize());
		check(Math.floor(x / (domain.config.cellSize() / 2)) == 4, "Publication entered the solid positive-x micro box");
		checkPublished(domain, cell, offsets, masses, target, projection, storage);
	}

	private static void checkPublished(FluidDomain domain, int cell, ArrayList<double[]> offsets,
			double[] masses, double[] target, double[] projection, double[] storage) {
		int[] coordinate = {cell % domain.sizeX, cell / domain.sizeX % domain.sizeY, cell / (domain.sizeX * domain.sizeY)};
		double h = domain.config.cellSize(); double[] actual = new double[3];
		for (int p = 0; p < masses.length; p++) {
			float[] point = new float[3];
			for (int a = 0; a < 3; a++) {
				point[a] = (float) ((coordinate[a] + 0.5 + offsets.get(p)[a]) * h);
				actual[a] += masses[p] * (point[a] / h - coordinate[a] - 0.5);
			}
			check(domain.particleCellIndex(point[0], point[1], point[2]) == cell, "Published inventory changed cell ownership");
			check(!domain.geometry.cutCells.pointInsideSolid(point[0], point[1], point[2], domain.config.cellSize()), "Published point entered solid support");
		}
		for (int a = 0; a < 3; a++) check(storage[a] == actual[a] - target[a] - projection[a], "Storage audit differs from published first moment");
	}

	private static void check(boolean valid, String message) { if (!valid) throw new AssertionError(message); }
}

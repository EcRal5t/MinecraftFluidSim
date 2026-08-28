package cc.ecisr.mmm.fluid;

import java.util.List;

/** Standalone M5 cavity-scanner gate: ambient/sealed classification, exact available volume, partial-face geometric edges and the scan budget. */
public final class AirCavityScannerValidation {
	private AirCavityScannerValidation() { }

	public static void main(String[] args) {
		validateOpenTankIsAmbient();
		validateControllerExtentIsSealed();
		validateSealedTankVolume();
		validateOpenUTubeIsAmbient();
		validateMultipleSealedCavities();
		validatePartialFaceGeometricEdge();
		validateInterfaceResidualAttribution();
		validateScanBudget();
		System.out.println("M5 cavity scanner gate passed: ambient/sealed classification, available volume, "
				+ "geometric edges and budget stop");
	}

	private static void validateControllerExtentIsSealed() {
		int n = 8;
		Grid grid = new Grid(n);
		AirCavityScanner.Result result = new AirCavityScanner(n, n, n, 0.5F, n * n * n)
				.scan(grid.solid, grid.markerCount, grid.liquidVolume, grid.cutCells, null, true);
		check(result.sealedNodeCount() == 1,
				"a validated controller interior must keep boundary-layer headspace sealed");
		checkRelative(result.sealedAvailableVolume()[0], 64.0,
				"controller interior gas volume");
	}

	private static void validateOpenTankIsAmbient() {
		int n = 8;
		Grid grid = tank(n, false, 1.0F);
		AirCavityScanner.Result result = scan(grid, n * n * n);
		check(result.sealedNodeCount() == 0, "open tank must have no sealed cavities");
		check(result.stopReason() == AirCavityScanner.StopReason.COMPLETED, "open tank scan must complete");
		for (int z = 1; z < n - 1; z++) for (int y = 4; y < n; y++) for (int x = 1; x < n - 1; x++) {
			check(result.cellNodeId()[(z * n + y) * n + x] == 0,
					"open tank air cell must be ambient at " + x + "," + y + "," + z);
		}
		for (int z = 1; z < n - 1; z++) for (int y = 1; y <= 3; y++) for (int x = 1; x < n - 1; x++) {
			check(result.cellNodeId()[(z * n + y) * n + x] == -1,
					"water cell must not join a gas node at " + x + "," + y + "," + z);
		}
	}

	private static void validateSealedTankVolume() {
		int n = 8;
		Grid grid = tank(n, true, 1.0F);
		AirCavityScanner.Result result = scan(grid, n * n * n);
		check(result.sealedNodeCount() == 1, "sealed tank must expose exactly one cavity");
		checkRelative(result.sealedAvailableVolume()[0], 6 * 6 * 3, "sealed tank cavity volume");

		Grid halfMetre = tank(n, true, 0.5F);
		AirCavityScanner.Result scaled = new AirCavityScanner(n, n, n, 0.5F, n * n * n)
				.scan(halfMetre.solid, halfMetre.markerCount, halfMetre.liquidVolume, halfMetre.cutCells);
		checkRelative(scaled.sealedAvailableVolume()[0], 6 * 6 * 3 * 0.125, "half-metre cavity volume");
	}

	private static void validateOpenUTubeIsAmbient() {
		int n = 8;
		Grid grid = new Grid(n);
		for (int z = 0; z < n; z++) for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
			int i = (z * n + y) * n + x;
			boolean shell = x == 0 || x == n - 1 || z == 0 || z == n - 1 || y == 0;
			boolean divider = (x == 3 || x == 4) && y <= 5;
			if (shell || divider) grid.solid[i] = true;
			else if (y <= 1) { grid.markerCount[i] = 8; grid.liquidVolume[i] = 1.0F; }
		}
		AirCavityScanner.Result result = scan(grid, n * n * n);
		check(result.sealedNodeCount() == 0, "open U-tube air must classify as ambient");
		for (int z = 1; z < n - 1; z++) for (int y = 2; y < n - 1; y++) for (int x = 1; x < n - 1; x++) {
			if (x == 3 || x == 4) continue;
			check(result.cellNodeId()[(z * n + y) * n + x] == 0,
					"U-tube air cell must be ambient at " + x + "," + y + "," + z);
		}
	}

	private static void validateMultipleSealedCavities() {
		int sizeX = 16, sizeY = 8, sizeZ = 8;
		Grid grid = new Grid(sizeX, sizeY, sizeZ, 1.0F);
		for (int z = 0; z < sizeZ; z++) for (int y = 0; y < sizeY; y++) for (int x = 0; x < sizeX; x++) {
			int i = (z * sizeY + y) * sizeX + x;
			boolean outerShell = x == 0 || x == sizeX - 1 || z == 0 || z == sizeZ - 1 || y == 0 || y == sizeY - 1;
			boolean divider = x == 8;
			if (outerShell || divider) grid.solid[i] = true;
		}
		AirCavityScanner.Result result = new AirCavityScanner(sizeX, sizeY, sizeZ, 1.0F, sizeX * sizeY * sizeZ)
				.scan(grid.solid, grid.markerCount, grid.liquidVolume, grid.cutCells);
		check(result.sealedNodeCount() == 2, "two isolated boxes must produce two sealed nodes, got " + result.sealedNodeCount());
		checkRelative(result.sealedAvailableVolume()[0], 7 * 6 * 6, "first sealed cavity volume");
		checkRelative(result.sealedAvailableVolume()[1], 6 * 6 * 6, "second sealed cavity volume");
		check(result.cellNodeId()[(1 * sizeY + 1) * sizeX + 1] == 1, "first cavity id must go to the lower cell index");
		check(result.cellNodeId()[(1 * sizeY + 1) * sizeX + 9] == 2, "second cavity id must follow discovery order");
	}

	private static void validatePartialFaceGeometricEdge() {
		int n = 8;
		Grid grid = new Grid(n);
		for (int z = 0; z < n; z++) for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
			int i = (z * n + y) * n + x;
			boolean shell = x == 0 || x == n - 1 || z == 0 || z == n - 1 || y == 0 || y == n - 1;
			boolean divider = x == 4 && y >= 1 && y < n - 1 && z >= 1 && z < n - 1;
			if (shell || divider) grid.solid[i] = true;
		}
		// one wall cell keeps a single open quadrant on its left face: a 1/4-open gap, not a connection
		grid.cutCells.replaceMask((1 * n + 1) * n + 4, 0xFE);
		grid.solid[(1 * n + 1) * n + 4] = false;
		AirCavityScanner.Result result = scan(grid, n * n * n);
		check(result.sealedNodeCount() == 3,
				"left chamber, sliver pocket and right chamber must stay separate, got " + result.sealedNodeCount());
		checkRelative(result.sealedAvailableVolume()[0], 3 * 6 * 6, "left chamber volume");
		checkRelative(result.sealedAvailableVolume()[1], 0.125, "sliver pocket volume");
		checkRelative(result.sealedAvailableVolume()[2], 2 * 6 * 6, "right chamber volume");
		List<AirCavityScanner.GeometricEdge> edges = result.geometricEdges();
		check(edges.size() == 1, "exactly one geometric edge must exist, got " + edges.size());
		check(edges.get(0).firstNode() == 1 && edges.get(0).secondNode() == 2,
				"the gap must connect the left chamber to the sliver pocket");
		checkRelative(edges.get(0).areaSquareMeters(), 0.25, "quarter-open face area");
	}

	private static void validateInterfaceResidualAttribution() {
		int n = 8;
		Grid grid = tank(n, true, 1.0F);
		for (int y = 2; y <= 3; y++) for (int z = 1; z < n - 1; z++) for (int x = 1; x < n - 1; x++) {
			int i = (z * n + y) * n + x;
			grid.markerCount[i] = 1;
			grid.liquidVolume[i] = 0.5F;
		}
		AirCavityScanner scanner = new AirCavityScanner(n, n, n, 1.0F, n * n * n);
		AirCavityScanner.Result result = scanner.scan(
				grid.solid, grid.markerCount, grid.liquidVolume, grid.cutCells);
		check(result.sealedNodeCount() == 1, "partially filled sealed tank must stay one cavity");
		checkRelative(result.sealedAvailableVolume()[0], 6 * 6 * 3 + 2 * 6 * 6 * 0.5,
				"multi-cell interface residual must join the sealed cavity volume");
		for (int y = 2; y <= 3; y++) for (int z = 1; z < n - 1; z++) for (int x = 1; x < n - 1; x++) {
			int cell = (z * n + y) * n + x;
			check(result.cellNodeId()[cell] == -1 && result.cellLineageNodeId()[cell] == 1,
					"connected partial interface gas must retain runtime cavity lineage without entering persisted membership");
		}
		for (int y = 2; y <= 3; y++) for (int z = 1; z < n - 1; z++) for (int x = 1; x < n - 1; x++) {
			grid.liquidVolume[(z * n + y) * n + x] = 0.25F;
		}
		double[] stableVolumes = scanner.measureSealedAvailableVolumes(
				grid.solid, grid.markerCount, grid.liquidVolume, grid.cutCells, null,
				result.cellNodeId(), result.cellLineageNodeId(), result.sealedNodeCount());
		AirCavityScanner.Result rescanned = scanner.scan(
				grid.solid, grid.markerCount, grid.liquidVolume, grid.cutCells);
		check(java.util.Arrays.equals(result.cellNodeId(), rescanned.cellNodeId())
				&& java.util.Arrays.equals(result.cellLineageNodeId(), rescanned.cellLineageNodeId())
				&& Double.doubleToRawLongBits(stableVolumes[0])
				== Double.doubleToRawLongBits(rescanned.sealedAvailableVolume()[0]),
				"stable-lineage available-volume refresh differed from a complete cavity rescan");

		Grid openGrid = tank(n, false, 1.0F);
		for (int z = 1; z < n - 1; z++) for (int x = 1; x < n - 1; x++) {
			int i = (z * n + 3) * n + x;
			openGrid.markerCount[i] = 1;
			openGrid.liquidVolume[i] = 0.5F;
		}
		AirCavityScanner.Result openResult = scan(openGrid, n * n * n);
		check(openResult.sealedNodeCount() == 0, "residual next to ambient must not create sealed inventory");
	}

	private static void validateScanBudget() {
		int n = 8;
		Grid grid = tank(n, true, 1.0F);
		AirCavityScanner.Result result = new AirCavityScanner(n, n, n, 1.0F, 50)
				.scan(grid.solid, grid.markerCount, grid.liquidVolume, grid.cutCells);
		check(result.stopReason() == AirCavityScanner.StopReason.BUDGET_EXHAUSTED, "budget must stop the scan");
		check(result.sealedNodeCount() == 0, "budget exhaustion must classify air as ambient, never sealed");
		check(result.visitedAirCells() <= 50, "visited cells must respect the budget, got " + result.visitedAirCells());
		for (int z = 1; z < n - 1; z++) for (int y = 4; y < n - 1; y++) for (int x = 1; x < n - 1; x++) {
			check(result.cellNodeId()[(z * n + y) * n + x] == 0,
					"unvisited air must degrade to ambient at " + x + "," + y + "," + z);
		}
	}

	private static AirCavityScanner.Result scan(Grid grid, int budget) {
		int n = grid.sizeX;
		return new AirCavityScanner(n, grid.sizeY, grid.sizeZ, grid.cellSize, budget)
				.scan(grid.solid, grid.markerCount, grid.liquidVolume, grid.cutCells);
	}

	private static Grid tank(int n, boolean roof, float cellSize) {
		Grid grid = new Grid(n, n, n, cellSize);
		for (int z = 0; z < n; z++) for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
			int i = (z * n + y) * n + x;
			boolean shell = x == 0 || x == n - 1 || z == 0 || z == n - 1 || y == 0 || (roof && y == n - 1);
			if (shell) grid.solid[i] = true;
			else if (y <= 3) { grid.markerCount[i] = 8; grid.liquidVolume[i] = 1.0F; }
		}
		return grid;
	}

	private static final class Grid {
		final int sizeX;
		final int sizeY;
		final int sizeZ;
		final float cellSize;
		final boolean[] solid;
		final int[] markerCount;
		final double[] liquidVolume;
		final FlipCutCellGeometry cutCells;

		Grid(int sizeX, int sizeY, int sizeZ, float cellSize) {
			this.sizeX = sizeX;
			this.sizeY = sizeY;
			this.sizeZ = sizeZ;
			this.cellSize = cellSize;
			this.solid = new boolean[sizeX * sizeY * sizeZ];
			this.markerCount = new int[sizeX * sizeY * sizeZ];
			this.liquidVolume = new double[sizeX * sizeY * sizeZ];
			this.cutCells = new FlipCutCellGeometry(sizeX, sizeY, sizeZ);
		}

		Grid(int n) { this(n, n, n, 1.0F); }
	}

	private static void checkRelative(double actual, double expected, String what) {
		double error = Math.abs(actual - expected) / expected;
		if (error > 1.0E-9) throw new AssertionError(what + ": actual=" + actual + " expected=" + expected);
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}

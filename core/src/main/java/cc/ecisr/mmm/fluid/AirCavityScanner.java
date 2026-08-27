package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.granular.GranularCouplingFields;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

	/**
	 * Grid topology scan that labels air cells into gas-network nodes.
	 *
	 * <p>An air cell is non-solid with no markers and positive open volume. Six-neighbour
	 * flood fill passes only through fully open faces; a partially open face between two
	 * different components becomes a finite-rate geometric edge instead of a connection.
	 * A component is ambient (node 0, gauge zero) when it touches an explicitly ambient
	 * domain boundary or when the scan budget is exhausted — never a sealed cavity with finite
	 * inventory. Cavity volume is measured continuously from the smoothed liquid-volume
	 * field: every attributed cell contributes its open volume minus the clamped liquid
	 * fraction, so available volume follows the liquid surface without marker-quantisation
	 * jitter. A partially filled surface cell attributes to the adjacent node with the
	 * smallest id (ambient wins).</p>
	 */
	final class AirCavityScanner {
	/** Face area at or above this fraction counts as fully open for connectivity. */
	private static final float FULL_FACE_THRESHOLD = 1.0F - 1.0E-4F;

	public enum StopReason { COMPLETED, BUDGET_EXHAUSTED }

	public record GeometricEdge(int firstNode, int secondNode, double areaSquareMeters) {
		public GeometricEdge {
			if (firstNode == secondNode) throw new IllegalArgumentException("An edge needs two distinct nodes");
			if (!(areaSquareMeters > 0.0)) throw new IllegalArgumentException("Edge area must be positive");
		}
	}

	public record Result(
			int[] cellNodeId,
			int[] cellLineageNodeId,
			double[] cellAvailableVolume,
			int sealedNodeCount,
			double[] sealedAvailableVolume,
			List<GeometricEdge> geometricEdges,
			int visitedAirCells,
			StopReason stopReason
	) { }

	private final int sizeX;
	private final int sizeY;
	private final int sizeZ;
	private final int cellCount;
	private final double cellVolumeCubicMeters;
	private final double faceAreaSquareMeters;
	private final int scanBudgetCells;
	private final int[] floodStack;

	AirCavityScanner(int sizeX, int sizeY, int sizeZ, float cellSize, int scanBudgetCells) {
		if (sizeX < 2 || sizeY < 2 || sizeZ < 2) throw new IllegalArgumentException("All dimensions must be at least two cells");
		if (!(cellSize > 0.0F)) throw new IllegalArgumentException("cellSize must be positive");
		if (scanBudgetCells <= 0) throw new IllegalArgumentException("Scan budget must be positive");
		this.sizeX = sizeX;
		this.sizeY = sizeY;
		this.sizeZ = sizeZ;
		this.cellCount = Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ);
		this.cellVolumeCubicMeters = (double) cellSize * cellSize * cellSize;
		this.faceAreaSquareMeters = (double) cellSize * cellSize;
		this.scanBudgetCells = scanBudgetCells;
		this.floodStack = new int[cellCount];
	}

	Result scan(boolean[] solid, int[] markerCount, float[] cellLiquidVolume, FlipCutCellGeometry cutCells) {
		return scan(solid, markerCount, cellLiquidVolume, cutCells, null, false);
	}

	Result scan(boolean[] solid, int[] markerCount, float[] cellLiquidVolume,
	            FlipCutCellGeometry cutCells, GranularCouplingFields granular) {
		return scan(solid, markerCount, cellLiquidVolume, cutCells, granular, false);
	}

	Result scan(boolean[] solid, int[] markerCount, float[] cellLiquidVolume,
	            FlipCutCellGeometry cutCells, GranularCouplingFields granular,
	            boolean sealedDomainBoundary) {
		return scan(solid, markerCount, cellLiquidVolume, cutCells, granular, sealedDomainBoundary, null);
	}

	Result scan(boolean[] solid, int[] markerCount, float[] cellLiquidVolume,
	            FlipCutCellGeometry cutCells, GranularCouplingFields granular,
	            boolean sealedDomainBoundary, MovingBodyBoundary movingBodies) {
		boolean budgetExhausted = false;
		int visitedAirCells = 0;
		int sealedNodes = 0;
		int[] cellNodeId = new int[cellCount];
		Arrays.fill(cellNodeId, -1);
		for (int seed = 0; seed < cellCount && !budgetExhausted; seed++) {
			if (!isAirCell(seed, solid, markerCount, cellLiquidVolume, cutCells, granular, movingBodies)
					|| cellNodeId[seed] != -1) continue;
			boolean touchesBoundary = false;
			int stackSize = 0;
			floodStack[stackSize++] = seed;
			cellNodeId[seed] = -2;  // in-progress marker for the current component
			int componentSize = 0;
			while (stackSize > 0) {
				int cell = floodStack[--stackSize];
				if (visitedAirCells + componentSize >= scanBudgetCells) {
					budgetExhausted = true;
					break;
				}
				componentSize++;
				int x = cell % sizeX;
				int yz = cell / sizeX;
				int y = yz % sizeY;
				int z = yz / sizeY;
				if (!sealedDomainBoundary && (x == 0 || x + 1 == sizeX || y == 0 || y + 1 == sizeY
						|| z == 0 || z + 1 == sizeZ)) touchesBoundary = true;
				stackSize = floodNeighbors(cellNodeId, solid, markerCount, cellLiquidVolume, cutCells, granular,
						movingBodies, x, y, z, cell, stackSize);
			}
			visitedAirCells += componentSize;
			int nodeId = 0;
			if (!touchesBoundary && !budgetExhausted) nodeId = ++sealedNodes;
			for (int cell = 0; cell < cellCount; cell++) if (cellNodeId[cell] == -2) cellNodeId[cell] = nodeId;
		}
		if (budgetExhausted) for (int cell = 0; cell < cellCount; cell++) {
			if (cellNodeId[cell] < 0 && isAirCell(cell, solid, markerCount, cellLiquidVolume,
					cutCells, granular, movingBodies)) cellNodeId[cell] = 0;
		}
		double[] cellAvailableVolume = new double[cellCount];
		double[] sealedAvailableVolume = new double[sealedNodes];
		for (int cell = 0; cell < cellCount; cell++) {
			int nodeId = cellNodeId[cell];
			cellAvailableVolume[cell] = cellAirVolume(cell, cellLiquidVolume, cutCells, granular, movingBodies);
			if (nodeId >= 1) {
				sealedAvailableVolume[nodeId - 1] += cellAvailableVolume[cell];
			}
		}
		int[] cellLineageNodeId = cellNodeId.clone();
		addInterfaceResiduals(cellNodeId, cellLineageNodeId, solid, markerCount, cellLiquidVolume,
				cutCells, granular, movingBodies, cellAvailableVolume, sealedAvailableVolume);
		List<GeometricEdge> edges = collectGeometricEdges(cellNodeId, cutCells, movingBodies);
		return new Result(cellNodeId, cellLineageNodeId, cellAvailableVolume,
				sealedNodes, sealedAvailableVolume, edges, visitedAirCells,
				budgetExhausted ? StopReason.BUDGET_EXHAUSTED : StopReason.COMPLETED);
	}

	private boolean isAirCell(int cell, boolean[] solid, int[] markerCount, float[] cellLiquidVolume,
	                          FlipCutCellGeometry cutCells,
	                          GranularCouplingFields granular, MovingBodyBoundary movingBodies) {
		return !solid[cell] && markerCount[cell] == 0
				&& cellAirVolume(cell, cellLiquidVolume, cutCells, granular, movingBodies) > 0.0;
	}

	private int floodNeighbors(int[] cellNodeId, boolean[] solid, int[] markerCount, float[] cellLiquidVolume,
	                           FlipCutCellGeometry cutCells,
	                           GranularCouplingFields granular, MovingBodyBoundary movingBodies,
	                           int x, int y, int z, int from, int stackSize) {
		// fixed neighbour order keeps the traversal deterministic
		for (int axis = 0; axis < 3; axis++) for (int direction = -1; direction <= 1; direction += 2) {
			int nx = x + (axis == 0 ? direction : 0);
			int ny = y + (axis == 1 ? direction : 0);
			int nz = z + (axis == 2 ? direction : 0);
			if (nx < 0 || nx >= sizeX || ny < 0 || ny >= sizeY || nz < 0 || nz >= sizeZ) continue;
			int cell = (nz * sizeY + ny) * sizeX + nx;
			if (cellNodeId[cell] != -1 || !isAirCell(cell, solid, markerCount, cellLiquidVolume,
					cutCells, granular, movingBodies)) continue;
			float dynamicOpen = faceOpen(cutCells, movingBodies, from, cell);
			float fixedOpen = faceOpen(cutCells, null, from, cell);
			if (!(dynamicOpen > 0.0F)
					|| fixedOpen < FULL_FACE_THRESHOLD && dynamicOpen < FULL_FACE_THRESHOLD) continue;
			cellNodeId[cell] = -2;
			floodStack[stackSize++] = cell;
		}
		return stackSize;
	}

	/** Open fraction of the face shared by two adjacent cells on one axis. */
	private float faceOpen(FlipCutCellGeometry cutCells, MovingBodyBoundary movingBodies, int from, int to) {
		int x = from % sizeX;
		int yz = from / sizeX;
		int y = yz % sizeY;
		int z = yz / sizeY;
		if (to == from - 1) return xFaceOpen(cutCells, movingBodies, x, y, z);
		if (to == from + 1) return xFaceOpen(cutCells, movingBodies, x + 1, y, z);
		if (to == from - sizeX) return yFaceOpen(cutCells, movingBodies, x, y, z);
		if (to == from + sizeX) return yFaceOpen(cutCells, movingBodies, x, y + 1, z);
		if (to == from - sizeX * sizeY) return zFaceOpen(cutCells, movingBodies, x, y, z);
		if (to == from + sizeX * sizeY) return zFaceOpen(cutCells, movingBodies, x, y, z + 1);
		throw new IllegalArgumentException("Cells are not face-adjacent");
	}

	/** Continuous air volume of one cell: open volume minus the clamped liquid fraction. */
	private double cellAirVolume(int cell, float[] cellLiquidVolume, FlipCutCellGeometry cutCells,
	                             GranularCouplingFields granular, MovingBodyBoundary movingBodies) {
		float geometricOpen = (float) openCellVolume(cell, cutCells, movingBodies);
		float openVolume = granular == null ? geometricOpen : granular.effectiveOpenCellVolume(cell, geometricOpen);
		float liquid = Math.min(Math.max(cellLiquidVolume[cell], 0.0F), openVolume);
		return (openVolume - liquid) * cellVolumeCubicMeters;
	}

	private void addInterfaceResiduals(int[] cellNodeId, int[] cellLineageNodeId,
	                                   boolean[] solid, int[] markerCount,
	                                   float[] cellLiquidVolume, FlipCutCellGeometry cutCells,
	                                   GranularCouplingFields granular, MovingBodyBoundary movingBodies,
	                                   double[] cellAvailableVolume,
	                                   double[] sealedAvailableVolume) {
		for (int cell = 0; cell < cellNodeId.length; cell++) {
			if (solid[cell] || markerCount[cell] == 0) continue;
			double residualVolume = cellAirVolume(cell, cellLiquidVolume, cutCells, granular, movingBodies);
			cellAvailableVolume[cell] = residualVolume;
			if (!(residualVolume > 0.0)) continue;
			int attributed = -1;
			int x = cell % sizeX;
			int yz = cell / sizeX;
			int y = yz % sizeY;
			int z = yz / sizeY;
			for (int axis = 0; axis < 3 && attributed != 0; axis++) for (int direction = -1; direction <= 1; direction += 2) {
				int nx = x + (axis == 0 ? direction : 0);
				int ny = y + (axis == 1 ? direction : 0);
				int nz = z + (axis == 2 ? direction : 0);
				if (nx < 0 || nx >= sizeX || ny < 0 || ny >= sizeY || nz < 0 || nz >= sizeZ) continue;
				int neighbor = (nz * sizeY + ny) * sizeX + nx;
				if (cellNodeId[neighbor] <= -1) continue;
				if (faceOpen(cutCells, movingBodies, cell, neighbor) <= 0.0F) continue;
				attributed = attributed < 0 ? cellNodeId[neighbor] : Math.min(attributed, cellNodeId[neighbor]);
				if (attributed == 0) break;
			}
			if (attributed >= 0) cellLineageNodeId[cell] = attributed;
			if (attributed >= 1) sealedAvailableVolume[attributed - 1] += residualVolume;
		}
	}

	private List<GeometricEdge> collectGeometricEdges(int[] cellNodeId, FlipCutCellGeometry cutCells,
	                                                MovingBodyBoundary movingBodies) {
		TreeMap<Long, Double> areas = new TreeMap<>();
		for (int z = 0; z < sizeZ; z++) for (int y = 0; y < sizeY; y++) for (int x = 1; x < sizeX; x++) {
			int left = (z * sizeY + y) * sizeX + (x - 1);
			int right = (z * sizeY + y) * sizeX + x;
			accumulateEdge(areas, cellNodeId[left], cellNodeId[right], xFaceOpen(cutCells, movingBodies, x, y, z));
		}
		for (int z = 0; z < sizeZ; z++) for (int y = 1; y < sizeY; y++) for (int x = 0; x < sizeX; x++) {
			int below = (z * sizeY + (y - 1)) * sizeX + x;
			int above = (z * sizeY + y) * sizeX + x;
			accumulateEdge(areas, cellNodeId[below], cellNodeId[above], yFaceOpen(cutCells, movingBodies, x, y, z));
		}
		for (int z = 1; z < sizeZ; z++) for (int y = 0; y < sizeY; y++) for (int x = 0; x < sizeX; x++) {
			int back = ((z - 1) * sizeY + y) * sizeX + x;
			int front = (z * sizeY + y) * sizeX + x;
			accumulateEdge(areas, cellNodeId[back], cellNodeId[front], zFaceOpen(cutCells, movingBodies, x, y, z));
		}
		List<GeometricEdge> edges = new ArrayList<>(areas.size());
		for (Map.Entry<Long, Double> entry : areas.entrySet()) {
			long key = entry.getKey();
			edges.add(new GeometricEdge((int) (key >>> 32), (int) key, entry.getValue()));
		}
		return edges;
	}

	private double openCellVolume(int cell, FlipCutCellGeometry cutCells, MovingBodyBoundary movingBodies) {
		return movingBodies == null ? cutCells.openCellVolume(cell)
				: movingBodies.openCellFraction(cell, cutCells.openCellVolume(cell));
	}

	private float xFaceOpen(FlipCutCellGeometry cutCells, MovingBodyBoundary movingBodies, int x, int y, int z) {
		float fixed = cutCells.xFaceOpen(x, y, z);
		return movingBodies == null ? fixed : movingBodies.xFaceOpen(x, y, z, fixed);
	}

	private float yFaceOpen(FlipCutCellGeometry cutCells, MovingBodyBoundary movingBodies, int x, int y, int z) {
		float fixed = cutCells.yFaceOpen(x, y, z);
		return movingBodies == null ? fixed : movingBodies.yFaceOpen(x, y, z, fixed);
	}

	private float zFaceOpen(FlipCutCellGeometry cutCells, MovingBodyBoundary movingBodies, int x, int y, int z) {
		float fixed = cutCells.zFaceOpen(x, y, z);
		return movingBodies == null ? fixed : movingBodies.zFaceOpen(x, y, z, fixed);
	}

	private void accumulateEdge(TreeMap<Long, Double> areas, int first, int second, float openFraction) {
		if (first == second || first < 0 || second < 0) return;
		if (openFraction <= 0.0F || openFraction >= FULL_FACE_THRESHOLD) return;
		int low = Math.min(first, second);
		int high = Math.max(first, second);
		long key = ((long) low << 32) | high;
		areas.merge(key, openFraction * faceAreaSquareMeters, Double::sum);
	}
}

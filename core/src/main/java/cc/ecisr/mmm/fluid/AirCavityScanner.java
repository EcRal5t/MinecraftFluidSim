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
	 * inventory. Cavity volume is measured from exact per-cell marker volume: every attributed
	 * cell contributes its open volume minus the locally clamped liquid inventory, while a
	 * completed closed-domain scan normalizes the node total to the exact global complement. The smoothed
	 * free-surface field is derived geometry and cannot settle gas capacity or adiabatic work.
	 * A partially filled surface cell attributes to the adjacent node with the smallest id
	 * (ambient wins).</p>
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
	private final double[] measuredCellAvailableVolume;

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
		this.measuredCellAvailableVolume = new double[cellCount];
	}

	Result scan(boolean[] solid, int[] markerCount, double[] cellLiquidVolume, FlipCutCellGeometry cutCells) {
		return scan(solid, markerCount, cellLiquidVolume, cutCells, null, false);
	}

	Result scan(boolean[] solid, int[] markerCount, double[] cellLiquidVolume,
	            FlipCutCellGeometry cutCells, GranularCouplingFields granular) {
		return scan(solid, markerCount, cellLiquidVolume, cutCells, granular, false);
	}

	Result scan(boolean[] solid, int[] markerCount, double[] cellLiquidVolume,
	            FlipCutCellGeometry cutCells, GranularCouplingFields granular,
	            boolean sealedDomainBoundary) {
		return scan(solid, markerCount, cellLiquidVolume, cutCells, granular,
				sealedDomainBoundary, true);
	}

	/**
	 * Rebuilds a closed topology while retaining a proven finite bubble whose current gas volume
	 * lies entirely in partial liquid cells. The previous lineage is used only when a connected
	 * residual component has no current full-air/ambient neighbour; it therefore cannot seed a new
	 * bubble in liquid bulk or prevent a real merge.
	 */
	Result scanRetainingClosedLineages(
			boolean[] solid, int[] markerCount, double[] cellLiquidVolume,
			FlipCutCellGeometry cutCells, GranularCouplingFields granular,
			int[] previousCellLineageNodeId, int previousSealedNodeCount
	) {
		if (previousCellLineageNodeId == null || previousCellLineageNodeId.length != cellCount
				|| previousSealedNodeCount < 0) {
			throw new IllegalArgumentException("Previous gas lineage is incompatible");
		}
		Result scanned = scan(solid, markerCount, cellLiquidVolume, cutCells, granular, true, false);
		if (scanned.stopReason() == StopReason.COMPLETED) {
			scanned = retainOrphanedPartialLineages(scanned, solid, cutCells,
					previousCellLineageNodeId, previousSealedNodeCount);
			normalizeClosedCapacity(solid, cellLiquidVolume, cutCells, granular,
					scanned.sealedAvailableVolume());
		}
		return scanned;
	}

	private Result scan(boolean[] solid, int[] markerCount, double[] cellLiquidVolume,
	                    FlipCutCellGeometry cutCells, GranularCouplingFields granular,
	                    boolean sealedDomainBoundary, boolean normalizeClosedCapacity) {
		boolean budgetExhausted = false;
		int visitedAirCells = 0;
		int sealedNodes = 0;
		int[] cellNodeId = new int[cellCount];
		Arrays.fill(cellNodeId, -1);
		for (int seed = 0; seed < cellCount && !budgetExhausted; seed++) {
			if (!isAirCell(seed, solid, markerCount, cutCells, granular) || cellNodeId[seed] != -1) continue;
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
				stackSize = floodNeighbors(cellNodeId, solid, markerCount, cutCells, granular, x, y, z, cell, stackSize);
			}
			visitedAirCells += componentSize;
			int nodeId = 0;
			if (!touchesBoundary && !budgetExhausted) nodeId = ++sealedNodes;
			for (int cell = 0; cell < cellCount; cell++) if (cellNodeId[cell] == -2) cellNodeId[cell] = nodeId;
		}
		if (budgetExhausted) for (int cell = 0; cell < cellCount; cell++) {
			if (cellNodeId[cell] < 0 && isAirCell(cell, solid, markerCount, cutCells, granular)) cellNodeId[cell] = 0;
		}
		double[] cellAvailableVolume = new double[cellCount];
		double[] sealedAvailableVolume = new double[sealedNodes];
		for (int cell = 0; cell < cellCount; cell++) {
			int nodeId = cellNodeId[cell];
			cellAvailableVolume[cell] = cellAirVolume(cell, cellLiquidVolume, cutCells, granular);
			if (nodeId >= 1) {
				sealedAvailableVolume[nodeId - 1] += cellAvailableVolume[cell];
			}
		}
		int[] cellLineageNodeId = cellNodeId.clone();
		attributeInterfaceResiduals(cellNodeId, cellLineageNodeId, solid, markerCount, cellLiquidVolume,
				cutCells, granular, cellAvailableVolume, sealedAvailableVolume);
		if (sealedDomainBoundary && !budgetExhausted && normalizeClosedCapacity) normalizeClosedCapacity(
				solid, cellLiquidVolume, cutCells, granular, sealedAvailableVolume);
		List<GeometricEdge> edges = collectGeometricEdges(cellNodeId, cutCells);
		return new Result(cellNodeId, cellLineageNodeId, cellAvailableVolume,
				sealedNodes, sealedAvailableVolume, edges, visitedAirCells,
				budgetExhausted ? StopReason.BUDGET_EXHAUSTED : StopReason.COMPLETED);
	}

	private Result retainOrphanedPartialLineages(
			Result scanned, boolean[] solid, FlipCutCellGeometry cutCells,
			int[] previousLineage, int previousSealedNodeCount
	) {
		int[] membership = scanned.cellNodeId();
		int[] lineage = scanned.cellLineageNodeId();
		double[] cellAvailable = scanned.cellAvailableVolume();
		boolean[] visited = new boolean[cellCount];
		int temporaryNodeCount = scanned.sealedNodeCount();
		boolean retained = false;
		for (int seed = 0; seed < cellCount; seed++) {
			int previousNode = previousLineage[seed];
			if (visited[seed] || previousNode < 1 || previousNode > previousSealedNodeCount
					|| lineage[seed] >= 0 || solid[seed] || !(cellAvailable[seed] > 0.0)) continue;
			int head = 0, tail = 0;
			boolean touchesCurrentGas = false;
			floodStack[tail++] = seed;
			visited[seed] = true;
			while (head < tail) {
				int cell = floodStack[head++];
				int x = cell % sizeX;
				int yz = cell / sizeX;
				int y = yz % sizeY;
				int z = yz / sizeY;
				for (int axis = 0; axis < 3; axis++) for (int direction = -1; direction <= 1; direction += 2) {
					int nx = x + (axis == 0 ? direction : 0);
					int ny = y + (axis == 1 ? direction : 0);
					int nz = z + (axis == 2 ? direction : 0);
					if (nx < 0 || nx >= sizeX || ny < 0 || ny >= sizeY || nz < 0 || nz >= sizeZ) continue;
					int neighbor = (nz * sizeY + ny) * sizeX + nx;
					if (faceOpen(cutCells, cell, neighbor) <= 0.0F) continue;
					if (lineage[neighbor] >= 0) {
						touchesCurrentGas = true;
						continue;
					}
					if (!visited[neighbor] && previousLineage[neighbor] == previousNode
							&& !solid[neighbor] && cellAvailable[neighbor] > 0.0) {
						visited[neighbor] = true;
						floodStack[tail++] = neighbor;
					}
				}
			}
			if (touchesCurrentGas) continue;
			int retainedNode = ++temporaryNodeCount;
			for (int componentCell = 0; componentCell < tail; componentCell++) {
				int cell = floodStack[componentCell];
				membership[cell] = retainedNode;
				lineage[cell] = retainedNode;
			}
			retained = true;
		}
		if (!retained) return scanned;

		int[] minimumCell = new int[temporaryNodeCount + 1];
		Arrays.fill(minimumCell, Integer.MAX_VALUE);
		for (int cell = 0; cell < cellCount; cell++) {
			int node = membership[cell];
			if (node > 0) minimumCell[node] = Math.min(minimumCell[node], cell);
		}
		TreeMap<Integer, Integer> nodeByMinimumCell = new TreeMap<>();
		for (int node = 1; node <= temporaryNodeCount; node++) {
			if (minimumCell[node] == Integer.MAX_VALUE
					|| nodeByMinimumCell.put(minimumCell[node], node) != null) {
				throw new IllegalStateException("Retained gas node has no canonical owning cell");
			}
		}
		int[] canonicalByTemporary = new int[temporaryNodeCount + 1];
		int canonical = 0;
		for (int temporary : nodeByMinimumCell.values()) canonicalByTemporary[temporary] = ++canonical;
		for (int cell = 0; cell < cellCount; cell++) {
			if (membership[cell] > 0) membership[cell] = canonicalByTemporary[membership[cell]];
			if (lineage[cell] > 0) lineage[cell] = canonicalByTemporary[lineage[cell]];
		}
		double[] availableByNode = new double[temporaryNodeCount];
		for (int cell = 0; cell < cellCount; cell++) {
			int node = membership[cell] > 0 ? membership[cell] : lineage[cell];
			if (node > 0) availableByNode[node - 1] += cellAvailable[cell];
		}
		ArrayList<GeometricEdge> remappedEdges = new ArrayList<>(scanned.geometricEdges().size());
		for (GeometricEdge edge : scanned.geometricEdges()) {
			int first = edge.firstNode() > 0 ? canonicalByTemporary[edge.firstNode()] : edge.firstNode();
			int second = edge.secondNode() > 0 ? canonicalByTemporary[edge.secondNode()] : edge.secondNode();
			remappedEdges.add(new GeometricEdge(first, second, edge.areaSquareMeters()));
		}
		return new Result(membership, lineage, cellAvailable, temporaryNodeCount, availableByNode,
				List.copyOf(remappedEdges), scanned.visitedAirCells(), scanned.stopReason());
	}

	/** Recomputes node volumes when marker cardinality and every cell membership are unchanged. */
	double[] measureSealedAvailableVolumes(
			boolean[] solid, int[] markerCount, double[] cellLiquidVolume,
			FlipCutCellGeometry cutCells, GranularCouplingFields granular,
			int[] cellNodeId, int[] cellLineageNodeId, int sealedNodeCount,
			boolean sealedDomainBoundary
	) {
		if (solid.length != cellCount || markerCount.length != cellCount
				|| cellLiquidVolume.length != cellCount || cellNodeId.length != cellCount
				|| cellLineageNodeId.length != cellCount || sealedNodeCount < 0) {
			throw new IllegalArgumentException("Stable gas topology volume input is incompatible");
		}
		double[] result = new double[sealedNodeCount];
		Arrays.fill(measuredCellAvailableVolume, 0.0);
		for (int cell = 0; cell < cellCount; cell++) if (!solid[cell] && markerCount[cell] > 0) {
			measuredCellAvailableVolume[cell] = cellAirVolume(cell, cellLiquidVolume, cutCells, granular);
		}
		assignInterfaceLineage(cellNodeId, cellLineageNodeId, solid, markerCount,
				measuredCellAvailableVolume, cutCells, granular, sealedNodeCount);
		for (int cell = 0; cell < cellCount; cell++) {
			int node = cellNodeId[cell];
			if (node < 1 && !solid[cell] && markerCount[cell] > 0) node = cellLineageNodeId[cell];
			if (node < 1) continue;
			if (node > sealedNodeCount) {
				throw new IllegalArgumentException("Stable gas topology names an unknown node");
			}
			result[node - 1] += cellAirVolume(cell, cellLiquidVolume, cutCells, granular);
		}
		if (sealedDomainBoundary) normalizeClosedCapacity(
				solid, cellLiquidVolume, cutCells, granular, result);
		return result;
	}

	/** Makes a closed shell's total gas capacity the exact complement of its carrier inventories. */
	private void normalizeClosedCapacity(
			boolean[] solid, double[] cellLiquidVolume, FlipCutCellGeometry cutCells,
			GranularCouplingFields granular, double[] sealedAvailableVolume
	) {
		double open = 0.0;
		double liquid = 0.0;
		for (int cell = 0; cell < cellCount; cell++) {
			if (!solid[cell]) {
				float geometricOpen = cutCells.openCellVolume(cell);
				open += granular == null ? geometricOpen
						: granular.effectiveOpenCellVolume(cell, geometricOpen);
			}
			liquid += cellLiquidVolume[cell];
		}
		double expected = (open - liquid) * cellVolumeCubicMeters;
		if (!(Double.isFinite(expected) && expected >= 0.0)) {
			throw new IllegalStateException("Closed gas capacity is smaller than carrier inventory");
		}
		if (sealedAvailableVolume.length == 0) {
			if (expected > 1.0E-12) throw new IllegalStateException(
					"Closed residual gas volume has no topology owner");
			return;
		}
		double measured = 0.0;
		for (double volume : sealedAvailableVolume) measured += volume;
		if (!(Double.isFinite(measured) && measured > 0.0)) {
			if (expected == 0.0) return;
			throw new IllegalStateException("Closed gas topology has no measurable capacity");
		}
		double scale = expected / measured;
		double assigned = 0.0;
		for (int node = 0; node + 1 < sealedAvailableVolume.length; node++) {
			sealedAvailableVolume[node] *= scale;
			assigned += sealedAvailableVolume[node];
		}
		sealedAvailableVolume[sealedAvailableVolume.length - 1] = expected - assigned;
	}

	private boolean isAirCell(int cell, boolean[] solid, int[] markerCount, FlipCutCellGeometry cutCells,
	                          GranularCouplingFields granular) {
		float geometricOpen = cutCells.openCellVolume(cell);
		float available = granular == null ? geometricOpen : granular.effectiveOpenCellVolume(cell, geometricOpen);
		return !solid[cell] && markerCount[cell] == 0 && available > 0.0F;
	}

	private int floodNeighbors(int[] cellNodeId, boolean[] solid, int[] markerCount, FlipCutCellGeometry cutCells,
	                           GranularCouplingFields granular,
	                           int x, int y, int z, int from, int stackSize) {
		// fixed neighbour order keeps the traversal deterministic
		for (int axis = 0; axis < 3; axis++) for (int direction = -1; direction <= 1; direction += 2) {
			int nx = x + (axis == 0 ? direction : 0);
			int ny = y + (axis == 1 ? direction : 0);
			int nz = z + (axis == 2 ? direction : 0);
			if (nx < 0 || nx >= sizeX || ny < 0 || ny >= sizeY || nz < 0 || nz >= sizeZ) continue;
			int cell = (nz * sizeY + ny) * sizeX + nx;
			if (cellNodeId[cell] != -1 || !isAirCell(cell, solid, markerCount, cutCells, granular)) continue;
			if (faceOpen(cutCells, from, cell) < FULL_FACE_THRESHOLD) continue;
			cellNodeId[cell] = -2;
			floodStack[stackSize++] = cell;
		}
		return stackSize;
	}

	/** Open fraction of the face shared by two adjacent cells on one axis. */
	private float faceOpen(FlipCutCellGeometry cutCells, int from, int to) {
		int x = from % sizeX;
		int yz = from / sizeX;
		int y = yz % sizeY;
		int z = yz / sizeY;
		if (to == from - 1) return cutCells.xFaceOpen(x, y, z);
		if (to == from + 1) return cutCells.xFaceOpen(x + 1, y, z);
		if (to == from - sizeX) return cutCells.yFaceOpen(x, y, z);
		if (to == from + sizeX) return cutCells.yFaceOpen(x, y + 1, z);
		if (to == from - sizeX * sizeY) return cutCells.zFaceOpen(x, y, z);
		if (to == from + sizeX * sizeY) return cutCells.zFaceOpen(x, y, z + 1);
		throw new IllegalArgumentException("Cells are not face-adjacent");
	}

	/** Continuous air volume of one cell: open volume minus the clamped liquid fraction. */
	private double cellAirVolume(int cell, double[] cellLiquidVolume, FlipCutCellGeometry cutCells,
	                             GranularCouplingFields granular) {
		float geometricOpen = cutCells.openCellVolume(cell);
		double openVolume = granular == null ? geometricOpen : granular.effectiveOpenCellVolume(cell, geometricOpen);
		double liquid = Math.min(Math.max(cellLiquidVolume[cell], 0.0), openVolume);
		return (openVolume - liquid) * cellVolumeCubicMeters;
	}

	private void attributeInterfaceResiduals(int[] cellNodeId, int[] cellLineageNodeId,
	                                         boolean[] solid, int[] markerCount,
	                                         double[] cellLiquidVolume, FlipCutCellGeometry cutCells,
	                                         GranularCouplingFields granular, double[] cellAvailableVolume,
	                                         double[] sealedAvailableVolume) {
		for (int cell = 0; cell < cellNodeId.length; cell++) {
			if (solid[cell] || markerCount[cell] == 0) continue;
			double residualVolume = cellAirVolume(cell, cellLiquidVolume, cutCells, granular);
			cellAvailableVolume[cell] = residualVolume;
		}
		assignInterfaceLineage(cellNodeId, cellLineageNodeId, solid, markerCount,
				cellAvailableVolume, cutCells, granular, sealedAvailableVolume.length);
		for (int cell = 0; cell < cellCount; cell++) {
			int node = cellLineageNodeId[cell];
			if (cellNodeId[cell] < 0 && node >= 1) {
				sealedAvailableVolume[node - 1] += cellAvailableVolume[cell];
			}
		}
	}

	private void assignInterfaceLineage(
			int[] cellNodeId, int[] cellLineageNodeId, boolean[] solid, int[] markerCount,
			double[] cellAvailableVolume, FlipCutCellGeometry cutCells,
			GranularCouplingFields granular, int sealedNodeCount
	) {
		System.arraycopy(cellNodeId, 0, cellLineageNodeId, 0, cellCount);
		// Lower node ids win shared interface paths, matching the direct-neighbour rule (ambient is 0).
		// Propagation crosses gas-centred residual cells only. Liquid-centred interface cells receive
		// one adjacent lineage below, but never form a gas path through a bulk liquid body.
		for (int node = 0; node <= sealedNodeCount; node++) {
			int stackSize = 0;
			for (int cell = 0; cell < cellCount; cell++) if (cellNodeId[cell] == node) {
				floodStack[stackSize++] = cell;
			}
			while (stackSize > 0) {
				int from = floodStack[--stackSize];
				int x = from % sizeX;
				int yz = from / sizeX;
				int y = yz % sizeY;
				int z = yz / sizeY;
				for (int axis = 0; axis < 3; axis++) for (int direction = -1; direction <= 1; direction += 2) {
					int nx = x + (axis == 0 ? direction : 0);
					int ny = y + (axis == 1 ? direction : 0);
					int nz = z + (axis == 2 ? direction : 0);
					if (nx < 0 || nx >= sizeX || ny < 0 || ny >= sizeY || nz < 0 || nz >= sizeZ) continue;
					int neighbor = (nz * sizeY + ny) * sizeX + nx;
					if (cellLineageNodeId[neighbor] >= 0 || solid[neighbor]
							|| markerCount[neighbor] == 0
							|| !gasCenteredResidual(neighbor, cellAvailableVolume, cutCells, granular)
							|| faceOpen(cutCells, from, neighbor) <= 0.0F) continue;
					cellLineageNodeId[neighbor] = node;
					floodStack[stackSize++] = neighbor;
				}
			}
		}
		for (int cell = 0; cell < cellCount; cell++) {
			if (cellLineageNodeId[cell] >= 0 || solid[cell] || markerCount[cell] == 0
					|| !(cellAvailableVolume[cell] > 0.0)) continue;
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
				int node = cellLineageNodeId[neighbor];
				if (node < 0 || faceOpen(cutCells, cell, neighbor) <= 0.0F) continue;
				if (cellNodeId[neighbor] < 0
						&& !gasCenteredResidual(neighbor, cellAvailableVolume, cutCells, granular)) continue;
				attributed = attributed < 0 ? node : Math.min(attributed, node);
			}
			if (attributed >= 0) cellLineageNodeId[cell] = attributed;
		}
	}

	private boolean gasCenteredResidual(
			int cell, double[] cellAvailableVolume, FlipCutCellGeometry cutCells,
			GranularCouplingFields granular
	) {
		float geometricOpen = cutCells.openCellVolume(cell);
		double openVolume = granular == null ? geometricOpen
				: granular.effectiveOpenCellVolume(cell, geometricOpen);
		return cellAvailableVolume[cell] >= 0.5 * openVolume * cellVolumeCubicMeters;
	}
	private List<GeometricEdge> collectGeometricEdges(int[] cellNodeId, FlipCutCellGeometry cutCells) {
		TreeMap<Long, Double> areas = new TreeMap<>();
		for (int z = 0; z < sizeZ; z++) for (int y = 0; y < sizeY; y++) for (int x = 1; x < sizeX; x++) {
			int left = (z * sizeY + y) * sizeX + (x - 1);
			int right = (z * sizeY + y) * sizeX + x;
			accumulateEdge(areas, cellNodeId[left], cellNodeId[right], cutCells.xFaceOpen(x, y, z));
		}
		for (int z = 0; z < sizeZ; z++) for (int y = 1; y < sizeY; y++) for (int x = 0; x < sizeX; x++) {
			int below = (z * sizeY + (y - 1)) * sizeX + x;
			int above = (z * sizeY + y) * sizeX + x;
			accumulateEdge(areas, cellNodeId[below], cellNodeId[above], cutCells.yFaceOpen(x, y, z));
		}
		for (int z = 1; z < sizeZ; z++) for (int y = 0; y < sizeY; y++) for (int x = 0; x < sizeX; x++) {
			int back = ((z - 1) * sizeY + y) * sizeX + x;
			int front = (z * sizeY + y) * sizeX + x;
			accumulateEdge(areas, cellNodeId[back], cellNodeId[front], cutCells.zFaceOpen(x, y, z));
		}
		List<GeometricEdge> edges = new ArrayList<>(areas.size());
		for (Map.Entry<Long, Double> entry : areas.entrySet()) {
			long key = entry.getKey();
			edges.add(new GeometricEdge((int) (key >>> 32), (int) key, entry.getValue()));
		}
		return edges;
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

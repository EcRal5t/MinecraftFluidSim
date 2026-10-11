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
	 * completed closed-domain scan settles each node against the exact complement of the cells it
	 * owns. The smoothed free-surface field is derived geometry and cannot settle gas capacity or
	 * adiabatic work.
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
	 * residual component has no current full-air/ambient neighbour. If no owners survive at all,
	 * residual components establish new owning cells; the remap determines their actual inventory.
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
			if (scanned.sealedNodeCount() == 0 && previousSealedNodeCount > 0) {
				// Old memberships can disappear while newly extracted residuals have no lineage.
				// First retain proven owners; only when none remain, seed the residual geometry.
				int[] unowned = new int[cellCount];
				Arrays.fill(unowned, -1);
				scanned = retainOrphanedPartialLineages(scanned, solid, cutCells, unowned, 0);
			}
			normalizeClosedCapacity(solid, cellLiquidVolume, cutCells, granular,
					scanned.cellNodeId(), scanned.cellLineageNodeId(), scanned.sealedAvailableVolume());
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
		List<GeometricEdge> edges = collectGeometricEdges(cellNodeId, cutCells);
		Result result = new Result(cellNodeId, cellLineageNodeId, cellAvailableVolume,
				sealedNodes, sealedAvailableVolume, edges, visitedAirCells,
				budgetExhausted ? StopReason.BUDGET_EXHAUSTED : StopReason.COMPLETED);
		if (sealedDomainBoundary && !budgetExhausted && normalizeClosedCapacity) {
			if (sealedNodes == 0) {
				result = retainOrphanedPartialLineages(result, solid, cutCells, cellLineageNodeId.clone(), 0);
			}
			normalizeClosedCapacity(solid, cellLiquidVolume, cutCells, granular,
					result.cellNodeId(), result.cellLineageNodeId(), result.sealedAvailableVolume());
		}
		return result;
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
		boolean[] previousSurvivesAsAir = survivingAirNodes(
				membership, cellAvailable, previousLineage, previousSealedNodeCount);
		// Fractional extraction from a full domain can expose only partial cells.
		// With no old or full-air owner, their connected residuals are new vacuum cavities.
		boolean newVacuum = previousSealedNodeCount == 0 && scanned.sealedNodeCount() == 0;
		for (int seed = 0; seed < cellCount; seed++) {
			int previousNode = previousLineage[seed];
			if (visited[seed] || !newVacuum && (previousNode < 1 || previousNode > previousSealedNodeCount
					|| previousSurvivesAsAir[previousNode])
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
					if (!visited[neighbor] && (newVacuum || previousLineage[neighbor] == previousNode)
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
				throw new GasTopologyBlockedException("Retained gas node has no canonical owning cell");
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

	/**
	 * Reports which previous nodes still own at least one current air cell.
	 *
	 * <p>Only a previous node whose gas volume now lies entirely in partial liquid cells may be
	 * rebuilt from its interface lineage. A node that still owns a full-air or ambient cell is
	 * matched by membership overlap instead, so its interface cells that merely flickered out of
	 * the current interface set stay unattributed rather than being promoted into an independent
	 * sealed owner. That keeps an ordinary marker void inside the liquid bulk from being read as
	 * a new bubble carrying a share of the headspace inventory.</p>
	 */
	private boolean[] survivingAirNodes(
			int[] membership, double[] cellAvailable, int[] previousLineage, int previousSealedNodeCount
	) {
		boolean[] survives = new boolean[previousSealedNodeCount + 1];
		for (int cell = 0; cell < cellCount; cell++) {
			if (membership[cell] < 0 || !(cellAvailable[cell] > 0.0)) continue;
			int previousNode = previousLineage[cell];
			if (previousNode >= 1 && previousNode <= previousSealedNodeCount) survives[previousNode] = true;
		}
		return survives;
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
				solid, cellLiquidVolume, cutCells, granular, cellNodeId, cellLineageNodeId, result);
		return result;
	}

	/**
	 * Settles a closed shell's gas capacity against the carrier inventory each cavity owns.
	 *
	 * <p>Every cell pays its own signed complement - effective open volume minus authoritative
	 * liquid inventory - to the one cavity that currently owns it. Marker clustering therefore
	 * shrinks only the cavity those markers are attributed to and can no longer reach a cavity
	 * behind a solid wall; a free-surface cell's liquid is charged to the gas it touches, and a
	 * filled bulk cell settles at exactly zero. Volume that no cavity owns is the residual of
	 * partially filled cells that touch no gas at all. Such a cell has no owner to pay, so its
	 * residual keeps the existing capacity-weighted settlement, which is what preserves the exact
	 * complement of the whole shell. A cavity whose own attributed carriers exceed its open volume
	 * is crushed to zero here; the topology coordinator then hands its finite gas to a cavity it can
	 * reach, so a zero-volume control volume only ever carries vacuum.</p>
	 */
	private void normalizeClosedCapacity(
			boolean[] solid, double[] cellLiquidVolume, FlipCutCellGeometry cutCells,
			GranularCouplingFields granular, int[] cellNodeId, int[] cellLineageNodeId,
			double[] sealedAvailableVolume
	) {
		int nodes = sealedAvailableVolume.length;
		double open = 0.0;
		double liquid = 0.0;
		for (int cell = 0; cell < cellCount; cell++) {
			if (!solid[cell]) open += cutCells.openCellVolume(cell);
			liquid += cellLiquidVolume[cell];
		}
		if (granular != null) open -= granular.totalSolidSvq() / (double) cc.ecisr.mmm.granular.SolidVolume.SVQ_PER_CELL;
		double expected = (open - liquid) * cellVolumeCubicMeters;
		if (!(Double.isFinite(expected) && expected >= 0.0)) {
			throw new GasTopologyBlockedException("Closed gas capacity is smaller than carrier inventory");
		}
		if (nodes == 0) {
			if (expected > 1.0E-12) throw new GasTopologyBlockedException(
					"Closed residual gas volume has no topology owner");
			return;
		}
		double[] owned = new double[nodes];
		for (int cell = 0; cell < cellCount; cell++) {
			double inventory = Math.max(cellLiquidVolume[cell], 0.0);
			double complement = (openCellVolume(cell, cutCells, granular) - inventory) * cellVolumeCubicMeters;
			int owner = cellNodeId[cell] >= 1 ? cellNodeId[cell] : cellLineageNodeId[cell];
			if (owner < 1 && complement < 0.0) {
				// A cell whose markers over-fill it owns no gas, so interface lineage has nothing to
				// attribute to it. Its excess is still paid by the cavity those markers sit in - the
				// one a face-adjacent cell already owns - never by a cavity behind a solid wall.
				owner = adjacentCavityOwner(cell, cellNodeId, cellLineageNodeId, cutCells);
			}
			if (owner < 1) continue;
			owned[owner - 1] += complement;
		}
		double settled = 0.0;
		for (int node = 0; node < nodes; node++) {
			// A cavity whose own attributed carriers already exceed its open volume is crushed to
			// zero; the deficit joins the unowned residual instead of being hidden in a neighbour.
			owned[node] = Math.max(owned[node], 0.0);
			settled += owned[node];
		}
		if (!(settled > 0.0)) {
			if (expected == 0.0) {
				Arrays.fill(sealedAvailableVolume, 0.0);
				return;
			}
			throw new GasTopologyBlockedException("Closed gas topology has no measurable capacity");
		}
		double scale = expected / settled;
		double assigned = 0.0;
		int largest = 0;
		for (int node = 0; node < nodes; node++) {
			sealedAvailableVolume[node] = owned[node] * scale;
			assigned += sealedAvailableVolume[node];
			if (sealedAvailableVolume[node] > sealedAvailableVolume[largest]) largest = node;
		}
		// The complement is exact, so the rounding remainder belongs to the cavity that can absorb
		// it. Taking it from the last slot instead would make a crushed cavity negative.
		sealedAvailableVolume[largest] += expected - assigned;
	}

	/**
	 * The single cavity predicate: a non-solid cell with available volume that carries no marker.
	 *
	 * <p>This deliberately does not classify the cell as "air" or as "an unsampled liquid cell". Both
	 * are the same thing to the scan - a connected component of carrier-free volume - and the same
	 * flood fill resolves them. What has to differ is not whether a component may become a cavity but
	 * what inventory it starts with: a component with no previous owner is an explicit vacuum node and
	 * inherits nothing, which is enforced where lineage is assigned, not here.</p>
	 */
	private boolean isAirCell(int cell, boolean[] solid, int[] markerCount, FlipCutCellGeometry cutCells,
	                          GranularCouplingFields granular) {
		return !solid[cell] && markerCount[cell] == 0 && openCellVolume(cell, cutCells, granular) > 0.0;
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

	/** Owning cavity of a face-adjacent cell that already owns gas, or -1 when there is none. */
	private int adjacentCavityOwner(
			int cell, int[] cellNodeId, int[] cellLineageNodeId, FlipCutCellGeometry cutCells
	) {
		int x = cell % sizeX;
		int yz = cell / sizeX;
		int y = yz % sizeY;
		int z = yz / sizeY;
		int owner = -1;
		for (int axis = 0; axis < 3; axis++) for (int direction = -1; direction <= 1; direction += 2) {
			int nx = x + (axis == 0 ? direction : 0);
			int ny = y + (axis == 1 ? direction : 0);
			int nz = z + (axis == 2 ? direction : 0);
			if (nx < 0 || nx >= sizeX || ny < 0 || ny >= sizeY || nz < 0 || nz >= sizeZ) continue;
			int neighbor = (nz * sizeY + ny) * sizeX + nx;
			if (faceOpen(cutCells, cell, neighbor) <= 0.0F) continue;
			int node = cellNodeId[neighbor] >= 1 ? cellNodeId[neighbor] : cellLineageNodeId[neighbor];
			if (node < 1) continue;
			owner = owner < 0 ? node : Math.min(owner, node);
		}
		return owner;
	}

	/** Open volume of one cell after cut-cell geometry and granular/coherent occupancy. */
	private double openCellVolume(int cell, FlipCutCellGeometry cutCells, GranularCouplingFields granular) {
		float geometricOpen = cutCells.openCellVolume(cell);
		return granular == null ? geometricOpen : granular.effectiveOpenCellVolume(cell, geometricOpen);
	}

	/** Continuous air volume of one cell: open volume minus the clamped liquid fraction. */
	private double cellAirVolume(int cell, double[] cellLiquidVolume, FlipCutCellGeometry cutCells,
	                             GranularCouplingFields granular) {
		double openVolume = openCellVolume(cell, cutCells, granular);
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
		return cellAvailableVolume[cell]
				>= 0.5 * openCellVolume(cell, cutCells, granular) * cellVolumeCubicMeters;
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

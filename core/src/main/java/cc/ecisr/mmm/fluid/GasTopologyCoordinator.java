package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.thermal.GasEnergyStore;

import cc.ecisr.mmm.fluid.air.AirEdge;
import cc.ecisr.mmm.fluid.air.AirNetwork;
import cc.ecisr.mmm.fluid.air.GasInventory;
import cc.ecisr.mmm.fluid.air.GasRegistry;
import cc.ecisr.mmm.fluid.air.GasStateSnapshot;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Coordinates the gas topology lifecycle: bootstrap, restore, export, refresh with its
 * remap planning, ambient exchange through the finite-rate orifice transport, and topology
 * validation. The stable overlap/lineage rules and their exception classification are kept
 * verbatim; the coordinator owns the cavity scanner and every membership/plan transition,
 * while the domain retains only thin entry delegates.
 */
final class GasTopologyCoordinator {
	private final FlipFluidDomain domain;
	private final AirCavityScanner scanner;

	GasTopologyCoordinator(FlipFluidDomain domain) {
		this.domain = domain;
		this.scanner = new AirCavityScanner(domain.sizeX, domain.sizeY, domain.sizeZ,
				domain.config.cellSize(), domain.cellCount);
	}

	/** Initializes gas topology from current carrier geometry and books sealed ambient source once. */
	GasStateSnapshot bootstrap(String chemicalRegistryFingerprint) {
		domain.ensureGrid();
		refresh();
		return export(chemicalRegistryFingerprint);
	}

	GasStateSnapshot export(String chemicalRegistryFingerprint) {
		if (domain.gasState.cellNodeId == null) {
			throw new IllegalStateException("Gas topology has not been explicitly initialized");
		}
		if (!domain.gasState.network.auditClosed()) throw new IllegalStateException("Gas component audit does not close");
		return GasStateSnapshot.capture(domain.sizeX, domain.sizeY, domain.sizeZ, domain.gasState.cellNodeId,
				domain.gasState.network,
				chemicalRegistryFingerprint == null ? "" : chemicalRegistryFingerprint,
				domain.gasState.operatorVentedTopology);
	}

	/** Restores complete gas truth after its matching liquid/material state. */
	void restore(GasStateSnapshot state, GasRegistry registry) {
		if (state == null || registry == null || state.sizeX() != domain.sizeX
				|| state.sizeY() != domain.sizeY || state.sizeZ() != domain.sizeZ
				|| !state.registryFingerprint().equals(registry.fingerprint())) {
			throw new IllegalArgumentException("Gas restore identity/dimensions mismatch");
		}
		if (domain.gasState.cellNodeId != null || domain.gasState.network.nodeCount() != 0) {
			throw new IllegalStateException("Gas restore requires an uninitialized topology");
		}
		domain.ensureGrid();
		AirCavityScanner.Result result = domain.gasState.sealedDomainBoundary
				? scanner.scanRetainingClosedLineages(domain.geometry.solid, domain.markerGrid.markerCount,
						domain.markerGrid.exactCellMarkerVolume, domain.geometry.cutCells,
						domain.pressureGranularCoupling, state.cellNodeOrdinal(), state.nodes().size())
				: scanner.scan(domain.geometry.solid, domain.markerGrid.markerCount,
						domain.markerGrid.exactCellMarkerVolume, domain.geometry.cutCells,
						domain.pressureGranularCoupling, false);
		int[] expected = state.cellNodeOrdinal(), actual = result.cellNodeId();
		boolean ventedAmbient = state.operatorVentedTopology();
		if (ventedAmbient && !state.nodes().isEmpty()) {
			throw new IllegalArgumentException("Operator-vented gas state contains sealed inventory");
		}
		if (!ventedAmbient && result.sealedNodeCount() != state.nodes().size()) {
			throw new IllegalArgumentException("Restored gas sealed-node count does not match derived topology");
		}
		for (int cell = 0; cell < domain.cellCount; cell++) {
			int derived = actual[cell] > 0 ? actual[cell] : 0;
			if (!ventedAmbient && expected[cell] != derived) throw new IllegalArgumentException(
					"Restored gas topology differs from committed membership at cell " + cell);
			if (ventedAmbient && expected[cell] != 0) throw new IllegalArgumentException(
					"Operator-vented gas topology contains a sealed membership");
		}
		for (int node = 0; node < state.nodes().size(); node++) {
			int minimum = Integer.MAX_VALUE;
			for (int cell = 0; cell < expected.length; cell++) if (expected[cell] == node + 1) minimum = Math.min(minimum, cell);
			if (minimum != state.nodes().get(node).topologyKey()) {
				throw new IllegalArgumentException("Restored gas topology key mismatch");
			}
		}
		AirNetwork restored = new AirNetwork(registry);
		restored.restoreNodes(ventedAmbient ? new double[0] : result.sealedAvailableVolume(),
				state.nodes().stream().map(GasStateSnapshot.Node::inventory).toList(),
				state.externalSource(), state.escapedSink(), state.massRoundoff(), state.residualRoundoff());
		for (AirCavityScanner.GeometricEdge edge : ventedAmbient ? List.<AirCavityScanner.GeometricEdge>of()
				: result.geometricEdges()) {
			restored.addOrMergeEdge(edge.firstNode(), edge.secondNode(), edge.areaSquareMeters(), 0.65);
		}
		domain.gasState.network = restored;
		domain.gasState.cellNodeId = actual;
		domain.gasState.cellLineageNodeId = result.cellLineageNodeId();
		if (ventedAmbient) {
			for (int cell = 0; cell < domain.cellCount; cell++) {
				if (domain.gasState.cellNodeId[cell] > 0) domain.gasState.cellNodeId[cell] = AirNetwork.AMBIENT_NODE;
				if (domain.gasState.cellLineageNodeId[cell] > 0) domain.gasState.cellLineageNodeId[cell] = AirNetwork.AMBIENT_NODE;
			}
		}
		domain.gasState.previousSealedNodeCount = ventedAmbient ? 0 : result.sealedNodeCount();
		domain.gasState.pressureActive = domain.gasState.previousSealedNodeCount > 0;
		domain.gasState.operatorVentedTopology = ventedAmbient;
	}

	void refresh() {
		AirCavityScanner.Result result = domain.gasState.sealedDomainBoundary && domain.gasState.cellLineageNodeId != null
				? scanner.scanRetainingClosedLineages(domain.geometry.solid, domain.markerGrid.markerCount,
						domain.markerGrid.exactCellMarkerVolume, domain.geometry.cutCells,
						domain.pressureGranularCoupling, domain.gasState.cellLineageNodeId,
						domain.gasState.previousSealedNodeCount)
				: scanner.scan(domain.geometry.solid, domain.markerGrid.markerCount,
						domain.markerGrid.exactCellMarkerVolume, domain.geometry.cutCells,
						domain.pressureGranularCoupling, domain.gasState.sealedDomainBoundary);
		commit(plan(result));
	}

	/** Preserves node membership while updating continuous cavity volume and its adiabatic work candidate. */
	void refreshAvailableVolume() {
		if (domain.gasState.cellNodeId == null || domain.gasState.cellLineageNodeId == null
				|| domain.gasState.previousSealedNodeCount != domain.gasState.network.nodeCount()) {
			throw new IllegalStateException("Stable gas topology is unavailable");
		}
		double[] availableVolumes = scanner.measureSealedAvailableVolumes(
				domain.geometry.solid, domain.markerGrid.markerCount, domain.markerGrid.exactCellMarkerVolume,
				domain.geometry.cutCells, domain.pressureGranularCoupling,
				domain.gasState.cellNodeId, domain.gasState.cellLineageNodeId,
				domain.gasState.previousSealedNodeCount, domain.gasState.sealedDomainBoundary);
		ArrayList<GasInventory> inventories = new ArrayList<>(domain.gasState.previousSealedNodeCount);
		double[] mixedEnergy = new double[domain.gasState.previousSealedNodeCount];
		double[] inheritedAvailable = new double[domain.gasState.previousSealedNodeCount];
		for (int node = 1; node <= domain.gasState.previousSealedNodeCount; node++) {
			inventories.add(domain.gasState.network.inventory(node));
			mixedEnergy[node - 1] = domain.thermalState.gasNodeEnergy(node, domain.gasState.network);
			inheritedAvailable[node - 1] = domain.gasState.network.availableVolume(node);
		}
		GasEnergyStore.GasRemapPlan thermalPlan = domain.thermalState.planGasRemap(
				domain.gasState.network, inventories, availableVolumes, mixedEnergy, inheritedAvailable, 0.0, 0.0);
		AirNetwork.RemapPlan gasPlan = domain.gasState.network.planRemapNodes(
				availableVolumes, inventories, GasInventory.EMPTY, GasInventory.EMPTY, domain.gasState.network.edges());
		domain.thermalState.commitGasRemap(thermalPlan);
		domain.gasState.network.commitRemapNodes(gasPlan);
	}

	/** Plans one topology candidate against the current carrier geometry without committing it. */
	GasTopologyPlan planFromFreshScan() {
		return plan(scanner.scan(domain.geometry.solid, new int[domain.cellCount], new double[domain.cellCount],
				domain.geometry.cutCells, null, domain.gasState.sealedDomainBoundary));
	}

	/** Builds the complete gas/energy topology candidate before any owner is mutated. */
	GasTopologyPlan plan(AirCavityScanner.Result result) {
		int sealed = result.sealedNodeCount();
		boolean initialBootstrap = domain.gasState.cellNodeId == null;
		if (domain.gasState.sealedDomainBoundary && !initialBootstrap
				&& result.stopReason() != AirCavityScanner.StopReason.COMPLETED) {
			throw new GasTopologyBlockedException("Closed gas topology scan exhausted its budget");
		}
		boolean thermalGas = domain.thermalState != null && domain.thermalState.gasModelBound();
		double[] availableVolumes = result.sealedAvailableVolume();
		ArrayList<GasInventory> inventories = new ArrayList<>(sealed);
		for (int node = 0; node < sealed; node++) inventories.add(GasInventory.EMPTY);
		GasInventory sourceDelta = GasInventory.EMPTY;
		GasInventory sinkDelta = GasInventory.EMPTY;
		double[] mixedEnergy = thermalGas ? new double[sealed] : null;
		double[] inheritedAvailable = thermalGas ? new double[sealed] : null;
		double sourceEnergy = 0.0, sinkEnergy = 0.0;
		if (domain.gasState.previousSealedNodeCount > 0 && sealed > 0) {
			int previous = domain.gasState.previousSealedNodeCount;
			TreeMap<Long, Long> overlap = new TreeMap<>();
			boolean[] hasMembershipOverlap = new boolean[previous + 1];
			boolean[] newNodeHasMembershipOverlap = new boolean[sealed + 1];
			int[] newMembership = result.cellNodeId();
			int[] newLabels = result.cellLineageNodeId();
			double[] cellAvailableVolume = result.cellAvailableVolume();
			for (int cell = 0; cell < domain.cellCount; cell++) {
				int old = domain.gasState.cellNodeId[cell];
				if (old < 1) continue;
				int now = newMembership[cell];
				if (now < 0 || !(cellAvailableVolume[cell] > 0.0)) continue;
				long key = ((long) old << 32) | (now & 0xffffffffL);
				long weight = Math.max(1L, AirNetwork.nanos(cellAvailableVolume[cell]));
				overlap.merge(key, weight, Math::addExact);
				hasMembershipOverlap[old] = true;
				if (now > 0) newNodeHasMembershipOverlap[now] = true;
			}
			// A newly full-air child can have no same-cell membership overlap when its cell was an
			// interface residual in the previous topology. It inherits only from provably adjacent
			// runtime lineages; each old inventory is then split by actual child volume. With no old
			// owner it is a vacuum cavity, never ambient inventory behind an unchanged closed shell.
			// An old node that still owns an open cell keeps being matched by membership overlap, so
			// a marker void flickering open inside the liquid bulk stays a vacuum cavity instead of
			// siphoning a share of a headspace that never went anywhere.
			boolean[] previousOwnsAirCell = previousNodesOwningAirCells(newMembership, cellAvailableVolume);
			addAdjacentPreviousGasOverlaps(newMembership, newNodeHasMembershipOverlap,
					availableVolumes, overlap, hasMembershipOverlap, previousOwnsAirCell);
			// Runtime interface lineage is a fallback for a node whose full-air membership vanished
			// completely. It must not siphon a still-present cavity into ambient as its liquid surface moves.
			addDisappearingPreviousGasOverlaps(hasMembershipOverlap,
					newMembership, newLabels, cellAvailableVolume, overlap);
			if (domain.gasState.sealedDomainBoundary) retainClosedOrphans(overlap, previous, sealed, availableVolumes);
			boolean[] contributed = new boolean[sealed + 1];
			for (int old = 1; old <= previous; old++) {
				GasInventory inventory = domain.gasState.network.inventory(old);
				if (inventory.isEmpty()) continue;
				Map<Long, Long> oldOverlap = overlap.subMap(
						(long) old << 32, (long) (old + 1) << 32);
				int destinations = oldOverlap.size();
				if (destinations == 0) {
					if (domain.gasState.sealedDomainBoundary) {
						throw new GasTopologyBlockedException("Closed gas inventory lost its topology lineage: "
								+ gasTopologyLossDetail(old, newMembership, newLabels, cellAvailableVolume, sealed));
					}
					sinkDelta = sinkDelta.merge(inventory);
					if (thermalGas) sinkEnergy += domain.thermalState.gasNodeEnergy(old, domain.gasState.network);
					continue;
				}
				long[] weights = new long[destinations];
				int weight = 0;
				for (long available : oldOverlap.values()) weights[weight++] = available;
				List<GasInventory> parts = inventory.partition(weights);
				double[] partEnergy = !thermalGas ? null
						: domain.thermalState.partitionGasNodeEnergy(old, parts, domain.gasState.network);
				double[] partAvailable = !thermalGas ? null
						: partitionTopologyVolume(domain.gasState.network.availableVolume(old), weights);
				int part = 0;
				for (Map.Entry<Long, Long> entry : oldOverlap.entrySet()) {
					int node = (int) (entry.getKey() & 0xffffffffL);
					if (node == AirNetwork.AMBIENT_NODE) {
						sinkDelta = sinkDelta.merge(parts.get(part));
						if (thermalGas) sinkEnergy += partEnergy[part];
					} else {
						inventories.set(node - 1, inventories.get(node - 1).merge(parts.get(part)));
						if (thermalGas) {
							mixedEnergy[node - 1] += partEnergy[part];
							inheritedAvailable[node - 1] += partAvailable[part];
						}
						contributed[node] = true;
					}
					part++;
				}
			}
			for (int node = 1; node <= sealed; node++) {
				if (!contributed[node]) {
					if (domain.gasState.sealedDomainBoundary) {
						if (thermalGas) inheritedAvailable[node - 1] = availableVolumes[node - 1];
						continue;
					}
					GasInventory created = domain.gasState.network.ambientInventory(
							Math.max(1L, AirNetwork.nanos(availableVolumes[node - 1])));
					inventories.set(node - 1, created);
					sourceDelta = sourceDelta.merge(created);
					if (thermalGas) {
						mixedEnergy[node - 1] = domain.thermalState.ambientGasEnergy(created);
						inheritedAvailable[node - 1] = availableVolumes[node - 1];
						sourceEnergy += mixedEnergy[node - 1];
					}
				}
			}
		} else {
			if (domain.gasState.previousSealedNodeCount > 0) {
				for (int node = 1; node <= domain.gasState.previousSealedNodeCount; node++) {
					if (domain.gasState.sealedDomainBoundary && !domain.gasState.network.inventory(node).isEmpty()) {
						throw new GasTopologyBlockedException("Closed gas inventory has no available cavity volume");
					}
					sinkDelta = sinkDelta.merge(domain.gasState.network.inventory(node));
					if (thermalGas) sinkEnergy += domain.thermalState.gasNodeEnergy(node, domain.gasState.network);
				}
			}
			for (int node = 1; node <= sealed; node++) {
				if (domain.gasState.sealedDomainBoundary && !initialBootstrap) {
					if (thermalGas) inheritedAvailable[node - 1] = availableVolumes[node - 1];
					continue;
				}
				GasInventory created = domain.gasState.network.ambientInventory(
						Math.max(1L, AirNetwork.nanos(availableVolumes[node - 1])));
				inventories.set(node - 1, created);
				sourceDelta = sourceDelta.merge(created);
				if (thermalGas) {
					mixedEnergy[node - 1] = domain.thermalState.ambientGasEnergy(created);
					inheritedAvailable[node - 1] = availableVolumes[node - 1];
					sourceEnergy += mixedEnergy[node - 1];
				}
			}
		}
		if (domain.gasState.sealedDomainBoundary && !initialBootstrap
				&& (!sourceDelta.isEmpty() || !sinkDelta.isEmpty())) {
			throw new GasTopologyBlockedException("Closed gas topology attempted an external source or sink");
		}
		GasEnergyStore.GasRemapPlan thermalPlan = !thermalGas ? null : domain.thermalState.planGasRemap(
				domain.gasState.network, inventories, availableVolumes, mixedEnergy, inheritedAvailable, sourceEnergy, sinkEnergy);
		ArrayList<AirEdge> edges = new ArrayList<>(result.geometricEdges().size());
		for (AirCavityScanner.GeometricEdge edge : result.geometricEdges()) {
			edges.add(new AirEdge(edge.firstNode(), edge.secondNode(), edge.areaSquareMeters(), 0.65));
		}
		AirNetwork.RemapPlan gasPlan = domain.gasState.network.planRemapNodes(
				availableVolumes, inventories, sourceDelta, sinkDelta, edges);
		return new GasTopologyPlan(result, gasPlan, thermalPlan);
	}

	/**
	 * Reports which previous nodes still own at least one open cell in the new topology.
	 *
	 * <p>A previous node that still owns an open cell is matched by membership overlap, so only a
	 * node reduced entirely to interface residuals may hand its inventory to a neighbouring new
	 * component. That keeps a transient marker void in the liquid bulk from being treated as a
	 * descendant of the headspace; a new cavity with no old owner is an explicit vacuum node.</p>
	 */
	private boolean[] previousNodesOwningAirCells(int[] newMembership, double[] cellAvailableVolume) {
		boolean[] owns = new boolean[domain.gasState.previousSealedNodeCount + 1];
		for (int cell = 0; cell < domain.cellCount; cell++) {
			if (newMembership[cell] < 0 || !(cellAvailableVolume[cell] > 0.0)) continue;
			int previous = domain.rawGasNodeOfCell(cell);
			if (previous >= 1 && previous <= domain.gasState.previousSealedNodeCount) owns[previous] = true;
		}
		return owns;
	}

	/** Adds each old lineage touching a newly full-air child in one bounded cell pass. */
	private void addAdjacentPreviousGasOverlaps(
			int[] newMembership, boolean[] newNodeHasMembershipOverlap, double[] availableVolumes,
			TreeMap<Long, Long> overlap, boolean[] hasMembershipOverlap, boolean[] previousOwnsAirCell
	) {
		TreeSet<Long> adjacentPairs = new TreeSet<>();
		for (int cell = 0; cell < domain.cellCount; cell++) {
			int newNode = newMembership[cell];
			if (newNode < 1 || newNodeHasMembershipOverlap[newNode]) continue;
			int sameCellOwner = domain.gasState.cellLineageNodeId[cell];
			if (sameCellOwner > 0 && !previousOwnsAirCell[sameCellOwner]) {
				adjacentPairs.add(gasOverlapKey(sameCellOwner, newNode));
			}
			int x = cell % domain.sizeX;
			int yz = cell / domain.sizeX;
			int y = yz % domain.sizeY;
			int z = yz / domain.sizeY;
			for (int axis = 0; axis < 3; axis++) for (int direction = -1; direction <= 1; direction += 2) {
				int nx = x + (axis == 0 ? direction : 0);
				int ny = y + (axis == 1 ? direction : 0);
				int nz = z + (axis == 2 ? direction : 0);
				if (nx < 0 || nx >= domain.sizeX || ny < 0 || ny >= domain.sizeY || nz < 0 || nz >= domain.sizeZ) continue;
				if (domain.openFaceFromCell(cell, nx - x, ny - y, nz - z) <= 0.0F) continue;
				int adjacentOwner = domain.gasState.cellLineageNodeId[domain.cellIndex(nx, ny, nz)];
				if (adjacentOwner > 0 && !previousOwnsAirCell[adjacentOwner]) {
					adjacentPairs.add(gasOverlapKey(adjacentOwner, newNode));
				}
			}
		}
		for (long key : adjacentPairs) {
			int old = (int) (key >>> 32);
			int newNode = (int) key;
			long weight = Math.max(1L, AirNetwork.nanos(availableVolumes[newNode - 1]));
			overlap.merge(key, weight, Math::addExact);
			hasMembershipOverlap[old] = true;
			newNodeHasMembershipOverlap[newNode] = true;
		}
	}

	/** Returns every fully vanished old child in one pass over its previous interface cells. */
	private void addDisappearingPreviousGasOverlaps(
			boolean[] hasMembershipOverlap, int[] newMembership, int[] newLabels,
			double[] cellAvailableVolume, TreeMap<Long, Long> overlap
	) {
		for (int cell = 0; cell < domain.cellCount; cell++) {
			int old = domain.gasState.cellLineageNodeId[cell];
			if (old < 1 || hasMembershipOverlap[old]) continue;
			addGasOverlapDestination(old, newMembership[cell] >= 0 ? newMembership[cell] : newLabels[cell],
					cellAvailableVolume[cell], overlap);
			int x = cell % domain.sizeX;
			int yz = cell / domain.sizeX;
			int y = yz % domain.sizeY;
			int z = yz / domain.sizeY;
			for (int axis = 0; axis < 3; axis++) for (int direction = -1; direction <= 1; direction += 2) {
				int nx = x + (axis == 0 ? direction : 0);
				int ny = y + (axis == 1 ? direction : 0);
				int nz = z + (axis == 2 ? direction : 0);
				if (nx < 0 || nx >= domain.sizeX || ny < 0 || ny >= domain.sizeY || nz < 0 || nz >= domain.sizeZ) continue;
				if (domain.openFaceFromCell(cell, nx - x, ny - y, nz - z) <= 0.0F) continue;
				int neighbor = domain.cellIndex(nx, ny, nz);
				int now = newMembership[neighbor] >= 0 ? newMembership[neighbor] : newLabels[neighbor];
				addGasOverlapDestination(old, now, cellAvailableVolume[neighbor], overlap);
			}
		}
	}

	/**
	 * Hands inventory that lost every topological destination to the cavities that still exist.
	 *
	 * <p>A sealed shell cannot lose gas. When a node's cells end up carrying no air volume at all -
	 * a pocket the carrier closed over, or a headspace fragment it crushed - the scan gives it no
	 * destination, but `normalizeClosedCapacity` has already reassigned its capacity to the cavities
	 * that remain. The inventory travels with that capacity, weighted the same way, instead of being
	 * reported as lost.</p>
	 *
	 * <p>This is deliberately not a judgement about what the node used to be. A bubble pinched off by
	 * the carrier, a headspace split by a rising surface and a carrier void that flickered shut are
	 * all the same event here: gas in a closed volume whose cavity is gone. Only conservation decides
	 * where it goes.</p>
	 */
	private void retainClosedOrphans(
			TreeMap<Long, Long> overlap, int previous, int sealed, double[] availableVolumes
	) {
		boolean[] orphaned = new boolean[previous + 1];
		for (int old = 1; old <= previous; old++) {
			orphaned[old] = !domain.gasState.network.inventory(old).isEmpty()
					&& overlap.subMap((long) old << 32, (long) (old + 1) << 32).isEmpty();
		}
		for (int old = 1; old <= previous; old++) {
			if (!orphaned[old]) continue;
			for (int node = 1; node <= sealed; node++) {
				overlap.merge(gasOverlapKey(old, node),
						Math.max(1L, AirNetwork.nanos(availableVolumes[node - 1])), Math::addExact);
			}
		}
	}

	private static void addGasOverlapDestination(
			int old, int now, double availableVolume, TreeMap<Long, Long> overlap
	) {
		if (now < 0 || !(availableVolume > 0.0)) return;
		long key = gasOverlapKey(old, now);
		long weight = Math.max(1L, AirNetwork.nanos(availableVolume));
		overlap.merge(key, weight, Math::addExact);
	}

	private static double[] partitionTopologyVolume(double total, long[] weights) {
		long weightSum = 0L;
		for (long weight : weights) weightSum = Math.addExact(weightSum, weight);
		if (!(Double.isFinite(total) && total > 0.0) || weightSum <= 0L) {
			throw new IllegalArgumentException("Gas topology volume partition is invalid");
		}
		double[] result = new double[weights.length];
		double assigned = 0.0;
		for (int index = 0; index < weights.length; index++) {
			result[index] = index == weights.length - 1 ? total - assigned
					: total * ((double) weights[index] / weightSum);
			assigned += result[index];
		}
		return result;
	}

	private static long gasOverlapKey(int old, int now) {
		return ((long) old << 32) | (now & 0xffffffffL);
	}

	private String gasTopologyLossDetail(
			int old, int[] newMembership, int[] newLabels, double[] cellAvailableVolume, int sealed
	) {
		int oldMembers = 0, oldLineage = 0, becameLiquid = 0, becameAmbient = 0;
		int lineageLiquid = 0, lineageAmbient = 0, lineageSealed = 0;
		double lineageAvailable = 0.0;
		for (int cell = 0; cell < domain.cellCount; cell++) {
			if (domain.gasState.cellNodeId[cell] == old) {
				oldMembers++;
				if (newMembership[cell] < 0) becameLiquid++;
				else if (newMembership[cell] == AirNetwork.AMBIENT_NODE) becameAmbient++;
			}
			if (domain.gasState.cellLineageNodeId[cell] != old) continue;
			oldLineage++;
			lineageAvailable += cellAvailableVolume[cell];
			if (newLabels[cell] < 0) lineageLiquid++;
			else if (newLabels[cell] == AirNetwork.AMBIENT_NODE) lineageAmbient++;
			else lineageSealed++;
		}
		return "old=" + old + ", previousNodes=" + domain.gasState.previousSealedNodeCount + ", newNodes=" + sealed
				+ ", oldMembers=" + oldMembers + ", oldLineage=" + oldLineage
				+ ", memberLiquid=" + becameLiquid + ", memberAmbient=" + becameAmbient
				+ ", lineageLiquid=" + lineageLiquid + ", lineageAmbient=" + lineageAmbient
				+ ", lineageSealed=" + lineageSealed + ", lineageAvailable=" + lineageAvailable
				+ ", inventoryNanos=" + domain.gasState.network.inventory(old).standardVolumeNanos();
	}

	/** Publishes a fully planned topology; all fallible physics and ledger work happened above. */
	/**
	 * Verifies the planned topology against a fresh geometry scan without mutating anything.
	 * The scan inputs are the solid geometry only, which neither the condensed nor the granular
	 * clear changes, so a diverging fresh scan means the plan is stale and the whole operator
	 * clear must be rejected before any owner mutates.
	 */
	void preflight(GasTopologyPlan plan) {
		AirCavityScanner.Result fresh = domain.gasTopologyCoordinator.scanner.scan(
				domain.geometry.solid, new int[domain.cellCount], new double[domain.cellCount],
				domain.geometry.cutCells, null, domain.gasState.sealedDomainBoundary);
		if (fresh.sealedNodeCount() != plan.scan().sealedNodeCount()
				|| !Arrays.equals(fresh.cellNodeId(), plan.scan().cellNodeId())
				|| !Arrays.equals(fresh.cellLineageNodeId(), plan.scan().cellLineageNodeId())) {
			throw new IllegalStateException("Gas topology changed after operator clear planning");
		}
	}

	void commit(GasTopologyPlan plan) {
		if (plan.thermalPlan() != null) domain.thermalState.commitGasRemap(plan.thermalPlan());
		domain.gasState.network.commitRemapNodes(plan.gasPlan());
		AirCavityScanner.Result result = plan.scan();
		int sealed = result.sealedNodeCount();
		domain.gasState.cellNodeId = result.cellNodeId();
		domain.gasState.cellLineageNodeId = result.cellLineageNodeId();
		domain.gasState.previousSealedNodeCount = sealed;
		domain.gasState.pressureActive = sealed > 0;
	}

	/**
	 * Refreshes the gas network from the current grid: rescans cavity topology, transfers
	 * sealed inventories onto the new slots (majority mapping, proportional split with exact
	 * remainder on a genuine pinch), refreshes available volumes and geometric orifice edges,
	 * then advances finite-rate orifice transport. New cavities start at ambient equilibrium;
	 * a cavity that opens to ambient or vanishes under liquid vents through the remap ledger.
	 */
	void step(float dt) {
		refresh();
		if (!domain.gasState.pressureActive) return;
		if (domain.thermalState == null || !domain.thermalState.gasModelBound()) domain.gasState.network.stepEdges(dt);
		else domain.gasState.network.stepEdges(dt, new AirNetwork.NodeThermodynamics() {
			@Override public double gaugePressure(int nodeId) {
				return nodeId == AirNetwork.AMBIENT_NODE ? 0.0
						: domain.thermalState.gasGaugePressure(nodeId, domain.gasState.network);
			}
			@Override public double density(int nodeId) {
				return nodeId == AirNetwork.AMBIENT_NODE ? AirNetwork.AIR_DENSITY
						: domain.thermalState.gasDensity(nodeId, domain.gasState.network);
			}
		}, (source, target, moved, sourceAfter, targetAfter) -> domain.thermalState.planGasTransfer(
				source, target, moved, sourceAfter, targetAfter, domain.gasState.network));
	}

	/**
	 * Brings the derived gas topology to the same carrier geometry that a checkpoint will encode.
	 * Marker advection and end-of-step reseeding may change the scanner topology after the last
	 * mechanical gas update. Persistence must either commit this jointly planned remap or keep the
	 * previous HEAD; it may never publish current markers beside a stale gas-node membership table.
	 */
	void synchronizeForPersistence() {
		if (domain.gasState.operatorVentedTopology) return;
		domain.ensureGrid();
		refresh();
	}

	record GasTopologyPlan(
			AirCavityScanner.Result scan,
			AirNetwork.RemapPlan gasPlan,
			GasEnergyStore.GasRemapPlan thermalPlan
	) { }
}

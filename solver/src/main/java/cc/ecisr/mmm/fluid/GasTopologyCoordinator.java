package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.thermal.GasEnergyStore;
import cc.ecisr.mmm.thermal.GasThermalModel;
import cc.ecisr.mmm.thermal.ThermalDomainState;
import cc.ecisr.mmm.units.SignedDyadic;

import cc.ecisr.mmm.fluid.air.AirEdge;
import cc.ecisr.mmm.fluid.air.AirNetwork;
import cc.ecisr.mmm.fluid.air.GasInventory;
import cc.ecisr.mmm.fluid.air.GasAuditLedger;
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
	private final FluidDomain domain;
	private final AirCavityScanner scanner;
	private int[] phaseCounts;
	private double[] phaseVolumes;
	private long phasePreviewRevision = -1;
	private AirNetwork phasePreviewNetwork;

	private record PhaseEdit(int node, AirNetwork.PhaseMutationPlan gas, double energy,
			ThermalDomainState.PhaseCellEnergyPlan thermal) { }

	GasTopologyCoordinator(FluidDomain domain) {
		this.domain = domain;
		this.scanner = new AirCavityScanner(domain.sizeX, domain.sizeY, domain.sizeZ,
				domain.config.cellSize(), domain.cellCount);
	}

	/** Initializes gas topology from current carrier geometry and books sealed ambient source once. */
	GasStateSnapshot bootstrap(String chemicalRegistryFingerprint) {
		domain.ensureInventoryGrid();
		refresh();
		return export(chemicalRegistryFingerprint);
	}

	GasStateSnapshot export(String chemicalRegistryFingerprint) {
		if (domain.gasState.cellNodeId == null) {
			throw new IllegalStateException("Gas topology has not been explicitly initialized");
		}
		if (!domain.gasState.network.auditClosed()) throw new IllegalStateException("Gas component audit does not close");
		return GasStateSnapshot.capture(domain.sizeX, domain.sizeY, domain.sizeZ, domain.gasState.cellNodeId,
				domain.gasState.cellLineageNodeId, domain.gasState.network,
				chemicalRegistryFingerprint == null ? "" : chemicalRegistryFingerprint);
	}

	/** Restores complete gas truth after its matching liquid/material state. */
	void restore(GasStateSnapshot state, GasRegistry registry) {
		restore(state, registry, null);
	}

	void restore(GasStateSnapshot state, GasRegistry registry, FlipThermalParticleState thermal) {
		if (state == null || registry == null || state.sizeX() != domain.sizeX
				|| state.sizeY() != domain.sizeY || state.sizeZ() != domain.sizeZ
				|| !state.registryFingerprint().equals(registry.fingerprint())) {
			throw new IllegalArgumentException("Gas restore identity/dimensions mismatch");
		}
		if (domain.gasState.cellNodeId != null || domain.gasState.network.nodeCount() != 0) {
			throw new IllegalStateException("Gas restore requires an uninitialized topology");
		}
		domain.ensureInventoryGrid();
		var occupancy = domain.pressureGranularCoupling;
		if (thermal != null && !thermal.coherentGroups().isEmpty()) {
			thermal.requireCompatible(domain.sizeX, domain.sizeY, domain.sizeZ, domain.config.cellSize(), domain.particles.count());
			domain.validateCoherentRestoreCapacity(thermal.coherentGroups());
			// Thermal gas energies need the restored network, while gas geometry needs
			// coherent inventory. Derive geometry from the complete incoming snapshot first.
			occupancy = new cc.ecisr.mmm.granular.GranularCouplingFields(
					domain.sizeX, domain.sizeY, domain.sizeZ, domain.config.cellSize());
			long[] fixed = new long[domain.cellCount];
			for (var group : thermal.coherentGroups()) fixed[group.cell()] = Math.addExact(fixed[group.cell()], group.occupiedVolumeSvq());
			occupancy.setFixedCellSvq(fixed);
			if (domain.granularState != null) occupancy.rebuild(domain.granularState.parcels, domain.granularState.deposits);
		}
		AirCavityScanner.Result result = domain.gasState.sealedDomainBoundary
				? scanner.scanRetainingClosedLineages(domain.geometry.solid, domain.markerGrid.markerCount,
						domain.markerGrid.exactCellMarkerVolume, domain.geometry.cutCells,
						occupancy, state.cellLineageOrdinal(), state.nodes().size())
				: scanner.scan(domain.geometry.solid, domain.markerGrid.markerCount,
						domain.markerGrid.exactCellMarkerVolume, domain.geometry.cutCells,
						occupancy, false);
		int[] expected = state.cellNodeOrdinal(), actual = result.cellNodeId();
		if (result.sealedNodeCount() != state.nodes().size()) {
			throw new IllegalArgumentException("Restored gas sealed-node count does not match derived topology");
		}
		for (int cell = 0; cell < domain.cellCount; cell++) {
			int derived = actual[cell] > 0 ? actual[cell] : 0;
			if (expected[cell] != derived) throw new IllegalArgumentException(
					"Restored gas topology differs from committed membership at cell " + cell);
		}
		for (int node = 0; node < state.nodes().size(); node++) {
			int minimum = Integer.MAX_VALUE;
			for (int cell = 0; cell < expected.length; cell++) if (expected[cell] == node + 1) minimum = Math.min(minimum, cell);
			if (minimum != state.nodes().get(node).topologyKey()) {
				throw new IllegalArgumentException("Restored gas topology key mismatch");
			}
		}
		AirNetwork restored = new AirNetwork(registry);
		restored.restoreNodes(result.sealedAvailableVolume(),
				state.nodes().stream().map(GasStateSnapshot.Node::inventory).toList(),
				state.externalSource(), state.escapedSink(), state.massRoundoff(), state.residualRoundoff());
		for (AirCavityScanner.GeometricEdge edge : result.geometricEdges()) {
			restored.addOrMergeEdge(edge.firstNode(), edge.secondNode(), edge.areaSquareMeters(), 0.65);
		}
		domain.gasState.network = restored;
		domain.gasState.cellNodeId = actual;
		domain.gasState.cellLineageNodeId = result.cellLineageNodeId();
		domain.gasState.previousSealedNodeCount = result.sealedNodeCount();
		domain.gasState.pressureActive = domain.gasState.previousSealedNodeCount > 0;
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
		commit(plan(result, false));
	}

	/** Remaps phase-edited membership while leaving its pending {@code -p dV} for the next projection. */
	void refreshDeferringPhaseVolumeWork() {
		AirCavityScanner.Result result = domain.gasState.sealedDomainBoundary && domain.gasState.cellLineageNodeId != null
				? scanner.scanRetainingClosedLineages(domain.geometry.solid, domain.markerGrid.markerCount,
						domain.markerGrid.exactCellMarkerVolume, domain.geometry.cutCells,
						domain.pressureGranularCoupling, domain.gasState.cellLineageNodeId,
						domain.gasState.previousSealedNodeCount)
				: scanner.scan(domain.geometry.solid, domain.markerGrid.markerCount,
						domain.markerGrid.exactCellMarkerVolume, domain.geometry.cutCells,
						domain.pressureGranularCoupling, domain.gasState.sealedDomainBoundary);
		commit(plan(result, true));
	}

	/** Plans the complete topology successor using one-cell geometry and node-column overlays. */
	GasTopologyPlan planAfterPhase(int cell, int markerCount, double markerVolume, int node,
			AirNetwork.PhaseMutationPlan gas, double energy, ThermalDomainState.PhaseCellEnergyPlan thermal) {
		domain.gasState.network.requirePhaseMutationCurrent(gas);
		domain.thermalState.requirePhaseCellEnergyCurrent(thermal);
		if (phaseCounts == null) { phaseCounts = new int[domain.cellCount]; phaseVolumes = new double[domain.cellCount]; }
		if (phasePreviewNetwork != domain.gasState.network || phasePreviewRevision != domain.gasState.network.revision()) {
			phasePreviewNetwork = domain.gasState.network;
			System.arraycopy(domain.markerGrid.markerCount, 0, phaseCounts, 0, phaseCounts.length);
			System.arraycopy(domain.markerGrid.exactCellMarkerVolume, 0, phaseVolumes, 0, phaseVolumes.length);
			phasePreviewRevision = domain.gasState.network.revision();
		}
		int originalCount = phaseCounts[cell]; double originalVolume = phaseVolumes[cell];
		phaseCounts[cell] = markerCount; phaseVolumes[cell] = markerVolume;
		try {
			AirCavityScanner.Result result = domain.gasState.sealedDomainBoundary && domain.gasState.cellLineageNodeId != null
					? scanner.scanRetainingClosedLineages(domain.geometry.solid, phaseCounts, phaseVolumes,
							domain.geometry.cutCells, domain.pressureGranularCoupling, domain.gasState.cellLineageNodeId,
							domain.gasState.previousSealedNodeCount)
					: scanner.scan(domain.geometry.solid, phaseCounts, phaseVolumes, domain.geometry.cutCells,
							domain.pressureGranularCoupling, domain.gasState.sealedDomainBoundary);
			return plan(result, true, new PhaseEdit(node, gas, energy, thermal));
		} catch (IllegalArgumentException | ArithmeticException | GasTopologyBlockedException unavailable) {
			throw new PhaseChangeStageSupport.PhaseCandidateUnavailableException(
					"Phase-edited gas topology has no complete representable remap", unavailable);
		} finally {
			phaseCounts[cell] = originalCount; phaseVolumes[cell] = originalVolume;
		}
	}

	void requireAfterPhaseCurrent(GasTopologyPlan plan, AirNetwork.PhaseMutationPlan gas,
			ThermalDomainState.PhaseCellEnergyPlan thermal) {
		domain.gasState.network.requireRemapAfterPhaseCurrent(plan.gasPlan(), gas);
		domain.thermalState.requireGasRemapAfterPhaseCurrent(plan.thermalPlan(), thermal);
	}

	private GasInventory inventory(int node, PhaseEdit edit) {
		return edit != null && edit.node() == node ? edit.gas().inventoryAfter() : domain.gasState.network.inventory(node);
	}

	private double energy(int node, PhaseEdit edit) {
		return edit != null && edit.node() == node ? edit.energy() : domain.thermalState.gasNodeEnergy(node, domain.gasState.network);
	}

	private double[] partitionEnergy(int node, List<GasInventory> parts, PhaseEdit edit) {
		return edit != null && edit.node() == node
				? new GasThermalModel(domain.thermalState.registry(), domain.gasState.network.registry())
						.partitionEnergy(inventory(node, edit), edit.energy(), parts)
				: domain.thermalState.partitionGasNodeEnergy(node, parts, domain.gasState.network);
	}

	/** Plans one topology candidate against the current carrier geometry without committing it. */
	GasTopologyPlan planFromFreshScan() {
		return plan(scanner.scan(domain.geometry.solid, new int[domain.cellCount], new double[domain.cellCount],
				domain.geometry.cutCells, null, domain.gasState.sealedDomainBoundary), false);
	}

	/** Builds the complete gas/energy topology candidate before any owner is mutated. */
	GasTopologyPlan plan(AirCavityScanner.Result result, boolean deferPhaseVolumeWork) {
		return plan(result, deferPhaseVolumeWork, null);
	}

	private GasTopologyPlan plan(AirCavityScanner.Result result, boolean deferPhaseVolumeWork, PhaseEdit edit) {
		GasTopologyPlan single = planSurvivingSingleCavity(result, deferPhaseVolumeWork, edit);
		return single == null ? planWithOverlaps(result, deferPhaseVolumeWork, edit) : single;
	}

	private GasTopologyPlan planSurvivingSingleCavity(AirCavityScanner.Result result, boolean deferPhaseVolumeWork, PhaseEdit edit) {
		if (!domain.gasState.sealedDomainBoundary || domain.gasState.cellNodeId == null
				|| domain.gasState.previousSealedNodeCount != 1 || result.sealedNodeCount() != 1
				|| result.stopReason() != AirCavityScanner.StopReason.COMPLETED) return null;
		boolean overlap = false; double vacuumVolume = 0;
		for (int c = 0; c < domain.cellCount; c++) {
			int membership = result.cellNodeId()[c]; double volume = result.cellAvailableVolume()[c];
			if (volume <= 0) continue;
			if (domain.gasState.cellNodeId[c] == 1) {
				if (membership == 0) return null;
				overlap |= membership == 1;
			}
			int destination = membership >= 0 ? membership : result.cellLineageNodeId()[c];
			if (domain.rawGasNodeOfCell(c) < 0 && destination == 1) vacuumVolume += volume;
		}
		if (!overlap) return null;
		boolean thermal = domain.thermalState != null && domain.thermalState.gasModelBound();
		double[] volumes = result.sealedAvailableVolume();
		double[] mixed = thermal ? new double[] {energy(1, edit)} : null;
		double[] inherited = thermal ? new double[] {domain.gasState.network.availableVolume(1)} : null;
		if (thermal) {
			double added = Math.max(0, volumes[0] - inherited[0]);
			if (vacuumVolume > added) vacuumVolume *= added / vacuumVolume;
			if (vacuumVolume > 0) inherited[0] += vacuumVolume;
			if (deferPhaseVolumeWork) inherited[0] = volumes[0];
		}
		return prepareRemap(result, List.of(inventory(1, edit)), volumes,
				mixed, inherited, GasAuditLedger.EMPTY, GasAuditLedger.EMPTY, SignedDyadic.ZERO, SignedDyadic.ZERO, edit);
	}

	private GasTopologyPlan planWithOverlaps(AirCavityScanner.Result result, boolean deferPhaseVolumeWork, PhaseEdit edit) {
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
		GasAuditLedger sourceDelta = GasAuditLedger.EMPTY;
		GasAuditLedger sinkDelta = GasAuditLedger.EMPTY;
		SignedDyadic[] mixedEnergy = thermalGas ? new SignedDyadic[sealed] : null;
		if (thermalGas) Arrays.fill(mixedEnergy, SignedDyadic.ZERO);
		double[] inheritedAvailable = thermalGas ? new double[sealed] : null;
		SignedDyadic sourceEnergy = SignedDyadic.ZERO, sinkEnergy = SignedDyadic.ZERO;
		if (!initialBootstrap && sealed > 0) {
			int previous = domain.gasState.previousSealedNodeCount;
			TreeMap<Long, Long> overlap = new TreeMap<>();
			boolean[] hasMembershipOverlap = new boolean[previous + 1];
			boolean[] newNodeHasMembershipOverlap = new boolean[sealed + 1];
			int[] newMembership = result.cellNodeId();
			int[] newLabels = result.cellLineageNodeId();
			double[] cellAvailableVolume = result.cellAvailableVolume();
			double[] ambientInheritedAvailable = new double[sealed + 1];
			double[] vacuumInheritedAvailable = new double[sealed + 1];
			for (int cell = 0; cell < domain.cellCount; cell++) {
				int oldMembership = domain.gasState.cellNodeId[cell];
				int oldLineage = domain.rawGasNodeOfCell(cell);
				int ambientDestination = newMembership[cell] >= 0 ? newMembership[cell] : newLabels[cell];
				if (!domain.gasState.sealedDomainBoundary && oldLineage == AirNetwork.AMBIENT_NODE
						&& ambientDestination > 0 && cellAvailableVolume[cell] > 0.0) {
					ambientInheritedAvailable[ambientDestination] += cellAvailableVolume[cell];
				}
				if (oldLineage < 0 && ambientDestination > 0 && cellAvailableVolume[cell] > 0.0) {
					vacuumInheritedAvailable[ambientDestination] += cellAvailableVolume[cell];
				}
				if (oldMembership < 1) continue;
				int now = newMembership[cell];
				if (now < 0 || !(cellAvailableVolume[cell] > 0.0)) continue;
				long key = ((long) oldMembership << 32) | (now & 0xffffffffL);
				long weight = Math.max(1L, AirNetwork.nanos(cellAvailableVolume[cell]));
				overlap.merge(key, weight, Math::addExact);
				hasMembershipOverlap[oldMembership] = true;
				if (now > 0) newNodeHasMembershipOverlap[now] = true;
			}
			if (domain.gasState.sealedDomainBoundary) {
				// Sealed node capacities already account for the entire global complement,
				// including marker clustering. Newly visible local voids may only inherit
				// capacity added beyond those old control volumes; otherwise remapping
				// invents an old volume and charges fictitious compression work each step.
				double oldCapacity = 0, newCapacity = 0, unownedCapacity = 0;
				for (int node = 1; node <= previous; node++) oldCapacity += domain.gasState.network.availableVolume(node);
				for (double volume : availableVolumes) newCapacity += volume;
				for (double volume : vacuumInheritedAvailable) unownedCapacity += volume;
				double addedCapacity = Math.max(0, newCapacity - oldCapacity);
				if (unownedCapacity > addedCapacity) {
					double scale = addedCapacity / unownedCapacity;
					for (int node = 1; node <= sealed; node++) vacuumInheritedAvailable[node] *= scale;
				}
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
			if (domain.gasState.sealedDomainBoundary) retainClosedOrphans(overlap, previous, sealed,
				availableVolumes, newMembership, newLabels, cellAvailableVolume, edit);
			boolean[] contributed = new boolean[sealed + 1];
			for (int old = 1; old <= previous; old++) {
				GasInventory inventory = inventory(old, edit);
				Map<Long, Long> oldOverlap = overlap.subMap(
						(long) old << 32, (long) (old + 1) << 32);
				int destinations = oldOverlap.size();
				if (destinations == 0) {
					// A vacuum cavity crushed to zero volume has no matter, U or surviving
					// control volume to transfer. A vacuum with a destination is handled below
					// so its inherited volume still distinguishes free expansion from work.
					if (inventory.isEmpty()) continue;
					if (domain.gasState.sealedDomainBoundary) {
						throw new GasTopologyBlockedException("Closed gas inventory lost its topology lineage: "
								+ gasTopologyLossDetail(old, newMembership, newLabels, cellAvailableVolume, sealed));
					}
					sinkDelta = sinkDelta.add(inventory);
					if (thermalGas) sinkEnergy = sinkEnergy.add(SignedDyadic.of(energy(old, edit)));
					continue;
				}
				long[] weights = new long[destinations];
				int weight = 0;
				for (long available : oldOverlap.values()) weights[weight++] = available;
				List<GasInventory> parts = inventory.partition(weights);
				double[] partEnergy = !thermalGas ? null : inventory.isEmpty() ? new double[destinations]
						: partitionEnergy(old, parts, edit);
				double[] partAvailable = !thermalGas ? null
						: partitionTopologyVolume(domain.gasState.network.availableVolume(old), weights);
				int part = 0;
				for (Map.Entry<Long, Long> entry : oldOverlap.entrySet()) {
					int node = (int) (entry.getKey() & 0xffffffffL);
					if (node == AirNetwork.AMBIENT_NODE) {
						sinkDelta = sinkDelta.add(parts.get(part));
						if (thermalGas) sinkEnergy = sinkEnergy.add(SignedDyadic.of(partEnergy[part]));
					} else {
						inventories.set(node - 1, inventories.get(node - 1).merge(parts.get(part)));
						if (thermalGas) {
							// A zero-inventory node is still an already existing vacuum control volume.
							// Carrying its old volume prevents a later merge from being mistaken for
							// reversible boundary expansion and creating fictitious cooling/work.
							mixedEnergy[node - 1] = mixedEnergy[node - 1].add(SignedDyadic.of(partEnergy[part]));
							inheritedAvailable[node - 1] += partAvailable[part];
						}
						contributed[node] = true;
					}
					part++;
				}
			}
			for (int node = 1; node <= sealed; node++) {
				if (!(vacuumInheritedAvailable[node] > 0.0)) continue;
				if (thermalGas) inheritedAvailable[node - 1] += vacuumInheritedAvailable[node];
				contributed[node] = true;
			}
			for (int node = 1; node <= sealed; node++) {
				if (!(ambientInheritedAvailable[node] > 0.0)) continue;
				GasInventory trappedAmbient = domain.gasState.network.ambientInventory(
						Math.max(1L, AirNetwork.nanos(ambientInheritedAvailable[node])));
				inventories.set(node - 1, inventories.get(node - 1).merge(trappedAmbient));
				sourceDelta = sourceDelta.add(trappedAmbient);
				if (thermalGas) {
					double trappedEnergy = domain.thermalState.ambientGasEnergy(trappedAmbient);
					mixedEnergy[node - 1] = mixedEnergy[node - 1].add(SignedDyadic.of(trappedEnergy));
					inheritedAvailable[node - 1] += ambientInheritedAvailable[node];
					sourceEnergy = sourceEnergy.add(SignedDyadic.of(trappedEnergy));
				}
				contributed[node] = true;
			}
			for (int node = 1; node <= sealed; node++) {
				if (!contributed[node]) {
					// After bootstrap, a new component with neither sealed nor ambient
					// lineage is a genuine vacuum cavity. Its current control volume is
					// inherited without matter, U or an external source.
					if (thermalGas) inheritedAvailable[node - 1] = availableVolumes[node - 1];
				}
			}
		} else {
			if (domain.gasState.previousSealedNodeCount > 0) {
				for (int node = 1; node <= domain.gasState.previousSealedNodeCount; node++) {
					if (domain.gasState.sealedDomainBoundary && !inventory(node, edit).isEmpty()) {
						throw new GasTopologyBlockedException("Closed gas inventory has no available cavity volume");
					}
					sinkDelta = sinkDelta.add(inventory(node, edit));
					if (thermalGas) sinkEnergy = sinkEnergy.add(SignedDyadic.of(energy(node, edit)));
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
				sourceDelta = sourceDelta.add(created);
				if (thermalGas) {
					mixedEnergy[node - 1] = SignedDyadic.of(domain.thermalState.ambientGasEnergy(created));
					inheritedAvailable[node - 1] = availableVolumes[node - 1];
					sourceEnergy = sourceEnergy.add(mixedEnergy[node - 1]);
				}
			}
		}
		if (domain.gasState.sealedDomainBoundary && !initialBootstrap
				&& (!sourceDelta.isEmpty() || !sinkDelta.isEmpty())) {
			throw new GasTopologyBlockedException("Closed gas topology attempted an external source or sink");
		}
		if (thermalGas && deferPhaseVolumeWork) {
			// The phase commit already recorded the exact cell dV. Preserve its post-transfer node U
			// across this topology-only remap; the coupled fixed-volume phase update already conserved total U.
			System.arraycopy(availableVolumes, 0, inheritedAvailable, 0, availableVolumes.length);
		}
		return prepareRemap(result, inventories, availableVolumes, doubles(mixedEnergy), inheritedAvailable,
				sourceDelta, sinkDelta, sourceEnergy, sinkEnergy, edit);
	}

	private GasTopologyPlan prepareRemap(AirCavityScanner.Result result, List<GasInventory> inventories,
			double[] availableVolumes, double[] mixedEnergy, double[] inheritedAvailable,
			GasAuditLedger sourceDelta, GasAuditLedger sinkDelta, SignedDyadic sourceEnergy, SignedDyadic sinkEnergy, PhaseEdit edit) {
		GasEnergyStore.GasRemapPlan thermalPlan = mixedEnergy == null ? null : edit == null
				? domain.thermalState.planGasRemap(domain.gasState.network, inventories, availableVolumes,
						mixedEnergy, inheritedAvailable, sourceEnergy, sinkEnergy)
				: domain.thermalState.planGasRemapAfterPhase(edit.thermal(), edit.node(), edit.energy(),
						domain.gasState.network, inventories, availableVolumes, mixedEnergy, inheritedAvailable, sourceEnergy, sinkEnergy);
		ArrayList<AirEdge> edges = new ArrayList<>(result.geometricEdges().size());
		for (AirCavityScanner.GeometricEdge edge : result.geometricEdges()) {
			edges.add(new AirEdge(edge.firstNode(), edge.secondNode(), edge.areaSquareMeters(), 0.65));
		}
		AirNetwork.RemapPlan gasPlan = edit == null
				? domain.gasState.network.planRemapNodes(availableVolumes, inventories, sourceDelta, sinkDelta, edges)
				: domain.gasState.network.planRemapAfterPhase(edit.gas(), availableVolumes, inventories, sourceDelta, sinkDelta, edges);
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

	private static double[] doubles(SignedDyadic[] values) {
		if (values == null) return null;
		double[] result = new double[values.length];
		for (int i = 0; i < values.length; i++) result[i] = values[i].toDouble();
		return result;
	}

	/**
	 * Hands inventory that lost every topological destination to a cavity it can actually reach.
	 *
	 * <p>A sealed shell cannot lose gas. When a node's cells end up carrying no air volume at all -
	 * a pocket the carrier closed over, or a headspace fragment it crushed - the scan gives it no
	 * destination, but `normalizeClosedCapacity` has already settled its capacity onto the cavities
	 * that remain. The inventory travels with that capacity instead of being reported as lost.</p>
	 *
	 * <p>This is deliberately not a judgement about what the node used to be. A bubble pinched off by
	 * the carrier, a headspace split by a rising surface and a carrier void that flickered shut are
	 * all the same event here: gas in a closed volume whose cavity is gone. Where it goes is decided
	 * by reachability first and by conservation only when nothing is reachable.</p>
	 */

	private void retainClosedOrphans(
			TreeMap<Long, Long> overlap, int previous, int sealed, double[] availableVolumes,
			int[] newMembership, int[] newLabels, double[] cellAvailableVolume, PhaseEdit edit
	) {
		boolean anyCapacity = false;
		for (int node = 1; node <= sealed; node++) anyCapacity |= availableVolumes[node - 1] > 0.0;
		boolean[] orphaned = new boolean[previous + 1];
		for (int old = 1; old <= previous; old++) {
			if (inventory(old, edit).isEmpty()) continue;
			// Finite gas cannot stay in a cavity whose control volume is already gone, so a crushed
			// destination is not a destination. Stranding the inventory there would make the next
			// thermal remap a singular node instead of moving the gas the shell still owns.
			overlap.subMap((long) old << 32, (long) (old + 1) << 32).keySet()
					.removeIf(key -> !cavityHasVolume(key.longValue(), availableVolumes));
			orphaned[old] = overlap.subMap((long) old << 32, (long) (old + 1) << 32).isEmpty();
		}
		for (int old = 1; old <= previous; old++) {
			if (!orphaned[old]) continue;
			Map<Integer, Long> reachable = nearestReachableCavities(
					old, newMembership, newLabels, cellAvailableVolume, availableVolumes);
			if (!reachable.isEmpty()) {
				for (Map.Entry<Integer, Long> destination : reachable.entrySet()) {
					overlap.merge(gasOverlapKey(old, destination.getKey()),
							destination.getValue(), Math::addExact);
				}
				continue;
			}
			// Nothing is reachable through open geometry: the shell is the only owner left. A shell
			// that ran out of gas space leaves every cavity at zero, and the inventory still has to
			// land somewhere - dropping it would break the shell's conservation audit instead of
			// reporting the over-filled state.
			for (int node = 1; node <= sealed; node++) {
				if (anyCapacity && !(availableVolumes[node - 1] > 0.0)) continue;
				overlap.merge(gasOverlapKey(old, node),
						Math.max(1L, AirNetwork.nanos(availableVolumes[node - 1])), Math::addExact);
			}
		}
	}

	private static boolean cavityHasVolume(long overlapKey, double[] availableVolumes) {
		int node = (int) (overlapKey & 0xffffffffL);
		return node >= 1 && availableVolumes[node - 1] > 0.0;
	}

	/**
	 * Reports the cavities an orphaned node can still reach, nearest ring first.
	 *
	 * <p>Adjacent destinations are already covered by the disappearing-lineage pass, so a surviving
	 * orphan starts from the cells it used to own and widens one face-neighbour ring at a time. The
	 * first ring that owns any current gas receives the inventory: a pocket the carrier closed over
	 * hands its gas to the cavity it is actually next to instead of to every cavity in the shell.
	 * An orphan that reaches no cavity at all reports an empty map.</p>
	 */
	private Map<Integer, Long> nearestReachableCavities(
			int old, int[] newMembership, int[] newLabels, double[] cellAvailableVolume,
			double[] availableVolumes
	) {
		boolean[] seen = new boolean[domain.cellCount];
		int[] queue = new int[domain.cellCount];
		int tail = 0;
		for (int cell = 0; cell < domain.cellCount; cell++) {
			if (domain.gasState.cellLineageNodeId[cell] == old) {
				seen[cell] = true;
				queue[tail++] = cell;
			}
		}
		int head = 0;
		while (head < tail) {
			TreeMap<Integer, Long> ring = new TreeMap<>();
			int ringEnd = tail;
			while (head < ringEnd) {
				int cell = queue[head++];
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
					if (!(cellAvailableVolume[neighbor] > 0.0)) continue;
					int destination = newMembership[neighbor] >= 0 ? newMembership[neighbor] : newLabels[neighbor];
					if (destination > 0 && availableVolumes[destination - 1] > 0.0) {
						ring.merge(destination,
								Math.max(1L, AirNetwork.nanos(cellAvailableVolume[neighbor])), Long::sum);
					}
					if (!seen[neighbor]) {
						seen[neighbor] = true;
						queue[tail++] = neighbor;
					}
				}
			}
			if (!ring.isEmpty()) return ring;
		}
		return Map.of();
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
		if (!(Double.isFinite(total) && total >= 0.0) || weightSum <= 0L) {
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

	/**
	 * Verifies the planned topology against its owner generations and a fresh geometry scan.
	 * The scan inputs are the solid geometry only, which neither the condensed nor the granular
	 * clear changes, so a diverging fresh scan means the plan is stale and the whole operator
	 * clear must be rejected before any owner mutates.
	 */
	void preflight(GasTopologyPlan plan) {
		requireCanCommit(plan);
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
		requireCanCommit(plan);
		if (plan.thermalPlan() != null) domain.thermalState.commitGasRemap(plan.thermalPlan());
		domain.gasState.network.commitRemapNodes(plan.gasPlan());
		AirCavityScanner.Result result = plan.scan();
		int sealed = result.sealedNodeCount();
		domain.gasState.cellNodeId = result.cellNodeId();
		domain.gasState.cellLineageNodeId = result.cellLineageNodeId();
		domain.gasState.previousSealedNodeCount = sealed;
		domain.gasState.pressureActive = sealed > 0;
	}

	/** Checks both owners before either side of the joint gas topology commit is published. */
	private void requireCanCommit(GasTopologyPlan plan) {
		if (plan == null) throw new IllegalArgumentException("Gas topology plan is missing");
		domain.gasState.network.requireCanCommitRemap(plan.gasPlan());
		if (plan.thermalPlan() != null) {
			if (domain.thermalState == null) {
				throw new IllegalStateException("Gas topology plan lost its thermal owner");
			}
			domain.thermalState.requireCanCommitGasRemap(plan.thermalPlan());
		}
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
		else domain.gasState.network.stepEdges(dt, domain.thermalState.gasThermodynamics(domain.gasState.network),
				(source, target, moved, sourceAfter, targetAfter) -> domain.thermalState.planGasTransfer(
				source, target, moved, sourceAfter, targetAfter, domain.gasState.network));
	}

	/**
	 * Brings the derived gas topology to the same carrier geometry that a checkpoint will encode.
	 * Marker advection and end-of-step reseeding may change the scanner topology after the last
	 * mechanical gas update. Persistence must either commit this jointly planned remap or keep the
	 * previous HEAD; it may never publish current markers beside a stale gas-node membership table.
	 */
	void synchronizeForPersistence() {
		domain.ensureInventoryGrid();
		refresh();
	}

	record GasTopologyPlan(
			AirCavityScanner.Result scan,
			AirNetwork.RemapPlan gasPlan,
			GasEnergyStore.GasRemapPlan thermalPlan
	) { }
}

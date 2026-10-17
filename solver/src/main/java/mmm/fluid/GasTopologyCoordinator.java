package mmm.fluid;

import mmm.chemistry.ExactRational;
import mmm.fluid.air.AirEdge;
import mmm.fluid.air.AirNetwork;
import mmm.fluid.air.ExactGasPartitionWeights;
import mmm.fluid.air.GasAuditLedger;
import mmm.fluid.air.GasInventory;
import mmm.fluid.air.GasRegistry;
import mmm.fluid.air.GasStateSnapshot;
import mmm.granular.GranularCouplingFields;
import mmm.thermal.GasEnergyStore;
import mmm.thermal.GasThermalModel;
import mmm.thermal.ThermalDomainState;
import mmm.units.SignedDyadic;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Joint inventory and energy remapping on captured positive PLIC microbox supports. */
final class GasTopologyCoordinator {
	private final FluidDomain domain;
	private GasRuntimeGeometry committedFrame;
	private AirNetwork committedNetwork;
	private long committedNetworkRevision;
	private record PhaseEdit(int node, AirNetwork.PhaseMutationPlan gas, double energy,
	                         ThermalDomainState.PhaseCellEnergyPlan thermal) { }
	GasTopologyCoordinator(FluidDomain domain) { this.domain = domain; }

	GasStateSnapshot bootstrap(String fingerprint) { refresh(); return export(fingerprint); }
	GasStateSnapshot export(String fingerprint) {
		if (domain.gasState.frame == null) throw new IllegalStateException("Gas topology has not been explicitly initialized");
		if (!domain.gasState.network.auditClosed()) throw new IllegalStateException("Gas component audit does not close");
		return GasStateSnapshot.capture(domain.sizeX, domain.sizeY, domain.sizeZ,
				domain.gasState.frame.pieceNodes(), domain.gasState.network, fingerprint == null ? "" : fingerprint);
	}
	void restore(GasStateSnapshot state, GasRegistry registry) { restore(state, registry, null); }

	/** Reconstructs capacities before publishing restored gas stock or thermal node columns. */
	void restore(GasStateSnapshot state, GasRegistry registry, FlipThermalParticleState thermal) {
		if (state == null || registry == null || state.sizeX() != domain.sizeX
				|| state.sizeY() != domain.sizeY || state.sizeZ() != domain.sizeZ
				|| !state.registryFingerprint().equals(registry.fingerprint()))
			throw new IllegalArgumentException("Gas restore identity/dimensions mismatch");
		if (domain.gasState.frame != null || domain.gasState.network.nodeCount() != 0)
			throw new IllegalStateException("Gas restore requires an uninitialized topology");
		domain.ensureInventoryGrid(); long[] occupied = currentOccupied();
		if (thermal != null && !thermal.coherentGroups().isEmpty()) {
			thermal.requireCompatible(domain.sizeX, domain.sizeY, domain.sizeZ, domain.config.cellSize(), domain.particles.count());
			domain.validateCoherentRestoreCapacity(thermal.coherentGroups());
			var occupancy = new GranularCouplingFields(domain.sizeX, domain.sizeY, domain.sizeZ, domain.config.cellSize());
			long[] fixed = new long[domain.cellCount];
			for (var group : thermal.coherentGroups()) fixed[group.cell()] = Math.addExact(fixed[group.cell()], group.occupiedVolumeSvq());
			occupancy.setFixedCellSvq(fixed);
			if (domain.granularState != null) occupancy.rebuild(domain.granularState.parcels, domain.granularState.deposits);
			occupied = occupancy.copySolidSvq();
		}
		var frame = GasRuntimeGeometry.capture(domain, null, occupied, domain.gasState.sealedDomainBoundary);
		if (!Arrays.equals(state.pieceNodeOrdinal(), frame.pieceNodes()) || state.nodes().size() != frame.nodeCount())
			throw new IllegalArgumentException("Restored gas microbox membership differs from carrier geometry");
		for (int node = 1; node <= frame.nodeCount(); node++)
			if (state.nodes().get(node - 1).topologyKey() != frame.minimumPiece(node))
				throw new IllegalArgumentException("Restored gas topology key mismatch");
		AirNetwork restored = new AirNetwork(registry);
		restored.restoreNodes(volumes(frame), state.nodes().stream().map(GasStateSnapshot.Node::inventory).toList(),
				state.externalSource(), state.escapedSink(), state.massRoundoff(), state.residualRoundoff());
		for (var edge : frame.topology().connections())
			restored.addOrMergeEdge(edge.firstNode(), edge.secondNode(), edge.area(), 0.65);
		domain.gasState.network = restored; domain.gasState.frame = frame;
		domain.gasState.pressureActive = frame.nodeCount() > 0;
		domain.rebuildPressureBoundaryLineage();
		rememberCommittedTopology();
	}

	void refresh() { refresh(false); }
	void refreshDeferringPhaseVolumeWork() { refresh(true); }
	private void refresh(boolean deferVolumeWork) {
		domain.ensureInventoryGrid();
		var frame = domain.gasState.frame;
		var occupied = currentOccupied();
		if (frame != null && frame == committedFrame && domain.gasState.network == committedNetwork
				&& committedNetworkRevision == committedNetwork.revision()
				&& frame.sealedExterior() == domain.gasState.sealedDomainBoundary
				&& frame.matchesCarrierGeometry(domain, occupied)) return;
		commit(plan(GasRuntimeGeometry.capture(domain, null, occupied, domain.gasState.sealedDomainBoundary),
				deferVolumeWork, null));
	}
	private void rememberCommittedTopology() {
		committedFrame = domain.gasState.frame;
		committedNetwork = domain.gasState.network;
		committedNetworkRevision = committedNetwork.revision();
	}
	GasTopologyPlan planAfterPhase(PhaseLiquidGeometryEdit geometryEdit, int node,
			AirNetwork.PhaseMutationPlan gas, double energy, ThermalDomainState.PhaseCellEnergyPlan thermal,
			GasRuntimeGeometry.PhaseSource source) {
		domain.gasState.network.requirePhaseMutationCurrent(gas);
		domain.thermalState.requirePhaseCellEnergyCurrent(thermal);
		try {
			domain.ensureInventoryGrid();
			return plan(GasRuntimeGeometry.capturePhaseEdit(domain, geometryEdit, currentOccupied(),
					domain.gasState.sealedDomainBoundary, source), true, new PhaseEdit(node, gas, energy, thermal));
		}
		catch (GasTopologyBlockedException | GasInventory.RepresentationException unavailable) {
			throw new PhaseChangeStageSupport.PhaseCandidateUnavailableException(
					"Phase-edited gas topology: " + unavailable.getMessage(), unavailable);
		}
	}
	GasTopologyPlan planStaticCellReplacement(PhaseLiquidGeometryEdit edit) {
		if (edit == null) throw new IllegalArgumentException("Gas static cell replacement is missing");
		return planAfterLiquidEdits(List.of(edit), currentOccupied(), false, edit.cell());
	}
	GasTopologyPlan planAfterLiquidEdits(List<PhaseLiquidGeometryEdit> edits) {
		return planAfterLiquidEdits(edits, currentOccupied(), false);
	}
	/** A mechanical candidate supplies its actual successor solid occupancy before carrier publication. */
	GasTopologyPlan planAfterLiquidEdits(List<PhaseLiquidGeometryEdit> edits, long[] occupied,
	                                  boolean refineMissingSuccessor) {
		return planAfterLiquidEdits(edits, occupied, refineMissingSuccessor, -1);
	}
	GasTopologyPlan planAfterAdvection(List<PhaseLiquidGeometryEdit> edits, long[] occupied,
	                                   ConservativeLiquidFlux flux, CellRemapPorts ports, float dt) {
		var old = domain.gasState.frame; if (old == null) return null;
		domain.ensureInventoryGrid();
		var next = GasRuntimeGeometry.captureLiquidEdits(domain, edits, occupied, domain.gasState.sealedDomainBoundary);
        return plan(next, false, null, true, -1);
    }

	private GasTopologyPlan planAfterLiquidEdits(List<PhaseLiquidGeometryEdit> edits, long[] occupied,
	                                           boolean refineMissingSuccessor, int staticCell) {
		if (edits == null) throw new IllegalArgumentException("Gas liquid edit list is missing");
		boolean[] selected = new boolean[domain.cellCount];
		for (var edit : edits) {
			if (edit == null || edit.cell() >= domain.cellCount || selected[edit.cell()]
					|| edit.collisionMask() != domain.geometry.cutCells.mask(edit.cell()))
				throw new IllegalArgumentException("Repeated gas liquid edit or mismatched collision mask");
			selected[edit.cell()] = true;
		}
		if (domain.gasState.frame == null) return null;
		domain.ensureInventoryGrid();
		return plan(GasRuntimeGeometry.captureLiquidEdits(domain, edits, occupied,
				domain.gasState.sealedDomainBoundary), false, null, refineMissingSuccessor, staticCell);
	}
	private long[] currentOccupied() {
		return domain.granularCoupling == null ? new long[domain.cellCount] : domain.granularCoupling.copySolidSvq();
	}
	void requireAfterPhaseCurrent(GasTopologyPlan plan, AirNetwork.PhaseMutationPlan gas,
			ThermalDomainState.PhaseCellEnergyPlan thermal) {
		if (plan == null || plan.source() != domain.gasState.frame
				|| plan.frame().sealedExterior() != domain.gasState.sealedDomainBoundary
				|| !plan.frame().matchesSourceCarrierGeometry(domain, currentOccupied()))
			throw new IllegalStateException("Phase gas geometry changed after planning");
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
	GasTopologyPlan planFromFreshScan() {
		domain.ensureInventoryGrid();
		return plan(GasRuntimeGeometry.allDry(domain, domain.gasState.sealedDomainBoundary), false, null);
	}

	/** Liquid and coherent removal preserves actual active/deposit granular occupancy. */
	GasTopologyPlan planAfterCondensedClear() {
		if (domain.gasState.frame == null) return null;
		domain.ensureInventoryGrid();
		var retained = new GranularCouplingFields(domain.sizeX, domain.sizeY, domain.sizeZ, domain.config.cellSize());
		if (domain.granularState != null) retained.rebuild(domain.granularState.parcels, domain.granularState.deposits);
		return plan(GasRuntimeGeometry.allDry(domain, retained.copySolidSvq(), domain.gasState.sealedDomainBoundary), false, null);
	}

	/** Single-successor finite stock needs positive support; splits and ambient capture retain exact physical weights. */
	private TreeMap<Long, List<ExactRational>> overlaps(GasRuntimeGeometry old, GasRuntimeGeometry next, GasGeometryTransition transition) {
		var result = new TreeMap<Long, List<ExactRational>>();
		var capacities = old.capacity().pieces();
		int[] potential = new int[old.nodeCount() + 1]; Arrays.fill(potential, -1);
		boolean[] certified = new boolean[potential.length];
		for (var capacity : capacities) {
			int piece = capacity.id(), source = old.nodeOfPiece(piece), destination = next.nodeOfPiece(piece);
			if (source <= 0 || destination < 0) continue;
			if (potential[source] == -1) potential[source] = destination;
			else if (potential[source] >= 0 && potential[source] != destination) potential[source] = -2;
			if (!certified[source] && potential[source] >= 0) certified[source] = transition.positive(piece);
		}
		// Potential targets include every positive intersection; one certified microbox proves support.
		for (int source = 1; source < potential.length; source++) {
			certified[source] &= potential[source] >= 0;
			if (certified[source]) result.put((long) source << 32 | (potential[source] & 0xffffffffL), List.of(ExactRational.ONE));
		}
		var intersections = new ExactRational[capacities.size()];
		int[] successor = new int[old.nodeCount() + 1]; Arrays.fill(successor, -1);
		for (int index = 0; index < capacities.size(); index++) {
			var capacity = capacities.get(index);
			int piece = capacity.id(), source = old.nodeOfPiece(piece), destination = next.nodeOfPiece(piece);
			// Persistent ambient space carries no finite stock, control volume or external transfer.
			if (destination < 0 || source == 0 && destination == 0) continue;
			if (source > 0 && certified[source]) continue;
			ExactRational overlap = transition.volume(piece);
			if (overlap.isZero()) continue;
			intersections[index] = overlap;
			if (source > 0) {
				if (successor[source] == -1) successor[source] = destination;
				else if (successor[source] >= 0 && successor[source] != destination) successor[source] = -2;
			}
		}
		for (int index = 0; index < capacities.size(); index++) {
			var overlap = intersections[index]; if (overlap == null) continue;
			var capacity = capacities.get(index);
			int piece = capacity.id(), source = old.nodeOfPiece(piece), destination = next.nodeOfPiece(piece);
			long pair = (long) source << 32 | (destination & 0xffffffffL);
			if (source > 0 && successor[source] >= 0) {
				result.putIfAbsent(pair, List.of(ExactRational.ONE));
				continue;
			}
			var before = old.geometry().piece(piece);
			var weight = exact(SignedDyadic.of(capacity.cubicMeters())).multiply(divide(overlap, before.normalizedVolume()));
			result.computeIfAbsent(pair, ignored -> new ArrayList<>()).add(weight);
		}
		return result;
	}

	private GasTopologyPlan plan(GasRuntimeGeometry next, boolean deferVolumeWork, PhaseEdit edit) {
		return plan(next, deferVolumeWork, edit, false, -1);
	}
	private GasTopologyPlan plan(GasRuntimeGeometry next, boolean deferVolumeWork, PhaseEdit edit,
	                             boolean refineMissingSuccessor, int staticCell) {
		return plan(next, deferVolumeWork, edit, refineMissingSuccessor, staticCell, Map.of());
	}
	private GasTopologyPlan plan(GasRuntimeGeometry next, boolean deferVolumeWork, PhaseEdit edit,
	                             boolean refineMissingSuccessor, int staticCell, Map<Integer, Integer> advectionSuccessors) {
		return plan(next, deferVolumeWork, edit, refineMissingSuccessor, staticCell, advectionSuccessors, null);
	}
	private GasTopologyPlan plan(GasRuntimeGeometry next, boolean deferVolumeWork, PhaseEdit edit,
	                             boolean refineMissingSuccessor, int staticCell, Map<Integer, Integer> advectionSuccessors,
	                             Map<Long, List<ExactRational>> motionWeights) {
		var old = domain.gasState.frame;
		var transition = old == null ? null : next.transitionFrom(old);
		boolean thermal = domain.thermalState != null && domain.thermalState.gasModelBound();
		int count = next.nodeCount(); double[] available = volumes(next);
		var inventories = new ArrayList<GasInventory>(count);
		for (int node = 0; node < count; node++) inventories.add(GasInventory.EMPTY);
		boolean retainControlVolumes = thermal && !deferVolumeWork;
		SignedDyadic[] mixed = thermal ? new SignedDyadic[count] : null;
		SignedDyadic[] inherited = retainControlVolumes ? new SignedDyadic[count] : null;
		if (thermal) Arrays.fill(mixed, SignedDyadic.ZERO);
		if (retainControlVolumes) Arrays.fill(inherited, SignedDyadic.ZERO);
		GasAuditLedger sourceDelta = GasAuditLedger.EMPTY, sinkDelta = GasAuditLedger.EMPTY;
		SignedDyadic sourceEnergy = SignedDyadic.ZERO, sinkEnergy = SignedDyadic.ZERO;
		boolean[] contributed = retainControlVolumes ? new boolean[count + 1] : null;
		if (old == null) {
			for (int node = 1; node <= count; node++) {
				GasInventory created = ambientInventory(available[node - 1]);
				inventories.set(node - 1, created); sourceDelta = sourceDelta.add(created);
				if (thermal) {
					mixed[node - 1] = SignedDyadic.of(domain.thermalState.ambientGasEnergy(created));
					if (retainControlVolumes) inherited[node - 1] = SignedDyadic.of(available[node - 1]);
					sourceEnergy = sourceEnergy.add(mixed[node - 1]);
				}
			}
		} else {
			var overlap = motionWeights == null ? overlaps(old, next, transition) : new TreeMap<>(motionWeights);
			for (var successor : advectionSuccessors.entrySet()) {
				long source = (long) successor.getKey() << 32;
				overlap.subMap(source, (long) (successor.getKey() + 1) << 32).clear();
				overlap.put(source | successor.getValue(), List.of(ExactRational.ONE));
			}
			if (staticCell >= 0 && old.matchesCarrierGeometry(domain, currentOccupied())) {
				var sweep = StaticGasPistonSweep.certify(old, next, staticCell);
				if (sweep != null && overlap.subMap((long) sweep.source() << 32, (long) (sweep.source() + 1) << 32).isEmpty())
					// The external operation first relaxes this isolated pair at sum(U), sum(V),
					// then compresses quasistatically. The receiver's old vacuum volume is retained
					// by its ordinary overlap, so the existing thermal plan accounts for both stages.
					overlap.put((long) sweep.source() << 32 | sweep.destination(), List.of(ExactRational.ONE));
			}
			if (edit == null) retainReachableSingleCavity(old, next, overlap);
			if (edit == null) transferDisplacedGas(old, next, overlap);
			for (int previous = 1; previous <= old.nodeCount(); previous++) {
				Map<Long, List<ExactRational>> destinations = overlap.subMap((long) previous << 32, (long) (previous + 1) << 32);
				GasInventory stock = inventory(previous, edit);
				if (destinations.isEmpty()) {
					if (stock.isEmpty()) continue;
					String reason = "Finite gas stock has no positive successor support: node="
							+ previous + ", key=" + old.minimumPiece(previous) + ", standardNanos=" + stock.standardVolumeNanos()
							+ ", oldVolumeM3=" + old.capacity().chambers().get(previous - 1).cubicMeters()
							+ ", oldCellLiquid=" + old.liquidStock().get(old.minimumPiece(previous) / 8).volume().toDouble()
							+ ", nextCellLiquid=" + next.liquidStock().get(old.minimumPiece(previous) / 8).volume().toDouble();
					if (refineMissingSuccessor) throw new MechanicalTimeRefinement(2, reason);
					throw new GasTopologyBlockedException(reason);
				}
				boolean split = destinations.size() > 1;
				var weights = split ? ExactGasPartitionWeights.ofTerms(List.copyOf(destinations.values())) : null;
				var parts = split ? stock.partition(weights) : List.of(stock);
				double[] energies = !thermal ? null : stock.isEmpty() ? new double[parts.size()]
						: split ? partitionEnergy(previous, parts, edit) : new double[]{energy(previous, edit)};
				double[] control = !retainControlVolumes ? null : split
						? GasTopologyVolumePartition.partitionRational(domain.gasState.network.availableVolume(previous), weights)
						: new double[]{domain.gasState.network.availableVolume(previous)};
				int part = 0;
				for (var entry : destinations.entrySet()) {
					int node = (int) (long) entry.getKey();
					if (motionWeights != null && !stock.isEmpty() && parts.get(part).isEmpty())
						throw new GasTopologyBlockedException("Translated positive gas successor cannot retain a standard-volume quantum: node=" + node);
					if (node == 0) {
						sinkDelta = sinkDelta.add(parts.get(part));
						if (thermal) sinkEnergy = sinkEnergy.add(SignedDyadic.of(energies[part]));
					} else {
						inventories.set(node - 1, inventories.get(node - 1).merge(parts.get(part)));
						if (retainControlVolumes) contributed[node] = true;
						if (thermal) {
							mixed[node - 1] = mixed[node - 1].add(SignedDyadic.of(energies[part]));
							// Existing vacuum carries its old control volume through a free-expansion merge.
							if (retainControlVolumes) inherited[node - 1] = inherited[node - 1].add(SignedDyadic.of(control[part]));
						}
					}
					part++;
				}
			}
			if (!next.sealedExterior()) for (var entry : overlap.subMap(0L, 1L << 32).entrySet()) {
				int node = (int) (long) entry.getKey(); if (node == 0) continue;
				double trappedVolume = GasTopologyVolumePartition.absoluteRational(entry.getValue());
				var trapped = ambientInventory(trappedVolume);
				inventories.set(node - 1, inventories.get(node - 1).merge(trapped));
				sourceDelta = sourceDelta.add(trapped);
				if (retainControlVolumes) contributed[node] = true;
				if (thermal) {
					var trappedEnergy = SignedDyadic.of(domain.thermalState.ambientGasEnergy(trapped));
					mixed[node - 1] = mixed[node - 1].add(trappedEnergy);
					if (retainControlVolumes) inherited[node - 1] = inherited[node - 1].add(SignedDyadic.of(trappedVolume));
					sourceEnergy = sourceEnergy.add(trappedEnergy);
				}
			}
			if (next.sealedExterior() && (!sourceDelta.isEmpty() || !sinkDelta.isEmpty()))
				throw new GasTopologyBlockedException("Closed gas topology attempted an external source or sink");
			if (retainControlVolumes) for (var piece : next.capacity().pieces()) {
				int node = next.nodeOfPiece(piece.id()), micro = piece.id() & 7, cell = piece.id() / 8;
				// Removing a fixed wall exposes vacuum without a moving liquid interface doing volume work.
				if (node > 0 && contributed[node] && (old.geometry().collisionMask(cell) & 1 << micro) != 0)
					inherited[node - 1] = inherited[node - 1].add(SignedDyadic.of(piece.cubicMeters()));
			}
			if (retainControlVolumes) for (int node = 1; node <= count; node++)
				// New space inside a surviving finite cavity performs work on its old control volume.
				// A whole new unowned cavity starts as vacuum at its actual current volume.
				if (!contributed[node]) inherited[node - 1] = SignedDyadic.of(available[node - 1]);
		}
		double[] mixedEnergy = doubles(mixed);
		double[] inheritedVolume = retainControlVolumes ? doubles(inherited) : thermal ? available.clone() : null;
		GasEnergyStore.GasRemapPlan thermalPlan = !thermal ? null : edit == null
				? domain.thermalState.planGasRemap(domain.gasState.network, inventories, available,
						mixedEnergy, inheritedVolume, sourceEnergy, sinkEnergy)
				: domain.thermalState.planGasRemapAfterPhase(edit.thermal(), edit.node(), edit.energy(), domain.gasState.network,
						inventories, available, mixedEnergy, inheritedVolume, sourceEnergy, sinkEnergy);
		var edges = new ArrayList<AirEdge>(next.topology().connections().size());
		for (var edge : next.topology().connections()) edges.add(new AirEdge(edge.firstNode(), edge.secondNode(), edge.area(), 0.65));
		AirNetwork.RemapPlan gasPlan = edit == null
				? domain.gasState.network.planRemapNodes(available, inventories, sourceDelta, sinkDelta, edges)
				: domain.gasState.network.planRemapAfterPhase(edit.gas(), available, inventories, sourceDelta, sinkDelta, edges);
		return new GasTopologyPlan(next, old, gasPlan, thermalPlan, transition);
	}

	/** A delayed liquid edit can expose a connected path before displacing every original dry piece. */
	private void retainReachableSingleCavity(GasRuntimeGeometry old, GasRuntimeGeometry next,
	                                       TreeMap<Long, List<ExactRational>> overlap) {
		long pair = 1L << 32 | 1;
		if (!old.sealedExterior() || !next.sealedExterior() || old.nodeCount() != 1 || next.nodeCount() != 1
				|| !overlap.subMap(1L << 32, 2L << 32).isEmpty()) return;
		var current = GasRuntimeGeometry.capture(domain, null, currentOccupied(), domain.gasState.sealedDomainBoundary);
		if (current.nodeCount() != 1 || !Arrays.equals(old.occupiedSvq(), current.occupiedSvq())) return;
		for (int cell = 0; cell < domain.cellCount; cell++)
			if (old.geometry().collisionMask(cell) != current.geometry().collisionMask(cell)) return;
		var first = overlaps(old, current, new GasGeometryTransition(old, current)).get(pair);
		var second = overlaps(current, next, new GasGeometryTransition(current, next)).get(pair);
		if (first != null && !first.isEmpty() && second != null && !second.isEmpty())
			// Each positive leg lies in the same fully connected finite gas chamber. Its homogeneous
			// stock follows that actual path; a single unchanged inventory's isentropes compose by V0/V2.
			overlap.put(pair, first);
	}
	/** A swept-away dry pocket follows the connected old/new dry support to its nearest surviving chamber. */
	private void transferDisplacedGas(GasRuntimeGeometry old, GasRuntimeGeometry next,
	                                  TreeMap<Long, List<ExactRational>> overlap) {
        boolean missing = false;
        for (int node = 1; node <= old.nodeCount(); node++) {
            if (overlap.subMap((long) node << 32, (long) (node + 1) << 32).isEmpty()) {
                missing = true;
                break;
            }
        }
        if (!missing) return;
		int count = domain.cellCount * 8;
		var links = new int[count][6];
		for (var row : links) Arrays.fill(row, -1);
		for (var geometry : List.of(old.geometry(), next.geometry())) for (var port : geometry.ports()) {
			links[port.first()][2 * port.axis() + 1] = port.second();
			links[port.second()][2 * port.axis()] = port.first();
		}
		int[] strides = {1, domain.sizeX, domain.sizeX * domain.sizeY}, sizes = {domain.sizeX, domain.sizeY, domain.sizeZ};
		for (var source : old.geometry().pieces()) if (next.geometry().piece(source.id()) == null) {
			int cell = source.cell(), micro = source.micro();
			int[] coordinates = {cell % domain.sizeX, cell / domain.sizeX % domain.sizeY, cell / (domain.sizeX * domain.sizeY)};
			for (int axis = 0; axis < 3; axis++) for (int sign : new int[]{-1, 1}) {
				boolean across = (micro >>> axis & 1) == (sign > 0 ? 1 : 0);
				if (across && (coordinates[axis] + sign < 0 || coordinates[axis] + sign >= sizes[axis])) continue;
				int receiverCell = cell + (across ? sign * strides[axis] : 0), receiverMicro = micro ^ 1 << axis;
				var receiver = next.geometry().piece(8 * receiverCell + receiverMicro);
				if (receiver == null) continue;
				double firstCoordinate = across ? sign > 0 ? 1 : 0 : .5;
				double secondCoordinate = across ? 1 - firstCoordinate : .5;
				var planes = new ArrayList<GasFaceIntersection.Plane>(2);
				addDryFacePlane(planes, source.dryPlane(), axis, firstCoordinate);
				addDryFacePlane(planes, receiver.dryPlane(), axis, secondCoordinate);
				if (GasFaceIntersection.area(.5 * (micro >>> ((axis + 1) % 3) & 1),
						.5 * (micro >>> ((axis + 2) % 3) & 1), .5, planes) > 0) {
					links[source.id()][2 * axis + (sign > 0 ? 1 : 0)] = receiver.id();
					links[receiver.id()][2 * axis + (sign > 0 ? 0 : 1)] = source.id();
				}
			}
		}
		int[] queue = new int[count], seen = new int[count];
		for (int node = 1; node <= old.nodeCount(); node++) {
			long key = (long) node << 32;
			if (!overlap.subMap(key, (long) (node + 1) << 32).isEmpty()) continue;
			int head = 0, tail = 0;
			for (var piece : old.capacity().pieces()) if (old.nodeOfPiece(piece.id()) == node) {
				queue[tail++] = piece.id(); seen[piece.id()] = node;
			}
			var destinations = new TreeMap<Integer, Double>();
			while (head < tail && destinations.isEmpty()) {
				int end = tail;
				while (head < end) {
					int piece = queue[head++];
					int destination = next.nodeOfPiece(piece);
					if (destination > 0) destinations.merge(destination, next.geometry().piece(piece).volume(), Double::sum);
					for (int peer : links[piece]) if (peer >= 0 && seen[peer] != node) { seen[peer] = node; queue[tail++] = peer; }
				}
			}
			// Pockets below one percent of a cell are transported as unresolved gas through
			// the local open collision component. Their inventory and energy move together.
			if (destinations.isEmpty() && old.capacity().chambers().get(node - 1).cubicMeters()
					<= .01 * old.geometry().cellVolumeCubicMeters()) {
				Arrays.fill(seen, 0); head = tail = 0;
				for (var piece : old.capacity().pieces()) if (old.nodeOfPiece(piece.id()) == node) {
					queue[tail++] = piece.id(); seen[piece.id()] = 1;
				}
				for (int distance = 0; distance < 2 && destinations.isEmpty(); distance++) {
					int end = tail;
					while (head < end) {
						int piece = queue[head++], cell = piece / 8, micro = piece & 7;
						int[] coordinate = {cell % domain.sizeX, cell / domain.sizeX % domain.sizeY, cell / (domain.sizeX * domain.sizeY)};
						for (int axis = 0; axis < 3; axis++) for (int sign : new int[]{-1, 1}) {
							boolean across = (micro >>> axis & 1) == (sign > 0 ? 1 : 0);
							if (across && (coordinate[axis] + sign < 0 || coordinate[axis] + sign >= sizes[axis])) continue;
							int peerCell = cell + (across ? sign * strides[axis] : 0), peerMicro = micro ^ 1 << axis;
							int peer = 8 * peerCell + peerMicro;
							if (seen[peer] != 0 || (next.geometry().collisionMask(peerCell) & 1 << peerMicro) != 0) continue;
							seen[peer] = 1; queue[tail++] = peer;
							int destination = next.nodeOfPiece(peer);
							if (destination > 0) destinations.merge(destination, next.geometry().piece(peer).volume(), Double::sum);
						}
					}
				}
				Arrays.fill(seen, 0);
			}
			for (var destination : destinations.entrySet()) overlap.put(key | destination.getKey(),
					List.of(MicroBoxPlaneVolume.rational(destination.getValue())));
		}
	}
	private static void addDryFacePlane(List<GasFaceIntersection.Plane> planes, GasGeometryOverlap.HalfSpace plane,
	                                   int axis, double coordinate) {
		if (plane == null) return;
		SignedDyadic[] n = {plane.nx(), plane.ny(), plane.nz()};
		planes.add(new GasFaceIntersection.Plane(n[(axis + 1) % 3], n[(axis + 2) % 3],
				plane.threshold().subtract(n[axis].multiply(SignedDyadic.of(coordinate)))));
	}
	private GasInventory ambientInventory(double volume) {
		if (!(volume > 0 && Double.isFinite(volume))) throw new GasTopologyBlockedException("Ambient capture has no positive finite volume");
		long nanos = AirNetwork.nanos(volume);
		if (nanos == 0) throw new GasTopologyBlockedException("Ambient capture volume is below the gas inventory resolution");
		return domain.gasState.network.ambientInventory(nanos);
	}
	private static ExactRational exact(SignedDyadic value) {
		return value.exponent() >= 0 ? new ExactRational(value.significand().shiftLeft(value.exponent()), BigInteger.ONE)
				: new ExactRational(value.significand(), BigInteger.ONE.shiftLeft(-value.exponent()));
	}
	private static ExactRational divide(ExactRational a, ExactRational b) {
		return new ExactRational(a.numerator().multiply(b.denominator()), a.denominator().multiply(b.numerator()));
	}
	private static double[] doubles(SignedDyadic[] values) {
		if (values == null) return null;
		double[] result = new double[values.length];
		for (int i = 0; i < values.length; i++) {
			result[i] = values[i].toDouble();
			if (!Double.isFinite(result[i]) || values[i].signum() > 0 && !(result[i] > 0))
				throw new GasTopologyBlockedException("Gas remap energy or volume is not representable");
		}
		return result;
	}
	private static double[] volumes(GasRuntimeGeometry frame) {
		double[] result = new double[frame.nodeCount()];
		for (var chamber : frame.capacity().chambers()) result[chamber.node() - 1] = chamber.cubicMeters();
		return result;
	}

	/** Operator-clear preflight binds the complete source before condensed inventory changes. */
	void preflight(GasTopologyPlan plan) {
		requireBeforeCarrierCommit(plan);
		var fresh = GasRuntimeGeometry.allDry(domain, domain.gasState.sealedDomainBoundary);
		if (!Arrays.equals(fresh.pieceNodes(), plan.frame().pieceNodes()) || !Arrays.equals(volumes(fresh), volumes(plan.frame())))
			throw new IllegalStateException("Gas clear successor changed after planning");
	}
	void requireBeforeCarrierCommit(GasTopologyPlan plan) {
		requireOwnersCurrent(plan); domain.ensureInventoryGrid();
		if (!plan.frame().matchesSourceCarrierGeometry(domain, currentOccupied()))
			throw new IllegalStateException("Gas source geometry changed after carrier edit planning");
	}
	void commit(GasTopologyPlan plan) {
		requireOwnersCurrent(plan); domain.ensureInventoryGrid();
		if (!plan.frame().matchesCarrierGeometry(domain, currentOccupied()))
			throw new IllegalStateException("Gas successor geometry differs from published carrier stock");
		if (plan.thermalPlan() != null) domain.thermalState.commitGasRemap(plan.thermalPlan());
		domain.gasState.network.commitRemapNodes(plan.gasPlan()); domain.gasState.frame = plan.frame();
		domain.gasState.pressureActive = plan.frame().nodeCount() > 0;
		domain.rebuildPressureBoundaryLineage();
		rememberCommittedTopology();
	}
	private void requireOwnersCurrent(GasTopologyPlan plan) {
		if (plan == null) throw new IllegalArgumentException("Gas topology plan is missing");
		if (plan.source() != domain.gasState.frame || plan.frame().sealedExterior() != domain.gasState.sealedDomainBoundary)
			throw new IllegalStateException("Gas topology owner changed after planning");
		domain.gasState.network.requireCanCommitRemap(plan.gasPlan());
		if (plan.thermalPlan() != null) {
			if (domain.thermalState == null) throw new IllegalStateException("Gas topology plan lost its thermal owner");
			domain.thermalState.requireCanCommitGasRemap(plan.thermalPlan());
		}
	}
	void step(float dt) {
		refresh(); if (!domain.gasState.pressureActive) return;
		if (domain.thermalState == null || !domain.thermalState.gasModelBound()) domain.gasState.network.stepEdges(dt);
		else domain.gasState.network.stepEdges(dt, domain.thermalState.gasThermodynamics(domain.gasState.network),
				(source, target, moved, sourceAfter, targetAfter) -> domain.thermalState.planGasTransfer(
						source, target, moved, sourceAfter, targetAfter, domain.gasState.network));
	}
	void synchronizeForPersistence() { refresh(); }
	record GasTopologyPlan(GasRuntimeGeometry frame, GasRuntimeGeometry source,
	                       AirNetwork.RemapPlan gasPlan, GasEnergyStore.GasRemapPlan thermalPlan,
	                       GasGeometryTransition transition) { }
}

package mmm.fluid;

import mmm.inventory.LiquidCellInventory;


import mmm.fluid.air.AirEdge;
import mmm.fluid.air.AirNetwork;
import mmm.fluid.air.GasPartitionWeights;
import mmm.fluid.air.GasAuditLedger;
import mmm.fluid.air.GasInventory;
import mmm.fluid.air.GasRegistry;
import mmm.fluid.air.GasStateSnapshot;
import mmm.granular.GranularCouplingFields;
import mmm.thermal.GasEnergyStore;
import mmm.thermal.GasThermalModel;
import mmm.thermal.ThermalDomainState;
import mmm.units.SignedDyadic;

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
	record PhaseBatch(List<GasInventory> inventories, double[] energies,
	                  GasAuditLedger source, GasAuditLedger sink,
	                  GasEnergyStore.InventoryEnergyPlan gasThermal,
	                  ThermalDomainState.PhaseBatchEnergyPlan cellThermal) {
		PhaseBatch { inventories = List.copyOf(inventories); energies = energies.clone(); }
	}
	GasTopologyCoordinator(FluidDomain domain) { this.domain = domain; }

	GasStateSnapshot bootstrap(String fingerprint) { refresh(); return export(fingerprint); }
	GasStateSnapshot export(String fingerprint) {
		if (domain.gasState.frame == null) throw new IllegalStateException("Gas topology has not been explicitly initialized");
		if (!domain.gasState.network.auditClosed()) throw new IllegalStateException("Gas component audit does not close");
		return GasStateSnapshot.capture(domain.sizeX, domain.sizeY, domain.sizeZ,
				domain.gasState.frame.pieceNodes(), domain.gasState.network, fingerprint == null ? "" : fingerprint);
	}
	void restore(GasStateSnapshot state, GasRegistry registry) { restoreOwners(state, registry, List.of()); }

	/** Reconstructs capacities before publishing restored gas stock or thermal node columns. */
	void restore(GasStateSnapshot state, GasRegistry registry, FlipThermalParticleState thermal) {
		if (thermal != null) thermal.requireCompatible(domain.sizeX, domain.sizeY, domain.sizeZ, domain.config.cellSize(), domain.liquidCells.physicalSize());
		restoreOwners(state, registry, thermal == null ? List.of() : thermal.coherentGroups());
	}
	void restore(GasStateSnapshot state, GasRegistry registry, mmm.thermal.ThermalOwnerSnapshot thermal) {
		restoreOwners(state, registry, thermal == null ? List.of() : thermal.coherentGroups());
	}
	private void restoreOwners(GasStateSnapshot state, GasRegistry registry, List<mmm.thermal.CoherentPhaseStore.Group> groups) {
		if (state == null || registry == null || state.sizeX() != domain.sizeX
				|| state.sizeY() != domain.sizeY || state.sizeZ() != domain.sizeZ
				|| !state.registryFingerprint().equals(registry.fingerprint()))
			throw new IllegalArgumentException("Gas restore identity/dimensions mismatch");
		if (domain.gasState.frame != null || domain.gasState.network.nodeCount() != 0)
			throw new IllegalStateException("Gas restore requires an uninitialized topology");
		domain.ensureInventoryGrid(); long[] occupied = currentOccupied();
		if (!groups.isEmpty()) {
			domain.validateCoherentRestoreCapacity(groups);
			var occupancy = new GranularCouplingFields(domain.sizeX, domain.sizeY, domain.sizeZ, domain.config.cellSize());
			long[] fixed = new long[domain.cellCount];
			for (var group : groups) fixed[group.cell()] = Math.addExact(fixed[group.cell()], group.occupiedVolumeSvq());
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
				state.externalSource(), state.escapedSink());
		for (var edge : frame.topology().connections())
			restored.addOrMergeEdge(edge.firstNode(), edge.secondNode(), edge.area(), 0.65);
		domain.gasState.network = restored; domain.gasState.frame = frame;
		domain.gasState.pressureActive = frame.nodeCount() > 0;
		rememberCommittedTopology();
	}

	/** A private candidate continues immutable geometry and an independently owned network. */
	void restoreRuntime(GasRuntimeGeometry frame, AirNetwork.RuntimeSnapshot network) {
		if (domain.gasState.frame != null || domain.gasState.network.nodeCount() != 0)
			throw new IllegalStateException("Runtime gas continuation requires an empty target");
		domain.gasState.network = AirNetwork.fromRuntimeSnapshot(network); domain.gasState.frame = frame;
		domain.gasState.pressureActive = frame.nodeCount() > 0; rememberCommittedTopology();
	}

	void refresh() {
		domain.ensureInventoryGrid();
		var frame = domain.gasState.frame;
		var occupied = currentOccupied();
		if (frame != null && frame == committedFrame && domain.gasState.network == committedNetwork
				&& committedNetworkRevision == committedNetwork.revision()
				&& frame.sealedExterior() == domain.gasState.sealedDomainBoundary
				&& frame.matchesCarrierGeometry(domain, occupied)) return;
		commit(plan(GasRuntimeGeometry.capture(domain, null, occupied, domain.gasState.sealedDomainBoundary),
				false, null));
	}
	private void rememberCommittedTopology() {
		committedFrame = domain.gasState.frame;
		committedNetwork = domain.gasState.network;
		committedNetworkRevision = committedNetwork.revision();
	}
	GasTopologyPlan planAfterPhaseBatch(LiquidCellInventory future, PhaseBatch phase) {
		domain.thermalState.requireGasInventoryEnergyCurrent(phase.gasThermal(), domain.gasState.network);
		domain.thermalState.requirePhaseBatchEnergyCurrent(phase.cellThermal());
		return plan(GasRuntimeGeometry.captureCellFuture(domain, future, currentOccupied(),
				domain.gasState.sealedDomainBoundary), true, phase);
	}
	GasTopologyPlan planStaticCellReplacement(PhaseLiquidGeometryEdit edit) {
		if (edit == null) throw new IllegalArgumentException("Gas static cell replacement is missing");
		return planAfterLiquidEdits(List.of(edit), currentOccupied(), false);
	}
	GasTopologyPlan planAfterLiquidEdits(List<PhaseLiquidGeometryEdit> edits) {
		return planAfterLiquidEdits(edits, currentOccupied(), false);
	}
	/** A mechanical candidate supplies its actual successor solid occupancy before carrier publication. */
	GasTopologyPlan planAfterLiquidEdits(List<PhaseLiquidGeometryEdit> edits, long[] occupied,
	                                  boolean refineMissingSuccessor) {
		return planAfterLiquidEditsCaptured(edits, occupied, refineMissingSuccessor);
	}
	GasTopologyPlan planAfterLiquidCells(LiquidCellInventory future, long[] occupied, boolean refine) {
		if (domain.gasState.frame == null) return null;
		return plan(GasRuntimeGeometry.captureCellFuture(domain, future, occupied,
				domain.gasState.sealedDomainBoundary), false, null, refine);
	}
	GasTopologyPlan planCellFuture(LiquidCellInventory future, long[] occupied, boolean advection) {
		return planAfterLiquidCells(future, occupied, advection);
	}
	GasTopologyPlan planAfterCollisionCells(LiquidCellInventory future, long[] occupied, byte[] collisionMasks) {
		if (domain.gasState.frame == null) return null;
		return plan(GasRuntimeGeometry.captureCellFuture(domain, future, occupied,
				domain.gasState.sealedDomainBoundary, collisionMasks), false, null);
	}

	private GasTopologyPlan planAfterLiquidEditsCaptured(List<PhaseLiquidGeometryEdit> edits, long[] occupied,
	                                                   boolean refineMissingSuccessor) {
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
				domain.gasState.sealedDomainBoundary), false, null, refineMissingSuccessor);
	}
	private long[] currentOccupied() {
		return domain.granularCoupling == null ? new long[domain.cellCount] : domain.granularCoupling.copySolidSvq();
	}
	void requireAfterPhaseBatchCurrent(GasTopologyPlan plan, PhaseBatch phase) {
		if (plan == null || plan.source() != domain.gasState.frame
				|| plan.frame().sealedExterior() != domain.gasState.sealedDomainBoundary
				|| !plan.frame().matchesSourceCarrierGeometry(domain, currentOccupied()))
			throw new IllegalStateException("Phase gas geometry changed after planning");
		domain.gasState.network.requireCanCommitRemap(plan.gasPlan());
		domain.thermalState.requireGasRemapAfterBatchCurrent(plan.thermalPlan(), phase.gasThermal(),
				phase.cellThermal(), domain.gasState.network);
	}
	private GasInventory inventory(int node, PhaseBatch edit) {
		return edit != null ? edit.inventories().get(node - 1) : domain.gasState.network.inventory(node);
	}
	private double energy(int node, PhaseBatch edit) {
		return edit != null ? edit.energies()[node - 1] : domain.thermalState.gasNodeEnergy(node, domain.gasState.network);
	}
	private double[] partitionEnergy(int node, List<GasInventory> parts, PhaseBatch edit) {
		return edit != null
				? new GasThermalModel(domain.thermalState.registry(), domain.gasState.network.registry())
						.partitionEnergy(inventory(node, edit), energy(node, edit), parts)
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
	private TreeMap<Long, List<Double>> overlaps(GasRuntimeGeometry old, GasRuntimeGeometry next, GasGeometryTransition transition) {
		var result = new TreeMap<Long, List<Double>>();
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
			if (certified[source]) result.put((long) source << 32 | (potential[source] & 0xffffffffL), List.of(1.0));
		}
		var intersections = new double[capacities.size()];
		int[] successor = new int[old.nodeCount() + 1]; Arrays.fill(successor, -1);
		for (int index = 0; index < capacities.size(); index++) {
			var capacity = capacities.get(index);
			int piece = capacity.id(), source = old.nodeOfPiece(piece), destination = next.nodeOfPiece(piece);
			// Persistent ambient space carries no finite stock, control volume or external transfer.
			if (destination < 0 || source == 0 && destination == 0) continue;
			if (source > 0 && certified[source]) continue;
			double overlap = transition.volume(piece);
			if (overlap == 0) continue;
			intersections[index] = overlap;
			if (source > 0) {
				if (successor[source] == -1) successor[source] = destination;
				else if (successor[source] >= 0 && successor[source] != destination) successor[source] = -2;
			}
		}
		for (int index = 0; index < capacities.size(); index++) {
			var overlap = intersections[index]; if (overlap == 0) continue;
			var capacity = capacities.get(index);
			int piece = capacity.id(), source = old.nodeOfPiece(piece), destination = next.nodeOfPiece(piece);
			long pair = (long) source << 32 | (destination & 0xffffffffL);
			if (source > 0 && successor[source] >= 0) {
				result.putIfAbsent(pair, List.of(1.0));
				continue;
			}
			var before = old.geometry().piece(piece);
			var weight = capacity.cubicMeters() * (overlap / before.normalizedVolume());
			result.computeIfAbsent(pair, ignored -> new ArrayList<>()).add(weight);
		}
		return result;
	}

	private GasTopologyPlan plan(GasRuntimeGeometry next, boolean deferVolumeWork, PhaseBatch edit) {
		return plan(next, deferVolumeWork, edit, false);
	}
	private GasTopologyPlan plan(GasRuntimeGeometry next, boolean deferVolumeWork, PhaseBatch edit,
	                             boolean refineMissingSuccessor) {
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
			var successors = successors(old, next, transition);
			var overlap = successors.weights();
			var displaced = successors.relocated();
			var relocated = new SignedDyadic[count]; Arrays.fill(relocated, SignedDyadic.ZERO);
			for (int previous = 1; previous <= old.nodeCount(); previous++) {
				Map<Long, List<Double>> destinations = overlap.subMap((long) previous << 32, (long) (previous + 1) << 32);
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
				var weights = split ? GasPartitionWeights.of(destinations.values().stream().mapToDouble(values -> values.stream().mapToDouble(Double::doubleValue).sum()).toArray()) : null;
				var parts = split ? stock.partition(weights) : List.of(stock);
				double[] energies = !thermal ? null : stock.isEmpty() ? new double[parts.size()]
						: split ? partitionEnergy(previous, parts, edit) : new double[]{energy(previous, edit)};
				double[] control = !retainControlVolumes ? null : split
						? GasTopologyVolumePartition.partition(domain.gasState.network.availableVolume(previous), weights)
						: new double[]{domain.gasState.network.availableVolume(previous)};
				int part = 0;
				for (var entry : destinations.entrySet()) {
					int node = (int) (long) entry.getKey();
					if (node == 0) {
						sinkDelta = sinkDelta.add(parts.get(part));
						if (thermal) sinkEnergy = sinkEnergy.add(SignedDyadic.of(energies[part]));
					} else {
						inventories.set(node - 1, inventories.get(node - 1).merge(parts.get(part)));
						if (retainControlVolumes) contributed[node] = true;
						if (thermal) {
							mixed[node - 1] = mixed[node - 1].add(SignedDyadic.of(energies[part]));
							// Existing vacuum carries its old control volume through a free-expansion merge.
							if (retainControlVolumes) {
							inherited[node - 1] = inherited[node - 1].add(SignedDyadic.of(control[part]));
							if (displaced.contains(entry.getKey())) relocated[node - 1] = relocated[node - 1].add(SignedDyadic.of(control[part]));
						}
						}
					}
					part++;
				}
			}
			if (!next.sealedExterior()) for (var entry : overlap.subMap(0L, 1L << 32).entrySet()) {
				int node = (int) (long) entry.getKey(); if (node == 0) continue;
				double trappedVolume = GasTopologyVolumePartition.absoluteVolume(entry.getValue());
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
				if (node > 0 && contributed[node] && (old.geometry().collisionMask(cell) & 1 << micro) != 0) {
					var opened = SignedDyadic.of(piece.cubicMeters());
					var occupied = opened.compareTo(relocated[node - 1]) < 0 ? opened : relocated[node - 1];
					// A relocated cavity already brought its control volume into this newly open space.
					relocated[node - 1] = relocated[node - 1].subtract(occupied);
					inherited[node - 1] = inherited[node - 1].add(opened.subtract(occupied));
				}
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
				: domain.thermalState.planGasRemapAfterBatch(domain.gasState.network, edit.gasThermal(), edit.cellThermal(),
						inventories, available, mixedEnergy, inheritedVolume, sourceEnergy, sinkEnergy);
		var edges = new ArrayList<AirEdge>(next.topology().connections().size());
		for (var edge : next.topology().connections()) edges.add(new AirEdge(edge.firstNode(), edge.secondNode(), edge.area(), 0.65));
		AirNetwork.RemapPlan gasPlan = domain.gasState.network.planRemapNodes(available, inventories,
				edit == null ? sourceDelta : sourceDelta.add(edit.source()),
				edit == null ? sinkDelta : sinkDelta.add(edit.sink()), edges);
		return new GasTopologyPlan(next, old, gasPlan, thermalPlan, transition);
	}

	private record Successors(TreeMap<Long, List<Double>> weights, java.util.Set<Long> relocated) { }

	/**
	 * PLIC overlap resolves ordinary splits. When a reconstructed bubble changes position inside a
	 * fixed open component, its lumped inventory follows that component's space-time dry graph.
	 * This first-order representation change spans at most one local component before following
	 * resolved gas connections; full liquid cells and fixed walls continue to separate chambers.
	 */
	private Successors successors(GasRuntimeGeometry old, GasRuntimeGeometry next,
	                              GasGeometryTransition transition) {
		var weights = overlaps(old, next, transition);
		boolean missing = false;
		for (int node = 1; node <= old.nodeCount(); node++)
			missing |= weights.subMap((long) node << 32, (long) (node + 1) << 32).isEmpty();
		if (!missing) return new Successors(weights, java.util.Set.of());
		int[] parent = new int[8 * domain.cellCount];
		byte[] rank = new byte[parent.length];
		for (int piece = 0; piece < parent.length; piece++) parent[piece] = piece;
		for (var geometry : List.of(old.geometry(), next.geometry())) {
			for (int cell = 0; cell < domain.cellCount; cell++)
				for (int support : CellRemapMicroTopology.components(geometry.collisionMask(cell))) {
					int first = -1;
					for (int micro = 0; micro < 8; micro++) if ((support & 1 << micro) != 0) {
						int piece = 8 * cell + micro;
						if (old.nodeOfPiece(piece) < 0 && next.nodeOfPiece(piece) < 0) continue;
						if (first < 0) first = piece; else join(parent, rank, first, piece);
					}
				}
			// Finite apertures remain network edges and never become instantaneous stock transfers.
			for (var port : geometry.ports()) if (port.fullCutConnection())
				join(parent, rank, port.first(), port.second());
		}
		int[] strides = {1, domain.sizeX, domain.sizeX * domain.sizeY};
		int[] sizes = {domain.sizeX, domain.sizeY, domain.sizeZ};
		for (var source : old.geometry().pieces()) if (next.geometry().piece(source.id()) == null) {
			int cell = source.cell(), micro = source.micro();
			int[] coordinate = {cell % domain.sizeX, cell / domain.sizeX % domain.sizeY,
					cell / (domain.sizeX * domain.sizeY)};
			for (int axis = 0; axis < 3; axis++) for (int sign : new int[]{-1, 1}) {
				boolean across = (micro >>> axis & 1) == (sign > 0 ? 1 : 0);
				if (!across || coordinate[axis] + sign < 0 || coordinate[axis] + sign >= sizes[axis]) continue;
				int receiverCell = cell + sign * strides[axis], receiverMicro = micro ^ 1 << axis;
				var receiver = next.geometry().piece(8 * receiverCell + receiverMicro);
				if (receiver == null) continue;
				double firstCoordinate = sign > 0 ? 1 : 0;
				var planes = new ArrayList<GasFaceIntersection.Plane>(2);
				addDryFacePlane(planes, source.dryPlane(), axis, firstCoordinate);
				addDryFacePlane(planes, receiver.dryPlane(), axis, 1 - firstCoordinate);
				if (GasFaceIntersection.area(.5 * (micro >>> ((axis + 1) % 3) & 1),
						.5 * (micro >>> ((axis + 2) % 3) & 1), .5, planes) > 0)
					join(parent, rank, source.id(), receiver.id());
			}
		}
		var capacities = new java.util.HashMap<Integer, TreeMap<Integer, Double>>();
		for (var piece : next.capacity().pieces()) {
			int node = next.nodeOfPiece(piece.id());
			if (node >= 0) capacities.computeIfAbsent(root(parent, piece.id()), ignored -> new TreeMap<>())
					.merge(node, piece.cubicMeters(), Double::sum);
		}
		var relocated = new java.util.HashSet<Long>();
		for (int node = 1; node <= old.nodeCount(); node++) {
			long key = (long) node << 32;
			if (!weights.subMap(key, (long) (node + 1) << 32).isEmpty()) continue;
			var destinations = capacities.get(root(parent, old.minimumPiece(node)));
			if (destinations == null) continue;
			for (var destination : destinations.entrySet()) {
				long pair = key | destination.getKey();
				weights.put(pair, List.of(destination.getValue())); relocated.add(pair);
			}
		}
		return new Successors(weights, relocated);
	}
	private static int root(int[] parent, int piece) {
		int root = piece;
		while (parent[root] != root) root = parent[root];
		while (parent[piece] != piece) { int next = parent[piece]; parent[piece] = root; piece = next; }
		return root;
	}
	private static void join(int[] parent, byte[] rank, int first, int second) {
		int a = root(parent, first), b = root(parent, second);
		if (a == b) return;
		if (rank[a] < rank[b]) parent[a] = b;
		else { parent[b] = a; if (rank[a] == rank[b]) rank[a]++; }
	}
	private static void addDryFacePlane(List<GasFaceIntersection.Plane> planes,
	                                   GasGeometryOverlap.HalfSpace plane, int axis, double coordinate) {
		if (plane == null) return;
		double[] n = {plane.nx(), plane.ny(), plane.nz()};
		planes.add(new GasFaceIntersection.Plane(n[(axis + 1) % 3], n[(axis + 2) % 3],
				plane.threshold() - n[axis] * coordinate));
	}
	private GasInventory ambientInventory(double volume) {
		if (!(volume > 0 && Double.isFinite(volume))) throw new GasTopologyBlockedException("Ambient capture has no positive finite volume");
		long nanos = AirNetwork.nanos(volume);
		// Standard volume rounds to the nearest 10^-9 m³, including zero below half a quantum.
		if (nanos == 0) return GasInventory.EMPTY;
		return domain.gasState.network.ambientInventory(nanos);
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
				batch -> domain.thermalState.planGasFlow(batch, domain.gasState.network));
		// Orifice transfers change stock and U on the same nodes, capacities and faces.
		rememberCommittedTopology();
	}
	void synchronizeForPersistence() { refresh(); }
	record GasTopologyPlan(GasRuntimeGeometry frame, GasRuntimeGeometry source,
	                       AirNetwork.RemapPlan gasPlan, GasEnergyStore.GasRemapPlan thermalPlan,
	                       GasGeometryTransition transition) { }
}

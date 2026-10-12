package mmm.fluid;

import mmm.chemistry.ChemicalMixtureRecord;
import mmm.chemistry.ChemicalParcelStore;
import mmm.chemistry.ChemicalReactionEngine;
import mmm.granular.GranularCouplingFields;
import mmm.granular.GranularDepositDynamics;
import mmm.granular.GranularDepositStore;
import mmm.granular.GranularLimits;
import mmm.granular.GranularMergeKinematics;
import mmm.granular.GranularParcelStore;
import mmm.granular.GranularPhaseTransferEngine;
import mmm.granular.SolidCompositionRecord;
import mmm.granular.SolidLiquidReaction;
import mmm.thermal.GranularEnergyColumn;
import mmm.thermal.GranularEnergyState;
import mmm.thermal.ThermalCarrierContext;
import mmm.thermal.ThermalDomainState;
import mmm.thermal.ThermalStateFunction;
import mmm.units.SignedDyadic;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Plans and commits exact liquid/solid precipitation and dissolution inside a private candidate. */
final class GranularReactionStage {
	record Workspace(
			int sizeX, int sizeY, int cellCount, float cellSize,
			LiquidParticleStore particles, SignedDyadic[] pendingPhaseCellVolume,
			ThermalCarrierContext thermalMarkers
	) { }

	interface Access {
		boolean enabled();
		ChemicalParcelStore chemistry();
		FluidMaterialRegistry materialRegistry();
		GranularDomainState granularState();
		GranularPhaseTransferEngine transferEngine();
		FlipGranularCollision collision();
		GranularCouplingFields coupling();
		GranularDepositDynamics depositDynamics();
		ThermalDomainState thermalState();
		int particleCell(int marker);
		int parcelCell(GranularParcelStore.Parcel parcel);
		long geometricOpenSvq(int cell);
		float mergeVelocityDelta();
		void markGridDirty();
	}

	private final Workspace state;
	private final Access access;
	private Map<Integer, List<Integer>> activeByCell;
	private boolean occupancyCurrent;
	private SignedDyadic[] liquidVolumes;

	GranularReactionStage(Workspace state, Access access) {
		this.state = state;
		this.access = access;
	}

	FluidDomain.PhaseTransferStats step(int[] dirtyCells) {
		activeByCell = null;
		occupancyCurrent = false;
		liquidVolumes = null;
		if (!access.enabled() || dirtyCells.length == 0) {
			return new FluidDomain.PhaseTransferStats(0, 0, 0, 0L);
		}
		ChemicalParcelStore chemistry = access.chemistry();
		GranularDomainState granular = access.granularState();
		boolean[] dirty = new boolean[state.cellCount()];
		for (int cell : dirtyCells) {
			if (cell < 0 || cell >= state.cellCount()) {
				throw new IllegalStateException("Chemical dirty cell is outside the domain");
			}
			dirty[cell] = true;
		}
		@SuppressWarnings("unchecked")
		TreeMap<Integer, List<Integer>>[] liquidByPhase = new TreeMap[state.cellCount()];
		for (int marker = 0; marker < state.particles().count(); marker++) {
			int cell = access.particleCell(marker);
			if (!dirty[cell]) continue;
			int phase = chemistry.registry().phaseHandleForMaterial(
					access.materialRegistry().material(state.particles().materialHandle(marker)).id());
			if (liquidByPhase[cell] == null) liquidByPhase[cell] = new TreeMap<>();
			liquidByPhase[cell].computeIfAbsent(phase, ignored -> new ArrayList<>()).add(marker);
		}
		int transfers = 0, precipitated = 0, dissolved = 0, evaluations = 0;
		long signedSvq = 0L;
		boolean operationBudgetExhausted = false;
		for (int dirtyIndex = 0; dirtyIndex < dirtyCells.length; dirtyIndex++) {
			int cell = dirtyCells[dirtyIndex];
			if (liquidByPhase[cell] == null) continue;
			for (SolidLiquidReaction rule : granular.registry.reactions()) {
				if (evaluations == GranularLimits.MAX_OPERATIONS_PER_LOGICAL_STEP) {
					for (int deferred = dirtyIndex; deferred < dirtyCells.length; deferred++) {
						chemistry.markDirtyCell(dirtyCells[deferred]);
					}
					operationBudgetExhausted = true;
					break;
				}
				evaluations++;
				int phase = chemistry.registry().phaseHandle(rule.liquidPhaseId());
				List<Integer> markers = liquidByPhase[cell].get(phase);
				if (markers == null || markers.isEmpty()) continue;
				AccessibleSolid accessible = accessibleSolid(cell, rule);
				long existingExtent = accessible == null ? 0L : accessible.totalExtent();
				if (existingExtent == 0L && !hasCompleteAqueousExtent(markers, rule)) continue;
				long perStepBatches = Math.min(rule.accessibleSurfaceSvqPerCellStep(),
						granular.registry.material(granular.registry.materialHandle(
								rule.solidMaterialId())).maximumActiveParcelSvq())
						/ rule.solidVolumeSvqPerBatch();
				long perStepExtent = Math.multiplyExact(perStepBatches, rule.solidExtentPerBatch());
				if (perStepExtent <= 0L) continue;
				List<GranularPhaseTransferEngine.LiquidParcel> liquid = new ArrayList<>(markers.size());
				for (int marker : markers) liquid.add(new GranularPhaseTransferEngine.LiquidParcel(
						marker, state.particles().volume(marker), state.particles().mass(marker), chemistry.record(marker)));
				GranularPhaseTransferEngine.Plan plan = access.transferEngine().plan(
						rule, liquid, existingExtent, perStepExtent);
				if (plan.changed() && !plan.precipitation() && accessible != null
						&& -plan.signedExtent() > accessible.selected().extent()) {
					plan = access.transferEngine().plan(rule, liquid, existingExtent,
							Math.min(perStepExtent, accessible.selected().extent()));
				}
				if (!plan.changed()) {
					if (plan.retryRequired()) chemistry.markDirtyCell(cell);
					continue;
				}
				if (plan.precipitation()) {
					if (!commitPrecipitation(cell, rule, plan)) {
						/*
						 * Geometry and fixed inventory budgets are candidate resources, not
						 * authoritative-state validity. Keep the cell dirty so a later generation
						 * can retry after carriers or active parcels move.
						 */
						chemistry.markDirtyCell(cell);
						continue;
					}
				} else {
					if (accessible == null || accessible.selected() == null) {
						throw new GranularPhaseTransferEngine.BlockedException(
								"Dissolution target has no accessible solid inventory");
					}
					if (!commitDissolution(cell, rule, plan, accessible.selected())) {
						chemistry.markDirtyCell(cell);
						continue;
					}
				}
				transfers++;
				if (plan.precipitation()) precipitated++; else dissolved++;
				signedSvq = Math.addExact(signedSvq, plan.signedSolidSvq());
			}
			if (operationBudgetExhausted) break;
		}
		if (transfers > 0) access.markGridDirty();
		return new FluidDomain.PhaseTransferStats(transfers, precipitated, dissolved, signedSvq);
	}

	private boolean hasCompleteAqueousExtent(List<Integer> markers, SolidLiquidReaction rule) {
		ChemicalParcelStore chemistry = access.chemistry();
		for (Map.Entry<String, Integer> term : rule.aqueousStoichiometry().entrySet()) {
			int species = chemistry.registry().speciesHandle(term.getKey());
			long available = 0L;
			for (int marker : markers) available = Math.addExact(available, chemistry.record(marker).amount(species));
			if (available < term.getValue()) return false;
		}
		return true;
	}

	private AccessibleSolid accessibleSolid(int cell, SolidLiquidReaction rule) {
		GranularDomainState granular = access.granularState();
		long total = 0L;
		SolidEntity selected = null;
		int material = granular.registry.materialHandle(rule.solidMaterialId());
		for (int parcelIndex : activeParcels(cell)) {
			GranularParcelStore.Parcel parcel = granular.parcels.parcel(parcelIndex);
			if (parcel.materialHandle() != material) continue;
			long extent = matchingSolidExtent(
					parcel.solidCompositionHandle(), parcel.solidVolumeSvq(), parcel.mass(), rule);
			if (extent <= 0L) continue;
			total = Math.addExact(total, extent);
			if (selected == null) selected = SolidEntity.parcel(parcelIndex, parcel, extent);
		}
		GranularDepositStore.Group exposed = granular.deposits.dominantGroup(cell);
		if (exposed != null && exposed.materialHandle() == material) {
			long extent = matchingSolidExtent(
					exposed.solidCompositionHandle(), exposed.solidVolumeSvq(), exposed.mass(), rule);
			if (extent > 0L) {
				total = Math.addExact(total, extent);
				if (selected == null) selected = SolidEntity.deposit(cell, exposed, extent);
			}
		}
		return total == 0L ? null : new AccessibleSolid(total, selected);
	}

	private List<Integer> activeParcels(int cell) {
		if (activeByCell == null) {
			activeByCell = new java.util.HashMap<>();
			GranularParcelStore parcels = access.granularState().parcels;
			for (int ordinal = 0; ordinal < parcels.count(); ordinal++) {
				int parcelCell = access.parcelCell(parcels.parcel(ordinal));
				activeByCell.computeIfAbsent(parcelCell, ignored -> new ArrayList<>()).add(ordinal);
			}
		}
		return activeByCell.getOrDefault(cell, List.of());
	}

	private long matchingSolidExtent(
			int compositionHandle, long solidSvq, double mass, SolidLiquidReaction rule
	) {
		GranularDomainState granular = access.granularState();
		ChemicalParcelStore chemistry = access.chemistry();
		if (compositionHandle == 0 || !granular.compositions.isLive(compositionHandle)) return 0L;
		SolidCompositionRecord composition = granular.compositions.record(compositionHandle);
		if (composition.massResidual() != 0.0 || composition.entryCount() != rule.solidComposition().size()
				|| !FluidDomain.massClose(composition.totalMass(chemistry.registry()), mass)) return 0L;
		long extent = -1L;
		for (Map.Entry<String, Integer> term : rule.solidComposition().entrySet()) {
			long amount = composition.amount(chemistry.registry().speciesHandle(term.getKey()));
			if (amount <= 0L || amount % term.getValue() != 0L) return 0L;
			long candidate = amount / term.getValue();
			if (extent < 0L) extent = candidate; else if (extent != candidate) return 0L;
		}
		return extent > 0L && extent % rule.solidExtentPerBatch() == 0L
				&& Math.multiplyExact(extent / rule.solidExtentPerBatch(), rule.solidVolumeSvqPerBatch()) == solidSvq
				? extent : 0L;
	}

	private boolean commitPrecipitation(
			int cell, SolidLiquidReaction rule, GranularPhaseTransferEngine.Plan plan
	) {
		ChemicalParcelStore chemistry = access.chemistry();
		GranularDomainState granular = access.granularState();
		SignedDyadic pendingVolume = planGranularPhaseVolumeEvent(plan);
		int[] markers = updateMarkers(plan);
		ChemicalMixtureRecord[] mixtures = updateMixtures(plan);
		if (!canReplaceMarkers(markers, mixtures)) return false;
		JointPhaseCapacity capacity = reactionCapacity(plan);
		int carrierMarker = precipitationCarrierMarker(cell, plan);
		int x = cell % state.sizeX(), yz = cell / state.sizeX();
		int y = yz % state.sizeY(), z = yz / state.sizeY();
		long spawnSequence = ((long) cell << 16) + granular.parcels.count();
		GranularCouplingFields.Position position = GranularReactionMomentum.precipitationPosition(
				state.particles(), chemistry.unitScale(), plan);
		float h = state.cellSize();
		boolean useMassMomentPosition = position != null && position.x() >= x * h && position.x() < (x + 1) * h
				&& position.y() >= y * h && position.y() < (y + 1) * h
				&& position.z() >= z * h && position.z() < (z + 1) * h
				&& access.collision().positionValid(position.x(), position.y(), position.z(), plan.signedSolidSvq());
		if (!useMassMomentPosition) {
			position = access.collision().spawnPosition(x, y, z, plan.signedSolidSvq(), spawnSequence,
					state.particles().positionX(carrierMarker), state.particles().positionY(carrierMarker),
					state.particles().positionZ(carrierMarker));
		}
		refreshOccupancy();
		int material = granular.registry.materialHandle(rule.solidMaterialId());
		GranularReactionMomentum.Plan momentum = GranularReactionMomentum.plan(
				state.particles(), chemistry.unitScale(), plan, 0, 0, 0);
		PrecipitationMerge merge = precipitationMerge(
				cell, material, plan, position, momentum, capacity);
		if (useMassMomentPosition && merge == null && (!granular.compositions.hasCapacity(1)
				|| !granular.parcels.hasCapacity(1) || !capacity.fitsReplacement(
				access.coupling(), null, 0, position, plan.signedSolidSvq()))) {
			position = access.collision().spawnPosition(x, y, z, plan.signedSolidSvq(), spawnSequence,
					state.particles().positionX(carrierMarker), state.particles().positionY(carrierMarker),
					state.particles().positionZ(carrierMarker));
			merge = precipitationMerge(cell, material, plan, position, momentum, capacity);
		}
		if (merge == null && (position == null || !capacity.fitsReplacement(
				access.coupling(), null, 0, position, plan.signedSolidSvq()))) {
			position = new GranularCouplingFields.Position((x + .5F) * h, (y + .5F) * h, (z + .5F) * h);
			if (!access.collision().positionValid(position.x(), position.y(), position.z(), plan.signedSolidSvq())
					|| !capacity.fitsReplacement(access.coupling(), null, 0, position, plan.signedSolidSvq())) return false;
			merge = precipitationMerge(cell, material, plan, position, momentum, capacity);
		}
		ThermalDomainState thermal = access.thermalState();
		if (merge != null) {
			ThermalDomainState.ReactionPlan thermalPlan = thermal == null ? null : planThermalReaction(
					cell, thermalReactionMarkers(plan), new GranularEnergyState.GranularReactionEdit(
							GranularEnergyState.GranularReactionKind.REPLACE_ACTIVE, merge.parcelIndex(), null,
							material, merge.parcel().mass(),
							merge.composition().stableAmounts(chemistry.registry())));
			if (thermal != null && thermalPlan == null) return false;
			if (thermalPlan != null) thermal.requireGranularReactionCurrent(thermalPlan);
			chemistry.commitPlannedReplacements(markers, mixtures);
			applyMarkerUpdates(plan, momentum);
			granular.compositions.overwrite(merge.compositionHandle(), merge.composition());
			granular.parcels.overwrite(merge.parcelIndex(), merge.parcel());
			if (thermalPlan != null) thermal.commitGranularReaction(thermalPlan, state.thermalMarkers());
			commitPendingPhaseVolume(cell, pendingVolume);
			liquidVolumes = capacity.liquid();
			markPhaseTransferDirty(cell);
			return true;
		}
		if (!granular.compositions.hasCapacity(1) || !granular.parcels.hasCapacity(1)) {
			return false;
		}
		ThermalDomainState.ReactionPlan thermalPlan = thermal == null ? null : planThermalReaction(
				cell, thermalReactionMarkers(plan), new GranularEnergyState.GranularReactionEdit(
						GranularEnergyState.GranularReactionKind.ADD_ACTIVE, -1, null, material, plan.signedSolidMass(),
						plan.transferredComposition().stableAmounts(chemistry.registry())));
		if (thermal != null && thermalPlan == null) return false;
		if (thermalPlan != null) thermal.requireGranularReactionCurrent(thermalPlan);
		int composition = granular.compositions.create(plan.transferredComposition());
		GranularParcelStore.Parcel parcel;
		try {
			parcel = new GranularParcelStore.Parcel(position.x(), position.y(), position.z(),
					momentum.solidX(), momentum.solidY(), momentum.solidZ(), plan.signedSolidSvq(), plan.signedSolidMass(),
					material, composition, 0, (byte) 0);
			if (!granular.parcels.canAdd(parcel)) throw new GranularPhaseTransferEngine.BlockedException(
					"Precipitation active parcel budget exceeded");
			chemistry.commitPlannedReplacements(markers, mixtures);
		} catch (RuntimeException failure) {
			granular.compositions.release(composition);
			throw failure;
		}
		applyMarkerUpdates(plan, momentum);
		granular.parcels.add(parcel);
		if (thermalPlan != null) {
			thermal.addGranularReactionActive();
			thermal.commitGranularReaction(thermalPlan, state.thermalMarkers());
		}
		commitPendingPhaseVolume(cell, pendingVolume);
		liquidVolumes = capacity.liquid();
		markPhaseTransferDirty(cell);
		return true;
	}

	private PrecipitationMerge precipitationMerge(
			int cell, int material, GranularPhaseTransferEngine.Plan plan,
			GranularCouplingFields.Position spawned, GranularReactionMomentum.Plan momentum, JointPhaseCapacity capacity
	) {
		if (spawned == null) return null;
		GranularDomainState granular = access.granularState();
		long maximum = granular.registry.material(material).maximumActiveParcelSvq();
		float velocityX = momentum.solidX();
		float velocityY = momentum.solidY();
		float velocityZ = momentum.solidZ();
		float maximumVelocityDelta = access.mergeVelocityDelta();
		for (int parcelIndex : activeParcels(cell)) {
			GranularParcelStore.Parcel existing = granular.parcels.parcel(parcelIndex);
			if (existing.materialHandle() != material || existing.solidCompositionHandle() == 0) continue;
			long combinedSvq = Math.addExact(existing.solidVolumeSvq(), plan.signedSolidSvq());
			if (combinedSvq > maximum || !plan.transferredComposition().proportionalTo(
					granular.compositions.record(existing.solidCompositionHandle()),
					plan.signedSolidSvq(), existing.solidVolumeSvq())) continue;
			float deltaX = existing.velocityX() - velocityX;
			float deltaY = existing.velocityY() - velocityY;
			float deltaZ = existing.velocityZ() - velocityZ;
			if (deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ
					> maximumVelocityDelta * maximumVelocityDelta) continue;
			if (!GranularMergeKinematics.preservesAngularMomentum(existing,
					spawned.x(), spawned.y(), spawned.z(), velocityX, velocityY, velocityZ)) continue;
			double combinedMass = existing.mass() + plan.signedSolidMass();
			try { PhaseChangeStageSupport.representedPhysicalMassTransfer(
					existing.mass(), combinedMass, plan.signedSolidMass()); }
			catch (PhaseChangeStageSupport.PhaseCandidateUnavailableException unavailable) { continue; }
			float x = weighted(existing.x(), existing.mass(), spawned.x(), plan.signedSolidMass(), combinedMass);
			float y = weighted(existing.y(), existing.mass(), spawned.y(), plan.signedSolidMass(), combinedMass);
			float z = weighted(existing.z(), existing.mass(), spawned.z(), plan.signedSolidMass(), combinedMass);
			if (!access.collision().positionValid(x, y, z, combinedSvq)) continue;
			SolidCompositionRecord composition = granular.compositions.record(existing.solidCompositionHandle())
					.plus(plan.transferredComposition());
			GranularParcelStore.Parcel merged = new GranularParcelStore.Parcel(
					x, y, z,
					weighted(existing.velocityX(), existing.mass(), velocityX, plan.signedSolidMass(), combinedMass),
					weighted(existing.velocityY(), existing.mass(), velocityY, plan.signedSolidMass(), combinedMass),
					weighted(existing.velocityZ(), existing.mass(), velocityZ, plan.signedSolidMass(), combinedMass),
					combinedSvq, combinedMass, material, existing.solidCompositionHandle(), 0, (byte) 0);
			if (!capacity.fitsReplacement(access.coupling(),
					new GranularCouplingFields.Position(existing.x(), existing.y(), existing.z()), existing.solidVolumeSvq(),
					new GranularCouplingFields.Position(x, y, z), combinedSvq)) continue;
			return new PrecipitationMerge(
					parcelIndex, existing.solidCompositionHandle(), composition, merged);
		}
		return null;
	}

	private int precipitationCarrierMarker(int cell, GranularPhaseTransferEngine.Plan plan) {
		int candidates = 0;
		for (GranularPhaseTransferEngine.MarkerUpdate update : plan.markerUpdates()) {
			if (state.particles().volume(update.marker()) > update.cellVolume()) candidates++;
		}
		if (candidates == 0) throw new GranularPhaseTransferEngine.BlockedException(
				"Precipitation did not remove volume from a carrier marker");
		int selected = Math.floorMod((long) cell * 31L + access.granularState().parcels.count(), candidates);
		for (GranularPhaseTransferEngine.MarkerUpdate update : plan.markerUpdates()) {
			if (state.particles().volume(update.marker()) <= update.cellVolume()) continue;
			if (selected-- == 0) return update.marker();
		}
		throw new IllegalStateException("Precipitation carrier selection changed during planning");
	}

	private boolean commitDissolution(
			int cell, SolidLiquidReaction rule, GranularPhaseTransferEngine.Plan plan, SolidEntity solid
	) {
		ChemicalParcelStore chemistry = access.chemistry();
		GranularDomainState granular = access.granularState();
		SignedDyadic pendingVolume = planGranularPhaseVolumeEvent(plan);
		if (!rule.reversible()) throw new GranularPhaseTransferEngine.BlockedException(
				"Solid-liquid rule is not reversible");
		long removedSvq = -plan.signedSolidSvq();
		double removedMass = -plan.signedSolidMass();
		if (removedSvq <= 0L || removedSvq > solid.solidSvq()) {
			throw new GranularPhaseTransferEngine.BlockedException(
					"Dissolution exceeds accessible solid inventory");
		}
		SolidCompositionRecord original = granular.compositions.record(solid.compositionHandle());
		boolean full = removedSvq == solid.solidSvq();
		SolidCompositionRecord remaining = full ? null : subtractComposition(
				original, plan.transferredComposition());
		double remainingMass = solid.mass() - removedMass;
		try { PhaseChangeStageSupport.representedPhysicalMassTransfer(
				solid.mass(), full ? 0.0 : remainingMass, -removedMass); }
		catch (PhaseChangeStageSupport.PhaseCandidateUnavailableException unavailable) { return false; }
		if (!full && (!(remainingMass > 0.0)
				|| !FluidDomain.massClose(remaining.totalMass(chemistry.registry()), remainingMass))) {
			return false;
		}
		if (solid.deposit()) {
			GranularDepositStore.SolidPortion preview = granular.deposits.peekTakeCompositionAware(
					cell, solid.materialHandle(), solid.compositionHandle(), removedSvq);
			if (!FluidDomain.massClose(preview.mass(), removedMass)) {
				return false;
			}
		}
		int[] markers = updateMarkers(plan);
		ChemicalMixtureRecord[] mixtures = updateMixtures(plan);
		if (!canReplaceMarkers(markers, mixtures)) return false;
		GranularParcelStore.Parcel donor = solid.deposit() ? null : granular.parcels.parcel(solid.index());
		JointPhaseCapacity capacity = reactionCapacity(plan);
		refreshOccupancy();
		if (solid.deposit()) {
			if (!capacity.fitsDepositRemoval(access.coupling(), cell, removedSvq)) return false;
		} else {
			var position = new GranularCouplingFields.Position(donor.x(), donor.y(), donor.z());
			if (!capacity.fitsReplacement(access.coupling(), position, donor.solidVolumeSvq(),
					position, donor.solidVolumeSvq() - removedSvq)) return false;
		}
		GranularReactionMomentum.Plan momentum = GranularReactionMomentum.plan(
				state.particles(), chemistry.unitScale(), plan,
				donor == null ? 0 : donor.velocityX(), donor == null ? 0 : donor.velocityY(),
				donor == null ? 0 : donor.velocityZ());
		GranularEnergyColumn.DepositKey thermalDepositKey = solid.deposit()
				? new GranularEnergyColumn.DepositKey(cell, solid.materialHandle(), solid.compositionHandle()) : null;
		GranularEnergyState.GranularReactionKind thermalKind = solid.deposit()
				? (full ? GranularEnergyState.GranularReactionKind.REMOVE_DEPOSIT
				: GranularEnergyState.GranularReactionKind.REPLACE_DEPOSIT)
				: (full ? GranularEnergyState.GranularReactionKind.REMOVE_ACTIVE
				: GranularEnergyState.GranularReactionKind.REPLACE_ACTIVE);
		Map<String, Long> finalComposition = full ? Map.of() : remaining.stableAmounts(chemistry.registry());
		ThermalDomainState thermal = access.thermalState();
		ThermalDomainState.ReactionPlan thermalPlan = thermal == null ? null : planThermalReaction(
				cell, thermalReactionMarkers(plan), new GranularEnergyState.GranularReactionEdit(
						thermalKind, solid.deposit() ? -1 : solid.index(), thermalDepositKey,
						full ? -1 : solid.materialHandle(), full ? 0.0 : remainingMass, finalComposition));
		if (thermal != null && thermalPlan == null) return false;
		if (thermal != null) {
			if (solid.deposit()) thermal.previewDepositTake(thermalDepositKey, removedSvq, solid.solidSvq());
			else thermal.previewActiveTake(solid.index(), removedSvq, solid.solidSvq());
			thermal.requireGranularReactionCurrent(thermalPlan);
		}
		chemistry.commitPlannedReplacements(markers, mixtures);
		applyMarkerUpdates(plan, momentum);
		if (solid.deposit()) granular.deposits.takeCompositionAware(
				cell, solid.materialHandle(), solid.compositionHandle(), removedSvq);
		else if (full) granular.parcels.removeSwap(solid.index());
		else {
			GranularParcelStore.Parcel parcel = granular.parcels.parcel(solid.index());
			granular.parcels.overwrite(solid.index(), new GranularParcelStore.Parcel(
					parcel.x(), parcel.y(), parcel.z(), parcel.velocityX(), parcel.velocityY(), parcel.velocityZ(),
					parcel.solidVolumeSvq() - removedSvq, remainingMass, parcel.materialHandle(),
					parcel.solidCompositionHandle(), parcel.contactAge(), parcel.flags()));
		}
		if (thermalPlan != null) {
			if (solid.deposit()) thermal.takeGranularReactionDeposit(
					thermalDepositKey, removedSvq, solid.solidSvq());
			else thermal.takeGranularReactionActive(solid.index(), removedSvq, solid.solidSvq());
		}
		if (full) granular.compositions.release(solid.compositionHandle());
		else granular.compositions.overwrite(solid.compositionHandle(), remaining);
		if (thermalPlan != null) thermal.commitGranularReaction(thermalPlan, state.thermalMarkers());
		commitPendingPhaseVolume(cell, pendingVolume);
		liquidVolumes = capacity.liquid();
		markPhaseTransferDirty(cell);
		return true;
	}

	private JointPhaseCapacity reactionCapacity(GranularPhaseTransferEngine.Plan plan) {
		if (liquidVolumes == null) liquidVolumes = JointPhaseCapacity.collectLiquid(
				state.cellCount(), state.particles(), access::particleCell);
		return JointPhaseCapacity.plan(liquidVolumes, state.particles(),
				access::particleCell, access::geometricOpenSvq, plan);
	}

	private void refreshOccupancy() {
		if (!occupancyCurrent) {
			var granular = access.granularState();
			access.coupling().rebuild(granular.parcels, granular.deposits);
			occupancyCurrent = true;
		}
	}

	private ThermalDomainState.ReactionPlan planThermalReaction(int cell,
			List<GranularEnergyState.ReactionMarker> replacements, GranularEnergyState.GranularReactionEdit edit) {
		try {
			return access.thermalState().requireGranularReaction(cell, replacements, edit, state.thermalMarkers());
		} catch (ThermalStateFunction.EnergyOutsideValidityException
				| ThermalStateFunction.EnergyRepresentationException
				| ThermalDomainState.PhaseCellEnergyUnavailableException unavailable) {
			return null;
		}
	}

	private boolean canReplaceMarkers(int[] markers, ChemicalMixtureRecord[] mixtures) {
		try {
			access.chemistry().requireCanCommitPlannedReplacements(markers, mixtures);
			return true;
		} catch (ChemicalReactionEngine.BudgetExceededException unavailable) {
			return false;
		}
	}

	private SignedDyadic planGranularPhaseVolumeEvent(GranularPhaseTransferEngine.Plan plan) {
		if (access.thermalState() == null) return SignedDyadic.ZERO;
		SignedDyadic delta = cellVolumeDyadic(plan.signedSolidSvq());
		for (GranularPhaseTransferEngine.MarkerUpdate update : plan.markerUpdates()) {
			delta = delta.add(SignedDyadic.of(update.cellVolume()))
					.subtract(SignedDyadic.of(state.particles().volume(update.marker())));
		}
		return delta;
	}

	private void commitPendingPhaseVolume(int cell, SignedDyadic signedCellVolume) {
		if (access.thermalState() != null && !signedCellVolume.equals(SignedDyadic.ZERO)) {
			state.pendingPhaseCellVolume()[cell] = state.pendingPhaseCellVolume()[cell].add(signedCellVolume);
		}
	}

	private static SignedDyadic cellVolumeDyadic(long signedSvq) {
		return signedSvq == 0L ? SignedDyadic.ZERO
				: new SignedDyadic(BigInteger.valueOf(signedSvq), -24);
	}

	private SolidCompositionRecord subtractComposition(
			SolidCompositionRecord original, SolidCompositionRecord removed
	) {
		ChemicalParcelStore chemistry = access.chemistry();
		TreeMap<String, Long> remaining = new TreeMap<>();
		for (int entry = 0; entry < original.entryCount(); entry++) {
			int species = original.speciesHandle(entry);
			long amount = original.amountAt(entry) - removed.amount(species);
			if (amount < 0L) throw new GranularPhaseTransferEngine.BlockedException(
					"Dissolution composition underflow");
			if (amount > 0L) remaining.put(chemistry.registry().species(species).id(), amount);
		}
		if (remaining.isEmpty()) throw new GranularPhaseTransferEngine.BlockedException(
				"Partial dissolution emptied solid composition");
		return SolidCompositionRecord.fromStableAmounts(
				chemistry.registry(), remaining, original.massResidual());
	}

	private static int[] updateMarkers(GranularPhaseTransferEngine.Plan plan) {
		int[] result = new int[plan.markerUpdates().size()];
		for (int index = 0; index < result.length; index++) result[index] = plan.markerUpdates().get(index).marker();
		return result;
	}

	private static ChemicalMixtureRecord[] updateMixtures(GranularPhaseTransferEngine.Plan plan) {
		ChemicalMixtureRecord[] result = new ChemicalMixtureRecord[plan.markerUpdates().size()];
		for (int index = 0; index < result.length; index++) result[index] = plan.markerUpdates().get(index).mixture();
		return result;
	}

	private static List<GranularEnergyState.ReactionMarker> thermalReactionMarkers(
			GranularPhaseTransferEngine.Plan plan
	) {
		ArrayList<GranularEnergyState.ReactionMarker> result = new ArrayList<>(plan.markerUpdates().size());
		for (GranularPhaseTransferEngine.MarkerUpdate update : plan.markerUpdates()) {
			result.add(new GranularEnergyState.ReactionMarker(update.marker(), update.mass(), update.mixture()));
		}
		return List.copyOf(result);
	}

	private void applyMarkerUpdates(GranularPhaseTransferEngine.Plan plan, GranularReactionMomentum.Plan momentum) {
		for (int index = 0; index < plan.markerUpdates().size(); index++) {
			GranularPhaseTransferEngine.MarkerUpdate update = plan.markerUpdates().get(index);
			state.particles().setVolume(update.marker(), update.cellVolume());
			state.particles().setMass(update.marker(), update.mass());
			state.particles().setVelocityX(update.marker(), momentum.x()[index]);
			state.particles().setVelocityY(update.marker(), momentum.y()[index]);
			state.particles().setVelocityZ(update.marker(), momentum.z()[index]);
		}
	}

	private void markPhaseTransferDirty(int cell) {
		activeByCell = null; // A commit can append, move or swap-remove an active parcel.
		occupancyCurrent = false;
		access.chemistry().markDirtyCell(cell);
		access.depositDynamics().disturbCell(cell);
		access.markGridDirty();
	}

	private static float weighted(float left, double leftWeight, float right, double rightWeight, double total) {
		return (float) ((left * leftWeight + right * rightWeight) / total);
	}

	private record AccessibleSolid(long totalExtent, SolidEntity selected) { }
	private record PrecipitationMerge(
			int parcelIndex, int compositionHandle, SolidCompositionRecord composition,
			GranularParcelStore.Parcel parcel
	) { }
	private record SolidEntity(
			boolean deposit, int index, int materialHandle, int compositionHandle,
			long solidSvq, double mass, long extent
	) {
		static SolidEntity parcel(int index, GranularParcelStore.Parcel parcel, long extent) {
			return new SolidEntity(false, index, parcel.materialHandle(), parcel.solidCompositionHandle(),
					parcel.solidVolumeSvq(), parcel.mass(), extent);
		}
		static SolidEntity deposit(int cell, GranularDepositStore.Group group, long extent) {
			return new SolidEntity(true, cell, group.materialHandle(), group.solidCompositionHandle(),
					group.solidVolumeSvq(), group.mass(), extent);
		}
	}
}

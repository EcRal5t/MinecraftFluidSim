package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.chemistry.ChemicalMixtureRecord;
import cc.ecisr.mmm.chemistry.ChemicalParcelStore;
import cc.ecisr.mmm.granular.GranularCouplingFields;
import cc.ecisr.mmm.granular.GranularDepositDynamics;
import cc.ecisr.mmm.granular.GranularDepositStore;
import cc.ecisr.mmm.granular.GranularLimits;
import cc.ecisr.mmm.granular.GranularParcelStore;
import cc.ecisr.mmm.granular.GranularPhaseTransferEngine;
import cc.ecisr.mmm.granular.SolidCompositionRecord;
import cc.ecisr.mmm.granular.SolidLiquidReaction;
import cc.ecisr.mmm.thermal.GranularEnergyColumn;
import cc.ecisr.mmm.thermal.GranularEnergyState;
import cc.ecisr.mmm.thermal.ThermalCarrierContext;
import cc.ecisr.mmm.thermal.ThermalDomainState;
import cc.ecisr.mmm.units.SignedDyadic;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Plans and commits exact liquid/solid precipitation and dissolution inside a private candidate. */
final class GranularReactionStage {
	record Workspace(
			int sizeX, int sizeY, int cellCount,
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
		FlipThermalState thermalState();
		int particleCell(int marker);
		int parcelCell(GranularParcelStore.Parcel parcel);
		long geometricOpenSvq(int cell);
		float mergeVelocityDelta();
		void markGridDirty();
	}

	private final Workspace state;
	private final Access access;

	GranularReactionStage(Workspace state, Access access) {
		this.state = state;
		this.access = access;
	}

	FlipFluidDomain.PhaseTransferStats step(int[] dirtyCells) {
		if (!access.enabled() || dirtyCells.length == 0) {
			return new FlipFluidDomain.PhaseTransferStats(0, 0, 0, 0L);
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
		for (int cell : dirtyCells) {
			if (liquidByPhase[cell] == null) continue;
			for (SolidLiquidReaction rule : granular.registry.reactions()) {
				if (++evaluations > GranularLimits.MAX_OPERATIONS_PER_LOGICAL_STEP) {
					throw new GranularPhaseTransferEngine.BlockedException("Phase-transfer operation budget exceeded");
				}
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
				if (perStepExtent <= 0L) throw new GranularPhaseTransferEngine.BlockedException(
						"Solid-liquid rule is below one representable transfer extent");
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
				if (!plan.changed()) continue;
				if (plan.precipitation()) commitPrecipitation(cell, rule, plan);
				else {
					if (accessible == null || accessible.selected() == null) {
						throw new GranularPhaseTransferEngine.BlockedException(
								"Dissolution target has no accessible solid inventory");
					}
					commitDissolution(cell, rule, plan, accessible.selected());
				}
				transfers++;
				if (plan.precipitation()) precipitated++; else dissolved++;
				signedSvq = Math.addExact(signedSvq, plan.signedSolidSvq());
			}
		}
		if (transfers > 0) access.markGridDirty();
		return new FlipFluidDomain.PhaseTransferStats(transfers, precipitated, dissolved, signedSvq);
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
		for (int parcelIndex = 0; parcelIndex < granular.parcels.count(); parcelIndex++) {
			GranularParcelStore.Parcel parcel = granular.parcels.parcel(parcelIndex);
			if (parcel.materialHandle() != material || access.parcelCell(parcel) != cell) continue;
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

	private long matchingSolidExtent(
			int compositionHandle, long solidSvq, double mass, SolidLiquidReaction rule
	) {
		GranularDomainState granular = access.granularState();
		ChemicalParcelStore chemistry = access.chemistry();
		if (compositionHandle == 0 || !granular.compositions.isLive(compositionHandle)) return 0L;
		SolidCompositionRecord composition = granular.compositions.record(compositionHandle);
		if (composition.massResidual() != 0.0 || composition.entryCount() != rule.solidComposition().size()
				|| !FlipFluidDomain.massClose(composition.totalMass(chemistry.registry()), mass)) return 0L;
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

	private void commitPrecipitation(
			int cell, SolidLiquidReaction rule, GranularPhaseTransferEngine.Plan plan
	) {
		ChemicalParcelStore chemistry = access.chemistry();
		GranularDomainState granular = access.granularState();
		SignedDyadic pendingVolume = planGranularPhaseVolumeEvent(plan);
		int[] markers = updateMarkers(plan);
		ChemicalMixtureRecord[] mixtures = updateMixtures(plan);
		chemistry.requireCanCommitPlannedReplacements(markers, mixtures);
		int carrierMarker = precipitationCarrierMarker(cell, plan);
		int x = cell % state.sizeX(), yz = cell / state.sizeX();
		int y = yz % state.sizeY(), z = yz / state.sizeY();
		long spawnSequence = ((long) cell << 16) + granular.parcels.count();
		GranularCouplingFields.Position position = access.collision().spawnPosition(
				x, y, z, plan.signedSolidSvq(), spawnSequence,
				state.particles().positionX(carrierMarker), state.particles().positionY(carrierMarker),
				state.particles().positionZ(carrierMarker));
		if (position == null) throw new GranularPhaseTransferEngine.BlockedException(
				"Precipitation cell has no collision-valid spawn point");
		GranularCouplingFields coupling = access.coupling();
		coupling.rebuild(granular.parcels, granular.deposits);
		long[] occupiedBefore = coupling.copySolidSvq();
		int material = granular.registry.materialHandle(rule.solidMaterialId());
		PrecipitationMerge merge = precipitationMerge(
				cell, material, plan, position, carrierMarker, occupiedBefore);
		FlipThermalState thermal = access.thermalState();
		if (merge != null) {
			ThermalDomainState.ReactionPlan thermalPlan = thermal == null ? null : thermal.requireGranularReaction(
					cell, thermalReactionMarkers(plan), new GranularEnergyState.GranularReactionEdit(
							GranularEnergyState.GranularReactionKind.REPLACE_ACTIVE, merge.parcelIndex(), null,
							material, merge.parcel().mass(),
							merge.composition().stableAmounts(chemistry.registry())), state.thermalMarkers());
			chemistry.commitPlannedReplacements(markers, mixtures);
			applyMarkerUpdates(plan);
			granular.compositions.overwrite(merge.compositionHandle(), merge.composition());
			granular.parcels.overwrite(merge.parcelIndex(), merge.parcel());
			if (thermalPlan != null) thermal.commitGranularReaction(thermalPlan, state.thermalMarkers());
			commitPendingPhaseVolume(cell, pendingVolume);
			markPhaseTransferDirty(cell);
			return;
		}
		if (!granular.compositions.hasCapacity(1) || !granular.parcels.hasCapacity(1)) {
			throw new GranularPhaseTransferEngine.BlockedException("Precipitation parcel/composition budget exceeded");
		}
		if (!coupling.tryPlanParcel(
				occupiedBefore, position, plan.signedSolidSvq(), access::geometricOpenSvq)) {
			throw new GranularPhaseTransferEngine.BlockedException(
					"Precipitation active parcel exceeds exact joint occupancy");
		}
		int composition = granular.compositions.create(plan.transferredComposition());
		GranularParcelStore.Parcel parcel = new GranularParcelStore.Parcel(
				position.x(), position.y(), position.z(),
				state.particles().velocityX(carrierMarker), state.particles().velocityY(carrierMarker),
				state.particles().velocityZ(carrierMarker), plan.signedSolidSvq(), plan.signedSolidMass(),
				material, composition, 0, (byte) 0);
		if (!granular.parcels.canAdd(parcel)) {
			granular.compositions.release(composition);
			throw new GranularPhaseTransferEngine.BlockedException("Precipitation active parcel budget exceeded");
		}
		ThermalDomainState.ReactionPlan thermalPlan = thermal == null ? null : thermal.requireGranularReaction(
				cell, thermalReactionMarkers(plan), new GranularEnergyState.GranularReactionEdit(
						GranularEnergyState.GranularReactionKind.ADD_ACTIVE, -1, null, material, parcel.mass(),
						plan.transferredComposition().stableAmounts(chemistry.registry())), state.thermalMarkers());
		try {
			chemistry.commitPlannedReplacements(markers, mixtures);
		} catch (RuntimeException failure) {
			granular.compositions.release(composition);
			throw failure;
		}
		applyMarkerUpdates(plan);
		granular.parcels.add(parcel);
		if (thermalPlan != null) {
			thermal.addGranularReactionActive();
			thermal.commitGranularReaction(thermalPlan, state.thermalMarkers());
		}
		commitPendingPhaseVolume(cell, pendingVolume);
		markPhaseTransferDirty(cell);
	}

	private PrecipitationMerge precipitationMerge(
			int cell, int material, GranularPhaseTransferEngine.Plan plan,
			GranularCouplingFields.Position spawned, int carrierMarker, long[] occupiedBefore
	) {
		GranularDomainState granular = access.granularState();
		long maximum = granular.registry.material(material).maximumActiveParcelSvq();
		float velocityX = state.particles().velocityX(carrierMarker);
		float velocityY = state.particles().velocityY(carrierMarker);
		float velocityZ = state.particles().velocityZ(carrierMarker);
		float maximumVelocityDelta = access.mergeVelocityDelta();
		for (int parcelIndex = 0; parcelIndex < granular.parcels.count(); parcelIndex++) {
			GranularParcelStore.Parcel existing = granular.parcels.parcel(parcelIndex);
			if (existing.materialHandle() != material || existing.solidCompositionHandle() == 0
					|| access.parcelCell(existing) != cell) continue;
			long combinedSvq = Math.addExact(existing.solidVolumeSvq(), plan.signedSolidSvq());
			if (combinedSvq > maximum || !plan.transferredComposition().proportionalTo(
					granular.compositions.record(existing.solidCompositionHandle()),
					plan.signedSolidSvq(), existing.solidVolumeSvq())) continue;
			float deltaX = existing.velocityX() - velocityX;
			float deltaY = existing.velocityY() - velocityY;
			float deltaZ = existing.velocityZ() - velocityZ;
			if (deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ
					> maximumVelocityDelta * maximumVelocityDelta) continue;
			double combinedMass = existing.mass() + plan.signedSolidMass();
			float x = weighted(existing.x(), existing.solidVolumeSvq(), spawned.x(), plan.signedSolidSvq(), combinedSvq);
			float y = weighted(existing.y(), existing.solidVolumeSvq(), spawned.y(), plan.signedSolidSvq(), combinedSvq);
			float z = weighted(existing.z(), existing.solidVolumeSvq(), spawned.z(), plan.signedSolidSvq(), combinedSvq);
			if (!access.collision().positionValid(x, y, z, combinedSvq)) continue;
			SolidCompositionRecord composition = granular.compositions.record(existing.solidCompositionHandle())
					.plus(plan.transferredComposition());
			GranularParcelStore.Parcel merged = new GranularParcelStore.Parcel(
					x, y, z,
					weighted(existing.velocityX(), existing.mass(), velocityX, plan.signedSolidMass(), combinedMass),
					weighted(existing.velocityY(), existing.mass(), velocityY, plan.signedSolidMass(), combinedMass),
					weighted(existing.velocityZ(), existing.mass(), velocityZ, plan.signedSolidMass(), combinedMass),
					combinedSvq, combinedMass, material, existing.solidCompositionHandle(), 0, (byte) 0);
			long[] planned = occupiedBefore.clone();
			access.coupling().removePlannedParcel(planned,
					new GranularCouplingFields.Position(existing.x(), existing.y(), existing.z()),
					existing.solidVolumeSvq());
			if (!access.coupling().tryPlanParcel(
					planned, new GranularCouplingFields.Position(x, y, z), combinedSvq,
					access::geometricOpenSvq)) continue;
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

	private void commitDissolution(
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
		if (!full && (!(remainingMass > 0.0)
				|| !FlipFluidDomain.massClose(remaining.totalMass(chemistry.registry()), remainingMass))) {
			throw new GranularPhaseTransferEngine.BlockedException(
					"Dissolution solid mass split is not representable");
		}
		if (solid.deposit()) {
			GranularDepositStore.SolidPortion preview = granular.deposits.peekTakeCompositionAware(
					cell, solid.materialHandle(), solid.compositionHandle(), removedSvq);
			if (!FlipFluidDomain.massClose(preview.mass(), removedMass)) {
				throw new GranularPhaseTransferEngine.BlockedException(
						"Dissolution deposit mass split is not representable");
			}
		}
		int[] markers = updateMarkers(plan);
		ChemicalMixtureRecord[] mixtures = updateMixtures(plan);
		chemistry.requireCanCommitPlannedReplacements(markers, mixtures);
		GranularEnergyColumn.DepositKey thermalDepositKey = solid.deposit()
				? new GranularEnergyColumn.DepositKey(cell, solid.materialHandle(), solid.compositionHandle()) : null;
		GranularEnergyState.GranularReactionKind thermalKind = solid.deposit()
				? (full ? GranularEnergyState.GranularReactionKind.REMOVE_DEPOSIT
				: GranularEnergyState.GranularReactionKind.REPLACE_DEPOSIT)
				: (full ? GranularEnergyState.GranularReactionKind.REMOVE_ACTIVE
				: GranularEnergyState.GranularReactionKind.REPLACE_ACTIVE);
		Map<String, Long> finalComposition = full ? Map.of() : remaining.stableAmounts(chemistry.registry());
		FlipThermalState thermal = access.thermalState();
		ThermalDomainState.ReactionPlan thermalPlan = thermal == null ? null : thermal.requireGranularReaction(
				cell, thermalReactionMarkers(plan), new GranularEnergyState.GranularReactionEdit(
						thermalKind, solid.deposit() ? -1 : solid.index(), thermalDepositKey,
						full ? -1 : solid.materialHandle(), full ? 0.0 : remainingMass, finalComposition),
				state.thermalMarkers());
		if (thermal != null) {
			if (solid.deposit()) thermal.previewDepositTake(thermalDepositKey, removedSvq, solid.solidSvq());
			else thermal.previewActiveTake(solid.index(), removedSvq, solid.solidSvq());
		}
		chemistry.commitPlannedReplacements(markers, mixtures);
		applyMarkerUpdates(plan);
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
		markPhaseTransferDirty(cell);
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

	private void applyMarkerUpdates(GranularPhaseTransferEngine.Plan plan) {
		for (GranularPhaseTransferEngine.MarkerUpdate update : plan.markerUpdates()) {
			state.particles().setVolume(update.marker(), update.cellVolume());
			state.particles().setMass(update.marker(), update.mass());
		}
	}

	private void markPhaseTransferDirty(int cell) {
		access.chemistry().markDirtyCell(cell);
		access.depositDynamics().disturbCell(cell);
		access.markGridDirty();
	}

	private static float weighted(float left, long leftWeight, float right, long rightWeight, long total) {
		return (float) (((double) left * leftWeight + (double) right * rightWeight) / total);
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

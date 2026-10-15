package mmm.fluid;

import mmm.thermal.ThermalDomainState;

import mmm.chemistry.ChemicalMixtureRecord;
import mmm.chemistry.ChemicalParcelStore;
import mmm.fluid.air.AirNetwork;
import mmm.granular.SolidVolume;
import mmm.thermal.CoherentPhaseStore;
import mmm.thermal.ThermalLimits;
import mmm.thermal.ThermalMaterialDefinition;
import mmm.thermal.ThermalPhaseTransition;
import mmm.thermal.ThermalStateFunction;

import java.util.ArrayList;
import java.util.List;

import static mmm.fluid.PhaseChangeStageSupport.PhaseCandidateUnavailableException;
import static mmm.fluid.PhaseChangeStageSupport.PhaseCellInventory;
import static mmm.fluid.PhaseChangeStageSupport.PhaseComposition;
import static mmm.fluid.PhaseChangeStageSupport.PhaseRemovalPlan;
import static mmm.fluid.PhaseChangeStageSupport.massTolerance;

/** Owns exact-batch coherent-solid/liquid planning and commit in the thermal axis. */
final class CondensedPhaseChangeStage {
	private final PhaseChangeStageSupport support;

	CondensedPhaseChangeStage(PhaseChangeStageSupport support) {
		this.support = support;
	}

	FluidDomain.ThermalPhaseStats step(int maximumOperations, boolean phaseGridPrepared) {
		support.prepareGranularOccupancy();
		ThermalDomainState thermal = support.thermal();
		if (thermal == null) throw new IllegalStateException("Thermal state is not bound");
		if (maximumOperations < 0 || maximumOperations > ThermalLimits.MAX_PHASE_OPERATIONS_PER_STEP) {
			throw new IllegalArgumentException("Thermal phase operation budget is invalid");
		}
		if (!phaseGridPrepared) thermal.preparePhaseScan(support.state().thermalMarkers());
		int operations = 0, frozen = 0, melted = 0;
		long signedBatches = 0L;
		for (ThermalPhaseTransition transition : thermal.registry().transitions()) {
			if (transition.interfaceRequirement() != ThermalPhaseTransition.InterfaceRequirement.NONE) continue;
			ThermalMaterialDefinition lower = thermal.registry().material(
					thermal.registry().materialHandle(transition.lowerEnergyMaterialId()));
			ThermalMaterialDefinition upper = thermal.registry().material(
					thermal.registry().materialHandle(transition.upperEnergyMaterialId()));
			if (lower.carrierKind() != ThermalMaterialDefinition.CarrierKind.COHERENT_SOLID
					|| upper.carrierKind() != ThermalMaterialDefinition.CarrierKind.LIQUID) continue;
			int liquidHandle = support.fluidMaterialHandle(upper.carrierId());
			boolean[] targetLiquidCells = new boolean[support.state().cellCount()];
			for (int marker = 0; marker < support.state().particles().count(); marker++) {
				if (support.state().particles().materialHandle(marker) == liquidHandle)
					targetLiquidCells[support.particleCell(marker)] = true;
			}
			for (int cell = 0; cell < support.state().cellCount() && operations < maximumOperations; cell++) {
				int coherentGroup = thermal.coherentPhases().find(cell, lower.id());
				boolean targetLiquidPresent = targetLiquidCells[cell];
				if (!targetLiquidPresent && coherentGroup < 0) continue;
				double absolutePressure = AirNetwork.REFERENCE_PRESSURE + support.pressureReference()
						+ support.state().projection().pressure[cell];
				if (!Double.isFinite(absolutePressure)) throw new FluidDomain.ThermalPhaseBlockedException(
						"Condensed phase pressure is non-finite");
				// Liquid tension has no positive-pressure thermodynamic phase diagram.
				absolutePressure = Math.max(0.0, absolutePressure);
				double temperature = thermal.phaseScanTemperatureInCell(cell);
				double equilibrium = transition.equilibriumTemperatureK(absolutePressure);
				if (temperature == equilibrium
						|| temperature < equilibrium && !targetLiquidPresent
						|| temperature > equilibrium && coherentGroup < 0) continue;
				CondensedCellInventory inventory = phaseCellInventory(cell, liquidHandle, lower.id());
				if (inventory == null) continue;
				JointPhasePlan plan = planJointPhaseChange(
						cell, liquidHandle, transition, inventory, maximumOperations - operations,
						temperature, equilibrium);
				if (plan == null) continue;
				long magnitude = plan.batches();
				if (plan.freezing()) commitFreeze(cell, plan);
				else commitMelt(cell, liquidHandle, transition, plan);
				operations = Math.addExact(operations, Math.toIntExact(magnitude));
				signedBatches = Math.addExact(signedBatches, plan.freezing() ? magnitude : -magnitude);
				if (plan.freezing()) frozen++; else melted++;
			}
		}
		return new FluidDomain.ThermalPhaseStats(operations, frozen, melted, signedBatches);
	}

	private CondensedCellInventory phaseCellInventory(int cell, int liquidHandle, String coherentPhaseId) {
		var particles = support.state().particles(); var chemistry = support.chemistry();
		double liquidMass = 0, coherentMass = 0, freezableMass = 0;
		long liquidSvq = 0, coherentSvq = 0, freezableSvq = 0, unchangedSvq = 0;
		boolean found = false, coherentFound = false;
		for (int marker = 0; marker < particles.count(); marker++) if (support.particleCell(marker) == cell) {
			if (particles.materialHandle(marker) != liquidHandle) {
				unchangedSvq = Math.addExact(unchangedSvq, SolidVolume.cellVolumeSvqAtLeast(particles.volume(marker)));
				continue;
			}
			liquidSvq = Math.addExact(liquidSvq, SolidVolume.cellVolumeSvqAtLeast(particles.volume(marker)));
			liquidMass += support.state().unitScale().kilograms(particles.mass(marker)); found = true;
			if (chemistry == null || !chemistry.indicator(marker)) {
				freezableSvq = Math.addExact(freezableSvq, SolidVolume.cellVolumeSvqAtMost(particles.volume(marker)));
				freezableMass += support.state().unitScale().kilograms(particles.mass(marker));
			}
		}
		for (var group : support.thermal().coherentPhases().snapshot()) if (group.cell() == cell) {
			if (!group.phaseId().equals(coherentPhaseId)) {
				unchangedSvq = Math.addExact(unchangedSvq, group.occupiedVolumeSvq()); continue;
			}
			if (coherentFound) throw new IllegalStateException("Phase cell contains duplicate coherent groups");
			if (chemistry != null && group.composition() == null || chemistry == null && group.composition() != null)
				throw new FluidDomain.ThermalPhaseBlockedException("Coherent composition/chemistry capability mismatch");
			coherentFound = true; found = true;
			coherentSvq = group.occupiedVolumeSvq(); coherentMass = group.mass();
		}
		return found ? new CondensedCellInventory(new PhaseCellInventory(coherentSvq, liquidSvq,
				coherentMass, liquidMass, freezableSvq, freezableMass), unchangedSvq) : null;
	}

	private JointPhasePlan planJointPhaseChange(
			int cell, int liquidHandle, ThermalPhaseTransition transition,
			CondensedCellInventory inventory, long maximumBatches, double temperature, double equilibrium
	) {
		PhaseCellInventory before = inventory.target();
		if (temperature == equilibrium || maximumBatches == 0L) return null;
		double batchMass = transition.massMuPerBatch().toDouble();
		if (!(Double.isFinite(batchMass) && batchMass > 0.0)) {
			throw new FluidDomain.ThermalPhaseBlockedException(
					"Condensed phase batch mass is not representable");
		}
		boolean freezing = temperature < equilibrium;
		long volumeInventory = freezing ? before.freezableUpperSvq() : before.lowerVolumeSvq();
		long volumeBatch = freezing ? transition.upperVolumeSvqPerBatch()
				: transition.lowerVolumeSvqPerBatch();
		double massInventory = freezing ? before.freezableUpperMass() : before.lowerMass();
		long byVolume = volumeInventory / volumeBatch;
		long byMass = (long) Math.floor((massInventory + massTolerance(massInventory)) / batchMass);
		long high = Math.min(maximumBatches, Math.min(byVolume, byMass));
		long signedVolumePerBatch = freezing
				? transition.lowerVolumeSvqPerBatch() - transition.upperVolumeSvqPerBatch()
				: transition.upperVolumeSvqPerBatch() - transition.lowerVolumeSvqPerBatch();
		long occupied = Math.addExact(
				Math.addExact(before.lowerVolumeSvq(), before.upperVolumeSvq()),
				Math.addExact(support.granularSvq(cell), inventory.unchangedSvq()));
		long capacity = SolidVolume.fromCellVolumeExact(
				support.state().geometry().cutCells.openCellVolume(cell));
		if (signedVolumePerBatch > 0L) high = Math.min(high, (capacity - occupied) / signedVolumePerBatch);
		long minimumBatches = minimumBatchesForCapacity(occupied, capacity, signedVolumePerBatch);
		if (high < minimumBatches) return null;
		boolean capacityForced = occupied > capacity;
		var thermalCell = support.thermal().preparePhaseCandidateCell(cell, support.state().thermalMarkers());
		var liquid = new PhaseLiquidInventory(support, cell);
		var initialLiquid = liquid.inventory();
		double maximumMass = transition.massMuPerBatch().multiply(high).toDouble();
		double massError = Math.nextUp((initialLiquid.size() + 4.0) * massTolerance(maximumMass)
				+ (initialLiquid.size() + 2.0) * Math.ulp(maximumMass));
		var registry = support.thermal().registry();
		var extent = support.thermal().preparePhaseExtentCurve(thermalCell, initialLiquid,
				registry.materialHandle(freezing ? transition.upperEnergyMaterialId() : transition.lowerEnergyMaterialId()),
				registry.materialHandle(freezing ? transition.lowerEnergyMaterialId() : transition.upperEnergyMaterialId()),
				equilibrium, maximumMass, massError);
		if (extent != null) {
			long center = GasPhaseBatchSearch.upperBound(high, batches ->
					extent.beforeRoot(transition.massMuPerBatch().multiply(batches).toDouble()));
			long left = Math.max(minimumBatches - 1, center), right = Math.max(minimumBatches, center + 1);
			JointPhasePlan best = null;
			while (left >= minimumBatches || right <= high) {
				double leftDistance = left < minimumBatches ? Double.POSITIVE_INFINITY
						: extent.distanceLower(transition.massMuPerBatch().multiply(left).toDouble());
				double rightDistance = right > high ? Double.POSITIVE_INFINITY
						: extent.distanceLower(transition.massMuPerBatch().multiply(right).toDouble());
				double acceptedDistance = best != null ? Math.abs(best.temperatureAfterK() - equilibrium)
						: capacityForced ? Double.POSITIVE_INFINITY : Math.abs(temperature - equilibrium);
				if (Math.min(leftDistance, rightDistance) >= acceptedDistance) break;
				long batches = rightDistance <= leftDistance ? right++ : left--;
				try {
					var candidate = trial(cell, liquidHandle, transition, inventory, freezing, batches, thermalCell, liquid);
					if (candidate.distanceLower(equilibrium) < acceptedDistance)
						best = acceptTrial(candidate, freezing, capacityForced, temperature, equilibrium, best);
				} catch (ThermalStateFunction.EnergyOutsideValidityException
						| ThermalStateFunction.EnergyRepresentationException
						| ThermalDomainState.EmptyPhaseCandidateEnergyException
						| PhaseCandidateUnavailableException unavailable) {
					// Actual species and carrier resolution are checked at each selected integer extent.
				}
			}
			return best;
		}
		var trials = new ArrayList<JointPhaseTrial>();
		for (long batches = high; batches >= minimumBatches; batches--) {
			try {
				var trial = trial(cell, liquidHandle, transition, inventory, freezing, batches, thermalCell, liquid);
				if (capacityForced || Math.abs(trial.thermal().temperatureK() - equilibrium)
						- trial.thermal().temperatureToleranceK() < Math.abs(temperature - equilibrium)) trials.add(trial);
			} catch (ThermalStateFunction.EnergyOutsideValidityException
					| ThermalStateFunction.EnergyRepresentationException
					| ThermalDomainState.EmptyPhaseCandidateEnergyException
					| PhaseCandidateUnavailableException unavailable) {
				// Integer species and carrier resolution can leave isolated unavailable extents.
			}
		}
		trials.sort(java.util.Comparator.comparingDouble(candidate -> candidate.distanceLower(equilibrium)));
		JointPhasePlan best = null;
		for (var trial : trials) {
			if (best != null && trial.distanceLower(equilibrium) >= Math.abs(best.temperatureAfterK() - equilibrium)) break;
			best = acceptTrial(trial, freezing, capacityForced, temperature, equilibrium, best);
		}
		return best;
	}

	private JointPhasePlan acceptTrial(JointPhaseTrial trial, boolean freezing, boolean capacityForced,
	                                  double temperature, double equilibrium, JointPhasePlan best) {
		ThermalDomainState.PhaseCellEnergyPlan assignment;
		try { assignment = support.thermal().planPhaseCellEnergy(trial.thermal()); }
		catch (ThermalDomainState.PhaseCellEnergyUnavailableException unavailable) { return best; }
		if (!capacityForced && !strictlyCloserToEquilibrium(assignment.temperatureK(), temperature, equilibrium)) return best;
		if (best != null && !strictlyCloserToEquilibrium(assignment.temperatureK(), best.temperatureAfterK(), equilibrium)) return best;
		return new JointPhasePlan(trial.batches(), freezing, trial.removal(), trial.coherentTransfer(),
				trial.meltedMixture(), trial.meltSlot(), assignment, trial.signedVolumeSvq());
	}

	static long minimumBatchesForCapacity(long occupiedSvq, long capacitySvq, long signedVolumeSvqPerBatch) {
		if (occupiedSvq < 0L || capacitySvq < 0L) {
			throw new IllegalArgumentException("Phase capacity inputs are invalid");
		}
		if (occupiedSvq <= capacitySvq) return 1L;
		if (signedVolumeSvqPerBatch >= 0L) return Long.MAX_VALUE;
		long shrink = Math.negateExact(signedVolumeSvqPerBatch);
		long excess = occupiedSvq - capacitySvq;
		return excess / shrink + (excess % shrink == 0L ? 0L : 1L);
	}

	private static boolean strictlyCloserToEquilibrium(
			double candidateK, double acceptedK, double equilibriumK
	) {
		double threshold = Math.max(1.0E-12,
				Math.max(Math.ulp(candidateK), Math.ulp(equilibriumK)) * 16.0);
		return Math.abs(candidateK - equilibriumK) < Math.abs(acceptedK - equilibriumK) - threshold;
	}

	private JointPhaseTrial trial(
			int cell, int liquidHandle, ThermalPhaseTransition transition,
			CondensedCellInventory inventory, boolean freezing, long batches,
			ThermalDomainState.PhaseCandidateCell thermalCell, PhaseLiquidInventory liquid
	) {
		PhaseCellInventory before = inventory.target();
		ThermalDomainState thermal = support.thermal();
		ChemicalParcelStore chemistry = support.chemistry();
		LiquidParticleStore particles = support.state().particles();
		long lowerDelta = Math.multiplyExact(batches, transition.lowerVolumeSvqPerBatch());
		long upperDelta = Math.multiplyExact(batches, transition.upperVolumeSvqPerBatch());
		if (!support.canExpandCondensedVolume(freezing ? lowerDelta - upperDelta : upperDelta - lowerDelta)) {
			throw new PhaseCandidateUnavailableException("Condensed phase expansion exceeds region carrier capacity");
		}
		double requestedMass = transition.massMuPerBatch().multiply(batches).toDouble();
		if (!(Double.isFinite(requestedMass) && requestedMass > 0.0)) {
			throw new FluidDomain.ThermalPhaseBlockedException(
					"Condensed phase extent mass is not representable");
		}
		long finalLowerSvq, finalUpperSvq;
		PhaseRemovalPlan removal = null;
		List<ThermalStateFunction.InventoryPortion> remainingLiquid = null;
		CoherentPhaseStore.Group coherentTransfer, coherentFinal;
		ChemicalMixtureRecord meltedMixture = null;
		int meltSlot = -1;
		if (freezing) {
			if (upperDelta > before.freezableUpperSvq()
					|| requestedMass > before.freezableUpperMass() + massTolerance(before.freezableUpperMass())) {
				throw new FluidDomain.ThermalPhaseBlockedException(
						"Freeze extent exceeds non-indicator liquid inventory");
			}
			var liquidRemoval = liquid.remove(liquidHandle, -1, upperDelta, requestedMass, null, false);
			removal = liquidRemoval.plan(); remainingLiquid = liquidRemoval.inventory();
			finalLowerSvq = Math.addExact(before.lowerVolumeSvq(), lowerDelta);
			finalUpperSvq = before.upperVolumeSvq() - upperDelta;
			coherentTransfer = new CoherentPhaseStore.Group(
					cell, transition.lowerEnergyMaterialId(), lowerDelta, requestedMass,
					removal.movedComposition().toCoherent(), 0.0);
			try {
				coherentFinal = thermal.coherentPhases().previewAdd(coherentTransfer);
			} catch (IllegalArgumentException | IllegalStateException | ArithmeticException unavailable) {
				throw new PhaseCandidateUnavailableException(
						"Freeze cannot represent another coherent carrier group", unavailable);
			}
			PhaseChangeStageSupport.representedPhysicalMassTransfer(
					before.lowerMass(), coherentFinal.mass(), requestedMass);
		} else {
			if (lowerDelta > before.lowerVolumeSvq()
					|| requestedMass > before.lowerMass() + massTolerance(before.lowerMass())) {
				throw new FluidDomain.ThermalPhaseBlockedException(
						"Melt extent exceeds coherent inventory");
			}
			try {
				var split = thermal.coherentPhases().previewSplit(
						cell, transition.lowerEnergyMaterialId(), lowerDelta, chemistry == null ? null : chemistry.registry());
				coherentTransfer = split.removed(); coherentFinal = split.remaining();
			} catch (IllegalArgumentException | ArithmeticException unavailable) {
				throw new PhaseCandidateUnavailableException(
						"Melt cannot represent the coherent carrier split", unavailable);
			}
			if (!FluidDomain.massClose(coherentTransfer.mass(), requestedMass)) {
				throw new FluidDomain.ThermalPhaseBlockedException(
						"Melt exact batch mass differs from coherent inventory");
			}
			PhaseChangeStageSupport.representedPhysicalMassTransfer(0.0, coherentTransfer.mass(), requestedMass);
			PhaseChangeStageSupport.representedPhysicalMassTransfer(before.lowerMass(),
					coherentFinal == null ? 0.0 : coherentFinal.mass(), -coherentTransfer.mass());
			support.representedMassTransfer(0.0, support.state().unitScale().solverMass(coherentTransfer.mass()),
					coherentTransfer.mass());
			PhaseComposition moved = PhaseComposition.fromCoherent(coherentTransfer.composition());
			finalLowerSvq = before.lowerVolumeSvq() - lowerDelta;
			finalUpperSvq = Math.addExact(before.upperVolumeSvq(), upperDelta);
			if (chemistry != null) {
				meltedMixture = moved.toMixture(chemistry.registry());
				chemistry.requireCompatible(
						meltedMixture, support.materialRegistry().material(liquidHandle).id(), false);
				if (meltedMixture.totalQuanta() > mmm.chemistry.ChemicalLimits.MAX_REGION_TOTAL_QUANTA
						- chemistry.totalQuanta()) {
					throw new PhaseCandidateUnavailableException("Melt awaits liquid chemical capacity");
				}
			} else if (!moved.isEmpty()) {
				throw new FluidDomain.ThermalPhaseBlockedException(
						"Chemical coherent phase cannot melt without chemistry state");
			}
			if (particles.count() >= support.state().maximumParticleCount()) {
				throw new PhaseCandidateUnavailableException(
						"Melt exceeds the liquid marker budget");
			}
			meltSlot = support.firstOpenSlot(cell);
			if (meltSlot < 0) throw new FluidDomain.ThermalPhaseBlockedException(
					"Melt has no collision-valid liquid marker slot");
		}
		long occupied = Math.addExact(
				Math.addExact(finalLowerSvq, finalUpperSvq), Math.addExact(support.granularSvq(cell), inventory.unchangedSvq()));
		long capacity = SolidVolume.fromCellVolumeExact(
				support.state().geometry().cutCells.openCellVolume(cell));
		if (occupied > capacity) throw new FluidDomain.ThermalPhaseBlockedException(
				"Condensed phase extent exceeds joint cell capacity");
		var phaseInventory = condensedInventoryAfter(cell, transition.lowerEnergyMaterialId(), coherentFinal,
				freezing ? remainingLiquid : liquid.afterCondensation(liquidHandle, -1, 0, meltedMixture, coherentTransfer.mass()));
		var thermalPlan = thermal.trialPhaseCellEnergy(thermalCell, phaseInventory);
		long signedVolumeSvq = freezing ? lowerDelta - upperDelta : upperDelta - lowerDelta;
		return new JointPhaseTrial(
				batches, removal, coherentTransfer, meltedMixture,
				meltSlot, thermalPlan, signedVolumeSvq);
	}

	/** Mirrors remove-swap ordinals and each final raw owner's rounded mass/composition. */
	private List<ThermalStateFunction.InventoryPortion> condensedInventoryAfter(
			int cell, String phaseId, CoherentPhaseStore.Group coherentFinal,
			List<ThermalStateFunction.InventoryPortion> finalLiquid
	) {
		var thermal = support.thermal();
		var result = new ArrayList<ThermalStateFunction.InventoryPortion>(finalLiquid);
		boolean targetFound = false;
		for (var group : thermal.coherentPhases().snapshot()) if (group.cell() == cell) {
			if (group.phaseId().equals(phaseId)) { group = coherentFinal; targetFound = true; }
			if (group != null) result.add(coherentPortion(group));
		}
		if (!targetFound && coherentFinal != null) result.add(coherentPortion(coherentFinal));
		return List.copyOf(result);
	}

	private ThermalStateFunction.InventoryPortion coherentPortion(CoherentPhaseStore.Group group) {
		return new ThermalStateFunction.InventoryPortion(support.thermal().registry().materialHandle(group.phaseId()),
				group.mass(), group.composition() == null ? java.util.Map.of() : group.composition().stableAmounts());
	}

	private record CondensedCellInventory(
			PhaseCellInventory target, long unchangedSvq
	) { }

	private void commitFreeze(int cell, JointPhasePlan plan) {
		support.thermal().requirePhaseCellEnergyCurrent(plan.thermalPlan());
		support.commitLiquidRemoval(cell, plan.removal());
		support.thermal().addCoherentInternal(plan.coherentTransfer());
		support.commitPendingVolume(cell, plan.signedVolumeSvq());
		support.refreshCoherentOccupancy();
		support.markGridDirty();
		support.thermal().commitPhaseCellEnergy(plan.thermalPlan(), support.state().thermalMarkers());
	}

	private void commitMelt(
			int cell, int liquidHandle, ThermalPhaseTransition transition, JointPhasePlan plan
	) {
		long removedSvq = plan.coherentTransfer().occupiedVolumeSvq();
		long addedSvq = Math.multiplyExact(plan.batches(), transition.upperVolumeSvqPerBatch());
		support.thermal().requirePhaseCellEnergyCurrent(plan.thermalPlan());
		CoherentPhaseStore.Group removed = support.thermal().takeCoherentInternal(
				cell, transition.lowerEnergyMaterialId(), removedSvq,
				support.chemistry() == null ? null : support.chemistry().registry());
		int marker = support.state().particles().count();
		support.spawnParticle(
				cell, plan.meltSlot(), SolidVolume.toCellVolume(addedSvq), liquidHandle,
				support.state().unitScale().solverMass(removed.mass()), plan.meltedMixture());
		support.thermal().addLiquidEnergy(marker, removed.internalEnergy());
		support.commitPendingVolume(cell, plan.signedVolumeSvq());
		support.refreshCoherentOccupancy();
		support.markGridDirty();
		support.thermal().commitPhaseCellEnergy(plan.thermalPlan(), support.state().thermalMarkers());
	}

	private record JointPhaseTrial(long batches, PhaseRemovalPlan removal,
			CoherentPhaseStore.Group coherentTransfer, ChemicalMixtureRecord meltedMixture, int meltSlot,
			ThermalDomainState.PhaseCellTrial thermal, long signedVolumeSvq) {
		double distanceLower(double equilibrium) {
			return Math.max(0, Math.abs(thermal.temperatureK() - equilibrium) - thermal.temperatureToleranceK());
		}
	}

	private record JointPhasePlan(
			long batches, boolean freezing, PhaseRemovalPlan removal,
			CoherentPhaseStore.Group coherentTransfer, ChemicalMixtureRecord meltedMixture,
			int meltSlot, ThermalDomainState.PhaseCellEnergyPlan thermalPlan, long signedVolumeSvq
	) {
		double temperatureAfterK() { return thermalPlan.temperatureK(); }
	}
}

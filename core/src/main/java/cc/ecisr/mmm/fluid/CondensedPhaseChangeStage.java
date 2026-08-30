package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.chemistry.ChemicalMixtureRecord;
import cc.ecisr.mmm.chemistry.ChemicalParcelStore;
import cc.ecisr.mmm.fluid.air.AirNetwork;
import cc.ecisr.mmm.granular.SolidVolume;
import cc.ecisr.mmm.thermal.CoherentPhaseStore;
import cc.ecisr.mmm.thermal.LiquidEnergyColumn;
import cc.ecisr.mmm.thermal.ThermalLimits;
import cc.ecisr.mmm.thermal.ThermalMaterialDefinition;
import cc.ecisr.mmm.thermal.ThermalPhaseTransition;
import cc.ecisr.mmm.thermal.ThermalStateFunction;
import cc.ecisr.mmm.units.SignedDyadic;

import java.util.ArrayList;
import java.util.List;

import static cc.ecisr.mmm.fluid.PhaseChangeStageSupport.PhaseCandidateUnavailableException;
import static cc.ecisr.mmm.fluid.PhaseChangeStageSupport.PhaseCellInventory;
import static cc.ecisr.mmm.fluid.PhaseChangeStageSupport.PhaseComposition;
import static cc.ecisr.mmm.fluid.PhaseChangeStageSupport.PhaseCompositionBuilder;
import static cc.ecisr.mmm.fluid.PhaseChangeStageSupport.PhaseMarkerAllocation;
import static cc.ecisr.mmm.fluid.PhaseChangeStageSupport.PhaseRemovalPlan;
import static cc.ecisr.mmm.fluid.PhaseChangeStageSupport.massTolerance;

/** Owns exact-batch coherent-solid/liquid planning and commit in the thermal axis. */
final class CondensedPhaseChangeStage {
	private final PhaseChangeStageSupport support;

	CondensedPhaseChangeStage(PhaseChangeStageSupport support) {
		this.support = support;
	}

	FlipFluidDomain.ThermalPhaseStats step(int maximumOperations, boolean phaseGridPrepared) {
		FlipThermalState thermal = support.thermal();
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
			for (int cell = 0; cell < support.state().cellCount() && operations < maximumOperations; cell++) {
				long coherentSvq = thermal.coherentPhases().cellOccupiedSvq(cell);
				if (support.state().markerGrid().markerCount[cell] == 0 && coherentSvq == 0L) continue;
				double absolutePressure = AirNetwork.REFERENCE_PRESSURE + support.pressureReference()
						+ support.state().projection().pressure[cell];
				if (!(Double.isFinite(absolutePressure)
						&& absolutePressure >= transition.minimumPressurePa()
						&& absolutePressure <= transition.maximumPressurePa())) {
					throw new FlipFluidDomain.ThermalPhaseBlockedException(
							"Condensed phase pressure " + absolutePressure + " Pa is outside " + transition.id()
									+ " curve " + transition.minimumPressurePa() + ".."
									+ transition.maximumPressurePa() + " Pa");
				}
				double temperature = thermal.phaseScanTemperatureInCell(cell);
				double equilibrium = transition.equilibriumTemperatureK(absolutePressure);
				if (temperature == equilibrium
						|| temperature < equilibrium && support.state().markerGrid().markerCount[cell] == 0
						|| temperature > equilibrium && coherentSvq == 0L) continue;
				PhaseCellInventory inventory = phaseCellInventory(cell, liquidHandle, lower.id());
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
		return new FlipFluidDomain.ThermalPhaseStats(operations, frozen, melted, signedBatches);
	}

	private PhaseCellInventory phaseCellInventory(int cell, int liquidHandle, String coherentPhaseId) {
		LiquidParticleStore particles = support.state().particles();
		ChemicalParcelStore chemistry = support.chemistry();
		FlipThermalState thermal = support.thermal();
		double liquidMass = 0.0, coherentMass = 0.0, energy = 0.0, freezableMass = 0.0;
		long liquidSvq = 0L, coherentSvq = 0L, freezableSvq = 0L;
		PhaseCompositionBuilder liquidComposition = chemistry == null ? null
				: new PhaseCompositionBuilder(chemistry);
		PhaseComposition coherentComposition = null;
		boolean found = false;
		for (int marker = 0; marker < particles.count(); marker++) {
			if (support.particleCell(marker) != cell) continue;
			if (particles.materialHandle(marker) != liquidHandle) {
				throw new FlipFluidDomain.ThermalPhaseBlockedException(
						"Phase cell contains an unsupported liquid heat-bath carrier");
			}
			liquidSvq = Math.addExact(liquidSvq, SolidVolume.cellVolumeSvqAtLeast(particles.volume(marker)));
			liquidMass += support.state().unitScale().kilograms(particles.mass(marker));
			energy += thermal.liquidInternalEnergy(marker);
			found = true;
			if (chemistry != null) liquidComposition.add(chemistry.record(marker));
			if (chemistry == null || !chemistry.indicator(marker)) {
				freezableSvq = Math.addExact(
						freezableSvq, SolidVolume.cellVolumeSvqAtMost(particles.volume(marker)));
				freezableMass += support.state().unitScale().kilograms(particles.mass(marker));
			}
		}
		for (CoherentPhaseStore.Group group : thermal.coherentPhases().snapshot()) {
			if (group.cell() != cell) continue;
			if (!group.phaseId().equals(coherentPhaseId)) {
				throw new FlipFluidDomain.ThermalPhaseBlockedException(
						"Phase cell contains an unsupported coherent heat-bath carrier");
			}
			if (coherentComposition != null) throw new FlipFluidDomain.ThermalPhaseBlockedException(
					"Phase cell contains duplicate coherent groups");
			if (chemistry != null && group.composition() == null
					|| chemistry == null && group.composition() != null) {
				throw new FlipFluidDomain.ThermalPhaseBlockedException(
						"Coherent composition/chemistry capability mismatch");
			}
			coherentComposition = PhaseComposition.fromCoherent(group.composition());
			coherentSvq = Math.addExact(coherentSvq, group.occupiedVolumeSvq());
			coherentMass += group.mass();
			energy += group.internalEnergy();
			found = true;
		}
		return found ? new PhaseCellInventory(
				coherentSvq, liquidSvq, coherentMass, liquidMass, energy,
				coherentComposition == null ? PhaseComposition.EMPTY : coherentComposition,
				liquidComposition == null ? PhaseComposition.EMPTY : liquidComposition.build(),
				freezableSvq, freezableMass) : null;
	}

	private JointPhasePlan planJointPhaseChange(
			int cell, int liquidHandle, ThermalPhaseTransition transition,
			PhaseCellInventory before, long maximumBatches, double temperature, double equilibrium
	) {
		if (temperature == equilibrium || maximumBatches == 0L) return null;
		double batchMass = transition.massMuPerBatch().toDouble();
		if (!(Double.isFinite(batchMass) && batchMass > 0.0)) {
			throw new FlipFluidDomain.ThermalPhaseBlockedException(
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
				Math.addExact(before.lowerVolumeSvq(), before.upperVolumeSvq()), support.granularSvq(cell));
		long capacity = SolidVolume.fromCellVolumeExact(
				support.state().geometry().cutCells.openCellVolume(cell));
		if (signedVolumePerBatch > 0L) high = Math.min(high, (capacity - occupied) / signedVolumePerBatch);
		long minimumBatches = minimumBatchesForCapacity(occupied, capacity, signedVolumePerBatch);
		if (high < minimumBatches) return null;
		boolean capacityForced = occupied > capacity;
		JointPhasePlan first;
		try {
			first = candidate(cell, liquidHandle, transition, before, freezing, minimumBatches);
		} catch (ThermalStateFunction.EnergyOutsideValidityException
				| PhaseCandidateUnavailableException unavailable) {
			return null;
		}
		if (!capacityForced && !strictlyCloserToEquilibrium(
				first.temperatureAfterK(), temperature, equilibrium)) return null;
		if (crossedEquilibrium(first.temperatureAfterK(), equilibrium, freezing)) return first;
		JointPhasePlan best = first;
		long low = minimumBatches + 1L;
		while (low <= high) {
			long middle = low + ((high - low) >>> 1);
			JointPhasePlan candidate;
			try {
				candidate = candidate(cell, liquidHandle, transition, before, freezing, middle);
			} catch (ThermalStateFunction.EnergyOutsideValidityException
					| PhaseCandidateUnavailableException unavailable) {
				high = middle - 1L;
				continue;
			}
			if (crossedEquilibrium(candidate.temperatureAfterK(), equilibrium, freezing)) {
				if (strictlyCloserToEquilibrium(
						candidate.temperatureAfterK(), best.temperatureAfterK(), equilibrium)) best = candidate;
				high = middle - 1L;
			} else {
				best = candidate;
				low = middle + 1L;
			}
		}
		return best;
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

	private static boolean crossedEquilibrium(double candidateK, double equilibriumK, boolean freezing) {
		return freezing ? candidateK > equilibriumK : candidateK < equilibriumK;
	}

	private static boolean strictlyCloserToEquilibrium(
			double candidateK, double acceptedK, double equilibriumK
	) {
		double threshold = Math.max(1.0E-12,
				Math.max(Math.ulp(candidateK), Math.ulp(equilibriumK)) * 16.0);
		return Math.abs(candidateK - equilibriumK) < Math.abs(acceptedK - equilibriumK) - threshold;
	}

	private JointPhasePlan candidate(
			int cell, int liquidHandle, ThermalPhaseTransition transition,
			PhaseCellInventory before, boolean freezing, long batches
	) {
		FlipThermalState thermal = support.thermal();
		ChemicalParcelStore chemistry = support.chemistry();
		LiquidParticleStore particles = support.state().particles();
		long lowerDelta = Math.multiplyExact(batches, transition.lowerVolumeSvqPerBatch());
		long upperDelta = Math.multiplyExact(batches, transition.upperVolumeSvqPerBatch());
		double requestedMass = transition.massMuPerBatch().toDouble() * batches;
		if (!(Double.isFinite(requestedMass) && requestedMass > 0.0)) {
			throw new FlipFluidDomain.ThermalPhaseBlockedException(
					"Condensed phase extent mass is not representable");
		}
		long finalLowerSvq, finalUpperSvq;
		double finalLowerMass, finalUpperMass;
		PhaseComposition finalLowerComposition, finalUpperComposition;
		PhaseRemovalPlan removal = null;
		CoherentPhaseStore.Group coherentTransfer;
		ChemicalMixtureRecord meltedMixture = null;
		int meltSlot = -1;
		if (freezing) {
			if (upperDelta > before.freezableUpperSvq()
					|| requestedMass > before.freezableUpperMass() + massTolerance(before.freezableUpperMass())) {
				throw new FlipFluidDomain.ThermalPhaseBlockedException(
						"Freeze extent exceeds non-indicator liquid inventory");
			}
			removal = planLiquidRemoval(cell, liquidHandle, upperDelta, requestedMass);
			finalLowerSvq = Math.addExact(before.lowerVolumeSvq(), lowerDelta);
			finalUpperSvq = before.upperVolumeSvq() - upperDelta;
			finalLowerMass = before.lowerMass() + requestedMass;
			finalUpperMass = before.upperMass() - requestedMass;
			if (finalUpperMass < 0.0 && finalUpperMass >= -massTolerance(before.upperMass())) finalUpperMass = 0.0;
			finalLowerComposition = before.lowerComposition().plus(removal.movedComposition());
			finalUpperComposition = before.upperComposition().minus(removal.movedComposition());
			coherentTransfer = new CoherentPhaseStore.Group(
					cell, transition.lowerEnergyMaterialId(), lowerDelta, requestedMass,
					removal.movedComposition().toCoherent(), removal.movedEnergy());
			thermal.coherentPhases().previewAdd(coherentTransfer);
		} else {
			if (lowerDelta > before.lowerVolumeSvq()
					|| requestedMass > before.lowerMass() + massTolerance(before.lowerMass())) {
				throw new FlipFluidDomain.ThermalPhaseBlockedException(
						"Melt extent exceeds coherent inventory");
			}
			coherentTransfer = thermal.coherentPhases().previewTake(
					cell, transition.lowerEnergyMaterialId(), lowerDelta);
			if (!FlipFluidDomain.massClose(coherentTransfer.mass(), requestedMass)) {
				throw new FlipFluidDomain.ThermalPhaseBlockedException(
						"Melt exact batch mass differs from coherent inventory");
			}
			PhaseComposition moved = PhaseComposition.fromCoherent(coherentTransfer.composition());
			finalLowerSvq = before.lowerVolumeSvq() - lowerDelta;
			finalUpperSvq = Math.addExact(before.upperVolumeSvq(), upperDelta);
			finalLowerMass = before.lowerMass() - coherentTransfer.mass();
			finalUpperMass = before.upperMass() + coherentTransfer.mass();
			if (finalLowerMass < 0.0 && finalLowerMass >= -massTolerance(before.lowerMass())) finalLowerMass = 0.0;
			finalLowerComposition = before.lowerComposition().minus(moved);
			finalUpperComposition = before.upperComposition().plus(moved);
			if (chemistry != null) {
				meltedMixture = moved.toMixture(chemistry.registry());
				chemistry.requireCompatible(
						meltedMixture, support.materialRegistry().material(liquidHandle).id(), false);
			} else if (!moved.isEmpty()) {
				throw new FlipFluidDomain.ThermalPhaseBlockedException(
						"Chemical coherent phase cannot melt without chemistry state");
			}
			if (particles.count() >= support.state().maximumParticleCount()) {
				throw new FlipFluidDomain.ThermalPhaseBlockedException(
						"Melt exceeds the liquid marker budget");
			}
			meltSlot = support.firstOpenSlot(cell);
			if (meltSlot < 0) throw new FlipFluidDomain.ThermalPhaseBlockedException(
					"Melt has no collision-valid liquid marker slot");
		}
		long occupied = Math.addExact(
				Math.addExact(finalLowerSvq, finalUpperSvq), support.granularSvq(cell));
		long capacity = SolidVolume.fromCellVolumeExact(
				support.state().geometry().cutCells.openCellVolume(cell));
		if (occupied > capacity) throw new FlipFluidDomain.ThermalPhaseBlockedException(
				"Condensed phase extent exceeds joint cell capacity");
		ArrayList<ThermalStateFunction.InventoryPortion> phaseInventory = new ArrayList<>(2);
		if (finalLowerMass > 0.0) phaseInventory.add(new ThermalStateFunction.InventoryPortion(
				thermal.registry().materialHandle(transition.lowerEnergyMaterialId()),
				finalLowerMass, finalLowerComposition.amounts()));
		if (finalUpperMass > 0.0) phaseInventory.add(new ThermalStateFunction.InventoryPortion(
				thermal.thermalMaterialHandleForFluid(liquidHandle),
				finalUpperMass, finalUpperComposition.amounts()));
		double candidateTemperature = thermal.phaseCandidateTemperature(
				cell, phaseInventory, support.state().thermalMarkers());
		long signedVolumeSvq = freezing ? lowerDelta - upperDelta : upperDelta - lowerDelta;
		return new JointPhasePlan(
				batches, freezing, removal, coherentTransfer, meltedMixture,
				meltSlot, candidateTemperature, signedVolumeSvq);
	}

	private PhaseRemovalPlan planLiquidRemoval(
			int cell, int liquidHandle, long requestedSvq, double requestedMass
	) {
		ChemicalParcelStore chemistry = support.chemistry();
		LiquidParticleStore particles = support.state().particles();
		ArrayList<PhaseMarkerAllocation> allocations = new ArrayList<>();
		PhaseCompositionBuilder moved = chemistry == null ? null : new PhaseCompositionBuilder(chemistry);
		SignedDyadic movedEnergy = SignedDyadic.ZERO;
		long remainingSvq = requestedSvq;
		double remainingMass = requestedMass;
		double cellTemperature = support.thermal().temperatureInCell(
				cell, support.state().thermalMarkers());
		for (int marker = particles.count() - 1; marker >= 0 && remainingSvq > 0L; marker--) {
			if (support.particleCell(marker) != cell || particles.materialHandle(marker) != liquidHandle
					|| chemistry != null && chemistry.indicator(marker)) continue;
			long markerSvq = SolidVolume.cellVolumeSvqAtMost(particles.volume(marker));
			if (markerSvq == 0L) continue;
			long takeSvq = Math.min(markerSvq, remainingSvq);
			double takeMass = takeSvq == remainingSvq ? remainingMass
					: support.state().unitScale().kilograms(particles.mass(marker))
							* ((double) takeSvq / markerSvq);
			double takeSolverMass = support.state().unitScale().solverMass(takeMass);
			boolean full = takeSvq == markerSvq
					&& markerSvq == SolidVolume.cellVolumeSvqAtLeast(particles.volume(marker));
			float newVolume = full ? 0.0F
					: PhaseChangeStageSupport.adjustedMarkerVolume(
							particles.volume(marker), -takeSvq, "Freeze");
			double newMass = particles.mass(marker) - takeSolverMass;
			if (!(takeMass > 0.0) || full && Math.abs(newMass) > massTolerance(particles.mass(marker))
					|| !full && !(newMass > 0.0)) {
				throw new PhaseCandidateUnavailableException(
						"Freeze batch mass/volume split is not representable");
			}
			ChemicalParcelStore.SplitPlan chemicalPlan = chemistry == null ? null
					: chemistry.planTruncate(marker, newVolume, SolidVolume.toCellVolume(takeSvq),
							full ? 0.0 : newMass, takeSolverMass);
			ChemicalMixtureRecord kept = chemicalPlan == null ? null : chemicalPlan.kept();
			LiquidEnergyColumn.Truncation thermalPlan = support.planThermalTruncate(
					marker, full ? 0.0 : newMass, kept, cellTemperature);
			if (chemicalPlan != null) moved.add(chemicalPlan.removed());
			if (thermalPlan != null) movedEnergy = movedEnergy.add(SignedDyadic.of(thermalPlan.removedEnergy()));
			allocations.add(new PhaseMarkerAllocation(
					marker, newVolume, full ? 0.0 : newMass, full, chemicalPlan, thermalPlan));
			remainingSvq -= takeSvq;
			remainingMass -= takeMass;
		}
		if (remainingSvq != 0L || Math.abs(remainingMass) > massTolerance(requestedMass)) {
			throw new PhaseCandidateUnavailableException(
					"Freeze exact batch cannot be allocated to liquid markers");
		}
		return new PhaseRemovalPlan(
				List.copyOf(allocations), chemistry == null ? PhaseComposition.EMPTY : moved.build(),
				movedEnergy.toDouble());
	}

	private void commitFreeze(int cell, JointPhasePlan plan) {
		SignedDyadic inventoryBefore = support.exactInternalEnergy();
		support.commitLiquidRemoval(cell, plan.removal());
		support.thermal().addCoherentInternal(plan.coherentTransfer());
		support.commitPendingVolume(cell, plan.signedVolumeSvq());
		support.refreshCoherentOccupancy();
		support.markGridDirty();
		support.thermal().repartitionCellEnergyConserving(
				cell, support.state().thermalMarkers(), inventoryBefore);
	}

	private void commitMelt(
			int cell, int liquidHandle, ThermalPhaseTransition transition, JointPhasePlan plan
	) {
		long removedSvq = plan.coherentTransfer().occupiedVolumeSvq();
		long addedSvq = Math.multiplyExact(plan.batches(), transition.upperVolumeSvqPerBatch());
		SignedDyadic inventoryBefore = support.exactInternalEnergy();
		CoherentPhaseStore.Group removed = support.thermal().takeCoherentInternal(
				cell, transition.lowerEnergyMaterialId(), removedSvq);
		int marker = support.state().particles().count();
		support.spawnParticle(
				cell, plan.meltSlot(), SolidVolume.toCellVolume(addedSvq), liquidHandle,
				support.state().unitScale().solverMass(removed.mass()), plan.meltedMixture());
		support.thermal().addLiquidEnergy(marker, removed.internalEnergy());
		support.commitPendingVolume(cell, plan.signedVolumeSvq());
		support.refreshCoherentOccupancy();
		support.markGridDirty();
		support.thermal().repartitionCellEnergyConserving(
				cell, support.state().thermalMarkers(), inventoryBefore);
	}

	private record JointPhasePlan(
			long batches, boolean freezing, PhaseRemovalPlan removal,
			CoherentPhaseStore.Group coherentTransfer, ChemicalMixtureRecord meltedMixture,
			int meltSlot, double temperatureAfterK, long signedVolumeSvq
	) { }
}

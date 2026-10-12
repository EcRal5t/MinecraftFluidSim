package mmm.fluid;

import mmm.thermal.ThermalDomainState;

import mmm.chemistry.ChemicalMixtureRecord;
import mmm.chemistry.ChemicalParcelStore;
import mmm.fluid.air.AirNetwork;
import mmm.granular.SolidVolume;
import mmm.thermal.CoherentPhaseStore;
import mmm.thermal.LiquidEnergyColumn;
import mmm.thermal.ThermalLimits;
import mmm.thermal.ThermalMaterialDefinition;
import mmm.thermal.ThermalPhaseTransition;
import mmm.thermal.ThermalStateFunction;
import mmm.units.SignedDyadic;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.TreeMap;
import java.util.List;

import static mmm.fluid.PhaseChangeStageSupport.PhaseCandidateUnavailableException;
import static mmm.fluid.PhaseChangeStageSupport.PhaseCellInventory;
import static mmm.fluid.PhaseChangeStageSupport.PhaseComposition;
import static mmm.fluid.PhaseChangeStageSupport.PhaseCompositionBuilder;
import static mmm.fluid.PhaseChangeStageSupport.PhaseMarkerAllocation;
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
		LiquidParticleStore particles = support.state().particles();
		ChemicalParcelStore chemistry = support.chemistry();
		ThermalDomainState thermal = support.thermal();
		ArrayList<ThermalStateFunction.InventoryPortion> unchanged = new ArrayList<>();
		double liquidMass = 0.0, coherentMass = 0.0, energy = 0.0, freezableMass = 0.0;
		long liquidSvq = 0L, coherentSvq = 0L, freezableSvq = 0L, unchangedSvq = 0L;
		PhaseCompositionBuilder liquidComposition = chemistry == null ? null
				: new PhaseCompositionBuilder(chemistry);
		PhaseComposition coherentComposition = null;
		boolean found = false;
		for (int marker = 0; marker < particles.count(); marker++) {
			if (support.particleCell(marker) != cell) continue;
			if (particles.materialHandle(marker) != liquidHandle) {
				unchangedSvq = Math.addExact(unchangedSvq, SolidVolume.cellVolumeSvqAtLeast(particles.volume(marker)));
				unchanged.add(new ThermalStateFunction.InventoryPortion(
						thermal.thermalMaterialHandleForFluid(particles.materialHandle(marker)),
						support.state().unitScale().kilograms(particles.mass(marker)),
						chemistry == null ? java.util.Map.of() : ThermalStateFunction.stableAmounts(
								chemistry.record(marker), chemistry.registry())));
				continue;
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
				unchangedSvq = Math.addExact(unchangedSvq, group.occupiedVolumeSvq());
				unchanged.add(new ThermalStateFunction.InventoryPortion(
						thermal.registry().materialHandle(group.phaseId()), group.mass(),
						group.composition() == null ? java.util.Map.of()
								: group.composition().stableAmounts()));
				continue;
			}
			if (coherentComposition != null) throw new FluidDomain.ThermalPhaseBlockedException(
					"Phase cell contains duplicate coherent groups");
			if (chemistry != null && group.composition() == null
					|| chemistry == null && group.composition() != null) {
				throw new FluidDomain.ThermalPhaseBlockedException(
						"Coherent composition/chemistry capability mismatch");
			}
			coherentComposition = PhaseComposition.fromCoherent(group.composition());
			coherentSvq = Math.addExact(coherentSvq, group.occupiedVolumeSvq());
			coherentMass += group.mass();
			energy += group.internalEnergy();
			found = true;
		}
		return found ? new CondensedCellInventory(new PhaseCellInventory(
				coherentSvq, liquidSvq, coherentMass, liquidMass, energy,
				coherentComposition == null ? PhaseComposition.EMPTY : coherentComposition,
				liquidComposition == null ? PhaseComposition.EMPTY : liquidComposition.build(),
				freezableSvq, freezableMass), List.copyOf(unchanged), unchangedSvq) : null;
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
		JointPhasePlan best = null;
		var thermalCell = support.thermal().preparePhaseCandidateCell(cell, support.state().thermalMarkers());
		var bounds = new CondensedPhaseBatchBounds(support, cell, liquidHandle, transition,
				before, inventory.unchanged(), freezing, temperature);
		var intervals = new ArrayDeque<BatchInterval>();
		if (high > minimumBatches) intervals.push(new BatchInterval(minimumBatches, high - 1));
		intervals.push(new BatchInterval(high, high));
		// Integer species/carrier holes require actual leaves. Proven temperature
		// envelopes can exclude intervals that cannot improve the retained result.
		while (!intervals.isEmpty()) {
			var interval = intervals.pop();
			if ((best != null || !capacityForced) && bounds.excluded(interval.low(), interval.high(),
					best == null ? temperature : best.temperatureAfterK(), equilibrium)) continue;
			if (interval.low() != interval.high()) {
				long middle = interval.low() + (interval.high() - interval.low()) / 2;
				intervals.push(new BatchInterval(interval.low(), middle));
				intervals.push(new BatchInterval(middle + 1, interval.high()));
				continue;
			}
			long batches = interval.low();
			JointPhasePlan candidate;
			try {
				candidate = candidate(cell, liquidHandle, transition, inventory, freezing, batches, thermalCell);
			} catch (ThermalStateFunction.EnergyOutsideValidityException
					| ThermalStateFunction.EnergyRepresentationException
					| ThermalDomainState.PhaseCellEnergyUnavailableException
					| PhaseCandidateUnavailableException unavailable) {
				continue;
			}
			if (!capacityForced && !strictlyCloserToEquilibrium(
					candidate.temperatureAfterK(), temperature, equilibrium)) continue;
			if (best == null || strictlyCloserToEquilibrium(
					candidate.temperatureAfterK(), best.temperatureAfterK(), equilibrium)) {
				best = candidate;
				// Every accepted improvement must exceed at least 1e-12 K. Once inside
				// that distance, no remaining candidate can improve the retained result.
				if (Math.abs(best.temperatureAfterK() - equilibrium) <= 1.0E-12) break;
			}
		}
		return best;
	}

	private record BatchInterval(long low, long high) { }

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

	private JointPhasePlan candidate(
			int cell, int liquidHandle, ThermalPhaseTransition transition,
			CondensedCellInventory inventory, boolean freezing, long batches,
			ThermalDomainState.PhaseCandidateCell thermalCell
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
		double requestedMass = transition.massMuPerBatch().toDouble() * batches;
		if (!(Double.isFinite(requestedMass) && requestedMass > 0.0)) {
			throw new FluidDomain.ThermalPhaseBlockedException(
					"Condensed phase extent mass is not representable");
		}
		long finalLowerSvq, finalUpperSvq;
		PhaseRemovalPlan removal = null;
		CoherentPhaseStore.Group coherentTransfer, coherentFinal;
		ChemicalMixtureRecord meltedMixture = null;
		int meltSlot = -1;
		if (freezing) {
			if (upperDelta > before.freezableUpperSvq()
					|| requestedMass > before.freezableUpperMass() + massTolerance(before.freezableUpperMass())) {
				throw new FluidDomain.ThermalPhaseBlockedException(
						"Freeze extent exceeds non-indicator liquid inventory");
			}
			removal = planLiquidRemoval(cell, liquidHandle, upperDelta, requestedMass);
			finalLowerSvq = Math.addExact(before.lowerVolumeSvq(), lowerDelta);
			finalUpperSvq = before.upperVolumeSvq() - upperDelta;
			coherentTransfer = new CoherentPhaseStore.Group(
					cell, transition.lowerEnergyMaterialId(), lowerDelta, requestedMass,
					removal.movedComposition().toCoherent(), removal.movedEnergy());
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
				coherentTransfer = thermal.coherentPhases().previewTake(
						cell, transition.lowerEnergyMaterialId(), lowerDelta, chemistry == null ? null : chemistry.registry());
				coherentFinal = thermal.coherentPhases().previewRemaining(
						cell, transition.lowerEnergyMaterialId(), lowerDelta, chemistry == null ? null : chemistry.registry());
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
		var phaseInventory = condensedInventoryAfter(cell, liquidHandle, transition.lowerEnergyMaterialId(),
				removal, coherentFinal, freezing ? null : coherentTransfer, meltedMixture);
		var thermalPlan = thermal.planPhaseCellEnergy(thermalCell, phaseInventory);
		long signedVolumeSvq = freezing ? lowerDelta - upperDelta : upperDelta - lowerDelta;
		return new JointPhasePlan(
				batches, freezing, removal, coherentTransfer, meltedMixture,
				meltSlot, thermalPlan, signedVolumeSvq);
	}

	/** Mirrors remove-swap ordinals and each final raw owner's rounded mass/composition. */
	private List<ThermalStateFunction.InventoryPortion> condensedInventoryAfter(
			int cell, int liquidHandle, String phaseId, PhaseRemovalPlan removal,
			CoherentPhaseStore.Group coherentFinal, CoherentPhaseStore.Group melted,
			ChemicalMixtureRecord meltedMixture
	) {
		var thermal = support.thermal();
		var particles = support.state().particles();
		var chemistry = support.chemistry();
		var allocations = new HashMap<Integer, PhaseMarkerAllocation>();
		if (removal != null) for (var allocation : removal.allocations()) allocations.put(allocation.marker(), allocation);
		var ordinals = new TreeMap<Integer, ThermalStateFunction.InventoryPortion>();
		for (int marker = 0; marker < particles.count(); marker++) {
			if (support.particleCell(marker) != cell) continue;
			var allocation = allocations.get(marker);
			if (allocation != null && allocation.fullRemoval()) continue;
			double mass = allocation == null ? particles.mass(marker) : allocation.newMass();
			var mixture = chemistry == null ? null : allocation == null
					? chemistry.record(marker) : allocation.chemicalPlan().kept();
			ordinals.put(marker, liquidPortion(particles.materialHandle(marker), mass, mixture));
		}
		int count = particles.count();
		if (removal != null) for (var allocation : removal.allocations()) if (allocation.fullRemoval()) {
			int last = --count;
			if (allocation.marker() != last) {
				var swapped = ordinals.remove(last);
				if (swapped != null) ordinals.put(allocation.marker(), swapped);
			}
		}
		var result = new ArrayList<ThermalStateFunction.InventoryPortion>(ordinals.values());
		if (melted != null) result.add(liquidPortion(liquidHandle,
				support.state().unitScale().solverMass(melted.mass()), meltedMixture));
		boolean targetFound = false;
		for (var group : thermal.coherentPhases().snapshot()) if (group.cell() == cell) {
			if (group.phaseId().equals(phaseId)) { group = coherentFinal; targetFound = true; }
			if (group != null) result.add(coherentPortion(group));
		}
		if (!targetFound && coherentFinal != null) result.add(coherentPortion(coherentFinal));
		return List.copyOf(result);
	}

	private ThermalStateFunction.InventoryPortion liquidPortion(
			int materialHandle, double solverMass, ChemicalMixtureRecord mixture
	) {
		return new ThermalStateFunction.InventoryPortion(support.thermal().thermalMaterialHandleForFluid(materialHandle),
				support.state().unitScale().kilograms(solverMass), mixture == null ? java.util.Map.of()
					: ThermalStateFunction.stableAmounts(mixture, support.chemistry().registry()));
	}

	private ThermalStateFunction.InventoryPortion coherentPortion(CoherentPhaseStore.Group group) {
		return new ThermalStateFunction.InventoryPortion(support.thermal().registry().materialHandle(group.phaseId()),
				group.mass(), group.composition() == null ? java.util.Map.of() : group.composition().stableAmounts());
	}

	private record CondensedCellInventory(
			PhaseCellInventory target, List<ThermalStateFunction.InventoryPortion> unchanged, long unchangedSvq
	) { }

	private PhaseRemovalPlan planLiquidRemoval(
			int cell, int liquidHandle, long requestedSvq, double requestedMass
	) {
		ChemicalParcelStore chemistry = support.chemistry();
		LiquidParticleStore particles = support.state().particles();
		ArrayList<PhaseMarkerAllocation> allocations = new ArrayList<>();
		PhaseCompositionBuilder moved = chemistry == null ? null : new PhaseCompositionBuilder(chemistry);
		SignedDyadic movedEnergy = SignedDyadic.ZERO;
		SignedDyadic actualRemovedMass = SignedDyadic.ZERO;
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
			if (!(Double.isFinite(takeMass) && takeMass > 0.0 && takeMass <= remainingMass)) {
				throw new PhaseCandidateUnavailableException(
						"Freeze batch mass cannot be allocated across the selected marker volumes");
			}
			double takeSolverMass = support.state().unitScale().solverMass(takeMass);
			boolean full = takeSvq == markerSvq
					&& markerSvq == SolidVolume.cellVolumeSvqAtLeast(particles.volume(marker));
			float newVolume = full ? 0.0F
					: PhaseChangeStageSupport.adjustedMarkerVolume(
							particles.volume(marker), -takeSvq, "Freeze");
			double newMass = full ? 0.0 : particles.mass(marker) - takeSolverMass;
			if (!full && !(newMass > 0.0)) {
				throw new PhaseCandidateUnavailableException(
						"Freeze batch mass/volume split is not representable");
			}
			support.representedMassTransfer(particles.mass(marker), newMass, -takeMass);
			actualRemovedMass = actualRemovedMass.add(SignedDyadic.of(
					support.state().unitScale().kilograms(particles.mass(marker))).subtract(SignedDyadic.of(
					support.state().unitScale().kilograms(newMass))));
			ChemicalParcelStore.SplitPlan chemicalPlan;
			try {
				chemicalPlan = chemistry == null ? null : chemistry.planTruncate(
						marker, newVolume, SolidVolume.toCellVolume(takeSvq),
						full ? 0.0 : newMass, takeSolverMass);
			} catch (mmm.chemistry.ChemicalReactionEngine.BudgetExceededException unavailable) {
				throw new PhaseCandidateUnavailableException(
						"Freeze batch is below the chemical carrier resolution", unavailable);
			}
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
		PhaseChangeStageSupport.representedPhysicalMassTransfer(0.0, actualRemovedMass.toDouble(), requestedMass);
		return new PhaseRemovalPlan(
				List.copyOf(allocations), chemistry == null ? PhaseComposition.EMPTY : moved.build(),
				movedEnergy.toDouble());
	}

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

	private record JointPhasePlan(
			long batches, boolean freezing, PhaseRemovalPlan removal,
			CoherentPhaseStore.Group coherentTransfer, ChemicalMixtureRecord meltedMixture,
			int meltSlot, ThermalDomainState.PhaseCellEnergyPlan thermalPlan, long signedVolumeSvq
	) {
		double temperatureAfterK() { return thermalPlan.temperatureK(); }
	}
}

package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.chemistry.ChemicalMixtureRecord;
import cc.ecisr.mmm.chemistry.ChemicalParcelStore;
import cc.ecisr.mmm.fluid.air.GasComponentAmount;
import cc.ecisr.mmm.fluid.air.GasInventory;
import cc.ecisr.mmm.granular.SolidVolume;
import cc.ecisr.mmm.thermal.GasLiquidPhaseModel;
import cc.ecisr.mmm.thermal.LiquidEnergyColumn;
import cc.ecisr.mmm.thermal.ThermalDomainState;
import cc.ecisr.mmm.thermal.ThermalLimits;
import cc.ecisr.mmm.thermal.ThermalMaterialDefinition;
import cc.ecisr.mmm.thermal.ThermalPhaseTransition;
import cc.ecisr.mmm.thermal.ThermalStateFunction;
import cc.ecisr.mmm.units.SignedDyadic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static cc.ecisr.mmm.fluid.PhaseChangeStageSupport.PhaseCandidateUnavailableException;
import static cc.ecisr.mmm.fluid.PhaseChangeStageSupport.PhaseCellInventory;
import static cc.ecisr.mmm.fluid.PhaseChangeStageSupport.PhaseComposition;
import static cc.ecisr.mmm.fluid.PhaseChangeStageSupport.PhaseCompositionBuilder;
import static cc.ecisr.mmm.fluid.PhaseChangeStageSupport.PhaseMarkerAllocation;
import static cc.ecisr.mmm.fluid.PhaseChangeStageSupport.PhaseRemovalPlan;
import static cc.ecisr.mmm.fluid.PhaseChangeStageSupport.massTolerance;

/** Owns sealed-interface liquid/gas planning and commit in the thermal axis. */
final class GasLiquidPhaseChangeStage {
	private final PhaseChangeStageSupport support;
	private final GasPhaseInterfaceResolver interfaces;

	GasLiquidPhaseChangeStage(PhaseChangeStageSupport support) {
		this.support = support;
		this.interfaces = new GasPhaseInterfaceResolver(support);
	}

	FluidDomain.GasThermalPhaseStats step(int maximumOperations, boolean phaseGridPrepared) {
		ThermalDomainState thermal = support.thermal();
		if (thermal == null || !thermal.gasModelBound()) {
			throw new IllegalStateException("Gas phase change requires bound gas thermal state");
		}
		if (maximumOperations < 0 || maximumOperations > ThermalLimits.MAX_PHASE_OPERATIONS_PER_STEP) {
			throw new IllegalArgumentException("Gas phase operation budget is invalid");
		}
		support.ensureGrid();
		support.refreshGasTopology();
		if (!phaseGridPrepared) thermal.preparePhaseScan(support.state().thermalMarkers());
		GasDomainState gasState = support.gasState();
		GasLiquidPhaseModel phase = thermal.gasLiquidPhase(gasState.network);
		HashMap<GasPhaseDirectionKey, GasLiquidPhaseModel.Direction> stepDirections = new HashMap<>();
		int operations = 0, boiled = 0, condensed = 0;
		for (ThermalPhaseTransition transition : thermal.registry().transitions()) {
			if (transition.interfaceRequirement()
					!= ThermalPhaseTransition.InterfaceRequirement.LIQUID_GAS) continue;
			ThermalMaterialDefinition liquid = thermal.registry().material(
					thermal.registry().materialHandle(transition.lowerEnergyMaterialId()));
			ThermalMaterialDefinition gas = thermal.registry().material(
					thermal.registry().materialHandle(transition.upperEnergyMaterialId()));
			if (liquid.carrierKind() != ThermalMaterialDefinition.CarrierKind.LIQUID
					|| gas.carrierKind() != ThermalMaterialDefinition.CarrierKind.GAS) continue;
			int liquidHandle = support.fluidMaterialHandle(liquid.carrierId());
			int gasThermalHandle = thermal.registry().materialHandle(gas.id());
			boolean[] volatileSpecies = gasVolatileSpecies(gasThermalHandle);
			for (int cell = 0; cell < support.state().cellCount() && operations < maximumOperations; cell++) {
				if (!support.state().pendingPhaseCellVolume()[cell].equals(SignedDyadic.ZERO)) continue;
				if (!gasLiquidCellMatches(cell, liquidHandle)) continue;
				double liquidTemperature = thermal.phaseScanTemperatureInCell(cell);
				PhaseCellInventory inventory = null;
				ThermalDomainState.PhaseCandidateCell thermalCell = null;
				for (GasPhaseInterfaceResolver.Candidate gasInterface : interfaces.resolve(
						cell, transition, phase, gasState, liquidTemperature)) {
					int node = gasInterface.node();
					GasLiquidPhaseModel.Saturation saturation = gasInterface.saturation();
					GasLiquidPhaseModel.Direction direction = saturation.direction();
					if (direction == GasLiquidPhaseModel.Direction.EQUILIBRIUM) continue;
					GasPhaseDirectionKey directionKey = new GasPhaseDirectionKey(
							transition.id(), node, Double.doubleToRawLongBits(liquidTemperature));
					GasLiquidPhaseModel.Direction firstDirection = stepDirections.putIfAbsent(directionKey, direction);
					if (firstDirection != null && firstDirection != direction) continue;
					if (inventory == null) {
						inventory = gasLiquidCellInventory(cell, liquidHandle);
						if (inventory == null) break;
						thermalCell = thermal.preparePhaseCandidateCell(
								cell, support.state().thermalMarkers());
					}
					long remainingBudget = maximumOperations - operations;
					if (direction == GasLiquidPhaseModel.Direction.BOIL) {
						GasBoilPlan plan = planBoil(
								cell, node, liquidHandle, gasThermalHandle, transition, inventory,
								phase, thermalCell, volatileSpecies, liquidTemperature,
								saturation.pressureImbalancePa(), remainingBudget);
						if (plan == null) continue;
						commitBoil(cell, node, plan, thermalCell);
						support.refreshGasGeometry();
						operations = Math.addExact(operations, Math.toIntExact(plan.batches()));
						boiled++;
						break;
					} else if (direction == GasLiquidPhaseModel.Direction.CONDENSE) {
						GasCondensePlan plan = planCondense(
								cell, node, liquidHandle, transition, phase,
								thermalCell, gas.carrierId(), saturation.pressureImbalancePa(), remainingBudget);
						if (plan == null) continue;
						commitCondense(cell, node, liquidHandle, plan, thermalCell);
						support.refreshGasGeometry();
						operations = Math.addExact(operations, Math.toIntExact(plan.batches()));
						condensed++;
						break;
					}
				}
			}
		}
		return new FluidDomain.GasThermalPhaseStats(operations, boiled, condensed);
	}

	private GasBoilPlan planBoil(
			int cell, int node, int liquidHandle, int gasThermalHandle,
			ThermalPhaseTransition transition, PhaseCellInventory inventory,
			GasLiquidPhaseModel phase, ThermalDomainState.PhaseCandidateCell thermalCell,
			boolean[] volatileSpecies, double liquidTemperature,
			double initialPressureImbalancePa, long maximumBatches
	) {
		double batchMass = transition.massMuPerBatch().toDouble();
		long byVolume = inventory.freezableUpperSvq() / transition.lowerVolumeSvqPerBatch();
		long byMass = (long) Math.floor((inventory.freezableUpperMass()
				+ massTolerance(inventory.freezableUpperMass())) / batchMass);
		long high = Math.min(maximumBatches, Math.min(byVolume, byMass));
		if (high <= 0L) return null;
		GasBoilPlan first = boilCandidate(
				cell, node, liquidHandle, gasThermalHandle, transition, inventory, phase,
				thermalCell, volatileSpecies, liquidTemperature, 1L);
		if (first == null) return null;
		if (first.directionAfter() == GasLiquidPhaseModel.Direction.CONDENSE) {
			return first.saturationAfter().closerToEquilibriumThan(initialPressureImbalancePa) ? first : null;
		}
		if (first.directionAfter() == GasLiquidPhaseModel.Direction.EQUILIBRIUM) return first;
		GasBoilPlan best = first;
		long limit = high;
		long low = 2L;
		while (low <= high) {
			long middle = low + ((high - low) >>> 1);
			GasBoilPlan candidate = boilCandidate(
					cell, node, liquidHandle, gasThermalHandle, transition, inventory,
					phase, thermalCell, volatileSpecies, liquidTemperature, middle);
			if (candidate == null || candidate.directionAfter() != GasLiquidPhaseModel.Direction.BOIL) {
				high = middle - 1L;
			} else {
				best = candidate;
				low = middle + 1L;
			}
		}
		long crossingBatches = best.batches() + 1L;
		if (crossingBatches > limit) return best;
		GasBoilPlan crossing = boilCandidate(
				cell, node, liquidHandle, gasThermalHandle, transition, inventory, phase,
				thermalCell, volatileSpecies, liquidTemperature, crossingBatches);
		return crossing != null && crossing.saturationAfter().closerToEquilibriumThan(
				best.saturationAfter().pressureImbalancePa()) ? crossing : best;
	}

	private GasBoilPlan boilCandidate(
			int cell, int node, int liquidHandle, int gasThermalHandle,
			ThermalPhaseTransition transition, PhaseCellInventory before,
			GasLiquidPhaseModel phase, ThermalDomainState.PhaseCandidateCell thermalCell,
			boolean[] volatileSpecies, double liquidTemperature, long batches
	) {
		try {
			ThermalDomainState thermal = support.thermal();
			ChemicalParcelStore chemistry = support.chemistry();
			GasDomainState gasState = support.gasState();
			long removedSvq = Math.multiplyExact(batches, transition.lowerVolumeSvqPerBatch());
			double removedMass = transition.massMuPerBatch().multiply(batches).toDouble();
			PhaseRemovalPlan removal = planGasRemoval(
					cell, liquidHandle, removedSvq, removedMass, liquidTemperature, volatileSpecies);
			GasInventory batch = chemistry == null ? phase.gasBatch(transition, batches)
					: phase.gasBatch(transition, batches, removal.movedComposition().amounts(),
							removal.movedComposition().massResidual());
			double batchEnergy = phase.internalEnergyAtTemperature(transition, batch, liquidTemperature);
			double remainingMass = before.upperMass() - removedMass;
			if (remainingMass < 0.0 && remainingMass >= -massTolerance(before.upperMass())) remainingMass = 0.0;
			PhaseComposition remainingComposition = before.upperComposition().minus(removal.movedComposition());
			List<ThermalStateFunction.InventoryPortion> remainingLiquid = liquidInventoryAfterBoiling(
					cell, liquidHandle, remainingMass, remainingComposition);
			double candidateLiquidTemperature = thermal.requirePhaseCandidateState(
					thermalCell, remainingLiquid, -batchEnergy);
			GasInventory candidateGas = gasState.network.inventory(node).merge(batch);
			double candidateGasEnergy = thermal.gasNodeEnergy(node, gasState.network) + batchEnergy;
			GasLiquidPhaseModel.Saturation saturationAfter = prospectiveSaturation(
					node, transition, phase, candidateLiquidTemperature, candidateGas, candidateGasEnergy);
			return new GasBoilPlan(
					batches, removedSvq, removal, batch, candidateGasEnergy, saturationAfter);
		} catch (ThermalStateFunction.EnergyOutsideValidityException
				| PhaseCandidateUnavailableException unavailable) {
			return null;
		}
	}

	private void commitBoil(
			int cell, int node, GasBoilPlan plan,
			ThermalDomainState.PhaseCandidateCell thermalCell
	) {
		ThermalDomainState thermal = support.thermal();
		GasDomainState gasState = support.gasState();
		double gasEnergyBefore = thermal.gasNodeEnergy(node, gasState.network);
		support.commitLiquidRemoval(cell, plan.removal());
		gasState.network.commitPhaseSource(node, plan.batch());
		thermal.commitGasNodeEnergy(node, plan.candidateGasEnergy());
		support.commitPendingVolume(cell, -plan.removedSvq());
		support.markGridDirty();
		thermal.repartitionPhaseCellAndGasEnergyConserving(
				thermalCell, gasEnergyBefore, plan.candidateGasEnergy(),
				support.state().thermalMarkers(), plan.removal().carrierTopologyChanged());
	}

	private GasCondensePlan planCondense(
			int cell, int node, int liquidHandle, ThermalPhaseTransition transition,
			GasLiquidPhaseModel phase,
			ThermalDomainState.PhaseCandidateCell thermalCell, String gasCarrierId,
			double initialPressureImbalancePa, long maximumBatches
	) {
		GasDomainState gasState = support.gasState();
		GasComponentAmount vapor = gasState.network.inventory(node).component(gasCarrierId);
		if (vapor == null) return null;
		long nanosPerBatch = phase.gasBatch(transition, 1L).standardVolumeNanos();
		long byGas = vapor.standardVolumeNanos() / nanosPerBatch;
		long occupied = Math.addExact(Math.addExact(
				liquidCellSvq(cell), support.granularSvq(cell)),
				support.thermal().coherentPhases().cellOccupiedSvq(cell));
		long capacity = SolidVolume.fromCellVolumeExact(
				support.state().geometry().cutCells.openCellVolume(cell));
		long byCapacity = (capacity - occupied) / transition.lowerVolumeSvqPerBatch();
		long high = Math.min(maximumBatches, Math.min(byGas, byCapacity));
		if (high <= 0L) return null;
		GasCondensePlan first = condenseCandidate(
				cell, node, liquidHandle, transition, phase, thermalCell, 1L);
		if (first == null) return null;
		if (first.directionAfter() == GasLiquidPhaseModel.Direction.BOIL) {
			return first.saturationAfter().closerToEquilibriumThan(initialPressureImbalancePa) ? first : null;
		}
		if (first.directionAfter() == GasLiquidPhaseModel.Direction.EQUILIBRIUM) return first;
		GasCondensePlan best = first;
		long limit = high;
		long low = 2L;
		while (low <= high) {
			long middle = low + ((high - low) >>> 1);
			GasCondensePlan candidate = condenseCandidate(
					cell, node, liquidHandle, transition, phase, thermalCell, middle);
			if (candidate == null || candidate.directionAfter()
					!= GasLiquidPhaseModel.Direction.CONDENSE) {
				high = middle - 1L;
			} else {
				best = candidate;
				low = middle + 1L;
			}
		}
		long crossingBatches = best.batches() + 1L;
		if (crossingBatches > limit) return best;
		GasCondensePlan crossing = condenseCandidate(
				cell, node, liquidHandle, transition, phase, thermalCell, crossingBatches);
		return crossing != null && crossing.saturationAfter().closerToEquilibriumThan(
				best.saturationAfter().pressureImbalancePa()) ? crossing : best;
	}

	private GasCondensePlan condenseCandidate(
			int cell, int node, int liquidHandle, ThermalPhaseTransition transition,
			GasLiquidPhaseModel phase,
			ThermalDomainState.PhaseCandidateCell thermalCell, long batches
	) {
		try {
			ThermalDomainState thermal = support.thermal();
			ChemicalParcelStore chemistry = support.chemistry();
			GasDomainState gasState = support.gasState();
			LiquidParticleStore particles = support.state().particles();
			GasInventory requestedBatch = phase.gasBatch(transition, batches);
			String componentId = requestedBatch.components().getFirst().componentId();
			GasInventory.Split proportionalTake = gasState.network.previewComponentTake(
					node, componentId, requestedBatch.standardVolumeNanos());
			double requestedMass = transition.massMuPerBatch().multiply(batches).toDouble();
			GasComponentAmount proportionalMoved = proportionalTake.first().components().getFirst();
			double requestedResidual = requestedBatch.components().getFirst().massResidual();
			if (chemistry != null) {
				requestedResidual = requestedMass - PhaseComposition.fromGas(proportionalMoved)
						.exactSpeciesMass(chemistry.registry()).toDouble();
			}
			GasInventory.Split take;
			try {
				take = gasState.network.previewComponentTake(
						node, componentId, requestedBatch.standardVolumeNanos(),
						requestedMass, requestedResidual);
			} catch (IllegalArgumentException unrepresentableColumns) {
				throw new PhaseCandidateUnavailableException(
						"Condensation has no complete representable gas column quantum", unrepresentableColumns);
			}
			GasComponentAmount moved = take.first().components().getFirst();
			if (!FluidDomain.massClose(moved.mass(), requestedMass)) {
				throw new FluidDomain.ThermalPhaseBlockedException(
						"Condensation gas batch mass differs from its exact transition");
			}
			long addedSvq = Math.multiplyExact(batches, transition.lowerVolumeSvqPerBatch());
			long occupied = Math.addExact(Math.addExact(
					liquidCellSvq(cell), support.granularSvq(cell)),
					thermal.coherentPhases().cellOccupiedSvq(cell));
			long capacity = SolidVolume.fromCellVolumeExact(
					support.state().geometry().cutCells.openCellVolume(cell));
			if (Math.addExact(occupied, addedSvq) > capacity) {
				throw new PhaseCandidateUnavailableException(
						"Condensation exceeds interface-cell capacity");
			}
			double gasTemperature = thermal.gasTemperature(node, gasState.network);
			double batchEnergy = phase.internalEnergyAtTemperature(transition, take.first(), gasTemperature);
			double candidateGasEnergy = thermal.gasNodeEnergy(node, gasState.network) - batchEnergy;
			int target = -1;
			for (int marker = support.state().markerGrid().cellMarkerHead[cell]; marker >= 0;
					marker = support.state().markerGrid().particleCellNext[marker]) {
				if (particles.materialHandle(marker) == liquidHandle
						&& (chemistry == null || !chemistry.indicator(marker))) {
					target = marker;
					break;
				}
			}
			PhaseComposition movedComposition = PhaseComposition.fromGas(moved);
			ChemicalMixtureRecord condensedMixture = null;
			if (chemistry != null) {
				if (movedComposition.isEmpty()) throw new FluidDomain.ThermalPhaseBlockedException(
						"Composition-bearing condensation has no gas species truth");
				condensedMixture = target >= 0
						? PhaseComposition.fromMixture(chemistry.record(target), chemistry.registry())
								.plus(movedComposition).toMixture(chemistry.registry())
						: movedComposition.toMixture(chemistry.registry());
				chemistry.requireCompatible(
						condensedMixture, support.materialRegistry().material(liquidHandle).id(), false);
				if (target >= 0) chemistry.requireCanCommitPlannedReplacements(
						new int[]{target}, new ChemicalMixtureRecord[]{condensedMixture});
			} else if (!moved.species().isEmpty()) {
				throw new FluidDomain.ThermalPhaseBlockedException(
						"Gas species require liquid chemistry during condensation");
			}
			float targetVolume = 0.0F;
			double targetMass = 0.0;
			int slot = -1;
			if (target >= 0) {
				targetVolume = PhaseChangeStageSupport.adjustedMarkerVolume(
						particles.volume(target), addedSvq, "Condensation");
				targetMass = particles.mass(target) + support.state().unitScale().solverMass(moved.mass());
			} else {
				if (particles.count() >= support.state().maximumParticleCount()) {
					throw new PhaseCandidateUnavailableException("Condensation exceeds marker budget");
				}
				slot = support.firstOpenSlot(cell);
				if (slot < 0) throw new PhaseCandidateUnavailableException(
						"Condensation has no collision-valid marker slot");
			}
			double candidateLiquidTemperature = thermal.requirePhaseCandidateState(
					thermalCell, liquidInventoryAfterCondensation(
							cell, liquidHandle, target, targetMass, condensedMixture, moved.mass()),
					batchEnergy);
			GasLiquidPhaseModel.Saturation saturationAfter = prospectiveSaturation(
					node, transition, phase, candidateLiquidTemperature, take.second(), candidateGasEnergy);
			return new GasCondensePlan(
					batches, take.first(), candidateGasEnergy, target, condensedMixture,
					targetVolume, targetMass, slot, addedSvq, moved.mass(), saturationAfter);
		} catch (ThermalStateFunction.EnergyOutsideValidityException
				| PhaseCandidateUnavailableException unavailable) {
			return null;
		}
	}

	private GasLiquidPhaseModel.Saturation prospectiveSaturation(
			int node, ThermalPhaseTransition transition, GasLiquidPhaseModel phase,
			double liquidTemperature, GasInventory gasInventory, double gasEnergy
	) {
		try {
			double pressure = support.thermal().requireGasNodeState(
					node, gasInventory, gasEnergy, support.gasState().network);
			return phase.saturation(transition, liquidTemperature, gasInventory, pressure);
		} catch (ThermalStateFunction.EnergyOutsideValidityException outsideThermalRange) {
			throw outsideThermalRange;
		} catch (IllegalArgumentException outsideGasPhaseRange) {
			throw new PhaseCandidateUnavailableException(
					"Gas phase candidate is outside its pressure or saturation validity range",
					outsideGasPhaseRange);
		}
	}

	private void commitCondense(
			int cell, int node, int liquidHandle, GasCondensePlan plan,
			ThermalDomainState.PhaseCandidateCell thermalCell
	) {
		ThermalDomainState thermal = support.thermal();
		ChemicalParcelStore chemistry = support.chemistry();
		GasDomainState gasState = support.gasState();
		LiquidParticleStore particles = support.state().particles();
		double gasEnergyBefore = thermal.gasNodeEnergy(node, gasState.network);
		gasState.network.commitPhaseSink(node, plan.removedGas());
		thermal.commitGasNodeEnergy(node, plan.candidateGasEnergy());
		if (plan.target() >= 0) {
			if (chemistry != null) chemistry.commitPlannedReplacements(
					new int[]{plan.target()}, new ChemicalMixtureRecord[]{plan.condensedMixture()});
			particles.setVolume(plan.target(), plan.targetVolume());
			particles.setMass(plan.target(), plan.targetMass());
		} else {
			support.spawnParticle(
					cell, plan.slot(), SolidVolume.toCellVolume(plan.addedSvq()), liquidHandle,
					support.state().unitScale().solverMass(plan.movedMass()), plan.condensedMixture());
		}
		support.commitPendingVolume(cell, plan.addedSvq());
		support.markGridDirty();
		thermal.repartitionPhaseCellAndGasEnergyConserving(
				thermalCell, gasEnergyBefore, plan.candidateGasEnergy(),
				support.state().thermalMarkers(), plan.target() < 0);
	}

	private PhaseCellInventory gasLiquidCellInventory(int cell, int liquidHandle) {
		LiquidParticleStore particles = support.state().particles();
		ChemicalParcelStore chemistry = support.chemistry();
		double mass = 0.0, energy = 0.0, freezableMass = 0.0;
		long svq = 0L, freezableSvq = 0L;
		boolean found = false;
		PhaseCompositionBuilder composition = chemistry == null ? null
				: new PhaseCompositionBuilder(chemistry);
		for (int marker = support.state().markerGrid().cellMarkerHead[cell]; marker >= 0;
				marker = support.state().markerGrid().particleCellNext[marker]) {
			if (particles.materialHandle(marker) != liquidHandle) continue;
			svq = Math.addExact(svq, SolidVolume.cellVolumeSvqAtLeast(particles.volume(marker)));
			mass += support.state().unitScale().kilograms(particles.mass(marker));
			energy += support.thermal().liquidInternalEnergy(marker);
			found = true;
			if (chemistry != null) composition.add(chemistry.record(marker));
			if (chemistry == null || !chemistry.indicator(marker)) {
				freezableSvq = Math.addExact(
						freezableSvq, SolidVolume.cellVolumeSvqAtMost(particles.volume(marker)));
				freezableMass += support.state().unitScale().kilograms(particles.mass(marker));
			}
		}
		return found ? new PhaseCellInventory(
				0L, svq, 0.0, mass, energy, PhaseComposition.EMPTY,
				chemistry == null ? PhaseComposition.EMPTY : composition.build(),
				freezableSvq, freezableMass) : null;
	}

	private boolean gasLiquidCellMatches(int cell, int liquidHandle) {
		boolean found = false;
		for (int marker = support.state().markerGrid().cellMarkerHead[cell]; marker >= 0;
				marker = support.state().markerGrid().particleCellNext[marker]) {
			if (support.state().particles().materialHandle(marker) == liquidHandle) found = true;
		}
		return found;
	}

	private long liquidCellSvq(int cell) {
		LiquidParticleStore particles = support.state().particles();
		long total = 0L;
		for (int marker = support.state().markerGrid().cellMarkerHead[cell]; marker >= 0;
				marker = support.state().markerGrid().particleCellNext[marker]) {
			total = Math.addExact(total, SolidVolume.cellVolumeSvqAtLeast(particles.volume(marker)));
		}
		return total;
	}

	private boolean[] gasVolatileSpecies(int gasThermalHandle) {
		ChemicalParcelStore chemistry = support.chemistry();
		if (chemistry == null) return null;
		var component = support.gasState().network.registry().component(
				support.thermal().registry().material(gasThermalHandle).carrierId());
		boolean[] result = new boolean[chemistry.registry().species().size()];
		for (int species = 0; species < result.length; species++) {
			String stableId = chemistry.registry().species(species).id();
			result[species] = component.allowsSpecies(stableId)
					&& support.thermal().registry().hasSpeciesOffset(gasThermalHandle, stableId);
		}
		return result;
	}

	private PhaseRemovalPlan planGasRemoval(
			int cell, int liquidHandle, long requestedSvq, double requestedMass,
			double cellTemperature, boolean[] volatileSpecies
	) {
		LiquidParticleStore particles = support.state().particles();
		ChemicalParcelStore chemistry = support.chemistry();
		ArrayList<PhaseMarkerAllocation> allocations = new ArrayList<>();
		PhaseCompositionBuilder moved = chemistry == null ? null : new PhaseCompositionBuilder(chemistry);
		SignedDyadic movedEnergy = SignedDyadic.ZERO;
		long remainingSvq = requestedSvq;
		double remainingMass = requestedMass;
		for (int marker = support.state().markerGrid().cellMarkerTail[cell];
				marker >= 0 && remainingSvq > 0L;
				marker = support.state().markerGrid().particleCellPrevious[marker]) {
			if (particles.materialHandle(marker) != liquidHandle
					|| chemistry != null && chemistry.indicator(marker)) continue;
			long markerSvq = SolidVolume.cellVolumeSvqAtMost(particles.volume(marker));
			if (markerSvq == 0L) continue;
			long takeSvq = Math.min(markerSvq, remainingSvq);
			if (chemistry != null) {
				boolean movedSpecies = false, nonVolatile = false;
				ChemicalMixtureRecord mixture = chemistry.record(marker);
				for (int entry = 0; entry < mixture.entryCount(); entry++) {
					if (volatileSpecies[mixture.speciesHandle(entry)]) movedSpecies = true;
					else nonVolatile = true;
				}
				if (!movedSpecies) continue;
				if (takeSvq == markerSvq && nonVolatile) {
					if (markerSvq <= 1L) continue;
					takeSvq = markerSvq - 1L;
				}
			}
			double takeMass = takeSvq == remainingSvq ? remainingMass
					: support.state().unitScale().kilograms(particles.mass(marker))
							* ((double) takeSvq / markerSvq);
			double takeSolverMass = support.state().unitScale().solverMass(takeMass);
			boolean full = takeSvq == markerSvq
					&& markerSvq == SolidVolume.cellVolumeSvqAtLeast(particles.volume(marker));
			float newVolume = full ? 0.0F
					: PhaseChangeStageSupport.adjustedMarkerVolume(
							particles.volume(marker), -takeSvq, "Boiling");
			double newMass = particles.mass(marker) - takeSolverMass;
			if (!(takeMass > 0.0) || full && Math.abs(newMass) > massTolerance(particles.mass(marker))
					|| !full && !(newMass > 0.0)) {
				throw new PhaseCandidateUnavailableException(
						"Boiling batch mass/volume split is not representable");
			}
			ChemicalParcelStore.SplitPlan chemicalPlan = chemistry == null ? null
					: chemistry.planSelectivePhaseTruncate(
							marker, newVolume, SolidVolume.toCellVolume(takeSvq),
							full ? 0.0 : newMass, takeSolverMass, volatileSpecies);
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
					"Boiling exact batch cannot be allocated to volatile liquid inventory");
		}
		return new PhaseRemovalPlan(
				List.copyOf(allocations), chemistry == null ? PhaseComposition.EMPTY : moved.build(),
				movedEnergy.toDouble());
	}

	private List<ThermalStateFunction.InventoryPortion> liquidInventoryAfterCondensation(
			int cell, int liquidHandle, int target, double targetMass,
			ChemicalMixtureRecord condensedMixture, double appendedMass
	) {
		LiquidParticleStore particles = support.state().particles();
		ChemicalParcelStore chemistry = support.chemistry();
		ArrayList<ThermalStateFunction.InventoryPortion> result = new ArrayList<>();
		for (int marker = support.state().markerGrid().cellMarkerHead[cell]; marker >= 0;
				marker = support.state().markerGrid().particleCellNext[marker]) {
			int markerLiquidHandle = particles.materialHandle(marker);
			double mass = marker == target ? targetMass : particles.mass(marker);
			ChemicalMixtureRecord mixture = chemistry == null ? null
					: marker == target ? condensedMixture : chemistry.record(marker);
			result.add(liquidInventoryPortion(
					markerLiquidHandle, support.state().unitScale().kilograms(mass), mixture));
		}
		if (target < 0) result.add(liquidInventoryPortion(
				liquidHandle, appendedMass, condensedMixture));
		return List.copyOf(result);
	}

	/** Keeps non-transitioning liquid carriers in the same cell as exact LTE heat-bath inventory. */
	private List<ThermalStateFunction.InventoryPortion> liquidInventoryAfterBoiling(
			int cell, int liquidHandle, double remainingMass, PhaseComposition remainingComposition
	) {
		LiquidParticleStore particles = support.state().particles();
		ChemicalParcelStore chemistry = support.chemistry();
		ArrayList<ThermalStateFunction.InventoryPortion> result = new ArrayList<>();
		for (int marker = support.state().markerGrid().cellMarkerHead[cell]; marker >= 0;
				marker = support.state().markerGrid().particleCellNext[marker]) {
			int markerLiquidHandle = particles.materialHandle(marker);
			if (markerLiquidHandle == liquidHandle) continue;
			result.add(liquidInventoryPortion(markerLiquidHandle,
					support.state().unitScale().kilograms(particles.mass(marker)),
					chemistry == null ? null : chemistry.record(marker)));
		}
		if (remainingMass > 0.0) result.add(new ThermalStateFunction.InventoryPortion(
				support.thermal().thermalMaterialHandleForFluid(liquidHandle), remainingMass,
				chemistry == null ? Map.of() : remainingComposition.amounts()));
		return List.copyOf(result);
	}

	private ThermalStateFunction.InventoryPortion liquidInventoryPortion(
			int liquidHandle, double physicalMass, ChemicalMixtureRecord mixture
	) {
		ChemicalParcelStore chemistry = support.chemistry();
		return new ThermalStateFunction.InventoryPortion(
				support.thermal().thermalMaterialHandleForFluid(liquidHandle), physicalMass,
				mixture == null ? Map.of() : mixture.stableAmounts(chemistry.registry()));
	}

	private record GasBoilPlan(
			long batches, long removedSvq, PhaseRemovalPlan removal, GasInventory batch,
			double candidateGasEnergy, GasLiquidPhaseModel.Saturation saturationAfter
	) {
		GasLiquidPhaseModel.Direction directionAfter() { return saturationAfter.direction(); }
	}

	private record GasCondensePlan(
			long batches, GasInventory removedGas, double candidateGasEnergy, int target,
			ChemicalMixtureRecord condensedMixture, float targetVolume, double targetMass,
			int slot, long addedSvq, double movedMass, GasLiquidPhaseModel.Saturation saturationAfter
	) {
		GasLiquidPhaseModel.Direction directionAfter() { return saturationAfter.direction(); }
	}

	private record GasPhaseDirectionKey(String transitionId, int node, long liquidTemperatureBits) { }
}

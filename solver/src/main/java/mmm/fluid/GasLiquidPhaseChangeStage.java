package mmm.fluid;

import mmm.chemistry.ChemicalMixtureRecord;
import mmm.chemistry.ChemicalParcelStore;
import mmm.fluid.air.AirNetwork;
import mmm.fluid.air.GasComponentAmount;
import mmm.fluid.air.GasInventory;
import mmm.granular.SolidVolume;
import mmm.thermal.GasLiquidPhaseModel;
import mmm.thermal.GasThermalModel;
import mmm.thermal.LiquidEnergyColumn;
import mmm.thermal.ThermalDomainState;
import mmm.thermal.ThermalLimits;
import mmm.thermal.ThermalMaterialDefinition;
import mmm.thermal.ThermalPhaseTransition;
import mmm.thermal.ThermalStateFunction;
import mmm.units.SignedDyadic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongFunction;

import static mmm.fluid.PhaseChangeStageSupport.PhaseCandidateUnavailableException;
import static mmm.fluid.PhaseChangeStageSupport.PhaseCellInventory;
import static mmm.fluid.PhaseChangeStageSupport.PhaseComposition;
import static mmm.fluid.PhaseChangeStageSupport.PhaseCompositionBuilder;
import static mmm.fluid.PhaseChangeStageSupport.PhaseMarkerAllocation;
import static mmm.fluid.PhaseChangeStageSupport.PhaseRemovalPlan;
import static mmm.fluid.PhaseChangeStageSupport.massTolerance;

/** Owns sealed-interface liquid/gas planning and commit in the thermal axis. */
final class GasLiquidPhaseChangeStage {
	private final PhaseChangeStageSupport support;
	private final GasPhaseInterfaceResolver interfaces;

	GasLiquidPhaseChangeStage(PhaseChangeStageSupport support) {
		this.support = support;
		this.interfaces = new GasPhaseInterfaceResolver(support);
	}

	FluidDomain.GasThermalPhaseStats step(int maximumOperations, boolean phaseGridPrepared) {
		support.prepareGranularOccupancy();
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
				ThermalDomainState.PhaseCandidateCell thermalCell = null;
				for (GasPhaseInterfaceResolver.Candidate gasInterface : interfaces.resolve(
						cell, transition, phase, gasState, liquidTemperature)) {
					int node = gasInterface.node();
					GasLiquidPhaseModel.Saturation saturation = gasInterface.saturation();
					GasLiquidPhaseModel.Direction direction = saturation.direction();
					if (direction == GasLiquidPhaseModel.Direction.EQUILIBRIUM) continue;
					PhaseCellInventory inventory = gasLiquidCellInventory(cell, liquidHandle, gasInterface.supportMask());
					if (inventory == null) continue;
					if (thermalCell == null) {
						thermalCell = thermal.preparePhaseCandidateCell(
								cell, support.state().thermalMarkers());
					}
					long remainingBudget = maximumOperations - operations;
					if (direction == GasLiquidPhaseModel.Direction.BOIL) {
						GasBoilPlan plan = planBoil(
								cell, node, liquidHandle, gasThermalHandle, transition, inventory,
								phase, thermalCell, volatileSpecies, liquidTemperature,
								remainingBudget, gasInterface);
						if (plan == null) continue;
						commitBoil(cell, node, plan);
						operations = Math.addExact(operations, Math.toIntExact(plan.batches()));
						boiled++;
						break;
					} else if (direction == GasLiquidPhaseModel.Direction.CONDENSE) {
						GasCondensePlan plan = planCondense(
								cell, node, liquidHandle, transition, phase,
								thermalCell, gas.carrierId(), remainingBudget, gasInterface);
						if (plan == null) continue;
						commitCondense(cell, node, liquidHandle, plan);
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
			long maximumBatches, GasPhaseInterfaceResolver.Candidate contact
	) {
		double batchMass = transition.massMuPerBatch().toDouble();
		long byVolume = inventory.freezableUpperSvq() / transition.lowerVolumeSvqPerBatch();
		long byMass = (long) Math.floor((inventory.freezableUpperMass()
				+ massTolerance(inventory.freezableUpperMass())) / batchMass);
		AirNetwork network = support.gasState().network;
		long gasRoom = Math.min(Long.MAX_VALUE - network.inventory(node).standardVolumeNanos(),
				Math.min(Long.MAX_VALUE - network.sealedInventoryNanos(),
						Long.MAX_VALUE - network.externalSource().standardVolumeNanos()));
		long byGas = gasRoom
				/ phase.gasBatch(transition, 1L).standardVolumeNanos();
		long high = Math.min(maximumBatches, Math.min(byGas, Math.min(byVolume, byMass)));
		if (high <= 0L) return null;
		var contactPlanes = GasRuntimeGeometry.prepareBoilContactPlanes(support, cell, contact);
		var envelope = GasPhaseGeometryEnvelope.forBoiling(support, cell, node);
		if (envelope == null) return GasPhaseBatchSearch.maximum(high, batches -> completeBoil(cell, node, transition, phase,
				contact, contactPlanes, prepareBoilInventory(cell, node, liquidHandle, transition, phase, thermalCell,
						volatileSpecies, liquidTemperature, batches, contact)), (low, upper) -> false);
		GasBoilInventoryPlan[] prepared = new GasBoilInventoryPlan[Math.toIntExact(high) + 1];
		boolean[] evaluated = new boolean[prepared.length];
		LongFunction<GasBoilInventoryPlan> caloric = batches -> {
			int index = Math.toIntExact(batches);
			if (!evaluated[index]) {
				prepared[index] = prepareBoilInventory(cell, node, liquidHandle, transition, phase,
						thermalCell, volatileSpecies, liquidTemperature, batches, contact);
				evaluated[index] = true;
			}
			return prepared[index];
		};
		GasThermalModel gasThermal = new GasThermalModel(support.thermal().registry(), network.registry());
		var bounds = GasBoilingBatchBounds.forBoiling(support, cell, node, liquidHandle, transition, thermalCell,
				liquidTemperature, volatileSpecies, contact.supportMask(), phase, envelope);
		GasPhaseBatchSearch.Exclusion exclusion = (low, upper) -> {
			if (bounds != null) return bounds.excluded(low, upper);
			for (long batches = upper; batches >= low; batches--) {
				GasBoilInventoryPlan candidate = caloric.apply(batches);
				if (candidate == null) continue;
				if (!boilingSaturationExcluded(candidate, envelope, transition, phase, gasThermal)) return false;
				prepared[Math.toIntExact(batches)] = null;
			}
			return true;
		};
		return GasPhaseBatchSearch.maximum(high, batches -> {
			GasBoilInventoryPlan inventoryPlan = caloric.apply(batches);
			GasBoilPlan candidate = bounds != null && inventoryPlan != null
					&& boilingSaturationExcluded(inventoryPlan, envelope, transition, phase, gasThermal) ? null
					: completeBoil(cell, node, transition, phase, contact, contactPlanes, inventoryPlan);
			prepared[Math.toIntExact(batches)] = null;
			return candidate;
		}, exclusion);
	}

	private GasBoilInventoryPlan prepareBoilInventory(
			int cell, int node, int liquidHandle, ThermalPhaseTransition transition,
			GasLiquidPhaseModel phase, ThermalDomainState.PhaseCandidateCell thermalCell,
			boolean[] volatileSpecies, double liquidTemperature, long batches, GasPhaseInterfaceResolver.Candidate contact
	) {
		try {
			ThermalDomainState thermal = support.thermal();
			ChemicalParcelStore chemistry = support.chemistry();
			GasDomainState gasState = support.gasState();
			long removedSvq = Math.multiplyExact(batches, transition.lowerVolumeSvqPerBatch());
			double removedMass = transition.massMuPerBatch().multiply(batches).toDouble();
			PhaseRemovalPlan removal = planGasRemoval(
					cell, liquidHandle, removedSvq, removedMass, liquidTemperature, volatileSpecies, contact.supportMask());
			GasInventory batch = chemistry == null ? phase.gasBatch(transition, batches)
					: phase.gasBatch(transition, batches, removal.movedComposition().amounts(),
							removal.movedComposition().massResidual());
			double batchEnergy = phase.internalEnergyAtTemperature(transition, batch, liquidTemperature);
			List<ThermalStateFunction.InventoryPortion> remainingLiquid = liquidInventoryAfterBoiling(
					cell, removal);
			AirNetwork.PhaseMutationPlan gasPlan = gasState.network.planPhaseSource(node, batch);
			GasInventory candidateGas = gasPlan.inventoryAfter();
			support.representedGasMassTransfer(node, candidateGas, batch.components().getFirst().componentId(), removedMass);
			double candidateGasEnergy = thermal.gasNodeEnergy(node, gasState.network) + batchEnergy;
			if (!Double.isFinite(candidateGasEnergy)) throw new PhaseCandidateUnavailableException(
					"Boiling gas energy exceeds its finite representation");
			ThermalDomainState.PhaseCellEnergyPlan thermalPlan = thermal.planPhaseCellEnergy(
					thermalCell, remainingLiquid, thermal.gasNodeEnergy(node, gasState.network), candidateGasEnergy);
			var liquidGeometry = PhaseLiquidGeometryEdit.afterBoiling(support.state(), cell, removal);
			return new GasBoilInventoryPlan(batches, removedSvq, removal, gasPlan, thermalPlan, candidateGasEnergy, liquidGeometry);
		} catch (ThermalStateFunction.EnergyOutsideValidityException
				| ThermalStateFunction.EnergyRepresentationException
				| ThermalDomainState.EmptyPhaseCandidateEnergyException
				| AirNetwork.PhaseCapacityExceededException
				| ThermalDomainState.PhaseCellEnergyUnavailableException
				| PhaseCandidateUnavailableException unavailable) {
			return null;
		}
	}

	private boolean boilingSaturationExcluded(GasBoilInventoryPlan candidate, GasPhaseGeometryEnvelope envelope,
	                                         ThermalPhaseTransition transition, GasLiquidPhaseModel phase, GasThermalModel gasThermal) {
		try {
			double volume = envelope.volumeUpper(candidate.liquidGeometry());
			if (!(Double.isFinite(volume) && volume > 0)) return false;
			var stock = candidate.gasPlan().inventoryAfter();
			double temperature = candidate.thermalPlan().temperatureK();
			if (envelope.possibleStocks().isEmpty()) {
				double pressureLower = gasThermal.absolutePressure(stock, volume, candidate.candidateGasEnergy());
				if (Double.isNaN(temperature)) temperature = gasThermal.state(stock, candidate.candidateGasEnergy()).temperatureK();
				double partialLower = phase.saturation(transition, temperature, stock, pressureLower).componentPartialPressurePa();
				return phase.saturationPressureBound(transition, temperature, true) < Math.max(0, Math.nextDown(partialLower));
			}
			// An absent LTE liquid carrier follows the actual mixed gas temperature;
			// its saturation upper bound needs more than the gas-temperature lower bound.
			if (Double.isNaN(temperature)) return false;
			var merged = new GasPhaseMergeBounds(support.thermal().registry(), gasThermal, envelope.possibleStocks());
			double gasTemperature = merged.temperatureLower(stock, candidate.candidateGasEnergy());
			String vaporId = support.thermal().registry().material(
					support.thermal().registry().materialHandle(transition.upperEnergyMaterialId())).carrierId();
			double partialLower = merged.partialPressureLower(stock, vaporId, gasTemperature,
					envelope.baseVolumeUpper(candidate.liquidGeometry(), vaporId));
			if (!Double.isFinite(partialLower)) return false;
			return phase.saturationPressureBound(transition, temperature, true) < Math.max(0, Math.nextDown(partialLower));
		} catch (IllegalArgumentException | ArithmeticException unresolved) { return false; }
	}

	private GasBoilPlan completeBoil(int cell, int node, ThermalPhaseTransition transition, GasLiquidPhaseModel phase,
	                                GasPhaseInterfaceResolver.Candidate contact, GasRuntimeGeometry.BoilContactPlanes contactPlanes,
	                                GasBoilInventoryPlan candidate) {
		if (candidate == null) return null;
		if (!interfaces.mayKeepBoilContact(contactPlanes, candidate.liquidGeometry(), contact)) return null;
		try {
			var topology = support.planGasGeometry(candidate.liquidGeometry(), node, candidate.gasPlan(),
					candidate.candidateGasEnergy(), candidate.thermalPlan());
			if (!interfaces.allowsAfter(cell, contact, transition, phase, candidate.thermalPlan().temperatureK(),
					topology, GasLiquidPhaseModel.Direction.BOIL, false)) return null;
			return new GasBoilPlan(candidate.batches(), candidate.removedSvq(), candidate.removal(), candidate.gasPlan(),
					candidate.thermalPlan(), candidate.candidateGasEnergy(), topology);
		} catch (ThermalStateFunction.EnergyOutsideValidityException
				| ThermalStateFunction.EnergyRepresentationException
				| ThermalDomainState.EmptyPhaseCandidateEnergyException
				| AirNetwork.PhaseCapacityExceededException
				| ThermalDomainState.PhaseCellEnergyUnavailableException
				| PhaseCandidateUnavailableException unavailable) {
			return null;
		}
	}

	private void commitBoil(
			int cell, int node, GasBoilPlan plan
	) {
		ThermalDomainState thermal = support.thermal();
		GasDomainState gasState = support.gasState();
		thermal.requirePhaseCellEnergyCurrent(plan.thermalPlan());
		gasState.network.requirePhaseMutationCurrent(plan.gasPlan());
		support.requireGasGeometryCurrent(plan.topology(), plan.gasPlan(), plan.thermalPlan());
		support.commitLiquidRemoval(cell, plan.removal());
		gasState.network.commitPhaseMutation(plan.gasPlan());
		thermal.commitGasNodeEnergy(node, plan.candidateGasEnergy());
		support.commitPendingVolume(cell, -plan.removedSvq());
		support.markGridDirty();
		thermal.commitPhaseCellEnergy(plan.thermalPlan(), support.state().thermalMarkers());
		support.commitGasGeometry(plan.topology());
	}

	private GasCondensePlan planCondense(
			int cell, int node, int liquidHandle, ThermalPhaseTransition transition,
			GasLiquidPhaseModel phase,
			ThermalDomainState.PhaseCandidateCell thermalCell, String gasCarrierId,
			long maximumBatches, GasPhaseInterfaceResolver.Candidate contact
	) {
		GasDomainState gasState = support.gasState();
		GasComponentAmount vapor = gasState.network.inventory(node).component(gasCarrierId);
		if (vapor == null) return null;
		long nanosPerBatch = phase.gasBatch(transition, 1L).standardVolumeNanos();
		long byGas = vapor.standardVolumeNanos() / nanosPerBatch;
		byGas = Math.min(byGas,
				(Long.MAX_VALUE - gasState.network.escapedSink().standardVolumeNanos()) / nanosPerBatch);
		long occupied = Math.addExact(Math.addExact(
				liquidCellSvq(cell), support.granularSvq(cell)),
				support.thermal().coherentPhases().cellOccupiedSvq(cell));
		long capacity = SolidVolume.fromCellVolumeExact(
				support.state().geometry().cutCells.openCellVolume(cell));
		long byCapacity = (capacity - occupied) / transition.lowerVolumeSvqPerBatch();
		long high = Math.min(maximumBatches, Math.min(byGas, byCapacity));
		if (high <= 0L) return null;
		return GasPhaseBatchSearch.maximum(high, batches -> condenseCandidate(
				cell, node, liquidHandle, transition, phase, thermalCell, batches, contact), (low, upper) -> false);
	}

	private GasCondensePlan condenseCandidate(
			int cell, int node, int liquidHandle, ThermalPhaseTransition transition,
			GasLiquidPhaseModel phase,
			ThermalDomainState.PhaseCandidateCell thermalCell, long batches, GasPhaseInterfaceResolver.Candidate contact
	) {
		try {
			ThermalDomainState thermal = support.thermal();
			ChemicalParcelStore chemistry = support.chemistry();
			GasDomainState gasState = support.gasState();
			LiquidParticleStore particles = support.state().particles();
			double requestedMass = transition.massMuPerBatch().multiply(batches).toDouble();
			if (!(Double.isFinite(requestedMass) && requestedMass > 0.0)) throw new PhaseCandidateUnavailableException(
					"Condensation batch mass exceeds its finite representation");
			GasInventory requestedBatch = phase.gasBatch(transition, batches);
			String componentId = requestedBatch.components().getFirst().componentId();
			GasInventory.Split proportionalTake = gasState.network.previewComponentTake(
					node, componentId, requestedBatch.standardVolumeNanos());
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
			AirNetwork.PhaseMutationPlan gasPlan = gasState.network.planPhaseSink(node, take.first());
			support.representedGasMassTransfer(node, gasPlan.inventoryAfter(), componentId, -requestedMass);
			if (!FluidDomain.massClose(moved.mass(), requestedMass)) {
				throw new FluidDomain.ThermalPhaseBlockedException(
						"Condensation gas batch mass differs from its exact transition");
			}
			long addedSvq = Math.multiplyExact(batches, transition.lowerVolumeSvqPerBatch());
			if (!support.canExpandCondensedVolume(addedSvq)) {
				throw new PhaseCandidateUnavailableException("Condensation exceeds region carrier capacity");
			}
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
			if (!Double.isFinite(candidateGasEnergy)) throw new PhaseCandidateUnavailableException(
					"Condensation gas energy exceeds its finite representation");
			PhaseComposition movedComposition = PhaseComposition.fromGas(moved);
			ChemicalMixtureRecord condensedMixture = null;
			if (chemistry != null) {
				long movedQuanta = 0L;
				for (long amount : movedComposition.amounts().values()) movedQuanta = Math.addExact(movedQuanta, amount);
				if (movedQuanta > mmm.chemistry.ChemicalLimits.MAX_REGION_TOTAL_QUANTA
						- chemistry.totalQuanta()) {
					throw new PhaseCandidateUnavailableException("Condensation awaits liquid chemical capacity");
				}
			}
			int target = -1;
			for (int marker = support.state().markerGrid().cellMarkerHead[cell]; marker >= 0;
					marker = support.state().markerGrid().particleCellNext[marker]) {
				if (markerInSupport(cell, marker, contact.supportMask()) && particles.materialHandle(marker) == liquidHandle
						&& (chemistry == null || !chemistry.indicator(marker))) {
					try {
						PhaseChangeStageSupport.adjustedMarkerVolume(particles.volume(marker), addedSvq, "Condensation");
						double candidateMass = particles.mass(marker) + support.state().unitScale().solverMass(moved.mass());
						support.representedMassTransfer(particles.mass(marker), candidateMass, moved.mass());
						if (chemistry != null) {
							condensedMixture = PhaseComposition.fromMixture(chemistry.record(marker), chemistry.registry())
									.plus(movedComposition).toMixture(chemistry.registry());
							chemistry.requireCompatible(condensedMixture, support.materialRegistry().material(liquidHandle).id(), false);
						}
					} catch (PhaseCandidateUnavailableException unavailable) {
						continue;
					}
					target = marker;
					break;
				}
			}
			if (chemistry == null && !moved.species().isEmpty()) {
				throw new FluidDomain.ThermalPhaseBlockedException(
						"Gas species require liquid chemistry during condensation");
			}
			float targetVolume = 0.0F;
			double targetMass = 0.0;
			CarrierMassMomentum.Plan momentum = null;
			int slot = -1;
			if (target >= 0) {
				targetVolume = PhaseChangeStageSupport.adjustedMarkerVolume(
						particles.volume(target), addedSvq, "Condensation");
				targetMass = particles.mass(target) + support.state().unitScale().solverMass(moved.mass());
				try {
					momentum = CarrierMassMomentum.plan(particles, new int[]{target}, new double[]{targetMass},
							targetMass - particles.mass(target), 0.0F, 0.0F, 0.0F);
				} catch (IllegalArgumentException unrepresentableMomentum) {
					throw new PhaseCandidateUnavailableException(
							"Condensation momentum exceeds liquid carrier representation", unrepresentableMomentum);
				}
			} else {
				support.representedMassTransfer(0.0, support.state().unitScale().solverMass(moved.mass()), moved.mass());
				if (chemistry != null) {
					if (movedComposition.amounts().isEmpty()) throw new PhaseCandidateUnavailableException(
							"Condensation needs an existing chemical receiver for a mass-only gas portion");
					condensedMixture = movedComposition.toMixture(chemistry.registry());
					chemistry.requireCompatible(condensedMixture, support.materialRegistry().material(liquidHandle).id(), false);
				}
				if (particles.count() >= support.state().maximumParticleCount()) {
					throw new PhaseCandidateUnavailableException("Condensation exceeds marker budget");
				}
				for (slot = support.firstOpenSlot(cell); slot >= 0; slot = support.firstOpenSlot(cell, slot + 1)) {
					float[] position = support.markerSlotPosition(cell, slot);
					if (CellRemapMicroTopology.positionSupport(support.state().geometry().cutCells.mask(cell), support.state().cellSize(),
							position[0], position[1], position[2]) == contact.supportMask()) break;
				}
				if (slot < 0) throw new PhaseCandidateUnavailableException(
						"Condensation has no collision-valid marker slot");
			}
			ThermalDomainState.PhaseCellEnergyPlan thermalPlan = thermal.planPhaseCellEnergy(
					thermalCell, liquidInventoryAfterCondensation(
							cell, liquidHandle, target, targetMass, condensedMixture, moved.mass()),
					thermal.gasNodeEnergy(node, support.gasState().network), candidateGasEnergy);
			double candidateLiquidTemperature = thermalPlan.temperatureK();
			var liquidGeometry = PhaseLiquidGeometryEdit.afterCondensation(support.state(), cell, target, targetVolume,
					SolidVolume.toCellVolume(addedSvq), target < 0 ? support.markerSlotPosition(cell, slot) : null);
			var topology = support.planGasGeometry(liquidGeometry, node, gasPlan, candidateGasEnergy, thermalPlan);
			if (!interfaces.allowsAfter(cell, contact, transition, phase, candidateLiquidTemperature,
					topology, GasLiquidPhaseModel.Direction.CONDENSE, gasPlan.inventoryAfter().isEmpty())) return null;
			return new GasCondensePlan(
					batches, gasPlan, thermalPlan, candidateGasEnergy, target, condensedMixture,
					targetVolume, targetMass, momentum, slot, addedSvq, moved.mass(), topology);
		} catch (ThermalStateFunction.EnergyOutsideValidityException
				| ThermalStateFunction.EnergyRepresentationException
				| AirNetwork.PhaseCapacityExceededException
				| ThermalDomainState.PhaseCellEnergyUnavailableException
				| PhaseCandidateUnavailableException unavailable) {
			return null;
		}
	}

	private void commitCondense(
			int cell, int node, int liquidHandle, GasCondensePlan plan
	) {
		ThermalDomainState thermal = support.thermal();
		ChemicalParcelStore chemistry = support.chemistry();
		GasDomainState gasState = support.gasState();
		LiquidParticleStore particles = support.state().particles();
		thermal.requirePhaseCellEnergyCurrent(plan.thermalPlan());
		gasState.network.requirePhaseMutationCurrent(plan.gasPlan());
		support.requireGasGeometryCurrent(plan.topology(), plan.gasPlan(), plan.thermalPlan());
		gasState.network.commitPhaseMutation(plan.gasPlan());
		thermal.commitGasNodeEnergy(node, plan.candidateGasEnergy());
		if (plan.target() >= 0) {
			if (chemistry != null) chemistry.commitPlannedReplacements(
					new int[]{plan.target()}, new ChemicalMixtureRecord[]{plan.condensedMixture()});
			particles.setVolume(plan.target(), plan.targetVolume());
			particles.setMass(plan.target(), plan.targetMass());
			plan.momentum().apply(particles);
		} else {
			support.spawnParticle(
					cell, plan.slot(), SolidVolume.toCellVolume(plan.addedSvq()), liquidHandle,
					support.state().unitScale().solverMass(plan.movedMass()), plan.condensedMixture());
		}
		support.commitPendingVolume(cell, plan.addedSvq());
		support.markGridDirty();
		thermal.commitPhaseCellEnergy(plan.thermalPlan(), support.state().thermalMarkers());
		support.commitGasGeometry(plan.topology());
	}

	private PhaseCellInventory gasLiquidCellInventory(int cell, int liquidHandle, int supportMask) {
		LiquidParticleStore particles = support.state().particles();
		ChemicalParcelStore chemistry = support.chemistry();
		double mass = 0.0, energy = 0.0, freezableMass = 0.0;
		long svq = 0L, freezableSvq = 0L;
		boolean found = false;
		PhaseCompositionBuilder composition = chemistry == null ? null
				: new PhaseCompositionBuilder(chemistry);
		for (int marker = support.state().markerGrid().cellMarkerHead[cell]; marker >= 0;
				marker = support.state().markerGrid().particleCellNext[marker]) {
			if (particles.materialHandle(marker) != liquidHandle || !markerInSupport(cell, marker, supportMask)) continue;
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

	private boolean markerInSupport(int cell, int marker, int supportMask) {
		var particles = support.state().particles();
		return CellRemapMicroTopology.positionSupport(support.state().geometry().cutCells.mask(cell), support.state().cellSize(),
				particles.positionX(marker), particles.positionY(marker), particles.positionZ(marker)) == supportMask;
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
			double cellTemperature, boolean[] volatileSpecies, int supportMask
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
					|| !markerInSupport(cell, marker, supportMask) || chemistry != null && chemistry.indicator(marker)) continue;
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
			if (!(Double.isFinite(takeMass) && takeMass > 0.0 && takeMass <= remainingMass)) {
				throw new PhaseCandidateUnavailableException(
						"Boiling batch mass cannot be allocated across the selected marker volumes");
			}
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
			support.representedMassTransfer(particles.mass(marker), full ? 0.0 : newMass, -takeMass);
			ChemicalParcelStore.SplitPlan chemicalPlan;
			try {
				chemicalPlan = chemistry == null ? null : chemistry.planSelectivePhaseTruncate(
						marker, newVolume, SolidVolume.toCellVolume(takeSvq),
						full ? 0.0 : newMass, takeSolverMass, volatileSpecies);
			} catch (mmm.chemistry.ChemicalReactionEngine.BudgetExceededException unavailable) {
				throw new PhaseCandidateUnavailableException(
						"Boiling batch is below the chemical carrier resolution", unavailable);
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
		java.util.TreeMap<Integer, ThermalStateFunction.InventoryPortion> finalOrdinals = new java.util.TreeMap<>();
		for (int marker = support.state().markerGrid().cellMarkerHead[cell]; marker >= 0;
				marker = support.state().markerGrid().particleCellNext[marker]) {
			int markerLiquidHandle = particles.materialHandle(marker);
			double mass = marker == target ? targetMass : particles.mass(marker);
			ChemicalMixtureRecord mixture = chemistry == null ? null
					: marker == target ? condensedMixture : chemistry.record(marker);
			finalOrdinals.put(marker, liquidInventoryPortion(
					markerLiquidHandle, support.state().unitScale().kilograms(mass), mixture));
		}
		ArrayList<ThermalStateFunction.InventoryPortion> result = new ArrayList<>(finalOrdinals.values());
		if (target < 0) result.add(liquidInventoryPortion(
				liquidHandle, support.state().unitScale().kilograms(support.state().unitScale().solverMass(appendedMass)), condensedMixture));
		return List.copyOf(result);
	}

	/** Keeps non-transitioning liquid carriers in the same cell as exact LTE heat-bath inventory. */
	private List<ThermalStateFunction.InventoryPortion> liquidInventoryAfterBoiling(
			int cell, PhaseRemovalPlan removal
	) {
		LiquidParticleStore particles = support.state().particles();
		ChemicalParcelStore chemistry = support.chemistry();
		HashMap<Integer, PhaseMarkerAllocation> allocations = new HashMap<>();
		for (var allocation : removal.allocations()) allocations.put(allocation.marker(), allocation);
		java.util.TreeMap<Integer, ThermalStateFunction.InventoryPortion> finalOrdinals = new java.util.TreeMap<>();
		for (int marker = support.state().markerGrid().cellMarkerHead[cell]; marker >= 0;
				marker = support.state().markerGrid().particleCellNext[marker]) {
			var allocation = allocations.get(marker);
			if (allocation != null && allocation.fullRemoval()) continue;
			finalOrdinals.put(marker, liquidInventoryPortion(particles.materialHandle(marker),
					support.state().unitScale().kilograms(allocation == null ? particles.mass(marker) : allocation.newMass()),
					chemistry == null ? null : allocation == null ? chemistry.record(marker) : allocation.chemicalPlan().kept()));
		}
		int count = particles.count();
		for (var allocation : removal.allocations()) if (allocation.fullRemoval()) {
			int last = --count;
			finalOrdinals.remove(allocation.marker());
			if (allocation.marker() != last) {
				var swapped = finalOrdinals.remove(last);
				if (swapped != null) finalOrdinals.put(allocation.marker(), swapped);
			}
		}
		return List.copyOf(finalOrdinals.values());
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
			long batches, long removedSvq, PhaseRemovalPlan removal, AirNetwork.PhaseMutationPlan gasPlan,
			ThermalDomainState.PhaseCellEnergyPlan thermalPlan, double candidateGasEnergy, GasTopologyCoordinator.GasTopologyPlan topology
	) { }

	private record GasBoilInventoryPlan(long batches, long removedSvq, PhaseRemovalPlan removal,
	                                    AirNetwork.PhaseMutationPlan gasPlan, ThermalDomainState.PhaseCellEnergyPlan thermalPlan,
	                                    double candidateGasEnergy, PhaseLiquidGeometryEdit liquidGeometry) { }

	private record GasCondensePlan(
			long batches, AirNetwork.PhaseMutationPlan gasPlan, ThermalDomainState.PhaseCellEnergyPlan thermalPlan,
			double candidateGasEnergy, int target,
			ChemicalMixtureRecord condensedMixture, float targetVolume, double targetMass,
			CarrierMassMomentum.Plan momentum, int slot, long addedSvq, double movedMass,
			GasTopologyCoordinator.GasTopologyPlan topology
	) { }

}

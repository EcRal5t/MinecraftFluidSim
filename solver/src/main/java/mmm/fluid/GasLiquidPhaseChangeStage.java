package mmm.fluid;

import mmm.chemistry.ChemicalMixtureRecord;
import mmm.chemistry.ChemicalParcelStore;
import mmm.fluid.air.AirNetwork;
import mmm.fluid.air.GasComponentAmount;
import mmm.fluid.air.GasInventory;
import mmm.granular.SolidVolume;
import mmm.thermal.GasLiquidPhaseModel;
import mmm.thermal.GasThermalModel;
import mmm.thermal.ThermalDomainState;
import mmm.thermal.ThermalLimits;
import mmm.thermal.ThermalMaterialDefinition;
import mmm.thermal.ThermalPhaseTransition;
import mmm.thermal.ThermalStateFunction;


import static mmm.fluid.PhaseChangeStageSupport.PhaseCandidateUnavailableException;
import static mmm.fluid.PhaseChangeStageSupport.PhaseCellInventory;
import static mmm.fluid.PhaseChangeStageSupport.PhaseComposition;
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
		support.ensureInventoryGrid();
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
				if (support.state().pendingPhaseVolume().isPending(cell)) continue;
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
								cell, node, liquidHandle, transition, inventory,
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
			int cell, int node, int liquidHandle,
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
		var liquidSource = new PhaseLiquidInventory(support, cell);
		var geometrySource = support.prepareGasGeometrySource(cell);
		double sourceGasEnergy = support.thermal().gasNodeEnergy(node, network);
		GasBoilTrial selected = GasPhaseBatchSearch.equilibrium(high, batches -> {
			GasBoilTrial trial;
			try {
				trial = prepareBoilTrial(cell, node, liquidHandle, transition, phase, thermalCell,
						volatileSpecies, liquidTemperature, batches, contact, liquidSource, sourceGasEnergy);
			} catch (ThermalStateFunction.EnergyOutsideValidityException insufficientLiquidEnergy) {
				// Removing latent heat has crossed the positive-temperature end of this source curve.
				return new GasPhaseBatchSearch.Sample<>(Double.NEGATIVE_INFINITY, null);
			}
			if (trial == null) return null;
			double residual = saturationResidual(node, transition, phase, trial.thermalTrial(),
					network.inventory(node).merge(trial.batch()), trial.candidateGasEnergy(), trial.removedSvq(), true);
			return new GasPhaseBatchSearch.Sample<>(residual, residual >= 0 ? trial : null);
		});
		return completeBoil(node, contact, geometrySource, selected);
	}

	private GasBoilTrial prepareBoilTrial(
			int cell, int node, int liquidHandle, ThermalPhaseTransition transition,
			GasLiquidPhaseModel phase, ThermalDomainState.PhaseCandidateCell thermalCell,
			boolean[] volatileSpecies, double liquidTemperature, long batches, GasPhaseInterfaceResolver.Candidate contact,
			PhaseLiquidInventory liquidSource, double sourceGasEnergy
	) {
		try {
			ThermalDomainState thermal = support.thermal();
			ChemicalParcelStore chemistry = support.chemistry();
			GasDomainState gasState = support.gasState();
			long removedSvq = Math.multiplyExact(batches, transition.lowerVolumeSvqPerBatch());
			double removedMass = transition.massMuPerBatch().multiply(batches).toDouble();
			var removal = liquidSource.remove(liquidHandle, contact.supportMask(), removedSvq, removedMass, volatileSpecies, true);
			GasInventory batch = chemistry == null ? phase.gasBatch(transition, batches)
					: phase.gasBatch(transition, batches, removal.plan().movedComposition().amounts(),
							removal.plan().movedComposition().massResidual());
			GasInventory candidateGas;
			try { candidateGas = gasState.network.inventory(node).merge(batch); }
			catch (GasInventory.RepresentationException capacity) {
				throw new PhaseCandidateUnavailableException("Boiling gas inventory exceeds its carrier representation", capacity);
			}
			support.representedGasMassTransfer(node, candidateGas, batch.components().getFirst().componentId(), removedMass);
			double candidateGasEnergy;
			ThermalDomainState.PhaseCellTrial thermalTrial;
			if (removal.inventory().isEmpty() && thermalCell.unchangedSolids().isEmpty()) {
				var vaporized = thermal.trialVaporizedCellEnergy(thermalCell, sourceGasEnergy);
				candidateGasEnergy = vaporized.gasEnergyAfter(); thermalTrial = vaporized.thermalTrial();
			} else {
				candidateGasEnergy = sourceGasEnergy + phase.internalEnergyAtTemperature(transition, batch, liquidTemperature);
				if (!Double.isFinite(candidateGasEnergy)) throw new PhaseCandidateUnavailableException(
						"Boiling gas energy exceeds its finite representation");
				thermalTrial = thermal.trialPhaseCellEnergy(thermalCell, removal.inventory(), sourceGasEnergy, candidateGasEnergy);
			}
			return new GasBoilTrial(batches, removedSvq, removal, batch, thermalTrial, candidateGasEnergy);
		} catch (ThermalStateFunction.EnergyRepresentationException
				| ThermalDomainState.EmptyPhaseCandidateEnergyException
				| AirNetwork.PhaseCapacityExceededException
				| ThermalDomainState.PhaseCellEnergyUnavailableException
				| PhaseCandidateUnavailableException unavailable) {
			return null;
		}
	}

	private GasBoilPlan completeBoil(int node,
	                                GasPhaseInterfaceResolver.Candidate contact, GasRuntimeGeometry.PhaseSource source,
	                                GasBoilTrial candidate) {
		if (candidate == null) return null;
		try {
			if (source != null && !source.transition(candidate.liquidGeometry()).hasContact(contact.contactPieces())) return null;
			var gasPlan = support.gasState().network.planPhaseSource(node, candidate.batch());
			var thermalPlan = support.thermal().planPhaseCellEnergy(candidate.thermalTrial());
			var topology = support.planGasGeometry(candidate.liquidGeometry(), node, gasPlan,
					candidate.candidateGasEnergy(), thermalPlan, source);
			return new GasBoilPlan(candidate.batches(), candidate.removedSvq(), candidate.removal(), gasPlan,
					thermalPlan, candidate.candidateGasEnergy(), topology);
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
		var liquidSource = new PhaseLiquidInventory(support, cell);
		var geometrySource = support.prepareGasGeometrySource(cell);
		double sourceGasEnergy = support.thermal().gasNodeEnergy(node, gasState.network);
		double sourceGasTemperature = support.thermal().gasTemperature(node, gasState.network);
		GasCondenseTrial selected = GasPhaseBatchSearch.equilibrium(high, batches -> {
			var trial = prepareCondenseTrial(cell, node, liquidHandle, transition, phase, thermalCell, batches,
					contact, liquidSource, sourceGasEnergy, sourceGasTemperature);
			if (trial == null) return null;
			var remaining = gasState.network.planPhaseSink(node, trial.removedGas()).inventoryAfter();
			double residual = saturationResidual(node, transition, phase, trial.thermalTrial(), remaining,
					trial.candidateGasEnergy(), -trial.addedSvq(), false);
			return new GasPhaseBatchSearch.Sample<>(residual, residual >= 0 ? trial : null);
		});
		return completeCondense(node, geometrySource, selected);
	}

	/** Phase equilibrium uses the current contact's control volume; topology is remapped once afterwards. */
	private double saturationResidual(int node, ThermalPhaseTransition transition, GasLiquidPhaseModel phase,
	                                  ThermalDomainState.PhaseCellTrial liquid, GasInventory gas, double energy,
	                                  long gasVolumeDeltaSvq, boolean boiling) {
		if (!boiling && gas.isEmpty()) return Double.POSITIVE_INFINITY;
		double h = support.state().cellSize();
		double volume = support.gasState().network.availableVolume(node)
				+ (double) gasVolumeDeltaSvq / SolidVolume.SVQ_PER_BV * h * h * h;
		if (volume <= 0) return Double.NEGATIVE_INFINITY;
		var thermal = new GasThermalModel(support.thermal().registry(), support.gasState().network.registry());
		double temperature = liquid.temperatureK();
		if (Double.isNaN(temperature)) temperature = thermal.state(gas, energy).temperatureK();
		double pressure = thermal.absolutePressure(gas, volume, energy);
		double residual = phase.saturation(transition, temperature, gas, pressure).pressureImbalancePa();
		return boiling ? residual : -residual;
	}

	private GasCondenseTrial prepareCondenseTrial(
			int cell, int node, int liquidHandle, ThermalPhaseTransition transition,
			GasLiquidPhaseModel phase,
			ThermalDomainState.PhaseCandidateCell thermalCell, long batches, GasPhaseInterfaceResolver.Candidate contact,
			PhaseLiquidInventory liquidSource, double sourceGasEnergy, double sourceGasTemperature
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
			} catch (GasInventory.RepresentationException unrepresentableColumns) {
				throw new PhaseCandidateUnavailableException(
						"Condensation has no complete representable gas column quantum", unrepresentableColumns);
			}
			GasComponentAmount moved = take.first().components().getFirst();
			support.representedGasMassTransfer(node, take.second(), componentId, -requestedMass);
			if (!FluidDomain.massClose(moved.mass(), requestedMass)) {
				throw new FluidDomain.ThermalPhaseBlockedException(
						"Condensation gas batch mass differs from its exact transition");
			}
			long addedSvq = Math.multiplyExact(batches, transition.lowerVolumeSvqPerBatch());
			double candidateGasEnergy = take.second().isEmpty() ? 0.0
					: sourceGasEnergy - phase.internalEnergyAtTemperature(transition, take.first(), sourceGasTemperature);
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
			int slot = -1;
			if (target >= 0) {
				targetVolume = PhaseChangeStageSupport.adjustedMarkerVolume(
						particles.volume(target), addedSvq, "Condensation");
				targetMass = particles.mass(target) + support.state().unitScale().solverMass(moved.mass());
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
			var thermalTrial = thermal.trialPhaseCellEnergy(thermalCell,
					liquidSource.afterCondensation(liquidHandle, target, targetMass, condensedMixture, moved.mass()),
					sourceGasEnergy, candidateGasEnergy);
			var liquidGeometry = liquidSource.geometryAfterCondensation(target, targetVolume,
					SolidVolume.toCellVolume(addedSvq), target < 0 ? support.markerSlotPosition(cell, slot) : null);
			return new GasCondenseTrial(batches, take.first(), thermalTrial, candidateGasEnergy, target, condensedMixture,
					targetVolume, targetMass, slot, addedSvq, moved.mass(), liquidGeometry);
		} catch (ThermalStateFunction.EnergyOutsideValidityException
				| ThermalStateFunction.EnergyRepresentationException
				| AirNetwork.PhaseCapacityExceededException
				| ThermalDomainState.PhaseCellEnergyUnavailableException
				| PhaseCandidateUnavailableException unavailable) {
			return null;
		}
	}

	private GasCondensePlan completeCondense(int node, GasRuntimeGeometry.PhaseSource source,
	                                        GasCondenseTrial candidate) {
		if (candidate == null) return null;
		try {
			var gasPlan = support.gasState().network.planPhaseSink(node, candidate.removedGas());
			var thermalPlan = support.thermal().planPhaseCellEnergy(candidate.thermalTrial());
			var topology = support.planGasGeometry(candidate.liquidGeometry(), node, gasPlan,
					candidate.candidateGasEnergy(), thermalPlan, source);
			CarrierMassMomentum.Plan momentum = null;
			if (candidate.target() >= 0) {
				var particles = support.state().particles();
				try {
					momentum = CarrierMassMomentum.plan(particles, new int[]{candidate.target()}, new double[]{candidate.targetMass()},
							candidate.targetMass() - particles.mass(candidate.target()), 0, 0, 0);
				} catch (CarrierMassMomentum.RepresentationException unrepresentableMomentum) {
					throw new PhaseCandidateUnavailableException(
							"Condensation momentum exceeds liquid carrier representation", unrepresentableMomentum);
				}
			}
			return new GasCondensePlan(candidate.batches(), gasPlan, thermalPlan, candidate.candidateGasEnergy(),
					candidate.target(), candidate.condensedMixture(), candidate.targetVolume(), candidate.targetMass(), momentum,
					candidate.slot(), candidate.addedSvq(), candidate.movedMass(), topology);
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
		double mass = 0.0, freezableMass = 0.0;
		long svq = 0L, freezableSvq = 0L;
		boolean found = false;
		for (int marker = support.state().markerGrid().cellMarkerHead[cell]; marker >= 0;
				marker = support.state().markerGrid().particleCellNext[marker]) {
			if (particles.materialHandle(marker) != liquidHandle || !markerInSupport(cell, marker, supportMask)) continue;
			svq = Math.addExact(svq, SolidVolume.cellVolumeSvqAtLeast(particles.volume(marker)));
			mass += support.state().unitScale().kilograms(particles.mass(marker));
			found = true;
			if (chemistry == null || !chemistry.indicator(marker)) {
				freezableSvq = Math.addExact(
						freezableSvq, SolidVolume.cellVolumeSvqAtMost(particles.volume(marker)));
				freezableMass += support.state().unitScale().kilograms(particles.mass(marker));
			}
		}
		return found ? new PhaseCellInventory(
				0L, svq, 0.0, mass, freezableSvq, freezableMass) : null;
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

	private record GasBoilPlan(
			long batches, long removedSvq, PhaseRemovalPlan removal, AirNetwork.PhaseMutationPlan gasPlan,
			ThermalDomainState.PhaseCellEnergyPlan thermalPlan, double candidateGasEnergy, GasTopologyCoordinator.GasTopologyPlan topology
	) { }

	private record GasBoilTrial(long batches, long removedSvq, PhaseLiquidInventory.Removal liquid,
	                            GasInventory batch, ThermalDomainState.PhaseCellTrial thermalTrial,
	                            double candidateGasEnergy) {
		PhaseRemovalPlan removal() { return liquid.plan(); }
		PhaseLiquidGeometryEdit liquidGeometry() { return liquid.geometry(); }
	}

	private record GasCondenseTrial(long batches, GasInventory removedGas, ThermalDomainState.PhaseCellTrial thermalTrial,
	                                double candidateGasEnergy, int target, ChemicalMixtureRecord condensedMixture,
	                                float targetVolume, double targetMass, int slot, long addedSvq, double movedMass,
	                                PhaseLiquidGeometryEdit liquidGeometry) { }

	private record GasCondensePlan(
			long batches, AirNetwork.PhaseMutationPlan gasPlan, ThermalDomainState.PhaseCellEnergyPlan thermalPlan,
			double candidateGasEnergy, int target,
			ChemicalMixtureRecord condensedMixture, float targetVolume, double targetMass,
			CarrierMassMomentum.Plan momentum, int slot, long addedSvq, double movedMass,
			GasTopologyCoordinator.GasTopologyPlan topology
	) { }

}

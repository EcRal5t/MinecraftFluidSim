package mmm.fluid;

import mmm.chemistry.RegionChemistry;
import mmm.granular.GranularCouplingFields;
import mmm.granular.GranularDepositDynamics;
import java.util.List;
import mmm.units.SignedDyadic;
import mmm.granular.GranularInventory;
import mmm.granular.GranularParcelStore;
import mmm.granular.SolidVolume;
import mmm.thermal.GranularEnergyColumn;
import mmm.thermal.ThermalLimits;
import mmm.thermal.ThermalTransport;
import mmm.world.MovingBodyFluidCoupling;
import static mmm.fluid.FluidDomain.*;

/** Orders complete logical steps and supplies granular transitions, body inputs and energy observations. */
final class DomainStep {
	private final FluidDomain domain;
	DomainStep(FluidDomain domain) { this.domain = domain; }
	public StepStats step() { return step(false, 0); }
	public StepStats step(boolean phaseChangeEnabled, int maximumPhaseOperations) {
		return step(phaseChangeEnabled, maximumPhaseOperations, false, true);
	}
	public StepStats stepMechanicsOnly() { return step(false, 0, false, false); }
	public StepStats stepWithMechanicalAudit(boolean phaseChangeEnabled, int maximumPhaseOperations) {
		return step(phaseChangeEnabled, maximumPhaseOperations, true, true);
	}
	StepStats step(
			boolean phaseChangeEnabled, int maximumPhaseOperations, boolean captureMechanicalAudit,
			boolean thermochemistryEnabled
	) {
		return step(phaseChangeEnabled, maximumPhaseOperations, captureMechanicalAudit, thermochemistryEnabled, 0);
	}
	StepStats step(
			boolean phaseChangeEnabled, int maximumPhaseOperations, boolean captureMechanicalAudit,
			boolean thermochemistryEnabled, int preparedSubsteps
	) {
		if (preparedSubsteps < 0) throw new IllegalArgumentException("Prepared mechanical substeps are invalid");
		if (phaseChangeEnabled && (maximumPhaseOperations <= 0
				|| maximumPhaseOperations > ThermalLimits.MAX_PHASE_OPERATIONS_PER_STEP)) {
			throw new IllegalArgumentException("Production phase operation budget is invalid");
		}
		domain.mechanicalEnergy.beginStep(captureMechanicalAudit);
		float before = domain.totalVolume();
		double massBefore = domain.totalMass();
		MechanicalAuditAccumulator mechanicalAudit = captureMechanicalAudit
				? new MechanicalAuditAccumulator(mechanicalCarrierKineticEnergyJoules(), mechanicalPotentialEnergyJoules())
				: null;
		SignedDyadic thermalMechanicalWorkBefore = !captureMechanicalAudit || domain.thermalState == null ? SignedDyadic.ZERO
				: domain.thermalState.auditLedger().mechanicalWork();
		domain.lastLiquidBoundaryImpulseX = domain.lastLiquidBoundaryImpulseY = domain.lastLiquidBoundaryImpulseZ = 0;
		domain.lastGranularBoundaryImpulseX = 0.0;
		domain.lastGranularBoundaryImpulseY = 0.0;
		domain.lastGranularBoundaryImpulseZ = 0.0;
		int substeps = preparedSubsteps > 0 ? preparedSubsteps : calculateSubsteps(domain.config.timeStep());
		float dt = domain.config.timeStep() / substeps;
		int granularParcelCountBeforeAdvection = domain.precipitationEnabled ? captureReactiveGranularCells() : 0;
		int pressureIterations = 0;
		ViscousStepStats viscosity = ViscousStepStats.INACTIVE;

		for (int substep = 0; substep < substeps; substep++) {
			MechanicalStepPipeline.SubstepOutcome outcome = domain.mechanicalPipeline.substep(
					dt, substep, captureMechanicalAudit, mechanicalAudit);
			pressureIterations += outcome.pressureIterations();
			viscosity = viscosity.plus(outcome.viscosity());

			if (outcome.granularMechanics() != null) {
				domain.lastGranularBoundaryImpulseX += outcome.granularMechanics().boundaryImpulseX();
				domain.lastGranularBoundaryImpulseY += outcome.granularMechanics().boundaryImpulseY();
				domain.lastGranularBoundaryImpulseZ += outcome.granularMechanics().boundaryImpulseZ();
			}
		}
		double carrierTransferInitialKineticEnergy = captureMechanicalAudit ? mechanicalCarrierKineticEnergyJoules() : 0.0;
		if (domain.precipitationEnabled) markMovedReactiveGranularCells(granularParcelCountBeforeAdvection);
		ThermalAxisStats thermalAxis = thermochemistryEnabled
				? runThermalReactionPhaseAxis(phaseChangeEnabled, maximumPhaseOperations)
				: inactiveThermalAxisStats();
		if (!thermochemistryEnabled) {
			domain.lastChemicalReactionStats = thermalAxis.chemistry();
			domain.lastPhaseTransferStats = thermalAxis.granularPhase();
			domain.lastThermalPhaseStats = thermalAxis.condensedPhase();
			domain.lastGasThermalPhaseStats = thermalAxis.gasPhase();
		}
		RegionChemistry.ReactionStats chemistry = thermalAxis.chemistry();
		GranularTransitionStage.Outcome granularTransition = domain.granularTransitionStage.step();
		domain.lastGranularDepositStats = granularTransition.depositStats();

		double finalParticleKineticEnergy = captureMechanicalAudit ? mechanicalCarrierKineticEnergyJoules() : 0;
		if (captureMechanicalAudit) mechanicalAudit.carrierInventoryTransferJoules +=
				finalParticleKineticEnergy - carrierTransferInitialKineticEnergy;
		return new StepStats(
				substeps, pressureIterations, (float)domain.pressureSystem.lastResult().residualRms(), before, domain.totalVolume(), massBefore, domain.totalMass(),
				chemistry.visitedCells(), chemistry.evaluations(), chemistry.changedStocks(),
				thermalAxis.condensedPhase(), thermalAxis.gasPhase(),
				captureMechanicalAudit ? mechanicalAudit.finish(
						finalParticleKineticEnergy, mechanicalPotentialEnergyJoules(),
						(domain.thermalState == null ? SignedDyadic.ZERO : domain.thermalState.auditLedger().mechanicalWork())
								.subtract(thermalMechanicalWorkBefore)) : MechanicalStepAudit.notCaptured(),
				thermalAxis, domain.lastGranularDepositStats, domain.carrierPropertiesWorkspace.finishStep(), viscosity, domain.mechanicalEnergy.correction()
		);
	}
	private static ThermalAxisStats inactiveThermalAxisStats() {
		return new ThermalAxisStats(
				new ThermalTransport.Stats(0, 0, 0.0, SignedDyadic.ZERO, SignedDyadic.ZERO),
				new RegionChemistry.ReactionStats(0, 0, 0),
				new PhaseTransferStats(0, 0, 0, 0L),
				new FluidDomain.ThermalPhaseStats(0, 0, 0, 0L),
				new FluidDomain.GasThermalPhaseStats(0, 0, 0));
	}
	double mechanicalCarrierKineticEnergyJoules() {
		return checkedMechanicalEnergy(particleKineticEnergyJoules() + granularKineticEnergyJoules());
	}
	double mechanicalGridKineticEnergyJoules() {
		return checkedMechanicalEnergy(gridKineticEnergyJoules() + granularKineticEnergyJoules());
	}
	double granularKineticEnergyJoules() {
		double energy = 0;
		if (domain.granularState != null) for (int index = 0; index < domain.granularState.parcels.count(); index++) {
			var p = domain.granularState.parcels.parcel(index);
			energy += .5 * p.mass() * (p.velocityX() * (double) p.velocityX()
					+ p.velocityY() * (double) p.velocityY() + p.velocityZ() * (double) p.velocityZ());
		}
		return checkedMechanicalEnergy(energy);
	}
	double mechanicalPotentialEnergyJoules() {
		double[] energy = {gravitationalPotentialEnergyJoules()};
        if(domain.thermalState!=null)for(var group:domain.thermalState.coherentPhases().snapshot())
            energy[0]+=mmm.units.Mass.kilograms(group.massUnits())*domain.config.gravity()*domain.config.cellSize()
                *(group.cell()/domain.sizeX%domain.sizeY+.5);
		if (domain.granularState != null) {
			for (int index = 0; index < domain.granularState.parcels.count(); index++) {
				var p = domain.granularState.parcels.parcel(index);
				energy[0] += p.mass() * domain.config.gravity() * p.y();
			}
			domain.granularState.deposits.forEachGroup((cell, porosity, material, composition, volume, mass) -> {
				double centre = domain.config.cellSize() * (cell / domain.sizeX % domain.sizeY
						+ .5 * domain.granularState.deposits.cellBulkVolumeSvq(cell) / SolidVolume.SVQ_PER_CELL);
				energy[0] += mmm.units.Mass.kilograms(mass) * domain.config.gravity() * centre;
			});
		}
		return checkedMechanicalEnergy(energy[0]);
	}
	private static double checkedMechanicalEnergy(double energy) {
		if (!(Double.isFinite(energy) && energy >= 0)) throw new ArithmeticException("Mechanical carrier energy is invalid");
		return energy;
	}
	double particleKineticEnergyJoules() { domain.domainFlowFields.ensureVelocityGrid(); return gridKineticEnergyJoules(); }
	double gravitationalPotentialEnergyJoules() {
		double energy = 0;
		for (var stock : domain.liquidCells.physicalStocks()) {
			double y = domain.config.cellSize() * (stock.key().cell() / domain.sizeX % domain.sizeY + .5);
			energy += mmm.units.Mass.kilograms(stock.massUnits()) * domain.config.gravity() * y;
		}
		return checkedMechanicalEnergy(energy);
	}
	double gridKineticEnergyJoules() {
		double densityScale = 1.0;
		double energy = faceKineticEnergyJoules(domain.mac.velocityX, domain.mac.faceMassX, densityScale)
				+ faceKineticEnergyJoules(domain.mac.velocityY, domain.mac.faceMassY, densityScale)
				+ faceKineticEnergyJoules(domain.mac.velocityZ, domain.mac.faceMassZ, densityScale);
		if (!(Double.isFinite(energy) && energy >= 0.0)) {
			throw new ArithmeticException("Grid kinetic-energy audit is invalid");
		}
		return energy;
	}
	private double faceKineticEnergyJoules(float[] velocity, double[] weight, double densityScale) {
		double energy = 0;
		for (int face = 0; face < velocity.length; face++) {
			double kilograms = domain.unitScale.kilograms(weight[face] * densityScale);
			energy += 0.5 * kilograms * velocity[face] * (double) velocity[face];
		}
		return energy;
	}

	void rebuildInventoryFields() { domain.rebuildDerivedFromLiquidCells(); }
	public ThermalAxisStats stepThermalAxis(boolean phaseChangeEnabled, int maximumPhaseOperations) {
		if (domain.thermalState == null) throw new IllegalStateException("Thermal state is not bound");
		if (phaseChangeEnabled && (maximumPhaseOperations <= 0
				|| maximumPhaseOperations > ThermalLimits.MAX_PHASE_OPERATIONS_PER_STEP)) {
			throw new IllegalArgumentException("Production phase operation budget is invalid");
		}
		ThermalAxisStats result = runThermalReactionPhaseAxis(phaseChangeEnabled, maximumPhaseOperations);
		domain.gridDirty = true;
		domain.domainFlowFields.ensureInventoryGrid();
		return result;
	}
	ThermalAxisStats runThermalReactionPhaseAxis(boolean phaseChangeEnabled, int maximumPhaseOperations) {
		ThermalAxisStats result = domain.thermochemistryPipeline.step(
				domain.thermalState != null, domain.precipitationEnabled, domain.config.timeStep(),
				phaseChangeEnabled, maximumPhaseOperations);
		domain.lastChemicalReactionStats = result.chemistry();
		domain.lastPhaseTransferStats = result.granularPhase();
		domain.lastThermalPhaseStats = result.condensedPhase();
		domain.lastGasThermalPhaseStats = result.gasPhase();
		return result;
	}
	public void synchronizeGasTopologyForPersistence() {
		domain.gasTopologyCoordinator.synchronizeForPersistence();
	}
	float gasPressureOfAirCell(int airCell) {
		if (!domain.gasState.pressureActive) return 0.0F;
		int node = gasNodeOfAirCell(airCell);
		if (node <= 0) return 0.0F;
		return gasPressureOfNode(node);
	}
	int gasNodeOfAirCell(int cell) { return domain.gasState.frame == null ? -1 : domain.gasState.frame.commonCellNode(cell); }
	int rawGasNodeOfCell(int cell) { return gasNodeOfAirCell(cell); }
	float gasPressureOfNode(int node) {
		return (float) domain.gasBoundary.nodeGaugePressurePa(node);
	}
	int particleCellIndex(int particle) {
		return domain.liquidCells.physicalStockAt(particle).key().cell();
	}
	int particleCellIndex(float positionX, float positionY, float positionZ) {
		float h = domain.config.cellSize();
		int x = Math.min(domain.sizeX - 1, Math.max(0, (int) Math.floor(positionX / h)));
		int y = Math.min(domain.sizeY - 1, Math.max(0, (int) Math.floor(positionY / h)));
		int z = Math.min(domain.sizeZ - 1, Math.max(0, (int) Math.floor(positionZ / h)));
		return domain.domainFlowFields.cellIndex(x, y, z);
	}
	long granularGeometricOpenSvq(int cell) {
		return SolidVolume.fromCellVolumeExact(domain.geometry.cutCells.openCellVolume(cell));
	}
	GranularDepositDynamics granularDepositDynamics() { return domain.granularDepositDynamics; }
	GranularDepositDynamics.Environment granularDepositEnvironment(long[] plannedSolid) {
		return granularDepositEnvironment(plannedSolid, GranularBedShear.prepareFlow(domain, null));
	}
	GranularDepositDynamics.Environment granularDepositEnvironment(long[] plannedSolid, GranularBedShear.Flow flow) {
		return new GranularDepositDynamics.Environment() {
			private GranularBedShear.LiftWork liftWork;
			private SignedDyadic wakeRounding=SignedDyadic.ZERO;
			private GranularInventory.EnergyPlan relaxationEnergy;
			@Override public boolean planWakeWork(int cell, SignedDyadic joules) {
				liftWork = flow.planLift(cell, joules);
				return liftWork != null;
			}
			@Override public void requireWakeWorkCurrent(){liftWork.requireCurrent(domain);}
			@Override public SignedDyadic wakeWorkRounding(){return wakeRounding;}
			@Override public void commitWakeWork() {
				var impulse = liftWork.apply(domain);
				wakeRounding=liftWork.mechanicalRounding();
				receiveContactImpulse(impulse[0], impulse[1], impulse[2]);
				liftWork = null;
			}
			@Override public double gravity() { return domain.config.gravity(); }
			@Override public long geometricOpenSvq(int cell) {
				return SolidVolume.fromCellVolumeExact(domain.geometry.cutCells.openCellVolume(cell));
			}

			@Override public boolean horizontalPathOpen(int fromCell, int directionX, int directionZ) {
				if (directionX == 0 && directionZ == 0 || Math.abs(directionX) > 1 || Math.abs(directionZ) > 1) {
					throw new IllegalArgumentException("Granular slope direction is invalid");
				}
				int x = fromCell % domain.sizeX, yz = fromCell / domain.sizeX, y = yz % domain.sizeY, z = yz / domain.sizeY;
				int targetX = x + directionX, targetZ = z + directionZ;
				if (!domain.domainFlowFields.inside(targetX, y, targetZ)) return false;
				if (directionX == 0 || directionZ == 0) {
					return domain.domainFlowFields.openFaceFromCell(fromCell, directionX, 0, directionZ) > 0.0F;
				}
				boolean xThenZ = domain.domainFlowFields.openFaceFromCell(fromCell, directionX, 0, 0) > 0.0F;
				int afterX = domain.domainFlowFields.cellIndex(targetX, y, z);
				xThenZ &= domain.domainFlowFields.openFaceFromCell(afterX, 0, 0, directionZ) > 0.0F;
				boolean zThenX = domain.domainFlowFields.openFaceFromCell(fromCell, 0, 0, directionZ) > 0.0F;
				int afterZ = domain.domainFlowFields.cellIndex(x, y, targetZ);
				zThenX &= domain.domainFlowFields.openFaceFromCell(afterZ, directionX, 0, 0) > 0.0F;
				return xThenZ || zThenX;
			}

			@Override public double shields(int surfaceCell, mmm.granular.ParticulateMaterial material) {
				return flow.shields(surfaceCell, material);
			}

			@Override public boolean verticalPathOpen(float x, float fromY, float z, float toY, long solidSvq) {
				float h = domain.config.cellSize();
				int cx = Math.min(domain.sizeX - 1, Math.max(0, (int) Math.floor(x / h)));
				int cz = Math.min(domain.sizeZ - 1, Math.max(0, (int) Math.floor(z / h)));
				int from = Math.min(domain.sizeY - 1, Math.max(0, (int) Math.floor(fromY / h)));
				int to = Math.min(domain.sizeY - 1, Math.max(0, (int) Math.floor(toY / h)));
				for (int y = Math.min(from, to); y < Math.max(from, to); y++) {
					if (domain.domainFlowFields.openFaceFromCell(domain.domainFlowFields.cellIndex(cx, y, cz), 0, 1, 0) <= 0.0F) return false;
				}
				return !domain.granularCollision.verticalSpherePathTouchesSolid(x, fromY, z, toY, solidSvq);
			}

			@Override public int verticalPathWork(float x, float fromY, float z, float toY, long solidSvq) {
				return domain.granularCollision.verticalSpherePathWork(x, fromY, z, toY, solidSvq);
			}

			@Override public boolean bedGeometryFits(int cell, long bulk) { return domain.granularCollision.bedGeometryFits(cell, bulk); }

			@Override public boolean horizontalTransferPathOpen(float x, float y, float z, float toX, float toZ, long solidSvq) {
				return !domain.granularCollision.spherePathTouchesSolid(x, y, z, toX, y, toZ, solidSvq);
			}

			@Override public int horizontalTransferPathWork(float x, float y, float z, float toX, float toZ, long solidSvq) {
				return domain.granularCollision.spherePathWork(x, y, z, toX, y, toZ, solidSvq);
			}

			@Override public void receiveContactImpulse(double x, double y, double z) {
				domain.lastGranularBoundaryImpulseX += x;
				domain.lastGranularBoundaryImpulseY += y;
				domain.lastGranularBoundaryImpulseZ += z;
			}

			@Override public boolean planSettlement(GranularInventory.SettlementPlan plan) {
				long[] candidate = plannedSolid.clone();
				for (var parcel : plan.parcels()) domain.granularCoupling.removePlannedParcel(candidate,
						parcelPosition(parcel), parcel.solidVolumeSvq());
				for (var target : plan.targets()) if (!domain.granularCoupling.tryPlanCell(candidate, target.cell(), target.addedSolidVolumeSvq(),
						(domain).domainStep::granularGeometricOpenSvq)) return false;
				System.arraycopy(candidate, 0, plannedSolid, 0, candidate.length);
				return true;
			}

			@Override public void cancelSettlement(GranularInventory.SettlementPlan plan) {
				for (var target : plan.targets()) domain.granularCoupling.removePlannedCell(plannedSolid, target.cell(), target.addedSolidVolumeSvq());
				for (var parcel : plan.parcels()) restorePlannedParcel(plannedSolid, parcelPosition(parcel),
						parcel.solidVolumeSvq(), "joint settlement rollback");
			}

			@Override public boolean planRelaxation(int fromCell, int toCell, long solidSvq) {
				domain.granularCoupling.removePlannedCell(plannedSolid, fromCell, solidSvq);
				if (domain.granularCoupling.tryPlanCell(plannedSolid, toCell, solidSvq,
						(domain).domainStep::granularGeometricOpenSvq)) return true;
				restorePlannedCell(plannedSolid, fromCell, solidSvq, "relaxation");
				return false;
			}

			@Override public void cancelRelaxation(int fromCell, int toCell, long solidSvq) {
				relaxationEnergy = null;
				domain.granularCoupling.removePlannedCell(plannedSolid, toCell, solidSvq);
				restorePlannedCell(plannedSolid, fromCell, solidSvq, "relaxation rollback");
			}

			@Override public void requireThermalRelaxation(
					int fromCell, int toCell, int material, int composition, long movedMassUnits, long sourceMassUnits, SignedDyadic heat
			) {
				if (domain.thermalState == null) return;
				relaxationEnergy = domain.thermalState.planDepositMove(
						new GranularEnergyColumn.DepositKey(fromCell, material, composition),
						new GranularEnergyColumn.DepositKey(toCell, material, composition), movedMassUnits, sourceMassUnits, heat);
				relaxationEnergy.requireCurrent();
			}

			@Override public void commitThermalRelaxation(
					int fromCell, int toCell, int material, int composition, long movedMassUnits, long sourceMassUnits, SignedDyadic heat
			) {
				if (domain.thermalState == null) return;
				if (relaxationEnergy == null) throw new IllegalStateException("Missing granular relaxation energy plan");
				relaxationEnergy.commit();
				relaxationEnergy = null;
			}

			@Override public int wakeGeometryWork() {
				return domain.cellCount + 2 * domain.granularState.parcels.count() + 27;
			}
			@Override public boolean planWake(int cell, long solidSvq, float x, float y, float z) {
				if (!domain.granularCollision.positionValid(x, y, z, solidSvq)) return false;
				var admission = new GranularSpawnAdmission(domain.sizeX, domain.sizeY, domain.sizeZ, domain.config.cellSize(), domain.granularState.parcels, 0);
				if (!admission.allows(x, y, z, solidSvq)) return false;
				domain.granularCoupling.removePlannedCell(plannedSolid, cell, solidSvq);
				GranularCouplingFields.Position position = new GranularCouplingFields.Position(x, y, z);
				if (domain.granularCoupling.tryPlanParcel(plannedSolid, position, solidSvq,
						(domain).domainStep::granularGeometricOpenSvq)) return true;
				restorePlannedCell(plannedSolid, cell, solidSvq, "wake");
				return false;
			}

			@Override public void cancelWake(int cell, long solidSvq, float x, float y, float z) {
				domain.granularCoupling.removePlannedParcel(plannedSolid,
						new GranularCouplingFields.Position(x, y, z), solidSvq);
				restorePlannedCell(plannedSolid, cell, solidSvq, "wake rollback");
			}

			@Override public void onSolidChanged(int cell) {
				if (domain.precipitationEnabled) domain.chemistry.markDirtyCell(cell);
			}
		};
	}
	static GranularCouplingFields.Position parcelPosition(GranularParcelStore.Parcel parcel) {
		return new GranularCouplingFields.Position(parcel.x(), parcel.y(), parcel.z(), parcel.ceilMask());
	}
	void restorePlannedParcel(
			long[] plannedSolid, GranularCouplingFields.Position position, long solidSvq, String transition
	) {
		if (!domain.granularCoupling.tryPlanParcel(plannedSolid, position, solidSvq, domain.domainStep::granularGeometricOpenSvq)) {
			throw new IllegalStateException("Granular " + transition + " could not restore parcel occupancy");
		}
	}
	void restorePlannedCell(long[] plannedSolid, int cell, long solidSvq, String transition) {
		if (!domain.granularCoupling.tryPlanCell(plannedSolid, cell, solidSvq, domain.domainStep::granularGeometricOpenSvq)) {
			throw new IllegalStateException("Granular " + transition + " could not restore deposit occupancy");
		}
	}
	int captureReactiveGranularCells() {
		int count = domain.granularState.parcels.count();
		for (int parcel = 0; parcel < count; parcel++) {
			GranularParcelStore.Parcel state = domain.granularState.parcels.parcel(parcel);
			domain.granularReactivePreviousCells[parcel] = state.solidCompositionHandle() == 0
					? -1 : granularParcelCell(state);
		}
		return count;
	}
	void markMovedReactiveGranularCells(int expectedCount) {
		if (domain.granularState.parcels.count() != expectedCount) {
			throw new IllegalStateException("Granular advection changed the exact active parcel inventory");
		}
		for (int parcel = 0; parcel < expectedCount; parcel++) {
			int previous = domain.granularReactivePreviousCells[parcel];
			if (previous < 0) continue;
			int current = granularParcelCell(domain.granularState.parcels.parcel(parcel));
			if (current != previous) {
				domain.chemistry.markDirtyCell(previous);
				domain.chemistry.markDirtyCell(current);
			}
		}
	}
	static float trilinearWeight(int ox, int oy, int oz, float fx, float fy, float fz) {
		return (ox == 0 ? 1.0F - fx : fx) * (oy == 0 ? 1.0F - fy : fy) * (oz == 0 ? 1.0F - fz : fz);
	}

	int calculateSubsteps(float dt) {
		return Math.max(1, domain.minimumMechanicalSubsteps);
	}
	public MovingBodyFluidCoupling.Result applyMovingBodyCoupling(MovingBodyFluidCoupling.Request request) {
		if (request == null) throw new IllegalArgumentException("Moving-body request is missing");
		requireFiniteMarkerVelocity(request.velocityXMetresPerSecond());
		requireFiniteMarkerVelocity(request.velocityYMetresPerSecond());
		requireFiniteMarkerVelocity(request.velocityZMetresPerSecond());
		domain.domainFlowFields.preparePhysicalState();
		double h = domain.config.cellSize(), cellVolume = domain.unitScale.cubicMetersPerNormalizedVolume();
		double requested = request.displacedVolumeCubicMetres() / cellVolume;
		if (!(Double.isFinite(requested) && requested > 0)) throw new IllegalArgumentException("Body displacement exceeds solver scale");
		double[] weighted = new double[domain.cellCount]; double total = 0; int coupled = 0;
		for (int cell = 0; cell < domain.cellCount; cell++) {
			double weight = MovingBodyFluidCoupling.kernelWeight((cell % domain.sizeX + .5) * h - request.localXMetres(),
					(cell / domain.sizeX % domain.sizeY + .5) * h - request.localYMetres(),
					(cell / (domain.sizeX * domain.sizeY) + .5) * h - request.localZMetres(), h);
			weighted[cell] = weight * domain.liquidCells.exactCellVolume(cell).toDouble();
			if (weighted[cell] > 0) { total += weighted[cell]; coupled++; }
		}
		if (total == 0) return MovingBodyFluidCoupling.Result.NONE;
		double scale = Math.min(1, requested / total); double[] impulse = new double[3];
		double externalWork=0;
		double[][] momentum=new double[3][];
		domain.cellMacStage.beginMechanicalHeat();
		double[] target = {request.velocityXMetresPerSecond(), request.velocityYMetresPerSecond(), request.velocityZMetresPerSecond()};
		for (int axis = 0; axis < 3; axis++) {
			float[] velocity = axis == 0 ? domain.mac.velocityX : axis == 1 ? domain.mac.velocityY : domain.mac.velocityZ;
			double[] masses = axis == 0 ? domain.cellMacStage.massX : axis == 1 ? domain.cellMacStage.massY : domain.cellMacStage.massZ;
			momentum[axis]=new double[masses.length];
			for(int face=0;face<masses.length;face++)momentum[axis][face]=masses[face]*velocity[face];
			int nx = domain.sizeX + (axis == 0 ? 1 : 0), ny = domain.sizeY + (axis == 1 ? 1 : 0), nz = domain.sizeZ + (axis == 2 ? 1 : 0);
			for (int z = 0; z < nz; z++) for (int y = 0; y < ny; y++) for (int x = 0; x < nx; x++) {
				int coordinate = axis == 0 ? x : axis == 1 ? y : z, length = axis == 0 ? domain.sizeX : axis == 1 ? domain.sizeY : domain.sizeZ;
				if (coordinate == 0 || coordinate == length) continue;
				int right = domain.domainFlowFields.cellIndex(x, y, z), left = domain.domainFlowFields.cellIndex(x - (axis == 0 ? 1 : 0), y - (axis == 1 ? 1 : 0), z - (axis == 2 ? 1 : 0));
				int face = (z * ny + y) * nx + x;
				double volume = domain.liquidCells.exactCellVolume(left).toDouble() + domain.liquidCells.exactCellVolume(right).toDouble();
				if (volume == 0) continue;
				double blend = scale * (weighted[left] + weighted[right]) / volume;
				float old = velocity[face], next = (float) (old + blend * (target[axis] - old));
				double change=(double)next-old,faceImpulse=masses[face]*change;
				momentum[axis][face]=masses[face]*next;
				impulse[axis]+=faceImpulse;externalWork+=target[axis]*faceImpulse;
				// The prescribed body supplies V*I. Its dashpot turns the remaining
				// local work into heat; the factorization avoids subtracting large K.
				double heat=masses[face]*change*(target[axis]-old-.5*change);
				domain.cellMacStage.addMechanicalHeatAtFace(axis,face,heat);
			}
		}
		var result=new MovingBodyFluidCoupling.Result(coupled,scale*total*cellVolume,
				impulse[0],impulse[1],impulse[2],externalWork);
		domain.cellMacStage.publishMomentum(momentum);
		return result;
	}
	MovingBodyFluidCoupling.Result applyMovingBodyCouplings(java.util.List<MovingBodyFluidCoupling.Request> requests) {
		MovingBodyFluidCoupling.Result result = MovingBodyFluidCoupling.Result.NONE;
		for (var request : requests) result = result.plus(applyMovingBodyCoupling(request));
		return result;
	}
	static void requireFiniteMarkerVelocity(double velocity) {
		if (!Double.isFinite(velocity) || !Float.isFinite((float) velocity)) {
			throw new IllegalArgumentException("Moving-body coupling velocity exceeds marker representation");
		}
	}
	int granularParcelCell(GranularParcelStore.Parcel parcel) {
		int x = Math.min(domain.sizeX - 1, Math.max(0, (int) Math.floor(parcel.x() / domain.config.cellSize())));
		int y = Math.min(domain.sizeY - 1, Math.max(0, (int) Math.floor(parcel.y() / domain.config.cellSize())));
		int z = Math.min(domain.sizeZ - 1, Math.max(0, (int) Math.floor(parcel.z() / domain.config.cellSize())));
		return domain.domainFlowFields.cellIndex(x, y, z);
	}
	float granularMergeVelocityDelta() {
		return Math.max(domain.config.cellSize() * 0.5F, domain.config.gravity() * domain.config.timeStep() * 1.25F);
	}
	boolean granularOccupancyFitsGeometry(GranularCouplingFields coupling) {
		for (int cell = 0; cell < domain.cellCount; cell++) {
			if (coupling.solidSvq(cell) > SolidVolume.fromCellVolumeExact(domain.geometry.cutCells.openCellVolume(cell))) return false;
		}
		return true;
	}
	boolean planGranularMerge(
			long[] planned, GranularParcelStore.Parcel first, GranularParcelStore.Parcel second,
			GranularParcelStore.Parcel merged
	) {
		if (!domain.granularCollision.positionValid(merged.x(), merged.y(), merged.z(), merged.solidVolumeSvq())) return false;
		GranularCouplingFields.Position firstPosition = parcelPosition(first);
		GranularCouplingFields.Position secondPosition = parcelPosition(second);
		domain.granularCoupling.removePlannedParcel(planned, firstPosition, first.solidVolumeSvq());
		domain.granularCoupling.removePlannedParcel(planned, secondPosition, second.solidVolumeSvq());
		GranularCouplingFields.Position mergedPosition = parcelPosition(merged);
		if (domain.granularCoupling.tryPlanParcel(planned, mergedPosition, merged.solidVolumeSvq(),
				domain.domainStep::granularGeometricOpenSvq)) return true;
		if (!domain.granularCoupling.tryPlanParcel(planned, firstPosition, first.solidVolumeSvq(),
				domain.domainStep::granularGeometricOpenSvq)
				|| !domain.granularCoupling.tryPlanParcel(planned, secondPosition, second.solidVolumeSvq(),
				domain.domainStep::granularGeometricOpenSvq)) {
			throw new IllegalStateException("Rejected granular compaction could not restore exact occupancy");
		}
		return false;
	}
}

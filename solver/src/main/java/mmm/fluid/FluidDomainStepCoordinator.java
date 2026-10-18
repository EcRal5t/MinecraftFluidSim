package mmm.fluid;

import mmm.chemistry.ChemicalParcelStore;
import mmm.fluid.air.AirNetwork;
import mmm.granular.GranularCouplingFields;
import mmm.granular.GranularDepositDynamics;
import mmm.granular.GranularInventory;
import mmm.granular.GranularParcelStore;
import mmm.granular.SolidVolume;
import mmm.units.SignedDyadic;
import mmm.thermal.GranularEnergyColumn;
import mmm.thermal.ThermalLimits;
import mmm.thermal.ThermalTransport;
import mmm.world.MovingBodyFluidCoupling;

import java.util.Arrays;

/** Orders complete mechanical and thermochemical logical steps and their audits. */
abstract class FluidDomainStepCoordinator extends FluidDomainMechanicalFields {
	/** Per-attempt numerical workspace; restored requests and committed candidates start at one. */
	int minimumMechanicalSubsteps = 1;

	FluidDomainStepCoordinator(int sizeX, int sizeY, int sizeZ, FluidSolverConfig config, int maximumParticleCount) {
		super(sizeX, sizeY, sizeZ, config, maximumParticleCount);
	}

	public StepStats step() { return step(false, 0); }

	public StepStats step(boolean phaseChangeEnabled, int maximumPhaseOperations) {
		return step(phaseChangeEnabled, maximumPhaseOperations, false, true);
	}

	/** Consumes existing mechanical liens without advancing conduction, chemistry or any phase transfer. */
	public StepStats stepMechanicsOnly() { return step(false, 0, false, false); }

	/** Diagnostic production step with a staged mechanical-energy audit; authoritative physics is unchanged. */
	public StepStats stepWithMechanicalAudit(boolean phaseChangeEnabled, int maximumPhaseOperations) {
		return step(phaseChangeEnabled, maximumPhaseOperations, true, true);
	}

	StepStats step(
			boolean phaseChangeEnabled, int maximumPhaseOperations, boolean captureMechanicalAudit,
			boolean thermochemistryEnabled
	) {
		return step(phaseChangeEnabled, maximumPhaseOperations, captureMechanicalAudit, thermochemistryEnabled, 0);
	}

	/** Reuses the granular displacement subdivision prepared for this backend attempt. */
	StepStats step(
			boolean phaseChangeEnabled, int maximumPhaseOperations, boolean captureMechanicalAudit,
			boolean thermochemistryEnabled, int preparedSubsteps
	) {
		if (preparedSubsteps < 0) throw new IllegalArgumentException("Prepared mechanical substeps are invalid");
		if (phaseChangeEnabled && (maximumPhaseOperations <= 0
				|| maximumPhaseOperations > ThermalLimits.MAX_PHASE_OPERATIONS_PER_STEP)) {
			throw new IllegalArgumentException("Production phase operation budget is invalid");
		}
		carrierPropertiesWorkspace.beginStep();
		float before = domain().totalVolume();
		double massBefore = domain().totalMass();
		MechanicalAuditAccumulator mechanicalAudit = captureMechanicalAudit
				? new MechanicalAuditAccumulator(mechanicalCarrierKineticEnergyJoules(), mechanicalPotentialEnergyJoules())
				: null;
		SignedDyadic thermalMechanicalWorkBefore = !captureMechanicalAudit || thermalState == null ? SignedDyadic.ZERO
				: thermalState.auditLedger().mechanicalWork();
		lastGranularBoundaryImpulseX = 0.0;
		lastGranularBoundaryImpulseY = 0.0;
		lastGranularBoundaryImpulseZ = 0.0;
		int substeps = preparedSubsteps > 0 ? preparedSubsteps : calculateSubsteps(config.timeStep());
		float dt = config.timeStep() / substeps;
		int granularParcelCountBeforeAdvection = precipitationEnabled ? captureReactiveGranularCells() : 0;
		int pressureIterations = 0;
		ViscousStepStats viscosity = ViscousStepStats.INACTIVE;
		LiquidTransportStats liquidTransport = LiquidTransportStats.INACTIVE;
		for (int substep = 0; substep < substeps; substep++) {
			MechanicalStepPipeline.SubstepOutcome outcome = mechanicalPipeline.substep(
					dt, substep, captureMechanicalAudit, mechanicalAudit);
			pressureIterations += outcome.pressureIterations();
			viscosity = viscosity.plus(outcome.viscosity());
			liquidTransport = liquidTransport.plus(outcome.liquidTransport());
			if (outcome.granularMechanics() != null) {
				lastGranularBoundaryImpulseX += outcome.granularMechanics().boundaryImpulseX();
				lastGranularBoundaryImpulseY += outcome.granularMechanics().boundaryImpulseY();
				lastGranularBoundaryImpulseZ += outcome.granularMechanics().boundaryImpulseZ();
			}
		}
		double carrierTransferInitialKineticEnergy = captureMechanicalAudit ? mechanicalCarrierKineticEnergyJoules() : 0.0;
		if (precipitationEnabled) markMovedReactiveGranularCells(granularParcelCountBeforeAdvection);
		ThermalAxisStats thermalAxis = thermochemistryEnabled
				? runThermalReactionPhaseAxis(phaseChangeEnabled, maximumPhaseOperations)
				: inactiveThermalAxisStats();
		if (!thermochemistryEnabled) {
			lastChemicalReactionStats = thermalAxis.chemistry();
			lastPhaseTransferStats = thermalAxis.granularPhase();
			lastThermalPhaseStats = thermalAxis.condensedPhase();
			lastGasThermalPhaseStats = thermalAxis.gasPhase();
		}
		ChemicalParcelStore.ReactionStats chemistry = thermalAxis.chemistry();
		GranularTransitionStage.Outcome granularTransition = granularTransitionStage.step();
		lastGranularDepositStats = granularTransition.depositStats();
		liquidTransport = liquidTransport.plus(granularTransition.liquidTransport());
		double finalParticleKineticEnergy = captureMechanicalAudit ? mechanicalCarrierKineticEnergyJoules() : 0;
		if (captureMechanicalAudit) mechanicalAudit.carrierInventoryTransferJoules +=
				finalParticleKineticEnergy - carrierTransferInitialKineticEnergy;
		return new StepStats(
				substeps, pressureIterations, lastPressureResidual, before, domain().totalVolume(), massBefore, domain().totalMass(),
				0, 0, 0, 0, 0, 0, 0,
				chemistry.dirtyCells(), chemistry.evaluations(), chemistry.changedMarkers(),
				thermalAxis.condensedPhase(), thermalAxis.gasPhase(),
				captureMechanicalAudit ? mechanicalAudit.finish(
						finalParticleKineticEnergy, mechanicalPotentialEnergyJoules(),
						(thermalState == null ? SignedDyadic.ZERO : thermalState.auditLedger().mechanicalWork())
								.subtract(thermalMechanicalWorkBefore)) : MechanicalStepAudit.notCaptured(),
				thermalAxis, lastGranularDepositStats, carrierPropertiesWorkspace.finishStep(), viscosity, liquidTransport
		);
	}

	private static ThermalAxisStats inactiveThermalAxisStats() {
		return new ThermalAxisStats(
				new ThermalTransport.Stats(0, 0, 0.0, SignedDyadic.ZERO, SignedDyadic.ZERO),
				new ChemicalParcelStore.ReactionStats(0, 0, 0),
				new PhaseTransferStats(0, 0, 0, 0L),
				new FluidDomain.ThermalPhaseStats(0, 0, 0, 0L),
				new FluidDomain.GasThermalPhaseStats(0, 0, 0));
	}

	/** Diagnostic kinetic energy of liquid markers and active granular carriers. */
	double mechanicalCarrierKineticEnergyJoules() {
		return checkedMechanicalEnergy(particleKineticEnergyJoules() + granularKineticEnergyJoules());
	}

	/** The grid represents liquid only; active granular velocity remains in its own carrier store. */
	double mechanicalGridKineticEnergyJoules() {
		return checkedMechanicalEnergy(gridKineticEnergyJoules() + granularKineticEnergyJoules());
	}

	double granularKineticEnergyJoules() {
		double energy = 0;
		if (granularState != null) for (int index = 0; index < granularState.parcels.count(); index++) {
			var p = granularState.parcels.parcel(index);
			energy += .5 * p.mass() * (p.velocityX() * (double) p.velocityX()
					+ p.velocityY() * (double) p.velocityY() + p.velocityZ() * (double) p.velocityZ());
		}
		return checkedMechanicalEnergy(energy);
	}

	/** Liquid and active positions are stored points; each deposit uses its uniform bulk-bed centre. */
	double mechanicalPotentialEnergyJoules() {
		double[] energy = {gravitationalPotentialEnergyJoules()};
		if (granularState != null) {
			for (int index = 0; index < granularState.parcels.count(); index++) {
				var p = granularState.parcels.parcel(index);
				energy[0] += p.mass() * config.gravity() * p.y();
			}
			granularState.deposits.forEachGroup((cell, porosity, material, composition, volume, mass) -> {
				double centre = config.cellSize() * (cell / sizeX % sizeY
						+ .5 * granularState.deposits.cellBulkVolumeSvq(cell) / SolidVolume.SVQ_PER_CELL);
				energy[0] += mmm.units.Mass.kilograms(mass) * config.gravity() * centre;
			});
		}
		return checkedMechanicalEnergy(energy[0]);
	}

	private static double checkedMechanicalEnergy(double energy) {
		if (!(Double.isFinite(energy) && energy >= 0)) throw new ArithmeticException("Mechanical carrier energy is invalid");
		return energy;
	}

	/** Liquid-only energy used by liquid transfer observations. */
	double particleKineticEnergyJoules() { ensureGrid(); return gridKineticEnergyJoules(); }

	double gravitationalPotentialEnergyJoules() {
		double energy = 0;
		for (var stock : liquidCells.physicalStocks()) {
			double y = config.cellSize() * (stock.key().cell() / sizeX % sizeY + .5);
			energy += mmm.units.Mass.kilograms(stock.massUnits()) * config.gravity() * y;
		}
		return checkedMechanicalEnergy(energy);
	}

	double gridKineticEnergyJoules() {
		double densityScale = 1.0;
		double energy = faceKineticEnergyJoules(mac.velocityX, mac.p2gWeightX, densityScale)
				+ faceKineticEnergyJoules(mac.velocityY, mac.p2gWeightY, densityScale)
				+ faceKineticEnergyJoules(mac.velocityZ, mac.p2gWeightZ, densityScale);
		if (!(Double.isFinite(energy) && energy >= 0.0)) {
			throw new ArithmeticException("Grid kinetic-energy audit is invalid");
		}
		return energy;
	}

	private double faceKineticEnergyJoules(float[] velocity, double[] weight, double densityScale) {
		double energy = 0;
		for (int face = 0; face < velocity.length; face++) {
			double kilograms = unitScale.kilograms(weight[face] * densityScale);
			energy += 0.5 * kilograms * velocity[face] * (double) velocity[face];
		}
		return energy;
	}

	/** Explicit solver-unit diagnostic of the variational face metric; physical audits use joules. */
	double gridKineticEnergySolverUnits() {
		double densityScale = 1.0;
		double energy = faceKineticEnergy(mac.velocityX, mac.p2gWeightX, densityScale)
				+ faceKineticEnergy(mac.velocityY, mac.p2gWeightY, densityScale)
				+ faceKineticEnergy(mac.velocityZ, mac.p2gWeightZ, densityScale);
		return energy;
	}

	static double faceKineticEnergy(float[] velocity, double[] weight, double densityScale) {
		double energy = 0.0;
		for (int face = 0; face < velocity.length; face++) {
			energy += 0.5 * densityScale * weight[face] * velocity[face] * (double) velocity[face];
		}
		return energy;
	}

	void rebuildGridFromParticles() { domain().rebuildDerivedFromLiquidCells(); }



	/** Runs the non-motion logical axis while a region is hydrostatically asleep. */
	public ThermalAxisStats stepThermalAxis(boolean phaseChangeEnabled, int maximumPhaseOperations) {
		if (thermalState == null) throw new IllegalStateException("Thermal state is not bound");
		if (phaseChangeEnabled && (maximumPhaseOperations <= 0
				|| maximumPhaseOperations > ThermalLimits.MAX_PHASE_OPERATIONS_PER_STEP)) {
			throw new IllegalArgumentException("Production phase operation budget is invalid");
		}
		ThermalAxisStats result = runThermalReactionPhaseAxis(phaseChangeEnabled, maximumPhaseOperations);
		gridDirty = true;
		ensureInventoryGrid();
		return result;
	}

	ThermalAxisStats runThermalReactionPhaseAxis(boolean phaseChangeEnabled, int maximumPhaseOperations) {
		ThermalAxisStats result = thermochemistryPipeline.step(
				thermalState != null, precipitationEnabled, config.timeStep(),
				phaseChangeEnabled, maximumPhaseOperations);
		lastChemicalReactionStats = result.chemistry();
		lastPhaseTransferStats = result.granularPhase();
		lastThermalPhaseStats = result.condensedPhase();
		lastGasThermalPhaseStats = result.gasPhase();
		return result;
	}

	public record ThermalAxisStats(
			ThermalTransport.Stats transport,
			ChemicalParcelStore.ReactionStats chemistry,
			PhaseTransferStats granularPhase,
			FluidDomain.ThermalPhaseStats condensedPhase,
			FluidDomain.GasThermalPhaseStats gasPhase
	) { }

	/**
	 * Brings the derived gas topology to the same carrier geometry that a checkpoint will encode:
	 * persistence must either commit this jointly planned remap or keep the previous HEAD.
	 */
	public void synchronizeGasTopologyForPersistence() {
		gasTopologyCoordinator.synchronizeForPersistence();
	}

	/** Gauge pressure of the sealed cavity owning an air cell; zero for ambient and inactive gas. */
	float gasPressureOfAirCell(int airCell) {
		if (!gasState.pressureActive) return 0.0F;
		int node = gasNodeOfAirCell(airCell);
		if (node <= 0) return 0.0F;
		return gasPressureOfNode(node);
	}

	int gasNodeOfAirCell(int cell) { return gasState.frame == null ? -1 : gasState.frame.commonCellNode(cell); }
	int rawGasNodeOfCell(int cell) { return gasNodeOfAirCell(cell); }

	float gasPressureOfNode(int node) {
		return (float) gasBoundary.nodeGaugePressurePa(node);
	}

	int particleCellIndex(int particle) {
		return liquidCells.physicalStockAt(particle).key().cell();
	}

	int particleCellIndex(float positionX, float positionY, float positionZ) {
		float h = config.cellSize();
		int x = Math.min(sizeX - 1, Math.max(0, (int) Math.floor(positionX / h)));
		int y = Math.min(sizeY - 1, Math.max(0, (int) Math.floor(positionY / h)));
		int z = Math.min(sizeZ - 1, Math.max(0, (int) Math.floor(positionZ / h)));
		return cellIndex(x, y, z);
	}

	long granularGeometricOpenSvq(int cell) {
		return SolidVolume.fromCellVolumeExact(geometry.cutCells.openCellVolume(cell));
	}

	GranularDepositDynamics granularDepositDynamics() { return granularDepositDynamics; }

	GranularDepositDynamics.Environment granularDepositEnvironment(long[] plannedSolid) {
		return new GranularDepositDynamics.Environment() {
			private GranularBedShear.LiftWork liftWork;
			private SignedDyadic wakeRounding=SignedDyadic.ZERO;
			private GranularInventory.EnergyPlan relaxationEnergy;
			@Override public boolean planWakeWork(int cell, SignedDyadic joules) {
				liftWork = GranularBedShear.planLift(domain(), cell, joules);
				return liftWork != null;
			}
			@Override public void requireWakeWorkCurrent(){liftWork.requireCurrent(domain());}
			@Override public SignedDyadic wakeWorkRounding(){return wakeRounding;}
			@Override public void commitWakeWork() {
				var impulse = liftWork.apply(domain());
				wakeRounding=liftWork.mechanicalRounding();
				receiveContactImpulse(impulse[0], impulse[1], impulse[2]);
				liftWork = null;
			}
			@Override public double gravity() { return config.gravity(); }
			@Override public long geometricOpenSvq(int cell) {
				return SolidVolume.fromCellVolumeExact(geometry.cutCells.openCellVolume(cell));
			}

			@Override public boolean horizontalPathOpen(int fromCell, int directionX, int directionZ) {
				if (directionX == 0 && directionZ == 0 || Math.abs(directionX) > 1 || Math.abs(directionZ) > 1) {
					throw new IllegalArgumentException("Granular slope direction is invalid");
				}
				int x = fromCell % sizeX, yz = fromCell / sizeX, y = yz % sizeY, z = yz / sizeY;
				int targetX = x + directionX, targetZ = z + directionZ;
				if (!inside(targetX, y, targetZ)) return false;
				if (directionX == 0 || directionZ == 0) {
					return openFaceFromCell(fromCell, directionX, 0, directionZ) > 0.0F;
				}
				boolean xThenZ = openFaceFromCell(fromCell, directionX, 0, 0) > 0.0F;
				int afterX = cellIndex(targetX, y, z);
				xThenZ &= openFaceFromCell(afterX, 0, 0, directionZ) > 0.0F;
				boolean zThenX = openFaceFromCell(fromCell, 0, 0, directionZ) > 0.0F;
				int afterZ = cellIndex(x, y, targetZ);
				zThenX &= openFaceFromCell(afterZ, directionX, 0, 0) > 0.0F;
				return xThenZ || zThenX;
			}

			@Override public double shields(int surfaceCell, mmm.granular.ParticulateMaterial material) {
				return GranularBedShear.shields(domain(), surfaceCell, material);
			}

			@Override public boolean verticalPathOpen(float x, float fromY, float z, float toY, long solidSvq) {
				float h = config.cellSize();
				int cx = Math.min(sizeX - 1, Math.max(0, (int) Math.floor(x / h)));
				int cz = Math.min(sizeZ - 1, Math.max(0, (int) Math.floor(z / h)));
				int from = Math.min(sizeY - 1, Math.max(0, (int) Math.floor(fromY / h)));
				int to = Math.min(sizeY - 1, Math.max(0, (int) Math.floor(toY / h)));
				for (int y = Math.min(from, to); y < Math.max(from, to); y++) {
					if (openFaceFromCell(cellIndex(cx, y, cz), 0, 1, 0) <= 0.0F) return false;
				}
				return !granularCollision.verticalSpherePathTouchesSolid(x, fromY, z, toY, solidSvq);
			}

			@Override public int verticalPathWork(float x, float fromY, float z, float toY, long solidSvq) {
				return granularCollision.verticalSpherePathWork(x, fromY, z, toY, solidSvq);
			}

			@Override public boolean bedGeometryFits(int cell, long bulk) { return granularCollision.bedGeometryFits(cell, bulk); }

			@Override public boolean horizontalTransferPathOpen(float x, float y, float z, float toX, float toZ, long solidSvq) {
				return !granularCollision.spherePathTouchesSolid(x, y, z, toX, y, toZ, solidSvq);
			}

			@Override public int horizontalTransferPathWork(float x, float y, float z, float toX, float toZ, long solidSvq) {
				return granularCollision.spherePathWork(x, y, z, toX, y, toZ, solidSvq);
			}

			@Override public void receiveContactImpulse(double x, double y, double z) {
				lastGranularBoundaryImpulseX += x;
				lastGranularBoundaryImpulseY += y;
				lastGranularBoundaryImpulseZ += z;
			}

			@Override public boolean planSettlement(GranularInventory.SettlementPlan plan) {
				long[] candidate = plannedSolid.clone();
				for (var parcel : plan.parcels()) granularCoupling.removePlannedParcel(candidate,
						parcelPosition(parcel), parcel.solidVolumeSvq());
				for (var target : plan.targets()) if (!granularCoupling.tryPlanCell(candidate, target.cell(), target.addedSolidVolumeSvq(),
						((FluidDomain) FluidDomainStepCoordinator.this)::granularGeometricOpenSvq)) return false;
				System.arraycopy(candidate, 0, plannedSolid, 0, candidate.length);
				return true;
			}

			@Override public void cancelSettlement(GranularInventory.SettlementPlan plan) {
				for (var target : plan.targets()) granularCoupling.removePlannedCell(plannedSolid, target.cell(), target.addedSolidVolumeSvq());
				for (var parcel : plan.parcels()) restorePlannedParcel(plannedSolid, parcelPosition(parcel),
						parcel.solidVolumeSvq(), "joint settlement rollback");
			}

			@Override public boolean planRelaxation(int fromCell, int toCell, long solidSvq) {
				granularCoupling.removePlannedCell(plannedSolid, fromCell, solidSvq);
				if (granularCoupling.tryPlanCell(plannedSolid, toCell, solidSvq,
						((FluidDomain) FluidDomainStepCoordinator.this)::granularGeometricOpenSvq)) return true;
				restorePlannedCell(plannedSolid, fromCell, solidSvq, "relaxation");
				return false;
			}

			@Override public void cancelRelaxation(int fromCell, int toCell, long solidSvq) {
				relaxationEnergy = null;
				granularCoupling.removePlannedCell(plannedSolid, toCell, solidSvq);
				restorePlannedCell(plannedSolid, fromCell, solidSvq, "relaxation rollback");
			}

			@Override public void requireThermalRelaxation(
					int fromCell, int toCell, int material, int composition, long movedMassUnits, long sourceMassUnits, SignedDyadic heat
			) {
				if (thermalState == null) return;
				relaxationEnergy = thermalState.planDepositMove(
						new GranularEnergyColumn.DepositKey(fromCell, material, composition),
						new GranularEnergyColumn.DepositKey(toCell, material, composition), movedMassUnits, sourceMassUnits, heat);
				relaxationEnergy.requireCurrent();
			}

			@Override public void commitThermalRelaxation(
					int fromCell, int toCell, int material, int composition, long movedMassUnits, long sourceMassUnits, SignedDyadic heat
			) {
				if (thermalState == null) return;
				if (relaxationEnergy == null) throw new IllegalStateException("Missing granular relaxation energy plan");
				relaxationEnergy.commit();
				relaxationEnergy = null;
			}

			@Override public int wakeGeometryWork() {
				return cellCount + 2 * granularState.parcels.count() + 27;
			}
			@Override public boolean planWake(int cell, long solidSvq, float x, float y, float z) {
				if (!granularCollision.positionValid(x, y, z, solidSvq)) return false;
				var admission = new GranularSpawnAdmission(sizeX, sizeY, sizeZ, config.cellSize(), granularState.parcels, 0);
				if (!admission.allows(x, y, z, solidSvq)) return false;
				granularCoupling.removePlannedCell(plannedSolid, cell, solidSvq);
				GranularCouplingFields.Position position = new GranularCouplingFields.Position(x, y, z);
				if (granularCoupling.tryPlanParcel(plannedSolid, position, solidSvq,
						((FluidDomain) FluidDomainStepCoordinator.this)::granularGeometricOpenSvq)) return true;
				restorePlannedCell(plannedSolid, cell, solidSvq, "wake");
				return false;
			}

			@Override public void cancelWake(int cell, long solidSvq, float x, float y, float z) {
				granularCoupling.removePlannedParcel(plannedSolid,
						new GranularCouplingFields.Position(x, y, z), solidSvq);
				restorePlannedCell(plannedSolid, cell, solidSvq, "wake rollback");
			}

			@Override public void onSolidChanged(int cell) {
				if (precipitationEnabled) chemicalParcels.markDirtyCell(cell);
			}
		};
	}

	static GranularCouplingFields.Position parcelPosition(GranularParcelStore.Parcel parcel) {
		return new GranularCouplingFields.Position(parcel.x(), parcel.y(), parcel.z(), parcel.ceilMask());
	}

	void restorePlannedParcel(
			long[] plannedSolid, GranularCouplingFields.Position position, long solidSvq, String transition
	) {
		if (!granularCoupling.tryPlanParcel(plannedSolid, position, solidSvq, this::granularGeometricOpenSvq)) {
			throw new IllegalStateException("Granular " + transition + " could not restore parcel occupancy");
		}
	}

	void restorePlannedCell(long[] plannedSolid, int cell, long solidSvq, String transition) {
		if (!granularCoupling.tryPlanCell(plannedSolid, cell, solidSvq, this::granularGeometricOpenSvq)) {
			throw new IllegalStateException("Granular " + transition + " could not restore deposit occupancy");
		}
	}

	int captureReactiveGranularCells() {
		int count = granularState.parcels.count();
		for (int parcel = 0; parcel < count; parcel++) {
			GranularParcelStore.Parcel state = granularState.parcels.parcel(parcel);
			granularReactivePreviousCells[parcel] = state.solidCompositionHandle() == 0
					? -1 : granularParcelCell(state);
		}
		return count;
	}

	void markMovedReactiveGranularCells(int expectedCount) {
		if (granularState.parcels.count() != expectedCount) {
			throw new IllegalStateException("Granular advection changed the exact active parcel inventory");
		}
		for (int parcel = 0; parcel < expectedCount; parcel++) {
			int previous = granularReactivePreviousCells[parcel];
			if (previous < 0) continue;
			int current = granularParcelCell(granularState.parcels.parcel(parcel));
			if (current != previous) {
				chemicalParcels.markDirtyCell(previous);
				chemicalParcels.markDirtyCell(current);
			}
		}
	}

	/**
	 * Darcy pore damping reads the same derived carrier workspace the granular drag and bed shear use,
	 * so one revision derives each cell's density, temperature and viscosity exactly once.
	 */
	CarrierProperties granularCarrierProperties(int cell) {
		LiquidCarrierProperties properties = domain().derivedCarrierState();
		return properties.hasLiquid(cell)
				? new CarrierProperties(properties.density(cell), properties.viscosityPascalSeconds(cell))
				: new CarrierProperties(LiquidCarrierProperties.AIR_DENSITY, LiquidCarrierProperties.AIR_VISCOSITY);
	}

	static float trilinearWeight(int ox, int oy, int oz, float fx, float fy, float fz) {
		return (ox == 0 ? 1.0F - fx : fx) * (oy == 0 ? 1.0F - fy : fy) * (oz == 0 ? 1.0F - fz : fz);
	}

	static void normalize(float[] values, float[] weights) {
		for (int i = 0; i < values.length; i++) values[i] = weights[i] > 0 ? values[i] / weights[i] : 0.0F;
	}

	int calculateSubsteps(float dt) {
		double required = 1;
		double maximum = 0;
		if (granularState != null) for (int index = 0; index < granularState.parcels.count(); index++) {
			var parcel = granularState.parcels.parcel(index);
			maximum = Math.max(maximum, Math.abs(parcel.velocityX()) + Math.abs(parcel.velocityY()) + Math.abs(parcel.velocityZ()));
		}
		if (maximum > 0) required = Math.max(required, (maximum + config.gravity() * dt) * dt / (config.cellSize() * config.maxCourantNumber()));
		return Math.max(minimumMechanicalSubsteps, Math.max(1, (int) Math.ceil(required)));
	}

	/**
	 * Applies a bounded immersed moving-body constraint to authoritative marker momentum.
	 * Volume, mass, composition and energy are unchanged; the returned SI impulse is calculated from
	 * the actual float velocity writes, including their representational roundoff.
	 */
	public MovingBodyFluidCoupling.Result applyMovingBodyCoupling(MovingBodyFluidCoupling.Request request) {
		if (request == null) throw new IllegalArgumentException("Moving-body request is missing");
		requireFiniteMarkerVelocity(request.velocityXMetresPerSecond());
		requireFiniteMarkerVelocity(request.velocityYMetresPerSecond());
		requireFiniteMarkerVelocity(request.velocityZMetresPerSecond());
		ensureGrid();
		double h = config.cellSize(), cellVolume = unitScale.cubicMetersPerNormalizedVolume();
		double requested = request.displacedVolumeCubicMetres() / cellVolume;
		if (!(Double.isFinite(requested) && requested > 0)) throw new IllegalArgumentException("Body displacement exceeds solver scale");
		double[] weighted = new double[cellCount]; double total = 0; int coupled = 0;
		for (int cell = 0; cell < cellCount; cell++) {
			double weight = MovingBodyFluidCoupling.kernelWeight((cell % sizeX + .5) * h - request.localXMetres(),
					(cell / sizeX % sizeY + .5) * h - request.localYMetres(),
					(cell / (sizeX * sizeY) + .5) * h - request.localZMetres(), h);
			weighted[cell] = weight * liquidCells.exactCellVolume(cell).toDouble();
			if (weighted[cell] > 0) { total += weighted[cell]; coupled++; }
		}
		if (total == 0) return MovingBodyFluidCoupling.Result.NONE;
		double scale = Math.min(1, requested / total); double[] impulse = new double[3];
		double[] target = {request.velocityXMetresPerSecond(), request.velocityYMetresPerSecond(), request.velocityZMetresPerSecond()};
		for (int axis = 0; axis < 3; axis++) {
			float[] velocity = axis == 0 ? mac.velocityX : axis == 1 ? mac.velocityY : mac.velocityZ;
			double[] masses = axis == 0 ? cellMacStage.massX : axis == 1 ? cellMacStage.massY : cellMacStage.massZ;
			int nx = sizeX + (axis == 0 ? 1 : 0), ny = sizeY + (axis == 1 ? 1 : 0), nz = sizeZ + (axis == 2 ? 1 : 0);
			for (int z = 0; z < nz; z++) for (int y = 0; y < ny; y++) for (int x = 0; x < nx; x++) {
				int coordinate = axis == 0 ? x : axis == 1 ? y : z, length = axis == 0 ? sizeX : axis == 1 ? sizeY : sizeZ;
				if (coordinate == 0 || coordinate == length) continue;
				int right = cellIndex(x, y, z), left = cellIndex(x - (axis == 0 ? 1 : 0), y - (axis == 1 ? 1 : 0), z - (axis == 2 ? 1 : 0));
				int face = (z * ny + y) * nx + x;
				double volume = liquidCells.exactCellVolume(left).toDouble() + liquidCells.exactCellVolume(right).toDouble();
				if (volume == 0) continue;
				double blend = scale * (weighted[left] + weighted[right]) / volume;
				float old = velocity[face], next = (float) (old + blend * (target[axis] - old));
				velocity[face] = next; impulse[axis] += masses[face] * (next - old);
			}
		}
		cellMacStage.publishMomentum();
		return new MovingBodyFluidCoupling.Result(coupled, scale * total * cellVolume, impulse[0], impulse[1], impulse[2]);
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
		int x = Math.min(sizeX - 1, Math.max(0, (int) Math.floor(parcel.x() / config.cellSize())));
		int y = Math.min(sizeY - 1, Math.max(0, (int) Math.floor(parcel.y() / config.cellSize())));
		int z = Math.min(sizeZ - 1, Math.max(0, (int) Math.floor(parcel.z() / config.cellSize())));
		return cellIndex(x, y, z);
	}


	float granularMergeVelocityDelta() {
		return Math.max(config.cellSize() * 0.5F, config.gravity() * config.timeStep() * 1.25F);
	}

	public record PhaseTransferStats(int transfers, int precipitations, int dissolutions, long signedSolidSvq) { }

	boolean granularOccupancyFitsGeometry(GranularCouplingFields coupling) {
		for (int cell = 0; cell < cellCount; cell++) {
			if (coupling.solidSvq(cell) > SolidVolume.fromCellVolumeExact(geometry.cutCells.openCellVolume(cell))) return false;
		}
		return true;
	}

	boolean planGranularMerge(
			long[] planned, GranularParcelStore.Parcel first, GranularParcelStore.Parcel second,
			GranularParcelStore.Parcel merged
	) {
		if (!granularCollision.positionValid(merged.x(), merged.y(), merged.z(), merged.solidVolumeSvq())) return false;
		GranularCouplingFields.Position firstPosition = parcelPosition(first);
		GranularCouplingFields.Position secondPosition = parcelPosition(second);
		granularCoupling.removePlannedParcel(planned, firstPosition, first.solidVolumeSvq());
		granularCoupling.removePlannedParcel(planned, secondPosition, second.solidVolumeSvq());
		GranularCouplingFields.Position mergedPosition = parcelPosition(merged);
		if (granularCoupling.tryPlanParcel(planned, mergedPosition, merged.solidVolumeSvq(),
				this::granularGeometricOpenSvq)) return true;
		if (!granularCoupling.tryPlanParcel(planned, firstPosition, first.solidVolumeSvq(),
				this::granularGeometricOpenSvq)
				|| !granularCoupling.tryPlanParcel(planned, secondPosition, second.solidVolumeSvq(),
				this::granularGeometricOpenSvq)) {
			throw new IllegalStateException("Rejected granular compaction could not restore exact occupancy");
		}
		return false;
	}

}

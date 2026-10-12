package mmm.fluid;

import mmm.chemistry.ChemicalParcelStore;
import mmm.fluid.air.AirNetwork;
import mmm.granular.GranularCarrier;
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

	/** Reuses the CFL estimate already charged to this private backend attempt. */
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
		ParticleReseedStage.Outcome reseedOutcome = particleReseedStage.step(captureMechanicalAudit);
		double reseedInitialKineticEnergy = reseedOutcome.initialKineticEnergy();
		if (captureMechanicalAudit) mechanicalAudit.carrierInventoryTransferJoules +=
				reseedInitialKineticEnergy - carrierTransferInitialKineticEnergy;
		FlipParticleReseeder.Result reseeding = reseedOutcome.result();
		double finalParticleKineticEnergy = reseedOutcome.finalKineticEnergy();
		if (captureMechanicalAudit) mechanicalAudit.reseedTransferJoules +=
				finalParticleKineticEnergy - reseedInitialKineticEnergy;
		return new StepStats(
				substeps, pressureIterations, lastPressureResidual, before, domain().totalVolume(), massBefore, domain().totalMass(),
				reseeding.merged(), reseeding.split(), reseeding.budgetSkipped(), reseeding.geometrySkipped(),
				reseeding.conservationSkipped(),
				reseeding.ledgerError(), reseeding.massLedgerError(),
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
				energy[0] += mass * config.gravity() * centre;
			});
		}
		return checkedMechanicalEnergy(energy[0]);
	}

	private static double checkedMechanicalEnergy(double energy) {
		if (!(Double.isFinite(energy) && energy >= 0)) throw new ArithmeticException("Mechanical carrier energy is invalid");
		return energy;
	}

	/** Liquid-only energy used by liquid transfer observations. */
	double particleKineticEnergyJoules() {
		double energy = 0.0;
		for (int marker = 0; marker < particles.count(); marker++) {
			double speedSquared = particles.velocityX(marker) * (double) particles.velocityX(marker)
					+ particles.velocityY(marker) * (double) particles.velocityY(marker)
					+ particles.velocityZ(marker) * (double) particles.velocityZ(marker);
			energy += 0.5 * unitScale.kilograms(particles.mass(marker)) * speedSquared;
		}
		if (!(Double.isFinite(energy) && energy >= 0.0)) {
			throw new ArithmeticException("Particle kinetic-energy audit is invalid");
		}
		return energy;
	}

	double gravitationalPotentialEnergyJoules() {
		double energy = 0.0;
		for (int marker = 0; marker < particles.count(); marker++) {
			energy += unitScale.kilograms(particles.mass(marker)) * config.gravity() * particles.positionY(marker);
		}
		if (!(Double.isFinite(energy) && energy >= 0.0)) {
			throw new ArithmeticException("Particle gravitational-potential audit is invalid");
		}
		return energy;
	}

	double gridKineticEnergyJoules() {
		double densityScale = variableDensityActive ? 1.0 : config.density();
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
		double densityScale = variableDensityActive ? 1.0 : config.density();
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

	void rebuildGridFromParticles() { particleToGridStage.rebuild(); }

	/** Rebuilds only the derived carrier geometry consumed by an in-progress gas phase scan. */
	void rebuildGasPhaseGeometryFromParticles() {
		Arrays.fill(volume, 0.0F);
		Arrays.fill(markerGrid.markerCount, 0);
		Arrays.fill(markerGrid.cellMarkerHead, -1);
		Arrays.fill(markerGrid.cellMarkerTail, -1);
		Arrays.fill(markerGrid.exactCellMarkerVolume, 0.0);
		for (int marker = 0; marker < particles.count(); marker++) {
			int cell = particleCellIndex(marker);
			int previous = markerGrid.cellMarkerTail[cell];
			markerGrid.particleCellPrevious[marker] = previous;
			markerGrid.particleCellNext[marker] = -1;
			if (previous < 0) markerGrid.cellMarkerHead[cell] = marker;
			else markerGrid.particleCellNext[previous] = marker;
			markerGrid.cellMarkerTail[cell] = marker;
			markerGrid.markerCount[cell]++;
			markerGrid.exactCellMarkerVolume[cell] += particles.volume(marker);
			if (!CellLiquidVolumeTransport.enabled())
				splatCenter(particles.positionX(marker), particles.positionY(marker), particles.positionZ(marker), particles.volume(marker));
		}
		if (CellLiquidVolumeTransport.enabled()) {
			markerGrid.exactCellVolumeInventory = ExactCellVolumeSums.sum(particles.directArrays().volume,
					particles.count(), markerGrid.cellMarkerHead, markerGrid.particleCellNext);
			for (int cell = 0; cell < cellCount; cell++) {
				markerGrid.exactCellMarkerVolume[cell] = markerGrid.exactCellVolumeInventory[cell].toDouble();
				volume[cell] = (float) markerGrid.exactCellMarkerVolume[cell];
			}
		}
	}

	void refreshGasPhaseGeometry() {
		// A surviving marker ordinal does not prove stable gas membership: condensation can fill a
		// retained partial-cell cavity, and either direction can cross the gas-centred interface
		// threshold. Rebuild from carrier truth before jointly remapping gas inventory and U so the
		// next checkpoint can be reconstructed from its persisted markers alone.
		rebuildGasPhaseGeometryFromParticles();
		gasTopologyCoordinator.refreshDeferringPhaseVolumeWork();
	}

	/** Runs the non-motion logical axis while a region is hydrostatically asleep. */
	public ThermalAxisStats stepThermalAxis(boolean phaseChangeEnabled, int maximumPhaseOperations) {
		if (thermalState == null) throw new IllegalStateException("Thermal state is not bound");
		if (phaseChangeEnabled && (maximumPhaseOperations <= 0
				|| maximumPhaseOperations > ThermalLimits.MAX_PHASE_OPERATIONS_PER_STEP)) {
			throw new IllegalArgumentException("Production phase operation budget is invalid");
		}
		ThermalAxisStats result = runThermalReactionPhaseAxis(phaseChangeEnabled, maximumPhaseOperations);
		gridDirty = true;
		rebuildGridFromParticles();
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

	int gasNodeOfAirCell(int airCell) {
		if (gasState.frame != null) {
			for (int micro = 0; micro < 8; micro++) if (gasState.frame.nodeOfPiece(8 * airCell + micro) >= 0)
				return gasState.frame.commonCellNode(airCell);
		}
		int node = projection.pressureAirNodeId[airCell];
		return node >= 0 ? node : rawGasNodeOfCell(airCell);
	}

	int rawGasNodeOfCell(int cell) {
		return gasBoundary.rawCellNode(cell);
	}

	/** Extends gas lineage through pressure-air fringe without crossing pressure-fluid bulk. */
	void rebuildPressureBoundaryLineage() {
		Arrays.fill(projection.pressureAirNodeId, -1);
		if (!gasBoundary.pressureActive() || !gasBoundary.topologyInitialized()) return;
		for (int node = AirNetwork.AMBIENT_NODE; node <= gasBoundary.previousSealedNodeCount(); node++) {
			int head = 0, tail = 0;
			for (int cell = 0; cell < cellCount; cell++) if (isAir(cell)
					&& rawGasNodeOfCell(cell) == node
					&& (projection.pressureAirNodeId[cell] < 0 || node < projection.pressureAirNodeId[cell])) {
				projection.pressureAirNodeId[cell] = node;
				projection.pressureAirQueue[tail++] = cell;
			}
			while (head < tail) {
				int cell = projection.pressureAirQueue[head++];
				int x = cell % sizeX;
				int yz = cell / sizeX;
				int y = yz % sizeY;
				int z = yz / sizeY;
				tail = visitPressureAir(cell, x - 1, y, z, -1, 0, 0, node, tail);
				tail = visitPressureAir(cell, x + 1, y, z, 1, 0, 0, node, tail);
				tail = visitPressureAir(cell, x, y - 1, z, 0, -1, 0, node, tail);
				tail = visitPressureAir(cell, x, y + 1, z, 0, 1, 0, node, tail);
				tail = visitPressureAir(cell, x, y, z - 1, 0, 0, -1, node, tail);
				tail = visitPressureAir(cell, x, y, z + 1, 0, 0, 1, node, tail);
			}
		}
	}

	int visitPressureAir(
			int from, int x, int y, int z, int directionX, int directionY, int directionZ,
			int node, int tail
	) {
		if (!inside(x, y, z)) return tail;
		int cell = cellIndex(x, y, z);
		// Actual gas geometry already owns its pressure source; extend only into missing pressure-air support.
		if (gasState.frame != null) for (int micro = 0; micro < 8; micro++)
			if (gasState.frame.nodeOfPiece(8 * cell + micro) >= 0) return tail;
		if (!isAir(cell) || projection.pressureAirNodeId[cell] >= 0 && projection.pressureAirNodeId[cell] <= node
				|| !(pressureOpenFaceFromCell(from, cell, directionX, directionY, directionZ) > 0.0F)) {
			return tail;
		}
		projection.pressureAirNodeId[cell] = node;
		projection.pressureAirQueue[tail] = cell;
		return tail + 1;
	}

	float gasPressureOfNode(int node) {
		return (float) gasBoundary.nodeGaugePressurePa(node);
	}

	int particleCellIndex(int particle) {
		return particleCellIndex(particles.positionX(particle), particles.positionY(particle), particles.positionZ(particle));
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
				granularCoupling.removePlannedCell(plannedSolid, toCell, solidSvq);
				restorePlannedCell(plannedSolid, fromCell, solidSvq, "relaxation rollback");
			}

			@Override public void requireThermalRelaxation(
					int fromCell, int toCell, int material, int composition, long movedSvq, long sourceTotalSvq
			) {
				if (thermalState == null) return;
				thermalState.requireDepositMove(
						new GranularEnergyColumn.DepositKey(fromCell, material, composition),
						new GranularEnergyColumn.DepositKey(toCell, material, composition), movedSvq, sourceTotalSvq);
			}

			@Override public void commitThermalRelaxation(
					int fromCell, int toCell, int material, int composition, long movedSvq, long sourceTotalSvq
			) {
				if (thermalState == null) return;
				thermalState.commitDepositMove(
						new GranularEnergyColumn.DepositKey(fromCell, material, composition),
						new GranularEnergyColumn.DepositKey(toCell, material, composition), movedSvq, sourceTotalSvq);
			}

			@Override public boolean planWake(int cell, long solidSvq, float x, float y, float z) {
				if (!granularCollision.positionValid(x, y, z, solidSvq)) return false;
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
		return new GranularCouplingFields.Position(parcel.x(), parcel.y(), parcel.z());
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
				? new CarrierProperties(properties.density(cell), properties.viscosityPaPerS(cell))
				: new CarrierProperties(LiquidCarrierProperties.AIR_DENSITY, LiquidCarrierProperties.AIR_VISCOSITY);
	}

	void splatCenter(float px, float py, float pz, float particleAmount) {
		float h = config.cellSize();
		float gx = px / h - 0.5F, gy = py / h - 0.5F, gz = pz / h - 0.5F;
		int x0 = (int) Math.floor(gx), y0 = (int) Math.floor(gy), z0 = (int) Math.floor(gz);
		float fx = gx - x0, fy = gy - y0, fz = gz - z0;
		float validWeight = 0.0F;
		for (int oz = 0; oz <= 1; oz++) for (int oy = 0; oy <= 1; oy++) for (int ox = 0; ox <= 1; ox++) {
			int x = x0 + ox, y = y0 + oy, z = z0 + oz;
			if (!inside(x, y, z) || geometry.solid[cellIndex(x, y, z)]) continue;
			validWeight += trilinearWeight(ox, oy, oz, fx, fy, fz);
		}
		if (validWeight <= 1.0E-8F) return;
		float scale = particleAmount / validWeight;
		for (int oz = 0; oz <= 1; oz++) for (int oy = 0; oy <= 1; oy++) for (int ox = 0; ox <= 1; ox++) {
			int x = x0 + ox, y = y0 + oy, z = z0 + oz;
			if (!inside(x, y, z)) continue;
			int index = cellIndex(x, y, z);
			if (!geometry.solid[index]) volume[index] += scale * trilinearWeight(ox, oy, oz, fx, fy, fz);
		}
	}

	void splatPressureVolume(float px, float py, float pz, float particleAmount) {
		double h = config.cellSize();
		double gridX = px / h, gridY = py / h, gridZ = pz / h;
		int centerX = (int) Math.floor(gridX), centerY = (int) Math.floor(gridY), centerZ = (int) Math.floor(gridZ);
		int component = closedLiquidVolume.component(particleCellIndex(px, py, pz));
		int neighborX = gridX - centerX < 0.5 ? centerX - 1 : centerX + 1;
		int neighborY = gridY - centerY < 0.5 ? centerY - 1 : centerY + 1;
		int neighborZ = gridZ - centerZ < 0.5 ? centerZ - 1 : centerZ + 1;
		double centerWeightX = pressureKernelWeight(gridX, centerX);
		double centerWeightY = pressureKernelWeight(gridY, centerY);
		double centerWeightZ = pressureKernelWeight(gridZ, centerZ);
		double neighborWeightX = pressureKernelWeight(gridX, neighborX);
		double neighborWeightY = pressureKernelWeight(gridY, neighborY);
		double neighborWeightZ = pressureKernelWeight(gridZ, neighborZ);
		double available = 0.0;
		for (int oz = 0; oz < 2; oz++) {
			int z = oz == 0 ? centerZ : neighborZ;
			double weightZ = oz == 0 ? centerWeightZ : neighborWeightZ;
			if (z < 0 || z >= sizeZ || !(weightZ > 0.0)) continue;
			for (int oy = 0; oy < 2; oy++) {
				int y = oy == 0 ? centerY : neighborY;
				double weightY = oy == 0 ? centerWeightY : neighborWeightY;
				if (y < 0 || y >= sizeY || !(weightY > 0.0)) continue;
				for (int ox = 0; ox < 2; ox++) {
					int x = ox == 0 ? centerX : neighborX;
					double weightX = ox == 0 ? centerWeightX : neighborWeightX;
					if (x < 0 || x >= sizeX || !(weightX > 0.0)) continue;
					int cell = cellIndex(x, y, z);
					if (geometry.solid[cell] || closedLiquidVolume.component(cell) != component) continue;
					available += weightX * weightY * weightZ;
				}
			}
		}
		if (!(available > 0.0)) {
			throw new IllegalStateException("Marker pressure footprint has no open cell overlap");
		}
		double scale = particleAmount / available;
		for (int oz = 0; oz < 2; oz++) {
			int z = oz == 0 ? centerZ : neighborZ;
			double weightZ = oz == 0 ? centerWeightZ : neighborWeightZ;
			if (z < 0 || z >= sizeZ || !(weightZ > 0.0)) continue;
			for (int oy = 0; oy < 2; oy++) {
				int y = oy == 0 ? centerY : neighborY;
				double weightY = oy == 0 ? centerWeightY : neighborWeightY;
				if (y < 0 || y >= sizeY || !(weightY > 0.0)) continue;
				for (int ox = 0; ox < 2; ox++) {
					int x = ox == 0 ? centerX : neighborX;
					double weightX = ox == 0 ? centerWeightX : neighborWeightX;
					if (x < 0 || x >= sizeX || !(weightX > 0.0)) continue;
					int cell = cellIndex(x, y, z);
					if (geometry.solid[cell] || closedLiquidVolume.component(cell) != component) continue;
					projection.pressureVolume[cell] += weightX * weightY * weightZ * scale;
				}
			}
		}
	}

	/** C1 partition induced by the canonical quarter-cell spacing of the 2x2x2 marker lattice. */
	static double pressureKernelWeight(double coordinate, int cell) {
		double distance = Math.abs(coordinate - (cell + 0.5));
		if (distance <= 0.25) return 1.0;
		if (distance >= 0.75) return 0.0;
		double transition = (distance - 0.25) * 2.0;
		return 1.0 - transition * transition * (3.0 - 2.0 * transition);
	}

	static float trilinearWeight(int ox, int oy, int oz, float fx, float fy, float fz) {
		return (ox == 0 ? 1.0F - fx : fx) * (oy == 0 ? 1.0F - fy : fy) * (oz == 0 ? 1.0F - fz : fz);
	}

	/** Scatters a marker already expressed in cell coordinates, preserving ordinal accumulation order. */
	void splatFaceField(float[] sums, float[] weights, int axis, float cellX, float cellY, float cellZ, float value, float particleAmount) {
		int nx = axis == 0 ? sizeX + 1 : sizeX;
		int ny = axis == 1 ? sizeY + 1 : sizeY;
		int nz = axis == 2 ? sizeZ + 1 : sizeZ;
		float gx = Math.clamp(cellX - (axis == 0 ? 0.0F : 0.5F), 0, nx - 1);
		float gy = Math.clamp(cellY - (axis == 1 ? 0.0F : 0.5F), 0, ny - 1);
		float gz = Math.clamp(cellZ - (axis == 2 ? 0.0F : 0.5F), 0, nz - 1);
		int x0 = (int) Math.floor(gx), y0 = (int) Math.floor(gy), z0 = (int) Math.floor(gz);
		float fx = gx - x0, fy = gy - y0, fz = gz - z0;
		int endX = Math.min(1, nx - 1 - x0), endY = Math.min(1, ny - 1 - y0), endZ = Math.min(1, nz - 1 - z0);
		for (int oz = 0; oz <= endZ; oz++) for (int oy = 0; oy <= endY; oy++) for (int ox = 0; ox <= endX; ox++) {
			int x = x0 + ox, y = y0 + oy, z = z0 + oz;
			float weight = (ox == 0 ? 1.0F - fx : fx) * (oy == 0 ? 1.0F - fy : fy) * (oz == 0 ? 1.0F - fz : fz);
			int index = (z * ny + y) * nx + x;
			float weighted = weight * particleAmount;
			sums[index] += value * weighted;
			weights[index] += weighted;
		}
	}

	static void normalize(float[] values, float[] weights) {
		for (int i = 0; i < values.length; i++) values[i] = weights[i] > 0 ? values[i] / weights[i] : 0.0F;
	}

	int calculateSubsteps(float dt) {
		if (CellLiquidVolumeTransport.enabled()) return calculateVolumeTransportSubsteps(dt);
		float maximum = 0.0F;
		for (int i = 0; i < particles.count(); i++) {
			maximum = Math.max(maximum, Math.abs(particles.velocityX(i)) + Math.abs(particles.velocityY(i)) + Math.abs(particles.velocityZ(i)));
		}
		if (granularState != null) for (int index = 0; index < granularState.parcels.count(); index++) {
			GranularParcelStore.Parcel parcel = granularState.parcels.parcel(index);
			maximum = Math.max(maximum, Math.abs(parcel.velocityX())
					+ Math.abs(parcel.velocityY()) + Math.abs(parcel.velocityZ()));
		}
		maximum += config.gravity() * dt;
		return Math.max(minimumMechanicalSubsteps, (int) Math.ceil(maximum * dt / (config.cellSize() * config.maxCourantNumber())));
	}

	private int calculateVolumeTransportSubsteps(float dt) {
		ensureGrid();
		gridToParticleStage.prepareAdvectionCells();
		if (cellLiquidVolumeTransport == null) cellLiquidVolumeTransport = new CellLiquidVolumeTransport(domain());
		double required = cellLiquidVolumeTransport.courantRate(-config.gravity() * dt) * dt;
		double maximum = 0;
		for (int i = 0; i < particles.count(); i++) {
			if (gridToParticleStage.advectionCells()[particleCellIndex(i)]) continue;
			double x = particles.velocityX(i), y = particles.velocityY(i), z = particles.velocityZ(i);
			maximum = Math.max(maximum, Math.sqrt(x * x + y * y + z * z));
		}
		if (granularState != null) for (int index = 0; index < granularState.parcels.count(); index++) {
			var parcel = granularState.parcels.parcel(index);
			maximum = Math.max(maximum, Math.abs(parcel.velocityX()) + Math.abs(parcel.velocityY()) + Math.abs(parcel.velocityZ()));
		}
		maximum += config.gravity() * dt;
		required = Math.max(required, maximum * dt / (config.cellSize() * config.maxCourantNumber()));
		return Math.max(minimumMechanicalSubsteps, (int) Math.ceil(required));
	}

	/**
	 * Applies a bounded immersed moving-body constraint to authoritative marker momentum.
	 * Volume, mass, composition and energy are unchanged; the returned SI impulse is calculated from
	 * the actual float velocity writes, including their representational roundoff.
	 */
	public MovingBodyFluidCoupling.Result applyMovingBodyCoupling(MovingBodyFluidCoupling.Request request) {
		return applyMovingBodyCoupling(request, null);
	}

	MovingBodyFluidCoupling.Result applyMovingBodyCouplings(java.util.List<MovingBodyFluidCoupling.Request> requests) {
		if (requests.isEmpty()) return MovingBodyFluidCoupling.Result.NONE;
		if (requests.size() == 1) return applyMovingBodyCoupling(requests.getFirst());
		var neighborhoods = new MarkerNeighborhoodIndex(sizeX, sizeY, sizeZ, particles.count(), marker -> particleCellIndex(
				particles.positionX(marker), particles.positionY(marker), particles.positionZ(marker)));
		MovingBodyFluidCoupling.Result result = MovingBodyFluidCoupling.Result.NONE;
		for (var request : requests) {
			result = result.plus(applyMovingBodyCoupling(request, neighborhoods.around(
					request.localXMetres(), request.localYMetres(), request.localZMetres(), config.cellSize())));
		}
		return result;
	}

	private MovingBodyFluidCoupling.Result applyMovingBodyCoupling(MovingBodyFluidCoupling.Request request, int[] ordinals) {
		if (request == null) throw new IllegalArgumentException("Moving-body coupling request is missing");
		requireFiniteMarkerVelocity(request.velocityXMetresPerSecond());
		requireFiniteMarkerVelocity(request.velocityYMetresPerSecond());
		requireFiniteMarkerVelocity(request.velocityZMetresPerSecond());
		double cellVolumeM3 = (double) config.cellSize() * config.cellSize() * config.cellSize();
		double requestedCellVolume = request.displacedVolumeCubicMetres() / cellVolumeM3;
		if (!(Double.isFinite(requestedCellVolume) && requestedCellVolume > 0.0)) {
			throw new IllegalArgumentException("Moving-body displaced volume is outside the solver scale");
		}
		double weightedVolume = 0.0;
		int coupledMarkers = 0;
		int count = ordinals == null ? particles.count() : ordinals.length;
		for (int index = 0; index < count; index++) {
			int marker = ordinals == null ? index : ordinals[index];
			double weight = movingBodyWeight(request, marker);
			if (weight <= 0.0) continue;
			weightedVolume += weight * particles.volume(marker);
			coupledMarkers++;
		}
		if (!(weightedVolume > 0.0)) return MovingBodyFluidCoupling.Result.NONE;
		double volumeScale = Math.min(1.0, requestedCellVolume / weightedVolume);
		double impulseX = 0.0, impulseY = 0.0, impulseZ = 0.0, coupledVolume = 0.0;
		boolean changed = false;
		for (int index = 0; index < count; index++) {
			int marker = ordinals == null ? index : ordinals[index];
			double weight = movingBodyWeight(request, marker);
			double blend = volumeScale * weight;
			if (blend <= 0.0) continue;
			float oldX = particles.velocityX(marker), oldY = particles.velocityY(marker), oldZ = particles.velocityZ(marker);
			float newX = (float) (oldX + blend * (request.velocityXMetresPerSecond() - oldX));
			float newY = (float) (oldY + blend * (request.velocityYMetresPerSecond() - oldY));
			float newZ = (float) (oldZ + blend * (request.velocityZMetresPerSecond() - oldZ));
			particles.setVelocityX(marker, newX); particles.setVelocityY(marker, newY); particles.setVelocityZ(marker, newZ);
			impulseX += particles.mass(marker) * (newX - oldX);
			impulseY += particles.mass(marker) * (newY - oldY);
			impulseZ += particles.mass(marker) * (newZ - oldZ);
			coupledVolume += blend * particles.volume(marker);
			changed |= newX != oldX || newY != oldY || newZ != oldZ;
		}
		if (changed) gridDirty = true;
		return new MovingBodyFluidCoupling.Result(coupledMarkers, coupledVolume * cellVolumeM3,
				impulseX * cellVolumeM3, impulseY * cellVolumeM3, impulseZ * cellVolumeM3);
	}

	double movingBodyWeight(MovingBodyFluidCoupling.Request request, int marker) {
		return MovingBodyFluidCoupling.kernelWeight(
				particles.positionX(marker) - request.localXMetres(),
				particles.positionY(marker) - request.localYMetres(),
				particles.positionZ(marker) - request.localZMetres(), config.cellSize());
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

	static boolean massClose(double left, double right) {
		return Math.abs(left - right) <= Math.max(1.0E-9, Math.max(Math.ulp(left), Math.ulp(right)) * 8.0);
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
		GranularCouplingFields.Position firstPosition = new GranularCouplingFields.Position(first.x(), first.y(), first.z());
		GranularCouplingFields.Position secondPosition = new GranularCouplingFields.Position(second.x(), second.y(), second.z());
		granularCoupling.removePlannedParcel(planned, firstPosition, first.solidVolumeSvq());
		granularCoupling.removePlannedParcel(planned, secondPosition, second.solidVolumeSvq());
		GranularCouplingFields.Position mergedPosition = new GranularCouplingFields.Position(
				merged.x(), merged.y(), merged.z());
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

package mmm.fluid;

import mmm.granular.GranularMechanics;
import mmm.thermal.ThermalDomainState;


/**
 * Composes one mechanical substep from the explicit solver stages in the established order:
 * shared cell geometry, gas topology, granular mechanics, porous damping, viscous stress,
 * gravity, thermal buoyancy, surface tension, solid-face enforcement, pressure solve, the pending
 * phase-geometry synchronization, velocity projection, finite-volume mass and momentum transport
 * and collision commit. The pipeline returns only typed stats and the pending-geometry
 * completion flag; thermal and chemistry axes stay outside.
 */
final class MechanicalStepPipeline {
	enum Stage {
		IDLE, GRID_REBUILD, GAS_TOPOLOGY, CARRIER_PROPERTIES, GRANULAR_PREDICTION,
		POROUS_DAMPING, SOLID_FACE_CONSTRAINT, VISCOSITY, GRAVITY, THERMAL_BUOYANCY,
		SURFACE_TENSION, PRESSURE_SOLVE, PRESSURE_PUBLICATION,
		GRANULAR_COLLISION_PREVIEW, ADVECTION, GRANULAR_COLLISION_COMMIT, COMPLETE
	}
	private Stage diagnosticStage = Stage.IDLE;
	Stage diagnosticStage() { return diagnosticStage; }
	record Workspace(
			GasTopologyCoordinator gasTopology, PendingPhaseVolumeWork pendingPhaseVolume
	) { }

	interface Access {
		void ensureGrid();
		void prepareCarrierProperties();
		double gridAndGranularKineticEnergyJoules();
		double carrierKineticEnergyJoules();
		double granularKineticEnergyJoules();
		int lastPressureIterations();
		ThermalDomainState thermalState();
	}

	private final Workspace state;
	private final Access access;
	private final GranularMechanicalStage granularMechanicalStage;
	private final BodyForceStage bodyForceStage;
	private final PressureProjectionStage pressureStage;
	private final LiquidViscosityStage viscosityStage;
	private final CellMacStage cellMac;

	MechanicalStepPipeline(
			Workspace state, Access access, GranularMechanicalStage granularMechanicalStage,
			BodyForceStage bodyForceStage, PressureProjectionStage pressureStage, LiquidViscosityStage viscosityStage,
			CellMacStage cellMac
	) {
		this.state = state;
		this.access = access;
		this.granularMechanicalStage = granularMechanicalStage;
		this.bodyForceStage = bodyForceStage;
		this.pressureStage = pressureStage;
		this.viscosityStage = viscosityStage;
		this.cellMac = cellMac;
	}

	record SubstepOutcome(int pressureIterations, GranularMechanics.StepStats granularMechanics,
	                      boolean pendingWorkConsumed, FluidDomain.ViscousStepStats viscosity,
	                      FluidDomain.LiquidTransportStats liquidTransport) { }

	SubstepOutcome substep(float dt, int substep, boolean captureAudit, MechanicalAuditAccumulator audit) {
		diagnosticStage = Stage.GRID_REBUILD;
		access.ensureGrid();
		double stageKineticEnergy = captureAudit ? access.gridAndGranularKineticEnergyJoules() : 0.0;
		diagnosticStage = Stage.GAS_TOPOLOGY;
		try {
			state.gasTopology.step(dt);
		} catch (GasTopologyBlockedException blocked) {
			throw blocked;
		} catch (IllegalArgumentException | IllegalStateException invalidGasThermal) {
			if (access.thermalState() == null) throw invalidGasThermal;
			throw new FluidDomain.GasThermalBlockedException(invalidGasThermal.getMessage(), invalidGasThermal);
		}
		diagnosticStage = Stage.CARRIER_PROPERTIES;
		access.prepareCarrierProperties();
		diagnosticStage = Stage.GRANULAR_PREDICTION;
		GranularMechanics.StepStats granularMechanics = granularMechanicalStage.predictVelocities(dt);
		double nextKineticEnergy = captureAudit ? access.gridAndGranularKineticEnergyJoules() : 0.0;
		if (captureAudit) {
			audit.gravityWorkJoules += nextKineticEnergy - stageKineticEnergy;
			stageKineticEnergy = nextKineticEnergy;
		}
		// Dampen previously committed flow before body forces so a zero-velocity porous bed retains
		// the full hydrostatic gravity/pressure balance in the shared projection.
		diagnosticStage = Stage.POROUS_DAMPING;
		bodyForceStage.applyGranularPorousDamping(dt);
		if (captureAudit) {
			nextKineticEnergy = access.gridAndGranularKineticEnergyJoules();
			audit.porousBoundaryWorkJoules += nextKineticEnergy - stageKineticEnergy;
			stageKineticEnergy = nextKineticEnergy;
		}
		// Evaluate incoming strain before gravity prediction so hydrostatic flow retains zero viscous dissipation.
		diagnosticStage = Stage.SOLID_FACE_CONSTRAINT;
		bodyForceStage.enforceSolidFaceVelocities();
		if (captureAudit) {
			nextKineticEnergy = access.gridAndGranularKineticEnergyJoules();
			audit.fixedBoundaryWorkJoules += nextKineticEnergy - stageKineticEnergy;
			stageKineticEnergy = nextKineticEnergy;
		}
		diagnosticStage = Stage.VISCOSITY;
		FluidDomain.ViscousStepStats viscosity = viscosityStage.apply(dt);
		if (captureAudit) {
			nextKineticEnergy = access.gridAndGranularKineticEnergyJoules();
			audit.viscousWorkJoules += nextKineticEnergy - stageKineticEnergy;
			stageKineticEnergy = nextKineticEnergy;
		}
		diagnosticStage = Stage.GRAVITY;
		bodyForceStage.applyGravity(dt);
		if (captureAudit) {
			nextKineticEnergy = access.gridAndGranularKineticEnergyJoules();
			audit.gravityWorkJoules += nextKineticEnergy - stageKineticEnergy;
			stageKineticEnergy = nextKineticEnergy;
		}
		diagnosticStage = Stage.THERMAL_BUOYANCY;
		bodyForceStage.applyThermalBuoyancy(dt);
		if (captureAudit) {
			nextKineticEnergy = access.gridAndGranularKineticEnergyJoules();
			audit.thermalBuoyancyWorkJoules += nextKineticEnergy - stageKineticEnergy;
			stageKineticEnergy = nextKineticEnergy;
		}
		diagnosticStage = Stage.SURFACE_TENSION;
		bodyForceStage.applyLiquidSolidSurfaceEnergyForce(dt);
		bodyForceStage.applyLiquidPairSurfaceTensionForce(dt);
		if (captureAudit) {
			nextKineticEnergy = access.gridAndGranularKineticEnergyJoules();
			audit.surfaceTensionWorkJoules += nextKineticEnergy - stageKineticEnergy;
			stageKineticEnergy = nextKineticEnergy;
		}
		bodyForceStage.enforceSolidFaceVelocities();
		if (captureAudit) {
			nextKineticEnergy = access.gridAndGranularKineticEnergyJoules();
			audit.fixedBoundaryWorkJoules += nextKineticEnergy - stageKineticEnergy;
			stageKineticEnergy = nextKineticEnergy;
		}
		pressureStage.predictDrag(dt);
		diagnosticStage = Stage.GRANULAR_COLLISION_PREVIEW;
		granularMechanicalStage.previewCollision(dt);
		granularMechanicalStage.publishCollisionVelocities();
		diagnosticStage = Stage.PRESSURE_SOLVE;
		pressureStage.solve(dt);
		int pressureIterations = access.lastPressureIterations();
		boolean phaseGeometryPending = substep == 0 && state.pendingPhaseVolume.hasWork();
		diagnosticStage = Stage.PRESSURE_PUBLICATION;
		pressureStage.projectVelocities(dt);
		cellMac.prepareTransport(dt);
		granularMechanicalStage.validateVelocities();
		pressureIterations = access.lastPressureIterations();
		if (captureAudit) {
			nextKineticEnergy = access.gridAndGranularKineticEnergyJoules();
			double wallWork=pressureStage.wallBoundaryWork();
			audit.fixedBoundaryWorkJoules+=wallWork;
			audit.pressureProjectionWorkJoules += nextKineticEnergy - stageKineticEnergy-wallWork;
			stageKineticEnergy = nextKineticEnergy;
		}
		if (phaseGeometryPending) {
			// Phase inventories already share a conservative fixed-volume energy update.
			// Their internal displacement is not external work on the whole thermal system.
			state.pendingPhaseVolume.clear();
		}
		bodyForceStage.enforceSolidFaceVelocities();
		if (captureAudit) {
			nextKineticEnergy = access.gridAndGranularKineticEnergyJoules();
			audit.fixedBoundaryWorkJoules += nextKineticEnergy - stageKineticEnergy;
			stageKineticEnergy = nextKineticEnergy;
		}
		diagnosticStage = Stage.ADVECTION;
		double beforeTransport = captureAudit ? access.carrierKineticEnergyJoules() : 0;
		FluidDomain.LiquidTransportStats transport = cellMac.transport(dt);
		diagnosticStage = Stage.GRANULAR_COLLISION_COMMIT;
		granularMechanicalStage.commitCollision();
		if (captureAudit) audit.volumeTransportTransferJoules += access.carrierKineticEnergyJoules() - beforeTransport;
		diagnosticStage = Stage.COMPLETE;
		return new SubstepOutcome(pressureIterations, granularMechanics, phaseGeometryPending, viscosity, transport);
	}

}

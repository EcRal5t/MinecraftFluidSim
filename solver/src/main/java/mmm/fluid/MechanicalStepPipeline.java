package mmm.fluid;

import mmm.granular.GranularMechanics;


/**
 * Composes one mechanical substep from the explicit solver stages in the established order:
 * shared cell geometry, gas topology, granular mechanics, porous damping, viscous stress,
 * gravity, thermal buoyancy, surface tension, solid-face enforcement, joint finite-inertia pressure
 * projection, collision/position prediction, bounded finite-volume mass and momentum transport
 * and collision commit. The pipeline returns only typed stats and the pending-geometry
 * completion flag; thermal and chemistry axes stay outside.
 */
final class MechanicalStepPipeline {
	enum Stage {
		IDLE, GRID_REBUILD, GAS_TOPOLOGY, CARRIER_PROPERTIES, GRANULAR_PREDICTION,
		POROUS_DAMPING, SOLID_FACE_CONSTRAINT, VISCOSITY, GRAVITY, THERMAL_BUOYANCY,
		SURFACE_TENSION, PRESSURE_SOLVE, PRESSURE_PUBLICATION,
		GRANULAR_COLLISION_PREVIEW, ADVECTION, COMPLETE
	}
	private Stage diagnosticStage = Stage.IDLE;
	private final FluidDomain domain;
	private final GranularMechanicalStage granularMechanicalStage;
	private final BodyForceStage bodyForceStage;
	private final PressureSystem pressure;
	private final LiquidViscosityStage viscosityStage;
	private final CellMacStage cellMac;

	MechanicalStepPipeline(FluidDomain domain) {
		this.domain = domain;
		granularMechanicalStage = domain.granularMechanicalStage;
		bodyForceStage = domain.bodyForceStage;
		pressure = domain.pressureSystem;
		viscosityStage = new LiquidViscosityStage(domain);
		cellMac = domain.cellMacStage;
	}

	record SubstepOutcome(int pressureIterations, GranularMechanics.StepStats granularMechanics,
	                      FluidDomain.ViscousStepStats viscosity) { }

	SubstepOutcome substep(float dt, int substep, boolean captureAudit, MechanicalAuditAccumulator audit) {
		try { return runSubstep(dt, substep, captureAudit, audit); }
		catch (RuntimeException failure) {
			failure.addSuppressed(new IllegalStateException("Mechanical stage=" + diagnosticStage
					+ ", substep=" + substep + ", dt=" + dt + ", domain=" + domain.sizeX + "x" + domain.sizeY + "x" + domain.sizeZ));
			throw failure;
		}
	}

	private void prepareCarrierProperties() {
		if (domain.materialDefinitions != null) {
			domain.carrierPropertiesWorkspace.invalidate();
			domain.domainPhaseInventory.derivedCarrierState();
		}
	}

	private SubstepOutcome runSubstep(float dt, int substep, boolean captureAudit, MechanicalAuditAccumulator audit) {
		diagnosticStage = Stage.GRID_REBUILD;
		domain.domainFlowFields.ensureGrid();
		double stageKineticEnergy = captureAudit ? domain.domainStep.mechanicalGridKineticEnergyJoules() : 0.0;
		cellMac.constrainUnresolvedWallMomentum();
		cellMac.beginDissipation();
		if(captureAudit){
			double constrained=domain.domainStep.mechanicalGridKineticEnergyJoules();
			audit.fixedBoundaryWorkJoules+=constrained-stageKineticEnergy;
			stageKineticEnergy=constrained;
		}
		diagnosticStage = Stage.GAS_TOPOLOGY;
		try {
			domain.gasTopologyCoordinator.step(dt);
		} catch (GasTopologyBlockedException blocked) {
			throw blocked;
		} catch (IllegalArgumentException | IllegalStateException invalidGasThermal) {
			if (domain.thermalState == null) throw invalidGasThermal;
			throw new FluidDomain.GasThermalBlockedException(invalidGasThermal.getMessage(), invalidGasThermal);
		}
		diagnosticStage = Stage.CARRIER_PROPERTIES;
		prepareCarrierProperties();
		diagnosticStage = Stage.GRANULAR_PREDICTION;
		GranularMechanics.StepStats granularMechanics = granularMechanicalStage.predictVelocities(dt);
		double nextKineticEnergy = captureAudit ? domain.domainStep.mechanicalGridKineticEnergyJoules() : 0.0;
		if (captureAudit) {
			audit.gravityWorkJoules += nextKineticEnergy - stageKineticEnergy;
			stageKineticEnergy = nextKineticEnergy;
		}
		// Dampen previously committed flow before body forces so a zero-velocity porous bed retains
		// the full hydrostatic gravity/pressure balance in the shared projection.
		diagnosticStage = Stage.POROUS_DAMPING;
		bodyForceStage.applyGranularPorousDamping(dt);
		if (captureAudit) {
			nextKineticEnergy = domain.domainStep.mechanicalGridKineticEnergyJoules();
			audit.porousBoundaryWorkJoules += nextKineticEnergy - stageKineticEnergy;
			stageKineticEnergy = nextKineticEnergy;
		}
		// Evaluate incoming strain before gravity prediction so hydrostatic flow retains zero viscous dissipation.
		diagnosticStage = Stage.SOLID_FACE_CONSTRAINT;
		bodyForceStage.enforceSolidFaceVelocities();
		if (captureAudit) {
			nextKineticEnergy = domain.domainStep.mechanicalGridKineticEnergyJoules();
			audit.fixedBoundaryWorkJoules += nextKineticEnergy - stageKineticEnergy;
			stageKineticEnergy = nextKineticEnergy;
		}
		diagnosticStage = Stage.VISCOSITY;
		FluidDomain.ViscousStepStats viscosity = viscosityStage.apply(dt);
		if (captureAudit) {
			nextKineticEnergy = domain.domainStep.mechanicalGridKineticEnergyJoules();
			audit.viscousWorkJoules += nextKineticEnergy - stageKineticEnergy;
			stageKineticEnergy = nextKineticEnergy;
		}
		diagnosticStage = Stage.GRAVITY;
		double supportWeightWork=bodyForceStage.applyGravity(dt);
		if (captureAudit) {
			nextKineticEnergy = domain.domainStep.mechanicalGridKineticEnergyJoules();
			audit.gravityWorkJoules += nextKineticEnergy - stageKineticEnergy-supportWeightWork;
			audit.fixedBoundaryWorkJoules += supportWeightWork;
			stageKineticEnergy = nextKineticEnergy;
		}
		diagnosticStage = Stage.THERMAL_BUOYANCY;
		bodyForceStage.applyThermalBuoyancy(dt);
		if (captureAudit) {
			nextKineticEnergy = domain.domainStep.mechanicalGridKineticEnergyJoules();
			audit.thermalBuoyancyWorkJoules += nextKineticEnergy - stageKineticEnergy;
			stageKineticEnergy = nextKineticEnergy;
		}
		diagnosticStage = Stage.SURFACE_TENSION;
		bodyForceStage.applyLiquidPairSurfaceTensionForce(dt);
		if (captureAudit) {
			nextKineticEnergy = domain.domainStep.mechanicalGridKineticEnergyJoules();
			audit.surfaceTensionWorkJoules += nextKineticEnergy - stageKineticEnergy;
			stageKineticEnergy = nextKineticEnergy;
		}
		bodyForceStage.enforceSolidFaceVelocities();
		if (captureAudit) {
			nextKineticEnergy = domain.domainStep.mechanicalGridKineticEnergyJoules();
			audit.fixedBoundaryWorkJoules += nextKineticEnergy - stageKineticEnergy;
			stageKineticEnergy = nextKineticEnergy;
		}
		diagnosticStage = Stage.PRESSURE_SOLVE;
		pressure.solve(dt);
		boolean phaseGeometryPending = substep == 0 && domain.pendingPhaseVolume.hasWork();
		diagnosticStage = Stage.PRESSURE_PUBLICATION;
		pressure.projectVelocities(dt);
		if(captureAudit){
			nextKineticEnergy=domain.domainStep.mechanicalGridKineticEnergyJoules();
			audit.pressureProjectionWorkJoules+=nextKineticEnergy-stageKineticEnergy;stageKineticEnergy=nextKineticEnergy;
		}
		diagnosticStage = Stage.GRANULAR_COLLISION_PREVIEW;
		granularMechanicalStage.previewCollision(dt);
		granularMechanicalStage.publishCollisionVelocities();
		granularMechanicalStage.validateVelocities();
		int pressureIterations = domain.lastPressureIterations;
		if (captureAudit) {
			nextKineticEnergy = domain.domainStep.mechanicalGridKineticEnergyJoules();
			audit.contactWorkJoules += nextKineticEnergy - stageKineticEnergy;
			stageKineticEnergy = nextKineticEnergy;
		}
		if (phaseGeometryPending) {
			// Phase inventories already share a conservative fixed-volume energy update.
			// Their internal displacement is not external work on the whole thermal system.
			domain.pendingPhaseVolume.clear();
		}
		bodyForceStage.enforceSolidFaceVelocities();
		if (captureAudit) {
			nextKineticEnergy = domain.domainStep.mechanicalGridKineticEnergyJoules();
			audit.fixedBoundaryWorkJoules += nextKineticEnergy - stageKineticEnergy;
			stageKineticEnergy = nextKineticEnergy;
		}
		diagnosticStage = Stage.ADVECTION;
		double beforeTransport = captureAudit ? domain.domainStep.mechanicalCarrierKineticEnergyJoules() : 0;
		cellMac.transport(dt);
		if (captureAudit) audit.volumeTransportTransferJoules += domain.domainStep.mechanicalCarrierKineticEnergyJoules() - beforeTransport;
		diagnosticStage = Stage.COMPLETE;
		return new SubstepOutcome(pressureIterations, granularMechanics, viscosity);
	}

}

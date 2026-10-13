package mmm.fluid;

import mmm.granular.GranularMechanics;
import mmm.thermal.ThermalDomainState;
import mmm.units.SignedDyadic;

import java.util.Arrays;

/**
 * Composes one mechanical substep from the explicit solver stages in the established order:
 * gas topology step, particle-to-grid rebuild, granular mechanics, porous damping, viscous stress,
 * gravity, thermal buoyancy, surface tension, solid-face enforcement, pressure solve, the pending
 * phase-geometry synchronization, velocity projection, grid-to-particle handoff, advection
 * and collision commit. The pipeline returns only typed stats and the pending-geometry
 * completion flag; thermal/chemistry/reseed axes stay outside.
 */
final class MechanicalStepPipeline {
	record Workspace(
			GasTopologyCoordinator gasTopology, MacVelocityGrid mac,
			SignedDyadic[] pendingPhaseCellVolume, FluidSolverConfig config
	) { }

	interface Access {
		void ensureGrid();
		void prepareCarrierProperties();
		double projectedCourant(float dt);
		double gridAndGranularKineticEnergyJoules();
		double carrierKineticEnergyJoules();
		double granularKineticEnergyJoules();
		int lastPressureIterations();
		ThermalDomainState thermalState();
		boolean hasPendingPhaseVolumeWork();
		boolean markerPressureActive();
		double markerPressurePredictorKineticEnergyJoules();
	}

	private final Workspace state;
	private final Access access;
	private final GranularMechanicalStage granularMechanicalStage;
	private final BodyForceStage bodyForceStage;
	private final PressureProjectionStage pressureStage;
	private final LiquidViscosityStage viscosityStage;
	private final GridToParticleStage gridToParticleStage;
	private final ParticleAdvectionStage particleAdvectionStage;

	MechanicalStepPipeline(
			Workspace state, Access access, GranularMechanicalStage granularMechanicalStage,
			BodyForceStage bodyForceStage, PressureProjectionStage pressureStage, LiquidViscosityStage viscosityStage,
			GridToParticleStage gridToParticleStage, ParticleAdvectionStage particleAdvectionStage
	) {
		this.state = state;
		this.access = access;
		this.granularMechanicalStage = granularMechanicalStage;
		this.bodyForceStage = bodyForceStage;
		this.pressureStage = pressureStage;
		this.viscosityStage = viscosityStage;
		this.gridToParticleStage = gridToParticleStage;
		this.particleAdvectionStage = particleAdvectionStage;
	}

	record SubstepOutcome(int pressureIterations, GranularMechanics.StepStats granularMechanics,
	                      boolean pendingWorkConsumed, FluidDomain.ViscousStepStats viscosity,
	                      FluidDomain.LiquidTransportStats liquidTransport) { }

	SubstepOutcome substep(float dt, int substep, boolean captureAudit, MechanicalAuditAccumulator audit) {
		access.ensureGrid();
		double stageKineticEnergy = captureAudit ? access.gridAndGranularKineticEnergyJoules() : 0.0;
		if (captureAudit) audit.particleToGridTransferJoules +=
				stageKineticEnergy - access.carrierKineticEnergyJoules();
		try {
			state.gasTopology.step(dt);
		} catch (GasTopologyBlockedException blocked) {
			throw blocked;
		} catch (IllegalArgumentException | IllegalStateException invalidGasThermal) {
			if (access.thermalState() == null) throw invalidGasThermal;
			throw new FluidDomain.GasThermalBlockedException(invalidGasThermal.getMessage(), invalidGasThermal);
		}
		access.prepareCarrierProperties();
		MacVelocityGrid mac = state.mac;
		System.arraycopy(mac.velocityX, 0, mac.p2gVelocityX, 0, mac.velocityX.length);
		System.arraycopy(mac.velocityY, 0, mac.p2gVelocityY, 0, mac.velocityY.length);
		System.arraycopy(mac.velocityZ, 0, mac.p2gVelocityZ, 0, mac.velocityZ.length);
		GranularMechanics.StepStats granularMechanics = granularMechanicalStage.predictVelocities(dt);
		double nextKineticEnergy = captureAudit ? access.gridAndGranularKineticEnergyJoules() : 0.0;
		if (captureAudit) {
			audit.gravityWorkJoules += nextKineticEnergy - stageKineticEnergy;
			stageKineticEnergy = nextKineticEnergy;
		}
		// Dampen previously committed flow before body forces so a zero-velocity porous bed retains
		// the full hydrostatic gravity/pressure balance in the shared projection.
		bodyForceStage.applyGranularPorousDamping(dt);
		if (captureAudit) {
			nextKineticEnergy = access.gridAndGranularKineticEnergyJoules();
			audit.porousBoundaryWorkJoules += nextKineticEnergy - stageKineticEnergy;
			stageKineticEnergy = nextKineticEnergy;
		}
		// Evaluate incoming strain before gravity prediction so hydrostatic flow retains zero viscous dissipation.
		bodyForceStage.enforceSolidFaceVelocities();
		if (captureAudit) {
			nextKineticEnergy = access.gridAndGranularKineticEnergyJoules();
			audit.fixedBoundaryWorkJoules += nextKineticEnergy - stageKineticEnergy;
			stageKineticEnergy = nextKineticEnergy;
		}
		FluidDomain.ViscousStepStats viscosity = viscosityStage.apply(dt);
		if (captureAudit) {
			nextKineticEnergy = access.gridAndGranularKineticEnergyJoules();
			audit.viscousWorkJoules += nextKineticEnergy - stageKineticEnergy;
			stageKineticEnergy = nextKineticEnergy;
		}
		bodyForceStage.applyGravity(dt);
		if (captureAudit) {
			nextKineticEnergy = access.gridAndGranularKineticEnergyJoules();
			audit.gravityWorkJoules += nextKineticEnergy - stageKineticEnergy;
			stageKineticEnergy = nextKineticEnergy;
		}
		bodyForceStage.applyThermalBuoyancy(dt);
		if (captureAudit) {
			nextKineticEnergy = access.gridAndGranularKineticEnergyJoules();
			audit.thermalBuoyancyWorkJoules += nextKineticEnergy - stageKineticEnergy;
			stageKineticEnergy = nextKineticEnergy;
		}
		bodyForceStage.applyLiquidGasSurfaceTensionForce(dt);
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
		gridToParticleStage.capturePreProjectionEnergy();
		pressureStage.solve(dt);
		boolean markerPressure = access.markerPressureActive();
		if (captureAudit && markerPressure) {
			double predictorEnergy = access.markerPressurePredictorKineticEnergyJoules();
			audit.gridToParticleTransferJoules += predictorEnergy - stageKineticEnergy;
			stageKineticEnergy = predictorEnergy;
		}
		int pressureIterations = access.lastPressureIterations();
		boolean phaseGeometryPending = substep == 0 && access.hasPendingPhaseVolumeWork();
		pressureStage.projectVelocities(dt);
		if (CellLiquidVolumeTransport.enabled()) gridToParticleStage.prepareAdvectionCells();
		validateProjectedCourant(dt);
		granularMechanicalStage.previewCollision(dt);
		granularMechanicalStage.validateVelocities();
		if (captureAudit) {
			nextKineticEnergy = markerPressure ? access.carrierKineticEnergyJoules() : access.gridAndGranularKineticEnergyJoules();
			audit.pressureProjectionWorkJoules += nextKineticEnergy - stageKineticEnergy;
			stageKineticEnergy = nextKineticEnergy;
		}
		if (phaseGeometryPending) {
			// Phase inventories already share a conservative fixed-volume energy update.
			// Their internal displacement is not external work on the whole thermal system.
			Arrays.fill(state.pendingPhaseCellVolume, SignedDyadic.ZERO);
		}
		bodyForceStage.enforceSolidFaceVelocities();
		if (captureAudit) {
			nextKineticEnergy = markerPressure ? access.carrierKineticEnergyJoules() : access.gridAndGranularKineticEnergyJoules();
			audit.fixedBoundaryWorkJoules += nextKineticEnergy - stageKineticEnergy;
			stageKineticEnergy = nextKineticEnergy;
		}
		gridToParticleStage.extrapolateAndTransfer();
		// The extension has already classified this unchanged geometry; recheck its new velocities.
		validateProjectedCourant(dt);
		double particleKineticEnergy = captureAudit ? access.carrierKineticEnergyJoules() : 0.0;
		if (captureAudit) audit.gridToParticleTransferJoules +=
				particleKineticEnergy - stageKineticEnergy;
		double granularCollisionBefore = captureAudit ? access.granularKineticEnergyJoules() : 0;
		particleAdvectionStage.advect(dt);
		granularMechanicalStage.commitCollision();
		if (captureAudit) {
			double transfer = access.carrierKineticEnergyJoules() - particleKineticEnergy;
			if (particleAdvectionStage.usedConservativeVolume()) {
				double collision = particleAdvectionStage.conservativeCollisionWorkJoules()
						+ access.granularKineticEnergyJoules() - granularCollisionBefore;
				audit.volumeTransportTransferJoules += transfer - collision;
				audit.particleCollisionWorkJoules += collision;
			}
			else audit.particleCollisionWorkJoules += transfer;
		}
		return new SubstepOutcome(pressureIterations, granularMechanics, phaseGeometryPending, viscosity, particleAdvectionStage.transportStats());
	}

	/** Pressure can accelerate a resting liquid beyond the initial marker-based step estimate. */
	private void validateProjectedCourant(float dt) {
		double courant = access.projectedCourant(dt);
		if (courant > 1 + 8 * Math.ulp(1.0F)) {
			throw new MechanicalTimeRefinement((int) Math.ceil(courant * state.config.timeStep() / dt));
		}
	}

}

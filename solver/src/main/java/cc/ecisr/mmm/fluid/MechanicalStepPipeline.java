package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.granular.GranularMechanics;
import cc.ecisr.mmm.thermal.ThermalCarrierContext;
import cc.ecisr.mmm.thermal.ThermalDomainState;
import cc.ecisr.mmm.units.SignedDyadic;

import java.util.Arrays;

/**
 * Composes one mechanical substep from the explicit solver stages in the established order:
 * gas topology step, particle-to-grid rebuild, granular mechanics, porous damping, gravity,
 * thermal buoyancy, surface tension, solid-face enforcement, pressure solve, the pending
 * phase-work plan and consumption, velocity projection, grid-to-particle handoff, advection
 * and collision commit. The pipeline returns only typed stats and the pending-work
 * consumption flag; thermal/chemistry/reseed axes stay outside.
 */
final class MechanicalStepPipeline {
	record Workspace(
			GasTopologyCoordinator gasTopology, MacVelocityGrid mac,
			SignedDyadic[] pendingPhaseCellVolume, PressureProjectionWorkspace projection,
			FluidSolverConfig config, ThermalCarrierContext thermalMarkers
	) { }

	interface Access {
		void ensureGrid();
		double gridKineticEnergyJoules();
		double particleKineticEnergyJoules();
		int lastPressureIterations();
		ThermalDomainState thermalState();
		boolean hasPendingPhaseVolumeWork();
		float pressureReference();
	}

	private final Workspace state;
	private final Access access;
	private final GranularMechanicalStage granularMechanicalStage;
	private final BodyForceStage bodyForceStage;
	private final PressureProjectionStage pressureStage;
	private final GridToParticleStage gridToParticleStage;
	private final ParticleAdvectionStage particleAdvectionStage;

	MechanicalStepPipeline(
			Workspace state, Access access, GranularMechanicalStage granularMechanicalStage,
			BodyForceStage bodyForceStage, PressureProjectionStage pressureStage,
			GridToParticleStage gridToParticleStage, ParticleAdvectionStage particleAdvectionStage
	) {
		this.state = state;
		this.access = access;
		this.granularMechanicalStage = granularMechanicalStage;
		this.bodyForceStage = bodyForceStage;
		this.pressureStage = pressureStage;
		this.gridToParticleStage = gridToParticleStage;
		this.particleAdvectionStage = particleAdvectionStage;
	}

	record SubstepOutcome(int pressureIterations, GranularMechanics.StepStats granularMechanics,
	                      boolean pendingWorkConsumed) { }

	SubstepOutcome substep(float dt, int substep, boolean captureAudit, MechanicalAuditAccumulator audit) {
		access.ensureGrid();
		double stageKineticEnergy = captureAudit ? access.gridKineticEnergyJoules() : 0.0;
		if (captureAudit) audit.particleToGridTransferJoules +=
				stageKineticEnergy - access.particleKineticEnergyJoules();
		try {
			state.gasTopology.step(dt);
		} catch (GasTopologyBlockedException blocked) {
			throw blocked;
		} catch (IllegalArgumentException | IllegalStateException invalidGasThermal) {
			if (access.thermalState() == null) throw invalidGasThermal;
			throw new FluidDomain.GasThermalBlockedException(invalidGasThermal.getMessage(), invalidGasThermal);
		}
		MacVelocityGrid mac = state.mac;
		System.arraycopy(mac.velocityX, 0, mac.p2gVelocityX, 0, mac.velocityX.length);
		System.arraycopy(mac.velocityY, 0, mac.p2gVelocityY, 0, mac.velocityY.length);
		System.arraycopy(mac.velocityZ, 0, mac.p2gVelocityZ, 0, mac.velocityZ.length);
		GranularMechanics.StepStats granularMechanics = granularMechanicalStage.predictVelocities(dt);
		double nextKineticEnergy = captureAudit ? access.gridKineticEnergyJoules() : 0.0;
		if (captureAudit) {
			audit.granularCouplingWorkJoules += nextKineticEnergy - stageKineticEnergy;
			stageKineticEnergy = nextKineticEnergy;
		}
		// Dampen previously committed flow before body forces so a zero-velocity porous bed retains
		// the full hydrostatic gravity/pressure balance in the shared projection.
		bodyForceStage.applyGranularPorousDamping(dt);
		if (captureAudit) {
			nextKineticEnergy = access.gridKineticEnergyJoules();
			audit.porousBoundaryWorkJoules += nextKineticEnergy - stageKineticEnergy;
			stageKineticEnergy = nextKineticEnergy;
		}
		bodyForceStage.applyGravity(dt);
		if (captureAudit) {
			nextKineticEnergy = access.gridKineticEnergyJoules();
			audit.gravityWorkJoules += nextKineticEnergy - stageKineticEnergy;
			stageKineticEnergy = nextKineticEnergy;
		}
		bodyForceStage.applyThermalBuoyancy(dt);
		if (captureAudit) {
			nextKineticEnergy = access.gridKineticEnergyJoules();
			audit.thermalBuoyancyWorkJoules += nextKineticEnergy - stageKineticEnergy;
			stageKineticEnergy = nextKineticEnergy;
		}
		bodyForceStage.applyLiquidGasSurfaceTensionForce(dt);
		bodyForceStage.applyLiquidSolidSurfaceEnergyForce(dt);
		bodyForceStage.applyLiquidPairSurfaceTensionForce(dt);
		if (captureAudit) {
			nextKineticEnergy = access.gridKineticEnergyJoules();
			audit.surfaceTensionWorkJoules += nextKineticEnergy - stageKineticEnergy;
			stageKineticEnergy = nextKineticEnergy;
		}
		bodyForceStage.enforceSolidFaceVelocities();
		if (captureAudit) {
			nextKineticEnergy = access.gridKineticEnergyJoules();
			audit.fixedBoundaryWorkJoules += nextKineticEnergy - stageKineticEnergy;
			stageKineticEnergy = nextKineticEnergy;
		}
		gridToParticleStage.capturePreProjectionEnergy();
		pressureStage.solve(dt);
		int pressureIterations = access.lastPressureIterations();
		ThermalDomainState.PhaseMechanicalWorkPlan phaseWork = null;
		if (substep == 0 && access.thermalState() != null && access.hasPendingPhaseVolumeWork()) try {
			phaseWork = access.thermalState().planPhaseMechanicalWork(
					state.pendingPhaseCellVolume, state.projection.pressure, access.pressureReference(),
					state.config.cellSize(), state.thermalMarkers);
		} catch (IllegalArgumentException invalidPendingWork) {
			throw new FluidDomain.ThermalPhaseBlockedException(
					"Pending phase mechanical work cannot be consumed: " + invalidPendingWork.getMessage(),
					invalidPendingWork);
		}
		pressureStage.projectVelocities(dt);
		granularMechanicalStage.previewCollision(dt);
		granularMechanicalStage.validateVelocities();
		if (captureAudit) {
			nextKineticEnergy = access.gridKineticEnergyJoules();
			audit.pressureProjectionWorkJoules += nextKineticEnergy - stageKineticEnergy;
			stageKineticEnergy = nextKineticEnergy;
		}
		if (phaseWork != null) {
			access.thermalState().commitPhaseMechanicalWork(phaseWork, state.thermalMarkers);
			Arrays.fill(state.pendingPhaseCellVolume, SignedDyadic.ZERO);
		}
		bodyForceStage.enforceSolidFaceVelocities();
		if (captureAudit) {
			nextKineticEnergy = access.gridKineticEnergyJoules();
			audit.fixedBoundaryWorkJoules += nextKineticEnergy - stageKineticEnergy;
			stageKineticEnergy = nextKineticEnergy;
		}
		gridToParticleStage.extrapolateAndTransfer();
		double particleKineticEnergy = captureAudit ? access.particleKineticEnergyJoules() : 0.0;
		if (captureAudit) audit.gridToParticleTransferJoules +=
				particleKineticEnergy - stageKineticEnergy;
		particleAdvectionStage.advect(dt);
		if (captureAudit) audit.particleCollisionWorkJoules +=
				access.particleKineticEnergyJoules() - particleKineticEnergy;
		granularMechanicalStage.commitCollision();
		return new SubstepOutcome(pressureIterations, granularMechanics, phaseWork != null);
	}
}

package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.granular.GranularMechanics;
import cc.ecisr.mmm.thermal.SignedDyadic;

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
	private final FlipFluidDomain domain;
	private final GranularMechanicalStage granularMechanicalStage;
	private final BodyForceStage bodyForceStage;
	private final PressureProjectionStage pressureStage;
	private final GridToParticleStage gridToParticleStage;
	private final ParticleAdvectionStage particleAdvectionStage;

	MechanicalStepPipeline(
			FlipFluidDomain domain, GranularMechanicalStage granularMechanicalStage,
			BodyForceStage bodyForceStage, PressureProjectionStage pressureStage,
			GridToParticleStage gridToParticleStage, ParticleAdvectionStage particleAdvectionStage
	) {
		this.domain = domain;
		this.granularMechanicalStage = granularMechanicalStage;
		this.bodyForceStage = bodyForceStage;
		this.pressureStage = pressureStage;
		this.gridToParticleStage = gridToParticleStage;
		this.particleAdvectionStage = particleAdvectionStage;
	}

	record SubstepOutcome(int pressureIterations, GranularMechanics.StepStats granularMechanics,
	                      boolean pendingWorkConsumed) { }

	SubstepOutcome substep(float dt, int substep, boolean captureAudit, MechanicalAuditAccumulator audit) {
		domain.ensureGrid();
		double stageKineticEnergy = captureAudit ? domain.gridKineticEnergyJoules() : 0.0;
		if (captureAudit) audit.particleToGridTransferJoules +=
				stageKineticEnergy - domain.particleKineticEnergyJoules();
		try {
			domain.gasTopologyCoordinator.step(dt);
		} catch (GasTopologyBlockedException blocked) {
			throw blocked;
		} catch (IllegalArgumentException | IllegalStateException invalidGasThermal) {
			if (domain.thermalState == null) throw invalidGasThermal;
			throw new FlipFluidDomain.GasThermalBlockedException(invalidGasThermal.getMessage(), invalidGasThermal);
		}
		System.arraycopy(domain.mac.velocityX, 0, domain.mac.p2gVelocityX, 0, domain.mac.velocityX.length);
		System.arraycopy(domain.mac.velocityY, 0, domain.mac.p2gVelocityY, 0, domain.mac.velocityY.length);
		System.arraycopy(domain.mac.velocityZ, 0, domain.mac.p2gVelocityZ, 0, domain.mac.velocityZ.length);
		GranularMechanics.StepStats granularMechanics = granularMechanicalStage.stepVelocities(dt);
		double nextKineticEnergy = captureAudit ? domain.gridKineticEnergyJoules() : 0.0;
		if (captureAudit) {
			audit.granularCouplingWorkJoules += nextKineticEnergy - stageKineticEnergy;
			stageKineticEnergy = nextKineticEnergy;
		}
		// Dampen previously committed flow before body forces so a zero-velocity porous bed retains
		// the full hydrostatic gravity/pressure balance in the shared projection.
		bodyForceStage.applyGranularPorousDamping(dt);
		if (captureAudit) {
			nextKineticEnergy = domain.gridKineticEnergyJoules();
			audit.porousBoundaryWorkJoules += nextKineticEnergy - stageKineticEnergy;
			stageKineticEnergy = nextKineticEnergy;
		}
		bodyForceStage.applyGravity(dt);
		if (captureAudit) {
			nextKineticEnergy = domain.gridKineticEnergyJoules();
			audit.gravityWorkJoules += nextKineticEnergy - stageKineticEnergy;
			stageKineticEnergy = nextKineticEnergy;
		}
		bodyForceStage.applyThermalBuoyancy(dt);
		if (captureAudit) {
			nextKineticEnergy = domain.gridKineticEnergyJoules();
			audit.thermalBuoyancyWorkJoules += nextKineticEnergy - stageKineticEnergy;
			stageKineticEnergy = nextKineticEnergy;
		}
		bodyForceStage.applyLiquidPairSurfaceTensionForce(dt);
		if (captureAudit) {
			nextKineticEnergy = domain.gridKineticEnergyJoules();
			audit.surfaceTensionWorkJoules += nextKineticEnergy - stageKineticEnergy;
			stageKineticEnergy = nextKineticEnergy;
		}
		bodyForceStage.enforceSolidFaceVelocities();
		if (captureAudit) {
			nextKineticEnergy = domain.gridKineticEnergyJoules();
			audit.fixedBoundaryWorkJoules += nextKineticEnergy - stageKineticEnergy;
			stageKineticEnergy = nextKineticEnergy;
		}
		pressureStage.solve(dt);
		int pressureIterations = domain.lastPressureIterations;
		FlipThermalState.PhaseMechanicalWorkPlan phaseWork = null;
		if (substep == 0 && domain.thermalState != null && domain.hasPendingPhaseVolumeWork()) try {
			phaseWork = domain.thermalState.planPhaseMechanicalWork(
					domain.pendingPhaseCellVolume, domain.projection.pressure, domain.pressureReference,
					domain.config.cellSize(), domain.thermalMarkerContext());
		} catch (IllegalArgumentException invalidPendingWork) {
			throw new FlipFluidDomain.ThermalPhaseBlockedException(
					"Pending phase mechanical work cannot be consumed: " + invalidPendingWork.getMessage(),
					invalidPendingWork);
		}
		pressureStage.projectVelocities(dt);
		if (captureAudit) {
			nextKineticEnergy = domain.gridKineticEnergyJoules();
			audit.pressureProjectionWorkJoules += nextKineticEnergy - stageKineticEnergy;
			stageKineticEnergy = nextKineticEnergy;
		}
		if (phaseWork != null) {
			domain.thermalState.commitPhaseMechanicalWork(phaseWork, domain.thermalMarkerContext());
			Arrays.fill(domain.pendingPhaseCellVolume, SignedDyadic.ZERO);
		}
		bodyForceStage.enforceSolidFaceVelocities();
		if (captureAudit) {
			nextKineticEnergy = domain.gridKineticEnergyJoules();
			audit.fixedBoundaryWorkJoules += nextKineticEnergy - stageKineticEnergy;
			stageKineticEnergy = nextKineticEnergy;
		}
		gridToParticleStage.extrapolateAndTransfer();
		double particleKineticEnergy = captureAudit ? domain.particleKineticEnergyJoules() : 0.0;
		if (captureAudit) audit.gridToParticleTransferJoules +=
				particleKineticEnergy - stageKineticEnergy;
		particleAdvectionStage.advect(dt);
		if (captureAudit) audit.particleCollisionWorkJoules +=
				domain.particleKineticEnergyJoules() - particleKineticEnergy;
		granularMechanicalStage.commitCollision();
		return new SubstepOutcome(pressureIterations, granularMechanics, phaseWork != null);
	}
}

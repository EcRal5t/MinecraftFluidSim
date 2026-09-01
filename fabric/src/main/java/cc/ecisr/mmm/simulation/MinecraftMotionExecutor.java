package cc.ecisr.mmm.simulation;

import cc.ecisr.mmm.application.RegionApplicationService;
import cc.ecisr.mmm.application.command.MotionStepCommand;
import cc.ecisr.mmm.application.result.MotionStepResult;
import cc.ecisr.mmm.world.MovingBodyFluidCoupling;
import cc.ecisr.mmm.worldprofile.WorldCapability;

import java.util.List;
import java.util.Objects;

/** Executes and accounts one complete logical motion transaction for a live Minecraft region. */
final class MinecraftMotionExecutor {
	private final RegionApplicationService application;
	private final CommittedWorldRegistries registries;
	private final MinecraftRuntimePolicy policy;

	MinecraftMotionExecutor(
			RegionApplicationService application,
			CommittedWorldRegistries registries,
			MinecraftRuntimePolicy policy
	) {
		this.application = Objects.requireNonNull(application, "Region application service");
		this.registries = Objects.requireNonNull(registries, "Committed world registries");
		this.policy = Objects.requireNonNull(policy, "Runtime policy");
	}

	MotionStepResult step(FluidRegion region, List<MovingBodyFluidCoupling.Request> movingBodies) {
		boolean phaseChange = registries.hasCapability(WorldCapability.PHASECHANGE);
		return step(region, movingBodies, phaseChange);
	}

	/** Advances one authoritative mechanical projection without creating another phase-work lien. */
	MotionStepResult settlePendingPhaseWork(FluidRegion region) {
		if (!region.domain().hasPendingPhaseVolumeWork()) return null;
		long start = System.nanoTime();
		MotionStepResult result = application.stepMotion(MotionStepCommand.pendingPhaseSettlement(
				region.id(), region.solverGeneration()));
		if (!result.accepted()) throw new IllegalStateException(
				"Motion step application rejected synchronous phase settlement: " + result.status());
		region.recordStep(result.stats(), (System.nanoTime() - start) / 1_000_000.0);
		if (region.domain().hasPendingPhaseVolumeWork()) {
			throw new cc.ecisr.mmm.fluid.FluidDomain.ThermalPhaseBlockedException(
					"Mechanical settlement retained pending phase work");
		}
		return result;
	}

	private MotionStepResult step(
			FluidRegion region, List<MovingBodyFluidCoupling.Request> movingBodies, boolean phaseChange
	) {
		long start = System.nanoTime();
		MotionStepResult result = application.stepMotion(new MotionStepCommand(
				region.id(), region.solverGeneration(), movingBodies, phaseChange,
				phaseChange ? policy.maximumPhaseOperationsPerStep() : 0, false));
		if (!result.accepted()) throw new IllegalStateException(
				"Motion step application rejected synchronous region state: " + result.status());
		region.recordStep(result.stats(), (System.nanoTime() - start) / 1_000_000.0);
		return result;
	}
}

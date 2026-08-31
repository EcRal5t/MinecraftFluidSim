package cc.ecisr.mmm.simulation;

import cc.ecisr.mmm.MmmMod;
import cc.ecisr.mmm.application.RegionApplicationService;
import cc.ecisr.mmm.application.RegionRuntime;
import cc.ecisr.mmm.application.RegionTickUseCase;
import cc.ecisr.mmm.application.command.MotionStepCommand;
import cc.ecisr.mmm.application.command.ThermalStepCommand;
import cc.ecisr.mmm.chemistry.ChemicalReactionEngine;
import cc.ecisr.mmm.fluid.FluidDomain;
import cc.ecisr.mmm.fluid.GasTopologyBlockedException;
import cc.ecisr.mmm.granular.GranularDepositDynamics;
import cc.ecisr.mmm.granular.GranularMechanics;
import cc.ecisr.mmm.granular.GranularPhaseTransferEngine;
import cc.ecisr.mmm.thermal.ThermalAuditLedger;
import cc.ecisr.mmm.world.MovingBodyFluidCoupling;
import cc.ecisr.mmm.worldprofile.WorldCapability;
import cc.ecisr.mmm.worldprofile.WorldCapabilityProfile;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Objects;

/** Executes one available region tick and maps typed solver failures back to Minecraft state. */
final class MinecraftRegionTick {
	private static final int SNAPSHOT_INTERVAL_TICKS = 4;
	private static final int HYDROSTATIC_QUIET_STEPS = 200;
	private static final int THERMAL_QUIET_STEPS = 200;
	private static final float HYDROSTATIC_MAX_MARKER_SPEED = 2.5E-3F;
	private static final RegionTickUseCase.Policy POLICY = RegionTickUseCase.Policy.production();
	private static final GranularDepositDynamics.Stats NO_GRANULAR_TRANSITION =
			new GranularDepositDynamics.Stats(0, 0, 0, 0, 0);

	private final RegionTickUseCase useCase;
	private final MinecraftRegionInputs inputs;
	private final FluidSnapshotPublisher snapshots;
	private final WorldCapabilityProfile profile;
	private final int maximumPhaseOperationsPerStep;

	MinecraftRegionTick(
			RegionApplicationService application,
			MinecraftRegionInputs inputs,
			FluidSnapshotPublisher snapshots,
			WorldCapabilityProfile profile,
			int maximumPhaseOperationsPerStep
	) {
		this.useCase = new RegionTickUseCase(Objects.requireNonNull(application, "Region application"), POLICY);
		this.inputs = Objects.requireNonNull(inputs, "Minecraft region inputs");
		this.snapshots = Objects.requireNonNull(snapshots, "Snapshot publisher");
		this.profile = Objects.requireNonNull(profile, "World capability profile");
		if (maximumPhaseOperationsPerStep < 0) {
			throw new IllegalArgumentException("Phase-operation budget must be non-negative");
		}
		this.maximumPhaseOperationsPerStep = maximumPhaseOperationsPerStep;
	}

	void finishServerTick() { inputs.finishServerTick(); }

	void tickAvailable(ServerLevel level, FluidRegion region) {
		long tick = region.incrementTick();
		boolean collisionsChanged = false;
		if (inputs.boundariesDue(tick)) {
			try { collisionsChanged = refreshRegionInputs(level, region).changedCells() > 0; }
			catch (FluidDomain.CollisionEditBlockedException blocked) {
				blockCollisionEdit(level, region, blocked);
				return;
			}
		}
		MinecraftRegionInputs.TickInputs frozen = inputs.captureTick(level, region, tick,
				profile.hasCapability(WorldCapability.THERMAL));
		int affectedEntities = frozen.affectedEntities();
		RegionRuntime tickRuntime = frozen.runtime();
		RegionTickUseCase.Plan tickPlan = useCase.plan(tickRuntime);
		if (tickPlan.axis() == RegionTickUseCase.Axis.NONE) {
			if (collisionsChanged) snapshots.sendToLevel(level, region);
			return;
		}
		boolean motionStepDue = tickPlan.axis() == RegionTickUseCase.Axis.MOTION;
		ThermalAuditLedger thermalBefore = profile.hasCapability(WorldCapability.THERMAL)
				? region.domain().thermalAudit() : null;
		FluidDomain.ThermalAxisStats completedThermalAxis = null;
		GranularDepositDynamics.Stats completedGranularTransition = NO_GRANULAR_TRANSITION;
		boolean autoPause = false;
		try {
			boolean phaseChange = profile.hasCapability(WorldCapability.PHASECHANGE);
			int phaseBudget = phaseChange ? maximumPhaseOperationsPerStep : 0;
			if (motionStepDue) {
				region.wakeThermal(ThermalActivity.WakeReason.CARRIER_MOTION);
				RegionTickUseCase.MotionOutcome outcome = useCase.executeMotion(tickPlan,
						new MotionStepCommand(region.id(), region.solverGeneration(), frozen.movingBodies(),
								phaseChange, phaseBudget, false));
				if (!outcome.result().accepted()) throw new IllegalStateException(
						"Motion step application rejected synchronous region state: " + outcome.result().status());
				region.recordStep(outcome.result().stats(), outcome.elapsedMilliseconds());
				applyRuntime(region, tickRuntime, outcome.runtime(), outcome.elapsedMilliseconds());
				autoPause = outcome.autoPause();
				completedThermalAxis = outcome.result().stats().thermalAxis();
				completedGranularTransition = outcome.result().stats().granularTransition();
				MovingBodyFluidCoupling.Result coupling = outcome.result().movingBodyCoupling();
				region.setLastEntityCoupling(new FluidRegion.EntityCouplingStats(affectedEntities,
						coupling.impulseXKilogramMetresPerSecond(), coupling.impulseYKilogramMetresPerSecond(),
						coupling.impulseZKilogramMetresPerSecond()));
			} else {
				RegionTickUseCase.ThermalOutcome outcome = useCase.executeThermal(tickPlan,
						new ThermalStepCommand(region.id(), region.solverGeneration(), phaseChange, phaseBudget));
				if (!outcome.result().accepted()) throw new IllegalStateException(
						"Thermal step application rejected synchronous region state: " + outcome.result().status());
				region.recordThermalStep(outcome.elapsedMilliseconds());
				applyRuntime(region, tickRuntime, outcome.runtime(), outcome.elapsedMilliseconds());
				autoPause = outcome.autoPause();
				completedThermalAxis = outcome.result().stats();
			}
		} catch (RuntimeException failure) {
			if (!blockStepFailure(level, region, failure)) throw failure;
			return;
		}
		if (profile.hasCapability(WorldCapability.THERMAL)) {
			observeThermalStep(region, thermalBefore, motionStepDue,
					completedThermalAxis, completedGranularTransition);
		}
		FluidDomain.StepStats stats = region.lastStats();
		float residualLimit = Math.max(1.0E-5F, region.domain().config().pressureTolerance() * 4.0F);
		boolean hydrostaticCandidate = !collisionsChanged && affectedEntities == 0
				&& region.domain().maximumParticleSpeed() <= HYDROSTATIC_MAX_MARKER_SPEED
				&& region.domain().granularReadyForHydrostaticSleep()
				&& stats != null && stats.pressureResidual() <= residualLimit;
		if (motionStepDue && region.lifecycle().observeCompletedStep(hydrostaticCandidate, HYDROSTATIC_QUIET_STEPS)) {
			float exactVolume = region.domain().totalVolume();
			int markerCount = region.domain().particleCount();
			region.domain().commitHydrostaticSleep();
			if (markerCount != region.domain().particleCount() || exactVolume != region.domain().totalVolume()) {
				throw new IllegalStateException("Hydrostatic sleep changed the exact marker ledger for region " + region.id());
			}
			MmmMod.LOGGER.info("Region {} entered hydrostatic sleep with {} markers and {} exact cell volume",
					region.id(), markerCount, exactVolume);
		}
		if (autoPause) {
			region.blockPerformance("wall-time PERFORMANCE_PAUSE：" + POLICY.hardPauseStreak()
					+ " consecutive steps exceeded " + POLICY.hardPauseMilliseconds() + " ms");
			MmmMod.LOGGER.warn("Auto-paused fluid region {} after {} consecutive steps above {} ms",
					region.id(), POLICY.hardPauseStreak(), POLICY.hardPauseMilliseconds());
		}
		if (tick % SNAPSHOT_INTERVAL_TICKS == 0 || region.paused() || region.availabilityFailure() != null
				|| region.lifecycle().state() == FluidRegionLifecycle.State.HYDROSTATIC_SLEEP) {
			snapshots.sendToLevel(level, region);
		}
	}

	FluidRegion.CollisionStats refreshRegionInputs(ServerLevel level, FluidRegion region) {
		FluidRegion.CollisionStats collisions = inputs.refreshCollisions(level, region);
		try {
			if (inputs.refreshThermalBoundary(level, region)) {
				region.wakeThermal(ThermalActivity.WakeReason.BOUNDARY_CHANGE);
			}
		} catch (RuntimeException invalidBoundary) {
			blockThermal(level, region, "thermal-definition LOCKED：" + invalidBoundary.getMessage(), invalidBoundary);
		}
		return collisions;
	}

	boolean refreshThermalBoundary(ServerLevel level, FluidRegion region) {
		try {
			boolean changed = inputs.refreshThermalBoundary(level, region);
			if (changed) region.wakeThermal(ThermalActivity.WakeReason.BOUNDARY_CHANGE);
			return changed;
		} catch (RuntimeException invalidBoundary) {
			blockThermal(level, region, "thermal-definition LOCKED：" + invalidBoundary.getMessage(), invalidBoundary);
			return false;
		}
	}

	void blockCollisionEdit(
			ServerLevel level, FluidRegion region, FluidDomain.CollisionEditBlockedException blocked
	) {
		String detail = blocked.getCause() == null ? blocked.getMessage() : blocked.getCause().getMessage();
		region.blockCollision("collision-edit-blocked PAUSED：" + detail);
		MmmMod.LOGGER.error("Region {} could not reconcile a Minecraft collision edit with authoritative inventory and is paused",
				region.id(), blocked);
		for (ServerPlayer player : level.players()) player.sendSystemMessage(Component.literal(
				"物质、运动与力学无法把方块碰撞编辑与物质账本联合提交，区域已安全暂停：" + detail
						+ "；世界方块优先，区域代次保持可恢复，请查看 /mmm status"));
		snapshots.sendToLevel(level, region);
	}

	void blockThermal(ServerLevel level, FluidRegion region, String failure, RuntimeException blocked) {
		region.blockThermal(failure);
		MmmMod.LOGGER.error("Region {} rejected a thermal logical step and is paused: {}",
				region.id(), failure, blocked);
		for (ServerPlayer player : level.players()) player.sendSystemMessage(Component.literal(
				"物质、运动与力学热学步骤被安全拒绝，区域已暂停：" + failure + "；请查看 /mmm status"));
		snapshots.sendToLevel(level, region);
	}

	void blockNumerical(
			ServerLevel level, FluidRegion region, FluidDomain.PressureSolveBlockedException blocked
	) {
		String failure = "pressure-solver NUMERICAL_PAUSE：" + blocked.getMessage();
		region.blockNumerical(failure);
		MmmMod.LOGGER.error("Region {} rejected an unconverged pressure candidate and is paused",
				region.id(), blocked);
		for (ServerPlayer player : level.players()) player.sendSystemMessage(Component.literal(
				"物质、运动与力学压力求解未收敛，本次候选未提交，区域已安全暂停："
						+ blocked.getMessage() + "；请查看 /mmm status"));
		snapshots.sendToLevel(level, region);
	}

	void blockGasTopology(
			ServerLevel level, FluidRegion region, GasTopologyBlockedException blocked
	) {
		String failure = "gas-topology NUMERICAL_PAUSE：" + blocked.getMessage();
		region.blockNumerical(failure);
		MmmMod.LOGGER.error("Region {} rejected a gas-topology candidate and is paused",
				region.id(), blocked);
		for (ServerPlayer player : level.players()) player.sendSystemMessage(Component.literal(
				"物质、运动与力学气体拓扑账本无法闭合，本次候选未提交，区域已安全暂停："
						+ blocked.getMessage() + "；请查看 /mmm status"));
		snapshots.sendToLevel(level, region);
	}

	/** Maps a rejected logical-step candidate through the single runtime pause taxonomy. */
	boolean blockStepFailure(ServerLevel level, FluidRegion region, RuntimeException failure) {
		if (failure instanceof FluidDomain.PressureSolveBlockedException blocked) {
			blockNumerical(level, region, blocked);
			return true;
		}
		if (failure instanceof GasTopologyBlockedException blocked) {
			blockGasTopology(level, region, blocked);
			return true;
		}
		if (failure instanceof FluidDomain.ThermalTransportBlockedException blocked) {
			blockThermal(level, region, "thermal-transport PAUSED：" + blocked.getMessage(), blocked);
			return true;
		}
		if (failure instanceof FluidDomain.GasThermalBlockedException blocked) {
			blockThermal(level, region, "gas-thermal PAUSED：" + blocked.getMessage(), blocked);
			return true;
		}
		if (failure instanceof FluidDomain.ThermalPhaseBlockedException blocked) {
			blockThermal(level, region, "phase-change PAUSED：" + blocked.getMessage(), blocked);
			return true;
		}
		if (failure instanceof ChemicalReactionEngine.BudgetExceededException budget) {
			String detail = "chemistry-blocked PAUSED：" + budget.getMessage();
			region.blockChemistry(detail);
			MmmMod.LOGGER.error("Region {} exhausted a frozen chemistry budget; the complete candidate was rejected and the region is paused",
					region.id(), budget);
			for (ServerPlayer player : level.players()) player.sendSystemMessage(Component.literal(
					"物质、运动与力学化学步骤已整体拒绝，区域被安全暂停：" + budget.getMessage()
							+ "；候选未提交，请查看 /mmm status"));
			snapshots.sendToLevel(level, region);
			return true;
		}
		if (failure instanceof GranularPhaseTransferEngine.BlockedException blocked) {
			String detail = "granular-phase-blocked PAUSED：" + blocked.getMessage();
			region.blockGranular(detail);
			MmmMod.LOGGER.error("Region {} rejected a granular phase-transfer candidate and is paused",
					region.id(), blocked);
			for (ServerPlayer player : level.players()) player.sendSystemMessage(Component.literal(
					"物质、运动与力学固液相转移被安全暂停：" + blocked.getMessage()
							+ "；候选未提交，请查看 /mmm status"));
			snapshots.sendToLevel(level, region);
			return true;
		}
		if (failure instanceof GranularMechanics.ValidityExceededException invalid) {
			region.blockGranular("granular-validity-blocked PAUSED：" + invalid.getMessage());
			MmmMod.LOGGER.error("Region {} exceeded a frozen granular mechanics validity range and is paused",
					region.id(), invalid);
			for (ServerPlayer player : level.players()) player.sendSystemMessage(Component.literal(
					"物质、运动与力学颗粒力学超出定义有效范围，区域被安全暂停：" + invalid.getMessage()
							+ "；候选未提交，请查看 /mmm status"));
			snapshots.sendToLevel(level, region);
			return true;
		}
		return false;
	}

	private static void observeThermalStep(
			FluidRegion region, ThermalAuditLedger before, boolean carrierMotion,
			FluidDomain.ThermalAxisStats thermalAxis,
			GranularDepositDynamics.Stats granularTransition
	) {
		FluidDomain domain = region.domain();
		ThermalAuditLedger after = domain.thermalAudit();
		boolean gasActivity = !after.externalSource().equals(before.externalSource())
				|| !after.externalSink().equals(before.externalSink())
				|| !after.mechanicalWork().equals(before.mechanicalWork())
				|| !after.finiteDeviceExchange().equals(before.finiteDeviceExchange());
		boolean chemistryDirty = thermalAxis.chemistry().changedMarkers() != 0;
		boolean granularDirty = thermalAxis.granularPhase().transfers() != 0
				|| carrierMotion && (granularTransition.settledParcels() != 0
				|| granularTransition.relaxedMoves() != 0
				|| granularTransition.wokenParcels() != 0
				|| granularTransition.dirtyColumns() != 0);
		boolean phaseDirty = thermalAxis.condensedPhase().operations() != 0
				|| thermalAxis.gasPhase().operations() != 0;
		boolean auditClosed = domain.exactInternalEnergy().equals(after.expectedInventory());
		boolean pendingVolumeOrWork = domain.hasPendingPhaseVolumeWork();
		region.observeThermal(new ThermalActivity.Observation(thermalAxis.transport(),
				chemistryDirty, granularDirty, phaseDirty, gasActivity, carrierMotion,
				pendingVolumeOrWork, auditClosed), THERMAL_QUIET_STEPS);
		if (!carrierMotion && (chemistryDirty || granularDirty || phaseDirty || gasActivity
				|| pendingVolumeOrWork)) {
			region.wakeForActivity();
		}
	}

	private static void applyRuntime(
			FluidRegion region, RegionRuntime before, RegionRuntime after, double elapsedMilliseconds
	) {
		region.setHardOverrunStreak(after.hardOverrunStreak());
		region.setRecoveryStreak(after.recoveryStreak());
		if (after.stepIntervalTicks() != before.stepIntervalTicks()) {
			region.setStepIntervalTicks(after.stepIntervalTicks());
			if (after.stepIntervalTicks() > before.stepIntervalTicks()) {
				MmmMod.LOGGER.info("Reduced fluid simulation rate to approximately {} Hz after a {} ms step",
						20 / after.stepIntervalTicks(), String.format("%.1f", elapsedMilliseconds));
			}
		}
	}
}

package mmm.simulation;

import mmm.fluid.FluidDomainContracts;

import mmm.fluid.HostFluidState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Objects;

/** Administrator-only writes against the selected live region. */
public final class MinecraftRegionOperatorActions {
	private final WorldStartupCoordinator.Result startup;
	private final FluidRegionManager regions;
	private final FluidWorldTargetResolver targets;
	private final FluidRegionOperations operations;
	private final FluidSnapshotPublisher snapshots;
	private final MinecraftTakeoverAdapter takeover;
	private final MinecraftDebugFixtures fixtures;
	private final MinecraftRegionAvailability availability;
	private final MinecraftRegionTick regionTick;
	private final MinecraftMotionExecutor motion;
	private final MinecraftPendingPhaseWorkBarrier phaseWorkBarrier;
	private final MinecraftRuntimePolicy policy;
	private final mmm.application.ContainerRecoveryCoordinator containerRecovery;

	MinecraftRegionOperatorActions(
			WorldStartupCoordinator.Result startup,
			FluidRegionManager regions,
			FluidWorldTargetResolver targets,
			FluidRegionOperations operations,
			FluidSnapshotPublisher snapshots,
			MinecraftTakeoverAdapter takeover,
			MinecraftDebugFixtures fixtures,
			MinecraftRegionAvailability availability,
			MinecraftRegionTick regionTick,
			MinecraftMotionExecutor motion,
			MinecraftPendingPhaseWorkBarrier phaseWorkBarrier,
			MinecraftRuntimePolicy policy,
			mmm.application.ContainerRecoveryCoordinator containerRecovery
	) {
		this.startup = Objects.requireNonNull(startup, "World startup result");
		this.regions = Objects.requireNonNull(regions, "Region manager");
		this.targets = Objects.requireNonNull(targets, "World target resolver");
		this.operations = Objects.requireNonNull(operations, "Region operations");
		this.snapshots = Objects.requireNonNull(snapshots, "Snapshot publisher");
		this.takeover = Objects.requireNonNull(takeover, "Takeover adapter");
		this.fixtures = Objects.requireNonNull(fixtures, "Debug fixtures");
		this.availability = Objects.requireNonNull(availability, "Region availability");
		this.regionTick = Objects.requireNonNull(regionTick, "Region tick");
		this.motion = Objects.requireNonNull(motion, "Motion executor");
		this.phaseWorkBarrier = Objects.requireNonNull(phaseWorkBarrier, "Phase-work barrier");
		this.policy = Objects.requireNonNull(policy, "Runtime policy");
		this.containerRecovery = Objects.requireNonNull(containerRecovery, "Container recovery coordinator");
	}

	public boolean exists() { return activeRegion() != null; }

	public boolean setPaused(boolean paused, ServerLevel level) {
		FluidRegion region = activeRegion();
		if (region != null && availability.hasPending(region.id())) return false;
		if (region != null && paused && !region.solverLoaded() && region.hasSelectedVolume()) {
			if (!startup.authoritativeWritesReady()) return false;
			if (region.paused()) return true;
			try { operations.setUnloadedRunIntent(region, true); return true; }
			catch (java.io.IOException | RuntimeException failure) {
				mmm.MmmMod.LOGGER.error("Could not persist run intent for unloaded region {}", region.id(), failure);
				return false;
			}
		}
		return setPaused(region, paused, () -> availability.ensure(level, region));
	}

	static boolean setPaused(FluidRegion region, boolean paused, java.util.function.BooleanSupplier ensureAvailable) {
		if (region != null && !paused) clearPerformancePauseForExplicitRetry(region);
		if (region == null || (!paused && !ensureAvailable.getAsBoolean())) return false;
		region.setPaused(paused);
		return true;
	}

	public boolean entityPhysicsEnabled() { return policy.entityPhysicsEnabled(); }
	public void setEntityPhysicsEnabled(boolean enabled) { policy.setEntityPhysicsEnabled(enabled); }
	public boolean controllerFeatureEnabled() { return policy.controllerEnabled(); }
	public boolean worldBridgeFeatureEnabled() { return policy.liveWorldBridgeEnabled(); }
	public boolean configuredWorldBridgeFeatureEnabled() { return policy.configuredWorldBridgeEnabled(); }
	public String setControllerFeatureEnabled(boolean enabled) { return policy.setControllerEnabled(enabled); }
	public String setWorldBridgeFeatureForRestart(boolean enabled) { return policy.setWorldBridgeForRestart(enabled); }
	public boolean debugOverlays() { return policy.debugOverlays(); }

	public void setDebugOverlays(boolean enabled, ServerLevel level) {
		policy.setDebugOverlays(enabled);
		sendSnapshot(level);
	}

	public int step(ServerLevel level, int count) {
		FluidRegion region = activeRegion();
		if (region == null || !level.dimension().equals(region.dimension())) return 0;
		clearPerformancePauseForExplicitRetry(region);
		if (!availability.ensure(level, region)) return -1;
		try {
			region.wakeForActivity();
			for (int i = 0; i < count; i++) regionTick.manualStep(level, region, motion);
		} catch (RuntimeException failure) {
			if (!regionTick.blockStepFailure(level, region, failure)) throw failure;
			return -1;
		}
		sendSnapshot(level);
		return count;
	}

	/** Explicit run/step retries clear only wall-time evidence, never physical or transaction failures. */
	static void clearPerformancePauseForExplicitRetry(FluidRegion region) {
		region.clearExpansionBlock();
		if (region.availabilityReason() == mmm.api.RegionAvailabilityReason.PERFORMANCE_PAUSE) {
			region.clearPerformanceBlock();
		}
	}

	public WaterEditResult pourWater(ServerPlayer player, int buckets) {
		FluidRegion region = activeRegion();
		if (region == null || !player.level().dimension().equals(region.dimension())) return WaterEditResult.NO_DOMAIN;
		if (!availability.ensure(player.level(), region)) return WaterEditResult.blocked(region.availabilityFailure());
		if (!phaseWorkBarrier.settle(player.level(), region)) return WaterEditResult.blocked(region.availabilityFailure());
		FluidWorldTargetResolver.CellTarget resolved = targets.aimedCellForEdit(player, region, false);
		if (resolved == null) return WaterEditResult.NO_TARGET;
		CellTarget target = new CellTarget(resolved.x(), resolved.y(), resolved.z());
		long generation = region.solverGeneration();
		HostFluidState edit = region.beginHostEdit();
		float requested = (float) edit.unitScale().normalizedVolume(buckets);
		FluidDomainContracts.VolumeAddition addition = edit.addVolumeNearBudgeted(
				target.x(), target.y(), target.z(), requested, 6);
		if (addition.addedVolume() > 0.0F) {
			region.commitHostEdit(generation, edit);
			region.wakeForActivity();
		}
		sendSnapshot(player.level());
		return new WaterEditResult(true, target, buckets, edit.unitScale().cubicMeters(addition.addedVolume()),
				addition.markerBudgetLimited(), null);
	}

	public WaterEditResult drainWater(ServerPlayer player, int buckets) {
		FluidRegion region = activeRegion();
		if (region == null || !player.level().dimension().equals(region.dimension())) return WaterEditResult.NO_DOMAIN;
		if (!availability.ensureForInventoryDecrease(player.level(), region)) {
			return WaterEditResult.blocked(region.availabilityFailure());
		}
		if (!phaseWorkBarrier.settle(player.level(), region)) return WaterEditResult.blocked(region.availabilityFailure());
		FluidWorldTargetResolver.CellTarget resolved = targets.aimedCellForEdit(player, region, true);
		if (resolved == null) return WaterEditResult.NO_TARGET;
		CellTarget target = new CellTarget(resolved.x(), resolved.y(), resolved.z());
		long generation = region.solverGeneration();
		HostFluidState edit = region.beginHostEdit();
		float requested = (float) edit.unitScale().normalizedVolume(buckets);
		float changed = edit.removeVolumeNear(target.x(), target.y(), target.z(), requested, 6);
		if (changed > 0.0F) {
			region.commitHostEdit(generation, edit);
			region.wakeForActivity();
		}
		sendSnapshot(player.level());
		return new WaterEditResult(true, target, buckets, edit.unitScale().cubicMeters(changed), false, null);
	}

	public CollisionRefreshResult refreshCollisions(ServerLevel level) {
		FluidRegion region = activeRegion();
		if (region == null || !level.dimension().equals(region.dimension())) return CollisionRefreshResult.EMPTY;
		FluidRegion.CollisionStats stats = region.lastCollisionStats();
		if (availability.ensure(level, region)) {
			try {
				stats = regionTick.refreshRegionInputs(level, region);
			} catch (FluidDomainContracts.CollisionEditBlockedException blocked) {
				regionTick.blockCollisionEdit(level, region, blocked);
				throw blocked;
			}
		}
		var units = mmm.units.SolverUnitScale.forCellSize(region.solverConfig().cellSize());
		return new CollisionRefreshResult(stats.changedCells(), units.cubicMeters(stats.displacedCellVolume()),
				units.cubicMeters(stats.escapedCellVolume()), stats.markerBudgetLimited());
	}

	public RestorationStats remove(MinecraftServer server) {
		FluidRegion selected = activeRegion();
		if (selected == null) return RestorationStats.failed(false, 0, 0, 0, "没有选中的区域");
		if (selected.hasSelectedVolume()) {
			return RestorationStats.failed(false, 0, 0, 0,
					"区域需使用探针打开控制台，依次清空凝聚相、排气、释放方块归属并移除区域");
		}
		if (containerRecovery.hasAnyLock()) return RestorationStats.failed(false, 0, 0, 0,
				"存在未完成或不可识别的容器转移 WAL；拒绝移除区域，先完成恢复");
		RestorationStats restoration = takeover.restore(server);
		if (!restoration.completed()) {
			FluidRegion region = activeRegion();
			if (region != null) region.setPaused(true);
			return restoration;
		}
		fixtures.restoreAirDemoScaffolding(server);
		regions.remove(selected.id());
		try { snapshots.sendRemoval(server, selected); }
		finally { selected.discardTransient(); }
		return new RestorationStats(restoration.journalFound(), restoration.restoredBlocks(),
				restoration.alreadyRestoredBlocks(), restoration.skippedBlocks(), 0, 0, true, null);
	}

	public String emptySelectedRegion(ServerLevel level) {
		if (!startup.authoritativeWritesReady()) return authoritativeWriteFailure("清空区域库存");
		FluidRegion region = activeRegion();
		if (region == null || !level.dimension().equals(region.dimension())) return "没有活动流体域或位于其他维度";
		if (region.hasSelectedVolume()) return "清空失败：请手持物质场探针右击区域内方块打开控制台，并使用“清空凝聚相”的预览/确认操作";
		if (!availability.ensureForInventoryDecrease(level, region)) {
			return "清空失败：流体域当前不可用：" + region.availabilityFailure();
		}
		if (availability.hasPending(region.id())) return "清空失败：区域存在未完成或不可识别的跨域事务 WAL";
		long generation = region.solverGeneration();
		HostFluidState domain = region.beginHostEdit();
		if (domain.hasPendingPhaseMotionWork()) return "清空失败：区域相变后尚未完成下一次机械推进";
		double beforeCubicMeters = domain.unitScale().cubicMeters(domain.totalVolumeExact());
		int beforeParticles = domain.particleCount();
		FluidDomainContracts.OperatorClearPlan clearPlan;
		try {
			clearPlan = domain.planOperatorClear();
		} catch (RuntimeException failure) {
			return "清空失败：联合液体/coherent/颗粒预检未通过，活动库存未改变：" + failure.getMessage();
		}
		FluidDomainContracts.OperatorClear cleared;
		try {
			cleared = domain.commitOperatorClear(clearPlan);
			region.commitHostEdit(generation, domain);
		} catch (RuntimeException failure) {
			region.setPaused(true);
			region.setAvailabilityFailure("empty 联合提交违反已预检不变量：" + failure.getMessage());
			return "清空联合提交失败；区域已暂停：" + failure.getMessage();
		}
		region.wakeForActivity();
		FluidDomainContracts.CondensedClear condensed = cleared.condensed();
		FluidDomainContracts.GranularClear granular = cleared.granular();
		sendSnapshot(level, region);
		return String.format("已清空区域：%s 液体（%d 个 marker），%d SVQ coherent（%d groups），%d SVQ 颗粒（active %d，deposit groups %d）",
				mmm.units.UnitFormat.volume(beforeCubicMeters), beforeParticles, condensed.coherentVolumeSvq(), condensed.coherentGroups(),
				granular.solidVolumeSvq(), granular.activeParcels(), granular.depositGroups());
	}

    boolean isLifecycleManagedBlock(ServerLevel level, BlockPos position) { return false; }

	private String authoritativeWriteFailure(String operation) {
		if (!startup.authoritativeWritesReady() && startup.state() != WorldStartupCoordinator.State.READY) {
			return operation + "失败：world capability startup=" + startup.state()
					+ "；不可证明的 profile 不会创建或改写区域";
		}
		return operation + "失败：server.properties 未通过校验；原文件已保留，请修复后重启服务器";
	}

	private void sendSnapshot(ServerLevel level) {
		FluidRegion region = activeRegion();
		if (region != null && level.dimension().equals(region.dimension())) sendSnapshot(level, region);
	}

	private void sendSnapshot(ServerLevel level, FluidRegion region) {
		snapshots.sendToLevel(level, region);
	}

	private FluidRegion activeRegion() { return regions.selected(); }

	public record CellTarget(int x, int y, int z) { }

	public record CollisionRefreshResult(
			int changedCells, double displacedVolumeCubicMeters, double escapedVolumeCubicMeters,
			boolean markerBudgetLimited
	) {
		private static final CollisionRefreshResult EMPTY = new CollisionRefreshResult(0, 0.0, 0.0, false);
	}

	public record WaterEditResult(
			boolean domainExists,
			CellTarget target,
			double requestedVolumeCubicMeters,
			double changedVolumeCubicMeters,
			boolean markerBudgetLimited,
			String failure
	) {
		private static final WaterEditResult NO_DOMAIN = new WaterEditResult(false, null, 0.0F, 0.0F, false, null);
		private static final WaterEditResult NO_TARGET = new WaterEditResult(true, null, 0.0F, 0.0F, false, null);
		private static WaterEditResult blocked(String failure) {
			return new WaterEditResult(true, null, 0.0F, 0.0F, false, failure);
		}
	}
}

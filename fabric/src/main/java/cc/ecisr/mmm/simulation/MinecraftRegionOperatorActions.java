package cc.ecisr.mmm.simulation;

import cc.ecisr.mmm.fluid.FluidDomain;
import cc.ecisr.mmm.fluid.GasTopologyBlockedException;
import cc.ecisr.mmm.granular.GranularMechanics;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
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
	private final cc.ecisr.mmm.application.ContainerRecoveryCoordinator containerRecovery;

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
			cc.ecisr.mmm.application.ContainerRecoveryCoordinator containerRecovery
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
		if (region != null && !paused
				&& region.availabilityReason() == cc.ecisr.mmm.api.RegionAvailabilityReason.PERFORMANCE_PAUSE) {
			region.clearPerformanceBlock();
		}
		if (region == null || (!paused && !availability.ensure(level, region))) return false;
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
		if (!availability.ensure(level, region)) return -1;
		region.wakeForActivity();
		try {
			for (int i = 0; i < count; i++) motion.step(region, List.of());
		} catch (FluidDomain.PressureSolveBlockedException blocked) {
			regionTick.blockNumerical(level, region, blocked);
			return -1;
		} catch (GasTopologyBlockedException blocked) {
			regionTick.blockGasTopology(level, region, blocked);
			return -1;
		} catch (FluidDomain.ThermalTransportBlockedException blocked) {
			regionTick.blockThermal(level, region, "thermal-transport PAUSED：" + blocked.getMessage(), blocked);
			return -1;
		} catch (FluidDomain.GasThermalBlockedException blocked) {
			regionTick.blockThermal(level, region, "gas-thermal PAUSED：" + blocked.getMessage(), blocked);
			return -1;
		} catch (FluidDomain.ThermalPhaseBlockedException blocked) {
			regionTick.blockThermal(level, region, "phase-change PAUSED：" + blocked.getMessage(), blocked);
			return -1;
		} catch (GranularMechanics.ValidityExceededException invalid) {
			region.blockGranular("granular-validity-blocked PAUSED：" + invalid.getMessage());
			return -1;
		}
		sendSnapshot(level);
		return count;
	}

	public WaterEditResult pourWater(ServerPlayer player, int buckets) {
		FluidRegion region = activeRegion();
		if (region == null || !player.level().dimension().equals(region.dimension())) return WaterEditResult.NO_DOMAIN;
		if (!availability.ensure(player.level(), region)) return WaterEditResult.blocked(region.availabilityFailure());
		if (!phaseWorkBarrier.settle(player.level(), region)) return WaterEditResult.blocked(region.availabilityFailure());
		FluidWorldTargetResolver.CellTarget resolved = targets.aimedCellForEdit(player, region, false);
		if (resolved == null) return WaterEditResult.NO_TARGET;
		CellTarget target = new CellTarget(resolved.x(), resolved.y(), resolved.z());
		float requested = buckets * FluidRegionFactory.CELL_VOLUMES_PER_BLOCK;
		FluidDomain.VolumeAddition addition = region.domain().addVolumeNearBudgeted(
				target.x(), target.y(), target.z(), requested, 6);
		if (addition.addedVolume() > 0.0F) region.wakeForActivity();
		sendSnapshot(player.level());
		return new WaterEditResult(true, target, requested, addition.addedVolume(),
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
		float requested = buckets * FluidRegionFactory.CELL_VOLUMES_PER_BLOCK;
		float changed = region.domain().removeVolumeNear(target.x(), target.y(), target.z(), requested, 6);
		if (changed > 0.0F) region.wakeForActivity();
		sendSnapshot(player.level());
		return new WaterEditResult(true, target, requested, changed, false, null);
	}

	public FluidRegion.CollisionStats refreshCollisions(ServerLevel level) {
		FluidRegion region = activeRegion();
		if (region == null || !level.dimension().equals(region.dimension())) return FluidRegion.CollisionStats.EMPTY;
		if (!availability.ensure(level, region)) return region.lastCollisionStats();
		try {
			return regionTick.refreshRegionInputs(level, region);
		} catch (FluidDomain.CollisionEditBlockedException blocked) {
			regionTick.blockCollisionEdit(level, region, blocked);
			throw blocked;
		}
	}

	public RestorationStats remove(MinecraftServer server) {
		if (regions.regions().stream().anyMatch(FluidRegion::controllerManaged)) {
			return RestorationStats.failed(false, 0, 0, 0,
					"controller 区域需手持物质场探针普通右击控制器打开控制台，依次预览并确认：清空凝聚相、排放密闭气体、释放 ownership、移除区域");
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
		regions.clear();
		snapshots.clearAll(server);
		return new RestorationStats(restoration.journalFound(), restoration.restoredBlocks(),
				restoration.alreadyRestoredBlocks(), restoration.skippedBlocks(), 0, 0, true, null);
	}

	public String emptySelectedRegion(ServerLevel level) {
		if (!startup.authoritativeWritesReady()) return authoritativeWriteFailure("清空区域库存");
		FluidRegion region = activeRegion();
		if (region == null || !level.dimension().equals(region.dimension())) return "没有活动流体域或位于其他维度";
		if (region.controllerManaged()) return "清空失败：请手持物质场探针普通右击控制器打开控制台，并使用“清空凝聚相”的预览/确认操作";
		if (!availability.ensureForInventoryDecrease(level, region)) {
			return "清空失败：流体域当前不可用：" + region.availabilityFailure();
		}
		if (containerRecovery.hasPending(region.id())) return "清空失败：区域存在未完成或不可识别的容器转移 WAL；拒绝清空";
		FluidDomain domain = region.domain();
		if (domain.hasPendingPhaseVolumeWork()) return "清空失败：区域存在尚未由下一次 pressure projection 消费的相变体积/功记录";
		float beforeBlocks = domain.totalVolume() / FluidRegionFactory.CELL_VOLUMES_PER_BLOCK;
		int beforeParticles = domain.particleCount();
		FluidDomain.OperatorClearPlan clearPlan;
		try {
			clearPlan = domain.planOperatorClear();
		} catch (RuntimeException failure) {
			return "清空失败：联合液体/coherent/颗粒预检未通过，活动库存未改变：" + failure.getMessage();
		}
		FluidDomain.OperatorClear cleared;
		try {
			cleared = domain.commitOperatorClear(clearPlan);
		} catch (RuntimeException failure) {
			region.setPaused(true);
			region.setAvailabilityFailure("empty 联合提交违反已预检不变量：" + failure.getMessage());
			return "清空联合提交失败；区域已暂停：" + failure.getMessage();
		}
		region.wakeForActivity();
		FluidDomain.CondensedClear condensed = cleared.condensed();
		FluidDomain.GranularClear granular = cleared.granular();
		sendSnapshot(level, region);
		return String.format("已清空区域：%.3f 方块液体（%d 个 marker），%d SVQ coherent（%d groups），%d SVQ 颗粒（active %d，deposit groups %d）",
				beforeBlocks, beforeParticles, condensed.coherentVolumeSvq(), condensed.coherentGroups(),
				granular.solidVolumeSvq(), granular.activeParcels(), granular.depositGroups());
	}

	boolean isLifecycleManagedBlock(ServerLevel level, BlockPos position) {
		if (level == null || position == null || !level.getServer().isSameThread()) return false;
		long packed = position.asLong();
		for (FluidRegion region : regions.regions()) if (region.dimension().equals(level.dimension())
				&& region.footprint() != null && region.footprint().boundaryBlocks().contains(packed)) return true;
		return false;
	}

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

	public record WaterEditResult(
			boolean domainExists,
			CellTarget target,
			float requestedCellVolume,
			float changedCellVolume,
			boolean markerBudgetLimited,
			String failure
	) {
		private static final WaterEditResult NO_DOMAIN = new WaterEditResult(false, null, 0.0F, 0.0F, false, null);
		private static final WaterEditResult NO_TARGET = new WaterEditResult(true, null, 0.0F, 0.0F, false, null);
		private static WaterEditResult blocked(String failure) {
			return new WaterEditResult(true, null, 0.0F, 0.0F, false, failure);
		}
		public float changedBlocks() { return changedCellVolume / FluidRegionFactory.CELL_VOLUMES_PER_BLOCK; }
	}
}

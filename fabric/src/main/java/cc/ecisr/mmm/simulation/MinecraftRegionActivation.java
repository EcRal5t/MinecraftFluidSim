package cc.ecisr.mmm.simulation;

import cc.ecisr.mmm.MmmMod;
import cc.ecisr.mmm.application.ContainerRecoveryCoordinator;
import cc.ecisr.mmm.application.RegionCatalog;
import cc.ecisr.mmm.block.MmmBlocks;
import cc.ecisr.mmm.simulation.controller.FluidBoundaryScanner;
import cc.ecisr.mmm.simulation.controller.FluidControllerRegions;
import cc.ecisr.mmm.simulation.controller.FluidRegionFootprint;
import cc.ecisr.mmm.simulation.ownership.OwnershipJournal;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Owns controller selection, run-toggle and new-region activation through the first durable HEAD. */
public final class MinecraftRegionActivation {
	private final WorldStartupCoordinator.Result startup;
	private final CommittedWorldRegistries world;
	private final FluidRegionManager regions;
	private final FluidRegionOperations operations;
	private final FluidSnapshotPublisher snapshots;
	private final MinecraftRegionTick regionTick;
	private final MinecraftTakeoverAdapter takeover;
	private final MinecraftDebugFixtures debugFixtures;
	private final ContainerRecoveryCoordinator containerRecovery;
	private final BooleanSupplier controllerFeatureEnabled;
	private final BooleanSupplier defaultEntityPhysics;
	private final BooleanSupplier defaultDebugOverlays;
	private final Supplier<List<String>> ownershipIssues;
	private final MinecraftRegionAvailability availability;
	private final boolean worldBridgeFeatureEnabled;
	private final int maximumThermalSubstepsPerStep;

	MinecraftRegionActivation(
			WorldStartupCoordinator.Result startup,
			CommittedWorldRegistries world,
			FluidRegionManager regions,
			FluidRegionOperations operations,
			FluidSnapshotPublisher snapshots,
			MinecraftRegionTick regionTick,
			MinecraftTakeoverAdapter takeover,
			MinecraftDebugFixtures debugFixtures,
			ContainerRecoveryCoordinator containerRecovery,
			BooleanSupplier controllerFeatureEnabled,
			BooleanSupplier defaultEntityPhysics,
			BooleanSupplier defaultDebugOverlays,
			Supplier<List<String>> ownershipIssues,
			MinecraftRegionAvailability availability,
			boolean worldBridgeFeatureEnabled,
			int maximumThermalSubstepsPerStep
	) {
		this.startup = Objects.requireNonNull(startup, "World startup result");
		this.world = Objects.requireNonNull(world, "Committed world registries");
		this.regions = Objects.requireNonNull(regions, "Region manager");
		this.operations = Objects.requireNonNull(operations, "Region operations");
		this.snapshots = Objects.requireNonNull(snapshots, "Snapshot publisher");
		this.regionTick = Objects.requireNonNull(regionTick, "Region tick adapter");
		this.takeover = Objects.requireNonNull(takeover, "Takeover adapter");
		this.debugFixtures = Objects.requireNonNull(debugFixtures, "Debug fixtures");
		this.containerRecovery = Objects.requireNonNull(containerRecovery, "Container recovery coordinator");
		this.controllerFeatureEnabled = Objects.requireNonNull(controllerFeatureEnabled, "Controller feature gate");
		this.defaultEntityPhysics = Objects.requireNonNull(defaultEntityPhysics, "Default entity physics");
		this.defaultDebugOverlays = Objects.requireNonNull(defaultDebugOverlays, "Default debug overlays");
		this.ownershipIssues = Objects.requireNonNull(ownershipIssues, "Ownership issues");
		this.availability = Objects.requireNonNull(availability, "Region availability");
		this.worldBridgeFeatureEnabled = worldBridgeFeatureEnabled;
		this.maximumThermalSubstepsPerStep = maximumThermalSubstepsPerStep;
	}

	public Result activate(ServerPlayer player, BlockPos controllerPosition, boolean toggleRunning) {
		ServerLevel level = player.level();
		debugFixtures.restoreAirDemoScaffolding(level.getServer());
		if (!level.getBlockState(controllerPosition).is(MmmBlocks.FLUID_CONTROLLER)) {
			return Result.failed("目标方块不再是 Matter, Motion & Mechanics 控制器");
		}
		for (FluidRegion existing : regions.regions()) {
			FluidRegionFootprint existingFootprint = existing.footprint();
			if (existingFootprint == null || !existing.dimension().equals(level.dimension())
					|| !existingFootprint.controllerPosition().equals(controllerPosition)) continue;
			regions.select(existing.id());
			if (!availability.ensure(level, existing)) {
				return Result.failed("控制器区域当前不可用：" + existing.availabilityFailure());
			}
			Action action;
			if (toggleRunning) {
				if (!startup.authoritativeWritesReady()) {
					return Result.failed(authoritativeWriteFailure("修改控制器运行意图"));
				}
				if (existing.paused()) {
					existing.setPaused(false);
					existing.wakeForActivity();
					action = Action.RESUMED;
				} else {
					existing.setPaused(true);
					action = Action.PAUSED;
				}
			} else {
				action = existing.paused() ? Action.SELECTED_PAUSED : Action.SELECTED_RUNNING;
			}
			snapshots.sendToLevel(level, existing);
			return Result.succeeded(existingFootprint, action);
		}
		if (!controllerFeatureEnabled.getAsBoolean()) return Result.DISABLED;
		if (!startup.authoritativeWritesReady()) return Result.failed(authoritativeWriteFailure("创建控制器区域"));
		if (containerRecovery.hasStoreIssues()) {
			return Result.failed("container-transfer 恢复目录含未知或损坏内容；已全局锁住新控制器，详见日志");
		}
		if (operations.creationLocked() || !ownershipIssues.get().isEmpty()) {
			return Result.failed("存在未解决或超预算的 region-state/ownership；已锁住新控制器区域，详见 /mmm regions list、/mmm ownership 与日志");
		}

		RestorationStats restoration = takeover.restore(level.getServer());
		if (!restoration.completed()) return Result.failed(restoration.failure());
		FluidBoundaryScanner.ScanResult scan = FluidControllerRegions.scan(
				level, controllerPosition, (int) Math.max(1L, Math.min(Integer.MAX_VALUE,
						regions.limits().maximumCellsPerRegion() / FluidRegionFactory.CELL_VOLUMES_PER_BLOCK)));
		if (!scan.succeeded()) return Result.failed(scan.failure());
		FluidRegionFootprint footprint = scan.footprint();
		List<OwnershipJournal.BlockEntry> ownershipPreimage;
		try {
			ownershipPreimage = operations.scanOwnershipPreimage(level, footprint);
		} catch (IOException invalidPreimage) {
			return Result.failed("无法冻结 controller world preimage：" + invalidPreimage.getMessage());
		}
		if (!worldBridgeFeatureEnabled && !ownershipPreimage.isEmpty()) {
			return Result.failed("worldbridge 策略关闭；拒绝接管壳内原版水，请先排空或启用策略并重启");
		}
		ControllerCreationPlan creationPlan = new ControllerCreationPlan(
				world.profile().profileFingerprint(), footprint, ownershipPreimage);
		long requiredCells;
		long requiredMarkers;
		try {
			requiredCells = Math.multiplyExact(
					footprint.denseBlockCapacity(), FluidRegionFactory.CELL_VOLUMES_PER_BLOCK);
			requiredMarkers = Math.multiplyExact(requiredCells, 8L);
		} catch (ArithmeticException exception) {
			return Result.failed("控制器区域尺寸溢出资源计数");
		}
		RegionCatalog.AdmissionResult preview = regions.previewAdd(requiredCells, requiredMarkers);
		if (!preview.accepted()) return Result.failed(preview.failure());
		String availabilityFailure = FluidControllerRegions.availabilityFailure(level, footprint);
		if (availabilityFailure != null) return Result.failed(availabilityFailure);

		FluidRegion candidate;
		try {
			candidate = FluidRegionFactory.createControllerRegion(level, footprint, requiredMarkers);
		} catch (IllegalArgumentException | ArithmeticException exception) {
			return Result.failed("无法分配控制器区域：" + exception.getMessage());
		}
		candidate.setEntityPhysicsEnabled(defaultEntityPhysics.getAsBoolean());
		candidate.setDebugOverlays(defaultDebugOverlays.getAsBoolean());
		RegionCatalog.AdmissionResult exactPreview = regions.previewAdd(candidate);
		if (!exactPreview.accepted()) return Result.failed(exactPreview.failure());
		try {
			regionTick.refreshRegionInputs(level, candidate);
			if (worldBridgeFeatureEnabled) FluidRegionFactory.captureVanillaWater(level, candidate);
			world.bindConfiguredState(candidate.domain(), maximumThermalSubstepsPerStep);
		} catch (RuntimeException invalidCandidate) {
			return Result.failed("无法构造完整控制器区域候选：" + invalidCandidate.getMessage());
		}
		candidate.setPaused(false);
		candidate.wakeForActivity();
		try {
			if (!creationPlan.profileFingerprint().equals(world.profile().profileFingerprint())
					|| !creationPlan.footprint().equals(candidate.footprint())) {
				throw new IOException("Controller creation proof changed after preflight");
			}
			if (worldBridgeFeatureEnabled) operations.acquireOwnership(
					level, candidate, creationPlan.ownershipPreimage());
			else operations.checkpoint(candidate);
		} catch (IOException | RuntimeException exception) {
			MmmMod.LOGGER.error("Controller region {} was rejected after its initial checkpoint failed",
					candidate.id(), exception);
			return Result.failed("区域未创建；初始 checkpoint/ownership 事务失败：" + exception.getMessage());
		}
		RegionCatalog.AdmissionResult admission = regions.addAndSelect(candidate);
		if (!admission.accepted()) {
			String issue = "首个 region HEAD 已提交，但 live owner 登记违反已通过的 admission 预检：" + admission.failure();
			operations.recordIssue(issue);
			MmmMod.LOGGER.error("Controller region {} requires restart after live registration failed: {}",
					candidate.id(), admission.failure());
			return Result.failed(issue + "；请重启服务器恢复已提交区域");
		}
		snapshots.sendToLevel(level, candidate);
		return Result.created(footprint);
	}

	private String authoritativeWriteFailure(String operation) {
		if (!startup.authoritativeWritesReady() && startup.state() != WorldStartupCoordinator.State.READY) {
			return operation + "失败：world capability startup=" + startup.state()
					+ "；不可证明的 profile 不会创建或改写区域";
		}
		return operation + "失败：server.properties 未通过校验；原文件已保留，请修复后重启服务器";
	}

	public record Result(
			boolean succeeded, boolean disabled, Action action, String failure,
			int interiorBlocks, int sizeX, int sizeY, int sizeZ, int requiredChunks
	) {
		private static final Result DISABLED = new Result(
				false, true, null, "controller feature flag is disabled", 0, 0, 0, 0, 0);

		private static Result failed(String failure) {
			return new Result(false, false, null, failure, 0, 0, 0, 0, 0);
		}

		private static Result created(FluidRegionFootprint footprint) {
			return succeeded(footprint, Action.CREATED);
		}

		private static Result succeeded(FluidRegionFootprint footprint, Action action) {
			return new Result(true, false, action, null, footprint.interiorBlocks().size(),
					footprint.blockSizeX(), footprint.blockSizeY(), footprint.blockSizeZ(),
					footprint.requiredChunks().size());
		}
	}

	public enum Action { CREATED, SELECTED_RUNNING, SELECTED_PAUSED, PAUSED, RESUMED }

	private record ControllerCreationPlan(
			String profileFingerprint,
			FluidRegionFootprint footprint,
			List<OwnershipJournal.BlockEntry> ownershipPreimage
	) {
		private ControllerCreationPlan {
			if (profileFingerprint == null || !profileFingerprint.matches("[0-9a-f]{64}")) {
				throw new IllegalArgumentException("Controller creation profile fingerprint is invalid");
			}
			Objects.requireNonNull(footprint, "footprint");
			ownershipPreimage = List.copyOf(ownershipPreimage);
		}
	}
}

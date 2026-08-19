package com.example.simulation;

import com.example.RealWaterMod;
import com.example.block.RealWaterBlocks;
import com.example.fluid.FlipFluidDomain;
import com.example.network.FluidSnapshotPayload;
import com.example.network.FluidProtocolServer;
import com.example.simulation.controller.FluidBoundaryScanner;
import com.example.simulation.controller.FluidControllerRegions;
import com.example.simulation.controller.FluidRegionFootprint;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/** Adapts fluid regions to Minecraft while delegating region-state lifecycle and takeover transactions. */
public final class FluidSimulationService {
	public static final int BLOCK_SIZE_X = 12;
	public static final int BLOCK_SIZE_Y = 8;
	public static final int BLOCK_SIZE_Z = 12;
	public static final int CELLS_PER_BLOCK = 2;
	private static final int CELL_VOLUMES_PER_BLOCK = CELLS_PER_BLOCK * CELLS_PER_BLOCK * CELLS_PER_BLOCK;
	private static final int SNAPSHOT_INTERVAL_TICKS = 4;
	private static final int COLLISION_REFRESH_INTERVAL_TICKS = 5;
	private static final double STEP_BUDGET_MILLIS = 40.0;
	private static final double HARD_PAUSE_MILLIS = 200.0;
	private static final int HARD_PAUSE_STREAK = 3;
	private static final int MAX_STEP_INTERVAL_TICKS = 4;
	private static final int RECOVERY_STEPS = 8;
	private static final int HYDROSTATIC_QUIET_STEPS = 200;
	private static final float HYDROSTATIC_MAX_MARKER_SPEED = 2.5E-3F;
	private static final double AIM_DISTANCE = 16.0;
	private static final long COMPATIBILITY_REGION_CELLS = (long) BLOCK_SIZE_X * BLOCK_SIZE_Y * BLOCK_SIZE_Z
			* CELLS_PER_BLOCK * CELLS_PER_BLOCK * CELLS_PER_BLOCK;
	private static final long COMPATIBILITY_REGION_MARKERS = COMPATIBILITY_REGION_CELLS * 8L;
	private static final Map<MinecraftServer, FluidSimulationService> SERVICES = new WeakHashMap<>();

	private final Map<BlockPos, BlockState> takeoverOriginalBlocks = new HashMap<>();
	private final Path worldRoot;
	private final boolean serverConfigLocked;
	private FluidServerConfig serverConfig;
	private final FluidRegionManager regionManager;
	private final FluidRegionOperations regionOperations;
	private ResourceKey<Level> takeoverDimension;
	private boolean defaultEntityPhysicsEnabled = true;
	private boolean defaultDebugOverlays;
	private boolean controllerFeatureEnabled;
	private String lastTakeoverFailure;
	private boolean takeoverMaintenanceEnabled;

	private FluidSimulationService(MinecraftServer server) {
		worldRoot = server.getWorldPath(LevelResource.ROOT);
		boolean configurationLocked = false;
		try {
			serverConfig = FluidServerConfig.loadOrCreate(worldRoot);
		} catch (IOException exception) {
			serverConfig = FluidServerConfig.defaults();
			configurationLocked = true;
			RealWaterMod.LOGGER.error("Could not load Real Water world config; controllers remain disabled", exception);
		}
		serverConfigLocked = configurationLocked;
		controllerFeatureEnabled = serverConfig.controllersEnabled();
		regionManager = new FluidRegionManager(serverConfig.limits());
		regionOperations = new FluidRegionOperations(worldRoot, regionManager, configurationLocked);
		if (configurationLocked) regionOperations.recordIssue(
				"server.properties 不可用，已安全使用默认预算并关闭控制器；原文件未覆盖");
	}

	public static FluidSimulationService get(MinecraftServer server) {
		return SERVICES.computeIfAbsent(server, FluidSimulationService::new);
	}

	public static void removeServer(MinecraftServer server) {
		FluidSimulationService service = SERVICES.remove(server);
		if (service != null) {
			service.checkpointForShutdown();
			service.restoreTakeover(server);
		}
	}

	public static void recoverTakeoverBackup(MinecraftServer server) {
		FluidSimulationService service = get(server);
		for (String issue : service.regionOperations.issues()) RealWaterMod.LOGGER.error("Real Water persistent region issue: {}", issue);
		RestorationStats result = service.restoreTakeover(server);
		if (result.completed() && result.journalFound()) {
			RealWaterMod.LOGGER.warn(
					"Recovered unfinished Real Water takeover: restored {} blocks, already restored {}, skipped {} occupied positions",
					result.restoredBlocks(), result.alreadyRestoredBlocks(), result.skippedBlocks()
			);
		}
	}

	public boolean exists() {
		return activeRegion() != null;
	}

	public boolean paused() {
		FluidRegion region = activeRegion();
		return region == null || region.paused();
	}

	public boolean controllerFeatureEnabled() {
		return controllerFeatureEnabled;
	}

	/** Returns null on success; the world-local flag is durably replaced before runtime state changes. */
	public String setControllerFeatureEnabled(boolean enabled) {
		if (serverConfigLocked) return "server.properties 未通过 v1 校验；原文件已保留，请修复后重启服务器";
		FluidServerConfig replacement = serverConfig.withControllersEnabled(enabled);
		try {
			replacement.save(worldRoot);
		} catch (IOException exception) {
			RealWaterMod.LOGGER.error("Could not persist Real Water controller feature flag", exception);
			return exception.getMessage();
		}
		serverConfig = replacement;
		controllerFeatureEnabled = enabled;
		if (!enabled) {
			for (FluidRegion region : regionManager.regions()) {
				if (region.controllerManaged()) region.setAvailabilityFailure("controller feature flag is disabled");
			}
		}
		return null;
	}

	public ControllerActivation activateController(ServerPlayer player, BlockPos controllerPosition) {
		if (!controllerFeatureEnabled) return ControllerActivation.DISABLED;
		ServerLevel level = player.level();
		if (!level.getBlockState(controllerPosition).is(RealWaterBlocks.FLUID_CONTROLLER)) {
			return ControllerActivation.failed("目标方块不再是 Real Water 控制器");
		}
		for (FluidRegion existing : regionManager.regions()) {
			FluidRegionFootprint existingFootprint = existing.footprint();
			if (existingFootprint == null || !existing.dimension().equals(level.dimension())
					|| !existingFootprint.controllerPosition().equals(controllerPosition)) continue;
			regionManager.select(existing.id());
			ensureRegionAvailable(level, existing);
			sendSnapshot(level);
			return ControllerActivation.selected(existingFootprint);
		}
		if (regionOperations.creationLocked()) {
			return ControllerActivation.failed("存在未解决或超预算的 region-state；已锁住新控制器区域，详见 /realwater regions list 与日志");
		}

		FluidBoundaryScanner.ScanResult scan = FluidControllerRegions.scan(
				level, controllerPosition, (int) Math.max(1L, Math.min(Integer.MAX_VALUE,
						regionManager.limits().maximumCellsPerRegion() / CELL_VOLUMES_PER_BLOCK)
				)
		);
		if (!scan.succeeded()) return ControllerActivation.failed(scan.failure());
		FluidRegionFootprint footprint = scan.footprint();
		long requiredCells;
		long requiredMarkers;
		try {
			requiredCells = Math.multiplyExact(footprint.denseBlockCapacity(), CELL_VOLUMES_PER_BLOCK);
			requiredMarkers = Math.multiplyExact(requiredCells, 8L);
		} catch (ArithmeticException exception) {
			return ControllerActivation.failed("控制器区域尺寸溢出资源计数");
		}
		FluidRegionManager.AdmissionResult preview = regionManager.previewAdd(requiredCells, requiredMarkers);
		if (!preview.accepted()) return ControllerActivation.failed(preview.failure());

		RestorationStats restoration = restoreTakeover(level.getServer());
		if (!restoration.completed()) return ControllerActivation.failed(restoration.failure());
		String availabilityFailure = FluidControllerRegions.availabilityFailure(level, footprint);
		if (availabilityFailure != null) return ControllerActivation.failed(availabilityFailure);

		FluidRegion candidate;
		try {
			candidate = FluidRegionFactory.createControllerRegion(level, footprint, CELLS_PER_BLOCK, requiredMarkers);
		} catch (IllegalArgumentException | ArithmeticException exception) {
			return ControllerActivation.failed("无法分配控制器区域：" + exception.getMessage());
		}
		candidate.setEntityPhysicsEnabled(defaultEntityPhysicsEnabled);
		candidate.setDebugOverlays(defaultDebugOverlays);
		FluidRegionManager.AdmissionResult admission = regionManager.addAndSelect(candidate);
		if (!admission.accepted()) return ControllerActivation.failed(admission.failure());
		refreshCollisions(level, candidate);
		FluidRegionFactory.captureVanillaWater(level, candidate);
		candidate.setPaused(true);
		try {
			regionOperations.checkpoint(candidate);
		} catch (IOException | RuntimeException exception) {
			candidate.setAvailabilityFailure("initial checkpoint failed: " + exception.getMessage());
			RealWaterMod.LOGGER.error("Controller region {} exists in memory but its initial checkpoint failed", candidate.id(), exception);
			return ControllerActivation.failed("区域已安全暂停，但初始 checkpoint 失败：" + exception.getMessage());
		}
		sendSnapshot(level);
		return ControllerActivation.created(footprint);
	}

	public boolean createDemo(ServerPlayer player) {
		Vec3 look = player.getLookAngle();
		BlockPos center = BlockPos.containing(player.getX() + look.x * 9.0, player.getY(), player.getZ() + look.z * 9.0);
		ServerLevel level = player.level();
		RestorationStats restoration = restoreTakeover(level.getServer());
		if (!restoration.completed()) return false;
		if (activeRegion() != null && activeRegion().controllerManaged()) {
			lastTakeoverFailure = "拒绝用开发测试域覆盖持久控制器区域；请先显式移除控制器区域";
			return false;
		}
		BlockPos newOrigin = center.offset(-BLOCK_SIZE_X / 2, -2, -BLOCK_SIZE_Z / 2);
		try {
			createEmpty(level, newOrigin);
		} catch (IllegalStateException exception) {
			lastTakeoverFailure = exception.getMessage();
			return false;
		}
		refreshCollisions(level);

		FlipFluidDomain domain = domain();
		int fillX = domain.sizeX() * 2 / 5;
		int fillY = domain.sizeY() * 3 / 4;
		for (int z = 0; z < domain.sizeZ(); z++) {
			for (int y = 0; y < fillY; y++) {
				for (int x = 0; x < fillX; x++) {
					if (!domain.isSolid(x, y, z)) domain.setVolume(x, y, z, 1.0F);
				}
			}
		}
		activeRegion().setPaused(true);
		sendSnapshot(level);
		return true;
	}

	public CaptureResult captureNearbyWater(ServerPlayer player, boolean takeover) {
		ServerLevel level = player.level();
		RestorationStats restoration = restoreTakeover(level.getServer());
		if (!restoration.completed()) {
			return new CaptureResult(0, -1, restoration.failure());
		}
		if (activeRegion() != null && activeRegion().controllerManaged()) {
			return new CaptureResult(0, -1, "拒绝用开发捕获域覆盖持久控制器区域；请先显式移除控制器区域");
		}
		BlockPos newOrigin = player.blockPosition().offset(-BLOCK_SIZE_X / 2, -2, -BLOCK_SIZE_Z / 2);
		try {
			createEmpty(level, newOrigin);
		} catch (IllegalStateException exception) {
			return new CaptureResult(0, -1, exception.getMessage());
		}
		refreshCollisions(level);
		int wetCells = FluidRegionFactory.captureVanillaWater(level, activeRegion());
		int removedBlocks = takeover ? beginTakeover(level) : 0;
		activeRegion().setPaused(true);
		sendSnapshot(level);
		return new CaptureResult(wetCells, removedBlocks, removedBlocks < 0 ? lastTakeoverFailure : null);
	}

	/** Temporarily replaces only standalone vanilla water blocks, never waterlogged blocks. */
	private int beginTakeover(ServerLevel level) {
		Map<BlockPos, BlockState> candidates = new HashMap<>();
		for (int blockZ = 0; blockZ < BLOCK_SIZE_Z; blockZ++) for (int blockY = 0; blockY < BLOCK_SIZE_Y; blockY++) for (int blockX = 0; blockX < BLOCK_SIZE_X; blockX++) {
			BlockPos pos = origin().offset(blockX, blockY, blockZ);
			BlockState state = level.getBlockState(pos);
			if (!state.is(Blocks.WATER)) continue;
			candidates.put(pos.immutable(), state);
		}
		if (candidates.isEmpty()) return 0;
		try {
			TakeoverBackupStore.BeginResult result = TakeoverBackupStore.begin(level.getServer(), level.dimension(), candidates);
			takeoverDimension = level.dimension();
			for (BlockPos position : result.removedPositions()) {
				takeoverOriginalBlocks.put(position, candidates.get(position));
			}
			takeoverMaintenanceEnabled = result.removedBlocks() > 0;
			lastTakeoverFailure = null;
			if (result.removedBlocks() == 0) {
				RestorationStats cleanup = restoreTakeover(level.getServer());
				if (!cleanup.completed()) return -1;
			}
			return result.removedBlocks();
		} catch (IOException | RuntimeException exception) {
			String beginFailure = "接管事务启动失败：" + exception.getMessage();
			RealWaterMod.LOGGER.error("Refusing water takeover; no recovery file will be overwritten", exception);
			RestorationStats rollback = restoreTakeover(level.getServer());
			lastTakeoverFailure = rollback.completed()
					? beginFailure + "；已安全回滚，原版水未保持接管状态"
					: beginFailure + "；自动回滚未完成，journal 已锁定新 takeover：" + rollback.failure();
			return -1;
		}
	}

	public RestorationStats restoreTakeover(MinecraftServer server) {
		// Once restoration begins, never let the tick hook remove a block that an interrupted pass already restored.
		takeoverMaintenanceEnabled = false;
		try {
			TakeoverBackupStore.RecoveryResult result = TakeoverBackupStore.recover(server);
			if (!result.found() && !takeoverOriginalBlocks.isEmpty()) {
				lastTakeoverFailure = "活动接管缺少 on-disk journal；已停止替换域，需从世界备份核对";
				RealWaterMod.LOGGER.error(lastTakeoverFailure);
				return RestorationStats.failed(false, 0, 0, 0, lastTakeoverFailure);
			}
			takeoverOriginalBlocks.clear();
			takeoverDimension = null;
			lastTakeoverFailure = null;
			return new RestorationStats(
					result.found(), result.restoredBlocks(), result.alreadyRestoredBlocks(), result.skippedBlocks(), true, null
			);
		} catch (TakeoverTransaction.RecoveryException exception) {
			if (exception.worldPassCompleted()) {
				takeoverOriginalBlocks.clear();
				takeoverDimension = null;
			}
			lastTakeoverFailure = "恢复未完成，journal 已保留并锁定新 takeover：" + exception.getMessage();
			RealWaterMod.LOGGER.error("Could not finish the Real Water takeover recovery; recovery files were preserved", exception);
			return RestorationStats.failed(
					true,
					exception.restoredBlocks(),
					exception.alreadyRestoredBlocks(),
					exception.skippedBlocks(),
					lastTakeoverFailure
			);
		} catch (IOException | RuntimeException exception) {
			lastTakeoverFailure = "恢复未完成，journal 已保留并锁定新 takeover：" + exception.getMessage();
			RealWaterMod.LOGGER.error("Could not recover the unfinished Real Water takeover; recovery files were preserved", exception);
			return RestorationStats.failed(TakeoverBackupStore.isLocked(server), 0, 0, 0, lastTakeoverFailure);
		}
	}

	private void createEmpty(ServerLevel level, BlockPos origin) {
		FluidRegion replaced = activeRegion();
		if (replaced != null && replaced.controllerManaged()) {
			throw new IllegalStateException("Refusing to replace a persistent controller region; remove it explicitly first");
		}
		FluidRegionManager.AdmissionResult preview = regionManager.previewReplacement(
				COMPATIBILITY_REGION_CELLS, COMPATIBILITY_REGION_MARKERS);
		if (!preview.accepted()) throw new IllegalStateException("Compatibility fluid region rejected: " + preview.failure());
		FluidRegion candidate = FluidRegionFactory.createCommandRegion(
				level, origin, BLOCK_SIZE_X, BLOCK_SIZE_Y, BLOCK_SIZE_Z,
				CELLS_PER_BLOCK, COMPATIBILITY_REGION_MARKERS
		);
		candidate.setEntityPhysicsEnabled(defaultEntityPhysicsEnabled);
		candidate.setDebugOverlays(defaultDebugOverlays);
		FluidRegionManager.AdmissionResult admission = regionManager.replaceSelected(candidate);
		if (!admission.accepted()) throw new IllegalStateException("Compatibility fluid region rejected: " + admission.failure());
		if (replaced != null) sendRegionRemoval(level.getServer(), replaced);
	}

	public boolean setPaused(boolean value, ServerLevel level) {
		FluidRegion region = activeRegion();
		if (region == null) return false;
		if (!value && !ensureRegionAvailable(level, region)) return false;
		region.setPaused(value);
		return true;
	}

	public boolean entityPhysicsEnabled() {
		FluidRegion region = activeRegion();
		return region == null ? defaultEntityPhysicsEnabled : region.entityPhysicsEnabled();
	}

	public void setEntityPhysicsEnabled(boolean enabled) {
		defaultEntityPhysicsEnabled = enabled;
		FluidRegion region = activeRegion();
		if (region != null) region.setEntityPhysicsEnabled(enabled);
	}

	public boolean debugOverlays() {
		FluidRegion region = activeRegion();
		return region == null ? defaultDebugOverlays : region.debugOverlays();
	}

	public void setDebugOverlays(boolean enabled, ServerLevel level) {
		defaultDebugOverlays = enabled;
		FluidRegion region = activeRegion();
		if (region != null) region.setDebugOverlays(enabled);
		sendSnapshot(level);
	}

	public int step(ServerLevel level, int count) {
		if (!hasRegionIn(level)) return 0;
		FluidRegion region = activeRegion();
		if (!ensureRegionAvailable(level, region)) return -1;
		region.wakeForActivity();
		for (int i = 0; i < count; i++) stepOnce(region);
		sendSnapshot(level);
		return count;
	}

	public void tick(MinecraftServer server) {
		regionOperations.advanceTick();
		if (takeoverDimension != null) {
			ServerLevel takeoverLevel = server.getLevel(takeoverDimension);
			if (takeoverLevel != null) maintainTakeover(takeoverLevel);
		}
		for (FluidRegion region : regionManager.regions()) {
			ServerLevel level = server.getLevel(region.dimension());
			if (level == null) {
				regionOperations.unloadForFailure(server, region, "dimension is not loaded", FluidSimulationService::sendRegionRemoval);
				continue;
			}
			tickRegion(level, region);
		}
	}

	private void tickRegion(ServerLevel level, FluidRegion region) {
		if (!ensureRegionAvailable(level, region)) return;
		long tick = region.incrementTick();
		boolean collisionsChanged = false;
		if (tick % COLLISION_REFRESH_INTERVAL_TICKS == 0) {
			collisionsChanged = refreshCollisions(level, region).changedCells() > 0;
		}
		int affectedEntities = region.entityPhysicsEnabled()
				? FluidRegionWorldInteraction.applyEntityForces(level, region)
				: 0;
		region.setLastAffectedEntities(affectedEntities);
		if (collisionsChanged || affectedEntities > 0) region.wakeForActivity();
		if (!region.canStep()) {
			if (collisionsChanged) sendSnapshot(level, region);
			return;
		}
		if (tick % region.stepIntervalTicks() != 0) {
			if (collisionsChanged) sendSnapshot(level, region);
			return;
		}
		stepOnce(region);
		updateStepPacing(region);
		FlipFluidDomain.StepStats stats = region.lastStats();
		float residualLimit = Math.max(1.0E-5F, region.domain().config().pressureTolerance() * 4.0F);
		boolean hydrostaticCandidate = !collisionsChanged && affectedEntities == 0
				&& region.domain().maximumParticleSpeed() <= HYDROSTATIC_MAX_MARKER_SPEED
				&& stats != null && stats.pressureResidual() <= residualLimit;
		if (region.lifecycle().observeCompletedStep(hydrostaticCandidate, HYDROSTATIC_QUIET_STEPS)) {
			float exactVolume = region.domain().totalVolume();
			int markerCount = region.domain().particleCount();
			region.domain().commitHydrostaticSleep();
			if (markerCount != region.domain().particleCount() || exactVolume != region.domain().totalVolume()) {
				throw new IllegalStateException("Hydrostatic sleep changed the exact marker ledger for region " + region.id());
			}
			RealWaterMod.LOGGER.info("Region {} entered hydrostatic sleep with {} markers and {} exact cell volume",
					region.id(), markerCount, exactVolume);
		}
		if (region.hardOverrunStreak() >= HARD_PAUSE_STREAK) {
			region.setPaused(true);
			RealWaterMod.LOGGER.warn("Auto-paused fluid region {} after {} consecutive steps above {} ms",
					region.id(), HARD_PAUSE_STREAK, HARD_PAUSE_MILLIS);
		}
		if (tick % SNAPSHOT_INTERVAL_TICKS == 0 || region.paused()
				|| region.lifecycle().state() == FluidRegionLifecycle.State.HYDROSTATIC_SLEEP) {
			sendSnapshot(level, region);
		}
	}

	private void maintainTakeover(ServerLevel level) {
		if (!takeoverMaintenanceEnabled || takeoverOriginalBlocks.isEmpty() || !level.dimension().equals(takeoverDimension)) return;
		for (BlockPos pos : takeoverOriginalBlocks.keySet()) {
			if (level.getBlockState(pos).is(Blocks.WATER)) {
				level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS);
			}
		}
	}

	private boolean ensureRegionAvailable(ServerLevel level, FluidRegion region) {
		return regionOperations.ensureAvailable(
				level, region, controllerFeatureEnabled, this::refreshCollisions, this::sendSnapshot,
				FluidSimulationService::sendRegionRemoval
		);
	}

	private static void stepOnce(FluidRegion region) {
		long start = System.nanoTime();
		FlipFluidDomain.StepStats stats = region.domain().step();
		region.recordStep(stats, (System.nanoTime() - start) / 1_000_000.0);
	}

	private static void updateStepPacing(FluidRegion region) {
		double lastStepMilliseconds = region.lastStepMilliseconds();
		if (lastStepMilliseconds > HARD_PAUSE_MILLIS) region.setHardOverrunStreak(region.hardOverrunStreak() + 1);
		else region.setHardOverrunStreak(0);

		int requiredInterval = Mth.clamp((int) Math.ceil(lastStepMilliseconds / STEP_BUDGET_MILLIS), 1, MAX_STEP_INTERVAL_TICKS);
		if (requiredInterval > region.stepIntervalTicks()) {
			region.setStepIntervalTicks(requiredInterval);
			region.setRecoveryStreak(0);
			RealWaterMod.LOGGER.info("Reduced fluid simulation rate to approximately {} Hz after a {} ms step",
					20 / region.stepIntervalTicks(), String.format("%.1f", lastStepMilliseconds));
		} else if (requiredInterval < region.stepIntervalTicks()) {
			region.setRecoveryStreak(region.recoveryStreak() + 1);
			if (region.recoveryStreak() >= RECOVERY_STEPS) {
				region.setStepIntervalTicks(region.stepIntervalTicks() - 1);
				region.setRecoveryStreak(0);
			}
		} else {
			region.setRecoveryStreak(0);
		}
	}

	public WaterEditResult pourWater(ServerPlayer player, int buckets) {
		if (!hasRegionIn(player.level())) return WaterEditResult.NO_DOMAIN;
		FluidRegion region = activeRegion();
		if (!ensureRegionAvailable(player.level(), region)) {
			return WaterEditResult.blocked(region.availabilityFailure());
		}
		CellTarget target = findAimedCell(player, false);
		if (target == null) return WaterEditResult.NO_TARGET;
		float requested = buckets * CELL_VOLUMES_PER_BLOCK;
		FlipFluidDomain.VolumeAddition addition = domain().addVolumeNearBudgeted(
				target.x(), target.y(), target.z(), requested, 6
		);
		if (addition.addedVolume() > 0.0F) region.wakeForActivity();
		sendSnapshot(player.level());
		return new WaterEditResult(true, target, requested, addition.addedVolume(), addition.markerBudgetLimited(), null);
	}

	public WaterEditResult drainWater(ServerPlayer player, int buckets) {
		if (!hasRegionIn(player.level())) return WaterEditResult.NO_DOMAIN;
		FluidRegion region = activeRegion();
		if (!ensureRegionAvailable(player.level(), region)) {
			return WaterEditResult.blocked(region.availabilityFailure());
		}
		CellTarget target = findAimedCell(player, true);
		if (target == null) return WaterEditResult.NO_TARGET;
		float requested = buckets * CELL_VOLUMES_PER_BLOCK;
		float changed = domain().removeVolumeNear(target.x(), target.y(), target.z(), requested, 6);
		if (changed > 0.0F) region.wakeForActivity();
		sendSnapshot(player.level());
		return new WaterEditResult(true, target, requested, changed, false, null);
	}

	/** Re-samples Minecraft collision shapes at the simulation's subcell resolution. */
	public FluidRegion.CollisionStats refreshCollisions(ServerLevel level) {
		FluidRegion region = activeRegion();
		if (region == null || !level.dimension().equals(region.dimension())) return FluidRegion.CollisionStats.EMPTY;
		if (!ensureRegionAvailable(level, region)) return region.lastCollisionStats();
		return refreshCollisions(level, region);
	}

	private FluidRegion.CollisionStats refreshCollisions(ServerLevel level, FluidRegion region) {
		FluidRegion.CollisionStats stats = FluidRegionWorldInteraction.refreshCollisions(
				level, region, CELLS_PER_BLOCK
		);
		if (stats.changedCells() > 0 || stats.displacedCellVolume() > 0.0F
				|| stats.escapedCellVolume() > 0.0F || stats.markerBudgetLimited()) {
			region.setLastCollisionStats(stats);
		}
		region.markCollisionRefreshed();
		if (stats.changedCells() > 0) region.wakeForActivity();
		return stats;
	}

	private CellTarget findAimedCell(ServerPlayer player, boolean requireFluid) {
		FlipFluidDomain domain = domain();
		Vec3 eye = player.getEyePosition();
		Vec3 look = player.getLookAngle();
		double step = domain.config().cellSize() * 0.25;
		CellTarget previousOpen = null;
		CellTarget previousSample = null;
		boolean enteredDomain = false;

		for (double distance = 0.0; distance <= AIM_DISTANCE; distance += step) {
			Vec3 point = eye.add(look.scale(distance));
			CellTarget cell = worldToCell(point);
			if (cell == null) {
				if (enteredDomain && !requireFluid) return previousOpen;
				continue;
			}
			enteredDomain = true;
			if (cell.equals(previousSample)) continue;
			previousSample = cell;
			if (requireFluid) {
				if (domain.volume(cell.x(), cell.y(), cell.z()) > domain.config().fluidEpsilon()) return cell;
			} else if (domain.isSolid(cell.x(), cell.y(), cell.z())) {
				return previousOpen;
			} else if (domain.volume(cell.x(), cell.y(), cell.z()) > domain.config().fluidEpsilon()) {
				return previousOpen == null ? cell : previousOpen;
			} else {
				previousOpen = cell;
			}
		}
		return requireFluid ? null : previousOpen;
	}

	private CellTarget worldToCell(Vec3 point) {
		FlipFluidDomain domain = domain();
		BlockPos origin = origin();
		float h = domain.config().cellSize();
		int x = Mth.floor((point.x - origin.getX()) / h);
		int y = Mth.floor((point.y - origin.getY()) / h);
		int z = Mth.floor((point.z - origin.getZ()) / h);
		if (x < 0 || x >= domain.sizeX() || y < 0 || y >= domain.sizeY() || z < 0 || z >= domain.sizeZ()) return null;
		return new CellTarget(x, y, z);
	}

	public RestorationStats remove(MinecraftServer server) {
		RestorationStats restoration = restoreTakeover(server);
		if (!restoration.completed()) {
			FluidRegion region = activeRegion();
			if (region != null) region.setPaused(true);
			return restoration;
		}
		String persistenceFailure = regionOperations.deletePersistentRegions();
		if (persistenceFailure != null) return RestorationStats.failed(false, 0, 0, 0, persistenceFailure);
		regionManager.clear();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			FluidProtocolServer.send(player, FluidSnapshotPayload.clearAll());
		}
		return restoration;
	}

	public List<String> regionSummaries() {
		return regionOperations.summaries();
	}

	public SelectionResult selectRegion(String prefix) {
		return regionOperations.select(prefix);
	}

	public String checkpointSelected() {
		return regionOperations.checkpointSelected();
	}

	private void checkpointForShutdown() {
		regionOperations.checkpointForShutdown();
	}

	public String status(MinecraftServer server) {
		String takeoverLock = TakeoverBackupStore.lockDescription(server);
		FluidRegionManager.BudgetUsage usage = regionManager.usage();
		FluidRegion region = activeRegion();
		if (region == null) {
			return "没有活动流体域 | regions 0/" + regionManager.limits().maximumRegions()
					+ " | controller flag " + (controllerFeatureEnabled ? "on" : "off")
					+ " | takeover lock " + takeoverLock
					+ (regionOperations.issues().isEmpty() ? "" : " | persistence issues " + regionOperations.issues().size())
					+ (lastTakeoverFailure == null ? "" : " | " + lastTakeoverFailure);
		}
		FluidRegionManager.BudgetUsage selectedUsage = regionManager.usage();
		if (!region.solverLoaded()) {
			return String.format(
					"UNLOADED/PAUSED | selected %s | regions %d/%d, reserved %d/%d cells, %d/%d markers | controller %s | %s | origin %s | grid %dx%dx%d | checkpoint %d, water %.3f cells, markers %d | controller flag %s | takeover lock %s%s",
					region.id().toString().substring(0, 8), selectedUsage.regionCount(), regionManager.limits().maximumRegions(),
					selectedUsage.reservedCells(), regionManager.limits().maximumTotalCells(), selectedUsage.reservedMarkers(),
					regionManager.limits().maximumTotalMarkers(), region.footprint().controllerPosition().toShortString(),
					region.availabilityFailure() == null ? "awaiting explicit run" : "blocked: " + region.availabilityFailure(),
					region.origin().toShortString(), region.sizeX(), region.sizeY(), region.sizeZ(), region.checkpointGeneration(),
					region.checkpointVolume(), region.checkpointMarkerCount(), controllerFeatureEnabled ? "on" : "off", takeoverLock,
					regionOperations.issues().isEmpty() ? "" : " | persistence issues " + regionOperations.issues().size());
		}
		FlipFluidDomain.StepStats lastStats = region.lastStats();
		String stats = lastStats == null
				? "尚未步进"
				: String.format("%.2f ms, substeps %d, PCG %d, residual %.4g%s",
						region.lastStepMilliseconds(), lastStats.substeps(), lastStats.pressureIterations(), lastStats.pressureResidual(),
						lastStats.reseededMerges() == 0 && lastStats.reseededSplits() == 0
								&& lastStats.reseedBudgetSkipped() == 0 && lastStats.reseedGeometrySkipped() == 0
								? ""
								: String.format(", reseed split %d/merge %d, skipped budget %d/geometry %d, ledger %.3g",
										lastStats.reseededSplits(), lastStats.reseededMerges(),
										lastStats.reseedBudgetSkipped(), lastStats.reseedGeometrySkipped(),
										lastStats.reseedLedgerError()));
		FlipFluidDomain domain = region.domain();
		BlockPos origin = region.origin();
		float cellVolumes = domain.totalVolume();
		FlipFluidDomain.VolumeDistribution distribution = domain.volumeDistribution(8.0F / 255.0F);
		FlipFluidDomain.SolidDistribution solids = domain.solidDistribution();
		FluidRegion.CollisionStats collision = region.lastCollisionStats();
		FluidRegionFootprint footprint = region.footprint();
		String regionSource = footprint == null
				? "command"
				: "controller " + footprint.controllerPosition().toShortString() + ", boundary "
						+ footprint.boundaryBlocks().size() + ", chunks " + footprint.requiredChunks().size();
		String availability = region.availabilityFailure() == null ? "ready" : "blocked: " + region.availabilityFailure();
		return String.format(
				"%s%s | selected %s | regions %d/%d, reserved %d/%d cells, %d/%d markers | %s | %s | origin %s | grid %dx%dx%d | sim %.1f Hz | water %.2f blocks (%.2f cells) | particles %d/%d in %d cells | visible %d, dense %d | takeover %d, lock %s | entities %s/%d | maxP %.1f | collision partial/full %d/%d, last Δ%d, escaped %.3f%s | %s",
				region.lifecycle().state(), region.paused() ? "/PAUSED" : "",
				region.id().toString().substring(0, 8),
				usage.regionCount(), regionManager.limits().maximumRegions(),
				usage.reservedCells(), regionManager.limits().maximumTotalCells(),
				usage.reservedMarkers(), regionManager.limits().maximumTotalMarkers(),
				regionSource, availability,
				origin.toShortString(), domain.sizeX(), domain.sizeY(), domain.sizeZ(),
				20.0 / region.stepIntervalTicks(),
				cellVolumes / CELL_VOLUMES_PER_BLOCK, cellVolumes,
				domain.particleCount(), domain.maximumParticleCount(), domain.markerCellCount(),
				distribution.visibleCells(), distribution.denseCells(), takeoverOriginalBlocks.size(), takeoverLock,
				region.entityPhysicsEnabled() ? "on" : "off", region.lastAffectedEntities(),
				domain.maximumPressure(),
				solids.partialCells(), solids.fullCells(),
				collision.changedCells(), collision.escapedCellVolume() / CELL_VOLUMES_PER_BLOCK,
				collision.markerBudgetLimited() ? " (marker budget)" : "", stats
		);
	}

	public void sendSnapshot(ServerLevel level) {
		FluidRegion region = activeRegion();
		if (region != null && level.dimension().equals(region.dimension())) sendSnapshot(level, region);
	}

	public void sendSnapshots(ServerPlayer player) {
		for (FluidRegion region : regionManager.regions()) {
			if (!region.solverLoaded() || !player.level().dimension().equals(region.dimension())) continue;
			FluidProtocolServer.send(player, createSnapshot(region));
		}
	}

	private void sendSnapshot(ServerLevel level, FluidRegion region) {
		if (!region.solverLoaded() || !level.dimension().equals(region.dimension())) return;
		FluidSnapshotPayload payload = createSnapshot(region);
		for (ServerPlayer player : level.players()) FluidProtocolServer.send(player, payload);
	}

	private static FluidSnapshotPayload createSnapshot(FluidRegion region) {
		FlipFluidDomain domain = region.domain();
		return FluidSnapshotPayload.snapshot(
				region.dimension().identifier(), region.id(), region.generation(), region.nextSnapshotSequence(),
				region.origin(), domain.config().cellSize(), domain.sizeX(), domain.sizeY(), domain.sizeZ(),
				domain.copyQuantizedVolume(), domain.copyMarkerMask(), domain.copySolidMask(), region.debugOverlays()
		);
	}

	private static void sendRegionRemoval(MinecraftServer server, FluidRegion region) {
		FluidSnapshotPayload payload = FluidSnapshotPayload.removeRegion(
				region.dimension().identifier(), region.id(), region.generation(), region.nextSnapshotSequence()
		);
		for (ServerPlayer player : server.getPlayerList().getPlayers()) FluidProtocolServer.send(player, payload);
	}

	private FluidRegion activeRegion() {
		return regionManager.selected();
	}

	private FlipFluidDomain domain() {
		return activeRegion().domain();
	}

	private BlockPos origin() {
		return activeRegion().origin();
	}

	private boolean hasRegionIn(ServerLevel level) {
		FluidRegion region = activeRegion();
		return region != null && level.dimension().equals(region.dimension());
	}

	public record CellTarget(int x, int y, int z) { }

	public String lastTakeoverFailure() {
		return lastTakeoverFailure;
	}

	public record CaptureResult(int wetCells, int removedVanillaBlocks, String failure) { }

	public record SelectionResult(boolean succeeded, UUID regionId, String failure) {
		static SelectionResult failed(String failure) { return new SelectionResult(false, null, failure); }
	}

	public record ControllerActivation(
			boolean succeeded,
			boolean selectedExisting,
			boolean disabled,
			String failure,
			int interiorBlocks,
			int sizeX,
			int sizeY,
			int sizeZ,
			int requiredChunks
	) {
		private static final ControllerActivation DISABLED = new ControllerActivation(
				false, false, true, "controller feature flag is disabled", 0, 0, 0, 0, 0
		);

		private static ControllerActivation failed(String failure) {
			return new ControllerActivation(false, false, false, failure, 0, 0, 0, 0, 0);
		}

		private static ControllerActivation created(FluidRegionFootprint footprint) {
			return succeeded(footprint, false);
		}

		private static ControllerActivation selected(FluidRegionFootprint footprint) {
			return succeeded(footprint, true);
		}

		private static ControllerActivation succeeded(FluidRegionFootprint footprint, boolean selectedExisting) {
			return new ControllerActivation(
					true, selectedExisting, false, null, footprint.interiorBlocks().size(),
					footprint.blockSizeX(), footprint.blockSizeY(), footprint.blockSizeZ(),
					footprint.requiredChunks().size()
			);
		}
	}

	public record RestorationStats(
			boolean journalFound,
			int restoredBlocks,
			int alreadyRestoredBlocks,
			int skippedBlocks,
			boolean completed,
			String failure
	) {
		private static RestorationStats failed(
				boolean journalFound,
				int restoredBlocks,
				int alreadyRestoredBlocks,
				int skippedBlocks,
				String failure
		) {
			return new RestorationStats(journalFound, restoredBlocks, alreadyRestoredBlocks, skippedBlocks, false, failure);
		}
	}

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

		public float changedBlocks() {
			return changedCellVolume / CELL_VOLUMES_PER_BLOCK;
		}
	}

}

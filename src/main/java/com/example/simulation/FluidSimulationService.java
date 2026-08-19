package com.example.simulation;

import com.example.RealWaterMod;
import com.example.block.RealWaterBlocks;
import com.example.fluid.FlipFluidDomain;
import com.example.network.FluidSnapshotPayload;
import com.example.simulation.controller.FluidBoundaryScanner;
import com.example.simulation.controller.FluidControllerRegions;
import com.example.simulation.controller.FluidRegionFootprint;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
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
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/** Adapts the currently selected in-memory fluid region to Minecraft; only takeover recovery metadata is persisted. */
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
	private static final int MAX_REGIONS = 4;
	private static final int MAX_CONTROLLER_INTERIOR_BLOCKS = Math.toIntExact(
			COMPATIBILITY_REGION_CELLS / CELL_VOLUMES_PER_BLOCK
	);
	private static final Map<MinecraftServer, FluidSimulationService> SERVICES = new WeakHashMap<>();

	private final Map<BlockPos, BlockState> takeoverOriginalBlocks = new HashMap<>();
	private final FluidRegionManager regionManager = new FluidRegionManager(new FluidRegionManager.Limits(
			MAX_REGIONS, COMPATIBILITY_REGION_CELLS, COMPATIBILITY_REGION_MARKERS,
			COMPATIBILITY_REGION_CELLS * MAX_REGIONS, COMPATIBILITY_REGION_MARKERS * MAX_REGIONS
	));
	private ResourceKey<Level> takeoverDimension;
	private boolean defaultEntityPhysicsEnabled = true;
	private boolean defaultDebugOverlays;
	private boolean controllerFeatureEnabled;
	private String lastTakeoverFailure;
	private boolean takeoverMaintenanceEnabled;

	private FluidSimulationService() { }

	public static FluidSimulationService get(MinecraftServer server) {
		return SERVICES.computeIfAbsent(server, ignored -> new FluidSimulationService());
	}

	public static void removeServer(MinecraftServer server) {
		FluidSimulationService service = SERVICES.remove(server);
		if (service != null) service.restoreTakeover(server);
	}

	public static void recoverTakeoverBackup(MinecraftServer server) {
		RestorationStats result = get(server).restoreTakeover(server);
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

	public void setControllerFeatureEnabled(boolean enabled) {
		controllerFeatureEnabled = enabled;
		if (!enabled) {
			for (FluidRegion region : regionManager.regions()) {
				if (region.controllerManaged()) region.setAvailabilityFailure("controller feature flag is disabled");
			}
		}
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

		FluidBoundaryScanner.ScanResult scan = FluidControllerRegions.scan(
				level, controllerPosition, MAX_CONTROLLER_INTERIOR_BLOCKS
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
		sendSnapshot(level);
		return ControllerActivation.created(footprint);
	}

	public boolean createDemo(ServerPlayer player) {
		Vec3 look = player.getLookAngle();
		BlockPos center = BlockPos.containing(player.getX() + look.x * 9.0, player.getY(), player.getZ() + look.z * 9.0);
		ServerLevel level = player.level();
		RestorationStats restoration = restoreTakeover(level.getServer());
		if (!restoration.completed()) return false;
		BlockPos newOrigin = center.offset(-BLOCK_SIZE_X / 2, -2, -BLOCK_SIZE_Z / 2);
		createEmpty(level, newOrigin);
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
		BlockPos newOrigin = player.blockPosition().offset(-BLOCK_SIZE_X / 2, -2, -BLOCK_SIZE_Z / 2);
		createEmpty(level, newOrigin);
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
		FluidRegion candidate = FluidRegionFactory.createCommandRegion(
				level, origin, BLOCK_SIZE_X, BLOCK_SIZE_Y, BLOCK_SIZE_Z,
				CELLS_PER_BLOCK, COMPATIBILITY_REGION_MARKERS
		);
		candidate.setEntityPhysicsEnabled(defaultEntityPhysicsEnabled);
		candidate.setDebugOverlays(defaultDebugOverlays);
		FluidRegionManager.AdmissionResult admission = regionManager.replaceSelected(candidate);
		if (!admission.accepted()) throw new IllegalStateException("Compatibility fluid region rejected: " + admission.failure());
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
		if (takeoverDimension != null) {
			ServerLevel takeoverLevel = server.getLevel(takeoverDimension);
			if (takeoverLevel != null) maintainTakeover(takeoverLevel);
		}
		FluidRegion selected = activeRegion();
		for (FluidRegion region : regionManager.regions()) {
			ServerLevel level = server.getLevel(region.dimension());
			if (level == null) {
				region.setAvailabilityFailure("dimension is not loaded");
				continue;
			}
			tickRegion(level, region, region == selected);
		}
	}

	private void tickRegion(ServerLevel level, FluidRegion region, boolean selected) {
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
			if (selected && collisionsChanged) sendSnapshot(level);
			return;
		}
		if (tick % region.stepIntervalTicks() != 0) {
			if (selected && collisionsChanged) sendSnapshot(level);
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
		if (selected && (tick % SNAPSHOT_INTERVAL_TICKS == 0 || region.paused()
				|| region.lifecycle().state() == FluidRegionLifecycle.State.HYDROSTATIC_SLEEP)) sendSnapshot(level);
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
		if (!region.controllerManaged()) {
			region.setAvailabilityFailure(null);
			return true;
		}
		String failure = controllerFeatureEnabled
				? FluidControllerRegions.availabilityFailure(level, region.footprint())
				: "controller feature flag is disabled";
		if (failure == null) {
			region.setAvailabilityFailure(null);
			return true;
		}
		if (!failure.equals(region.availabilityFailure())) {
			RealWaterMod.LOGGER.warn("Pausing controller region {}: {}", region.id(), failure);
		}
		region.setAvailabilityFailure(failure);
		return false;
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
		region.setLastCollisionStats(stats);
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
		regionManager.clear();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (ServerPlayNetworking.canSend(player, FluidSnapshotPayload.TYPE)) ServerPlayNetworking.send(player, FluidSnapshotPayload.clear());
		}
		return restoration;
	}

	public String status(MinecraftServer server) {
		String takeoverLock = TakeoverBackupStore.lockDescription(server);
		FluidRegionManager.BudgetUsage usage = regionManager.usage();
		FluidRegion region = activeRegion();
		if (region == null) {
			return "没有活动流体域 | regions 0/" + regionManager.limits().maximumRegions()
					+ " | controller flag " + (controllerFeatureEnabled ? "on" : "off")
					+ " | takeover lock " + takeoverLock
					+ (lastTakeoverFailure == null ? "" : " | " + lastTakeoverFailure);
		}
		FlipFluidDomain.StepStats lastStats = region.lastStats();
		String stats = lastStats == null
				? "尚未步进"
				: String.format("%.2f ms, substeps %d, PCG %d, residual %.4g",
						region.lastStepMilliseconds(), lastStats.substeps(), lastStats.pressureIterations(), lastStats.pressureResidual());
		FlipFluidDomain domain = region.domain();
		BlockPos origin = region.origin();
		float cellVolumes = domain.totalVolume();
		FlipFluidDomain.VolumeDistribution distribution = domain.volumeDistribution(8.0F / 255.0F);
		FluidRegion.CollisionStats collision = region.lastCollisionStats();
		FluidRegionFootprint footprint = region.footprint();
		String regionSource = footprint == null
				? "command"
				: "controller " + footprint.controllerPosition().toShortString() + ", boundary "
						+ footprint.boundaryBlocks().size() + ", chunks " + footprint.requiredChunks().size();
		String availability = region.availabilityFailure() == null ? "ready" : "blocked: " + region.availabilityFailure();
		return String.format(
				"%s%s | selected %s | regions %d/%d, reserved %d/%d cells, %d/%d markers | %s | %s | origin %s | grid %dx%dx%d | sim %.1f Hz | water %.2f blocks (%.2f cells) | particles %d/%d in %d cells | visible %d, dense %d | takeover %d, lock %s | entities %s/%d | maxP %.1f | collision Δ%d, escaped %.3f%s | %s",
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
				collision.changedCells(), collision.escapedCellVolume() / CELL_VOLUMES_PER_BLOCK,
				collision.markerBudgetLimited() ? " (marker budget)" : "", stats
		);
	}

	public void sendSnapshot(ServerLevel level) {
		if (!hasRegionIn(level)) return;
		FlipFluidDomain domain = domain();
		FluidSnapshotPayload payload = new FluidSnapshotPayload(
				origin(), domain.config().cellSize(), domain.sizeX(), domain.sizeY(), domain.sizeZ(),
				domain.copyQuantizedVolume(), domain.copyMarkerMask(), domain.copySolidMask(), activeRegion().debugOverlays()
		);
		for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
			if (!ServerPlayNetworking.canSend(player, FluidSnapshotPayload.TYPE)) continue;
			ServerPlayNetworking.send(player,
					player.level().dimension().equals(level.dimension()) ? payload : FluidSnapshotPayload.clear());
		}
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

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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

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
	private static final double AIM_DISTANCE = 16.0;
	private static final int ENTITY_SAMPLES_XZ = 3;
	private static final int ENTITY_SAMPLES_Y = 5;
	private static final double NEUTRAL_SUBMERGENCE = 0.72;
	private static final double FULL_SUBMERGENCE_DRAG = 0.20;
	private static final double SIMULATION_TICKS_PER_SECOND = 20.0;
	private static final long COMPATIBILITY_REGION_CELLS = (long) BLOCK_SIZE_X * BLOCK_SIZE_Y * BLOCK_SIZE_Z
			* CELLS_PER_BLOCK * CELLS_PER_BLOCK * CELLS_PER_BLOCK;
	private static final long COMPATIBILITY_REGION_MARKERS = COMPATIBILITY_REGION_CELLS * 8L;
	private static final int MAX_CONTROLLER_INTERIOR_BLOCKS = Math.toIntExact(
			COMPATIBILITY_REGION_CELLS / CELL_VOLUMES_PER_BLOCK
	);
	private static final Map<MinecraftServer, FluidSimulationService> SERVICES = new WeakHashMap<>();

	private final Map<BlockPos, BlockState> takeoverOriginalBlocks = new HashMap<>();
	private final FluidRegionManager regionManager = new FluidRegionManager(new FluidRegionManager.Limits(
			1, COMPATIBILITY_REGION_CELLS, COMPATIBILITY_REGION_MARKERS,
			COMPATIBILITY_REGION_CELLS, COMPATIBILITY_REGION_MARKERS
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
		FluidRegion region = activeRegion();
		if (!enabled && region != null && region.controllerManaged()) {
			region.setPaused(true);
			region.setAvailabilityFailure("controller feature flag is disabled");
		}
	}

	public ControllerActivation activateController(ServerPlayer player, BlockPos controllerPosition) {
		if (!controllerFeatureEnabled) return ControllerActivation.DISABLED;
		ServerLevel level = player.level();
		if (!level.getBlockState(controllerPosition).is(RealWaterBlocks.FLUID_CONTROLLER)) {
			return ControllerActivation.failed("目标方块不再是 Real Water 控制器");
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
		FluidRegionManager.AdmissionResult preview = regionManager.previewReplacement(requiredCells, requiredMarkers);
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
		FluidRegionManager.AdmissionResult admission = regionManager.replaceSelected(candidate);
		if (!admission.accepted()) return ControllerActivation.failed(admission.failure());
		refreshCollisions(level);
		FluidRegionFactory.captureVanillaWater(level, candidate);
		candidate.setPaused(true);
		sendSnapshot(level);
		return ControllerActivation.succeeded(footprint);
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
		for (int i = 0; i < count; i++) stepOnce(region);
		sendSnapshot(level);
		return count;
	}

	public void tick(MinecraftServer server) {
		FluidRegion region = activeRegion();
		if (region == null) return;
		ServerLevel level = server.getLevel(region.dimension());
		if (level == null) return;
		if (!ensureRegionAvailable(level, region)) return;
		maintainTakeover(level);
		long tick = region.incrementTick();
		boolean collisionsChanged = false;
		if (tick % COLLISION_REFRESH_INTERVAL_TICKS == 0) {
			collisionsChanged = refreshCollisions(level).changedCells() > 0;
		}
		region.setLastAffectedEntities(region.entityPhysicsEnabled() ? applyEntityForces(level) : 0);
		if (region.paused()) {
			if (collisionsChanged) sendSnapshot(level);
			return;
		}
		if (tick % region.stepIntervalTicks() != 0) {
			if (collisionsChanged) sendSnapshot(level);
			return;
		}
		stepOnce(region);
		updateStepPacing(region);
		if (region.hardOverrunStreak() >= HARD_PAUSE_STREAK) {
			region.setPaused(true);
			RealWaterMod.LOGGER.warn("Auto-paused fluid domain after {} consecutive steps above {} ms", HARD_PAUSE_STREAK, HARD_PAUSE_MILLIS);
		}
		if (tick % SNAPSHOT_INTERVAL_TICKS == 0 || region.paused()) sendSnapshot(level);
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
		region.setPaused(true);
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

	private int applyEntityForces(ServerLevel level) {
		FluidRegion region = activeRegion();
		BlockPos origin = region.origin();
		FlipFluidDomain domain = region.domain();
		float cellSize = domain.config().cellSize();
		AABB bounds = new AABB(
				origin.getX(), origin.getY(), origin.getZ(),
				origin.getX() + domain.sizeX() * cellSize,
				origin.getY() + domain.sizeY() * cellSize,
				origin.getZ() + domain.sizeZ() * cellSize
		);
		int affected = 0;
		for (Entity entity : level.getEntities((Entity) null, bounds, this::canAffectEntity)) {
			EntityFluidContact contact = sampleEntityContact(entity);
			if (contact.submergedFraction() <= 0.0) continue;
			applyFluidForce(entity, contact);
			affected++;
		}
		return affected;
	}

	private boolean canAffectEntity(Entity entity) {
		if (!entity.isAlive() || entity.isRemoved() || entity.noPhysics || entity.isPassenger() || entity.isInWater()) return false;
		return !(entity instanceof Player player) || (!player.isSpectator() && !player.getAbilities().flying);
	}

	private EntityFluidContact sampleEntityContact(Entity entity) {
		FlipFluidDomain domain = domain();
		BlockPos origin = origin();
		AABB box = entity.getBoundingBox();
		int totalSamples = ENTITY_SAMPLES_XZ * ENTITY_SAMPLES_Y * ENTITY_SAMPLES_XZ;
		int submergedSamples = 0;
		double velocityX = 0.0, velocityY = 0.0, velocityZ = 0.0;

		for (int sampleZ = 0; sampleZ < ENTITY_SAMPLES_XZ; sampleZ++) {
			for (int sampleY = 0; sampleY < ENTITY_SAMPLES_Y; sampleY++) {
				for (int sampleX = 0; sampleX < ENTITY_SAMPLES_XZ; sampleX++) {
					double worldX = sampleCoordinate(box.minX, box.maxX, sampleX, ENTITY_SAMPLES_XZ);
					double worldY = sampleCoordinate(box.minY, box.maxY, sampleY, ENTITY_SAMPLES_Y);
					double worldZ = sampleCoordinate(box.minZ, box.maxZ, sampleZ, ENTITY_SAMPLES_XZ);
					float localX = (float) (worldX - origin.getX());
					float localY = (float) (worldY - origin.getY());
					float localZ = (float) (worldZ - origin.getZ());
					if (!domain.containsFluidAt(localX, localY, localZ)) continue;
					FlipFluidDomain.VelocitySample velocity = domain.sampleVelocity(localX, localY, localZ);
					velocityX += velocity.x();
					velocityY += velocity.y();
					velocityZ += velocity.z();
					submergedSamples++;
				}
			}
		}

		if (submergedSamples == 0) return EntityFluidContact.DRY;
		double inverseWetSamples = 1.0 / submergedSamples;
		return new EntityFluidContact(
				(double) submergedSamples / totalSamples,
				new Vec3(velocityX * inverseWetSamples, velocityY * inverseWetSamples, velocityZ * inverseWetSamples)
		);
	}

	private static double sampleCoordinate(double minimum, double maximum, int sample, int count) {
		return minimum + (maximum - minimum) * (sample + 0.5) / count;
	}

	private static void applyFluidForce(Entity entity, EntityFluidContact contact) {
		double submerged = contact.submergedFraction();
		double drag = FULL_SUBMERGENCE_DRAG * submerged;
		Vec3 targetVelocity = contact.fluidVelocity().scale(1.0 / SIMULATION_TICKS_PER_SECOND);
		Vec3 current = entity.getDeltaMovement();
		Vec3 next = current.add(targetVelocity.subtract(current).scale(drag));
		if (!entity.isNoGravity()) {
			double buoyancy = Math.min(0.20, entity.getGravity() * submerged / NEUTRAL_SUBMERGENCE);
			next = next.add(0.0, buoyancy, 0.0);
		}
		entity.setDeltaMovement(next);
		entity.hurtMarked = true;
		if (submerged > 0.15) entity.resetFallDistance();
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
		sendSnapshot(player.level());
		return new WaterEditResult(true, target, requested, changed, false, null);
	}

	/** Re-samples Minecraft collision shapes at the simulation's subcell resolution. */
	public FluidRegion.CollisionStats refreshCollisions(ServerLevel level) {
		FluidRegion region = activeRegion();
		if (region == null || !level.dimension().equals(region.dimension())) return FluidRegion.CollisionStats.EMPTY;
		if (!ensureRegionAvailable(level, region)) return region.lastCollisionStats();
		FlipFluidDomain domain = region.domain();
		BlockPos origin = region.origin();
		FluidRegionFootprint footprint = region.footprint();
		int blockSizeX = domain.sizeX() / CELLS_PER_BLOCK;
		int blockSizeY = domain.sizeY() / CELLS_PER_BLOCK;
		int blockSizeZ = domain.sizeZ() / CELLS_PER_BLOCK;
		int changedCells = 0;
		float displaced = 0.0F;
		float escaped = 0.0F;
		boolean markerBudgetLimited = false;
		double inset = 1.0E-5;

		for (int blockZ = 0; blockZ < blockSizeZ; blockZ++) {
			for (int blockY = 0; blockY < blockSizeY; blockY++) {
				for (int blockX = 0; blockX < blockSizeX; blockX++) {
					BlockPos pos = origin.offset(blockX, blockY, blockZ);
					boolean outsideScannedInterior = footprint != null && !footprint.containsInterior(pos);
					BlockState state = outsideScannedInterior ? Blocks.BEDROCK.defaultBlockState() : level.getBlockState(pos);
					boolean fullBlock = outsideScannedInterior || state.isCollisionShapeFullBlock(level, pos);
					VoxelShape shape = fullBlock ? Shapes.block() : state.getCollisionShape(level, pos);
					float displacedInBlock = 0.0F;

					for (int localZ = 0; localZ < CELLS_PER_BLOCK; localZ++) {
						for (int localY = 0; localY < CELLS_PER_BLOCK; localY++) {
							for (int localX = 0; localX < CELLS_PER_BLOCK; localX++) {
								int cellX = blockX * CELLS_PER_BLOCK + localX;
								int cellY = blockY * CELLS_PER_BLOCK + localY;
								int cellZ = blockZ * CELLS_PER_BLOCK + localZ;
								boolean desired = fullBlock || intersectsSubcell(shape, localX, localY, localZ, inset);
								if (domain.isSolid(cellX, cellY, cellZ) == desired) continue;
								displacedInBlock += domain.setSolid(cellX, cellY, cellZ, desired);
								changedCells++;
							}
						}
					}

					if (displacedInBlock > 0.0F) {
						displaced += displacedInBlock;
						int centerX = blockX * CELLS_PER_BLOCK + CELLS_PER_BLOCK / 2;
						int centerY = blockY * CELLS_PER_BLOCK + CELLS_PER_BLOCK / 2;
						int centerZ = blockZ * CELLS_PER_BLOCK + CELLS_PER_BLOCK / 2;
						FlipFluidDomain.VolumeAddition restoration = domain.addVolumeNearBudgeted(
								centerX, centerY, centerZ, displacedInBlock, 8
						);
						escaped += displacedInBlock - restoration.addedVolume();
						markerBudgetLimited |= restoration.markerBudgetLimited();
					}
				}
			}
		}

		FluidRegion.CollisionStats stats = new FluidRegion.CollisionStats(
				changedCells, displaced, escaped, markerBudgetLimited
		);
		region.setLastCollisionStats(stats);
		return stats;
	}

	private static boolean intersectsSubcell(VoxelShape shape, int localX, int localY, int localZ, double inset) {
		if (shape.isEmpty()) return false;
		double size = 1.0 / CELLS_PER_BLOCK;
		double minX = localX * size + inset, minY = localY * size + inset, minZ = localZ * size + inset;
		double maxX = (localX + 1) * size - inset, maxY = (localY + 1) * size - inset, maxZ = (localZ + 1) * size - inset;
		return Shapes.joinIsNotEmpty(shape, Shapes.box(minX, minY, minZ, maxX, maxY, maxZ), BooleanOp.AND);
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
		FluidRegion region = activeRegion();
		if (region == null) {
			return "没有活动流体域 | controller flag " + (controllerFeatureEnabled ? "on" : "off")
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
				"%s | %s | %s | origin %s | grid %dx%dx%d | sim %.1f Hz | water %.2f blocks (%.2f cells) | particles %d/%d in %d cells | visible %d, dense %d | takeover %d, lock %s | entities %s/%d | maxP %.1f | collision Δ%d, escaped %.3f%s | %s",
				region.paused() ? "暂停" : "运行",
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
		for (ServerPlayer player : level.players()) {
			if (ServerPlayNetworking.canSend(player, FluidSnapshotPayload.TYPE)) ServerPlayNetworking.send(player, payload);
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
			boolean disabled,
			String failure,
			int interiorBlocks,
			int sizeX,
			int sizeY,
			int sizeZ,
			int requiredChunks
	) {
		private static final ControllerActivation DISABLED = new ControllerActivation(
				false, true, "controller feature flag is disabled", 0, 0, 0, 0, 0
		);

		private static ControllerActivation failed(String failure) {
			return new ControllerActivation(false, false, failure, 0, 0, 0, 0, 0);
		}

		private static ControllerActivation succeeded(FluidRegionFootprint footprint) {
			return new ControllerActivation(
					true, false, null, footprint.interiorBlocks().size(),
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

	private record EntityFluidContact(double submergedFraction, Vec3 fluidVelocity) {
		private static final EntityFluidContact DRY = new EntityFluidContact(0.0, Vec3.ZERO);
	}
}

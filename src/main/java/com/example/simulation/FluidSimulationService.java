package com.example.simulation;

import com.example.RealWaterMod;
import com.example.fluid.FlipFluidDomain;
import com.example.fluid.FluidSolverConfig;
import com.example.network.FluidSnapshotPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/** Owns one bounded prototype domain per running server. Nothing is persisted yet. */
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
	private static final Map<MinecraftServer, FluidSimulationService> SERVICES = new WeakHashMap<>();

	private final Map<BlockPos, BlockState> takeoverOriginalBlocks = new HashMap<>();
	private FlipFluidDomain domain;
	private BlockPos origin = BlockPos.ZERO;
	private ResourceKey<Level> dimension;
	private ResourceKey<Level> takeoverDimension;
	private boolean paused = true;
	private long tick;
	private double lastStepMilliseconds;
	private FlipFluidDomain.StepStats lastStats;
	private CollisionStats lastCollisionStats = CollisionStats.EMPTY;
	private boolean entityPhysicsEnabled = true;
	private boolean debugOverlays;
	private int lastAffectedEntities;
	private int stepIntervalTicks = 1;
	private int hardOverrunStreak;
	private int recoveryStreak;

	private FluidSimulationService() { }

	public static FluidSimulationService get(MinecraftServer server) {
		return SERVICES.computeIfAbsent(server, ignored -> new FluidSimulationService());
	}

	public static void removeServer(MinecraftServer server) {
		FluidSimulationService service = SERVICES.remove(server);
		if (service != null) service.restoreTakeover(server);
	}

	public boolean exists() {
		return domain != null;
	}

	public boolean paused() {
		return paused;
	}

	public void createDemo(ServerPlayer player) {
		Vec3 look = player.getLookAngle();
		BlockPos center = BlockPos.containing(player.getX() + look.x * 9.0, player.getY(), player.getZ() + look.z * 9.0);
		origin = center.offset(-BLOCK_SIZE_X / 2, -2, -BLOCK_SIZE_Z / 2);
		ServerLevel level = player.level();
		restoreTakeover(level.getServer());
		createEmpty(level);
		refreshCollisions(level);

		int fillX = domain.sizeX() * 2 / 5;
		int fillY = domain.sizeY() * 3 / 4;
		for (int z = 0; z < domain.sizeZ(); z++) {
			for (int y = 0; y < fillY; y++) {
				for (int x = 0; x < fillX; x++) {
					if (!domain.isSolid(x, y, z)) domain.setVolume(x, y, z, 1.0F);
				}
			}
		}
		paused = true;
		sendSnapshot(level);
	}

	public CaptureResult captureNearbyWater(ServerPlayer player, boolean takeover) {
		ServerLevel level = player.level();
		restoreTakeover(level.getServer());
		origin = player.blockPosition().offset(-BLOCK_SIZE_X / 2, -2, -BLOCK_SIZE_Z / 2);
		createEmpty(level);
		refreshCollisions(level);
		int wetCells = 0;
		float cellSize = domain.config().cellSize();

		for (int z = 0; z < domain.sizeZ(); z++) {
			for (int y = 0; y < domain.sizeY(); y++) {
				for (int x = 0; x < domain.sizeX(); x++) {
					double worldX = origin.getX() + (x + 0.5) * cellSize;
					double worldY = origin.getY() + (y + 0.5) * cellSize;
					double worldZ = origin.getZ() + (z + 0.5) * cellSize;
					BlockPos blockPos = BlockPos.containing(worldX, worldY, worldZ);
					BlockState state = level.getBlockState(blockPos);
					if (domain.isSolid(x, y, z)) continue;
					FluidState fluid = state.getFluidState();
					if (!fluid.is(FluidTags.WATER)) continue;
					float waterHeight = fluid.getHeight(level, blockPos);
					double cellBottom = origin.getY() + y * cellSize;
					double localBottom = cellBottom - Math.floor(cellBottom);
					float fraction = Mth.clamp((float) ((waterHeight - localBottom) / cellSize), 0.0F, 1.0F);
					if (fraction > 0.0F) {
						domain.setVolume(x, y, z, fraction);
						wetCells++;
					}
				}
			}
		}
		int removedBlocks = takeover ? beginTakeover(level) : 0;
		paused = true;
		sendSnapshot(level);
		return new CaptureResult(wetCells, removedBlocks);
	}

	/** Temporarily replaces only standalone vanilla water blocks, never waterlogged blocks. */
	private int beginTakeover(ServerLevel level) {
		takeoverDimension = level.dimension();
		int removed = 0;
		for (int blockZ = 0; blockZ < BLOCK_SIZE_Z; blockZ++) for (int blockY = 0; blockY < BLOCK_SIZE_Y; blockY++) for (int blockX = 0; blockX < BLOCK_SIZE_X; blockX++) {
			BlockPos pos = origin.offset(blockX, blockY, blockZ);
			BlockState state = level.getBlockState(pos);
			if (!state.is(Blocks.WATER)) continue;
			if (level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS)) {
				takeoverOriginalBlocks.put(pos.immutable(), state);
				removed++;
			}
		}
		if (removed == 0) takeoverDimension = null;
		return removed;
	}

	public RestorationStats restoreTakeover(MinecraftServer server) {
		if (takeoverOriginalBlocks.isEmpty()) {
			takeoverDimension = null;
			return RestorationStats.EMPTY;
		}
		ServerLevel level = takeoverDimension == null ? null : server.getLevel(takeoverDimension);
		if (level == null) return new RestorationStats(0, takeoverOriginalBlocks.size());
		int restored = 0, skipped = 0;
		for (Map.Entry<BlockPos, BlockState> entry : takeoverOriginalBlocks.entrySet()) {
			BlockState current = level.getBlockState(entry.getKey());
			if (!current.isAir() && !current.is(Blocks.WATER)) {
				skipped++;
				continue;
			}
			if (level.setBlock(entry.getKey(), entry.getValue(), Block.UPDATE_ALL)) restored++;
			else skipped++;
		}
		takeoverOriginalBlocks.clear();
		takeoverDimension = null;
		return new RestorationStats(restored, skipped);
	}

	private void createEmpty(ServerLevel level) {
		float cellSize = 1.0F / CELLS_PER_BLOCK;
		FluidSolverConfig config = new FluidSolverConfig(
				cellSize, 1_000.0F, 9.81F, 1.0F / 20.0F,
				120, 1.0E-4F, 0.40F, 2.0E-3F, 0.80F
		);
		domain = new FlipFluidDomain(
				BLOCK_SIZE_X * CELLS_PER_BLOCK,
				BLOCK_SIZE_Y * CELLS_PER_BLOCK,
				BLOCK_SIZE_Z * CELLS_PER_BLOCK,
				config
		);
		dimension = level.dimension();
		lastStats = null;
		lastStepMilliseconds = 0.0;
		tick = 0;
		stepIntervalTicks = 1;
		hardOverrunStreak = 0;
		recoveryStreak = 0;
	}

	public void setPaused(boolean value) {
		paused = value;
	}

	public boolean entityPhysicsEnabled() {
		return entityPhysicsEnabled;
	}

	public void setEntityPhysicsEnabled(boolean enabled) {
		entityPhysicsEnabled = enabled;
		if (!enabled) lastAffectedEntities = 0;
	}

	public boolean debugOverlays() {
		return debugOverlays;
	}

	public void setDebugOverlays(boolean enabled, ServerLevel level) {
		debugOverlays = enabled;
		sendSnapshot(level);
	}

	public int step(ServerLevel level, int count) {
		if (domain == null || !level.dimension().equals(dimension)) return 0;
		for (int i = 0; i < count; i++) stepOnce();
		sendSnapshot(level);
		return count;
	}

	public void tick(MinecraftServer server) {
		if (domain == null) return;
		ServerLevel level = server.getLevel(dimension);
		if (level == null) return;
		maintainTakeover(level);
		tick++;
		boolean collisionsChanged = false;
		if (tick % COLLISION_REFRESH_INTERVAL_TICKS == 0) {
			collisionsChanged = refreshCollisions(level).changedCells() > 0;
		}
		lastAffectedEntities = entityPhysicsEnabled ? applyEntityForces(level) : 0;
		if (paused) {
			if (collisionsChanged) sendSnapshot(level);
			return;
		}
		if (tick % stepIntervalTicks != 0) {
			if (collisionsChanged) sendSnapshot(level);
			return;
		}
		stepOnce();
		updateStepPacing();
		if (hardOverrunStreak >= HARD_PAUSE_STREAK) {
			paused = true;
			RealWaterMod.LOGGER.warn("Auto-paused fluid domain after {} consecutive steps above {} ms", HARD_PAUSE_STREAK, HARD_PAUSE_MILLIS);
		}
		if (tick % SNAPSHOT_INTERVAL_TICKS == 0 || paused) sendSnapshot(level);
	}

	private void maintainTakeover(ServerLevel level) {
		if (takeoverOriginalBlocks.isEmpty() || !level.dimension().equals(takeoverDimension)) return;
		for (BlockPos pos : takeoverOriginalBlocks.keySet()) {
			if (level.getBlockState(pos).is(Blocks.WATER)) {
				level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS);
			}
		}
	}

	private void stepOnce() {
		long start = System.nanoTime();
		lastStats = domain.step();
		lastStepMilliseconds = (System.nanoTime() - start) / 1_000_000.0;
	}

	private void updateStepPacing() {
		if (lastStepMilliseconds > HARD_PAUSE_MILLIS) hardOverrunStreak++;
		else hardOverrunStreak = 0;

		int requiredInterval = Mth.clamp((int) Math.ceil(lastStepMilliseconds / STEP_BUDGET_MILLIS), 1, MAX_STEP_INTERVAL_TICKS);
		if (requiredInterval > stepIntervalTicks) {
			stepIntervalTicks = requiredInterval;
			recoveryStreak = 0;
			RealWaterMod.LOGGER.info("Reduced fluid simulation rate to approximately {} Hz after a {} ms step",
					20 / stepIntervalTicks, String.format("%.1f", lastStepMilliseconds));
		} else if (requiredInterval < stepIntervalTicks) {
			recoveryStreak++;
			if (recoveryStreak >= RECOVERY_STEPS) {
				stepIntervalTicks--;
				recoveryStreak = 0;
			}
		} else {
			recoveryStreak = 0;
		}
	}

	private int applyEntityForces(ServerLevel level) {
		AABB bounds = new AABB(
				origin.getX(), origin.getY(), origin.getZ(),
				origin.getX() + BLOCK_SIZE_X,
				origin.getY() + BLOCK_SIZE_Y,
				origin.getZ() + BLOCK_SIZE_Z
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
		if (domain == null || !player.level().dimension().equals(dimension)) return WaterEditResult.NO_DOMAIN;
		CellTarget target = findAimedCell(player, false);
		if (target == null) return WaterEditResult.NO_TARGET;
		float requested = buckets * CELL_VOLUMES_PER_BLOCK;
		float changed = domain.addVolumeNear(target.x(), target.y(), target.z(), requested, 6);
		sendSnapshot(player.level());
		return new WaterEditResult(true, target, requested, changed);
	}

	public WaterEditResult drainWater(ServerPlayer player, int buckets) {
		if (domain == null || !player.level().dimension().equals(dimension)) return WaterEditResult.NO_DOMAIN;
		CellTarget target = findAimedCell(player, true);
		if (target == null) return WaterEditResult.NO_TARGET;
		float requested = buckets * CELL_VOLUMES_PER_BLOCK;
		float changed = domain.removeVolumeNear(target.x(), target.y(), target.z(), requested, 6);
		sendSnapshot(player.level());
		return new WaterEditResult(true, target, requested, changed);
	}

	/** Re-samples Minecraft collision shapes at the simulation's subcell resolution. */
	public CollisionStats refreshCollisions(ServerLevel level) {
		if (domain == null || !level.dimension().equals(dimension)) return CollisionStats.EMPTY;
		int changedCells = 0;
		float displaced = 0.0F;
		float escaped = 0.0F;
		double inset = 1.0E-5;

		for (int blockZ = 0; blockZ < BLOCK_SIZE_Z; blockZ++) {
			for (int blockY = 0; blockY < BLOCK_SIZE_Y; blockY++) {
				for (int blockX = 0; blockX < BLOCK_SIZE_X; blockX++) {
					BlockPos pos = origin.offset(blockX, blockY, blockZ);
					BlockState state = level.getBlockState(pos);
					boolean fullBlock = state.isCollisionShapeFullBlock(level, pos);
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
								if (desired) displacedInBlock += domain.volume(cellX, cellY, cellZ);
								domain.setSolid(cellX, cellY, cellZ, desired);
								changedCells++;
							}
						}
					}

					if (displacedInBlock > 0.0F) {
						displaced += displacedInBlock;
						int centerX = blockX * CELLS_PER_BLOCK + CELLS_PER_BLOCK / 2;
						int centerY = blockY * CELLS_PER_BLOCK + CELLS_PER_BLOCK / 2;
						int centerZ = blockZ * CELLS_PER_BLOCK + CELLS_PER_BLOCK / 2;
						float restored = domain.addVolumeNear(centerX, centerY, centerZ, displacedInBlock, 8);
						escaped += displacedInBlock - restored;
					}
				}
			}
		}

		lastCollisionStats = new CollisionStats(changedCells, displaced, escaped);
		return lastCollisionStats;
	}

	private static boolean intersectsSubcell(VoxelShape shape, int localX, int localY, int localZ, double inset) {
		if (shape.isEmpty()) return false;
		double size = 1.0 / CELLS_PER_BLOCK;
		double minX = localX * size + inset, minY = localY * size + inset, minZ = localZ * size + inset;
		double maxX = (localX + 1) * size - inset, maxY = (localY + 1) * size - inset, maxZ = (localZ + 1) * size - inset;
		return Shapes.joinIsNotEmpty(shape, Shapes.box(minX, minY, minZ, maxX, maxY, maxZ), BooleanOp.AND);
	}

	private CellTarget findAimedCell(ServerPlayer player, boolean requireFluid) {
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
		float h = domain.config().cellSize();
		int x = Mth.floor((point.x - origin.getX()) / h);
		int y = Mth.floor((point.y - origin.getY()) / h);
		int z = Mth.floor((point.z - origin.getZ()) / h);
		if (x < 0 || x >= domain.sizeX() || y < 0 || y >= domain.sizeY() || z < 0 || z >= domain.sizeZ()) return null;
		return new CellTarget(x, y, z);
	}

	public RestorationStats remove(MinecraftServer server) {
		RestorationStats restoration = restoreTakeover(server);
		domain = null;
		dimension = null;
		paused = true;
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (ServerPlayNetworking.canSend(player, FluidSnapshotPayload.TYPE)) ServerPlayNetworking.send(player, FluidSnapshotPayload.clear());
		}
		return restoration;
	}

	public String status() {
		if (domain == null) return "没有活动流体域";
		String stats = lastStats == null
				? "尚未步进"
				: String.format("%.2f ms, substeps %d, PCG %d, residual %.4g",
						lastStepMilliseconds, lastStats.substeps(), lastStats.pressureIterations(), lastStats.pressureResidual());
		float cellVolumes = domain.totalVolume();
		FlipFluidDomain.VolumeDistribution distribution = domain.volumeDistribution(8.0F / 255.0F);
		return String.format(
				"%s | origin %s | grid %dx%dx%d | sim %.1f Hz | water %.2f blocks (%.2f cells) | particles %d | visible %d, dense %d | takeover %d | entities %s/%d | maxP %.1f | collision Δ%d, escaped %.3f | %s",
				paused ? "暂停" : "运行",
				origin.toShortString(), domain.sizeX(), domain.sizeY(), domain.sizeZ(),
				20.0 / stepIntervalTicks,
				cellVolumes / CELL_VOLUMES_PER_BLOCK, cellVolumes,
				domain.particleCount(),
				distribution.visibleCells(), distribution.denseCells(), takeoverOriginalBlocks.size(), entityPhysicsEnabled ? "on" : "off", lastAffectedEntities,
				domain.maximumPressure(),
				lastCollisionStats.changedCells(), lastCollisionStats.escapedCellVolume() / CELL_VOLUMES_PER_BLOCK, stats
		);
	}

	public void sendSnapshot(ServerLevel level) {
		if (domain == null || !level.dimension().equals(dimension)) return;
		FluidSnapshotPayload payload = new FluidSnapshotPayload(
				origin, domain.config().cellSize(), domain.sizeX(), domain.sizeY(), domain.sizeZ(),
				domain.copyQuantizedVolume(), domain.copySolidMask(), debugOverlays
		);
		for (ServerPlayer player : level.players()) {
			if (ServerPlayNetworking.canSend(player, FluidSnapshotPayload.TYPE)) ServerPlayNetworking.send(player, payload);
		}
	}

	public record CellTarget(int x, int y, int z) { }

	public record CaptureResult(int wetCells, int removedVanillaBlocks) { }

	public record RestorationStats(int restoredBlocks, int skippedBlocks) {
		private static final RestorationStats EMPTY = new RestorationStats(0, 0);
	}

	public record WaterEditResult(boolean domainExists, CellTarget target, float requestedCellVolume, float changedCellVolume) {
		private static final WaterEditResult NO_DOMAIN = new WaterEditResult(false, null, 0.0F, 0.0F);
		private static final WaterEditResult NO_TARGET = new WaterEditResult(true, null, 0.0F, 0.0F);

		public float changedBlocks() {
			return changedCellVolume / CELL_VOLUMES_PER_BLOCK;
		}
	}

	public record CollisionStats(int changedCells, float displacedCellVolume, float escapedCellVolume) {
		private static final CollisionStats EMPTY = new CollisionStats(0, 0.0F, 0.0F);
	}

	private record EntityFluidContact(double submergedFraction, Vec3 fluidVelocity) {
		private static final EntityFluidContact DRY = new EntityFluidContact(0.0, Vec3.ZERO);
	}
}

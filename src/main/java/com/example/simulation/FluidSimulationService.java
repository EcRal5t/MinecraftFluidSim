package com.example.simulation;

import com.example.RealWaterMod;
import com.example.fluid.FluidDomain;
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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.WeakHashMap;

/** Owns one bounded prototype domain per running server. Nothing is persisted yet. */
public final class FluidSimulationService {
	public static final int BLOCK_SIZE_X = 12;
	public static final int BLOCK_SIZE_Y = 8;
	public static final int BLOCK_SIZE_Z = 12;
	public static final int CELLS_PER_BLOCK = 2;
	private static final int SNAPSHOT_INTERVAL_TICKS = 4;
	private static final double AUTO_PAUSE_MILLIS = 75.0;
	private static final Map<MinecraftServer, FluidSimulationService> SERVICES = new WeakHashMap<>();

	private FluidDomain domain;
	private BlockPos origin = BlockPos.ZERO;
	private ResourceKey<Level> dimension;
	private boolean paused = true;
	private long tick;
	private double lastStepMilliseconds;
	private FluidDomain.StepStats lastStats;

	private FluidSimulationService() { }

	public static FluidSimulationService get(MinecraftServer server) {
		return SERVICES.computeIfAbsent(server, ignored -> new FluidSimulationService());
	}

	public static void removeServer(MinecraftServer server) {
		SERVICES.remove(server);
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
		createEmpty(level);
		captureSolids(level);

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

	public int captureNearbyWater(ServerPlayer player) {
		origin = player.blockPosition().offset(-BLOCK_SIZE_X / 2, -2, -BLOCK_SIZE_Z / 2);
		ServerLevel level = player.level();
		createEmpty(level);
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
					if (state.isCollisionShapeFullBlock(level, blockPos)) {
						domain.setSolid(x, y, z, true);
						continue;
					}
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
		paused = true;
		sendSnapshot(level);
		return wetCells;
	}

	private void createEmpty(ServerLevel level) {
		float cellSize = 1.0F / CELLS_PER_BLOCK;
		FluidSolverConfig config = new FluidSolverConfig(
				cellSize, 1_000.0F, 9.81F, 1.0F / 20.0F,
				120, 1.0E-4F, 0.40F, 1.0E-4F
		);
		domain = new FluidDomain(
				BLOCK_SIZE_X * CELLS_PER_BLOCK,
				BLOCK_SIZE_Y * CELLS_PER_BLOCK,
				BLOCK_SIZE_Z * CELLS_PER_BLOCK,
				config
		);
		dimension = level.dimension();
		lastStats = null;
		lastStepMilliseconds = 0.0;
		tick = 0;
	}

	private void captureSolids(ServerLevel level) {
		float cellSize = domain.config().cellSize();
		for (int z = 0; z < domain.sizeZ(); z++) for (int y = 0; y < domain.sizeY(); y++) for (int x = 0; x < domain.sizeX(); x++) {
			BlockPos pos = BlockPos.containing(
					origin.getX() + (x + 0.5) * cellSize,
					origin.getY() + (y + 0.5) * cellSize,
					origin.getZ() + (z + 0.5) * cellSize
			);
			if (level.getBlockState(pos).isCollisionShapeFullBlock(level, pos)) domain.setSolid(x, y, z, true);
		}
	}

	public void setPaused(boolean value) {
		paused = value;
	}

	public int step(ServerLevel level, int count) {
		if (domain == null || level.dimension() != dimension) return 0;
		for (int i = 0; i < count; i++) stepOnce();
		sendSnapshot(level);
		return count;
	}

	public void tick(MinecraftServer server) {
		if (domain == null || paused) return;
		ServerLevel level = server.getLevel(dimension);
		if (level == null) return;
		stepOnce();
		tick++;
		if (lastStepMilliseconds > AUTO_PAUSE_MILLIS) {
			paused = true;
			RealWaterMod.LOGGER.warn("Auto-paused fluid domain after a {} ms step", String.format("%.1f", lastStepMilliseconds));
		}
		if (tick % SNAPSHOT_INTERVAL_TICKS == 0 || paused) sendSnapshot(level);
	}

	private void stepOnce() {
		long start = System.nanoTime();
		lastStats = domain.step();
		lastStepMilliseconds = (System.nanoTime() - start) / 1_000_000.0;
	}

	public void remove(MinecraftServer server) {
		domain = null;
		dimension = null;
		paused = true;
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (ServerPlayNetworking.canSend(player, FluidSnapshotPayload.TYPE)) ServerPlayNetworking.send(player, FluidSnapshotPayload.clear());
		}
	}

	public String status() {
		if (domain == null) return "没有活动流体域";
		String stats = lastStats == null
				? "尚未步进"
				: String.format("%.2f ms, PCG %d, residual %.4g", lastStepMilliseconds, lastStats.pressureIterations(), lastStats.pressureResidual());
		return String.format(
				"%s | origin %s | grid %dx%dx%d | water %.2f | maxP %.1f | %s",
				paused ? "暂停" : "运行",
				origin.toShortString(), domain.sizeX(), domain.sizeY(), domain.sizeZ(),
				domain.totalVolume(), domain.maximumPressure(), stats
		);
	}

	public void sendSnapshot(ServerLevel level) {
		if (domain == null || level.dimension() != dimension) return;
		FluidSnapshotPayload payload = new FluidSnapshotPayload(
				origin, domain.config().cellSize(), domain.sizeX(), domain.sizeY(), domain.sizeZ(), domain.copyQuantizedVolume()
		);
		for (ServerPlayer player : level.players()) {
			if (ServerPlayNetworking.canSend(player, FluidSnapshotPayload.TYPE)) ServerPlayNetworking.send(player, payload);
		}
	}
}

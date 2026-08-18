package com.example.gpuprobe;

import com.example.fluid.FlipFluidDomain;
import com.example.fluid.FluidSolverConfig;
import com.example.network.FluidSnapshotPayload;
import com.example.simulation.FluidSimulationService;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

/** In-memory, world-read-free service for the optional GPU visualization demo. */
final class GpuProbeService {
	private static final int NX = 24, NY = 16, NZ = 24;
	private static final float H = 0.5F, DT = 1.0F / 20.0F, GRAVITY = 9.81F, RHO = 1_000.0F;
	private static final int PRESSURE_ITERATIONS = 96;
	private static final int SNAPSHOT_INTERVAL_TICKS = 4;
	private static final Map<MinecraftServer, GpuProbeService> SERVICES = new WeakHashMap<>();

	private Backend backend;
	private BlockPos origin = BlockPos.ZERO;
	private ResourceKey<Level> dimension;
	private boolean paused = true;
	private long tick;
	private int completedSteps;
	private int hardOverrunStreak;
	private String fallbackReason;
	private String failure;

	private GpuProbeService() { }

	static GpuProbeService get(MinecraftServer server) {
		return SERVICES.computeIfAbsent(server, ignored -> new GpuProbeService());
	}

	static void removeServer(MinecraftServer server) {
		GpuProbeService service = SERVICES.remove(server);
		if (service != null) service.closeBackend();
	}

	boolean exists() { return backend != null; }

	CreationResult createDemo(ServerPlayer player, int deviceIndex) {
		MinecraftServer server = player.level().getServer();
		if (FluidSimulationService.get(server).exists()) {
			return new CreationResult(false, false, "主 Real Water 域正在使用同一个可视化通道；请先 /realwater remove");
		}
		closeBackend();
		Vec3 look = player.getLookAngle();
		BlockPos center = BlockPos.containing(player.getX() + look.x * 9.0, player.getY(), player.getZ() + look.z * 9.0);
		origin = center.offset(-6, -2, -6);
		dimension = player.level().dimension();
		fallbackReason = null;
		failure = null;
		try {
			VulkanFlipPipeline pipeline = new VulkanFlipPipeline(gpuConfig(), deviceIndex);
			pipeline.initializeDamBreak();
			backend = new VulkanBackend(pipeline);
		} catch (RuntimeException | LinkageError exception) {
			fallbackReason = exception.getClass().getSimpleName() + ": " + safeMessage(exception);
			RealWaterGpuProbeMod.LOGGER.warn("GPU demo initialization failed; using isolated CPU fallback", exception);
			backend = new CpuBackend(createCpuDamBreak());
		}
		paused = true;
		tick = 0;
		completedSteps = 0;
		hardOverrunStreak = 0;
		sendSnapshot(player.level());
		return new CreationResult(true, backend instanceof VulkanBackend, fallbackReason);
	}

	void setPaused(boolean value) { paused = value; }

	int step(ServerLevel level, int count) {
		if (backend == null || !level.dimension().equals(dimension)) return 0;
		if (FluidSimulationService.get(level.getServer()).exists()) return 0;
		int completed = runManualLoop(count, this::stepOnce, () -> hardOverrunStreak);
		sendSnapshot(level);
		return completed;
	}

	static int runManualLoop(int count, BooleanSupplier step, IntSupplier hardOverrunStreak) {
		int completed = 0;
		for (int i = 0; i < count; i++) {
			if (!step.getAsBoolean()) break;
			completed++;
			// Manual stepping is intentionally available while paused. Stop only at the hard
			// overrun guard; the ordinary paused flag must not collapse "step N" to one step.
			if (hardOverrunStreak.getAsInt() >= 3) break;
		}
		return completed;
	}

	void tick(MinecraftServer server) {
		if (backend == null) return;
		if (FluidSimulationService.get(server).exists()) {
			paused = true;
			failure = "主 Real Water 域已启动，GPU probe 已暂停以避免快照冲突";
			return;
		}
		ServerLevel level = server.getLevel(dimension);
		if (level == null || paused) return;
		tick++;
		if (!stepOnce()) return;
		if (tick % SNAPSHOT_INTERVAL_TICKS == 0 || paused) sendSnapshot(level);
	}

	private boolean stepOnce() {
		try {
			backend.step();
			completedSteps++;
			if (backend.lastStepMilliseconds() > 200.0) hardOverrunStreak++; else hardOverrunStreak = 0;
			if (hardOverrunStreak >= 3) {
				paused = true;
				failure = "连续三步超过 200 ms，probe 已自动暂停";
			}
			return true;
		} catch (RuntimeException | LinkageError exception) {
			paused = true;
			failure = exception.getClass().getSimpleName() + ": " + safeMessage(exception);
			RealWaterGpuProbeMod.LOGGER.error("GPU probe step failed; isolated demo paused and main CPU mod is unaffected", exception);
			return false;
		}
	}

	String status(MinecraftServer server) {
		if (backend == null) return "没有活动 GPU probe 域；主 Real Water CPU 后端不受影响";
		String conflict = FluidSimulationService.get(server).exists() ? " | 与主域冲突，已暂停" : "";
		return String.format(Locale.ROOT,
				"%s | backend %s | origin %s | grid %dx%dx%d | water %.2f blocks (%.2f cells) | particles %d | step %.3f ms | steps %d | %s%s%s%s",
				paused ? "暂停" : "运行", backend.name(), origin.toShortString(), NX, NY, NZ,
				backend.volumeCells() / 8.0F, backend.volumeCells(), backend.particleCount(), backend.lastStepMilliseconds(), completedSteps,
				backend.diagnostics(),
				fallbackReason == null ? "" : " | CPU fallback: " + fallbackReason,
				failure == null ? "" : " | failure: " + failure,
				conflict);
	}

	void remove(MinecraftServer server) {
		closeBackend();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (ServerPlayNetworking.canSend(player, FluidSnapshotPayload.TYPE)) ServerPlayNetworking.send(player, FluidSnapshotPayload.clear());
		}
		FluidSimulationService main = FluidSimulationService.get(server);
		if (main.exists()) {
			for (ServerLevel level : server.getAllLevels()) main.sendSnapshot(level);
		}
	}

	private void sendSnapshot(ServerLevel level) {
		if (backend == null || !level.dimension().equals(dimension)) return;
		Snapshot snapshot = backend.snapshot();
		FluidSnapshotPayload payload = new FluidSnapshotPayload(origin, H, NX, NY, NZ,
				snapshot.density(), snapshot.markers(), snapshot.solids(), true);
		for (ServerPlayer player : level.players()) {
			if (ServerPlayNetworking.canSend(player, FluidSnapshotPayload.TYPE)) ServerPlayNetworking.send(player, payload);
		}
	}

	private void closeBackend() {
		if (backend != null) {
			try { backend.close(); }
			catch (RuntimeException | LinkageError exception) { RealWaterGpuProbeMod.LOGGER.warn("Failed to close GPU probe backend", exception); }
		}
		backend = null;
		dimension = null;
		paused = true;
	}

	static String benchmark(int deviceIndex, int blockSize, VulkanFlipPipeline.PressureSolver pressureSolver) {
		if (blockSize != 12 && blockSize != 32) throw new IllegalArgumentException("benchmark 只接受 12 或 32 方块边长");
		boolean large = blockSize == 32;
		int grid = large ? 64 : 24;
		int ny = large ? 64 : 16;
		int particles = large ? 2_097_152 : 24 * 16 * 24 * 8;
		int pressureIterations = switch (pressureSolver) {
			case JACOBI -> large ? 64 : PRESSURE_ITERATIONS;
			case RBGS_SOR -> large ? 64 : 48;
			case PCG -> large ? 64 : 32;
		};
		VulkanFlipPipeline.Config config = new VulkanFlipPipeline.Config(grid, ny, grid, H, DT, large ? 0.0F : GRAVITY, RHO,
				pressureIterations, particles);
		try (VulkanFlipPipeline pipeline = new VulkanFlipPipeline(config, deviceIndex,
				VulkanFlipPipeline.MemoryMode.DEVICE_LOCAL_STAGING, pressureSolver)) {
			if (large) pipeline.initializeFull(); else pipeline.initializeDamBreak();
			int warmup = large ? 1 : 3, measured = large ? 3 : 20;
			for (int i = 0; i < warmup; i++) pipeline.step();
			double total = 0.0, minimum = Double.POSITIVE_INFINITY, maximum = 0.0;
			for (int i = 0; i < measured; i++) {
				pipeline.step();
				double millis = pipeline.lastStepMilliseconds();
				total += millis; minimum = Math.min(minimum, millis); maximum = Math.max(maximum, millis);
			}
			VulkanFlipPipeline.Diagnostics diagnostics = pipeline.diagnostics();
			VulkanFlipPipeline.ReadbackStats readback = pipeline.measureReadback();
			return String.format(Locale.ROOT,
					"%s | memory %s | pressure %s/%d | %dx%dx%d grid | particles %d | %d steps avg %.3f ms (%.3f..%.3f) | divergence %.5f→%.5f | regular diagnostics 64 B/step | snapshot %d B %.3f ms | diagnostic full-state %d B %.3f ms",
					pipeline.deviceName(), pipeline.memoryMode().label(), pressureSolver.label(), pressureIterations,
					grid, ny, grid, pipeline.particleCount(), measured, total / measured, minimum, maximum,
					diagnostics.preProjectionMaximumDivergence(), diagnostics.maximumDivergence(),
					readback.compactBytes(), readback.compactNanos() / 1_000_000.0,
					readback.fullBytes(), readback.fullNanos() / 1_000_000.0);
		}
	}

	private static VulkanFlipPipeline.Config gpuConfig() {
		return new VulkanFlipPipeline.Config(NX, NY, NZ, H, DT, GRAVITY, RHO, PRESSURE_ITERATIONS, NX * NY * NZ * 8);
	}

	private static FlipFluidDomain createCpuDamBreak() {
		FluidSolverConfig config = new FluidSolverConfig(H, RHO, GRAVITY, DT, 200, 1.0E-5F, 0.45F, 2.0E-3F, 0.80F);
		FlipFluidDomain domain = new FlipFluidDomain(NX, NY, NZ, config);
		for (int z = 0; z < NZ; z++) for (int y = 0; y < NY * 3 / 4; y++) for (int x = 0; x < NX * 2 / 5; x++) domain.setVolume(x, y, z, 1.0F);
		return domain;
	}

	private static String safeMessage(Throwable throwable) {
		return throwable.getMessage() == null ? throwable.toString() : throwable.getMessage();
	}

	record CreationResult(boolean created, boolean gpu, String detail) { }
	private record Snapshot(byte[] density, byte[] markers, byte[] solids) { }

	private interface Backend extends AutoCloseable {
		String name();
		void step();
		int particleCount();
		float volumeCells();
		double lastStepMilliseconds();
		Snapshot snapshot();
		String diagnostics();
		@Override void close();
	}

	private static final class VulkanBackend implements Backend {
		private final VulkanFlipPipeline pipeline;
		private VulkanBackend(VulkanFlipPipeline pipeline) { this.pipeline = pipeline; }
		@Override public String name() { return "Vulkan/" + pipeline.memoryMode().label() + "/" + pipeline.pressureSolver().label() + "/" + pipeline.deviceName(); }
		@Override public void step() { pipeline.step(); }
		@Override public int particleCount() { return pipeline.particleCount(); }
		@Override public float volumeCells() { return pipeline.totalParticleVolume(); }
		@Override public double lastStepMilliseconds() { return pipeline.lastStepMilliseconds(); }
		@Override public Snapshot snapshot() {
			VulkanFlipPipeline.SnapshotData data = pipeline.snapshot();
			return new Snapshot(data.density(), data.markers(), data.solids());
		}
		@Override public String diagnostics() {
			VulkanFlipPipeline.Diagnostics d = pipeline.diagnostics();
			return String.format(Locale.ROOT, "diag readback 64 B/step | maxP %.1f | divergence %.5f→%.5f RMS %.5f | GPU volume %.3f cells",
					d.maximumPressure(), d.preProjectionMaximumDivergence(), d.maximumDivergence(), d.rmsDivergence(), d.particleVolume());
		}
		@Override public void close() { pipeline.close(); }
	}

	private static final class CpuBackend implements Backend {
		private final FlipFluidDomain domain;
		private double lastStepMilliseconds;
		private FlipFluidDomain.StepStats stats;
		private CpuBackend(FlipFluidDomain domain) { this.domain = domain; }
		@Override public String name() { return "CPU fallback"; }
		@Override public void step() {
			long started = System.nanoTime();
			stats = domain.step();
			lastStepMilliseconds = (System.nanoTime() - started) / 1_000_000.0;
		}
		@Override public int particleCount() { return domain.particleCount(); }
		@Override public float volumeCells() { return domain.totalVolume(); }
		@Override public double lastStepMilliseconds() { return lastStepMilliseconds; }
		@Override public Snapshot snapshot() { return new Snapshot(domain.copyQuantizedVolume(), domain.copyMarkerMask(), domain.copySolidMask()); }
		@Override public String diagnostics() {
			return stats == null ? "尚未步进" : String.format(Locale.ROOT, "maxP %.1f | PCG %d residual %.4g",
					domain.maximumPressure(), stats.pressureIterations(), stats.pressureResidual());
		}
		@Override public void close() { }
	}
}

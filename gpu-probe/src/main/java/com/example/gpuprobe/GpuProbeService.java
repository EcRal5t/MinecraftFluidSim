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

/** In-memory service for generated demos and explicit read-only world-collision snapshots. */
final class GpuProbeService {
	private static final int NX = 24, NY = 16, NZ = 24;
	private static final int BLOCKS_X = 12, BLOCKS_Y = 8, BLOCKS_Z = 12;
	private static final float H = 0.5F, DT = 1.0F / 20.0F, GRAVITY = 9.81F, RHO = 1_000.0F;
	private static final int PRESSURE_ITERATIONS = 96;
	private static final int SNAPSHOT_INTERVAL_TICKS = 4;
	private static final int CHECKPOINT_INTERVAL_STEPS = 20;
	private static final Map<MinecraftServer, GpuProbeService> SERVICES = new WeakHashMap<>();

	private Backend backend;
	private BlockPos origin = BlockPos.ZERO;
	private ResourceKey<Level> dimension;
	private boolean paused = true;
	private long tick;
	private int completedSteps;
	private int hardOverrunStreak;
	private int runtimeRecoveryCount;
	private double lastSchedulingWallMilliseconds;
	private String fallbackReason;
	private String failure;
	private String collisionDescription;
	private GpuProbeCheckpoint checkpoint;

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
		Vec3 look = player.getLookAngle();
		BlockPos center = BlockPos.containing(player.getX() + look.x * 9.0, player.getY(), player.getZ() + look.z * 9.0);
		BlockPos candidateOrigin = center.offset(-6, -2, -6);
		return createBackend(player, deviceIndex, candidateOrigin, null,
				VulkanFlipPipeline.PressureSolver.JACOBI, "collision generated/no world reads");
	}

	CreationResult createWorldDemo(ServerPlayer player, int deviceIndex) {
		MinecraftServer server = player.level().getServer();
		if (FluidSimulationService.get(server).exists()) {
			return new CreationResult(false, false, "主 Real Water 域正在使用同一个可视化通道；请先 /realwater remove");
		}
		Vec3 look = player.getLookAngle();
		BlockPos center = BlockPos.containing(player.getX() + look.x * 9.0, player.getY(), player.getZ() + look.z * 9.0);
		BlockPos candidateOrigin = center.offset(-6, -2, -6);
		WorldCollisionSnapshot.CaptureResult captured = WorldCollisionSnapshot.capture(
				player.level(), candidateOrigin, BLOCKS_X, BLOCKS_Y, BLOCKS_Z);
		if (!captured.captured()) return new CreationResult(false, false, captured.failure());
		String description = String.format(Locale.ROOT,
				"collision frozen-world/read-only %d solid cells in %d blocks, %d loaded chunks",
				captured.solidCellCount(), captured.solidBlockCount(), captured.loadedChunkCount());
		return createBackend(player, deviceIndex, candidateOrigin, captured.solidCells(),
				VulkanFlipPipeline.PressureSolver.MULTIGRID_TWO_LEVEL, description);
	}

	private CreationResult createBackend(ServerPlayer player, int deviceIndex, BlockPos candidateOrigin,
	                                     byte[] solidCells, VulkanFlipPipeline.PressureSolver pressureSolver,
	                                     String newCollisionDescription) {
		closeBackend();
		origin = candidateOrigin;
		dimension = player.level().dimension();
		collisionDescription = newCollisionDescription;
		fallbackReason = null;
		failure = null;
		checkpoint = null;
		VulkanFlipPipeline pipeline = null;
		try {
			pipeline = new VulkanFlipPipeline(gpuConfig(pressureSolver), deviceIndex,
					VulkanFlipPipeline.MemoryMode.DEVICE_LOCAL_STAGING, pressureSolver);
			if (solidCells == null) pipeline.initializeDamBreak(); else pipeline.initializeDamBreakWithSolids(solidCells);
			checkpoint = pipeline.captureCheckpoint(0L);
			backend = new VulkanBackend(pipeline);
		} catch (RuntimeException | LinkageError exception) {
			if (pipeline != null) {
				try { pipeline.close(); }
				catch (RuntimeException | LinkageError closeFailure) { exception.addSuppressed(closeFailure); }
			}
			fallbackReason = exception.getClass().getSimpleName() + ": " + safeMessage(exception);
			RealWaterGpuProbeMod.LOGGER.warn("GPU demo initialization failed; using isolated CPU fallback", exception);
			backend = new CpuBackend(createCpuDamBreak(solidCells));
		}
		paused = true;
		tick = 0;
		completedSteps = 0;
		hardOverrunStreak = 0;
		runtimeRecoveryCount = 0;
		lastSchedulingWallMilliseconds = 0.0;
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
		if (!stepOnce()) {
			sendSnapshot(level);
			return;
		}
		if (tick % SNAPSHOT_INTERVAL_TICKS == 0 || paused) sendSnapshot(level);
	}

	private boolean stepOnce() {
		long started = System.nanoTime();
		try {
			backend.step();
			int nextGeneration = Math.addExact(completedSteps, 1);
			if (backend instanceof VulkanBackend vulkan && checkpointDue(nextGeneration, checkpoint)) {
				GpuProbeCheckpoint candidate = vulkan.captureCheckpoint(nextGeneration);
				if (candidate.generation() != nextGeneration) throw new IllegalStateException("GPU checkpoint generation mismatch");
				checkpoint = candidate;
			}
			completedSteps = nextGeneration;
			lastSchedulingWallMilliseconds = (System.nanoTime() - started) / 1_000_000.0;
			if (lastSchedulingWallMilliseconds > 200.0) hardOverrunStreak++; else hardOverrunStreak = 0;
			if (hardOverrunStreak >= 3) {
				paused = true;
				failure = "连续三步超过 200 ms，probe 已自动暂停";
			}
			return true;
		} catch (RuntimeException | LinkageError exception) {
			lastSchedulingWallMilliseconds = (System.nanoTime() - started) / 1_000_000.0;
			recoverRuntimeFailure(exception, "step/checkpoint");
			return false;
		}
	}

	static boolean checkpointDue(int completedGeneration, GpuProbeCheckpoint current) {
		return current != null && completedGeneration - current.generation() >= CHECKPOINT_INTERVAL_STEPS;
	}

	private boolean recoverRuntimeFailure(Throwable failureCause, String operation) {
		paused = true;
		String cause = failureCause.getClass().getSimpleName() + ": " + safeMessage(failureCause);
		if (!(backend instanceof VulkanBackend failedVulkan)) {
			failure = operation + " failed without a recoverable GPU checkpoint: " + cause;
			RealWaterGpuProbeMod.LOGGER.error("CPU fallback operation failed; isolated demo paused", failureCause);
			return false;
		}
		if (checkpoint == null) {
			backend = null;
			failure = operation + " failed without a recoverable GPU checkpoint: " + cause;
			try { failedVulkan.close(); }
			catch (RuntimeException | LinkageError closeFailure) { failureCause.addSuppressed(closeFailure); }
			RealWaterGpuProbeMod.LOGGER.error("GPU probe operation failed without a recoverable checkpoint; backend disabled", failureCause);
			return false;
		}

		GpuProbeCheckpoint recoveryPoint = checkpoint;
		CpuBackend recovered;
		try {
			recovered = new CpuBackend(recoveryPoint.restore(cpuConfig()));
		} catch (RuntimeException recoveryFailure) {
			failureCause.addSuppressed(recoveryFailure);
			backend = null;
			failure = operation + " failed and CPU checkpoint restore was rejected: " + cause;
			try { failedVulkan.close(); }
			catch (RuntimeException | LinkageError closeFailure) { failureCause.addSuppressed(closeFailure); }
			RealWaterGpuProbeMod.LOGGER.error("GPU probe operation and CPU checkpoint recovery both failed; backend disabled", failureCause);
			return false;
		}

		backend = recovered;
		completedSteps = Math.toIntExact(recoveryPoint.generation());
		hardOverrunStreak = 0;
		runtimeRecoveryCount++;
		fallbackReason = String.format(Locale.ROOT, "runtime GPU failure; restored CPU checkpoint generation %d", recoveryPoint.generation());
		failure = String.format(Locale.ROOT,
				"%s failed (%s); rolled back to CPU checkpoint generation %d and paused",
				operation, cause, recoveryPoint.generation());
		try { failedVulkan.close(); }
		catch (RuntimeException | LinkageError closeFailure) { failureCause.addSuppressed(closeFailure); }
		RealWaterGpuProbeMod.LOGGER.error("GPU probe operation failed; restored isolated CPU fallback from the last complete checkpoint and paused", failureCause);
		return true;
	}

	String status(MinecraftServer server) {
		if (backend == null) return failure == null
				? "没有活动 GPU probe 域；主 Real Water CPU 后端不受影响"
				: "GPU probe 后端已停用；主 Real Water CPU 后端不受影响 | failure: " + failure;
		String diagnostics;
		try {
			diagnostics = backend.diagnostics();
		} catch (RuntimeException | LinkageError exception) {
			if (!recoverRuntimeFailure(exception, "status diagnostics") || backend == null) {
				return "GPU probe 后端已停用；主 Real Water CPU 后端不受影响 | failure: " + failure;
			}
			diagnostics = backend.diagnostics();
		}
		String conflict = FluidSimulationService.get(server).exists() ? " | 与主域冲突，已暂停" : "";
		String checkpointStatus = checkpoint == null ? "" : String.format(Locale.ROOT,
				" | checkpoint gen %d age %d steps CPU %d B/readback %d B | recoveries %d",
				checkpoint.generation(), completedSteps - checkpoint.generation(), checkpoint.cpuBytes(),
				checkpoint.gpuReadbackBytes(), runtimeRecoveryCount);
		return String.format(Locale.ROOT,
				"%s | backend %s | origin %s | grid %dx%dx%d | water %.2f blocks (%.2f cells) | particles %d | scheduling wall %.3f ms | steps %d | %s | %s%s%s%s%s",
				paused ? "暂停" : "运行", backend.name(), origin.toShortString(), NX, NY, NZ,
				backend.volumeCells() / 8.0F, backend.volumeCells(), backend.particleCount(), lastSchedulingWallMilliseconds, completedSteps,
				diagnostics,
				collisionDescription,
				fallbackReason == null ? "" : " | CPU fallback: " + fallbackReason,
				failure == null ? "" : " | failure: " + failure,
				checkpointStatus,
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
		Snapshot snapshot;
		try {
			snapshot = backend.snapshot();
		} catch (RuntimeException | LinkageError exception) {
			if (!recoverRuntimeFailure(exception, "snapshot readback") || backend == null) return;
			snapshot = backend.snapshot();
		}
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
		checkpoint = null;
		dimension = null;
		collisionDescription = null;
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
			case PCG, PCG_DIAGONAL -> large ? 64 : 32;
			case MULTIGRID_TWO_LEVEL, MULTIGRID_HIERARCHY -> 2;
		};
		VulkanFlipPipeline.Config config = new VulkanFlipPipeline.Config(grid, ny, grid, H, DT, large ? 0.0F : GRAVITY, RHO,
				pressureIterations, particles);
		try (VulkanFlipPipeline pipeline = new VulkanFlipPipeline(config, deviceIndex,
				VulkanFlipPipeline.MemoryMode.DEVICE_LOCAL_STAGING, pressureSolver)) {
			if (large) pipeline.initializeFull(); else pipeline.initializeDamBreak();
			int warmup = large ? 1 : 3, measured = large ? 3 : 20;
			for (int i = 0; i < warmup; i++) pipeline.step();
			pipeline.diagnostics();
			double schedulingTotal = 0.0, schedulingMinimum = Double.POSITIVE_INFINITY, schedulingMaximum = 0.0;
			double gpuTotal = 0.0, gpuMinimum = Double.POSITIVE_INFINITY, gpuMaximum = 0.0;
			pipeline.beginGpuTimingCapture();
			long batchStarted = System.nanoTime();
			for (int i = 0; i < measured; i++) {
				pipeline.step();
				double schedulingMillis = pipeline.lastStepMilliseconds();
				schedulingTotal += schedulingMillis;
				schedulingMinimum = Math.min(schedulingMinimum, schedulingMillis);
				schedulingMaximum = Math.max(schedulingMaximum, schedulingMillis);
			}
			long[] gpuNanos = pipeline.endGpuTimingCapture();
			double throughputWallAverage = (System.nanoTime() - batchStarted) / 1_000_000.0 / measured;
			if (gpuNanos.length != measured) throw new IllegalStateException("Vulkan timestamp capture count mismatch on " + pipeline.deviceName());
			for (long nanos : gpuNanos) {
				double gpuMillis = nanos / 1_000_000.0;
				gpuTotal += gpuMillis; gpuMinimum = Math.min(gpuMinimum, gpuMillis); gpuMaximum = Math.max(gpuMaximum, gpuMillis);
			}
			VulkanFlipPipeline.Diagnostics diagnostics = pipeline.diagnostics();
			VulkanFlipPipeline.ReadbackStats readback = pipeline.measureReadback();
			return String.format(Locale.ROOT,
					"%s | memory %s | pressure %s/%d | %dx%dx%d grid | particles %d | %d steps GPU timestamp avg %.3f ms (%.3f..%.3f), throughput wall avg %.3f ms, scheduling wall avg %.3f ms (%.3f..%.3f) | divergence %.5f→%.5f | regular diagnostics 64 B/step through 3-slot ring | snapshot %d B %.3f ms | diagnostic full-state %d B %.3f ms",
					pipeline.deviceName(), pipeline.memoryMode().label(), pressureSolver.label(), pressureIterations,
					grid, ny, grid, pipeline.particleCount(), measured, gpuTotal / measured, gpuMinimum, gpuMaximum,
					throughputWallAverage, schedulingTotal / measured, schedulingMinimum, schedulingMaximum,
					diagnostics.preProjectionMaximumDivergence(), diagnostics.maximumDivergence(),
					readback.compactBytes(), readback.compactNanos() / 1_000_000.0,
					readback.fullBytes(), readback.fullNanos() / 1_000_000.0);
		}
	}

	private static VulkanFlipPipeline.Config gpuConfig(VulkanFlipPipeline.PressureSolver pressureSolver) {
		int iterations = pressureSolver == VulkanFlipPipeline.PressureSolver.MULTIGRID_TWO_LEVEL
				|| pressureSolver == VulkanFlipPipeline.PressureSolver.MULTIGRID_HIERARCHY ? 2 : PRESSURE_ITERATIONS;
		return new VulkanFlipPipeline.Config(NX, NY, NZ, H, DT, GRAVITY, RHO, iterations, NX * NY * NZ * 8);
	}

	static FlipFluidDomain createCpuDamBreak(byte[] solidCells) {
		FlipFluidDomain domain = new FlipFluidDomain(NX, NY, NZ, cpuConfig());
		if (solidCells != null) {
			if (solidCells.length != NX * NY * NZ) throw new IllegalArgumentException("solid mask size does not match CPU grid");
			for (int z = 0; z < NZ; z++) for (int y = 0; y < NY; y++) for (int x = 0; x < NX; x++) {
				if (solidCells[x + NX * (y + NY * z)] != 0) domain.setSolid(x, y, z, true);
			}
		}
		for (int z = 0; z < NZ; z++) for (int y = 0; y < NY * 3 / 4; y++) for (int x = 0; x < NX * 2 / 5; x++) {
			if (!domain.isSolid(x, y, z)) domain.setVolume(x, y, z, 1.0F);
		}
		return domain;
	}

	static FluidSolverConfig cpuConfig() {
		return new FluidSolverConfig(H, RHO, GRAVITY, DT, 200, 1.0E-5F, 0.45F, 2.0E-3F, 0.80F);
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
		double lastGpuStepMilliseconds();
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
		@Override public double lastGpuStepMilliseconds() { return pipeline.lastGpuStepMilliseconds(); }
		GpuProbeCheckpoint captureCheckpoint(long generation) { return pipeline.captureCheckpoint(generation); }
		@Override public Snapshot snapshot() {
			VulkanFlipPipeline.SnapshotData data = pipeline.snapshot();
			return new Snapshot(data.density(), data.markers(), data.solids());
		}
		@Override public String diagnostics() {
			VulkanFlipPipeline.Diagnostics d = pipeline.diagnostics();
			double gpuMillis = lastGpuStepMilliseconds();
			String timing = Double.isFinite(gpuMillis)
					? String.format(Locale.ROOT, "GPU timestamp %.3f ms", gpuMillis)
					: "GPU timestamp 尚未步进";
			return String.format(Locale.ROOT, "%s | %d×%.2f ms physical substeps | diag readback 64 B/step through 3-slot ring | maxP %.1f | divergence %.5f→%.5f RMS %.5f | GPU volume %.3f cells",
					timing, pipeline.physicalSubstepsPerStep(),
					pipeline.config().timeStep() * 1_000.0F / pipeline.physicalSubstepsPerStep(),
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
		@Override public double lastGpuStepMilliseconds() { return Double.NaN; }
		@Override public Snapshot snapshot() { return new Snapshot(domain.copyQuantizedVolume(), domain.copyMarkerMask(), domain.copySolidMask()); }
		@Override public String diagnostics() {
			return stats == null ? "尚未步进" : String.format(Locale.ROOT, "maxP %.1f | PCG %d residual %.4g",
					domain.maximumPressure(), stats.pressureIterations(), stats.pressureResidual());
		}
		@Override public void close() { }
	}
}

package mmm.gpuprobe;

import java.util.Locale;

/** Headless Vulkan throughput/readback report; it owns no Minecraft or world state. */
final class GpuProbeBenchmark {
	private GpuProbeBenchmark() { }

	static String run(int deviceIndex, int blockSize, VulkanFlipPipeline.PressureSolver pressureSolver) {
		if (blockSize != 12 && blockSize != 32) {
			throw new IllegalArgumentException("benchmark 只接受 12 或 32 方块边长");
		}
		boolean large = blockSize == 32;
		int grid = large ? 64 : 24;
		int ny = large ? 64 : 16;
		int particles = large ? 2_097_152 : 24 * 16 * 24 * 8;
		int pressureIterations = switch (pressureSolver) {
			case JACOBI -> large ? 64 : GpuProbeScenario.PRESSURE_ITERATIONS;
			case RBGS_SOR -> large ? 64 : 48;
			case PCG_DIAGONAL -> large ? 64 : 32;
			case MULTIGRID_TWO_LEVEL, MULTIGRID_HIERARCHY -> 2;
		};
		VulkanFlipPipeline.Config config = new VulkanFlipPipeline.Config(
				grid, ny, grid, GpuProbeScenario.CELL_SIZE, GpuProbeScenario.TIME_STEP,
				large ? 0.0F : GpuProbeScenario.GRAVITY, GpuProbeScenario.DENSITY,
				pressureIterations, particles);
		try (VulkanFlipPipeline pipeline = new VulkanFlipPipeline(config, deviceIndex,
				VulkanFlipPipeline.MemoryMode.DEVICE_LOCAL_STAGING, pressureSolver)) {
			if (large) pipeline.initializeFull(); else pipeline.initializeDamBreak();
			int warmup = large ? 1 : 3;
			int measured = large ? 3 : 20;
			for (int i = 0; i < warmup; i++) pipeline.step();
			pipeline.diagnostics();
			double schedulingTotal = 0.0;
			double schedulingMinimum = Double.POSITIVE_INFINITY;
			double schedulingMaximum = 0.0;
			double gpuTotal = 0.0;
			double gpuMinimum = Double.POSITIVE_INFINITY;
			double gpuMaximum = 0.0;
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
			if (gpuNanos.length != measured) {
				throw new IllegalStateException("Vulkan timestamp capture count mismatch on " + pipeline.deviceName());
			}
			for (long nanos : gpuNanos) {
				double gpuMillis = nanos / 1_000_000.0;
				gpuTotal += gpuMillis;
				gpuMinimum = Math.min(gpuMinimum, gpuMillis);
				gpuMaximum = Math.max(gpuMaximum, gpuMillis);
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
}

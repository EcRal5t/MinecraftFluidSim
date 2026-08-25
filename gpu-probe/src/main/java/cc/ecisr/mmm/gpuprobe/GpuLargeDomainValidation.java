package cc.ecisr.mmm.gpuprobe;

import java.util.ArrayList;
import java.util.List;

/**
 * Expensive 64-cubed solver-grid gates. Keep these separate from the default small-domain checks so
 * ordinary iterations can use --skip-large without loading publication-scale fixtures into the main suite.
 */
final class GpuLargeDomainValidation {
	private static final float CELL_SIZE = 0.5F;
	private static final float DT = 1.0F / 20.0F;
	private static final float RHO = 1_000.0F;
	private static final float GRAVITY = 9.81F;

	private GpuLargeDomainValidation() { }

	static List<String> run(int device) {
		List<String> results = new ArrayList<>();
		results.add(validateLargeHierarchyHydrostaticColumn(device));
		results.add(validateLargeHierarchyObstacles(device));
		results.add(benchmarkLargeMultigridCurve(device));
		results.add(benchmarkLargeHierarchyCurve(device));
		results.add(benchmarkLargePressureSolvers(device));
		results.add(benchmarkThirtyTwoCubedBlocks(device));
		return results;
	}

	private static String benchmarkThirtyTwoCubedBlocks(int device) {
		int grid = 64;
		int particles = 32 * 32 * 32 * 64;
		// Zero gravity isolates full-domain compute throughput from the closed-box pressure nullspace.
		VulkanFlipPipeline.Config config = new VulkanFlipPipeline.Config(grid, grid, grid, CELL_SIZE, DT, 0.0F, RHO, 64, particles);
		try (VulkanFlipPipeline gpu = new VulkanFlipPipeline(config, device)) {
			long initializationStarted = System.nanoTime();
			gpu.initializeFull();
			require(gpu.particleCount() == particles, "32^3-block particle count");
			require(close(gpu.totalParticleVolume(), 32.0F * 32.0F * 32.0F * 8.0F, 0.1F), "32^3-block volume");
			double initializationSeconds = secondsSince(initializationStarted);
			gpu.step();
			Timing timing = time(gpu, 3);
			VulkanFlipPipeline.Diagnostics diagnostics = gpu.diagnostics();
			requireFinite(diagnostics);
			VulkanFlipPipeline.ReadbackStats readback = gpu.measureReadback();
			return "benchmark 32^3 blocks / 64^3 grid: " + timing.format()
					 + String.format(" particles=%d init=%.3fs volume=%.1f cells compact=%dB/%.3fms full=%dB/%.3fms",
					 gpu.particleCount(), initializationSeconds, gpu.totalParticleVolume(), readback.compactBytes(),
					 readback.compactNanos() / 1_000_000.0, readback.fullBytes(), readback.fullNanos() / 1_000_000.0);
		}
	}

	private static String benchmarkLargePressureSolvers(int device) {
		int grid = 64, capacity = 32 * 32 * 32 * 64;
		List<String> results = new ArrayList<>();
		for (VulkanFlipPipeline.PressureSolver solver : VulkanFlipPipeline.PressureSolver.values()) {
			int iterations = switch (solver) { case JACOBI -> 64; case RBGS_SOR -> 64; case PCG, PCG_DIAGONAL -> 64; case MULTIGRID_TWO_LEVEL, MULTIGRID_HIERARCHY -> 2; };
			VulkanFlipPipeline.Config config = new VulkanFlipPipeline.Config(grid, grid, grid, CELL_SIZE, DT, GRAVITY, RHO,
					iterations, capacity);
			try (VulkanFlipPipeline gpu = new VulkanFlipPipeline(config, device,
					VulkanFlipPipeline.MemoryMode.DEVICE_LOCAL_STAGING, solver)) {
				gpu.initializeDamBreak();
				gpu.step();
				VulkanFlipPipeline.Diagnostics firstStep = gpu.diagnostics();
				requireFinite(firstStep);
				require(firstStep.maximumDivergence() < firstStep.preProjectionMaximumDivergence(), solver + " large first projection failed");
				Timing timing = time(gpu, 3);
				VulkanFlipPipeline.Diagnostics diagnostics = gpu.diagnostics();
				requireFinite(diagnostics);
				require(close(diagnostics.particleVolume(), gpu.totalParticleVolume(), 0.1F), solver + " large volume changed");
				require(diagnostics.maximumDivergence() < diagnostics.preProjectionMaximumDivergence(), solver + " large projection failed");
				results.add(String.format("%s/%d %s firstDiv %.5f->%.5f step4Div %.5f->%.5f", solver.label(), iterations, timing.format(),
						firstStep.preProjectionMaximumDivergence(), firstStep.maximumDivergence(),
						diagnostics.preProjectionMaximumDivergence(), diagnostics.maximumDivergence()));
			}
		}
		return "benchmark pressure solvers 32^3-block domain / partial dam: " + String.join(" | ", results);
	}

	private static String benchmarkLargeMultigridCurve(int device) {
		int grid = 64, capacity = 32 * 32 * 32 * 64;
		List<CurvePoint> points = new ArrayList<>();
		for (int cycles : new int[] {1, 2, 3}) {
			VulkanFlipPipeline.Config config = new VulkanFlipPipeline.Config(grid, grid, grid, CELL_SIZE, DT, GRAVITY, RHO,
					cycles, capacity);
			try (VulkanFlipPipeline gpu = new VulkanFlipPipeline(config, device,
					VulkanFlipPipeline.MemoryMode.DEVICE_LOCAL_STAGING, VulkanFlipPipeline.PressureSolver.MULTIGRID_TWO_LEVEL)) {
				gpu.initializeDamBreak();
				gpu.step();
				double minimumMillis = Double.POSITIVE_INFINITY;
				VulkanFlipPipeline.Diagnostics diagnostics = null;
				for (int sample = 0; sample < 3; sample++) {
					gpu.initializeDamBreak();
					gpu.step();
					minimumMillis = Math.min(minimumMillis, gpu.lastGpuStepMilliseconds());
					diagnostics = gpu.diagnostics();
				}
				requireFinite(diagnostics);
				require(close(diagnostics.particleVolume(), gpu.totalParticleVolume(), 0.1F), "MG2 large curve volume changed");
				points.add(new CurvePoint(cycles, minimumMillis, diagnostics.maximumDivergence()));
			}
		}
		requireStrictlyImproving(points, "MG2 large convergence curve");
		return "benchmark MG2 64^3-grid convergence curve: "
				+ points.stream().map(CurvePoint::format).reduce((a, b) -> a + ", " + b).orElseThrow();
	}

	private static String validateLargeHierarchyHydrostaticColumn(int device) {
		int grid = 64, capacity = 32 * 32 * 32 * 64;
		VulkanFlipPipeline.Config config = new VulkanFlipPipeline.Config(grid, grid, grid, CELL_SIZE, DT, GRAVITY, RHO,
				2, capacity);
		try (VulkanFlipPipeline gpu = new VulkanFlipPipeline(config, device,
				VulkanFlipPipeline.MemoryMode.DEVICE_LOCAL_STAGING, VulkanFlipPipeline.PressureSolver.MULTIGRID_HIERARCHY)) {
			gpu.initializeColumn();
			float volume = gpu.totalParticleVolume();
			for (int step = 0; step < 8; step++) gpu.step();
			VulkanFlipPipeline.Diagnostics diagnostics = gpu.diagnostics();
			requireFinite(diagnostics);
			require(gpu.multigridLevelCount() == 4, "large MGH column did not use four levels");
			require(close(diagnostics.particleVolume(), volume, 0.1F), "large MGH column volume changed");
			require(diagnostics.maximumDivergence() < diagnostics.preProjectionMaximumDivergence(),
					"large MGH column projection failed");
			VulkanFlipPipeline.PressureProfile profile = gpu.pressureProfile();
			int lowest = firstOccupied(profile), highest = lastOccupied(profile);
			require(lowest >= 0 && highest > lowest, "large MGH column pressure profile has insufficient layers");
			float bottom = profile.averageByY()[lowest], top = profile.averageByY()[highest];
			require(Float.isFinite(bottom) && Float.isFinite(top) && bottom > top && diagnostics.maximumPressure() > 10_000.0F,
					"large MGH hydrostatic pressure direction failed");
			return String.format("correctness MGH 64^3 column: levels=4 volume=%.3f cells maxP=%.3fPa bottomP=%.3fPa topP=%.3fPa div %.5f->%.5f",
					volume, diagnostics.maximumPressure(), bottom, top,
					diagnostics.preProjectionMaximumDivergence(), diagnostics.maximumDivergence());
		}
	}

	private static String validateLargeHierarchyObstacles(int device) {
		int grid = 64, capacity = 32 * 32 * 32 * 64;
		VulkanFlipPipeline.Config config = new VulkanFlipPipeline.Config(grid, grid, grid, CELL_SIZE, DT, GRAVITY, RHO,
				2, capacity);
		try (VulkanFlipPipeline gpu = new VulkanFlipPipeline(config, device,
				VulkanFlipPipeline.MemoryMode.DEVICE_LOCAL_STAGING, VulkanFlipPipeline.PressureSolver.MULTIGRID_HIERARCHY)) {
			gpu.initializeObstacleDamBreak();
			float volume = gpu.totalParticleVolume();
			gpu.step();
			VulkanFlipPipeline.Diagnostics diagnostics = gpu.diagnostics();
			requireFinite(diagnostics);
			require(close(diagnostics.particleVolume(), volume, 0.1F), "large MGH obstacle volume changed");
			require(diagnostics.maximumDivergence() < diagnostics.preProjectionMaximumDivergence(),
					"large MGH obstacle projection failed");
			require(gpu.particlesInsideSolidCount() == 0, "large MGH particle entered a solid");
			float[] weights = gpu.packedHierarchyXWeights();
			int[] dimensions = {32, 16, 8, 4};
			int offset = 0;
			StringBuilder blockedSummary = new StringBuilder(), openSummary = new StringBuilder();
			for (int level = 0; level < dimensions.length; level++) {
				int dimension = dimensions[level], wallX = dimension / 2 - 1;
				int wallYEnd = 24 >> level, wallZStart = 8 >> level, wallZEnd = 24 >> level;
				float blocked = 0.0F;
				for (int z = wallZStart; z < wallZEnd; z++) for (int y = 0; y < wallYEnd; y++) {
					blocked = Math.max(blocked, Math.abs(weights[offset + wallX + dimension * (y + dimension * z)]));
				}
				float open = weights[offset + wallX];
				require(blocked <= 1.0E-6F, "MGH hierarchy crossed thin wall at level " + (level + 1) + ": " + blocked);
				require(open >= 0.99F, "MGH hierarchy lost open face at level " + (level + 1) + ": " + open);
				if (level > 0) { blockedSummary.append('/'); openSummary.append('/'); }
				blockedSummary.append(String.format("%.1f", blocked));
				openSummary.append(String.format("%.1f", open));
				offset += dimension * dimension * dimension;
			}
			return String.format("correctness MGH 64^3 obstacles: levels=4 solids=%d particles=%d volume=%.3f cells wall=%s open=%s inside=0 div %.5f->%.5f",
					gpu.solidCellCount(), gpu.particleCount(), volume, blockedSummary, openSummary,
					diagnostics.preProjectionMaximumDivergence(), diagnostics.maximumDivergence());
		}
	}

	private static String benchmarkLargeHierarchyCurve(int device) {
		int grid = 64, capacity = 32 * 32 * 32 * 64;
		List<CurvePoint> points = new ArrayList<>();
		int levels = 0;
		String topology = "";
		for (int cycles : new int[] {1, 2, 3}) {
			VulkanFlipPipeline.Config config = new VulkanFlipPipeline.Config(grid, grid, grid, CELL_SIZE, DT, GRAVITY, RHO,
					cycles, capacity);
			try (VulkanFlipPipeline gpu = new VulkanFlipPipeline(config, device,
					VulkanFlipPipeline.MemoryMode.DEVICE_LOCAL_STAGING, VulkanFlipPipeline.PressureSolver.MULTIGRID_HIERARCHY)) {
				levels = gpu.multigridLevelCount();
				gpu.initializeDamBreak();
				gpu.step();
				if (cycles == 1) topology = validateHierarchyTopology(gpu);
				double minimumMillis = Double.POSITIVE_INFINITY;
				VulkanFlipPipeline.Diagnostics diagnostics = null;
				for (int sample = 0; sample < 3; sample++) {
					gpu.initializeDamBreak();
					gpu.step();
					minimumMillis = Math.min(minimumMillis, gpu.lastGpuStepMilliseconds());
					diagnostics = gpu.diagnostics();
				}
				requireFinite(diagnostics);
				points.add(new CurvePoint(cycles, minimumMillis, diagnostics.maximumDivergence()));
			}
		}
		require(levels == 4, "64^3 MGH hierarchy level count: " + levels);
		requireStrictlyImproving(points, "MGH large convergence curve");
		require(points.get(1).maximumDivergence() < 0.16F, "MGH two-cycle 64^3 quality gate failed");
		require(points.get(2).maximumDivergence() < 0.11F, "MGH three-cycle 64^3 quality gate failed");
		return "benchmark MGH 64^3-grid convergence curve (" + topology + "): "
				+ points.stream().map(CurvePoint::format).reduce((a, b) -> a + ", " + b).orElseThrow();
	}

	private static String validateHierarchyTopology(VulkanFlipPipeline gpu) {
		VulkanFlipPipeline.HierarchyLevelStats[] stats = gpu.hierarchyLevelStats();
		int[] expectedCells = {32 * 32 * 32, 16 * 16 * 16, 8 * 8 * 8, 4 * 4 * 4};
		require(stats.length == expectedCells.length, "64^3 MGH topology length: " + stats.length);
		StringBuilder fluidCells = new StringBuilder();
		for (int i = 0; i < stats.length; i++) {
			VulkanFlipPipeline.HierarchyLevelStats level = stats[i];
			require(level.level() == i + 1 && level.cells() == expectedCells[i], "MGH packed level shape mismatch at " + (i + 1));
			require(level.fluidCells() > 0, "MGH packed level has no fluid at " + (i + 1));
			require(Float.isFinite(level.maximumRhs()) && level.maximumRhs() > 0.0F, "MGH level RHS invalid at " + (i + 1));
			require(Float.isFinite(level.maximumPressure()) && level.maximumPressure() > 0.0F, "MGH level pressure invalid at " + (i + 1));
			require(Float.isFinite(level.minimumPositiveDiagonal()) && level.minimumPositiveDiagonal() > 0.0F,
					"MGH level diagonal invalid at " + (i + 1));
			require(level.maximumDiagonal() <= 6.0F + 1.0E-5F, "MGH level diagonal exceeds fine operator at " + (i + 1));
			require(level.maximumWeight() >= 0.0F && level.maximumWeight() <= 1.0F + 1.0E-5F,
					"MGH level coupling weight invalid at " + (i + 1));
			if (i > 0) fluidCells.append('/');
			fluidCells.append(level.fluidCells());
		}
		return "levels=4 cells=32768/4096/512/64 fluid=" + fluidCells;
	}

	private static void requireStrictlyImproving(List<CurvePoint> points, String label) {
		for (int i = 1; i < points.size(); i++) {
			require(points.get(i).maximumDivergence() < points.get(i - 1).maximumDivergence(),
					label + " did not improve from point " + i + " to " + (i + 1));
		}
	}

	private static Timing time(VulkanFlipPipeline gpu, int steps) {
		double schedulingTotal = 0.0, schedulingMinimum = Double.POSITIVE_INFINITY, schedulingMaximum = 0.0;
		double gpuTotal = 0.0, gpuMinimum = Double.POSITIVE_INFINITY, gpuMaximum = 0.0;
		gpu.beginGpuTimingCapture();
		long batchStarted = System.nanoTime();
		for (int i = 0; i < steps; i++) {
			gpu.step();
			double schedulingMillis = gpu.lastStepMilliseconds();
			schedulingTotal += schedulingMillis;
			schedulingMinimum = Math.min(schedulingMinimum, schedulingMillis);
			schedulingMaximum = Math.max(schedulingMaximum, schedulingMillis);
		}
		long[] gpuNanos = gpu.endGpuTimingCapture();
		double throughputWallAverage = (System.nanoTime() - batchStarted) / 1_000_000.0 / steps;
		require(gpuNanos.length == steps, "GPU timing capture count does not match submitted steps");
		for (long nanos : gpuNanos) {
			double gpuMillis = nanos / 1_000_000.0;
			require(Double.isFinite(gpuMillis) && gpuMillis > 0.0, "missing GPU timestamp while timing");
			gpuTotal += gpuMillis;
			gpuMinimum = Math.min(gpuMinimum, gpuMillis);
			gpuMaximum = Math.max(gpuMaximum, gpuMillis);
		}
		return new Timing(steps, gpuTotal / steps, gpuMinimum, gpuMaximum,
				throughputWallAverage, schedulingTotal / steps, schedulingMinimum, schedulingMaximum);
	}

	private static int firstOccupied(VulkanFlipPipeline.PressureProfile profile) {
		for (int y = 0; y < profile.fluidCellsByY().length; y++) if (profile.fluidCellsByY()[y] > 0) return y;
		return -1;
	}

	private static int lastOccupied(VulkanFlipPipeline.PressureProfile profile) {
		for (int y = profile.fluidCellsByY().length - 1; y >= 0; y--) if (profile.fluidCellsByY()[y] > 0) return y;
		return -1;
	}

	private static boolean close(float actual, float expected, float tolerance) {
		return Math.abs(actual - expected) <= tolerance;
	}

	private static double secondsSince(long started) {
		return (System.nanoTime() - started) / 1_000_000_000.0;
	}

	private static void requireFinite(VulkanFlipPipeline.Diagnostics diagnostics) {
		require(Float.isFinite(diagnostics.preProjectionMaximumDivergence()), "non-finite pre-projection divergence");
		require(Float.isFinite(diagnostics.maximumDivergence()), "non-finite post-projection divergence");
		require(Float.isFinite(diagnostics.rmsDivergence()), "non-finite RMS divergence");
		require(Float.isFinite(diagnostics.maximumPressure()), "non-finite pressure");
		require(Float.isFinite(diagnostics.particleVolume()), "non-finite particle volume");
		require(Float.isFinite(diagnostics.centerOfMassX()), "non-finite center of mass");
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}

	private record Timing(int steps, double gpuAverageMillis, double gpuMinimumMillis, double gpuMaximumMillis,
	                      double throughputWallAverageMillis, double schedulingWallAverageMillis,
	                      double schedulingWallMinimumMillis, double schedulingWallMaximumMillis) {
		String format() {
			return String.format("steps=%d GPU avg=%.3fms min=%.3fms max=%.3fms throughput wall avg=%.3fms scheduling wall avg=%.3fms min=%.3fms max=%.3fms",
					steps, gpuAverageMillis, gpuMinimumMillis, gpuMaximumMillis,
					throughputWallAverageMillis, schedulingWallAverageMillis, schedulingWallMinimumMillis, schedulingWallMaximumMillis);
		}
	}

	private record CurvePoint(int iterations, double milliseconds, float maximumDivergence) {
		String format() { return String.format("%d:%.6f/%.3fms", iterations, maximumDivergence, milliseconds); }
	}
}

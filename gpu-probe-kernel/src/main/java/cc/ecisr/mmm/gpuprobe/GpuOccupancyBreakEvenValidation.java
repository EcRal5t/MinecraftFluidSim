package cc.ecisr.mmm.gpuprobe;

import cc.ecisr.mmm.fluid.FluidDomain;
import cc.ecisr.mmm.fluid.FluidSolverConfig;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Standalone M1 CPU/GPU occupancy break-even matrix. It measures evolved physical steps only:
 * initialization, shader compilation and readback validation are deliberately outside the timed regions.
 */
public final class GpuOccupancyBreakEvenValidation {
	private static final float CELL_SIZE = 0.5F;
	private static final float DT = 1.0F / 20.0F;
	private static final float GRAVITY = 9.81F;
	private static final float RHO = 1_000.0F;
	private static final int GPU_PRESSURE_ITERATIONS = 96;
	private static final int REPEATED_BATCHES = 7;
	private static final int MEASURED_STEPS = 6;

	private GpuOccupancyBreakEvenValidation() { }

	public static void main(String[] args) {
		Locale.setDefault(Locale.ROOT);
		int device = args.length == 0 ? 0 : Integer.parseInt(args[0]);
		warmCpuJit();
		long started = System.nanoTime();
		System.out.printf("M1 occupancy method: median of %d reset batches x %d evolved steps; CPU current PCG wall versus GPU Jacobi/%d completed-batch throughput wall%n",
				REPEATED_BATCHES, MEASURED_STEPS, GPU_PRESSURE_ITERATIONS);
		List<Result> results = new ArrayList<>();
		for (Scenario scenario : scenarios()) {
			Result result = benchmark(device, scenario);
			results.add(result);
			System.out.println(result.format());
		}

		String crossover = sustainedScaleCrossover(results);
		String occupancyCrossover = sustainedOccupancyCrossover(results, 1.0);
		String practicalOccupancyCrossover = sustainedOccupancyCrossover(results, 1.10);
		Result floor = find(results, "break-even-sweep");
		Result current = find(results, "current-dam");
		System.out.printf(Locale.ROOT,
				"M1 occupancy summary: device=%s | sustained 25%%-occupancy grid crossover=%s | observed 24x16x24 one-marker/cell crossover=%s | 10%%-margin crossover=%s | current-domain speedup=%.2fx | current CPU %.3fms/GPU throughput %.3fms | one-marker floor GPU timestamp %.3fms/throughput %.3fms/scheduling %.3fms%n",
				current.deviceName(), crossover, occupancyCrossover, practicalOccupancyCrossover,
				current.speedup(), current.cpu().averageMillis(),
				current.gpu().throughputAverageMillis(), floor.gpu().timestampAverageMillis(),
				floor.gpu().throughputAverageMillis(), floor.gpu().schedulingAverageMillis());
		System.out.printf(Locale.ROOT, "GPU occupancy break-even validation passed on device %d in %.3f s%n",
				device, (System.nanoTime() - started) / 1_000_000_000.0);
	}

	private static List<Scenario> scenarios() {
		List<Scenario> scenarios = new ArrayList<>();
		for (int grid : new int[] {4, 6, 8, 12, 16, 24, 32}) {
			int cells = Math.multiplyExact(Math.multiplyExact(grid, grid), grid);
			scenarios.add(new Scenario("scale-25pct", grid + "^3-25pct", grid, grid, grid,
					Math.max(1, cells / 4), 8));
		}

		for (int occupiedCells : new int[] {1, 4, 16, 64, 256, 1_024, 4_096, 8_192}) {
			scenarios.add(new Scenario("break-even-sweep", "24x16x24-one-marker-per-cell", 24, 16, 24,
					occupiedCells, 1));
		}
		for (int markersPerCell : new int[] {1, 2, 4, 8}) {
			scenarios.add(new Scenario("marker-sweep", "fixed-1024-fluid-cells", 24, 16, 24,
					1_024, markersPerCell));
		}
		for (int markersPerCell : new int[] {8, 4, 2, 1}) {
			scenarios.add(new Scenario("fluid-cell-sweep", "fixed-8192-markers", 24, 16, 24,
					8_192 / markersPerCell, markersPerCell));
		}
		scenarios.add(new Scenario("current-dam", "24x16x24-current-dam-count", 24, 16, 24,
				2_592, 8));
		return scenarios;
	}

	private static Result benchmark(int device, Scenario scenario) {
		int expectedParticles = scenario.particleCount();
		float expectedVolume = expectedParticles * 0.125F;
		CpuTiming cpu = timeCpu(scenario, expectedParticles, expectedVolume);
		GpuTiming gpu = timeGpu(device, scenario, expectedParticles, expectedVolume);
		return new Result(scenario, gpu.deviceName(), cpu, gpu, cpu.averageMillis() / gpu.throughputAverageMillis());
	}

	private static CpuTiming timeCpu(Scenario scenario, int expectedParticles, float expectedVolume) {
		List<CpuTiming> batches = new ArrayList<>();
		for (int repeat = 0; repeat < REPEATED_BATCHES; repeat++) {
			FluidDomain domain = createCpuDomain(scenario);
			require(domain.particleCount() == expectedParticles, "CPU initial marker count changed for " + scenario.label());
			require(domain.markerCellCount() == scenario.occupiedCells(), "CPU initial fluid-cell count changed for " + scenario.label());
			require(close(domain.totalVolume(), expectedVolume), "CPU initial marker volume changed for " + scenario.label());

			double totalMillis = 0.0, minimumMillis = Double.POSITIVE_INFINITY, maximumMillis = 0.0;
			int maximumSubsteps = 0, pressureIterations = 0;
			for (int step = 0; step < MEASURED_STEPS; step++) {
				long started = System.nanoTime();
				var candidate = GpuProbeScenario.evaluateCpuStep(domain, step);
				FluidDomain.StepStats stats = candidate.stats();
				domain = candidate.claimPreparedCpuDomain();
				double milliseconds = (System.nanoTime() - started) / 1_000_000.0;
				totalMillis += milliseconds;
				minimumMillis = Math.min(minimumMillis, milliseconds);
				maximumMillis = Math.max(maximumMillis, milliseconds);
				maximumSubsteps = Math.max(maximumSubsteps, stats.substeps());
				pressureIterations += stats.pressureIterations();
			}
			require(domain.particleCount() == expectedParticles, "CPU marker count changed while timing " + scenario.label());
			require(close(domain.totalVolume(), expectedVolume), "CPU marker volume changed while timing " + scenario.label());
			batches.add(new CpuTiming(totalMillis / MEASURED_STEPS, minimumMillis, maximumMillis,
					domain.markerCellCount(), maximumSubsteps, (double) pressureIterations / MEASURED_STEPS));
		}
		batches.sort(Comparator.comparingDouble(CpuTiming::averageMillis));
		return batches.get(REPEATED_BATCHES / 2);
	}

	private static GpuTiming timeGpu(int device, Scenario scenario, int expectedParticles, float expectedVolume) {
		int capacity = Math.multiplyExact(scenario.cellCount(), 8);
		VulkanFlipPipeline.Config config = new VulkanFlipPipeline.Config(scenario.nx(), scenario.ny(), scenario.nz(),
				CELL_SIZE, DT, GRAVITY, RHO, GPU_PRESSURE_ITERATIONS, capacity);
		try (VulkanFlipPipeline gpu = new VulkanFlipPipeline(config, device,
				VulkanFlipPipeline.MemoryMode.DEVICE_LOCAL_STAGING, VulkanFlipPipeline.PressureSolver.JACOBI)) {
			gpu.initializeOccupancy(scenario.occupiedCells(), scenario.markersPerCell());
			for (int step = 0; step < 2; step++) gpu.step();
			gpu.diagnostics();
			List<GpuTiming> batches = new ArrayList<>();
			for (int repeat = 0; repeat < REPEATED_BATCHES; repeat++) {
				gpu.initializeOccupancy(scenario.occupiedCells(), scenario.markersPerCell());
				require(gpu.particleCount() == expectedParticles, "GPU initial marker count changed for " + scenario.label());
				require(gpu.markerCellCountFromParticles() == scenario.occupiedCells(),
						"GPU initial fluid-cell count changed for " + scenario.label());
				require(close(gpu.totalParticleVolume(), expectedVolume), "GPU initial marker volume changed for " + scenario.label());
				batches.add(timeGpuBatch(gpu, scenario, expectedParticles, expectedVolume));
			}
			batches.sort(Comparator.comparingDouble(GpuTiming::throughputAverageMillis));
			return batches.get(REPEATED_BATCHES / 2);
		}
	}

	private static GpuTiming timeGpuBatch(VulkanFlipPipeline gpu, Scenario scenario,
	                                      int expectedParticles, float expectedVolume) {
		double schedulingTotal = 0.0, schedulingMinimum = Double.POSITIVE_INFINITY, schedulingMaximum = 0.0;
		gpu.beginGpuTimingCapture();
		long batchStarted = System.nanoTime();
		for (int step = 0; step < MEASURED_STEPS; step++) {
			gpu.step();
			double milliseconds = gpu.lastStepMilliseconds();
			schedulingTotal += milliseconds;
			schedulingMinimum = Math.min(schedulingMinimum, milliseconds);
			schedulingMaximum = Math.max(schedulingMaximum, milliseconds);
		}
		long[] timestampNanos = gpu.endGpuTimingCapture();
		double throughput = (System.nanoTime() - batchStarted) / 1_000_000.0 / MEASURED_STEPS;
		require(timestampNanos.length == MEASURED_STEPS, "GPU timestamp count changed for " + scenario.label());
		double timestampTotal = 0.0, timestampMinimum = Double.POSITIVE_INFINITY, timestampMaximum = 0.0;
		for (long nanos : timestampNanos) {
			double milliseconds = nanos / 1_000_000.0;
			require(Double.isFinite(milliseconds) && milliseconds > 0.0,
					"GPU timestamp is invalid for " + scenario.label());
			timestampTotal += milliseconds;
			timestampMinimum = Math.min(timestampMinimum, milliseconds);
			timestampMaximum = Math.max(timestampMaximum, milliseconds);
		}
		VulkanFlipPipeline.Diagnostics diagnostics = gpu.diagnostics();
		require(Float.isFinite(diagnostics.maximumDivergence()) && Float.isFinite(diagnostics.maximumPressure()),
				"GPU diagnostics became non-finite for " + scenario.label());
		require(close(diagnostics.particleVolume(), expectedVolume), "GPU diagnostic marker volume changed for " + scenario.label());
		require(gpu.particleCount() == expectedParticles, "GPU marker count changed while timing " + scenario.label());
		int finalFluidCells = gpu.markerCellCountFromParticles();
		require(finalFluidCells > 0 && finalFluidCells <= expectedParticles,
				"GPU final fluid-cell count is invalid for " + scenario.label());
		return new GpuTiming(gpu.deviceName(), timestampTotal / MEASURED_STEPS, timestampMinimum, timestampMaximum,
				throughput, schedulingTotal / MEASURED_STEPS, schedulingMinimum, schedulingMaximum, finalFluidCells);
	}

	private static FluidDomain createCpuDomain(Scenario scenario) {
		FluidSolverConfig config = new FluidSolverConfig(CELL_SIZE, RHO, GRAVITY, DT,
				200, 1.0E-5F, 0.45F, 2.0E-3F, 0.80F);
		FluidDomain domain = new FluidDomain(scenario.nx(), scenario.ny(), scenario.nz(), config);
		float cellVolume = scenario.markersPerCell() * 0.125F;
		for (int cell = 0; cell < scenario.occupiedCells(); cell++) {
			int x = cell % scenario.nx(), yz = cell / scenario.nx();
			int y = yz % scenario.ny(), z = yz / scenario.ny();
			domain.setVolume(x, y, z, cellVolume);
		}
		return domain;
	}

	private static void warmCpuJit() {
		Scenario warmup = new Scenario("jit", "jit", 12, 12, 12, 432, 8);
		FluidDomain domain = createCpuDomain(warmup);
		for (int step = 0; step < 4; step++) {
			domain = GpuProbeScenario.evaluateCpuStep(domain, step).claimPreparedCpuDomain();
		}
	}

	private static String sustainedScaleCrossover(List<Result> results) {
		List<Result> scale = results.stream().filter(result -> result.scenario().family().equals("scale-25pct")).toList();
		for (int candidate = 0; candidate < scale.size(); candidate++) {
			boolean sustained = true;
			for (int larger = candidate; larger < scale.size(); larger++) {
				if (scale.get(larger).speedup() < 1.0) { sustained = false; break; }
			}
			if (sustained) return scale.get(candidate).scenario().gridLabel();
		}
		return "not observed through " + scale.getLast().scenario().gridLabel();
	}

	private static String sustainedOccupancyCrossover(List<Result> results, double requiredSpeedup) {
		List<Result> sweep = results.stream().filter(result -> result.scenario().family().equals("break-even-sweep")).toList();
		for (int candidate = 0; candidate < sweep.size(); candidate++) {
			boolean sustained = true;
			for (int larger = candidate; larger < sweep.size(); larger++) {
				if (sweep.get(larger).speedup() < requiredSpeedup) { sustained = false; break; }
			}
			if (sustained) return sweep.get(candidate).scenario().occupiedCells() + " fluid cells/markers";
		}
		return "not observed through " + sweep.getLast().scenario().occupiedCells() + " fluid cells/markers";
	}

	private static Result find(List<Result> results, String family) {
		return results.stream().filter(result -> result.scenario().family().equals(family)).findFirst().orElseThrow();
	}

	private static boolean close(float actual, float expected) {
		return Math.abs(actual - expected) <= Math.max(1.0E-3F, expected * 1.0E-5F);
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}

	private record Scenario(String family, String label, int nx, int ny, int nz,
	                        int occupiedCells, int markersPerCell) {
		Scenario {
			if (nx < 2 || ny < 2 || nz < 2 || occupiedCells < 1 || occupiedCells > Math.multiplyExact(Math.multiplyExact(nx, ny), nz)
					|| markersPerCell < 1 || markersPerCell > 8) throw new IllegalArgumentException("Invalid occupancy scenario");
		}
		int cellCount() { return Math.multiplyExact(Math.multiplyExact(nx, ny), nz); }
		int particleCount() { return Math.multiplyExact(occupiedCells, markersPerCell); }
		String gridLabel() { return nx == ny && ny == nz ? nx + "^3" : nx + "x" + ny + "x" + nz; }
	}

	private record CpuTiming(double averageMillis, double minimumMillis, double maximumMillis,
	                         int finalFluidCells, int maximumSubsteps, double averagePressureIterations) { }

	private record GpuTiming(String deviceName, double timestampAverageMillis, double timestampMinimumMillis,
	                         double timestampMaximumMillis, double throughputAverageMillis,
	                         double schedulingAverageMillis, double schedulingMinimumMillis,
	                         double schedulingMaximumMillis, int finalFluidCells) { }

	private record Result(Scenario scenario, String deviceName, CpuTiming cpu, GpuTiming gpu, double speedup) {
		String format() {
			return String.format(Locale.ROOT,
					"occupancy family=%s case=%s grid=%s cells=%d initialFluid=%d markersPerCell=%d particles=%d | CPU wall %.3fms (%.3f..%.3f) finalFluid=%d maxSubsteps=%d avgPCG=%.1f | GPU timestamp %.3fms (%.3f..%.3f) throughput %.3fms scheduling %.3fms (%.3f..%.3f) finalFluid=%d | CPU/throughput %.2fx",
					scenario.family(), scenario.label(), scenario.gridLabel(), scenario.cellCount(),
					scenario.occupiedCells(), scenario.markersPerCell(), scenario.particleCount(),
					cpu.averageMillis(), cpu.minimumMillis(), cpu.maximumMillis(), cpu.finalFluidCells(),
					cpu.maximumSubsteps(), cpu.averagePressureIterations(),
					gpu.timestampAverageMillis(), gpu.timestampMinimumMillis(), gpu.timestampMaximumMillis(),
					gpu.throughputAverageMillis(), gpu.schedulingAverageMillis(), gpu.schedulingMinimumMillis(),
					gpu.schedulingMaximumMillis(), gpu.finalFluidCells(), speedup);
		}
	}
}

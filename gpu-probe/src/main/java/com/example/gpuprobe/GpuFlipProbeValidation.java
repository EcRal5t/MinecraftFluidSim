package com.example.gpuprobe;

import com.example.fluid.FlipFluidDomain;
import com.example.fluid.FluidSolverConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Standalone M1 correctness and throughput gate. It never starts Minecraft, opens a window, or accesses a save.
 */
public final class GpuFlipProbeValidation {
	private static final float CELL_SIZE = 0.5F;
	private static final float DT = 1.0F / 20.0F;
	private static final float RHO = 1_000.0F;
	private static final float GRAVITY = 9.81F;

	private GpuFlipProbeValidation() { }

	public static void main(String[] args) {
		Locale.setDefault(Locale.ROOT);
		int device = args.length == 0 ? 0 : Integer.parseInt(args[0]);
		boolean skipLarge = args.length > 1 && "--skip-large".equals(args[1]);
		long suiteStarted = System.nanoTime();
		List<String> results = new ArrayList<>();
		results.add(validateDamBreak(device));
		results.add(validateHydrostaticColumn(device));
		results.add(benchmarkCurrentDomain(device));
		if (!skipLarge) results.add(benchmarkThirtyTwoCubedBlocks(device));
		for (String result : results) System.out.println(result);
		System.out.printf("GPU FLIP validation passed on device %d in %.3f s%n", device, secondsSince(suiteStarted));
	}

	private static String validateDamBreak(int device) {
		VulkanFlipPipeline.Config config = new VulkanFlipPipeline.Config(24, 16, 24, CELL_SIZE, DT, GRAVITY, RHO, 96, 24 * 16 * 24 * 8);
		try (VulkanFlipPipeline gpu = new VulkanFlipPipeline(config, device)) {
			gpu.initializeDamBreak();
			FlipFluidDomain cpu = cpuDamBreak(config);
			int expectedParticles = 9 * 12 * 24 * 8;
			float expectedVolume = expectedParticles / 8.0F;
			require(gpu.particleCount() == expectedParticles, "dam particle count");
			require(close(gpu.totalParticleVolume(), expectedVolume, 1.0E-4F), "GPU particle volume before stepping");
			require(close(cpu.totalVolume(), expectedVolume, 1.0E-4F), "CPU particle volume before stepping");

			VulkanFlipPipeline.Diagnostics diagnostics = null;
			double gpuMillis = 0.0;
			double cpuMillis = 0.0;
			for (int step = 0; step < 6; step++) {
				gpu.step();
				gpuMillis += gpu.lastStepMilliseconds();
				long cpuStarted = System.nanoTime();
				cpu.step();
				cpuMillis += (System.nanoTime() - cpuStarted) / 1_000_000.0;
				diagnostics = gpu.diagnostics();
				requireFinite(diagnostics);
				require(diagnostics.maximumDivergence() <= diagnostics.preProjectionMaximumDivergence() * 0.55F + 2.0E-3F,
						"pressure projection did not sufficiently reduce maximum divergence");
				require(close(gpu.totalParticleVolume(), expectedVolume, 1.0E-4F), "GPU particle volume changed");
				require(close(cpu.totalVolume(), expectedVolume, 1.0E-4F), "CPU particle volume changed");
			}
			float centerDifference = Math.abs(diagnostics.centerOfMassX() - cpu.centerOfMassX());
			require(centerDifference < 0.85F, "CPU/GPU center of mass diverged by " + centerDifference + " cells");
			VulkanFlipPipeline.SnapshotData snapshot = gpu.snapshot();
			int markers = nonzero(snapshot.markers());
			require(markers > 0 && markers <= config.cellCount(), "invalid marker snapshot");
			require(markers == gpu.markerCellCountFromParticles(), "snapshot marker mask is not post-advection particle occupancy");
			require(close(gpu.reconstructedDensityVolume(), gpu.totalParticleVolume(), 0.20F),
					"boundary-normalized surface density does not close to particle volume");
			return "correctness dam: device=" + gpu.deviceName()
					+ String.format(" particles=%d volume=%.3f cells centerDifference=%.4f postDiv=%.6f preDiv=%.6f GPU=%.3fms CPU=%.3fms markerCells=%d",
					gpu.particleCount(), gpu.totalParticleVolume(), centerDifference, diagnostics.maximumDivergence(),
					diagnostics.preProjectionMaximumDivergence(), gpuMillis / 6.0, cpuMillis / 6.0, markers);
		}
	}

	private static String validateHydrostaticColumn(int device) {
		VulkanFlipPipeline.Config config = new VulkanFlipPipeline.Config(16, 20, 16, CELL_SIZE, DT, GRAVITY, RHO, 160, 16 * 20 * 16 * 8);
		try (VulkanFlipPipeline gpu = new VulkanFlipPipeline(config, device)) {
			gpu.initializeColumn();
			float volume = gpu.totalParticleVolume();
			for (int i = 0; i < 8; i++) gpu.step();
			VulkanFlipPipeline.Diagnostics diagnostics = gpu.diagnostics();
			requireFinite(diagnostics);
			require(close(gpu.totalParticleVolume(), volume, 1.0E-4F), "column particle volume changed");
			require(diagnostics.maximumPressure() > 100.0F, "column did not develop hydrostatic pressure");
			VulkanFlipPipeline.PressureProfile profile = gpu.pressureProfile();
			int lowest = firstOccupied(profile), highest = lastOccupied(profile);
			require(lowest >= 0 && highest > lowest, "column pressure profile has insufficient occupied layers");
			float bottom = profile.averageByY()[lowest], top = profile.averageByY()[highest];
			require(Float.isFinite(bottom) && Float.isFinite(top) && bottom > top,
					"hydrostatic pressure does not decrease with height: bottom=" + bottom + ", top=" + top);
			return String.format("correctness column: volume=%.3f cells maxP=%.3fPa bottomP=%.3fPa topP=%.3fPa postDiv=%.6f preDiv=%.6f",
					volume, diagnostics.maximumPressure(), bottom, top, diagnostics.maximumDivergence(), diagnostics.preProjectionMaximumDivergence());
		}
	}

	private static String benchmarkCurrentDomain(int device) {
		VulkanFlipPipeline.Config config = new VulkanFlipPipeline.Config(24, 16, 24, CELL_SIZE, DT, GRAVITY, RHO, 96, 24 * 16 * 24 * 8);
		try (VulkanFlipPipeline gpu = new VulkanFlipPipeline(config, device)) {
			gpu.initializeDamBreak();
			for (int i = 0; i < 3; i++) gpu.step();
			Timing timing = time(gpu, 20);
			VulkanFlipPipeline.ReadbackStats readback = gpu.measureReadback();
			return "benchmark current 12x8x12 blocks / 24x16x24 grid: " + timing.format()
					+ String.format(" particles=%d compact=%dB/%.3fms full=%dB/%.3fms checksums=%016x/%016x",
					gpu.particleCount(), readback.compactBytes(), readback.compactNanos() / 1_000_000.0,
					readback.fullBytes(), readback.fullNanos() / 1_000_000.0, readback.compactChecksum(), readback.fullChecksum());
		}
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

	private static FlipFluidDomain cpuDamBreak(VulkanFlipPipeline.Config config) {
		FluidSolverConfig cpuConfig = new FluidSolverConfig(CELL_SIZE, RHO, GRAVITY, DT, 200, 1.0E-5F, 0.45F, 2.0E-3F, 0.80F);
		FlipFluidDomain domain = new FlipFluidDomain(config.nx(), config.ny(), config.nz(), cpuConfig);
		for (int z = 0; z < config.nz(); z++) for (int y = 0; y < config.ny() * 3 / 4; y++) for (int x = 0; x < config.nx() * 2 / 5; x++) {
			domain.setVolume(x, y, z, 1.0F);
		}
		return domain;
	}

	private static Timing time(VulkanFlipPipeline gpu, int steps) {
		double total = 0.0, minimum = Double.POSITIVE_INFINITY, maximum = 0.0;
		for (int i = 0; i < steps; i++) {
			gpu.step();
			double millis = gpu.lastStepMilliseconds();
			total += millis;
			minimum = Math.min(minimum, millis);
			maximum = Math.max(maximum, millis);
		}
		return new Timing(steps, total / steps, minimum, maximum);
	}

	private static int firstOccupied(VulkanFlipPipeline.PressureProfile profile) {
		for (int y = 0; y < profile.fluidCellsByY().length; y++) if (profile.fluidCellsByY()[y] > 0) return y;
		return -1;
	}

	private static int lastOccupied(VulkanFlipPipeline.PressureProfile profile) {
		for (int y = profile.fluidCellsByY().length - 1; y >= 0; y--) if (profile.fluidCellsByY()[y] > 0) return y;
		return -1;
	}

	private static int nonzero(byte[] values) {
		int result = 0;
		for (byte value : values) if (value != 0) result++;
		return result;
	}

	private static boolean close(float actual, float expected, float tolerance) { return Math.abs(actual - expected) <= tolerance; }
	private static double secondsSince(long started) { return (System.nanoTime() - started) / 1_000_000_000.0; }

	private static void requireFinite(VulkanFlipPipeline.Diagnostics diagnostics) {
		require(Float.isFinite(diagnostics.preProjectionMaximumDivergence()), "non-finite pre-projection divergence");
		require(Float.isFinite(diagnostics.maximumDivergence()), "non-finite post-projection divergence");
		require(Float.isFinite(diagnostics.rmsDivergence()), "non-finite RMS divergence");
		require(Float.isFinite(diagnostics.maximumPressure()), "non-finite pressure");
		require(Float.isFinite(diagnostics.centerOfMassX()), "non-finite center of mass");
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}

	private record Timing(int steps, double averageMillis, double minimumMillis, double maximumMillis) {
		String format() { return String.format("steps=%d avg=%.3fms min=%.3fms max=%.3fms", steps, averageMillis, minimumMillis, maximumMillis); }
	}
}

package com.example.gpuprobe;

import com.example.fluid.FlipFluidDomain;
import com.example.fluid.FluidSolverConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

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
		results.add(validateProbeCommandTree());
		results.add(validateManualStepScheduling());
		results.add(validateDamBreak(device));
		results.add(validateHydrostaticColumn(device));
		results.add(validateMultigridHydrostaticColumn(device, VulkanFlipPipeline.PressureSolver.MULTIGRID_TWO_LEVEL, "MG2"));
		results.add(validateMultigridHydrostaticColumn(device, VulkanFlipPipeline.PressureSolver.MULTIGRID_HIERARCHY, "MGH"));
		results.add(validateMultigridObstacles(device));
		results.add(validateWorldCollisionMaskIngestion(device));
		results.add(validatePressureSolvers(device));
		results.add(validatePressureConvergenceCurves(device));
		results.add(validateMemoryModes(device));
		results.add(validateMultigridMemoryModes(device));
		results.add(validateHierarchyMemoryModes(device));
		results.add(benchmarkCurrentDomain(device, VulkanFlipPipeline.MemoryMode.DEVICE_LOCAL_STAGING));
		results.add(benchmarkCurrentDomain(device, VulkanFlipPipeline.MemoryMode.HOST_VISIBLE));
		if (!skipLarge) {
			results.add(validateLargeHierarchyHydrostaticColumn(device));
			results.add(validateLargeHierarchyObstacles(device));
			results.add(benchmarkLargeMultigridCurve(device));
			results.add(benchmarkLargeHierarchyCurve(device));
			results.add(benchmarkLargePressureSolvers(device));
			results.add(benchmarkThirtyTwoCubedBlocks(device));
		}
		for (String result : results) System.out.println(result);
		System.out.printf("GPU FLIP validation passed on device %d in %.3f s%n", device, secondsSince(suiteStarted));
	}

	private static String validateProbeCommandTree() {
		var probe = RealWaterGpuProbeMod.probeCommand().build();
		require("probe".equals(probe.getName()) && probe.getCommand() != null, "probe command lost its default executor");
		var device = probe.getChild("device");
		require(device != null && device.getCommand() != null, "probe device argument is not nested under probe");
		require(probe.getChildren().size() == 1, "probe command has an unexpected child layout");
		return "correctness command tree: /realwatergpu probe and /realwatergpu probe <device> are both executable";
	}

	private static String validateManualStepScheduling() {
		AtomicInteger executed = new AtomicInteger();
		int completed = GpuProbeService.runManualLoop(6, () -> { executed.incrementAndGet(); return true; }, () -> 0);
		require(completed == 6 && executed.get() == 6, "paused manual step count collapsed");
		executed.set(0);
		completed = GpuProbeService.runManualLoop(20, () -> { executed.incrementAndGet(); return true; },
				() -> executed.get() >= 3 ? 3 : executed.get());
		require(completed == 3 && executed.get() == 3, "manual step ignored hard-overrun guard");
		return "correctness manual scheduling: requested=6 completed=6 hardGuard=3";
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
				require(close(diagnostics.particleVolume(), expectedVolume, 1.0E-3F), "GPU-resident particle volume diagnostic changed");
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
			require(close(diagnostics.particleVolume(), volume, 1.0E-3F), "column GPU-resident particle volume changed");
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

	private static String validateMultigridHydrostaticColumn(int device, VulkanFlipPipeline.PressureSolver solver,
	                                                         String label) {
		VulkanFlipPipeline.Config config = new VulkanFlipPipeline.Config(16, 20, 16, CELL_SIZE, DT, GRAVITY, RHO, 2, 16 * 20 * 16 * 8);
		try (VulkanFlipPipeline gpu = new VulkanFlipPipeline(config, device,
				VulkanFlipPipeline.MemoryMode.DEVICE_LOCAL_STAGING, solver)) {
			gpu.initializeColumn();
			float volume = gpu.totalParticleVolume();
			for (int i = 0; i < 8; i++) gpu.step();
			VulkanFlipPipeline.Diagnostics diagnostics = gpu.diagnostics();
			requireFinite(diagnostics);
			require(close(diagnostics.particleVolume(), volume, 1.0E-3F), label + " column particle volume changed");
			require(diagnostics.maximumDivergence() <= diagnostics.preProjectionMaximumDivergence() * 0.20F + 2.0E-3F,
					label + " column projection did not sufficiently reduce divergence");
			VulkanFlipPipeline.PressureProfile profile = gpu.pressureProfile();
			int lowest = firstOccupied(profile), highest = lastOccupied(profile);
			require(lowest >= 0 && highest > lowest, label + " column pressure profile has insufficient occupied layers");
			float bottom = profile.averageByY()[lowest], top = profile.averageByY()[highest];
			require(Float.isFinite(bottom) && Float.isFinite(top) && bottom > 8_000.0F && bottom < 11_000.0F
					&& Math.abs(top) < 100.0F && diagnostics.maximumPressure() > 10_000.0F && diagnostics.maximumPressure() < 16_000.0F,
					label + " hydrostatic pressure profile failed: max=" + diagnostics.maximumPressure() + ", bottom=" + bottom + ", top=" + top);
			return String.format("correctness %s column: cycles=2 levels=%d volume=%.3f cells maxP=%.3fPa bottomP=%.3fPa topP=%.3fPa postDiv=%.6f preDiv=%.6f",
					label, gpu.multigridLevelCount(), volume, diagnostics.maximumPressure(), bottom, top,
					diagnostics.maximumDivergence(), diagnostics.preProjectionMaximumDivergence());
		}
	}

	private static String validateMultigridObstacles(int device) {
		VulkanFlipPipeline.Config jacobiConfig = new VulkanFlipPipeline.Config(24, 16, 24, CELL_SIZE, DT, GRAVITY, RHO,
				96, 24 * 16 * 24 * 8);
		VulkanFlipPipeline.Config mgConfig = new VulkanFlipPipeline.Config(24, 16, 24, CELL_SIZE, DT, GRAVITY, RHO,
				2, 24 * 16 * 24 * 8);
		try (VulkanFlipPipeline jacobi = new VulkanFlipPipeline(jacobiConfig, device);
		     VulkanFlipPipeline multigrid = new VulkanFlipPipeline(mgConfig, device,
				     VulkanFlipPipeline.MemoryMode.DEVICE_LOCAL_STAGING, VulkanFlipPipeline.PressureSolver.MULTIGRID_TWO_LEVEL);
		     VulkanFlipPipeline hierarchy = new VulkanFlipPipeline(mgConfig, device,
				     VulkanFlipPipeline.MemoryMode.DEVICE_LOCAL_STAGING, VulkanFlipPipeline.PressureSolver.MULTIGRID_HIERARCHY)) {
			jacobi.initializeObstacleDamBreak();
			multigrid.initializeObstacleDamBreak();
			hierarchy.initializeObstacleDamBreak();
			require(jacobi.particleCount() == multigrid.particleCount()
					&& multigrid.particleCount() == hierarchy.particleCount(), "obstacle solver particle counts differ");
			require(jacobi.solidCellCount() == 228 && multigrid.solidCellCount() == 228
					&& hierarchy.solidCellCount() == 228, "synthetic obstacle cell count");
			float volume = multigrid.totalParticleVolume();
			float blockedCoupling = 0.0F, hierarchyBlockedCoupling = 0.0F;
			float openCoupling = 0.0F, hierarchyOpenCoupling = 0.0F;
			for (int step = 0; step < 8; step++) {
				jacobi.step();
				multigrid.step();
				hierarchy.step();
				VulkanFlipPipeline.Diagnostics jacobiDiagnostics = jacobi.diagnostics();
				VulkanFlipPipeline.Diagnostics mgDiagnostics = multigrid.diagnostics();
				VulkanFlipPipeline.Diagnostics hierarchyDiagnostics = hierarchy.diagnostics();
				requireFinite(jacobiDiagnostics); requireFinite(mgDiagnostics); requireFinite(hierarchyDiagnostics);
				require(close(mgDiagnostics.particleVolume(), volume, 1.0E-3F), "MG2 obstacle volume changed");
				require(close(hierarchyDiagnostics.particleVolume(), volume, 1.0E-3F), "MGH obstacle volume changed");
				require(mgDiagnostics.maximumDivergence() <= mgDiagnostics.preProjectionMaximumDivergence() * 0.50F + 3.0E-3F,
						"MG2 obstacle projection did not sufficiently reduce divergence at step " + step);
				require(hierarchyDiagnostics.maximumDivergence() <= hierarchyDiagnostics.preProjectionMaximumDivergence() * 0.50F + 3.0E-3F,
						"MGH obstacle projection did not sufficiently reduce divergence at step " + step);
				if (step == 0) {
					float[] xWeights = multigrid.coarseXWeights();
					float[] hierarchyXWeights = hierarchy.coarseXWeights();
					int coarseNx = 12, coarseNy = 8;
					for (int z = 3; z < 9; z++) for (int y = 0; y < 6; y++) {
						int cell = 5 + coarseNx * (y + coarseNy * z);
						blockedCoupling = Math.max(blockedCoupling, Math.abs(xWeights[cell]));
						hierarchyBlockedCoupling = Math.max(hierarchyBlockedCoupling, Math.abs(hierarchyXWeights[cell]));
					}
					openCoupling = xWeights[5];
					hierarchyOpenCoupling = hierarchyXWeights[5];
				}
			}
			require(blockedCoupling <= 1.0E-6F, "MG2 coarse operator crossed thin wall: " + blockedCoupling);
			require(openCoupling >= 0.99F, "MG2 coarse operator lost open face: " + openCoupling);
			require(hierarchyBlockedCoupling <= 1.0E-6F, "MGH coarse operator crossed thin wall: " + hierarchyBlockedCoupling);
			require(hierarchyOpenCoupling >= 0.99F, "MGH coarse operator lost open face: " + hierarchyOpenCoupling);
			require(jacobi.particlesInsideSolidCount() == 0 && multigrid.particlesInsideSolidCount() == 0
					&& hierarchy.particlesInsideSolidCount() == 0,
					"particles entered synthetic obstacle");
			VulkanFlipPipeline.SnapshotData jacobiSnapshot = jacobi.snapshot();
			VulkanFlipPipeline.SnapshotData mgSnapshot = multigrid.snapshot();
			VulkanFlipPipeline.SnapshotData hierarchySnapshot = hierarchy.snapshot();
			require(mismatches(jacobiSnapshot.solids(), mgSnapshot.solids()) == 0, "obstacle solid snapshots differ");
			require(mismatches(mgSnapshot.solids(), hierarchySnapshot.solids()) == 0, "MGH obstacle solid snapshot differs");
			VulkanFlipPipeline.Diagnostics jacobiDiagnostics = jacobi.diagnostics();
			VulkanFlipPipeline.Diagnostics mgDiagnostics = multigrid.diagnostics();
			VulkanFlipPipeline.Diagnostics hierarchyDiagnostics = hierarchy.diagnostics();
			return String.format("correctness MG2/MGH obstacles: solids=228 particles=%d volume=%.3f cells coarseWall=%.6f/%.6f open=%.3f/%.3f inside=0 JacobiDiv %.5f->%.5f MG2Div %.5f->%.5f MGHDiv %.5f->%.5f",
					multigrid.particleCount(), volume, blockedCoupling, hierarchyBlockedCoupling, openCoupling, hierarchyOpenCoupling,
					jacobiDiagnostics.preProjectionMaximumDivergence(), jacobiDiagnostics.maximumDivergence(),
					mgDiagnostics.preProjectionMaximumDivergence(), mgDiagnostics.maximumDivergence(),
					hierarchyDiagnostics.preProjectionMaximumDivergence(), hierarchyDiagnostics.maximumDivergence());
		}
	}

	private static String validateWorldCollisionMaskIngestion(int device) {
		int blocksX = 12, blocksY = 8, blocksZ = 12;
		byte[] blockMasks = new byte[blocksX * blocksY * blocksZ];
		for (int z = 0; z < blocksZ; z++) for (int x = 0; x < blocksX; x++) {
			blockMasks[blockIndex(x, 0, z, blocksX, blocksY)] = (byte) 0xff;
		}
		// A lower half-block shelf exercises the same eight-bit local ordering used by world shape sampling.
		int lowerHalfMask = (1 << 0) | (1 << 1) | (1 << 4) | (1 << 5);
		for (int z = 3; z < 9; z++) for (int x = 3; x < 7; x++) {
			blockMasks[blockIndex(x, 3, z, blocksX, blocksY)] = (byte) lowerHalfMask;
		}
		for (int y = 1; y < 6; y++) for (int z = 5; z < 7; z++) {
			blockMasks[blockIndex(6, y, z, blocksX, blocksY)] = (byte) 0xff;
		}

		byte[] solidCells = WorldCollisionSnapshot.expandBlockMasks(blocksX, blocksY, blocksZ, blockMasks);
		int nx = blocksX * 2, ny = blocksY * 2, nz = blocksZ * 2;
		int solidCount = WorldCollisionSnapshot.countSolidCells(solidCells);
		require(solidCount == 1_320, "expanded world collision solid count: " + solidCount);
		require(solidCells[6 + nx * (6 + ny * 6)] != 0, "lower-half block bit did not map to expected subcell");
		require(solidCells[6 + nx * (7 + ny * 6)] == 0, "lower-half block occupied upper subcell");

		int expectedFluidCells = 0;
		for (int z = 0; z < nz; z++) for (int y = 0; y < ny * 3 / 4; y++) for (int x = 0; x < nx * 2 / 5; x++) {
			if (solidCells[x + nx * (y + ny * z)] == 0) expectedFluidCells++;
		}
		FlipFluidDomain cpuFallback = GpuProbeService.createCpuDamBreak(solidCells);
		require(mismatches(cpuFallback.copySolidMask(), solidCells) == 0, "CPU fallback world collision mask changed");
		require(close(cpuFallback.totalVolume(), expectedFluidCells, 1.0E-3F), "CPU fallback world collision volume");
		float cpuVolume = cpuFallback.totalVolume();
		for (int step = 0; step < 4; step++) cpuFallback.step();
		require(close(cpuFallback.totalVolume(), cpuVolume, 1.0E-3F), "CPU fallback world collision volume changed");
		VulkanFlipPipeline.Config config = new VulkanFlipPipeline.Config(nx, ny, nz, CELL_SIZE, DT, GRAVITY, RHO,
				2, nx * ny * nz * 8);
		try (VulkanFlipPipeline gpu = new VulkanFlipPipeline(config, device,
				VulkanFlipPipeline.MemoryMode.DEVICE_LOCAL_STAGING, VulkanFlipPipeline.PressureSolver.MULTIGRID_TWO_LEVEL)) {
			gpu.initializeDamBreakWithSolids(solidCells);
			require(gpu.solidCellCount() == solidCount, "GPU world collision mask count");
			require(gpu.particleCount() == expectedFluidCells * 8, "world collision initialization particle count");
			require(mismatches(gpu.snapshot().solids(), solidCells) == 0, "world collision snapshot changed during upload");
			float volume = gpu.totalParticleVolume();
			for (int step = 0; step < 8; step++) {
				gpu.step();
				VulkanFlipPipeline.Diagnostics diagnostics = gpu.diagnostics();
				requireFinite(diagnostics);
				require(close(diagnostics.particleVolume(), volume, 1.0E-3F), "world collision volume changed");
			}
			require(gpu.particlesInsideSolidCount() == 0, "particle entered expanded world collision mask");
			VulkanFlipPipeline.Diagnostics diagnostics = gpu.diagnostics();
			return String.format("correctness world collision ingestion: blocks=%dx%dx%d solids=%d particles=%d volume=%.3f cells inside=0 MG2Div %.5f->%.5f",
					blocksX, blocksY, blocksZ, solidCount, gpu.particleCount(), volume,
					diagnostics.preProjectionMaximumDivergence(), diagnostics.maximumDivergence());
		}
	}

	private static int blockIndex(int x, int y, int z, int blocksX, int blocksY) {
		return x + blocksX * (y + blocksY * z);
	}

	private static String validatePressureSolvers(int device) {
		List<PressureResult> results = new ArrayList<>();
		for (VulkanFlipPipeline.PressureSolver solver : VulkanFlipPipeline.PressureSolver.values()) {
			int iterations = switch (solver) { case JACOBI -> 96; case RBGS_SOR -> 48; case PCG, PCG_DIAGONAL -> 32; case MULTIGRID_TWO_LEVEL, MULTIGRID_HIERARCHY -> 2; };
			float maximumRatio = switch (solver) { case JACOBI -> 0.25F; case RBGS_SOR -> 0.08F; case PCG, PCG_DIAGONAL -> 0.02F; case MULTIGRID_TWO_LEVEL, MULTIGRID_HIERARCHY -> 0.20F; };
			VulkanFlipPipeline.Config config = new VulkanFlipPipeline.Config(24, 16, 24, CELL_SIZE, DT, GRAVITY, RHO,
					iterations, 24 * 16 * 24 * 8);
			try (VulkanFlipPipeline gpu = new VulkanFlipPipeline(config, device,
					VulkanFlipPipeline.MemoryMode.DEVICE_LOCAL_STAGING, solver)) {
				gpu.initializeDamBreak();
				double totalMillis = 0.0;
				VulkanFlipPipeline.Diagnostics diagnostics = null;
				for (int step = 0; step < 6; step++) {
					gpu.step();
					totalMillis += gpu.lastStepMilliseconds();
					diagnostics = gpu.diagnostics();
					requireFinite(diagnostics);
					require(close(diagnostics.particleVolume(), 2_592.0F, 1.0E-3F), solver + " volume changed");
					require(diagnostics.maximumDivergence() <= diagnostics.preProjectionMaximumDivergence() * maximumRatio + 2.0E-3F,
							solver + " convergence ratio failed at step " + step);
				}
				results.add(new PressureResult(solver, iterations, totalMillis / 6.0, diagnostics));
			}
		}
		return "correctness pressure solvers: " + results.stream().map(PressureResult::format).reduce((a, b) -> a + " | " + b).orElseThrow();
	}

	private static String validatePressureConvergenceCurves(int device) {
		List<String> curves = new ArrayList<>();
		for (VulkanFlipPipeline.PressureSolver solver : VulkanFlipPipeline.PressureSolver.values()) {
			int[] iterationCounts = switch (solver) {
				case JACOBI -> new int[] {32, 64, 96};
				case RBGS_SOR -> new int[] {16, 32, 48};
				case PCG, PCG_DIAGONAL -> new int[] {16, 24, 32};
				case MULTIGRID_TWO_LEVEL, MULTIGRID_HIERARCHY -> new int[] {1, 2, 3};
			};
			List<CurvePoint> points = new ArrayList<>();
			for (int iterations : iterationCounts) {
				VulkanFlipPipeline.Config config = new VulkanFlipPipeline.Config(24, 16, 24, CELL_SIZE, DT, GRAVITY, RHO,
						iterations, 24 * 16 * 24 * 8);
				try (VulkanFlipPipeline gpu = new VulkanFlipPipeline(config, device,
						VulkanFlipPipeline.MemoryMode.DEVICE_LOCAL_STAGING, solver)) {
					gpu.initializeDamBreak();
					gpu.step();
					double minimumMillis = Double.POSITIVE_INFINITY;
					VulkanFlipPipeline.Diagnostics diagnostics = null;
					for (int sample = 0; sample < 3; sample++) {
						gpu.initializeDamBreak();
						gpu.step();
						minimumMillis = Math.min(minimumMillis, gpu.lastStepMilliseconds());
						diagnostics = gpu.diagnostics();
					}
					requireFinite(diagnostics);
					points.add(new CurvePoint(iterations, minimumMillis, diagnostics.maximumDivergence()));
				}
			}
			require(points.getLast().maximumDivergence() < points.getFirst().maximumDivergence(), solver + " convergence curve did not improve");
			curves.add(solver.label() + " " + points.stream().map(CurvePoint::format).reduce((a, b) -> a + ", " + b).orElseThrow());
		}
		return "correctness pressure convergence curves: " + String.join(" | ", curves);
	}

	private static String validateMemoryModes(int device) {
		return validateMemoryModes(device, VulkanFlipPipeline.PressureSolver.JACOBI, 96, 6, "memory modes");
	}

	private static String validateMultigridMemoryModes(int device) {
		return validateMemoryModes(device, VulkanFlipPipeline.PressureSolver.MULTIGRID_TWO_LEVEL, 2, 4, "MG2 memory modes");
	}

	private static String validateHierarchyMemoryModes(int device) {
		return validateMemoryModes(device, VulkanFlipPipeline.PressureSolver.MULTIGRID_HIERARCHY, 2, 4, "MGH memory modes");
	}

	private static String validateMemoryModes(int device, VulkanFlipPipeline.PressureSolver solver, int iterations,
	                                          int steps, String label) {
		VulkanFlipPipeline.Config config = new VulkanFlipPipeline.Config(24, 16, 24, CELL_SIZE, DT, GRAVITY, RHO, iterations, 24 * 16 * 24 * 8);
		try (VulkanFlipPipeline deviceLocal = new VulkanFlipPipeline(config, device, VulkanFlipPipeline.MemoryMode.DEVICE_LOCAL_STAGING, solver);
		     VulkanFlipPipeline hostVisible = new VulkanFlipPipeline(config, device, VulkanFlipPipeline.MemoryMode.HOST_VISIBLE, solver)) {
			if (solver == VulkanFlipPipeline.PressureSolver.MULTIGRID_TWO_LEVEL
					|| solver == VulkanFlipPipeline.PressureSolver.MULTIGRID_HIERARCHY) {
				deviceLocal.initializeObstacleDamBreak();
				hostVisible.initializeObstacleDamBreak();
			} else {
				deviceLocal.initializeDamBreak();
				hostVisible.initializeDamBreak();
			}
			for (int i = 0; i < steps; i++) { deviceLocal.step(); hostVisible.step(); }
			VulkanFlipPipeline.Diagnostics deviceDiagnostics = deviceLocal.diagnostics();
			VulkanFlipPipeline.Diagnostics hostDiagnostics = hostVisible.diagnostics();
			requireFinite(deviceDiagnostics); requireFinite(hostDiagnostics);
			VulkanFlipPipeline.SnapshotData deviceSnapshot = deviceLocal.snapshot();
			VulkanFlipPipeline.SnapshotData hostSnapshot = hostVisible.snapshot();
			int markerMismatches = mismatches(deviceSnapshot.markers(), hostSnapshot.markers());
			int solidMismatches = mismatches(deviceSnapshot.solids(), hostSnapshot.solids());
			double densityMae = meanAbsoluteByteDifference(deviceSnapshot.density(), hostSnapshot.density());
			float centerDifference = Math.abs(deviceDiagnostics.centerOfMassX() - hostDiagnostics.centerOfMassX());
			float pressureDifference = Math.abs(deviceDiagnostics.maximumPressure() - hostDiagnostics.maximumPressure());
			require(markerMismatches == 0, solver + " device-local/host-visible marker masks differ in " + markerMismatches + " cells");
			require(solidMismatches == 0, solver + " device-local/host-visible solid masks differ in " + solidMismatches + " cells");
			require(densityMae <= 0.05, solver + " device-local/host-visible density MAE " + densityMae);
			require(centerDifference <= 1.0E-3F, solver + " device-local/host-visible center difference " + centerDifference);
			require(pressureDifference <= Math.max(0.05F, hostDiagnostics.maximumPressure() * 1.0E-4F),
					solver + " device-local/host-visible pressure difference " + pressureDifference);
			return String.format("correctness %s: steps=%d markersMismatch=%d solidMismatch=%d densityMAE=%.4f centerDifference=%.6f pressureDifference=%.6f",
					label, steps, markerMismatches, solidMismatches, densityMae, centerDifference, pressureDifference);
		}
	}

	private static String benchmarkCurrentDomain(int device, VulkanFlipPipeline.MemoryMode memoryMode) {
		VulkanFlipPipeline.Config config = new VulkanFlipPipeline.Config(24, 16, 24, CELL_SIZE, DT, GRAVITY, RHO, 96, 24 * 16 * 24 * 8);
		try (VulkanFlipPipeline gpu = new VulkanFlipPipeline(config, device, memoryMode)) {
			gpu.initializeDamBreak();
			for (int i = 0; i < 3; i++) gpu.step();
			Timing timing = time(gpu, 20);
			VulkanFlipPipeline.ReadbackStats readback = gpu.measureReadback();
			return "benchmark current 12x8x12 blocks / 24x16x24 grid [" + memoryMode + "]: " + timing.format()
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
					minimumMillis = Math.min(minimumMillis, gpu.lastStepMilliseconds());
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
					minimumMillis = Math.min(minimumMillis, gpu.lastStepMilliseconds());
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

	private static int mismatches(byte[] left, byte[] right) {
		if (left.length != right.length) throw new IllegalArgumentException("array sizes differ");
		int count = 0;
		for (int i = 0; i < left.length; i++) if (left[i] != right[i]) count++;
		return count;
	}

	private static double meanAbsoluteByteDifference(byte[] left, byte[] right) {
		if (left.length != right.length) throw new IllegalArgumentException("array sizes differ");
		long total = 0L;
		for (int i = 0; i < left.length; i++) total += Math.abs(Byte.toUnsignedInt(left[i]) - Byte.toUnsignedInt(right[i]));
		return (double) total / left.length;
	}

	private static boolean close(float actual, float expected, float tolerance) { return Math.abs(actual - expected) <= tolerance; }
	private static double secondsSince(long started) { return (System.nanoTime() - started) / 1_000_000_000.0; }

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

	private record Timing(int steps, double averageMillis, double minimumMillis, double maximumMillis) {
		String format() { return String.format("steps=%d avg=%.3fms min=%.3fms max=%.3fms", steps, averageMillis, minimumMillis, maximumMillis); }
	}
	private record PressureResult(VulkanFlipPipeline.PressureSolver solver, int iterations, double milliseconds,
	                              VulkanFlipPipeline.Diagnostics diagnostics) {
		String format() {
			return String.format("%s/%d avg %.3fms step6 div %.6f->%.6f", solver.label(), iterations, milliseconds,
					diagnostics.preProjectionMaximumDivergence(), diagnostics.maximumDivergence());
		}
	}
	private record CurvePoint(int iterations, double milliseconds, float maximumDivergence) {
		String format() { return String.format("%d:%.6f/%.3fms", iterations, maximumDivergence, milliseconds); }
	}
}

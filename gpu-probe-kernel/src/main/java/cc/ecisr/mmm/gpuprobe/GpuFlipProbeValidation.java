package cc.ecisr.mmm.gpuprobe;

import cc.ecisr.mmm.fluid.FluidDomain;
import cc.ecisr.mmm.fluid.FluidSolverConfig;

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
		List<String> options = List.of(args).subList(Math.min(1, args.length), args.length);
		boolean skipLarge = options.contains("--skip-large");
		boolean skipLong = options.contains("--skip-long");
		long suiteStarted = System.nanoTime();
		List<String> results = new ArrayList<>();
		results.add(validateTimestampQueries(device));
		results.add(validateBoundedSubmissionRing(device));
		results.add(validateCpuReadableCheckpoint(device));
		results.add(validateManualStepScheduling());
		results.add(validateDamBreak(device));
		results.add(validateHydrostaticColumn(device));
		results.add(validatePressureSolvers(device));
		results.add(validatePressureConvergenceCurves(device));
		results.add(validateMultigridHydrostaticColumn(device, VulkanFlipPipeline.PressureSolver.MULTIGRID_TWO_LEVEL, "MG2"));
		results.add(validateMultigridHydrostaticColumn(device, VulkanFlipPipeline.PressureSolver.MULTIGRID_HIERARCHY, "MGH"));
		results.add(validateMultigridObstacles(device));
		results.add(validateWorldCollisionMaskIngestion(device));
		results.add(validateMemoryModes(device));
		results.add(validateMultigridMemoryModes(device));
		results.add(validateHierarchyMemoryModes(device));
		if (!skipLong) results.add(GpuLongRunSurfaceValidation.run(device));
		results.add(benchmarkCurrentDomain(device, VulkanFlipPipeline.MemoryMode.DEVICE_LOCAL_STAGING));
		results.add(benchmarkCurrentDomain(device, VulkanFlipPipeline.MemoryMode.HOST_VISIBLE));
		if (!skipLarge) results.addAll(GpuLargeDomainValidation.run(device));
		for (String result : results) System.out.println(result);
		System.out.printf("GPU FLIP validation passed on device %d in %.3f s%n", device, secondsSince(suiteStarted));
	}

	private static String validateTimestampQueries(int device) {
		VulkanFlipPipeline.Config config = new VulkanFlipPipeline.Config(24, 16, 24, CELL_SIZE, DT, GRAVITY, RHO,
				96, 24 * 16 * 24 * 8);
		try (VulkanFlipPipeline gpu = new VulkanFlipPipeline(config, device)) {
			require(gpu.hasTimestampQueries(), "selected compute queue does not support Vulkan timestamp queries");
			gpu.initializeDamBreak();
			double schedulingTotal = 0.0, completionWallTotal = 0.0, gpuTotal = 0.0;
			for (int step = 0; step < 4; step++) {
				long started = System.nanoTime();
				gpu.step();
				double schedulingMillis = gpu.lastStepMilliseconds(), gpuMillis = gpu.lastGpuStepMilliseconds();
				double completionWallMillis = (System.nanoTime() - started) / 1_000_000.0;
				require(close(gpu.diagnostics().particleVolume(), gpu.totalParticleVolume(), 1.0E-3F),
						"timestamp step " + step + " changed marker volume: "
								+ gpu.diagnostics().particleVolume() + " != " + gpu.totalParticleVolume());
				require(Double.isFinite(gpuMillis) && gpuMillis > 0.0, "invalid Vulkan timestamp duration");
				require(Double.isFinite(schedulingMillis) && schedulingMillis >= 0.0, "invalid scheduling wall duration");
				require(gpuMillis <= completionWallMillis + 0.25, "GPU timestamp exceeds completed wall duration");
				schedulingTotal += schedulingMillis;
				completionWallTotal += completionWallMillis;
				gpuTotal += gpuMillis;
			}
			return String.format("correctness timestamp queries: steps=4 GPU avg %.3fms scheduling wall avg %.3fms completed wall avg %.3fms",
					gpuTotal / 4.0, schedulingTotal / 4.0, completionWallTotal / 4.0);
		}
	}

	private static String validateBoundedSubmissionRing(int device) {
		VulkanFlipPipeline.Config config = new VulkanFlipPipeline.Config(24, 16, 24, CELL_SIZE, DT, GRAVITY, RHO,
				32, 24 * 16 * 24 * 8);
		try (VulkanFlipPipeline gpu = new VulkanFlipPipeline(config, device)) {
			gpu.initializeDamBreak();
			gpu.diagnostics();
			require(gpu.submissionRingSize() == 3, "unexpected Vulkan submission ring size");
			require(gpu.regularStagingBytes() == 3L * 64L, "regular staging ring is not three compact diagnostic slots");
			for (int step = 0; step < 6; step++) gpu.step();
			VulkanFlipPipeline.Diagnostics diagnostics = gpu.diagnostics();
			require(gpu.maximumOutstandingSubmissions() == gpu.submissionRingSize(),
					"Vulkan submissions did not exercise every bounded ring slot");
			float copiedVolume = totalParticleVolume(gpu.copyParticlePositionVolume());
			require(close(diagnostics.particleVolume(), gpu.totalParticleVolume(), 1.0E-3F),
					"ring-staged diagnostics changed particle volume: " + diagnostics.particleVolume()
							+ " != " + gpu.totalParticleVolume() + " copied=" + copiedVolume);
			require(Double.isFinite(gpu.lastGpuStepMilliseconds()) && gpu.lastGpuStepMilliseconds() > 0.0,
					"ring-staged timestamp is unavailable");
			String result = String.format("correctness bounded staging ring: slots=%d bytes=%d maximumOutstanding=%d volume=%.3f closeWithOutstanding=3",
					gpu.submissionRingSize(), gpu.regularStagingBytes(), gpu.maximumOutstandingSubmissions(), diagnostics.particleVolume());
			for (int step = 0; step < gpu.submissionRingSize(); step++) gpu.step();
			return result;
		}
	}

	private static float totalParticleVolume(float[] positionVolume) {
		float total = 0.0F;
		for (int index = 3; index < positionVolume.length; index += 4) total += positionVolume[index];
		return total;
	}

	private static String validateCpuReadableCheckpoint(int device) {
		VulkanFlipPipeline.Config config = new VulkanFlipPipeline.Config(24, 16, 24, CELL_SIZE, DT, GRAVITY, RHO,
				2, 24 * 16 * 24 * 8);
		StringBuilder modes = new StringBuilder();
		for (VulkanFlipPipeline.MemoryMode mode : VulkanFlipPipeline.MemoryMode.values()) {
			try (VulkanFlipPipeline gpu = new VulkanFlipPipeline(config, device, mode,
					VulkanFlipPipeline.PressureSolver.MULTIGRID_TWO_LEVEL)) {
				gpu.initializeObstacleDamBreak();
				int initialSolidCells = gpu.solidCellCount();
				require(initialSolidCells > 0, "initial upload lost obstacle solids in " + mode);
				for (int step = 0; step < 4; step++) gpu.step();
				VulkanFlipPipeline.Diagnostics diagnostics = gpu.diagnostics();
				GpuProbeCheckpoint checkpoint = gpu.captureCheckpoint(4L);
				require(checkpoint.generation() == 4L, "checkpoint generation changed");
				require(checkpoint.particleCount() == gpu.particleCount(), "checkpoint particle count changed");
				require(close(checkpoint.volumeCells(), gpu.totalParticleVolume(), 1.0E-4F),
						"checkpoint particle volume changed");
				require(Math.abs(checkpoint.centerOfMassX() - diagnostics.centerOfMassX()) <= 1.0E-3F,
						"checkpoint center of mass differs from completed GPU generation");
				require(checkpoint.solidCellCount() == initialSolidCells,
						"checkpoint changed obstacle solids in " + mode + ": "
								+ initialSolidCells + " -> " + checkpoint.solidCellCount());
				long expectedCpuBytes = (long) checkpoint.particleCount() * 7L * Float.BYTES + config.cellCount();
				long expectedReadbackBytes = (long) checkpoint.particleCount() * 8L * Float.BYTES
						+ (long) config.cellCount() * Integer.BYTES;
				require(checkpoint.cpuBytes() == expectedCpuBytes, "checkpoint CPU footprint is not compact and bounded");
				require(checkpoint.gpuReadbackBytes() == expectedReadbackBytes, "checkpoint readback byte count changed");

				FluidDomain recovered = checkpoint.restore(GpuProbeScenario.cpuConfig());
				require(recovered.particleCount() == checkpoint.particleCount(), "CPU recovery particle count changed");
				require(close(recovered.totalVolume(), checkpoint.volumeCells(), 1.0E-4F), "CPU recovery volume changed");
				require(Math.abs(recovered.centerOfMassX() - checkpoint.centerOfMassX()) <= 1.0E-5F,
						"CPU recovery center of mass changed");
				require(nonzero(recovered.copyBinarySolidMask()) == checkpoint.solidCellCount(), "CPU recovery solid mask changed");
				float recoveredVolume = recovered.totalVolume();
				recovered = GpuProbeScenario.evaluateCpuStep(recovered, 4L).claimPreparedCpuDomain();
				require(close(recovered.totalVolume(), recoveredVolume, 1.0E-4F),
						"CPU recovery first step changed particle truth");

				for (int step = 0; step < 2; step++) gpu.step();
				FluidDomain rolledBack = checkpoint.restore(GpuProbeScenario.cpuConfig());
				require(Math.abs(rolledBack.centerOfMassX() - checkpoint.centerOfMassX()) <= 1.0E-5F,
						"checkpoint mutated after newer GPU submissions");
				require(!GpuProbeScenario.checkpointDue(23, checkpoint)
						&& GpuProbeScenario.checkpointDue(24, checkpoint), "checkpoint cadence is not twenty generations");
				if (!modes.isEmpty()) modes.append(',');
				modes.append(mode);
			}
		}
		return String.format("correctness CPU-readable runtime recovery checkpoint: modes=%s generation=4 interval=20",
				modes);
	}

	private static String validateManualStepScheduling() {
		AtomicInteger executed = new AtomicInteger();
		int completed = GpuProbeScenario.runManualLoop(6, () -> { executed.incrementAndGet(); return true; }, () -> 0);
		require(completed == 6 && executed.get() == 6, "paused manual step count collapsed");
		executed.set(0);
		completed = GpuProbeScenario.runManualLoop(20, () -> { executed.incrementAndGet(); return true; },
				() -> executed.get() >= 3 ? 3 : executed.get());
		require(completed == 3 && executed.get() == 3, "manual step ignored hard-overrun guard");
		return "correctness manual scheduling: requested=6 completed=6 hardGuard=3";
	}

	private static String validateDamBreak(int device) {
		VulkanFlipPipeline.Config config = new VulkanFlipPipeline.Config(24, 16, 24, CELL_SIZE, DT, GRAVITY, RHO, 96, 24 * 16 * 24 * 8);
		try (VulkanFlipPipeline gpu = new VulkanFlipPipeline(config, device)) {
			gpu.initializeDamBreak();
			FluidDomain cpu = cpuDamBreak(config);
			int expectedParticles = 9 * 12 * 24 * 8;
			float expectedVolume = expectedParticles / 8.0F;
			require(gpu.particleCount() == expectedParticles, "dam particle count");
			require(close(gpu.totalParticleVolume(), expectedVolume, 1.0E-4F), "GPU particle volume before stepping");
			require(close(cpu.totalVolume(), expectedVolume, 1.0E-4F), "CPU particle volume before stepping");
			require(java.util.Arrays.equals(gpu.copyParticlePositionVolume(),
					cpu.exportParticleState().copyPositionVolume()), "CPU/GPU initial marker quadrature differs");

			VulkanFlipPipeline.Diagnostics diagnostics = null;
			double gpuWallMillis = 0.0;
			double gpuDeviceMillis = 0.0;
			double cpuMillis = 0.0;
			for (int step = 0; step < 6; step++) {
				gpu.step();
				gpuWallMillis += gpu.lastStepMilliseconds();
				gpuDeviceMillis += gpu.lastGpuStepMilliseconds();
				long cpuStarted = System.nanoTime();
				cpu = GpuProbeScenario.evaluateCpuStep(cpu, step).claimPreparedCpuDomain();
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
					+ String.format(" particles=%d volume=%.3f cells centerDifference=%.4f postDiv=%.6f preDiv=%.6f GPU timestamp=%.3fms scheduling wall=%.3fms CPU=%.3fms markerCells=%d",
					gpu.particleCount(), gpu.totalParticleVolume(), centerDifference, diagnostics.maximumDivergence(),
					diagnostics.preProjectionMaximumDivergence(), gpuDeviceMillis / 6.0, gpuWallMillis / 6.0, cpuMillis / 6.0, markers);
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
		VulkanFlipPipeline.Config config = new VulkanFlipPipeline.Config(16, 20, 16, CELL_SIZE, DT, GRAVITY, RHO, 8, 16 * 20 * 16 * 8);
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
			require(Float.isFinite(bottom) && Float.isFinite(top)
					&& diagnostics.maximumPressure() > 0.0F && bottom > top,
					label + " hydrostatic pressure direction failed: max="
							+ diagnostics.maximumPressure() + ", bottom=" + bottom + ", top=" + top);
			return String.format("correctness %s column: cycles=%d levels=%d volume=%.3f cells maxP=%.3fPa bottomP=%.3fPa topP=%.3fPa residual=%.6g postDiv=%.6f preDiv=%.6f",
					label, config.pressureIterations(), gpu.multigridLevelCount(), volume, diagnostics.maximumPressure(), bottom, top,
					diagnostics.pressureResidual(),
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

		byte[] solidCells = CollisionMaskExpansion.expand(blocksX, blocksY, blocksZ, blockMasks);
		int nx = blocksX * 2, ny = blocksY * 2, nz = blocksZ * 2;
		int solidCount = CollisionMaskExpansion.countOccupied(solidCells);
		require(solidCount == 1_320, "expanded world collision solid count: " + solidCount);
		require(solidCells[6 + nx * (6 + ny * 6)] != 0, "lower-half block bit did not map to expected subcell");
		require(solidCells[6 + nx * (7 + ny * 6)] == 0, "lower-half block occupied upper subcell");

		int expectedFluidCells = 0;
		for (int z = 0; z < nz; z++) for (int y = 0; y < ny * 3 / 4; y++) for (int x = 0; x < nx * 2 / 5; x++) {
			if (solidCells[x + nx * (y + ny * z)] == 0) expectedFluidCells++;
		}
		FluidDomain cpuFallback = GpuProbeScenario.createCpuDamBreak(solidCells);
		require(mismatches(cpuFallback.copyBinarySolidMask(), solidCells) == 0, "CPU fallback world collision mask changed");
		require(close(cpuFallback.totalVolume(), expectedFluidCells, 1.0E-3F), "CPU fallback world collision volume");
		float cpuVolume = cpuFallback.totalVolume();
		for (int step = 0; step < 4; step++) {
			cpuFallback = GpuProbeScenario.evaluateCpuStep(cpuFallback, step).claimPreparedCpuDomain();
		}
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
			int iterations = switch (solver) { case JACOBI -> 96; case RBGS_SOR -> 48; case PCG_DIAGONAL -> 256; case MULTIGRID_TWO_LEVEL, MULTIGRID_HIERARCHY -> 2; };
			float maximumRatio = switch (solver) { case JACOBI -> 0.25F; case RBGS_SOR -> 0.08F; case PCG_DIAGONAL -> 0.02F; case MULTIGRID_TWO_LEVEL, MULTIGRID_HIERARCHY -> 0.20F; };
			VulkanFlipPipeline.Config config = new VulkanFlipPipeline.Config(24, 16, 24, CELL_SIZE, DT, GRAVITY, RHO,
					iterations, 24 * 16 * 24 * 8);
			try (VulkanFlipPipeline gpu = new VulkanFlipPipeline(config, device,
					VulkanFlipPipeline.MemoryMode.DEVICE_LOCAL_STAGING, solver)) {
				gpu.initializeDamBreak();
				double wallTotalMillis = 0.0, gpuTotalMillis = 0.0;
				VulkanFlipPipeline.Diagnostics diagnostics = null;
				for (int step = 0; step < 6; step++) {
					gpu.step();
					wallTotalMillis += gpu.lastStepMilliseconds();
					gpuTotalMillis += gpu.lastGpuStepMilliseconds();
					diagnostics = gpu.diagnostics();
					requireFinite(diagnostics);
					require(close(diagnostics.particleVolume(), 2_592.0F, 1.0E-3F), solver + " volume changed");
					require(diagnostics.maximumDivergence() <= diagnostics.preProjectionMaximumDivergence() * maximumRatio + 2.0E-3F,
							solver + " convergence ratio failed at step " + step);
				}
				results.add(new PressureResult(solver, iterations, gpuTotalMillis / 6.0, wallTotalMillis / 6.0, diagnostics));
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
				case PCG_DIAGONAL -> new int[] {16, 24, 32};
				case MULTIGRID_TWO_LEVEL, MULTIGRID_HIERARCHY -> new int[] {1, 2, 3};
			};
			List<CurvePoint> points = new ArrayList<>();
			for (int iterations : iterationCounts) {
				VulkanFlipPipeline.Config config = new VulkanFlipPipeline.Config(24, 16, 24, CELL_SIZE, DT, GRAVITY, RHO,
						iterations, GpuProbeScenario.PRESSURE_TOLERANCE, false, 24 * 16 * 24 * 8);
				try (VulkanFlipPipeline gpu = new VulkanFlipPipeline(config, device,
						VulkanFlipPipeline.MemoryMode.DEVICE_LOCAL_STAGING, solver)) {
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
			VulkanFlipPipeline.SnapshotData initialDeviceSnapshot = deviceLocal.snapshot();
			VulkanFlipPipeline.SnapshotData initialHostSnapshot = hostVisible.snapshot();
			require(mismatches(initialDeviceSnapshot.density(), initialHostSnapshot.density()) == 0,
					solver + " memory modes disagree before stepping");
			require(mismatches(initialDeviceSnapshot.markers(), initialHostSnapshot.markers()) == 0,
					solver + " memory modes lost initial marker occupancy");
			require(mismatches(initialDeviceSnapshot.solids(), initialHostSnapshot.solids()) == 0,
					solver + " memory modes lost initial solid occupancy");
			float initialVolume = deviceLocal.totalParticleVolume();
			require(close(hostVisible.totalParticleVolume(), initialVolume, 1.0E-4F),
					solver + " memory modes disagree on initial particle volume");
			for (int i = 0; i < steps; i++) { deviceLocal.step(); hostVisible.step(); }
			VulkanFlipPipeline.Diagnostics deviceDiagnostics = deviceLocal.diagnostics();
			VulkanFlipPipeline.Diagnostics hostDiagnostics = hostVisible.diagnostics();
			requireFinite(deviceDiagnostics); requireFinite(hostDiagnostics);
			VulkanFlipPipeline.SnapshotData deviceSnapshot = deviceLocal.snapshot();
			VulkanFlipPipeline.SnapshotData hostSnapshot = hostVisible.snapshot();
			int markerMismatches = mismatches(deviceSnapshot.markers(), hostSnapshot.markers());
			int solidMismatches = mismatches(deviceSnapshot.solids(), hostSnapshot.solids());
			double densityMae = meanAbsoluteByteDifference(deviceSnapshot.density(), hostSnapshot.density());
			int densityMaximumDifference = maximumAbsoluteByteDifference(deviceSnapshot.density(), hostSnapshot.density());
			float centerDifference = Math.abs(deviceDiagnostics.centerOfMassX() - hostDiagnostics.centerOfMassX());
			float pressureDifference = Math.abs(deviceDiagnostics.maximumPressure() - hostDiagnostics.maximumPressure());
			require(solidMismatches == 0, solver + " device-local/host-visible solid masks differ in " + solidMismatches + " cells");
			require(close(deviceDiagnostics.particleVolume(), initialVolume, 1.0E-3F),
					solver + " device-local execution changed particle volume");
			require(close(hostDiagnostics.particleVolume(), initialVolume, 1.0E-3F),
					solver + " host-visible execution changed particle volume");
			require(nonzero(deviceSnapshot.markers()) == deviceLocal.markerCellCountFromParticles(),
					solver + " device-local snapshot is not post-advection occupancy");
			require(nonzero(hostSnapshot.markers()) == hostVisible.markerCellCountFromParticles(),
					solver + " host-visible snapshot is not post-advection occupancy");
			// Floating-point atomic reductions make two independent nonlinear trajectories
			// non-bitwise-deterministic. The initial state tests memory transfer exactly;
			// each evolved state is then judged by conservation and occupancy invariants.
			return String.format("correctness %s: steps=%d markersMismatch=%d solidMismatch=%d densityMAE=%.4f densityMax=%d centerDifference=%.6f pressureDifference=%.6f volume=%.3f",
					label, steps, markerMismatches, solidMismatches, densityMae, densityMaximumDifference,
					centerDifference, pressureDifference, initialVolume);
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

	private static FluidDomain cpuDamBreak(VulkanFlipPipeline.Config config) {
		FluidSolverConfig cpuConfig = new FluidSolverConfig(CELL_SIZE, RHO, GRAVITY, DT, 200, 1.0E-5F, 0.45F, 2.0E-3F, 0.80F);
		FluidDomain domain = new FluidDomain(config.nx(), config.ny(), config.nz(), cpuConfig);
		for (int z = 0; z < config.nz(); z++) for (int y = 0; y < config.ny() * 3 / 4; y++) for (int x = 0; x < config.nx() * 2 / 5; x++) {
			domain.setVolume(x, y, z, 1.0F);
		}
		return domain;
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

	private static int maximumAbsoluteByteDifference(byte[] left, byte[] right) {
		if (left.length != right.length) throw new IllegalArgumentException("array sizes differ");
		int maximum = 0;
		for (int i = 0; i < left.length; i++) {
			maximum = Math.max(maximum, Math.abs(Byte.toUnsignedInt(left[i]) - Byte.toUnsignedInt(right[i])));
		}
		return maximum;
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

	private record Timing(int steps, double gpuAverageMillis, double gpuMinimumMillis, double gpuMaximumMillis,
	                      double throughputWallAverageMillis, double schedulingWallAverageMillis,
	                      double schedulingWallMinimumMillis, double schedulingWallMaximumMillis) {
		String format() {
			return String.format("steps=%d GPU avg=%.3fms min=%.3fms max=%.3fms throughput wall avg=%.3fms scheduling wall avg=%.3fms min=%.3fms max=%.3fms",
					steps, gpuAverageMillis, gpuMinimumMillis, gpuMaximumMillis,
					throughputWallAverageMillis, schedulingWallAverageMillis, schedulingWallMinimumMillis, schedulingWallMaximumMillis);
		}
	}
	private record PressureResult(VulkanFlipPipeline.PressureSolver solver, int iterations, double gpuMilliseconds,
	                              double wallMilliseconds,
	                              VulkanFlipPipeline.Diagnostics diagnostics) {
		String format() {
			return String.format("%s/%d GPU %.3fms scheduling wall %.3fms step6 div %.6f->%.6f", solver.label(), iterations,
					gpuMilliseconds, wallMilliseconds,
					diagnostics.preProjectionMaximumDivergence(), diagnostics.maximumDivergence());
		}
	}
	private record CurvePoint(int iterations, double milliseconds, float maximumDivergence) {
		String format() { return String.format("%d:%.6f/%.3fms", iterations, maximumDivergence, milliseconds); }
	}
}

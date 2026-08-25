package cc.ecisr.mmm.gpuprobe;

import cc.ecisr.mmm.fluid.FlipFluidDomain;

import java.util.Locale;

/** Long-run marker-distribution gate for the generated M1 dam-break domain. */
public final class GpuLongRunSurfaceValidation {
	private static final int NX = 24, NY = 16, NZ = 24;
	private static final int STEPS = 1_000;
	private static final float CELL_SIZE = 0.5F;
	private static final float DT = 1.0F / 20.0F;
	private static final float GRAVITY = 9.81F;
	private static final float RHO = 1_000.0F;

	private GpuLongRunSurfaceValidation() { }

	public static void main(String[] args) {
		Locale.setDefault(Locale.ROOT);
		int device = args.length == 0 ? 0 : Integer.parseInt(args[0]);
		System.out.println(run(device));
	}

	static String run(int device) {
		VulkanFlipPipeline.Config config = new VulkanFlipPipeline.Config(
				NX, NY, NZ, CELL_SIZE, DT, GRAVITY, RHO, 96, NX * NY * NZ * 8);
		FlipFluidDomain cpu = GpuProbeService.createCpuDamBreak(null);
		SurfaceStats gpuStats;
		int gpuPhysicalSubsteps;
		try (VulkanFlipPipeline gpu = new VulkanFlipPipeline(config, device,
				VulkanFlipPipeline.MemoryMode.DEVICE_LOCAL_STAGING,
				VulkanFlipPipeline.PressureSolver.JACOBI)) {
			gpu.initializeDamBreak();
			float expectedVolume = gpu.totalParticleVolume();
			for (int step = 0; step < STEPS; step++) gpu.step();
			VulkanFlipPipeline.Diagnostics diagnostics = gpu.diagnostics();
			require(close(diagnostics.particleVolume(), expectedVolume, 1.0E-3F),
					"long-run GPU particle-volume ledger changed");
			gpuStats = SurfaceStats.of(gpu.snapshot(), NX, NY, NZ);
			gpuPhysicalSubsteps = gpu.physicalSubstepsPerStep();
		}

		float cpuVolume = cpu.totalVolume();
		for (int step = 0; step < STEPS; step++) cpu.step();
		require(close(cpu.totalVolume(), cpuVolume, 1.0E-4F), "long-run CPU particle-volume ledger changed");
		SurfaceStats cpuStats = SurfaceStats.of(
				new VulkanFlipPipeline.SnapshotData(cpu.copyQuantizedVolume(), cpu.copyMarkerMask(), cpu.copySolidMask()),
				NX, NY, NZ);
		String detail = String.format(Locale.ROOT,
				"GPU substeps=%d markerY=%d cells=%d edge=%.3f/%.3f interiorMean=%.3f; CPU markerY=%d cells=%d edge=%.3f/%.3f interiorMean=%.3f",
				gpuPhysicalSubsteps, gpuStats.highestMarkerY(), gpuStats.markerCells(), gpuStats.edgeMinimumDensity(),
				gpuStats.edgeMeanDensity(), gpuStats.interiorMeanDensity(), cpuStats.highestMarkerY(),
				cpuStats.markerCells(), cpuStats.edgeMinimumDensity(), cpuStats.edgeMeanDensity(),
				cpuStats.interiorMeanDensity());

		require(gpuStats.highestMarkerY() >= cpuStats.highestMarkerY(),
				"GPU markers collapsed below CPU reference: " + detail);
		require(gpuStats.markerCells() >= Math.round(cpuStats.markerCells() * 0.98F),
				"GPU marker occupancy collapsed below CPU reference: " + detail);
		require(gpuStats.edgeMeanDensity() >= cpuStats.edgeMeanDensity() * 0.80F,
				"GPU visible edge depth collapsed below CPU reference: " + detail);
		require(gpuStats.edgeMinimumDensity() >= cpuStats.edgeMinimumDensity() * 0.75F,
				"GPU retained a persistent edge density pit: " + detail);
		require(gpuStats.edgeMeanDensity() >= gpuStats.interiorMeanDensity() * 0.85F,
				"GPU edge/interior density ratio indicates a boundary depression: " + detail);

		return "correctness long-run surface: steps=" + STEPS + " | " + detail;
	}

	private static boolean close(float first, float second, float tolerance) {
		return Math.abs(first - second) <= tolerance;
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}

	private record SurfaceStats(
			int highestMarkerY,
			int markerCells,
			float edgeMinimumDensity,
			float edgeMeanDensity,
			float interiorMeanDensity
	) {
		private static SurfaceStats of(VulkanFlipPipeline.SnapshotData snapshot, int nx, int ny, int nz) {
			int highestMarkerY = -1, markerCells = 0, edgeColumns = 0, interiorColumns = 0;
			float edgeMinimum = Float.POSITIVE_INFINITY, edgeTotal = 0.0F, interiorTotal = 0.0F;
			for (int z = 0; z < nz; z++) for (int x = 0; x < nx; x++) {
				float columnDensity = 0.0F;
				for (int y = 0; y < ny; y++) {
					int index = x + nx * (y + ny * z);
					columnDensity += Byte.toUnsignedInt(snapshot.density()[index]) / 255.0F;
					if (snapshot.markers()[index] != 0) {
						markerCells++;
						highestMarkerY = Math.max(highestMarkerY, y);
					}
				}
				boolean edge = x == 0 || x + 1 == nx || z == 0 || z + 1 == nz;
				if (edge) {
					edgeMinimum = Math.min(edgeMinimum, columnDensity);
					edgeTotal += columnDensity;
					edgeColumns++;
				} else {
					interiorTotal += columnDensity;
					interiorColumns++;
				}
			}
			return new SurfaceStats(highestMarkerY, markerCells, edgeMinimum, edgeTotal / edgeColumns,
					interiorTotal / interiorColumns);
		}
	}
}

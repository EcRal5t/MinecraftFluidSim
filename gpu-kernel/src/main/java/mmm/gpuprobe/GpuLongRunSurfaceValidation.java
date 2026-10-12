package mmm.gpuprobe;

import mmm.fluid.FluidDomain;

import java.math.BigDecimal;
import java.util.Locale;

/** Long-run conservation, energy-stability and published-surface gate for the generated dam-break domain. */
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
		int steps = args.length < 2 ? STEPS : Integer.parseInt(args[1]);
		System.out.println(run(device, steps));
	}

	static String run(int device) {
		return run(device, STEPS);
	}

	static String run(int device, int steps) {
		if (steps < 1) throw new IllegalArgumentException("Long-run step count must be positive");
		VulkanFlipPipeline.Config config = new VulkanFlipPipeline.Config(
				NX, NY, NZ, CELL_SIZE, DT, GRAVITY, RHO, 96, NX * NY * NZ * 8);
		FluidDomain cpu = GpuProbeScenario.createCpuDamBreak(null);
		SurfaceStats gpuStats;
		ExactSurfaceStats gpuExactStats;
		MechanicalStats initialGpuEnergy;
		MechanicalStats finalGpuEnergy;
		double finalGpuVolume;
		int gpuPhysicalSubsteps;
		int gpuConservationRefusals;
		double expectedVolume;
		BigDecimal expectedVolumeLedger;
		BigDecimal finalGpuVolumeLedger;
		try (VulkanFlipPipeline gpu = new VulkanFlipPipeline(config, device,
				VulkanFlipPipeline.MemoryMode.DEVICE_LOCAL_STAGING,
				VulkanFlipPipeline.PressureSolver.JACOBI)) {
			gpu.initializeDamBreak();
			VulkanFlipPipeline.ActiveParticles initialParticles = gpu.copyActiveParticles();
			expectedVolume = exactTotalVolume(initialParticles.positionVolume());
			expectedVolumeLedger = exactVolumeLedger(initialParticles.positionVolume());
			initialGpuEnergy = MechanicalStats.of(
					initialParticles.positionVolume(), initialParticles.velocities(), GRAVITY);
			for (int step = 0; step < steps; step++) gpu.step();
			VulkanFlipPipeline.Diagnostics diagnostics = gpu.diagnostics();
			require(close(diagnostics.particleVolume(), (float) expectedVolume, 1.0E-3F),
					"long-run GPU particle-volume ledger changed: resident=" + diagnostics.particleVolume()
							+ ", authoritative=" + expectedVolume);
			gpuConservationRefusals = diagnostics.reseedConservationSkipped();
			gpuStats = SurfaceStats.of(gpu.snapshot(), NX, NY, NZ);
			VulkanFlipPipeline.ActiveParticles finalParticles = gpu.copyActiveParticles();
			gpuExactStats = ExactSurfaceStats.of(finalParticles.positionVolume(), NX, NY, NZ, CELL_SIZE);
			finalGpuVolume = gpuExactStats.totalVolume();
			finalGpuVolumeLedger = exactVolumeLedger(finalParticles.positionVolume());
			finalGpuEnergy = MechanicalStats.of(
					finalParticles.positionVolume(), finalParticles.velocities(), GRAVITY);
			gpuPhysicalSubsteps = gpu.physicalSubstepsPerStep();
		}

		mmm.fluid.FlipParticleState initialCpuState = cpu.exportParticleState();
		BigDecimal expectedCpuVolumeLedger = exactVolumeLedger(initialCpuState.copyPositionVolume());
		MechanicalStats initialCpuEnergy = MechanicalStats.of(
				initialCpuState.copyPositionVolume(), initialCpuState.copyParticleVelocities(), GRAVITY);
		for (int step = 0; step < steps; step++) {
			cpu = GpuProbeScenario.evaluateCpuStep(cpu, step).claimPreparedCpuDomain();
		}
		mmm.fluid.FlipParticleState finalCpuState = cpu.exportParticleState();
		ExactSurfaceStats cpuExactStats = ExactSurfaceStats.of(
				finalCpuState.copyPositionVolume(), NX, NY, NZ, CELL_SIZE);
		BigDecimal finalCpuVolumeLedger = exactVolumeLedger(finalCpuState.copyPositionVolume());
		MechanicalStats finalCpuEnergy = MechanicalStats.of(
				finalCpuState.copyPositionVolume(), finalCpuState.copyParticleVelocities(), GRAVITY);
		SurfaceStats cpuStats = SurfaceStats.of(
				new VulkanFlipPipeline.SnapshotData(cpu.copyQuantizedVolume(), cpu.copyMarkerMask(), cpu.copyBinarySolidMask()),
				NX, NY, NZ);
		String detail = String.format(Locale.ROOT,
				"GPU substeps=%d mergeRefusals=%d markerY=%d cells=%d quantizedVolume=%.3f energy=%s->%s exact=%s; CPU markerY=%d cells=%d quantizedVolume=%.3f energy=%s->%s exact=%s",
				gpuPhysicalSubsteps, gpuConservationRefusals, gpuStats.highestMarkerY(), gpuStats.markerCells(), gpuStats.representedVolume(),
				initialGpuEnergy, finalGpuEnergy, gpuExactStats, cpuStats.highestMarkerY(), cpuStats.markerCells(),
				cpuStats.representedVolume(), initialCpuEnergy, finalCpuEnergy, cpuExactStats);

		require(finalGpuVolumeLedger.compareTo(expectedVolumeLedger) == 0,
				"GPU exact marker-volume ledger changed: " + expectedVolume + " -> " + finalGpuVolume + "; " + detail);
		require(finalCpuVolumeLedger.compareTo(expectedCpuVolumeLedger) == 0,
				"CPU exact marker-volume ledger changed: " + expectedCpuVolumeLedger + " -> "
						+ cpuExactStats.totalVolume() + "; " + detail);
		require(gpuExactStats.insideDomain(), "GPU marker left the simulated domain: " + detail);
		require(cpuExactStats.insideDomain(), "CPU marker left the simulated domain: " + detail);
		require(finalGpuEnergy.total() <= initialGpuEnergy.total(),
				"GPU gravity/projection/transfer pipeline created mechanical energy: " + detail);
		require(finalCpuEnergy.total() <= initialCpuEnergy.total(),
				"CPU gravity/projection/transfer pipeline created mechanical energy: " + detail);
		require(gpuStats.highestMarkerY() == gpuExactStats.highestMarkerY()
				&& gpuStats.markerCells() == gpuExactStats.occupiedCells(),
				"GPU snapshot no longer represents the post-advection exact marker footprint: " + detail);
		require(cpuStats.highestMarkerY() == cpuExactStats.highestMarkerY()
				&& cpuStats.markerCells() == cpuExactStats.occupiedCells(),
				"CPU snapshot no longer represents the post-advection exact marker footprint: " + detail);

		return "correctness long-run surface: steps=" + steps + " | " + detail;
	}

	private static boolean close(float first, float second, float tolerance) {
		return Math.abs(first - second) <= tolerance;
	}

	private static double exactTotalVolume(float[] positionVolume) {
		double total = 0.0;
		for (int index = 3; index < positionVolume.length; index += 4) total += positionVolume[index];
		return total;
	}

	private static BigDecimal exactVolumeLedger(float[] positionVolume) {
		BigDecimal total = BigDecimal.ZERO;
		for (int index = 3; index < positionVolume.length; index += 4) {
			total = total.add(new BigDecimal((double) positionVolume[index]));
		}
		return total;
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}

	private record SurfaceStats(
			int highestMarkerY,
			int markerCells,
			float representedVolume,
			float edgeMinimumDensity,
			float edgeMeanDensity,
			float interiorMeanDensity
	) {
		private static SurfaceStats of(VulkanFlipPipeline.SnapshotData snapshot, int nx, int ny, int nz) {
			int highestMarkerY = -1, markerCells = 0, edgeColumns = 0, interiorColumns = 0;
			float representedVolume = 0.0F;
			float edgeMinimum = Float.POSITIVE_INFINITY, edgeTotal = 0.0F, interiorTotal = 0.0F;
			for (int z = 0; z < nz; z++) for (int x = 0; x < nx; x++) {
				float columnDensity = 0.0F;
				for (int y = 0; y < ny; y++) {
					int index = x + nx * (y + ny * z);
					float density = Byte.toUnsignedInt(snapshot.density()[index]) / 255.0F;
					columnDensity += density;
					representedVolume += density;
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
			return new SurfaceStats(highestMarkerY, markerCells, representedVolume,
					edgeMinimum, edgeTotal / edgeColumns,
					interiorTotal / interiorColumns);
		}
	}

	private record MechanicalStats(float potential, float kinetic, float total, float maximumSpeed) {
		private static MechanicalStats of(float[] particles, float[] velocities, float gravity) {
			double volume = 0.0, potential = 0.0, kinetic = 0.0;
			float maximumSpeed = 0.0F;
			for (int particle = 0; particle < particles.length / 4; particle++) {
				int positionBase = particle * 4, velocityBase = particle * 3;
				float amount = particles[positionBase + 3];
				double speedSquared = velocities[velocityBase] * (double) velocities[velocityBase]
						+ velocities[velocityBase + 1] * (double) velocities[velocityBase + 1]
						+ velocities[velocityBase + 2] * (double) velocities[velocityBase + 2];
				volume += amount;
				potential += amount * gravity * particles[positionBase + 1];
				kinetic += 0.5 * amount * speedSquared;
				maximumSpeed = Math.max(maximumSpeed, (float) Math.sqrt(speedSquared));
			}
			if (!(volume > 0.0) || !Double.isFinite(potential) || !Double.isFinite(kinetic)) {
				throw new AssertionError("non-finite long-run mechanical diagnostic");
			}
			float specificPotential = (float) (potential / volume);
			float specificKinetic = (float) (kinetic / volume);
			return new MechanicalStats(specificPotential, specificKinetic,
					specificPotential + specificKinetic, maximumSpeed);
		}
	}

	private record ExactSurfaceStats(
			int occupiedCells, int highestMarkerY, double totalVolume, float centerOfMassY, boolean insideDomain,
			float maximumCellVolume, float excessCellVolume, float topLayerVolume, float volumeAboveTopLayer
	) {
		private static ExactSurfaceStats of(float[] particles, int nx, int ny, int nz, float cellSize) {
			float[] cellVolumes = new float[nx * ny * nz];
			double totalVolume = 0.0, weightedY = 0.0;
			boolean insideDomain = true;
			for (int particle = 0; particle < particles.length / 4; particle++) {
				int base = particle * 4;
				insideDomain &= particles[base] >= 0.0F && particles[base] < nx * cellSize
						&& particles[base + 1] >= 0.0F && particles[base + 1] < ny * cellSize
						&& particles[base + 2] >= 0.0F && particles[base + 2] < nz * cellSize;
				int x = Math.clamp((int) Math.floor(particles[base] / cellSize), 0, nx - 1);
				int y = Math.clamp((int) Math.floor(particles[base + 1] / cellSize), 0, ny - 1);
				int z = Math.clamp((int) Math.floor(particles[base + 2] / cellSize), 0, nz - 1);
				float volume = particles[base + 3];
				cellVolumes[x + nx * (y + ny * z)] += volume;
				totalVolume += volume;
				weightedY += particles[base + 1] / cellSize * volume;
			}
			int topLayer = (int) Math.floor(totalVolume / (nx * nz));
			int occupied = 0, highest = -1;
			float maximum = 0.0F, excess = 0.0F, top = 0.0F, above = 0.0F;
			for (int z = 0; z < nz; z++) for (int y = 0; y < ny; y++) for (int x = 0; x < nx; x++) {
				float volume = cellVolumes[x + nx * (y + ny * z)];
				if (volume > 0.0F) { occupied++; highest = Math.max(highest, y); }
				maximum = Math.max(maximum, volume);
				excess += Math.max(0.0F, volume - 1.0F);
				if (y == topLayer) top += volume;
				else if (y > topLayer) above += volume;
			}
			return new ExactSurfaceStats(occupied, highest, totalVolume,
					(float) (weightedY / totalVolume), insideDomain, maximum, excess, top, above);
		}
	}
}

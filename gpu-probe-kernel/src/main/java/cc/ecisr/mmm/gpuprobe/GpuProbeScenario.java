package cc.ecisr.mmm.gpuprobe;

import cc.ecisr.mmm.fluid.FluidDomain;
import cc.ecisr.mmm.fluid.FluidSolverConfig;

import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

/** Deterministic generated probe domain shared by the headless kernel and optional adapter. */
final class GpuProbeScenario {
	static final int NX = 24;
	static final int NY = 16;
	static final int NZ = 24;
	static final float CELL_SIZE = 0.5F;
	static final float TIME_STEP = 1.0F / 20.0F;
	static final float GRAVITY = 9.81F;
	static final float DENSITY = 1_000.0F;
	static final int PRESSURE_ITERATIONS = 96;
	static final float PRESSURE_TOLERANCE = 1.0E-5F;
	private static final int CHECKPOINT_INTERVAL_STEPS = 20;

	private GpuProbeScenario() { }

	static VulkanFlipPipeline.Config gpuConfig(VulkanFlipPipeline.PressureSolver pressureSolver) {
		int iterations = pressureSolver == VulkanFlipPipeline.PressureSolver.MULTIGRID_TWO_LEVEL
				|| pressureSolver == VulkanFlipPipeline.PressureSolver.MULTIGRID_HIERARCHY
				? 2 : PRESSURE_ITERATIONS;
		return new VulkanFlipPipeline.Config(NX, NY, NZ, CELL_SIZE, TIME_STEP, GRAVITY, DENSITY,
				iterations, NX * NY * NZ * 8);
	}

	static FluidDomain createCpuDamBreak(byte[] solidCells) {
		FluidDomain domain = new FluidDomain(NX, NY, NZ, cpuConfig());
		if (solidCells != null) {
			if (solidCells.length != NX * NY * NZ) {
				throw new IllegalArgumentException("solid mask size does not match CPU grid");
			}
			for (int z = 0; z < NZ; z++) for (int y = 0; y < NY; y++) for (int x = 0; x < NX; x++) {
				if (solidCells[x + NX * (y + NY * z)] != 0) domain.setSolid(x, y, z, true);
			}
		}
		for (int z = 0; z < NZ; z++) for (int y = 0; y < NY * 3 / 4; y++) {
			for (int x = 0; x < NX * 2 / 5; x++) {
				if (!domain.isSolid(x, y, z)) domain.setVolume(x, y, z, 1.0F);
			}
		}
		return domain;
	}

	static FluidSolverConfig cpuConfig() {
		return new FluidSolverConfig(CELL_SIZE, DENSITY, GRAVITY, TIME_STEP,
				200, PRESSURE_TOLERANCE, 0.45F, 2.0E-3F, 0.80F);
	}

	static boolean checkpointDue(int completedGeneration, GpuProbeCheckpoint current) {
		return current != null && completedGeneration - current.generation() >= CHECKPOINT_INTERVAL_STEPS;
	}

	static int runManualLoop(int count, BooleanSupplier step, IntSupplier hardOverrunStreak) {
		int completed = 0;
		for (int i = 0; i < count; i++) {
			if (!step.getAsBoolean()) break;
			completed++;
			if (hardOverrunStreak.getAsInt() >= 3) break;
		}
		return completed;
	}
}

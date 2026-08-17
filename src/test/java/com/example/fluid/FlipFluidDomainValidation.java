package com.example.fluid;

/** Deterministic checks for the particle/grid solver without Minecraft. */
public final class FlipFluidDomainValidation {
	private FlipFluidDomainValidation() { }

	public static void main(String[] args) {
		massAndDamBreak();
		wallBoundaryReconstruction();
		waterMovesAlongWallsIntoCorner();
		minecraftSizedBenchmark();
		System.out.println("FlipFluidDomain validation passed");
	}

	private static void massAndDamBreak() {
		FlipFluidDomain domain = new FlipFluidDomain(16, 10, 10, config());
		for (int z = 0; z < domain.sizeZ(); z++) {
			for (int y = 0; y < 7; y++) {
				for (int x = 0; x < 5; x++) domain.setVolume(x, y, z, 1.0F);
			}
		}
		float initialVolume = domain.totalVolume();
		float initialCenter = domain.centerOfMassX();
		for (int i = 0; i < 80; i++) {
			FlipFluidDomain.StepStats stats = domain.step();
			check(stats.relativeVolumeError() < 1.0E-6F, "particle volume changed during a step");
		}
		check(Math.abs(domain.totalVolume() - initialVolume) < 1.0E-4F, "dam-break lost volume");
		check(domain.centerOfMassX() > initialCenter + 0.5F, "dam-break did not spread");
	}

	private static void wallBoundaryReconstruction() {
		FlipFluidDomain domain = new FlipFluidDomain(8, 6, 8, config());
		for (int y = 0; y < domain.sizeY(); y++) {
			for (int z = 0; z < domain.sizeZ(); z++) domain.setSolid(0, y, z, true);
			for (int x = 0; x < domain.sizeX(); x++) domain.setSolid(x, y, 0, true);
		}
		for (int z = 1; z <= 4; z++) for (int y = 0; y <= 2; y++) for (int x = 1; x <= 4; x++) {
			domain.setVolume(x, y, z, 1.0F);
		}
		float particles = domain.totalVolume();
		float reconstructed = reconstructedVolume(domain);
		check(Math.abs(reconstructed - particles) < 1.0E-3F,
				"wall boundary lost reconstructed volume: particles=" + particles + ", grid=" + reconstructed);
		for (int i = 0; i < 40; i++) domain.step();
		check(Math.abs(reconstructedVolume(domain) - particles) < 1.0E-3F, "wall reconstruction drifted after motion");
	}

	private static void waterMovesAlongWallsIntoCorner() {
		FlipFluidDomain domain = new FlipFluidDomain(8, 6, 8, config());
		for (int z = 0; z < 8; z++) for (int x = 0; x < 8; x++) domain.setSolid(x, 0, z, true);
		for (int z = 0; z < 8; z++) for (int y = 0; y < 6; y++) domain.setSolid(0, y, z, true);
		for (int x = 0; x < 8; x++) for (int y = 0; y < 6; y++) domain.setSolid(x, y, 0, true);
		for (int z = 4; z <= 6; z++) for (int y = 1; y <= 4; y++) for (int x = 4; x <= 6; x++) domain.setVolume(x, y, z, 1.0F);
		for (int step = 0; step < 160; step++) domain.step();
		float cornerColumn = 0.0F;
		for (int y = 1; y < domain.sizeY(); y++) cornerColumn += domain.volume(1, y, 1);
		check(cornerColumn > 0.20F, "particles failed to move tangentially into a wall corner: " + cornerColumn);
	}

	private static void minecraftSizedBenchmark() {
		FlipFluidDomain domain = new FlipFluidDomain(24, 16, 24, config());
		for (int z = 0; z < 24; z++) for (int y = 0; y < 12; y++) for (int x = 0; x < 9; x++) {
			domain.setVolume(x, y, z, 1.0F);
		}
		long start = System.nanoTime();
		FlipFluidDomain.StepStats stats = null;
		for (int i = 0; i < 8; i++) stats = domain.step();
		double milliseconds = (System.nanoTime() - start) / 1_000_000.0 / 8.0;
		System.out.printf("FLIP benchmark: %.2f ms/step, particles %d, PCG %d, residual %.4g%n",
				milliseconds, domain.particleCount(), stats.pressureIterations(), stats.pressureResidual());
	}

	private static float reconstructedVolume(FlipFluidDomain domain) {
		float result = 0.0F;
		for (int z = 0; z < domain.sizeZ(); z++) for (int y = 0; y < domain.sizeY(); y++) for (int x = 0; x < domain.sizeX(); x++) {
			result += domain.volume(x, y, z);
		}
		return result;
	}

	private static FluidSolverConfig config() {
		return new FluidSolverConfig(0.5F, 1_000.0F, 9.81F, 1.0F / 20.0F,
				120, 1.0E-4F, 0.40F, 2.0E-3F, 0.80F);
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}

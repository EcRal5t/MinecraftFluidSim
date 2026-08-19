package com.example.fluid;

/** Deterministic checks for the particle/grid solver without Minecraft. */
public final class FlipFluidDomainValidation {
	private FlipFluidDomainValidation() { }

	public static void main(String[] args) {
		massAndDamBreak();
		wallBoundaryReconstruction();
		waterMovesAlongWallsIntoCorner();
		solidDisplacementPreservesExactParticleVolume();
		markerBudgetIsHardAndConservative();
		particleCheckpointRestoreIsTransactional();
		hydrostaticSleepCommitPreservesParticleLedger();
		minecraftSizedBenchmark();
		System.out.println("FlipFluidDomain validation passed");
	}

	private static void markerBudgetIsHardAndConservative() {
		FlipFluidDomain domain = new FlipFluidDomain(4, 4, 4, config(), 10);
		domain.setVolume(0, 0, 0, 1.0F);
		check(domain.particleCount() == 8 && Math.abs(domain.totalVolume() - 1.0F) < 1.0E-6F,
				"full cell did not use the expected exact marker ledger");
		FlipFluidDomain.VolumeAddition partial = domain.addVolumeNearBudgeted(1, 0, 0, 1.0F, 2);
		check(Math.abs(partial.addedVolume() - 0.25F) < 1.0E-6F && partial.markerBudgetLimited(),
				"remaining marker budget was not conservatively exposed");
		check(domain.particleCount() == 10 && Math.abs(domain.totalVolume() - 1.25F) < 1.0E-6F,
				"budgeted addition manufactured or lost exact particle volume");

		float preserved = domain.totalVolume();
		boolean rejected = false;
		try {
			domain.setVolume(3, 3, 3, 1.0F);
		} catch (FlipFluidDomain.ParticleBudgetExceededException expected) {
			rejected = expected.requiredMarkers() == 8 && expected.availableMarkers() == 0;
		}
		check(rejected, "setVolume did not report the exhausted hard marker budget");
		check(domain.particleCount() == 10 && Math.abs(domain.totalVolume() - preserved) < 1.0E-6F,
				"rejected setVolume partially mutated the exact marker ledger");

		FlipFluidDomain replacement = new FlipFluidDomain(4, 4, 4, config(), 8);
		replacement.setVolume(0, 0, 0, 0.5F);
		replacement.setVolume(1, 0, 0, 0.5F);
		rejected = false;
		try {
			replacement.setVolume(0, 0, 0, 1.0F);
		} catch (FlipFluidDomain.ParticleBudgetExceededException expected) {
			rejected = true;
		}
		check(rejected && replacement.particleCount() == 8 && Math.abs(replacement.totalVolume() - 1.0F) < 1.0E-6F,
				"failed in-place replacement deleted the cell's old marker volume");

		float[] oversizedPositionVolume = new float[44];
		for (int marker = 0; marker < 11; marker++) {
			oversizedPositionVolume[marker * 4] = 0.25F;
			oversizedPositionVolume[marker * 4 + 1] = 0.25F;
			oversizedPositionVolume[marker * 4 + 2] = 0.25F;
			oversizedPositionVolume[marker * 4 + 3] = 0.125F;
		}
		FlipParticleState oversized = new FlipParticleState(
				4, 4, 4, 0.5F, 11, 11.0F / 8.0F,
				oversizedPositionVolume, new float[33], new byte[64]
		);
		rejected = false;
		try {
			domain.restoreParticleState(oversized);
		} catch (IllegalArgumentException expected) {
			rejected = true;
		}
		check(rejected && domain.particleCount() == 10 && Math.abs(domain.totalVolume() - preserved) < 1.0E-6F,
				"oversized checkpoint bypassed the marker budget or mutated the domain");
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

	private static void solidDisplacementPreservesExactParticleVolume() {
		FlipFluidDomain domain = new FlipFluidDomain(10, 8, 10, config());
		for (int z = 3; z <= 6; z++) for (int y = 1; y <= 3; y++) for (int x = 3; x <= 6; x++) domain.setVolume(x, y, z, 0.73F);
		float before = domain.totalVolume();
		float displaced = 0.0F;
		for (int z = 4; z <= 5; z++) for (int y = 1; y <= 2; y++) for (int x = 4; x <= 5; x++) displaced += domain.setSolid(x, y, z, true);
		float restored = domain.addVolumeNear(4, 2, 4, displaced, 5);
		check(Math.abs(restored - displaced) < 1.0E-5F, "could not restore displaced particle volume");
		check(Math.abs(domain.totalVolume() - before) < 1.0E-4F,
				"solid displacement changed particle volume: before=" + before + ", after=" + domain.totalVolume());
	}

	private static void particleCheckpointRestoreIsTransactional() {
		FlipFluidDomain domain = new FlipFluidDomain(6, 5, 4, config());
		byte[] solids = new byte[6 * 5 * 4];
		solids[5 + 6 * (4 + 5 * 3)] = 1;
		float[] positions = {
				0.25F, 0.25F, 0.25F, 0.125F,
				1.25F, 0.75F, 0.75F, 0.0625F
		};
		float[] velocities = {0.5F, -0.25F, 0.125F, -0.125F, 0.25F, -0.5F};
		FlipParticleState state = new FlipParticleState(6, 5, 4, 0.5F, 2, 0.1875F,
				positions, velocities, solids);
		domain.restoreParticleState(state);
		check(domain.particleCount() == 2, "checkpoint particle count changed");
		check(Math.abs(domain.totalVolume() - 0.1875F) < 1.0E-6F, "checkpoint particle volume changed");
		check(domain.markerCellCount() == 2, "checkpoint marker occupancy changed");
		check(domain.isSolid(5, 4, 3), "checkpoint solid mask changed");
		float beforeStep = domain.totalVolume();
		domain.step();
		check(Math.abs(domain.totalVolume() - beforeStep) < 1.0E-6F, "restored checkpoint lost volume on its first CPU step");

		float preservedVolume = domain.totalVolume();
		float[] invalidPositions = positions.clone();
		invalidPositions[0] = 2.75F;
		invalidPositions[1] = 2.25F;
		invalidPositions[2] = 1.75F;
		boolean rejected = false;
		try {
			domain.restoreParticleState(new FlipParticleState(6, 5, 4, 0.5F, 2, 0.1875F,
					invalidPositions, velocities, solids));
		}
		catch (IllegalArgumentException expected) { rejected = true; }
		check(rejected, "checkpoint particle inside solid was accepted");
		check(Math.abs(domain.totalVolume() - preservedVolume) < 1.0E-6F,
				"rejected checkpoint partially mutated the particle ledger");
	}

	private static void hydrostaticSleepCommitPreservesParticleLedger() {
		FlipFluidDomain domain = new FlipFluidDomain(6, 5, 4, config());
		domain.restoreParticleState(new FlipParticleState(
				6, 5, 4, 0.5F, 2, 0.1875F,
				new float[] {
						0.25F, 0.25F, 0.25F, 0.125F,
						1.25F, 0.75F, 0.75F, 0.0625F
				},
				new float[] {0.5F, -0.25F, 0.125F, -0.125F, 0.25F, -0.5F},
				new byte[6 * 5 * 4]
		));
		float exactVolume = domain.totalVolume();
		int markerCount = domain.particleCount();
		check(domain.maximumParticleSpeed() > 0.5F, "sleep fixture did not contain marker momentum");
		domain.commitHydrostaticSleep();
		check(domain.maximumParticleSpeed() == 0.0F, "hydrostatic sleep left marker momentum");
		check(domain.particleCount() == markerCount && Math.abs(domain.totalVolume() - exactVolume) < 1.0E-6F,
				"hydrostatic scheduling changed the exact particle ledger");
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

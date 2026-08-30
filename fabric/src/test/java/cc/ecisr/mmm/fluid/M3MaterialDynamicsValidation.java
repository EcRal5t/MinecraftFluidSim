package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.world.MovingBodyFluidCoupling;

import java.util.Arrays;
import java.util.List;

/** Active-core M3 dynamics gates for unequal-area connected vessels and density inversion. */
final class M3MaterialDynamicsValidation {
	private M3MaterialDynamicsValidation() { }

	static Summary validate() {
		ConnectedVesselResult vessel = unequalAreaConnectedVesselBalances();
		LayeringResult layering = densityInversionLayersConservatively();
		movingBodyCommitsOnlyMarkerMomentum();
		return new Summary(vessel.finalHeadDifference(), layering.heavyCenterY(), layering.lightCenterY());
	}

	private static void movingBodyCommitsOnlyMarkerMomentum() {
		FluidSolverConfig config = new FluidSolverConfig(
				0.5F, 1_000.0F, 0.0F, 1.0F / 40.0F,
				200, 1.0E-5F, 0.40F, 2.0E-3F, 0.80F);
		FluidDomain domain = new FluidDomain(3, 3, 3, config, 128);
		domain.setVolume(1, 1, 1, 1.0F);
		FlipParticleState before = domain.exportParticleState();
		double massBefore = domain.totalMass();
		MovingBodyFluidCoupling.Result result = domain.applyMovingBodyCoupling(
				new MovingBodyFluidCoupling.Request(0.75F, 0.75F, 0.75F, 2.0, -0.5, 0.25, 0.025));
		FlipParticleState after = domain.exportParticleState();
		check(result.coupledMarkers() > 0 && result.coupledVolumeCubicMetres() > 0.0
				&& result.impulseXKilogramMetresPerSecond() > 0.0
				&& result.impulseYKilogramMetresPerSecond() < 0.0
				&& result.impulseZKilogramMetresPerSecond() > 0.0,
				"moving body did not commit a three-axis marker impulse");
		check(Arrays.equals(before.copyPositionVolume(), after.copyPositionVolume())
				&& Double.doubleToRawLongBits(massBefore) == Double.doubleToRawLongBits(domain.totalMass()),
				"moving-body coupling changed authoritative volume or mass");
		float[] velocityBefore = before.copyParticleVelocities();
		float[] velocityAfter = after.copyParticleVelocities();
		double momentumX = 0.0, momentumY = 0.0, momentumZ = 0.0;
		double cellVolumeCubicMetres = (double) config.cellSize() * config.cellSize() * config.cellSize();
		float[] positionVolume = after.copyPositionVolume();
		for (int marker = 0; marker < after.particleCount(); marker++) {
			double markerMass = config.density() * positionVolume[marker * 4 + 3];
			momentumX += markerMass * (velocityAfter[marker * 3] - velocityBefore[marker * 3])
					* cellVolumeCubicMetres;
			momentumY += markerMass * (velocityAfter[marker * 3 + 1] - velocityBefore[marker * 3 + 1])
					* cellVolumeCubicMetres;
			momentumZ += markerMass * (velocityAfter[marker * 3 + 2] - velocityBefore[marker * 3 + 2])
					* cellVolumeCubicMetres;
		}
		check(Double.doubleToRawLongBits(momentumX)
				== Double.doubleToRawLongBits(result.impulseXKilogramMetresPerSecond())
				&& Double.doubleToRawLongBits(momentumY)
				== Double.doubleToRawLongBits(result.impulseYKilogramMetresPerSecond())
				&& Double.doubleToRawLongBits(momentumZ)
				== Double.doubleToRawLongBits(result.impulseZKilogramMetresPerSecond()),
				"moving-body impulse report differs from committed marker momentum");
	}

	private static ConnectedVesselResult unequalAreaConnectedVesselBalances() {
		FluidSolverConfig config = config();
		FluidDomain domain = new FluidDomain(9, 9, 5, config, 768);
		fillSolid(domain);
		for (int x = 1; x <= 7; x++) open(domain, x, 1, 1);
		for (int x = 6; x <= 7; x++) open(domain, x, 1, 2);
		for (int y = 2; y <= 7; y++) {
			open(domain, 1, y, 1);
			for (int x = 6; x <= 7; x++) for (int z = 1; z <= 2; z++) open(domain, x, y, z);
		}
		// vent both column tops through the boundary layer so the air above the water is ambient;
		// a sealed pocket would (correctly, since M5) act as a gas spring and hold a level difference
		open(domain, 1, 8, 1);
		for (int x = 6; x <= 7; x++) for (int z = 1; z <= 2; z++) open(domain, x, 8, z);
		for (int x = 1; x <= 7; x++) domain.setVolume(x, 1, 1, 1.0F);
		for (int x = 6; x <= 7; x++) domain.setVolume(x, 1, 2, 1.0F);
		for (int y = 2; y <= 6; y++) domain.setVolume(1, y, 1, 1.0F);
		for (int x = 6; x <= 7; x++) for (int z = 1; z <= 2; z++) domain.setVolume(x, 2, z, 1.0F);

		double initialVolume = domain.totalVolume(), initialMass = domain.totalMass();
		double initialDifference = connectedHeadDifference(domain);
		int steps = 0;
		for (; steps < 1_200; steps++) {
			FluidDomain.StepStats stats = domain.step();
			check(stats.relativeVolumeError() <= 2.0E-6F && stats.relativeMassError() <= 2.0E-12,
					"unequal-area connector changed physical inventories");
			if (steps >= 300 && connectedHeadDifference(domain) < 0.10 && domain.maximumParticleSpeed() < 0.04F) break;
		}
		double finalDifference = connectedHeadDifference(domain);
		check(finalDifference < initialDifference * 0.05,
				"unequal-area connector did not converge to one hydraulic head: initial="
						+ initialDifference + ", final=" + finalDifference
						+ ", maxSpeed=" + domain.maximumParticleSpeed());
		check(Math.abs(domain.totalVolume() - initialVolume) <= 1.0E-4,
				"unequal-area connector changed exact marker volume");
		check(Math.abs(domain.totalMass() - initialMass) <= 1.0E-8,
				"unequal-area connector changed exact marker mass");
		System.out.printf("  active unequal-area connector headDiff %.3f -> %.3f in %d steps, maxSpeed=%.3g%n",
				initialDifference, finalDifference, steps + 1, domain.maximumParticleSpeed());
		return new ConnectedVesselResult(initialDifference, finalDifference, steps + 1);
	}

	private static double connectedHeadDifference(FluidDomain domain) {
		FlipParticleState state = domain.exportParticleState();
		float[] markers = state.copyPositionVolume();
		double leftVolume = 0.0, rightVolume = 0.0;
		for (int particle = 0; particle < state.particleCount(); particle++) {
			int base = particle * 4;
			int x = (int) Math.floor(markers[base] / state.cellSize());
			int z = (int) Math.floor(markers[base + 2] / state.cellSize());
			if (x == 1 && z == 1) leftVolume += markers[base + 3];
			if (x >= 6 && x <= 7 && z >= 1 && z <= 2) rightVolume += markers[base + 3];
		}
		return Math.abs(leftVolume - rightVolume / 4.0);
	}

	private static LayeringResult densityInversionLayersConservatively() {
		FluidMaterialRegistry registry = new FluidMaterialRegistry(List.of(
				new FluidMaterial("mmm:heavy", 1_000.0, "mmm:heavy_phase", 0.0, Math.PI / 2.0),
				new FluidMaterial("mmm:light", 800.0, "mmm:light_phase", 0.0, Math.PI / 2.0)
		));
		int heavy = registry.handle("mmm:heavy"), light = registry.handle("mmm:light");
		FluidSolverConfig config = config();
		FluidDomain domain = new FluidDomain(6, 7, 4, config, 1_024);
		fillSolid(domain);
		for (int y = 1; y <= 5; y++) for (int x = 1; x <= 4; x++) for (int z = 1; z <= 2; z++) {
			open(domain, x, y, z);
		}
		// vent the box top through the boundary layer so the gas pocket above the liquid is ambient
		for (int x = 1; x <= 4; x++) for (int z = 1; z <= 2; z++) open(domain, x, 6, z);
		for (int y = 1; y <= 4; y++) for (int x = 1; x <= 4; x++) for (int z = 1; z <= 2; z++) {
			domain.setVolume(x, y, z, 1.0F);
		}
		seedInterchangeVelocity(domain);
		for (int y = 1; y <= 2; y++) for (int x = 1; x <= 4; x++) for (int z = 1; z <= 2; z++) {
			domain.setMaterialInCell(x, y, z, registry, light);
		}
		for (int y = 3; y <= 4; y++) for (int x = 1; x <= 4; x++) for (int z = 1; z <= 2; z++) {
			domain.setMaterialInCell(x, y, z, registry, heavy);
		}
		FluidDomain.MaterialInventory initialHeavy = domain.materialInventory(heavy);
		FluidDomain.MaterialInventory initialLight = domain.materialInventory(light);
		double maximumMassError = 0.0;
		for (int step = 0; step < 1_200; step++) {
			FluidDomain.StepStats stats = domain.step();
			maximumMassError = Math.max(maximumMassError, stats.relativeMassError());
			check(stats.relativeVolumeError() <= 2.0E-6F,
					"density inversion changed exact marker volume");
		}
		FluidDomain.MaterialInventory finalHeavy = domain.materialInventory(heavy);
		FluidDomain.MaterialInventory finalLight = domain.materialInventory(light);
		check(finalHeavy.centerY() + 0.20 < finalLight.centerY(),
				"density inversion did not settle heavy material below light material: heavy="
						+ finalHeavy.centerY() + ", light=" + finalLight.centerY());
		check(Math.abs(finalHeavy.mass() - initialHeavy.mass()) <= 1.0E-8
				&& Math.abs(finalLight.mass() - initialLight.mass()) <= 1.0E-8,
				"density inversion moved mass across material ledgers");
		check(maximumMassError <= 2.0E-12, "density inversion exceeded mass-ledger tolerance");
		System.out.printf("  active density inversion COM heavy/light %.3f/%.3f -> %.3f/%.3f, maxSpeed=%.3g%n",
				initialHeavy.centerY(), initialLight.centerY(), finalHeavy.centerY(), finalLight.centerY(),
				domain.maximumParticleSpeed());
		return new LayeringResult(finalHeavy.centerY(), finalLight.centerY());
	}

	private static void seedInterchangeVelocity(FluidDomain domain) {
		FlipParticleState initial = domain.exportParticleState();
		float[] positions = initial.copyPositionVolume();
		float[] velocities = initial.copyParticleVelocities();
		for (int particle = 0; particle < initial.particleCount(); particle++) {
			int base = particle * 4;
			boolean left = positions[base] < 3.0F;
			velocities[particle * 3 + 1] = left ? -0.30F : 0.30F;
		}
		domain.restoreParticleState(new FlipParticleState(
				domain.sizeX(), domain.sizeY(), domain.sizeZ(), initial.cellSize(), initial.particleCount(),
				initial.totalVolume(), positions, velocities, initial.copySolidMask()
		));
	}

	private static FluidSolverConfig config() {
		return new FluidSolverConfig(
				1.0F, 1_000.0F, 9.81F, 1.0F / 40.0F,
				200, 1.0E-5F, 0.40F, 2.0E-3F, 0.80F
		);
	}

	private static void fillSolid(FluidDomain domain) {
		for (int z = 0; z < domain.sizeZ(); z++) for (int y = 0; y < domain.sizeY(); y++) {
			for (int x = 0; x < domain.sizeX(); x++) domain.setSolid(x, y, z, true);
		}
	}

	private static void open(FluidDomain domain, int x, int y, int z) {
		domain.setSolid(x, y, z, false);
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}

	record Summary(double connectedHeadDifference, double heavyCenterY, double lightCenterY) { }
	private record ConnectedVesselResult(double initialHeadDifference, double finalHeadDifference, int steps) { }
	private record LayeringResult(double heavyCenterY, double lightCenterY) { }
}

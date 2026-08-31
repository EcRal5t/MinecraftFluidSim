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
		return new Summary(vessel.minimumHeadDifference(), layering.heavyCenterY(), layering.lightCenterY());
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
		double minimumDifference = initialDifference;
		int minimumStep = 0;
		for (int step = 0; step < 1_200; step++) {
			FluidDomain.StepStats stats = domain.step();
			check(stats.relativeVolumeError() <= 2.0E-6F && stats.relativeMassError() <= 2.0E-12,
					"unequal-area connector changed physical inventories");
			double difference = connectedHeadDifference(domain);
			if (difference < minimumDifference) {
				minimumDifference = difference;
				minimumStep = step + 1;
			}
		}
		double finalDifference = connectedHeadDifference(domain);
		check(minimumDifference < initialDifference * 0.025,
				"inviscid unequal-area connector never crossed its equal-head equilibrium: initial="
						+ initialDifference + ", minimum=" + minimumDifference + ", final=" + finalDifference
						+ ", maxSpeed=" + domain.maximumParticleSpeed());
		check(Math.abs(domain.totalVolume() - initialVolume) <= 1.0E-4,
				"unequal-area connector changed exact marker volume");
		check(Math.abs(domain.totalMass() - initialMass) <= 1.0E-8,
				"unequal-area connector changed exact marker mass");
		System.out.printf("  active inviscid unequal-area connector headDiff %.3f -> min %.3f at %d -> %.3f, maxSpeed=%.3g%n",
				initialDifference, minimumDifference, minimumStep, finalDifference, domain.maximumParticleSpeed());
		return new ConnectedVesselResult(initialDifference, minimumDifference, finalDifference, minimumStep);
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
		for (int x = 1; x <= 4; x++) for (int z = 1; z <= 2; z++) {
			domain.setMaterialInCell(x, 1, z, registry, light);
			domain.setMaterialInCell(x, 4, z, registry, heavy);
			// Equal-volume step perturbation of the otherwise flat heavy-over-light interface.
			// The pressure/gravity coupling, rather than a prescribed marker velocity, must amplify it.
			domain.setMaterialInCell(x, 2, z, registry, x <= 2 ? heavy : light);
			domain.setMaterialInCell(x, 3, z, registry, x <= 2 ? light : heavy);
		}
		FluidDomain.MaterialInventory initialHeavy = domain.materialInventory(heavy);
		FluidDomain.MaterialInventory initialLight = domain.materialInventory(light);
		double maximumMassError = 0.0;
		double minimumSeparation = initialHeavy.centerY() - initialLight.centerY();
		float maximumSpeedObserved = domain.maximumParticleSpeed();
		int inversionStep = -1;
		for (int step = 0; step < 4_800; step++) {
			FluidDomain.StepStats stats = domain.step();
			maximumMassError = Math.max(maximumMassError, stats.relativeMassError());
			check(stats.relativeVolumeError() <= 2.0E-6F,
					"density inversion changed exact marker volume");
			FluidDomain.MaterialInventory heavyNow = domain.materialInventory(heavy);
			FluidDomain.MaterialInventory lightNow = domain.materialInventory(light);
			double separation = heavyNow.centerY() - lightNow.centerY();
			maximumSpeedObserved = Math.max(maximumSpeedObserved, domain.maximumParticleSpeed());
			if (separation < minimumSeparation) {
				minimumSeparation = separation;
				inversionStep = step + 1;
			}
			if (minimumSeparation < -0.20) break;
		}
		FluidDomain.MaterialInventory finalHeavy = domain.materialInventory(heavy);
		FluidDomain.MaterialInventory finalLight = domain.materialInventory(light);
		check(minimumSeparation < -0.20,
				"perturbed heavy-over-light interface never underwent Rayleigh-Taylor inversion: minimum separation="
						+ minimumSeparation + ", final heavy/light=" + finalHeavy.centerY() + "/" + finalLight.centerY()
						+ ", maximum speed=" + maximumSpeedObserved);
		check(Math.abs(finalHeavy.mass() - initialHeavy.mass()) <= 1.0E-8
				&& Math.abs(finalLight.mass() - initialLight.mass()) <= 1.0E-8,
				"density inversion moved mass across material ledgers");
		check(maximumMassError <= 2.0E-12, "density inversion exceeded mass-ledger tolerance");
		System.out.printf("  active inviscid density inversion COM heavy/light %.3f/%.3f -> %.3f/%.3f, minSeparation=%.3f at %d, maxSpeed=%.3g%n",
				initialHeavy.centerY(), initialLight.centerY(), finalHeavy.centerY(), finalLight.centerY(),
				minimumSeparation, inversionStep, domain.maximumParticleSpeed());
		return new LayeringResult(finalHeavy.centerY(), finalLight.centerY());
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
	private record ConnectedVesselResult(
			double initialHeadDifference, double minimumHeadDifference, double finalHeadDifference, int minimumStep
	) { }
	private record LayeringResult(double heavyCenterY, double lightCenterY) { }
}

package cc.ecisr.mmm.fluid;

/** Deterministic M2.5 gates for PLIC volume reconstruction and ghost-fluid pressure weights. */
public final class FlipFluidFreeSurfaceValidation {
	private FlipFluidFreeSurfaceValidation() { }

	public static void main(String[] args) {
		boolean fast = java.util.Arrays.asList(args).contains("--fast");
		planeVolumeInversionIsAccurate();
		axisAlignedInterfaceDistanceUsesExactCellLedger();
		pressurePhaseUsesConservativeFootprintVolume();
		bodyOverlapResolvesContinuousSubmergence();
		pureWaterMarkerMassLedgerTracksVolume();
		volumeDriftCorrectionPreservesLegitimateSurfaceDeficits();
		fractionalHydrostaticColumnsBalanceGravity();
		pressureNonConvergenceRejectsStep();
		thinLayerRegularizationRemainsStable();
		isolatedDropletReceivesUndilutedGravity();
		if (!fast) longRunHydrostaticSurfaceDoesNotSink();
		waterDescendsAStairSlope();
		System.out.println("FlipFluid free-surface validation passed" + (fast ? " (short; 5,000-step soak skipped)" : ""));
	}

	private static void bodyOverlapResolvesContinuousSubmergence() {
		FluidSolverConfig config = FluidSolverConfig.prototypeDefaults();
		FluidDomain domain = new FluidDomain(6, 6, 6, config, 6 * 6 * 6 * 8);
		for (int z = 0; z < 6; z++) for (int x = 0; x < 6; x++) {
			domain.setVolume(x, 0, z, 1.0F);
			domain.setVolume(x, 1, z, 1.0F);
			domain.setVolume(x, 2, z, 1.0F);
		}
		double width = 0.60;
		double height = 1.80;
		double expectedFraction = 0.72;
		double surfaceY = 3.0 * config.cellSize();
		double minimumY = surfaceY - height * expectedFraction;
		FluidDomain.BodyFluidSample sample = domain.sampleAxisAlignedBody(
				1.20, minimumY, 1.20, 1.20 + width, minimumY + height, 1.20 + width);
		double bodyVolume = width * height * width;
		check(Math.abs(sample.displacedVolumeCubicMetres() / bodyVolume - expectedFraction) < 1.0E-6,
				"body overlap quantized continuous neutral submergence: "
						+ sample.displacedVolumeCubicMetres() / bodyVolume);
		check(sample.centerYMetres() >= minimumY && sample.centerYMetres() <= surfaceY,
				"body overlap centroid escaped the wet AABB segment: " + sample.centerYMetres());

		FluidDomain.BodyFluidSample shifted = domain.sampleAxisAlignedBody(
				1.20, minimumY + 0.01, 1.20, 1.20 + width, minimumY + height + 0.01, 1.20 + width);
		check(Math.abs((sample.displacedVolumeCubicMetres() - shifted.displacedVolumeCubicMetres())
				- width * width * 0.01) < 1.0E-6,
				"body overlap did not respond continuously to a sub-sample vertical displacement");
	}

	private static void pureWaterMarkerMassLedgerTracksVolume() {
		FluidSolverConfig config = FluidSolverConfig.prototypeDefaults();
		FluidDomain domain = new FluidDomain(4, 4, 4, config, 128);
		domain.setVolume(1, 1, 1, 1.0F);
		domain.setVolume(2, 1, 1, 0.375F);
		check(Math.abs(domain.totalMass() - (double) config.density() * domain.totalVolume()) <= 1.0E-9,
				"pure-water marker mass did not match the exact volume ledger");
		domain.removeVolumeNear(2, 1, 1, 0.125F, 0);
		check(Math.abs(domain.totalMass() - (double) config.density() * domain.totalVolume()) <= 1.0E-9,
				"partial marker drain did not remove proportional mass");
		domain.step();
		check(Math.abs(domain.totalMass() - (double) config.density() * domain.totalVolume()) <= 1.0E-9,
				"pure-water reseeding did not preserve the mass/volume relation");
	}

	private static void planeVolumeInversionIsAccurate() {
		float[][] normals = {
				{1.0F, 0.0F, 0.0F},
				{0.0F, -1.0F, 0.0F},
				{0.70710677F, 0.70710677F, 0.0F},
				{-0.26726124F, 0.5345225F, 0.8017837F}
		};
		float[] fractions = {0.05F, 0.25F, 0.50F, 0.75F, 0.95F};
		for (float[] normal : normals) for (float fraction : fractions) {
			float offset = FlipFreeSurfaceGeometry.solvePlaneOffset(normal[0], normal[1], normal[2], fraction);
			float reconstructed = FlipFreeSurfaceGeometry.unitCubeLiquidFraction(
					normal[0], normal[1], normal[2], offset
			);
			check(Math.abs(reconstructed - fraction) < 2.0E-6F,
					"PLIC inversion drifted: expected=" + fraction + ", reconstructed=" + reconstructed);
		}
	}

	private static void axisAlignedInterfaceDistanceUsesExactCellLedger() {
		for (float fraction : new float[] {0.25F, 0.60F, 0.75F, 1.0F}) {
			int sizeX = 5, sizeY = 4, sizeZ = 5;
			int cellCount = sizeX * sizeY * sizeZ;
			boolean[] solid = new boolean[cellCount];
			int[] markerCount = new int[cellCount];
			FlipFreeSurfaceGeometry geometry = new FlipFreeSurfaceGeometry(sizeX, sizeY, sizeZ);
			FlipCutCellGeometry cutCells = new FlipCutCellGeometry(sizeX, sizeY, sizeZ);
			geometry.clearExactVolumes();
			for (int z = 0; z < sizeZ; z++) for (int x = 0; x < sizeX; x++) {
				int lower = index(sizeX, sizeY, x, 0, z);
				int surface = index(sizeX, sizeY, x, 1, z);
				markerCount[lower] = 8;
				markerCount[surface] = 1;
				geometry.addExactVolume(lower, 1.0F);
				geometry.addExactVolume(surface, fraction);
			}
			geometry.rebuild(solid, markerCount, cutCells);
			int surface = index(sizeX, sizeY, 2, 1, 2);
			float expected = fraction >= 1.0F - 1.0E-6F
					? 0.5F : Math.max(FlipFreeSurfaceGeometry.MINIMUM_THETA, fraction - 0.5F);
			float actual = geometry.pressureTheta(surface, 0, 1, 0);
			check(Math.abs(actual - expected) < 2.0E-5F,
					"axis-aligned theta did not follow the exact cell fraction: fraction=" + fraction + ", theta=" + actual);
			check(Math.abs(geometry.liquidFraction(surface) - fraction) < 1.0E-7F,
					"geometry did not retain the exact per-cell marker sum");
		}
	}

	private static void pressurePhaseUsesConservativeFootprintVolume() {
		int sizeX = 5, sizeY = 3, sizeZ = 3;
		int cellCount = sizeX * sizeY * sizeZ;
		boolean[] solid = new boolean[cellCount];
		boolean[] fluid = new boolean[cellCount];
		int[] markerCount = new int[cellCount];
		double[] reconstructed = new double[cellCount];
		int bulk = index(sizeX, sizeY, 1, 1, 1);
		int fringe = index(sizeX, sizeY, 2, 1, 1);
		FlipPressurePhaseGeometry pressure = new FlipPressurePhaseGeometry(sizeX, sizeY, sizeZ);
		FlipCutCellGeometry cutCells = new FlipCutCellGeometry(sizeX, sizeY, sizeZ);
		reconstructed[bulk] = 0.875F;
		reconstructed[fringe] = 0.125F;
		pressure.rebuild(solid, markerCount, reconstructed, cutCells, null, fluid);
		check(fluid[bulk] && !fluid[fringe]
				&& Math.abs(pressure.liquidFraction(bulk) - 0.875F) < 1.0E-7F
				&& Math.abs(pressure.liquidFraction(fringe) - 0.125F) < 1.0E-7F,
				"pressure phase did not retain the conservative center-contained footprint");
		reconstructed[bulk] = 0.75F;
		reconstructed[fringe] = 0.25F;
		pressure.rebuild(solid, markerCount, reconstructed, cutCells, null, fluid);
		check(fluid[bulk] && !fluid[fringe]
				&& Math.abs(pressure.liquidFraction(bulk) + pressure.liquidFraction(fringe) - 1.0F) < 1.0E-7F,
				"pressure footprint transfer did not preserve its source volume");
		int wall = index(sizeX, sizeY, 2, 2, 1);
		solid[wall] = true;
		cutCells.replaceMask(wall, FlipCutCellGeometry.FULL_MASK);
		markerCount[fringe] = 1;
		pressure.rebuild(solid, markerCount, reconstructed, cutCells, null, fluid);
		check(fluid[bulk] && fluid[fringe],
				"wall-supported marker cell was discarded as footprint-only pressure fringe");
	}

	private static void volumeDriftCorrectionPreservesLegitimateSurfaceDeficits() {
		float dt = 0.05F;
		check(Math.abs(FlipVolumeDriftCorrection.targetDivergence(1.25F, 1.25F, 1.0F, true, dt) - 0.25F) < 1.0E-7F,
				"overfilled surface cell did not request pressure expansion");
		check(Math.abs(FlipVolumeDriftCorrection.targetDivergence(0.75F, 0.75F, 1.0F, false, dt) + 0.25F) < 1.0E-7F,
				"underfilled interior cell did not request pressure convergence");
		check(FlipVolumeDriftCorrection.targetDivergence(0.75F, 0.75F, 1.0F, true, dt) == 0.0F,
				"legitimate underfilled free-surface cell was compressed");
		check(FlipVolumeDriftCorrection.targetDivergence(0.75F, 1.0F, 1.0F, false, dt) == 0.0F,
				"full exact interior cell was corrected from a smoothed-density deficit");
		check(FlipVolumeDriftCorrection.targetDivergence(0.5F, 0.5F, 0.5F, false, dt) == 0.0F,
				"cut-cell rest volume did not use its open capacity");
		check(Math.abs(FlipVolumeDriftCorrection.targetDivergence(4.0F, 4.0F, 1.0F, false, dt) - 2.0F) < 1.0E-7F,
				"volume-drift correction did not obey its per-step cap");
	}

	private static void fractionalHydrostaticColumnsBalanceGravity() {
		for (float surfaceFraction : new float[] {0.60F, 0.75F, 1.0F}) {
			FluidSolverConfig config = strictConfig();
			FluidDomain domain = new FluidDomain(2, 5, 2, config);
			for (int z = 0; z < 2; z++) for (int x = 0; x < 2; x++) {
				domain.setVolume(x, 0, z, 1.0F);
				domain.setVolume(x, 1, z, 1.0F);
				domain.setVolume(x, 2, z, surfaceFraction);
			}
			float exactVolume = domain.totalVolume();
			for (int step = 0; step < 200; step++) domain.step();
			float theta = surfaceFraction >= 1.0F - 1.0E-6F ? 0.5F : surfaceFraction - 0.5F;
			for (int y = 0; y <= 2; y++) {
				float expected = config.density() * config.gravity() * config.cellSize() * (2 - y + theta);
				float actual = domain.pressure(0, y, 0);
				float relativeError = Math.abs(actual - expected) / Math.max(1.0F, expected);
				check(relativeError < 2.0E-4F,
						"fractional hydrostatic pressure missed the analytic column: fraction=" + surfaceFraction
								+ ", y=" + y + ", expected=" + expected + ", actual=" + actual);
			}
			check(domain.maximumParticleSpeed() < 2.0E-4F,
					"fractional hydrostatic projection left parasitic marker velocity: " + domain.maximumParticleSpeed());
			check(Math.abs(domain.totalVolume() - exactVolume) < 1.0E-6F,
					"fractional pressure changed the exact marker ledger");
		}
	}

	private static void pressureNonConvergenceRejectsStep() {
		FluidSolverConfig config = new FluidSolverConfig(
				1.0F, 1_000.0F, 9.81F, 1.0F / 20.0F,
				1, 1.0E-12F, 0.45F, 2.0E-3F, 0.80F);
		FluidDomain domain = new FluidDomain(4, 6, 4, config);
		for (int z = 0; z < 4; z++) for (int x = 0; x < 4; x++) {
			domain.setVolume(x, 0, z, 1.0F);
			domain.setVolume(x, 1, z, 0.75F);
		}
		FlipParticleState before = domain.exportParticleState();
		try {
			domain.step();
			throw new AssertionError("unconverged pressure candidate was accepted");
		} catch (FluidDomain.PressureSolveBlockedException expected) {
			check(java.util.Arrays.equals(before.copyPositionVolume(),
						domain.exportParticleState().copyPositionVolume()),
					"rejected pressure candidate changed marker position/volume truth");
			check(java.util.Arrays.equals(before.copyParticleVelocities(),
						domain.exportParticleState().copyParticleVelocities()),
					"rejected pressure candidate changed marker momentum truth");
		}
	}

	private static void thinLayerRegularizationRemainsStable() {
		FluidSolverConfig config = strictConfig();
		FluidDomain domain = new FluidDomain(3, 3, 3, config);
		for (int z = 0; z < 3; z++) for (int x = 0; x < 3; x++) domain.setVolume(x, 0, z, 0.25F);
		float exactVolume = domain.totalVolume();
		for (int step = 0; step < 40; step++) {
			FluidDomain.StepStats stats = domain.step();
			check(Float.isFinite(stats.pressureResidual()), "thin-layer pressure residual became non-finite");
			check(stats.relativeVolumeError() < 1.0E-6F, "thin-layer step changed marker volume");
		}
		check(Math.abs(domain.totalVolume() - exactVolume) < 1.0E-6F, "thin layer lost exact marker volume");
		check(domain.maximumParticleSpeed() < 2.0E-3F,
				"theta regularization did not keep a sub-center thin layer stable: " + domain.maximumParticleSpeed());
	}

	private static void isolatedDropletReceivesUndilutedGravity() {
		float timeStep = 1.0F / 20.0F;
		FluidSolverConfig config = new FluidSolverConfig(
				0.5F, 1_000.0F, 9.81F, timeStep,
				200, 1.0E-5F, 0.45F, 2.0E-3F, 0.80F
		);
		FluidDomain domain = new FluidDomain(8, 32, 8, config);
		domain.setVolume(4, 24, 4, 0.125F);
		float exactVolume = domain.totalVolume();
		float initialCenterY = centerOfMassY(domain.exportParticleState());
		int steps = 8;
		for (int step = 0; step < steps; step++) domain.step();

		float expectedFall = config.gravity() * timeStep * timeStep * steps * (steps + 1) * 0.5F;
		float actualFall = initialCenterY - centerOfMassY(domain.exportParticleState());
		check(Math.abs(actualFall - expectedFall) < 2.0E-5F,
				"isolated droplet gravity was diluted by undefined air faces: expected fall="
						+ expectedFall + ", actual=" + actualFall);
		check(Math.abs(domain.maximumParticleSpeed() - config.gravity() * timeStep * steps) < 2.0E-5F,
				"isolated droplet velocity did not accumulate gravity without damping: "
						+ domain.maximumParticleSpeed());
		check(Math.abs(domain.totalVolume() - exactVolume) < 1.0E-7F,
				"isolated droplet changed the exact marker ledger");
	}

	private static void longRunHydrostaticSurfaceDoesNotSink() {
		FluidSolverConfig config = strictConfig();
		FluidDomain domain = new FluidDomain(4, 8, 4, config, 4 * 8 * 4 * 8);
		for (int z = 0; z < 4; z++) for (int x = 0; x < 4; x++) domain.setSolid(x, 0, z, true);
		for (int z = 0; z < 4; z++) for (int y = 1; y <= 4; y++) for (int x = 0; x < 4; x++) {
			domain.setVolume(x, y, z, 1.0F);
		}
		FlipParticleState initial = domain.exportParticleState();
		float initialCenterY = centerOfMassY(initial);
		FluidDomain.VolumeDistribution initialDistribution = domain.volumeDistribution(8.0F / 255.0F);
		for (int step = 0; step < 5_000; step++) domain.step();

		FlipParticleState result = domain.exportParticleState();
		double[] layers = exactLayerVolumes(result);
		check(result.particleCount() == initial.particleCount(), "hydrostatic marker count drifted over 5,000 steps");
		check(Math.abs(centerOfMassY(result) - initialCenterY) < 5.0E-4F,
				"hydrostatic physical surface sank over 5,000 steps");
		for (int y = 1; y <= 4; y++) {
			check(Math.abs(layers[y] - 16.0) < 1.0E-7,
					"hydrostatic layer changed exact marker volume at y=" + y + ": " + layers[y]);
		}
		FluidDomain.VolumeDistribution resultDistribution = domain.volumeDistribution(8.0F / 255.0F);
		check(resultDistribution.equals(initialDistribution),
				"hydrostatic reconstructed surface changed over 5,000 steps: before="
						+ initialDistribution + ", after=" + resultDistribution);
		check(domain.maximumParticleSpeed() < 1.0E-4F,
				"hydrostatic drift correction created persistent marker motion: " + domain.maximumParticleSpeed());
	}

	private static void waterDescendsAStairSlope() {
		FluidSolverConfig config = new FluidSolverConfig(
				1.0F, 1_000.0F, 9.81F, 1.0F / 40.0F,
				200, 1.0E-5F, 0.35F, 2.0E-3F, 0.80F
		);
		FluidDomain domain = new FluidDomain(12, 8, 4, config);
		for (int z = 0; z < 4; z++) for (int x = 0; x < 12; x++) {
			int topSolid = x < 2 ? 3 : x < 4 ? 2 : 1;
			for (int y = 0; y <= topSolid; y++) domain.setSolid(x, y, z, true);
		}
		for (int z = 0; z < 4; z++) for (int x = 0; x < 4; x++) {
			int firstFluid = x < 2 ? 4 : 3;
			for (int y = firstFluid; y <= 6; y++) domain.setVolume(x, y, z, 1.0F);
		}
		float exactVolume = domain.totalVolume();
		float initialCenter = domain.centerOfMassX();
		for (int step = 0; step < 300; step++) {
			FluidDomain.StepStats stats = domain.step();
			check(Float.isFinite(stats.pressureResidual()), "stair-slope pressure residual became non-finite");
			check(stats.relativeVolumeError() < 2.0E-6F, "stair-slope flow changed marker volume");
		}
		check(domain.centerOfMassX() > initialCenter + 1.0F,
				"water did not descend the fixed stair slope: before=" + initialCenter + ", after=" + domain.centerOfMassX());
		check(Math.abs(domain.totalVolume() - exactVolume) < 1.0E-4F,
				"stair-slope flow changed the exact particle ledger");
		domain.exportParticleState();
	}

	private static FluidSolverConfig strictConfig() {
		return new FluidSolverConfig(
				1.0F, 1_000.0F, 9.81F, 1.0F / 40.0F,
				300, 1.0E-7F, 0.40F, 2.0E-3F, 0.80F
		);
	}

	private static int index(int sizeX, int sizeY, int x, int y, int z) {
		return (z * sizeY + y) * sizeX + x;
	}

	private static float centerOfMassY(FlipParticleState state) {
		float[] positionVolume = state.copyPositionVolume();
		double weightedY = 0.0, volume = 0.0;
		for (int offset = 0; offset < positionVolume.length; offset += 4) {
			weightedY += positionVolume[offset + 1] * positionVolume[offset + 3];
			volume += positionVolume[offset + 3];
		}
		return (float) (weightedY / volume);
	}

	private static double[] exactLayerVolumes(FlipParticleState state) {
		double[] layers = new double[state.sizeY()];
		float[] positionVolume = state.copyPositionVolume();
		for (int offset = 0; offset < positionVolume.length; offset += 4) {
			int y = Math.min(state.sizeY() - 1,
					Math.max(0, (int) Math.floor(positionVolume[offset + 1] / state.cellSize())));
			layers[y] += positionVolume[offset + 3];
		}
		return layers;
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}

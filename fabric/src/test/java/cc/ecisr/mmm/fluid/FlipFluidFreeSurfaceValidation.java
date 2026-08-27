package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.world.MovingBodyStepResult;
import cc.ecisr.mmm.world.MovingBodySurfaceSnapshot;

import java.util.List;

/** Deterministic M2.5 gates for PLIC volume reconstruction and ghost-fluid pressure weights. */
public final class FlipFluidFreeSurfaceValidation {
	private FlipFluidFreeSurfaceValidation() { }

	public static void main(String[] args) {
		boolean fast = java.util.Arrays.asList(args).contains("--fast");
		planeVolumeInversionIsAccurate();
		axisAlignedInterfaceDistanceUsesExactCellLedger();
		pureWaterMarkerMassLedgerTracksVolume();
		volumeDriftCorrectionPreservesLegitimateSurfaceDeficits();
		fractionalHydrostaticColumnsBalanceGravity();
		projectedHydrostaticPressureIntegratesOnMovingBox();
		floatingBodyReplayCommitsEveryTick();
		mixedBodiesAreOrderIndependent();
		movingBodyPressureNonconvergenceRejectsCandidate();
		thinLayerRegularizationRemainsStable();
		isolatedDropletReceivesUndilutedGravity();
		if (!fast) longRunHydrostaticSurfaceDoesNotSink();
		waterDescendsAStairSlope();
		System.out.println("FlipFluid free-surface validation passed" + (fast ? " (short; 5,000-step soak skipped)" : ""));
	}

	private static void floatingBodyReplayCommitsEveryTick() {
		FluidSolverConfig config = new FluidSolverConfig(0.5F, 1_000.0F, 9.81F, 0.05F,
				240, 1.0E-6F, 0.40F, 2.0E-3F, 0.0F);
		FlipFluidDomain domain = new FlipFluidDomain(10, 12, 10, config, 20_000);
		for (int z = 0; z < 10; z++) for (int y = 0; y < 12; y++) for (int x = 0; x < 10; x++) {
			domain.setSolid(x, y, z, true);
		}
		for (int z = 1; z <= 8; z++) for (int y = 1; y <= 10; y++) for (int x = 1; x <= 8; x++) {
			domain.setSolid(x, y, z, false);
		}
		for (int z = 1; z <= 8; z++) for (int y = 1; y <= 3; y++) for (int x = 1; x <= 8; x++) {
			domain.setVolume(x, y, z, 1.0F);
		}
		for (int z = 1; z <= 8; z++) for (int x = 1; x <= 8; x++) domain.setVolume(x, 4, z, 0.5F);

		MovingBodySurfaceSnapshot.BoxPose previous = new MovingBodySurfaceSnapshot.BoxPose(
				2.2, 1.63, 2.2, 2.8, 3.43, 2.8);
		prepareBodyCompatibleFluid(domain, previous);
		FluidDomainState state = domain.captureAuthoritativeState();
		MovingBodySurfaceSnapshot.BoxPose current = previous;
		double nextTickVelocityY = -1.6 * 0.98;
		CpuFluidSolverBackend backend = new CpuFluidSolverBackend();
		for (int step = 0; step < 80; step++) {
			double boundaryVelocityY = (current.minimumY() - previous.minimumY()) / config.timeStep();
			MovingBodySurfaceSnapshot.Body body = new MovingBodySurfaceSnapshot.Body(
					1L, previous, current, 0.0, boundaryVelocityY, 0.0,
					0.0, nextTickVelocityY, 0.0,
					MovingBodySurfaceSnapshot.CouplingMode.DYNAMIC, 70.0);
			FluidStepCandidate candidate = backend.evaluate(new FluidStepRequest(
					step, state, new MovingBodySurfaceSnapshot(List.of(body)), false, 0));
			MovingBodyStepResult result = candidate.movingBodyResults().getFirst();
			state = candidate.state();
			previous = current;
			current = translateY(current, result.finalVelocityYMetresPerSecond() * config.timeStep());
			nextTickVelocityY = (result.finalVelocityYMetresPerSecond() - 1.6) * 0.98;
			check(Double.isFinite(current.minimumY()) && Double.isFinite(nextTickVelocityY),
					"floating player replay produced a non-finite entity state at tick " + step);
		}
	}

	private static void prepareBodyCompatibleFluid(
			FlipFluidDomain domain, MovingBodySurfaceSnapshot.BoxPose body
	) {
		double cellSize = domain.config().cellSize();
		double cellVolume = cellSize * cellSize * cellSize;
		for (int z = 1; z <= 8; z++) for (int y = 1; y <= 4; y++) for (int x = 1; x <= 8; x++) {
			double overlapX = overlap(x * cellSize, (x + 1) * cellSize, body.minimumX(), body.maximumX());
			double overlapZ = overlap(z * cellSize, (z + 1) * cellSize, body.minimumZ(), body.maximumZ());
			double maximumBlockedFraction = overlapX * overlapZ * cellSize / cellVolume;
			if (maximumBlockedFraction > 0.0) {
				float trajectoryCapacity = Math.nextDown((float) (1.0 - maximumBlockedFraction));
				float initialVolume = y <= 3 ? 1.0F : 0.5F;
				domain.setVolume(x, y, z, Math.min(initialVolume, trajectoryCapacity));
			}
		}
		FlipParticleState particles = domain.exportParticleState();
		float[] positionVolume = particles.copyPositionVolume();
		double bodyCentreX = 0.5 * (body.minimumX() + body.maximumX());
		double bodyCentreZ = 0.5 * (body.minimumZ() + body.maximumZ());
		for (int marker = 0; marker < particles.particleCount(); marker++) {
			int base = marker * 4;
			if (!(positionVolume[base] > body.minimumX() && positionVolume[base] < body.maximumX()
					&& positionVolume[base + 1] > body.minimumY() && positionVolume[base + 1] < body.maximumY()
					&& positionVolume[base + 2] > body.minimumZ() && positionVolume[base + 2] < body.maximumZ())) continue;
			int cellX = (int) Math.floor(positionVolume[base] / cellSize);
			int cellZ = (int) Math.floor(positionVolume[base + 2] / cellSize);
			positionVolume[base] = (float) ((cellX + (cellX * cellSize < bodyCentreX ? 0.125 : 0.875)) * cellSize);
			positionVolume[base + 2] = (float) ((cellZ + (cellZ * cellSize < bodyCentreZ ? 0.125 : 0.875)) * cellSize);
		}
		domain.restoreParticleState(new FlipParticleState(
				particles.sizeX(), particles.sizeY(), particles.sizeZ(), particles.cellSize(),
				particles.particleCount(), particles.totalVolume(), positionVolume,
				particles.copyParticleVelocities(), particles.copyCollisionMicroMasks()));
	}

	private static double overlap(double firstMinimum, double firstMaximum,
			double secondMinimum, double secondMaximum) {
		return Math.max(0.0, Math.min(firstMaximum, secondMaximum) - Math.max(firstMinimum, secondMinimum));
	}

	private static MovingBodySurfaceSnapshot.BoxPose translateY(
			MovingBodySurfaceSnapshot.BoxPose pose, double displacement
	) {
		return new MovingBodySurfaceSnapshot.BoxPose(
				pose.minimumX(), pose.minimumY() + displacement, pose.minimumZ(),
				pose.maximumX(), pose.maximumY() + displacement, pose.maximumZ());
	}

	private static void pureWaterMarkerMassLedgerTracksVolume() {
		FluidSolverConfig config = FluidSolverConfig.prototypeDefaults();
		FlipFluidDomain domain = new FlipFluidDomain(4, 4, 4, config, 128);
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

	private static void projectedHydrostaticPressureIntegratesOnMovingBox() {
		FluidSolverConfig config = new FluidSolverConfig(0.5F, 1_000.0F, 9.81F, 0.05F,
				240, 1.0E-6F, 0.40F, 2.0E-3F, 0.0F);
		FlipFluidDomain domain = new FlipFluidDomain(5, 19, 5, config, 4_096);
		for (int z = 0; z < 5; z++) for (int y = 0; y < 19; y++) for (int x = 0; x < 5; x++) {
			domain.setSolid(x, y, z, true);
		}
		for (int z = 1; z <= 3; z++) for (int y = 1; y <= 17; y++) for (int x = 1; x <= 3; x++) {
			domain.setSolid(x, y, z, false);
		}
		for (int z = 1; z <= 3; z++) for (int y = 1; y <= 16; y++) for (int x = 1; x <= 3; x++) {
			domain.setVolume(x, y, z, 1.0F);
		}
		for (int y = 5; y <= 9; y++) domain.setVolume(2, y, 2, 0.94F);
		MovingBodySurfaceSnapshot.BoxPose pose = new MovingBodySurfaceSnapshot.BoxPose(
				1.2, 2.75, 1.2, 1.3, 4.75, 1.3);
		MovingBodySurfaceSnapshot.Body body = new MovingBodySurfaceSnapshot.Body(
				1L, pose, pose, 0.0, 0.0, 0.0,
				MovingBodySurfaceSnapshot.CouplingMode.KINEMATIC_EXTERNAL, 0.0);
		FluidDomainState authoritative = domain.captureAuthoritativeState();
		FluidStepCandidate candidate = new CpuFluidSolverBackend().evaluate(new FluidStepRequest(
				0L, authoritative,
				new MovingBodySurfaceSnapshot(List.of(body)), false, 0));
		check(candidate.movingBodyResults().size() == 1,
				"backend candidate omitted its moving-body pressure settlement");
		MovingBodyStepResult result = candidate.movingBodyResults().getFirst();
		double expected = config.density() * config.gravity()
				* body.displacedVolumeCubicMetres() * config.timeStep();
		check(Math.abs(result.pressureImpulseYKilogramMetresPerSecond() - expected) / expected <= 1.0E-3
				&& Math.abs(result.pressureImpulseXKilogramMetresPerSecond()) <= expected * 1.0E-7
				&& Math.abs(result.pressureImpulseZKilogramMetresPerSecond()) <= expected * 1.0E-7,
				"projected hydrostatic box impulse missed rho*g*V: actual="
						+ result.pressureImpulseYKilogramMetresPerSecond() + " expected=" + expected);

		MovingBodySurfaceSnapshot.Body dynamicBody = new MovingBodySurfaceSnapshot.Body(
				1L, pose, pose, 0.0, 0.0, 0.0,
				MovingBodySurfaceSnapshot.CouplingMode.DYNAMIC, 70.0);
		MovingBodyStepResult dynamic = new CpuFluidSolverBackend().evaluate(new FluidStepRequest(
				0L, authoritative, new MovingBodySurfaceSnapshot(List.of(dynamicBody)), false, 0))
				.movingBodyResults().getFirst();
		check(dynamic.pressureImpulseYKilogramMetresPerSecond() > 0.0
				&& dynamic.pressureImpulseYKilogramMetresPerSecond() < result.pressureImpulseYKilogramMetresPerSecond()
				&& Math.abs(dynamic.finalVelocityYMetresPerSecond()
				- dynamic.pressureImpulseYKilogramMetresPerSecond() / 70.0) <= 1.0E-12
				&& dynamic.unacceptedBoundaryImpulseYKilogramMetresPerSecond() == 0.0,
				"dynamic pressure Schur settlement did not close body momentum or added mass");

		MovingBodySurfaceSnapshot.Body fallingAfterStationaryTick = new MovingBodySurfaceSnapshot.Body(
				1L, pose, pose, 0.0, 0.0, 0.0, 0.0, -1.0, 0.0,
				MovingBodySurfaceSnapshot.CouplingMode.DYNAMIC, 70.0);
		MovingBodyStepResult falling = new CpuFluidSolverBackend().evaluate(new FluidStepRequest(
				0L, authoritative,
				new MovingBodySurfaceSnapshot(List.of(fallingAfterStationaryTick)), false, 0))
				.movingBodyResults().getFirst();
		double fallingImpulseY = falling.pressureImpulseYKilogramMetresPerSecond()
				+ falling.viscousImpulseYKilogramMetresPerSecond();
		check(Math.abs(falling.finalVelocityYMetresPerSecond() - (-1.0 + fallingImpulseY / 70.0)) <= 1.0E-12,
				"dynamic settlement initialized momentum from boundary displacement instead of next-tick velocity");
	}

	private static void mixedBodiesAreOrderIndependent() {
		FluidSolverConfig config = new FluidSolverConfig(0.5F, 1_000.0F, 9.81F, 0.05F,
				240, 1.0E-6F, 0.40F, 2.0E-3F, 0.0F);
		FlipFluidDomain domain = enclosedHydrostaticDomain(config);
		for (int x : new int[]{1, 3}) for (int y = 5; y <= 9; y++) domain.setVolume(x, y, 2, 0.94F);
		MovingBodySurfaceSnapshot.BoxPose firstPose = new MovingBodySurfaceSnapshot.BoxPose(
				0.7, 2.75, 1.2, 0.8, 4.75, 1.3);
		MovingBodySurfaceSnapshot.BoxPose secondPose = new MovingBodySurfaceSnapshot.BoxPose(
				1.7, 2.75, 1.2, 1.8, 4.75, 1.3);
		MovingBodySurfaceSnapshot.Body dynamic = new MovingBodySurfaceSnapshot.Body(
				4L, firstPose, firstPose, 0.0, 0.0, 0.0,
				MovingBodySurfaceSnapshot.CouplingMode.DYNAMIC, 70.0);
		MovingBodySurfaceSnapshot.Body external = new MovingBodySurfaceSnapshot.Body(
				9L, secondPose, secondPose, 0.0, 0.0, 0.0,
				MovingBodySurfaceSnapshot.CouplingMode.KINEMATIC_EXTERNAL, 0.0);
		FluidDomainState state = domain.captureAuthoritativeState();
		FluidStepCandidate forward = new CpuFluidSolverBackend().evaluate(new FluidStepRequest(
				0L, state, new MovingBodySurfaceSnapshot(List.of(dynamic, external)), false, 0));
		FluidStepCandidate reversed = new CpuFluidSolverBackend().evaluate(new FluidStepRequest(
				0L, state, new MovingBodySurfaceSnapshot(List.of(external, dynamic)), false, 0));
		check(forward.movingBodyResults().equals(reversed.movingBodyResults()),
				"moving-body settlement depends on request order");
		MovingBodyStepResult dynamicResult = forward.movingBodyResults().getFirst();
		MovingBodyStepResult externalResult = forward.movingBodyResults().getLast();
		check(dynamicResult.transientId() == 4L && externalResult.transientId() == 9L
				&& dynamicResult.unacceptedBoundaryImpulseYKilogramMetresPerSecond() == 0.0
				&& externalResult.unacceptedBoundaryImpulseYKilogramMetresPerSecond()
				== externalResult.pressureImpulseYKilogramMetresPerSecond()
				+ externalResult.viscousImpulseYKilogramMetresPerSecond(),
				"mixed dynamic/kinematic momentum ledger did not close");
	}

	private static FlipFluidDomain enclosedHydrostaticDomain(FluidSolverConfig config) {
		FlipFluidDomain domain = new FlipFluidDomain(5, 19, 5, config, 4_096);
		for (int z = 0; z < 5; z++) for (int y = 0; y < 19; y++) for (int x = 0; x < 5; x++) {
			domain.setSolid(x, y, z, true);
		}
		for (int z = 1; z <= 3; z++) for (int y = 1; y <= 17; y++) for (int x = 1; x <= 3; x++) {
			domain.setSolid(x, y, z, false);
		}
		for (int z = 1; z <= 3; z++) for (int y = 1; y <= 16; y++) for (int x = 1; x <= 3; x++) {
			domain.setVolume(x, y, z, 1.0F);
		}
		return domain;
	}

	private static void movingBodyPressureNonconvergenceRejectsCandidate() {
		FluidSolverConfig config = new FluidSolverConfig(0.5F, 1_000.0F, 9.81F, 0.05F,
				1, 1.0E-12F, 0.40F, 2.0E-3F, 0.0F);
		FlipFluidDomain domain = enclosedHydrostaticDomain(config);
		for (int y = 5; y <= 9; y++) domain.setVolume(2, y, 2, 0.94F);
		MovingBodySurfaceSnapshot.BoxPose pose = new MovingBodySurfaceSnapshot.BoxPose(
				1.2, 2.75, 1.2, 1.3, 4.75, 1.3);
		MovingBodySurfaceSnapshot.Body body = new MovingBodySurfaceSnapshot.Body(
				1L, pose, pose, 0.0, 0.0, 0.0,
				MovingBodySurfaceSnapshot.CouplingMode.DYNAMIC, 1.0);
		try {
			new CpuFluidSolverBackend().evaluate(new FluidStepRequest(0L,
					domain.captureAuthoritativeState(), new MovingBodySurfaceSnapshot(List.of(body)), false, 0));
			throw new AssertionError("nonconverged moving-body pressure candidate was accepted");
		} catch (FlipFluidDomain.MovingBodyBlockedException expected) {
			check(expected.getMessage().contains("residual target"),
					"moving-body pressure rejection lost its numerical reason");
		}
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
			FlipFluidDomain domain = new FlipFluidDomain(2, 5, 2, config);
			for (int z = 0; z < 2; z++) for (int x = 0; x < 2; x++) {
				domain.setVolume(x, 0, z, 1.0F);
				domain.setVolume(x, 1, z, 1.0F);
				domain.setVolume(x, 2, z, surfaceFraction);
			}
			float exactVolume = domain.totalVolume();
			domain.step();
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

	private static void thinLayerRegularizationRemainsStable() {
		FluidSolverConfig config = strictConfig();
		FlipFluidDomain domain = new FlipFluidDomain(3, 3, 3, config);
		for (int z = 0; z < 3; z++) for (int x = 0; x < 3; x++) domain.setVolume(x, 0, z, 0.25F);
		float exactVolume = domain.totalVolume();
		for (int step = 0; step < 40; step++) {
			FlipFluidDomain.StepStats stats = domain.step();
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
		FlipFluidDomain domain = new FlipFluidDomain(8, 32, 8, config);
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
		FlipFluidDomain domain = new FlipFluidDomain(4, 8, 4, config, 4 * 8 * 4 * 8);
		for (int z = 0; z < 4; z++) for (int x = 0; x < 4; x++) domain.setSolid(x, 0, z, true);
		for (int z = 0; z < 4; z++) for (int y = 1; y <= 4; y++) for (int x = 0; x < 4; x++) {
			domain.setVolume(x, y, z, 1.0F);
		}
		FlipParticleState initial = domain.exportParticleState();
		float initialCenterY = centerOfMassY(initial);
		FlipFluidDomain.VolumeDistribution initialDistribution = domain.volumeDistribution(8.0F / 255.0F);
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
		FlipFluidDomain.VolumeDistribution resultDistribution = domain.volumeDistribution(8.0F / 255.0F);
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
		FlipFluidDomain domain = new FlipFluidDomain(12, 8, 4, config);
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
			FlipFluidDomain.StepStats stats = domain.step();
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

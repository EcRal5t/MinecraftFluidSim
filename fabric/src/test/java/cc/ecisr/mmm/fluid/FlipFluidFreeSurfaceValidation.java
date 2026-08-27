package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.air.BuiltinGasDefinitions;
import cc.ecisr.mmm.fluid.air.GasRegistry;
import cc.ecisr.mmm.granular.SolidVolume;
import cc.ecisr.mmm.thermal.ThermalDefinitionCodec;
import cc.ecisr.mmm.thermal.ThermalLimits;
import cc.ecisr.mmm.thermal.ThermalMaterialDefinition;
import cc.ecisr.mmm.thermal.ThermalPhaseTransition;
import cc.ecisr.mmm.thermal.ThermalRegistry;
import cc.ecisr.mmm.world.MovingBodyStepResult;
import cc.ecisr.mmm.world.MovingBodySurfaceSnapshot;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
		mechanicalAuditIsOptIn();
		fractionalHydrostaticColumnsBalanceGravity();
		projectedHydrostaticPressureIntegratesOnMovingBox();
		smallExternalBodyDisturbanceDoesNotAmplifyVelocity();
		mixedBodiesAreOrderIndependent();
		movingBodyPressureNonconvergenceRejectsCandidate();
		thinLayerRegularizationRemainsStable();
		isolatedDropletReceivesUndilutedGravity();
		if (!fast) {
			closedHalfPoolWithoutBodiesDoesNotSelfExcite();
			floatingBodyReplayHasDecayingEnvelope();
		}
		waterDescendsAStairSlope();
		System.out.println("FlipFluid free-surface validation passed"
				+ (fast ? " (short; production closed-pool replays skipped)" : ""));
	}

	private static void mechanicalAuditIsOptIn() {
		FlipFluidDomain domain = new FlipFluidDomain(2, 2, 2, strictConfig(), 128);
		domain.setVolume(0, 0, 0, 1.0F);
		FluidDomainState state = domain.captureAuthoritativeState();
		CpuFluidSolverBackend backend = new CpuFluidSolverBackend();
		FluidStepCandidate ordinary = backend.evaluate(new FluidStepRequest(
				0L, state, MovingBodySurfaceSnapshot.EMPTY, false, 0));
		FluidStepCandidate audited = backend.evaluate(new FluidStepRequest(
				0L, state, MovingBodySurfaceSnapshot.EMPTY, false, 0, true));
		check(!ordinary.stats().mechanicalAudit().enabled()
				&& audited.stats().mechanicalAudit().enabled(),
				"production request unexpectedly paid for staged mechanical diagnostics");
	}

	private static void floatingBodyReplayHasDecayingEnvelope() {
		FluidSolverConfig config = new FluidSolverConfig(
				0.5F, 1_000.0F, 9.81F, 1.0F / 20.0F,
				120, 1.0E-4F, 0.40F, 2.0E-3F, 0.80F);
		FlipFluidDomain domain = new FlipFluidDomain(10, 10, 10, config, 10 * 10 * 10 * 8);
		domain.sealDomainBoundary();
		for (int z = 0; z < 10; z++) for (int y = 0; y < 5; y++) for (int x = 0; x < 10; x++) {
			domain.setVolume(x, y, z, 1.0F);
		}

		MovingBodySurfaceSnapshot.BoxPose previous = new MovingBodySurfaceSnapshot.BoxPose(
				2.2, 2.25, 2.2, 2.8, 4.05, 2.8);
		prepareBodyCompatibleFluid(domain, previous, 4);
		FluidMaterialRegistry liquids = BuiltinFluidMaterials.createRegistry().runtimeRegistry();
		GasRegistry gas = BuiltinGasDefinitions.createRegistry();
		domain.bindGasRegistry(gas);
		domain.bindThermal(productionPoolThermalRegistry(gas), liquids, GasRegistry.STANDARD_TEMPERATURE_K);
		FluidDomainState state = domain.captureAuthoritativeState();
		MovingBodySurfaceSnapshot.BoxPose current = previous;
		double nextTickVelocityX = 0.0;
		double nextTickVelocityY = -config.gravity() * config.timeStep();
		double nextTickVelocityZ = 0.0;
		double[] centres = new double[600];
		BodyWindow early = new BodyWindow(), middle = new BodyWindow(), late = new BodyWindow();
		CpuFluidSolverBackend backend = new CpuFluidSolverBackend();
		for (int step = 0; step < centres.length; step++) {
			double boundaryVelocityX = (current.minimumX() - previous.minimumX()) / config.timeStep();
			double boundaryVelocityY = (current.minimumY() - previous.minimumY()) / config.timeStep();
			double boundaryVelocityZ = (current.minimumZ() - previous.minimumZ()) / config.timeStep();
			MovingBodySurfaceSnapshot.Body body = new MovingBodySurfaceSnapshot.Body(
					1L, previous, current, boundaryVelocityX, boundaryVelocityY, boundaryVelocityZ,
					nextTickVelocityX, nextTickVelocityY, nextTickVelocityZ,
					MovingBodySurfaceSnapshot.CouplingMode.DYNAMIC, 70.0);
			FluidStepCandidate candidate;
			try {
				candidate = backend.evaluate(new FluidStepRequest(
						step, state, new MovingBodySurfaceSnapshot(List.of(body)), true,
						ThermalLimits.MAX_PHASE_OPERATIONS_PER_STEP, true));
			} catch (RuntimeException failed) {
				throw new AssertionError("floating player replay failed at tick " + step
						+ " and y=" + current.minimumY(), failed);
			}
			MovingBodyStepResult result = candidate.movingBodyResults().getFirst();
			state = candidate.state();
			centres[step] = 0.5 * (current.minimumY() + current.maximumY());
			PoolMetrics metrics = poolMetrics(state);
			if (step < 40) early.add(metrics, candidate.stats().mechanicalAudit(), result,
					centres[step], result.finalVelocityYMetresPerSecond(), 70.0);
			else if (step >= 280 && step < 320) middle.add(metrics, candidate.stats().mechanicalAudit(), result,
					centres[step], result.finalVelocityYMetresPerSecond(), 70.0);
			else if (step >= 560) late.add(metrics, candidate.stats().mechanicalAudit(), result,
					centres[step], result.finalVelocityYMetresPerSecond(), 70.0);
			previous = current;
			current = translate(current,
					result.finalVelocityXMetresPerSecond() * config.timeStep(),
					result.finalVelocityYMetresPerSecond() * config.timeStep(),
					result.finalVelocityZMetresPerSecond() * config.timeStep());
			nextTickVelocityX = result.finalVelocityXMetresPerSecond();
			nextTickVelocityY = result.finalVelocityYMetresPerSecond()
					- config.gravity() * config.timeStep();
			nextTickVelocityZ = result.finalVelocityZMetresPerSecond();
			check(Double.isFinite(current.minimumX()) && Double.isFinite(current.minimumY())
					&& Double.isFinite(current.minimumZ()) && Double.isFinite(nextTickVelocityX)
					&& Double.isFinite(nextTickVelocityY) && Double.isFinite(nextTickVelocityZ),
					"floating player replay produced a non-finite entity state at tick " + step);
		}
		System.out.println("  closed half-pool floating-player audit: early=" + early
				+ " middle=" + middle + " late=" + late);
		checkOscillationSettles(centres, config.fluidEpsilon() * config.cellSize());
		check(middle.maximumFluidKineticEnergyJoules < early.maximumFluidKineticEnergyJoules
				&& late.maximumFluidKineticEnergyJoules < middle.maximumFluidKineticEnergyJoules,
				"floating player amplified the liquid kinetic-energy envelope: early=" + early
						+ " middle=" + middle + " late=" + late);
		check(early.bodyMomentumCloses() && middle.bodyMomentumCloses() && late.bodyMomentumCloses(),
				"floating player impulse did not close its body momentum: early=" + early
						+ " middle=" + middle + " late=" + late);
	}

	private static void smallExternalBodyDisturbanceDoesNotAmplifyVelocity() {
		FluidSolverConfig config = new FluidSolverConfig(0.5F, 1_000.0F, 9.81F, 0.05F,
				240, 1.0E-6F, 0.40F, 2.0E-3F, 0.0F);
		FlipFluidDomain domain = new FlipFluidDomain(12, 12, 12, config, 32_000);
		for (int z = 0; z < 12; z++) for (int y = 0; y < 12; y++) for (int x = 0; x < 12; x++) {
			domain.setSolid(x, y, z, true);
		}
		for (int z = 1; z <= 10; z++) for (int y = 1; y <= 10; y++) for (int x = 1; x <= 10; x++) {
			domain.setSolid(x, y, z, false);
		}
		domain.setSolid(1, 11, 1, false);
		for (int z = 1; z <= 10; z++) for (int y = 1; y <= 5; y++) for (int x = 1; x <= 10; x++) {
			domain.setVolume(x, y, z, 1.0F);
		}
		FluidDomainState state = domain.captureAuthoritativeState();
		MovingBodySurfaceSnapshot.BoxPose above = new MovingBodySurfaceSnapshot.BoxPose(
				2.875, 3.01, 2.875, 3.125, 3.26, 3.125);
		MovingBodySurfaceSnapshot.BoxPose resting = translateY(above, -0.02);
		double initialPeakSpeed = 0.0, delayedPeakSpeed = 0.0, latePeakSpeed = 0.0;
		CpuFluidSolverBackend backend = new CpuFluidSolverBackend();
		for (int step = 0; step < 600; step++) {
			MovingBodySurfaceSnapshot.BoxPose before = step == 0 ? above : resting;
			double velocityY = step == 0 ? -0.02 / config.timeStep() : 0.0;
			MovingBodySurfaceSnapshot.Body item = new MovingBodySurfaceSnapshot.Body(
					2L, before, resting, 0.0, velocityY, 0.0,
					MovingBodySurfaceSnapshot.CouplingMode.KINEMATIC_EXTERNAL, 0.0);
			FluidStepCandidate candidate = backend.evaluate(new FluidStepRequest(
					step, state, new MovingBodySurfaceSnapshot(List.of(item)), false, 0));
			state = candidate.state();
			double speed = maximumSpeed(state.particles());
			if (step < 100) initialPeakSpeed = Math.max(initialPeakSpeed, speed);
			else delayedPeakSpeed = Math.max(delayedPeakSpeed, speed);
			if (step >= 500) latePeakSpeed = Math.max(latePeakSpeed, speed);
		}
		check(delayedPeakSpeed <= initialPeakSpeed,
				"small settled external body caused delayed liquid-speed amplification: "
						+ initialPeakSpeed + " -> " + delayedPeakSpeed);
		check(latePeakSpeed <= config.fluidEpsilon(),
				"small settled external body left liquid moving at " + latePeakSpeed);
	}

	private static double maximumSpeed(FlipParticleState particles) {
		float[] velocity = particles.copyParticleVelocities();
		double maximum = 0.0;
		for (int marker = 0; marker < particles.particleCount(); marker++) {
			double vx = velocity[marker * 3], vy = velocity[marker * 3 + 1], vz = velocity[marker * 3 + 2];
			maximum = Math.max(maximum, Math.sqrt(vx * vx + vy * vy + vz * vz));
		}
		return maximum;
	}

	private static void checkOscillationSettles(double[] positions, double stoppingDistance) {
		double[] extrema = new double[positions.length];
		int count = 0;
		for (int tick = 1; tick + 1 < positions.length; tick++) {
			double before = positions[tick] - positions[tick - 1];
			double after = positions[tick + 1] - positions[tick];
			if (before != 0.0 && after != 0.0 && Math.signum(before) != Math.signum(after)) {
				extrema[count++] = positions[tick];
			}
		}
		check(count >= 4, "floating player replay did not expose enough extrema for an envelope audit");
		double firstHalfCycle = Math.abs(extrema[1] - extrema[0]);
		double lastHalfCycle = Math.abs(extrema[count - 1] - extrema[count - 2]);
		check(lastHalfCycle < firstHalfCycle && lastHalfCycle <= stoppingDistance,
				"floating player oscillation did not settle: first=" + firstHalfCycle
						+ ", last=" + lastHalfCycle + ", stop=" + stoppingDistance);
	}

	private static void prepareBodyCompatibleFluid(
			FlipFluidDomain domain, MovingBodySurfaceSnapshot.BoxPose body, int filledTopCell
	) {
		double cellSize = domain.config().cellSize();
		double cellVolume = cellSize * cellSize * cellSize;
		for (int z = 1; z + 1 < domain.sizeZ(); z++) for (int y = 1; y <= filledTopCell; y++) {
			for (int x = 1; x + 1 < domain.sizeX(); x++) {
			double overlapX = overlap(x * cellSize, (x + 1) * cellSize, body.minimumX(), body.maximumX());
			double overlapY = overlap(y * cellSize, (y + 1) * cellSize, body.minimumY(), body.maximumY());
			double overlapZ = overlap(z * cellSize, (z + 1) * cellSize, body.minimumZ(), body.maximumZ());
			double maximumBlockedFraction = overlapX * overlapZ * cellSize / cellVolume;
			if (overlapY > 0.0 && maximumBlockedFraction > 0.0) {
				float trajectoryCapacity = Math.nextDown((float) (1.0 - maximumBlockedFraction));
				domain.setVolume(x, y, z, trajectoryCapacity);
			}
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
		return translate(pose, 0.0, displacement, 0.0);
	}

	private static MovingBodySurfaceSnapshot.BoxPose translate(
			MovingBodySurfaceSnapshot.BoxPose pose, double displacementX, double displacementY, double displacementZ
	) {
		return new MovingBodySurfaceSnapshot.BoxPose(
				pose.minimumX() + displacementX, pose.minimumY() + displacementY, pose.minimumZ() + displacementZ,
				pose.maximumX() + displacementX, pose.maximumY() + displacementY, pose.maximumZ() + displacementZ);
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

	private static void closedHalfPoolWithoutBodiesDoesNotSelfExcite() {
		FluidSolverConfig config = new FluidSolverConfig(
				0.5F, 1_000.0F, 9.81F, 1.0F / 20.0F,
				120, 1.0E-4F, 0.40F, 2.0E-3F, 0.80F);
		FlipFluidDomain domain = new FlipFluidDomain(10, 10, 10, config, 10 * 10 * 10 * 8);
		domain.sealDomainBoundary();
		for (int z = 0; z < 10; z++) for (int y = 0; y < 5; y++) for (int x = 0; x < 10; x++) {
			domain.setVolume(x, y, z, 1.0F);
		}
		FluidMaterialRegistry liquids = BuiltinFluidMaterials.createRegistry().runtimeRegistry();
		GasRegistry gas = BuiltinGasDefinitions.createRegistry();
		domain.bindGasRegistry(gas);
		ThermalRegistry thermal = productionPoolThermalRegistry(gas);
		domain.bindThermal(thermal, liquids, GasRegistry.STANDARD_TEMPERATURE_K);
		ThermalPhaseTransition waterVapor = thermal.transitions().stream()
				.filter(transition -> transition.id().equals("mmm:water_vapor")).findFirst().orElseThrow();
		double phaseQuantumHeight = (double) waterVapor.lowerVolumeSvqPerBatch()
				/ SolidVolume.SVQ_PER_CELL * config.cellSize();
		double phaseQuantumVelocity = phaseQuantumHeight / config.timeStep();

		FluidDomainState state = domain.captureAuthoritativeState();
		PoolMetrics initial = poolMetrics(state);
		PoolWindow early = new PoolWindow(), middle = new PoolWindow(), late = new PoolWindow();
		CpuFluidSolverBackend backend = new CpuFluidSolverBackend();
		FluidStepCandidate phaseDisabledPreview = backend.evaluate(new FluidStepRequest(
				0L, state, MovingBodySurfaceSnapshot.EMPTY, false, 0, true));
		System.out.println("  closed half-pool phase-disabled first step: metrics="
				+ poolMetrics(phaseDisabledPreview.state()) + " stats=" + phaseDisabledPreview.stats());
		for (int step = 0; step < 240; step++) {
			FluidStepCandidate candidate;
			try {
				candidate = backend.evaluate(new FluidStepRequest(
						step, state, MovingBodySurfaceSnapshot.EMPTY, true,
						ThermalLimits.MAX_PHASE_OPERATIONS_PER_STEP, true));
			} catch (RuntimeException failed) {
				throw new AssertionError("closed half-pool production candidate failed at tick " + step, failed);
			}
			state = candidate.state();
			PoolMetrics metrics = poolMetrics(state);
			if (step == 0) System.out.println("  closed half-pool phase-enabled first step: metrics="
					+ metrics + " stats=" + candidate.stats() + " pendingCount="
					+ state.pendingPhaseVolume().entries().size() + " coherent="
					+ state.thermal().coherentGroups() + " gas=" + state.gas().nodes());
			if (step == 0) check(state.pendingPhaseVolume().entries().size() == 100,
					"closed half-pool did not retain one pending phase-volume entry per interface cell");
			if (step == 1) check(state.pendingPhaseVolume().entries().isEmpty(),
					"closed half-pool did not consume restored phase-volume work on the next projection");
			if (step < 20) early.add(metrics, candidate.stats().mechanicalAudit());
			else if (step >= 100 && step < 120) middle.add(metrics, candidate.stats().mechanicalAudit());
			else if (step >= 220) late.add(metrics, candidate.stats().mechanicalAudit());
		}
		System.out.println("  closed half-pool no-body audit: initial=" + initial
				+ " early=" + early + " middle=" + middle + " late=" + late);
		double physicalLiquidMass = late.maximumTotalVolume * Math.pow(config.cellSize(), 3.0) * config.density();
		double quantumKineticBound = 0.5 * physicalLiquidMass * phaseQuantumVelocity * phaseQuantumVelocity;
		check(late.maximumKineticEnergyJoules <= quantumKineticBound
				&& late.maximumSpeedMetresPerSecond <= phaseQuantumVelocity
				&& late.maximumSurfaceReliefMetres <= phaseQuantumHeight,
				"closed half-pool exceeded its exact phase-quantum motion bound: quantumHeight="
						+ phaseQuantumHeight + " quantumVelocity=" + phaseQuantumVelocity
						+ " quantumK=" + quantumKineticBound + " late=" + late);
		check(Double.doubleToRawLongBits(early.minimumTotalVolume)
				== Double.doubleToRawLongBits(late.minimumTotalVolume)
				&& Double.doubleToRawLongBits(early.maximumTotalVolume)
				== Double.doubleToRawLongBits(late.maximumTotalVolume)
				&& state.pendingPhaseVolume().entries().isEmpty(),
				"closed half-pool repeated phase transfer after reaching its quantized equilibrium: early="
						+ early + ", late=" + late + ", pending=" + state.pendingPhaseVolume().entries());
	}

	private static ThermalRegistry productionPoolThermalRegistry(GasRegistry gas) {
		ArrayList<ThermalMaterialDefinition> materials = new ArrayList<>();
		for (String id : List.of("water", "oil", "mercury", "ice", "air", "water_vapor", "co2")) {
			try (InputStreamReader reader = productionResource("thermal_materials/" + id + ".json")) {
				materials.add(ThermalDefinitionCodec.decodeMaterial("mmm:" + id, reader));
			} catch (java.io.IOException failure) {
				throw new AssertionError("Could not close production thermal material " + id, failure);
			}
		}
		ArrayList<ThermalPhaseTransition> transitions = new ArrayList<>();
		for (String id : List.of("ice_water", "water_vapor")) {
			try (InputStreamReader reader = productionResource("phase_transitions/" + id + ".json")) {
				transitions.add(ThermalDefinitionCodec.decodeTransition("mmm:" + id, reader));
			} catch (java.io.IOException failure) {
				throw new AssertionError("Could not close production phase transition " + id, failure);
			}
		}
		return new ThermalRegistry(materials, List.of(), transitions, List.of(), List.of(),
				"", "", gas.fingerprint());
	}

	private static InputStreamReader productionResource(String path) {
		var stream = FlipFluidFreeSurfaceValidation.class.getResourceAsStream("/data/mmm/mmm/" + path);
		if (stream == null) throw new AssertionError("Missing production fixture resource " + path);
		return new InputStreamReader(stream, StandardCharsets.UTF_8);
	}

	private static PoolMetrics poolMetrics(FluidDomainState state) {
		FlipMaterialParticleState particles = state.materials();
		if (particles == null) throw new AssertionError("Production pool lost its material carrier state");
		float[] positionVolume = particles.copyPositionVolume();
		float[] velocity = particles.copyParticleVelocities();
		double[] mass = particles.copyParticleMass();
		double cellVolume = Math.pow(state.config().cellSize(), 3.0);
		double kinetic = 0.0, maximumSpeed = 0.0;
		double[] columnVolume = new double[state.sizeX() * state.sizeZ()];
		for (int marker = 0; marker < particles.particleCount(); marker++) {
			int p = marker * 4, v = marker * 3;
			double speedSquared = velocity[v] * velocity[v]
					+ velocity[v + 1] * velocity[v + 1] + velocity[v + 2] * velocity[v + 2];
			kinetic += 0.5 * mass[marker] * cellVolume * speedSquared;
			maximumSpeed = Math.max(maximumSpeed, Math.sqrt(speedSquared));
			int x = Math.clamp((int) Math.floor(positionVolume[p] / state.config().cellSize()), 0, state.sizeX() - 1);
			int z = Math.clamp((int) Math.floor(positionVolume[p + 2] / state.config().cellSize()), 0, state.sizeZ() - 1);
			columnVolume[z * state.sizeX() + x] += positionVolume[p + 3];
		}
		double minimum = Double.POSITIVE_INFINITY, maximum = Double.NEGATIVE_INFINITY;
		for (double volume : columnVolume) {
			double height = volume * state.config().cellSize();
			minimum = Math.min(minimum, height);
			maximum = Math.max(maximum, height);
		}
		return new PoolMetrics(kinetic, maximumSpeed, maximum - minimum, particles.totalVolume());
	}

	private record PoolMetrics(
			double kineticEnergyJoules,
			double maximumSpeedMetresPerSecond,
			double surfaceReliefMetres,
			double totalVolume
	) { }

	private static final class PoolWindow {
		private double maximumKineticEnergyJoules;
		private double maximumSpeedMetresPerSecond;
		private double maximumSurfaceReliefMetres;
		private double minimumTotalVolume = Double.POSITIVE_INFINITY;
		private double maximumTotalVolume = Double.NEGATIVE_INFINITY;
		private double particleToGridTransferJoules;
		private double gravityWorkJoules;
		private double pressureProjectionWorkJoules;
		private double viscosityWorkJoules;
		private double gridToParticleTransferJoules;
		private double thermalBuoyancyWorkJoules;
		private double surfaceTensionWorkJoules;
		private double fixedBoundaryWorkJoules;
		private double closureResidualJoules;

		private void add(PoolMetrics metrics, FlipFluidDomain.MechanicalStepAudit audit) {
			check(audit.enabled(), "closed-pool fixture did not request its mechanical audit");
			maximumKineticEnergyJoules = Math.max(maximumKineticEnergyJoules, metrics.kineticEnergyJoules());
			maximumSpeedMetresPerSecond = Math.max(maximumSpeedMetresPerSecond,
					metrics.maximumSpeedMetresPerSecond());
			maximumSurfaceReliefMetres = Math.max(maximumSurfaceReliefMetres, metrics.surfaceReliefMetres());
			minimumTotalVolume = Math.min(minimumTotalVolume, metrics.totalVolume());
			maximumTotalVolume = Math.max(maximumTotalVolume, metrics.totalVolume());
			particleToGridTransferJoules += audit.particleToGridTransferJoules();
			gravityWorkJoules += audit.gravityWorkJoules();
			pressureProjectionWorkJoules += audit.pressureProjectionWorkJoules();
			viscosityWorkJoules += audit.viscosityWorkJoules();
			gridToParticleTransferJoules += audit.gridToParticleTransferJoules();
			thermalBuoyancyWorkJoules += audit.thermalBuoyancyWorkJoules();
			surfaceTensionWorkJoules += audit.surfaceTensionWorkJoules();
			fixedBoundaryWorkJoules += audit.fixedBoundaryWorkJoules();
			closureResidualJoules += audit.closureResidualJoules();
		}

		@Override public String toString() {
			return "{K=" + maximumKineticEnergyJoules + " J, vmax=" + maximumSpeedMetresPerSecond
					+ " m/s, relief=" + maximumSurfaceReliefMetres + " m, volume="
					+ minimumTotalVolume + ".." + maximumTotalVolume + ", P2G="
					+ particleToGridTransferJoules + " J, gravity=" + gravityWorkJoules
					+ " J, pressure=" + pressureProjectionWorkJoules + " J, viscosity="
					+ viscosityWorkJoules + " J, buoyancy=" + thermalBuoyancyWorkJoules
					+ " J, surface=" + surfaceTensionWorkJoules + " J, fixed="
					+ fixedBoundaryWorkJoules + " J, G2P=" + gridToParticleTransferJoules
					+ " J, closure=" + closureResidualJoules + " J}";
		}
	}

	private static final class BodyWindow {
		private double minimumCentreY = Double.POSITIVE_INFINITY;
		private double maximumCentreY = Double.NEGATIVE_INFINITY;
		private double maximumBodySpeedMetresPerSecond;
		private double maximumFluidSpeedMetresPerSecond;
		private double maximumFluidKineticEnergyJoules;
		private double pressureImpulseYKilogramMetresPerSecond;
		private double viscousImpulseYKilogramMetresPerSecond;
		private double bodyMomentumChangeYKilogramMetresPerSecond;
		private double displacementWorkJoules;
		private double movingBoundaryWorkJoules;
		private double gravityWorkJoules;
		private double pressureProjectionWorkJoules;
		private double closureResidualJoules;

		private void add(
				PoolMetrics metrics, FlipFluidDomain.MechanicalStepAudit audit,
				MovingBodyStepResult result, double centreY, double bodyVelocityY, double bodyMassKilograms
		) {
			check(audit.enabled(), "floating-player fixture did not request its mechanical audit");
			minimumCentreY = Math.min(minimumCentreY, centreY);
			maximumCentreY = Math.max(maximumCentreY, centreY);
			maximumBodySpeedMetresPerSecond = Math.max(maximumBodySpeedMetresPerSecond, Math.abs(bodyVelocityY));
			maximumFluidSpeedMetresPerSecond = Math.max(maximumFluidSpeedMetresPerSecond,
					metrics.maximumSpeedMetresPerSecond());
			maximumFluidKineticEnergyJoules = Math.max(maximumFluidKineticEnergyJoules,
					metrics.kineticEnergyJoules());
			pressureImpulseYKilogramMetresPerSecond += result.pressureImpulseYKilogramMetresPerSecond();
			viscousImpulseYKilogramMetresPerSecond += result.viscousImpulseYKilogramMetresPerSecond();
			bodyMomentumChangeYKilogramMetresPerSecond += bodyMassKilograms
					* (result.finalVelocityYMetresPerSecond() - result.initialVelocityYMetresPerSecond());
			displacementWorkJoules += result.displacementWorkJoules();
			movingBoundaryWorkJoules += audit.movingAndPorousBoundaryWorkJoules();
			gravityWorkJoules += audit.gravityWorkJoules();
			pressureProjectionWorkJoules += audit.pressureProjectionWorkJoules();
			closureResidualJoules += audit.closureResidualJoules();
		}

		private boolean bodyMomentumCloses() {
			double impulse = pressureImpulseYKilogramMetresPerSecond
					+ viscousImpulseYKilogramMetresPerSecond;
			double scale = Math.abs(pressureImpulseYKilogramMetresPerSecond)
					+ Math.abs(viscousImpulseYKilogramMetresPerSecond)
					+ Math.abs(bodyMomentumChangeYKilogramMetresPerSecond);
			return Math.abs(impulse - bodyMomentumChangeYKilogramMetresPerSecond)
					<= Math.ulp(scale == 0.0 ? 1.0 : scale) * 128.0;
		}

		@Override public String toString() {
			return "{centre=" + minimumCentreY + ".." + maximumCentreY + " m, bodyV="
					+ maximumBodySpeedMetresPerSecond + " m/s, fluidV=" + maximumFluidSpeedMetresPerSecond
					+ " m/s, fluidK=" + maximumFluidKineticEnergyJoules + " J, pressureImpulseY="
					+ pressureImpulseYKilogramMetresPerSecond + " kg*m/s, viscousImpulseY="
					+ viscousImpulseYKilogramMetresPerSecond + " kg*m/s, momentumResidual="
					+ (pressureImpulseYKilogramMetresPerSecond + viscousImpulseYKilogramMetresPerSecond
					- bodyMomentumChangeYKilogramMetresPerSecond) + " kg*m/s, bodyWork="
					+ displacementWorkJoules + " J, movingBoundary=" + movingBoundaryWorkJoules
					+ " J, gravity=" + gravityWorkJoules + " J, projection="
					+ pressureProjectionWorkJoules + " J, closure=" + closureResidualJoules + " J}";
		}
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

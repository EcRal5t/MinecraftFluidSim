package cc.ecisr.mmm.simulation;

import cc.ecisr.mmm.fluid.CpuFluidSolverBackend;
import cc.ecisr.mmm.fluid.FluidDomainState;
import cc.ecisr.mmm.fluid.FluidDomain;
import cc.ecisr.mmm.fluid.FluidStepCandidate;
import cc.ecisr.mmm.fluid.FluidStepRequest;
import cc.ecisr.mmm.fluid.air.BuiltinGasDefinitions;
import cc.ecisr.mmm.thermal.ThermalLimits;
import cc.ecisr.mmm.world.MovingBodyFluidCoupling;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/** Replays controller injection through the default live step and the complete authoritative handoff. */

abstract class DynamicReplayValidationScenarios extends ProductionReplayValidationSupport {
	static void dynamicPlayerFloatRemainsBoundedAcrossBackend() throws Exception {
		final double bodyWidth = 0.60;
		final double bodyHeight = 1.80;
		final double bodyVolume = bodyWidth * bodyHeight * bodyWidth;
		final double initialBodyMinimumX = 1.70;
		final double initialBodyMinimumZ = 1.70;
		final double gravity = 0.08;
		final double vanillaAirDrag = 0.98;
		final int motionIntervalTicks = 4;
		final int totalTicks = 2_000;
		final int tailTicks = 200;
		FullCapabilityTestFixture.Registries registries = loadProductionRegistries();
		FluidDomain domain = createFullyBoundPlanarHalfFill(registries);
		double bodyMinimumX = initialBodyMinimumX;
		double bodyMinimumZ = initialBodyMinimumZ;
		double bodyMinimumY = bodyBottomForSubmergence(
				domain, bodyMinimumX, bodyMinimumZ, bodyWidth, bodyHeight,
				MinecraftRegionInputs.NEUTRAL_SUBMERGENCE);
		double initialBodyMinimumY = bodyMinimumY;
		double minimumTailY = Double.POSITIVE_INFINITY;
		double maximumTailY = Double.NEGATIVE_INFINITY;
		double maximumDisplacement = 0.0;
		double maximumKinetic = 0.0;
		double tailMaximumKinetic = 0.0;
		Vec3 bodyVelocity = Vec3.ZERO;
		FluidDomainState state = domain.captureAuthoritativeState();
		CpuFluidSolverBackend backend = new CpuFluidSolverBackend();
		long generation = 0L;
		for (int tick = 1; tick <= totalTicks; tick++) {
			Vec3 displacementThisTick = bodyVelocity;
			bodyMinimumX += displacementThisTick.x;
			bodyMinimumY += displacementThisTick.y;
			bodyMinimumZ += displacementThisTick.z;
			Vec3 velocityBeforeResponse = new Vec3(
					bodyVelocity.x * vanillaAirDrag,
					(bodyVelocity.y - gravity) * vanillaAirDrag,
					bodyVelocity.z * vanillaAirDrag);
			FluidDomain.BodyFluidSample sample = domain.sampleAxisAlignedBody(
					bodyMinimumX, bodyMinimumY, bodyMinimumZ,
					bodyMinimumX + bodyWidth, bodyMinimumY + bodyHeight, bodyMinimumZ + bodyWidth);
			double submergedFraction = sample.displacedVolumeCubicMetres() / bodyVolume;
			require(submergedFraction > 0.0 && submergedFraction <= 1.0 + 1.0E-9,
					"dynamic player left the finite wet AABB observation at tick " + tick
							+ ": submergence=" + submergedFraction);
			bodyVelocity = MinecraftRegionInputs.responseVelocity(
					velocityBeforeResponse,
					new Vec3(sample.velocityXMetresPerSecond(), sample.velocityYMetresPerSecond(),
							sample.velocityZMetresPerSecond()),
					Math.min(1.0, submergedFraction), gravity, false);
			maximumDisplacement = Math.max(maximumDisplacement, Math.abs(bodyMinimumY - initialBodyMinimumY));
			if (tick > totalTicks - tailTicks) {
				minimumTailY = Math.min(minimumTailY, bodyMinimumY);
				maximumTailY = Math.max(maximumTailY, bodyMinimumY);
			}
			if (tick % motionIntervalTicks != 0) continue;
			MovingBodyFluidCoupling.Request body = new MovingBodyFluidCoupling.Request(
					(float) sample.centerXMetres(), (float) sample.centerYMetres(),
					(float) sample.centerZMetres(),
					displacementThisTick.x * 20.0, displacementThisTick.y * 20.0,
					displacementThisTick.z * 20.0, sample.displacedVolumeCubicMetres());
			FluidStepCandidate candidate = backend.evaluate(new FluidStepRequest(
					generation, state, List.of(body), false, 0, true));
			double kinetic = candidate.stats().mechanicalAudit().finalParticleKineticEnergyJoules();
			maximumKinetic = Math.max(maximumKinetic, kinetic);
			if (tick > totalTicks - tailTicks) tailMaximumKinetic = Math.max(tailMaximumKinetic, kinetic);
			domain = FluidDomain.restoreAuthoritativeState(candidate.state());
			state = candidate.state();
			generation++;
		}
		double tailRange = maximumTailY - minimumTailY;
		double finalKinetic = kineticEnergy(domain.exportMaterialParticleState());
		System.out.printf("  dynamic player float gate: displacement=%.9g m, tail range=%.9g m, "
				+ "tail vy=%+.9g block/tick, liquid K max/tail/final=%.9g/%.9g/%.9g J%n",
				maximumDisplacement, tailRange, bodyVelocity.y,
				maximumKinetic, tailMaximumKinetic, finalKinetic);
		require(maximumDisplacement <= productionConfig().cellSize(),
				"dynamic player displacement exceeded one solver cell: " + maximumDisplacement + " m");
		require(tailRange <= 0.05,
				"dynamic player retained or amplified a visible vertical oscillation: " + tailRange + " m");
		require(Math.abs(bodyVelocity.y) <= 0.005,
				"dynamic player tail velocity did not settle: " + bodyVelocity.y + " blocks/tick");
		require(tailMaximumKinetic <= 0.025 && finalKinetic <= 0.002,
				"dynamic player disturbance did not decay to the stationary-body energy envelope: max/tail/final="
						+ maximumKinetic + "/" + tailMaximumKinetic + "/" + finalKinetic + " J");
	}

	static double bodyBottomForSubmergence(
			FluidDomain domain, double minimumX, double minimumZ,
			double width, double height, double targetFraction
	) {
		double bodyVolume = width * height * width;
		double wet = 0.0;
		double dry = domain.sizeY() * domain.config().cellSize();
		for (int iteration = 0; iteration < 80; iteration++) {
			double middle = 0.5 * (wet + dry);
			double fraction = domain.sampleAxisAlignedBody(
					minimumX, middle, minimumZ, minimumX + width, middle + height, minimumZ + width)
					.displacedVolumeCubicMetres() / bodyVolume;
			if (fraction > targetFraction) wet = middle;
			else dry = middle;
		}
		double result = 0.5 * (wet + dry);
		double fraction = domain.sampleAxisAlignedBody(
				minimumX, result, minimumZ, minimumX + width, result + height, minimumZ + width)
				.displacedVolumeCubicMetres() / bodyVolume;
		require(Math.abs(fraction - targetFraction) <= 1.0E-9,
				"could not place the dynamic player at neutral continuous submergence: " + fraction);
		return result;
	}

	static void stationaryPlayerSizedBodyDoesNotExcitePlanarSurface() throws Exception {
		FullCapabilityTestFixture.Registries registries = loadProductionRegistries();
		FluidDomain initial = createFullyBoundPlanarHalfFill(registries);
		FluidDomainState state = initial.captureAuthoritativeState();
		CpuFluidSolverBackend backend = new CpuFluidSolverBackend();
		MovingBodyFluidCoupling.Request body = new MovingBodyFluidCoupling.Request(
				2.0F, 1.60F, 2.0F, 0.0, 0.0, 0.0, 0.6 * 1.8 * 0.6 * 0.72);
		double initialCenterY = centerOfMassY(initial.exportMaterialParticleState());
		double maximumKinetic = 0.0, accumulatedLiquidY = 0.0, accumulatedReactionY = 0.0;
		FluidDomain restored = initial;
		for (long generation = 0L; generation < 500L; generation++) {
			FluidStepCandidate candidate = backend.evaluate(new FluidStepRequest(
					generation, state, List.of(body), false, 0, true));
			MovingBodyFluidCoupling.Result coupling = candidate.movingBodyCoupling();
			require(coupling.coupledMarkers() > 0 && coupling.coupledVolumeCubicMetres() > 0.0,
					"stationary player-sized body lost its wet moving-boundary support");
			accumulatedLiquidY += coupling.impulseYKilogramMetresPerSecond();
			accumulatedReactionY -= coupling.impulseYKilogramMetresPerSecond();
			maximumKinetic = Math.max(maximumKinetic,
					candidate.stats().mechanicalAudit().finalParticleKineticEnergyJoules());
			restored = FluidDomain.restoreAuthoritativeState(candidate.state());
			state = candidate.state();
		}
		double finalKinetic = kineticEnergy(restored.exportMaterialParticleState());
		double centerYDelta = centerOfMassY(restored.exportMaterialParticleState()) - initialCenterY;
		require(Math.abs(accumulatedLiquidY + accumulatedReactionY) <= 1.0E-15,
				"stationary moving-boundary liquid/reaction impulse ledger did not close");
		require(maximumKinetic <= 0.025 && finalKinetic <= 0.002 && Math.abs(centerYDelta) <= 0.001,
				"stationary player-sized moving boundary excited the planar surface: max/final="
						+ maximumKinetic + "/" + finalKinetic + " J, centerYDelta=" + centerYDelta);
		System.out.printf("  stationary player-sized body gate: K max/final=%.9g/%.9g J, "
				+ "centerYDelta=%+.9g m, liquid/reaction Jy=%+.9g/%+.9g kg*m/s%n",
				maximumKinetic, finalKinetic, centerYDelta, accumulatedLiquidY, accumulatedReactionY);
	}

	/**
	 * Lower bound for the moving-body coupling. The other gates only bound how little a body may excite
	 * the surface, so a wiring regression which hands core a zero boundary velocity would pass all of
	 * them while the liquid never responds to a body that is actually moving.
	 */
	static void walkingBodyDisturbsThenSettlesAcrossBackend() throws Exception {
		final double bodyWidth = 0.60;
		final double bodyHeight = 1.80;
		final double bodyVolume = bodyWidth * bodyHeight * bodyWidth;
		final double walkingMetresPerSecond = 4.3;
		final int walkingTicks = 8;
		final int settleTicks = 400;
		FullCapabilityTestFixture.Registries registries = loadProductionRegistries();
		FluidDomain initial = createFullyBoundPlanarHalfFill(registries);
		double bodyMinimumX = 0.50;
		double bodyMinimumZ = 1.70;
		double bodyMinimumY = bodyBottomForSubmergence(
				initial, bodyMinimumX, bodyMinimumZ, bodyWidth, bodyHeight,
				MinecraftRegionInputs.NEUTRAL_SUBMERGENCE);
		double perTick = walkingMetresPerSecond / 20.0;
		// Added-mass scale of the immersed body: the disturbance floor is a small fraction of it, so the
		// gate rejects a coupling that is silently zero without pinning the exact physical response.
		double addedMassJoules = 0.5 * productionConfig().density() * bodyVolume
				* MinecraftRegionInputs.NEUTRAL_SUBMERGENCE
				* walkingMetresPerSecond * walkingMetresPerSecond;
		FluidDomainState state = initial.captureAuthoritativeState();
		CpuFluidSolverBackend backend = new CpuFluidSolverBackend();
		long generation = 0L;
		double maximumKinetic = 0.0, tailMaximumKinetic = 0.0, peakImpulseX = 0.0;
		double maximumStepMillis = 0.0;
		FluidDomain restored = initial;
		for (int tick = 1; tick <= walkingTicks + settleTicks; tick++) {
			boolean walking = tick <= walkingTicks;
			FluidDomain.BodyFluidSample sample = restored.sampleAxisAlignedBody(
					bodyMinimumX, bodyMinimumY, bodyMinimumZ,
					bodyMinimumX + bodyWidth, bodyMinimumY + bodyHeight, bodyMinimumZ + bodyWidth);
			double submergedFraction = sample.displacedVolumeCubicMetres() / bodyVolume;
			require(submergedFraction > 0.0 && submergedFraction <= 1.0 + 1.0E-9,
					"walking body left the finite wet AABB observation at tick " + tick
							+ ": submergence=" + submergedFraction);
			MovingBodyFluidCoupling.Request body = new MovingBodyFluidCoupling.Request(
					(float) sample.centerXMetres(), (float) sample.centerYMetres(),
					(float) sample.centerZMetres(),
					walking ? walkingMetresPerSecond : 0.0, 0.0, 0.0,
					sample.displacedVolumeCubicMetres());
			// Production orders this exactly the same way: capture, freeze the body, evaluate the
			// complete candidate, then restore it. The wall time therefore covers the whole step.
			long stepStart = System.nanoTime();
			FluidStepCandidate candidate = backend.evaluate(new FluidStepRequest(
					generation, state, List.of(body), false, 0));
			MovingBodyFluidCoupling.Result coupling = candidate.movingBodyCoupling();
			require(coupling.coupledMarkers() > 0 && coupling.coupledVolumeCubicMetres() > 0.0,
					"walking body lost its wet moving-boundary support at tick " + tick);
			restored = FluidDomain.restoreAuthoritativeState(candidate.state());
			double stepMillis = (System.nanoTime() - stepStart) / 1_000_000.0;
			if (tick > WARMUP_STEPS) maximumStepMillis = Math.max(maximumStepMillis, stepMillis);
			double kinetic = kineticEnergy(restored.exportMaterialParticleState());
			maximumKinetic = Math.max(maximumKinetic, kinetic);
			if (walking) {
				peakImpulseX = Math.max(peakImpulseX,
						Math.abs(coupling.impulseXKilogramMetresPerSecond()));
			} else if (tick > settleTicks - 100) {
				tailMaximumKinetic = Math.max(tailMaximumKinetic, kinetic);
			}
			state = candidate.state();
			generation++;
			if (walking) bodyMinimumX += perTick;
		}
		double finalKinetic = kineticEnergy(restored.exportMaterialParticleState());
		System.out.printf("  walking body gate: peak impulse=%.9g kg*m/s, K max/tail/final="
				+ "%.9g/%.9g/%.9g J, added-mass scale=%.9g J, step max=%.3f ms%n",
				peakImpulseX, maximumKinetic, tailMaximumKinetic, finalKinetic, addedMassJoules,
				maximumStepMillis);
		require(peakImpulseX > 0.0,
				"a walking body reported no liquid impulse; the moving-body wiring is not reaching the step");
		require(maximumKinetic >= addedMassJoules * 1.0E-3,
				"a walking body produced no observable liquid disturbance: K=" + maximumKinetic
						+ " J against an added-mass scale of " + addedMassJoules + " J");
		// The current solver has no viscosity stage, so a coherent slosh is only damped through the
		// PIC/FLIP blend and survives as a bounded standing mode. The gate therefore forbids growth and
		// self-excitation rather than claiming the injected energy is fully dissipated.
		require(tailMaximumKinetic <= maximumKinetic * 0.25 && finalKinetic <= maximumKinetic * 0.25,
				"walking-body disturbance did not stay bounded after the body stopped: max/tail/final="
						+ maximumKinetic + "/" + tailMaximumKinetic + "/" + finalKinetic + " J");
		require(maximumStepMillis <= STEP_BUDGET_MILLIS,
				"a production-shaped backend step exceeded the server-tick budget: max="
						+ maximumStepMillis + " ms");
	}

	static void compressedPlanarHalfFillRemainsStableAcrossBackend() throws Exception {
		FullCapabilityTestFixture.Registries registries = loadProductionRegistries();
		FluidDomain initial = createFullyBoundCompressedPlanarHalfFill(registries);
		FluidDomainState state = initial.captureAuthoritativeState();
		FluidDomain restored = FluidDomain.restoreAuthoritativeState(state);
		CpuFluidSolverBackend backend = new CpuFluidSolverBackend();
		long generation = 0L;
		double initialCenterY = centerOfMassY(restored.exportMaterialParticleState());
		double maximumKinetic = 0.0, tailMaximumKinetic = 0.0;
		double maximumBodyPressureResidual = 0.0, maximumPressureResidual = 0.0;
		long phaseOperations = 0L, boiledCells = 0L, condensedCells = 0L;
		double previousAvailable = restored.gasBoundary().availableVolume(1);
		require(kineticEnergy(restored.exportMaterialParticleState()) == 0.0,
				"compressed planar fixture did not start at rest");
		require(restored.gasNodeGaugePressurePa(1) > 100_000.0,
				"compressed planar fixture did not retain its sealed external compression");
		System.out.printf("  compressed planar initial: V=%.12f m3 p=%+.6f Pa T=%.9f K U=%.9g J%n",
				previousAvailable, restored.gasNodeGaugePressurePa(1),
				restored.gasNodeTemperatureK(1), restored.exactInternalEnergy().toDouble());
		for (int step = 0; step < 1_000; step++) {
			FluidStepCandidate candidate = backend.evaluate(new FluidStepRequest(
					generation, state, List.of(), false, 0, true));
			require(candidate.movingBodyCoupling().equals(MovingBodyFluidCoupling.Result.NONE),
					"compressed no-entity backend reported a liquid moving-body impulse at step " + step);
			require(candidate.baseGeneration() == generation
					&& candidate.candidateGeneration() == generation + 1L,
					"compressed planar backend generation was not adjacent at step " + step);
			FluidDomain.StepStats stats = candidate.stats();
			FluidDomain.MechanicalStepAudit audit = stats.mechanicalAudit();
			restored = FluidDomain.restoreAuthoritativeState(candidate.state());
			double kinetic = audit.finalParticleKineticEnergyJoules();
			double available = restored.gasBoundary().availableVolume(1);
			double deltaAvailable = available - previousAvailable;
			maximumKinetic = Math.max(maximumKinetic, kinetic);
			if (step >= 500) tailMaximumKinetic = Math.max(tailMaximumKinetic, kinetic);
			double bodyPressure = audit.gravityWorkJoules() + audit.thermalBuoyancyWorkJoules()
					+ audit.surfaceTensionWorkJoules() + audit.fixedBoundaryWorkJoules()
					+ audit.pressureProjectionWorkJoules();
			maximumBodyPressureResidual = Math.max(maximumBodyPressureResidual, Math.abs(bodyPressure));
			maximumPressureResidual = Math.max(maximumPressureResidual, stats.pressureResidual());
			phaseOperations += stats.gasPhase().operations();
			boiledCells += stats.gasPhase().boiledCells();
			condensedCells += stats.gasPhase().condensedCells();
			require(audit.captured() && restored.gasBoundary().auditClosed()
					&& restored.exactInternalEnergy().equals(restored.thermalAudit().expectedInventory()),
					"compressed planar backend opened an authoritative audit at step " + step);
			state = candidate.state();
			generation++;
			previousAvailable = available;
			if ((step + 1) % 100 == 0) {
				System.out.printf("  compressed planar backend step %03d: K=%.9g J dV=%+.9g m3 "
						+ "PCG=%d residual=%.9g p=%+.3f Pa T=%.6f K%n",
						step + 1, kinetic, deltaAvailable, stats.pressureIterations(),
						stats.pressureResidual(), restored.gasNodeGaugePressurePa(1),
						restored.gasNodeTemperatureK(1));
			}
		}
		double finalKinetic = kineticEnergy(restored.exportMaterialParticleState());
		double centerYDelta = centerOfMassY(restored.exportMaterialParticleState()) - initialCenterY;
		require(maximumKinetic <= 0.025 && tailMaximumKinetic <= 0.015 && finalKinetic <= 0.015,
				"compressed planar free surface self-excited: max=" + maximumKinetic
						+ " tail=" + tailMaximumKinetic + " final=" + finalKinetic + " J");
		require(Math.abs(centerYDelta) <= 0.001,
				"compressed planar center of mass drifted by " + centerYDelta + " m");
		require(maximumBodyPressureResidual <= 0.05,
				"compressed planar body-force/pressure work did not balance: "
						+ maximumBodyPressureResidual + " J");
		require(phaseOperations == 0L && boiledCells == 0L && condensedCells == 0L,
				"compressed mechanical-equilibrium fixture unexpectedly executed gas phase changes");
		System.out.printf("  compressed planar backend gate: kinetic max/tail/final="
				+ "%.9g/%.9g/%.9g J, centerYDelta=%+.9g m, max body-pressure residual=%.9g J, "
				+ "max PCG residual=%.9g%n", maximumKinetic, tailMaximumKinetic, finalKinetic,
				centerYDelta, maximumBodyPressureResidual, maximumPressureResidual);
	}

	static void defaultLiveStepsRemainBounded() throws Exception {
		FluidDomain warmup = createPouredControllerDomain();
		long warmupStart = System.nanoTime();
		CpuFluidSolverBackend warmupBackend = new CpuFluidSolverBackend();
		for (int generation = 0; generation < 10; generation++) {
			FluidStepCandidate warmupCandidate = warmupBackend.evaluate(
					new FluidStepRequest(generation, warmup.captureAuthoritativeState(), List.of(), false, 0));
			warmup = requirePreparedCpuDomain(warmupCandidate);
		}
		double warmupMilliseconds = (System.nanoTime() - warmupStart) / 1_000_000.0;
		System.out.printf("  production live JVM warmup: %.3f ms for 10 production-shaped steps "
				+ "(reported separately from frame budget)%n", warmupMilliseconds);

		FluidDomain domain = createPouredControllerDomain();
		double volume = domain.totalVolumeExact();
		double mass = domain.totalMass();
		ArrayList<Double> elapsed = new ArrayList<>(REPLAY_STEPS);
		CpuFluidSolverBackend backend = new CpuFluidSolverBackend();
		long generation = 0L;
		for (int step = 0; step < REPLAY_STEPS; step++) {
			long start = System.nanoTime();
			FluidDomain.StepStats stats;
			try {
				// The live budget covers the production CPU path: capture one immutable input, evaluate on
				// a private aggregate, and atomically adopt that already evaluated aggregate.
				FluidStepCandidate candidate = backend.evaluate(
						new FluidStepRequest(generation, domain.captureAuthoritativeState(), List.of(), false, 0));
				domain = requirePreparedCpuDomain(candidate);
				stats = candidate.stats();
				generation++;
			} catch (RuntimeException failure) {
				throw new AssertionError("production live-domain replay failed at step " + step
						+ ": " + failure.getMessage(), failure);
			}
			double milliseconds = (System.nanoTime() - start) / 1_000_000.0;
			elapsed.add(milliseconds);
			require(domain.totalVolumeExact() == volume && domain.totalMass() == mass,
					"default production step changed liquid volume or mass at step " + step);
			System.out.printf("  production live step %02d: %.3f ms, particles=%d, volume=%.9f, "
					+ "maxSpeed=%.6f, substeps=%d, PCG=%d, residual=%.6g%n",
					step + 1, milliseconds, domain.particleCount(), domain.totalVolumeExact(),
					domain.maximumParticleSpeed(), stats.substeps(), stats.pressureIterations(),
					stats.pressureResidual());
		}
		double maximum = elapsed.stream().mapToDouble(Double::doubleValue).max().orElseThrow();
		double[] ordered = elapsed.stream().mapToDouble(Double::doubleValue).sorted().toArray();
		double median = (ordered[ordered.length / 2 - 1] + ordered[ordered.length / 2]) * 0.5;
		System.out.printf("  production live timing: median=%.3f ms, max=%.3f ms, budget=%.1f ms%n",
				median, maximum, STEP_BUDGET_MILLIS);
		require(maximum <= STEP_BUDGET_MILLIS,
				"a production step exceeded the server-tick budget: max=" + maximum + " ms");
		System.out.println("  default live-domain replay remained valid and bounded for 20 steps");

		damBreakProductionStepsRemainBelowPauseThreshold();
	}

	private static void damBreakProductionStepsRemainBelowPauseThreshold() throws Exception {
		final double performancePauseThresholdMillis = 200.0;
		FullCapabilityTestFixture.Registries registries = loadProductionRegistries();
		FluidDomain warmup = createFullyBoundHalfFilledDomain(registries);
		CpuFluidSolverBackend backend = new CpuFluidSolverBackend();
		for (int generation = 0; generation < 5; generation++) {
			FluidStepCandidate candidate = backend.evaluate(new FluidStepRequest(
					generation, warmup.captureAuthoritativeState(), List.of(), true,
					ThermalLimits.MAX_PHASE_OPERATIONS_PER_STEP));
			warmup = requirePreparedCpuDomain(candidate);
		}

		FluidDomain domain = createFullyBoundDamBreakDemo(registries);
		double maximumMillis = 0.0;
		int consecutiveOverruns = 0;
		int maximumConsecutiveOverruns = 0;
		for (int generation = 0; generation < 20; generation++) {
			double volumeBefore = domain.totalVolumeExact();
			double massBefore = domain.totalMass();
			long start = System.nanoTime();
			FluidStepCandidate candidate = backend.evaluate(new FluidStepRequest(
					generation, domain.captureAuthoritativeState(), List.of(), true,
					ThermalLimits.MAX_PHASE_OPERATIONS_PER_STEP));
			domain = requirePreparedCpuDomain(candidate);
			double elapsedMillis = (System.nanoTime() - start) / 1_000_000.0;
			maximumMillis = Math.max(maximumMillis, elapsedMillis);
			consecutiveOverruns = elapsedMillis > performancePauseThresholdMillis
					? consecutiveOverruns + 1 : 0;
			maximumConsecutiveOverruns = Math.max(maximumConsecutiveOverruns, consecutiveOverruns);
			int phaseOperations = candidate.stats().condensedPhase().operations()
					+ candidate.stats().gasPhase().operations()
					+ candidate.stats().thermalAxis().granularPhase().transfers();
			if (phaseOperations == 0) {
				require(domain.totalVolumeExact() == volumeBefore && domain.totalMass() == massBefore,
						"phase-idle dam-break step changed liquid volume or mass");
			}
			require(Double.isFinite(domain.totalVolumeExact()) && domain.totalVolumeExact() > 0.0
					&& Double.isFinite(domain.totalMass()) && domain.totalMass() > 0.0,
					"dam-break production step produced invalid liquid inventory");
			System.out.printf("  dam-break live step %d: %.3f ms, particles=%d, volume=%.9f, "
					+ "substeps=%d, PCG=%d, residual=%.6g, phase=%d%n",
					generation + 1, elapsedMillis, domain.particleCount(),
					domain.totalVolumeExact(), candidate.stats().substeps(),
					candidate.stats().pressureIterations(), candidate.stats().pressureResidual(), phaseOperations);
		}
		require(maximumConsecutiveOverruns < 3,
				"dam-break production path would trigger the wall-time pause: max="
						+ maximumMillis + " ms, consecutive overruns=" + maximumConsecutiveOverruns);
		System.out.printf("  dam-break live maximum %.3f ms; consecutive > %.1f ms=%d (< 3 pause limit)%n",
				maximumMillis, performancePauseThresholdMillis, maximumConsecutiveOverruns);
	}

	static void floorDamBreakGasThermalPathRemainsDefined() throws Exception {
		FullCapabilityTestFixture.Registries registries = loadProductionRegistries();
		FluidDomain domain = createFullyBoundFloorDamBreakDemo(registries);
		CpuFluidSolverBackend backend = new CpuFluidSolverBackend();
		boolean observedTrappedAmbient = false;
		boolean observedAirVaporMixture = false;
		for (int generation = 0; generation < 200; generation++) {
			try {
				FluidStepCandidate candidate = backend.evaluate(new FluidStepRequest(
						generation, domain.captureAuthoritativeState(), List.of(), true,
						ThermalLimits.MAX_PHASE_OPERATIONS_PER_STEP));
				domain = requirePreparedCpuDomain(candidate);
				require(domain.gasBoundary().auditClosed()
						&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
						"floor dam-break opened its gas or total-U audit at step " + (generation + 1));
				for (int node = 1; node <= domain.gasBoundary().nodeCount(); node++) {
					require(Double.isFinite(domain.gasBoundary().availableVolume(node))
							&& domain.gasBoundary().availableVolume(node) > 0.0
							&& domain.gasBoundary().standardVolumeNanos(node) >= 0L,
							"floor dam-break produced an invalid sealed gas node at step " + (generation + 1));
					observedAirVaporMixture |= domain.gasBoundary().inventory(node)
							.component(BuiltinGasDefinitions.AIR) != null
							&& domain.gasBoundary().inventory(node)
							.component(BuiltinGasDefinitions.WATER_VAPOR) != null;
				}
				observedTrappedAmbient |= domain.gasBoundary().drawnFromAmbientNanos() > 0L;
				if ((generation + 1) % 20 == 0) {
					System.out.printf("  floor dam-break step %d: particles=%d, volume=%.9f, "
							+ "gasNodes=%d, phase=%d%n", generation + 1, domain.particleCount(),
							domain.totalVolumeExact(), domain.gasBoundary().nodeCount(),
							candidate.stats().gasPhase().operations());
				}
			} catch (RuntimeException failure) {
				StringBuilder gas = new StringBuilder();
				for (int node = 1; node <= domain.gasBoundary().nodeCount(); node++) {
					gas.append(" node=").append(node)
							.append(" V=").append(domain.gasBoundary().availableVolume(node))
							.append(" Vstd=").append(domain.gasBoundary().standardVolume(node));
				}
				throw new AssertionError("floor dam-break failed at step " + (generation + 1) + gas
						+ ": " + failure.getMessage(), failure);
			}
		}
		require(observedTrappedAmbient && observedAirVaporMixture,
				"floor dam-break did not exercise ambient pinch-off into a finite air/vapor node");
	}

	private static FluidDomain requirePreparedCpuDomain(FluidStepCandidate candidate) {
		FluidDomain prepared = candidate.claimPreparedCpuDomain();
		require(prepared != null, "CPU backend did not retain its evaluated candidate aggregate");
		return prepared;
	}

	static void fullyBoundHalfFillRoundTripsCurrentGasGeometry() throws Exception {
		FullCapabilityTestFixture.Registries registries = loadProductionRegistries();
		FluidDomain committed = createFullyBoundHalfFilledDomain(registries);
		FluidDomainState captured = committed.captureAuthoritativeState();
		FluidDomain restored = FluidDomain.restoreAuthoritativeState(captured);
		FluidDomainState roundTrip = restored.captureAuthoritativeState();

		require(committed.totalVolumeExact() == restored.totalVolumeExact()
				&& committed.totalMass() == restored.totalMass(),
				"full-capability capture/restore changed liquid volume or mass");
		require(committed.exactInternalEnergy().equals(restored.exactInternalEnergy())
				&& committed.thermalAudit().equals(restored.thermalAudit()),
				"full-capability capture/restore changed total U or its audit");
		require(restored.gasBoundary().auditClosed(), "restored full-capability gas ledger is open");
		requireSameGasState(captured.gas(), roundTrip.gas());
		System.out.printf("  full-capability half-fill round trip: particles=%d, volume=%.9f, gasNodes=%d%n",
				restored.particleCount(), restored.totalVolumeExact(), roundTrip.gas().nodes().size());

		CpuFluidSolverBackend backend = new CpuFluidSolverBackend();
		long backendStart = System.nanoTime();
		FluidStepCandidate candidate = backend.evaluate(new FluidStepRequest(
				7L, captured, List.of(), false, 0));
		double backendMilliseconds = (System.nanoTime() - backendStart) / 1_000_000.0;
		FluidDomain evaluated = FluidDomain.restoreAuthoritativeState(candidate.state());
		FluidDomainState evaluatedRoundTrip = evaluated.captureAuthoritativeState();
		require(candidate.baseGeneration() == 7L && candidate.candidateGeneration() == 8L,
				"full-capability backend candidate generation is not adjacent");
		require(evaluated.gasBoundary().auditClosed()
				&& evaluated.exactInternalEnergy().equals(evaluated.thermalAudit().expectedInventory()),
				"full-capability backend candidate did not close gas or total U audit");
		requireSameGasState(candidate.state().gas(), evaluatedRoundTrip.gas());
		System.out.printf("  full-capability phase-off backend candidate: %.3f ms (diagnostic only), "
				+ "substeps=%d, PCG=%d, gasNodes=%d%n", backendMilliseconds,
				candidate.stats().substeps(), candidate.stats().pressureIterations(),
				evaluatedRoundTrip.gas().nodes().size());
	}

	static void fullyBoundPhaseOnStepRemainsBounded() throws Exception {
		FullCapabilityTestFixture.Registries registries = loadProductionRegistries();
		FluidDomain phaseOff = createFullyBoundHalfFilledDomain(registries);
		long phaseOffStart = System.nanoTime();
		phaseOff.step(false, 0);
		System.out.printf("  full-capability phase-off direct step: %.3f ms%n",
				(System.nanoTime() - phaseOffStart) / 1_000_000.0);
		FluidDomain warmup = createFullyBoundHalfFilledDomain(registries);
		warmup.step(true, ThermalLimits.MAX_PHASE_OPERATIONS_PER_STEP);

		FluidDomain domain = createFullyBoundHalfFilledDomain(registries);
		long start = System.nanoTime();
		FluidDomain.StepStats stats = domain.step(true, ThermalLimits.MAX_PHASE_OPERATIONS_PER_STEP);
		double milliseconds = (System.nanoTime() - start) / 1_000_000.0;
		FluidDomain.GasThermalPhaseStats phase = domain.lastGasThermalPhaseStats();
		FluidDomainState committed = domain.captureAuthoritativeState();
		FluidDomain restored = FluidDomain.restoreAuthoritativeState(committed);
		require(domain.gasBoundary().auditClosed()
				&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
				"full-capability phase-on step did not close gas or total U audit");
		require(committed.pendingPhaseVolume().entries().equals(
					restored.exportPendingPhaseVolumeState().entries())
				&& restored.gasBoundary().auditClosed()
				&& restored.exactInternalEnergy().equals(restored.thermalAudit().expectedInventory()),
				"full-capability phase-on state did not preserve pending volume, gas or U across restore");
		System.out.printf("  full-capability phase-on production step: %.3f ms, operations=%d, "
				+ "boiled=%d, condensed=%d, substeps=%d, PCG=%d%n", milliseconds,
				phase.operations(), phase.boiledCells(), phase.condensedCells(),
				stats.substeps(), stats.pressureIterations());
		require(milliseconds <= STEP_BUDGET_MILLIS,
				"full-capability phase-on production step exceeded the server-tick budget: "
						+ milliseconds + " ms");
	}

	static void planarHalfFillWithoutEntitiesRemainsStableAcrossBackend() throws Exception {
		FullCapabilityTestFixture.Registries registries = loadProductionRegistries();
		FluidDomain initial = createFullyBoundPlanarHalfFill(registries);
		FluidDomainState state = initial.captureAuthoritativeState();
		CpuFluidSolverBackend backend = new CpuFluidSolverBackend();
		long generation = 0L;
		double initialKinetic = kineticEnergy(initial.exportMaterialParticleState());
		require(initialKinetic == 0.0, "planar no-entity fixture did not start at rest");
		double initialCenterY = centerOfMassY(initial.exportMaterialParticleState());
		double maximumKinetic = 0.0, tailMaximumKinetic = 0.0;
		double maximumBodyPressureResidual = 0.0;
		long phaseOperations = 0L, boiledCells = 0L, condensedCells = 0L;
		FluidDomain restored = initial;
		for (int step = 0; step < 500; step++) {
			FluidStepCandidate candidate = backend.evaluate(new FluidStepRequest(
					generation, state, List.of(), false, 0, true));
			require(candidate.movingBodyCoupling().equals(MovingBodyFluidCoupling.Result.NONE),
					"planar no-entity backend reported a liquid moving-body impulse at step " + step);
			require(candidate.baseGeneration() == generation
					&& candidate.candidateGeneration() == generation + 1L,
					"planar no-entity backend generation was not adjacent at step " + step);
			FluidDomain.StepStats stats = candidate.stats();
			FluidDomain.MechanicalStepAudit audit = stats.mechanicalAudit();
			require(audit.captured(), "planar no-entity backend omitted its requested mechanical audit");
			double kinetic = audit.finalParticleKineticEnergyJoules();
			maximumKinetic = Math.max(maximumKinetic, kinetic);
			if (step >= 300) tailMaximumKinetic = Math.max(tailMaximumKinetic, kinetic);
			double bodyPressureResidual = audit.gravityWorkJoules()
					+ audit.thermalBuoyancyWorkJoules() + audit.surfaceTensionWorkJoules()
					+ audit.fixedBoundaryWorkJoules() + audit.pressureProjectionWorkJoules();
			maximumBodyPressureResidual = Math.max(
					maximumBodyPressureResidual, Math.abs(bodyPressureResidual));
			phaseOperations += stats.gasPhase().operations();
			boiledCells += stats.gasPhase().boiledCells();
			condensedCells += stats.gasPhase().condensedCells();

			restored = FluidDomain.restoreAuthoritativeState(candidate.state());
			require(restored.gasBoundary().nodeCount() == 1 && restored.gasBoundary().auditClosed(),
					"planar no-entity backend restore opened or changed the sealed gas owner at step " + step);
			require(restored.exactInternalEnergy().equals(restored.thermalAudit().expectedInventory()),
					"planar no-entity backend restore opened total U at step " + step);
			require(candidate.state().pendingPhaseVolume().entries().equals(
					restored.exportPendingPhaseVolumeState().entries()),
					"planar no-entity backend restore changed pending phase volume at step " + step);
			state = candidate.state();
			generation++;
			if ((step + 1) % 100 == 0) {
				System.out.printf("  planar no-entity backend step %03d: kinetic=%.9g J maxSpeed=%.6g "
						+ "centerY=%.9f phase=%d/%d/%d p=%+.3f Pa T=%.6f K%n",
						step + 1, kinetic, restored.maximumParticleSpeed(),
						centerOfMassY(restored.exportMaterialParticleState()), stats.gasPhase().operations(),
						stats.gasPhase().boiledCells(), stats.gasPhase().condensedCells(),
						restored.gasNodeGaugePressurePa(1), restored.gasNodeTemperatureK(1));
			}
		}
		double finalKinetic = kineticEnergy(restored.exportMaterialParticleState());
		double centerYDelta = centerOfMassY(restored.exportMaterialParticleState()) - initialCenterY;
		require(Double.isFinite(finalKinetic), "planar no-entity final kinetic observation is not finite");
		require(maximumKinetic <= 0.025 && tailMaximumKinetic <= 0.005 && finalKinetic <= 0.002,
				"planar no-entity free surface self-excited: max=" + maximumKinetic
						+ " tail=" + tailMaximumKinetic + " final=" + finalKinetic + " J");
		require(Math.abs(centerYDelta) <= 0.001,
				"planar no-entity center of mass drifted by " + centerYDelta + " m");
		require(maximumBodyPressureResidual <= 0.05,
				"planar no-entity body-force/pressure work did not balance: "
						+ maximumBodyPressureResidual + " J");
		require(phaseOperations == 0L && boiledCells == 0L && condensedCells == 0L,
				"planar mechanical-equilibrium fixture unexpectedly executed gas phase changes");
		System.out.printf("  planar no-entity backend gate: kinetic max/tail/final="
				+ "%.9g/%.9g/%.9g J, centerYDelta=%+.9g m, max body-pressure residual=%.9g J%n",
				maximumKinetic, tailMaximumKinetic, finalKinetic, centerYDelta,
				maximumBodyPressureResidual);
	}

}

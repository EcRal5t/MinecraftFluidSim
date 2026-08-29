package cc.ecisr.mmm.simulation;

import cc.ecisr.mmm.chemistry.ChemicalRegistry;
import cc.ecisr.mmm.fluid.BuiltinFluidMaterials;
import cc.ecisr.mmm.fluid.CpuFluidSolverBackend;
import cc.ecisr.mmm.fluid.FluidDomainState;
import cc.ecisr.mmm.fluid.FlipFluidDomain;
import cc.ecisr.mmm.fluid.FlipMaterialParticleState;
import cc.ecisr.mmm.fluid.FlipParticleState;
import cc.ecisr.mmm.fluid.FluidMaterialRegistry;
import cc.ecisr.mmm.fluid.FluidSolverConfig;
import cc.ecisr.mmm.fluid.FluidStepCandidate;
import cc.ecisr.mmm.fluid.FluidStepRequest;
import cc.ecisr.mmm.fluid.air.AirNetwork;
import cc.ecisr.mmm.fluid.air.GasRegistry;
import cc.ecisr.mmm.fluid.air.GasStateSnapshot;
import cc.ecisr.mmm.granular.GranularRegistry;
import cc.ecisr.mmm.thermal.ThermalLimits;
import cc.ecisr.mmm.thermal.ThermalRegistry;
import cc.ecisr.mmm.thermal.ThermalTransport;
import cc.ecisr.mmm.world.MovingBodyFluidCoupling;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Replays controller injection through the default live step and the complete authoritative handoff. */
public final class ProductionFluidReplayValidation {
	private static final int REPLAY_STEPS = 20;
	private static final int WARMUP_STEPS = 20;
	private static final double STEP_BUDGET_MILLIS = 40.0;

	private ProductionFluidReplayValidation() { }

	public static void main(String[] args) throws Exception {
		defaultLiveStepsRemainBounded();
		controllerPourSequenceWithoutGasSettles();
		controllerPourSequencePreservesGasInventoryAcrossBackend();
		controllerEmptyThenBoundaryPourPreservesGasAcrossBackend();
		fullyBoundHalfFillRoundTripsCurrentGasGeometry();
		fullyBoundPhaseOnStepRemainsBounded();
		planarHalfFillWithoutEntitiesRemainsStableAcrossBackend();
		compressedPlanarHalfFillRemainsStableAcrossBackend();
		stationaryPlayerSizedBodyDoesNotExcitePlanarSurface();
		System.out.println("Production fluid replay passed");
	}

	/** Isolates liquid projection from the sealed-gas spring for the reported two-pour trajectory. */
	private static void controllerPourSequenceWithoutGasSettles() {
		FlipFluidDomain domain = new FlipFluidDomain(8, 8, 8, productionConfig(), 4_096);
		domain.sealDomainBoundary();
		bindEmptyMaterialState(domain, new FluidMaterialRegistry(BuiltinFluidMaterials.DEFAULT_PALETTE));
		FlipFluidDomain.VolumeAddition first = domain.addVolumeNearBudgeted(5, 0, 4, 128.0F, 6);
		require(first.addedVolume() == 128.0F && !first.markerBudgetLimited(),
				"gas-free control did not inject the first 16 BV source: " + first);
		for (int step = 0; step < 40; step++) domain.stepWithMechanicalAudit(false, 0);
		FlipFluidDomain.VolumeAddition second = domain.addVolumeNearBudgeted(3, 3, 5, 112.0F, 6);
		require(second.addedVolume() == 112.0F && !second.markerBudgetLimited(),
				"gas-free control did not inject the second 14 BV source: " + second);
		double expectedVolume = domain.totalVolumeExact();
		double expectedMass = domain.totalMass();
		double maximumKinetic = 0.0, tailMaximumKinetic = 0.0, finalKinetic = 0.0;
		double cumulativePressureWork = 0.0;
		for (int step = 0; step < 1_000; step++) {
			FlipFluidDomain.StepStats stats = domain.stepWithMechanicalAudit(false, 0);
			FlipFluidDomain.MechanicalStepAudit audit = stats.mechanicalAudit();
			finalKinetic = audit.finalParticleKineticEnergyJoules();
			cumulativePressureWork += audit.pressureProjectionWorkJoules();
			maximumKinetic = Math.max(maximumKinetic, finalKinetic);
			if (step >= 800) tailMaximumKinetic = Math.max(tailMaximumKinetic, finalKinetic);
		}
		require(domain.totalVolumeExact() == expectedVolume && domain.totalMass() == expectedMass,
				"reported two-pour replay changed exact liquid volume or mass");
		require(cumulativePressureWork < 0.0,
				"reported two-pour pressure projection injected net kinetic energy: "
						+ cumulativePressureWork + " J");
		require(tailMaximumKinetic <= 60.0 && finalKinetic <= 50.0,
				"reported two-pour replay remained self-excited: max/tail/final="
						+ maximumKinetic + "/" + tailMaximumKinetic + "/" + finalKinetic + " J");
		System.out.printf("  controller pour gas-free control: K max/tail/final="
				+ "%.9g/%.9g/%.9g J, pressure work=%+.9g J, speed=%.6g%n",
				maximumKinetic, tailMaximumKinetic, finalKinetic,
				cumulativePressureWork, domain.maximumParticleSpeed());
	}

	/** Replays the reported controller pours with the production gas/thermal state and backend handoff. */
	private static void controllerPourSequencePreservesGasInventoryAcrossBackend() throws Exception {
		RegistrySet registries = loadProductionRegistries();
		FlipFluidDomain domain = createEmptyFullyBoundController(registries);
		var initialSource = domain.gasBoundary().externalSource();
		var initialSink = domain.gasBoundary().escapedSink();
		long initialInventory = domain.gasBoundary().sealedInventoryNanos();
		require(domain.gasBoundary().nodeCount() == 1 && domain.gasBoundary().auditClosed()
				&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
				"empty production controller did not bootstrap one closed gas owner");
		FlipFluidDomain.VolumeAddition first = domain.addVolumeNearBudgeted(5, 0, 4, 128.0F, 6);
		require(first.addedVolume() == 128.0F && !first.markerBudgetLimited(),
				"production controller did not inject the first 16 BV source: " + first);

		CpuFluidSolverBackend backend = new CpuFluidSolverBackend();
		long generation = 0L;
		FluidDomainState state = domain.captureAuthoritativeState();
		require(domain.gasBoundary().externalSource().equals(initialSource)
					&& domain.gasBoundary().escapedSink().equals(initialSink)
					&& domain.gasBoundary().sealedInventoryNanos() == initialInventory,
				"capturing the first reported pour changed gas inventory: " + gasLedgerDetail(domain));
		require(Math.abs(totalGasAvailableVolume(domain) - 48.0) <= 1.0E-12,
				"first reported pour did not compress the closed headspace to 48 m3");
		for (int step = 0; step < 40; step++) {
			FluidStepCandidate candidate;
			try {
				candidate = backend.evaluate(new FluidStepRequest(
						generation, state, List.of(), false, 0, true));
			} catch (RuntimeException failure) {
				throw new IllegalStateException("first reported controller pour failed at step " + step, failure);
			}
			domain = FlipFluidDomain.restoreAuthoritativeState(candidate.state());
			require(domain.gasBoundary().externalSource().equals(initialSource)
						&& domain.gasBoundary().escapedSink().equals(initialSink)
						&& domain.gasBoundary().sealedInventoryNanos() == initialInventory
						&& domain.gasBoundary().auditClosed()
						&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
					"first reported controller pour changed gas inventory at step " + step);
			state = candidate.state();
			generation++;
		}

		FlipFluidDomain.VolumeAddition second = domain.addVolumeNearBudgeted(3, 3, 5, 112.0F, 6);
		require(second.addedVolume() == 112.0F && !second.markerBudgetLimited(),
				"production controller did not inject the second 14 BV source: " + second);
		state = domain.captureAuthoritativeState();
		require(domain.gasBoundary().externalSource().equals(initialSource)
					&& domain.gasBoundary().escapedSink().equals(initialSink)
					&& domain.gasBoundary().sealedInventoryNanos() == initialInventory,
				"capturing the second reported pour changed gas inventory: " + gasLedgerDetail(domain));
		require(Math.abs(totalGasAvailableVolume(domain) - 34.0) <= 1.0E-12,
				"second reported pour did not compress the closed headspace to 34 m3");
		for (int step = 0; step < 1_000; step++) {
			FluidStepCandidate candidate;
			try {
				candidate = backend.evaluate(new FluidStepRequest(
						generation, state, List.of(), false, 0, true));
			} catch (RuntimeException failure) {
				FlipFluidDomain diagnostic = FlipFluidDomain.restoreAuthoritativeState(state);
				throw new IllegalStateException("second reported controller pour failed at step " + step
						+ "; input " + gasLedgerDetail(diagnostic), failure);
			}
			domain = FlipFluidDomain.restoreAuthoritativeState(candidate.state());
			require(domain.gasBoundary().externalSource().equals(initialSource)
						&& domain.gasBoundary().escapedSink().equals(initialSink)
						&& domain.gasBoundary().sealedInventoryNanos() == initialInventory
						&& domain.gasBoundary().auditClosed()
						&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
					"second reported controller pour changed gas inventory at step " + step
							+ ": " + gasLedgerDetail(domain));
			state = candidate.state();
			generation++;
		}
		double finalAvailable = totalGasAvailableVolume(domain);
		double minimumGasPressure = Double.POSITIVE_INFINITY;
		int gasBearingNodes = 0;
		for (int node : domain.gasBoundary().nodeIds()) {
			if (domain.gasBoundary().inventory(node).isEmpty()) continue;
			gasBearingNodes++;
			double temperature = domain.gasNodeTemperatureK(node);
			double expectedPressure = AirNetwork.REFERENCE_PRESSURE
					* domain.gasBoundary().standardVolume(node) / domain.gasBoundary().availableVolume(node)
					* temperature / GasRegistry.STANDARD_TEMPERATURE_K - AirNetwork.REFERENCE_PRESSURE;
			double pressure = domain.gasNodeGaugePressurePa(node);
			require(Math.abs(pressure - expectedPressure) <= Math.max(1.0E-8, Math.abs(expectedPressure) * 1.0E-12),
					"compressed controller pressure did not match inventory/volume/temperature EoS");
			minimumGasPressure = Math.min(minimumGasPressure, pressure);
		}
		require(gasBearingNodes > 0 && minimumGasPressure > 0.0
				&& Math.abs(finalAvailable - 34.0) <= 1.0E-12,
				"settled controller did not retain a positively pressurized 34 m3 headspace");
		System.out.printf("  controller pour gas ledger: %.9f m3std in %.9f m3, "
				+ "minimum gauge pressure=%+.3f Pa across %d backend candidates%n",
				domain.gasBoundary().sealedInventoryNanos() * 1.0E-9, finalAvailable,
				minimumGasPressure, generation);
	}

	private static double totalGasAvailableVolume(FlipFluidDomain domain) {
		double result = 0.0;
		for (int node : domain.gasBoundary().nodeIds()) result += domain.gasBoundary().availableVolume(node);
		return result;
	}

	/** Replays the thermal-off world path: restore 34 m3std, empty liquid, then pour at the boundary. */
	public static void controllerEmptyThenBoundaryPourPreservesGasAcrossBackend() throws Exception {
		RegistrySet registries = loadProductionRegistries();
		FlipFluidDomain domain = new FlipFluidDomain(8, 8, 8, productionConfig(), 4_096);
		domain.sealDomainBoundary();
		bindEmptyMaterialState(domain, registries.liquids());
		FlipFluidDomain.VolumeAddition first = domain.addVolumeNearBudgeted(5, 0, 4, 128.0F, 6);
		FlipFluidDomain.VolumeAddition second = domain.addVolumeNearBudgeted(3, 3, 5, 112.0F, 6);
		require(first.addedVolume() == 128.0F && second.addedVolume() == 112.0F,
				"thermal-off restore fixture did not establish 30 BV before gas bootstrap");
		domain.bindGasRegistry(registries.gas());
		domain.bootstrapGasState("");
		long retainedInventory = domain.gasBoundary().sealedInventoryNanos();
		require(retainedInventory == 34_000_000_000L && domain.gasBoundary().auditClosed(),
				"thermal-off restore fixture did not bootstrap the reported 34 m3std inventory");
		var source = domain.gasBoundary().externalSource();
		var sink = domain.gasBoundary().escapedSink();
		domain.commitOperatorClear(domain.planOperatorClear());
		require(domain.totalVolumeExact() == 0.0F
				&& Math.abs(totalGasAvailableVolume(domain) - 64.0) <= 1.0E-12
				&& domain.gasBoundary().sealedInventoryNanos() == retainedInventory,
				"operator empty did not retain and expand the finite closed gas inventory");

		FlipFluidDomain.VolumeAddition boundaryPour = domain.addVolumeNearBudgeted(6, 0, 0, 128.0F, 6);
		require(boundaryPour.addedVolume() == 128.0F && !boundaryPour.markerBudgetLimited(),
				"thermal-off boundary pour did not inject the complete 16 BV source");
		CpuFluidSolverBackend backend = new CpuFluidSolverBackend();
		FluidDomainState state = domain.captureAuthoritativeState();
		long generation = 0L;
		int maximumNodes = domain.gasBoundary().nodeCount();
		for (int step = 0; step < 200; step++) {
			FluidStepCandidate candidate;
			try {
				candidate = backend.evaluate(new FluidStepRequest(
						generation, state, List.of(), false, 0, true));
			} catch (RuntimeException failure) {
				throw new IllegalStateException("thermal-off empty/boundary-pour replay failed at step " + step,
						failure);
			}
			domain = FlipFluidDomain.restoreAuthoritativeState(candidate.state());
			maximumNodes = Math.max(maximumNodes, domain.gasBoundary().nodeCount());
			require(domain.gasBoundary().sealedInventoryNanos() == retainedInventory
						&& domain.gasBoundary().externalSource().equals(source)
						&& domain.gasBoundary().escapedSink().equals(sink)
						&& domain.gasBoundary().auditClosed()
						&& Math.abs(totalGasAvailableVolume(domain) - 48.0) <= 1.0E-12,
					"thermal-off empty/boundary-pour replay changed gas truth at step " + step
							+ ": " + gasLedgerDetail(domain));
			state = candidate.state();
			generation++;
		}
		System.out.printf("  thermal-off empty/boundary-pour gas lineage: %.9f m3std in %.9f m3, "
				+ "maxNodes=%d across %d backend candidates%n",
				retainedInventory * 1.0E-9, totalGasAvailableVolume(domain), maximumNodes, generation);
	}

	/**
	 * Replays the reported two-pour controller trajectory under the default feature gates, where
	 * the closed headspace is isothermal and bootstrap completes on the empty shell.
	 *
	 * <p>The headspace must stay a single sealed owner throughout. A marker-free cell enclosed by
	 * marker-bearing neighbours is an unsampled liquid cell, not a resolved cavity; treating it as
	 * one splits off a pocket that carries a share of the headspace inventory and is then crushed
	 * to zero capacity by the next advection, which pauses the region for good.</p>
	 */
	public static void controllerPoursKeepSingleSealedGasOwner() {
		FlipFluidDomain domain = new FlipFluidDomain(8, 8, 8, productionConfig(), 4_096);
		domain.sealDomainBoundary();
		bindEmptyMaterialState(domain, new FluidMaterialRegistry(BuiltinFluidMaterials.DEFAULT_PALETTE));
		domain.step(false, 0);
		long inventory = domain.gasBoundary().sealedInventoryNanos();
		require(inventory == 64_000_000_000L && domain.gasBoundary().nodeCount() == 1,
				"empty default-gate controller did not bootstrap one closed gas owner: "
						+ gasLedgerDetail(domain));

		FlipFluidDomain.VolumeAddition first = domain.addVolumeNearBudgeted(2, 0, 0, 128.0F, 6);
		require(first.addedVolume() == 128.0F && !first.markerBudgetLimited(),
				"reported first pour did not inject the complete 16 BV source: " + first);
		for (int step = 0; step < 60; step++) domain.step(false, 0);
		require(Math.abs(totalGasAvailableVolume(domain) - 48.0) <= 1.0E-12,
				"first reported pour did not compress the closed headspace to 48 m3");

		FlipFluidDomain.VolumeAddition second = domain.addVolumeNearBudgeted(5, 3, 5, 112.0F, 6);
		require(second.addedVolume() == 112.0F && !second.markerBudgetLimited(),
				"reported second pour did not inject the complete 14 BV source: " + second);
		int maximumNodes = domain.gasBoundary().nodeCount();
		for (int step = 0; step < 200; step++) {
			domain.step(false, 0);
			maximumNodes = Math.max(maximumNodes, domain.gasBoundary().nodeCount());
			require(domain.gasBoundary().auditClosed(),
					"reported two-pour replay opened the gas ledger at step " + step
							+ ": " + gasLedgerDetail(domain));
		}
		require(domain.gasBoundary().nodeCount() == 1,
				"reported two-pour replay left the closed headspace split across "
						+ domain.gasBoundary().nodeCount() + " owners, peak " + maximumNodes
						+ ": " + gasLedgerDetail(domain));
		require(domain.gasBoundary().sealedInventoryNanos() == inventory,
				"reported two-pour replay changed sealed gas inventory: " + gasLedgerDetail(domain));
		require(Math.abs(totalGasAvailableVolume(domain) - 34.0) <= 1.0E-12,
				"settled controller did not retain a 34 m3 headspace");
		System.out.printf("  controller two-pour headspace: %.9f m3std in %.9f m3, peakNodes=%d%n",
				inventory * 1.0E-9, totalGasAvailableVolume(domain), maximumNodes);
	}

	private static String gasLedgerDetail(FlipFluidDomain domain) {
		StringBuilder nodes = new StringBuilder();
		for (int node : domain.gasBoundary().nodeIds()) nodes.append(" node").append(node)
				.append('=').append(domain.gasBoundary().inventory(node));
		return "nodes=" + domain.gasBoundary().nodeCount()
				+ " sealed=" + domain.gasBoundary().sealedInventoryNanos()
				+ " source=" + domain.gasBoundary().externalSource().standardVolumeNanos()
				+ "/" + domain.gasBoundary().externalSource().entries()
				+ " sink=" + domain.gasBoundary().escapedSink().standardVolumeNanos()
				+ "/" + domain.gasBoundary().escapedSink().entries()
				+ " massRoundoff=" + domain.gasBoundary().massRoundoff()
				+ " residualRoundoff=" + domain.gasBoundary().residualRoundoff()
				+ " audit=" + domain.gasBoundary().auditClosed() + nodes;
	}

	public static void dynamicPlayerFloatRemainsBoundedAcrossBackend() throws Exception {
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
		RegistrySet registries = loadProductionRegistries();
		FlipFluidDomain domain = createFullyBoundPlanarHalfFill(registries);
		double bodyMinimumX = initialBodyMinimumX;
		double bodyMinimumZ = initialBodyMinimumZ;
		double bodyMinimumY = bodyBottomForSubmergence(
				domain, bodyMinimumX, bodyMinimumZ, bodyWidth, bodyHeight,
				MinecraftEntityFluidInteraction.NEUTRAL_SUBMERGENCE);
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
			FlipFluidDomain.BodyFluidSample sample = domain.sampleAxisAlignedBody(
					bodyMinimumX, bodyMinimumY, bodyMinimumZ,
					bodyMinimumX + bodyWidth, bodyMinimumY + bodyHeight, bodyMinimumZ + bodyWidth);
			double submergedFraction = sample.displacedVolumeCubicMetres() / bodyVolume;
			require(submergedFraction > 0.0 && submergedFraction <= 1.0 + 1.0E-9,
					"dynamic player left the finite wet AABB observation at tick " + tick
							+ ": submergence=" + submergedFraction);
			bodyVelocity = MinecraftEntityFluidInteraction.responseVelocity(
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
			domain = FlipFluidDomain.restoreAuthoritativeState(candidate.state());
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

	private static double bodyBottomForSubmergence(
			FlipFluidDomain domain, double minimumX, double minimumZ,
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

	private static void stationaryPlayerSizedBodyDoesNotExcitePlanarSurface() throws Exception {
		RegistrySet registries = loadProductionRegistries();
		FlipFluidDomain initial = createFullyBoundPlanarHalfFill(registries);
		FluidDomainState state = initial.captureAuthoritativeState();
		CpuFluidSolverBackend backend = new CpuFluidSolverBackend();
		MovingBodyFluidCoupling.Request body = new MovingBodyFluidCoupling.Request(
				2.0F, 1.60F, 2.0F, 0.0, 0.0, 0.0, 0.6 * 1.8 * 0.6 * 0.72);
		double initialCenterY = centerOfMassY(initial.exportMaterialParticleState());
		double maximumKinetic = 0.0, accumulatedLiquidY = 0.0, accumulatedReactionY = 0.0;
		FlipFluidDomain restored = initial;
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
			restored = FlipFluidDomain.restoreAuthoritativeState(candidate.state());
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
	public static void walkingBodyDisturbsThenSettlesAcrossBackend() throws Exception {
		final double bodyWidth = 0.60;
		final double bodyHeight = 1.80;
		final double bodyVolume = bodyWidth * bodyHeight * bodyWidth;
		final double walkingMetresPerSecond = 4.3;
		final int walkingTicks = 8;
		final int settleTicks = 400;
		RegistrySet registries = loadProductionRegistries();
		FlipFluidDomain initial = createFullyBoundPlanarHalfFill(registries);
		double bodyMinimumX = 0.50;
		double bodyMinimumZ = 1.70;
		double bodyMinimumY = bodyBottomForSubmergence(
				initial, bodyMinimumX, bodyMinimumZ, bodyWidth, bodyHeight,
				MinecraftEntityFluidInteraction.NEUTRAL_SUBMERGENCE);
		double perTick = walkingMetresPerSecond / 20.0;
		// Added-mass scale of the immersed body: the disturbance floor is a small fraction of it, so the
		// gate rejects a coupling that is silently zero without pinning the exact physical response.
		double addedMassJoules = 0.5 * productionConfig().density() * bodyVolume
				* MinecraftEntityFluidInteraction.NEUTRAL_SUBMERGENCE
				* walkingMetresPerSecond * walkingMetresPerSecond;
		FluidDomainState state = initial.captureAuthoritativeState();
		CpuFluidSolverBackend backend = new CpuFluidSolverBackend();
		long generation = 0L;
		double maximumKinetic = 0.0, tailMaximumKinetic = 0.0, peakImpulseX = 0.0;
		double maximumStepMillis = 0.0;
		FlipFluidDomain restored = initial;
		for (int tick = 1; tick <= walkingTicks + settleTicks; tick++) {
			boolean walking = tick <= walkingTicks;
			FlipFluidDomain.BodyFluidSample sample = restored.sampleAxisAlignedBody(
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
			restored = FlipFluidDomain.restoreAuthoritativeState(candidate.state());
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

	private static void compressedPlanarHalfFillRemainsStableAcrossBackend() throws Exception {
		RegistrySet registries = loadProductionRegistries();
		FlipFluidDomain initial = createFullyBoundCompressedPlanarHalfFill(registries);
		FluidDomainState state = initial.captureAuthoritativeState();
		FlipFluidDomain restored = FlipFluidDomain.restoreAuthoritativeState(state);
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
			FlipFluidDomain.StepStats stats = candidate.stats();
			FlipFluidDomain.MechanicalStepAudit audit = stats.mechanicalAudit();
			restored = FlipFluidDomain.restoreAuthoritativeState(candidate.state());
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

	public static void defaultLiveStepsRemainBounded() {
		FlipFluidDomain warmup = createPouredControllerDomain();
		long warmupStart = System.nanoTime();
		CpuFluidSolverBackend warmupBackend = new CpuFluidSolverBackend();
		FluidDomainState warmupState = warmup.captureAuthoritativeState();
		for (int generation = 0; generation < 10; generation++) {
			FluidStepCandidate warmupCandidate = warmupBackend.evaluate(
					new FluidStepRequest(generation, warmupState, List.of(), false, 0));
			warmup = FlipFluidDomain.restoreAuthoritativeState(warmupCandidate.state());
			warmupState = warmupCandidate.state();
		}
		double warmupMilliseconds = (System.nanoTime() - warmupStart) / 1_000_000.0;
		System.out.printf("  production live JVM warmup: %.3f ms for 10 production-shaped steps "
				+ "(reported separately from frame budget)%n", warmupMilliseconds);

		FlipFluidDomain domain = createPouredControllerDomain();
		double volume = domain.totalVolumeExact();
		double mass = domain.totalMass();
		ArrayList<Double> elapsed = new ArrayList<>(REPLAY_STEPS);
		CpuFluidSolverBackend backend = new CpuFluidSolverBackend();
		FluidDomainState state = domain.captureAuthoritativeState();
		long generation = 0L;
		for (int step = 0; step < REPLAY_STEPS; step++) {
			long start = System.nanoTime();
			FlipFluidDomain.StepStats stats;
			try {
				// The live budget has to cover what a production motion step actually does: capture the
				// authoritative state, evaluate a complete candidate, then restore it as the new owner.
				FluidStepCandidate candidate = backend.evaluate(
						new FluidStepRequest(generation, state, List.of(), false, 0));
				domain = FlipFluidDomain.restoreAuthoritativeState(candidate.state());
				stats = candidate.stats();
				state = candidate.state();
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
	}

	private static void fullyBoundHalfFillRoundTripsCurrentGasGeometry() throws Exception {
		RegistrySet registries = loadProductionRegistries();
		FlipFluidDomain committed = createFullyBoundHalfFilledDomain(registries);
		FluidDomainState captured = committed.captureAuthoritativeState();
		FlipFluidDomain restored = FlipFluidDomain.restoreAuthoritativeState(captured);
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
		FlipFluidDomain evaluated = FlipFluidDomain.restoreAuthoritativeState(candidate.state());
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

	private static void fullyBoundPhaseOnStepRemainsBounded() throws Exception {
		RegistrySet registries = loadProductionRegistries();
		FlipFluidDomain phaseOff = createFullyBoundHalfFilledDomain(registries);
		long phaseOffStart = System.nanoTime();
		phaseOff.step(false, 0);
		System.out.printf("  full-capability phase-off direct step: %.3f ms%n",
				(System.nanoTime() - phaseOffStart) / 1_000_000.0);
		FlipFluidDomain warmup = createFullyBoundHalfFilledDomain(registries);
		warmup.step(true, ThermalLimits.MAX_PHASE_OPERATIONS_PER_STEP);

		FlipFluidDomain domain = createFullyBoundHalfFilledDomain(registries);
		long start = System.nanoTime();
		FlipFluidDomain.StepStats stats = domain.step(true, ThermalLimits.MAX_PHASE_OPERATIONS_PER_STEP);
		double milliseconds = (System.nanoTime() - start) / 1_000_000.0;
		FlipFluidDomain.GasThermalPhaseStats phase = domain.lastGasThermalPhaseStats();
		FluidDomainState committed = domain.captureAuthoritativeState();
		FlipFluidDomain restored = FlipFluidDomain.restoreAuthoritativeState(committed);
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

	private static void planarHalfFillWithoutEntitiesRemainsStableAcrossBackend() throws Exception {
		RegistrySet registries = loadProductionRegistries();
		FlipFluidDomain initial = createFullyBoundPlanarHalfFill(registries);
		FluidDomainState state = initial.captureAuthoritativeState();
		CpuFluidSolverBackend backend = new CpuFluidSolverBackend();
		long generation = 0L;
		double initialKinetic = kineticEnergy(initial.exportMaterialParticleState());
		require(initialKinetic == 0.0, "planar no-entity fixture did not start at rest");
		double initialCenterY = centerOfMassY(initial.exportMaterialParticleState());
		double maximumKinetic = 0.0, tailMaximumKinetic = 0.0;
		double maximumBodyPressureResidual = 0.0;
		long phaseOperations = 0L, boiledCells = 0L, condensedCells = 0L;
		FlipFluidDomain restored = initial;
		for (int step = 0; step < 500; step++) {
			FluidStepCandidate candidate = backend.evaluate(new FluidStepRequest(
					generation, state, List.of(), false, 0, true));
			require(candidate.movingBodyCoupling().equals(MovingBodyFluidCoupling.Result.NONE),
					"planar no-entity backend reported a liquid moving-body impulse at step " + step);
			require(candidate.baseGeneration() == generation
					&& candidate.candidateGeneration() == generation + 1L,
					"planar no-entity backend generation was not adjacent at step " + step);
			FlipFluidDomain.StepStats stats = candidate.stats();
			FlipFluidDomain.MechanicalStepAudit audit = stats.mechanicalAudit();
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

			restored = FlipFluidDomain.restoreAuthoritativeState(candidate.state());
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

	private static FlipFluidDomain createPouredControllerDomain() {
		FlipFluidDomain domain = new FlipFluidDomain(8, 8, 8, productionConfig(), 4_096);
		domain.sealDomainBoundary();
		bindEmptyMaterialState(domain, new FluidMaterialRegistry(BuiltinFluidMaterials.DEFAULT_PALETTE));
		FlipFluidDomain.VolumeAddition addition = domain.addVolumeNearBudgeted(3, 0, 0, 128.0F, 6);
		require(addition.addedVolume() == 128.0F && !addition.markerBudgetLimited(),
				"fixture did not inject the complete 16 BV source: " + addition);
		return domain;
	}

	static FlipFluidDomain createFullyBoundHalfFilledDomain(RegistrySet registries) {
		FlipFluidDomain domain = createEmptyFullyBoundController(registries);
		int[][] sources = {{3, 0, 0}, {4, 7, 7}};
		for (int pour = 0; pour < sources.length; pour++) {
			int[] source = sources[pour];
			FlipFluidDomain.VolumeAddition addition = domain.addVolumeNearBudgeted(
					source[0], source[1], source[2], 128.0F, 6);
			require(addition.addedVolume() == 128.0F && !addition.markerBudgetLimited(),
					"full-capability fixture did not inject pour " + (pour + 1) + ": " + addition);
		}
		return domain;
	}

	static FlipFluidDomain createEmptyFullyBoundController(RegistrySet registries) {
		FlipFluidDomain domain = new FlipFluidDomain(8, 8, 8, productionConfig(), 4_096);
		domain.sealDomainBoundary();
		bindEmptyMaterialState(domain, registries.liquids());
		FluidMaterialRegistry runtimeLiquids = domain.materialRegistry();
		domain.bindChemistry(registries.chemical());
		domain.bindGranular(registries.granular(), runtimeLiquids);
		domain.setPrecipitationEnabled(true);
		domain.bindGasRegistry(registries.gas());
		domain.bindThermal(registries.thermal(), runtimeLiquids,
				GasRegistry.STANDARD_TEMPERATURE_K, ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS);
		return domain;
	}

	static FlipFluidDomain createFullyBoundPlanarHalfFill(RegistrySet registries) {
		FlipFluidDomain domain = new FlipFluidDomain(8, 8, 8, productionConfig(), 4_096);
		domain.sealDomainBoundary();
		bindEmptyMaterialState(domain, registries.liquids());
		FluidMaterialRegistry runtimeLiquids = domain.materialRegistry();
		for (int y = 0; y < 4; y++) for (int z = 0; z < 8; z++) for (int x = 0; x < 8; x++) {
			domain.setVolume(x, y, z, 1.0F);
		}
		domain.bindChemistry(registries.chemical());
		domain.bindGranular(registries.granular(), runtimeLiquids);
		domain.setPrecipitationEnabled(true);
		domain.bindGasRegistry(registries.gas());
		domain.bindThermal(registries.thermal(), runtimeLiquids,
				GasRegistry.STANDARD_TEMPERATURE_K, ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS);
		return domain;
	}

	private static FlipFluidDomain createFullyBoundCompressedPlanarHalfFill(RegistrySet registries) {
		FlipFluidDomain domain = new FlipFluidDomain(8, 8, 8, productionConfig(), 4_096);
		domain.sealDomainBoundary();
		bindEmptyMaterialState(domain, registries.liquids());
		FluidMaterialRegistry runtimeLiquids = domain.materialRegistry();
		domain.bindChemistry(registries.chemical());
		domain.bindGranular(registries.granular(), runtimeLiquids);
		domain.setPrecipitationEnabled(true);
		domain.bindGasRegistry(registries.gas());
		domain.bindThermal(registries.thermal(), runtimeLiquids,
				GasRegistry.STANDARD_TEMPERATURE_K, ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS);
		for (int y = 0; y < 4; y++) for (int z = 0; z < 8; z++) for (int x = 0; x < 8; x++) {
			domain.setVolume(x, y, z, 1.0F);
		}
		return domain;
	}

	private static double kineticEnergy(FlipMaterialParticleState state) {
		double[] mass = state.copyParticleMass();
		float[] velocity = state.copyParticleVelocities();
		double result = 0.0;
		for (int marker = 0; marker < mass.length; marker++) {
			int base = marker * 3;
			result += 0.5 * mass[marker] * (velocity[base] * velocity[base]
					+ velocity[base + 1] * velocity[base + 1]
					+ velocity[base + 2] * velocity[base + 2]);
		}
		return result * Math.pow(state.cellSize(), 3.0);
	}

	private static double centerOfMassY(FlipMaterialParticleState state) {
		double[] mass = state.copyParticleMass();
		float[] positionVolume = state.copyPositionVolume();
		double weighted = 0.0, total = 0.0;
		for (int marker = 0; marker < mass.length; marker++) {
			weighted += mass[marker] * positionVolume[marker * 4 + 1];
			total += mass[marker];
		}
		return weighted / total;
	}

	private static FluidSolverConfig productionConfig() {
		return new FluidSolverConfig(0.5F, 1_000.0F, 9.81F, 1.0F / 20.0F,
				120, 1.0E-4F, 0.40F, 2.0E-3F, 0.80F);
	}

	private static void bindEmptyMaterialState(FlipFluidDomain domain, FluidMaterialRegistry liquids) {
		FlipParticleState empty = domain.exportParticleState();
		domain.restoreMaterialParticleState(new FlipMaterialParticleState(
				empty.sizeX(), empty.sizeY(), empty.sizeZ(), empty.cellSize(), 0, 0.0, 0.0, liquids.materials(),
				empty.copyPositionVolume(), empty.copyParticleVelocities(), empty.copySolidMask(),
				new double[0], new int[0]));
	}

	static RegistrySet loadProductionRegistries() throws Exception {
		ProductionRegistries.Set registries = ProductionRegistries.load();
		return new RegistrySet(new FluidMaterialRegistry(BuiltinFluidMaterials.DEFAULT_PALETTE),
				registries.chemical(), registries.granular(), registries.gas(), registries.thermal());
	}

	private static void requireSameGasState(GasStateSnapshot expected, GasStateSnapshot actual) {
		require(expected != null && actual != null
				&& expected.registryFingerprint().equals(actual.registryFingerprint())
				&& expected.chemicalRegistryFingerprint().equals(actual.chemicalRegistryFingerprint())
				&& expected.nodes().equals(actual.nodes())
				&& Arrays.equals(expected.cellNodeOrdinal(), actual.cellNodeOrdinal())
				&& expected.externalSource().equals(actual.externalSource())
				&& expected.escapedSink().equals(actual.escapedSink())
				&& expected.massRoundoff().equals(actual.massRoundoff())
				&& expected.residualRoundoff().equals(actual.residualRoundoff()),
				"full-capability gas topology or inventory changed across capture/restore");
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}

	record RegistrySet(
			FluidMaterialRegistry liquids, ChemicalRegistry chemical, GranularRegistry granular,
			GasRegistry gas, ThermalRegistry thermal
	) { }
}

package cc.ecisr.mmm.simulation;

import cc.ecisr.mmm.chemistry.ChemicalDataPackLoader;
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
import cc.ecisr.mmm.fluid.air.BuiltinGasDefinitions;
import cc.ecisr.mmm.fluid.air.GasRegistry;
import cc.ecisr.mmm.fluid.air.GasStateSnapshot;
import cc.ecisr.mmm.granular.GranularDataPackLoader;
import cc.ecisr.mmm.granular.GranularRegistry;
import cc.ecisr.mmm.thermal.ThermalDataPackLoader;
import cc.ecisr.mmm.thermal.ThermalLimits;
import cc.ecisr.mmm.thermal.ThermalRegistry;
import cc.ecisr.mmm.thermal.ThermalTransport;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;

import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** Replays controller injection through the default live step and the complete authoritative handoff. */
public final class ProductionFluidReplayValidation {
	private static final int REPLAY_STEPS = 20;
	private static final double STEP_BUDGET_MILLIS = 40.0;

	private ProductionFluidReplayValidation() { }

	public static void main(String[] args) throws Exception {
		defaultLiveStepsRemainBounded();
		fullyBoundHalfFillRoundTripsCurrentGasGeometry();
		fullyBoundPhaseOnStepRemainsBounded();
		planarHalfFillWithoutEntitiesRemainsStableAcrossBackend();
		compressedPlanarHalfFillRemainsStableAcrossBackend();
		System.out.println("Production fluid replay passed");
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
		double previousAvailable = restored.airNetwork().availableVolume(1);
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
			require(candidate.baseGeneration() == generation
					&& candidate.candidateGeneration() == generation + 1L,
					"compressed planar backend generation was not adjacent at step " + step);
			FlipFluidDomain.StepStats stats = candidate.stats();
			FlipFluidDomain.MechanicalStepAudit audit = stats.mechanicalAudit();
			restored = FlipFluidDomain.restoreAuthoritativeState(candidate.state());
			double kinetic = audit.finalParticleKineticEnergyJoules();
			double available = restored.airNetwork().availableVolume(1);
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
			require(audit.captured() && restored.airNetwork().auditClosed()
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

	private static void defaultLiveStepsRemainBounded() {
		FlipFluidDomain warmup = createPouredControllerDomain();
		long warmupStart = System.nanoTime();
		warmup.step(false, 0);
		double warmupMilliseconds = (System.nanoTime() - warmupStart) / 1_000_000.0;
		System.out.printf("  production live JVM warmup: %.3f ms (reported separately from frame budget)%n",
				warmupMilliseconds);

		FlipFluidDomain domain = createPouredControllerDomain();
		double volume = domain.totalVolumeExact();
		double mass = domain.totalMass();
		ArrayList<Double> elapsed = new ArrayList<>(REPLAY_STEPS);
		for (int step = 0; step < REPLAY_STEPS; step++) {
			long start = System.nanoTime();
			FlipFluidDomain.StepStats stats;
			try {
				stats = domain.step(false, 0);
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
		require(restored.airNetwork().auditClosed(), "restored full-capability gas ledger is open");
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
		require(evaluated.airNetwork().auditClosed()
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
		require(domain.airNetwork().auditClosed()
				&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
				"full-capability phase-on step did not close gas or total U audit");
		require(committed.pendingPhaseVolume().entries().equals(
					restored.exportPendingPhaseVolumeState().entries())
				&& restored.airNetwork().auditClosed()
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
			require(restored.airNetwork().nodeCount() == 1 && restored.airNetwork().auditClosed(),
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

	private static FlipFluidDomain createFullyBoundHalfFilledDomain(RegistrySet registries) {
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

	private static FlipFluidDomain createFullyBoundPlanarHalfFill(RegistrySet registries) {
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

	private static RegistrySet loadProductionRegistries() throws Exception {
		Path root = productionResourceRoot();
		PackLocationInfo location = new PackLocationInfo("mmm-production-replay",
				Component.literal("MMM production replay"), PackSource.BUILT_IN, Optional.empty());
		PackResources pack = new PathPackResources(location, root);
		try (MultiPackResourceManager resources = new MultiPackResourceManager(PackType.SERVER_DATA, List.of(pack))) {
			FluidMaterialRegistry liquids = new FluidMaterialRegistry(BuiltinFluidMaterials.DEFAULT_PALETTE);
			ChemicalRegistry chemical = ChemicalDataPackLoader.load(resources);
			GranularRegistry granular = GranularDataPackLoader.load(resources, chemical);
			GasRegistry gas = BuiltinGasDefinitions.createRegistry();
			ThermalRegistry thermal = ThermalDataPackLoader.load(resources, chemical.fingerprint(),
					granular.fingerprint(), gas.fingerprint());
			return new RegistrySet(liquids, chemical, granular, gas, thermal);
		}
	}

	private static Path productionResourceRoot() throws URISyntaxException {
		URL water = ProductionFluidReplayValidation.class.getResource(
				"/data/mmm/mmm/thermal_materials/water.json");
		if (water == null || !water.getProtocol().equals("file")) {
			throw new AssertionError("Production datapack resources are not available as a validation directory");
		}
		Path cursor = Path.of(water.toURI()).toAbsolutePath().normalize();
		while (cursor != null && !Files.isDirectory(cursor.resolve("data"))) cursor = cursor.getParent();
		if (cursor == null) throw new AssertionError("Could not resolve the production datapack root");
		return cursor;
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

	private record RegistrySet(
			FluidMaterialRegistry liquids, ChemicalRegistry chemical, GranularRegistry granular,
			GasRegistry gas, ThermalRegistry thermal
	) { }
}

package cc.ecisr.mmm.simulation;

import cc.ecisr.mmm.chemistry.ChemicalDataPackLoader;
import cc.ecisr.mmm.chemistry.ChemicalRegistry;
import cc.ecisr.mmm.fluid.BuiltinFluidMaterials;
import cc.ecisr.mmm.fluid.FluidDomainState;
import cc.ecisr.mmm.fluid.FlipFluidDomain;
import cc.ecisr.mmm.fluid.FlipMaterialParticleState;
import cc.ecisr.mmm.fluid.FlipParticleState;
import cc.ecisr.mmm.fluid.FluidMaterialRegistry;
import cc.ecisr.mmm.fluid.FluidSolverConfig;
import cc.ecisr.mmm.fluid.air.BuiltinGasDefinitions;
import cc.ecisr.mmm.fluid.air.GasRegistry;
import cc.ecisr.mmm.fluid.air.GasStateSnapshot;
import cc.ecisr.mmm.granular.GranularDataPackLoader;
import cc.ecisr.mmm.granular.GranularRegistry;
import cc.ecisr.mmm.thermal.ThermalDataPackLoader;
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
		System.out.println("Production fluid replay passed");
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
					granular.fingerprint());
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

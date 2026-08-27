package cc.ecisr.mmm.simulation;

import cc.ecisr.mmm.chemistry.ChemicalDataPackLoader;
import cc.ecisr.mmm.chemistry.ChemicalRegistry;
import cc.ecisr.mmm.fluid.BuiltinFluidMaterials;
import cc.ecisr.mmm.fluid.FlipFluidDomain;
import cc.ecisr.mmm.fluid.FlipMaterialParticleState;
import cc.ecisr.mmm.fluid.FlipParticleState;
import cc.ecisr.mmm.fluid.FluidMaterialRegistry;
import cc.ecisr.mmm.fluid.FluidSolverConfig;
import cc.ecisr.mmm.fluid.air.BuiltinGasDefinitions;
import cc.ecisr.mmm.fluid.air.GasRegistry;
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
import java.util.List;
import java.util.Optional;

/** Replays controllerdemo -> pour 16 through the production definitions and step boundary of this commit. */
public final class ProductionFluidReplayValidation {
	private static final int REPLAY_STEPS = 20;
	private static final double STEP_BUDGET_MILLIS = 40.0;

	private ProductionFluidReplayValidation() { }

	public static void main(String[] args) throws Exception {
		RegistrySet registries = loadProductionRegistries();
		FlipFluidDomain domain = createPouredControllerDomain(registries);
		ArrayList<Double> elapsed = new ArrayList<>(REPLAY_STEPS);
		for (int step = 0; step < REPLAY_STEPS; step++) {
			long start = System.nanoTime();
			FlipFluidDomain.StepStats stats;
			try {
				stats = domain.step(true, ThermalLimits.MAX_PHASE_OPERATIONS_PER_STEP);
			} catch (RuntimeException failure) {
				throw new AssertionError("production live-domain replay failed at step " + step
						+ ": " + failure.getMessage(), failure);
			}
			double milliseconds = (System.nanoTime() - start) / 1_000_000.0;
			elapsed.add(milliseconds);
			require(domain.airNetwork().auditClosed(), "gas ledger opened at step " + step);
			require(domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
					"total U audit opened at step " + step);
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
		System.out.println("Production fluid replay passed: 20 live-domain steps remained valid and bounded");
	}

	private static FlipFluidDomain createPouredControllerDomain(RegistrySet registries) {
		FluidSolverConfig config = new FluidSolverConfig(0.5F, 1_000.0F, 9.81F, 1.0F / 20.0F,
				120, 1.0E-4F, 0.40F, 2.0E-3F, 0.80F);
		FlipFluidDomain domain = new FlipFluidDomain(8, 8, 8, config, 4_096);
		domain.sealDomainBoundary();
		bindEmptyMaterialState(domain, registries.liquids());
		domain.bindChemistry(registries.chemical());
		domain.bindGranular(registries.granular(), registries.liquids());
		domain.setPrecipitationEnabled(true);
		domain.bindGasRegistry(registries.gas());
		domain.bindThermal(registries.thermal(), registries.liquids(),
				GasRegistry.STANDARD_TEMPERATURE_K, ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS);
		FlipFluidDomain.VolumeAddition addition = domain.addVolumeNearBudgeted(3, 0, 0, 128.0F, 6);
		require(addition.addedVolume() == 128.0F && !addition.markerBudgetLimited(),
				"fixture did not inject the complete 16 BV source: " + addition);
		return domain;
	}

	private static void bindEmptyMaterialState(FlipFluidDomain domain, FluidMaterialRegistry liquids) {
		FlipParticleState empty = domain.exportParticleState();
		domain.restoreMaterialParticleState(new FlipMaterialParticleState(
				empty.sizeX(), empty.sizeY(), empty.sizeZ(), empty.cellSize(), 0, 0.0, 0.0, liquids,
				empty.copyPositionVolume(), empty.copyParticleVelocities(), empty.copySolidMask(),
				new double[0], new int[0]));
	}

	private static RegistrySet loadProductionRegistries() throws Exception {
		Path root = productionResourceRoot();
		PackLocationInfo location = new PackLocationInfo("mmm-production-replay",
				Component.literal("MMM production replay"), PackSource.BUILT_IN, Optional.empty());
		PackResources pack = new PathPackResources(location, root);
		try (MultiPackResourceManager resources = new MultiPackResourceManager(PackType.SERVER_DATA, List.of(pack))) {
			FluidMaterialRegistry liquids = BuiltinFluidMaterials.createRegistry().runtimeRegistry();
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

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}

	private record RegistrySet(
			FluidMaterialRegistry liquids, ChemicalRegistry chemical, GranularRegistry granular,
			GasRegistry gas, ThermalRegistry thermal
	) { }
}

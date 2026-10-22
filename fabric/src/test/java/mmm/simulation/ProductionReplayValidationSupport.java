package mmm.simulation;

import mmm.fluid.BuiltinFluidMaterials;
import mmm.fluid.FluidDomain;
import mmm.fluid.FluidMaterialRegistry;
import mmm.fluid.FluidSolverConfig;
import mmm.fluid.air.GasRegistry;
import mmm.fluid.air.GasStateSnapshot;
import mmm.thermal.ThermalTransport;

import java.util.Arrays;

/** Replays controller injection through the default live step and the complete authoritative handoff. */

abstract class ProductionReplayValidationSupport {
	static final int REPLAY_STEPS = 20;
	static final int WARMUP_STEPS = 20;
	static final double STEP_BUDGET_MILLIS = 40.0;

	static FluidDomain createPouredControllerDomain() {
		FluidDomain domain = new FluidDomain(8, 8, 8, productionConfig());
		domain.sealDomainBoundary();
		FullCapabilityTestFixture.bindEmptyMaterialState(
				domain, new FluidMaterialRegistry(BuiltinFluidMaterials.DEFAULT_PALETTE));
		FluidDomain.VolumeAddition addition = domain.addVolumeNearBudgeted(3, 0, 0, 128.0F, 6);
		require(addition.addedVolume() == 128.0F && !addition.markerBudgetLimited(),
				"fixture did not inject the complete 16 m³ source: " + addition);
		return domain;
	}

	static FluidDomain createFullyBoundHalfFilledDomain(FullCapabilityTestFixture.Registries registries) {
		FluidDomain domain = createEmptyFullyBoundController(registries);
		int[][] sources = {{3, 0, 0}, {4, 7, 7}};
		for (int pour = 0; pour < sources.length; pour++) {
			int[] source = sources[pour];
			FluidDomain.VolumeAddition addition = domain.addVolumeNearBudgeted(
					source[0], source[1], source[2], 128.0F, 6);
			require(addition.addedVolume() == 128.0F && !addition.markerBudgetLimited(),
					"full-capability fixture did not inject pour " + (pour + 1) + ": " + addition);
		}
		return domain;
	}

	static FluidDomain createEmptyFullyBoundController(FullCapabilityTestFixture.Registries registries) {
		FluidDomain domain = new FluidDomain(8, 8, 8, productionConfig());
		domain.sealDomainBoundary();
		FullCapabilityTestFixture.bindEmptyMaterialState(domain, registries.liquids());
		FullCapabilityTestFixture.bindFull(domain, registries,
				GasRegistry.STANDARD_TEMPERATURE_K, ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS);
		return domain;
	}

	static FluidDomain createFullyBoundPlanarHalfFill(FullCapabilityTestFixture.Registries registries) {
		FluidDomain domain = new FluidDomain(8, 8, 8, productionConfig());
		domain.sealDomainBoundary();
		FullCapabilityTestFixture.bindEmptyMaterialState(domain, registries.liquids());
		float[] volumes=new float[domain.sizeX()*domain.sizeY()*domain.sizeZ()];
		for (int y = 0; y < 4; y++) for (int z = 0; z < 8; z++) for (int x = 0; x < 8; x++) {
			volumes[(z*domain.sizeY()+y)*domain.sizeX()+x]=1;
		}
		domain.setVolumes(volumes);
		FullCapabilityTestFixture.bindFull(domain, registries,
				GasRegistry.STANDARD_TEMPERATURE_K, ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS);
		return domain;
	}

	static FluidDomain createFullyBoundCompressedPlanarHalfFill(FullCapabilityTestFixture.Registries registries) {
		FluidDomain domain = new FluidDomain(8, 8, 8, productionConfig());
		domain.sealDomainBoundary();
		FullCapabilityTestFixture.bindEmptyMaterialState(domain, registries.liquids());
		FullCapabilityTestFixture.bindFull(domain, registries,
				GasRegistry.STANDARD_TEMPERATURE_K, ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS);
		float[] volumes=new float[domain.sizeX()*domain.sizeY()*domain.sizeZ()];
		for (int y = 0; y < 4; y++) for (int z = 0; z < 8; z++) for (int x = 0; x < 8; x++) {
			volumes[(z*domain.sizeY()+y)*domain.sizeX()+x]=1;
		}
		domain.setVolumes(volumes);
		return domain;
	}

	/** Maximum-fill solver-side shape and capability profile created by {@code /mmm demo} in empty space. */
	static FluidDomain createFullyBoundDamBreakDemo(FullCapabilityTestFixture.Registries registries) {
		FluidDomain domain = new FluidDomain(24, 16, 24, productionConfig());
		FullCapabilityTestFixture.bindEmptyMaterialState(domain, registries.liquids());
		float[] volumes=new float[domain.sizeX()*domain.sizeY()*domain.sizeZ()];
		for (int y = 0; y < 12; y++) for (int z = 0; z < 24; z++) for (int x = 0; x < 9; x++) {
			volumes[(z*domain.sizeY()+y)*domain.sizeX()+x]=1;
		}
		domain.setVolumes(volumes);
		FullCapabilityTestFixture.bindFull(domain, registries,
				GasRegistry.STANDARD_TEMPERATURE_K, ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS);
		require(domain.totalVolumeExact() == 2_592.0,
				"dam-break fixture does not match the production demo inventory");
		return domain;
	}

	/** Production {@code /mmm demo} over four full collision layers, matching the world fixture. */
	static FluidDomain createFullyBoundFloorDamBreakDemo(FullCapabilityTestFixture.Registries registries) {
		FluidDomain domain = new FluidDomain(24, 16, 24, productionConfig());
		FullCapabilityTestFixture.bindEmptyMaterialState(domain, registries.liquids());
		var floor=new java.util.ArrayList<FluidDomain.CollisionEdit>();
		for (int y = 0; y < 4; y++) for (int z = 0; z < 24; z++) for (int x = 0; x < 24; x++) {
			floor.add(new FluidDomain.CollisionEdit(x,y,z,255));
		}
		domain.beginCollisionEdits(domain.planCollisionEdits(floor));
		for(FluidDomain.CollisionEdit edit:floor) domain.setSolidMicroMask(edit.x(),edit.y(),edit.z(),edit.mask());
		float[] volumes=new float[24*16*24];
		for (int y = 0; y < 12; y++) for (int z = 0; z < 24; z++) for (int x = 0; x < 9; x++) {
			if (!domain.isSolid(x, y, z)) volumes[(z*domain.sizeY()+y)*domain.sizeX()+x]=1;
		}
		domain.setVolumes(volumes);
		FullCapabilityTestFixture.bindFull(domain, registries,
				GasRegistry.STANDARD_TEMPERATURE_K, ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS);
		require(domain.totalVolumeExact() == 1_728.0,
				"floor dam-break fixture does not match the production demo inventory");
		return domain;
	}

	static double kineticEnergy(FluidDomain domain) { return domain.liquidKineticEnergyJoules(); }

	static double centerOfMassY(FluidDomain domain) {
		double weighted=0; long mass=0;
		for (var stock:domain.exportLiquidCellState().physicalStocks()) {
			weighted+=stock.massUnits()*(stock.key().cell()/domain.sizeX()%domain.sizeY()+.5)*domain.config().cellSize();
			mass=Math.addExact(mass,stock.massUnits());
		}
		return weighted/mass;
	}

	static FluidSolverConfig productionConfig() {
		return new FluidSolverConfig(0.5F, 1_000.0F, 9.81F, 1.0F / 20.0F,
				120, 1.0E-4F, 2.0E-3F);
	}

	static FullCapabilityTestFixture.Registries loadProductionRegistries() throws Exception {
		return FullCapabilityTestFixture.load();
	}

	static void requireSameGasState(GasStateSnapshot expected, GasStateSnapshot actual) {
		require(expected != null && actual != null
				&& expected.registryFingerprint().equals(actual.registryFingerprint())
				&& expected.chemicalRegistryFingerprint().equals(actual.chemicalRegistryFingerprint())
				&& expected.nodes().equals(actual.nodes())
				&& Arrays.equals(expected.pieceNodeOrdinal(), actual.pieceNodeOrdinal())
				&& expected.externalSource().equals(actual.externalSource())
				&& expected.escapedSink().equals(actual.escapedSink()),
				"full-capability gas topology or inventory changed across capture/restore");
	}


	static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}

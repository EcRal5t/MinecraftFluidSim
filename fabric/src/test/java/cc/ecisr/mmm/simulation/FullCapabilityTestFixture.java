package cc.ecisr.mmm.simulation;

import cc.ecisr.mmm.chemistry.ChemicalRegistry;
import cc.ecisr.mmm.fluid.BuiltinFluidMaterials;
import cc.ecisr.mmm.fluid.FlipMaterialParticleState;
import cc.ecisr.mmm.fluid.FluidDomain;
import cc.ecisr.mmm.fluid.FluidMaterialRegistry;
import cc.ecisr.mmm.fluid.air.GasRegistry;
import cc.ecisr.mmm.granular.GranularRegistry;
import cc.ecisr.mmm.thermal.ThermalRegistry;

/** One authoritative production-registry and empty-carrier fixture for full-profile validations. */
public final class FullCapabilityTestFixture {
	private FullCapabilityTestFixture() { }

	public static Registries load() throws Exception {
		ProductionRegistries.Set production = ProductionRegistries.load();
		return new Registries(new FluidMaterialRegistry(BuiltinFluidMaterials.DEFAULT_PALETTE),
				production.chemical(), production.granular(), production.gas(), production.thermal());
	}

	public static FluidMaterialRegistry bindEmptyMaterialState(FluidDomain domain, FluidMaterialRegistry liquids) {
		domain.restoreMaterialParticleState(new FlipMaterialParticleState(
				domain.sizeX(), domain.sizeY(), domain.sizeZ(), domain.config().cellSize(),
				0, 0.0, 0.0, liquids.materials(), new float[0], new float[0],
				new byte[domain.sizeX() * domain.sizeY() * domain.sizeZ()], new double[0], new int[0]));
		return domain.materialRegistry();
	}

	public static void bindFull(
			FluidDomain domain, Registries registries, double initialTemperatureK, int maximumThermalSubsteps
	) {
		FluidMaterialRegistry liquids = domain.materialRegistry() == null
				? bindEmptyMaterialState(domain, registries.liquids()) : domain.materialRegistry();
		domain.bindChemistry(registries.chemical());
		domain.bindGranular(registries.granular(), liquids);
		domain.setPrecipitationEnabled(true);
		domain.bindGasRegistry(registries.gas());
		domain.bindThermal(registries.thermal(), liquids, initialTemperatureK, maximumThermalSubsteps);
	}

	public record Registries(
			FluidMaterialRegistry liquids,
			ChemicalRegistry chemical,
			GranularRegistry granular,
			GasRegistry gas,
			ThermalRegistry thermal
	) { }
}

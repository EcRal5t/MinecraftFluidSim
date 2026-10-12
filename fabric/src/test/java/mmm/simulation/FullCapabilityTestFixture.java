package mmm.simulation;

import mmm.chemistry.ChemicalRegistry;
import mmm.fluid.BuiltinFluidMaterials;
import mmm.fluid.FlipMaterialParticleState;
import mmm.fluid.FluidDomain;
import mmm.fluid.FluidMaterialRegistry;
import mmm.fluid.MaterialRegistry;
import mmm.fluid.air.GasRegistry;
import mmm.granular.GranularRegistry;
import mmm.thermal.ThermalRegistry;

/** One authoritative production-registry and empty-carrier fixture for full-profile validations. */
public final class FullCapabilityTestFixture {
	private FullCapabilityTestFixture() { }

	public static Registries load() throws Exception {
		ProductionRegistries.Set production = ProductionRegistries.load();
		return new Registries(new FluidMaterialRegistry(BuiltinFluidMaterials.DEFAULT_PALETTE),
				production.chemical(), production.granular(), production.gas(), production.thermal(),
				production.material());
	}

	/** Custom constitutive data exercises both in-memory and disk restoration. */
	public static MaterialRegistry doubledWaterViscosity() {
		MaterialRegistry baseline = BuiltinFluidMaterials.createRegistry();
		return new MaterialRegistry(baseline.entries().stream().map(entry ->
				!entry.material().id().equals("mmm:water") ? entry : new MaterialRegistry.MaterialEntry(
						entry.material(), entry.viscosity().stream().map(segment -> new MaterialRegistry.ViscositySegment(
								segment.minimumTemperatureK(), segment.maximumTemperatureK(),
								2 * segment.dynamicViscosityPaPerSAtMinimum(),
								2 * segment.dynamicViscosityPaPerSAtMaximum())).toList())).toList(), baseline.pairTensions());
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
		domain.bindGranular(registries.granular(), liquids, registries.materials());
		domain.setPrecipitationEnabled(true);
		domain.bindGasRegistry(registries.gas());
		domain.bindThermal(registries.thermal(), liquids, initialTemperatureK, maximumThermalSubsteps);
	}

	public record Registries(
			FluidMaterialRegistry liquids,
			ChemicalRegistry chemical,
			GranularRegistry granular,
			GasRegistry gas,
			ThermalRegistry thermal,
			MaterialRegistry materials
	) { }
}

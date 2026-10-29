package mmm.simulation;

import mmm.chemistry.ChemicalRegistry;
import mmm.fluid.BuiltinFluidMaterials;
import mmm.fluid.FlipMaterialParticleState;
import mmm.fluid.HostFluidState;
import mmm.fluid.FluidMaterialRegistry;
import mmm.fluid.MaterialRegistry;
import mmm.fluid.air.GasRegistry;
import mmm.granular.GranularRegistry;
import mmm.thermal.ThermalRegistry;

/** One authoritative production-registry and empty-carrier fixture for full-profile validations. */
public final class FullCapabilityTestFixture {
	private FullCapabilityTestFixture() { }

	/** Set a resolved pure composition within the stock's actual mass. */
	public static void assignPureSpecies(HostFluidState domain, int ordinal, String species) {
		var registry = domain.chemistry().registry();
		long mass = domain.exportLiquidCellState().physicalStocks().get(ordinal).massUnits();
		var mixture = mmm.chemistry.ChemicalMixtureRecord.fromSolutesAndSolventMass(registry, java.util.Map.of(), species, mass);
		domain.setMarkerChemical(ordinal, mixture.stableAmounts(registry));
	}

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
								2 * segment.dynamicViscosityPascalSecondsAtMinimum(),
								2 * segment.dynamicViscosityPascalSecondsAtMaximum())).toList())).toList(), baseline.pairTensions());
	}

	public static FluidMaterialRegistry bindEmptyMaterialState(HostFluidState domain, FluidMaterialRegistry liquids) {
		domain.restoreMaterialParticleState(new FlipMaterialParticleState(
				domain.sizeX(), domain.sizeY(), domain.sizeZ(), domain.config().cellSize(),
				0, 0.0, mmm.units.SolverUnitScale.forCellSize(domain.config().cellSize()).massUnitsForNormalized(0.0), liquids.materials(), new float[0], new float[0],
				new byte[domain.sizeX() * domain.sizeY() * domain.sizeZ()], java.util.Arrays.stream(new double[0]).mapToLong(m -> mmm.units.SolverUnitScale.forCellSize(domain.config().cellSize()).massUnitsForNormalized(m)).toArray(), new int[0]));
		return domain.materialRegistry();
	}

	public static void bindFull(
			HostFluidState domain, Registries registries, double initialTemperatureK, int maximumThermalSubsteps
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

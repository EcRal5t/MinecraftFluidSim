package mmm.fluid.air;

import mmm.chemistry.BuiltinChemicalDefinitions;
import mmm.chemistry.ExactRational;
import java.util.List;
import java.util.ArrayList;

/** Four ideal-gas components with one dry-air mole recipe and optional water partial pressure. */
public final class BuiltinGasDefinitions {
	public static final String NITROGEN = "mmm:nitrogen", OXYGEN = "mmm:oxygen";
	public static final String WATER_VAPOR = "mmm:water_vapor", ETHANOL_VAPOR = "mmm:ethanol_vapor", CO2 = "mmm:co2";
	public static final double NITROGEN_MOLAR_MASS = .028, OXYGEN_MOLAR_MASS = .032;
	public static final double WATER_MOLAR_MASS = .018, ETHANOL_MOLAR_MASS = .046, CO2_MOLAR_MASS = .044;
	public static final double WATER_VAPOR_STANDARD_DENSITY = standardDensity(WATER_MOLAR_MASS);
	public static final double CO2_STANDARD_DENSITY = standardDensity(CO2_MOLAR_MASS);
	private static final List<GasRegistry.AmbientFraction> DRY_AIR = List.of(
			new GasRegistry.AmbientFraction(NITROGEN, ExactRational.parse(".78958")),
			new GasRegistry.AmbientFraction(OXYGEN, ExactRational.parse(".21")),
			new GasRegistry.AmbientFraction(CO2, ExactRational.parse(".00042")));
	private BuiltinGasDefinitions() { }
	public static double standardDensity(double molarMass) {
		return GasRegistry.STANDARD_PRESSURE_PA * molarMass / (GasRegistry.MOLAR_GAS_CONSTANT * GasRegistry.STANDARD_TEMPERATURE_K);
	}
	public static List<GasRegistry.AmbientFraction> dryAirRecipe() { return DRY_AIR; }
	/** Water mole fraction equals p_water / p_total; dry gases occupy the remaining pressure. */
	public static List<GasRegistry.AmbientFraction> airRecipe(ExactRational waterMoleFraction) {
		if (waterMoleFraction == null || waterMoleFraction.signum() < 0 || waterMoleFraction.compareTo(ExactRational.ONE) > 0)
			throw new IllegalArgumentException("Water mole fraction must lie between zero and one");
		var result = new ArrayList<GasRegistry.AmbientFraction>();
		var dry = ExactRational.ONE.subtract(waterMoleFraction);
		if (dry.signum() > 0) for (var part : DRY_AIR) result.add(new GasRegistry.AmbientFraction(part.componentId(), part.fraction().multiply(dry)));
		if (waterMoleFraction.signum() > 0) result.add(new GasRegistry.AmbientFraction(WATER_VAPOR, waterMoleFraction));
		return List.copyOf(result);
	}
	private static GasComponentDefinition pure(String component, String species, double molarMass, long massUnitsPerQuantum) {
		var concentration = ExactRational.parse(Double.toString(GasRegistry.STANDARD_PRESSURE_PA
				/ (GasRegistry.MOLAR_GAS_CONSTANT * GasRegistry.STANDARD_TEMPERATURE_K)));
		return new GasComponentDefinition(component, standardDensity(molarMass), List.of(species),
				List.of(new GasComponentDefinition.DefaultSpecies(species, concentration, massUnitsPerQuantum)));
	}
	public static GasRegistry createRegistry() {
		return new GasRegistry(List.of(pure(NITROGEN, BuiltinChemicalDefinitions.N2, NITROGEN_MOLAR_MASS, 280),
				pure(OXYGEN, BuiltinChemicalDefinitions.O2, OXYGEN_MOLAR_MASS, 320),
				pure(WATER_VAPOR, BuiltinChemicalDefinitions.H2O, WATER_MOLAR_MASS, 180),
				pure(ETHANOL_VAPOR, BuiltinChemicalDefinitions.ETHANOL, ETHANOL_MOLAR_MASS, 460),
				pure(CO2, BuiltinChemicalDefinitions.CO2, CO2_MOLAR_MASS, 440)), DRY_AIR);
	}
}

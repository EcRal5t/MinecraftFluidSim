package mmm.fluid.air;

import mmm.chemistry.BuiltinChemicalDefinitions;
import mmm.chemistry.ExactRational;

import java.util.List;

/** Built-in gas registry. */
public final class BuiltinGasDefinitions {
	public static final String AIR = "mmm:air";
	public static final String WATER_VAPOR = "mmm:water_vapor";
	public static final String CO2 = "mmm:co2";
	/** Ideal water-vapor density at the frozen gas standard state, derived from p=rho*R*T. */
	public static final double WATER_VAPOR_STANDARD_DENSITY = GasRegistry.STANDARD_PRESSURE_PA
			/ (461.52 * GasRegistry.STANDARD_TEMPERATURE_K);
	/** Ideal carbon-dioxide density at the frozen gas standard state, derived from p=rho*R*T. */
	public static final double CO2_STANDARD_DENSITY = GasRegistry.STANDARD_PRESSURE_PA
			/ ((8.314462618 / 0.044) * GasRegistry.STANDARD_TEMPERATURE_K);

	private BuiltinGasDefinitions() { }

	public static GasRegistry createRegistry() {
		return new GasRegistry(
				List.of(
						new GasComponentDefinition(AIR, AirNetwork.AIR_DENSITY, AirNetwork.AIR_DENSITY,
								List.of(), List.of()),
						new GasComponentDefinition(WATER_VAPOR, WATER_VAPOR_STANDARD_DENSITY,
								WATER_VAPOR_STANDARD_DENSITY, List.of(BuiltinChemicalDefinitions.H2O), List.of()),
						new GasComponentDefinition(CO2, CO2_STANDARD_DENSITY, CO2_STANDARD_DENSITY,
								List.of(BuiltinChemicalDefinitions.CO2), List.of())),
				List.of(new GasRegistry.AmbientFraction(AIR, ExactRational.ONE)));
	}
}

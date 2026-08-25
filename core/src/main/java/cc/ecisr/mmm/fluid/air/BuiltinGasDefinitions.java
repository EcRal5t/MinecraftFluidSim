package cc.ecisr.mmm.fluid.air;

import cc.ecisr.mmm.chemistry.ExactRational;

import java.util.List;

/** Built-in gas registry. */
public final class BuiltinGasDefinitions {
	public static final String AIR = "mmm:air";
	public static final String WATER_VAPOR = "mmm:water_vapor";
	/** Ideal water-vapor density at the frozen gas standard state, derived from p=rho*R*T. */
	public static final double WATER_VAPOR_STANDARD_DENSITY = GasRegistry.STANDARD_PRESSURE_PA
			/ (461.52 * GasRegistry.STANDARD_TEMPERATURE_K);

	private BuiltinGasDefinitions() { }

	public static GasRegistry createRegistry() {
		return new GasRegistry(
				List.of(
						new GasComponentDefinition(AIR, AirNetwork.AIR_DENSITY, AirNetwork.AIR_DENSITY, List.of()),
						new GasComponentDefinition(WATER_VAPOR, WATER_VAPOR_STANDARD_DENSITY,
								WATER_VAPOR_STANDARD_DENSITY, List.of())),
				List.of(new GasRegistry.AmbientFraction(AIR, ExactRational.ONE)));
	}
}

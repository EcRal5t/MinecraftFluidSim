package com.example.fluid.air;

import com.example.chemistry.ExactRational;

import java.util.List;

/** Built-in M5-compatible gas registry. */
public final class BuiltinGasDefinitions {
	public static final String AIR = "realwater:air";

	private BuiltinGasDefinitions() { }

	public static GasRegistry createRegistry() {
		return new GasRegistry(
				List.of(new GasComponentDefinition(AIR, AirNetwork.AIR_DENSITY, AirNetwork.AIR_DENSITY, List.of())),
				List.of(new GasRegistry.AmbientFraction(AIR, ExactRational.ONE)));
	}
}

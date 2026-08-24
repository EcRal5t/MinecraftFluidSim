package com.example.fluid.air;

/** Hard representation budgets shared by runtime gas state and region-state v4. */
public final class GasLimits {
	public static final int MAX_COMPONENTS = 64;
	public static final int MAX_COMPONENTS_PER_NODE = 32;
	public static final int MAX_SPECIES_PER_COMPONENT = 16;
	public static final int MAX_NODES_PER_REGION = 65_536;
	public static final int MAX_GAS_ID_LENGTH = 128;
	public static final int MAX_SERIALIZED_EXTENSION_BYTES = 16 * 1_048_576;

	private GasLimits() { }
}

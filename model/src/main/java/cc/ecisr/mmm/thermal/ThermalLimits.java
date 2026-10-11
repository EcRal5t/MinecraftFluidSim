package cc.ecisr.mmm.thermal;

/** Hard allocation and definition budgets for the thermal subsystem. */
public final class ThermalLimits {
	public static final int MAX_MATERIALS = 256;
	public static final int MAX_SEGMENTS_PER_MATERIAL = 16;
	public static final int MAX_TOTAL_SEGMENTS = 4_096;
	public static final int MAX_SPECIES_OFFSETS = 512;
	public static final int MAX_PHASE_TRANSITIONS = 512;
	public static final int MAX_EQUILIBRIUM_CURVE_POINTS = 64;
	public static final int MAX_BOUNDARIES = 512;
	public static final int MAX_GAS_LIQUID_PAIRS = 64;
	public static final int MAX_HENRY_SEGMENTS_PER_PAIR = 32;
	public static final int MAX_FINITE_DEVICE_TYPES = 64;
	public static final int MAX_COHERENT_GROUPS_PER_REGION = 262_144;
	public static final int MAX_THERMAL_CARRIERS_PER_CELL = 256;
	public static final int MAX_PHASE_OPERATIONS_PER_STEP = 4_096;
	public static final int MAX_GAS_NODES_PER_REGION = 65_536;
	public static final int MAX_GAS_TRANSFER_SEARCH_INTERVALS = 4_096;
	public static final int MAX_FINITE_DEVICE_CONTACTS = 16;
	public static final int MAX_FINITE_DEVICES_PER_REGION = 1_024;
	public static final int MAX_DEVICE_OBJECT_ID_LENGTH = 256;
	public static final int MAX_SERIALIZED_EXTENSION_BYTES = 16 * 1_048_576;
	public static final int MAX_THERMAL_ID_LENGTH = 128;
	/** Smallest normal positive binary64 temperature; this is a numeric domain edge, not a calibration clamp. */
	public static final double MIN_ABSOLUTE_TEMPERATURE_K = Double.MIN_NORMAL;

	private ThermalLimits() { }
}

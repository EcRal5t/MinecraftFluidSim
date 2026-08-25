package com.example.chemistry;

/** Frozen chemistry definition, runtime and bounded-container budgets. */
public final class ChemicalLimits {
	public static final long QUANTA_PER_AU = 1_000_000_000L;
	public static final int MAX_SPECIES = 256;
	public static final int MAX_COMPOSITION_BASES = 64;
	public static final int MAX_PHASES = 64;
	public static final int MAX_REACTIONS = 512;
	public static final int MAX_COMPOSITION_COEFFICIENT = 64;
	public static final int MAX_ABSOLUTE_CHARGE = 16;
	public static final int MAX_STOICHIOMETRIC_COEFFICIENT = 64;
	public static final int MAX_SPECIES_PER_PARCEL = 16;
	public static final int MAX_REACTION_EVALUATIONS_PER_STEP = 131_072;
	public static final long MAX_REGION_TOTAL_QUANTA = 2_000_000_000_000_000L;
	public static final long MAX_BUCKET_TOTAL_QUANTA = 1_024_000_000_000L;
	public static final int MAX_BUCKET_GROUPS = 16;
	public static final int MAX_BUCKET_SPECIES_ENTRIES = 64;
	public static final int MAX_COMPONENT_BYTES = 64 * 1024;
	/** Complete internal transfer/audit truth: one group per material/indicator pair. */
	public static final int MAX_TRANSFER_GROUPS = MAX_PHASES * 2;
	public static final int MAX_TRANSFER_SPECIES_PER_GROUP = MAX_SPECIES;
	public static final int MAX_TRANSFER_SPECIES_ENTRIES = MAX_TRANSFER_GROUPS * MAX_TRANSFER_SPECIES_PER_GROUP;
	public static final int MAX_CHEMICAL_ID_LENGTH = 128;
	public static final int MAX_RATIONAL_TEXT_LENGTH = 4_096;
	public static final int MAX_RATIONAL_SCALE = 4_096;
	public static final int MAX_RATIONAL_BITS = 16_384;
	public static final double MAX_INDICATOR_VOLUME_BV = 100.0;
	public static final int DISPLAY_SPECIES_PER_CELL = 4;

	private ChemicalLimits() { }
}

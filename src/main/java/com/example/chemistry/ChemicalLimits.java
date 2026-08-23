package com.example.chemistry;

/** Frozen M7 definition, runtime and bounded-container budgets. */
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
	public static final double MAX_INDICATOR_VOLUME_BV = 100.0;
	public static final int DISPLAY_SPECIES_PER_CELL = 4;

	private ChemicalLimits() { }
}

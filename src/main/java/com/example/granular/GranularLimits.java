package com.example.granular;

/** Hard allocation, definition and operation limits for M8 granular state. */
public final class GranularLimits {
	public static final int MAX_MATERIALS = 256;
	public static final int MAX_CARRIERS = 512;
	public static final int MAX_REACTIONS = 512;
	public static final int MAX_ACTIVE_PARCELS_PER_REGION = 16_384;
	public static final int MAX_ACTIVE_PARCELS_PER_SERVER = 65_536;
	public static final int MAX_DEPOSIT_GROUPS_PER_CELL = 8;
	public static final int MAX_DEPOSIT_GROUPS_PER_REGION = 65_536;
	public static final int MAX_DEPOSIT_GROUPS_PER_SERVER = 262_144;
	public static final int MAX_SOLID_COMPOSITION_ENTRIES = 16;
	public static final int MAX_REACTION_EXTENTS_PER_BATCH = 131_072;
	public static final int MAX_OPERATIONS_PER_LOGICAL_STEP = 131_072;
	public static final int MAX_DISPLAY_PARCELS_PER_REGION = 4_096;
	public static final int MAX_STABLE_ID_LENGTH = 128;
	public static final int POROSITY_SCALE = 1_000_000;
	public static final long MAX_PARCEL_VOLUME_SVQ = SolidVolume.SVQ_PER_CELL / 64L;

	private GranularLimits() { }
}

package cc.ecisr.mmm.simulation.persistence;

import java.io.IOException;

/** Explicit capability layout for one authoritative region checkpoint. */
public enum RegionStateLayout {
	PURE("pure", false, false, false, false),
	MATERIAL("material", true, false, false, false),
	CHEMICAL("chemical", true, true, false, false),
	GRANULAR("granular", true, false, true, false),
	THERMAL("thermal", true, false, false, true);

	private final String persistedId;
	private final boolean materials;
	private final boolean chemistryRequired;
	private final boolean granularRequired;
	private final boolean thermalRequired;

	RegionStateLayout(
			String persistedId, boolean materials, boolean chemistryRequired,
			boolean granularRequired, boolean thermalRequired
	) {
		this.persistedId = persistedId;
		this.materials = materials;
		this.chemistryRequired = chemistryRequired;
		this.granularRequired = granularRequired;
		this.thermalRequired = thermalRequired;
	}

	public String persistedId() { return persistedId; }
	public boolean hasMaterials() { return materials; }
	public boolean requiresChemistry() { return chemistryRequired; }
	public boolean permitsOptionalChemistry() { return this == GRANULAR || this == THERMAL; }
	public boolean requiresGranular() { return granularRequired; }
	public boolean requiresThermal() { return thermalRequired; }

	public static RegionStateLayout decode(String persistedId) throws IOException {
		for (RegionStateLayout layout : values()) if (layout.persistedId.equals(persistedId)) return layout;
		throw new IOException("Unsupported region-state layout " + persistedId);
	}
}

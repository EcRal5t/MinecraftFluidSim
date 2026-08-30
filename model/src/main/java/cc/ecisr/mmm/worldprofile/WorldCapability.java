package cc.ecisr.mmm.worldprofile;

import cc.ecisr.mmm.api.StableId;

import java.util.List;

/** Semantic world capability with fixed dependency edges; stable ids are the only persisted form. */
public enum WorldCapability {
	MATERIALS("mmm:materials"),
	CHEMISTRY("mmm:chemistry", MATERIALS),
	GRANULAR("mmm:granular", MATERIALS),
	PRECIPITATION("mmm:precipitation", CHEMISTRY, GRANULAR),
	THERMAL("mmm:thermal", MATERIALS),
	PHASECHANGE("mmm:phasechange", THERMAL);

	private final String stableId;
	private final List<WorldCapability> dependencies;

	WorldCapability(String stableId, WorldCapability... dependencies) {
		this.stableId = StableId.require(stableId, 64, "world capability id");
		this.dependencies = List.of(dependencies);
	}

	public String stableId() { return stableId; }

	public List<WorldCapability> dependencies() { return dependencies; }

	public static WorldCapability byStableId(String id) {
		String required = StableId.require(id, 64, "world capability id");
		for (WorldCapability capability : values()) {
			if (capability.stableId.equals(required)) return capability;
		}
		throw new IllegalArgumentException("Unknown world capability " + id);
	}
}

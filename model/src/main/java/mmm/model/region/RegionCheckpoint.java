package mmm.model.region;

import java.util.List;
import java.util.Objects;

/** Complete immutable logical checkpoint; durable slots are an adapter concern. */
public record RegionCheckpoint(RegionManifest manifest, List<RegionSectionState> sections) {
	public RegionCheckpoint {
		Objects.requireNonNull(manifest, "manifest");
		sections = List.copyOf(sections);
	}
}

package cc.ecisr.mmm.simulation.persistence;

import cc.ecisr.mmm.model.region.RegionCheckpoint;
import cc.ecisr.mmm.model.region.RegionManifest;
import cc.ecisr.mmm.model.region.RegionSectionState;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/** Transitional durable-store DTO adapter around the loader-neutral logical checkpoint. */
public final class RegionState {
	private final RegionCheckpoint checkpoint;

	public RegionState(RegionManifest manifest, List<RegionSectionState> sections) {
		this(new RegionCheckpoint(manifest, sections));
	}

	public RegionState(RegionCheckpoint checkpoint) {
		this.checkpoint = Objects.requireNonNull(checkpoint, "checkpoint");
	}

	public RegionCheckpoint checkpoint() { return checkpoint; }
	public RegionManifest manifest() { return checkpoint.manifest(); }
	public List<RegionSectionState> sections() { return checkpoint.sections(); }
	public void validate() throws IOException { new RegionCheckpointValidator(checkpoint).validate(); }
}

package cc.ecisr.mmm.application.port;

import cc.ecisr.mmm.application.result.CheckpointRegionResult;

import java.util.Objects;
import java.util.UUID;

/** Durable region checkpoint boundary; implementations must recheck generation before HEAD publication. */
public interface RegionCheckpointPort {
	Attempt checkpoint(UUID regionId, long expectedCheckpointGeneration);

	record Attempt(CheckpointRegionResult.Status status, long checkpointGeneration, String diagnostic) {
		public Attempt {
			Objects.requireNonNull(status, "status");
			if (checkpointGeneration < 0L) throw new IllegalArgumentException("Checkpoint attempt generation is invalid");
			if (diagnostic != null) {
				if (diagnostic.isBlank()) throw new IllegalArgumentException("Checkpoint attempt diagnostic is blank");
				diagnostic = diagnostic.length() <= 1_024 ? diagnostic : diagnostic.substring(0, 1_021) + "...";
			}
		}
	}
}

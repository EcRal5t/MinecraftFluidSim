package mmm.application.command;

import java.util.Objects;
import java.util.UUID;

/** Explicit checkpoint request after the adapter has frozen cross-domain WAL presence. */
public record CheckpointRegionCommand(
		UUID regionId,
		long expectedCheckpointGeneration,
		boolean containerWalPending
) {
	public CheckpointRegionCommand {
		Objects.requireNonNull(regionId, "regionId");
		if (expectedCheckpointGeneration < 0L) {
			throw new IllegalArgumentException("Expected checkpoint generation is invalid");
		}
	}
}

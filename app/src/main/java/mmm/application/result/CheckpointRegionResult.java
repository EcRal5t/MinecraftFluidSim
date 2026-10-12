package mmm.application.result;

import java.util.Objects;

public record CheckpointRegionResult(Status status, long checkpointGeneration, String diagnostic) {
	public enum Status {
		COMMITTED,
		ALREADY_UNLOADED,
		REGION_NOT_FOUND,
		NOT_PERSISTENT,
		STALE_GENERATION,
		PENDING_CONTAINER_WAL,
		OWNERSHIP_BLOCKED,
		PERSISTENCE_FAILED
	}

	public CheckpointRegionResult {
		Objects.requireNonNull(status, "status");
		if (checkpointGeneration < 0L) throw new IllegalArgumentException("Checkpoint result generation is invalid");
		if (diagnostic != null) {
			if (diagnostic.isBlank()) throw new IllegalArgumentException("Checkpoint diagnostic is blank");
			diagnostic = diagnostic.length() <= 1_024 ? diagnostic : diagnostic.substring(0, 1_021) + "...";
		}
		boolean needsDiagnostic = status == Status.OWNERSHIP_BLOCKED || status == Status.PERSISTENCE_FAILED;
		if (needsDiagnostic != (diagnostic != null)) {
			throw new IllegalArgumentException("Checkpoint result status/diagnostic disagree");
		}
	}

	public boolean committed() { return status == Status.COMMITTED; }
}

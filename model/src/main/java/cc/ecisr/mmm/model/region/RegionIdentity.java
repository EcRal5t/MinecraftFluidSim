package cc.ecisr.mmm.model.region;

import java.util.Objects;
import java.util.UUID;

/** Stable checkpoint identity with no loader or runtime ownership types. */
public record RegionIdentity(
		UUID regionId,
		String dimensionId,
		long checkpointGeneration,
		long networkGeneration
) {
	public RegionIdentity {
		Objects.requireNonNull(regionId, "regionId");
		Objects.requireNonNull(dimensionId, "dimensionId");
		if (dimensionId.isBlank() || checkpointGeneration <= 0L || networkGeneration <= 0L) {
			throw new IllegalArgumentException("Invalid region checkpoint identity");
		}
	}
}

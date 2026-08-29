package cc.ecisr.mmm.api.query;

import cc.ecisr.mmm.api.RegionAvailabilityReason;

/** A non-OK result never carries a partial observation. */
public record FluidQueryResult(
		FluidQueryStatus status,
		String detail,
		RegionAvailabilityReason availabilityReason,
		FluidCellObservation observation
) {
	public FluidQueryResult {
		if (status == null || detail == null || detail.isBlank()) {
			throw new IllegalArgumentException("Query result status/detail is missing");
		}
		if ((status == FluidQueryStatus.OK) != (observation != null)) {
			throw new IllegalArgumentException("Only an OK query may carry an observation");
		}
		if ((status == FluidQueryStatus.REGION_UNAVAILABLE) != (availabilityReason != null)) {
			throw new IllegalArgumentException("REGION_UNAVAILABLE requires exactly one availability reason");
		}
	}

	public static FluidQueryResult ok(FluidCellObservation observation) {
		return new FluidQueryResult(FluidQueryStatus.OK, "ok", null, observation);
	}

	public static FluidQueryResult absent(FluidQueryStatus status, String detail) {
		if (status == FluidQueryStatus.OK || status == FluidQueryStatus.REGION_UNAVAILABLE) {
			throw new IllegalArgumentException("Use the dedicated OK or unavailable result factory");
		}
		return new FluidQueryResult(status, detail, null, null);
	}

	public static FluidQueryResult unavailable(RegionAvailabilityReason reason, String detail) {
		return new FluidQueryResult(FluidQueryStatus.REGION_UNAVAILABLE, detail,
				java.util.Objects.requireNonNull(reason, "reason"), null);
	}
}

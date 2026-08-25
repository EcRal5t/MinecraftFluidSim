package cc.ecisr.mmm.api.query;

/** A non-OK result never carries a partial observation. */
public record FluidQueryResult(
		FluidQueryStatus status,
		String detail,
		FluidCellObservation observation
) {
	public FluidQueryResult {
		if (status == null || detail == null || detail.isBlank()) {
			throw new IllegalArgumentException("Query result status/detail is missing");
		}
		if ((status == FluidQueryStatus.OK) != (observation != null)) {
			throw new IllegalArgumentException("Only an OK query may carry an observation");
		}
	}

	public static FluidQueryResult ok(FluidCellObservation observation) {
		return new FluidQueryResult(FluidQueryStatus.OK, "ok", observation);
	}

	public static FluidQueryResult absent(FluidQueryStatus status, String detail) {
		if (status == FluidQueryStatus.OK) throw new IllegalArgumentException("OK requires an observation");
		return new FluidQueryResult(status, detail, null);
	}
}

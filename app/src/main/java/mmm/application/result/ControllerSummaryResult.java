package mmm.application.result;

import mmm.controller.ControllerSummary;

import java.util.Objects;

public record ControllerSummaryResult(Status status, ControllerSummary summary) {
	public enum Status { OK, REGION_NOT_FOUND }

	public ControllerSummaryResult {
		Objects.requireNonNull(status, "status");
		if ((status == Status.OK) != (summary != null)) {
			throw new IllegalArgumentException("Controller summary status/data disagree");
		}
	}

	public boolean ok() { return status == Status.OK; }
}

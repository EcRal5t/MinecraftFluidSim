package cc.ecisr.mmm.application.result;

import cc.ecisr.mmm.fluid.FluidDomain;
import cc.ecisr.mmm.fluid.FluidStepCandidate;
import cc.ecisr.mmm.world.MovingBodyFluidCoupling;

import java.util.Objects;

public record MotionStepResult(
		RegionStepStatus status,
		FluidDomain.StepStats stats,
		MovingBodyFluidCoupling.Result movingBodyCoupling
) {
	public MotionStepResult {
		Objects.requireNonNull(status, "status");
		boolean hasOutcome = stats != null && movingBodyCoupling != null;
		if ((status == RegionStepStatus.ACCEPTED) != hasOutcome
				|| (stats == null) != (movingBodyCoupling == null)) {
			throw new IllegalArgumentException("Motion step status/outcome disagree");
		}
	}

	public static MotionStepResult accepted(FluidStepCandidate candidate) {
		Objects.requireNonNull(candidate, "candidate");
		return new MotionStepResult(RegionStepStatus.ACCEPTED, candidate.stats(), candidate.movingBodyCoupling());
	}

	public static MotionStepResult rejected(RegionStepStatus status) {
		return new MotionStepResult(status, null, null);
	}
	public boolean accepted() { return status == RegionStepStatus.ACCEPTED; }
}

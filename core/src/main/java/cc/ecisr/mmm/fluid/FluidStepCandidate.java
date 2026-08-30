package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.world.MovingBodyFluidCoupling;

import java.util.Objects;

/** Complete uncommitted generation returned by a CPU or future GPU backend. */
public record FluidStepCandidate(
		String backendId,
		long baseGeneration,
		long candidateGeneration,
		FluidDomainState state,
		FluidDomain.StepStats stats,
		MovingBodyFluidCoupling.Result movingBodyCoupling
) {
	public FluidStepCandidate {
		if (backendId == null || backendId.isBlank()) throw new IllegalArgumentException("Backend id is missing");
		if (baseGeneration < 0L || candidateGeneration != Math.addExact(baseGeneration, 1L)) {
			throw new IllegalArgumentException("Candidate generation is not the immediate successor");
		}
		Objects.requireNonNull(state, "state");
		Objects.requireNonNull(stats, "stats");
		Objects.requireNonNull(movingBodyCoupling, "movingBodyCoupling");
	}
}

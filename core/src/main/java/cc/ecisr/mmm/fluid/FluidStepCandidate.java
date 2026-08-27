package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.world.MovingBodyStepResult;

import java.util.List;
import java.util.Objects;

/** Complete uncommitted generation returned by a CPU or future GPU backend. */
public record FluidStepCandidate(
		String backendId,
		long baseGeneration,
		long candidateGeneration,
		FluidDomainState state,
		FlipFluidDomain.StepStats stats,
		List<MovingBodyStepResult> movingBodyResults
) {
	public FluidStepCandidate(String backendId, long baseGeneration, long candidateGeneration,
			FluidDomainState state, FlipFluidDomain.StepStats stats) {
		this(backendId, baseGeneration, candidateGeneration, state, stats, List.of());
	}

	public FluidStepCandidate {
		if (backendId == null || backendId.isBlank()) throw new IllegalArgumentException("Backend id is missing");
		if (baseGeneration < 0L || candidateGeneration != Math.addExact(baseGeneration, 1L)) {
			throw new IllegalArgumentException("Candidate generation is not the immediate successor");
		}
		Objects.requireNonNull(state, "state");
		Objects.requireNonNull(stats, "stats");
		if (movingBodyResults == null) {
			throw new IllegalArgumentException("Moving-body results are missing");
		}
		long previousId = -1L;
		for (MovingBodyStepResult result : movingBodyResults) {
			if (result == null || result.transientId() <= previousId) {
				throw new IllegalArgumentException("Moving-body pressure results must have unique sorted ids");
			}
			previousId = result.transientId();
		}
		movingBodyResults = List.copyOf(movingBodyResults);
	}
}

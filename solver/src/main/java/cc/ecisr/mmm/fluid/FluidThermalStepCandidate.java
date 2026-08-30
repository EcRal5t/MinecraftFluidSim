package cc.ecisr.mmm.fluid;

import java.util.Objects;

/** Complete uncommitted thermal-only successor returned by a solver backend. */
public record FluidThermalStepCandidate(
		String backendId,
		long baseGeneration,
		long candidateGeneration,
		FluidDomainState state,
		FluidDomain.ThermalAxisStats stats
) {
	public FluidThermalStepCandidate {
		if (backendId == null || backendId.isBlank()) throw new IllegalArgumentException("Backend id is missing");
		if (baseGeneration < 0L || candidateGeneration != Math.addExact(baseGeneration, 1L)) {
			throw new IllegalArgumentException("Candidate generation is not the immediate successor");
		}
		Objects.requireNonNull(state, "state");
		Objects.requireNonNull(stats, "stats");
	}
}

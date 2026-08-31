package cc.ecisr.mmm.fluid;

import java.util.Objects;

/** Complete uncommitted thermal-only successor, with the same lazy CPU adoption path as motion. */
public final class FluidThermalStepCandidate {
	private final String backendId;
	private final long baseGeneration;
	private final long candidateGeneration;
	private final FluidDomainState.Layout layout;
	private final FluidDomain.ThermalAxisStats stats;
	private FluidDomain preparedCpuDomain;
	private boolean preparedCpuDomainClaimed;
	private volatile FluidDomainState state;

	public FluidThermalStepCandidate(
			String backendId, long baseGeneration, long candidateGeneration,
			FluidDomainState state, FluidDomain.ThermalAxisStats stats
	) {
		this(backendId, baseGeneration, candidateGeneration, Objects.requireNonNull(state, "state"),
				state.layout(), stats, null);
	}

	static FluidThermalStepCandidate preparedCpu(
			long baseGeneration, FluidDomain domain, FluidDomain.ThermalAxisStats stats
	) {
		Objects.requireNonNull(domain, "domain");
		return new FluidThermalStepCandidate(CpuFluidSolverBackend.ID, baseGeneration,
				Math.addExact(baseGeneration, 1L), null, domain.authoritativeLayout(), stats, domain);
	}

	private FluidThermalStepCandidate(
			String backendId, long baseGeneration, long candidateGeneration, FluidDomainState state,
			FluidDomainState.Layout layout, FluidDomain.ThermalAxisStats stats, FluidDomain preparedCpuDomain
	) {
		if (backendId == null || backendId.isBlank()) throw new IllegalArgumentException("Backend id is missing");
		if (baseGeneration < 0L || candidateGeneration != Math.addExact(baseGeneration, 1L)) {
			throw new IllegalArgumentException("Candidate generation is not the immediate successor");
		}
		this.backendId = backendId;
		this.baseGeneration = baseGeneration;
		this.candidateGeneration = candidateGeneration;
		this.state = state;
		this.layout = Objects.requireNonNull(layout, "layout");
		this.stats = Objects.requireNonNull(stats, "stats");
		this.preparedCpuDomain = preparedCpuDomain;
		if (preparedCpuDomain != null && (!CpuFluidSolverBackend.ID.equals(backendId)
				|| !preparedCpuDomain.authoritativeLayout().equals(layout))) {
			throw new IllegalArgumentException("Prepared CPU thermal candidate identity/layout is invalid");
		}
	}

	public String backendId() { return backendId; }
	public long baseGeneration() { return baseGeneration; }
	public long candidateGeneration() { return candidateGeneration; }
	public FluidDomainState.Layout layout() { return layout; }
	public FluidDomain.ThermalAxisStats stats() { return stats; }
	public synchronized FluidDomain claimPreparedCpuDomain() {
		if (preparedCpuDomainClaimed) {
			throw new IllegalStateException("Prepared CPU thermal candidate was already adopted");
		}
		if (preparedCpuDomain == null) return null;
		FluidDomain claimed = preparedCpuDomain;
		preparedCpuDomain = null;
		preparedCpuDomainClaimed = true;
		return claimed;
	}

	public FluidDomainState state() {
		FluidDomainState current = state;
		if (current != null) return current;
		synchronized (this) {
			current = state;
			if (current == null) {
				if (preparedCpuDomainClaimed) {
					throw new IllegalStateException("Adopted CPU thermal candidate no longer owns an immutable state source");
				}
				state = current = preparedCpuDomain.captureAuthoritativeState();
			}
			return current;
		}
	}
}

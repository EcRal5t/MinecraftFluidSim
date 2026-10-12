package mmm.fluid;

import mmm.world.MovingBodyFluidCoupling;

import java.util.Objects;

/**
 * Complete uncommitted generation returned by a solver backend.
 *
 * <p>The in-process CPU backend retains its already evaluated private aggregate so atomic adoption does not
 * serialize and reconstruct the same generation again. Its immutable state is materialized only when a
 * checkpoint, replay or diagnostic actually asks for it. Other backends provide the immutable state eagerly.
 */
public final class FluidStepCandidate {
	private final String backendId;
	private final long baseGeneration;
	private final long candidateGeneration;
	private final FluidDomainState.Layout layout;
	private final FluidDomain.StepStats stats;
	private final MovingBodyFluidCoupling.Result movingBodyCoupling;
	private FluidDomain preparedCpuDomain;
	private boolean preparedCpuDomainClaimed;
	private volatile FluidDomainState state;

	public FluidStepCandidate(
			String backendId, long baseGeneration, long candidateGeneration, FluidDomainState state,
			FluidDomain.StepStats stats, MovingBodyFluidCoupling.Result movingBodyCoupling
	) {
		this(backendId, baseGeneration, candidateGeneration, Objects.requireNonNull(state, "state"),
				state.layout(), stats, movingBodyCoupling, null);
	}

	static FluidStepCandidate preparedCpu(
			long baseGeneration, FluidDomain domain, FluidDomain.StepStats stats,
			MovingBodyFluidCoupling.Result movingBodyCoupling
	) {
		Objects.requireNonNull(domain, "domain");
		return new FluidStepCandidate(CpuFluidSolverBackend.ID, baseGeneration,
				Math.addExact(baseGeneration, 1L), null, domain.authoritativeLayout(), stats,
				movingBodyCoupling, domain);
	}

	private FluidStepCandidate(
			String backendId, long baseGeneration, long candidateGeneration, FluidDomainState state,
			FluidDomainState.Layout layout, FluidDomain.StepStats stats,
			MovingBodyFluidCoupling.Result movingBodyCoupling, FluidDomain preparedCpuDomain
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
		this.movingBodyCoupling = Objects.requireNonNull(movingBodyCoupling, "movingBodyCoupling");
		this.preparedCpuDomain = preparedCpuDomain;
		if (preparedCpuDomain != null && (!CpuFluidSolverBackend.ID.equals(backendId)
				|| !preparedCpuDomain.authoritativeLayout().equals(layout))) {
			throw new IllegalArgumentException("Prepared CPU candidate identity/layout is invalid");
		}
	}

	public String backendId() { return backendId; }
	public long baseGeneration() { return baseGeneration; }
	public long candidateGeneration() { return candidateGeneration; }
	public FluidDomainState.Layout layout() { return layout; }
	public FluidDomain.StepStats stats() { return stats; }
	public MovingBodyFluidCoupling.Result movingBodyCoupling() { return movingBodyCoupling; }

	/**
	 * Transfers the in-process CPU aggregate to the atomic adoption owner. An eager candidate returns
	 * {@code null}. A prepared candidate is single-use; after transfer its state can no longer be lazily
	 * materialized because the aggregate has become mutable live state.
	 */
	public synchronized FluidDomain claimPreparedCpuDomain() {
		if (preparedCpuDomainClaimed) {
			throw new IllegalStateException("Prepared CPU candidate was already adopted");
		}
		if (preparedCpuDomain == null) return null;
		FluidDomain claimed = preparedCpuDomain;
		preparedCpuDomain = null;
		preparedCpuDomainClaimed = true;
		return claimed;
	}

	/** Materializes the complete immutable handoff only for callers that actually consume it. */
	public FluidDomainState state() {
		FluidDomainState current = state;
		if (current != null) return current;
		synchronized (this) {
			current = state;
			if (current == null) {
				if (preparedCpuDomainClaimed) {
					throw new IllegalStateException("Adopted CPU candidate no longer owns an immutable state source");
				}
				state = current = preparedCpuDomain.captureAuthoritativeState();
			}
			return current;
		}
	}
}

package mmm.fluid;

import java.util.Objects;
import mmm.world.MovingBodyFluidCoupling;

/** A complete device successor, adopted once or released without changing the committed generation. */
public final class FluidStepCandidate implements AutoCloseable {
	/** Budget reservations include rejected attempts; zero denotes an unreported backend. */
	public record MechanicalResolution(int timeRefinements, long reservedSubsteps, String lastRefinementReason) {
		public MechanicalResolution {
			Objects.requireNonNull(lastRefinementReason, "lastRefinementReason");
			if (timeRefinements < 0 || reservedSubsteps < 0
					|| reservedSubsteps > 0 && reservedSubsteps < timeRefinements + 1L
					|| reservedSubsteps == 0 && timeRefinements != 0
					|| (timeRefinements == 0) != lastRefinementReason.isEmpty())
				throw new IllegalArgumentException("Mechanical resolution counters are inconsistent");
		}
		private static final MechanicalResolution UNREPORTED = new MechanicalResolution(0, 0, "");
	}
    private final String backendId;
    private final long baseGeneration;
    private final FluidDomainState.Layout layout;
    private final FluidDomainContracts.StepStats stats;
    private final MovingBodyFluidCoupling.Result movingBodyCoupling;
    private final MechanicalResolution mechanicalResolution;
    private PreparedFluidState prepared;
    private FluidDomainState snapshot;
    private boolean handled;

    private FluidStepCandidate(String backendId, long baseGeneration, PreparedFluidState prepared,
            FluidDomainContracts.StepStats stats, MovingBodyFluidCoupling.Result movingBodyCoupling, MechanicalResolution mechanicalResolution) {
        if (backendId == null || backendId.isBlank() || baseGeneration < 0)
            throw new IllegalArgumentException("Candidate identity is invalid");
        Math.addExact(baseGeneration, 1);
        this.backendId = backendId;
        this.baseGeneration = baseGeneration;
        this.prepared = Objects.requireNonNull(prepared);
        this.layout = prepared.layout();
        this.stats = Objects.requireNonNull(stats);
        this.movingBodyCoupling = Objects.requireNonNull(movingBodyCoupling);
        this.mechanicalResolution = Objects.requireNonNull(mechanicalResolution);
    }

    public static FluidStepCandidate preparedDevice(String backendId, long baseGeneration, PreparedFluidState state,
            FluidDomainContracts.StepStats stats, MovingBodyFluidCoupling.Result movingBodyCoupling, MechanicalResolution mechanicalResolution) {
        return new FluidStepCandidate(backendId, baseGeneration, state, stats, movingBodyCoupling, mechanicalResolution);
    }

    public String backendId() { return backendId; }
    public long baseGeneration() { return baseGeneration; }
    public long candidateGeneration() { return baseGeneration + 1; }
    public FluidDomainState.Layout layout() { return layout; }
    public FluidDomainContracts.StepStats stats() { return stats; }
    public MovingBodyFluidCoupling.Result movingBodyCoupling() { return movingBodyCoupling; }
    public MechanicalResolution mechanicalResolution() { return mechanicalResolution; }

    public synchronized PreparedFluidState claimPreparedDeviceState() {
        if (handled) throw new IllegalStateException("Candidate was already handled");
        handled = true;
        var result = prepared;
        prepared = null;
        return result;
    }

    /** Explicit checkpoint capture retains the same private generation until adoption. */
    public synchronized FluidDomainState state() {
        if (snapshot != null) return snapshot;
        if (handled) throw new IllegalStateException("Candidate no longer owns its generation");
        return snapshot = prepared.exportState();
    }

    @Override public synchronized void close() {
        if (handled) return;
        handled = true;
        var resource = prepared;
        prepared = null;
        resource.close();
    }
}

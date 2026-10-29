package mmm.fluid;

import java.util.Objects;
import mmm.world.MovingBodyFluidCoupling;

/** A complete device successor, adopted once or released without changing the committed generation. */
public final class FluidThermalStepCandidate implements AutoCloseable {
    private final String backendId;
    private final long baseGeneration;
    private final FluidDomainState.Layout layout;
    private final FluidDomainContracts.ThermalAxisStats stats;
    private PreparedFluidState prepared;
    private FluidDomainState snapshot;
    private boolean handled;

    private FluidThermalStepCandidate(String backendId, long baseGeneration, PreparedFluidState prepared,
            FluidDomainContracts.ThermalAxisStats stats) {
        if (backendId == null || backendId.isBlank() || baseGeneration < 0)
            throw new IllegalArgumentException("Candidate identity is invalid");
        Math.addExact(baseGeneration, 1);
        this.backendId = backendId;
        this.baseGeneration = baseGeneration;
        this.prepared = Objects.requireNonNull(prepared);
        this.layout = prepared.layout();
        this.stats = Objects.requireNonNull(stats);
    }

    public static FluidThermalStepCandidate preparedDevice(String backendId, long baseGeneration, PreparedFluidState state,
            FluidDomainContracts.ThermalAxisStats stats) {
        return new FluidThermalStepCandidate(backendId, baseGeneration, state, stats);
    }

    public String backendId() { return backendId; }
    public long baseGeneration() { return baseGeneration; }
    public long candidateGeneration() { return baseGeneration + 1; }
    public FluidDomainState.Layout layout() { return layout; }
    public FluidDomainContracts.ThermalAxisStats stats() { return stats; }

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

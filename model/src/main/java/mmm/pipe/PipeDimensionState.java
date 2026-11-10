package mmm.pipe;

import java.util.Objects;

/** One dimension's pipe inventories and conservation ledger, published together. */
public record PipeDimensionState(long generation, PipeNetworkState network, PipeAudit audit,
                                 java.util.Map<String,PipeInstallation> installations) {
    public PipeDimensionState(long generation, PipeNetworkState network, PipeAudit audit) {
        this(generation,network,audit,java.util.Map.of());
    }
    public PipeDimensionState {
        if (generation < 0) throw new IllegalArgumentException("Negative pipe generation");
        Objects.requireNonNull(network);
        Objects.requireNonNull(audit);
        installations = java.util.Map.copyOf(installations);
        var ids=network.segments().stream().map(PipeSegmentState::id).collect(java.util.stream.Collectors.toSet());
        if (!ids.containsAll(installations.keySet())) throw new IllegalArgumentException("Pipe installation has no inventory");
    }
}

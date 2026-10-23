package mmm.fluid;

import java.util.Objects;

/** Installs a compute provider for synchronous physical candidates on the current thread. */
public final class ComputeExecution implements AutoCloseable {
    private static final ThreadLocal<ComputeExecution> CURRENT = new ThreadLocal<>();
    private final Thread owner = Thread.currentThread();
    private final ComputeExecution previous;
    private final SparseMatrixCompute compute;
    private boolean closed;

    private ComputeExecution(SparseMatrixCompute compute) {
        this.compute = compute;
        previous = CURRENT.get();
        CURRENT.set(this);
    }

    public static ComputeExecution install(SparseMatrixCompute compute) {
        return new ComputeExecution(Objects.requireNonNull(compute));
    }

    /** Temporarily selects CPU pressure execution, including inside an injected scope. */
    public static ComputeExecution useCpu() { return new ComputeExecution(null); }

    static SparseMatrixCompute current() {
        var execution = CURRENT.get();
        return execution == null ? null : execution.compute;
    }

    @Override public void close() {
        if (closed) return;
        if (Thread.currentThread() != owner || CURRENT.get() != this)
            throw new IllegalStateException("Compute execution scopes must close in order on their installing thread");
        if (previous == null) CURRENT.remove();
        else CURRENT.set(previous);
        closed = true;
    }
}

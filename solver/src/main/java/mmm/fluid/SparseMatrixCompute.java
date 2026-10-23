package mmm.fluid;

/** Execution provider for assembled pressure CSR products. Failures propagate to the caller. */
public interface SparseMatrixCompute extends AutoCloseable {
    /** Explicit device loss: the uncommitted physical candidate can restart from its request. */
    class DeviceLostException extends IllegalStateException {
        public DeviceLostException(String message) { super(message); }
        public DeviceLostException(String message, Throwable cause) { super(message, cause); }
    }

    interface Provider {
        String id();
        SparseMatrixCompute create(int device);
    }

    @Override default void close() { }

    default mmm.inventory.LiquidQuantityCompute liquidQuantityCompute() { return null; }

    /**
     * Borrows read-only CSR arrays until the returned matrix is closed. Arrays may have spare
     * capacity; rowStarts[size] is the entry count. Only the first activeRowCount rows are used.
     */
    Matrix prepare(int size, int[] rowStarts, int[] columns, double[] values,
                   int[] activeRows, int activeRowCount);

    /**
     * Synchronous active-set pressure solve inputs, borrowed read-only until solve returns.
     * Unknowns occupy the first unknownCount entries; other arrays contain at least size entries.
     * Bounded rows use lowerBounds in the increment-pressure convention. The stopping rule,
     * active-set constraints and refreshed residual follow PressureLinearSolver; the provider
     * chooses its preconditioner within the supplied iteration budget.
     */
    record PressureProblem(int size, int[] unknowns, int unknownCount, boolean[] bounded,
                           double[] lowerBounds, double[] rhs, double[] initialPressure,
                           double tolerance, int maxIterations) { }

    /** The complete pressure vector and final refreshed projected residual. */
    record PressureSolution(double[] pressure, int iterations, double residualRms, boolean converged) { }

    interface Matrix extends AutoCloseable {
        /** Returns null when this matrix delegates iteration to the caller. */
        default PressureSolution solve(PressureProblem problem) { return null; }

        /** Writes the active destination rows; source and destination are distinct arrays. */
        void multiply(double[] source, double[] destination);
        @Override void close();
    }
}

package mmm.fluid;

/** Short resource/failure checks for a candidate's injected pressure execution. */
public final class ComputeExecutionValidation {
    public static void main(String[] args) {
        int[] calls = new int[3];
        SparseMatrixCompute first = (size, starts, columns, values, rows, count) -> {
            calls[0]++;
            return new SparseMatrixCompute.Matrix() {
                public void multiply(double[] source, double[] destination) {
                    calls[1]++;
                    for (int ordinal = 0; ordinal < count; ordinal++) {
                        int row = rows[ordinal];
                        double sum = 0;
                        for (int edge = starts[row]; edge < starts[row + 1]; edge++)
                            sum += values[edge] * source[columns[edge]];
                        destination[row] = sum;
                    }
                }
                public void close() { calls[2]++; }
            };
        };
        SparseMatrixCompute failing = (size, starts, columns, values, rows, count) -> {
            throw new IllegalStateException("device failure");
        };
        var matrix = new PressureSparseMatrix();
        try (var outer = ComputeExecution.install(first)) {
            matrix.begin(3, 2);
            matrix.activate(0); matrix.activate(2);
            matrix.add(0, 0, 2); matrix.add(0, 2, -1);
            matrix.add(2, 0, -1); matrix.add(2, 2, 3);
            matrix.finish();
            try (var inner = ComputeExecution.install(failing)) {
                try {
                    matrix.prepareCompute();
                    throw new AssertionError("Device failure was swallowed");
                } catch (IllegalStateException expected) {
                    if (!expected.getMessage().equals("device failure")) throw expected;
                }
            }
            matrix.prepareCompute();
            double[] result = {0, 17, 0};
            try {
                matrix.multiply(new double[]{4, 0, 5}, result);
                if (result[0] != 3 || result[1] != 17 || result[2] != 11)
                    throw new AssertionError("Injected CSR product changed active-row semantics");
            } finally { matrix.closeCompute(); }
            matrix.closeCompute();
        }
        if (ComputeExecution.current() != null || calls[0] != 1 || calls[1] != 1 || calls[2] != 1)
            throw new AssertionError("Compute execution scope or resource lifetime leaked");
        System.out.println("Compute execution resource and failure checks passed");
    }
}

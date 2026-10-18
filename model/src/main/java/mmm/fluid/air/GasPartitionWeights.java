package mmm.fluid.air;

import mmm.chemistry.ExactRational;
import mmm.units.SignedDyadic;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;

/** Floating geometry normalized once to bounded integer shares for conservative inventory transfer. */
public final class GasPartitionWeights {
    public static final class UnrepresentableVolumeException extends IllegalArgumentException {
        private UnrepresentableVolumeException(String message) { super(message); }
    }
    private static final long SCALE = 1L << 52;
    private final long[] weights;
    private final int pivot;

    private GasPartitionWeights(double[] values) {
        if (values == null || values.length == 0) throw new IllegalArgumentException("Gas partition needs children");
        weights = new long[values.length];
        int largest = 0;
        for (int i = 0; i < values.length; i++) {
            if (!(Double.isFinite(values[i]) && values[i] >= 0)) throw new IllegalArgumentException("Invalid gas partition weight");
            if (values[i] > values[largest]) largest = i;
        }
        pivot = largest;
        if (!(values[pivot] > 0)) throw new IllegalArgumentException("Gas partition weights sum to zero");
        double sum = 0;
        for (double value : values) sum += value / values[pivot];
        long assigned = 0;
        for (int i = 0; i < weights.length; i++) if (i != pivot && values[i] > 0) {
            // Every reachable positive child retains a share; quantities smaller than a quantum
            // follow the same largest-remainder allocation as all other children.
            weights[i] = Math.max(1L, (long) ((values[i] / values[pivot] / sum) * SCALE));
            assigned = Math.addExact(assigned, weights[i]);
        }
        weights[pivot] = SCALE - assigned;
        if (weights[pivot] <= 0) throw new IllegalArgumentException("Too many gas partition children");
    }
    public static GasPartitionWeights of(double[] weights) { return new GasPartitionWeights(weights); }
    public static GasPartitionWeights ofRationals(List<ExactRational> weights) {
        if (weights == null) throw new IllegalArgumentException("Gas partition weights are missing");
        return of(weights.stream().mapToDouble(ExactRational::toDouble).toArray());
    }
    public int childCount() { return weights.length; }

    /** Bounded integer quotients and remainders preserve every quantity, including one-nano stock. */
    public long[] allocateNanos(long total) {
        if (total < 0) throw new IllegalArgumentException("Negative gas partition amount");
        long[] shares = new long[weights.length];
        long[] remainders = new long[weights.length];
        BigInteger amount = BigInteger.valueOf(total), divisor = BigInteger.valueOf(SCALE);
        long assigned = 0;
        for (int i = 0; i < weights.length; i++) {
            // Both operands have fixed bounds: inventory <= 63 bits and weights <= 53 bits.
            var qr = amount.multiply(BigInteger.valueOf(weights[i])).divideAndRemainder(divisor);
            shares[i] = qr[0].longValueExact(); remainders[i] = qr[1].longValueExact();
            assigned = Math.addExact(assigned, shares[i]);
        }
        Integer[] order = new Integer[weights.length]; Arrays.setAll(order, i -> i);
        Arrays.sort(order, (a, b) -> {
            int comparison = Long.compare(remainders[b], remainders[a]);
            return comparison != 0 ? comparison : Integer.compare(a, b);
        });
        long remaining = total - assigned;
        if (remaining < 0 || remaining >= weights.length) throw new IllegalStateException("Gas partition failed to close");
        for (int i = 0; i < remaining; i++) shares[order[i]]++;
        return shares;
    }
    public double[] partitionControlVolume(double total) {
        if (!Double.isFinite(total) || total < 0) throw new IllegalArgumentException("Invalid gas control volume");
        double[] result = new double[weights.length];
        if (total == 0) return result;
        SignedDyadic assigned = SignedDyadic.ZERO;
        for (int i = 0; i < result.length; i++) if (i != pivot && weights[i] > 0) {
            result[i] = total * (weights[i] / (double) SCALE); requirePositive(result[i]);
            assigned = assigned.add(SignedDyadic.of(result[i]));
        }
        result[pivot] = SignedDyadic.of(total).subtract(assigned).toDouble();
        requirePositive(result[pivot]);
        return result;
    }
    private static void requirePositive(double value) {
        if (!(Double.isFinite(value) && value > 0)) throw new UnrepresentableVolumeException("Positive gas volume has no finite positive representation");
    }
}

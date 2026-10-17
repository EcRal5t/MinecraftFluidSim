package mmm.thermal;

import java.util.function.LongPredicate;

/** Bounded pressure-equilibrium search with exact inventory portions at every sampled extent. */
final class GasTransferSearch {
    private GasTransferSearch() { }

    static long maximum(long requested, LongPredicate permits) {
        if (requested <= 0) return 0;
        if (permits.test(requested)) return requested;
        long low = 0, high = requested;
        // At macroscopic stock, component-rounding holes are below the resolved flow scale.
        // Keep an accepted lower endpoint, then inspect the final small integer interval.
        while (high - low > 128) {
            long middle = low + (high - low) / 2;
            if (permits.test(middle)) low = middle;
            else high = middle;
        }
        for (long amount = high - 1; amount > low; amount--)
            if (permits.test(amount)) return amount;
        return low;
    }
}

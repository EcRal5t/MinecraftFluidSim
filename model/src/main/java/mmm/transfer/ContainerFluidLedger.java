package mmm.transfer;

/**
 * Exact container volume arithmetic: one nano is 10⁻⁹ m³.
 * Callers convert normalized liquid volumes through SolverUnitScale before encoding.
 * Encoding rounds physical m³ to the nearest nano, within half a nano.
 * Decoding returns physical m³; callers apply the destination cell scale before creating liquid markers.
 */
public final class ContainerFluidLedger {
	/** One block volume = one bucket capacity = 1 m³ = 1e9 nanovolume quanta. */
	public static final long NANOS_PER_BLOCK = 1_000_000_000L;
	private static final double MAX_SCALABLE = 9.0E18;

	private ContainerFluidLedger() { }

	/** Rounds a physical volume in m³ to integer nanos, with at most half a nano of rounding. */
	public static long cubicMetersToNanos(double cubicMeters) {
		if (!Double.isFinite(cubicMeters) || cubicMeters < 0.0) {
			throw new IllegalArgumentException("Volume is not a finite non-negative number: " + cubicMeters);
		}
		double scaled = cubicMeters * 1_000_000_000.0;
		if (scaled >= MAX_SCALABLE) {
			throw new IllegalArgumentException("Volume overflows the container ledger: " + cubicMeters);
		}
		return Math.round(scaled);
	}

	/** Exact-quanta volume as the nearest double cubic metres. */
	public static double nanosToCubicMeters(long nanos) {
		requireNonNegative(nanos);
		return nanos / 1_000_000_000.0;
	}

	/** Atomic fill of {@code destination} up to {@code capacity} from {@code source}. */
	public static long transferable(long destination, long source, long capacity) {
		requireNonNegative(destination);
		requireNonNegative(source);
		if (capacity <= 0L) throw new IllegalArgumentException("Capacity must be positive: " + capacity);
		if (destination > capacity) throw new IllegalArgumentException("Destination exceeds capacity");
		return Math.min(capacity - destination, source);
	}

	public static long checkedAdd(long a, long b) {
		return Math.addExact(a, b);
	}

	public static long checkedSubtract(long a, long b) {
		return Math.subtractExact(a, b);
	}

	/** Partial containers hold strictly between empty and one full bucket; invalid values return false. */
	public static boolean isPartialBucket(long nanos) {
		return nanos > 0L && nanos < NANOS_PER_BLOCK;
	}

	public static boolean isFullBucket(long nanos) {
		return nanos == NANOS_PER_BLOCK;
	}

	/** Exact two-bucket merge: the output is filled up to one bucket, the rest stays as remainder. */
	public static long mergeOutput(long firstNanos, long secondNanos) {
		requireNonNegative(firstNanos);
		requireNonNegative(secondNanos);
		return Math.min(Math.addExact(firstNanos, secondNanos), NANOS_PER_BLOCK);
	}

	public static long mergeRemainder(long firstNanos, long secondNanos) {
		return Math.addExact(firstNanos, secondNanos) - mergeOutput(firstNanos, secondNanos);
	}


	/** Read-only durability-bar width for a partial volume; invalid volumes show the minimum. */
	public static int barWidth(long nanos) {
		if (nanos <= 0L) return 1;
		return (int) Math.min(13L, Math.max(1L, Math.round(13.0 * nanos / NANOS_PER_BLOCK)));
	}

	private static void requireNonNegative(long nanos) {
		if (nanos < 0L) throw new IllegalArgumentException("Nanovolume must not be negative: " + nanos);
	}
}

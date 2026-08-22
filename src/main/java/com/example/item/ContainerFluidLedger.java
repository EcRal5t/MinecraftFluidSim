package com.example.item;

/**
 * Pure-Java container volume arithmetic on nanovolume quanta (1e-9 m³). The container-side truth
 * is always an exact {@code long}; the region-side truth stays the float marker
 * {@code particleVolume} sum. Conversions between the two stores round to the nearest quantum and
 * the bound is documented per transfer instead of being hidden: a double-exact marker sum maps to
 * within half a quantum, and re-created marker volumes round to the nearest float.
 */
public final class ContainerFluidLedger {
	/** One block volume = one bucket capacity = 1 m³ = 1e9 nanovolume quanta. */
	public static final long NANOS_PER_BLOCK = 1_000_000_000L;
	private static final double MAX_SCALABLE = 9.0E18;

	private ContainerFluidLedger() { }

	/** Rounds a non-negative double volume (e.g. a double-summed marker total) to quanta. */
	public static long volumeToNanos(double volume) {
		if (Double.isNaN(volume) || Double.isInfinite(volume) || volume < 0.0) {
			throw new IllegalArgumentException("Volume is not a finite non-negative number: " + volume);
		}
		double scaled = volume * 1_000_000_000.0;
		if (scaled >= MAX_SCALABLE) {
			throw new IllegalArgumentException("Volume overflows the container ledger: " + volume);
		}
		return Math.round(scaled);
	}

	/** Exact-quanta volume as the nearest double cubic metres. */
	public static double nanosToVolume(long nanos) {
		requireNonNegative(nanos);
		return nanos / 1_000_000_000.0;
	}

	/** Exact-quanta volume as the nearest float, the form new markers are created with. */
	public static float nanosToFloatVolume(long nanos) {
		return (float) nanosToVolume(nanos);
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

	/** Partial containers hold strictly between empty and one full bucket. */
	public static boolean isPartialBucket(long nanos) {
		requireNonNegative(nanos);
		return nanos > 0L && nanos < NANOS_PER_BLOCK;
	}

	public static boolean isFullBucket(long nanos) {
		return nanos == NANOS_PER_BLOCK;
	}

	private static void requireNonNegative(long nanos) {
		if (nanos < 0L) throw new IllegalArgumentException("Nanovolume must not be negative: " + nanos);
	}
}

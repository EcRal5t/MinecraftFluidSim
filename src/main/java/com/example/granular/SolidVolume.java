package com.example.granular;

/** Exact M8 solid-volume units. These constants are valid only for the frozen 0.5 m solver cell. */
public final class SolidVolume {
	public static final long SVQ_PER_BV = 1L << 27;
	public static final long SVQ_PER_CELL = 1L << 24;
	public static final long NANOS_PER_BV = 1_000_000_000L;

	private SolidVolume() { }

	public static float toCellVolume(long svq) {
		if (svq < 0L) throw new IllegalArgumentException("Solid volume cannot be negative");
		float result = Math.scalb((float) svq, -24);
		if (!Float.isFinite(result)) throw new IllegalArgumentException("Solid volume is not float-representable");
		return result;
	}

	public static long fromCellVolumeExact(float cellVolume) {
		if (!(Float.isFinite(cellVolume) && cellVolume >= 0.0F)) {
			throw new IllegalArgumentException("Cell volume must be finite and non-negative");
		}
		double scaled = Math.scalb((double) cellVolume, 24);
		if (scaled > Long.MAX_VALUE || scaled != Math.rint(scaled)) {
			throw new IllegalArgumentException("Cell volume is not an exact SVQ multiple");
		}
		return (long) scaled;
	}

	/** Largest solid amount no greater than the supplied container nanovolume. */
	public static long nanosToSvqAtMost(long nanos) {
		if (nanos < 0L) throw new IllegalArgumentException("Nanovolume cannot be negative");
		long whole = nanos / NANOS_PER_BV;
		long remainder = nanos % NANOS_PER_BV;
		return Math.addExact(Math.multiplyExact(whole, SVQ_PER_BV),
				Math.multiplyExact(remainder, SVQ_PER_BV) / NANOS_PER_BV);
	}

	/** Largest container nanovolume no greater than the supplied exact solid amount. */
	public static long svqToNanosAtMost(long svq) {
		if (svq < 0L) throw new IllegalArgumentException("Solid volume cannot be negative");
		long whole = svq / SVQ_PER_BV;
		long remainder = svq % SVQ_PER_BV;
		return Math.addExact(Math.multiplyExact(whole, NANOS_PER_BV),
				Math.multiplyExact(remainder, NANOS_PER_BV) / SVQ_PER_BV);
	}

	public static long bulkVolumeCeil(long skeletonSvq, int porosityQ) {
		if (skeletonSvq < 0L) throw new IllegalArgumentException("Skeleton volume cannot be negative");
		requirePorosity(porosityQ);
		if (skeletonSvq == 0L) return 0L;
		long denominator = GranularLimits.POROSITY_SCALE - (long) porosityQ;
		long numerator = Math.multiplyExact(skeletonSvq, GranularLimits.POROSITY_SCALE);
		return Math.addExact(numerator, denominator - 1L) / denominator;
	}

	static void requirePorosity(int porosityQ) {
		if (porosityQ <= 0 || porosityQ >= GranularLimits.POROSITY_SCALE) {
			throw new IllegalArgumentException("Porosity must be within (0, "
					+ GranularLimits.POROSITY_SCALE + ")");
		}
	}
}

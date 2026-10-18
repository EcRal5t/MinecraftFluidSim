package mmm.granular;

/** Solid inventory uses 2^-27 m³ per SVQ; normalized cell conversions use the default 0.5 m cell. */
public final class SolidVolume {
	public static final long SVQ_PER_CUBIC_METER = 1L << 27;
	public static final long SVQ_PER_CELL = 1L << 24;
	public static final long NANOS_PER_CUBIC_METER = 1_000_000_000L;

	private SolidVolume() { }

	/** Integer occupancy ceiling of the exact liquid ledger on the common solid-volume grid. */
	public static long cellVolumeSvqCeil(mmm.units.SignedDyadic volume) {
		if (volume.signum() < 0) throw new IllegalArgumentException("Occupied volume cannot be negative");
		int exponent = volume.exponent() + 24;
		if (exponent >= 0) return volume.significand().shiftLeft(exponent).longValueExact();
		var division = volume.significand().divideAndRemainder(java.math.BigInteger.ONE.shiftLeft(-exponent));
		return division[0].add(division[1].signum() == 0 ? java.math.BigInteger.ZERO : java.math.BigInteger.ONE).longValueExact();
	}

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

	/** Whole SVQ that can be removed without quantizing a marker's authoritative float tail. */
	public static long cellVolumeSvqAtMost(float cellVolume) {
		double scaled = scaledCellVolume(cellVolume);
		return (long) Math.floor(scaled);
	}

	/** Conservative SVQ occupancy bound that retains a marker's authoritative float tail. */
	public static long cellVolumeSvqAtLeast(float cellVolume) {
		double scaled = scaledCellVolume(cellVolume);
		return (long) Math.ceil(scaled);
	}

	private static double scaledCellVolume(float cellVolume) {
		if (!(Float.isFinite(cellVolume) && cellVolume >= 0.0F)) {
			throw new IllegalArgumentException("Cell volume must be finite and non-negative");
		}
		double scaled = Math.scalb((double) cellVolume, 24);
		if (scaled > Long.MAX_VALUE) throw new IllegalArgumentException("Cell volume exceeds the SVQ bound range");
		return scaled;
	}

	/** Applies an integer-SVQ delta without quantizing away an existing marker's sub-SVQ dyadic tail. */
	public static float addCellVolumeSvqExact(float cellVolume, long signedSvq) {
		if (!(Float.isFinite(cellVolume) && cellVolume > 0.0F)) {
			throw new IllegalArgumentException("Marker cell volume must be finite and positive");
		}
		double exact = (double) cellVolume + Math.scalb((double) signedSvq, -24);
		if (!(Double.isFinite(exact) && exact > 0.0 && exact <= Float.MAX_VALUE)) {
			throw new IllegalArgumentException("SVQ delta would make the marker volume invalid");
		}
		float result = (float) exact;
		double representedDelta = Math.scalb((double) result - cellVolume, 24);
		if (representedDelta != signedSvq) {
			throw new IllegalArgumentException("SVQ delta is not exactly representable for this marker volume");
		}
		return result;
	}

	/** Largest solid amount no greater than the supplied container nanovolume. */
	public static long nanosToSvqAtMost(long nanos) {
		if (nanos < 0L) throw new IllegalArgumentException("Nanovolume cannot be negative");
		long whole = nanos / NANOS_PER_CUBIC_METER;
		long remainder = nanos % NANOS_PER_CUBIC_METER;
		return Math.addExact(Math.multiplyExact(whole, SVQ_PER_CUBIC_METER),
				Math.multiplyExact(remainder, SVQ_PER_CUBIC_METER) / NANOS_PER_CUBIC_METER);
	}

	/** Largest container nanovolume no greater than the supplied exact solid amount. */
	public static long svqToNanosAtMost(long svq) {
		if (svq < 0L) throw new IllegalArgumentException("Solid volume cannot be negative");
		long whole = svq / SVQ_PER_CUBIC_METER;
		long remainder = svq % SVQ_PER_CUBIC_METER;
		return Math.addExact(Math.multiplyExact(whole, NANOS_PER_CUBIC_METER),
				Math.multiplyExact(remainder, NANOS_PER_CUBIC_METER) / SVQ_PER_CUBIC_METER);
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

package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.units.SignedDyadic;

/** Keeps positive small mass shares and assigns binary64 reduction roundoff to the largest share. */
final class CellRemapMassAllocation {
	private CellRemapMassAllocation() { }

	static double inventory(FluidDomain domain, java.util.ArrayList<Integer> ordinals) {
		int exponent = Integer.MAX_VALUE;
		for (int p : ordinals) {
			long bits = Double.doubleToRawLongBits(domain.particles.mass(p)), fraction = bits & 0x000fffffffffffffL;
			int rawExponent = (int) (bits >>> 52 & 0x7ff);
			if (rawExponent != 0) fraction |= 0x0010000000000000L;
			exponent = Math.min(exponent, (rawExponent == 0 ? -1074 : rawExponent - 1075) + Long.numberOfTrailingZeros(fraction));
		}
		long sum = 0;
		for (int p : ordinals) {
			double scaled = Math.scalb(domain.particles.mass(p), -exponent);
			if (scaled >= Long.MAX_VALUE || sum > Long.MAX_VALUE - (long) scaled) {
				double[] values = new double[ordinals.size()];
				for (int i = 0; i < values.length; i++) values[i] = domain.particles.mass(ordinals.get(i));
				return SignedDyadic.sum(values).toDouble();
			}
			sum += (long) scaled;
		}
		return SignedDyadic.of(sum, exponent).toDouble();
	}

	static double[] allocate(double mass, SignedDyadic volume, SignedDyadic[] weights) {
		return allocate(mass, volume, weights, Double.NaN);
	}

	/** Uniform density is retained only when every stored mass is an exact binary64 product. */
	static double density(FluidDomain domain, java.util.ArrayList<Integer> ordinals, SignedDyadic volume, double mass) {
		int first = ordinals.getFirst();
		double density = domain.particles.mass(first) / domain.particles.volume(first);
		if (!(Double.isFinite(density) && density > 0) || exactMass(density, volume) != mass) return Double.NaN;
		for (int p : ordinals) if (exactMass(density, SignedDyadic.of(domain.particles.volume(p))) != domain.particles.mass(p))
			return Double.NaN;
		return density;
	}

	static double exactMass(double density, SignedDyadic volume) {
		if (!(Double.isFinite(density) && density > 0) || volume.signum() < 0) return Double.NaN;
		if (volume.signum() == 0) return 0;
		if (!volume.fitsLongUnits(volume.exponent())) return Double.NaN;
		long bits = Double.doubleToRawLongBits(density), units = bits & 0x000fffffffffffffL;
		int rawExponent = (int) (bits >>> 52 & 0x7ff);
		if (rawExponent != 0) units |= 0x0010000000000000L;
		int trailing = Long.numberOfTrailingZeros(units); units >>>= trailing;
		long volumeUnits = volume.longUnitsExact(volume.exponent());
		// Both canonical significands are odd: a product over 53 bits cannot be stored exactly.
		if (volumeUnits > 0x001fffffffffffffL / units) return Double.NaN;
		long product = units * volumeUnits;
		long exponent = (long) volume.exponent() + (rawExponent == 0 ? -1074 : rawExponent - 1075) + trailing;
		if (exponent < -1074 || exponent + Long.SIZE - Long.numberOfLeadingZeros(product) - 1 > 1023) return Double.NaN;
		return Math.scalb((double) product, (int) exponent);
	}

	static double[] allocate(double mass, SignedDyadic volume, SignedDyadic[] weights, double density) {
		if (exactMass(density, volume) == mass) {
			double[] result = new double[weights.length]; boolean exact = true;
			for (int i = 0; i < result.length; i++) {
				result[i] = exactMass(density, weights[i]); exact &= Double.isFinite(result[i]);
			}
			if (exact) return result;
		}
		int largest = largest(weights); double total = volume.toDouble();
		double[] result = new double[weights.length];
		for (int i = 0; i < result.length; i++) if (i != largest && weights[i].signum() > 0) {
			result[i] = mass * (weights[i].toDouble() / total);
		}
		result[largest] = SignedDyadic.of(mass).subtract(SignedDyadic.sum(result)).toDouble();
		for (int i = 0; i < result.length; i++) if (weights[i].signum() > 0 && !(Double.isFinite(result[i]) && result[i] > 0))
			throw new IllegalStateException("Positive liquid volume has no representable positive mass share");
		return result;
	}
	static int largest(SignedDyadic[] weights) {
		int largest = 0;
		for (int i = 1; i < weights.length; i++) if (weights[i].compareTo(weights[largest]) > 0) largest = i;
		return largest;
	}
}

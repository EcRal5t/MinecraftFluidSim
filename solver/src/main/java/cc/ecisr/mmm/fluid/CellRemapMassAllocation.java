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
		int largest = largest(weights); double remaining = mass, total = volume.toDouble();
		double[] result = new double[weights.length];
		for (int i = 0; i < result.length; i++) if (i != largest && weights[i].signum() > 0) {
			result[i] = mass * (weights[i].toDouble() / total); remaining -= result[i];
		}
		result[largest] = remaining;
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

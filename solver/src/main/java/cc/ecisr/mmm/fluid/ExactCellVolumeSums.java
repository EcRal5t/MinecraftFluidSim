package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.units.SignedDyadic;

/** Exact raw binary32 sums over an existing cell index, with a bounded integer fast path. */
final class ExactCellVolumeSums {
	private ExactCellVolumeSums() { }

	static SignedDyadic[] sum(float[] volume, int count, int[] heads, int[] next) {
		for (int p = 0; p < count; p++) {
			float value = volume[p];
			if (value != 0 && (!Float.isFinite(value) || value < 0)) throw new IllegalArgumentException("Cell volume requires finite positive parcels");
		}
		SignedDyadic[] sums = new SignedDyadic[heads.length];
		for (int c = 0; c < heads.length; c++) {
			int exponent = cellUnitExponent(volume, heads[c], next);
			long sum = 0; boolean wide = false;
			for (int p = heads[c]; p >= 0; p = next[p]) {
				double scaled = Math.scalb((double) volume[p], -exponent);
				if (scaled >= Long.MAX_VALUE || sum > Long.MAX_VALUE - (long) scaled) { wide = true; break; }
				sum += (long) scaled;
			}
			if (!wide) sums[c] = sum == 0 ? SignedDyadic.ZERO : SignedDyadic.of(sum, exponent);
			else {
				SignedDyadic exact = SignedDyadic.ZERO;
				for (int p = heads[c]; p >= 0; p = next[p]) exact = exact.add(SignedDyadic.of(volume[p]));
				sums[c] = exact;
			}
		}
		return sums;
	}

	static int cellUnitExponent(float[] volume, int head, int[] next) {
		int exponent = 0;
		for (int p = head; p >= 0; p = next[p]) {
			int bits = Float.floatToRawIntBits(volume[p]), rawExponent = bits >>> 23 & 255;
			int significand = bits & 0x7fffff;
			if (rawExponent != 0) significand |= 0x800000;
			if (significand == 0) continue;
			exponent = Math.min(exponent, (rawExponent == 0 ? -149 : rawExponent - 150) + Integer.numberOfTrailingZeros(significand));
		}
		return exponent;
	}
}

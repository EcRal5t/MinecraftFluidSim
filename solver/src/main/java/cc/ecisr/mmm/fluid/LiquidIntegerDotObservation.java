package cc.ecisr.mmm.fluid;

import java.math.BigInteger;

/** Correctly rounded integer dot-product ratios, certified by outward integer intervals. */
final class LiquidIntegerDotObservation {
	private LiquidIntegerDotObservation() { }
	static double rounded(BigInteger[] coefficient, BigInteger[] value, BigInteger denominator) {
		if (coefficient.length != value.length || denominator.signum() <= 0) throw new IllegalArgumentException("Incomplete integer dot ratio");
		int productBits = 0;
		for (int i = 0; i < value.length; i++) if (coefficient[i].signum() != 0 && value[i].signum() != 0)
			productBits = Math.max(productBits, Math.addExact(coefficient[i].abs().bitLength(), value[i].abs().bitLength()));
		if (productBits == 0) return 0;
		int maximum = Math.max(productBits, denominator.bitLength());
		int precision = Math.min(maximum, 53 + 32 - Integer.numberOfLeadingZeros(value.length));
		for (;;) {
			int shift = Math.max(0, productBits - precision); var lower = BigInteger.ZERO; var upper = lower;
			for (int i = 0; i < value.length; i++) {
				int sign = coefficient[i].signum() * value[i].signum(); if (sign == 0) continue;
				var a = coefficient[i].abs(); var b = value[i].abs();
				int aShift = Math.max(0, a.bitLength() - precision), bShift = Math.max(0, b.bitLength() - precision);
				var aLow = a.shiftRight(aShift); var bLow = b.shiftRight(bShift);
				var aHigh = a.getLowestSetBit() < aShift ? aLow.add(BigInteger.ONE) : aLow;
				var bHigh = b.getLowestSetBit() < bShift ? bLow.add(BigInteger.ONE) : bLow;
				int rest = shift - aShift - bShift;
				var lo = aLow.multiply(bLow).shiftRight(rest); var hiProduct = aHigh.multiply(bHigh);
				var hi = hiProduct.shiftRight(rest); if (hiProduct.getLowestSetBit() < rest) hi = hi.add(BigInteger.ONE);
				lower = lower.add(sign > 0 ? lo : hi.negate()); upper = upper.add(sign > 0 ? hi : lo.negate());
			}
			int denominatorShift = Math.max(0, denominator.bitLength() - precision);
			var dLow = denominator.shiftRight(denominatorShift);
			var dHigh = denominator.getLowestSetBit() < denominatorShift ? dLow.add(BigInteger.ONE) : dLow;
			double lo = ratio(lower, lower.signum() < 0 ? dLow : dHigh, shift - denominatorShift);
			double hi = ratio(upper, upper.signum() < 0 ? dHigh : dLow, shift - denominatorShift);
			if (Double.doubleToRawLongBits(lo) == Double.doubleToRawLongBits(hi)) return lo;
			if (precision == maximum) throw new IllegalStateException("Exact integer dot interval did not close");
			precision = (int) Math.min(maximum, 2L * precision);
		}
	}
	private static double ratio(BigInteger numerator, BigInteger denominator, int shift) {
		return LiquidExactPolygon.roundedRatio(shift > 0 ? numerator.shiftLeft(shift) : numerator,
				shift < 0 ? denominator.shiftLeft(-shift) : denominator);
	}
}

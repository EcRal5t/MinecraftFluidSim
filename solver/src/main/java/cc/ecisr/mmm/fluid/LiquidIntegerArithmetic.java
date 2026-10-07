package cc.ecisr.mmm.fluid;

import java.math.BigInteger;

/** Exact Lehmer batches for large geometric integers; every accepted matrix is unimodular. */
final class LiquidIntegerArithmetic {
	private LiquidIntegerArithmetic() { }
	static BigInteger gcd(BigInteger first, BigInteger second) {
		var a = first.abs(); var b = second.abs();
		if (a.compareTo(b) < 0) { var swap = a; a = b; b = swap; }
		// The JDK's binary GCD is efficient for small operands; batching reduces whole-integer operations.
		while (b.bitLength() > 128) {
			int shift = a.bitLength() - 62;
			long x = a.shiftRight(shift).longValue(), y = b.shiftRight(shift).longValue();
			long aa = 1, ab = 0, ba = 0, bb = 1;
			while (y + ba > 0 && y + bb > 0) {
				long quotient = (x + aa) / (y + ba);
				if (quotient <= 0 || quotient != (x + ab) / (y + bb)) break;
				long nextBa, nextBb, nextY;
				try {
					nextBa = Math.subtractExact(aa, Math.multiplyExact(quotient, ba));
					nextBb = Math.subtractExact(ab, Math.multiplyExact(quotient, bb));
					nextY = Math.subtractExact(x, Math.multiplyExact(quotient, y));
				} catch (ArithmeticException overflow) { break; }
				// Signed 32-bit coefficients keep subsequent leading-word bounds within signed 64-bit arithmetic.
				if (nextY < 0 || nextBa < -Integer.MAX_VALUE || nextBa > Integer.MAX_VALUE || nextBb < -Integer.MAX_VALUE || nextBb > Integer.MAX_VALUE) break;
				aa = ba; ab = bb; ba = nextBa; bb = nextBb; x = y; y = nextY;
			}
			var nextA = a.multiply(BigInteger.valueOf(aa)).add(b.multiply(BigInteger.valueOf(ab)));
			var nextB = a.multiply(BigInteger.valueOf(ba)).add(b.multiply(BigInteger.valueOf(bb)));
			// Matrix row operations preserve GCD exactly. Full operands must also prove ordered, strict progress.
			if (ab != 0 && nextB.signum() >= 0 && nextA.compareTo(nextB) >= 0 && nextA.compareTo(a) < 0) { a = nextA; b = nextB; }
			else { var remainder = a.remainder(b); a = b; b = remainder; }
		}
		return a.gcd(b);
	}
}

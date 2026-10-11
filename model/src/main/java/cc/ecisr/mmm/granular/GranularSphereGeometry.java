package cc.ecisr.mmm.granular;

import cc.ecisr.mmm.units.SignedDyadic;
import java.math.BigInteger;
import java.util.ArrayList;

/** Shared closed sphere/AABB classification for placement and bulk-bed admission. */
public final class GranularSphereGeometry {
	private GranularSphereGeometry() { }

	public static boolean touchesBox(double x, double y, double z, double radius,
	                                double minX, double minY, double minZ,
	                                double maxX, double maxY, double maxZ) {
		double dx = x - Math.max(minX, Math.min(maxX, x));
		double dy = y - Math.max(minY, Math.min(maxY, y));
		double dz = z - Math.max(minZ, Math.min(maxZ, z));
		return dx * dx + dy * dy + dz * dz <= radius * radius;
	}

	/** Exact dyadic segment/AABB interior intersection; face, edge and corner tangency remain legal. */
	public static boolean pathEntersBox(double[] from, double[] to, double radius, double[] minimum, double[] maximum) {
		if (!(Double.isFinite(radius) && radius >= 0)) throw new IllegalArgumentException("Sphere path radius is invalid");
		SignedDyadic[] inputs = new SignedDyadic[13]; int unit = 0;
		for (int a = 0; a < 3; a++) {
			inputs[a] = SignedDyadic.of(from[a]); inputs[a + 3] = SignedDyadic.of(to[a]);
			inputs[a + 6] = SignedDyadic.of(minimum[a]); inputs[a + 9] = SignedDyadic.of(maximum[a]);
		}
		inputs[12] = SignedDyadic.of(radius);
		for (var input : inputs) unit = Math.min(unit, input.exponent());
		BigInteger[] values = new BigInteger[13], delta = new BigInteger[3];
		for (int i = 0; i < values.length; i++) values[i] = inputs[i].significand().shiftLeft(inputs[i].exponent() - unit);
		var knots = new ArrayList<Fraction>(8); knots.add(new Fraction(BigInteger.ZERO, BigInteger.ONE));
		knots.add(new Fraction(BigInteger.ONE, BigInteger.ONE));
		for (int a = 0; a < 3; a++) {
			delta[a] = values[a + 3].subtract(values[a]);
			if (delta[a].signum() == 0) continue;
			for (int side = 6; side <= 9; side += 3) {
				BigInteger numerator = values[a + side].subtract(values[a]), denominator = delta[a];
				if (denominator.signum() < 0) { numerator = numerator.negate(); denominator = denominator.negate(); }
				if (numerator.signum() > 0 && numerator.compareTo(denominator) < 0) knots.add(new Fraction(numerator, denominator));
			}
		}
		knots.sort(Fraction::compareTo);
		BigInteger radiusSquared = values[12].multiply(values[12]);
		for (int k = 0; k < knots.size() - 1; k++) {
			Fraction start = knots.get(k), end = knots.get(k + 1); if (start.compareTo(end) == 0) continue;
			BigInteger midN = start.n.multiply(end.d).add(end.n.multiply(start.d)), midD = start.d.multiply(end.d).shiftLeft(1);
			BigInteger A = BigInteger.ZERO, B = BigInteger.ZERO, C = BigInteger.ZERO;
			for (int a = 0; a < 3; a++) {
				BigInteger at = values[a].multiply(midD).add(delta[a].multiply(midN)), plane;
				if (at.compareTo(values[a + 6].multiply(midD)) < 0) plane = values[a + 6];
				else if (at.compareTo(values[a + 9].multiply(midD)) > 0) plane = values[a + 9];
				else continue;
				BigInteger offset = values[a].subtract(plane);
				A = A.add(delta[a].multiply(delta[a])); B = B.add(offset.multiply(delta[a])); C = C.add(offset.multiply(offset));
			}
			C = C.subtract(radiusSquared);
			if (A.signum() == 0) { if (C.signum() < 0) return true; continue; }
			Fraction optimum = new Fraction(B.negate(), A);
			if (optimum.compareTo(start) >= 0 && optimum.compareTo(end) <= 0) {
				if (A.multiply(C).compareTo(B.multiply(B)) < 0) return true;
			} else {
				Fraction t = optimum.compareTo(start) < 0 ? start : end;
				if (A.multiply(t.n.multiply(t.n)).add(B.multiply(t.n).multiply(t.d).shiftLeft(1))
						.add(C.multiply(t.d.multiply(t.d))).signum() < 0) return true;
			}
		}
		return false;
	}

	private record Fraction(BigInteger n, BigInteger d) implements Comparable<Fraction> {
		@Override public int compareTo(Fraction other) { return n.multiply(other.d).compareTo(other.n.multiply(d)); }
	}
}

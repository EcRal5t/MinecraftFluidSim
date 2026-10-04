package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.units.SignedDyadic;
import java.math.BigInteger;
import java.util.List;

/** A retained half-space and exact projective identity for its supporting plane. */
final class LiquidGeometryPlane {
	record Key(List<BigInteger> coefficients) { }
	private final LiquidPolyhedron.Point normal;
	private final double offset;
	private volatile Key identity;
	LiquidGeometryPlane(LiquidPolyhedron.Point normal, double offset) {
		if (normal == null || !(normal.dot(normal) > 0) || !Double.isFinite(normal.dot(normal)) || !Double.isFinite(offset))
			throw new IllegalArgumentException("Invalid liquid geometry plane");
		this.normal = normal; this.offset = offset;
	}
	LiquidPolyhedron.Point normal() { return normal; }
	double offset() { return offset; }
	private Boolean orientation;
	static LiquidGeometryPlane exact(List<BigInteger> coefficients) {
		int bits = 0; boolean positive = false; boolean found = false; BigInteger divisor = BigInteger.ZERO;
		for (int a = 0; a < 4; a++) {
			divisor = divisor.gcd(coefficients.get(a).abs());
			if (a < 3) { bits = Math.max(bits, coefficients.get(a).abs().bitLength()); if (!found && coefficients.get(a).signum() != 0) { positive = coefficients.get(a).signum() > 0; found = true; } }
		}
		if (!found) throw new IllegalArgumentException("Empty exact geometric normal");
		double[] values = new double[4]; for (int a = 0; a < 4; a++) values[a] = new SignedDyadic(coefficients.get(a), 1 - bits).toDouble();
		var result = new LiquidGeometryPlane(new LiquidPolyhedron.Point(values[0], values[1], values[2]), values[3]);
		if (!positive) divisor = divisor.negate(); final var scale = divisor;
		result.identity = new Key(coefficients.stream().map(c -> c.divide(scale)).toList()); result.orientation = positive; return result;
	}
	LiquidGeometryPlane opposite() {
		var result = new LiquidGeometryPlane(new LiquidPolyhedron.Point(-normal.x(), -normal.y(), -normal.z()), -offset);
		result.identity = key(); result.orientation = !positive(); return result;
	}
	LiquidGeometryPlane scaled(double scale) {
		var factor = LiquidExactPolygon.Fraction.of(scale); var coefficients = key().coefficients();
		var result = new java.util.ArrayList<BigInteger>(); int sign = positive() ? 1 : -1;
		for (int a = 0; a < 3; a++) result.add(coefficients.get(a).multiply(factor.denominator()).multiply(BigInteger.valueOf(sign)));
		result.add(coefficients.get(3).multiply(factor.numerator()).multiply(BigInteger.valueOf(sign))); return exact(result);
	}
	/** A shared projective plane uses one power-of-two coefficient scale for numerical evaluation. */
	LiquidGeometryPlane canonical() {
		var coefficients = key().coefficients(); int bits = 0;
		for (int a = 0; a < 3; a++) bits = Math.max(bits, coefficients.get(a).abs().bitLength());
		double[] values = new double[4];
		for (int a = 0; a < 4; a++) values[a] = new SignedDyadic(positive() ? coefficients.get(a) : coefficients.get(a).negate(), 1 - bits).toDouble();
		var result = new LiquidGeometryPlane(new LiquidPolyhedron.Point(values[0], values[1], values[2]), values[3]);
		if (!result.key().equals(key())) throw new IllegalStateException("Canonical geometric plane exceeds numerical range");
		return result;
	}
	double signedDistance(LiquidPolyhedron.Point point) { return normal.dot(point) - offset; }
	/** Forward error of plane evaluation, including rounded vertex coordinates. */
	double evaluationError(LiquidPolyhedron.Point point) {
		double magnitude = Math.abs(offset), coordinates = 0;
		for (int a = 0; a < 3; a++) {
			magnitude += Math.abs(normal.coordinate(a) * point.coordinate(a));
			coordinates += Math.abs(normal.coordinate(a)) * Math.ulp(point.coordinate(a));
		}
		return 16 * Math.ulp(1.0) * magnitude + 16 * coordinates;
	}
	boolean positive() { return orientation != null ? orientation : normal.x() != 0 ? normal.x() > 0 : normal.y() != 0 ? normal.y() > 0 : normal.z() > 0; }
	Key key() {
		Key cached = identity; if (cached != null) return cached;
		double[] values = {normal.x(), normal.y(), normal.z(), offset}; SignedDyadic[] dyadic = new SignedDyadic[4];
		int exponent = Integer.MAX_VALUE;
		for (int i = 0; i < 4; i++) { dyadic[i] = SignedDyadic.of(values[i]); if (dyadic[i].signum() != 0) exponent = Math.min(exponent, dyadic[i].exponent()); }
		BigInteger[] integer = new BigInteger[4]; BigInteger divisor = BigInteger.ZERO;
		for (int i = 0; i < 4; i++) {
			integer[i] = dyadic[i].signum() == 0 ? BigInteger.ZERO : dyadic[i].significand().shiftLeft(dyadic[i].exponent() - exponent);
			divisor = divisor.gcd(integer[i].abs());
		}
		if (!positive()) divisor = divisor.negate();
		for (int i = 0; i < 4; i++) integer[i] = integer[i].divide(divisor);
		return identity = new Key(List.of(integer));
	}
	LiquidPolyhedron clip(LiquidPolyhedron geometry) { return geometry.clip(this); }
}

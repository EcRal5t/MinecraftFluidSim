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
	LiquidGeometryPlane opposite() { return new LiquidGeometryPlane(new LiquidPolyhedron.Point(-normal.x(), -normal.y(), -normal.z()), -offset); }
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
	boolean positive() { return normal.x() != 0 ? normal.x() > 0 : normal.y() != 0 ? normal.y() > 0 : normal.z() > 0; }
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
	LiquidPolyhedron clip(LiquidPolyhedron geometry) { return geometry.clip(normal.x(), normal.y(), normal.z(), offset); }
}

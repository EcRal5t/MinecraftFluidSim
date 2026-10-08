package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;
import java.math.BigInteger;

/** Closest integer plane correction on a finite coordinate grid, with an exact volume constraint. */
final class LiquidIntegerPlaneProjection {
	private record Bezout(BigInteger gcd, BigInteger first, BigInteger second) { }
	private record Plane(BigInteger gcd, BigInteger[] particular, BigInteger[] first, BigInteger[] second) { }
	private LiquidIntegerPlaneProjection() { }
	static Fraction[] correct(Fraction[] center, Fraction[] area, Fraction sweptVolume, int bits) {
		var divisor = BigInteger.ONE;
		for (var component : area) divisor = divisor.divide(LiquidIntegerArithmetic.gcd(divisor, component.denominator())).multiply(component.denominator());
		var normal = new BigInteger[3]; var target = sweptVolume.multiply(Fraction.of(3)).multiply(new Fraction(divisor, BigInteger.ONE));
		for (int a = 0; a < 3; a++) {
			normal[a] = area[a].numerator().multiply(divisor.divide(area[a].denominator()));
			target = target.add(center[a].multiply(new Fraction(normal[a], BigInteger.ONE)));
		}
		var plane = reduced(kernel(normal));
		var required = plane.gcd.divide(LiquidIntegerArithmetic.gcd(plane.gcd, target.numerator()));
		var base = target.denominator().multiply(required);
		var squaredDiameter = dot(plane.first, plane.first).add(dot(plane.second, plane.second)).add(dot(plane.first, plane.second).abs().shiftLeft(1));
		var diameter = squaredDiameter.sqrt(); if (diameter.multiply(diameter).compareTo(squaredDiameter) < 0) diameter = diameter.add(BigInteger.ONE);
		int extra = Math.max(0, diameter.bitLength() - base.bitLength()); if (base.shiftLeft(extra).compareTo(diameter) < 0) extra++;
		// The reduced fundamental parallelogram has covering radius at most half this diameter.
		// Its physical covering radius is therefore at most half the requested binary coordinate spacing.
		var denominator = base.shiftLeft(Math.addExact(bits, extra));
		var origin = new BigInteger[3]; var remainder = target.numerator().multiply(denominator.divide(target.denominator()));
		for (int a = 0; a < 3; a++) {
			origin[a] = nearest(center[a].numerator().multiply(denominator), center[a].denominator());
			remainder = remainder.subtract(normal[a].multiply(origin[a]));
		}
		var quotient = remainder.divideAndRemainder(plane.gcd);
		if (quotient[1].signum() != 0) throw new IllegalStateException("Coordinate grid lost the integer volume constraint");
		var local = new Fraction[3];
		for (int a = 0; a < 3; a++) local[a] = center[a].multiply(new Fraction(denominator, BigInteger.ONE)).subtract(new Fraction(origin[a], BigInteger.ONE));
		var particular = scale(plane.particular, quotient[0]); var correction = closest(local, particular, plane.first, plane.second);
		var result = new Fraction[3];
		for (int a = 0; a < 3; a++) result[a] = new Fraction(origin[a].add(correction[a]), denominator);
		return result;
	}
	private static Plane kernel(BigInteger[] normal) {
		var first = bezout(normal[0], normal[1]);
		if (first.gcd.signum() == 0) {
			if (normal[2].signum() == 0) throw new IllegalArgumentException("Coordinate plane has no area");
			return new Plane(normal[2].abs(), new BigInteger[]{BigInteger.ZERO, BigInteger.ZERO, BigInteger.valueOf(normal[2].signum())},
					new BigInteger[]{BigInteger.ONE, BigInteger.ZERO, BigInteger.ZERO}, new BigInteger[]{BigInteger.ZERO, BigInteger.ONE, BigInteger.ZERO});
		}
		var second = bezout(first.gcd, normal[2]);
		var particular = new BigInteger[]{first.first.multiply(second.first), first.second.multiply(second.first), second.second};
		var a = new BigInteger[]{normal[1].divide(first.gcd), normal[0].divide(first.gcd).negate(), BigInteger.ZERO};
		var b = new BigInteger[]{first.first.multiply(normal[2].divide(second.gcd)).negate(),
				first.second.multiply(normal[2].divide(second.gcd)).negate(), first.gcd.divide(second.gcd)};
		return new Plane(second.gcd, particular, a, b);
	}
	private static Plane reduced(Plane plane) {
		var first = plane.first; var second = plane.second;
		while (true) {
			if (dot(second, second).compareTo(dot(first, first)) < 0) { var swap = first; first = second; second = swap; }
			var reduction = nearest(dot(first, second), dot(first, first));
			if (reduction.signum() == 0) break;
			second = add(second, scale(first, reduction.negate()));
		}
		return new Plane(plane.gcd, plane.particular, first, second);
	}
	/** A reduced rank-two lattice needs only the nearest perpendicular row and its two neighbors. */
	private static BigInteger[] closest(Fraction[] target, BigInteger[] particular, BigInteger[] first, BigInteger[] second) {
		var aa = dot(first, first); var ab = dot(first, second); var bb = dot(second, second);
		var offset = new Fraction[3]; for (int a = 0; a < 3; a++) offset[a] = target[a].subtract(new Fraction(particular[a], BigInteger.ONE));
		var targetFirst = dot(offset, first); var targetSecond = dot(offset, second);
		var rowCoordinate = targetSecond.multiply(new Fraction(aa, BigInteger.ONE)).subtract(targetFirst.multiply(new Fraction(ab, BigInteger.ONE)))
				.divide(new Fraction(aa.multiply(bb).subtract(ab.multiply(ab)), BigInteger.ONE));
		var row = nearest(rowCoordinate.numerator(), rowCoordinate.denominator());
		BigInteger[] selected = null; Fraction minimum = null;
		for (int adjacent = -1; adjacent <= 1; adjacent++) {
			var n = row.add(BigInteger.valueOf(adjacent)); var column = targetFirst.subtract(new Fraction(n.multiply(ab), BigInteger.ONE)).divide(new Fraction(aa, BigInteger.ONE));
			var m = nearest(column.numerator(), column.denominator()); var candidate = add(particular, add(scale(first, m), scale(second, n))); var distance = Fraction.ZERO;
			for (int a = 0; a < 3; a++) { var delta = new Fraction(candidate[a], BigInteger.ONE).subtract(target[a]); distance = distance.add(delta.multiply(delta)); }
			if (minimum == null || distance.compareTo(minimum) < 0) { minimum = distance; selected = candidate; }
		}
		return selected;
	}
	private static Bezout bezout(BigInteger first, BigInteger second) {
		var a = first.abs(); var b = second.abs(); var x = BigInteger.ONE; var y = BigInteger.ZERO; var u = BigInteger.ZERO; var v = BigInteger.ONE;
		while (b.signum() != 0) {
			var quotient = a.divideAndRemainder(b); var nextX = x.subtract(quotient[0].multiply(u)); var nextY = y.subtract(quotient[0].multiply(v));
			a = b; b = quotient[1]; x = u; y = v; u = nextX; v = nextY;
		}
		return new Bezout(a, first.signum() < 0 ? x.negate() : x, second.signum() < 0 ? y.negate() : y);
	}
	private static BigInteger nearest(BigInteger numerator, BigInteger denominator) {
		var quotient = numerator.abs().divideAndRemainder(denominator); int comparison = quotient[1].shiftLeft(1).compareTo(denominator);
		if (comparison > 0 || comparison == 0 && quotient[0].testBit(0)) quotient[0] = quotient[0].add(BigInteger.ONE);
		return numerator.signum() < 0 ? quotient[0].negate() : quotient[0];
	}
	private static BigInteger dot(BigInteger[] first, BigInteger[] second) {
		var result = BigInteger.ZERO; for (int a = 0; a < 3; a++) result = result.add(first[a].multiply(second[a])); return result;
	}
	private static Fraction dot(Fraction[] first, BigInteger[] second) {
		var result = Fraction.ZERO; for (int a = 0; a < 3; a++) result = result.add(first[a].multiply(new Fraction(second[a], BigInteger.ONE))); return result;
	}
	private static BigInteger[] scale(BigInteger[] vector, BigInteger factor) {
		return new BigInteger[]{vector[0].multiply(factor), vector[1].multiply(factor), vector[2].multiply(factor)};
	}
	private static BigInteger[] add(BigInteger[] first, BigInteger[] second) {
		return new BigInteger[]{first[0].add(second[0]), first[1].add(second[1]), first[2].add(second[2])};
	}
}

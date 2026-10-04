package cc.ecisr.mmm.fluid;

import java.util.Arrays;
import java.util.List;
import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;

/** Exact affine event coordinates for a piecewise constant characteristic velocity. */
final class LiquidCharacteristicMap {
	static final Fraction ZERO = Fraction.of(0);
	static final class Time {
		final Fraction[] coefficients;
		Time(Fraction[] coefficients) { this.coefficients = coefficients; }
		static Time constant(double value) { return new Time(new Fraction[] {ZERO, ZERO, ZERO, Fraction.of(value)}); }
		Time subtract(Time other) {
			var result = coefficients.clone(); for (int i = 0; i < 4; i++) result[i] = result[i].subtract(other.coefficients[i]); return new Time(result);
		}
		Time scaled(Fraction scale) {
			var result = coefficients.clone(); for (int i = 0; i < 4; i++) result[i] = result[i].multiply(scale); return new Time(result);
		}
		LiquidConvexRegion belowZero(LiquidConvexRegion region) {
			if (coefficients[0].numerator().signum() == 0 && coefficients[1].numerator().signum() == 0 && coefficients[2].numerator().signum() == 0)
				return coefficients[3].numerator().signum() <= 0 ? region : LiquidConvexRegion.EMPTY;
			var plane = coefficients.clone(); plane[3] = plane[3].negate(); return region.clip(plane);
		}
	}
	private final List<Fraction> values;
	private LiquidCharacteristicMap(Fraction[] values) { this.values = List.copyOf(Arrays.asList(values)); }
	static LiquidCharacteristicMap initial(Fraction[] velocity, double dt) {
		var result = new Fraction[12]; Arrays.fill(result, ZERO); for (int i = 0; i < 3; i++) {
			result[4 * i + i] = Fraction.of(1); result[4 * i + 3] = velocity[i].multiply(Fraction.of(dt));
		}
		return new LiquidCharacteristicMap(result);
	}
	Time residual(LiquidGeometryPlane plane) {
		var result = new Fraction[4]; Arrays.fill(result, ZERO);
		for (int i = 0; i < 3; i++) for (int j = 0; j < 4; j++) result[j] = result[j].add(coefficient(plane, i).multiply(values.get(4 * i + j)));
		result[3] = result[3].subtract(coefficient(plane, 3)); return new Time(result);
	}
	private static Fraction coefficient(LiquidGeometryPlane plane, int axis) {
		return new Fraction(plane.positive() ? plane.key().coefficients().get(axis) : plane.key().coefficients().get(axis).negate(), java.math.BigInteger.ONE);
	}
	static Fraction speed(LiquidGeometryPlane plane, Fraction[] velocity) {
		var result = ZERO; for (int i = 0; i < 3; i++) result = result.add(coefficient(plane, i).multiply(velocity[i])); return result;
	}
	LiquidCharacteristicMap cross(Fraction[] previous, Fraction[] next, Time remaining) {
		var result = values.toArray(Fraction[]::new);
		for (int i = 0; i < 3; i++) {
			var jump = next[i].subtract(previous[i]);
			for (int j = 0; j < 4; j++) result[4 * i + j] = result[4 * i + j].add(jump.multiply(remaining.coefficients[j]));
		}
		return new LiquidCharacteristicMap(result);
	}
	LiquidPolyhedron apply(LiquidPolyhedron source) {
		return apply(LiquidConvexRegion.of(source));
	}
	LiquidPolyhedron apply(LiquidConvexRegion source) {
		var mapped = source.transformed(values);
		if (!mapped.measure().isVolume(source.measure())) throw new IllegalStateException("Liquid characteristic map changes exact volume");
		return mapped.geometry();
	}
	@Override public boolean equals(Object other) { return other instanceof LiquidCharacteristicMap map && values.equals(map.values); }
	@Override public int hashCode() { return values.hashCode(); }
}

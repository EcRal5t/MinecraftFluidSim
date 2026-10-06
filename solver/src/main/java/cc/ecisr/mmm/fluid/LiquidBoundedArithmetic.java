package cc.ecisr.mmm.fluid;

/** Binary64 values with outward-rounded absolute bounds on all input and arithmetic errors. */
final class LiquidBoundedArithmetic {
	static final Value ZERO = new Value(0, 0), ONE = new Value(1, 0);
	record Value(double value, double error) {
		Value {
			if (!Double.isFinite(value) || !(error >= 0) || !Double.isFinite(error))
				throw new IllegalStateException("Pressure metric exceeds representable arithmetic range");
		}
		static Value of(LiquidExactPolygon.Fraction exact) {
			return ratio(exact.numerator(), exact.denominator());
		}
		/** Observation needs the exact integer ratio, independently of its common factors. */
		static Value ratio(java.math.BigInteger numerator, java.math.BigInteger denominator) {
			if (denominator.signum() == 0) throw new ArithmeticException("Zero observation denominator");
			if (numerator.signum() == 0) return ZERO;
			if (denominator.signum() < 0) { numerator = numerator.negate(); denominator = denominator.negate(); }
			double value = LiquidExactPolygon.roundedRatio(numerator, denominator); return new Value(value, Math.ulp(value));
		}
		Value add(Value other) {
			if (value == 0 && error == 0) return other;
			if (other.value == 0 && other.error == 0) return this;
			double result = value + other.value;
			return new Value(result, up(up(error + other.error) + Math.ulp(result)));
		}
		Value negate() { return new Value(-value, error); }
		Value subtract(Value other) { return add(other.negate()); }
		Value multiply(Value other) {
			if (value == 0 && error == 0 || other.value == 0 && other.error == 0) return ZERO;
			double result = value * other.value;
			double bound = up(up(Math.abs(value) * other.error) + up(Math.abs(other.value) * error));
			return new Value(result, up(up(bound + up(error * other.error)) + Math.ulp(result)));
		}
		Value divide(Value other) {
			double denominator = Math.nextDown(Math.abs(other.value) - other.error);
			if (!(denominator > 0)) throw new IllegalStateException("Pressure metric denominator has unresolved numerical sign");
			if (value == 0 && error == 0) return ZERO;
			double result = value / other.value;
			double residual = up(up(error + up(Math.abs(result) * other.error)) + up(Math.ulp(result) * Math.abs(other.value)));
			return new Value(result, up(residual / denominator));
		}
		Value sqrt() {
			if (value == 0 && error == 0) return ZERO;
			double lower = Math.nextDown(value - error), upper = up(value + error);
			if (!(lower > 0)) throw new IllegalStateException("Surface metric square root has unresolved numerical sign");
			double result = Math.sqrt(value);
			double below = Math.nextDown(Math.sqrt(lower)), above = up(Math.sqrt(upper));
			return new Value(result, up(Math.max(up(result - below), up(above - result))));
		}
		double magnitude() { return up(Math.abs(value) + error); }
	}
	static double up(double value) { return Math.nextUp(value); }
	private LiquidBoundedArithmetic() { }
}

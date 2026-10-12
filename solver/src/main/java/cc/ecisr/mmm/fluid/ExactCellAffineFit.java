package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.chemistry.ChemicalLimits;
import cc.ecisr.mmm.chemistry.ExactRational;
import cc.ecisr.mmm.units.SignedDyadic;
import java.math.BigInteger;
import java.util.Arrays;

/** Bounded exact weighted fit used when the ordinary cell QR cannot certify its stored map. */
final class ExactCellAffineFit {
	private static final int MAX_BITS = Math.min(4_096, ChemicalLimits.MAX_RATIONAL_BITS / 2 - 1);

	private ExactCellAffineFit() { }

	/**
	 * Returns high/low parts indexed by 4*marker+global feature (constant, x, y, z).
	 * A null result leaves singular, over-budget or insufficiently representable fits unavailable.
	 */
	static double[][] fit(double[] mass, double[][] feature, double[][] directions) {
		validate(mass, feature, directions);
		int n = mass.length, rank = feature.length;
		try {
			ExactRational[] weights = new ExactRational[n];
			ExactRational[][] phi = new ExactRational[rank][n], frame = new ExactRational[rank - 1][3];
			for (int i = 0; i < n; i++) {
				weights[i] = exact(mass[i]);
				for (int row = 0; row < rank; row++) phi[row][i] = exact(feature[row][i]);
			}
			for (int row = 0; row < rank - 1; row++) for (int axis = 0; axis < 3; axis++) frame[row][axis] = exact(directions[row][axis]);
			ExactRational[][] inverse = new ExactRational[rank][2 * rank];
			for (int row = 0; row < rank; row++) {
				Arrays.fill(inverse[row], ExactRational.ZERO); inverse[row][rank + row] = ExactRational.ONE;
				for (int col = 0; col <= row; col++) {
					ExactRational sum = ExactRational.ZERO;
					for (int i = 0; i < n; i++) sum = add(sum, multiply(weights[i], multiply(phi[row][i], phi[col][i])));
					inverse[row][col] = inverse[col][row] = sum;
				}
			}
			for (int col = 0; col < rank; col++) {
				int pivot = col;
				while (pivot < rank && inverse[pivot][col].isZero()) pivot++;
				if (pivot == rank) return null;
				ExactRational[] swap = inverse[col]; inverse[col] = inverse[pivot]; inverse[pivot] = swap;
				ExactRational divisor = inverse[col][col];
				for (int j = 0; j < 2 * rank; j++) inverse[col][j] = divide(inverse[col][j], divisor);
				for (int row = 0; row < rank; row++) if (row != col) {
					ExactRational factor = inverse[row][col];
					for (int j = 0; j < 2 * rank; j++) inverse[row][j] = subtract(inverse[row][j], multiply(factor, inverse[col][j]));
				}
			}
			double[][] result = new double[2][Math.multiplyExact(4, n)];
			for (int i = 0; i < n; i++) {
				ExactRational[] local = new ExactRational[rank];
				for (int row = 0; row < rank; row++) {
					ExactRational sum = ExactRational.ZERO;
					for (int col = 0; col < rank; col++) sum = add(sum, multiply(inverse[row][rank + col], phi[col][i]));
					local[row] = multiply(sum, weights[i]);
				}
				if (!store(result, 4 * i, local[0])) return null;
				for (int axis = 0; axis < 3; axis++) {
					ExactRational sum = ExactRational.ZERO;
					for (int row = 1; row < rank; row++) sum = add(sum, multiply(frame[row - 1][axis], local[row]));
					if (!store(result, 4 * i + axis + 1, sum)) return null;
				}
			}
			return certified(result, feature, directions) ? result : null;
		} catch (MagnitudeUnavailable unavailable) {
			return null;
		}
	}

	private static boolean certified(double[][] result, double[][] feature, double[][] directions) {
		int n = feature[0].length;
		SignedDyadic tolerance = SignedDyadic.of((n + 64.0) * Math.ulp(1.0));
		for (int term = 0; term < 4; term++) for (int row = 0; row < feature.length; row++) {
			SignedDyadic actual = SignedDyadic.ZERO;
			for (int i = 0; i < n; i++) {
				SignedDyadic weight = SignedDyadic.of(result[0][4 * i + term]).add(SignedDyadic.of(result[1][4 * i + term]));
				actual = actual.add(weight.multiply(SignedDyadic.of(feature[row][i])));
			}
			double expected = term == 0 ? (row == 0 ? 1 : 0) : row == 0 ? 0 : directions[row - 1][term - 1];
			SignedDyadic error = actual.subtract(SignedDyadic.of(expected));
			if ((error.signum() < 0 ? error.negate() : error).compareTo(tolerance) > 0) return false;
		}
		return true;
	}
	private static boolean store(double[][] result, int index, ExactRational weight) {
		double high = weight.toDouble(); if (!Double.isFinite(high)) return false;
		double low = subtract(weight, exact(high)).toDouble(); if (!Double.isFinite(low)) return false;
		result[0][index] = high; result[1][index] = low; return true;
	}
	private static ExactRational exact(double value) {
		SignedDyadic dyadic = SignedDyadic.of(value);
		return bounded(dyadic.exponent() >= 0
				? new ExactRational(dyadic.significand().shiftLeft(dyadic.exponent()), BigInteger.ONE)
				: new ExactRational(dyadic.significand(), BigInteger.ONE.shiftLeft(-dyadic.exponent())));
	}
	private static ExactRational add(ExactRational a, ExactRational b) { return bounded(a.add(b)); }
	private static ExactRational subtract(ExactRational a, ExactRational b) { return bounded(a.subtract(b)); }
	private static ExactRational multiply(ExactRational a, ExactRational b) { return bounded(a.multiply(b)); }
	private static ExactRational divide(ExactRational a, ExactRational b) {
		return bounded(new ExactRational(a.numerator().multiply(b.denominator()), a.denominator().multiply(b.numerator())));
	}
	private static ExactRational bounded(ExactRational value) {
		if (value.numerator().bitLength() > MAX_BITS || value.denominator().bitLength() > MAX_BITS) throw new MagnitudeUnavailable();
		return value;
	}
	private static void validate(double[] mass, double[][] feature, double[][] directions) {
		if (mass == null || feature == null || directions == null || feature.length < 1 || feature.length > 4
				|| mass.length < feature.length || directions.length != feature.length - 1 || mass.length > Integer.MAX_VALUE / 4)
			throw new IllegalArgumentException("Invalid exact affine fit shape");
		for (double value : mass) if (!(value > 0 && Double.isFinite(value))) throw new IllegalArgumentException("Invalid exact affine mass");
		for (double[] row : feature) {
			if (row == null || row.length != mass.length) throw new IllegalArgumentException("Invalid exact affine feature shape");
			for (double value : row) if (!Double.isFinite(value)) throw new IllegalArgumentException("Nonfinite exact affine feature");
		}
		for (double value : feature[0]) if (value != 1) throw new IllegalArgumentException("Exact affine constant feature is not one");
		for (double[] row : directions) {
			if (row == null || row.length != 3) throw new IllegalArgumentException("Invalid exact affine direction shape");
			for (double value : row) if (!Double.isFinite(value)) throw new IllegalArgumentException("Nonfinite exact affine direction");
		}
	}
	private static final class MagnitudeUnavailable extends RuntimeException { }
}

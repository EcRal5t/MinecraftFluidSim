package mmm.fluid;

import mmm.chemistry.ExactRational;
import mmm.units.SignedDyadic;
import java.math.BigInteger;
import java.util.Arrays;

/** Minimum-energy angular response and zero-momentum residual on actual positive mass nodes. */
final class CellRemapMechanicalFit {
	private CellRemapMechanicalFit() { }

	/**
	 * Positions and angular moment use cell-normalized distances; variance is mean squared speed.
	 * Null denotes incompatible support/energy or unavailable rational/velocity representation.
	 * The caller audits the final square-root and binary64 publication roundoff with its mechanical budget.
	 */
	static double[][] fit(double[] masses, double[][] positions, double[][] preferredVelocities,
	                      double[] sourceCentroid, double[] sourceMeanVelocity,
	                      double[] sourceCentralAngularMoment, double sourceVelocityVarianceTrace) {
		if (masses == null || masses.length == 0 || positions == null || preferredVelocities == null
				|| positions.length != masses.length || preferredVelocities.length != masses.length
				|| !(Double.isFinite(sourceVelocityVarianceTrace) && sourceVelocityVarianceTrace >= 0))
			throw new IllegalArgumentException("Invalid liquid mechanical fit shape or energy");
		validate(sourceCentroid); validate(sourceMeanVelocity); validate(sourceCentralAngularMoment);
		for (int i = 0; i < masses.length; i++) {
			if (!(Double.isFinite(masses[i]) && masses[i] > 0)) throw new IllegalArgumentException("Mechanical fit requires positive masses");
			validate(positions[i]); validate(preferredVelocities[i]);
		}
		try {
			int n = masses.length;
			var mass = new SignedDyadic[n]; var position = new SignedDyadic[n][3]; var preferred = new SignedDyadic[n][3];
			var total = SignedDyadic.ZERO; var first = dyadics(3); var second = new SignedDyadic[3][3];
			for (var row : second) Arrays.fill(row, SignedDyadic.ZERO);
			var preferredFirst = dyadics(3); var preferredAngular = dyadics(3); var preferredSquared = SignedDyadic.ZERO;
			for (int i = 0; i < n; i++) {
				mass[i] = SignedDyadic.of(masses[i]); total = total.add(mass[i]);
				var weightedPosition = dyadics(3);
				for (int a = 0; a < 3; a++) {
					position[i][a] = SignedDyadic.of(positions[i][a]); weightedPosition[a] = mass[i].multiply(position[i][a]);
					first[a] = first[a].add(weightedPosition[a]);
				}
				if (sourceVelocityVarianceTrace == 0) continue;
				for (int a = 0; a < 3; a++) preferred[i][a] = SignedDyadic.of(preferredVelocities[i][a]);
				for (int a = 0; a < 3; a++) {
					for (int b = a; b < 3; b++) second[a][b] = second[a][b].add(weightedPosition[a].multiply(position[i][b]));
					var weightedPreferred = mass[i].multiply(preferred[i][a]);
					preferredFirst[a] = preferredFirst[a].add(weightedPreferred);
					preferredSquared = preferredSquared.add(weightedPreferred.multiply(preferred[i][a]));
					int b = (a + 1) % 3, c = (a + 2) % 3;
					preferredAngular[a] = preferredAngular[a].add(weightedPosition[b].multiply(preferred[i][c])
							.subtract(weightedPosition[c].multiply(preferred[i][b])));
				}
			}
			var centroid = zeros(); for (int a = 0; a < 3; a++) centroid[a] = ratio(first[a], total);
			var meanVelocity = vector(sourceMeanVelocity);
			var shift = vector(sourceCentroid);
			for (int a = 0; a < 3; a++) shift[a] = shift[a].subtract(centroid[a]);
			var required = cross(shift, meanVelocity); var sourceAngular = vector(sourceCentralAngularMoment);
			for (int a = 0; a < 3; a++) required[a] = required[a].add(sourceAngular[a]);
			if (sourceVelocityVarianceTrace == 0) {
				for (var value : required) if (!value.isZero()) return null;
				var result = new double[n][3];
				for (int i = 0; i < n; i++) result[i] = sourceMeanVelocity.clone();
				return result;
			}
			var covariance = new ExactRational[3][3]; var trace = ExactRational.ZERO;
			for (int a = 0; a < 3; a++) for (int b = a; b < 3; b++) {
				covariance[a][b] = ratio(second[a][b], total).subtract(centroid[a].multiply(centroid[b]));
				covariance[b][a] = covariance[a][b];
				if (a == b) trace = trace.add(covariance[a][b]);
			}
			var inertia = new ExactRational[3][3];
			for (int a = 0; a < 3; a++) for (int b = 0; b < 3; b++)
				inertia[a][b] = (a == b ? trace : ExactRational.ZERO).subtract(covariance[a][b]);
			var rotation = solve(inertia, required); if (rotation == null) return null;
			var available = exact(sourceVelocityVarianceTrace).subtract(dot(required, rotation));
			if (available.signum() < 0) return null;
			var preferredMean = zeros(); var angular = zeros();
			for (int a = 0; a < 3; a++) preferredMean[a] = ratio(preferredFirst[a], total);
			var centreAngular = cross(centroid, preferredMean);
			for (int a = 0; a < 3; a++) angular[a] = ratio(preferredAngular[a], total).subtract(centreAngular[a]);
			var residualRotation = solve(inertia, angular); if (residualRotation == null) return null;
			var residualEnergy = ratio(preferredSquared, total).subtract(dot(preferredMean, preferredMean)).subtract(dot(angular, residualRotation));
			if (residualEnergy.signum() < 0) return null;
			if (residualEnergy.isZero() && !available.isZero()) {
				// Radial motion carries variance with exactly zero momentum and angular moment.
				preferred = position; preferredMean = centroid; residualRotation = zeros(); residualEnergy = trace;
				if (residualEnergy.isZero()) {
					if (n == 1) return null;
					preferred = new SignedDyadic[n][3]; for (var row : preferred) Arrays.fill(row, SignedDyadic.ZERO);
					preferred[0][0] = mass[1]; preferred[1][0] = mass[0].negate(); preferredMean = zeros();
					residualEnergy = ratio(mass[0].multiply(mass[1]).multiply(mass[1])
							.add(mass[1].multiply(mass[0]).multiply(mass[0])), total);
				}
			}
			var residualScale = available.isZero() ? ExactRational.ZERO : sqrtScale(divide(available, residualEnergy));
			var spin = zeros(); for (int a = 0; a < 3; a++) spin[a] = rotation[a].subtract(residualScale.multiply(residualRotation[a]));
			var centreSpin = cross(spin, centroid); var equations = new Equation[3];
			for (int a = 0; a < 3; a++) equations[a] = equation(new ExactRational[]{
					meanVelocity[a].subtract(residualScale.multiply(preferredMean[a])).subtract(centreSpin[a]),
					spin[(a + 1) % 3], spin[(a + 2) % 3].multiply(-1), residualScale});
			var result = new double[n][3];
			for (int i = 0; i < n; i++) for (int a = 0; a < 3; a++) {
				result[i][a] = evaluate(equations[a], position[i][(a + 2) % 3], position[i][(a + 1) % 3], preferred[i][a]);
				if (!Double.isFinite(result[i][a])) return null;
			}
			return result;
		} catch (IllegalArgumentException magnitude) {
			return null;
		}
	}

	/** Exact elimination retains thin nonzero inertia modes and tests the actual image. */
	private static ExactRational[] solve(ExactRational[][] inertia, ExactRational[] rhs) {
		var matrix = new ExactRational[3][4]; var pivots = new int[3]; int rank = 0;
		for (int a = 0; a < 3; a++) { System.arraycopy(inertia[a], 0, matrix[a], 0, 3); matrix[a][3] = rhs[a]; }
		for (int col = 0; col < 3; col++) {
			int pivot = rank; while (pivot < 3 && matrix[pivot][col].isZero()) pivot++;
			if (pivot == 3) continue;
			var swap = matrix[rank]; matrix[rank] = matrix[pivot]; matrix[pivot] = swap;
			var divisor = matrix[rank][col];
			for (int j = col; j < 4; j++) matrix[rank][j] = divide(matrix[rank][j], divisor);
			for (int row = 0; row < 3; row++) if (row != rank) {
				var factor = matrix[row][col];
				for (int j = col; j < 4; j++) matrix[row][j] = matrix[row][j].subtract(factor.multiply(matrix[rank][j]));
			}
			pivots[rank++] = col;
		}
		for (int row = rank; row < 3; row++) if (!matrix[row][3].isZero()) return null;
		var result = zeros(); for (int row = 0; row < rank; row++) result[pivots[row]] = matrix[row][3];
		return result;
	}
	private record Equation(SignedDyadic[] coefficients, BigInteger denominator) { }
	private static Equation equation(ExactRational[] values) {
		var denominator = BigInteger.ONE;
		for (var value : values) denominator = denominator.divide(denominator.gcd(value.denominator())).multiply(value.denominator());
		var coefficients = new SignedDyadic[values.length];
		for (int i = 0; i < values.length; i++) coefficients[i] = new SignedDyadic(values[i].numerator().multiply(denominator.divide(values[i].denominator())), 0);
		return new Equation(coefficients, denominator);
	}
	private static double evaluate(Equation equation, SignedDyadic first, SignedDyadic second, SignedDyadic preferred) {
		var c = equation.coefficients;
		var value = c[0].add(c[1].multiply(first)).add(c[2].multiply(second)).add(c[3].multiply(preferred));
		return value.exponent() >= 0
				? ExactRational.roundQuotientToDouble(value.significand().shiftLeft(value.exponent()), equation.denominator)
				: ExactRational.roundQuotientToDouble(value.significand(), equation.denominator.shiftLeft(-value.exponent()));
	}
	private static ExactRational dot(ExactRational[] a, ExactRational[] b) {
		var result = ExactRational.ZERO; for (int i = 0; i < 3; i++) result = result.add(a[i].multiply(b[i])); return result;
	}
	private static ExactRational[] cross(ExactRational[] a, ExactRational[] b) {
		var result = new ExactRational[3];
		for (int i = 0; i < 3; i++) result[i] = a[(i + 1) % 3].multiply(b[(i + 2) % 3]).subtract(a[(i + 2) % 3].multiply(b[(i + 1) % 3]));
		return result;
	}
	private static ExactRational[] zeros() { return new ExactRational[]{ExactRational.ZERO, ExactRational.ZERO, ExactRational.ZERO}; }
	private static SignedDyadic[] dyadics(int count) { var values = new SignedDyadic[count]; Arrays.fill(values, SignedDyadic.ZERO); return values; }
	private static ExactRational[] vector(double[] values) { return new ExactRational[]{exact(values[0]), exact(values[1]), exact(values[2])}; }
	private static ExactRational exact(double value) { return PlicComponentGeometry.ratio(SignedDyadic.of(value), SignedDyadic.of(1)); }
	private static ExactRational ratio(SignedDyadic numerator, SignedDyadic denominator) { return PlicComponentGeometry.ratio(numerator, denominator); }
	private static ExactRational divide(ExactRational a, ExactRational b) {
		var numeratorCommon = a.numerator().gcd(b.numerator()); var denominatorCommon = a.denominator().gcd(b.denominator());
		return new ExactRational(a.numerator().divide(numeratorCommon).multiply(b.denominator().divide(denominatorCommon)),
				a.denominator().divide(denominatorCommon).multiply(b.numerator().divide(numeratorCommon)));
	}
	private static ExactRational sqrtScale(ExactRational value) {
		if (value.isZero()) return ExactRational.ZERO;
		int scale = Math.floorDiv(value.numerator().bitLength() - value.denominator().bitLength(), 2);
		var normalized = scale >= 0 ? new ExactRational(value.numerator(), value.denominator().shiftLeft(2 * scale))
				: new ExactRational(value.numerator().shiftLeft(-2 * scale), value.denominator());
		var root = SignedDyadic.of(Math.sqrt(normalized.toDouble()));
		return PlicComponentGeometry.ratio(new SignedDyadic(root.significand(), Math.addExact(root.exponent(), scale)), SignedDyadic.of(1));
	}
	private static void validate(double[] vector) {
		if (vector == null || vector.length != 3) throw new IllegalArgumentException("Mechanical fit requires three components");
		for (double value : vector) if (!Double.isFinite(value)) throw new IllegalArgumentException("Nonfinite mechanical fit input");
	}
}

package mmm.fluid;

import mmm.chemistry.ExactRational;
import mmm.units.SignedDyadic;
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
			var weight = new ExactRational[n]; var r = new ExactRational[n][3]; var preferred = new ExactRational[n][3];
			var total = ExactRational.ZERO;
			for (int i = 0; i < n; i++) { weight[i] = exact(masses[i]); total = total.add(weight[i]); }
			for (int i = 0; i < n; i++) { weight[i] = divide(weight[i], total); r[i] = vector(positions[i]); preferred[i] = vector(preferredVelocities[i]); }
			var centroid = mean(weight, r); var meanVelocity = vector(sourceMeanVelocity);
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
			var inertia = new ExactRational[3][3]; for (var row : inertia) Arrays.fill(row, ExactRational.ZERO);
			for (int i = 0; i < n; i++) {
				for (int a = 0; a < 3; a++) r[i][a] = r[i][a].subtract(centroid[a]);
				var squared = dot(r[i], r[i]);
				for (int a = 0; a < 3; a++) for (int b = 0; b < 3; b++)
					inertia[a][b] = inertia[a][b].add(weight[i].multiply(
							(a == b ? squared : ExactRational.ZERO).subtract(r[i][a].multiply(r[i][b]))));
			}
			var rotation = solve(inertia, required); if (rotation == null) return null;
			var available = exact(sourceVelocityVarianceTrace).subtract(dot(required, rotation));
			if (available.signum() < 0) return null;
			var residual = preferred; var preferredMean = mean(weight, preferred);
			for (var row : residual) for (int a = 0; a < 3; a++) row[a] = row[a].subtract(preferredMean[a]);
			var residualRotation = solve(inertia, angular(weight, r, residual)); if (residualRotation == null) return null;
			for (int i = 0; i < n; i++) {
				var rigid = cross(residualRotation, r[i]);
				for (int a = 0; a < 3; a++) residual[i][a] = residual[i][a].subtract(rigid[a]);
			}
			var residualEnergy = energy(weight, residual);
			if (residualEnergy.isZero() && !available.isZero()) {
				// Radial motion carries variance with exactly zero momentum and angular moment.
				for (int i = 0; i < n; i++) residual[i] = r[i].clone();
				residualEnergy = energy(weight, residual);
				if (residualEnergy.isZero()) {
					if (n == 1) return null;
					residual[0][0] = weight[1]; residual[1][0] = weight[0].multiply(-1);
					residualEnergy = energy(weight, residual);
				}
			}
			var result = new double[n][3];
			for (int i = 0; i < n; i++) {
				var rigid = cross(rotation, r[i]);
				for (int a = 0; a < 3; a++) {
					double extra = available.isZero() || residual[i][a].isZero() ? 0
							: sqrt(divide(available.multiply(residual[i][a].multiply(residual[i][a])), residualEnergy));
					if (!Double.isFinite(extra)) return null;
					if (residual[i][a].signum() < 0) extra = -extra;
					result[i][a] = meanVelocity[a].add(rigid[a]).add(exact(extra)).toDouble();
					if (!Double.isFinite(result[i][a])) return null;
				}
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
	private static ExactRational[] mean(ExactRational[] weight, ExactRational[][] values) {
		var result = zeros();
		for (int i = 0; i < weight.length; i++) for (int a = 0; a < 3; a++) result[a] = result[a].add(weight[i].multiply(values[i][a]));
		return result;
	}
	private static ExactRational[] angular(ExactRational[] weight, ExactRational[][] r, ExactRational[][] velocity) {
		var result = zeros();
		for (int i = 0; i < weight.length; i++) {
			var moment = cross(r[i], velocity[i]);
			for (int a = 0; a < 3; a++) result[a] = result[a].add(weight[i].multiply(moment[a]));
		}
		return result;
	}
	private static ExactRational energy(ExactRational[] weight, ExactRational[][] velocity) {
		var result = ExactRational.ZERO;
		for (int i = 0; i < weight.length; i++) result = result.add(weight[i].multiply(dot(velocity[i], velocity[i])));
		return result;
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
	private static ExactRational[] vector(double[] values) { return new ExactRational[]{exact(values[0]), exact(values[1]), exact(values[2])}; }
	private static ExactRational exact(double value) { return PlicComponentGeometry.ratio(SignedDyadic.of(value), SignedDyadic.of(1)); }
	private static ExactRational divide(ExactRational a, ExactRational b) {
		return new ExactRational(a.numerator().multiply(b.denominator()), a.denominator().multiply(b.numerator()));
	}
	private static double sqrt(ExactRational value) {
		if (value.isZero()) return 0;
		int scale = Math.floorDiv(value.numerator().bitLength() - value.denominator().bitLength(), 2);
		var normalized = scale >= 0 ? new ExactRational(value.numerator(), value.denominator().shiftLeft(2 * scale))
				: new ExactRational(value.numerator().shiftLeft(-2 * scale), value.denominator());
		return Math.scalb(Math.sqrt(normalized.toDouble()), scale);
	}
	private static void validate(double[] vector) {
		if (vector == null || vector.length != 3) throw new IllegalArgumentException("Mechanical fit requires three components");
		for (double value : vector) if (!Double.isFinite(value)) throw new IllegalArgumentException("Nonfinite mechanical fit input");
	}
}

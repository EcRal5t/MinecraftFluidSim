package mmm.fluid;

import mmm.units.SignedDyadic;
import java.util.Arrays;
import org.ejml.data.DMatrixSparseCSC;
import org.ejml.sparse.FillReducing;
import org.ejml.sparse.csc.factory.DecompositionFactory_DSCC;
import org.ejml.sparse.csc.misc.TriangularSolver_DSCC;

/** Raw mass-whitened QR preconditions a full-row residual-minimizing pressure solve. */
final class SparsePressureFactor {
	private SparsePressureFactor() { }
	static PressureProjectionResult solve(CellVelocityConstraintOperator operator, double[] rhs, double[] pressure,
			double[] diagonal, boolean[] observed, boolean commonGauge, double[][] predictor, float dt, double tolerance, int maximumSolves) {
		var exact = new SignedDyadic[rhs.length];
		for (int row = 0; row < rhs.length; row++) exact[row] = SignedDyadic.of(rhs[row]);
		var candidate = pressure.clone(); var low = new double[rhs.length];
		var result = solve(operator, exact, candidate, low, diagonal, observed, commonGauge, predictor, dt, tolerance, maximumSolves, rhs);
		if (result.iterations() == 0 && !result.converged()) return result;
		var image = new double[rhs.length]; operator.residual(pressure, exact, image); double initial = rms(image);
		for (int row = 0; row < candidate.length; row++) {
			candidate[row] = SignedDyadic.of(candidate[row]).add(SignedDyadic.of(low[row])).toDouble();
			if (!Double.isFinite(candidate[row])) return unavailable(result.iterations(), initial);
		}
		operator.residual(candidate, exact, image); double residual = rms(image);
		if (Double.isFinite(residual) && (residual < initial || result.converged() && residual <= tolerance)) {
			System.arraycopy(candidate, 0, pressure, 0, pressure.length);
			return new PressureProjectionResult(result.iterations(), residual, residual <= tolerance);
		}
		return unavailable(result.iterations(), initial);
	}
	static PressureProjectionResult solve(CellVelocityConstraintOperator operator, SignedDyadic[] rhs, double[] pressure, double[] pressureLow,
			double[] diagonal, boolean[] observed, boolean commonGauge, double[][] predictor, float dt, double tolerance,
			int maximumSolves, double[] physicalSource) {
		if (pressure == pressureLow) throw new IllegalArgumentException("Aliased pressure accumulator columns");
		if (maximumSolves < 0) throw new IllegalArgumentException("Negative pressure factor solve budget");
		int iterations = 0; double initialResidual = Double.POSITIVE_INFINITY;
		if (maximumSolves == 0) return unavailable(iterations, initialResidual);
		try {
			double[] image = new double[rhs.length]; operator.residual(pressure, pressureLow, rhs, image);
			initialResidual = rms(image);
			PressureRowBasis basis = operator.rowBasis();
			if (!basis.observationNeutral(observed, diagonal, commonGauge)) return unavailable(iterations, initialResidual);
			double[] rate = new double[rhs.length]; operator.volumeRate(predictor, rate);
			if (!basis.compatibleSource(physicalSource, rate, dt)) return unavailable(iterations, initialResidual);
			if (Double.isFinite(initialResidual) && initialResidual <= tolerance)
				return new PressureProjectionResult(0, initialResidual, true);
			if (!Double.isFinite(initialResidual) || basis.size() == 0) return unavailable(iterations, initialResidual);
			double[] scale = new double[basis.size()];
			for (int row = 0; row < scale.length; row++) {
				scale[row] = 1 / Math.sqrt(diagonal[basis.row(row)]);
				if (!(scale[row] > 0 && Double.isFinite(scale[row]))) return unavailable(iterations, initialResidual);
			}
			int limit = Math.min(maximumSolves, basis.size());
			var size = operator.whitenedSize(basis);
			// Sparse QR can fill its input rectangle and R triangle; Arnoldi stores at most the solve budget.
			double rectangle = (double) size.features() * basis.size();
			double triangle = (double) basis.size() * (basis.size() + 1L) / 2;
			double krylovBytes = 8 * ((3.0 * limit + 16) * rhs.length + (limit + 1.0) * limit + 5.0 * limit);
			if (rectangle > Integer.MAX_VALUE || triangle > Integer.MAX_VALUE
					|| !fitsFactorMemory(36 * (rectangle + triangle) + 48.0 * size.maximumEntries()
							+ krylovBytes + 128.0 * (size.features() + basis.size()), new boolean[]{false}))
				return unavailable(iterations, initialResidual);
			var qr = DecompositionFactory_DSCC.qr(FillReducing.NONE);
			if (!qr.decompose(operator.massWhitenedTranspose(basis, scale))) return unavailable(iterations, initialResidual);
			var triangular = qr.getR(null, true);
			for (int row = 0; row < basis.size(); row++) {
				double pivot = triangular.get(row, row);
				if (pivot == 0 || !Double.isFinite(pivot)) return unavailable(iterations, initialResidual);
			}
			double[] origin = pressure.clone(), originLow = pressureLow.clone();
			double[] best = origin, bestLow = originLow; double residual = initialResidual;
			var vectors = new double[limit + 1][]; var directions = new double[limit][][];
			var hessenberg = new double[limit + 1][limit];
			double[] cosine = new double[limit], sine = new double[limit], transformed = new double[limit + 1];
			transformed[0] = norm(image); vectors[0] = image;
			for (int row = 0; row < image.length; row++) image[row] /= transformed[0];
			for (int column = 0; column < limit; column++) {
				iterations++;
				directions[column] = new double[2][rhs.length];
				if (!precondition(triangular, basis, scale, vectors[column], directions[column])) break;
				var next = new double[rhs.length]; operator.apply(directions[column][0], directions[column][1], next);
				if (!finite(next)) break;
				// Modified Gram-Schmidt with reorthogonalization keeps the Arnoldi basis usable near null directions.
				for (int pass = 0; pass < 2; pass++) for (int prior = 0; prior <= column; prior++) {
					double projection = dot(next, vectors[prior]); hessenberg[prior][column] += projection;
					for (int row = 0; row < next.length; row++) next[row] = Math.fma(-projection, vectors[prior][row], next[row]);
				}
				double length = norm(next); hessenberg[column + 1][column] = length;
				for (int prior = 0; prior < column; prior++) {
					double upper = hessenberg[prior][column], lower = hessenberg[prior + 1][column];
					hessenberg[prior][column] = cosine[prior] * upper + sine[prior] * lower;
					hessenberg[prior + 1][column] = -sine[prior] * upper + cosine[prior] * lower;
				}
				double pivot = Math.hypot(hessenberg[column][column], length);
				if (!(pivot > 0 && Double.isFinite(pivot))) break;
				cosine[column] = hessenberg[column][column] / pivot; sine[column] = length / pivot;
				hessenberg[column][column] = pivot; hessenberg[column + 1][column] = 0;
				transformed[column + 1] = -sine[column] * transformed[column]; transformed[column] *= cosine[column];
				// Arnoldi's residual estimate decides when to form a candidate; publication always checks actual A.
				double estimated = Math.abs(transformed[column + 1]) / Math.sqrt(Math.max(1, rhs.length));
				if (estimated <= tolerance || column + 1 == limit || length == 0) {
					int count = column + 1; double[] coefficients = Arrays.copyOf(transformed, count);
					for (int row = column; row >= 0; row--) {
						for (int later = row + 1; later < count; later++) coefficients[row] = Math.fma(-hessenberg[row][later], coefficients[later], coefficients[row]);
						coefficients[row] /= hessenberg[row][row];
					}
					if (!finite(coefficients)) break;
					var candidate = new double[rhs.length]; var candidateLow = new double[rhs.length];
					if (!combine(origin, originLow, directions, coefficients, candidate, candidateLow)) break;
					var actual = new double[rhs.length]; operator.residual(candidate, candidateLow, rhs, actual); double actualResidual = rms(actual);
					if (Double.isFinite(actualResidual) && actualResidual < residual) { best = candidate; bestLow = candidateLow; residual = actualResidual; }
					if (residual <= tolerance || length == 0) break;
				}
				vectors[column + 1] = next;
				for (int row = 0; row < next.length; row++) next[row] /= length;
			}
			System.arraycopy(best, 0, pressure, 0, pressure.length);
			System.arraycopy(bestLow, 0, pressureLow, 0, pressureLow.length);
			return new PressureProjectionResult(iterations, residual, residual <= tolerance);
		} catch (PressureRowBasis.Unavailable unavailable) { return unavailable(iterations, initialResidual); }
		catch (IllegalArgumentException unavailable) {
			if ("Thermal dyadic significand is over budget".equals(unavailable.getMessage())) return unavailable(iterations, initialResidual);
			throw unavailable;
		}
	}
	private static boolean precondition(DMatrixSparseCSC triangular, PressureRowBasis basis, double[] scale,
	                                   double[] rhs, double[][] result) {
		var solution = new double[basis.size()];
		for (int row = 0; row < solution.length; row++) solution[row] = rhs[basis.row(row)] * scale[row];
		if (!finite(solution)) return false;
		solveTriangular(triangular, solution);
		if (!finite(solution)) return false;
		for (int row = 0; row < solution.length; row++) {
			int selected = basis.row(row); var value = SignedDyadic.of(solution[row]).multiply(SignedDyadic.of(scale[row]));
			result[0][selected] = value.toDouble();
			if (!Double.isFinite(result[0][selected])) return false;
			result[1][selected] = value.subtract(SignedDyadic.of(result[0][selected])).toDouble();
		}
		return true;
	}
	private static boolean combine(double[] origin, double[] originLow, double[][][] directions, double[] coefficients,
	                               double[] result, double[] low) {
		for (int row = 0; row < result.length; row++) {
			var value = SignedDyadic.of(origin[row]).add(SignedDyadic.of(originLow[row]));
			for (int column = 0; column < coefficients.length; column++) value = value.add(SignedDyadic.of(coefficients[column])
					.multiply(SignedDyadic.of(directions[column][0][row]).add(SignedDyadic.of(directions[column][1][row]))));
			result[row] = value.toDouble(); if (!Double.isFinite(result[row])) return false;
			low[row] = value.subtract(SignedDyadic.of(result[row])).toDouble();
		}
		return true;
	}
	private static double dot(double[] first, double[] second) {
		double result = 0; for (int row = 0; row < first.length; row++) result = Math.fma(first[row], second[row], result); return result;
	}
	private static double norm(double[] values) { double result = 0; for (double value : values) result = Math.hypot(result, value); return result; }
	private static boolean finite(double[] values) { for (double value : values) if (!Double.isFinite(value)) return false; return true; }
	/** B^T = QR gives B B^T = R^T R; no explicit Q or rounded pressure Gram is needed. */
	private static void solveTriangular(DMatrixSparseCSC triangular, double[] solution) {
		for (int column = 0; column < triangular.numCols; column++) {
			double value = solution[column], pivot = 0;
			for (int entry = triangular.col_idx[column]; entry < triangular.col_idx[column + 1]; entry++) {
				int row = triangular.nz_rows[entry];
				if (row < column) value = Math.fma(-triangular.nz_values[entry], solution[row], value);
				else if (row == column) pivot = triangular.nz_values[entry];
			}
			solution[column] = value / pivot;
		}
		TriangularSolver_DSCC.solveU(triangular, solution);
	}
	private static PressureProjectionResult unavailable(int iterations, double residual) { return new PressureProjectionResult(iterations, residual, false); }
	private static double rms(double[] values) {
		double norm = 0; for (double value : values) norm = Math.hypot(norm, value);
		return norm / Math.sqrt(Math.max(1, values.length));
	}
	private static boolean fitsFactorMemory(double required, boolean[] reclaimed) {
		if (required > Runtime.getRuntime().maxMemory()) return false;
		if (required <= PressureRowBasis.availableMemory()) return true;
		if (reclaimed[0]) return false;
		reclaimed[0] = true; System.gc();
		return required <= PressureRowBasis.availableMemory();
	}
}

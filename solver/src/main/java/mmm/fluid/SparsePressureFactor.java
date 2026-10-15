package mmm.fluid;

import mmm.units.SignedDyadic;
import org.ejml.data.DMatrixSparseCSC;
import org.ejml.sparse.FillReducing;
import org.ejml.sparse.csc.factory.DecompositionFactory_DSCC;
import org.ejml.sparse.csc.misc.TriangularSolver_DSCC;

/** Mass-whitened constraint QR, with exact full-row refinement and an improving PCG seed. */
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
			boolean[] reclaimed = {false};
			PressureRowBasis basis = operator.rowBasis();
			if (!basis.observationNeutral(observed, diagonal, commonGauge)) return unavailable(iterations, initialResidual);
			double[] rate = new double[rhs.length]; operator.volumeRate(predictor, rate);
			// Subtracting A times a fixed reference preserves every exact row dependence.
			if (!basis.compatibleSource(physicalSource, rate, dt)) return unavailable(iterations, initialResidual);
			if (Double.isFinite(initialResidual) && initialResidual <= tolerance)
				return new PressureProjectionResult(0, initialResidual, true);
			if (basis.size() == 0) return unavailable(iterations, initialResidual);
			double[] scale = new double[basis.size()];
			for (int row = 0; row < scale.length; row++) {
				scale[row] = 1 / Math.sqrt(diagonal[basis.row(row)]);
				if (!(scale[row] > 0 && Double.isFinite(scale[row]))) return unavailable(iterations, initialResidual);
			}
			var size = operator.whitenedSize(basis);
			// V can fill the selected constraint rectangle; R fills its upper triangle.
			double rectangle = (double) size.features() * basis.size();
			double triangle = (double) basis.size() * (basis.size() + 1L) / 2;
			if (rectangle > Integer.MAX_VALUE || triangle > Integer.MAX_VALUE
					|| !fitsFactorMemory(36 * (rectangle + triangle) + 48.0 * size.maximumEntries()
							+ size.localBytes() + 128.0 * (size.features() + basis.size()), reclaimed))
				return unavailable(iterations, initialResidual);
			var transpose = operator.massWhitenedTranspose(basis, scale);
			var qr = DecompositionFactory_DSCC.qr(FillReducing.NONE);
			if (!qr.decompose(transpose)) return unavailable(iterations, initialResidual);
			var triangular = qr.getR(null, true);
			for (int row = 0; row < basis.size(); row++) {
				double pivot = triangular.get(row, row);
				if (pivot == 0 || !Double.isFinite(pivot)) return unavailable(iterations, initialResidual);
			}
			var solution = new double[basis.size()];
			for (int row = 0; row < basis.size(); row++) {
				solution[row] = rhs[basis.row(row)].multiply(SignedDyadic.of(scale[row])).toDouble();
				if (!Double.isFinite(solution[row])) return unavailable(iterations, initialResidual);
			}
			iterations++; solveTriangular(triangular, solution);
			double[] candidate = new double[rhs.length], candidateLow = new double[rhs.length];
			for (int row = 0; row < basis.size(); row++) {
				if (!Double.isFinite(solution[row])) return unavailable(iterations, initialResidual);
				var value = SignedDyadic.of(solution[row]).multiply(SignedDyadic.of(scale[row]));
				candidate[basis.row(row)] = value.toDouble();
				if (!Double.isFinite(candidate[basis.row(row)])) return unavailable(iterations, initialResidual);
				candidateLow[basis.row(row)] = value.subtract(SignedDyadic.of(candidate[basis.row(row)])).toDouble();
			}
			operator.residual(candidate, candidateLow, rhs, image); double residual = rms(image);
			if (!(Double.isFinite(residual) && residual < initialResidual)) return unavailable(iterations, initialResidual);
			while (residual > tolerance && iterations < maximumSolves) {
				boolean finite = true;
				for (int row = 0; row < basis.size(); row++) {
					solution[row] = image[basis.row(row)] * scale[row]; finite &= Double.isFinite(solution[row]);
				}
				if (!finite) break;
				iterations++; solveTriangular(triangular, solution);
				var trial = candidate.clone(); var trialLow = candidateLow.clone();
				for (int row = 0; row < basis.size(); row++) {
					if (!Double.isFinite(solution[row])) { finite = false; break; }
					int fine = basis.row(row);
					var update = SignedDyadic.of(candidate[fine]).add(SignedDyadic.of(candidateLow[fine]))
							.add(SignedDyadic.of(solution[row]).multiply(SignedDyadic.of(scale[row])));
					trial[fine] = update.toDouble();
					if (!Double.isFinite(trial[fine])) { finite = false; break; }
					trialLow[fine] = update.subtract(SignedDyadic.of(trial[fine])).toDouble(); finite &= Double.isFinite(trialLow[fine]);
				}
				if (!finite) break;
				var nextImage = new double[rhs.length]; operator.residual(trial, trialLow, rhs, nextImage); double next = rms(nextImage);
				if (!(Double.isFinite(next) && next < residual)) break;
				candidate = trial; candidateLow = trialLow; image = nextImage; residual = next;
			}
			System.arraycopy(candidate, 0, pressure, 0, pressure.length);
			System.arraycopy(candidateLow, 0, pressureLow, 0, pressureLow.length);
			return new PressureProjectionResult(iterations, residual, residual <= tolerance);
		} catch (PressureRowBasis.Unavailable unavailable) { return unavailable(iterations, initialResidual); }
		catch (IllegalArgumentException unavailable) {
			if ("Thermal dyadic significand is over budget".equals(unavailable.getMessage())) return unavailable(iterations, initialResidual);
			throw unavailable;
		}
	}
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

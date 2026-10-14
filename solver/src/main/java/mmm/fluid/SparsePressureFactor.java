package mmm.fluid;

import mmm.units.SignedDyadic;
import org.ejml.data.DMatrixRMaj;
import org.ejml.data.DMatrixSparseCSC;
import org.ejml.data.DMatrixSparseTriplet;
import org.ejml.ops.DConvertMatrixStruct;
import org.ejml.sparse.FillReducing;
import org.ejml.sparse.csc.factory.LinearSolverFactory_DSCC;

/** Certified principal coordinates, with full-operator refinement and a strictly improved PCG seed. */
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
			// EJML doubles each triangular capacity; one old buffer pair survives during growth.
			if (!fitsFactorMemory(30.0 * rhs.length * rhs.length + 128.0 * rhs.length, reclaimed)) return unavailable(iterations, initialResidual);
			PressureRowBasis basis = operator.rowBasis();
			if (!basis.observationNeutral(observed, diagonal, commonGauge)) return unavailable(iterations, initialResidual);
			double[] rate = new double[rhs.length]; operator.volumeRate(predictor, rate);
			// Subtracting A times a fixed reference preserves every exact row dependence.
			if (!basis.compatibleSource(physicalSource, rate, dt)) return unavailable(iterations, initialResidual);
			if (Double.isFinite(initialResidual) && initialResidual <= tolerance)
				return new PressureProjectionResult(0, initialResidual, true);
			var gram = operator.principalGram(basis); double[] scale = new double[basis.size()];
			long nonzeros = 2L * gram.values().length;
			if (nonzeros > (Integer.MAX_VALUE - basis.size()) / 4L) return unavailable(iterations, initialResidual);
			double triangle = (double) basis.size() * (basis.size() + 1L) / 2;
			double capacity = Math.max(4 * nonzeros + basis.size(), 2 * triangle + 3 * basis.size());
			if (!fitsFactorMemory(24 * capacity + 12 * triangle + 36 * nonzeros + 128.0 * basis.size(), reclaimed)) return unavailable(iterations, initialResidual);
			for (int row = 0; row < scale.length; row++) {
				scale[row] = 1 / Math.sqrt(diagonal[basis.row(row)]);
				if (!(scale[row] > 0 && Double.isFinite(scale[row]))) return unavailable(iterations, initialResidual);
			}
			var entries = new DMatrixSparseTriplet(basis.size(), basis.size(), (int) nonzeros);
			for (int entry = 0; entry < gram.values().length; entry++) {
				int row = gram.rows()[entry], column = gram.columns()[entry];
				double value = gram.values()[entry].multiply(SignedDyadic.of(scale[row])).multiply(SignedDyadic.of(scale[column])).toDouble();
				if (value == 0 || !Double.isFinite(value)) return unavailable(iterations, initialResidual);
				entries.addItem(row, column, value); if (row != column) entries.addItem(column, row, value);
			}
			var solver = LinearSolverFactory_DSCC.lu(FillReducing.NONE);
			if (!solver.setA(DConvertMatrixStruct.convert(entries, (DMatrixSparseCSC) null))) return unavailable(iterations, initialResidual);
			var source = new DMatrixRMaj(basis.size(), 1); var solution = new DMatrixRMaj(basis.size(), 1);
			for (int row = 0; row < basis.size(); row++) {
				source.data[row] = rhs[basis.row(row)].multiply(SignedDyadic.of(scale[row])).toDouble();
				if (!Double.isFinite(source.data[row])) return unavailable(iterations, initialResidual);
			}
			iterations++; solver.solve(source, solution);
			double[] candidate = new double[rhs.length], candidateLow = new double[rhs.length];
			for (int row = 0; row < basis.size(); row++) {
				candidate[basis.row(row)] = solution.data[row] * scale[row];
				if (!Double.isFinite(candidate[basis.row(row)])) return unavailable(iterations, initialResidual);
			}
			operator.residual(candidate, candidateLow, rhs, image); double residual = rms(image);
			if (!(Double.isFinite(residual) && residual < initialResidual)) return unavailable(iterations, initialResidual);
			while (residual > tolerance && iterations < maximumSolves) {
				boolean finite = true;
				for (int row = 0; row < basis.size(); row++) {
					source.data[row] = image[basis.row(row)] * scale[row]; finite &= Double.isFinite(source.data[row]);
				}
				if (!finite) break;
				iterations++; solver.solve(source, solution);
				var trial = candidate.clone(); var trialLow = candidateLow.clone();
				for (int row = 0; row < basis.size(); row++) {
					if (!Double.isFinite(solution.data[row])) { finite = false; break; }
					int fine = basis.row(row);
					var update = SignedDyadic.of(candidate[fine]).add(SignedDyadic.of(candidateLow[fine]))
							.add(SignedDyadic.of(solution.data[row]).multiply(SignedDyadic.of(scale[row])));
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

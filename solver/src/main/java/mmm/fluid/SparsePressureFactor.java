package mmm.fluid;

import mmm.units.SignedDyadic;
import org.ejml.data.DMatrixRMaj;
import org.ejml.data.DMatrixSparseCSC;
import org.ejml.data.DMatrixSparseTriplet;
import org.ejml.ops.DConvertMatrixStruct;
import org.ejml.sparse.FillReducing;
import org.ejml.sparse.csc.factory.LinearSolverFactory_DSCC;

/** Certified principal coordinates; a candidate is accepted only by the original full operator. */
final class SparsePressureFactor {
	private SparsePressureFactor() { }
	static boolean solve(CellVelocityConstraintOperator operator, double[] rhs, double[] pressure,
			double[] diagonal, boolean[] observed, boolean commonGauge, double[][] predictor, float dt, double tolerance) {
		try {
			boolean[] reclaimed = {false};
			// EJML doubles each triangular capacity; one old buffer pair survives during growth.
			if (!fitsFactorMemory(30.0 * rhs.length * rhs.length + 128.0 * rhs.length, reclaimed)) return false;
			PressureRowBasis basis = operator.rowBasis();
			if (!basis.observationNeutral(observed, diagonal, commonGauge)) return false;
			double[] rate = new double[rhs.length]; operator.volumeRate(predictor, rate);
			if (!basis.compatibleSource(rhs, rate, dt)) return false;
			var gram = operator.principalGram(basis); double[] scale = new double[basis.size()];
			long nonzeros = 2L * gram.values().length;
			if (nonzeros > (Integer.MAX_VALUE - basis.size()) / 4L) return false;
			double triangle = (double) basis.size() * (basis.size() + 1L) / 2;
			double capacity = Math.max(4 * nonzeros + basis.size(), 2 * triangle + 3 * basis.size());
			if (!fitsFactorMemory(24 * capacity + 12 * triangle + 36 * nonzeros + 128.0 * basis.size(), reclaimed)) return false;
			for (int row = 0; row < scale.length; row++) {
				scale[row] = 1 / Math.sqrt(diagonal[basis.row(row)]);
				if (!(scale[row] > 0 && Double.isFinite(scale[row]))) return false;
			}
			var entries = new DMatrixSparseTriplet(basis.size(), basis.size(), (int) nonzeros);
			for (int entry = 0; entry < gram.values().length; entry++) {
				int row = gram.rows()[entry], column = gram.columns()[entry];
				double value = gram.values()[entry].multiply(SignedDyadic.of(scale[row])).multiply(SignedDyadic.of(scale[column])).toDouble();
				if (value == 0 || !Double.isFinite(value)) return false;
				entries.addItem(row, column, value); if (row != column) entries.addItem(column, row, value);
			}
			var solver = LinearSolverFactory_DSCC.lu(FillReducing.NONE);
			if (!solver.setA(DConvertMatrixStruct.convert(entries, (DMatrixSparseCSC) null))) return false;
			var source = new DMatrixRMaj(basis.size(), 1); var solution = new DMatrixRMaj(basis.size(), 1);
			for (int row = 0; row < basis.size(); row++) { source.data[row] = rhs[basis.row(row)] * scale[row]; if (!Double.isFinite(source.data[row])) return false; }
			solver.solve(source, solution);
			double[] candidate = new double[rhs.length], image = new double[rhs.length];
			for (int row = 0; row < basis.size(); row++) { candidate[basis.row(row)] = solution.data[row] * scale[row]; if (!Double.isFinite(candidate[basis.row(row)])) return false; }
			operator.apply(candidate, image);
			double norm = 0; for (int row = 0; row < rhs.length; row++) norm = Math.hypot(norm, rhs[row] - image[row]);
			double residual = norm / Math.sqrt(Math.max(1, rhs.length));
			if (!(Double.isFinite(residual) && residual <= tolerance)) return false;
			System.arraycopy(candidate, 0, pressure, 0, pressure.length); return true;
		} catch (PressureRowBasis.Unavailable unavailable) { return false; }
		catch (IllegalArgumentException unavailable) {
			if ("Thermal dyadic significand is over budget".equals(unavailable.getMessage())) return false;
			throw unavailable;
		}
	}
	private static boolean fitsFactorMemory(double required, boolean[] reclaimed) {
		if (required > Runtime.getRuntime().maxMemory()) return false;
		if (required <= PressureRowBasis.availableMemory()) return true;
		if (reclaimed[0]) return false;
		reclaimed[0] = true; System.gc();
		return required <= PressureRowBasis.availableMemory();
	}
}

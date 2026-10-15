package mmm.fluid;

import java.util.TreeMap;
import java.util.Arrays;
import org.ejml.data.DMatrixSparseCSC;
import org.ejml.data.DMatrixSparseTriplet;
import org.ejml.ops.DConvertMatrixStruct;

/** Captured physical constraint coefficients and actual inertia shared by iteration and local whitening. */
final class PressureResponse {
	private final DMatrixSparseCSC transpose;
	private final double[] tails, inertia, load, loadLow, weighted, weightedLow;

	PressureResponse(DMatrixSparseCSC transpose, double[] tails, double[] inertia) {
		this.transpose = transpose; this.tails = tails; this.inertia = inertia;
		load = new double[transpose.numRows]; loadLow = new double[load.length];
		weighted = new double[load.length]; weightedLow = new double[load.length];
	}
	long bytes() { return 20L * transpose.nz_values.length + 4L * (transpose.numCols + 1L) + 40L * load.length; }
	private TreeMap<Integer, Integer> features(PressureRowBasis selected) {
		var features = new TreeMap<Integer, Integer>();
		for (int column = 0; column < selected.size(); column++) {
			int row = selected.row(column);
			for (int entry = transpose.col_idx[row]; entry < transpose.col_idx[row + 1]; entry++)
				features.put(transpose.nz_rows[entry], 0);
		}
		int next = 0; for (var entry : features.entrySet()) entry.setValue(next++);
		return features;
	}
	CellVelocityConstraintOperator.WhitenedSize size(PressureRowBasis selected) {
		int entries = 0;
		for (int column = 0; column < selected.size(); column++) {
			int row = selected.row(column);
			entries = Math.addExact(entries, transpose.col_idx[row + 1] - transpose.col_idx[row]);
		}
		return new CellVelocityConstraintOperator.WhitenedSize(features(selected).size(), entries);
	}
	DMatrixSparseCSC restrict(PressureRowBasis selected, double[] scale) {
		if (scale.length != selected.size()) throw new IllegalArgumentException("Whitened pressure scale shape mismatch");
		var features = features(selected);
		int entries = 0;
		for (int column = 0; column < selected.size(); column++) {
			int row = selected.row(column); entries = Math.addExact(entries, transpose.col_idx[row + 1] - transpose.col_idx[row]);
		}
		var result = new DMatrixSparseTriplet(features.size(), selected.size(), entries);
		for (int column = 0; column < selected.size(); column++) {
			int row = selected.row(column);
			for (int entry = transpose.col_idx[row]; entry < transpose.col_idx[row + 1]; entry++) {
				int feature = transpose.nz_rows[entry];
				double root = Math.sqrt(inertia[feature]), coefficient = transpose.nz_values[entry];
				double whitened = coefficient * root;
				double value = (whitened + (Math.fma(coefficient, root, -whitened) + tails[entry] * root)) * scale[column];
				if (!Double.isFinite(value)) throw new PressureRowBasis.Unavailable("Unrepresentable whitened pressure scale");
				if (value != 0) result.addItem(features.get(transpose.nz_rows[entry]), column, value);
			}
		}
		return DConvertMatrixStruct.convert(result, (DMatrixSparseCSC) null);
	}
	/** C M^-1 C^T pressure and its positive work, using the same original coefficient columns. */
	double apply(double[] high, double[] low, double[] result) {
		Arrays.fill(load, 0); Arrays.fill(loadLow, 0);
		for (int row = 0; row < transpose.numCols; row++)
			for (int entry = transpose.col_idx[row]; entry < transpose.col_idx[row + 1]; entry++) {
				int feature = transpose.nz_rows[entry]; double coefficient = transpose.nz_values[entry], tail = tails[entry];
				ConstraintNumerics.accumulateProduct(load, loadLow, feature, coefficient, high[row]);
				ConstraintNumerics.accumulateProduct(load, loadLow, feature, coefficient, low[row]);
				ConstraintNumerics.accumulateProduct(load, loadLow, feature, tail, high[row]);
				ConstraintNumerics.accumulateProduct(load, loadLow, feature, tail, low[row]);
			}
		Arrays.fill(weighted, 0); Arrays.fill(weightedLow, 0);
		for (int feature = 0; feature < load.length; feature++) {
			ConstraintNumerics.accumulateProduct(weighted, weightedLow, feature, load[feature], inertia[feature]);
			ConstraintNumerics.accumulateProduct(weighted, weightedLow, feature, loadLow[feature], inertia[feature]);
		}
		for (int row = 0; row < result.length; row++) {
			double sum = 0, correction = 0;
			for (int entry = transpose.col_idx[row]; entry < transpose.col_idx[row + 1]; entry++) {
				int feature = transpose.nz_rows[entry]; double coefficient = transpose.nz_values[entry];
				double product = coefficient * weighted[feature];
				double error = Math.fma(coefficient, weighted[feature], -product) + coefficient * weightedLow[feature]
						+ tails[entry] * weighted[feature] + tails[entry] * weightedLow[feature];
				double next = sum + product;
				correction += (Math.abs(sum) >= Math.abs(product) ? (sum - next) + product : (product - next) + sum) + error;
				sum = next;
			}
			result[row] = sum + correction;
		}
		double work = 0, correction = 0;
		for (int feature = 0; feature < load.length; feature++) {
			double product = load[feature] * weighted[feature];
			double error = Math.fma(load[feature], weighted[feature], -product)
					+ load[feature] * weightedLow[feature] + loadLow[feature] * weighted[feature]
					+ loadLow[feature] * weightedLow[feature];
			double next = work + product;
			correction += (Math.abs(work) >= Math.abs(product) ? (work - next) + product : (product - next) + work) + error;
			work = next;
		}
		return work + correction;
	}

}

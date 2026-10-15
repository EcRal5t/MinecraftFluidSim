package mmm.fluid;

import java.util.TreeMap;
import java.util.Arrays;
import org.ejml.data.DMatrixSparseCSC;
import org.ejml.data.DMatrixSparseTriplet;
import org.ejml.ops.DConvertMatrixStruct;

/** One captured actual-mass constraint transpose shared by QR patches and pressure iteration. */
final class PressureResponse {
	private final DMatrixSparseCSC transpose;
	private final double[] load, loadLow;

	PressureResponse(DMatrixSparseCSC transpose) {
		this.transpose = transpose;
		load = new double[transpose.numRows]; loadLow = new double[load.length];
	}
	long bytes() { return 12L * transpose.nz_length + 4L * (transpose.numCols + 1L) + 16L * load.length; }
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
				double value = transpose.nz_values[entry] * scale[column];
				if (!Double.isFinite(value)) throw new PressureRowBasis.Unavailable("Unrepresentable whitened pressure scale");
				if (value != 0) result.addItem(features.get(transpose.nz_rows[entry]), column, value);
			}
		}
		return DConvertMatrixStruct.convert(result, (DMatrixSparseCSC) null);
	}
	/** B B^T pressure and |B^T pressure| squared, without forming a rounded Gram matrix. */
	double apply(double[] high, double[] low, double[] result) {
		Arrays.fill(load, 0); Arrays.fill(loadLow, 0);
		for (int row = 0; row < transpose.numCols; row++)
			for (int entry = transpose.col_idx[row]; entry < transpose.col_idx[row + 1]; entry++) {
				int feature = transpose.nz_rows[entry]; double weight = transpose.nz_values[entry];
				ConstraintNumerics.accumulateProduct(load, loadLow, feature, weight, high[row]);
				ConstraintNumerics.accumulateProduct(load, loadLow, feature, weight, low[row]);
			}
		for (int row = 0; row < result.length; row++) {
			double sum = 0, correction = 0;
			for (int entry = transpose.col_idx[row]; entry < transpose.col_idx[row + 1]; entry++) {
				int feature = transpose.nz_rows[entry]; double weight = transpose.nz_values[entry];
				double product = weight * load[feature];
				double error = Math.fma(weight, load[feature], -product) + weight * loadLow[feature];
				double next = sum + product;
				correction += (Math.abs(sum) >= Math.abs(product) ? (sum - next) + product : (product - next) + sum) + error;
				sum = next;
			}
			result[row] = sum + correction;
		}
		double work = 0, correction = 0;
		for (int feature = 0; feature < load.length; feature++) {
			double square = load[feature] * load[feature];
			double error = Math.fma(load[feature], load[feature], -square) + 2 * load[feature] * loadLow[feature] + loadLow[feature] * loadLow[feature];
			double next = work + square;
			correction += (Math.abs(work) >= Math.abs(square) ? (work - next) + square : (square - next) + work) + error;
			work = next;
		}
		return work + correction;
	}
}

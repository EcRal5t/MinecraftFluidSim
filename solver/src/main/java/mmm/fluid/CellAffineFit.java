package mmm.fluid;

import java.util.ArrayList;
import java.util.Arrays;
import mmm.units.SignedDyadic;
import org.ejml.data.DMatrixRMaj;
import org.ejml.dense.row.factory.DecompositionFactory_DDRM;

/** Coupled actual-mass velocity modes with literal translation, rotation and supported strain fields. */
final class CellAffineFit {
	private CellAffineFit() { }

	static final class Model {
		private final int reliableRank;
		private final SignedDyadic[][] exactFields;
		private final SignedDyadic[][][] weights;

		private Model(int reliableRank, double[][] fields, SignedDyadic[][][] weights) {
			this.reliableRank = reliableRank; this.weights = weights;
			exactFields = new SignedDyadic[fields.length][12];
			for (int mode = 0; mode < fields.length; mode++) for (int term = 0; term < 12; term++)
				exactFields[mode][term] = SignedDyadic.of(fields[mode][term]);
		}
		int modeCount() { return exactFields.length; }
		int reliableRank() { return reliableRank; }
		SignedDyadic fieldExact(int mode, int axis, int term) { return exactFields[mode][4 * axis + term]; }
		SignedDyadic weightExact(int mode, int inputAxis, int localOrdinal) { return weights[mode][inputAxis][localOrdinal]; }
	}

	/** Positions and their storage errors are in cell-size units relative to the actual mass centre. */
	static Model capture(double[] mass, double[][] relativePosition, double[][] storageError,
			double[][] shape, double[][] shapeDerivative, double[] shapeCurvature, boolean[] shaped, double[] centerOffset) {
		validate(mass, relativePosition, storageError, shape, shapeDerivative, shapeCurvature, shaped, centerOffset);
		int n = mass.length;
		double maximumRoot = 0;
		for (double value : mass) maximumRoot = Math.max(maximumRoot, Math.sqrt(value));
		double[] root = new double[n], meanWeight = new double[n]; double total = 0;
		for (int p = 0; p < n; p++) {
			root[p] = Math.sqrt(mass[p]) / maximumRoot;
			total += root[p] * root[p];
		}
		int pivot = 0;
		for (int p = 0; p < n; p++) {
			meanWeight[p] = root[p] * root[p] / total;
			if (mass[p] > mass[pivot]) pivot = p;
		}
		var directions = reliableDirections(relativePosition, storageError, root, total);
		int translations = 0;
		for (boolean value : shaped) if (!value) translations++;
		int[] translationAxis = new int[translations];
		for (int axis = 0, k = 0; axis < 3; axis++) if (!shaped[axis]) translationAxis[k++] = axis;

		var original = new ArrayList<double[]>();
		for (int axis = 0; axis < 3; axis++) if (shaped[axis]) {
			double[] field = new double[12]; field[4 * axis] = 1; original.add(field);
		}
		for (int axis = 0; axis < 3; axis++) {
			double[] field = new double[12]; int i = (axis + 1) % 3, j = (axis + 2) % 3;
			field[4 * j + i + 1] = 1; field[4 * i + j + 1] = -1; original.add(field);
		}
		for (int i = 0; i < directions.length; i++) for (int j = i; j < directions.length; j++) {
			double[] field = new double[12];
			for (int a = 0; a < 3; a++) for (int b = 0; b < 3; b++)
				field[4 * a + b + 1] = directions[i][a] * directions[j][b]
						+ (i == j ? 0 : directions[j][a] * directions[i][b]);
			original.add(field);
		}
		for (double[] field : original) {
			// Literal rotations are about the physical cell centre, including on fully shaped blocks.
			for (int axis = 0; axis < 3; axis++) for (int j = 0; j < 3; j++)
				field[4 * axis] += field[4 * axis + j + 1] * centerOffset[j];
			for (int axis : translationAxis) {
				double anchor = evaluate(field, relativePosition[pivot], axis), mean = anchor;
				for (int p = 0; p < n; p++) if (p != pivot)
					mean += meanWeight[p] * (evaluate(field, relativePosition[p], axis) - anchor);
				field[4 * axis] -= mean;
			}
		}

		var active = new ArrayList<double[]>(); var samples = new ArrayList<double[]>(); var scales = new ArrayList<Double>();
		// Three unit velocity components fix the cell-scale metric even at a fully wall-shaped corner.
		double sampleNorm = Math.sqrt(3 * total), sampleError = 0;
		for (double[] field : original) {
			// A cell-scale field supplies the common physical scale. Small wall samples remain small.
			double scale = 0; for (double coefficient : field) scale = Math.hypot(scale, coefficient);
			if (scale == 0) continue;
			double[] sample = new double[3 * n]; double norm = 0;
			for (int p = 0; p < n; p++) for (int axis = 0; axis < 3; axis++) {
				double affine = evaluate(field, relativePosition[p], axis), affineError = 0;
				for (int j = 0; j < 3; j++) affineError += Math.abs(field[4 * axis + j + 1]) * 2 * storageError[p][j];
				double positionError = storageError[p][axis];
				double shapeError = (Math.abs(shapeDerivative[axis][p]) + Math.abs(shapeCurvature[axis]) * positionError) * positionError;
				double value = root[p] * shape[axis][p] * affine / scale;
				if (!Double.isFinite(value)) throw new IllegalArgumentException("Nonfinite coupled velocity sample");
				sample[3 * p + axis] = value; norm = Math.hypot(norm, value);
				double error = root[p] * (Math.abs(shape[axis][p]) * affineError
						+ (Math.abs(affine) + affineError) * shapeError) / scale;
				sampleError = Math.hypot(sampleError, error);
			}
			if (norm > 0) { active.add(field); samples.add(sample); scales.add(scale); sampleNorm = Math.hypot(sampleNorm, norm); }
		}
		int count = active.size(), selectedCount = 0; int[] pivots = new int[0];
		var normalized = new DMatrixRMaj(3 * n, count);
		for (int k = 0; k < count; k++) for (int p = 0; p < 3 * n; p++) normalized.set(p, k, samples.get(k)[p]);
		if (count > 0) {
			// The same position-storage uncertainty applies to rotations, strain and wall-shaped constants.
			double sampleUncertainty = sampleError + Math.max(3 * n, count) * Math.ulp(1.0) * sampleNorm;
			var support = DecompositionFactory_DDRM.svd(3 * n, count, false, false, true);
			if (!support.decompose(normalized.copy())) throw new IllegalStateException("Coupled velocity support SVD failed");
			int reliable = 0;
			for (int k = 0; k < support.numberOfSingularValues(); k++) {
				double singular = support.getSingularValues()[k];
				// Resolve each direction against its own energy perturbation, without the strong modes' Gram scale.
				if (singular * singular > 2 * singular * sampleUncertainty + sampleUncertainty * sampleUncertainty) reliable++;
			}
			var qr = DecompositionFactory_DDRM.qrp(3 * n, count);
			qr.setSingularThreshold(Math.max(3 * n, count) * Math.ulp(1.0));
			if (!qr.decompose(normalized.copy())) throw new IllegalStateException("Coupled velocity QR failed");
			selectedCount = Math.min(reliable, qr.getRank()); pivots = qr.getColPivots();
		}
		double[][] fields = new double[translations + selectedCount][12], rawWeights = new double[selectedCount][3 * n];
		for (int k = 0; k < translations; k++) fields[k][4 * translationAxis[k]] = 1;
		for (int k = 0; k < selectedCount; k++) fields[translations + k] = active.get(pivots[k]);
		if (selectedCount > 0) {
			var selected = new DMatrixRMaj(3 * n, selectedCount); var sampled = new boolean[3 * n];
			for (int k = 0; k < selectedCount; k++) for (int p = 0; p < 3 * n; p++) {
				double value = normalized.get(p, pivots[k]); selected.set(p, k, value); sampled[p] |= value != 0;
			}
			var svd = DecompositionFactory_DDRM.svd(3 * n, selectedCount, true, true, true);
			if (!svd.decompose(selected)) throw new IllegalStateException("Coupled velocity SVD failed");
			var u = svd.getU(null, false); var v = svd.getV(null, false); var singular = svd.getSingularValues();
			for (int j = 0; j < selectedCount; j++) if (!(singular[j] > 0 && Double.isFinite(singular[j])))
				throw new IllegalStateException("Selected coupled velocity modes are singular");
			for (int k = 0; k < selectedCount; k++) for (int p = 0; p < 3 * n; p++) if (sampled[p]) {
				double value = 0;
				for (int j = 0; j < selectedCount; j++) value += v.get(k, j) * u.get(p, j) / singular[j];
				value = value * root[p / 3] / scales.get(pivots[k]);
				if (!Double.isFinite(value)) throw new IllegalArgumentException("Unrepresentable coupled velocity weight");
				rawWeights[k][p] = value;
			}
		}
		return new Model(directions.length, fields, pairedWeights(rawWeights, meanWeight, shaped, translationAxis, pivot));
	}

	private static SignedDyadic[][][] pairedWeights(double[][] raw, double[] mean, boolean[] shaped, int[] translationAxis, int pivot) {
		int translations = translationAxis.length, n = mean.length;
		var weights = new SignedDyadic[translations + raw.length][3][n];
		for (int mode = 0; mode < weights.length; mode++) for (int axis = 0; axis < 3; axis++) {
			boolean translation = mode < translations && translationAxis[mode] == axis;
			SignedDyadic sum = SignedDyadic.ZERO;
			if (mode >= translations) for (int p = 0; p < n; p++) sum = sum.add(SignedDyadic.of(raw[mode - translations][3 * p + axis]));
			SignedDyadic reference = translation ? SignedDyadic.of(1) : SignedDyadic.ZERO;
			// The largest mass carries reduction rounding; tiny positive quadrature nodes retain their own scale.
			for (int p = 0; p < n; p++) if (shaped[axis] || p != pivot) {
				SignedDyadic value = mode < translations ? SignedDyadic.ZERO : SignedDyadic.of(raw[mode - translations][3 * p + axis]);
				if (!shaped[axis]) value = value.subtract(SignedDyadic.of(mean[p]).multiply(sum))
						.add(translation ? SignedDyadic.of(mean[p]) : SignedDyadic.ZERO);
				weights[mode][axis][p] = value;
				if (!shaped[axis]) reference = reference.subtract(value);
			}
			if (!shaped[axis]) weights[mode][axis][pivot] = reference;
		}
		return weights;
	}

	private static double[][] reliableDirections(double[][] position, double[][] error, double[] root, double total) {
		int n = root.length; double totalRoot = Math.sqrt(total), radius = 0, perturbation = 0;
		var geometry = new DMatrixRMaj(n, 3);
		for (int p = 0; p < n; p++) for (int axis = 0; axis < 3; axis++) {
			double value = root[p] / totalRoot * position[p][axis]; geometry.set(p, axis, value);
			radius = Math.hypot(radius, value); perturbation = Math.hypot(perturbation, root[p] / totalRoot * error[p][axis]);
		}
		// Centring adds another storage perturbation. The covariance bound is 2 R E + E squared.
		perturbation *= 2; double bound = 2 * radius * perturbation + perturbation * perturbation;
		if (radius == 0) return new double[0][3];
		var svd = DecompositionFactory_DDRM.svd(n, 3, false, true, true);
		if (!svd.decompose(geometry)) throw new IllegalStateException("Velocity support SVD failed");
		var v = svd.getV(null, false); var singular = svd.getSingularValues();
		Integer[] order = new Integer[svd.numberOfSingularValues()];
		for (int k = 0; k < order.length; k++) order[k] = k;
		Arrays.sort(order, (a, b) -> Double.compare(singular[b], singular[a]));
		var directions = new ArrayList<double[]>();
		for (int k : order) if (singular[k] * singular[k] > bound) {
			double[] direction = new double[3]; for (int axis = 0; axis < 3; axis++) direction[axis] = v.get(axis, k);
			directions.add(direction);
		}
		return directions.toArray(double[][]::new);
	}

	private static double evaluate(double[] field, double[] position, int axis) {
		double value = field[4 * axis];
		for (int j = 0; j < 3; j++) value += field[4 * axis + j + 1] * position[j];
		return value;
	}
	private static void validate(double[] mass, double[][] position, double[][] error, double[][] shape, double[][] shapeDerivative, double[] shapeCurvature, boolean[] shaped, double[] offset) {
		if (mass == null || mass.length == 0 || position == null || position.length != mass.length
				|| error == null || error.length != mass.length || shape == null || shape.length != 3
				|| shapeDerivative == null || shapeDerivative.length != 3 || shapeCurvature == null || shapeCurvature.length != 3
				|| shaped == null || shaped.length != 3 || offset == null || offset.length != 3)
			throw new IllegalArgumentException("Invalid coupled velocity input dimensions");
		for (int axis = 0; axis < 3; axis++) if (shape[axis] == null || shape[axis].length != mass.length || shapeDerivative[axis] == null
				|| shapeDerivative[axis].length != mass.length || !Double.isFinite(shapeCurvature[axis]) || !Double.isFinite(offset[axis]))
			throw new IllegalArgumentException("Invalid coupled velocity shape or centre");
		for (int p = 0; p < mass.length; p++) {
			if (!(mass[p] > 0 && Double.isFinite(mass[p])) || position[p] == null || position[p].length != 3
					|| error[p] == null || error[p].length != 3) throw new IllegalArgumentException("Invalid coupled velocity source");
			for (int axis = 0; axis < 3; axis++) if (!Double.isFinite(position[p][axis]) || !(error[p][axis] >= 0 && Double.isFinite(error[p][axis]))
					|| !Double.isFinite(shape[axis][p]) || !Double.isFinite(shapeDerivative[axis][p])
					|| !shaped[axis] && (shape[axis][p] != 1 || shapeDerivative[axis][p] != 0 || shapeCurvature[axis] != 0))
				throw new IllegalArgumentException("Invalid coupled velocity position, storage error or shape");
		}
	}
}

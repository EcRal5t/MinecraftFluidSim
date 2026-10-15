package mmm.fluid;

import mmm.units.SignedDyadic;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.ejml.data.DMatrixRMaj;
import org.ejml.dense.row.factory.DecompositionFactory_DDRM;
import org.ejml.ops.DConvertMatrixStruct;

/** Reliable mass-whitened local subspaces and a physical coarse space precondition the actual solve. */
final class SparsePressureFactor {
	private SparsePressureFactor() { }
	private record Patch(PressureRowBasis basis, double[] scale, double[][] inverseModes, double[] solution) { }
	private record Coarse(CellVelocityConstraintOperator.CoarseSpace space, Patch patch, double[] restricted, double[] restrictedLow) { }
	private record FactorSize(double retained, double workspace) { }
	static PressureProjectionResult solve(CellVelocityConstraintOperator operator, double[] rhs, double[] pressure,
			double[] diagonal, double tolerance, int maximumSolves) {
		var exact = new SignedDyadic[rhs.length];
		for (int row = 0; row < rhs.length; row++) exact[row] = SignedDyadic.of(rhs[row]);
		var candidate = pressure.clone(); var low = new double[rhs.length];
		var result = solve(operator, exact, candidate, low, diagonal, tolerance, maximumSolves);
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
			double[] diagonal, double tolerance,
			int maximumSolves) {
		if (pressure == pressureLow) throw new IllegalArgumentException("Aliased pressure accumulator columns");
		if (maximumSolves < 0) throw new IllegalArgumentException("Negative pressure factor solve budget");
		int iterations = 0; double initialResidual = Double.POSITIVE_INFINITY;
		if (maximumSolves == 0) return unavailable(iterations, initialResidual);
		try {
			double[] image = new double[rhs.length]; operator.residual(pressure, pressureLow, rhs, image);
			initialResidual = rms(image);
			if (Double.isFinite(initialResidual) && initialResidual <= tolerance)
				return new PressureProjectionResult(0, initialResidual, true);
			if (!Double.isFinite(initialResidual)) return unavailable(iterations, initialResidual);
			var coordinates = operator.rowPatches();
			var coarseSpace = operator.coarseSpace();
			var coarseBasis = coarseSpace == null ? null : coarseSpace.operator().coarseBasis();
			var diagonalScale = new double[rhs.length];
			for (int row = 0; row < diagonalScale.length; row++) if (diagonal[row] > 0) {
				diagonalScale[row] = 1 / Math.sqrt(diagonal[row]);
				if (!(diagonalScale[row] > 0 && Double.isFinite(diagonalScale[row]))) return unavailable(iterations, initialResidual);
			}
			double iterationBytes = 8 * 20.0 * rhs.length;
			double retainedBytes = operator.pressureResponseBytes(), workspaceBytes = 0;
			if (coarseSpace != null) retainedBytes += coarseSpace.operator().pressureResponseBytes();
			for (var basis : coordinates) {
				var size = factorSize(operator, basis); retainedBytes += size.retained;
				workspaceBytes = Math.max(workspaceBytes, size.workspace);
			}
			if (coarseBasis != null && coarseBasis.size() > 0) {
				var size = factorSize(coarseSpace.operator(), coarseBasis); retainedBytes += size.retained;
				workspaceBytes = Math.max(workspaceBytes, size.workspace);
			}
			// Only one patch's dense decomposition is live; retained subspace modes and CG vectors count together.
			if (retainedBytes + workspaceBytes + iterationBytes > PressureRowBasis.availableMemory())
				return unavailable(iterations, initialResidual);
			var patches = new ArrayList<Patch>(coordinates.size());
			for (var basis : coordinates) patches.add(factor(operator, basis, diagonalScale));
			Coarse coarse = null;
			if (coarseBasis != null && coarseBasis.size() > 0) {
				var scale = new double[coarseSpace.operator().rowCount()]; coarseSpace.operator().diagonal(scale);
				for (int row = 0; row < scale.length; row++) scale[row] = scale[row] > 0 ? 1 / Math.sqrt(scale[row]) : 0;
				coarse = new Coarse(coarseSpace, factor(coarseSpace.operator(), coarseBasis, scale),
						new double[scale.length], new double[scale.length]);
			}
			double[] candidate = pressure.clone(), candidateLow = pressureLow.clone();
			double[] best = candidate.clone(), bestLow = candidateLow.clone(); double residual = initialResidual;
			var response = new double[2][rhs.length]; var direction = new double[2][rhs.length];
			if (!precondition(patches, coarse, diagonalScale, image, response)) return unavailable(iterations, initialResidual);
			double product = dot(image, response);
			if (!(product > 0 && Double.isFinite(product))) return unavailable(iterations, initialResidual);
			copy(response, direction);
			var actual = new double[rhs.length]; var action = new double[rhs.length];
			double cycleResidual = initialResidual;
			while (iterations < maximumSolves) {
				iterations++;
				// The captured physical constraint and inertia supply the actual image and positive work.
				double work = operator.applyCachedResponse(direction[0], direction[1], action);
				if (!(work > 0 && Double.isFinite(work))) break;
				double alpha = product / work;
				if (!(alpha > 0 && Double.isFinite(alpha)) || !advance(candidate, candidateLow, direction, alpha)) break;
				for (int row = 0; row < image.length; row++) image[row] = Math.fma(-alpha, action[row], image[row]);
				double estimated = rms(image);
				// Recompute before cancellation against the cycle's original residual consumes half the working precision.
				if (!Double.isFinite(estimated) || estimated <= Math.max(tolerance, Math.sqrt(Math.ulp(1.0)) * cycleResidual)
						|| iterations == maximumSolves) {
					operator.residual(candidate, candidateLow, rhs, actual); double observed = rms(actual);
					if (Double.isFinite(observed) && observed < residual) {
						best = candidate.clone(); bestLow = candidateLow.clone(); residual = observed;
					}
					if (residual <= tolerance || iterations == maximumSolves || !(residual < cycleResidual)) break;
					// The recursive residual has lost accuracy: restart from the best actual state within the same budget.
					System.arraycopy(best, 0, candidate, 0, candidate.length);
					System.arraycopy(bestLow, 0, candidateLow, 0, candidateLow.length);
					operator.residual(best, bestLow, rhs, image); cycleResidual = residual;
					if (!precondition(patches, coarse, diagonalScale, image, response)) break;
					product = dot(image, response);
					if (!(product > 0 && Double.isFinite(product))) break;
					copy(response, direction); continue;
				}
				if (!precondition(patches, coarse, diagonalScale, image, response)) break;
				double next = dot(image, response), beta = next / product;
				if (!(next > 0 && Double.isFinite(next) && beta > 0 && Double.isFinite(beta))) break;
				if (!combine(response, direction, beta)) break;
				product = next;
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
	private static FactorSize factorSize(CellVelocityConstraintOperator operator, PressureRowBasis basis) {
		var size = operator.whitenedSize(basis);
		double rectangle = (double) size.features() * basis.size();
		double triangle = (double) basis.size() * (basis.size() + 1L) / 2;
		if (rectangle > Integer.MAX_VALUE || triangle > Integer.MAX_VALUE)
			throw new PressureRowBasis.Unavailable("Pressure subspace exceeds matrix index capacity");
		return new FactorSize(16 * triangle + 128.0 * basis.size(),
				36 * rectangle + 48.0 * size.maximumEntries() + 128.0 * size.features());
	}
	private static Patch factor(CellVelocityConstraintOperator operator, PressureRowBasis basis, double[] diagonalScale) {
		var scale = new double[basis.size()];
		for (int row = 0; row < scale.length; row++) {
			scale[row] = diagonalScale[basis.row(row)];
			if (!(scale[row] > 0 && Double.isFinite(scale[row])))
				throw new PressureRowBasis.Unavailable("Unrepresentable pressure subspace scale");
		}
		var sparse = operator.massWhitenedTranspose(basis, scale);
		var matrix = DConvertMatrixStruct.convert(sparse, (DMatrixRMaj) null);
		var svd = DecompositionFactory_DDRM.svd(matrix.numRows, matrix.numCols, false, true, true);
		if (!svd.decompose(matrix)) throw new PressureRowBasis.Unavailable("Pressure subspace SVD is unavailable");
		var singular = svd.getSingularValues(); var directions = svd.getV(null, false); double maximum = 0;
		for (int mode = 0; mode < svd.numberOfSingularValues(); mode++) maximum = Math.max(maximum, singular[mode]);
		double resolution = Math.max(matrix.numRows, matrix.numCols) * Math.ulp(1.0) * maximum;
		var inverseModes = new ArrayList<double[]>();
		for (int mode = 0; mode < svd.numberOfSingularValues(); mode++) if (singular[mode] > resolution) {
			var inverse = new double[basis.size()];
			for (int row = 0; row < inverse.length; row++) inverse[row] = directions.get(row, mode) / singular[mode];
			inverseModes.add(inverse);
		}
		return new Patch(basis, scale, inverseModes.toArray(double[][]::new), new double[basis.size()]);
	}

	private static boolean precondition(List<Patch> patches, Coarse coarse, double[] diagonalScale, double[] rhs, double[][] result) {
		Arrays.fill(result[0], 0); Arrays.fill(result[1], 0);
		for (int row = 0; row < rhs.length; row++) {
			double scale = diagonalScale[row], product = rhs[row] * scale;
			ConstraintNumerics.accumulateProduct(result[0], result[1], row, product, scale);
			ConstraintNumerics.accumulateProduct(result[0], result[1], row, Math.fma(rhs[row], scale, -product), scale);
		}
		for (var patch : patches) {
			var solution = response(patch, rhs);
			if (!finite(solution)) return false;
			for (int row = 0; row < solution.length; row++) {
				int global = patch.basis.row(row);
				ConstraintNumerics.accumulateProduct(result[0], result[1], global, solution[row], patch.scale[row]);
			}
		}
		if (coarse != null) {
			int[][] rows = coarse.space.fineRows(); double[][] weights = coarse.space.weights();
			var restricted = coarse.restricted; var restrictedLow = coarse.restrictedLow;
			Arrays.fill(restricted, 0); Arrays.fill(restrictedLow, 0);
			for (int column = 0; column < rows.length; column++) {
				for (int entry = 0; entry < rows[column].length; entry++)
					ConstraintNumerics.accumulateProduct(restricted, restrictedLow, column,
							weights[column][entry], rhs[rows[column][entry]]);
				restricted[column] += restrictedLow[column];
			}
			var solution = response(coarse.patch, restricted);
			if (!finite(solution)) return false;
			for (int row = 0; row < solution.length; row++) {
				int column = coarse.patch.basis.row(row);
				double value = solution[row] * coarse.patch.scale[row];
				double low = Math.fma(solution[row], coarse.patch.scale[row], -value);
				for (int entry = 0; entry < rows[column].length; entry++) {
					int fine = rows[column][entry];
					ConstraintNumerics.accumulateProduct(result[0], result[1], fine, value, weights[column][entry]);
					ConstraintNumerics.accumulateProduct(result[0], result[1], fine, low, weights[column][entry]);
				}
			}
		}
		return finite(result[0]) && finite(result[1]);
	}
	private static double[] response(Patch patch, double[] rhs) {
		var solution = patch.solution; Arrays.fill(solution, 0);
		for (var mode : patch.inverseModes) {
			double projection = 0, correction = 0;
			for (int row = 0; row < solution.length; row++) {
				double input = rhs[patch.basis.row(row)] * patch.scale[row], product = mode[row] * input;
				double next = projection + product, virtual = next - projection;
				correction += (projection - (next - virtual)) + (product - virtual) + Math.fma(mode[row], input, -product);
				projection = next;
			}
			projection += correction;
			for (int row = 0; row < solution.length; row++) solution[row] = Math.fma(mode[row], projection, solution[row]);
		}
		return solution;
	}

	private static boolean advance(double[] high, double[] low, double[][] direction, double scale) {
		for (int row = 0; row < high.length; row++) {
			ConstraintNumerics.accumulateProduct(high, low, row, scale, direction[0][row]);
			ConstraintNumerics.accumulateProduct(high, low, row, scale, direction[1][row]);
			if (!(Double.isFinite(high[row]) && Double.isFinite(low[row]))) return false;
		}
		return true;
	}
	private static boolean combine(double[][] response, double[][] direction, double beta) {
		for (int row = 0; row < direction[0].length; row++) {
			double high = direction[0][row], low = direction[1][row];
			direction[0][row] = response[0][row]; direction[1][row] = response[1][row];
			ConstraintNumerics.accumulateProduct(direction[0], direction[1], row, beta, high);
			ConstraintNumerics.accumulateProduct(direction[0], direction[1], row, beta, low);
			if (!(Double.isFinite(direction[0][row]) && Double.isFinite(direction[1][row]))) return false;
		}
		return true;
	}
	private static double dot(double[] residual, double[][] response) {
		double sum = 0, correction = 0;
		for (int row = 0; row < residual.length; row++) for (int column = 0; column < 2; column++) {
			double product = residual[row] * response[column][row], next = sum + product;
			double virtual = next - sum;
			correction += (sum - (next - virtual)) + (product - virtual)
					+ Math.fma(residual[row], response[column][row], -product);
			sum = next;
		}
		return sum + correction;
	}
	private static void copy(double[][] source, double[][] target) {
		for (int axis = 0; axis < 2; axis++) System.arraycopy(source[axis], 0, target[axis], 0, source[axis].length);
	}
	private static boolean finite(double[] values) { for (double value : values) if (!Double.isFinite(value)) return false; return true; }

	private static PressureProjectionResult unavailable(int iterations, double residual) { return new PressureProjectionResult(iterations, residual, false); }
	private static double rms(double[] values) {
		double norm = 0; for (double value : values) norm = Math.hypot(norm, value);
		return norm / Math.sqrt(Math.max(1, values.length));
	}
}

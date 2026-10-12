package mmm.fluid;

import mmm.chemistry.ExactRational;
import mmm.units.SignedDyadic;
import java.util.ArrayList;

/** Positive mass quadrature retaining the joint position/velocity first and second moments. */
final class CellRemapSpatialMoments {
	private double mass;
	private final double[] mean = new double[6];
	private final double[][] covariance = new double[6][6];

	void add(FluidDomain domain, int ordinal, ExactRational fraction) {
		add(domain, ordinal, fraction, 0, 0, 0);
	}

	/** The translation is in cell-normalized coordinates, relative to the source cell center. */
	void add(FluidDomain domain, int ordinal, ExactRational fraction, double dx, double dy, double dz) {
		if (fraction.signum() < 0) throw new IllegalArgumentException("Negative source share");
		if (fraction.isZero()) return;
		int cell = domain.particleCellIndex(ordinal);
		double h = domain.config.cellSize();
		SignedDyadic sourceMass = SignedDyadic.of(domain.particles.mass(ordinal));
		var rawMass = sourceMass.exponent() >= 0
				? new ExactRational(sourceMass.significand().shiftLeft(sourceMass.exponent()), java.math.BigInteger.ONE)
				: new ExactRational(sourceMass.significand(), java.math.BigInteger.ONE.shiftLeft(-sourceMass.exponent()));
		double weight = rawMass.multiply(fraction).toDouble();
		add(weight, new double[]{domain.particles.positionX(ordinal) / h - cell % domain.sizeX - .5 + dx,
				domain.particles.positionY(ordinal) / h - cell / domain.sizeX % domain.sizeY - .5 + dy,
				domain.particles.positionZ(ordinal) / h - cell / (domain.sizeX * domain.sizeY) - .5 + dz},
				new double[]{domain.particles.velocityX(ordinal), domain.particles.velocityY(ordinal), domain.particles.velocityZ(ordinal)});
	}

	void add(double weight, double[] position, double[] velocity) {
		checkPositive(weight);
		double[] point = new double[6];
		for (int a = 0; a < 3; a++) { point[a] = finite(position[a]); point[a + 3] = finite(velocity[a]); }
		merge(weight, point, null);
	}

	/** Parallel-axis merge uses centered moments, retaining small spreads around large velocities. */
	void add(CellRemapSpatialMoments other) {
		if (other.mass == 0) return;
		merge(other.mass, other.mean, other.covariance);
	}

	private void merge(double addedMass, double[] addedMean, double[][] addedCovariance) {
		double total = finite(mass + addedMass), ratio = addedMass / total, retained = mass / total;
		double[] delta = new double[6];
		for (int a = 0; a < 6; a++) delta[a] = addedMean[a] - mean[a];
		for (int a = 0; a < 6; a++) for (int b = a; b < 6; b++)
			covariance[b][a] = covariance[a][b] = finite(retained * covariance[a][b] + (addedCovariance == null ? 0 : ratio * addedCovariance[a][b])
					+ retained * ratio * delta[a] * delta[b]);
		for (int a = 0; a < 6; a++) mean[a] = finite(mean[a] + ratio * delta[a]);
		mass = total;
	}

	/** Joint affine advection: position += alpha * velocity in normalized cell coordinates. */
	void advancePositions(double alpha) {
		finite(alpha);
		for (int a = 0; a < 3; a++) for (int b = a; b < 3; b++) {
			double value = covariance[a][b] + alpha * (covariance[a][b + 3] + covariance[a + 3][b])
					+ alpha * alpha * covariance[a + 3][b + 3];
			covariance[a][b] = covariance[b][a] = finite(value);
		}
		for (int a = 0; a < 3; a++) {
			mean[a] = finite(mean[a] + alpha * mean[a + 3]);
			for (int b = 0; b < 3; b++) covariance[a][b + 3] = covariance[b + 3][a]
					= finite(covariance[a][b + 3] + alpha * covariance[a + 3][b + 3]);
		}
	}

	void setCentroid(double[] centroid) { for (int a = 0; a < 3; a++) mean[a] = finite(centroid[a]); }
	/** A transported face measure has a constant normal position and retains tangential moments. */
	void projectPositionAxis(int axis) {
		if (axis < 0 || axis >= 3) throw new IllegalArgumentException("Unknown spatial axis");
		for (int a = 0; a < 6; a++) covariance[axis][a] = covariance[a][axis] = 0;
	}
	double mass() { return mass; }
	double[] centroid() { return new double[]{mean[0], mean[1], mean[2]}; }
	double spatialVariance(int axis) { return covariance[axis][axis]; }
	int spatialRank() { return decompose().space.rank; }
	int spatialAxes() {
		int axes = 0; for (int a = 0; a < 3; a++) if (covariance[a][a] > 0) axes |= 1 << a; return axes;
	}
	int minimumCount() { var state = decompose(); return 1 + state.space.rank + state.residual.rank; }

	/** Rows are nodes, columns are axes. Preferred geometry chooses the latent orientation only. */
	Result fit(double[] weights, double[][] preferredOffsets) {
		checkPositive(mass);
		if (weights.length != preferredOffsets.length) throw new IllegalArgumentException("Quadrature layout differs");
		var state = decompose(); int count = weights.length;
		if (count < 1 + state.space.rank + state.residual.rank)
			throw failure(Failure.INSUFFICIENT_NODES, "Positive liquid quadrature cannot retain its joint moment rank");
		double total = SignedDyadic.sum(weights).toDouble(); checkPositive(total);
		double[] probability = new double[count];
		for (int p = 0; p < count; p++) { checkPositive(weights[p]); probability[p] = weights[p] / total; checkPositive(probability[p]); }
		var basis = new ArrayList<double[]>();
		for (int k = 0; k < state.space.rank; k++) {
			double[] candidate = new double[count];
			for (int p = 0; p < count; p++) for (int a = 0; a < 3; a++)
				candidate[p] += finite(preferredOffsets[p][a]) * state.space.axes[a][k];
			basis.add(nextBasis(candidate, probability, basis));
		}
		for (int k = 0; k < state.residual.rank; k++) basis.add(nextBasis(new double[count], probability, basis));
		double[][] positions = new double[count][3], velocities = new double[count][3];
		for (int p = 0; p < count; p++) for (int a = 0; a < 3; a++) {
			double x = mean[a], v = mean[a + 3];
			for (int k = 0; k < state.space.rank; k++) {
				x += state.space.axes[a][k] * Math.sqrt(state.space.values[k]) * basis.get(k)[p];
				v += state.affine[a][k] * basis.get(k)[p];
			}
			for (int k = 0; k < state.residual.rank; k++)
				v += state.residual.axes[a][k] * Math.sqrt(state.residual.values[k]) * basis.get(state.space.rank + k)[p];
			positions[p][a] = finite(x); velocities[p][a] = finite(v);
		}
		verify(probability, positions, velocities);
		return new Result(positions, velocities);
	}

	private Decomposition decompose() {
		checkPositive(mass);
		double[][] xx = new double[3][3], residual = new double[3][3];
		for (int a = 0; a < 3; a++) for (int b = 0; b < 3; b++) {
			xx[a][b] = covariance[a][b]; residual[a][b] = covariance[a + 3][b + 3];
		}
		Spectrum space = spectrum(xx, scale(xx)); double[][] affine = new double[3][3];
		for (int a = 0; a < 3; a++) for (int k = 0; k < space.rank; k++) {
			for (int b = 0; b < 3; b++) affine[a][k] += covariance[a + 3][b] * space.axes[b][k];
			affine[a][k] /= Math.sqrt(space.values[k]);
		}
		for (int a = 0; a < 3; a++) for (int b = 0; b < 3; b++) for (int k = 0; k < space.rank; k++)
			residual[a][b] -= affine[a][k] * affine[b][k];
		// Tolerance uses the full velocity covariance: an exact affine field has roundoff residuals.
		double velocityScale = 0;
		for (int a = 3; a < 6; a++) velocityScale = Math.max(velocityScale, Math.abs(covariance[a][a]));
		Spectrum unresolved = spectrum(residual, velocityScale);
		return new Decomposition(space, affine, unresolved);
	}

	private static double[] nextBasis(double[] preferred, double[] probability, ArrayList<double[]> previous) {
		double norm = orthogonalize(preferred, probability, previous);
		if (norm <= 1e-24) {
			// Centered coordinate indicators span every remaining positive-weight mode.
			double best = 0; double[] selected = null;
			for (int p = 0; p < probability.length; p++) {
				double[] candidate = new double[probability.length]; candidate[p] = 1 / Math.sqrt(probability[p]);
				double length = orthogonalize(candidate, probability, previous);
				if (length > best) { best = length; selected = candidate; }
			}
			if (!(best > 1e-24)) throw failure(Failure.UNREPRESENTABLE, "Positive quadrature has no representable orthogonal moment mode");
			preferred = selected; norm = best;
		}
		for (int p = 0; p < preferred.length; p++) preferred[p] = finite(preferred[p] / Math.sqrt(norm));
		return preferred;
	}

	private static double orthogonalize(double[] vector, double[] probability, ArrayList<double[]> previous) {
		// Reorthogonalization protects zero mean and cross moments for unequal positive masses.
		for (int pass = 0; pass < 2; pass++) {
			double mean = 0; for (int p = 0; p < vector.length; p++) mean += probability[p] * vector[p];
			for (int p = 0; p < vector.length; p++) vector[p] -= mean;
			for (double[] q : previous) {
				double dot = 0; for (int p = 0; p < vector.length; p++) dot += probability[p] * vector[p] * q[p];
				for (int p = 0; p < vector.length; p++) vector[p] -= dot * q[p];
			}
		}
		double norm = 0; for (int p = 0; p < vector.length; p++) norm += probability[p] * vector[p] * vector[p];
		return finite(norm);
	}

	/** Jacobi spectrum, including planar/linear covariance and a typed rejection of negative modes. */
	private static Spectrum spectrum(double[][] source, double referenceScale) {
		double[][] a = new double[3][3], q = new double[3][3];
		for (int i = 0; i < 3; i++) { a[i] = source[i].clone(); q[i][i] = 1; }
		for (int sweep = 0; sweep < 32; sweep++) {
			int p = 0, r = 1;
			for (int i = 0; i < 3; i++) for (int j = i + 1; j < 3; j++)
				if (Math.abs(a[i][j]) > Math.abs(a[p][r])) { p = i; r = j; }
			if (Math.abs(a[p][r]) <= 8 * Math.ulp(scale(a))) break;
			double angle = .5 * Math.atan2(2 * a[p][r], a[r][r] - a[p][p]);
			double c = Math.cos(angle), s = Math.sin(angle);
			for (int i = 0; i < 3; i++) {
				double ap = a[i][p], ar = a[i][r]; a[i][p] = c * ap - s * ar; a[i][r] = s * ap + c * ar;
				double qp = q[i][p], qr = q[i][r]; q[i][p] = c * qp - s * qr; q[i][r] = s * qp + c * qr;
			}
			for (int j = 0; j < 3; j++) {
				double ap = a[p][j], ar = a[r][j]; a[p][j] = c * ap - s * ar; a[r][j] = s * ap + c * ar;
			}
		}
		double tolerance = 128 * Math.ulp(Math.max(referenceScale, scale(a)));
		double[] values = new double[3]; double[][] axes = new double[3][3]; int rank = 0;
		for (int k = 0; k < 3; k++) {
			double value = finite(a[k][k]);
			if (value < -tolerance) throw failure(Failure.NON_POSITIVE_COVARIANCE, "Liquid joint covariance has a negative eigenvalue");
			if (value <= tolerance) continue;
			values[rank] = value; for (int i = 0; i < 3; i++) axes[i][rank] = q[i][k]; rank++;
		}
		return new Spectrum(values, axes, rank);
	}

	private void verify(double[] probability, double[][] positions, double[][] velocities) {
		double[] observed = new double[6]; double[][] second = new double[6][6];
		for (int p = 0; p < probability.length; p++) {
			double[] value = {positions[p][0], positions[p][1], positions[p][2], velocities[p][0], velocities[p][1], velocities[p][2]};
			for (int a = 0; a < 6; a++) {
				observed[a] += probability[p] * value[a];
				for (int b = a; b < 6; b++) second[a][b] += probability[p] * (value[a] - mean[a]) * (value[b] - mean[b]);
			}
		}
		for (int a = 0; a < 6; a++) {
			if (Math.abs(observed[a] - mean[a]) > 512 * Math.ulp(Math.max(Math.abs(mean[a]), Math.sqrt(Math.abs(covariance[a][a])))))
				throw failure(Failure.UNREPRESENTABLE, "Liquid quadrature cannot represent its first moment");
			for (int b = a; b < 6; b++) {
				double scale = Math.sqrt(Math.abs(covariance[a][a])) * Math.sqrt(Math.abs(covariance[b][b]));
				double tolerance = 1024 * Math.ulp(scale) + 1024 * Math.ulp(Math.abs(mean[a])) * Math.sqrt(Math.abs(covariance[b][b]))
						+ 1024 * Math.ulp(Math.abs(mean[b])) * Math.sqrt(Math.abs(covariance[a][a]));
				if (Math.abs(second[a][b] - covariance[a][b]) > tolerance)
					throw failure(Failure.UNREPRESENTABLE, "Liquid quadrature cannot represent its joint second moment");
			}
		}
	}

	private static double scale(double[][] matrix) {
		double value = 0; for (double[] row : matrix) for (double item : row) value = Math.max(value, Math.abs(item)); return finite(value);
	}
	private static void checkPositive(double value) {
		if (!(Double.isFinite(value) && value > 0)) throw failure(Failure.UNREPRESENTABLE, "Liquid moment mass must be finite and positive");
	}
	private static double finite(double value) {
		if (!Double.isFinite(value)) throw failure(Failure.UNREPRESENTABLE, "Liquid moment is unrepresentable in binary64"); return value;
	}
	private static FitFailure failure(Failure failure, String message) { return new FitFailure(failure, message); }
	record Result(double[][] positions, double[][] velocities) { }
	private record Spectrum(double[] values, double[][] axes, int rank) { }
	private record Decomposition(Spectrum space, double[][] affine, Spectrum residual) { }
	enum Failure { INSUFFICIENT_NODES, NON_POSITIVE_COVARIANCE, UNREPRESENTABLE }
	static final class FitFailure extends IllegalStateException {
		final Failure failure;
		FitFailure(Failure failure, String message) { super(message); this.failure = failure; }
	}
}

package mmm.fluid;

import mmm.chemistry.ExactRational;
import mmm.units.SignedDyadic;
import java.util.ArrayList;

/** Positive mass quadrature retaining the joint position/velocity first and second moments. */
final class CellRemapSpatialMoments {
	private double mass;
	private final double[] mean = new double[6];
	private final double[][] covariance = new double[6][6];
	private final JointMomentFactor factor = new JointMomentFactor();

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
		merge(weight, point, null, null);
	}

	/** Parallel-axis merge uses centered moments, retaining small spreads around large velocities. */
	void add(CellRemapSpatialMoments other) {
		if (other.mass == 0) return;
		merge(other.mass, other.mean, other.covariance, other.factor);
	}

	private void merge(double addedMass, double[] addedMean, double[][] addedCovariance, JointMomentFactor addedFactor) {
		double total = finite(mass + addedMass), ratio = addedMass / total, retained = mass / total;
		double[] delta = new double[6];
		for (int a = 0; a < 6; a++) delta[a] = addedMean[a] - mean[a];
		factor.merge(addedFactor, retained, ratio, delta);
		for (int a = 0; a < 6; a++) for (int b = a; b < 6; b++)
			covariance[b][a] = covariance[a][b] = finite(retained * covariance[a][b] + (addedCovariance == null ? 0 : ratio * addedCovariance[a][b])
					+ retained * ratio * delta[a] * delta[b]);
		for (int a = 0; a < 6; a++) mean[a] = finite(ratio <= retained
				? mean[a] + ratio * delta[a] : addedMean[a] - retained * delta[a]);
		mass = total;
	}

	/** Joint affine advection: position += alpha * velocity in normalized cell coordinates. */
	void advancePositions(double alpha) {
		finite(alpha);
		factor.advancePositions(alpha);
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
		factor.projectPositionAxis(axis);
		for (int a = 0; a < 6; a++) covariance[axis][a] = covariance[a][axis] = 0;
	}
	/** Uniform arrival age adds normal thickness; tangential trajectories retain their full step. */
	void spreadFaceArrival(int axis, double normalizedDt) {
		if (axis < 0 || axis >= 3 || !(Double.isFinite(normalizedDt) && normalizedDt >= 0))
			throw new IllegalArgumentException("Invalid face arrival interval");
		double rms = Math.hypot(mean[axis + 3], Math.sqrt(covariance[axis + 3][axis + 3]));
		double deviation = finite(normalizedDt / Math.sqrt(12) * rms);
		factor.addPositionSpread(axis, deviation);
		covariance[axis][axis] = finite(covariance[axis][axis] + deviation * deviation);
	}
	/** Bounded subcell shape completion; changes only the position block of the joint measure. */
	double[] completeFaceTangents(int axis, ExactRational[] swept, double normalizedVolume, double massFraction) {
		return completeFaceTangents(axis, swept, normalizedVolume, massFraction, spatialSpan());
	}
	double[] completeFaceTangents(int axis, ExactRational[] swept, double normalizedVolume, double massFraction, double[][] span) {
		if (axis < 0 || axis > 2 || swept.length != 10 || !(Double.isFinite(normalizedVolume) && normalizedVolume > 0)
				|| !(Double.isFinite(massFraction) && massFraction > 0 && massFraction <= 1))
			throw new IllegalArgumentException("Invalid liquid shape completion");
		var added = new double[3]; if (swept[0].isZero()) return added;
		if (span.length == 3) return added;
		int x = (axis + 1) % 3, y = (axis + 2) % 3;
		var xx = sweepCovariance(swept, x, x); var xy = sweepCovariance(swept, x, y); var yy = sweepCovariance(swept, y, y);
		var trace = xx.add(yy); if (trace.isZero()) return added;
		double length = Math.cbrt(normalizedVolume), limit = finite(length * length / 16);
		var scale = PlicComponentGeometry.ratio(SignedDyadic.of(limit), SignedDyadic.of(1)).multiply(inverse(trace));
		if (scale.compareTo(ExactRational.ONE) > 0) scale = ExactRational.ONE;
		scale = scale.multiply(PlicComponentGeometry.ratio(SignedDyadic.of(massFraction), SignedDyadic.of(1)));
		if (xx.signum() < 0 || yy.signum() < 0) throw new IllegalStateException("Negative swept liquid covariance");
		var direction = new double[3];
		if (!xx.isZero()) {
			direction[x] = finite(Math.sqrt(xx.multiply(scale).toDouble()));
			if (!(direction[x] > 0)) throw failure(Failure.UNREPRESENTABLE, "Liquid tangential shape underflows its factor");
			direction[y] = finite(xy.multiply(scale).toDouble() / direction[x]);
			addShapeSpread(direction, span, added);
			yy = yy.subtract(xy.multiply(xy).multiply(inverse(xx)));
		} else if (!xy.isZero()) throw new IllegalStateException("Swept covariance has an unsupported cross moment");
		if (yy.signum() < 0) throw new IllegalStateException("Swept liquid covariance is not positive semidefinite");
		if (!yy.isZero()) {
			java.util.Arrays.fill(direction, 0); direction[y] = finite(Math.sqrt(yy.multiply(scale).toDouble()));
			if (!(direction[y] > 0)) throw failure(Failure.UNREPRESENTABLE, "Liquid tangential shape underflows its factor");
			addShapeSpread(direction, span, added);
		}
		return added;
	}
	private static ExactRational sweepCovariance(ExactRational[] swept, int a, int b) {
		int low = Math.min(a, b), high = Math.max(a, b), index = low == 0 ? 4 + high : low == 1 ? 6 + high : 9;
		var inverseVolume = inverse(swept[0]);
		return swept[index].multiply(inverseVolume).subtract(swept[a + 1].multiply(swept[b + 1]).multiply(inverseVolume).multiply(inverseVolume));
	}
	private static ExactRational inverse(ExactRational value) { return new ExactRational(value.denominator(), value.numerator()); }
	double[][] spatialSpan() {
		var state = decompose(); var span = new double[state.spatialRank()][3];
		for (int k = 0; k < span.length; k++) {
			for (int a = 0; a < 3; a++) span[k][a] = state.loads()[a][k];
			for (int pass = 0; pass < 2; pass++) for (int j = 0; j < k; j++) {
				double dot = 0; for (int a = 0; a < 3; a++) dot += span[k][a] * span[j][a];
				for (int a = 0; a < 3; a++) span[k][a] -= dot * span[j][a];
			}
			double length = Math.hypot(Math.hypot(span[k][0], span[k][1]), span[k][2]);
			if (!(length > 0 && Double.isFinite(length))) throw failure(Failure.UNREPRESENTABLE, "Liquid spatial shape has an unrepresentable span");
			for (int a = 0; a < 3; a++) span[k][a] /= length;
		}
		return span;
	}
	private void addShapeSpread(double[] direction, double[][] span, double[] added) {
		for (int pass = 0; pass < 2; pass++) for (double[] resolved : span) {
			double dot = 0; for (int a = 0; a < 3; a++) dot += direction[a] * resolved[a];
			for (int a = 0; a < 3; a++) direction[a] -= dot * resolved[a];
		}
		addPositionSpread(direction); for (int a = 0; a < 3; a++) added[a] += direction[a] * direction[a];
	}
	private void addPositionSpread(double[] direction) {
		factor.addPositionSpread(direction);
		for (int a = 0; a < 3; a++) for (int b = a; b < 3; b++)
			covariance[b][a] = covariance[a][b] = finite(covariance[a][b] + direction[a] * direction[b]);
	}
	double mass() { return mass; }
	double[] centroid() { return new double[]{mean[0], mean[1], mean[2]}; }
	double spatialVariance(int axis) { return covariance[axis][axis]; }
	int spatialRank() { return decompose().spatialRank(); }
	int spatialAxes() {
		int axes = 0; for (int a = 0; a < 3; a++) if (covariance[a][a] > 0) axes |= 1 << a; return axes;
	}
	int minimumCount() { return 1 + decompose().rank(); }

	/** Rows are nodes, columns are axes. Preferred geometry chooses the latent orientation only. */
	Result fit(double[] weights, double[][] preferredOffsets) {
		checkPositive(mass);
		if (weights.length != preferredOffsets.length) throw new IllegalArgumentException("Quadrature layout differs");
		var state = decompose(); int count = weights.length;
		if (count < 1 + state.rank())
			throw failure(Failure.INSUFFICIENT_NODES, "Positive liquid quadrature cannot retain its joint moment rank");
		double total = SignedDyadic.sum(weights).toDouble(); checkPositive(total);
		double[] probability = new double[count];
		for (int p = 0; p < count; p++) { checkPositive(weights[p]); probability[p] = weights[p] / total; checkPositive(probability[p]); }
		var basis = new ArrayList<double[]>();
		for (int k = 0; k < state.rank(); k++) {
			double[] candidate = new double[count];
			double norm = 0; for (int a = 0; a < 3; a++) norm = Math.hypot(norm, state.loads()[a][k]);
			if (norm > 0) for (int p = 0; p < count; p++) for (int a = 0; a < 3; a++)
				candidate[p] += finite(preferredOffsets[p][a]) * (state.loads()[a][k] / norm);
			basis.add(nextBasis(candidate, probability, basis));
		}
		double[][] positions = new double[count][3], velocities = new double[count][3];
		for (int p = 0; p < count; p++) for (int a = 0; a < 3; a++) {
			double x = mean[a], v = mean[a + 3];
			for (int k = 0; k < state.rank(); k++) x += state.loads()[a][k] * basis.get(k)[p];
			for (int k = 0; k < state.rank(); k++) v += state.loads()[a + 3][k] * basis.get(k)[p];
			positions[p][a] = finite(x); velocities[p][a] = finite(v);
		}
		verify(probability, positions, velocities);
		return new Result(positions, velocities);
	}

	private JointMomentFactor.Decomposition decompose() {
		checkPositive(mass);
		return factor.decompose(covariance);
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
	private static void checkPositive(double value) {
		if (!(Double.isFinite(value) && value > 0)) throw failure(Failure.UNREPRESENTABLE, "Liquid moment mass must be finite and positive");
	}
	private static double finite(double value) {
		if (!Double.isFinite(value)) throw failure(Failure.UNREPRESENTABLE, "Liquid moment is unrepresentable in binary64"); return value;
	}
	private static FitFailure failure(Failure failure, String message) { return new FitFailure(failure, message); }
	record Result(double[][] positions, double[][] velocities) { }
	enum Failure { INSUFFICIENT_NODES, UNREPRESENTABLE }
	static final class FitFailure extends IllegalStateException {
		final Failure failure;
		FitFailure(Failure failure, String message) { super(message); this.failure = failure; }
	}
}

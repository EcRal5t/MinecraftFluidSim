package mmm.fluid;

import mmm.chemistry.ExactRational;
import mmm.units.SignedDyadic;

/** Mass-metric projection of a touching sphere component onto its common contact cone. */
final class GranularContactCompression {
	/** A fixed row is n.v(first); a pair row is n.(v(second)-v(first)). */
	record Row(int first, int second, double[] normal) {
		Row { normal = normal.clone(); }
	}

	/** Errors describe this binary64 reconstruction and its stored impulse resolution. */
	record Result(double[][] values, double[] impulses, double[] gapError, double[][] arithmeticError) {
		boolean admits(Row row) { return GranularContactCompression.admits(row, values, arithmeticError); }
	}

	private GranularContactCompression() { }
	private record MassUnit(double[] values, int exponent) { }

	private static MassUnit massUnit(double[] mass) {
		double largestMass = 0;
		for (double m : mass) {
			if (!(m > 0 && Double.isFinite(m))) throw new IllegalArgumentException("Invalid contact mass");
			largestMass = Math.max(largestMass, m);
		}
		int exponent = Math.getExponent(largestMass);
		if (largestMass > 0 && largestMass < Double.MIN_NORMAL) exponent = Math.getExponent(Math.scalb(largestMass, 54)) - 54;
		double[] scaled = new double[mass.length];
		for (int i = 0; i < mass.length; i++) {
			scaled[i] = Math.scalb(mass[i], -exponent);
			if (!(scaled[i] > 0) || Math.scalb(scaled[i], exponent) != mass[i])
				throw new MechanicalTimeRefinement(2, "Unrepresentable contact mass ratio");
		}
		return new MassUnit(scaled, exponent);
	}

	private static final class Progress {
		private final Runnable budget;
		private final Row[] rows;
		private final double[] lambda;
		private final double[][] values;
		private final double[] physicalMass;
		private final double[][] input;
		private final int massExponent;
		private int sweeps, operations;
		private String phase = "setup";
		private double[] lastGapError;
		private double[] lastGaps;
		private int lastWorst = -1;
		private double lastExcess, lastWorstGap, lastWorstBound, lastWorstLambda;

		Progress(Runnable budget, Row[] rows, double[] lambda, double[][] values, int massExponent,
		         double[] physicalMass, double[][] input) {
			this.budget = budget; this.rows = rows; this.lambda = lambda; this.values = values; this.massExponent = massExponent;
			this.physicalMass = physicalMass; this.input = input;
		}

		void spend() {
			operations++;
			try { budget.run(); }
			catch (MechanicalTimeRefinement failure) {
				int active = 0; for (double l : lambda) if (l > 0) active++;
				double impulse = Math.scalb(lastWorstLambda, massExponent);
				String details = "";
				if (values.length <= 3 && !phase.equals("setup")) {
					var dump = new StringBuilder("; smallComponent massKg=").append(java.util.Arrays.toString(physicalMass))
							.append(", input=").append(java.util.Arrays.deepToString(input))
							.append(", currentValues=").append(java.util.Arrays.deepToString(values));
					for (int k = 0; k < rows.length; k++) dump.append("; row=").append(k).append("[").append(rows[k].first()).append(',')
							.append(rows[k].second()).append("] normal=").append(java.util.Arrays.toString(rows[k].normal()))
							.append(", certifiedGap=").append(lastGaps == null ? Double.NaN : lastGaps[k])
							.append(", bound=").append(lastGapError == null ? Double.NaN : lastGapError[k])
							.append(", currentGap=").append(gap(rows[k], values)).append(", currentLambdaScaled=").append(lambda[k])
							.append(", currentImpulseKg=").append(Math.scalb(lambda[k], massExponent));
					details = dump.toString();
				}
				var reported = new MechanicalTimeRefinement(failure.minimumSubsteps,
						"Common contact compression: componentN=" + values.length + ", rows=" + rows.length + ", activeRows=" + active
						+ ", phase=" + phase + ", sweepsStarted=" + sweeps + ", componentOperations=" + operations
						+ ", lastMaxKktExcess=" + lastExcess + ", worstRow=" + lastWorst + ", certifiedGap=" + lastWorstGap
						+ ", lastGapBound=" + lastWorstBound + ", certifiedImpulseKg=" + impulse + ", impulseUlpKg=" + Math.ulp(impulse)
						+ details + "; " + failure.getMessage());
				reported.initCause(failure); throw reported;
			}
		}
	}

	static Result solve(double[] mass, double[][] input, Row[] rows, Runnable spend) {
		return solve(mass, input, rows, spend, null);
	}

	static Result solve(double[] mass, double[][] input, Row[] rows, Runnable spend, double[] initialImpulses) {
		if (mass.length != input.length) throw new IllegalArgumentException("Contact input size mismatch");
		double[] physicalMass = mass;
		var unit = massUnit(mass); int massExponent = unit.exponent(); mass = unit.values();
		double[][] values = new double[mass.length][3];
		for (int i = 0; i < mass.length; i++) {
			if (!(mass[i] > 0 && Double.isFinite(mass[i])) || input[i].length != 3)
				throw new IllegalArgumentException("Invalid contact mass or velocity");
			for (int a = 0; a < 3; a++) {
				if (!Double.isFinite(input[i][a])) throw new IllegalArgumentException("Nonfinite contact velocity");
				values[i][a] = input[i][a];
			}
		}
		double[] lambda = new double[rows.length];
		var progress = new Progress(spend, rows, lambda, values, massExponent, physicalMass, input);
		spend = progress::spend;
		for (int k = 0; k < rows.length; k++) {
			spend.run(); Row row = rows[k];
			if (row.first() < 0 || row.first() >= mass.length || row.second() < -1 || row.second() >= mass.length
					|| row.first() == row.second() || row.normal().length != 3)
				throw new IllegalArgumentException("Invalid contact row");
			double norm = 0;
			for (double n : row.normal()) {
				if (!Double.isFinite(n)) throw new IllegalArgumentException("Nonfinite contact normal");
				norm = Math.fma(n, n, norm);
			}
			double diagonal = norm / mass[row.first()] + (row.second() < 0 ? 0 : norm / mass[row.second()]);
			if (!(diagonal > 0 && Double.isFinite(diagonal)))
				throw new MechanicalTimeRefinement(2, "Unrepresentable contact inverse mass");
		}
		if (initialImpulses != null) {
			if (initialImpulses.length != rows.length) throw new IllegalArgumentException("Contact impulse input size mismatch");
			boolean seeded = false;
			for (int k = 0; k < rows.length; k++) {
				spend.run(); double impulse = initialImpulses[k];
				if (!(Double.isFinite(impulse) && impulse >= 0)) throw new IllegalArgumentException("Invalid initial contact impulse");
				lambda[k] = Math.scalb(impulse, -massExponent);
				if (!Double.isFinite(lambda[k]) || impulse > 0 && lambda[k] == 0)
					throw new MechanicalTimeRefinement(2, "Unrepresentable initial contact impulse");
				seeded |= lambda[k] > 0;
			}
			if (seeded) rescaleInitialImpulse(mass, input, rows, lambda, spend);
		}
		ActiveFace activeFace = null;
		while (true) {
			// Each sweep reconstructs from the original input, so roundoff cannot become a new source velocity.
			progress.phase = "reconstruct";
			double[][] error = reconstruct(mass, input, rows, lambda, values, spend);
			double[] gapError = new double[rows.length]; boolean accepted = true;
			double[] gaps = mass.length <= 3 ? new double[rows.length] : null;
			int worst = -1; double excess = 0, worstGap = 0, worstBound = 0, worstLambda = 0;
			progress.phase = "certify";
			for (int k = 0; k < rows.length; k++) {
				spend.run(); double g = gap(rows[k], values);
				gapError[k] = gapError(rows[k], values, error);
				if (gaps != null) gaps[k] = g;
				if (!(Double.isFinite(g) && Double.isFinite(gapError[k])))
					throw new MechanicalTimeRefinement(2, "Unrepresentable contact normal velocity");
				accepted &= g >= -gapError[k] && (lambda[k] == 0 || Math.abs(g) <= gapError[k]);
				double violation = (lambda[k] == 0 ? Math.max(0, -g) : Math.abs(g)) - gapError[k];
				if (violation > excess) { worst = k; excess = violation; worstGap = g; worstBound = gapError[k]; worstLambda = lambda[k]; }
			}
			progress.lastGapError = gapError;
			progress.lastGaps = gaps; progress.lastWorst = worst; progress.lastExcess = excess;
			progress.lastWorstGap = worstGap; progress.lastWorstBound = worstBound; progress.lastWorstLambda = worstLambda;
			if (accepted) {
				// The common power-of-two mass unit leaves the cone projection unchanged; audits receive kg impulses.
				double[] physicalImpulse = new double[lambda.length];
				for (int k = 0; k < lambda.length; k++) {
					physicalImpulse[k] = Math.scalb(lambda[k], massExponent);
					if (!Double.isFinite(physicalImpulse[k]) || lambda[k] > 0 && physicalImpulse[k] == 0)
						throw new MechanicalTimeRefinement(2, "Unrepresentable physical contact impulse");
				}
				return new Result(values, physicalImpulse, gapError, error);
			}
			progress.phase = "active-face"; progress.sweeps++;
			if (activeFace == null) activeFace = new ActiveFace(mass, input, rows, lambda, spend);
			boolean changed = activeFace.update(lambda, spend);
			if (!changed) throw new MechanicalTimeRefinement(2, "Contact impulse lattice cannot certify the common cone");
		}
	}

	static boolean admits(Row row, double[][] values, double[][] error) {
		double g = gap(row, values), bound = gapError(row, values, error);
		return Double.isFinite(g) && Double.isFinite(bound) && g >= -bound;
	}

	/** Per-contact expansion is followed by the same cone projection, then one source reconstruction. */
	static Result respond(double[] mass, double[][] input, Row[] rows, Result compression, double[] restitution, Runnable spend) {
		if (restitution.length != rows.length) throw new IllegalArgumentException("Contact restitution size mismatch");
		double[] total = compression.impulses().clone(); boolean expanding = false;
		for (int k = 0; k < rows.length; k++) {
			spend.run(); double e = restitution[k];
			if (!(Double.isFinite(e) && e >= 0 && e <= 1)) throw new IllegalArgumentException("Invalid contact restitution");
			double expansion = e * total[k]; expanding |= expansion > 0;
			total[k] += expansion;
			if (!Double.isFinite(total[k])) throw new MechanicalTimeRefinement(2, "Unrepresentable total contact impulse");
		}
		if (!expanding) { auditEnergy(mass, input, compression, spend); return compression; }
		Result response = rebuild(mass, input, rows, total, spend);
		boolean feasible = true;
		for (var row : rows) { spend.run(); feasible &= response.admits(row); }
		if (!feasible) {
			var correction = solve(mass, response.values(), rows, spend);
			for (int k = 0; k < rows.length; k++) {
				spend.run(); total[k] += correction.impulses()[k];
				if (!Double.isFinite(total[k])) throw new MechanicalTimeRefinement(2, "Unrepresentable total contact impulse");
			}
			response = rebuild(mass, input, rows, total, spend);
			for (var row : rows) {
				spend.run(); if (!response.admits(row))
					throw new MechanicalTimeRefinement(2, "Total contact impulse cannot certify the common cone");
			}
		}
		auditEnergy(mass, input, response, spend); return response;
	}

	private static Result rebuild(double[] mass, double[][] input, Row[] rows, double[] impulse, Runnable spend) {
		var unit = massUnit(mass); double[] scaled = new double[impulse.length];
		for (int k = 0; k < scaled.length; k++) {
			spend.run(); scaled[k] = Math.scalb(impulse[k], -unit.exponent());
			if (!Double.isFinite(scaled[k]) || impulse[k] > 0 && scaled[k] == 0)
				throw new MechanicalTimeRefinement(2, "Unrepresentable total contact impulse");
		}
		double[][] values = new double[mass.length][3];
		var error = reconstruct(unit.values(), input, rows, scaled, values, spend);
		double[] bounds = new double[rows.length];
		for (int k = 0; k < rows.length; k++) { spend.run(); bounds[k] = gapError(rows[k], values, error); }
		return new Result(values, impulse, bounds, error);
	}

	private static void auditEnergy(double[] mass, double[][] input, Result response, Runnable spend) {
		var change = SignedDyadic.ZERO; var bound = SignedDyadic.ZERO;
		for (int i = 0; i < mass.length; i++) {
			spend.run(); var weight = SignedDyadic.of(mass[i]);
			for (int a = 0; a < 3; a++) {
				spend.run(); var old = SignedDyadic.of(input[i][a]); var next = SignedDyadic.of(response.values()[i][a]);
				var error = SignedDyadic.of(response.arithmeticError()[i][a]);
				change = change.add(weight.multiply(next.multiply(next).subtract(old.multiply(old))).multiply(SignedDyadic.of(.5)));
				bound = bound.add(weight.multiply(error).multiply(SignedDyadic.of(Math.abs(response.values()[i][a])).add(error)));
			}
		}
		if (change.compareTo(bound) > 0)
			throw new MechanicalTimeRefinement(2, "Common contact restitution exceeds its reconstruction energy bound");
	}

	/** Minimize the new source's dual energy along the supplied nonnegative impulse ray. */
	private static void rescaleInitialImpulse(double[] mass, double[][] input, Row[] rows, double[] lambda, Runnable spend) {
		SignedDyadic[][] force = new SignedDyadic[mass.length][3];
		for (int k = 0; k < rows.length; k++) {
			spend.run(); if (lambda[k] == 0) continue;
			Row row = rows[k]; var impulse = SignedDyadic.of(lambda[k]);
			for (int a = 0; a < 3; a++) {
				if (row.normal()[a] == 0) continue;
				spend.run(); var value = impulse.multiply(SignedDyadic.of(row.normal()[a]));
				var first = row.second() < 0 ? value : value.negate();
				force[row.first()][a] = force[row.first()][a] == null ? first : force[row.first()][a].add(first);
				if (row.second() >= 0) {
					spend.run(); force[row.second()][a] = force[row.second()][a] == null ? value : force[row.second()][a].add(value);
				}
			}
		}
		var derivative = SignedDyadic.ZERO; var work = SignedDyadic.ZERO;
		for (int i = 0; i < mass.length; i++) {
			spend.run(); var square = SignedDyadic.ZERO;
			for (int a = 0; a < 3; a++) if (force[i][a] != null && force[i][a].signum() != 0) {
				spend.run(); var value = force[i][a]; square = square.add(value.multiply(value));
				derivative = derivative.add(value.multiply(SignedDyadic.of(input[i][a])));
			}
			double energy = ratio(square, SignedDyadic.of(mass[i])).toDouble();
			if (!Double.isFinite(energy)) return;
			work = work.add(SignedDyadic.of(energy));
		}
		if (work.signum() <= 0) return;
		var factor = derivative.signum() >= 0 ? ExactRational.ZERO : ratio(derivative.negate(), work);
		double[] seed = new double[lambda.length];
		for (int k = 0; k < lambda.length; k++) {
			spend.run(); var candidate = ratio(SignedDyadic.of(lambda[k]), SignedDyadic.of(1)).multiply(factor);
			seed[k] = candidate.toDouble();
			if (!Double.isFinite(seed[k]) || candidate.signum() > 0 && seed[k] == 0) return;
		}
		System.arraycopy(seed, 0, lambda, 0, lambda.length);
	}

	private static ExactRational ratio(SignedDyadic numerator, SignedDyadic denominator) {
		int power = numerator.exponent() - denominator.exponent();
		return power >= 0 ? new ExactRational(numerator.significand().shiftLeft(power), denominator.significand())
				: new ExactRational(numerator.significand(), denominator.significand().shiftLeft(-power));
	}

	/** Bound-constrained PCG uses actual source momentum for every residual refresh. */
	private static final class ActiveFace {
		private final Row[] rows;
		private final SignedDyadic[] mass;
		private final SignedDyadic[][] momentum, normal;
		private final int[][] axes;
		private final ExactRational[] inverseDiagonal;
		private boolean[] free;
		private double[] direction;
		private SignedDyadic previousRho;
		private boolean restart = true;

		ActiveFace(double[] weights, double[][] input, Row[] rows, double[] lambda, Runnable spend) {
			this.rows = rows; mass = new SignedDyadic[weights.length]; momentum = new SignedDyadic[weights.length][3];
			normal = new SignedDyadic[rows.length][3]; axes = new int[rows.length][];
			inverseDiagonal = new ExactRational[rows.length];
			for (int i = 0; i < weights.length; i++) {
				spend.run(); mass[i] = SignedDyadic.of(weights[i]);
				for (int a = 0; a < 3; a++) { spend.run(); momentum[i][a] = mass[i].multiply(SignedDyadic.of(input[i][a])); }
			}
			for (int k = 0; k < rows.length; k++) {
				var norm = SignedDyadic.ZERO;
				int[] active = new int[3]; int count = 0;
				for (int a = 0; a < 3; a++) {
					spend.run(); normal[k][a] = SignedDyadic.of(rows[k].normal()[a]); norm = norm.add(normal[k][a].multiply(normal[k][a]));
					if (normal[k][a].signum() != 0) active[count++] = a;
				}
				axes[k] = java.util.Arrays.copyOf(active, count);
				spend.run(); var diagonal = ratio(norm, mass[rows[k].first()]);
				if (rows[k].second() >= 0) { spend.run(); diagonal = diagonal.add(ratio(norm, mass[rows[k].second()])); }
				inverseDiagonal[k] = new ExactRational(diagonal.denominator(), diagonal.numerator());
				if (lambda[k] > 0) {
					spend.run(); Row row = rows[k]; var impulse = SignedDyadic.of(lambda[k]);
					add(k, row.first(), row.second() < 0 ? impulse : impulse.negate(), spend);
					if (row.second() >= 0) add(k, row.second(), impulse, spend);
				}
			}
		}

		boolean update(double[] lambda, Runnable spend) {
			double[] gradient = new double[rows.length], steepest = new double[rows.length];
			boolean[] nextFree = new boolean[rows.length]; var rho = SignedDyadic.ZERO;
			for (int k = 0; k < rows.length; k++) {
				spend.run(); Row row = rows[k];
				var g = ratio(dot(k, row.first(), spend), mass[row.first()]);
				if (row.second() >= 0) g = ratio(dot(k, row.second(), spend), mass[row.second()]).subtract(g);
				gradient[k] = g.toDouble(); nextFree[k] = lambda[k] > 0 || g.signum() <= 0;
				if (!Double.isFinite(gradient[k]))
					throw new MechanicalTimeRefinement(2, "Unrepresentable contact active-face residual");
				if (nextFree[k]) {
					steepest[k] = g.multiply(inverseDiagonal[k]).multiply(-1).toDouble();
					if (!Double.isFinite(steepest[k]))
						throw new MechanicalTimeRefinement(2, "Unrepresentable contact active-face residual");
					rho = rho.subtract(SignedDyadic.of(gradient[k]).multiply(SignedDyadic.of(steepest[k])));
				}
			}
			restart |= !java.util.Arrays.equals(free, nextFree); free = nextFree;
			boolean continued = !restart && previousRho != null && previousRho.signum() > 0;
			if (!continued) direction = steepest.clone();
			else {
				double beta = ratio(rho, previousRho).toDouble();
				for (int k = 0; k < rows.length; k++) {
					spend.run(); direction[k] = free[k] ? Math.fma(beta, direction[k], steepest[k]) : 0;
				}
			}
			// A face change or a direction out of a zero bound starts a new conjugate sequence.
			boolean descending = true; var derivative = SignedDyadic.ZERO;
			for (int k = 0; k < rows.length; k++) {
				spend.run(); descending &= Double.isFinite(direction[k]) && !(lambda[k] == 0 && direction[k] < 0);
				if (Double.isFinite(direction[k])) derivative = derivative.add(SignedDyadic.of(gradient[k]).multiply(SignedDyadic.of(direction[k])));
			}
			if (!descending || derivative.signum() >= 0) { direction = steepest.clone(); continued = false; }
			previousRho = rho; restart = false;
			double largest = 0;
			for (double p : direction) { spend.run(); largest = Math.max(largest, Math.abs(p)); }
			if (!(largest > 0 && Double.isFinite(largest))) return false;
			int exponent = Math.getExponent(largest);
			if (largest < Double.MIN_NORMAL) exponent = Math.getExponent(Math.scalb(largest, 54)) - 54;
			double[] normalized = new double[rows.length]; derivative = SignedDyadic.ZERO;
			for (int k = 0; k < rows.length; k++) {
				spend.run(); normalized[k] = Math.scalb(direction[k], -exponent);
				derivative = derivative.add(SignedDyadic.of(gradient[k]).multiply(SignedDyadic.of(normalized[k])));
			}
			var work = work(normalized, spend);
			if (derivative.signum() >= 0 || work.signum() <= 0) {
				if (continued) { restart = true; return true; }
				throw new MechanicalTimeRefinement(2, "Unrepresentable contact active-face descent");
			}
			var step = ratio(derivative.negate(), work); ExactRational bound = null;
			for (int k = 0; k < rows.length; k++) {
				spend.run(); if (normalized[k] >= 0) continue;
				var limit = ratio(SignedDyadic.of(lambda[k]), SignedDyadic.of(-normalized[k]));
				if (bound == null || limit.compareTo(bound) < 0) bound = limit;
			}
			if (bound != null && step.compareTo(bound) >= 0) { step = bound; restart = true; }
			boolean changed = false;
			for (int k = 0; k < rows.length; k++) {
				spend.run(); if (normalized[k] == 0) continue;
				var old = SignedDyadic.of(lambda[k]);
				var candidate = ratio(old, SignedDyadic.of(1)).add(ratio(SignedDyadic.of(normalized[k]), SignedDyadic.of(1)).multiply(step));
				double next = candidate.toDouble();
				if (candidate.signum() < 0 || !Double.isFinite(next) || candidate.signum() > 0 && next == 0)
					throw new MechanicalTimeRefinement(2, "Unrepresentable contact active-face impulse");
				if (next == lambda[k]) continue;
				var delta = SignedDyadic.of(next).subtract(old); lambda[k] = next; changed = true;
				Row row = rows[k]; add(k, row.first(), row.second() < 0 ? delta : delta.negate(), spend);
				if (row.second() >= 0) add(k, row.second(), delta, spend);
			}
			if (!changed && continued) { restart = true; return true; }
			return changed;
		}

		private SignedDyadic work(double[] direction, Runnable spend) {
			SignedDyadic[][] impulse = new SignedDyadic[mass.length][3];
			for (int k = 0; k < rows.length; k++) {
				spend.run(); if (direction[k] == 0) continue;
				Row row = rows[k]; var p = SignedDyadic.of(direction[k]);
				for (int a : axes[k]) {
					spend.run(); var value = p.multiply(normal[k][a]);
					var first = row.second() < 0 ? value : value.negate();
					impulse[row.first()][a] = impulse[row.first()][a] == null ? first : impulse[row.first()][a].add(first);
					if (row.second() >= 0) {
						spend.run();
						impulse[row.second()][a] = impulse[row.second()][a] == null ? value : impulse[row.second()][a].add(value);
					}
				}
			}
			var result = SignedDyadic.ZERO;
			for (int i = 0; i < mass.length; i++) {
				spend.run(); var square = SignedDyadic.ZERO;
				for (int a = 0; a < 3; a++) if (impulse[i][a] != null) {
					spend.run(); square = square.add(impulse[i][a].multiply(impulse[i][a]));
				}
				double value = ratio(square, mass[i]).toDouble();
				if (!Double.isFinite(value)) throw new MechanicalTimeRefinement(2, "Unrepresentable contact active-face work");
				result = result.add(SignedDyadic.of(value));
			}
			return result;
		}

		private SignedDyadic dot(int k, int source, Runnable spend) {
			var value = SignedDyadic.ZERO;
			for (int a : axes[k]) { spend.run(); value = value.add(normal[k][a].multiply(momentum[source][a])); }
			return value;
		}
		private void add(int k, int source, SignedDyadic delta, Runnable spend) {
			for (int a : axes[k]) { spend.run(); momentum[source][a] = momentum[source][a].add(normal[k][a].multiply(delta)); }
		}
	}

	static double[][] reconstruct(double[] mass, double[][] input, Row[] rows, double[] lambda,
	                                     double[][] values, Runnable spend) {
		double[][] force = new double[mass.length][3], low = new double[mass.length][3], high = new double[mass.length][3];
		for (int k = 0; k < rows.length; k++) {
			spend.run(); Row row = rows[k]; double l = lambda[k];
			if (l == 0) continue;
			// One adjacent binary64 impulse value bounds its storage resolution, including subnormals.
			double lower = Math.max(0, Math.nextDown(l)), upper = Math.nextUp(l);
			accumulate(row.first(), row.second() < 0 ? 1 : -1, row.normal(), l, lower, upper, force, low, high);
			if (row.second() >= 0) accumulate(row.second(), 1, row.normal(), l, lower, upper, force, low, high);
		}
		double[][] error = new double[mass.length][3];
		for (int i = 0; i < mass.length; i++) {
			spend.run();
			for (int a = 0; a < 3; a++) {
				values[i][a] = input[i][a] + force[i][a] / mass[i];
				double lower = Math.nextDown(input[i][a] + Math.nextDown(low[i][a] / mass[i]));
				double upper = Math.nextUp(input[i][a] + Math.nextUp(high[i][a] / mass[i]));
				error[i][a] = Math.nextUp(Math.max(values[i][a] - lower, upper - values[i][a]));
				if (!(Double.isFinite(values[i][a]) && Double.isFinite(error[i][a])))
					throw new MechanicalTimeRefinement(2, "Unrepresentable common contact reconstruction");
			}
		}
		return error;
	}

	private static void accumulate(int i, int sign, double[] normal, double l, double lower, double upper,
	                               double[][] force, double[][] low, double[][] high) {
		for (int a = 0; a < 3; a++) {
			double n = sign * normal[a]; if (n == 0) continue;
			force[i][a] = Math.fma(n, l, force[i][a]);
			double lo = n * (n < 0 ? upper : lower), hi = n * (n < 0 ? lower : upper);
			low[i][a] = Math.nextDown(low[i][a] + Math.nextDown(lo));
			high[i][a] = Math.nextUp(high[i][a] + Math.nextUp(hi));
		}
	}

	private static double gap(Row row, double[][] values) {
		double g = 0;
		for (int a = 0; a < 3; a++) {
			double v = row.second() < 0 ? values[row.first()][a] : values[row.second()][a] - values[row.first()][a];
			g = Math.fma(row.normal()[a], v, g);
		}
		return g;
	}

	static double gapError(Row row, double[][] values, double[][] error) {
		double lower = 0, upper = 0;
		for (int a = 0; a < 3; a++) {
			double n = row.normal()[a]; if (n == 0) continue;
			double lo = Math.nextDown(values[row.first()][a] - error[row.first()][a]);
			double hi = Math.nextUp(values[row.first()][a] + error[row.first()][a]);
			if (row.second() >= 0) {
				double nextLow = Math.nextDown(values[row.second()][a] - error[row.second()][a]);
				double nextHigh = Math.nextUp(values[row.second()][a] + error[row.second()][a]);
				double oldLow = lo; lo = Math.nextDown(nextLow - hi); hi = Math.nextUp(nextHigh - oldLow);
			}
			lower = Math.nextDown(lower + Math.nextDown(n * (n < 0 ? hi : lo)));
			upper = Math.nextUp(upper + Math.nextUp(n * (n < 0 ? lo : hi)));
		}
		double g = gap(row, values);
		return Math.nextUp(Math.max(g - lower, upper - g));
	}
}

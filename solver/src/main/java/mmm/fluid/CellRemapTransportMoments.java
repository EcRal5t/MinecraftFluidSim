package mmm.fluid;

import mmm.chemistry.ExactRational;
import mmm.units.SignedDyadic;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;

/** Allocates transport moments from one positive, supply-limited source integration rule. */
final class CellRemapTransportMoments {
	private final int[] ordinals, micro;
	private final ExactRational[] supply = zeros(8);
	private static final int MASS = 0, KINETIC = 1, MOMENTUM = 2, VOLUME_MOMENT = 5, VOLUME_MOTION = 8, MOMENTS = 11;
	private final BigInteger[][] sourceMoments = new BigInteger[MOMENTS][8];
	private final int[] momentExponents = new int[MOMENTS];
	private final ExactRational volume;

	CellRemapTransportMoments(FluidDomain domain, int cell, List<Integer> original) {
		ordinals = original.stream().mapToInt(Integer::intValue).toArray();
		micro = new int[ordinals.length];
		var moments = new SignedDyadic[MOMENTS][8];
		for (var row : moments) Arrays.fill(row, SignedDyadic.ZERO);
		ExactRational total = ExactRational.ZERO;
		for (int i = 0; i < ordinals.length; i++) {
			int p = ordinals[i];
			if (domain.particleCellIndex(p) != cell) throw new IllegalArgumentException("Transport source belongs to another cell");
			micro[i] = CellRemapMicroTopology.positionMicro(domain.config.cellSize(), domain.particles.positionX(p),
					domain.particles.positionY(p), domain.particles.positionZ(p));
			var v = SignedDyadic.of(domain.particles.volume(p)); var mass = SignedDyadic.of(domain.particles.mass(p));
			var velocity = new SignedDyadic[]{SignedDyadic.of(domain.particles.velocityX(p)), SignedDyadic.of(domain.particles.velocityY(p)), SignedDyadic.of(domain.particles.velocityZ(p))};
			double h = domain.config.cellSize();
			var position = new SignedDyadic[]{SignedDyadic.of(domain.particles.positionX(p) / h - cell % domain.sizeX - .5),
					SignedDyadic.of(domain.particles.positionY(p) / h - cell / domain.sizeX % domain.sizeY - .5),
					SignedDyadic.of(domain.particles.positionZ(p) / h - cell / (domain.sizeX * domain.sizeY) - .5)};
			if (v.signum() <= 0 || mass.signum() <= 0)
				throw new IllegalArgumentException("Transport requires positive raw source volume and mass");
			int m = micro[i]; var amount = rational(v);
			supply[m] = supply[m].add(amount); total = total.add(amount);
			moments[MASS][m] = moments[MASS][m].add(mass);
			for (int a = 0; a < 3; a++) {
				moments[MOMENTUM + a][m] = moments[MOMENTUM + a][m].add(mass.multiply(velocity[a]));
				moments[KINETIC][m] = moments[KINETIC][m].add(mass.multiply(velocity[a].multiply(velocity[a])));
				moments[VOLUME_MOMENT + a][m] = moments[VOLUME_MOMENT + a][m].add(v.multiply(position[a]));
				moments[VOLUME_MOTION + a][m] = moments[VOLUME_MOTION + a][m].add(v.multiply(velocity[a]));
			}
		}
		for (int row = 0; row < MOMENTS; row++) {
			// A nonpositive unit exponent keeps each column an integer over a power of two.
			int exponent = 0;
			for (var value : moments[row]) if (value.signum() != 0) exponent = Math.min(exponent, value.exponent());
			momentExponents[row] = exponent;
			for (int m = 0; m < 8; m++) sourceMoments[row][m] = moments[row][m].significand().shiftLeft(moments[row][m].exponent() - exponent);
		}
		volume = total;
	}

	/**
	 * The last piece is retained. Each outgoing row supplies raw micro inventory times its
	 * frozen geometric swept/wet fraction, or the corresponding supported high/low proposal.
	 * Positive entries define admissible source supply; their ratios set the preferred
	 * allocation. Every marker's mass, momentum and kinetic moment use the
	 * same exact weight, including the retained weight obtained by subtraction.
	 */
	Result allocate(SignedDyadic[] pieces, double[][] microPreferences) {
		int outgoing = pieces.length - 1;
		if (outgoing < 0 || microPreferences.length != outgoing)
			throw new IllegalArgumentException("Invalid transport share layout");
		ExactRational[] demand = new ExactRational[outgoing];
		ExactRational total = ExactRational.ZERO;
		for (int d = 0; d < pieces.length; d++) {
			if (pieces[d].signum() < 0) throw new IllegalArgumentException("Negative transport volume share");
			ExactRational amount = rational(pieces[d]); total = total.add(amount);
			if (d < outgoing) demand[d] = amount;
		}
		if (!total.equals(volume)) throw new IllegalArgumentException("Transport shares differ from raw source volume");
		ExactRational[][] allocation = new ExactRational[outgoing][8];
		boolean[][] allowed = new boolean[outgoing][8];
		ExactRational[] remaining = supply.clone();
		// Seed the flow with geometric proportions, capped by the actual original supply.
		for (int d = 0; d < outgoing; d++) {
			allocation[d] = zeros(8);
			if (microPreferences[d].length != 8) throw new IllegalArgumentException("Transport needs eight micro supply preferences");
			ExactRational[] preference = zeros(8); ExactRational sum = ExactRational.ZERO;
			for (int m = 0; m < 8; m++) {
				double swept = microPreferences[d][m];
				if (!(Double.isFinite(swept) && swept >= 0)) throw new IllegalArgumentException("Invalid transport sweep measure");
				allowed[d][m] = swept > 0 && supply[m].signum() > 0;
				if (allowed[d][m]) { preference[m] = rational(swept); sum = sum.add(preference[m]); }
			}
			if (sum.isZero()) continue;
			for (int m = 0; m < 8; m++) if (allowed[d][m]) {
				ExactRational amount = min(remaining[m], demand[d].multiply(divide(preference[m], sum)));
				allocation[d][m] = amount; remaining[m] = remaining[m].subtract(amount);
			}
		}
		// Alternating paths also reassign earlier shares when a later share has scarce support.
		for (int d = 0; d < outgoing; d++) {
			ExactRational missing = demand[d].subtract(sum(allocation[d]));
			while (missing.signum() > 0) {
				ExactRational advanced = augment(d, missing, allowed, allocation, remaining);
				if (advanced.isZero()) {
					int mask = 0; for (int m = 0; m < 8; m++) if (allowed[d][m]) mask |= 1 << m;
					throw new AllocationFailure(Failure.SOURCE_SUPPORT_EXHAUSTED,
							"Directional liquid transport exceeds its positive raw source support: share=" + d
									+ ", demand=" + demand[d].toDouble() + ", missing=" + missing.toDouble() + ", allowedMicroMask=" + mask);
				}
				missing = missing.subtract(advanced);
			}
		}
		ExactRational[][] microWeights = new ExactRational[pieces.length][8];
		for (int d = 0; d < pieces.length; d++) microWeights[d] = zeros(8);
		for (int m = 0; m < 8; m++) if (supply[m].signum() > 0) {
			ExactRational retained = ExactRational.ONE;
			for (int d = 0; d < outgoing; d++) {
				microWeights[d][m] = divide(allocation[d][m], supply[m]);
				retained = retained.subtract(microWeights[d][m]);
			}
			if (retained.signum() < 0) throw new IllegalStateException("Transport overdrew a source marker");
			microWeights[outgoing][m] = retained;
		}
		ExactRational[][] weights = new ExactRational[pieces.length][ordinals.length];
		for (int d = 0; d < pieces.length; d++) for (int p = 0; p < ordinals.length; p++) weights[d][p] = microWeights[d][micro[p]];
		return moments(weights, microWeights);
	}

	private Result moments(ExactRational[][] weights, ExactRational[][] microWeights) {
		double[][] momentum = new double[weights.length][3]; double[] kinetic = new double[weights.length];
		double[][] volumeMoment = new double[weights.length][3], volumeMotion = new double[weights.length][3];
		double[] mass = new double[weights.length];
		ExactRational[][] mechanical = new ExactRational[weights.length][5];
		for (int d = 0; d < weights.length; d++) {
			// All eleven moments share these eight positive allocation weights. Form their
			// common integer ratio once, then apply it to the captured binary source moments.
			BigInteger denominator = BigInteger.ONE;
			for (var weight : microWeights[d]) if (!weight.isZero())
				denominator = denominator.divide(denominator.gcd(weight.denominator())).multiply(weight.denominator());
			var numerators = new BigInteger[8];
			for (int m = 0; m < 8; m++) numerators[m] = microWeights[d][m].isZero() ? BigInteger.ZERO
					: microWeights[d][m].numerator().multiply(denominator.divide(microWeights[d][m].denominator()));
			mass[d] = observe(mechanical[d][MASS] = weightedMoment(MASS, numerators, denominator));
			kinetic[d] = observe(mechanical[d][KINETIC] = weightedMoment(KINETIC, numerators, denominator));
			for (int a = 0; a < 3; a++) {
				momentum[d][a] = observe(mechanical[d][MOMENTUM + a] = weightedMoment(MOMENTUM + a, numerators, denominator));
				volumeMoment[d][a] = observe(weightedMoment(VOLUME_MOMENT + a, numerators, denominator));
				volumeMotion[d][a] = observe(weightedMoment(VOLUME_MOTION + a, numerators, denominator));
			}
		}
		return new Result(ordinals.clone(), weights, mass, momentum, kinetic, volumeMoment, volumeMotion, mechanical);
	}

	private ExactRational weightedMoment(int row, BigInteger[] weights, BigInteger denominator) {
		BigInteger numerator = BigInteger.ZERO;
		for (int m = 0; m < 8; m++) if (weights[m].signum() != 0 && sourceMoments[row][m].signum() != 0)
			numerator = numerator.add(sourceMoments[row][m].multiply(weights[m]));
		if (numerator.signum() == 0) return ExactRational.ZERO;
		denominator = denominator.shiftLeft(-momentExponents[row]);
		// Reduce before constructing the bounded rational: the common denominator can
		// contain factors canceled by this particular moment's complete numerator.
		BigInteger divisor = numerator.gcd(denominator);
		return new ExactRational(numerator.divide(divisor), denominator.divide(divisor));
	}

	private static ExactRational augment(int start, ExactRational missing, boolean[][] allowed,
			ExactRational[][] flow, ExactRational[] remaining) {
		int shares = allowed.length, count = shares + 8;
		int[] previous = new int[count], queue = new int[count]; Arrays.fill(previous, -1);
		int head = 0, tail = 0, terminal = -1; queue[tail++] = start; previous[start] = start;
		while (head < tail && terminal < 0) {
			int node = queue[head++];
			if (node < shares) {
				for (int m = 0; m < 8; m++) if (allowed[node][m] && previous[shares + m] < 0) {
					previous[shares + m] = node; queue[tail++] = shares + m;
					if (remaining[m].signum() > 0) { terminal = shares + m; break; }
				}
			} else for (int d = 0; d < shares; d++) if (flow[d][node - shares].signum() > 0 && previous[d] < 0) {
				previous[d] = node; queue[tail++] = d;
			}
		}
		if (terminal < 0) return ExactRational.ZERO;
		ExactRational amount = min(missing, remaining[terminal - shares]);
		for (int node = terminal; node != start; node = previous[node]) if (node < shares)
			amount = min(amount, flow[node][previous[node] - shares]);
		remaining[terminal - shares] = remaining[terminal - shares].subtract(amount);
		for (int node = terminal; node != start; node = previous[node]) {
			int parent = previous[node];
			if (node >= shares) flow[parent][node - shares] = flow[parent][node - shares].add(amount);
			else flow[node][parent - shares] = flow[node][parent - shares].subtract(amount);
		}
		return amount;
	}

	/** K is the unhalved raw mass-weighted velocity square, matching the remap budget. */
	static final class Result {
		private final int[] ordinals;
		private final ExactRational[][] markerWeights, mechanical;
		private final double[] mass, kinetic;
		private final double[][] momentum, volumeMoment, volumeMotion;
		private Result(int[] ordinals, ExactRational[][] markerWeights, double[] mass, double[][] momentum,
		               double[] kinetic, double[][] volumeMoment, double[][] volumeMotion, ExactRational[][] mechanical) {
			this.ordinals = ordinals; this.markerWeights = markerWeights; this.mass = mass; this.momentum = momentum;
			this.kinetic = kinetic; this.volumeMoment = volumeMoment; this.volumeMotion = volumeMotion; this.mechanical = mechanical;
		}
		int[] ordinals() { return ordinals; }
		ExactRational[][] markerWeights() { return markerWeights; }
		double[] mass() { return mass; }
		double[][] momentum() { return momentum; }
		double[] kinetic() { return kinetic; }
		double[][] volumeMoment() { return volumeMoment; }
		double[][] volumeMotion() { return volumeMotion; }
		/** Independent-reference diagnostics request exact observation differences outside production remapping. */
		ExactRational[] massRounding() { return rounding(mass, MASS); }
		ExactRational[] kineticRounding() { return rounding(kinetic, KINETIC); }
		private ExactRational[] rounding(double[] observed, int column) {
			var result = new ExactRational[observed.length];
			for (int d = 0; d < result.length; d++) result[d] = rational(observed[d]).subtract(mechanical[d][column]);
			return result;
		}
		ExactRational[][] momentumRounding() {
			var result = new ExactRational[momentum.length][3];
			for (int d = 0; d < result.length; d++) for (int a = 0; a < 3; a++)
				result[d][a] = rational(momentum[d][a]).subtract(mechanical[d][MOMENTUM + a]);
			return result;
		}
	}

	enum Failure { SOURCE_SUPPORT_EXHAUSTED, UNREPRESENTABLE_MOMENTS }
	static final class AllocationFailure extends IllegalStateException {
		final Failure failure;
		AllocationFailure(Failure failure, String message) { super(message); this.failure = failure; }
	}
	private static double observe(ExactRational value) {
		double observed = value.toDouble();
		if (!Double.isFinite(observed) || observed == 0 && !value.isZero())
			throw new AllocationFailure(Failure.UNREPRESENTABLE_MOMENTS, "Liquid transport moment is unrepresentable in binary64");
		return observed;
	}
	private static ExactRational[] zeros(int count) {
		ExactRational[] result = new ExactRational[count]; Arrays.fill(result, ExactRational.ZERO); return result;
	}
	private static ExactRational sum(ExactRational[] values) {
		ExactRational result = ExactRational.ZERO; for (ExactRational value : values) result = result.add(value); return result;
	}
	private static ExactRational divide(ExactRational a, ExactRational b) {
		return new ExactRational(a.numerator().multiply(b.denominator()), a.denominator().multiply(b.numerator()));
	}
	private static ExactRational rational(double value) { return rational(SignedDyadic.of(value)); }
	private static ExactRational rational(SignedDyadic value) { return PlicComponentGeometry.ratio(value, SignedDyadic.of(1)); }
	private static ExactRational min(ExactRational a, ExactRational b) { return a.compareTo(b) <= 0 ? a : b; }
}

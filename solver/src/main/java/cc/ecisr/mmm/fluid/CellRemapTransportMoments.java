package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.chemistry.ExactRational;
import cc.ecisr.mmm.units.SignedDyadic;
import java.util.Arrays;
import java.util.List;

/** Allocates transport moments from one positive, supply-limited source integration rule. */
final class CellRemapTransportMoments {
	private final int[] ordinals, micro;
	private final ExactRational[] volumes, masses;
	private final ExactRational[][] velocities;
	private final ExactRational[][] positions;
	private final ExactRational[] supply = zeros(8);
	private final ExactRational volume;

	CellRemapTransportMoments(FluidDomain domain, int cell, List<Integer> original) {
		ordinals = original.stream().mapToInt(Integer::intValue).toArray();
		micro = new int[ordinals.length]; volumes = new ExactRational[ordinals.length];
		masses = new ExactRational[ordinals.length]; velocities = new ExactRational[ordinals.length][3];
		positions = new ExactRational[ordinals.length][3];
		ExactRational total = ExactRational.ZERO;
		for (int i = 0; i < ordinals.length; i++) {
			int p = ordinals[i];
			if (domain.particleCellIndex(p) != cell) throw new IllegalArgumentException("Transport source belongs to another cell");
			micro[i] = CellRemapMicroTopology.positionMicro(domain.config.cellSize(), domain.particles.positionX(p),
					domain.particles.positionY(p), domain.particles.positionZ(p));
			volumes[i] = ExactRational.ofFloat(domain.particles.volume(p));
			masses[i] = rational(domain.particles.mass(p));
			velocities[i][0] = rational(domain.particles.velocityX(p));
			velocities[i][1] = rational(domain.particles.velocityY(p));
			velocities[i][2] = rational(domain.particles.velocityZ(p));
			double h = domain.config.cellSize();
			positions[i][0] = rational(domain.particles.positionX(p) / h - cell % domain.sizeX - .5);
			positions[i][1] = rational(domain.particles.positionY(p) / h - cell / domain.sizeX % domain.sizeY - .5);
			positions[i][2] = rational(domain.particles.positionZ(p) / h - cell / (domain.sizeX * domain.sizeY) - .5);
			if (volumes[i].signum() <= 0 || masses[i].signum() <= 0)
				throw new IllegalArgumentException("Transport requires positive raw source volume and mass");
			supply[micro[i]] = supply[micro[i]].add(volumes[i]); total = total.add(volumes[i]);
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
				if (advanced.isZero()) throw new AllocationFailure(Failure.SOURCE_SUPPORT_EXHAUSTED,
						"Directional liquid transport exceeds its positive raw source support");
				missing = missing.subtract(advanced);
			}
		}
		ExactRational[][] weights = new ExactRational[pieces.length][ordinals.length];
		for (int p = 0; p < ordinals.length; p++) {
			ExactRational retained = ExactRational.ONE;
			for (int d = 0; d < outgoing; d++) {
				weights[d][p] = divide(allocation[d][micro[p]], supply[micro[p]]);
				retained = retained.subtract(weights[d][p]);
			}
			if (retained.signum() < 0) throw new IllegalStateException("Transport overdrew a source marker");
			weights[outgoing][p] = retained;
		}
		return moments(weights);
	}

	private Result moments(ExactRational[][] weights) {
		double[][] momentum = new double[weights.length][3]; double[] kinetic = new double[weights.length];
		double[][] volumeMoment = new double[weights.length][3], volumeMotion = new double[weights.length][3];
		double[] mass = new double[weights.length]; ExactRational[] massRounding = new ExactRational[weights.length];
		ExactRational[][] momentumRounding = new ExactRational[weights.length][3];
		ExactRational[] kineticRounding = new ExactRational[weights.length];
		for (int d = 0; d < weights.length; d++) {
			ExactRational exactMass = ExactRational.ZERO, k = ExactRational.ZERO;
			ExactRational[] p = zeros(3);
			ExactRational[] x = zeros(3), motion = zeros(3);
			for (int i = 0; i < ordinals.length; i++) {
				ExactRational weightedMass = masses[i].multiply(weights[d][i]); exactMass = exactMass.add(weightedMass);
				ExactRational weightedVolume = volumes[i].multiply(weights[d][i]);
				for (int a = 0; a < 3; a++) {
					x[a] = x[a].add(weightedVolume.multiply(positions[i][a]));
					motion[a] = motion[a].add(weightedVolume.multiply(velocities[i][a]));
					p[a] = p[a].add(weightedMass.multiply(velocities[i][a]));
					k = k.add(weightedMass.multiply(velocities[i][a].multiply(velocities[i][a])));
				}
			}
			mass[d] = observe(exactMass); massRounding[d] = rational(mass[d]).subtract(exactMass);
			kinetic[d] = observe(k); kineticRounding[d] = rational(kinetic[d]).subtract(k);
			for (int a = 0; a < 3; a++) {
				momentum[d][a] = observe(p[a]); momentumRounding[d][a] = rational(momentum[d][a]).subtract(p[a]);
				volumeMoment[d][a] = observe(x[a]); volumeMotion[d][a] = observe(motion[a]);
			}
		}
		return new Result(ordinals.clone(), weights, mass, momentum, kinetic, volumeMoment, volumeMotion,
				massRounding, momentumRounding, kineticRounding);
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
	record Result(int[] ordinals, ExactRational[][] markerWeights, double[] mass, double[][] momentum, double[] kinetic,
			double[][] volumeMoment, double[][] volumeMotion,
			ExactRational[] massRounding, ExactRational[][] momentumRounding, ExactRational[] kineticRounding) { }
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

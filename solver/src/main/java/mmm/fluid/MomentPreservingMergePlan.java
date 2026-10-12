package mmm.fluid;

import mmm.chemistry.ChemicalParcelStore;
import java.util.Arrays;

/** Preflights cell-local quadrature replacement before mutating any carrier or side column. */
final class MomentPreservingMergePlan {
	final int[] targets;
	final float[][] positions;
	final float[][] velocities;
	int geometrySkipped;
	int conservationSkipped;

	MomentPreservingMergePlan(int[] particles, boolean[] representatives, int count,
			float[] x, float[] y, float[] z, float[] vx, float[] vy, float[] vz,
			float[] volumes, double[] masses, int[] materials, int[] phases,
			ChemicalParcelStore chemistry, FlipParticleReseeder.ThermalAdapter thermal, float h,
			FlipParticleReseeder.PositionValidator validator) {
		targets = new int[count]; Arrays.fill(targets, -1);
		positions = new float[3][count];
		velocities = new float[3][count]; float[][] originalVelocity = new float[3][count];
		double[][] original = new double[3][count], candidate = new double[3][count];
		double[] originalWeights = new double[count], weights = new double[count];
		int[] groups = new int[count]; int groupCount = 0;
		double[] temperatures = new double[count], densities = new double[count];
		double[] origin = {Math.floor(x[particles[0]] / h) * h,
				Math.floor(y[particles[0]] / h) * h, Math.floor(z[particles[0]] / h) * h};
		for (int local = 0; local < count; local++) {
			int p = particles[local];
			positions[0][local] = x[p]; positions[1][local] = y[p]; positions[2][local] = z[p];
			originalVelocity[0][local] = velocities[0][local] = vx[p];
			originalVelocity[1][local] = velocities[1][local] = vy[p];
			originalVelocity[2][local] = velocities[2][local] = vz[p];
			for (int a = 0; a < 3; a++) original[a][local] = candidate[a][local] = (positions[a][local] - origin[a]) / h;
			originalWeights[local] = weights[local] = volumes[p];
			temperatures[local] = thermal == null ? 0 : thermal.beforeSplit(p).temperatureK();
			densities[local] = masses[p] / volumes[p];
			int equivalent = -1;
			for (int other = 0; other < local; other++) if (temperatures[local] == temperatures[other]
					&& densities[local] == densities[other] && sameGroup(p, particles[other], volumes, materials, phases, chemistry)) { equivalent = other; break; }
			groups[local] = equivalent < 0 ? groupCount++ : groups[equivalent];
		}
		// A rank-r covariance needs at least r+1 positive-weight integration points.
		for (int group = 0; group < groupCount; group++) {
			int selected = 0, first = -1;
			for (int local = 0; local < count; local++) if (groups[local] == group) {
				if (first < 0) first = local;
				if (representatives[local]) selected++;
			}
			int minimum = affineRank(original, groups, group, count, first) + 1;
			while (selected < minimum) {
				int best = -1; double greatest = -1;
				for (int local = 0; local < count; local++) if (groups[local] == group && !representatives[local]) {
					double nearest = Double.POSITIVE_INFINITY;
					for (int other = 0; other < count; other++) if (groups[other] == group && representatives[other]) {
						double distance = 0;
						for (int a = 0; a < 3; a++) distance += Math.pow(original[a][local] - original[a][other], 2);
						nearest = Math.min(nearest, distance);
					}
					if (nearest > greatest) { greatest = nearest; best = local; }
				}
				representatives[best] = true; selected++; geometrySkipped++;
			}
		}
		for (int local = 0; local < count; local++) {
			if (representatives[local]) continue;
			int nearest = -1; double distance = Double.POSITIVE_INFINITY; boolean compatible = false;
			for (int other = 0; other < count; other++) {
				if (!representatives[other] || groups[local] != groups[other]) continue;
				compatible = true;
				if (!exactSum(weights[local], weights[other])) continue;
				double squared = 0;
				for (int a = 0; a < 3; a++) squared += Math.pow(candidate[a][local] - candidate[a][other], 2);
				if (squared < distance) { distance = squared; nearest = other; }
			}
			if (nearest < 0) { if (compatible) conservationSkipped++; else geometrySkipped++; continue; }
			double sum = weights[local] + weights[nearest]; double[] center = new double[3];
			for (int a = 0; a < 3; a++) center[a] = (candidate[a][local] * weights[local] + candidate[a][nearest] * weights[nearest]) / sum;
			if (!validator.allowed((float) (origin[0] + h * center[0]), (float) (origin[1] + h * center[1]), (float) (origin[2] + h * center[2]))) { geometrySkipped++; continue; }
			// Match the binary32 centroid writes used by the eventual inventory merge.
			for (int a = 0; a < 3; a++) candidate[a][nearest] = ((float) (origin[a] + h * center[a]) - origin[a]) / h;
			weights[nearest] = sum; weights[local] = 0; targets[local] = nearest;
		}
		for (int group = 0; group < groupCount; group++) {
			int merges = 0;
			for (int local = 0; local < count; local++) if (groups[local] == group && targets[local] >= 0) merges++;
			if (merges == 0) continue;
			double[] beforeWeights = new double[count], afterWeights = new double[count];
			for (int local = 0; local < count; local++) if (groups[local] == group) {
				beforeWeights[local] = originalWeights[local]; afterWeights[local] = weights[local];
			}
			boolean valid = QuadratureMomentFit.fit(original, beforeWeights, candidate, afterWeights, count);
			for (int local = 0; local < count && valid; local++) if (afterWeights[local] > 0) {
				float[] point = new float[3];
				for (int a = 0; a < 3; a++) {
					point[a] = (float) (origin[a] + h * candidate[a][local]);
					valid &= Float.isFinite(point[a]) && point[a] >= origin[a] && point[a] < origin[a] + h;
				}
				valid &= validator.allowed(point[0], point[1], point[2]);
			}
			if (!valid) {
				geometrySkipped += merges;
				for (int local = 0; local < count; local++) if (groups[local] == group) targets[local] = -1;
				continue;
			}
			for (int local = 0; local < count; local++) if (afterWeights[local] > 0) for (int a = 0; a < 3; a++) {
				positions[a][local] = (float) (origin[a] + h * candidate[a][local]);
				candidate[a][local] = (positions[a][local] - origin[a]) / h;
			}
			QuadratureMomentFit.fitVelocity(original, beforeWeights, originalVelocity, candidate, afterWeights, count, velocities);
			double beforeEnergy = 0, afterEnergy = 0, velocityRoundoff = 0;
			for (int local = 0; local < count; local++) for (int a = 0; a < 3; a++) {
				double beforeValue = originalVelocity[a][local], afterValue = velocities[a][local];
				beforeEnergy += beforeWeights[local] * beforeValue * beforeValue;
				afterEnergy += afterWeights[local] * afterValue * afterValue;
				double ulp = Math.ulp(velocities[a][local]);
				velocityRoundoff += afterWeights[local] * (Math.abs(afterValue) * ulp + 0.25 * ulp * ulp);
			}
			if (!Double.isFinite(afterEnergy) || afterEnergy > beforeEnergy + velocityRoundoff + 32 * count * Math.ulp(beforeEnergy)) {
				geometrySkipped += merges;
				for (int local = 0; local < count; local++) if (groups[local] == group) {
					targets[local] = -1;
					for (int a = 0; a < 3; a++) {
						positions[a][local] = a == 0 ? x[particles[local]] : a == 1 ? y[particles[local]] : z[particles[local]];
						velocities[a][local] = originalVelocity[a][local];
					}
				}
			}
		}
	}

	private static boolean sameGroup(int first, int second, float[] volumes, int[] material, int[] phase, ChemicalParcelStore chemistry) {
		if (material[first] != material[second] || phase[first] != phase[second]) return false;
		if (chemistry == null) return true;
		if (chemistry.indicator(first) != chemistry.indicator(second)) return false;
		var a = chemistry.record(first); var b = chemistry.record(second);
		if (a.entryCount() != b.entryCount()) return false;
		for (int entry = 0; entry < a.entryCount(); entry++) if (a.speciesHandle(entry) != b.speciesHandle(entry)
				|| !sameConcentration(a.amountAt(entry), volumes[first], b.amountAt(entry), volumes[second])) return false;
		return true;
	}

	/** Exact comparison of integer quanta divided by binary32 volume, without overflowing a cross product. */
	private static boolean sameConcentration(long a, float va, long b, float vb) {
		int ba = Float.floatToRawIntBits(va), bb = Float.floatToRawIntBits(vb);
		int ea = ba >>> 23 & 255, eb = bb >>> 23 & 255;
		int ma = (ba & 0x7fffff) | (ea == 0 ? 0 : 0x800000), mb = (bb & 0x7fffff) | (eb == 0 ? 0 : 0x800000);
		int ta = Long.numberOfTrailingZeros(a), tb = Long.numberOfTrailingZeros(b);
		int tma = Integer.numberOfTrailingZeros(ma), tmb = Integer.numberOfTrailingZeros(mb);
		if (ta + tmb + Math.max(1, eb) != tb + tma + Math.max(1, ea)) return false;
		long oa = a >> ta, ob = b >> tb, oma = ma >> tma, omb = mb >> tmb;
		long amounts = gcd(oa, ob), mantissas = gcd(oma, omb);
		return oa / amounts == oma / mantissas && ob / amounts == omb / mantissas;
	}

	private static long gcd(long a, long b) {
		while (b != 0) { long remainder = a % b; a = b; b = remainder; }
		return a;
	}

	private static int affineRank(double[][] positions, int[] groups, int group, int count, int first) {
		double[][] basis = new double[3][3]; int rank = 0; double radiusSquared = 0;
		for (int p = 0; p < count; p++) if (groups[p] == group) {
			double distance = 0;
			for (int a = 0; a < 3; a++) distance += Math.pow(positions[a][p] - positions[a][first], 2);
			radiusSquared = Math.max(radiusSquared, distance);
		}
		for (int p = 0; p < count && rank < 3; p++) if (groups[p] == group) {
			double[] offset = new double[3];
			for (int a = 0; a < 3; a++) offset[a] = positions[a][p] - positions[a][first];
			for (int pass = 0; pass < 2; pass++) for (int b = 0; b < rank; b++) {
				double dot = 0;
				for (int a = 0; a < 3; a++) dot += basis[b][a] * offset[a];
				for (int a = 0; a < 3; a++) offset[a] -= dot * basis[b][a];
			}
			double squared = 0; for (double value : offset) squared += value * value;
			if (!(squared > radiusSquared * 1e-12)) continue;
			for (int a = 0; a < 3; a++) basis[rank][a] = offset[a] / Math.sqrt(squared);
			rank++;
		}
		return rank;
	}

	private static boolean exactSum(double first, double second) {
		double sum = first + second;
		return (double) (float) sum == sum && sum - first == second && sum - second == first;
	}
}

package mmm.fluid;

import mmm.chemistry.ChemicalLimits;
import mmm.chemistry.ExactRational;
import mmm.units.SignedDyadic;
import java.math.BigInteger;
import java.util.*;

/** Certified pressure coordinates in the actual velocity range, retaining every physical row. */
final class PressureRowBasis {
	private static final long PRIME = 2147483647;
	private static final ThreadLocal<Long> validationWorkLimit = new ThreadLocal<>();
	private static final class WorkBudget {
		private long remaining;
		WorkBudget(long allowance) {
			Long limited = validationWorkLimit.get(); remaining = limited == null ? allowance : Math.min(allowance, limited);
		}
		void charge(long cost) {
			if (cost > remaining) throw new Unavailable("Pressure dependence certificate exceeds exact work budget");
			remaining -= cost;
		}
		void charge(BigInteger first, BigInteger second) { charge(limbs(first) * limbs(second)); }
		private static long limbs(BigInteger value) { return Math.max(1, (value.abs().bitLength() + 31L) / 32); }
	}
	static void withWorkLimitForValidation(long limit, Runnable action) {
		if (limit < 0) throw new IllegalArgumentException("Pressure validation work limit is negative");
		Long previous = validationWorkLimit.get(); validationWorkLimit.set(limit);
		try { action.run(); } finally { if (previous == null) validationWorkLimit.remove(); else validationWorkLimit.set(previous); }
	}
	private static long inputWork(int rows, SignedDyadic[] coefficients, SignedDyadic[][] inertia, int stride) {
		long units = rows + (long) stride * inertia.length;
		for (var value : coefficients) units += inputWords(value);
		for (var gram : inertia) for (var value : gram) units += inputWords(value);
		// One 2048-bit word-pair operation per input row, nonzero and initial rational limb.
		return Math.multiplyExact(64L * 64, units);
	}
	private static long inputWords(SignedDyadic value) {
		if (value.signum() == 0) return 0;
		long numeratorBits = value.significand().abs().bitLength() + Math.max(0L, value.exponent());
		long denominatorBits = 1 + Math.max(0L, -(long) value.exponent());
		return 1 + (numeratorBits + 31) / 32 + (denominatorBits + 31) / 32;
	}
	static final class Unavailable extends RuntimeException { Unavailable(String message) { super(message); } }
	private record Pivot(TreeMap<Integer, ExactRational> values, TreeMap<Integer, ExactRational> relation) { }
	private record ModularPivot(int row, int column) { }
	private record Dependency(TreeMap<Integer, ExactRational> relation) { }
	private final int[] rows, positions;
	private final List<Dependency> dependencies;
	private final WorkBudget work;

	PressureRowBasis(int[] starts, int[] indices, SignedDyadic[] coefficients, SignedDyadic[][] inertia, int stride, double[] storage, double[] diagonal) {
		if (stride <= 0) throw new IllegalArgumentException("Invalid pressure factor stride");
		work = new WorkBudget(inputWork(storage.length, coefficients, inertia, stride));
		int[][] support = new int[inertia.length][]; int[] modes = new int[inertia.length];
		for (int f = 0; f < inertia.length; f++) {
			modes[f] = (int) Math.sqrt(inertia[f].length);
			if (modes[f] > stride || modes[f] * modes[f] != inertia[f].length)
				throw new IllegalArgumentException("Invalid pressure factor Gram shape");
			support[f] = independentColumns(inertia[f], modes[f]);
		}
		var features = new ArrayList<TreeMap<Integer, SignedDyadic>>(storage.length);
		long featureBytes = 0, available = availableMemory();
		for (int row = 0; row < storage.length; row++) {
			var feature = new TreeMap<Integer, SignedDyadic>(); int e = starts[row];
			while (e < starts[row + 1]) {
				int factor = indices[e] / stride; int first = e;
				while (e < starts[row + 1] && indices[e] / stride == factor) e++;
				if (support[factor].length == modes[factor]) {
					for (int j = first; j < e; j++) if (coefficients[j].signum() != 0) feature.put(indices[j], coefficients[j]);
				}
				else for (int column : support[factor]) {
					SignedDyadic value = SignedDyadic.ZERO;
					for (int j = first; j < e; j++) {
						work.charge(coefficients[j].significand(), inertia[factor][modes[factor] * (indices[j] % stride) + column].significand());
						value = value.add(coefficients[j].multiply(inertia[factor][modes[factor] * (indices[j] % stride) + column]));
					}
					if (value.signum() != 0) feature.put(stride * factor + column, value);
				}
			}
			if (storage[row] > 0) feature.put(Math.addExact(Math.multiplyExact(stride, inertia.length), row), SignedDyadic.of(1));
			features.add(feature); for (var value : feature.values()) featureBytes += 256L + 2L * ((value.significand().bitLength() + 7L) / 8);
			checkMemory(featureBytes, available);
		}
		var active = new ArrayList<Integer>(); var dependent = new ArrayList<Dependency>();
		for (int row = 0; row < features.size(); row++) {
			if (features.get(row).isEmpty()) {
				var relation = new TreeMap<Integer, ExactRational>(); relation.put(row, ExactRational.ONE);
				dependent.add(new Dependency(relation));
			} else active.add(row);
		}
		Integer[] order = active.toArray(Integer[]::new);
		// Strong physical rows keep dependent coordinates from amplifying factorization rounding.
		Arrays.sort(order, Comparator.<Integer>comparingDouble(row -> diagonal[row]).reversed()
				.thenComparingInt(row -> features.get(row).size()));
		var plan = modularPivots(features, order);
		var selected = new ArrayList<Integer>(); var independent = new HashSet<Integer>();
		for (var pivot : plan) { selected.add(pivot.row); independent.add(pivot.row); }
		if (plan.size() < order.length) {
			var replay = new PivotReplay(features, plan, featureBytes, available);
			for (int row : order) if (!independent.contains(row)) {
				Pivot candidate = replay.capture(row);
				for (int prior = 0; prior < plan.size(); prior++) {
					ExactRational scale = candidate.values.get(plan.get(prior).column);
					if (scale != null) replay.reduce(candidate, replay.get(prior), scale);
				}
				// Full feature columns remain present throughout replay, including columns outside the minor.
				if (candidate.values.isEmpty()) dependent.add(new Dependency(candidate.relation));
				else {
					// A prime can hide an independent exact row; its remaining column extends the same minor.
					replay.append(row, candidate); selected.add(row);
				}
			}
		}
		rows = selected.stream().mapToInt(Integer::intValue).sorted().toArray(); positions = new int[storage.length]; Arrays.fill(positions, -1);
		for (int i = 0; i < rows.length; i++) positions[rows[i]] = i;
		dependencies = List.copyOf(dependent);
	}
	/** Replays only the exact pivots reached by an unselected row; the modular minor fixes their order. */
	private final class PivotReplay {
		private final List<TreeMap<Integer, SignedDyadic>> features;
		private final List<ModularPivot> plan;
		private final ArrayList<Pivot> pivots;
		private final int[] nextPrior;
		private final long available;
		private long liveBytes;

		PivotReplay(List<TreeMap<Integer, SignedDyadic>> features, List<ModularPivot> plan, long bytes, long available) {
			this.features = features; this.plan = plan; this.liveBytes = bytes; this.available = available;
			pivots = new ArrayList<>(Collections.nCopies(plan.size(), null)); nextPrior = new int[features.size()];
		}
		Pivot capture(int row) {
			var values = new TreeMap<Integer, ExactRational>();
			features.get(row).forEach((column, value) -> values.put(column, rational(value)));
			var relation = new TreeMap<Integer, ExactRational>(); relation.put(row, ExactRational.ONE);
			var pivot = new Pivot(values, relation); account(pivotBytes(pivot)); return pivot;
		}
		Pivot get(int index) {
			if (nextPrior[index] == index + 1) return pivots.get(index);
			int[] pending = new int[index + 1]; int depth = 1; pending[0] = index;
			while (depth > 0) {
				int current = pending[depth - 1]; Pivot pivot = pivots.get(current);
				if (pivot == null) { pivot = capture(plan.get(current).row); pivots.set(current, pivot); }
				int prior = nextPrior[current];
				if (prior < current) {
					ExactRational scale = pivot.values.get(plan.get(prior).column);
					if (scale == null) { nextPrior[current]++; continue; }
					if (nextPrior[prior] != prior + 1) { pending[depth++] = prior; continue; }
					reduce(pivot, pivots.get(prior), scale); nextPrior[current]++; continue;
				}
				normalize(pivot, plan.get(current).column); nextPrior[current] = current + 1; depth--;
			}
			return pivots.get(index);
		}
		void reduce(Pivot target, Pivot source, ExactRational scale) {
			long before = pivotBytes(target);
			subtract(target.values, source.values, scale); subtract(target.relation, source.relation, scale);
			account(pivotBytes(target) - before);
		}
		void append(int row, Pivot pivot) {
			int column = pivot.values.firstKey(); normalize(pivot, column);
			nextPrior[plan.size()] = plan.size() + 1; plan.add(new ModularPivot(row, column)); pivots.add(pivot);
		}
		private void normalize(Pivot pivot, int column) {
			ExactRational value = pivot.values.get(column);
			if (value == null || value.isZero()) throw new IllegalStateException("Modular pressure minor lost its exact pivot");
			long before = pivotBytes(pivot); ExactRational inverse = divide(ExactRational.ONE, value);
			pivot.values.replaceAll((key, coefficient) -> multiply(coefficient, inverse));
			pivot.relation.replaceAll((key, coefficient) -> multiply(coefficient, inverse));
			account(pivotBytes(pivot) - before);
		}
		private void account(long delta) { liveBytes += delta; checkMemory(liveBytes, available); }
	}
	private static long pivotBytes(Pivot pivot) { return bytes(pivot.values) + bytes(pivot.relation); }

	int size() { return rows.length; }
	int row(int position) { return rows[position]; }
	int position(int row) { return positions[row]; }
	int dependencyCount() { return dependencies.size(); }
	boolean observationNeutral(boolean[] observed, double[] diagonal, boolean commonGauge) {
		for (var dependency : dependencies) {
			boolean changesObservation = false;
			for (int row : dependency.relation.keySet()) changesObservation |= observed[row] && diagonal[row] > 0;
			if (!changesObservation) continue;
			if (!commonGauge) return false;
			ExactRational shift = null;
			for (int row = 0; row < observed.length; row++) if (observed[row]) {
				var coefficient = dependency.relation.getOrDefault(row, ExactRational.ZERO);
				if (shift == null) shift = coefficient;
				else if (!shift.equals(coefficient)) return false;
			}
		}
		return true;
	}
	/** Only volumeRate's final rounding and the builder's division can disturb an exact row relation. */
	boolean compatibleSource(double[] rhs, double[] rate, float dt) {
		ExactRational time = rational(SignedDyadic.of(dt));
		for (var dependency : dependencies) {
			ExactRational defect = ExactRational.ZERO, bound = ExactRational.ZERO;
			for (var entry : dependency.relation.entrySet()) {
				int row = entry.getKey(); if (rhs[row] != -rate[row] / dt) return false;
				ExactRational coefficient = entry.getValue(); defect = add(defect, multiply(coefficient, rational(SignedDyadic.of(rhs[row]))));
				ExactRational half = rational(BigInteger.ONE, BigInteger.TWO);
				ExactRational rounding = add(multiply(rational(SignedDyadic.of(Math.ulp(rhs[row]))), half),
						divide(multiply(rational(SignedDyadic.of(Math.ulp(rate[row]))), half), time));
				bound = add(bound, multiply(absolute(coefficient), rounding));
			}
			ExactRational magnitude = absolute(defect);
			work.charge(magnitude.numerator(), bound.denominator()); work.charge(bound.numerator(), magnitude.denominator());
			if (magnitude.compareTo(bound) > 0) return false;
		}
		return true;
	}
	private void subtract(TreeMap<Integer, ExactRational> target, TreeMap<Integer, ExactRational> source, ExactRational scale) {
		for (var entry : source.entrySet()) {
			work.charge(1);
			ExactRational value = add(target.getOrDefault(entry.getKey(), ExactRational.ZERO), negate(multiply(scale, entry.getValue())));
			if (value.isZero()) target.remove(entry.getKey()); else target.put(entry.getKey(), value);
		}
	}
	private int[] independentColumns(SignedDyadic[] gram, int modes) {
		var candidates = new ArrayList<TreeMap<Integer, SignedDyadic>>(); var order = new Integer[modes];
		for (int column = 0; column < modes; column++) {
			order[column] = column; var values = new TreeMap<Integer, SignedDyadic>();
			for (int row = 0; row < modes; row++) if (gram[modes * row + column].signum() != 0)
				values.put(row, gram[modes * row + column]);
			candidates.add(values);
		}
		if (modularRank(candidates, order) == modes) return java.util.stream.IntStream.range(0, modes).toArray();
		var pivots = new TreeMap<Integer, TreeMap<Integer, ExactRational>>(); var selected = new ArrayList<Integer>();
		for (int column = 0; column < modes; column++) {
			var values = new TreeMap<Integer, ExactRational>(); candidates.get(column).forEach((row, value) -> values.put(row, rational(value)));
			while (!values.isEmpty()) {
				int row = values.firstKey(); var pivot = pivots.get(row);
				if (pivot == null) { ExactRational inverse = divide(ExactRational.ONE, values.get(row)); values.replaceAll((key, value) -> multiply(value, inverse)); pivots.put(row, values); selected.add(column); break; }
				subtract(values, pivot, values.get(row));
			}
		}
		return selected.stream().mapToInt(Integer::intValue).toArray();
	}
	private int modularRank(List<TreeMap<Integer, SignedDyadic>> features, Integer[] order) { return modularPivots(features, order).size(); }
	private ArrayList<ModularPivot> modularPivots(List<TreeMap<Integer, SignedDyadic>> features, Integer[] order) {
		var selected = new ArrayList<ModularPivot>();
		var pivots = new HashMap<Integer, TreeMap<Integer, Long>>(); long entries = 0, available = availableMemory();
		for (int row : order) {
			var current = new TreeMap<Integer, Long>(); features.get(row).forEach((column, value) -> { long residue = modular(value); if (residue != 0) current.put(column, residue); });
			while (!current.isEmpty()) {
				int column = current.firstKey(); var pivot = pivots.get(column);
				checkMemory(256L * (entries + current.size()), available);
				if (pivot == null) { long inverse = power(current.get(column), PRIME - 2); current.replaceAll((key, value) -> value * inverse % PRIME); pivots.put(column, current); selected.add(new ModularPivot(row, column)); entries += current.size(); break; }
				long scale = current.get(column);
				for (var entry : pivot.entrySet()) { work.charge(1); long value = Math.floorMod(current.getOrDefault(entry.getKey(), 0L) - scale * entry.getValue(), PRIME); if (value == 0) current.remove(entry.getKey()); else current.put(entry.getKey(), value); }
			}
		}
		return selected;
	}
	private long modular(SignedDyadic value) { work.charge(value.significand(), BigInteger.valueOf(PRIME)); return value.significand().mod(BigInteger.valueOf(PRIME)).longValue() * power(value.exponent() >= 0 ? 2 : (PRIME + 1) / 2, Math.abs((long) value.exponent())) % PRIME; }
	private static long power(long base, long exponent) { long result = 1; for (; exponent > 0; exponent >>>= 1, base = base * base % PRIME) if ((exponent & 1) != 0) result = result * base % PRIME; return result; }
	private ExactRational rational(SignedDyadic value) { return value.exponent() >= 0 ? rational(value.significand().shiftLeft(value.exponent()), BigInteger.ONE) : rational(value.significand(), BigInteger.ONE.shiftLeft(-value.exponent())); }
	private ExactRational rational(BigInteger numerator, BigInteger denominator) {
		if (numerator.signum() == 0) return ExactRational.ZERO;
		work.charge(numerator, denominator); BigInteger gcd = numerator.gcd(denominator);
		work.charge(numerator, gcd); numerator = numerator.divide(gcd);
		work.charge(denominator, gcd); denominator = denominator.divide(gcd);
		if (numerator.bitLength() > ChemicalLimits.MAX_RATIONAL_BITS || denominator.bitLength() > ChemicalLimits.MAX_RATIONAL_BITS) throw new Unavailable("Pressure dependence certificate exceeds exact rational bit budget");
		work.charge(numerator, denominator); return new ExactRational(numerator, denominator);
	}
	private ExactRational add(ExactRational a, ExactRational b) {
		work.charge(a.denominator(), b.denominator()); BigInteger gcd = a.denominator().gcd(b.denominator());
		work.charge(b.denominator(), gcd); BigInteger first = b.denominator().divide(gcd);
		work.charge(a.denominator(), gcd); BigInteger second = a.denominator().divide(gcd);
		work.charge(a.numerator(), first); work.charge(b.numerator(), second); work.charge(a.denominator(), first);
		return rational(a.numerator().multiply(first).add(b.numerator().multiply(second)), a.denominator().multiply(first));
	}
	private ExactRational multiply(ExactRational a, ExactRational b) {
		work.charge(a.numerator(), b.denominator()); BigInteger first = a.numerator().gcd(b.denominator());
		work.charge(b.numerator(), a.denominator()); BigInteger second = b.numerator().gcd(a.denominator());
		work.charge(a.numerator(), first); work.charge(b.numerator(), second); work.charge(a.denominator(), second); work.charge(b.denominator(), first);
		var an = a.numerator().divide(first); var bn = b.numerator().divide(second);
		var ad = a.denominator().divide(second); var bd = b.denominator().divide(first);
		work.charge(an, bn); work.charge(ad, bd); return rational(an.multiply(bn), ad.multiply(bd));
	}
	private ExactRational divide(ExactRational a, ExactRational b) { return multiply(a, rational(b.denominator(), b.numerator())); }
	private ExactRational negate(ExactRational value) { return rational(value.numerator().negate(), value.denominator()); }
	private ExactRational absolute(ExactRational value) { return value.signum() < 0 ? rational(value.numerator().negate(), value.denominator()) : value; }
	private static long bytes(TreeMap<Integer, ExactRational> values) {
		long result = 0; for (var value : values.values()) result += 256L + 2L * ((value.numerator().bitLength() + value.denominator().bitLength() + 15L) / 8); return result;
	}
	static long availableMemory() { return Runtime.getRuntime().maxMemory() - Runtime.getRuntime().totalMemory() + Runtime.getRuntime().freeMemory(); }
	private static void checkMemory(long bytes, long available) { if (bytes > available / 2) throw new Unavailable("Pressure dependence certificate exceeds available memory"); }
}

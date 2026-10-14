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
	private static long inputWork(int rows, SignedDyadic[] coefficients, SignedDyadic[][] inertia) {
		long units = rows + 4L * inertia.length;
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
	private record Dependency(TreeMap<Integer, ExactRational> relation) { }
	private final int[] rows, positions;
	private final List<Dependency> dependencies;
	private final WorkBudget work;

	PressureRowBasis(int[] starts, int[] indices, SignedDyadic[] coefficients, SignedDyadic[][] inertia, double[] storage, double[] diagonal) {
		work = new WorkBudget(inputWork(storage.length, coefficients, inertia));
		int[][] support = new int[inertia.length][];
		for (int f = 0; f < inertia.length; f++) support[f] = independentColumns(inertia[f]);
		var features = new ArrayList<TreeMap<Integer, SignedDyadic>>(storage.length);
		long featureBytes = 0, available = availableMemory();
		for (int row = 0; row < storage.length; row++) {
			var feature = new TreeMap<Integer, SignedDyadic>(); int e = starts[row];
			while (e < starts[row + 1]) {
				int factor = indices[e] / 4; int first = e;
				while (e < starts[row + 1] && indices[e] / 4 == factor) e++;
				if (support[factor].length == 4) for (int j = first; j < e; j++) feature.put(indices[j], coefficients[j]);
				else for (int column : support[factor]) {
					SignedDyadic value = SignedDyadic.ZERO;
					for (int j = first; j < e; j++) {
						work.charge(coefficients[j].significand(), inertia[factor][4 * (indices[j] % 4) + column].significand());
						value = value.add(coefficients[j].multiply(inertia[factor][4 * (indices[j] % 4) + column]));
					}
					if (value.signum() != 0) feature.put(4 * factor + column, value);
				}
			}
			if (storage[row] > 0) feature.put(Math.addExact(Math.multiplyExact(4, inertia.length), row), SignedDyadic.of(1));
			features.add(feature); for (var value : feature.values()) featureBytes += 256L + 2L * ((value.significand().bitLength() + 7L) / 8);
			checkMemory(featureBytes, available);
		}
		Integer[] order = new Integer[storage.length]; for (int row = 0; row < order.length; row++) order[row] = row;
		// Strong physical rows keep dependent coordinates from amplifying factorization rounding.
		Arrays.sort(order, Comparator.<Integer>comparingDouble(row -> diagonal[row]).reversed()
				.thenComparingInt(row -> features.get(row).size()));
		int modularRank = modularRank(features, order);
		var selected = new ArrayList<Integer>(); var dependent = new ArrayList<Dependency>();
		if (modularRank == storage.length) for (int row = 0; row < storage.length; row++) selected.add(row);
		else {
			var pivots = new TreeMap<Integer, Pivot>(); long liveBytes = featureBytes;
			for (int row : order) {
				var current = new TreeMap<Integer, ExactRational>(); features.get(row).forEach((column, value) -> current.put(column, rational(value)));
				var relation = new TreeMap<Integer, ExactRational>(); relation.put(row, ExactRational.ONE);
				checkMemory(liveBytes + bytes(current) + bytes(relation), available);
				while (!current.isEmpty()) {
					int column = current.firstKey(); Pivot pivot = pivots.get(column);
					if (pivot == null) {
						ExactRational inverse = divide(ExactRational.ONE, current.get(column));
						current.replaceAll((key, value) -> multiply(value, inverse)); relation.replaceAll((key, value) -> multiply(value, inverse));
						liveBytes += bytes(current) + bytes(relation); checkMemory(liveBytes, available);
						pivots.put(column, new Pivot(current, relation)); selected.add(row);
						break;
					}
					ExactRational scale = current.get(column); subtract(current, pivot.values, scale); subtract(relation, pivot.relation, scale);
					checkMemory(liveBytes + bytes(current) + bytes(relation), available);
				}
				if (current.isEmpty()) { liveBytes += bytes(relation); checkMemory(liveBytes, available); dependent.add(new Dependency(relation)); }
			}
		}
		rows = selected.stream().mapToInt(Integer::intValue).sorted().toArray(); positions = new int[storage.length]; Arrays.fill(positions, -1);
		for (int i = 0; i < rows.length; i++) positions[rows[i]] = i;
		dependencies = List.copyOf(dependent);
	}
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
	private int[] independentColumns(SignedDyadic[] gram) {
		var candidates = new ArrayList<TreeMap<Integer, SignedDyadic>>();
		for (int column = 0; column < 4; column++) { var values = new TreeMap<Integer, SignedDyadic>(); for (int row = 0; row < 4; row++) if (gram[4 * row + column].signum() != 0) values.put(row, gram[4 * row + column]); candidates.add(values); }
		if (modularRank(candidates, new Integer[]{0, 1, 2, 3}) == 4) return new int[]{0, 1, 2, 3};
		var pivots = new TreeMap<Integer, TreeMap<Integer, ExactRational>>(); var selected = new ArrayList<Integer>();
		for (int column = 0; column < 4; column++) {
			var values = new TreeMap<Integer, ExactRational>(); candidates.get(column).forEach((row, value) -> values.put(row, rational(value)));
			while (!values.isEmpty()) {
				int row = values.firstKey(); var pivot = pivots.get(row);
				if (pivot == null) { ExactRational inverse = divide(ExactRational.ONE, values.get(row)); values.replaceAll((key, value) -> multiply(value, inverse)); pivots.put(row, values); selected.add(column); break; }
				subtract(values, pivot, values.get(row));
			}
		}
		return selected.stream().mapToInt(Integer::intValue).toArray();
	}
	private int modularRank(List<TreeMap<Integer, SignedDyadic>> features, Integer[] order) {
		var pivots = new HashMap<Integer, TreeMap<Integer, Long>>(); long entries = 0, available = availableMemory();
		for (int row : order) {
			var current = new TreeMap<Integer, Long>(); features.get(row).forEach((column, value) -> { long residue = modular(value); if (residue != 0) current.put(column, residue); });
			while (!current.isEmpty()) {
				int column = current.firstKey(); var pivot = pivots.get(column);
				checkMemory(256L * (entries + current.size()), available);
				if (pivot == null) { long inverse = power(current.get(column), PRIME - 2); current.replaceAll((key, value) -> value * inverse % PRIME); pivots.put(column, current); entries += current.size(); break; }
				long scale = current.get(column);
				for (var entry : pivot.entrySet()) { work.charge(1); long value = Math.floorMod(current.getOrDefault(entry.getKey(), 0L) - scale * entry.getValue(), PRIME); if (value == 0) current.remove(entry.getKey()); else current.put(entry.getKey(), value); }
			}
		}
		return pivots.size();
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

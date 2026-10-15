package mmm.fluid;

import mmm.chemistry.ChemicalLimits;
import mmm.units.SignedDyadic;
import java.math.BigInteger;
import java.util.*;

/** Independent pressure coordinates in the actual velocity range, retaining every physical row. */
final class PressureRowBasis {
	private static final long PRIME = 2147483647;
	private static final ThreadLocal<Long> validationWorkLimit = new ThreadLocal<>();
	private static final class WorkBudget {
		private long remaining;
		WorkBudget(long allowance) {
			Long limited = validationWorkLimit.get(); remaining = limited == null ? allowance : Math.min(allowance, limited);
		}
		void charge(long cost) {
			if (cost > remaining) throw new Unavailable("Pressure row selection exceeds exact work budget");
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
		// One 2048-bit word-pair operation per input row, nonzero and initial integer limb.
		return Math.multiplyExact(64L * 64, units);
	}
	private static long inputWords(SignedDyadic value) {
		if (value.signum() == 0) return 0;
		long numeratorBits = value.significand().abs().bitLength() + Math.max(0L, value.exponent());
		long denominatorBits = 1 + Math.max(0L, -(long) value.exponent());
		return 1 + (numeratorBits + 31) / 32 + (denominatorBits + 31) / 32;
	}
	static final class Unavailable extends RuntimeException { Unavailable(String message) { super(message); } }
	private record ModularTerm(int pivot, long factor) { }
	private record ModularPivot(int row, TreeMap<Integer, Long> values, long inverse, List<ModularTerm> parents) { }
	private record ModularSelection(List<Integer> rows, Map<Integer, int[]> supports) { }
	private final int[] rows, positions;
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
		var active = new ArrayList<Integer>();
		for (int row = 0; row < features.size(); row++) if (!features.get(row).isEmpty()) active.add(row);
		Integer[] order = active.toArray(Integer[]::new);
		// Strong physical rows keep dependent coordinates from amplifying factorization rounding.
		Arrays.sort(order, Comparator.<Integer>comparingDouble(row -> diagonal[row]).reversed()
				.thenComparingInt(row -> features.get(row).size()));
		rows = selectIndependent(features, order, featureBytes, available).stream().mapToInt(Integer::intValue).sorted().toArray();
		positions = new int[storage.length]; Arrays.fill(positions, -1);
		for (int i = 0; i < rows.length; i++) positions[rows[i]] = i;
	}
	int size() { return rows.length; }
	int row(int position) { return rows[position]; }
	int position(int row) { return positions[row]; }
	int dependencyCount() { return positions.length - rows.length; }

	private List<Integer> selectIndependent(List<TreeMap<Integer, SignedDyadic>> features, Integer[] order,
	                                      long retainedBytes, long available) {
		var modular = modularSelection(features, order);
		var selected = new ArrayList<>(modular.rows); var independent = new HashSet<>(selected);
		for (int row : order) if (!independent.contains(row)) {
			int[] supportRows = modular.supports.get(row);
			var scope = new ArrayList<Integer>();
			for (int candidate : selected) if (Arrays.binarySearch(supportRows, candidate) >= 0) scope.add(candidate);
			boolean extra = extendsSpan(row, scope, features, retainedBytes, available);
			if (extra && scope.size() < selected.size()) extra = extendsSpan(row, selected, features, retainedBytes, available);
			if (extra) selected.add(row);
		}
		return selected;
	}
	/** Rebuild the complete local matrix: Bareiss updates every remaining row in one pivot order. */
	private boolean extendsSpan(int target, List<Integer> selected, List<TreeMap<Integer, SignedDyadic>> features,
	                            long retainedBytes, long available) {
		var matrix = new ArrayList<TreeMap<Integer, BigInteger>>(selected.size() + 1);
		long liveBytes = retainedBytes;
		for (int i = 0; i <= selected.size(); i++) {
			var values = integerRow(features.get(i == selected.size() ? target : selected.get(i)));
			matrix.add(values); liveBytes += bytes(values); checkMemory(liveBytes, available);
		}
		BigInteger previous = BigInteger.ONE;
		for (int rank = 0; rank < matrix.size(); rank++) {
			int column = Integer.MAX_VALUE, pivotRow = -1;
			for (int row = rank; row < matrix.size(); row++) if (!matrix.get(row).isEmpty()
					&& matrix.get(row).firstKey() < column) { column = matrix.get(row).firstKey(); pivotRow = row; }
			if (pivotRow < 0) return false;
			Collections.swap(matrix, rank, pivotRow);
			var pivot = matrix.get(rank); BigInteger value = pivot.get(column);
			for (int row = rank + 1; row < matrix.size(); row++) {
				var old = matrix.get(row); var next = new TreeMap<Integer, BigInteger>(); long nextBytes = 0;
				BigInteger factor = old.getOrDefault(column, BigInteger.ZERO);
				var columns = new TreeSet<>(old.tailMap(column, false).keySet());
				if (factor.signum() != 0) columns.addAll(pivot.tailMap(column, false).keySet());
				for (int entry : columns) {
					BigInteger first = old.getOrDefault(entry, BigInteger.ZERO), second = pivot.getOrDefault(entry, BigInteger.ZERO);
					work.charge(value, first); work.charge(factor, second);
					BigInteger numerator = value.multiply(first).subtract(factor.multiply(second));
					work.charge(numerator, previous);
					BigInteger reduced = numerator.divide(previous);
					if (reduced.abs().bitLength() > ChemicalLimits.MAX_RATIONAL_BITS)
						throw new Unavailable("Pressure row selection exceeds integer bit budget");
					if (reduced.signum() != 0) {
						next.put(entry, reduced); nextBytes += integerBytes(reduced);
					}
					checkMemory(liveBytes + nextBytes + 64L * columns.size() + integerBytes(numerator), available);
				}
				liveBytes += nextBytes - bytes(old); matrix.set(row, next);
			}
			previous = value;
		}
		return true;
	}
	private TreeMap<Integer, BigInteger> integerRow(TreeMap<Integer, SignedDyadic> source) {
		int exponent = Integer.MAX_VALUE;
		for (var value : source.values()) if (value.signum() != 0) exponent = Math.min(exponent, value.exponent());
		var row = new TreeMap<Integer, BigInteger>();
		for (var entry : source.entrySet()) {
			var value = entry.getValue(); if (value.signum() == 0) continue;
			long shift = (long) value.exponent() - exponent;
			if (shift + value.significand().abs().bitLength() > ChemicalLimits.MAX_RATIONAL_BITS)
				throw new Unavailable("Pressure row selection exceeds integer bit budget");
			work.charge(1 + (shift + 31) / 32);
			row.put(entry.getKey(), value.significand().shiftLeft((int) shift));
		}
		// Remove the input row's common integer scale before forming determinant minors.
		BigInteger content = BigInteger.ZERO;
		for (var value : row.values()) {
			work.charge(content, value); content = content.gcd(value);
			if (content.equals(BigInteger.ONE)) break;
		}
		if (content.compareTo(BigInteger.ONE) > 0) for (var entry : row.entrySet()) {
			work.charge(entry.getValue(), content); entry.setValue(entry.getValue().divide(content));
		}
		return row;
	}
	private int[] independentColumns(SignedDyadic[] gram, int modes) {
		var candidates = new ArrayList<TreeMap<Integer, SignedDyadic>>(); var order = new Integer[modes];
		for (int column = 0; column < modes; column++) {
			order[column] = column; var values = new TreeMap<Integer, SignedDyadic>();
			for (int row = 0; row < modes; row++) if (gram[modes * row + column].signum() != 0)
				values.put(row, gram[modes * row + column]);
			candidates.add(values);
		}
		long retainedBytes = 0;
		for (var row : candidates) for (var value : row.values()) retainedBytes += 256L + 2L * ((value.significand().bitLength() + 7L) / 8);
		return selectIndependent(candidates, order, retainedBytes, availableMemory()).stream().mapToInt(Integer::intValue).toArray();
	}
	private ModularSelection modularSelection(List<TreeMap<Integer, SignedDyadic>> features, Integer[] order) {
		var selected = new ArrayList<Integer>(); var pivots = new ArrayList<ModularPivot>();
		var columns = new HashMap<Integer, Integer>(); var supports = new HashMap<Integer, int[]>();
		long entries = 0, parentEntries = 0, available = availableMemory();
		for (int row : order) {
			var current = new TreeMap<Integer, Long>(); var parents = new ArrayList<ModularTerm>();
			features.get(row).forEach((column, value) -> { long residue = modular(value); if (residue != 0) current.put(column, residue); });
			while (!current.isEmpty()) {
				int column = current.firstKey(); Integer prior = columns.get(column);
				checkMemory(256L * (entries + current.size()) + 32L * parentEntries, available);
				if (prior == null) {
					long inverse = power(current.get(column), PRIME - 2); current.replaceAll((key, value) -> value * inverse % PRIME);
					columns.put(column, pivots.size()); pivots.add(new ModularPivot(row, current, inverse, parents));
					selected.add(row); entries += current.size(); break;
				}
				long scale = current.get(column); parents.add(new ModularTerm(prior, scale)); parentEntries++;
				for (var entry : pivots.get(prior).values.entrySet()) {
					work.charge(1); long value = Math.floorMod(current.getOrDefault(entry.getKey(), 0L) - scale * entry.getValue(), PRIME);
					if (value == 0) current.remove(entry.getKey()); else current.put(entry.getKey(), value);
				}
			}
			if (current.isEmpty()) { supports.put(row, modularSupport(row, parents, pivots)); parentEntries -= parents.size(); }
		}
		return new ModularSelection(selected, supports);
	}
	/** Reverse the modular elimination graph only for rejected rows; its coefficients propose an exact support. */
	private int[] modularSupport(int row, List<ModularTerm> parents, List<ModularPivot> pivots) {
		long[] load = new long[pivots.size()]; var relation = new TreeMap<Integer, Long>(); relation.put(row, 1L);
		for (var parent : parents) load[parent.pivot] = Math.floorMod(load[parent.pivot] - parent.factor, PRIME);
		for (int index = pivots.size() - 1; index >= 0; index--) if (load[index] != 0) {
			var pivot = pivots.get(index); work.charge(1); long coefficient = load[index] * pivot.inverse % PRIME;
			long value = (relation.getOrDefault(pivot.row, 0L) + coefficient) % PRIME;
			if (value == 0) relation.remove(pivot.row); else relation.put(pivot.row, value);
			for (var parent : pivot.parents) {
				work.charge(1); load[parent.pivot] = Math.floorMod(load[parent.pivot] - coefficient * parent.factor, PRIME);
			}
		}
		return relation.keySet().stream().mapToInt(Integer::intValue).toArray();
	}
	private long modular(SignedDyadic value) { work.charge(value.significand(), BigInteger.valueOf(PRIME)); return value.significand().mod(BigInteger.valueOf(PRIME)).longValue() * power(value.exponent() >= 0 ? 2 : (PRIME + 1) / 2, Math.abs((long) value.exponent())) % PRIME; }
	private static long power(long base, long exponent) { long result = 1; for (; exponent > 0; exponent >>>= 1, base = base * base % PRIME) if ((exponent & 1) != 0) result = result * base % PRIME; return result; }
	private static long bytes(TreeMap<Integer, BigInteger> values) {
		long result = 0; for (var value : values.values()) result += integerBytes(value); return result;
	}
	private static long integerBytes(BigInteger value) { return 256L + 2L * ((value.abs().bitLength() + 7L) / 8); }
	static long availableMemory() { return Runtime.getRuntime().maxMemory() - Runtime.getRuntime().totalMemory() + Runtime.getRuntime().freeMemory(); }
	private static void checkMemory(long bytes, long available) { if (bytes > available / 2) throw new Unavailable("Pressure row selection exceeds available memory"); }
}

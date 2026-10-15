package mmm.fluid;

import mmm.units.SignedDyadic;
import java.math.BigInteger;
import java.util.*;

/** Modularly independent coordinates in one overlapping velocity-block pressure patch. */
final class PressureRowBasis {
	private static final long PRIME = 2147483647;
	private static final ThreadLocal<Long> validationWorkLimit = new ThreadLocal<>();
	private static final class WorkBudget {
		private long remaining;
		WorkBudget(long allowance) {
			Long limited = validationWorkLimit.get(); remaining = limited == null ? allowance : Math.min(allowance, limited);
		}
		void charge(long cost) {
			if (cost > remaining) throw new Unavailable("Pressure row selection exceeds work budget");
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
	private final int[] rows;
	private PressureRowBasis(int[] rows) { this.rows = rows; }

	private record Features(List<TreeMap<Integer, SignedDyadic>> features, List<List<Integer>> incident,
	                        WorkBudget work, long bytes, long available) { }
	/** Capture common inertia once; zero-storage rows join their velocity patches, finite-gas rows use the full diagonal. */
	static List<PressureRowBasis> patches(int[] starts, int[] indices, SignedDyadic[] coefficients,
	                                     SignedDyadic[][] inertia, int stride, double[] storage, double[] diagonal) {
		var prepared = features(starts, indices, coefficients, inertia, stride, storage, false);
		var patches = new ArrayList<PressureRowBasis>(prepared.incident.size());
		for (var candidates : prepared.incident) {
			Integer[] order = candidates.stream().filter(row -> !prepared.features.get(row).isEmpty()).toArray(Integer[]::new);
			Arrays.sort(order, Comparator.<Integer>comparingDouble(row -> diagonal[row]).reversed()
					.thenComparingInt(row -> prepared.features.get(row).size()));
			int[] selected = selectIndependent(prepared.features, order, prepared.bytes, prepared.available, prepared.work)
					.stream().mapToInt(Integer::intValue).sorted().toArray();
			if (selected.length > 0) patches.add(new PressureRowBasis(selected));
		}
		return List.copyOf(patches);
	}
	/** The small physical coarse space uses the same modular selection, including its positive gas storage. */
	static PressureRowBasis coarse(int[] starts, int[] indices, SignedDyadic[] coefficients,
	                               SignedDyadic[][] inertia, int stride, double[] storage, double[] diagonal) {
		var prepared = features(starts, indices, coefficients, inertia, stride, storage, true);
		var order = new ArrayList<Integer>();
		for (int row = 0; row < storage.length; row++) if (!prepared.features.get(row).isEmpty()) order.add(row);
		order.sort(Comparator.<Integer>comparingDouble(row -> diagonal[row]).reversed()
				.thenComparingInt(row -> prepared.features.get(row).size()));
		return new PressureRowBasis(selectIndependent(prepared.features, order.toArray(Integer[]::new),
				prepared.bytes, prepared.available, prepared.work).stream().mapToInt(Integer::intValue).sorted().toArray());
	}
	private static Features features(int[] starts, int[] indices, SignedDyadic[] coefficients,
	                                 SignedDyadic[][] inertia, int stride, double[] storage, boolean includeStorage) {
		if (stride <= 0) throw new IllegalArgumentException("Invalid pressure factor stride");
		var work = new WorkBudget(inputWork(storage.length, coefficients, inertia, stride));
		int[][] support = new int[inertia.length][]; int[] modes = new int[inertia.length];
		for (int f = 0; f < inertia.length; f++) {
			modes[f] = (int) Math.sqrt(inertia[f].length);
			if (modes[f] > stride || modes[f] * modes[f] != inertia[f].length)
				throw new IllegalArgumentException("Invalid pressure factor Gram shape");
			support[f] = independentColumns(inertia[f], modes[f], work);
		}
		var incident = new ArrayList<List<Integer>>(inertia.length);
		for (int factor = 0; factor < inertia.length; factor++) incident.add(new ArrayList<>());
		var features = new ArrayList<TreeMap<Integer, SignedDyadic>>(storage.length);
		long featureBytes = 0, available = availableMemory();
		for (int row = 0; row < storage.length; row++) {
			if (!includeStorage && storage[row] > 0) { features.add(new TreeMap<>()); continue; }
			var feature = new TreeMap<Integer, SignedDyadic>(); int e = starts[row];
			while (e < starts[row + 1]) {
				int factor = indices[e] / stride; int first = e; incident.get(factor).add(row);
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
		return new Features(features, incident, work, featureBytes, available);
	}
	int size() { return rows.length; }
	int row(int position) { return rows[position]; }

	private static int[] independentColumns(SignedDyadic[] gram, int modes, WorkBudget work) {
		var candidates = new ArrayList<TreeMap<Integer, SignedDyadic>>(); var order = new Integer[modes];
		for (int column = 0; column < modes; column++) {
			order[column] = column; var values = new TreeMap<Integer, SignedDyadic>();
			for (int row = 0; row < modes; row++) if (gram[modes * row + column].signum() != 0)
				values.put(row, gram[modes * row + column]);
			candidates.add(values);
		}
		long retainedBytes = 0;
		for (var row : candidates) for (var value : row.values()) retainedBytes += 256L + 2L * ((value.significand().bitLength() + 7L) / 8);
		return selectIndependent(candidates, order, retainedBytes, availableMemory(), work).stream().mapToInt(Integer::intValue).toArray();
	}
	/** Independence modulo the prime implies real independence; all rows retain their diagonal response. */
	private static List<Integer> selectIndependent(List<TreeMap<Integer, SignedDyadic>> features, Integer[] order,
	                                      long retainedBytes, long available, WorkBudget work) {
		var selected = new ArrayList<Integer>();
		var pivots = new TreeMap<Integer, TreeMap<Integer, Long>>(); long entries = 0;
		for (int row : order) {
			var current = new TreeMap<Integer, Long>();
			features.get(row).forEach((column, value) -> { long residue = modular(value, work); if (residue != 0) current.put(column, residue); });
			while (!current.isEmpty()) {
				int column = current.firstKey(); var pivot = pivots.get(column);
				checkMemory(retainedBytes + 256L * (entries + current.size()), available);
				if (pivot == null) {
					long inverse = power(current.get(column), PRIME - 2);
					current.replaceAll((key, value) -> value * inverse % PRIME);
					pivots.put(column, current); selected.add(row); entries += current.size(); break;
				}
				long scale = current.get(column);
				for (var entry : pivot.entrySet()) {
					work.charge(1);
					long value = Math.floorMod(current.getOrDefault(entry.getKey(), 0L) - scale * entry.getValue(), PRIME);
					if (value == 0) current.remove(entry.getKey()); else current.put(entry.getKey(), value);
				}
			}
		}
		return selected;
	}
	private static long modular(SignedDyadic value, WorkBudget work) { work.charge(value.significand(), BigInteger.valueOf(PRIME)); return value.significand().mod(BigInteger.valueOf(PRIME)).longValue() * power(value.exponent() >= 0 ? 2 : (PRIME + 1) / 2, Math.abs((long) value.exponent())) % PRIME; }
	private static long power(long base, long exponent) { long result = 1; for (; exponent > 0; exponent >>>= 1, base = base * base % PRIME) if ((exponent & 1) != 0) result = result * base % PRIME; return result; }
	static long availableMemory() { return Runtime.getRuntime().maxMemory() - Runtime.getRuntime().totalMemory() + Runtime.getRuntime().freeMemory(); }
	private static void checkMemory(long bytes, long available) { if (bytes > available / 2) throw new Unavailable("Pressure row selection exceeds available memory"); }
}

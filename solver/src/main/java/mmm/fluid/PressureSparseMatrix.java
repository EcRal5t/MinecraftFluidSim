package mmm.fluid;

import java.util.Arrays;

/** Assembled no-drag pressure matrix: exact at D=0 and the coupled operator's IC(0) approximation. */
final class PressureSparseMatrix implements AutoCloseable {
	int[] start = new int[0];
	int[] column = new int[0];
	int[] activeRows = new int[0];
	double[] value = new double[0];
	double[] diagonal = new double[0];
	private boolean[] active = new boolean[0];
	private long[] keys = new long[64];
	private double[] sums = new double[64];
	private int size;
	private int count;
	private int entries;
	private SparseMatrixCompute.Matrix computeMatrix;

	void begin(int size, int expectedRows) {
		closeCompute();
		this.size = size;
		count = 0;
		entries = 0;
		int capacity = 64;
		while (capacity < 12L * expectedRows) capacity *= 2;
		if (keys.length < capacity) {
			keys = new long[capacity];
			sums = new double[capacity];
		}
		if (active.length < size) {
			active = new boolean[size];
			diagonal = new double[size];
			start = new int[size + 1];
			activeRows = new int[size];
		} else {
			Arrays.fill(active, false);
			Arrays.fill(diagonal, 0);
		}
		Arrays.fill(keys, 0);
		Arrays.fill(sums, 0);
	}

	void activate(int row) {
		active[row] = true;
		activeRows[count++] = row;
	}

	boolean active(int row) {
		return row >= 0 && row < size && active[row];
	}

	void add(int row, int col, double amount) {
		if (amount == 0 || !active(row) || !active(col)) return;
		if (3 * entries >= 2 * keys.length) grow();
		long key = (((long) row << 32) | (col & 0xffffffffL)) + 1;
		int slot = slot(key, keys.length);
		while (keys[slot] != 0 && keys[slot] != key) slot = (slot + 1) & (keys.length - 1);
		if (keys[slot] == 0) {
			keys[slot] = key;
			entries++;
		}
		sums[slot] += amount;
	}

	private static int slot(long key, int length) {
		key = (key ^ (key >>> 33)) * 0xff51afd7ed558ccdL;
		key = (key ^ (key >>> 33)) * 0xc4ceb9fe1a85ec53L;
		return (int) (key ^ (key >>> 33)) & (length - 1);
	}

	private void grow() {
		long[] oldKeys = keys;
		double[] oldSums = sums;
		keys = new long[2 * keys.length];
		sums = new double[keys.length];
		for (int i = 0; i < oldKeys.length; i++) {
			if (oldKeys[i] == 0) continue;
			int at = slot(oldKeys[i], keys.length);
			while (keys[at] != 0) at = (at + 1) & (keys.length - 1);
			keys[at] = oldKeys[i];
			sums[at] = oldSums[i];
		}
	}

	void finish() {
		Arrays.fill(start, 0);
		for (int i = 0; i < keys.length; i++) {
			if (keys[i] != 0 && sums[i] != 0) start[(int) ((keys[i] - 1) >>> 32) + 1]++;
		}
		for (int row = 0; row < size; row++) start[row + 1] += start[row];
		int nonzero = start[size];
		if (column.length < nonzero) {
			column = new int[nonzero];
			value = new double[nonzero];
		}
		// Reuse the diagonal storage as row cursors during assembly.
		for (int row = 0; row < size; row++) diagonal[row] = start[row];
		for (int i = 0; i < keys.length; i++) {
			if (keys[i] == 0 || sums[i] == 0) continue;
			long key = keys[i] - 1;
			int row = (int) (key >>> 32);
			int at = (int) diagonal[row]++;
			column[at] = (int) key;
			value[at] = sums[i];
		}
		Arrays.fill(diagonal, 0);
		for (int ordinal = 0; ordinal < count; ordinal++) {
			int row = activeRows[ordinal];
			sort(start[row], start[row + 1] - 1);
			for (int edge = start[row]; edge < start[row + 1]; edge++) {
				if (column[edge] == row) {
					diagonal[row] = value[edge];
					break;
				}
			}
		}
	}

	void prepareCompute() {
		var compute = ComputeExecution.current();
		if (compute != null) computeMatrix = java.util.Objects.requireNonNull(
				compute.prepare(size, start, column, value, activeRows, count));
	}

	SparseMatrixCompute.PressureSolution solve(SparseMatrixCompute.PressureProblem problem) {
		return computeMatrix == null ? null : computeMatrix.solve(problem);
	}

	@Override public void close() { closeCompute(); }

	void closeCompute() {
		var prepared = computeMatrix;
		computeMatrix = null;
		if (prepared != null) prepared.close();
	}

	private void sort(int low, int high) {
		while (low < high) {
			int i = low;
			int j = high;
			int pivot = column[(low + high) >>> 1];
			while (i <= j) {
				while (column[i] < pivot) i++;
				while (column[j] > pivot) j--;
				if (i <= j) {
					int col = column[i];
					column[i] = column[j];
					column[j] = col;
					double v = value[i];
					value[i++] = value[j];
					value[j--] = v;
				}
			}
			if (j - low < high - i) {
				if (low < j) sort(low, j);
				low = i;
			} else {
				if (i < high) sort(i, high);
				high = j;
			}
		}
	}

	void multiply(double[] source, double[] destination) {
		if (computeMatrix != null) {
			computeMatrix.multiply(source, destination);
			return;
		}
		for (int ordinal = 0; ordinal < count; ordinal++) {
			int row = activeRows[ordinal];
			double sum = 0;
			for (int edge = start[row]; edge < start[row + 1]; edge++) {
				sum += value[edge] * source[column[edge]];
			}
			destination[row] = sum;
		}
	}

	double diagonal(int row) { return diagonal[row]; }
	int size() { return size; }
	int nonzeroCount() { return start[size]; }
}

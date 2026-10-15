package mmm.fluid;

import java.util.ArrayList;
import java.util.List;

/** All physical pressure rows incident to an overlapping velocity-block patch. */
final class PressureRowBasis {
	static final class Unavailable extends RuntimeException { Unavailable(String message) { super(message); } }
	private final int[] rows;
	private PressureRowBasis(int[] rows) { this.rows = rows; }
	int size() { return rows.length; }
	int row(int position) { return rows[position]; }

	/** Numerical reliability is determined from the actual mass-whitened patch, retaining every fine row. */
	static List<PressureRowBasis> patches(int[] starts, int[] indices, int factorCount,
	                                     double[] storage, double[] diagonal) {
		if (factorCount < 0 || starts.length != storage.length + 1 || diagonal.length != storage.length)
			throw new IllegalArgumentException("Invalid pressure patch layout");
		var incident = new ArrayList<ArrayList<Integer>>(factorCount);
		for (int factor = 0; factor < factorCount; factor++) incident.add(new ArrayList<>());
		for (int row = 0; row < storage.length; row++) if (storage[row] == 0 && diagonal[row] > 0) {
			int previous = -1;
			for (int entry = starts[row]; entry < starts[row + 1]; entry++) {
				int factor = indices[entry];
				if (factor != previous) { incident.get(factor).add(row); previous = factor; }
			}
		}
		var result = new ArrayList<PressureRowBasis>();
		for (var rows : incident) if (!rows.isEmpty()) result.add(new PressureRowBasis(rows.stream().mapToInt(Integer::intValue).toArray()));
		return List.copyOf(result);
	}
	/** The physical coarse coordinates include their independent positive gas storage. */
	static PressureRowBasis coarse(double[] diagonal) {
		var rows = new ArrayList<Integer>();
		for (int row = 0; row < diagonal.length; row++) if (diagonal[row] > 0) rows.add(row);
		return new PressureRowBasis(rows.stream().mapToInt(Integer::intValue).toArray());
	}
	static long availableMemory() { return Runtime.getRuntime().maxMemory() - Runtime.getRuntime().totalMemory() + Runtime.getRuntime().freeMemory(); }
}

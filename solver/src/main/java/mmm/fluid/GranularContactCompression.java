package mmm.fluid;

/** Fixed-cost mass-weighted sequential impulses for representative sphere contacts. */
final class GranularContactCompression {
	static final int SWEEPS = 32;
	/** A fixed row is n.v(first); a pair row is n.(v(second)-v(first)). */
	record Row(int first, int second, double[] normal) {
		Row { normal = normal.clone(); }
	}
	record Result(double[][] values, double[] impulses, double[] gapError, double[][] arithmeticError) {
		boolean admits(Row row) { return GranularContactCompression.admits(row, values, arithmeticError); }
	}
	private GranularContactCompression() { }

	static Result solve(double[] mass, double[][] input, Row[] rows, Runnable spend) {
		double[] impulse = project(mass, input, rows, spend);
		return rebuild(mass, input, rows, impulse, spend);
	}

	/**
	 * A symmetric projected Gauss-Seidel pass minimizes kinetic energy over nonnegative impulses.
	 * The iteration count is independent of conditioning. Unresolved residual motion is handled by
	 * the next ordinary step and by the shared representative-position resolution.
	 */
	private static double[] project(double[] mass, double[][] input, Row[] rows, Runnable spend) {
		if (mass.length != input.length) throw new IllegalArgumentException("Contact input size mismatch");
		double[][] values = new double[input.length][3];
		for (int i = 0; i < mass.length; i++) {
			if (!(mass[i] > 0 && Double.isFinite(mass[i])) || input[i].length != 3)
				throw new IllegalArgumentException("Invalid contact mass or vector");
			for (int a = 0; a < 3; a++) {
				if (!Double.isFinite(input[i][a])) throw new IllegalArgumentException("Nonfinite contact vector");
				values[i][a] = input[i][a];
			}
		}
		double[] diagonal = new double[rows.length], impulse = new double[rows.length];
		for (int k = 0; k < rows.length; k++) {
			Row row = rows[k];
			if (row.first() < 0 || row.first() >= mass.length || row.second() < -1
					|| row.second() >= mass.length || row.first() == row.second() || row.normal().length != 3)
				throw new IllegalArgumentException("Invalid contact row");
			double norm = 0;
			for (double n : row.normal()) norm = Math.fma(n, n, norm);
			diagonal[k] = norm * (1 / mass[row.first()] + (row.second() < 0 ? 0 : 1 / mass[row.second()]));
			if (!(diagonal[k] > 0 && Double.isFinite(diagonal[k])))
				throw new IllegalArgumentException("Invalid contact inverse mass");
		}
		for (int pass = 0; pass < 2 * SWEEPS; pass++) {
			boolean changed = false;
			for (int visit = 0; visit < rows.length; visit++) {
				spend.run(); int k = (pass & 1) == 0 ? visit : rows.length - 1 - visit;
				double next = Math.max(0, impulse[k] - gap(rows[k], values) / diagonal[k]);
				double change = next - impulse[k]; impulse[k] = next; changed |= change != 0;
				apply(mass, values, rows[k], change);
			}
			if (!changed) break;
		}
		return impulse;
	}

	private static Result rebuild(double[] mass, double[][] input, Row[] rows, double[] impulse, Runnable spend) {
		double[][] values = new double[mass.length][3];
		double[][] error = reconstruct(mass, input, rows, impulse, values, spend);
		double[] bounds = new double[rows.length];
		for (int k = 0; k < rows.length; k++) bounds[k] = gapError(rows[k], values, error);
		return new Result(values, impulse, bounds, error);
	}
	static double[][] reconstruct(double[] mass, double[][] input, Row[] rows, double[] impulse,
	                              double[][] values, Runnable spend) {
		double[][] force = new double[mass.length][3], error = new double[mass.length][3];
		for (int k = 0; k < rows.length; k++) {
			spend.run(); Row row = rows[k];
			for (int a = 0; a < 3; a++) {
				double value = impulse[k] * row.normal()[a];
				force[row.first()][a] += row.second() < 0 ? value : -value;
				if (row.second() >= 0) force[row.second()][a] += value;
			}
		}
		for (int i = 0; i < mass.length; i++) for (int a = 0; a < 3; a++) {
			values[i][a] = input[i][a] + force[i][a] / mass[i];
			error[i][a] = 64 * Math.ulp(Math.max(Math.abs(input[i][a]), Math.abs(force[i][a] / mass[i])));
			if (!Double.isFinite(values[i][a])) throw new IllegalStateException("Nonfinite contact result");
		}
		return error;
	}
	private static void apply(double[] mass, double[][] values, Row row, double impulse) {
		for (int a = 0; a < 3; a++) {
			double change = impulse * row.normal()[a];
			values[row.first()][a] += (row.second() < 0 ? change : -change) / mass[row.first()];
			if (row.second() >= 0) values[row.second()][a] += change / mass[row.second()];
		}
	}
	static boolean admits(Row row, double[][] values, double[][] error) {
		return gap(row, values) >= -gapError(row, values, error);
	}
	private static double gap(Row row, double[][] values) {
		double g = 0;
		for (int a = 0; a < 3; a++) g = Math.fma(row.normal()[a], row.second() < 0
				? values[row.first()][a] : values[row.second()][a] - values[row.first()][a], g);
		return g;
	}
	static double gapError(Row row, double[][] values, double[][] error) {
		double bound = 0;
		for (int a = 0; a < 3; a++) bound += Math.abs(row.normal()[a])
				* (error[row.first()][a] + (row.second() < 0 ? 0 : error[row.second()][a]));
		return bound + 16 * Math.ulp(Math.abs(gap(row, values)));
	}
}

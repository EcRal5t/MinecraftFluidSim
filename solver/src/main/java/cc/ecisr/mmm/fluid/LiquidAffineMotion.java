package cc.ecisr.mmm.fluid;

/** Exact characteristic of a locally affine velocity field, evaluated by scaling and squaring. */
final class LiquidAffineMotion {
	private final double[] matrix;
	private final LiquidPolyhedron.Point center, displacement;

	LiquidAffineMotion(LiquidPolyhedron.Point center, LiquidPolyhedron.Point velocity, double[] gradient, double dt) {
		if (gradient.length != 9 || !Double.isFinite(dt)) throw new IllegalArgumentException("Invalid liquid affine characteristic");
		this.center = center;
		double[] argument = new double[16]; double norm = 0;
		for (int a = 0; a < 3; a++) {
			double row = 0;
			for (int b = 0; b < 3; b++) {
				argument[4 * a + b] = dt * gradient[3 * a + b]; row += Math.abs(argument[4 * a + b]);
			}
			argument[4 * a + 3] = dt * velocity.coordinate(a); row += Math.abs(argument[4 * a + 3]); norm = Math.max(norm, row);
		}
		if (!Double.isFinite(norm)) throw new IllegalStateException("Liquid affine characteristic exceeds numerical range");
		int scale = norm == 0 ? 0 : Math.max(0, Math.getExponent(norm) + 2);
		for (int i = 0; i < argument.length; i++) argument[i] = Math.scalb(argument[i], -scale);
		double[] exponential = identity(), term = identity();
		for (int order = 1; ; order++) {
			term = multiply(term, argument); boolean unchanged = true;
			for (int i = 0; i < term.length; i++) {
				term[i] /= order; double next = exponential[i] + term[i]; unchanged &= next == exponential[i]; exponential[i] = next;
			}
			if (unchanged) break;
		}
		for (int i = 0; i < scale; i++) exponential = multiply(exponential, exponential);
		for (double value : exponential) if (!Double.isFinite(value)) throw new IllegalStateException("Liquid affine characteristic overflow");
		matrix = new double[9];
		for (int a = 0; a < 3; a++) for (int b = 0; b < 3; b++) matrix[3 * a + b] = exponential[4 * a + b];
		displacement = new LiquidPolyhedron.Point(exponential[3], exponential[7], exponential[11]);
	}
	LiquidPolyhedron map(LiquidPolyhedron geometry) { return geometry.transformed(matrix, center, displacement); }
	private static double[] identity() { double[] result = new double[16]; for (int i = 0; i < 4; i++) result[5 * i] = 1; return result; }
	private static double[] multiply(double[] a, double[] b) {
		double[] result = new double[16];
		for (int row = 0; row < 4; row++) for (int column = 0; column < 4; column++)
			for (int k = 0; k < 4; k++) result[4 * row + column] += a[4 * row + k] * b[4 * k + column];
		return result;
	}
}

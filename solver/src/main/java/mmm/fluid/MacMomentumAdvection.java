package mmm.fluid;

import java.util.Arrays;

/** Conservative central dual flux and square-root mass midpoint, with reusable sparse work arrays. */
final class MacMomentumAdvection {
	private final int nx, ny, nz, component, count, edges;
	private final int[] left, right, fluxAxis, firstFlux, secondFlux, firstCell, secondCell;
	private final double[] flow, finalMass, geometricMass, scale, rhs, solution, residual, direction, product, skew, result;
	MacMomentumAdvection(int nx, int ny, int nz, int component) {
		this.nx = nx; this.ny = ny; this.nz = nz; this.component = component;
		int sx = nx + (component == 0 ? 1 : 0), sy = ny + (component == 1 ? 1 : 0), sz = nz + (component == 2 ? 1 : 0);
		count = sx * sy * sz; edges = (sx - 1) * sy * sz + sx * (sy - 1) * sz + sx * sy * (sz - 1);
		left = new int[edges]; right = new int[edges]; fluxAxis = new int[edges]; firstFlux = new int[edges]; secondFlux = new int[edges];
		firstCell = new int[count]; secondCell = new int[count];
		flow = new double[edges]; finalMass = new double[count]; geometricMass = finalMass.clone(); scale = finalMass.clone();
		rhs = finalMass.clone(); solution = finalMass.clone(); residual = finalMass.clone(); direction = finalMass.clone();
		product = finalMass.clone(); skew = finalMass.clone(); result = finalMass.clone();
		int edge = 0;
		for (int z = 0; z < sz; z++) for (int y = 0; y < sy; y++) for (int x = 0; x < sx; x++) {
			int i = (z * sy + y) * sx + x;
			firstCell[i] = cell(x, y, z);
			secondCell[i] = cell(x - (component == 0 ? 1 : 0), y - (component == 1 ? 1 : 0), z - (component == 2 ? 1 : 0));
			for (int axis = 0; axis < 3; axis++) {
				int dx = axis == 0 ? 1 : 0, dy = axis == 1 ? 1 : 0, dz = axis == 2 ? 1 : 0;
				if (x + dx >= sx || y + dy >= sy || z + dz >= sz) continue;
				left[edge] = i; right[edge] = ((z + dz) * sy + y + dy) * sx + x + dx; fluxAxis[edge] = axis;
				firstFlux[edge] = primal(axis, x + dx, y + dy, z + dz);
				secondFlux[edge] = axis == component ? primal(axis, x, y, z)
						: primal(axis, x + dx - (component == 0 ? 1 : 0), y + dy - (component == 1 ? 1 : 0), z + dz - (component == 2 ? 1 : 0));
				edge++;
			}
		}
	}

	/** Inputs are physical kg and kg moved over this complete advection interval. */
	double[] transport(double[] before, double[] after, double[][] primalFlux, float[] velocity) {
		for (int i = 0; i < count; i++) {
			double m0 = .5 * (value(before, firstCell[i]) + value(before, secondCell[i]));
			double m1 = .5 * (value(after, firstCell[i]) + value(after, secondCell[i]));
			finalMass[i] = m1; double root = Math.sqrt(m0) * Math.sqrt(m1); geometricMass[i] = root;
			double diagonal = .5 * (m0 + m1) + root;
			scale[i] = diagonal > 0 ? 1 / Math.sqrt(diagonal) : 0;
			rhs[i] = (m0 + root) * velocity[i] * scale[i];
		}
		// A dual interface receives half each surrounding primal mass flux.
		for (int edge = 0; edge < edges; edge++) {
			double[] column = primalFlux[fluxAxis[edge]];
			flow[edge] = .25 * (value(column, firstFlux[edge]) + value(column, secondFlux[edge])) * scale[left[edge]] * scale[right[edge]];
		}
		// D + S has positive diagonal D and skew S. Scaling gives SPD normal equations I - S*S.
		applySkew(rhs, skew); double norm = 0;
		Arrays.fill(solution, 0);
		for (int i = 0; i < count; i++) { direction[i] = residual[i] = rhs[i] - skew[i]; norm += residual[i] * residual[i]; }
		double original = norm;
		for (int iteration = 0; iteration < 64 && norm > original * 1e-20; iteration++) {
			applySkew(direction, skew); applySkew(skew, product);
			for (int i = 0; i < count; i++) product[i] = direction[i] - product[i];
			double alpha = norm / dot(direction, product);
			for (int i = 0; i < count; i++) { solution[i] += alpha * direction[i]; residual[i] -= alpha * product[i]; }
			double next = dot(residual, residual), beta = next / norm;
			for (int i = 0; i < count; i++) direction[i] = residual[i] + beta * direction[i];
			norm = next;
		}
		if (!Double.isFinite(norm) || norm > original * 1e-16)
			throw new IllegalStateException("MAC momentum midpoint did not converge: relative residual=" + Math.sqrt(norm / original));
		for (int i = 0; i < count; i++) {
			double midpoint = solution[i] * scale[i];
			result[i] = (finalMass[i] + geometricMass[i]) * midpoint - geometricMass[i] * velocity[i];
			if (!Double.isFinite(result[i])) throw new ArithmeticException("MAC momentum transport overflow");
		}
		return result;
	}
	private int cell(int x, int y, int z) { return x < 0 || y < 0 || z < 0 || x >= nx || y >= ny || z >= nz ? -1 : (z * ny + y) * nx + x; }
	private int primal(int axis, int x, int y, int z) {
		int sx = nx + (axis == 0 ? 1 : 0), sy = ny + (axis == 1 ? 1 : 0), sz = nz + (axis == 2 ? 1 : 0);
		return x < 0 || y < 0 || z < 0 || x >= sx || y >= sy || z >= sz ? -1 : (z * sy + y) * sx + x;
	}
	private void applySkew(double[] source, double[] destination) {
		Arrays.fill(destination, 0);
		for (int edge = 0; edge < edges; edge++) { int a = left[edge], b = right[edge]; double q = flow[edge];
			destination[a] += q * source[b]; destination[b] -= q * source[a]; }
	}
	private static double value(double[] values, int index) { return index < 0 ? 0 : values[index]; }
	private static double dot(double[] a, double[] b) { double sum = 0; for (int i = 0; i < a.length; i++) sum += a[i] * b[i]; return sum; }
}

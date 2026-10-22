package mmm.fluid.air;

import java.util.List;

/** Sparse positive pressure/storage solve for one frozen-coefficient cavity-flow step. */
final class GasOrificeFlow {
	private GasOrificeFlow() { }

	static double[] solve(double dt, List<AirEdge> edges, int[] first, int[] second,
	                      double[] pressure, double[] storage, double[] density, double[] ratio) {
		int n = pressure.length, m = edges.size();
		double[] conductance = new double[m], diagonal = storage.clone();
		for (int e = 0; e < m; e++) {
			int a = first[e], b = second[e], donor = pressure[a] >= pressure[b] ? a : b;
			double rho = density[donor] > 0 ? density[donor] : density[0];
			double sourceRatio = ratio[donor] > 0 ? ratio[donor] : ratio[0];
			double drop = Math.abs(pressure[a] - pressure[b]);
			// At exactly zero gradient the square-root law has no finite secant. Use the
			// local absolute-pressure/acoustic scale; this still has zero initial flux,
			// positive coupling and first-order consistency as a neighbouring node changes.
			if (drop == 0) drop = Math.max(AirNetwork.REFERENCE_PRESSURE,
					AirNetwork.REFERENCE_PRESSURE + Math.max(pressure[a], pressure[b]));
			double g = edges.get(e).dischargeCoefficient() * edges.get(e).areaSquareMeters()
					* Math.sqrt(2 / rho) / Math.sqrt(drop) * sourceRatio;
			if (!(Double.isFinite(g) && g > 0)) throw new IllegalArgumentException("Gas port conductance is invalid");
			conductance[e] = dt * g;
			if (a != 0) diagonal[a] += conductance[e];
			if (b != 0) diagonal[b] += conductance[e];
		}
		double[] root = new double[n], x = new double[n], rhs = new double[n];
		for (int i = 1; i < n; i++) {
			if (!(Double.isFinite(diagonal[i]) && diagonal[i] > 0)) throw new IllegalArgumentException("Gas pressure diagonal is invalid");
			root[i] = Math.sqrt(diagonal[i]); x[i] = root[i] * pressure[i];
			rhs[i] = storage[i] / root[i] * pressure[i];
		}
		double[] off = new double[m];
		for (int e = 0; e < m; e++) if (first[e] != 0 && second[e] != 0)
			off[e] = conductance[e] / root[first[e]] / root[second[e]];
		double[] residual = new double[n], direction = new double[n], product = new double[n];
		apply(x, product, first, second, off);
		for (int i = 1; i < n; i++) direction[i] = residual[i] = rhs[i] - product[i];
		double rr = dot(residual, residual), initial = rr;
		int maximumIterations = Math.max(32, Math.min(512, 4 * n));
		for (int iteration = 0; iteration < maximumIterations && rr > initial * 1e-18; iteration++) {
			apply(direction, product, first, second, off);
			double denominator = dot(direction, product);
			if (!(denominator > 0 && Double.isFinite(denominator))) throw new IllegalStateException("Gas pressure matrix lost positive storage");
			double alpha = rr / denominator;
			for (int i = 1; i < n; i++) { x[i] += alpha * direction[i]; residual[i] -= alpha * product[i]; }
			double next = dot(residual, residual), beta = next / rr;
			for (int i = 1; i < n; i++) direction[i] = residual[i] + beta * direction[i];
			rr = next;
		}
		if (!(Double.isFinite(rr) && (initial == 0 || rr <= initial * 1e-14)))
			throw new IllegalStateException("Gas pressure/storage solve did not converge: relativeResidual=" + Math.sqrt(rr / initial));
		double[] solved = new double[n];
		for (int i = 1; i < n; i++) solved[i] = x[i] / root[i];
		double[] transported = new double[m];
		for (int e = 0; e < m; e++) transported[e] = conductance[e] * (solved[first[e]] - solved[second[e]]);
		return transported;
	}
	/** Coordinate roots use the same inventory/energy endpoint that will be committed. */
	static double[] solveThermal(double dt, List<AirEdge> edges, int[] first, int[] second,
	                            double[] pressure, double[] density, double[] ratio,
	                            double[] limit, java.util.function.Function<double[], double[]> endpoint) {
		double[] flow = new double[edges.size()], conductance = new double[edges.size()];
		for (int e = 0; e < flow.length; e++) {
			int a = first[e], b = second[e], donor = pressure[a] >= pressure[b] ? a : b;
			double rho = density[donor] > 0 ? density[donor] : density[0];
			double drop = Math.abs(pressure[a] - pressure[b]);
			if (drop == 0) drop = Math.max(AirNetwork.REFERENCE_PRESSURE,
					AirNetwork.REFERENCE_PRESSURE + Math.max(pressure[a], pressure[b]));
			conductance[e] = dt * edges.get(e).dischargeCoefficient() * edges.get(e).areaSquareMeters()
					* Math.sqrt(2 / rho / drop) * (ratio[donor] > 0 ? ratio[donor] : ratio[0]);
		}
		for (int sweep = 0; sweep < 64; sweep++) {
			double change = 0, magnitude = 0;
			for (int e = 0; e < flow.length; e++) {
				double old = flow[e]; flow[e] = 0;
				double[] p = endpoint.apply(flow);
				double drive = conductance[e] * (p[first[e]] - p[second[e]]);
				if (drive == 0) { change = Math.max(change, Math.abs(old)); continue; }
				double sign = Math.signum(drive), lo = 0, hi = Math.abs(drive);
				int donor = sign > 0 ? first[e] : second[e];
				double remaining = limit[donor];
				for (int j = 0; j < flow.length; j++) if (j != e)
					if (flow[j] > 0 && first[j] == donor || flow[j] < 0 && second[j] == donor) remaining -= Math.abs(flow[j]);
				hi = Math.min(hi, Math.max(0, remaining));
				flow[e] = sign * hi; p = endpoint.apply(flow);
				double lowResidual = -Math.abs(drive);
				double highResidual = hi - sign * conductance[e] * (p[first[e]] - p[second[e]]);
				if (highResidual > 0) {
					boolean converged = false;
					for (int iteration = 0; iteration < 32; iteration++) {
						// Bracketed secants follow the smooth caloric response; the interval also
						// resolves integer inventory plateaus without numerical derivatives.
						double weight = Math.clamp(-lowResidual / (highResidual - lowResidual), .05, .95);
						double mid = lo + (hi - lo) * weight; flow[e] = sign * mid;
						p = endpoint.apply(flow);
						double residual = mid - sign * conductance[e] * (p[first[e]] - p[second[e]]);
						if (Math.abs(residual) <= 1e-10 + mid * 1e-10 || hi - lo <= 1e-10) { converged = true; break; }
						if (residual > 0) { hi = mid; highResidual = residual; }
						else { lo = mid; lowResidual = residual; }
					}
					if (!converged) throw new IllegalStateException("Gas port inventory/enthalpy root did not converge");
				}
				change = Math.max(change, Math.abs(flow[e] - old)); magnitude = Math.max(magnitude, Math.abs(flow[e]));
			}
			if (change <= 2e-9 + magnitude * 1e-8) return flow;
		}
		throw new IllegalStateException("Gas inventory/enthalpy pressure solve did not converge");
	}

	private static void apply(double[] x, double[] output, int[] first, int[] second, double[] off) {
		System.arraycopy(x, 0, output, 0, x.length); output[0] = 0;
		for (int e = 0; e < off.length; e++) if (off[e] != 0) {
			output[first[e]] -= off[e] * x[second[e]]; output[second[e]] -= off[e] * x[first[e]];
		}
	}
	private static double dot(double[] first, double[] second) {
		double sum = 0; for (int i = 1; i < first.length; i++) sum += first[i] * second[i]; return sum;
	}
}

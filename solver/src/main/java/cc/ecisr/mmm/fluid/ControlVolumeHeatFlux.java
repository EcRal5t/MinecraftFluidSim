package cc.ecisr.mmm.fluid;

/** Conductive flux from continuous face temperature and the shared control-volume metric. */
final class ControlVolumeHeatFlux {
	record Result(double[] faceTemperature, double[] cellHeating, double[] faceResidual,
			int iterations, double diffusionEnergy, double reservoirHeating) { }
	private final LiquidControlVolumeMesh mesh;
	private final ControlVolumeThermalMetric metric;
	private final double[] diagonal;

	ControlVolumeHeatFlux(LiquidControlVolumeMesh mesh, double[] conductivity) {
		metric = new ControlVolumeThermalMetric(mesh, conductivity); this.mesh = mesh; diagonal = metric.faceDiagonal;
	}

	/** NaN on an exterior face specifies zero heat flux; finite values specify a reservoir. */
	Result solve(double[] cellTemperature, double[] prescribed, double fluxTolerance, int maximumIterations) {
		if (cellTemperature.length != mesh.cells.size() || prescribed.length != diagonal.length
				|| !(Double.isFinite(fluxTolerance) && fluxTolerance > 0) || maximumIterations < 1)
			throw new IllegalArgumentException("Invalid heat flux solve");
		double reference = cellTemperature.length == 0 ? 0 : cellTemperature[0];
		var temperature = cellTemperature.clone(); var face = new double[diagonal.length]; var free = new boolean[diagonal.length];
		for (int c = 0; c < temperature.length; c++) {
			temperature[c] -= reference;
			if (!Double.isFinite(cellTemperature[c]) || !Double.isFinite(temperature[c])) throw new IllegalArgumentException("Non-finite cell temperature");
		}
		for (int f = 0; f < face.length; f++) {
			var patch = mesh.faces.get(f); boolean reservoir = !Double.isNaN(prescribed[f]);
			if (reservoir && (patch.second() >= 0 || !Double.isFinite(prescribed[f])))
				throw new IllegalArgumentException("Reservoir temperature requires an exterior face");
			free[f] = !reservoir && diagonal[f] > 0;
			face[f] = reservoir ? prescribed[f] - reference : patch.second() < 0 ? temperature[patch.first()]
					: 0.5 * temperature[patch.first()] + 0.5 * temperature[patch.second()];
			if (!Double.isFinite(face[f])) throw new IllegalArgumentException("Face temperature exceeds numerical range");
		}
		var residual = new double[face.length]; var direction = residual.clone(); var product = residual.clone();
		var zeros = new double[temperature.length];
		int iterations = 0; boolean restart = true; double previousNorm = 0;
		while (true) {
			if (restart) {
				metric.apply(temperature, face, null, residual);
				for (int f = 0; f < face.length; f++) residual[f] = free[f] ? -residual[f] : 0;
				if (maximum(residual) <= fluxTolerance) break;
			}
			if (iterations++ >= maximumIterations) throw new IllegalStateException("Control-volume heat flux did not converge");
			double norm = 0;
			for (int f = 0; f < face.length; f++) if (free[f]) norm += residual[f] * (residual[f] / diagonal[f]);
			if (!(norm > 0) || !Double.isFinite(norm)) throw new IllegalStateException("Heat flux residual exceeds numerical range");
			double beta = restart ? 0 : norm / previousNorm;
			for (int f = 0; f < face.length; f++) direction[f] = free[f] ? residual[f] / diagonal[f] + beta * direction[f] : 0;
			metric.apply(zeros, direction, null, product);
			double inertia = ConservativeConjugateDirections.dot(direction, product);
			if (!(inertia > 0) || !Double.isFinite(inertia)) throw new IllegalStateException("Heat flux direction has no positive diffusion energy");
			double alpha = norm / inertia;
			for (int f = 0; f < face.length; f++) if (free[f]) { face[f] += alpha * direction[f]; residual[f] -= alpha * product[f]; }
			previousNorm = norm; restart = maximum(residual) <= fluxTolerance;
		}
		double energy = metric.apply(temperature, face, null, residual);
		var heating = metric.heating(prescribed);
		for (int f = 0; f < face.length; f++) {
			if (free[f] && Math.abs(residual[f]) > fluxTolerance) throw new IllegalStateException("Heat flux publication exceeds its residual tolerance");
			face[f] += reference;
			if (!Double.isFinite(face[f])) throw new IllegalStateException("Heat flux face temperature overflow");
		}
		maximum(residual);
		return new Result(face, heating.inventoryRates(), residual, iterations, energy, heating.reservoir());
	}
	private static double maximum(double[] values) {
		double result = 0;
		for (double value : values) {
			if (!Double.isFinite(value)) throw new IllegalStateException("Heat flux residual overflow");
			result = Math.max(result, Math.abs(value));
		}
		return result;
	}
}

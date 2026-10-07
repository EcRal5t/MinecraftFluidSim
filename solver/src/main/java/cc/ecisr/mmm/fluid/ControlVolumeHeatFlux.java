package cc.ecisr.mmm.fluid;

import java.util.Arrays;

/** Conductive flux from continuous face temperature and the shared control-volume metric. */
final class ControlVolumeHeatFlux {
		record Result(double[] faceTemperature, double[] cellHeating, double[] faceResidual,
			int iterations, double diffusionEnergy, double reservoirHeating) { }
	private final LiquidControlVolumeMesh mesh;
	private final ControlVolumeGradient[] geometry;
	private final ControlVolumeDiffusion[] diffusion;
	private final double[] conductivity, diagonal;
	private final double[][] differences, flux;

	ControlVolumeHeatFlux(LiquidControlVolumeMesh mesh, double[] conductivity) {
		if (mesh == null || conductivity.length != mesh.cells.size()) throw new IllegalArgumentException("Incomplete heat flux geometry");
		this.mesh = mesh; this.conductivity = conductivity.clone();
		geometry = ControlVolumeGradient.build(mesh.cells, mesh.faces);
		diffusion = Arrays.stream(geometry).map(ControlVolumeDiffusion::new).toArray(ControlVolumeDiffusion[]::new);
		differences = new double[geometry.length][]; flux = differences.clone(); diagonal = new double[mesh.faces.size()];
		for (int c = 0; c < geometry.length; c++) {
			if (!(Double.isFinite(conductivity[c]) && conductivity[c] >= 0)) throw new IllegalArgumentException("Invalid control-volume conductivity");
			var g = geometry[c]; differences[c] = new double[g.indices.length]; flux[c] = differences[c].clone();
			for (int i = 0; i < g.indices.length; i++) diagonal[g.indices[i]] += conductivity[c] * g.basisEnergy(i);
		}
		for (double value : diagonal) if (!(Double.isFinite(value) && value >= 0)) throw new IllegalArgumentException("Heat flux preconditioning overflow");
	}

	/** NaN on an exterior face specifies zero heat flux; finite values specify a reservoir. */
	Result solve(double[] cellTemperature, double[] prescribed, double fluxTolerance, int maximumIterations) {
		if (cellTemperature.length != geometry.length || prescribed.length != diagonal.length
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
		var zeros = new double[temperature.length]; var gradient = new double[3];
		int iterations = 0; boolean restart = true; double previousNorm = 0;
		while (true) {
			if (restart) {
				evaluate(temperature, face, residual, gradient);
				for (int f = 0; f < face.length; f++) residual[f] = free[f] ? -residual[f] : 0;
				if (maximum(residual) <= fluxTolerance) break;
			}
			if (iterations++ >= maximumIterations) throw new IllegalStateException("Control-volume heat flux did not converge");
			double norm = 0;
			for (int f = 0; f < face.length; f++) if (free[f]) norm += residual[f] * (residual[f] / diagonal[f]);
			if (!(norm > 0) || !Double.isFinite(norm)) throw new IllegalStateException("Heat flux residual exceeds numerical range");
			double beta = restart ? 0 : norm / previousNorm;
			for (int f = 0; f < face.length; f++) direction[f] = free[f] ? residual[f] / diagonal[f] + beta * direction[f] : 0;
			evaluate(zeros, direction, product, gradient);
			double inertia = ConservativeConjugateDirections.dot(direction, product);
			if (!(inertia > 0) || !Double.isFinite(inertia)) throw new IllegalStateException("Heat flux direction has no positive diffusion energy");
			double alpha = norm / inertia;
			for (int f = 0; f < face.length; f++) if (free[f]) { face[f] += alpha * direction[f]; residual[f] -= alpha * product[f]; }
			previousNorm = norm; restart = maximum(residual) <= fluxTolerance;
		}
		var heating = new double[temperature.length];
		double energy = evaluate(temperature, face, residual, gradient), reservoir = 0;
		var firstRate = new double[face.length]; var secondRate = firstRate.clone();
		for (int c = 0; c < geometry.length; c++) for (int i = 0; i < geometry[c].indices.length; i++) {
			int f = geometry[c].indices[i];
			(mesh.faces.get(f).first() == c ? firstRate : secondRate)[f] = conductivity[c] * flux[c][i];
		}
		for (int f = 0; f < face.length; f++) {
			if (free[f] && Math.abs(residual[f]) > fluxTolerance) throw new IllegalStateException("Heat flux publication exceeds its residual tolerance");
			var patch = mesh.faces.get(f);
			if (patch.second() >= 0) {
				// Orthogonal projection onto one shared conservative flux. Each side changes by half its measured residual.
				double rate = 0.5 * firstRate[f] - 0.5 * secondRate[f];
				heating[patch.first()] += rate; heating[patch.second()] -= rate;
			} else if (!Double.isNaN(prescribed[f])) { heating[patch.first()] += firstRate[f]; reservoir += firstRate[f]; }
			face[f] += reference;
			if (!Double.isFinite(face[f])) throw new IllegalStateException("Heat flux face temperature overflow");
		}
		if (!Double.isFinite(reservoir)) throw new IllegalStateException("Reservoir heat flux overflow");
		maximum(heating); maximum(residual);
		return new Result(face, heating, residual, iterations, energy, reservoir);
	}

	private double evaluate(double[] temperature, double[] face, double[] residual, double[] gradient) {
		Arrays.fill(residual, 0); double energy = 0;
		for (int c = 0; c < geometry.length; c++) {
			if (conductivity[c] == 0) continue;
			var g = geometry[c];
			for (int i = 0; i < g.indices.length; i++) differences[c][i] = face[g.indices[i]] - temperature[c];
			energy += conductivity[c] * diffusion[c].apply(differences[c], flux[c], 1, 0, gradient);
			for (int i = 0; i < g.indices.length; i++) {
				double rate = conductivity[c] * flux[c][i]; residual[g.indices[i]] += rate;
			}
		}
		if (!Double.isFinite(energy)) throw new IllegalStateException("Total heat flux diffusion energy overflow");
		return energy;
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

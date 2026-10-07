package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.thermal.ThermalContactExchange;
import cc.ecisr.mmm.thermal.ThermalLimits;
import cc.ecisr.mmm.units.SignedDyadic;
import java.util.List;

/** Backward-Euler caloric conduction on the complete cell/face diffusion energy. */
final class ControlVolumeConduction {
	record Result(double[] energyChange, double[] temperature, double[] faceTemperature,
			int iterations, SignedDyadic reservoirEnergy, SignedDyadic roundoff,
			SignedDyadic roundoffBound, double maximumCaloricResidual, double maximumFluxCorrection) { }
	private final ControlVolumeThermalMetric metric;
	private final int cells, faces;
	private final double[] cellValue, faceValue, cellForce, faceForce;

	ControlVolumeConduction(LiquidControlVolumeMesh mesh, double[] conductivity) {
		metric = new ControlVolumeThermalMetric(mesh, conductivity); cells = mesh.cells.size(); faces = mesh.faces.size();
		cellValue = new double[cells]; faceValue = new double[faces]; cellForce = cellValue.clone(); faceForce = faceValue.clone();
	}

	Result advance(List<? extends ThermalContactExchange.Body> source, double[] prescribed,
			double dt, double energyTolerance, int maximumIterations) {
		var bodies = List.copyOf(source);
		if (bodies.size() != cells || prescribed.length != faces || !(Double.isFinite(dt) && dt > 0)
				|| !(Double.isFinite(energyTolerance) && energyTolerance > 0) || maximumIterations < 1)
			throw new IllegalArgumentException("Invalid resolved conduction input");
		var value = new double[cells + faces]; var free = new boolean[value.length]; var diagonal = value.clone();
		double reference = cells == 0 ? 0 : bodies.getFirst().temperatureK();
		for (int c = 0; c < cells; c++) {
			if (!bodies.get(c).finite()) throw new IllegalArgumentException("Control-volume conduction requires finite caloric cells");
			double temperature = bodies.get(c).temperatureK();
			if (!(Double.isFinite(temperature) && temperature >= ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K))
				throw new IllegalArgumentException("Invalid initial caloric temperature");
			value[c] = temperature - reference; free[c] = true;
		}
		for (int f = 0; f < faces; f++) {
			var face = metric.mesh.faces.get(f); boolean fixed = !Double.isNaN(prescribed[f]);
			if (fixed) {
				if (face.second() >= 0) throw new IllegalArgumentException("Thermal reservoir requires an exterior face");
				new ThermalContactExchange.Reservoir(prescribed[f]);
				value[cells + f] = prescribed[f] - reference;
			} else value[cells + f] = face.second() < 0 ? value[face.first()] : 0.5 * value[face.first()] + 0.5 * value[face.second()];
			free[cells + f] = !fixed && metric.faceDiagonal[f] > 0;
			diagonal[cells + f] = dt * metric.faceDiagonal[f];
			if (free[cells + f] && !(Double.isFinite(diagonal[cells + f]) && diagonal[cells + f] > 0))
				throw new IllegalStateException("Conduction face exceeds its temporal numerical support");
		}
		var residual = value.clone(); var capacity = new double[cells];
		var trial = value.clone(); var trialResidual = value.clone(); var trialCapacity = capacity.clone();
		int[] work = {0};
		while (true) {
			evaluate(bodies, reference, value, dt, residual, capacity);
			mask(residual, free); double error = maximum(residual);
			if (error <= energyTolerance) break;
			for (int c = 0; c < cells; c++) diagonal[c] = capacity[c] + dt * metric.cellDiagonal[c];
			var correction = solve(residual, capacity, free, diagonal, dt, energyTolerance, maximumIterations, work);
			double scale = 1;
			while (true) {
				if (++work[0] > maximumIterations) throw new IllegalStateException("Resolved conduction exhausted its iteration budget");
				boolean valid = true, changed = false;
				for (int i = 0; i < value.length; i++) {
					trial[i] = value[i] + scale * correction[i]; changed |= trial[i] != value[i];
					valid &= Double.isFinite(trial[i]) && (i >= cells || reference + trial[i] >= ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K);
				}
				if (!changed) throw new IllegalStateException("Resolved conduction correction is below its temperature precision");
				if (valid) {
					evaluate(bodies, reference, trial, dt, trialResidual, trialCapacity); mask(trialResidual, free);
					if (maximum(trialResidual) < error) { System.arraycopy(trial, 0, value, 0, value.length); break; }
				}
				scale *= 0.5;
			}
		}
		var heating = metric.heating(prescribed, faceForce); var changes = heating.cells(); var temperatures = new double[cells];
		var time = SignedDyadic.of(dt); var observationBound = SignedDyadic.ZERO;
		double caloricResidual = 0;
		for (int c = 0; c < cells; c++) {
			var exactCorrection = heating.quantities()[c].add(SignedDyadic.of(cellForce[c])).multiply(time);
			double correction = exactCorrection.signum() == 0 ? 0 : Math.nextUp(Math.abs(exactCorrection.toDouble()));
			changes[c] = heating.quantities()[c].multiply(time).toDouble();
			var state = bodies.get(c).stateAfterEnergyChange(changes[c]); temperatures[c] = state.temperatureK();
			double expected = bodies.get(c).energyChangeToTemperature(reference + value[c]);
			double error = Math.abs(expected - changes[c]); caloricResidual = Math.max(caloricResidual, error);
			if (!Double.isFinite(changes[c]) || !(Double.isFinite(temperatures[c]) && temperatures[c] >= ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K))
				throw new IllegalStateException("Resolved conduction caloric publication exceeds its positive-temperature support");
			observationBound = observationBound.add(SignedDyadic.of(Math.ulp(changes[c])).multiply(SignedDyadic.of(0.5)));
			double roundoffBound = Math.ulp(expected) + Math.ulp(changes[c]) + Math.ulp(dt * cellForce[c]) + Math.ulp(correction);
			if (error > energyTolerance + correction + roundoffBound) throw new IllegalStateException("Conservative heat flux exceeds its caloric residual certificate");
		}
		// The measured face correction is accounted separately from caloric solve tolerance.
		var reservoir = heating.reservoirQuantity().multiply(time);
		var roundoff = SignedDyadic.sum(changes).subtract(reservoir);
		if (roundoff.compareTo(observationBound) > 0 || roundoff.compareTo(observationBound.negate()) < 0)
			throw new IllegalStateException("Resolved conduction energy exceeds its single-rounding certificate");
		for (int f = 0; f < faces; f++) {
			faceValue[f] = reference + value[cells + f];
			if (!(Double.isFinite(faceValue[f]) && faceValue[f] >= ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K))
				throw new IllegalStateException("Resolved conduction face requires finer temperature support");
		}
		return new Result(changes, temperatures, faceValue.clone(), work[0], reservoir, roundoff, observationBound, caloricResidual,
				correctionBound(heating.maximumFluxCorrection(), dt));
	}

	private void evaluate(List<? extends ThermalContactExchange.Body> bodies, double reference, double[] value,
			double dt, double[] residual, double[] capacity) {
		action(value, residual, dt);
		for (int c = 0; c < cells; c++) {
			double temperature = reference + value[c];
			double change = bodies.get(c).energyChangeToTemperature(temperature);
			capacity[c] = bodies.get(c).stateAfterEnergyChange(change).heatCapacityEuPerK();
			if (!(Double.isFinite(capacity[c]) && capacity[c] > 0)) throw new IllegalArgumentException("Invalid caloric conduction capacity");
			residual[c] += change;
		}
	}
	private void action(double[] value, double[] result, double dt) {
		System.arraycopy(value, 0, cellValue, 0, cells); System.arraycopy(value, cells, faceValue, 0, faces);
		metric.apply(cellValue, faceValue, cellForce, faceForce);
		for (int c = 0; c < cells; c++) result[c] = dt * cellForce[c];
		for (int f = 0; f < faces; f++) result[cells + f] = dt * faceForce[f];
	}
	private double[] solve(double[] rhs, double[] capacity, boolean[] free, double[] diagonal, double dt,
			double tolerance, int maximumIterations, int[] work) {
		var result = new double[rhs.length]; var residual = rhs.clone();
		for (int i = 0; i < rhs.length; i++) residual[i] = -rhs[i];
		var direction = rhs.clone(); var product = rhs.clone(); boolean restart = true; double previous = 0;
		for (int i = 0; i < rhs.length; i++) if (free[i] && !(Double.isFinite(diagonal[i]) && diagonal[i] > 0))
			throw new IllegalStateException("Conduction capacity or diffusion exceeds numerical support");
		while (maximum(residual) > tolerance) {
			if (++work[0] > maximumIterations) throw new IllegalStateException("Resolved conduction exhausted its iteration budget");
			double norm = 0;
			for (int i = 0; i < rhs.length; i++) if (free[i]) norm += residual[i] * (residual[i] / diagonal[i]);
			if (!(Double.isFinite(norm) && norm > 0)) throw new IllegalStateException("Conduction residual exceeds numerical support");
			double beta = restart ? 0 : norm / previous;
			for (int i = 0; i < rhs.length; i++) direction[i] = free[i] ? residual[i] / diagonal[i] + beta * direction[i] : 0;
			action(direction, product, dt); for (int c = 0; c < cells; c++) product[c] += capacity[c] * direction[c];
			double inertia = ConservativeConjugateDirections.dot(direction, product);
			if (!(Double.isFinite(inertia) && inertia > 0)) throw new IllegalStateException("Conduction correction has no positive energy");
			double alpha = norm / inertia;
			for (int i = 0; i < rhs.length; i++) if (free[i]) { result[i] += alpha * direction[i]; residual[i] -= alpha * product[i]; }
			mask(residual, free); previous = norm; restart = false;
			if (maximum(residual) <= tolerance) {
				action(result, product, dt); for (int c = 0; c < cells; c++) product[c] += capacity[c] * result[c];
				for (int i = 0; i < rhs.length; i++) residual[i] = free[i] ? -rhs[i] - product[i] : 0;
				restart = true;
			}
		}
		return result;
	}
	private static void mask(double[] value, boolean[] free) { for (int i = 0; i < value.length; i++) if (!free[i]) value[i] = 0; }
	private static double correctionBound(double rate, double dt) {
		double value = rate * dt;
		if (!Double.isFinite(value)) throw new IllegalStateException("Conduction correction exceeds its energy support");
		double bound = rate == 0 ? 0 : Math.nextUp(value);
		if (!Double.isFinite(bound)) throw new IllegalStateException("Conduction correction bound exceeds its energy support");
		return bound;
	}
	private static double maximum(double[] value) {
		double maximum = 0;
		for (double entry : value) { if (!Double.isFinite(entry)) throw new IllegalStateException("Non-finite conduction residual"); maximum = Math.max(maximum, Math.abs(entry)); }
		return maximum;
	}
}

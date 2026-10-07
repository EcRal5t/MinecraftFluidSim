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
	private final int inventories, faces;
	private final double[] inventoryValue, faceValue, inventoryForce, faceForce;

	ControlVolumeConduction(LiquidControlVolumeMesh mesh, double[] conductivity) {
		this(new ControlVolumeThermalMetric(mesh, conductivity));
	}
	ControlVolumeConduction(LiquidControlVolumeMesh mesh, int[] inventoryByCell, int inventoryCount, double[] conductivity) {
		this(new ControlVolumeThermalMetric(mesh, inventoryByCell, inventoryCount, conductivity));
	}
	ControlVolumeConduction(LiquidControlVolumeMesh mesh, int inventoryCount, List<ControlVolumeThermalMetric.Portion> portions) {
		this(new ControlVolumeThermalMetric(mesh, inventoryCount, portions));
	}
	private ControlVolumeConduction(ControlVolumeThermalMetric metric) {
		this.metric = metric; inventories = metric.inventoryDiagonal.length; faces = metric.faceDiagonal.length;
		inventoryValue = new double[inventories]; faceValue = new double[faces]; inventoryForce = inventoryValue.clone(); faceForce = faceValue.clone();
	}

	Result advance(List<? extends ThermalContactExchange.Body> source, double[] prescribed,
			double dt, double energyTolerance, int maximumIterations) {
		var bodies = List.copyOf(source);
		if (bodies.size() != inventories || prescribed.length != faces || !(Double.isFinite(dt) && dt > 0)
				|| !(Double.isFinite(energyTolerance) && energyTolerance > 0) || maximumIterations < 1)
			throw new IllegalArgumentException("Invalid resolved conduction input");
		var value = new double[inventories + faces]; var free = new boolean[value.length]; var diagonal = value.clone();
		double reference = inventories == 0 ? 0 : bodies.getFirst().temperatureK();
		for (int c = 0; c < inventories; c++) {
			double temperature = bodies.get(c).temperatureK();
			if (!(Double.isFinite(temperature) && temperature >= ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K))
				throw new IllegalArgumentException("Invalid initial caloric temperature");
			value[c] = temperature - reference; free[c] = bodies.get(c).finite();
		}
		var initial = metric.initialTemperatures(java.util.Arrays.copyOf(value, inventories));
		for (int f = 0; f < faces; f++) {
			var face = metric.mesh.faces.get(f); boolean fixed = !Double.isNaN(prescribed[f]);
			if (fixed) {
				if (face.second() >= 0) throw new IllegalArgumentException("Thermal reservoir requires an exterior face");
				new ThermalContactExchange.Reservoir(prescribed[f]);
				value[inventories + f] = prescribed[f] - reference;
			} else value[inventories + f] = face.second() < 0 ? initial[face.first()]
					: 0.5 * initial[face.first()] + 0.5 * initial[face.second()];
			free[inventories + f] = !fixed && metric.faceDiagonal[f] > 0;
			diagonal[inventories + f] = dt * metric.faceDiagonal[f];
			if (free[inventories + f] && !(Double.isFinite(diagonal[inventories + f]) && diagonal[inventories + f] > 0))
				throw new IllegalStateException("Conduction face exceeds its temporal numerical support");
		}
		var residual = value.clone(); var capacity = new double[inventories];
		var trial = value.clone(); var trialResidual = value.clone(); var trialCapacity = capacity.clone();
		int[] work = {0};
		while (true) {
			evaluate(bodies, reference, value, dt, residual, capacity);
			mask(residual, free); double error = maximum(residual);
			if (error <= energyTolerance) break;
			for (int c = 0; c < inventories; c++) diagonal[c] = capacity[c] + dt * metric.inventoryDiagonal[c];
			var correction = solve(residual, capacity, free, diagonal, dt, energyTolerance, maximumIterations, work);
			double scale = 1;
			while (true) {
				if (++work[0] > maximumIterations) throw new IllegalStateException("Resolved conduction exhausted its iteration budget");
				boolean valid = true, changed = false;
				for (int i = 0; i < value.length; i++) {
					trial[i] = value[i] + scale * correction[i]; changed |= trial[i] != value[i];
					valid &= Double.isFinite(trial[i]) && (i >= inventories || reference + trial[i] >= ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K);
				}
				if (!changed) throw new IllegalStateException("Resolved conduction correction is below its temperature precision: residual="
						+ error + ", requested=" + energyTolerance);
				if (valid) {
					evaluate(bodies, reference, trial, dt, trialResidual, trialCapacity); mask(trialResidual, free);
					if (maximum(trialResidual) < error) { System.arraycopy(trial, 0, value, 0, value.length); break; }
				}
				scale *= 0.5;
			}
		}
		var heating = metric.heating(prescribed); var changes = heating.inventoryRates(); var temperatures = new double[inventories];
		var time = SignedDyadic.of(dt); var observationBound = SignedDyadic.ZERO;
		var reservoir = heating.reservoirQuantity().multiply(time);
		double caloricResidual = 0;
		for (int c = 0; c < inventories; c++) {
			if (!bodies.get(c).finite()) {
				reservoir = reservoir.subtract(heating.quantities()[c].multiply(time));
				changes[c] = 0; temperatures[c] = bodies.get(c).temperatureK(); continue;
			}
			var exactCorrection = heating.quantities()[c].add(SignedDyadic.of(inventoryForce[c])).multiply(time);
			double correction = exactCorrection.signum() == 0 ? 0 : Math.nextUp(Math.abs(exactCorrection.toDouble()));
			changes[c] = heating.quantities()[c].multiply(time).toDouble();
			var state = bodies.get(c).stateAfterEnergyChange(changes[c]); temperatures[c] = state.temperatureK();
			double expected = bodies.get(c).energyChangeToTemperature(reference + value[c]);
			double error = Math.abs(expected - changes[c]); caloricResidual = Math.max(caloricResidual, error);
			if (!Double.isFinite(changes[c]) || !(Double.isFinite(temperatures[c]) && temperatures[c] >= ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K))
				throw new IllegalStateException("Resolved conduction caloric publication exceeds its positive-temperature support");
			observationBound = observationBound.add(SignedDyadic.of(Math.ulp(changes[c])).multiply(SignedDyadic.of(0.5)));
			double roundoffBound = Math.ulp(expected) + Math.ulp(changes[c]) + Math.ulp(dt * inventoryForce[c]) + Math.ulp(correction);
			if (error > energyTolerance + correction + roundoffBound) throw new IllegalStateException("Conservative heat flux exceeds its caloric residual certificate");
		}
		// The measured face correction is accounted separately from caloric solve tolerance.
		var roundoff = SignedDyadic.sum(changes).subtract(reservoir);
		if (roundoff.compareTo(observationBound) > 0 || roundoff.compareTo(observationBound.negate()) < 0)
			throw new IllegalStateException("Resolved conduction energy exceeds its single-rounding certificate");
		for (int f = 0; f < faces; f++) {
			if (!metric.faceHasInventory(f) && Double.isNaN(prescribed[f])) { faceValue[f] = Double.NaN; continue; }
			faceValue[f] = reference + value[inventories + f];
			if (!(Double.isFinite(faceValue[f]) && faceValue[f] >= ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K))
				throw new IllegalStateException("Resolved conduction face requires finer temperature support");
		}
		return new Result(changes, temperatures, faceValue.clone(), work[0], reservoir, roundoff, observationBound, caloricResidual,
				correctionBound(heating.maximumFluxCorrection(), dt));
	}

	private void evaluate(List<? extends ThermalContactExchange.Body> bodies, double reference, double[] value,
			double dt, double[] residual, double[] capacity) {
		action(value, residual, dt);
		for (int c = 0; c < inventories; c++) {
			if (!bodies.get(c).finite()) { capacity[c] = 0; continue; }
			double temperature = reference + value[c];
			double change = bodies.get(c).energyChangeToTemperature(temperature);
			capacity[c] = bodies.get(c).stateAfterEnergyChange(change).heatCapacityEuPerK();
			if (!(Double.isFinite(capacity[c]) && capacity[c] > 0)) throw new IllegalArgumentException("Invalid caloric conduction capacity");
			residual[c] += change;
		}
	}
	private void action(double[] value, double[] result, double dt) {
		System.arraycopy(value, 0, inventoryValue, 0, inventories); System.arraycopy(value, inventories, faceValue, 0, faces);
		metric.apply(inventoryValue, faceValue, inventoryForce, faceForce);
		for (int c = 0; c < inventories; c++) result[c] = dt * inventoryForce[c];
		for (int f = 0; f < faces; f++) result[inventories + f] = dt * faceForce[f];
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
			action(direction, product, dt); for (int c = 0; c < inventories; c++) product[c] += capacity[c] * direction[c];
			double inertia = ConservativeConjugateDirections.dot(direction, product);
			if (!(Double.isFinite(inertia) && inertia > 0)) throw new IllegalStateException("Conduction correction has no positive energy");
			double alpha = norm / inertia;
			for (int i = 0; i < rhs.length; i++) if (free[i]) { result[i] += alpha * direction[i]; residual[i] -= alpha * product[i]; }
			mask(residual, free); previous = norm; restart = false;
			if (maximum(residual) <= tolerance) {
				action(result, product, dt); for (int c = 0; c < inventories; c++) product[c] += capacity[c] * result[c];
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

package cc.ecisr.mmm.thermal;

import cc.ecisr.mmm.units.SignedDyadic;
import java.util.Arrays;
import java.util.List;

/** Positive, conservative conduction on real caloric carriers, without an LTE redistribution. */
public final class ThermalCarrierTransport {
	/** second == -1 denotes a fixed-temperature external contact. */
	public record Contact(int first, int second, double conductance, double reservoirTemperatureK) {
		public Contact {
			if (first < 0 || second < -1 || first == second
					|| !(Double.isFinite(conductance) && conductance > 0))
				throw new IllegalArgumentException("Invalid carrier conduction contact");
			if (second < 0) new ThermalContactExchange.Reservoir(reservoirTemperatureK);
		}
	}

	private ThermalCarrierTransport() { }

	static ThermalTransport.Stats step(ThermalCellGrid.CarrierAccess owners, List<Contact> contacts,
	                                 double dt, int maximumSubsteps) {
		if (contacts == null || !(Double.isFinite(dt) && dt > 0))
			throw new IllegalArgumentException("Invalid carrier conduction input");
		int n = owners.carrierCount();
		var materials = new ThermalMaterialDefinition[n];
		double[] mass = new double[n], base = new double[n], energy = new double[n];
		double[] initialT = new double[n], temperature = new double[n], degree = new double[n];
		for (int p = 0; p < n; p++) {
			materials[p] = owners.material(p); mass[p] = owners.mass(p); base[p] = owners.baseEnergy(p);
			energy[p] = owners.internalEnergy(p);
			initialT[p] = new ThermalContactExchange.Carrier(materials[p], mass[p], base[p], energy[p]).temperatureK();
		}
		SignedDyadic before = SignedDyadic.sum(energy);
		for (Contact c : contacts) {
			if (c.first >= n || c.second >= n) throw new IllegalArgumentException("Unknown conduction owner");
			degree[c.first] += c.conductance;
			if (c.second >= 0) degree[c.second] += c.conductance;
		}
		double stableDt = Double.POSITIVE_INFINITY;
		for (int p = 0; p < n; p++) if (degree[p] > 0) {
			if (!Double.isFinite(degree[p])) throw new IllegalArgumentException("Non-finite conduction degree");
			double minCv = Double.POSITIVE_INFINITY;
			for (var segment : materials[p].caloricSegments()) minCv = Math.min(minCv, segment.specificHeatEuPerMuK());
			stableDt = Math.min(stableDt, 0.5 * mass[p] * minCv / degree[p]);
		}
		double required = Math.ceil(dt / stableDt);
		boolean implicit = required > maximumSubsteps;
		int substeps = implicit ? 1 : Math.max(1, (int) required);
		SignedDyadic reservoir = SignedDyadic.ZERO;
		double maximumTransfer = 0;
		double[] delta = new double[n];
		for (int step = 0; step < substeps; step++) {
			if (implicit) {
				// Symmetric contact splitting. Each nonlinear solve has a positive caloric interval.
				for (int sweep = 0; sweep < 2; sweep++) for (int i = 0; i < contacts.size(); i++) {
					Contact c = contacts.get(sweep == 0 ? i : contacts.size() - 1 - i);
					var first = new ThermalContactExchange.Carrier(materials[c.first], mass[c.first], base[c.first], energy[c.first]);
					ThermalContactExchange.Body second = c.second < 0
							? new ThermalContactExchange.Reservoir(c.reservoirTemperatureK)
							: new ThermalContactExchange.Carrier(materials[c.second], mass[c.second], base[c.second], energy[c.second]);
					double q = ThermalContactExchange.transfer(first, second, c.conductance, dt * 0.5);
					if (q == 0) continue;
					energy[c.first] += q;
					if (c.second >= 0) energy[c.second] -= q;
					else reservoir = reservoir.add(SignedDyadic.of(q));
					maximumTransfer = Math.max(maximumTransfer, Math.abs(q));
				}
			} else {
				for (int p = 0; p < n; p++) temperature[p] = materials[p].temperatureForSensibleSpecificEnergy((energy[p] - base[p]) / mass[p]);
				Arrays.fill(delta, 0);
				for (Contact c : contacts) {
					double otherT = c.second < 0 ? c.reservoirTemperatureK : temperature[c.second];
					double q = c.conductance * (otherT - temperature[c.first]) * (dt / substeps);
					if (!Double.isFinite(q)) throw new IllegalArgumentException("Non-finite conductive transfer");
					delta[c.first] += q;
					if (c.second >= 0) delta[c.second] -= q;
					else reservoir = reservoir.add(SignedDyadic.of(q));
					maximumTransfer = Math.max(maximumTransfer, Math.abs(q));
				}
				for (int p = 0; p < n; p++) if (delta[p] != 0) energy[p] += delta[p];
			}
		}
		double maximumChange = 0;
		for (int p = 0; p < n; p++) {
			double t = new ThermalContactExchange.Carrier(materials[p], mass[p], base[p], energy[p]).temperatureK();
			maximumChange = Math.max(maximumChange, Math.abs(t - initialT[p]));
		}
		SignedDyadic after = SignedDyadic.sum(energy);
		// All prospective caloric states pass before the first real owner is edited.
		for (int p = 0; p < n; p++) if (energy[p] != owners.internalEnergy(p)) owners.setInternalEnergy(p, energy[p]);
		return new ThermalTransport.Stats(substeps, contacts.size(), maximumChange, maximumTransfer,
				after.subtract(before).subtract(reservoir), reservoir);
	}
}

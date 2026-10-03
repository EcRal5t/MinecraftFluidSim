package cc.ecisr.mmm.thermal;

import cc.ecisr.mmm.units.SignedDyadic;
import cc.ecisr.mmm.compute.CpuWorkBatch;
import java.util.Arrays;
import java.util.List;

/** Positive, conservative conduction on real caloric carriers, without an LTE redistribution. */
public final class ThermalCarrierTransport {
	/** second == -1 denotes a fixed-temperature external contact. */
	public record Contact(int first, int second, double conductance, double reservoirTemperatureK) {
		public Contact { require(first, second, conductance, reservoirTemperatureK); }
		static void require(int first, int second, double conductance, double reservoirTemperatureK) {
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
		ThermalCarrierContacts graph = contacts instanceof ThermalCarrierContacts packed ? packed : new ThermalCarrierContacts(contacts);
		int n = owners.carrierCount();
		var materials = new ThermalMaterialDefinition[n];
		double[] mass = new double[n], base = new double[n], energy = new double[n];
		double[] initialT = new double[n], temperature = new double[n], degree = new double[n];
		CpuWorkBatch.run(n, (from, until) -> {
			for (int p = from; p < until; p++) {
				materials[p] = owners.material(p); mass[p] = owners.mass(p); base[p] = owners.baseEnergy(p);
				energy[p] = owners.internalEnergy(p);
				initialT[p] = new ThermalContactExchange.Carrier(materials[p], mass[p], base[p], energy[p]).temperatureK();
			}
		});
		SignedDyadic before = SignedDyadic.sum(energy);
		boolean exchangeRequired = false;
		for (int i = 0; i < graph.size(); i++) {
			int first = graph.first[i], second = graph.second[i]; double g = graph.conductance[i];
			if (first >= n || second >= n) throw new IllegalArgumentException("Unknown conduction owner");
			degree[first] += g;
			if (second >= 0) degree[second] += g;
			exchangeRequired |= initialT[first] != (second < 0 ? graph.reservoir[i] : initialT[second]);
		}
		double stableDt = Double.POSITIVE_INFINITY;
		for (int p = 0; p < n; p++) if (degree[p] > 0) {
			if (!Double.isFinite(degree[p])) throw new IllegalArgumentException("Non-finite conduction degree");
			double minCv = Double.POSITIVE_INFINITY;
			var segments = materials[p].caloricSegments();
			for (int segment = 0; segment < segments.size(); segment++)
				minCv = Math.min(minCv, segments.get(segment).specificHeatEuPerMuK());
			stableDt = Math.min(stableDt, 0.5 * mass[p] * minCv / degree[p]);
		}
		double required = exchangeRequired ? Math.ceil(dt / stableDt) : 1;
		boolean implicit = required > maximumSubsteps;
		int substeps = implicit ? 1 : Math.max(1, (int) required);
		SignedDyadic reservoir = SignedDyadic.ZERO;
		double maximumTransfer = 0;
		double[] delta = new double[n];
		for (int step = 0; step < substeps; step++) {
			if (implicit) {
				// Symmetric contact splitting. Each nonlinear solve has a positive caloric interval.
				for (int sweep = 0; sweep < 2; sweep++) for (int i = 0; i < graph.size(); i++) {
					int index = sweep == 0 ? i : graph.size() - 1 - i;
					int a = graph.first[index], b = graph.second[index];
					var first = new ThermalContactExchange.Carrier(materials[a], mass[a], base[a], energy[a]);
					ThermalContactExchange.Body second = b < 0
							? new ThermalContactExchange.Reservoir(graph.reservoir[index])
							: new ThermalContactExchange.Carrier(materials[b], mass[b], base[b], energy[b]);
					double q = ThermalContactExchange.transfer(first, second, graph.conductance[index], dt * 0.5);
					if (q == 0) continue;
					energy[a] += q;
					if (b >= 0) energy[b] -= q;
					else reservoir = reservoir.add(SignedDyadic.of(q));
					maximumTransfer = Math.max(maximumTransfer, Math.abs(q));
				}
			} else {
				if (step == 0) System.arraycopy(initialT, 0, temperature, 0, n);
				else CpuWorkBatch.run(n, (from, until) -> { for (int p = from; p < until; p++) { temperature[p] = materials[p].temperatureForSensibleSpecificEnergy((energy[p] - base[p]) / mass[p]); } });
				Arrays.fill(delta, 0);
				for (int i = 0; i < graph.size(); i++) {
					int first = graph.first[i], second = graph.second[i];
					double otherT = second < 0 ? graph.reservoir[i] : temperature[second];
					double q = graph.conductance[i] * (otherT - temperature[first]) * (dt / substeps);
					if (!Double.isFinite(q)) throw new IllegalArgumentException("Non-finite conductive transfer");
					delta[first] += q;
					if (second >= 0) delta[second] -= q;
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

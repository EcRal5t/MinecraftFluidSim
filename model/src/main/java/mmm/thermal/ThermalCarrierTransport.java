package mmm.thermal;

import mmm.units.SignedDyadic;
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
	/** One private carrier sample shared by spatial contacts, caloric solve and final publication. */
	public static final class Batch implements ThermalCellGrid.CarrierAccess {
		private final ThermalCellGrid.CarrierAccess owners;
		private final ThermalMaterialDefinition[] materials;
		private final double[] mass, base, energy, initialTemperature;
		private boolean publish;
		private Batch(ThermalCellGrid.CarrierAccess owners) {
			this.owners = owners;
			int count = owners.carrierCount();
			materials = new ThermalMaterialDefinition[count];
			mass = new double[count]; base = new double[count]; energy = new double[count];
			initialTemperature = new double[count];
			for (int owner = 0; owner < count; owner++) {
				materials[owner] = owners.material(owner); mass[owner] = owners.mass(owner);
				base[owner] = owners.baseEnergy(owner); energy[owner] = owners.internalEnergy(owner);
				initialTemperature[owner] = ThermalCaloricInversion.owner(materials[owner], mass[owner], energy[owner], base[owner]).temperatureK();
			}
		}
		@Override public int carrierCount() { return energy.length; }
		@Override public int cell(int owner) { return owners.cell(owner); }
		@Override public ThermalMaterialDefinition material(int owner) { return materials[owner]; }
		@Override public double mass(int owner) { return mass[owner]; }
		@Override public double volume(int owner) { return owners.volume(owner); }
		@Override public double baseEnergy(int owner) { return base[owner]; }
		@Override public double internalEnergy(int owner) { return energy[owner]; }
		@Override public void setInternalEnergy(int owner, double value) { throw new UnsupportedOperationException("Conduction batch is private"); }
		void commit() { if (publish) owners.setInternalEnergies(energy); }
	}
	public static Batch prepare(ThermalCellGrid.CarrierAccess owners) { return new Batch(owners); }

	static ThermalTransport.Stats step(ThermalCellGrid.CarrierAccess owners, List<Contact> contacts,
	                                 double dt, int maximumSubsteps) {
		var batch = prepare(owners);
		var result = solve(batch, contacts, dt, maximumSubsteps);
		batch.commit();
		return result;
	}
	static ThermalTransport.Stats solve(Batch batch, List<Contact> contacts, double dt, int maximumSubsteps) {
		if (contacts == null || !(Double.isFinite(dt) && dt > 0))
			throw new IllegalArgumentException("Invalid carrier conduction input");
		ThermalCarrierContacts graph = contacts instanceof ThermalCarrierContacts packed ? packed : new ThermalCarrierContacts(contacts);
		int n = batch.carrierCount();
		var materials = batch.materials;
		double[] mass = batch.mass, base = batch.base, energy = batch.energy;
		double[] initialT = batch.initialTemperature, degree = new double[n];
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
				minCv = Math.min(minCv, segments.get(segment).specificHeatJPerKgK());
			stableDt = Math.min(stableDt, 0.5 * mass[p] * minCv / degree[p]);
		}
		if (!exchangeRequired) return new ThermalTransport.Stats(1, contacts.size(), 0, 0,
				SignedDyadic.ZERO, SignedDyadic.ZERO);
		SignedDyadic before = SignedDyadic.sum(energy);
		double[] temperature = new double[n];
		double required = Math.ceil(dt / stableDt);
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
				else for (int p = 0; p < n; p++) temperature[p] = ThermalCaloricInversion.owner(materials[p], mass[p], energy[p], base[p]).temperatureK();
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
		batch.publish = true;
		return new ThermalTransport.Stats(substeps, contacts.size(), maximumChange, maximumTransfer,
				after.subtract(before).subtract(reservoir), reservoir);
	}
}

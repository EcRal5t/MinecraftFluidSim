package mmm.thermal;

import mmm.units.SignedDyadic;

import java.util.ArrayList;
import java.util.List;

/** Prepares the actual binary64 owner energies of one conserved LTE cell. */
final class ThermalCellEnergyAssignment {
	interface Inventory extends ThermalCaloricInversion.Inventory {
		double baseEnergy(int item);
		default SignedDyadic exactBaseEnergy(int item) { return SignedDyadic.of(baseEnergy(item)); }
	}
	record Entry(int item, ThermalMaterialDefinition material, double mass, double base, double energy) { }
	record Plan(List<Entry> entries, SignedDyadic actualEnergy, ThermalStateFunction.State actualState) { }
	static final class UnavailableException extends IllegalArgumentException {
		UnavailableException(String message, IllegalArgumentException cause) { super(message, cause); }
	}
	private ThermalCellEnergyAssignment() { }

	/** A common-temperature trial carries no owner publication or audit plan. */
	static final class Trial {
		final Inventory inventory;
		final SignedDyadic requested, reference;
		final ThermalCaloricInversion.Curve curve;
		final ThermalStateFunction.State state;
		private final SignedDyadic absoluteReference;
		private final int ownerCount;
		private double temperatureTolerance = Double.NaN;
		Trial(Inventory inventory, SignedDyadic requested) {
			this.inventory = inventory; this.requested = requested;
			SignedDyadic total = SignedDyadic.ZERO, absolute = SignedDyadic.ZERO;
			int owners = 0;
			for (int item = inventory.first(); item >= 0; item = inventory.next(item)) {
				SignedDyadic base = inventory.exactBaseEnergy(item);
				total = total.add(base);
				absolute = absolute.add(base.signum() < 0 ? SignedDyadic.ZERO.subtract(base) : base);
				owners++;
			}
			reference = total;
			curve = new ThermalCaloricInversion.Curve(inventory);
			state = curve.solve(requested.subtract(reference));
			absoluteReference = absolute; ownerCount = owners;
		}
		double temperatureTolerance() {
			if (Double.isNaN(temperatureTolerance)) temperatureTolerance = curve.assignmentTemperatureTolerance(
					requested, absoluteReference, ownerCount, state.temperatureK());
			return temperatureTolerance;
		}
	}

	static Plan prepare(Inventory inventory, SignedDyadic requested) {
		return prepare(new Trial(inventory, requested));
	}

	static Plan prepare(Trial trial) {
		Inventory inventory = trial.inventory;
		SignedDyadic requested = trial.requested;
		var requestedState = trial.state;
		double temperature = requestedState.temperatureK();
		ArrayList<Entry> modeled = new ArrayList<>();
		SignedDyadic modeledSum = SignedDyadic.ZERO;
		int preferred = -1, soleOverflow = -1;
		double largestCapacity = Double.NEGATIVE_INFINITY;
		for (int item = inventory.first(); item >= 0; item = inventory.next(item)) {
			ThermalMaterialDefinition material = inventory.material(item);
			double mass = inventory.mass(item), base = inventory.baseEnergy(item), energy;
			try {
				energy = inventory.exactBaseEnergy(item).add(ThermalContactExchange.exactEnergyChangeBetween(
						material, inventory.exactMass(item), material.referenceTemperatureK(), temperature)).toDouble();
				if (!Double.isFinite(energy)) throw new ThermalStateFunction.EnergyRepresentationException();
			} catch (ThermalStateFunction.EnergyRepresentationException overflow) {
				if (soleOverflow >= 0) throw new UnavailableException("More than one thermal carrier modeled energy is invalid", overflow);
				// Only the conservation pivot avoids storing its modeled energy.
				soleOverflow = modeled.size(); energy = 0.0;
			}
			double capacity = mass * material.specificHeatAt(temperature);
			if (capacity > largestCapacity) { largestCapacity = capacity; preferred = modeled.size(); }
			modeled.add(new Entry(item, material, mass, base, energy));
			modeledSum = modeledSum.add(SignedDyadic.of(energy));
		}
		if (modeled.isEmpty()) throw new IllegalArgumentException("Thermal assignment has no carriers");
		UnavailableException failure = null;
		if (soleOverflow >= 0) preferred = soleOverflow;
		for (int attempt = -1; attempt < (soleOverflow >= 0 ? 0 : modeled.size()); attempt++) {
			int pivot = attempt < 0 ? preferred : attempt;
			if (attempt >= 0 && pivot == preferred) continue;
			Entry entry = modeled.get(pivot);
			double remainder = requested.subtract(modeledSum.subtract(SignedDyadic.of(entry.energy()))).toDouble();
			try {
				if (!Double.isFinite(remainder)) throw new IllegalArgumentException("Thermal carrier energy remainder is invalid");
				if (pivot == soleOverflow || remainder != entry.energy()) {
					double restored = ThermalCaloricInversion.single(entry.material(), inventory.exactMass(entry.item()),
							SignedDyadic.of(remainder).subtract(inventory.exactBaseEnergy(entry.item()))).temperatureK();
					double tolerance = Math.max(1.0E-9, Math.max(Math.ulp(restored), Math.ulp(temperature)) * 16.0);
					if (Math.abs(restored - temperature) > tolerance) throw new IllegalArgumentException(
							"Thermal carrier remainder left local equilibrium (carrier=" + entry.item()
							+ ", cellTemperature=" + temperature + ", restoredTemperature=" + restored + ")");
				}
			} catch (IllegalArgumentException unavailable) {
				failure = new UnavailableException(unavailable.getMessage(), unavailable);
				continue;
			}
			try { return prepareActual(trial, modeled, pivot, remainder); }
			catch (UnavailableException unavailable) { failure = unavailable; }
		}
		throw failure;
	}

	private static Plan prepareActual(Trial trial, List<Entry> modeled, int pivot, double remainder) {
		Inventory inventory = trial.inventory;
		ArrayList<Entry> actual = new ArrayList<>(modeled.size());
		SignedDyadic actualSum = SignedDyadic.ZERO;
		for (int index = 0; index < modeled.size(); index++) {
			Entry original = modeled.get(index);
			double energy = index == pivot ? remainder : original.energy();
			try {
				ThermalCaloricInversion.single(original.material(), inventory.exactMass(original.item()),
						SignedDyadic.of(energy).subtract(inventory.exactBaseEnergy(original.item())));
			} catch (IllegalArgumentException invalid) {
				throw new UnavailableException("Thermal assigned carrier energy is invalid", invalid);
			}
			actual.add(index == pivot ? new Entry(original.item(), original.material(), original.mass(), original.base(), energy) : original);
			actualSum = actualSum.add(SignedDyadic.of(energy));
		}
		if (!Double.isFinite(actualSum.toDouble())) throw new UnavailableException("Thermal assigned cell energy is not finite", null);
		try {
			var state = actualSum.equals(trial.requested) ? trial.state : trial.curve.solve(actualSum.subtract(trial.reference));
			return new Plan(List.copyOf(actual), actualSum, state);
		} catch (IllegalArgumentException invalid) {
			throw new UnavailableException("Thermal assigned cell state is invalid", invalid);
		}
	}
}

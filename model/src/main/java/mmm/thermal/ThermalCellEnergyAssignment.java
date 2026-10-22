package mmm.thermal;

import mmm.units.SignedDyadic;

import java.util.ArrayList;
import java.util.List;

/** Prepares one floating LTE assignment and its exact stored-energy account. */
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
		final ThermalStateFunction.State state;
		final ThermalCaloricInversion.Prepared caloric;
		Trial(Inventory inventory, SignedDyadic requested) {
			this.inventory = inventory; this.requested = requested;
			SignedDyadic total = SignedDyadic.ZERO;
			for (int item = inventory.first(); item >= 0; item = inventory.next(item))
				total = total.add(inventory.exactBaseEnergy(item));
			reference = total;
			caloric=new ThermalCaloricInversion.Prepared(inventory);
			state = caloric.solve(requested.subtract(reference));
		}
	}

	static Plan prepare(Inventory inventory, SignedDyadic requested) {
		return prepare(new Trial(inventory, requested));
	}

	static Plan prepare(Trial trial) {
		try {
			Inventory inventory = trial.inventory;
			double temperature = trial.state.temperatureK();
			ArrayList<Entry> actual = new ArrayList<>();
			SignedDyadic modeledSum = SignedDyadic.ZERO;
			int pivot = -1;
			double largestCapacity = 0;
			for (int item = inventory.first(); item >= 0; item = inventory.next(item)) {
				var material = inventory.material(item);
				double mass = inventory.mass(item), base = inventory.baseEnergy(item);
				double energy = ThermalCaloricInversion.energyAt(material, mass, base, temperature);
				double capacity = mass * material.specificHeatAt(temperature);
				if (capacity > largestCapacity) { largestCapacity = capacity; pivot = actual.size(); }
				actual.add(new Entry(item, material, mass, base, energy));
				modeledSum = modeledSum.add(SignedDyadic.of(energy));
			}
			if (pivot < 0) throw new IllegalArgumentException("Thermal assignment has no positive heat capacity");
			Entry receiver = actual.get(pivot);
			SignedDyadic others = modeledSum.subtract(SignedDyadic.of(receiver.energy()));
			double remainder = trial.requested.subtract(others).toDouble();
			if (!Double.isFinite(remainder)) throw new ThermalStateFunction.EnergyRepresentationException();
			actual.set(pivot, new Entry(receiver.item(), receiver.material(), receiver.mass(), receiver.base(), remainder));
			// Stored carrier states retain finite positive temperatures after rounding.
			for (Entry entry : actual)
				ThermalCaloricInversion.owner(entry.material(), entry.mass(), entry.energy(), entry.base());
			SignedDyadic actualSum = others.add(SignedDyadic.of(remainder));
			if (!Double.isFinite(actualSum.toDouble())) throw new ThermalStateFunction.EnergyRepresentationException();
			var state = actualSum.equals(trial.requested) ? trial.state
					: trial.caloric.solve(actualSum.subtract(trial.reference));
			return new Plan(List.copyOf(actual), actualSum, state);
		} catch (IllegalArgumentException invalid) {
			throw new UnavailableException("Thermal assigned carrier state is invalid", invalid);
		}
	}
}

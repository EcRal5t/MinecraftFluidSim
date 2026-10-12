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

	static Plan prepare(Inventory inventory, SignedDyadic requested, double temperature) {
		ArrayList<Entry> modeled = new ArrayList<>();
		SignedDyadic modeledSum = SignedDyadic.ZERO, reference = SignedDyadic.ZERO;
		int preferred = -1, soleOverflow = -1;
		double largestCapacity = Double.NEGATIVE_INFINITY;
		for (int item = inventory.first(); item >= 0; item = inventory.next(item)) {
			ThermalMaterialDefinition material = inventory.material(item);
			double mass = inventory.mass(item), base = inventory.baseEnergy(item), energy;
			try { energy = ThermalCaloricInversion.energyAt(material, mass, base, temperature); }
			catch (ThermalStateFunction.EnergyRepresentationException overflow) {
				if (soleOverflow >= 0) throw new UnavailableException("More than one thermal carrier modeled energy is invalid", overflow);
				// Only the conservation pivot avoids storing its modeled energy.
				soleOverflow = modeled.size(); energy = 0.0;
			}
			double capacity = mass * material.specificHeatAt(temperature);
			if (capacity > largestCapacity) { largestCapacity = capacity; preferred = modeled.size(); }
			modeled.add(new Entry(item, material, mass, base, energy));
			modeledSum = modeledSum.add(SignedDyadic.of(energy));
			reference = reference.add(inventory.exactBaseEnergy(item));
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
					double restored = ThermalCaloricInversion.owner(entry.material(), entry.mass(), remainder, entry.base()).temperatureK();
					double tolerance = Math.max(1.0E-9, Math.max(Math.ulp(restored), Math.ulp(temperature)) * 16.0);
					if (Math.abs(restored - temperature) > tolerance) throw new IllegalArgumentException(
							"Thermal carrier remainder left local equilibrium (carrier=" + entry.item()
							+ ", cellTemperature=" + temperature + ", restoredTemperature=" + restored + ")");
				}
			} catch (IllegalArgumentException unavailable) {
				failure = new UnavailableException(unavailable.getMessage(), unavailable);
				continue;
			}
			try { return prepareActual(inventory, modeled, pivot, remainder, reference); }
			catch (UnavailableException unavailable) { failure = unavailable; }
		}
		throw failure;
	}

	private static Plan prepareActual(Inventory inventory, List<Entry> modeled, int pivot,
			double remainder, SignedDyadic reference) {
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
			var state = ThermalCaloricInversion.solve(inventory, actualSum.subtract(reference));
			return new Plan(List.copyOf(actual), actualSum, state);
		} catch (IllegalArgumentException invalid) {
			throw new UnavailableException("Thermal assigned cell state is invalid", invalid);
		}
	}
}

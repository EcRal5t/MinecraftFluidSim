package mmm.transfer;

import mmm.thermal.ThermalMaterialDefinition;
import mmm.thermal.ThermalRegistry;
import mmm.thermal.ThermalStateFunction;
import mmm.units.SignedDyadic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Equilibrated liquid-container temperature and energy partition derived from inventory plus total U. */
public final class LiquidContainerThermodynamics {
	private LiquidContainerThermodynamics() { }

	public static double temperatureK(
			ThermalRegistry registry, ThermalContents thermal, FluidMixture inventory
	) {
		requireInputs(registry, thermal);
		requireBinding(registry, thermal, ThermalContents.forMixture(
				registry.fingerprint(), inventory, thermal.internalEnergy()));
		return stateFunction(registry).stateForInventory(materialPortions(registry, inventory),
				thermal.internalEnergy()).temperatureK();
	}

	public static double temperatureK(
			ThermalRegistry registry, ThermalContents thermal, ChemicalContents inventory
	) {
		requireInputs(registry, thermal);
		requireChemicalBinding(registry, inventory);
		requireBinding(registry, thermal, ThermalContents.forChemical(
				registry.fingerprint(), inventory, thermal.internalEnergy()));
		return stateFunction(registry).stateForInventory(chemicalPortions(registry, inventory),
				thermal.internalEnergy()).temperatureK();
	}

	public static ThermalContents.EnergySplit split(
			ThermalRegistry registry, ThermalContents thermal, FluidMixture source,
			FluidMixture selected, FluidMixture remaining
	) {
		requireInputs(registry, thermal);
		requireBinding(registry, thermal, ThermalContents.forMixture(
				registry.fingerprint(), source, thermal.internalEnergy()));
		validateMaterialPartition(source, selected, remaining);
		return splitAtSourceTemperature(registry, thermal.internalEnergy(), materialPortions(registry, source),
				materialPortions(registry, selected), remaining == null ? null : materialPortions(registry, remaining));
	}

	public static ThermalContents.EnergySplit split(
			ThermalRegistry registry, ThermalContents thermal, ChemicalContents source,
			ChemicalContents selected, ChemicalContents remaining
	) {
		requireInputs(registry, thermal);
		requireChemicalBinding(registry, source);
		requireChemicalBinding(registry, selected);
		if (remaining != null) requireChemicalBinding(registry, remaining);
		requireBinding(registry, thermal, ThermalContents.forChemical(
				registry.fingerprint(), source, thermal.internalEnergy()));
		validateChemicalPartition(source, selected, remaining);
		return splitAtSourceTemperature(registry, thermal.internalEnergy(), chemicalPortions(registry, source),
				chemicalPortions(registry, selected), remaining == null ? null : chemicalPortions(registry, remaining));
	}

	private static ThermalContents.EnergySplit splitAtSourceTemperature(
			ThermalRegistry registry, double sourceEnergy,
			List<ThermalStateFunction.InventoryPortion> source,
			List<ThermalStateFunction.InventoryPortion> selected,
			List<ThermalStateFunction.InventoryPortion> remaining
	) {
		if (remaining == null) return new ThermalContents.EnergySplit(sourceEnergy, 0.0);
		ThermalStateFunction state = stateFunction(registry);
		double temperature = state.stateForInventory(source, sourceEnergy).temperatureK();
		double selectedCapacity = inventoryHeatCapacity(registry, selected, temperature);
		double remainingCapacity = inventoryHeatCapacity(registry, remaining, temperature);
		boolean selectedIsLarger = selectedCapacity >= remainingCapacity;
		double smaller = inventoryEnergy(state, selectedIsLarger ? remaining : selected, temperature);
		double larger = SignedDyadic.of(sourceEnergy).subtract(SignedDyadic.of(smaller)).toDouble();
		double selectedEnergy = selectedIsLarger ? larger : smaller;
		double remainingEnergy = selectedIsLarger ? smaller : larger;
		if (!Double.isFinite(selectedEnergy) || !Double.isFinite(remainingEnergy)) {
			throw new IllegalArgumentException("Container thermal split energy is not representable");
		}
		state.stateForInventory(selected, selectedEnergy);
		state.stateForInventory(remaining, remainingEnergy);
		return new ThermalContents.EnergySplit(selectedEnergy, remainingEnergy);
	}

	private static double inventoryHeatCapacity(ThermalRegistry registry,
			List<ThermalStateFunction.InventoryPortion> inventory, double temperature) {
		SignedDyadic total = SignedDyadic.ZERO;
		for (var portion : inventory) total = total.add(SignedDyadic.of(portion.massMu()
				* registry.material(portion.materialHandle()).specificHeatAt(temperature)));
		double capacity = total.toDouble();
		if (!(Double.isFinite(capacity) && capacity > 0))
			throw new IllegalArgumentException("Container thermal inventory heat capacity is invalid");
		return capacity;
	}

	private static double inventoryEnergy(
			ThermalStateFunction state, List<ThermalStateFunction.InventoryPortion> inventory, double temperature
	) {
		SignedDyadic exact = SignedDyadic.ZERO;
		for (ThermalStateFunction.InventoryPortion portion : inventory) {
			exact = exact.add(SignedDyadic.of(state.internalEnergyAtTemperature(
					portion.materialHandle(), portion.massMu(), portion.speciesQuanta(), temperature)));
		}
		double total = exact.toDouble();
		if (!Double.isFinite(total)) throw new IllegalArgumentException("Container thermal inventory energy overflow");
		return total;
	}

	private static List<ThermalStateFunction.InventoryPortion> materialPortions(
			ThermalRegistry registry, FluidMixture inventory
	) {
		ArrayList<ThermalStateFunction.InventoryPortion> result = new ArrayList<>(inventory.components().size());
		for (FluidMixture.Component component : inventory.components()) {
			result.add(new ThermalStateFunction.InventoryPortion(registry.materialHandle(
					ThermalMaterialDefinition.CarrierKind.LIQUID, component.materialId()), component.mass(), Map.of()));
		}
		return List.copyOf(result);
	}

	private static List<ThermalStateFunction.InventoryPortion> chemicalPortions(
			ThermalRegistry registry, ChemicalContents inventory
	) {
		ArrayList<ThermalStateFunction.InventoryPortion> result = new ArrayList<>(inventory.groups().size());
		for (ChemicalContents.Group group : inventory.groups()) {
			TreeMap<String, Long> species = new TreeMap<>();
			for (ChemicalContents.SpeciesAmount amount : group.species()) {
				species.put(amount.speciesId(), amount.amountQuanta());
			}
			result.add(new ThermalStateFunction.InventoryPortion(registry.materialHandle(
					ThermalMaterialDefinition.CarrierKind.LIQUID, group.materialId()),
					group.massKilograms(), species));
		}
		return List.copyOf(result);
	}

	private static void validateMaterialPartition(
			FluidMixture source, FluidMixture selected, FluidMixture remaining
	) {
		if (selected == null) throw new IllegalArgumentException("Selected material inventory is missing");
		if (remaining == null) {
			if (!selected.equals(source)) throw new IllegalArgumentException("Full material selection changed inventory");
			return;
		}
		Map<String, FluidMixture.Component> chosen = materialMap(selected);
		Map<String, FluidMixture.Component> left = materialMap(remaining);
		for (FluidMixture.Component component : source.components()) {
			FluidMixture.Component selectedPart = chosen.remove(component.materialId());
			FluidMixture.Component remainingPart = left.remove(component.materialId());
			validatePartition(component.volumeNanos(), component.mass(),
					selectedPart == null ? 0L : selectedPart.volumeNanos(),
					selectedPart == null ? 0.0 : selectedPart.mass(),
					remainingPart == null ? 0L : remainingPart.volumeNanos(),
					remainingPart == null ? 0.0 : remainingPart.mass());
		}
		if (!chosen.isEmpty() || !left.isEmpty()) throw new IllegalArgumentException("Material split added inventory");
	}

	private static void validateChemicalPartition(
			ChemicalContents source, ChemicalContents selected, ChemicalContents remaining
	) {
		if (selected == null) throw new IllegalArgumentException("Selected chemical inventory is missing");
		if (remaining == null) {
			if (!selected.equals(source)) throw new IllegalArgumentException("Full chemical selection changed inventory");
			return;
		}
		Map<String, ChemicalContents.Group> chosen = chemicalMap(selected);
		Map<String, ChemicalContents.Group> left = chemicalMap(remaining);
		for (ChemicalContents.Group group : source.groups()) {
			String key = chemicalKey(group);
			ChemicalContents.Group selectedPart = chosen.remove(key);
			ChemicalContents.Group remainingPart = left.remove(key);
			validatePartition(group.volumeNanos(), group.massKilograms(),
					selectedPart == null ? 0L : selectedPart.volumeNanos(),
					selectedPart == null ? 0.0 : selectedPart.massKilograms(),
					remainingPart == null ? 0L : remainingPart.volumeNanos(),
					remainingPart == null ? 0.0 : remainingPart.massKilograms());
			Map<String, Long> sourceSpecies = speciesMap(group);
			Map<String, Long> selectedSpecies = selectedPart == null ? Map.of() : speciesMap(selectedPart);
			Map<String, Long> remainingSpecies = remainingPart == null ? Map.of() : speciesMap(remainingPart);
			for (Map.Entry<String, Long> amount : sourceSpecies.entrySet()) {
				if (Math.addExact(selectedSpecies.getOrDefault(amount.getKey(), 0L),
						remainingSpecies.getOrDefault(amount.getKey(), 0L)) != amount.getValue()) {
					throw new IllegalArgumentException("Chemical split changed species inventory");
				}
			}
			if (!sourceSpecies.keySet().containsAll(selectedSpecies.keySet())
					|| !sourceSpecies.keySet().containsAll(remainingSpecies.keySet())) {
				throw new IllegalArgumentException("Chemical split added a species identity");
			}
		}
		if (!chosen.isEmpty() || !left.isEmpty()) throw new IllegalArgumentException("Chemical split added inventory");
	}

	private static void validatePartition(
			long sourceVolume, double sourceMass, long selectedVolume, double selectedMass,
			long remainingVolume, double remainingMass
	) {
		if (Math.addExact(selectedVolume, remainingVolume) != sourceVolume) {
			throw new IllegalArgumentException("Container split changed nanovolume inventory");
		}
		if (selectedVolume == 0L) {
			if (selectedMass != 0.0 || Double.doubleToRawLongBits(remainingMass)
					!= Double.doubleToRawLongBits(sourceMass)) {
				throw new IllegalArgumentException("Container split changed unsplit mass inventory");
			}
		} else if (remainingVolume == 0L) {
			if (remainingMass != 0.0 || Double.doubleToRawLongBits(selectedMass)
					!= Double.doubleToRawLongBits(sourceMass)) {
				throw new IllegalArgumentException("Container split changed fully selected mass inventory");
			}
		} else if (Double.doubleToRawLongBits(sourceMass - selectedMass)
				!= Double.doubleToRawLongBits(remainingMass)) {
			throw new IllegalArgumentException("Container split did not preserve subtraction-settled mass");
		}
	}

	private static Map<String, FluidMixture.Component> materialMap(FluidMixture mixture) {
		HashMap<String, FluidMixture.Component> result = new HashMap<>();
		for (FluidMixture.Component component : mixture.components()) result.put(component.materialId(), component);
		return result;
	}

	private static Map<String, ChemicalContents.Group> chemicalMap(ChemicalContents contents) {
		HashMap<String, ChemicalContents.Group> result = new HashMap<>();
		for (ChemicalContents.Group group : contents.groups()) result.put(chemicalKey(group), group);
		return result;
	}

	private static Map<String, Long> speciesMap(ChemicalContents.Group group) {
		HashMap<String, Long> result = new HashMap<>();
		for (ChemicalContents.SpeciesAmount amount : group.species()) {
			result.put(amount.speciesId(), amount.amountQuanta());
		}
		return result;
	}

	private static String chemicalKey(ChemicalContents.Group group) {
		return group.materialId() + '\0' + group.indicator();
	}

	private static void requireBinding(
			ThermalRegistry registry, ThermalContents actual, ThermalContents expected
	) {
		actual.validateRegistryFingerprint(registry.fingerprint());
		if (actual.underlyingKind() != expected.underlyingKind()
				|| !actual.underlyingHash().equals(expected.underlyingHash())) {
			throw new IllegalArgumentException("Container thermal truth does not match its liquid inventory");
		}
	}

	private static void requireInputs(ThermalRegistry registry, ThermalContents thermal) {
		if (registry == null || thermal == null) {
			throw new IllegalArgumentException("Container thermal truth is missing");
		}
	}

	private static void requireChemicalBinding(ThermalRegistry registry, ChemicalContents contents) {
		if (contents == null || !contents.registryFingerprint().equals(registry.chemicalRegistryFingerprint())) {
			throw new IllegalArgumentException("Container chemistry is not bound to the thermal registry");
		}
	}

	private static ThermalStateFunction stateFunction(ThermalRegistry registry) {
		if (registry == null) throw new IllegalArgumentException("Container thermal registry is missing");
		return new ThermalStateFunction(registry);
	}
}

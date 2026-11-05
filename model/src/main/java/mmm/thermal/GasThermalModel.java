package mmm.thermal;

import mmm.fluid.air.AirNetwork;
import mmm.fluid.air.GasComponentAmount;
import mmm.fluid.air.GasInventory;
import mmm.fluid.air.GasRegistry;
import mmm.units.SignedDyadic;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Non-isothermal local gas state functions; inventory and U are authoritative. */
public final class GasThermalModel {
	private final ThermalRegistry thermalRegistry;
	private final GasRegistry gasRegistry;
	private final ThermalStateFunction stateFunction;

	public GasThermalModel(ThermalRegistry thermalRegistry, GasRegistry gasRegistry) {
		if (thermalRegistry == null || gasRegistry == null) throw new IllegalArgumentException("Gas thermal registries are missing");
		this.thermalRegistry = thermalRegistry; this.gasRegistry = gasRegistry;
		this.stateFunction = new ThermalStateFunction(thermalRegistry);
	}

	ThermalRegistry thermalRegistry() { return thermalRegistry; }

	/** Create a local gas inventory for the common bounded EOS at a specified state. */
	public GasInventory inventoryAtPressure(double volume,double pressure,double temperature,List<GasRegistry.AmbientFraction> recipe) {
		if (!(Double.isFinite(volume)&&volume>=0&&Double.isFinite(pressure)&&pressure>=0
				&&pressure<MixtureVolumeModel.PRESSURE_LIMIT_PA&&Double.isFinite(temperature)&&temperature>0))
			throw new IllegalArgumentException("Gas source pressure/temperature is invalid");
		double standard=volume*pressure*MixtureVolumeModel.LIQUID_BULK_PA
				/(MixtureVolumeModel.PRESSURE_LIMIT_PA-pressure)/GasRegistry.STANDARD_PRESSURE_PA
				*GasRegistry.STANDARD_TEMPERATURE_K/temperature;
		return gasRegistry.createMixture(AirNetwork.nanos(standard),recipe);
	}

	public double internalEnergyAtTemperature(GasInventory inventory, double temperatureK) {
		if (inventory == null) throw new IllegalArgumentException("Gas inventory is missing");
		if (inventory.isEmpty()) {
			if (!Double.isFinite(temperatureK)) throw new IllegalArgumentException("Vacuum temperature is not finite");
			return 0.0;
		}
		return stateFunction.inventoryEnergyAtTemperature(portions(inventory), temperatureK);
	}

	public ThermalStateFunction.State state(GasInventory inventory, double internalEnergy) {
		return stateFunction.stateForInventory(portions(inventory), internalEnergy);
	}

	/** Validates the complete authoritative inventory/U pair, including explicit vacuum. */
	public void requireInventoryEnergy(GasInventory inventory, double internalEnergy) {
		if (inventory == null) throw new IllegalArgumentException("Gas inventory is missing");
		if (inventory.isEmpty()) {
			if (internalEnergy != 0.0) throw new IllegalArgumentException("Vacuum cannot carry internal energy");
			return;
		}
		state(inventory, internalEnergy);
	}

	public double absolutePressure(GasInventory inventory, double availableVolume, double internalEnergy) {
		requireAvailableVolume(availableVolume, inventory);
		if (inventory == null) throw new IllegalArgumentException("Gas inventory is missing");
		if (inventory.isEmpty()) {
			if (internalEnergy != 0.0) throw new IllegalArgumentException("Vacuum cannot carry internal energy");
			return 0.0;
		}
		ThermalStateFunction.State thermal = state(inventory, internalEnergy);
		return absolutePressure(inventory, availableVolume, thermal);
	}

	double absolutePressure(GasInventory inventory, double availableVolume, ThermalStateFunction.State thermal) {
		requireAvailableVolume(availableVolume, inventory);
		double standardVolume = AirNetwork.cubicMeters(inventory.standardVolumeNanos());
		double gasConstant = GasRegistry.STANDARD_PRESSURE_PA * standardVolume
				/ GasRegistry.STANDARD_TEMPERATURE_K;
		double pressure = MixtureVolumeModel.gasPressure(gasConstant, thermal.temperatureK(), availableVolume);
		if (!(Double.isFinite(pressure) && pressure >= 0.0)) {
			throw new ThermalStateFunction.EnergyRepresentationException("Gas pressure is outside the thermal model");
		}
		return pressure;
	}

	private double heatCapacityAt(
			List<ThermalStateFunction.InventoryPortion> portions, double temperatureK
	) {
		double heatCapacity = 0.0;
		for (ThermalStateFunction.InventoryPortion portion : portions) {
			heatCapacity += portion.massKg()
					* thermalRegistry.material(portion.materialHandle()).specificHeatAt(temperatureK);
		}
		if (!(Double.isFinite(heatCapacity) && heatCapacity > 0.0)) {
			throw new IllegalArgumentException("Gas heat capacity is invalid");
		}
		return heatCapacity;
	}

	/** Splits node U at its common temperature; the largest heat capacity absorbs the reduction error. */
	public double[] partitionEnergy(GasInventory source, double internalEnergy, List<GasInventory> children) {
		if (children == null || children.isEmpty()) throw new IllegalArgumentException("Gas energy partition needs children");
		if (source == null) throw new IllegalArgumentException("Gas inventory is missing");
		if (source.isEmpty()) {
			if (internalEnergy != 0.0 || children.stream().anyMatch(child -> child == null || !child.isEmpty())) {
				throw new IllegalArgumentException("Vacuum energy partition is invalid");
			}
			return new double[children.size()];
		}
		double temperature = state(source, internalEnergy).temperatureK();
		long childNanos = 0L;
		for (GasInventory child : children) childNanos = Math.addExact(childNanos, child.standardVolumeNanos());
		if (childNanos != source.standardVolumeNanos()) throw new IllegalArgumentException("Gas energy partition volume does not close");
		double[] result = new double[children.size()];
		int pivot = -1; double largestCapacity = 0.0;
		for (int index = 0; index < children.size(); index++) {
			GasInventory child = children.get(index);
			if (child.isEmpty()) continue;
			result[index] = internalEnergyAtTemperature(child, temperature);
			double capacity = heatCapacityAt(portions(child), temperature);
			if (capacity > largestCapacity) { pivot = index; largestCapacity = capacity; }
		}
		SignedDyadic otherEnergy = SignedDyadic.sum(result).subtract(SignedDyadic.of(result[pivot]));
		result[pivot] = SignedDyadic.of(internalEnergy).subtract(otherEnergy).toDouble();
		for (int index = 0; index < children.size(); index++) requireInventoryEnergy(children.get(index), result[index]);
		return result;
	}

	private List<ThermalStateFunction.InventoryPortion> portions(GasInventory inventory) {
		if (inventory == null || inventory.isEmpty()) throw new IllegalArgumentException("Gas thermal inventory is empty");
		ArrayList<ThermalStateFunction.InventoryPortion> portions = new ArrayList<>(inventory.components().size());
		for (GasComponentAmount component : inventory.components()) {
			if (!gasRegistry.hasComponent(component.componentId())) {
				throw new IllegalArgumentException("Unknown gas component " + component.componentId());
			}
			int handle = thermalRegistry.materialHandle(ThermalMaterialDefinition.CarrierKind.GAS, component.componentId());
			TreeMap<String, Long> species = new TreeMap<>();
			for (GasComponentAmount.SpeciesAmount amount : component.species()) species.put(amount.speciesId(), amount.amountQuanta());
			portions.add(new ThermalStateFunction.InventoryPortion(handle, component.mass(), Map.copyOf(species)));
		}
		return List.copyOf(portions);
	}

	private static void requireAvailableVolume(double availableVolume, GasInventory inventory) {
		if (!(Double.isFinite(availableVolume) && (availableVolume > 0.0 || availableVolume == 0.0 && inventory != null && inventory.isEmpty()))) {
			throw new IllegalArgumentException("Gas available volume is outside the thermal model");
		}
	}

}

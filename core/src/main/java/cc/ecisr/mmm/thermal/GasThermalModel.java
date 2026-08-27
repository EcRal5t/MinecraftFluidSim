package cc.ecisr.mmm.thermal;

import cc.ecisr.mmm.fluid.air.AirNetwork;
import cc.ecisr.mmm.fluid.air.GasComponentAmount;
import cc.ecisr.mmm.fluid.air.GasInventory;
import cc.ecisr.mmm.fluid.air.GasRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Pure non-isothermal ideal-gas state functions; node U remains the authoritative input. */
public final class GasThermalModel {
	public record Compression(double initialTemperatureK, double finalTemperatureK,
	                          double finalInternalEnergy, double workIntoGas) { }
	public record Outflow(double initialTemperatureK, double finalTemperatureK,
	                      double finalInternalEnergy, double carriedEnergy) { }

	private final ThermalRegistry thermalRegistry;
	private final GasRegistry gasRegistry;
	private final ThermalStateFunction stateFunction;

	public GasThermalModel(ThermalRegistry thermalRegistry, GasRegistry gasRegistry) {
		if (thermalRegistry == null || gasRegistry == null) throw new IllegalArgumentException("Gas thermal registries are missing");
		this.thermalRegistry = thermalRegistry; this.gasRegistry = gasRegistry;
		this.stateFunction = new ThermalStateFunction(thermalRegistry);
	}

	public double internalEnergyAtTemperature(GasInventory inventory, double temperatureK) {
		List<ThermalStateFunction.InventoryPortion> portions = portions(inventory);
		double energy = 0.0;
		for (ThermalStateFunction.InventoryPortion portion : portions) {
			energy += stateFunction.internalEnergyAtTemperature(
					portion.materialHandle(), portion.massMu(), portion.speciesQuanta(), temperatureK);
		}
		if (!Double.isFinite(energy)) throw new IllegalArgumentException("Gas internal energy is not finite");
		return energy;
	}

	public ThermalStateFunction.State state(GasInventory inventory, double internalEnergy) {
		return stateFunction.stateForInventory(portions(inventory), internalEnergy);
	}

	public double absolutePressure(GasInventory inventory, double availableVolume, double internalEnergy) {
		if (!(Double.isFinite(availableVolume) && availableVolume > 0.0)) {
			throw new IllegalArgumentException("Gas available volume is outside the thermal model");
		}
		double temperature = state(inventory, internalEnergy).temperatureK();
		double pressure = GasRegistry.STANDARD_PRESSURE_PA
				* (AirNetwork.cubicMeters(inventory.standardVolumeNanos()) / availableVolume)
				* (temperature / GasRegistry.STANDARD_TEMPERATURE_K);
		double minimum = GasRegistry.STANDARD_PRESSURE_PA + AirNetwork.MIN_GAUGE_PRESSURE;
		double maximum = GasRegistry.STANDARD_PRESSURE_PA + AirNetwork.MAX_GAUGE_PRESSURE;
		if (!(Double.isFinite(pressure) && pressure >= minimum && pressure <= maximum)) {
			throw new IllegalArgumentException("Gas pressure is outside the reviewed validity window");
		}
		return pressure;
	}

	public double density(GasInventory inventory, double availableVolume) {
		if (!(Double.isFinite(availableVolume) && availableVolume > 0.0)) {
			throw new IllegalArgumentException("Gas available volume is outside the thermal model");
		}
		double mass = 0.0;
		for (GasComponentAmount component : inventory.components()) mass += component.mass();
		double density = mass / availableVolume;
		if (!(Double.isFinite(density) && density > 0.0)) {
			throw new IllegalArgumentException("Gas density is outside the thermal model");
		}
		return density;
	}

	/** Exact constant-cv ideal-gas isentrope. Crossing a caloric segment is a validity failure. */
	public Compression compressAdiabatically(
			GasInventory inventory, double internalEnergy, double initialAvailableVolume, double finalAvailableVolume
	) {
		if (!(Double.isFinite(initialAvailableVolume) && initialAvailableVolume > 0.0
				&& Double.isFinite(finalAvailableVolume) && finalAvailableVolume > 0.0)) {
			throw new IllegalArgumentException("Gas compression volumes are invalid");
		}
		ThermalStateFunction.State initial = state(inventory, internalEnergy);
		double gasConstant = GasRegistry.STANDARD_PRESSURE_PA
				* AirNetwork.cubicMeters(inventory.standardVolumeNanos()) / GasRegistry.STANDARD_TEMPERATURE_K;
		double exponent = gasConstant / initial.heatCapacityEuPerK();
		if (!(Double.isFinite(exponent) && exponent > 0.0)) throw new IllegalArgumentException("Gas isentropic exponent is invalid");
		double targetTemperature = initial.temperatureK()
				* Math.pow(initialAvailableVolume / finalAvailableVolume, exponent);
		for (ThermalStateFunction.InventoryPortion portion : portions(inventory)) {
			ThermalMaterialDefinition material = thermalRegistry.material(portion.materialHandle());
			if (Double.doubleToRawLongBits(material.specificHeatAt(initial.temperatureK()))
					!= Double.doubleToRawLongBits(material.specificHeatAt(targetTemperature))) {
				throw new IllegalArgumentException("Gas compression crosses a caloric segment");
			}
		}
		double finalEnergy = internalEnergyAtTemperature(inventory, targetTemperature);
		absolutePressure(inventory, finalAvailableVolume, finalEnergy);
		return new Compression(initial.temperatureK(), targetTemperature, finalEnergy, finalEnergy - internalEnergy);
	}

	/** Source-state enthalpy carried by an orifice portion: h = u + p*v = u + P0*Vstd*T/T0. */
	public double enthalpy(GasInventory moved, double sourceTemperatureK) {
		double result = internalEnergyAtTemperature(moved, sourceTemperatureK)
				+ GasRegistry.STANDARD_PRESSURE_PA * AirNetwork.cubicMeters(moved.standardVolumeNanos())
				* sourceTemperatureK / GasRegistry.STANDARD_TEMPERATURE_K;
		if (!Double.isFinite(result)) throw new IllegalArgumentException("Gas flow enthalpy is not finite");
		return result;
	}

	/** Exact constant-cv blowdown of one well-mixed rigid node for a finite inventory take. */
	public Outflow adiabaticOutflow(
			GasInventory sourceBefore, double internalEnergy, GasInventory sourceAfter
	) {
		if (sourceBefore == null || sourceAfter == null || sourceBefore.isEmpty()
				|| sourceAfter.isEmpty()
				|| sourceAfter.standardVolumeNanos() >= sourceBefore.standardVolumeNanos()) {
			throw new IllegalArgumentException("Gas outflow inventories are invalid");
		}
		ThermalStateFunction.State initial = state(sourceBefore, internalEnergy);
		double gasConstant = GasRegistry.STANDARD_PRESSURE_PA
				* AirNetwork.cubicMeters(sourceBefore.standardVolumeNanos())
				/ GasRegistry.STANDARD_TEMPERATURE_K;
		double exponent = gasConstant / initial.heatCapacityEuPerK();
		if (!(Double.isFinite(exponent) && exponent > 0.0)) {
			throw new IllegalArgumentException("Gas outflow isentropic exponent is invalid");
		}
		double retainedFraction = (double) sourceAfter.standardVolumeNanos()
				/ sourceBefore.standardVolumeNanos();
		double targetTemperature = initial.temperatureK() * Math.pow(retainedFraction, exponent);
		for (ThermalStateFunction.InventoryPortion portion : portions(sourceAfter)) {
			ThermalMaterialDefinition material = thermalRegistry.material(portion.materialHandle());
			if (Double.doubleToRawLongBits(material.specificHeatAt(initial.temperatureK()))
					!= Double.doubleToRawLongBits(material.specificHeatAt(targetTemperature))) {
				throw new IllegalArgumentException("Gas outflow crosses a caloric segment");
			}
		}
		double finalEnergy = internalEnergyAtTemperature(sourceAfter, targetTemperature);
		double carried = internalEnergy - finalEnergy;
		if (!(Double.isFinite(carried) && carried > 0.0)) {
			throw new IllegalArgumentException("Gas outflow carried energy is invalid");
		}
		return new Outflow(initial.temperatureK(), targetTemperature, finalEnergy, carried);
	}

	/** Splits node U at its derived common temperature; the final child receives raw floating remainder. */
	public double[] partitionEnergy(GasInventory source, double internalEnergy, List<GasInventory> children) {
		if (children == null || children.isEmpty()) throw new IllegalArgumentException("Gas energy partition needs children");
		double temperature = state(source, internalEnergy).temperatureK();
		long childNanos = 0L;
		for (GasInventory child : children) childNanos = Math.addExact(childNanos, child.standardVolumeNanos());
		if (childNanos != source.standardVolumeNanos()) throw new IllegalArgumentException("Gas energy partition volume does not close");
		double[] result = new double[children.size()]; double assigned = 0.0;
		int last = -1;
		for (int index = children.size() - 1; index >= 0; index--) if (!children.get(index).isEmpty()) { last = index; break; }
		for (int index = 0; index < children.size(); index++) {
			GasInventory child = children.get(index);
			if (child.isEmpty()) continue;
			result[index] = index == last ? internalEnergy - assigned : internalEnergyAtTemperature(child, temperature);
			if (index != last) assigned += result[index];
			state(child, result[index]);
		}
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
}

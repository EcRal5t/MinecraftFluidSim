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

	/**
	 * Local ideal-gas closure for a non-vacuum control volume.  The acoustic quantities are
	 * derivatives of the same inventory/U equation of state as pressure; they are not solver
	 * tuning parameters.  Vacuum deliberately has no instance of this record because it has no
	 * continuum pressure unknown.
	 */
	public record HydrodynamicState(
			double temperatureK,
			double absolutePressurePa,
			double densityKgPerCubicMeter,
			double heatCapacityEuPerK,
			double gasConstantEuPerK,
			double isentropicExponent,
			double adiabaticBulkModulusPa,
			double soundSpeedMetersPerSecond,
			double pressureCompliancePerPa
	) {
		public HydrodynamicState {
			if (!(Double.isFinite(temperatureK) && temperatureK > 0.0
					&& Double.isFinite(absolutePressurePa) && absolutePressurePa > 0.0
					&& Double.isFinite(densityKgPerCubicMeter) && densityKgPerCubicMeter > 0.0
					&& Double.isFinite(heatCapacityEuPerK) && heatCapacityEuPerK > 0.0
					&& Double.isFinite(gasConstantEuPerK) && gasConstantEuPerK > 0.0
					&& Double.isFinite(isentropicExponent) && isentropicExponent > 1.0
					&& Double.isFinite(adiabaticBulkModulusPa) && adiabaticBulkModulusPa > 0.0
					&& Double.isFinite(soundSpeedMetersPerSecond) && soundSpeedMetersPerSecond > 0.0
					&& Double.isFinite(pressureCompliancePerPa) && pressureCompliancePerPa > 0.0)) {
				throw new IllegalArgumentException("Gas hydrodynamic state is invalid");
			}
		}
	}

	private final ThermalRegistry thermalRegistry;
	private final GasRegistry gasRegistry;
	private final ThermalStateFunction stateFunction;

	public GasThermalModel(ThermalRegistry thermalRegistry, GasRegistry gasRegistry) {
		if (thermalRegistry == null || gasRegistry == null) throw new IllegalArgumentException("Gas thermal registries are missing");
		this.thermalRegistry = thermalRegistry; this.gasRegistry = gasRegistry;
		this.stateFunction = new ThermalStateFunction(thermalRegistry);
	}

	public double internalEnergyAtTemperature(GasInventory inventory, double temperatureK) {
		if (inventory == null) throw new IllegalArgumentException("Gas inventory is missing");
		if (inventory.isEmpty()) {
			if (!Double.isFinite(temperatureK)) throw new IllegalArgumentException("Vacuum temperature is not finite");
			return 0.0;
		}
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

	/** Derives the thermodynamic and acoustic closure used by a compressible pressure solve. */
	public HydrodynamicState hydrodynamicState(
			GasInventory inventory, double availableVolume, double internalEnergy
	) {
		requireAvailableVolume(availableVolume);
		if (inventory == null) throw new IllegalArgumentException("Gas inventory is missing");
		if (inventory.isEmpty()) {
			if (internalEnergy != 0.0) throw new IllegalArgumentException("Vacuum cannot carry internal energy");
			throw new IllegalArgumentException("Vacuum has no continuum hydrodynamic state");
		}
		ThermalStateFunction.State thermal = state(inventory, internalEnergy);
		double standardVolume = AirNetwork.cubicMeters(inventory.standardVolumeNanos());
		double gasConstant = GasRegistry.STANDARD_PRESSURE_PA * standardVolume
				/ GasRegistry.STANDARD_TEMPERATURE_K;
		double pressure = gasConstant * thermal.temperatureK() / availableVolume;
		double density = requirePositiveMass(inventory) / availableVolume;
		double exponent = 1.0 + gasConstant / thermal.heatCapacityEuPerK();
		double bulkModulus = exponent * pressure;
		double soundSpeed = Math.sqrt(bulkModulus / density);
		double compliance = 1.0 / bulkModulus;
		return new HydrodynamicState(thermal.temperatureK(), pressure, density,
				thermal.heatCapacityEuPerK(), gasConstant, exponent, bulkModulus,
				soundSpeed, compliance);
	}

	public double absolutePressure(GasInventory inventory, double availableVolume, double internalEnergy) {
		requireAvailableVolume(availableVolume);
		if (inventory == null) throw new IllegalArgumentException("Gas inventory is missing");
		if (inventory.isEmpty()) {
			if (internalEnergy != 0.0) throw new IllegalArgumentException("Vacuum cannot carry internal energy");
			return 0.0;
		}
		return hydrodynamicState(inventory, availableVolume, internalEnergy).absolutePressurePa();
	}

	public double density(GasInventory inventory, double availableVolume) {
		requireAvailableVolume(availableVolume);
		if (inventory == null) throw new IllegalArgumentException("Gas inventory is missing");
		if (inventory.isEmpty()) return 0.0;
		double density = requirePositiveMass(inventory) / availableVolume;
		if (!(Double.isFinite(density) && density > 0.0)) {
			throw new IllegalArgumentException("Gas density is outside the thermal model");
		}
		return density;
	}

	/** Exact ideal-gas isentrope integrated across the material-owned piecewise-constant caloric model. */
	public Compression compressAdiabatically(
			GasInventory inventory, double internalEnergy, double initialAvailableVolume, double finalAvailableVolume
	) {
		if (!(Double.isFinite(initialAvailableVolume) && initialAvailableVolume > 0.0
				&& Double.isFinite(finalAvailableVolume) && finalAvailableVolume > 0.0)) {
			throw new IllegalArgumentException("Gas compression volumes are invalid");
		}
		if (inventory == null) throw new IllegalArgumentException("Gas inventory is missing");
		if (inventory.isEmpty()) {
			if (internalEnergy != 0.0) throw new IllegalArgumentException("Vacuum cannot carry internal energy");
			return new Compression(GasRegistry.STANDARD_TEMPERATURE_K,
					GasRegistry.STANDARD_TEMPERATURE_K, 0.0, 0.0);
		}
		HydrodynamicState initial = hydrodynamicState(inventory, initialAvailableVolume, internalEnergy);
		List<ThermalStateFunction.InventoryPortion> portions = portions(inventory);
		double entropyChange = initial.gasConstantEuPerK()
				* Math.log(initialAvailableVolume / finalAvailableVolume);
		double targetTemperature;
		try {
			targetTemperature = isentropicTemperature(
					portions, initial.temperatureK(), entropyChange);
		} catch (CaloricRangeException outside) {
			throw new IllegalArgumentException("Gas isentrope leaves the positive-temperature domain: "
					+ "T0=" + initial.temperatureK() + " K, V0=" + initialAvailableVolume
					+ " m^3, V1=" + finalAvailableVolume + " m^3, Vstd="
					+ AirNetwork.cubicMeters(inventory.standardVolumeNanos()) + " m^3std, components="
					+ inventory.components().stream().map(GasComponentAmount::componentId).toList()
					+ ", remaining |dS|="
					+ outside.remainingEntropyEuPerK + " EU/K", outside);
		}
		double finalEnergy = internalEnergyAtTemperature(inventory, targetTemperature);
		hydrodynamicState(inventory, finalAvailableVolume, finalEnergy);
		return new Compression(initial.temperatureK(), targetTemperature, finalEnergy, finalEnergy - internalEnergy);
	}

	private double isentropicTemperature(
			List<ThermalStateFunction.InventoryPortion> portions,
			double initialTemperatureK,
			double entropyChangeEuPerK
	) {
		if (entropyChangeEuPerK == 0.0) return initialTemperatureK;
		boolean heating = entropyChangeEuPerK > 0.0;
		double remaining = Math.abs(entropyChangeEuPerK);
		double temperature = initialTemperatureK;
		while (remaining > 0.0) {
			double boundary = heating
					? nextUpperCaloricBoundary(portions, temperature)
					: nextLowerCaloricBoundary(portions, temperature);
			if (heating && boundary == Double.POSITIVE_INFINITY) {
				double heatCapacity = heatCapacityAt(portions, temperature);
				double result = temperature * Math.exp(remaining / heatCapacity);
				if (!(Double.isFinite(result) && result > temperature)) {
					throw new CaloricRangeException(remaining);
				}
				return result;
			}
			if (!(heating ? boundary > temperature : boundary < temperature)) {
				throw new CaloricRangeException(remaining);
			}
			double sampleTemperature = Math.sqrt(temperature * boundary);
			double heatCapacity = heatCapacityAt(portions, sampleTemperature);
			double intervalEntropy = heatCapacity
					* Math.abs(Math.log(boundary) - Math.log(temperature));
			if (remaining <= intervalEntropy) {
				double signed = (heating ? 1.0 : -1.0) * remaining / heatCapacity;
				double result = temperature * Math.exp(signed);
				return heating ? Math.max(temperature, Math.min(boundary, result))
						: Math.max(boundary, Math.min(temperature, result));
			}
			remaining -= intervalEntropy;
			temperature = boundary;
		}
		return temperature;
	}

	private static final class CaloricRangeException extends IllegalArgumentException {
		private final double remainingEntropyEuPerK;

		private CaloricRangeException(double remainingEntropyEuPerK) {
			this.remainingEntropyEuPerK = remainingEntropyEuPerK;
		}
	}

	private double nextUpperCaloricBoundary(
			List<ThermalStateFunction.InventoryPortion> portions, double temperatureK
	) {
		double boundary = Double.POSITIVE_INFINITY;
		for (ThermalStateFunction.InventoryPortion portion : portions) {
			ThermalMaterialDefinition material = thermalRegistry.material(portion.materialHandle());
			double candidate = Double.POSITIVE_INFINITY;
			for (ThermalMaterialDefinition.CaloricSegment segment
					: material.caloricSegments()) {
				if (segment.maximumTemperatureK() > temperatureK) {
					candidate = segment.maximumTemperatureK();
					break;
				}
			}
			boundary = Math.min(boundary, candidate);
		}
		return boundary;
	}

	private double nextLowerCaloricBoundary(
			List<ThermalStateFunction.InventoryPortion> portions, double temperatureK
	) {
		double boundary = Double.NEGATIVE_INFINITY;
		for (ThermalStateFunction.InventoryPortion portion : portions) {
			ThermalMaterialDefinition material = thermalRegistry.material(portion.materialHandle());
			List<ThermalMaterialDefinition.CaloricSegment> segments = material.caloricSegments();
			double candidate = ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K;
			for (int index = segments.size() - 1; index >= 0; index--) {
				ThermalMaterialDefinition.CaloricSegment segment = segments.get(index);
				if (segment.minimumTemperatureK() < temperatureK) {
					candidate = segment.minimumTemperatureK();
					break;
				}
			}
			boundary = Math.max(boundary, candidate);
		}
		return boundary;
	}

	private double heatCapacityAt(
			List<ThermalStateFunction.InventoryPortion> portions, double temperatureK
	) {
		double heatCapacity = 0.0;
		for (ThermalStateFunction.InventoryPortion portion : portions) {
			heatCapacity += portion.massMu()
					* thermalRegistry.material(portion.materialHandle()).specificHeatAt(temperatureK);
		}
		if (!(Double.isFinite(heatCapacity) && heatCapacity > 0.0)) {
			throw new IllegalArgumentException("Gas heat capacity is invalid");
		}
		return heatCapacity;
	}

	/** Source-state enthalpy carried by an orifice portion: h = u + p*v = u + P0*Vstd*T/T0. */
	public double enthalpy(GasInventory moved, double sourceTemperatureK) {
		double result = internalEnergyAtTemperature(moved, sourceTemperatureK)
				+ GasRegistry.STANDARD_PRESSURE_PA * AirNetwork.cubicMeters(moved.standardVolumeNanos())
				* sourceTemperatureK / GasRegistry.STANDARD_TEMPERATURE_K;
		if (!Double.isFinite(result)) throw new IllegalArgumentException("Gas flow enthalpy is not finite");
		return result;
	}

	/** Splits node U at its derived common temperature; the final child receives raw floating remainder. */
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

	private static void requireAvailableVolume(double availableVolume) {
		if (!(Double.isFinite(availableVolume) && availableVolume > 0.0)) {
			throw new IllegalArgumentException("Gas available volume is outside the thermal model");
		}
	}

	private static double requirePositiveMass(GasInventory inventory) {
		double mass = inventory.totalMass();
		if (!(Double.isFinite(mass) && mass > 0.0)) {
			throw new IllegalArgumentException("Gas mass is outside the thermal model");
		}
		return mass;
	}
}

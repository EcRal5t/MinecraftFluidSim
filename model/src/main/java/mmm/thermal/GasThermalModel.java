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
			double heatCapacityJPerK,
			double gasConstantJPerK,
			double isentropicExponent,
			double adiabaticBulkModulusPa,
			double soundSpeedMetersPerSecond,
			double pressureCompliancePerPa
	) {
		public HydrodynamicState {
			if (!(Double.isFinite(temperatureK) && temperatureK > 0.0
					&& Double.isFinite(absolutePressurePa) && absolutePressurePa > 0.0
					&& Double.isFinite(densityKgPerCubicMeter) && densityKgPerCubicMeter > 0.0
					&& Double.isFinite(heatCapacityJPerK) && heatCapacityJPerK > 0.0
					&& Double.isFinite(gasConstantJPerK) && gasConstantJPerK > 0.0
					&& Double.isFinite(isentropicExponent) && isentropicExponent >= 1.0
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

	ThermalRegistry thermalRegistry() { return thermalRegistry; }

	/** A port's continuous work state uses real-valued mass fractions; compact inventory comes later. */
	AirNetwork.PortThermodynamics portThermodynamics(AirNetwork network, int first, int second,
	                                                double[] energies, double ambientTemperature) {
		double pa = first == 0 ? GasRegistry.STANDARD_PRESSURE_PA : absolutePressure(network.inventory(first), network.availableVolume(first), energies[first - 1]);
		double pb = second == 0 ? GasRegistry.STANDARD_PRESSURE_PA : absolutePressure(network.inventory(second), network.availableVolume(second), energies[second - 1]);
		int source = pa >= pb ? first : second, target = source == first ? second : first;
		GasInventory donor = source == 0 ? network.registry().createAmbient(1_000_000_000L) : network.inventory(source);
		double sourceVolume = source == 0 ? ambientTemperature / GasRegistry.STANDARD_TEMPERATURE_K : network.availableVolume(source);
		double sourceEnergy = source == 0 ? internalEnergyAtTemperature(donor, ambientTemperature) : energies[source - 1];
		double standard = AirNetwork.cubicMeters(donor.standardVolumeNanos());
		if (pa == pb || donor.isEmpty()) return new AirNetwork.PortThermodynamics(source, target, 0, 1, 1, 0, 1e-9, q -> 0);
		double maximum = source == 0 ? Double.POSITIVE_INFINITY : standard * maximumOutflowFraction(donor, sourceEnergy, sourceVolume);
		GasInventory receiver = target == 0 ? GasInventory.EMPTY : network.inventory(target);
		double receiverVolume = target == 0 ? 1 : network.availableVolume(target), receiverEnergy = target == 0 ? 0 : energies[target - 1];
		double receiverStandard = AirNetwork.cubicMeters(receiver.standardVolumeNanos());
		var receiving = receiver.isEmpty() ? List.<ThermalStateFunction.InventoryPortion>of() : portions(receiver);
		var sourceParts = portions(donor);
		double sourceOffset = 0;
		for (var part : sourceParts) sourceOffset += stateFunction.baseEnergy(part.materialHandle(), part.massKg(), part.speciesQuanta())
				- stateFunction.baseEnergy(part.materialHandle(), part.massKg(), Map.of());
		double offset = sourceOffset;
		double enthalpy = source == 0 ? enthalpy(donor, ambientTemperature) : 0;
		java.util.function.DoubleUnaryOperator response = amount -> {
			double fraction = amount / standard, sourcePressure, carried;
			if (source == 0) { sourcePressure = GasRegistry.STANDARD_PRESSURE_PA; carried = fraction * enthalpy; }
			else {
				var expanded = compressAdiabatically(donor, sourceEnergy, sourceVolume, sourceVolume / (1 - fraction));
				double remainingEnergy = (1 - fraction) * expanded.finalInternalEnergy();
				carried = sourceEnergy - remainingEnergy;
				sourcePressure = GasRegistry.STANDARD_PRESSURE_PA * standard * (1 - fraction)
						* expanded.finalTemperatureK() / GasRegistry.STANDARD_TEMPERATURE_K / sourceVolume;
			}
			if (target == 0) return sourcePressure - GasRegistry.STANDARD_PRESSURE_PA;
			var mixed = new ArrayList<ThermalStateFunction.InventoryPortion>(receiving);
			if (fraction > 0) for (var part : sourceParts) mixed.add(new ThermalStateFunction.InventoryPortion(part.materialHandle(), part.massKg() * fraction, Map.of()));
			if (mixed.isEmpty()) return sourcePressure;
			double temperature = stateFunction.stateForInventory(mixed, receiverEnergy + carried - offset * fraction).temperatureK();
			double receiverPressure = GasRegistry.STANDARD_PRESSURE_PA * (receiverStandard + amount)
					* temperature / GasRegistry.STANDARD_TEMPERATURE_K / receiverVolume;
			return sourcePressure - receiverPressure;
		};
		return new AirNetwork.PortThermodynamics(source, target, Math.abs(pa - pb), donor.totalMass() / sourceVolume,
				standard / sourceVolume, maximum, Math.max(1e-9, standard / donor.totalMassUnits()), response);
	}

	double maximumOutflowFraction(GasInventory inventory, double energy, double volume) {
		if (inventory.isEmpty()) return 0;
		double minimum = internalEnergyAtTemperature(inventory, ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K);
		double sensible = energy - minimum;
		var hydro = hydrodynamicState(inventory, volume, energy);
		double pressureEnergy = hydro.gasConstantJPerK() * hydro.temperatureK();
		return Math.max(0, Math.nextDown((sensible - 16 * (Math.ulp(energy) + Math.ulp(minimum))) / (sensible + pressureEnergy)));
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

	/** Derives the thermodynamic and acoustic closure used by a compressible pressure solve. */
	public HydrodynamicState hydrodynamicState(
			GasInventory inventory, double availableVolume, double internalEnergy
	) {
		requireAvailableVolume(availableVolume, inventory);
		if (inventory == null) throw new IllegalArgumentException("Gas inventory is missing");
		if (inventory.isEmpty()) {
			if (internalEnergy != 0.0) throw new IllegalArgumentException("Vacuum cannot carry internal energy");
			throw new IllegalArgumentException("Vacuum has no continuum hydrodynamic state");
		}
		ThermalStateFunction.State thermal = state(inventory, internalEnergy);
		return hydrodynamicState(inventory, availableVolume, thermal);
	}

	HydrodynamicState hydrodynamicState(GasInventory inventory, double availableVolume, ThermalStateFunction.State thermal) {
		requireAvailableVolume(availableVolume, inventory);
		double standardVolume = AirNetwork.cubicMeters(inventory.standardVolumeNanos());
		double gasConstant = GasRegistry.STANDARD_PRESSURE_PA * standardVolume
				/ GasRegistry.STANDARD_TEMPERATURE_K;
		double pressure = gasConstant * thermal.temperatureK() / availableVolume;
		double density = density(inventory, availableVolume);
		double exponent = 1.0 + gasConstant / thermal.heatCapacityJPerK();
		double bulkModulus = exponent * pressure;
		double soundSpeed = Math.sqrt(bulkModulus / density);
		double compliance = 1.0 / bulkModulus;
		return new HydrodynamicState(thermal.temperatureK(), pressure, density,
				thermal.heatCapacityJPerK(), gasConstant, exponent, bulkModulus,
				soundSpeed, compliance);
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
		double pressure = gasConstant * thermal.temperatureK() / availableVolume;
		if (!(Double.isFinite(pressure) && pressure >= 0.0)) {
			throw new ThermalStateFunction.EnergyRepresentationException("Gas pressure is outside the thermal model");
		}
		return pressure;
	}

	public double density(GasInventory inventory, double availableVolume) {
		requireAvailableVolume(availableVolume, inventory);
		if (inventory == null) throw new IllegalArgumentException("Gas inventory is missing");
		if (inventory.isEmpty()) return 0.0;
		double density = inventory.density(availableVolume);
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
		if (initialAvailableVolume == finalAvailableVolume) {
			return new Compression(initial.temperatureK(), initial.temperatureK(), internalEnergy, 0.0);
		}
		List<ThermalStateFunction.InventoryPortion> portions = portions(inventory);
		double entropyChange = initial.gasConstantJPerK()
				* logRatio(initialAvailableVolume, finalAvailableVolume);
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
					+ outside.remainingEntropyJPerK + " J/K", outside);
		}
		double energyChange = 0.0;
		for (ThermalStateFunction.InventoryPortion portion : portions) {
			energyChange += ThermalContactExchange.energyChangeBetween(thermalRegistry.material(portion.materialHandle()),
					portion.massKg(), initial.temperatureK(), targetTemperature);
		}
		double finalEnergy = internalEnergy + energyChange;
		hydrodynamicState(inventory, finalAvailableVolume, finalEnergy);
		return new Compression(initial.temperatureK(), targetTemperature, finalEnergy, finalEnergy - internalEnergy);
	}

	/** Prepared finite-inventory isentrope; heat-capacity segments are shared with volume work. */
	public final class PressureVolumeCurve {
		private final List<ThermalStateFunction.InventoryPortion> portions;
		private final HydrodynamicState initial;
		private final double initialVolume;
		private PressureVolumeCurve(GasInventory inventory, double volume, double energy) {
			portions = GasThermalModel.this.portions(inventory);
			initial = hydrodynamicState(inventory, volume, energy); initialVolume = volume;
		}
		public PressureVolume atPressure(double absolutePressure) {
			if (!(absolutePressure > 0 && Double.isFinite(absolutePressure)))
				throw new IllegalArgumentException("Gas pressure must be positive");
			double r = initial.gasConstantJPerK();
			double temperature = isentropicTemperature(portions, initial.temperatureK(),
					r * logRatio(absolutePressure, initial.absolutePressurePa()), r);
			double volume = initialVolume * (temperature / initial.temperatureK())
					* (initial.absolutePressurePa() / absolutePressure);
			double gamma = 1 + r / heatCapacityAt(portions, temperature);
			return new PressureVolume(volume, volume / (gamma * absolutePressure));
		}
	}
	public record PressureVolume(double volume, double compliance) {
		public PressureVolume {
			if (!(volume > 0 && compliance > 0 && Double.isFinite(volume) && Double.isFinite(compliance)))
				throw new IllegalArgumentException("Gas isentrope has no finite positive volume");
		}
	}
	public PressureVolumeCurve pressureVolumeCurve(GasInventory inventory, double volume, double energy) {
		return new PressureVolumeCurve(inventory, volume, energy);
	}

	private double isentropicTemperature(
			List<ThermalStateFunction.InventoryPortion> portions,
			double initialTemperatureK,
			double entropyChangeJPerK
	) {
		return isentropicTemperature(portions, initialTemperatureK, entropyChangeJPerK, 0);
	}
	private double isentropicTemperature(List<ThermalStateFunction.InventoryPortion> portions,
	                                    double initialTemperatureK, double entropyChangeJPerK,
	                                    double heatCapacityOffset) {
		if (entropyChangeJPerK == 0.0) return initialTemperatureK;
		boolean heating = entropyChangeJPerK > 0.0;
		double remaining = Math.abs(entropyChangeJPerK);
		double temperature = initialTemperatureK;
		while (remaining > 0.0) {
			double boundary = heating
					? nextUpperCaloricBoundary(portions, temperature)
					: nextLowerCaloricBoundary(portions, temperature);
			if (heating && boundary == Double.POSITIVE_INFINITY) {
				double heatCapacity = heatCapacityAt(portions, temperature) + heatCapacityOffset;
				double exponent = remaining / heatCapacity;
				double result = temperature + temperature * Math.expm1(exponent);
				if (!Double.isFinite(result)) result = scaledExponential(temperature, exponent);
				if (!(Double.isFinite(result) && result >= temperature)) {
					throw new CaloricRangeException(remaining);
				}
				return result;
			}
			if (!(heating ? boundary > temperature : boundary < temperature)) {
				throw new CaloricRangeException(remaining);
			}
			double sampleTemperature = Math.sqrt(temperature) * Math.sqrt(boundary);
			double heatCapacity = heatCapacityAt(portions, sampleTemperature) + heatCapacityOffset;
			double intervalEntropy = heatCapacity
					* Math.abs(logRatio(boundary, temperature));
			if (remaining <= intervalEntropy) {
				double signed = (heating ? 1.0 : -1.0) * remaining / heatCapacity;
				double result = scaledExponential(temperature, signed);
				return heating ? Math.max(temperature, Math.min(boundary, result))
						: Math.max(boundary, Math.min(temperature, result));
			}
			remaining -= intervalEntropy;
			temperature = boundary;
		}
		return temperature;
	}

	/** Retains nearby ratios without overflowing or underflowing far-separated endpoints. */
	private static double logRatio(double first, double second) {
		double relative = (first - second) / second;
		return Math.abs(relative) < 0.5 ? Math.log1p(relative) : Math.log(first) - Math.log(second);
	}

	private static double scaledExponential(double scale, double exponent) {
		double result = scale * Math.exp(exponent);
		return Double.isFinite(result) && result > 0.0 ? result : Math.exp(Math.log(scale) + exponent);
	}

	private static final class CaloricRangeException extends IllegalArgumentException {
		private final double remainingEntropyJPerK;

		private CaloricRangeException(double remainingEntropyJPerK) {
			super("Isentrope leaves the positive-temperature domain with " + remainingEntropyJPerK
					+ " J/K unconsumed");
			this.remainingEntropyJPerK = remainingEntropyJPerK;
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
			heatCapacity += portion.massKg()
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

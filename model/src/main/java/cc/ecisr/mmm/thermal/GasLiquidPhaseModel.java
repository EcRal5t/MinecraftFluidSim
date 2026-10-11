package cc.ecisr.mmm.thermal;

import cc.ecisr.mmm.fluid.air.AirNetwork;
import cc.ecisr.mmm.fluid.air.GasComponentAmount;
import cc.ecisr.mmm.fluid.air.GasComponentDefinition;
import cc.ecisr.mmm.fluid.air.GasInventory;
import cc.ecisr.mmm.fluid.air.GasRegistry;
import cc.ecisr.mmm.granular.SolidVolume;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Pure ideal-mixture saturation and exact-batch creation functions for liquid/gas transitions. */
public final class GasLiquidPhaseModel {
	public enum Direction { BOIL, CONDENSE, EQUILIBRIUM }
	public record Saturation(double saturationPressurePa, double componentPartialPressurePa, Direction direction) {
		public double pressureImbalancePa() { return saturationPressurePa - componentPartialPressurePa; }
	}

	private final ThermalRegistry thermalRegistry;
	private final GasRegistry gasRegistry;
	private final ThermalStateFunction stateFunction;

	public GasLiquidPhaseModel(ThermalRegistry thermalRegistry, GasRegistry gasRegistry) {
		if (thermalRegistry == null || gasRegistry == null) {
			throw new IllegalArgumentException("Liquid/gas phase registries are missing");
		}
		this.thermalRegistry = thermalRegistry; this.gasRegistry = gasRegistry;
		this.stateFunction = new ThermalStateFunction(thermalRegistry);
	}

	public Saturation saturation(
			ThermalPhaseTransition transition, double liquidTemperatureK,
			GasInventory gasInventory, double absolutePressurePa
	) {
		PhasePair pair = requirePair(transition);
		if (!(Double.isFinite(absolutePressurePa) && absolutePressurePa >= 0.0)) {
			throw new IllegalArgumentException("Gas/liquid phase pressure is outside the transition model");
		}
		pair.liquid().specificHeatAt(liquidTemperatureK);
		double saturation = GasRegistry.STANDARD_PRESSURE_PA * saturationExponential(transition, pair, liquidTemperatureK);
		if (!(Double.isFinite(saturation) && saturation >= 0.0)) {
			throw new IllegalArgumentException("Saturation pressure is outside the phase model");
		}
		GasComponentAmount component = gasInventory.component(pair.gas().carrierId());
		double partial = component == null ? 0.0
				: absolutePressurePa * ((double) component.standardVolumeNanos() / gasInventory.standardVolumeNanos());
		Direction direction = saturation > partial ? Direction.BOIL
				: saturation < partial ? Direction.CONDENSE : Direction.EQUILIBRIUM;
		return new Saturation(saturation, partial, direction);
	}

	/** Envelope for the production pressure expression at an outward-facing temperature endpoint. */
	public double saturationPressureBound(ThermalPhaseTransition transition, double temperatureK, boolean upper) {
		PhasePair pair = requirePair(transition);
		pair.liquid().specificHeatAt(temperatureK);
		double exponential = saturationExponential(transition, pair, temperatureK);
		// The raw exponent operations preserve temperature order. Four neighbours cover the
		// two Math.exp one-ULP errors, including a change of exponent, before positive scaling.
		for (int rounding = 0; rounding < 4; rounding++) exponential = upper
				? Math.nextUp(exponential) : Math.max(0.0, Math.nextDown(exponential));
		double pressure = GasRegistry.STANDARD_PRESSURE_PA * exponential;
		return upper ? Math.nextUp(pressure) : Math.max(0.0, Math.nextDown(pressure));
	}

	private static double saturationExponential(ThermalPhaseTransition transition, PhasePair pair, double temperatureK) {
		double exponent = -pair.latentEnthalpyPerMass() / pair.gasConstantPerMass()
				* (1.0 / temperatureK - 1.0 / referenceTemperature(transition));
		return Math.exp(exponent);
	}

	/** Creates a gas-side batch with exact phase mass and stable standard-volume nanos. */
	public GasInventory gasBatch(ThermalPhaseTransition transition, long batches) {
		double mass = transition.massMuPerBatch().multiply(batches).toDouble();
		return gasBatch(transition, batches, Map.of(), mass);
	}

	/** Creates a phase batch carrying already-conserved species and a physical-kilogram mass residual. */
	public GasInventory gasBatch(
			ThermalPhaseTransition transition, long batches,
			Map<String, Long> speciesQuanta, double massResidual
	) {
		if (batches <= 0L || batches > ThermalLimits.MAX_PHASE_OPERATIONS_PER_STEP) {
			throw new IllegalArgumentException("Gas phase batch count is invalid");
		}
		if (speciesQuanta == null || !Double.isFinite(massResidual)) {
			throw new IllegalArgumentException("Gas phase chemical columns are invalid");
		}
		PhasePair pair = requirePair(transition);
		double referenceTemperature = referenceTemperature(transition);
		double mass = transition.massMuPerBatch().multiply(batches).toDouble();
		GasComponentDefinition component = gasRegistry.component(pair.gas().carrierId());
		double standardVolumePerBatch = (double) transition.upperVolumeSvqPerBatch() / SolidVolume.SVQ_PER_BV
				* GasRegistry.STANDARD_TEMPERATURE_K / referenceTemperature;
		long nanosPerBatch = AirNetwork.nanos(standardVolumePerBatch);
		if (nanosPerBatch <= 0L
				|| Math.abs(AirNetwork.cubicMeters(nanosPerBatch) - standardVolumePerBatch) > 0.5E-9) {
			throw new IllegalArgumentException("Gas phase batch is not representable in standard-volume nanos");
		}
		long nanos = Math.multiplyExact(nanosPerBatch, batches);
		TreeMap<String, Long> canonical = new TreeMap<>();
		for (Map.Entry<String, Long> entry : speciesQuanta.entrySet()) {
			if (entry.getKey() == null || entry.getValue() == null || entry.getValue() <= 0L) {
				throw new IllegalArgumentException("Gas phase species amount is invalid");
			}
			if (!component.allowsSpecies(entry.getKey())) {
				throw new IllegalArgumentException("Gas phase component " + component.id()
						+ " does not allow species " + entry.getKey());
			}
			canonical.merge(entry.getKey(), entry.getValue(), Math::addExact);
		}
		return new GasInventory(List.of(new GasComponentAmount(component.id(), nanos, mass,
				canonical.entrySet().stream().map(entry -> new GasComponentAmount.SpeciesAmount(
						entry.getKey(), entry.getValue())).toList(), massResidual)));
	}

	public double internalEnergyAtTemperature(
			ThermalPhaseTransition transition, GasInventory gasBatch, double temperatureK
	) {
		PhasePair pair = requirePair(transition);
		GasComponentAmount component = gasBatch.component(pair.gas().carrierId());
		if (component == null || gasBatch.components().size() != 1) {
			throw new IllegalArgumentException("Gas phase energy batch has the wrong component");
		}
		int handle = thermalRegistry.materialHandle(ThermalMaterialDefinition.CarrierKind.GAS, component.componentId());
		TreeMap<String, Long> species = new TreeMap<>();
		for (GasComponentAmount.SpeciesAmount amount : component.species()) {
			species.put(amount.speciesId(), amount.amountQuanta());
		}
		return stateFunction.internalEnergyAtTemperature(handle, component.mass(), species, temperatureK);
	}

	private PhasePair requirePair(ThermalPhaseTransition transition) {
		if (transition == null || transition.interfaceRequirement()
				!= ThermalPhaseTransition.InterfaceRequirement.LIQUID_GAS) {
			throw new IllegalArgumentException("Transition is not a liquid/gas interface transition");
		}
		ThermalMaterialDefinition liquid = thermalRegistry.material(
				thermalRegistry.materialHandle(transition.lowerEnergyMaterialId()));
		ThermalMaterialDefinition gas = thermalRegistry.material(
				thermalRegistry.materialHandle(transition.upperEnergyMaterialId()));
		if (liquid.carrierKind() != ThermalMaterialDefinition.CarrierKind.LIQUID
				|| gas.carrierKind() != ThermalMaterialDefinition.CarrierKind.GAS
				|| !gasRegistry.hasComponent(gas.carrierId())) {
			throw new IllegalArgumentException("Liquid/gas transition carrier kinds are invalid");
		}
		double mass = transition.massMuPerBatch().toDouble();
		double referenceTemperature = referenceTemperature(transition);
		gasRegistry.component(gas.carrierId());
		double gasVolume = (double) transition.upperVolumeSvqPerBatch() / SolidVolume.SVQ_PER_BV;
		double standardGasVolume = gasVolume * GasRegistry.STANDARD_TEMPERATURE_K / referenceTemperature;
		double liquidVolume = (double) transition.lowerVolumeSvqPerBatch() / SolidVolume.SVQ_PER_BV;
		int liquidHandle = thermalRegistry.materialHandle(transition.lowerEnergyMaterialId());
		int gasHandle = thermalRegistry.materialHandle(transition.upperEnergyMaterialId());
		double liquidU = stateFunction.internalEnergyAtTemperature(
				liquidHandle, mass, Map.of(), referenceTemperature);
		double gasU = stateFunction.internalEnergyAtTemperature(
				gasHandle, mass, Map.of(), referenceTemperature);
		double latentEnthalpy = (gasU - liquidU + GasRegistry.STANDARD_PRESSURE_PA
				* (gasVolume - liquidVolume)) / mass;
		double gasConstant = GasRegistry.STANDARD_PRESSURE_PA * standardGasVolume
				/ (mass * GasRegistry.STANDARD_TEMPERATURE_K);
		if (!(Double.isFinite(latentEnthalpy) && latentEnthalpy > 0.0
				&& Double.isFinite(gasConstant) && gasConstant > 0.0)) {
			throw new IllegalArgumentException("Liquid/gas transition has an invalid latent state function");
		}
		return new PhasePair(liquid, gas, latentEnthalpy, gasConstant);
	}

	private static double referenceTemperature(ThermalPhaseTransition transition) {
		return transition.equilibriumTemperatureK(GasRegistry.STANDARD_PRESSURE_PA);
	}

	private record PhasePair(
			ThermalMaterialDefinition liquid, ThermalMaterialDefinition gas,
			double latentEnthalpyPerMass, double gasConstantPerMass
	) { }
}

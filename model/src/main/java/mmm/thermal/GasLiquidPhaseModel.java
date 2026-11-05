package mmm.thermal;

import mmm.fluid.air.AirNetwork;
import mmm.fluid.air.GasComponentAmount;
import mmm.fluid.air.GasComponentDefinition;
import mmm.fluid.air.GasInventory;
import mmm.fluid.air.GasRegistry;
import mmm.granular.SolidVolume;

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
	/** One immutable definition entry follows the transition currently scanned by the phase stage. */
	private volatile PhasePair preparedPair;

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
		GasComponentAmount component = gasInventory.component(pair.gas().carrierId());
		double partial = component == null ? 0.0
				: absolutePressurePa * ((double) component.standardVolumeNanos() / gasInventory.standardVolumeNanos());
		return saturation(pair, liquidTemperatureK, partial);
	}

	/** Uses the ambient reservoir's defined composition after an interface opens to it. */
	public Saturation ambientSaturation(ThermalPhaseTransition transition, double liquidTemperatureK) {
		PhasePair pair = requirePair(transition);
		double fraction = 0.0;
		for (var ambient : gasRegistry.ambient()) if (ambient.componentId().equals(pair.gas().carrierId())) {
			fraction = ambient.fraction().toDouble(); break;
		}
		return saturation(pair, liquidTemperatureK, GasRegistry.STANDARD_PRESSURE_PA * fraction);
	}

	/** Inverse of the same saturation law used by the phase-pressure residual. */
	public double saturationTemperatureK(ThermalPhaseTransition transition, double partialPressurePa) {
		PhasePair pair = requirePair(transition);
		if (!(Double.isFinite(partialPressurePa) && partialPressurePa >= 0))
			throw new IllegalArgumentException("Invalid vapor partial pressure");
		if (partialPressurePa == 0) return 0;
		double inverse = 1 / pair.referenceTemperatureK() - pair.gasConstantPerMass() / pair.latentEnthalpyPerMass()
				* Math.log(partialPressurePa / GasRegistry.STANDARD_PRESSURE_PA);
		return inverse > 0 ? 1 / inverse : Double.POSITIVE_INFINITY;
	}

	/** Local Clausius slope for a finite-heat-capacity phase exchange at the current temperature. */
	public double saturationSlopePaPerKelvin(ThermalPhaseTransition transition, double temperatureK,
	                                       double saturationPressurePa) {
		PhasePair pair = requirePair(transition);
		return saturationPressurePa * (pair.latentEnthalpyPerMass() / pair.gasConstantPerMass())
				/ temperatureK / temperatureK;
	}

	private static Saturation saturation(PhasePair pair,
	                                    double liquidTemperatureK, double partial) {
		pair.liquid().specificHeatAt(liquidTemperatureK);
		double saturation = GasRegistry.STANDARD_PRESSURE_PA * saturationExponential(pair, liquidTemperatureK);
		if (!(Double.isFinite(saturation) && saturation >= 0.0)) {
			throw new ThermalStateFunction.EnergyRepresentationException("Saturation pressure is outside the phase model");
		}
		Direction direction = saturation > partial ? Direction.BOIL
				: saturation < partial ? Direction.CONDENSE : Direction.EQUILIBRIUM;
		return new Saturation(saturation, partial, direction);
	}

	private static double saturationExponential(PhasePair pair, double temperatureK) {
		double exponent = -pair.latentEnthalpyPerMass() / pair.gasConstantPerMass()
				* (1.0 / temperatureK - 1.0 / pair.referenceTemperatureK());
		return Math.exp(exponent);
	}

	/** Creates a gas-side batch with exact phase mass and stable standard-volume nanos. */
	public GasInventory gasBatch(ThermalPhaseTransition transition, long batches) {
		long massUnits = Math.multiplyExact(requirePair(transition).massUnitsPerBatch(),batches);
		return gasBatch(transition, batches, Map.of(), massUnits);
	}

	/** Creates a phase batch carrying already-conserved species and a physical-kilogram mass residual. */
	public GasInventory gasBatch(
			ThermalPhaseTransition transition, long batches,
			Map<String, Long> speciesQuanta, long massResidualUnits
	) {
		if (batches <= 0L || batches > ThermalLimits.MAX_PHASE_OPERATIONS_PER_STEP) {
			throw new IllegalArgumentException("Gas phase batch count is invalid");
		}
		if (speciesQuanta == null) {
			throw new IllegalArgumentException("Gas phase chemical columns are invalid");
		}
		PhasePair pair = requirePair(transition);
		GasComponentDefinition component = gasRegistry.component(pair.gas().carrierId());
		long nanos = Math.multiplyExact(pair.nanosPerBatch(), batches);
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
		return new GasInventory(List.of(new GasComponentAmount(component.id(), nanos, Math.multiplyExact(pair.massUnitsPerBatch(),batches),
				canonical.entrySet().stream().map(entry -> new GasComponentAmount.SpeciesAmount(
						entry.getKey(), entry.getValue())).toList(), massResidualUnits)));
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
		PhasePair prepared = preparedPair;
		if (prepared != null && prepared.transition() == transition) return prepared;
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
		double mass = transition.massKgPerBatch().toDouble();
		double referenceTemperature = referenceTemperature(transition);
		if (!gasRegistry.component(gas.carrierId()).allowsSpecies(transition.speciesId()))
			throw new IllegalArgumentException("Gas phase does not contain transition species " + transition.speciesId());
		double gasVolume = (double) transition.upperVolumeSvqPerBatch() / SolidVolume.SVQ_PER_CUBIC_METER;
		double standardGasVolume = gasVolume * GasRegistry.STANDARD_TEMPERATURE_K / referenceTemperature;
		double liquidVolume = (double) transition.lowerVolumeSvqPerBatch() / SolidVolume.SVQ_PER_CUBIC_METER;
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
		long massUnits=mmm.units.Mass.exactUnits(transition.massKgPerBatch().numerator(),transition.massKgPerBatch().denominator());
		long nanos=AirNetwork.nanos(standardGasVolume);
		if(nanos<=0||Math.abs(AirNetwork.cubicMeters(nanos)-standardGasVolume)>0.5E-9)
			throw new IllegalArgumentException("Gas phase batch is not representable in standard-volume nanos");
		PhasePair pair = new PhasePair(transition, liquid, gas, referenceTemperature, latentEnthalpy, gasConstant,massUnits,nanos);
		preparedPair = pair;
		return pair;
	}

	private static double referenceTemperature(ThermalPhaseTransition transition) {
		return transition.equilibriumTemperatureK(GasRegistry.STANDARD_PRESSURE_PA);
	}

	private record PhasePair(
			ThermalPhaseTransition transition,
			ThermalMaterialDefinition liquid, ThermalMaterialDefinition gas,
			double referenceTemperatureK, double latentEnthalpyPerMass, double gasConstantPerMass,
			long massUnitsPerBatch,long nanosPerBatch
	) { }
}

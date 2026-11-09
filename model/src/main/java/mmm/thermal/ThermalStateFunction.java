package mmm.thermal;

import mmm.chemistry.ChemicalMixtureRecord;
import mmm.chemistry.ChemicalRegistry;
import mmm.units.SignedDyadic;

import java.util.Map;
import java.util.List;

/** Pure thermal state function: total internal energy is authoritative and temperature is an inversion result. */
public final class ThermalStateFunction {
	public enum PhaseState { LOWER, PLATEAU, UPPER }
	public record State(double temperatureK, double heatCapacityJPerK) { }
	/** A prospective inventory is finite and well-formed but its energy implies no positive-temperature state. */
	public static final class EnergyOutsideValidityException extends IllegalArgumentException {
		public EnergyOutsideValidityException() {
			super("Thermal LTE energy is outside the positive-temperature domain");
		}
		public EnergyOutsideValidityException(String message) { super(message); }
	}
	/** A well-formed prospective inventory exceeds the finite thermal column representation. */
	public static final class EnergyRepresentationException extends IllegalArgumentException {
		public EnergyRepresentationException() {
			this("Thermal energy or derived state exceeds its finite representation");
		}
		public EnergyRepresentationException(String message) { super(message); }
	}
	public record InventoryPortion(int materialHandle, double massKg, Map<String, Long> speciesQuanta) {
		public InventoryPortion {
			if (!(Double.isFinite(massKg) && massKg > 0.0) || speciesQuanta == null) {
				throw new IllegalArgumentException("Thermal inventory portion is invalid");
			}
			speciesQuanta = Map.copyOf(speciesQuanta);
		}
	}
	/** Actual caloric owner after transport, with its unchanged material/species reference energy. */
	public record MechanicalRecipient(ThermalMaterialDefinition material,double massKg,double baseEnergy,double internalEnergy) {
		public MechanicalRecipient {
			if(material==null||!(Double.isFinite(massKg)&&massKg>0)
					||!Double.isFinite(baseEnergy)||!Double.isFinite(internalEnergy))
				throw new IllegalArgumentException("Mechanical thermal recipient is invalid");
		}
	}

	/** Partitions one connected body's work by heat capacity or available sensible energy. */
	public static SignedDyadic[] partitionMechanicalEnergy(List<MechanicalRecipient> recipients,SignedDyadic exchange) {
		if(recipients==null||exchange==null)throw new IllegalArgumentException("Mechanical energy allocation is missing");
		int count=recipients.size();var parts=new SignedDyadic[count];java.util.Arrays.fill(parts,SignedDyadic.ZERO);
		if(exchange.signum()==0)return parts;
		if(count==0)throw new IllegalArgumentException("Mechanical energy has no thermal recipients");
		boolean cooling=exchange.signum()<0;
		var capacities=cooling?new SignedDyadic[count]:null;
		var available=SignedDyadic.ZERO;var weights=new double[count];
		var weightSum=new ThermalCaloricInversion.Sum();int largest=0;
		for(int i=0;i<count;i++){
			var owner=recipients.get(i);
			State state;
			try{state=ThermalCaloricInversion.owner(owner.material(),owner.massKg(),owner.internalEnergy(),owner.baseEnergy());}
			catch(IllegalArgumentException failure){
				failure.addSuppressed(new IllegalStateException("Mechanical caloric recipient="+i+", material="+owner.material().id()
						+", massKg="+owner.massKg()+", U="+owner.internalEnergy()+", referenceJ="+owner.baseEnergy()
						+", sensibleJ="+(owner.internalEnergy()-owner.baseEnergy())));throw failure;
			}
			if(cooling){
				double cold=ThermalCaloricInversion.energyAt(owner.material(),owner.massKg(),owner.baseEnergy(),ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K);
				// An owner already at the caloric inverter's cold rounding limit releases no further heat.
				cold=Math.min(cold,owner.internalEnergy());
				capacities[i]=SignedDyadic.of(owner.internalEnergy()).subtract(SignedDyadic.of(cold));
				available=available.add(capacities[i]);weights[i]=capacities[i].toDouble();
				if(capacities[i].compareTo(capacities[largest])>0)largest=i;
			}else{
				weights[i]=state.heatCapacityJPerK();if(weights[i]>weights[largest])largest=i;
			}
			weightSum.add(weights[i]);
		}
		var requested=cooling?exchange.negate():exchange;
		if(cooling&&requested.compareTo(available)>0){
			var total=SignedDyadic.ZERO;for(var owner:recipients)total=total.add(SignedDyadic.of(owner.internalEnergy()));
			throw new EnergyOutsideValidityException("Mechanical body has insufficient sensible energy: U="+total
					+", cold="+total.subtract(available)+", exchange="+exchange);
		}
		double totalWeight=weightSum.value(),magnitude=requested.toDouble();
		if(!(Double.isFinite(totalWeight)&&totalWeight>0&&Double.isFinite(magnitude)))throw new EnergyRepresentationException();
		var remaining=requested;
		for(int i=0;i<count;i++)if(i!=largest&&weights[i]>0){
			var part=SignedDyadic.of(magnitude*(weights[i]/totalWeight));
			if(cooling&&part.compareTo(capacities[i])>0)part=capacities[i];
			if(part.compareTo(remaining)>0)part=remaining;
			parts[i]=part;remaining=remaining.subtract(part);
		}
		var pivot=cooling&&remaining.compareTo(capacities[largest])>0?capacities[largest]:remaining;
		parts[largest]=pivot;remaining=remaining.subtract(pivot);
		// At full cooling, rounded proportional shares may leave the pivot full.
		// Spend the other owners' remaining caloric capacity in the same linear pass.
		if(cooling&&remaining.signum()>0)for(int i=0;i<count&&remaining.signum()>0;i++){
			var room=capacities[i].subtract(parts[i]);var part=room.compareTo(remaining)<0?room:remaining;
			parts[i]=parts[i].add(part);remaining=remaining.subtract(part);
		}
		if(cooling)for(int i=0;i<count;i++)parts[i]=parts[i].negate();
		return parts;
	}
	public record EquilibriumState(double temperatureK, PhaseState phaseState, double upperPhaseFraction,
	                               double lowerBoundaryEnergy, double upperBoundaryEnergy) { }

	private final ThermalRegistry registry;

	public ThermalStateFunction(ThermalRegistry registry) {
		if (registry == null) throw new IllegalArgumentException("Thermal registry is missing");
		this.registry = registry;
	}

	public ThermalMaterialDefinition materialForComposition(int handle,double mass,Map<String,Long> species) {
		return mmm.chemistry.AqueousMixtureProperties.thermalMaterial(registry.material(handle),mass,species,registry.chemistry());
	}
	public ThermalMaterialDefinition materialForComposition(int handle,double mass,ChemicalMixtureRecord mixture,ChemicalRegistry chemistry) {
		return materialForComposition(handle,mass,mixture==null||chemistry==null?Map.of():mixture.stableAmounts(chemistry));
	}

	public double internalEnergyAtTemperature(
			int materialHandle, double massKg, Map<String, Long> speciesQuanta, double temperatureK
	) {
		ThermalMaterialDefinition material = materialForComposition(materialHandle, massKg, speciesQuanta);
		double energy = ThermalCaloricInversion.energyAt(material, massKg, baseEnergy(materialHandle, massKg, speciesQuanta), temperatureK);
		if (!Double.isFinite(energy)) throw new EnergyRepresentationException();
		return energy;
	}

	public double internalEnergyAtTemperature(
			int materialHandle, double massKg, ChemicalMixtureRecord mixture,
			ChemicalRegistry chemicalRegistry, double temperatureK
	) {
		ThermalMaterialDefinition material = materialForComposition(materialHandle, massKg, mixture, chemicalRegistry);
		double energy = ThermalCaloricInversion.energyAt(material, massKg, baseEnergy(materialHandle, massKg, mixture, chemicalRegistry), temperatureK);
		if (!Double.isFinite(energy)) throw new EnergyRepresentationException();
		return energy;
	}

	public State stateForInternalEnergy(
			int materialHandle, double massKg, Map<String, Long> speciesQuanta, double internalEnergy
	) {
		requireMassEnergy(massKg, internalEnergy);
		return ThermalCaloricInversion.owner(materialForComposition(materialHandle, massKg, speciesQuanta), massKg, internalEnergy,
				baseEnergy(materialHandle, massKg, speciesQuanta));
	}

	/** One inventory energy column: retain each reference and caloric integral until final storage rounding. */
	public double inventoryEnergyAtTemperature(List<InventoryPortion> portions, double temperatureK) {
		if (portions == null || portions.isEmpty()) throw new IllegalArgumentException("Thermal LTE inventory is missing");
		SignedDyadic energy = SignedDyadic.ZERO;
		for (InventoryPortion portion : portions) {
			ThermalMaterialDefinition material = materialForComposition(portion.materialHandle(),portion.massKg(),portion.speciesQuanta());
			energy = energy.add(SignedDyadic.of(baseEnergy(portion.materialHandle(), portion.massKg(), portion.speciesQuanta())))
					.add(ThermalContactExchange.exactEnergyChangeBetween(material, portion.massKg(),
							material.referenceTemperatureK(), temperatureK));
		}
		double result = energy.toDouble();
		if (!Double.isFinite(result)) throw new EnergyRepresentationException();
		return result;
	}

	/** Closed-form common-temperature inversion for a heterogeneous LTE inventory. */
	public State stateForInventory(List<InventoryPortion> portions, double internalEnergy) {
		if (!Double.isFinite(internalEnergy)) throw new EnergyRepresentationException();
		return stateForInventory(portions, SignedDyadic.of(internalEnergy));
	}

	/** Retains an aggregate's exact energy until its fixed reference terms have been removed. */
	public State stateForInventory(List<InventoryPortion> portions, SignedDyadic totalEnergy) {
		if (portions == null || portions.isEmpty() || totalEnergy == null)
			throw new IllegalArgumentException("Thermal LTE inventory is missing or non-finite");
		return prepareInventory(portions).state(totalEnergy);
	}

	/** Fixed inventory preparation belongs to one caller operation, without cross-step caching. */
	final class PreparedInventory {
		private final SignedDyadic reference;
		private final ThermalCaloricInversion.Prepared caloric;
		private PreparedInventory(List<InventoryPortion> portions) {
			SignedDyadic base=SignedDyadic.ZERO;
			for(var portion:portions)base=base.add(SignedDyadic.of(
					baseEnergy(portion.materialHandle(),portion.massKg(),portion.speciesQuanta())));
			reference=base;
			caloric=new ThermalCaloricInversion.Prepared(new ThermalCaloricInversion.Inventory() {
				public int first(){return 0;}
				public int next(int item){return item+1<portions.size()?item+1:-1;}
				public ThermalMaterialDefinition material(int item){var p=portions.get(item);return materialForComposition(p.materialHandle(),p.massKg(),p.speciesQuanta());}
				public double mass(int item){return portions.get(item).massKg();}
			});
		}
		State state(double energy){
			if(!Double.isFinite(energy))throw new EnergyRepresentationException();
			return state(SignedDyadic.of(energy));
		}
		State state(SignedDyadic energy){return caloric.solve(energy.subtract(reference));}
	}
	PreparedInventory prepareInventory(List<InventoryPortion> portions) {
		if(portions==null||portions.isEmpty())throw new IllegalArgumentException("Thermal LTE inventory is missing or non-finite");
		return new PreparedInventory(portions);
	}

	public EquilibriumState equilibriumState(
			int transitionHandle, double massKg, Map<String, Long> speciesQuanta,
			double internalEnergy, double absolutePressurePa
	) {
		requireMassEnergy(massKg, internalEnergy);
		ThermalPhaseTransition transition = registry.transition(transitionHandle);
		int lowerHandle = registry.materialHandle(transition.lowerEnergyMaterialId());
		int upperHandle = registry.materialHandle(transition.upperEnergyMaterialId());
		double equilibrium = transition.equilibriumTemperatureK(absolutePressurePa);
		double lowerBoundary = internalEnergyAtTemperature(lowerHandle, massKg, speciesQuanta,
				equilibrium);
		double upperBoundary = internalEnergyAtTemperature(upperHandle, massKg, speciesQuanta,
				equilibrium);
		if (!(upperBoundary > lowerBoundary)) throw new IllegalStateException("Thermal transition energy gap is not positive");
		if (internalEnergy < lowerBoundary) {
			State state = stateForInternalEnergy(lowerHandle, massKg, speciesQuanta, internalEnergy);
			return new EquilibriumState(state.temperatureK(), PhaseState.LOWER, 0.0, lowerBoundary, upperBoundary);
		}
		if (internalEnergy > upperBoundary) {
			State state = stateForInternalEnergy(upperHandle, massKg, speciesQuanta, internalEnergy);
			return new EquilibriumState(state.temperatureK(), PhaseState.UPPER, 1.0, lowerBoundary, upperBoundary);
		}
		double fraction = (internalEnergy - lowerBoundary) / (upperBoundary - lowerBoundary);
		if (!Double.isFinite(fraction)) throw new IllegalArgumentException("Thermal phase fraction is not finite");
		return new EquilibriumState(equilibrium, PhaseState.PLATEAU,
				Math.max(0.0, Math.min(1.0, fraction)), lowerBoundary, upperBoundary);
	}

	public double baseEnergy(int materialHandle, double massKg, Map<String, Long> speciesQuanta) {
		if (!(Double.isFinite(massKg) && massKg > 0.0)) {
			throw new IllegalArgumentException("Thermal carrier mass must be finite and positive");
		}
		ThermalMaterialDefinition material = registry.material(materialHandle);
		double total = massKg * material.referenceSpecificEnergyJPerKg();
		if (speciesQuanta != null && !speciesQuanta.isEmpty()) {
			for (Map.Entry<String, Long> entry : speciesQuanta.entrySet()) {
				if (entry.getKey() == null || entry.getValue() == null || entry.getValue() < 0L) {
					throw new IllegalArgumentException("Thermal species inventory is invalid");
				}
			}
			for (Map.Entry<String, Long> entry : new java.util.TreeMap<>(speciesQuanta).entrySet()) {
				long quanta = entry.getValue();
				if (quanta == 0L) continue;
				ThermalSpeciesOffset offset = registry.speciesOffset(materialHandle, entry.getKey());
				total += referenceEnergy(offset, quanta);
			}
		}
		if (!Double.isFinite(total)) throw new EnergyRepresentationException();
		return total;
	}

	public double baseEnergy(
			int materialHandle, double massKg, ChemicalMixtureRecord mixture, ChemicalRegistry chemicalRegistry
	) {
		if (!(Double.isFinite(massKg) && massKg > 0.0)) {
			throw new IllegalArgumentException("Thermal carrier mass must be finite and positive");
		}
		ThermalMaterialDefinition material = registry.material(materialHandle);
		double total = massKg * material.referenceSpecificEnergyJPerKg();
		if (mixture != null) {
			if (chemicalRegistry == null) throw new IllegalArgumentException("Thermal chemical registry is missing");
			ThermalSpeciesOffset[] offsets = offsets(chemicalRegistry)[materialHandle];
			for (int entry = 0; entry < mixture.entryCount(); entry++) {
				long quanta = mixture.amountAt(entry);
				ThermalSpeciesOffset offset = requireOffset(offsets, mixture.speciesHandle(entry));
				total += referenceEnergy(offset, quanta);
			}
		}
		if (!Double.isFinite(total)) throw new EnergyRepresentationException();
		return total;
	}

	private double referenceEnergy(ThermalSpeciesOffset offset, long quanta) {
		if (offset.referenceInternalEnergyJPerMol().isZero()) return 0;
		return referenceCache().energy(offset, quanta);
	}

	/** Absolute binary64 error from reference-term conversion and ordered additions. */
	public double baseEnergyRoundoff(int materialHandle, double massKg, ChemicalMixtureRecord mixture, ChemicalRegistry chemistry) {
		double total = massKg * registry.material(materialHandle).referenceSpecificEnergyJPerKg();
		double error = Math.ulp(total);
		ThermalSpeciesOffset[] offsets = mixture == null ? null : offsets(chemistry)[materialHandle];
		if (mixture != null) for (int entry = 0; entry < mixture.entryCount(); entry++) {
			var offset = requireOffset(offsets, mixture.speciesHandle(entry));
			double term = referenceEnergy(offset, mixture.amountAt(entry));
			total += term;
			// The registered J/quantum coefficient and its product are each rounded once.
			error += 3 * Math.ulp(term) + Math.ulp(total);
		}
		return error;
	}

	private ThermalSpeciesOffset[][] offsets(ChemicalRegistry chemistry) {
		return registry.speciesOffsetTable(chemistry);
	}
	private static ThermalSpeciesOffset requireOffset(ThermalSpeciesOffset[] offsets, int species) {
		if (species < 0 || species >= offsets.length || offsets[species] == null)
			throw new IllegalArgumentException("Missing thermal species offset for chemical handle " + species);
		return offsets[species];
	}

	private ThermalReferenceEnergyCache referenceCache() {
		return registry.referenceEnergyCache();
	}

	private static void requireMassEnergy(double massKg, double internalEnergy) {
		if (!(Double.isFinite(massKg) && massKg > 0.0)) {
			throw new IllegalArgumentException("Thermal carrier mass must be finite and positive");
		}
		if (!Double.isFinite(internalEnergy)) throw new IllegalArgumentException("Thermal internal energy must be finite");
	}

	/** Normalizes a chemical mixture into the stable species-amount map used by inventory portions. */
	public static java.util.Map<String, Long> stableAmounts(
			ChemicalMixtureRecord mixture, ChemicalRegistry chemicalRegistry
	) {
		if (mixture == null) return Map.of();
		if (chemicalRegistry == null) throw new IllegalArgumentException("Thermal chemical registry is missing");
		java.util.TreeMap<String, Long> result = new java.util.TreeMap<>();
		for (int entry = 0; entry < mixture.entryCount(); entry++) result.put(
				chemicalRegistry.species(mixture.speciesHandle(entry)).id(), mixture.amountAt(entry));
		return Map.copyOf(result);
	}
}

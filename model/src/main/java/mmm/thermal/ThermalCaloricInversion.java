package mmm.thermal;

import mmm.units.SignedDyadic;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.TreeMap;

/** Floating piecewise caloric coordinates; stored energy and its publication remain exact. */
final class ThermalCaloricInversion {
	interface Inventory {
		int first(); int next(int item);
		ThermalMaterialDefinition material(int item);
		double mass(int item);
		default SignedDyadic exactMass(int item) { return SignedDyadic.of(mass(item)); }
	}
	private record Single(ThermalMaterialDefinition material, double mass) implements Inventory {
		public int first() { return 0; } public int next(int item) { return -1; }
		public ThermalMaterialDefinition material(int item) { return material; }
		public double mass(int item) { return mass; }
	}
	private static final double COLD_ROUNDOFF_K = 1e-7;
	private ThermalCaloricInversion() { }

	static ThermalStateFunction.State owner(ThermalMaterialDefinition material, double mass, double energy, double base) {
		if (!(Double.isFinite(mass) && mass > 0 && Double.isFinite(energy) && Double.isFinite(base)))
			throw new IllegalArgumentException("Thermal owner inventory is invalid");
		return solve(new Single(material,mass),energy-base);
	}
	static ThermalStateFunction.State single(ThermalMaterialDefinition material, double mass, SignedDyadic sensible) {
		return solve(new Single(material,mass),sensible.toDouble());
	}
	static ThermalStateFunction.State single(ThermalMaterialDefinition material, SignedDyadic mass, SignedDyadic sensible) {
		return single(material,mass.toDouble(),sensible);
	}
	static double heatCapacity(Inventory inventory, double temperature) {
		var total=new Sum();
		for(int item=inventory.first();item>=0;item=inventory.next(item))
			total.add(inventory.mass(item)*inventory.material(item).specificHeatAt(temperature));
		return checkedState(temperature,total.value()).heatCapacityJPerK();
	}
	static double energyAt(ThermalMaterialDefinition material, double mass, double base, double temperature) {
		if (!(Double.isFinite(mass) && mass > 0 && Double.isFinite(base)))
			throw new IllegalArgumentException("Thermal owner inventory is invalid");
		double energy=Math.fma(mass,material.sensibleSpecificEnergyAt(temperature),base);
		if(!Double.isFinite(energy))throw new ThermalStateFunction.EnergyRepresentationException();
		return energy;
	}
	static double modeledRoundoff(ThermalMaterialDefinition material, double mass, double base, double temperature) {
		double sensible=material.sensibleSpecificEnergyAt(temperature),term=mass*sensible;
		double bound=material.caloricSegments().size()*mass*Math.ulp(sensible)+Math.ulp(term)+Math.ulp(base+term)
				+Math.ulp(mass)*Math.abs(material.referenceSpecificEnergyJPerKg()+sensible)
				+mass*material.specificHeatAt(temperature)*Math.ulp(temperature);
		if(!Double.isFinite(bound))throw new ThermalStateFunction.EnergyRepresentationException();
		return bound;
	}
	static ThermalStateFunction.State solve(Inventory inventory,SignedDyadic target) { return solve(inventory,target.toDouble()); }
	static ThermalStateFunction.State solve(Inventory inventory,double target) {
		var capacity=new Sum();var numerator=new Sum();numerator.add(target);
		for(int item=inventory.first();item>=0;item=inventory.next(item)){
			var material=inventory.material(item);double mass=inventory.mass(item);
			requireOwner(material,mass);
			if(material.caloricSegments().size()!=1)return new Curve(inventory).solve(target);
			double part=mass*material.caloricSegments().getFirst().specificHeatJPerKgK();
			capacity.add(part);numerator.add(part*material.referenceTemperatureK());
		}
		double cv=capacity.value();return checkedState(numerator.value()/cv,cv);
	}

	/** Positive piecewise-affine caloric curve with one grouping per actual material. */
	static final class Curve {
		private final ThermalMaterialDefinition[] materials;
		private final double[] masses,knots,energies,capacities;
		private final double coldCapacity,minimumCapacity;
		Curve(Inventory inventory){
			var positions=new IdentityHashMap<ThermalMaterialDefinition,Integer>();
			var groupedMaterials=new ArrayList<ThermalMaterialDefinition>();var groupedMass=new ArrayList<Double>();
			for(int item=inventory.first();item>=0;item=inventory.next(item)){
				var material=inventory.material(item);double mass=inventory.mass(item);requireOwner(material,mass);
				Integer index=positions.get(material);
				if(index==null){positions.put(material,groupedMaterials.size());groupedMaterials.add(material);groupedMass.add(mass);}
				else groupedMass.set(index,groupedMass.get(index)+mass);
			}
			if(groupedMaterials.isEmpty())throw new IllegalArgumentException("Thermal caloric inventory is empty");
			materials=groupedMaterials.toArray(ThermalMaterialDefinition[]::new);
			masses=groupedMass.stream().mapToDouble(Double::doubleValue).toArray();
			var changes=new TreeMap<Double,Double>();var totalCapacity=new Sum();
			for(int group=0;group<materials.length;group++){
				var segments=materials[group].caloricSegments();totalCapacity.add(masses[group]*segments.getFirst().specificHeatJPerKgK());
				for(int segment=1;segment<segments.size();segment++){
					double change=masses[group]*(segments.get(segment).specificHeatJPerKgK()-segments.get(segment-1).specificHeatJPerKgK());
					changes.merge(segments.get(segment).minimumTemperatureK(),change,Double::sum);
				}
			}
			changes.values().removeIf(value->value==0);changes.putIfAbsent(materials[0].referenceTemperatureK(),0.0);
			knots=new double[changes.size()];energies=new double[knots.length];capacities=new double[knots.length];
			coldCapacity=totalCapacity.value();double capacity=coldCapacity,minimum=capacity;var energy=new Sum();
			double first=changes.firstKey();
			for(int group=0;group<materials.length;group++)energy.add(masses[group]*materials[group].sensibleSpecificEnergyBetween(materials[group].referenceTemperatureK(),first));
			int index=0;
			for(var change:changes.entrySet()){
				knots[index]=change.getKey();
				if(index>0)energy.add((knots[index]-knots[index-1])*capacity);
				capacity+=change.getValue();energies[index]=energy.value();capacities[index]=capacity;
				minimum=Math.min(minimum,capacity);index++;
			}
			minimumCapacity=minimum;
		}
		ThermalStateFunction.State solve(SignedDyadic target){return solve(target.toDouble());}
		ThermalStateFunction.State solve(double target){
			if(!Double.isFinite(target))throw new ThermalStateFunction.EnergyRepresentationException();
			int low=0,high=knots.length;
			while(low+1<high){int middle=(low+high)>>>1;if(target>=energies[middle])low=middle;else high=middle;}
			double cv=target<energies[0]?coldCapacity:capacities[low];
			double temperature=knots[low]+(target-energies[low])/cv;
			if(high<knots.length)temperature=Math.min(knots[high],temperature);
			return checkedState(temperature,cv);
		}
		ThermalStateFunction.State stateAt(double temperature){
			int index=java.util.Arrays.binarySearch(knots,temperature);if(index<0)index=-index-2;
			return checkedState(temperature,index<0?coldCapacity:capacities[index]);
		}
		double assignmentTemperatureTolerance(SignedDyadic requested,SignedDyadic absoluteReference,int owners,double temperature){
			var magnitude=new Sum();magnitude.add(absoluteReference.toDouble());
			for(int group=0;group<materials.length;group++)magnitude.add(Math.abs(masses[group]*materials[group].sensibleSpecificEnergyAt(temperature)));
			return COLD_ROUNDOFF_K+((owners+2)*Math.ulp(Math.abs(requested.toDouble())+2*magnitude.value()))/minimumCapacity;
		}
	}
	private static void requireOwner(ThermalMaterialDefinition material,double mass){
		if(material==null||!(mass>0&&Double.isFinite(mass)))throw new IllegalArgumentException("Thermal caloric inventory is invalid");
	}
	private static ThermalStateFunction.State checkedState(double temperature,double capacity){
		if(!Double.isFinite(temperature)||!Double.isFinite(capacity))throw new ThermalStateFunction.EnergyRepresentationException();
		if(!(capacity>0))throw new IllegalArgumentException("Thermal heat capacity is invalid");
		if(temperature< -COLD_ROUNDOFF_K)throw new ThermalStateFunction.EnergyOutsideValidityException();
		return new ThermalStateFunction.State(Math.max(ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K,temperature),capacity);
	}
	static final class Sum {
		private double sum,correction;
		void add(double value){double next=sum+value;correction+=Math.abs(sum)>=Math.abs(value)?(sum-next)+value:(value-next)+sum;sum=next;}
		double value(){return sum+correction;}
	}
}

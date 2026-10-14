package mmm.fluid;

import mmm.thermal.GasLiquidPhaseModel;
import mmm.thermal.GasThermalModel;
import mmm.thermal.ThermalCaloricBounds;
import mmm.thermal.ThermalCaloricBounds.PortionRange;
import mmm.thermal.ThermalCaloricBounds.QuantaRange;
import mmm.thermal.ThermalDomainState;
import mmm.thermal.ThermalLimits;
import mmm.thermal.ThermalPhaseTransition;
import mmm.units.SignedDyadic;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Whole-extent saturation bounds for gas born into a certified complete-source successor. */
final class GasBoilingBatchBounds {
	private record Owner(PortionRange before, Map<String, QuantaRange> remainingSpecies, boolean removable) { }
	private final ThermalCaloricBounds caloric;
	private final ThermalPhaseTransition transition;
	private final GasLiquidPhaseModel phase;
	private final GasPhaseGeometryEnvelope geometry;
	private final List<Owner> owners;
	private final Map<String, QuantaRange> vaporSpecies;
	private final SignedDyadic sourceEnergy, sourceCurveLow, sourceReferenceError, liquidSpecific;
	private final double temperature;
	private final int cell, supportMask, liquidHandle, gasHandle, donors;
	private final long nanosPerBatch;
	private final GasPhaseMergeBounds.Stock sourceGas;
	private final GasPhaseMergeBounds merged;
	private final long sourceVaporNanos;
	private final SignedDyadic vaporSpecific;

	private GasBoilingBatchBounds(ThermalCaloricBounds caloric, ThermalPhaseTransition transition, GasLiquidPhaseModel phase,
	                             GasPhaseGeometryEnvelope geometry, List<Owner> owners, Map<String, QuantaRange> vaporSpecies,
	                             SignedDyadic sourceEnergy, double temperature, int cell, int supportMask, int liquidHandle, int gasHandle, int donors,
	                             GasPhaseMergeBounds.Stock sourceGas, GasPhaseMergeBounds merged, String vaporId) {
		this.caloric=caloric; this.transition=transition; this.phase=phase; this.geometry=geometry; this.owners=List.copyOf(owners);
		this.vaporSpecies=Map.copyOf(vaporSpecies); this.sourceEnergy=sourceEnergy; this.temperature=temperature;
		this.cell=cell; this.supportMask=supportMask; this.liquidHandle=liquidHandle; this.gasHandle=gasHandle; this.donors=donors;
		this.sourceGas=sourceGas; this.merged=merged;
		var vapor=sourceGas.inventory().component(vaporId); this.sourceVaporNanos=vapor==null?0:vapor.standardVolumeNanos();
		var source=owners.stream().map(Owner::before).toList();
		this.sourceCurveLow=SignedDyadic.of(caloric.curveEnergyAtTemperature(source,temperature,false));
		SignedDyadic error=SignedDyadic.ZERO;
		for(var owner:owners) error=error.add(SignedDyadic.of(caloric.referenceRoundingBound(owner.before())));
		this.sourceReferenceError=error;
		this.liquidSpecific=caloric.specificCurveEnergy(liquidHandle,temperature);
		this.vaporSpecific=caloric.specificCurveEnergy(gasHandle,temperature);
		if(vaporSpecific.compareTo(liquidSpecific)<0)
			throw new IllegalArgumentException("Same-temperature vapor energy is below its liquid energy");
		this.nanosPerBatch=phase.gasBatch(transition,1).standardVolumeNanos();
	}

	static GasBoilingBatchBounds forBoiling(PhaseChangeStageSupport support, int cell, int node, int liquidHandle,
	                                      ThermalPhaseTransition transition, ThermalDomainState.PhaseCandidateCell thermalCell,
	                                      double temperature, boolean[] volatileSpecies, int supportMask,
	                                      GasLiquidPhaseModel phase, GasPhaseGeometryEnvelope geometry) {
		if(geometry==null) return null;
		try {
			var registry=support.thermal().registry(); var state=support.state(); var particles=state.particles(); var chemistry=support.chemistry();
			int liquid=registry.materialHandle(transition.lowerEnergyMaterialId()), gas=registry.materialHandle(transition.upperEnergyMaterialId());
			var owners=new ArrayList<Owner>(); var vapor=new TreeMap<String,QuantaRange>(); int donors=0;
			for(int marker=state.markerGrid().cellMarkerHead[cell];marker>=0;marker=state.markerGrid().particleCellNext[marker]) {
				int handle=support.thermal().thermalMaterialHandleForFluid(particles.materialHandle(marker));
				var species=new TreeMap<String,QuantaRange>(); var remaining=new TreeMap<String,QuantaRange>();
				boolean removable=particles.materialHandle(marker)==liquidHandle
						&& CellRemapMicroTopology.positionSupport(state.geometry().cutCells.mask(cell),state.cellSize(),
						particles.positionX(marker),particles.positionY(marker),particles.positionZ(marker))==supportMask
						&& (chemistry==null || !chemistry.indicator(marker));
				boolean hasVolatile=chemistry==null;
				if(chemistry!=null) for(var entry:chemistry.record(marker).stableAmounts(chemistry.registry()).entrySet()) {
					long amount=entry.getValue(); boolean moved=removable && volatileSpecies[chemistry.registry().speciesHandle(entry.getKey())];
					species.put(entry.getKey(),new QuantaRange(amount,amount)); remaining.put(entry.getKey(),new QuantaRange(moved?0:amount,amount));
					if(moved) {
						if(!registry.speciesOffset(liquid,entry.getKey()).referenceInternalEnergyEuPerAu()
								.equals(registry.speciesOffset(gas,entry.getKey()).referenceInternalEnergyEuPerAu())) return null;
						vapor.merge(entry.getKey(),new QuantaRange(0,amount),(a,b)->new QuantaRange(0,Math.addExact(a.high(),b.high()))); hasVolatile=true;
					}
				}
				removable &= hasVolatile;
				if(removable) { if(handle!=liquid) return null; donors++; }
				double mass=state.unitScale().kilograms(particles.mass(marker));
				owners.add(new Owner(new PortionRange(handle,mass,mass,species),remaining,removable));
			}
			for(var solid:thermalCell.unchangedSolids()) {
				var species=new TreeMap<String,QuantaRange>(); solid.speciesQuanta().forEach((id,q)->species.put(id,new QuantaRange(q,q)));
				var portion=new PortionRange(solid.materialHandle(),solid.massMu(),solid.massMu(),species); owners.add(new Owner(portion,species,false));
			}
			if(donors==0) return null;
			var network=support.gasState().network;
			var sourceGas=new GasPhaseMergeBounds.Stock(network.inventory(node),support.thermal().gasNodeEnergy(node,network),
					SignedDyadic.of(network.availableVolume(node)));
			if(sourceGas.inventory().isEmpty() && sourceGas.energy()!=0) return null;
			return new GasBoilingBatchBounds(new ThermalCaloricBounds(registry),transition,phase,geometry,owners,vapor,
					thermalCell.cellEnergy(),temperature,cell,supportMask,liquid,gas,donors,sourceGas,
					new GasPhaseMergeBounds(registry,new GasThermalModel(registry,network.registry()),geometry.possibleStocks()),
					registry.material(gas).carrierId());
		} catch(IllegalArgumentException | ArithmeticException unresolved) { return null; }
	}

	boolean excluded(long low, long high) {
		try {
			double massLow=transition.massMuPerBatch().multiply(low).toDouble(), massHigh=transition.massMuPerBatch().multiply(high).toDouble();
			double tolerance=PhaseChangeStageSupport.massTolerance(massHigh);
			var gas=new PortionRange(gasHandle,Math.max(0,Math.nextDown(massLow-PhaseChangeStageSupport.massTolerance(massLow))),
					Math.nextUp(massHigh+tolerance),vaporSpecies);
			double forwardError=caloric.forwardRoundingBound(gas,temperature);
			if(sourceGas.energy()!=0) {
				// The liquid assignment subtracts the rounded source-plus-born gas U.
				SignedDyadic magnitude=abs(SignedDyadic.of(sourceGas.energy())).add(energyMagnitude(List.of(gas),temperature,temperature));
				forwardError=Math.nextUp(SignedDyadic.of(forwardError)
						.add(SignedDyadic.of(Math.ulp(Math.nextUp(magnitude.toDouble())))).toDouble());
			}
			double gasLower=sourceGas.inventory().isEmpty() && geometry.possibleStocks().isEmpty()
					?caloric.temperatureWithEnergyError(List.of(gas),temperature,forwardError,false)
					:merged.temperatureLower(sourceGas.inventory(),sourceGas.energy(),gas,temperature,forwardError);
			// Each donor's actual kg change, born gas mass and final requested-mass remainder pass the existing
			// mass check. Each intervening positive remainder subtraction contributes at most one ULP.
			SignedDyadic massError=SignedDyadic.of(tolerance).multiply(SignedDyadic.of(donors+2L,0))
					.add(SignedDyadic.of(Math.ulp(massHigh)).multiply(SignedDyadic.of(donors,0)));
			double removedHigh=Math.nextUp(SignedDyadic.of(massHigh).add(massError).toDouble());
			var remaining=new ArrayList<PortionRange>(); var slope=new ArrayList<PortionRange>();
			SignedDyadic referenceError=sourceReferenceError, removableMass=SignedDyadic.ZERO;
			for(var owner:owners) {
				var before=owner.before(); double least=owner.removable()?Math.max(0,Math.nextDown(before.massHigh()-removedHigh)):before.massLow();
				var portion=new PortionRange(before.materialHandle(),least,before.massHigh(),owner.removable()?owner.remainingSpecies():before.species());
				remaining.add(portion); referenceError=referenceError.add(SignedDyadic.of(caloric.referenceRoundingBound(portion)));
				if(owner.removable()) removableMass=removableMass.add(SignedDyadic.of(before.massHigh())); else slope.add(before);
			}
			// Donor identities can change; their total retained mass has one conserved lower bound.
			double retained=Math.max(0,Math.nextDown(removableMass.subtract(SignedDyadic.of(massHigh)).subtract(massError).toDouble()));
			slope.add(new PortionRange(liquidHandle,retained,Math.nextUp(removableMass.toDouble()),Map.of()));
			// Complete depletion is checked against the future gas temperature by the actual candidate.
			if(slope.stream().noneMatch(portion->portion.massLow()>0)) return false;
			// Equal host species offsets cancel by exact integer conservation, even across carrier holes.
			SignedDyadic error=sourceEnergy.subtract(sourceCurveLow).add(referenceError)
					.add(SignedDyadic.of(caloric.referenceRoundingBound(gas))).add(SignedDyadic.of(forwardError))
					.add(massError.multiply(abs(liquidSpecific)));
			if(error.signum()<0) error=SignedDyadic.ZERO;
			double requestedUpper=caloric.temperatureWithEnergyError(slope,temperature,Math.nextUp(error.toDouble()),true);
			if(!Double.isFinite(requestedUpper)) return false;
			SignedDyadic magnitude=abs(sourceEnergy);
			magnitude=magnitude.add(energyMagnitude(List.of(gas),temperature,temperature));
			for(var portion:remaining) magnitude=magnitude.add(energyMagnitude(List.of(portion),ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K,requestedUpper));
			// The assignment's pivot is requested U minus the exact sum of the other stored columns.
			double pivotError=Math.ulp(Math.nextUp(magnitude.toDouble()));
			double liquidUpper=caloric.temperatureWithEnergyError(slope,temperature,Math.nextUp(error.add(SignedDyadic.of(pivotError)).toDouble()),true);
			SignedDyadic cooling=SignedDyadic.of(gas.massLow()).multiply(vaporSpecific.subtract(liquidSpecific))
					.subtract(error).subtract(SignedDyadic.of(pivotError));
			if(cooling.signum()>0) {
				// Maximum remaining heat capacity bounds cooling across the full caloric curve.
				var maximumSlope=slope.stream().map(portion->new PortionRange(portion.materialHandle(),portion.massHigh(),portion.massHigh(),Map.of())).toList();
				double target=Math.nextUp(SignedDyadic.of(caloric.curveEnergyAtTemperature(maximumSlope,temperature,true)).subtract(cooling).toDouble());
				liquidUpper=Math.min(liquidUpper,caloric.temperatureBound(maximumSlope,target,true,temperature));
			}
			double volume=geometry.boilingVolumeUpper(cell,supportMask,Math.multiplyExact(high,transition.lowerVolumeSvqPerBatch()));
			if(!(Double.isFinite(volume) && volume>0 && Double.isFinite(liquidUpper))) return false;
			long nanos=Math.addExact(sourceVaporNanos,Math.multiplyExact(low,nanosPerBatch));
			double pressure=GasPhaseMergeBounds.partialPressureLower(nanos,gasLower,volume);
			return phase.saturationPressureBound(transition,liquidUpper,true)<pressure;
		} catch(IllegalArgumentException | ArithmeticException unresolved) { return false; }
	}

	private SignedDyadic energyMagnitude(List<PortionRange> portions, double low, double high) {
		return SignedDyadic.of(Math.nextUp(Math.max(Math.abs(caloric.energyAtTemperature(portions,low,false)),
				Math.abs(caloric.energyAtTemperature(portions,high,true)))));
	}
	private static SignedDyadic abs(SignedDyadic value) { return value.signum()<0?value.negate():value; }
}

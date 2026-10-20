package mmm.fluid;

import mmm.inventory.LiquidCellInventory;

import mmm.chemistry.ChemicalMixtureRecord;
import mmm.fluid.air.AirNetwork;
import mmm.granular.SolidVolume;
import mmm.thermal.*;
import mmm.units.Mass;
import mmm.units.SignedDyadic;
import java.util.*;

/** Batched coherent-solid/liquid equilibrium over the same persistent liquid cells. */
final class CondensedPhaseChangeStage {
	private final PhaseChangeStageSupport support;
	CondensedPhaseChangeStage(PhaseChangeStageSupport support) { this.support=support; }
	private record GroupKey(int cell,String phase) implements Comparable<GroupKey> {
		public int compareTo(GroupKey other) { int n=Integer.compare(cell,other.cell);return n!=0?n:phase.compareTo(other.phase); }
	}
	private static final class Proposal {
		final int cell,liquid;
		final ThermalPhaseTransition transition;
		final boolean freezing;
		final long batchMass;
		final double equilibrium,score;
		final List<LiquidCellInventory.Stock> sources;
		long batches,removedMass;
		Proposal(int cell,int liquid,ThermalPhaseTransition transition,boolean freezing,long batchMass,
		         double equilibrium,double score,List<LiquidCellInventory.Stock> sources,long batches) {
			this.cell=cell;this.liquid=liquid;this.transition=transition;this.freezing=freezing;this.batchMass=batchMass;
			this.equilibrium=equilibrium;this.score=score;this.sources=sources;this.batches=batches;
		}
	}
	FluidDomain.ThermalPhaseStats step(int maximumOperations,boolean phaseGridPrepared) {
		var thermal=support.thermal();
		if(thermal==null) throw new IllegalStateException("Condensed phase requires thermal inventory");
		if(maximumOperations<0||maximumOperations>ThermalLimits.MAX_PHASE_OPERATIONS_PER_STEP) throw new IllegalArgumentException("Invalid phase operation budget");
		if(maximumOperations==0) return new FluidDomain.ThermalPhaseStats(0,0,0,0);
		if(!phaseGridPrepared) thermal.preparePhaseScan(support.state().thermalMarkers());
		var store=support.liquidCells();var function=new ThermalStateFunction(thermal.registry());
		var selectedCells=new TreeSet<Integer>();
		var originalGroups=thermal.coherentPhases().snapshot();
		var temperatureRange=thermal.observedCarrierTemperatureRange();
		record Candidate(int cell,int liquid,ThermalPhaseTransition transition) { }
		var candidates=new ArrayList<Candidate>();
		for(var transition:thermal.registry().transitions()) {
			if(transition.interfaceRequirement()!=ThermalPhaseTransition.InterfaceRequirement.NONE) continue;
			int lower=thermal.registry().materialHandle(transition.lowerEnergyMaterialId()),upper=thermal.registry().materialHandle(transition.upperEnergyMaterialId());
			if(thermal.registry().material(lower).carrierKind()!=ThermalMaterialDefinition.CarrierKind.COHERENT_SOLID
					||thermal.registry().material(upper).carrierKind()!=ThermalMaterialDefinition.CarrierKind.LIQUID) continue;
			int liquid=support.fluidMaterialHandle(thermal.registry().material(upper).carrierId());
			boolean hasLiquid=store.hasMaterial(liquid),hasCoherent=false;
			for(var group:originalGroups) if(group.phaseId().equals(transition.lowerEnergyMaterialId())) { hasCoherent=true;break; }
			if(!hasLiquid&&!hasCoherent) continue;
			double equilibrium=transition.equilibriumCurve().getFirst().temperatureK();
			boolean constant=true;
			for(var point:transition.equilibriumCurve()) if(point.temperatureK()!=equilibrium) { constant=false;break; }
			if(constant&&temperatureRange!=null
					&&(!hasLiquid||temperatureRange.minimumK()>=equilibrium)
					&&(!hasCoherent||temperatureRange.maximumK()<=equilibrium)) continue;
			var possible=new TreeSet<Integer>();
			var liquidSupports=new TreeMap<Integer,Integer>();
			for(var stock:store.physicalStocks()) if(stock.key().material()==liquid&&!stock.indicator())
				liquidSupports.merge(stock.key().cell(),stock.key().supportMask(),(a,b)->a|b);
			for(var entry:liquidSupports.entrySet()) {
				int cell=entry.getKey();
				double pressure=Math.max(0,AirNetwork.REFERENCE_PRESSURE+support.liquidPressureAt(cell,entry.getValue()));
				if(thermal.phaseScanTemperatureInCell(cell)<transition.equilibriumTemperatureK(pressure)) possible.add(cell);
			}
			for(var group:originalGroups) if(group.phaseId().equals(transition.lowerEnergyMaterialId())) {
				int cell=group.cell(),liquidSupport=0;
				for(var stock:store.physicalStocksInCell(cell)) if(stock.key().material()==liquid&&!stock.indicator()) liquidSupport|=stock.key().supportMask();
				if(liquidSupport==0) liquidSupport=~support.state().geometry().cutCells.mask(cell)&255;
				double pressure=Math.max(0,AirNetwork.REFERENCE_PRESSURE+support.liquidPressureAt(cell,liquidSupport));
				if(thermal.phaseScanTemperatureInCell(cell)>transition.equilibriumTemperatureK(pressure)) possible.add(cell);
			}
			for(int cell:possible) { selectedCells.add(cell);candidates.add(new Candidate(cell,liquid,transition)); }
		}
		if(candidates.isEmpty()) return new FluidDomain.ThermalPhaseStats(0,0,0,0);
		support.prepareGranularOccupancy();
		var captured=thermal.preparePhaseCandidateCells(selectedCells.stream().mapToInt(Integer::intValue).toArray(),support.state().thermalMarkers());
		var selected=new TreeMap<Integer,Proposal>();
		for(var candidate:candidates) {
			var proposal=propose(candidate.cell(),candidate.liquid(),candidate.transition(),captured[candidate.cell()],function,maximumOperations);
			if(proposal!=null&&(selected.get(candidate.cell())==null||proposal.score>selected.get(candidate.cell()).score)) selected.put(candidate.cell(),proposal);
		}
		long desired=0;for(var proposal:selected.values()) desired+=proposal.batches;
		if(desired==0) return new FluidDomain.ThermalPhaseStats(0,0,0,0);
		if(desired>maximumOperations) {
			long assigned=0;var remainder=new IdentityHashMap<Proposal,Double>();
			for(var p:selected.values()) { double target=p.batches*(maximumOperations/(double)desired);p.batches=(long)Math.floor(target);assigned+=p.batches;remainder.put(p,target-p.batches); }
			var order=new ArrayList<>(selected.values());order.sort(Comparator.<Proposal>comparingDouble(remainder::get).reversed().thenComparingInt(p->p.cell));
			for(int i=0;assigned<maximumOperations;i++,assigned++) order.get(i).batches++;
		}
		var futureGroups=new TreeMap<GroupKey,CoherentPhaseStore.Group>();
		for(var group:originalGroups) futureGroups.put(new GroupKey(group.cell(),group.phaseId()),group);
		var futureCells=new TreeMap<Integer,List<LiquidCellInventory.Stock>>();
		int operations=0,frozen=0,melted=0;long signed=0;
		for(var p:selected.values()) if(p.batches>0) {
			futureCells.put(p.cell,p.freezing?freeze(p,futureGroups,function):melt(p,futureGroups,function));
			operations+=Math.toIntExact(p.batches);signed+=p.freezing?p.batches:-p.batches;
			if(p.freezing) frozen++;else melted++;
		}
		var provisional=store.planEdits(edits(futureCells,null));
		var momentum=support.preparePhaseMomentum(provisional);
		var heat=new TreeMap<Integer,SignedDyadic>(momentum.heatByCell());
		long removed=0;for(var p:selected.values()) removed+=p.removedMass;
		SignedDyadic remaining=SignedDyadic.of(momentum.removedKineticEnergy());long remainingMass=removed;
		for(var p:selected.values()) if(p.removedMass>0) {
			var share=p.removedMass==remainingMass?remaining:SignedDyadic.of(remaining.toDouble()*(p.removedMass/(double)remainingMass));
			heat.merge(p.cell,share,SignedDyadic::add);remaining=remaining.subtract(share);remainingMass-=p.removedMass;
		}
		if(remaining.signum()!=0) throw new IllegalStateException("Frozen liquid kinetic energy lacks a coherent receiver");
		for(int cell:heat.keySet()) futureCells.putIfAbsent(cell,store.stocksInCell(cell));
		captured=thermal.preparePhaseCandidateCells(futureCells.keySet().stream().mapToInt(Integer::intValue).toArray(),support.state().thermalMarkers());
		SignedDyadic mechanicalHeat=SignedDyadic.ZERO;for(var value:heat.values()) mechanicalHeat=mechanicalHeat.add(value);
		var trials=new ArrayList<ThermalDomainState.PhaseCellTrial>();int[] counts=new int[futureCells.size()];int index=0;
		for(var entry:futureCells.entrySet()) {
			int cell=entry.getKey();var inventory=portions(entry.getValue());
			counts[index++]=inventory.size();
			for(var group:futureGroups.values()) if(group.cell()==cell) inventory.add(coherentPortion(group));
			trials.add(thermal.trialCondensedCellEnergyWithHeat(captured[cell],inventory,heat.getOrDefault(cell,SignedDyadic.ZERO)));
		}
		var groups=List.copyOf(futureGroups.values());
		var energy=thermal.planCondensedBatchEnergy(trials,counts,mechanicalHeat,support.state().thermalMarkers(),groups);
		var liquid=store.planEdits(edits(futureCells,energy));
		var gas=support.planCondensedGeometry(liquid.future(),groups);
		liquid.requireCurrent();thermal.requirePhaseBatchEnergyCurrent(energy);
		if(gas!=null) support.requireGeometryCurrent(gas);
		liquid.commit();support.commitPhaseMomentum(momentum.momentum());
		thermal.commitPhaseBatchEnergy(energy,support.state().thermalMarkers());
		for(var p:selected.values()) if(p.batches>0) {
			long delta=p.batches*(p.freezing?p.transition.lowerVolumeSvqPerBatch()-p.transition.upperVolumeSvqPerBatch()
					:p.transition.upperVolumeSvqPerBatch()-p.transition.lowerVolumeSvqPerBatch());
			if(delta!=0) support.state().pendingPhaseVolume().add(p.cell,normalized(delta));
			if(support.chemistry()!=null) support.chemistry().markDirtyCell(p.cell);
		}
		support.refreshCoherentOccupancy();support.markGridDirty();if(gas!=null) support.commitGasGeometry(gas);
		return new FluidDomain.ThermalPhaseStats(operations,frozen,melted,signed);
	}

	private Proposal propose(int cell,int liquid,ThermalPhaseTransition transition,ThermalDomainState.PhaseCandidateCell current,
	                         ThermalStateFunction function,int maximum) {
		var thermal=support.thermal();var store=support.liquidCells();
		int liquidSupport=0;for(var stock:store.physicalStocksInCell(cell)) if(stock.key().material()==liquid&&!stock.indicator()) liquidSupport|=stock.key().supportMask();
		if(liquidSupport==0)liquidSupport=~support.state().geometry().cutCells.mask(cell)&255;
		double pressure=Math.max(0,AirNetwork.REFERENCE_PRESSURE+support.liquidPressureAt(cell,liquidSupport));
		double equilibrium=transition.equilibriumTemperatureK(pressure),temperature=thermal.phaseScanTemperatureInCell(cell);
		if(temperature==equilibrium) return null;
		boolean freezing=temperature<equilibrium;
		var sources=store.physicalStocksInCell(cell).stream().filter(s->s.key().material()==liquid&&!s.indicator()).toList();
		int groupIndex=thermal.coherentPhases().find(cell,transition.lowerEnergyMaterialId());
		var coherent=groupIndex<0?null:thermal.coherentPhases().group(groupIndex);
		if(freezing&&sources.isEmpty()||!freezing&&coherent==null) return null;
		long batchMass=Mass.exactUnits(transition.massKgPerBatch().numerator(),transition.massKgPerBatch().denominator());
		long mass=freezing?sources.stream().mapToLong(LiquidCellInventory.Stock::massUnits).reduce(0,Math::addExact):coherent.massUnits();
		SignedDyadic volume=SignedDyadic.ZERO;
		if(freezing) for(var stock:sources) volume=volume.add(stock.volume());else volume=normalized(coherent.occupiedVolumeSvq());
		long sourceVolume=freezing?transition.upperVolumeSvqPerBatch():transition.lowerVolumeSvqPerBatch();
		long high=Math.min(maximum,Math.min(mass/batchMass,volumeBatches(volume,normalized(sourceVolume),maximum)));
		long delta=freezing?transition.lowerVolumeSvqPerBatch()-transition.upperVolumeSvqPerBatch()
				:transition.upperVolumeSvqPerBatch()-transition.lowerVolumeSvqPerBatch();
		if(delta>0) {
			SignedDyadic occupied=normalized(support.granularSvq(cell));
			for(var stock:store.stocksInCell(cell)) occupied=occupied.add(stock.volume());
			for(var group:thermal.coherentPhases().snapshot()) if(group.cell()==cell) occupied=occupied.add(normalized(group.occupiedVolumeSvq()));
			SignedDyadic room=SignedDyadic.of(support.state().geometry().cutCells.openCellVolume(cell)).subtract(occupied);
			high=Math.min(high,volumeBatches(room,normalized(delta),maximum));
		}
		if(high==0) return null;
		double equilibriumEnergy=0;SignedDyadic minimumEnergy=SignedDyadic.ZERO;
		for(var stock:store.physicalStocksInCell(cell)) {
			int handle=thermal.thermalMaterialHandleForFluid(stock.key().material());
			equilibriumEnergy+=energy(function,handle,stock.massKilograms(),stock.mixture(),equilibrium);
			minimumEnergy=minimumEnergy.add(SignedDyadic.of(energy(function,handle,stock.massKilograms(),stock.mixture(),ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K)));
		}
		for(var portion:current.unchangedSolids()) {
			equilibriumEnergy+=function.internalEnergyAtTemperature(portion.materialHandle(),portion.massKg(),portion.speciesQuanta(),equilibrium);
			minimumEnergy=minimumEnergy.add(SignedDyadic.of(function.internalEnergyAtTemperature(portion.materialHandle(),portion.massKg(),portion.speciesQuanta(),ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K)));
		}
		Map<String,Long> composition=new TreeMap<>();
		if(freezing) for(var stock:sources) add(composition,amounts(stock.mixture()));else if(coherent.composition()!=null) composition.putAll(coherent.composition().stableAmounts());
		int lower=thermal.registry().materialHandle(transition.lowerEnergyMaterialId()),upper=thermal.registry().materialHandle(transition.upperEnergyMaterialId());
		double kg=Mass.kilograms(mass);
		// Positive heat capacities make the cold endpoint the energy feasibility limit.
		// A phase with a smaller Cv can have a higher cold endpoint despite its latent heat.
		int from=freezing?upper:lower,to=freezing?lower:upper;
		double coldIncrease=(function.internalEnergyAtTemperature(to,kg,composition,ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K)
				-function.internalEnergyAtTemperature(from,kg,composition,ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K))/kg;
		if(coldIncrease>0) {
			double available=Math.max(0,current.cellEnergy().subtract(minimumEnergy).toDouble());
			high=Math.min(high,(long)Math.floor(available/(coldIncrease*Mass.kilograms(batchMass))));
			if(high==0)return null;
		}
		double latent=(function.internalEnergyAtTemperature(upper,kg,composition,equilibrium)-function.internalEnergyAtTemperature(lower,kg,composition,equilibrium))/kg;
		if(!(latent>0)) throw new IllegalArgumentException("Condensed transition has nonpositive latent heat");
		double desired=Math.abs(current.cellEnergy().toDouble()-equilibriumEnergy)/(latent*Mass.kilograms(batchMass));
		long batches=Math.min(high,Math.max(0,Math.round(desired)));
		return batches==0?null:new Proposal(cell,liquid,transition,freezing,batchMass,equilibrium,Math.abs(temperature-equilibrium),sources,batches);
	}

	private List<LiquidCellInventory.Stock> freeze(Proposal p,TreeMap<GroupKey,CoherentPhaseStore.Group> groups,ThermalStateFunction function) {
		var store=support.liquidCells();var rows=new TreeMap<LiquidCellInventory.Key,LiquidCellInventory.Stock>();for(var stock:store.stocksInCell(p.cell)) rows.put(stock.key(),stock);
		SignedDyadic target=normalized(Math.multiplyExact(p.batches,p.transition.upperVolumeSvqPerBatch())),total=SignedDyadic.ZERO;
		long sourceMass=0;for(var stock:p.sources) { total=total.add(stock.volume());sourceMass=Math.addExact(sourceMass,stock.massUnits()); }
		long requested=target.equals(total)?sourceMass:Math.multiplyExact(p.batches,p.batchMass);
		double[] weights=p.sources.stream().mapToDouble(LiquidCellInventory.Stock::massUnits).toArray();int[] ids=new int[weights.length];for(int i=0;i<ids.length;i++) ids[i]=i;
		long[] masses=mmm.chemistry.BinaryVolumeAllocator.allocate(requested,weights,ids);
		var composition=new TreeMap<String,Long>();SignedDyadic remaining=target;int last=p.sources.size()-1;
		for(int i=0;i<p.sources.size();i++) {
			var stock=p.sources.get(i);
			var take=target.equals(total)?stock.volume():i==last?remaining:SignedDyadic.of(target.toDouble()*(stock.volumeFraction()/total.toDouble()));
			remaining=remaining.subtract(take);var volume=stock.volume().subtract(take);long mass=stock.massUnits()-masses[i];
			var moved=split(amounts(stock.mixture()),masses[i],stock.massUnits());add(composition,moved);
			var kept=amounts(stock.mixture());for(var entry:moved.entrySet()) {long n=kept.get(entry.getKey())-entry.getValue();if(n==0)kept.remove(entry.getKey());else kept.put(entry.getKey(),n);}
			if(volume.signum()==0) { if(mass!=0)throw new IllegalStateException("Freezing volume and integer mass disagree");rows.remove(stock.key()); }
			else rows.put(stock.key(),new LiquidCellInventory.Stock(stock.key(),volume,mass,mixture(kept,mass),mass==0?0:stock.internalEnergy(),stock.indicator()));
		}
		long svq=Math.multiplyExact(p.batches,p.transition.lowerVolumeSvqPerBatch());var key=new GroupKey(p.cell,p.transition.lowerEnergyMaterialId());var old=groups.get(key);
		if(old!=null) {svq=Math.addExact(svq,old.occupiedVolumeSvq());if(old.composition()!=null)add(composition,old.composition().stableAmounts());}
		long mass=Math.addExact(requested,old==null?0:old.massUnits());
		groups.put(key,new CoherentPhaseStore.Group(p.cell,key.phase,svq,mass,coherentComposition(composition,mass),old==null?0:old.internalEnergy()));
		p.removedMass=requested;return List.copyOf(rows.values());
	}
	private List<LiquidCellInventory.Stock> melt(Proposal p,TreeMap<GroupKey,CoherentPhaseStore.Group> groups,ThermalStateFunction function) {
		var store=support.liquidCells();var key=new GroupKey(p.cell,p.transition.lowerEnergyMaterialId());
		var take=support.thermal().coherentPhases().previewSplit(p.cell,key.phase,Math.multiplyExact(p.batches,p.transition.lowerVolumeSvqPerBatch()),store.chemicalRegistry());
		if(take.remaining()==null)groups.remove(key);else groups.put(key,take.remaining());
		var rows=new TreeMap<LiquidCellInventory.Key,LiquidCellInventory.Stock>();for(var stock:store.stocksInCell(p.cell)) rows.put(stock.key(),stock);
		int mask=support.state().geometry().cutCells.mask(p.cell),best=0;double largest=-1;
		for(int component:CellRemapMicroTopology.components(mask)) {
			double occupied=0;for(var stock:rows.values())if(stock.key().supportMask()==component)occupied+=stock.volumeFraction();
			double room=Integer.bitCount(component)/8.0-occupied;if(room>largest){largest=room;best=component;}
		}
		if(best==0)throw new IllegalStateException("Melting cell has no open component");
		var liquidKey=new LiquidCellInventory.Key(p.cell,p.liquid,best);var old=rows.get(liquidKey);var composition=new TreeMap<String,Long>();
		if(take.removed().composition()!=null)composition.putAll(take.removed().composition().stableAmounts());
		if(old!=null)add(composition,amounts(old.mixture()));
		long mass=Math.addExact(take.removed().massUnits(),old==null?0:old.massUnits());
		var volume=normalized(Math.multiplyExact(p.batches,p.transition.upperVolumeSvqPerBatch()));if(old!=null)volume=volume.add(old.volume());
		var mixture=mixture(composition,mass);int handle=support.thermal().thermalMaterialHandleForFluid(p.liquid);
		rows.put(liquidKey,new LiquidCellInventory.Stock(liquidKey,volume,mass,mixture,energy(function,handle,Mass.kilograms(mass),mixture,p.equilibrium),false));
		return List.copyOf(rows.values());
	}
	private List<LiquidCellInventory.Edit> edits(Map<Integer,List<LiquidCellInventory.Stock>> future,ThermalDomainState.PhaseBatchEnergyPlan energy) {
		var edits=new ArrayList<LiquidCellInventory.Edit>();
		for(var entry:future.entrySet()) {
			var keys=new HashSet<LiquidCellInventory.Key>();double[] values=energy==null?null:energy.liquidEnergies(entry.getKey());int ordinal=0;
			for(var stock:entry.getValue()) {
				keys.add(stock.key());double u=stock.massUnits()==0?0:values==null?stock.internalEnergy():values[ordinal++];
				edits.add(new LiquidCellInventory.Edit(stock.key(),new LiquidCellInventory.Stock(stock.key(),stock.volume(),stock.massUnits(),stock.mixture(),u,stock.indicator())));
			}
			for(var before:support.liquidCells().stocksInCell(entry.getKey()))if(!keys.contains(before.key()))edits.add(new LiquidCellInventory.Edit(before.key(),null));
		}
		return edits;
	}
	private ArrayList<ThermalStateFunction.InventoryPortion> portions(List<LiquidCellInventory.Stock> stocks) {
		var portions=new ArrayList<ThermalStateFunction.InventoryPortion>();for(var stock:stocks)if(stock.massUnits()>0)portions.add(new ThermalStateFunction.InventoryPortion(
				support.thermal().thermalMaterialHandleForFluid(stock.key().material()),stock.massKilograms(),amounts(stock.mixture())));return portions;
	}
	private ThermalStateFunction.InventoryPortion coherentPortion(CoherentPhaseStore.Group group) {
		return new ThermalStateFunction.InventoryPortion(support.thermal().registry().materialHandle(group.phaseId()),group.mass(),
				group.composition()==null?Map.of():group.composition().stableAmounts());
	}
	private double energy(ThermalStateFunction f,int handle,double mass,ChemicalMixtureRecord mixture,double t) {
		return f.internalEnergyAtTemperature(handle,mass,mixture,support.liquidCells().chemicalRegistry(),t);
	}
	private TreeMap<String,Long> amounts(ChemicalMixtureRecord mixture) {
		return mixture==null?new TreeMap<>():new TreeMap<>(mixture.stableAmounts(support.liquidCells().chemicalRegistry()));
	}
	private ChemicalMixtureRecord mixture(Map<String,Long> composition,long mass) {
		var chemistry=support.liquidCells().chemicalRegistry();if(chemistry==null)return null;
		var result=ChemicalMixtureRecord.fromStableAmounts(chemistry,composition,0);return result.withMassResidualUnits(mass-result.speciesMassUnits(chemistry));
	}
	private CoherentPhaseStore.Composition coherentComposition(Map<String,Long> amounts,long mass) {
		if(amounts.isEmpty())return null;var mixture=mixture(amounts,mass);var parts=new ArrayList<CoherentPhaseStore.Component>();
		for(var entry:new TreeMap<>(amounts).entrySet())parts.add(new CoherentPhaseStore.Component(entry.getKey(),entry.getValue()));
		return new CoherentPhaseStore.Composition(parts,mixture.massResidualUnits());
	}
	private static void add(Map<String,Long> to,Map<String,Long> from) {for(var entry:from.entrySet())to.merge(entry.getKey(),entry.getValue(),Math::addExact);}
	private static TreeMap<String,Long> split(Map<String,Long> source,long take,long total) {
		var result=new TreeMap<String,Long>();for(var entry:source.entrySet()){long n=Mass.portion(entry.getValue(),take,total);if(n>0)result.put(entry.getKey(),n);}return result;
	}
	private SignedDyadic normalized(long svq) { return SignedDyadic.of(svq,-27).multiply(SignedDyadic.of(1/support.state().unitScale().cubicMetersPerNormalizedVolume())); }
	private static long volumeBatches(SignedDyadic volume,SignedDyadic batch,long maximum) {
		if(volume.signum()<=0)return 0;long n=Math.min(maximum,(long)Math.floor(volume.toDouble()/batch.toDouble()));
		while(n>0&&batch.multiply(SignedDyadic.of(n)).compareTo(volume)>0)n--;return n;
	}
	static long minimumBatchesForCapacity(long occupied,long capacity,long delta) {
		if(occupied<0||capacity<0)throw new IllegalArgumentException("Invalid phase capacity");
		if(occupied<=capacity)return 1;if(delta>=0)return Long.MAX_VALUE;long excess=occupied-capacity,shrink=-delta;return excess/shrink+(excess%shrink==0?0:1);
	}
}

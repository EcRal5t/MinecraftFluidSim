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
	private final FluidDomain domain;
	private final mmm.granular.GranularCouplingFields phaseGranularOccupancy;
	CondensedPhaseChangeStage(FluidDomain domain) {
		this.domain=domain;
		phaseGranularOccupancy=new mmm.granular.GranularCouplingFields(domain.sizeX,domain.sizeY,domain.sizeZ,domain.config.cellSize());
	}
	private void prepareGranularOccupancy(){
		if(domain.granularState==null)phaseGranularOccupancy.clear();
		else phaseGranularOccupancy.rebuild(domain.granularState.parcels,domain.granularState.deposits);
	}
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
	FluidDomain.ThermalPhaseStats step(int maximumOperations) {
		var thermal=domain.thermalState;
		if(thermal==null) throw new IllegalStateException("Condensed phase requires thermal inventory");
		if(maximumOperations<0||maximumOperations>ThermalLimits.MAX_PHASE_OPERATIONS_PER_STEP) throw new IllegalArgumentException("Invalid phase operation budget");
		if(maximumOperations==0) return new FluidDomain.ThermalPhaseStats(0,0,0,0);
		thermal.preparePhaseScan(domain.domainCapabilities.thermalMarkerContext());
		var store=domain.liquidCells;var function=thermal.stateFunction();
		var selectedCells=new TreeSet<Integer>();
		var originalGroups=thermal.coherentPhases().snapshot();
		var coherentByCell=new HashMap<Integer,List<CoherentPhaseStore.Group>>();
		for(var group:originalGroups) coherentByCell.computeIfAbsent(group.cell(),ignored->new ArrayList<>()).add(group);
		var temperatureRange=thermal.observedCarrierTemperatureRange();
		double maximumPressure = domain.pressureSystem.liquidPressureUpperBoundPa();
		record Candidate(int cell,int liquid,ThermalPhaseTransition transition,double equilibrium,double temperature) { }
		var candidates=new ArrayList<Candidate>();
		for(var transition:thermal.registry().transitions()) {
			if(transition.interfaceRequirement()!=ThermalPhaseTransition.InterfaceRequirement.NONE) continue;
			int lower=thermal.registry().materialHandle(transition.lowerEnergyMaterialId()),upper=thermal.registry().materialHandle(transition.upperEnergyMaterialId());
			if(thermal.registry().material(lower).carrierKind()!=ThermalMaterialDefinition.CarrierKind.COHERENT_SOLID
					||thermal.registry().material(upper).carrierKind()!=ThermalMaterialDefinition.CarrierKind.LIQUID) continue;
			int liquid=domain.materialRegistry.handle(thermal.registry().material(upper).carrierId());
			boolean hasLiquid=store.hasMaterial(liquid),hasCoherent=false;
			for(var group:originalGroups) if(group.phaseId().equals(transition.lowerEnergyMaterialId())) { hasCoherent=true;break; }
			if(!hasLiquid&&!hasCoherent) continue;
			var equilibriumRange = transition.equilibriumTemperatureRange(0.0, maximumPressure);
			double minimumMelting=equilibriumRange.minimumK(),maximumMelting=equilibriumRange.maximumK();
			boolean canFreeze=hasLiquid&&(temperatureRange==null||temperatureRange.minimumK()<maximumMelting);
			boolean canMelt=hasCoherent&&(temperatureRange==null||temperatureRange.maximumK()>minimumMelting);
			if(!canFreeze&&!canMelt)continue;
			boolean constant=minimumMelting==maximumMelting;
			var liquidSupports=new TreeMap<Integer,Integer>();
			if(canFreeze)for(var stock:store.physicalStocks()) if(stock.key().material()==liquid)
				liquidSupports.merge(stock.key().cell(),stock.key().supportMask(),(a,b)->a|b);
			var meltingCells=new HashSet<Integer>();
			if(canMelt)for(var group:originalGroups) if(group.phaseId().equals(transition.lowerEnergyMaterialId())) {
				meltingCells.add(group.cell());
			}
			var possibleCells=new TreeSet<Integer>(liquidSupports.keySet());possibleCells.addAll(meltingCells);
			for(int cell:possibleCells) {
				double temperature=thermal.phaseScanTemperatureInCell(cell);
				boolean freezing=liquidSupports.containsKey(cell)&&temperature<maximumMelting;
				boolean melting=meltingCells.contains(cell)&&temperature>minimumMelting;
				// The current pressure interval excludes impossible directions before wet-face preparation.
				if(!freezing&&!melting)continue;
				int liquidSupport=liquidSupports.getOrDefault(cell,0);
				if(!canFreeze)for(var stock:store.physicalStocksInCell(cell))
					if(stock.key().material()==liquid)liquidSupport|=stock.key().supportMask();
				if(liquidSupport==0) liquidSupport=~domain.geometry.cutCells.mask(cell)&255;
				double equilibrium=constant?minimumMelting:transition.equilibriumTemperatureK(Math.max(0,
						AirNetwork.REFERENCE_PRESSURE+domain.pressureSystem.liquidPressureAt(cell,liquidSupport)));
				if(freezing&&temperature<equilibrium||melting&&temperature>equilibrium) {
					selectedCells.add(cell);candidates.add(new Candidate(cell,liquid,transition,equilibrium,temperature));
				}
			}
		}
		if(candidates.isEmpty()) return new FluidDomain.ThermalPhaseStats(0,0,0,0);
		prepareGranularOccupancy();
		var captured=thermal.preparePhaseCandidateCells(selectedCells.stream().mapToInt(Integer::intValue).toArray(),domain.domainCapabilities.thermalMarkerContext());
		var selected=new TreeMap<Integer,Proposal>();
		for(var candidate:candidates) {
			var proposal=propose(candidate.cell(),candidate.liquid(),candidate.transition(),candidate.equilibrium(),candidate.temperature(),
					captured[candidate.cell()],coherentByCell.getOrDefault(candidate.cell(),List.of()),function,maximumOperations);
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
		var futureCells=new TreeMap<Integer,List<LiquidCellInventory.Stock>>();
		int operations,frozen,melted;long signed;
		LiquidCellInventory.Plan provisional;
		long[] occupied;
		var displacement=new ConservativeLiquidFlux(domain);
		while(true){
			futureGroups.clear();futureCells.clear();
			for(var group:originalGroups)futureGroups.put(new GroupKey(group.cell(),group.phaseId()),group);
			operations=frozen=melted=0;signed=0;
			for(var p:selected.values()){p.removedMass=0;if(p.batches>0){
				futureCells.put(p.cell,p.freezing?freeze(p,futureGroups,function):melt(p,futureGroups,function));
				operations+=Math.toIntExact(p.batches);signed+=p.freezing?p.batches:-p.batches;
				if(p.freezing)frozen++;else melted++;
			}}
			if(operations==0)return new FluidDomain.ThermalPhaseStats(0,0,0,0);
			provisional=store.planEdits(quantityEdits(futureCells));
			occupied=phaseGranularOccupancy.copySolidSvq();
			for(var group:futureGroups.values())occupied[group.cell()]=Math.addExact(occupied[group.cell()],group.occupiedVolumeSvq());
			try{displacement.prepareDisplacement(occupied,provisional.future());break;}
			catch(ConservativeLiquidFlux.CapacityUnavailable unavailable){
				boolean reduced=false;
				for(var p:selected.values())if(p.freezing&&p.batches>0&&unavailable.affectsCell(p.cell)){p.batches/=2;reduced=true;}
				if(!reduced)throw unavailable;
			}
		}
		var removedMassByCell=new TreeMap<Integer,Long>();
		for(var p:selected.values())if(p.removedMass>0)removedMassByCell.put(p.cell,p.removedMass);
		var momentum=domain.cellMacStage.preparePhaseMomentum(provisional,removedMassByCell);
		var heat=new TreeMap<Integer,SignedDyadic>(momentum.heatByCell());
		for(var entry:momentum.removedKineticEnergyByCell().entrySet())
			heat.merge(entry.getKey(),entry.getValue(),SignedDyadic::add);
		for(int cell:heat.keySet()) futureCells.putIfAbsent(cell,store.stocksInCell(cell));
		captured=thermal.preparePhaseCandidateCells(futureCells.keySet().stream().mapToInt(Integer::intValue).toArray(),domain.domainCapabilities.thermalMarkerContext());
		SignedDyadic mechanicalHeat=SignedDyadic.ZERO;for(var value:heat.values()) mechanicalHeat=mechanicalHeat.add(value);
		var groups=List.copyOf(futureGroups.values());
		coherentByCell.clear();
		for(var group:groups)coherentByCell.computeIfAbsent(group.cell(),ignored->new ArrayList<>()).add(group);
		var trials=new ArrayList<ThermalDomainState.PhaseCellTrial>();int[] counts=new int[futureCells.size()];int index=0;
		for(var entry:futureCells.entrySet()) {
			int cell=entry.getKey();var inventory=portions(entry.getValue());
			counts[index++]=inventory.size();
			for(var group:coherentByCell.getOrDefault(cell,List.of())) inventory.add(coherentPortion(group));
			trials.add(thermal.trialCondensedCellEnergyWithHeat(captured[cell],inventory,heat.getOrDefault(cell,SignedDyadic.ZERO)));
		}
		var energy=thermal.planCondensedBatchEnergy(trials,counts,mechanicalHeat,domain.domainCapabilities.thermalMarkerContext(),groups);
		var finalEnergies=new TreeMap<LiquidCellInventory.Key,Double>();
		for(var entry:futureCells.entrySet()) {
			double[] values=energy.liquidEnergies(entry.getKey());int ordinal=0;
			for(var stock:entry.getValue()) finalEnergies.put(stock.key(),stock.massUnits()==0?0:values[ordinal++]);
		}
		var phaseLiquid=provisional.withInternalEnergiesByStock(finalEnergies).future();
		displacement.prepareDisplacement(occupied,phaseLiquid);
		var transport=domain.cellMacStage.prepareLiquid(displacement,phaseLiquid);
		var displacedMomentum=new CellMomentumTransport(domain).prepare(transport,phaseVelocities(momentum.momentum(),phaseLiquid));
		var moved=domain.mechanicalEnergy.prepareDisplacement(transport.inventory().withMechanicalHeat(displacedMomentum.heatByCell()),null).inventory();
		var endpoint=new TreeMap<Integer,List<LiquidCellInventory.Stock>>();
		for(int cell=0;cell<domain.cellCount;cell++)if(store.stocksInCell(cell)!=moved.future().stocksInCell(cell))endpoint.put(cell,moved.future().stocksInCell(cell));
		var liquid=store.planEdits(quantityEdits(endpoint));
		var gas=domain.gasTopologyCoordinator.planAfterInternalConversion(liquid.future(),occupied,
				transport.gasAllocation().rebindFuture(domain,liquid.future()));
		thermal.requirePhaseBatchEnergyCurrent(energy);
		domain.cellMacStage.publishMaterialChange(liquid,()->domain.cellMacStage.commitMomentum(displacedMomentum),gas,occupied,()->{
			thermal.commitPhaseBatchEnergy(energy,domain.domainCapabilities.thermalMarkerContext());
			for(var p:selected.values()) if(p.batches>0) {
				long delta=p.batches*(p.freezing?p.transition.lowerVolumeSvqPerBatch()-p.transition.upperVolumeSvqPerBatch()
						:p.transition.upperVolumeSvqPerBatch()-p.transition.lowerVolumeSvqPerBatch());
				if(delta!=0) domain.pendingPhaseMotion.mark(p.cell);
			}
			domain.domainCapabilities.refreshCoherentOccupancy();
		});
		thermal.restoreAuditLedger(thermal.auditLedger().withMechanicalWork(moved.mechanicalHeat()).withRoundoff(moved.energyRoundoff()));
		return new FluidDomain.ThermalPhaseStats(operations,frozen,melted,signed);
	}

	private Proposal propose(int cell,int liquid,ThermalPhaseTransition transition,double equilibrium,double temperature,
	                         ThermalDomainState.PhaseCandidateCell current,List<CoherentPhaseStore.Group> coherentInCell,
	                         ThermalStateFunction function,int maximum) {
		var thermal=domain.thermalState;var store=domain.liquidCells;
		if(temperature==equilibrium) return null;
		boolean freezing=temperature<equilibrium;
		var sources=store.physicalStocksInCell(cell).stream().filter(s->s.key().material()==liquid).toList();
		int groupIndex=thermal.coherentPhases().find(cell,transition.lowerEnergyMaterialId());
		var coherent=groupIndex<0?null:thermal.coherentPhases().group(groupIndex);
		if(freezing&&sources.isEmpty()||!freezing&&coherent==null) return null;
		long batchMass=Mass.exactUnits(transition.massKgPerBatch().numerator(),transition.massKgPerBatch().denominator());
		long mass=freezing?sources.stream().mapToLong(LiquidCellInventory.Stock::massUnits).reduce(0,Math::addExact):coherent.massUnits();
		SignedDyadic volume=SignedDyadic.ZERO;
		if(freezing) for(var stock:sources) volume=volume.add(stock.volume());else volume=normalized(coherent.occupiedVolumeSvq());
		long sourceVolume=freezing?transition.upperVolumeSvqPerBatch():transition.lowerVolumeSvqPerBatch();
		long high=Math.min(maximum,Math.min(mass/batchMass,volumeBatches(volume,normalized(sourceVolume),maximum)));
		if(freezing){
			SignedDyadic solids=normalized(phaseGranularOccupancy.solidSvq(cell));
			for(var group:coherentInCell)solids=solids.add(normalized(group.occupiedVolumeSvq()));
			var room=SignedDyadic.of(domain.geometry.cutCells.openCellVolume(cell)).subtract(solids);
			high=Math.min(high,volumeBatches(room,normalized(transition.lowerVolumeSvqPerBatch()),maximum));
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
		var store=domain.liquidCells;var rows=new TreeMap<LiquidCellInventory.Key,LiquidCellInventory.Stock>();for(var stock:store.stocksInCell(p.cell)) rows.put(stock.key(),stock);
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
			else rows.put(stock.key(),new LiquidCellInventory.Stock(stock.key(),volume,mass,mixture(kept,mass),mass==0?0:stock.internalEnergy()));
		}
		long svq=Math.multiplyExact(p.batches,p.transition.lowerVolumeSvqPerBatch());var key=new GroupKey(p.cell,p.transition.lowerEnergyMaterialId());var old=groups.get(key);
		if(old!=null) {svq=Math.addExact(svq,old.occupiedVolumeSvq());if(old.composition()!=null)add(composition,old.composition().stableAmounts());}
		long mass=Math.addExact(requested,old==null?0:old.massUnits());
		groups.put(key,new CoherentPhaseStore.Group(p.cell,key.phase,svq,mass,coherentComposition(composition,mass),old==null?0:old.internalEnergy()));
		p.removedMass=requested;return List.copyOf(rows.values());
	}
	private List<LiquidCellInventory.Stock> melt(Proposal p,TreeMap<GroupKey,CoherentPhaseStore.Group> groups,ThermalStateFunction function) {
		var store=domain.liquidCells;var key=new GroupKey(p.cell,p.transition.lowerEnergyMaterialId());
		var take=domain.thermalState.coherentPhases().previewSplit(p.cell,key.phase,Math.multiplyExact(p.batches,p.transition.lowerVolumeSvqPerBatch()),store.chemicalRegistry());
		if(take.remaining()==null)groups.remove(key);else groups.put(key,take.remaining());
		var rows=new TreeMap<LiquidCellInventory.Key,LiquidCellInventory.Stock>();for(var stock:store.stocksInCell(p.cell)) rows.put(stock.key(),stock);
		int[] components=CellRemapMicroTopology.components(domain.geometry.cutCells.mask(p.cell));
		if(components.length!=1)throw new IllegalStateException("Condensed phase requires one connected open cell component");
		int best=components[0];
		var liquidKey=new LiquidCellInventory.Key(p.cell,p.liquid,best);var old=rows.get(liquidKey);var composition=new TreeMap<String,Long>();
		if(take.removed().composition()!=null)composition.putAll(take.removed().composition().stableAmounts());
		if(old!=null)add(composition,amounts(old.mixture()));
		long mass=Math.addExact(take.removed().massUnits(),old==null?0:old.massUnits());
		var volume=normalized(Math.multiplyExact(p.batches,p.transition.upperVolumeSvqPerBatch()));if(old!=null)volume=volume.add(old.volume());
		var mixture=mixture(composition,mass);int handle=domain.thermalState.thermalMaterialHandleForFluid(p.liquid);
		rows.put(liquidKey,new LiquidCellInventory.Stock(liquidKey,volume,mass,mixture,energy(function,handle,Mass.kilograms(mass),mixture,p.equilibrium)));
		return List.copyOf(rows.values());
	}
	private float[][] phaseVelocities(MacMomentumState momentum,LiquidCellInventory liquid){
		long[] mass=new long[domain.cellCount];for(var stock:liquid.physicalStocks())mass[stock.key().cell()]=Math.addExact(mass[stock.key().cell()],stock.massUnits());
		double[][] p={momentum.copyX(),momentum.copyY(),momentum.copyZ()};float[][] velocity=new float[3][];
		for(int axis=0;axis<3;axis++){
			int nx=domain.sizeX+(axis==0?1:0),ny=domain.sizeY+(axis==1?1:0);velocity[axis]=new float[p[axis].length];
			for(int face=0;face<p[axis].length;face++){
				int x=face%nx,y=face/nx%ny,z=face/(nx*ny),coordinate=axis==0?x:axis==1?y:z,size=axis==0?domain.sizeX:axis==1?domain.sizeY:domain.sizeZ;
				int right=coordinate<size?domain.domainFlowFields.cellIndex(x,y,z):-1,left=coordinate>0?domain.domainFlowFields.cellIndex(x-(axis==0?1:0),y-(axis==1?1:0),z-(axis==2?1:0)):-1;
				double m=.5*((left<0?0:Mass.kilograms(mass[left]))+(right<0?0:Mass.kilograms(mass[right])));
				if(m>0)velocity[axis][face]=(float)(p[axis][face]/m);
			}
		}
		return velocity;
	}
	private List<LiquidCellInventory.Edit> quantityEdits(Map<Integer,List<LiquidCellInventory.Stock>> future) {
		var edits=new ArrayList<LiquidCellInventory.Edit>();
		for(var entry:future.entrySet()) {
			var keys=new HashSet<LiquidCellInventory.Key>();
			for(var stock:entry.getValue()) {
				keys.add(stock.key());edits.add(new LiquidCellInventory.Edit(stock.key(),stock));
			}
			for(var before:domain.liquidCells.stocksInCell(entry.getKey()))if(!keys.contains(before.key()))edits.add(new LiquidCellInventory.Edit(before.key(),null));
		}
		return edits;
	}
	private ArrayList<ThermalStateFunction.InventoryPortion> portions(List<LiquidCellInventory.Stock> stocks) {
		var portions=new ArrayList<ThermalStateFunction.InventoryPortion>();for(var stock:stocks)if(stock.massUnits()>0)portions.add(new ThermalStateFunction.InventoryPortion(
				domain.thermalState.thermalMaterialHandleForFluid(stock.key().material()),stock.massKilograms(),amounts(stock.mixture())));return portions;
	}
	private ThermalStateFunction.InventoryPortion coherentPortion(CoherentPhaseStore.Group group) {
		return new ThermalStateFunction.InventoryPortion(domain.thermalState.registry().materialHandle(group.phaseId()),group.mass(),
				group.composition()==null?Map.of():group.composition().stableAmounts());
	}
	private double energy(ThermalStateFunction f,int handle,double mass,ChemicalMixtureRecord mixture,double t) {
		return f.internalEnergyAtTemperature(handle,mass,mixture,domain.liquidCells.chemicalRegistry(),t);
	}
	private TreeMap<String,Long> amounts(ChemicalMixtureRecord mixture) {
		return mixture==null?new TreeMap<>():new TreeMap<>(mixture.stableAmounts(domain.liquidCells.chemicalRegistry()));
	}
	private ChemicalMixtureRecord mixture(Map<String,Long> composition,long mass) {
		var chemistry=domain.liquidCells.chemicalRegistry();if(chemistry==null)return null;
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
	private SignedDyadic normalized(long svq) { return SignedDyadic.of(svq,-27).multiply(SignedDyadic.of(1/domain.unitScale.cubicMetersPerNormalizedVolume())); }
	private static long volumeBatches(SignedDyadic volume,SignedDyadic batch,long maximum) {
		if(volume.signum()<=0)return 0;long n=Math.min(maximum,(long)Math.floor(volume.toDouble()/batch.toDouble()));
		while(n>0&&batch.multiply(SignedDyadic.of(n)).compareTo(volume)>0)n--;return n;
	}
	static long minimumBatchesForCapacity(long occupied,long capacity,long delta) {
		if(occupied<0||capacity<0)throw new IllegalArgumentException("Invalid phase capacity");
		if(occupied<=capacity)return 1;if(delta>=0)return Long.MAX_VALUE;long excess=occupied-capacity,shrink=-delta;return excess/shrink+(excess%shrink==0?0:1);
	}
}

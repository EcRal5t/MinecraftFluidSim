package mmm.fluid;

import mmm.inventory.LiquidCellInventory;

import mmm.chemistry.ChemicalMixtureRecord;
import mmm.fluid.air.*;
import mmm.granular.SolidVolume;
import mmm.thermal.*;
import mmm.units.Mass;
import mmm.units.SignedDyadic;

import java.util.*;

/** Simultaneous cell extents, one vapor-pressure closure per chamber and one topology publication. */
final class GasLiquidPhaseChangeStage {
	private final PhaseChangeStageSupport support;
	GasLiquidPhaseChangeStage(PhaseChangeStageSupport support) { this.support = support; }

	private record Caloric(ThermalMaterialDefinition material, double mass, double base) {
		double energy(double t) { return base + mass * material.sensibleSpecificEnergyAt(t); }
		double capacity(double t) { return mass * material.specificHeatAt(t); }
	}
	private static final class Cell {
		final int id;
		final ThermalDomainState.PhaseCandidateCell thermal;
		final List<LiquidCellInventory.Stock> original;
		final List<Caloric> curve;
		final double temperature;
		List<LiquidCellInventory.Stock> future;
		double transfer;
		Cell(int id, ThermalDomainState.PhaseCandidateCell thermal, List<LiquidCellInventory.Stock> original,
		     List<Caloric> curve, double temperature) {
			this.id=id; this.thermal=thermal; this.original=original; this.curve=curve; this.temperature=temperature; future=original;
		}
		double energy(double t) { double u=0; for(var c:curve) u+=c.energy(t); return u; }
		double boundedTemperature(double requested) {
			double low=0,high=Double.POSITIVE_INFINITY;
			for(var c:curve) { low=Math.max(low,c.material.minimumTemperatureK()); high=Math.min(high,c.material.maximumTemperatureK()); }
			return Math.clamp(requested,low,high);
		}
	}
	private static final class Proposal {
		final Cell cell;
		final LiquidCellInventory.Stock source;
		final ThermalPhaseTransition transition;
		final int node, liquidHandle, gasHandle;
		final String component;
		final boolean boiling;
		final long batchMass,nanos,maximumBatches;
		final double batchKg,liquidM3PerKg,gasBase,liquidBase,carried,lower,upper,score;
		final SignedDyadic batchVolume;
		double mass;
		long batches;
		Proposal(Cell cell,LiquidCellInventory.Stock source,ThermalPhaseTransition transition,int node,
		         int liquidHandle,int gasHandle,String component,boolean boiling,long batchMass,long nanos,
		         double liquidM3PerKg,double gasBase,double liquidBase,double carried,double lower,double upper,
		         double score,long maximumBatches,SignedDyadic batchVolume) {
			this.cell=cell; this.source=source; this.transition=transition; this.node=node;
			this.liquidHandle=liquidHandle; this.gasHandle=gasHandle; this.component=component; this.boiling=boiling;
			this.batchMass=batchMass; this.nanos=nanos; this.batchKg=Mass.kilograms(batchMass);
			this.liquidM3PerKg=liquidM3PerKg; this.gasBase=gasBase; this.liquidBase=liquidBase; this.carried=carried;
			this.lower=lower; this.upper=upper; this.score=score; this.maximumBatches=maximumBatches; this.batchVolume=batchVolume;
		}
		double gasSpecificEnergy(ThermalRegistry registry,double t) { return gasBase+registry.material(gasHandle).sensibleSpecificEnergyAt(t); }
		double extent(double partial,GasLiquidPhaseModel phase,ThermalRegistry registry) {
			double t=cell.boundedTemperature(phase.saturationTemperatureK(transition,partial));
			double latent=carried-liquidBase-registry.material(liquidHandle).sensibleSpecificEnergyAt(t);
			return latent>0 ? Math.clamp((cell.thermal.cellEnergy().toDouble()-cell.energy(t))/latent,lower,upper):0;
		}
	}
	private record Group(int node,String component) implements Comparable<Group> {
		public int compareTo(Group other) { int n=Integer.compare(node,other.node); return n!=0?n:component.compareTo(other.component); }
	}

	FluidDomain.GasThermalPhaseStats step(int maximumOperations,boolean phaseGridPrepared) {
		var thermal=support.thermal();
		if(thermal==null||!thermal.gasModelBound()) throw new IllegalStateException("Gas phase requires thermal owners");
		if(maximumOperations<0||maximumOperations>ThermalLimits.MAX_PHASE_OPERATIONS_PER_STEP) throw new IllegalArgumentException("Invalid phase operation budget");
		if(maximumOperations==0) return new FluidDomain.GasThermalPhaseStats(0,0,0);
		support.prepareGranularOccupancy(); support.refreshGasTopology();
		if(!phaseGridPrepared) thermal.preparePhaseScan(support.state().thermalMarkers());
		var liquids=support.liquidCells(); var network=support.gasState().network;
		var batchCells=thermal.preparePhaseCandidateCells(liquids.physicalStocks().stream().mapToInt(s->s.key().cell()).distinct().toArray(),support.state().thermalMarkers());
		var phase=thermal.gasLiquidPhase(network); var function=new ThermalStateFunction(thermal.registry());
		// One interface extent per LTE cell; all other liquid and solid owners remain its heat bath.
		var selected=new TreeMap<Integer,Proposal>();
		var capturedCells=new HashMap<Integer,Cell>();
		var contacts=new HashMap<Integer,List<GasPhaseInterfaceResolver.Contact>>();
		for(var transition:thermal.registry().transitions()) {
			if(transition.interfaceRequirement()!=ThermalPhaseTransition.InterfaceRequirement.LIQUID_GAS) continue;
			var liquidMaterial=thermal.registry().material(thermal.registry().materialHandle(transition.lowerEnergyMaterialId()));
			var gasMaterial=thermal.registry().material(thermal.registry().materialHandle(transition.upperEnergyMaterialId()));
			if(liquidMaterial.carrierKind()!=ThermalMaterialDefinition.CarrierKind.LIQUID||gasMaterial.carrierKind()!=ThermalMaterialDefinition.CarrierKind.GAS) continue;
			int liquid=support.fluidMaterialHandle(liquidMaterial.carrierId());
			for(var stock:liquids.physicalStocks()) {
				int id=stock.key().cell();
				if(stock.key().material()!=liquid||stock.indicator()||support.state().pendingPhaseVolume().isPending(id)) continue;
				var cell=capturedCells.computeIfAbsent(id,key->captureCell(key,function,batchCells[key]));
				for(var contact:contacts.computeIfAbsent(id,key->GasPhaseInterfaceResolver.contacts(support.gasState().frame,key))) {
					if(contact.node()<=0||contact.supportMask()!=stock.key().supportMask()) continue;
					var proposal=propose(cell,stock,transition,contact.node(),phase,function,maximumOperations);
					if(proposal!=null&&(!selected.containsKey(id)||selected.get(id).score<proposal.score)) selected.put(id,proposal);
				}
			}
		}
		var groups=new TreeMap<Group,List<Proposal>>();
		for(var p:selected.values()) groups.computeIfAbsent(new Group(p.node,p.component),ignored->new ArrayList<>()).add(p);
		for(var group:groups.values()) closeChamber(group,phase,function);
		var proposals=new ArrayList<>(selected.values()); long[] desired=new long[proposals.size()];
		for(int i=0;i<desired.length;i++) {
			var p=proposals.get(i); desired[i]=Math.min(p.maximumBatches,Mass.fromKilograms(Math.abs(p.mass))/p.batchMass);
		}
		long[] counts=allocate(Math.min(maximumOperations,sum(desired)),desired);
		for(int i=0;i<counts.length;i++) proposals.get(i).batches=counts[i];
		var gas=new ArrayList<GasInventory>(network.nodeCount()); double[] energy=new double[network.nodeCount()];
		for(int node:network.nodeIds()) { gas.add(network.inventory(node)); energy[node-1]=thermal.gasNodeEnergy(node,network); }
		GasAuditLedger created=GasAuditLedger.EMPTY,removed=GasAuditLedger.EMPTY;
		int operations=0,boiled=0,condensed=0;
		for(var group:groups.values()) {
			int node=group.getFirst().node; var inventory=gas.get(node-1);
			for(var p:group) if(p.boiling&&p.batches>0) {
				var moved=boil(p,phase,function); inventory=inventory.merge(moved); created=created.add(moved);
				operations+=Math.toIntExact(p.batches); boiled++;
			}
			var receivers=group.stream().filter(p->!p.boiling&&p.batches>0).toList();
			if(!receivers.isEmpty()) {
				var vapor=inventory.component(receivers.getFirst().component);
				admitCondensation(receivers,vapor);
				for(var p:receivers) if(p.batches>0) {
					long nanos=Math.multiplyExact(p.batches,p.nanos),mass=Math.multiplyExact(p.batches,p.batchMass);
					var proportional=inventory.splitComponent(p.component,nanos).first().components().getFirst();
					long residual=mass-speciesMass(PhaseChangeStageSupport.PhaseComposition.fromGas(proportional).amounts());
					var take=inventory.splitComponent(p.component,nanos,mass,residual);
					inventory=take.second(); removed=removed.add(take.first());
					condense(p,take.first(),phase,function);
					operations+=Math.toIntExact(p.batches); condensed++;
				}
			}
			SignedDyadic totalEnergy=SignedDyadic.of(energy[node-1]); Cell last=null;
			for(var p:group) if(p.cell.future!=p.cell.original) { totalEnergy=totalEnergy.add(SignedDyadic.of(p.cell.transfer)); last=p.cell; }
			if(inventory.isEmpty()&&last!=null) { last.transfer-=totalEnergy.toDouble(); totalEnergy=SignedDyadic.ZERO; }
			gas.set(node-1,inventory); energy[node-1]=totalEnergy.toDouble();
		}
		if(operations==0) return new FluidDomain.GasThermalPhaseStats(0,0,0);
		var cells=new ArrayList<>(proposals.stream().map(p->p.cell).filter(c->c.future!=c.original).toList());
		var sourceEdits=new ArrayList<LiquidCellInventory.Edit>();
		for(var cell:cells) {
			var keys=new HashSet<LiquidCellInventory.Key>();
			for(var stock:cell.future) { keys.add(stock.key()); sourceEdits.add(new LiquidCellInventory.Edit(stock.key(),stock)); }
			for(var stock:cell.original) if(!keys.contains(stock.key())) sourceEdits.add(new LiquidCellInventory.Edit(stock.key(),null));
		}
		var momentum=support.preparePhaseMomentum(liquids.planEdits(sourceEdits));
		for(int cell:momentum.heatByCell().keySet()) if(cells.stream().noneMatch(c->c.id==cell))
			cells.add(capturedCells.computeIfAbsent(cell,key->captureCell(key,function,batchCells[key])));
		SignedDyadic mechanicalHeat=SignedDyadic.of(momentum.removedKineticEnergy());
		for(var heat:momentum.heatByCell().values()) mechanicalHeat=mechanicalHeat.add(heat);
		// A lumped gas chamber thermalizes the kinetic energy carried by newly vaporized mass.
		long[] evaporated=new long[network.nodeCount()]; long evaporatedTotal=0;
		for(var p:proposals) if(p.boiling&&p.batches>0) {
			long mass=Math.multiplyExact(p.batches,p.batchMass);
			evaporated[p.node-1]=Math.addExact(evaporated[p.node-1],mass); evaporatedTotal=Math.addExact(evaporatedTotal,mass);
		}
		SignedDyadic remainingHeat=SignedDyadic.of(momentum.removedKineticEnergy());
		long remainingMass=evaporatedTotal;
		for(int node=0;node<evaporated.length;node++) if(evaporated[node]>0) {
			SignedDyadic heat=evaporated[node]==remainingMass?remainingHeat
					:SignedDyadic.of(remainingHeat.toDouble()*(evaporated[node]/(double)remainingMass));
			energy[node]=SignedDyadic.of(energy[node]).add(heat).toDouble();
			remainingHeat=remainingHeat.subtract(heat); remainingMass-=evaporated[node];
		}
		if(remainingHeat.signum()!=0) throw new IllegalStateException("Removed liquid kinetic energy has no vapor receiver");
		var trials=new ArrayList<ThermalDomainState.PhaseCellTrial>(); int[] liquidCounts=new int[cells.size()];
		SignedDyadic transfer=SignedDyadic.of(momentum.removedKineticEnergy());
		for(int i=0;i<cells.size();i++) {
			var cell=cells.get(i); var portions=portions(cell.future); liquidCounts[i]=portions.size();
			transfer=transfer.add(SignedDyadic.of(cell.transfer));
			trials.add(portions.isEmpty()&&cell.thermal.unchangedSolids().isEmpty()
					?thermal.trialVaporizedCellEnergy(cell.thermal,0).thermalTrial()
					:thermal.trialPhaseCellEnergyWithHeat(cell.thermal,portions,cell.transfer,
						momentum.heatByCell().getOrDefault(cell.id,SignedDyadic.ZERO)));
		}
		var gasEnergy=thermal.planGasInventoryEnergy(network,network.nodeIds(),gas,energy,transfer);
		var cellEnergy=thermal.planPhaseBatchEnergy(trials,liquidCounts,gasEnergy.roundoff(),mechanicalHeat,support.state().thermalMarkers());
		var edits=new ArrayList<LiquidCellInventory.Edit>();
		for(var cell:cells) {
			double[] energies=cellEnergy.liquidEnergies(cell.id); int ordinal=0; var futureKeys=new HashSet<LiquidCellInventory.Key>();
			for(var stock:cell.future) {
				futureKeys.add(stock.key()); double u=stock.massUnits()==0?0:energies[ordinal++];
				edits.add(new LiquidCellInventory.Edit(stock.key(),new LiquidCellInventory.Stock(stock.key(),stock.volume(),stock.massUnits(),stock.mixture(),u,stock.indicator())));
			}
			for(var stock:cell.original) if(!futureKeys.contains(stock.key())) edits.add(new LiquidCellInventory.Edit(stock.key(),null));
		}
		var liquidPlan=liquids.planEdits(edits);
		var batch=new GasTopologyCoordinator.PhaseBatch(gas,energy,created,removed,gasEnergy,cellEnergy);
		var topology=support.planGasBatchGeometry(liquidPlan.future(),batch);
		liquidPlan.requireCurrent(); support.requireGasBatchGeometryCurrent(topology,batch);
		liquidPlan.commit();
		support.commitPhaseMomentum(momentum.momentum());
		for(var cell:cells) {
			SignedDyadic before=SignedDyadic.ZERO,after=SignedDyadic.ZERO;
			for(var stock:cell.original) before=before.add(stock.volume());
			for(var stock:cell.future) after=after.add(stock.volume());
			if(!after.equals(before)) support.state().pendingPhaseVolume().add(cell.id,after.subtract(before));
		}
		support.markGridDirty(); thermal.commitPhaseBatchEnergy(cellEnergy,support.state().thermalMarkers()); support.commitGasGeometry(topology);
		return new FluidDomain.GasThermalPhaseStats(operations,boiled,condensed);
	}

	/** One common limiter for inventory demand, followed by exact local component splits. */
	private static void admitCondensation(List<Proposal> receivers,GasComponentAmount vapor) {
		if(vapor==null) { for(var p:receivers) p.batches=0; return; }
		long mass=0,nanos=0;
		for(var p:receivers) { mass=Math.addExact(mass,Math.multiplyExact(p.batches,p.batchMass)); nanos=Math.addExact(nanos,Math.multiplyExact(p.batches,p.nanos)); }
		double scale=Math.min(1,Math.min(vapor.massUnits()/(double)mass,vapor.standardVolumeNanos()/(double)nanos));
		if(scale>=1) return;
		var order=new ArrayList<Proposal>(receivers); var remainders=new IdentityHashMap<Proposal,Double>();
		long usedMass=0,usedNanos=0;
		for(var p:receivers) {
			double target=p.batches*scale; long admitted=(long)Math.floor(target);
			remainders.put(p,target-admitted); p.batches=admitted;
			usedMass=Math.addExact(usedMass,Math.multiplyExact(admitted,p.batchMass)); usedNanos=Math.addExact(usedNanos,Math.multiplyExact(admitted,p.nanos));
		}
		order.sort(Comparator.<Proposal>comparingDouble(remainders::get).reversed().thenComparingInt(p->p.cell.id));
		for(var p:order) if(remainders.get(p)>0&&p.batchMass<=vapor.massUnits()-usedMass&&p.nanos<=vapor.standardVolumeNanos()-usedNanos) {
			p.batches++; usedMass+=p.batchMass; usedNanos+=p.nanos;
		}
	}

	private Cell captureCell(int id,ThermalStateFunction function,ThermalDomainState.PhaseCandidateCell current) {
		var thermal=support.thermal();
		if(current==null) throw new IllegalStateException("Phase cell was absent from its source batch");
		var stocks=support.liquidCells().stocksInCell(id); var curve=new ArrayList<Caloric>();
		for(var stock:stocks) if(stock.massUnits()>0) {
			int handle=thermal.thermalMaterialHandleForFluid(stock.key().material());
			curve.add(new Caloric(thermal.registry().material(handle),stock.massKilograms(),function.baseEnergy(handle,stock.massKilograms(),stock.mixture(),support.liquidCells().chemicalRegistry())));
		}
		for(var solid:current.unchangedSolids()) curve.add(new Caloric(thermal.registry().material(solid.materialHandle()),solid.massKg(),function.baseEnergy(solid.materialHandle(),solid.massKg(),solid.speciesQuanta())));
		return new Cell(id,current,stocks,curve,thermal.phaseScanTemperatureInCell(id));
	}

	private Proposal propose(Cell cell,LiquidCellInventory.Stock stock,ThermalPhaseTransition transition,int node,
	                         GasLiquidPhaseModel phase,ThermalStateFunction function,int maximum) {
		var thermal=support.thermal(); var network=support.gasState().network; var inventory=network.inventory(node);
		int liquid=thermal.registry().materialHandle(transition.lowerEnergyMaterialId()),gas=thermal.registry().materialHandle(transition.upperEnergyMaterialId());
		String component=thermal.registry().material(gas).carrierId();
		double pressure=thermal.requireGasNodeState(node,inventory,thermal.gasNodeEnergy(node,network),network);
		var saturation=phase.saturation(transition,cell.temperature,inventory,pressure);
		if(saturation.direction()==GasLiquidPhaseModel.Direction.EQUILIBRIUM) return null;
		boolean boiling=saturation.direction()==GasLiquidPhaseModel.Direction.BOIL;
		long batchMass=Mass.exactUnits(transition.massKgPerBatch().numerator(),transition.massKgPerBatch().denominator());
		long nanos=phase.gasBatch(transition,1).standardVolumeNanos();
		double batchKg=Mass.kilograms(batchMass),volume=(double)transition.lowerVolumeSvqPerBatch()/SolidVolume.SVQ_PER_CUBIC_METER;
		var normalized=SignedDyadic.of(support.state().unitScale().normalizedVolume(volume));
		Map<String,Long> species; double selectedMass; long high;
		if(boiling) {
			species=volatileSpecies(stock,component);
			boolean all=stock.mixture()==null||species.size()==stock.mixture().entryCount();
			long selected=all?stock.massUnits():Math.min(stock.massUnits(),speciesMass(species));
			selectedMass=Mass.kilograms(selected);
			high=Math.min(selected/batchMass,volumeBatches(stock.volume(),normalized,maximum));
		} else {
			var vapor=inventory.component(component); if(vapor==null) return null;
			species=PhaseChangeStageSupport.PhaseComposition.fromGas(vapor).amounts(); selectedMass=vapor.mass();
			int mask=support.state().geometry().cutCells.mask(cell.id);
			var capacities=CellRemapMicroTopology.capacities(mask,support.gasState().frame.poreVolume(cell.id));
			int[] masks=CellRemapMicroTopology.components(mask); SignedDyadic room=SignedDyadic.ZERO;
			for(int i=0;i<masks.length;i++) if(masks[i]==stock.key().supportMask()) room=capacities[i];
			for(var other:cell.original) if(other.key().supportMask()==stock.key().supportMask()) room=room.subtract(other.volume());
			high=Math.min(Math.min(vapor.massUnits()/batchMass,vapor.standardVolumeNanos()/nanos),volumeBatches(room,normalized,maximum));
		}
		if(high<=0||!(selectedMass>0)) return null;
		double gasBase=function.baseEnergy(gas,selectedMass,species)/selectedMass;
		double liquidBase=function.baseEnergy(liquid,selectedMass,species)/selectedMass;
		double sourceTemperature=boiling?cell.temperature:thermal.gasTemperature(node,network);
		double carried=gasBase+thermal.registry().material(gas).sensibleSpecificEnergyAt(sourceTemperature);
		return new Proposal(cell,stock,transition,node,liquid,gas,component,boiling,batchMass,nanos,volume/batchKg,
				gasBase,liquidBase,carried,boiling?0:-high*batchKg,boiling?high*batchKg:0,
				Math.abs(saturation.pressureImbalancePa()),high,normalized);
	}

	private void closeChamber(List<Proposal> group,GasLiquidPhaseModel phase,ThermalStateFunction function) {
		var first=group.getFirst(); var network=support.gasState().network; var thermal=support.thermal();
		var inventory=network.inventory(first.node); var registry=thermal.registry(); var curve=new ArrayList<Caloric>();
		for(var component:inventory.components()) {
			int handle=registry.materialHandle(ThermalMaterialDefinition.CarrierKind.GAS,component.componentId());
			curve.add(new Caloric(registry.material(handle),component.mass(),function.baseEnergy(handle,component.mass(),PhaseChangeStageSupport.PhaseComposition.fromGas(component).amounts())));
		}
		double sourceEnergy=thermal.gasNodeEnergy(first.node,network),sourceVolume=network.availableVolume(first.node);
		var vapor=inventory.component(first.component); double vaporNanos=vapor==null?0:vapor.standardVolumeNanos();
		var caloric=new GasCurve(curve,group,registry);
		double high=1;
		for(var p:group) high=Math.max(high,phase.saturation(p.transition,p.cell.temperature,GasInventory.EMPTY,0).saturationPressurePa());
		high=Math.max(high,thermal.requireGasNodeState(first.node,inventory,sourceEnergy,network));
		java.util.function.DoubleUnaryOperator residual=partial-> {
			double positive=0,negative=0;
			for(var p:group) { p.mass=p.extent(partial,phase,registry); if(p.mass>0) positive+=p.mass; else negative-=p.mass; }
			double available=vapor==null?0:vapor.mass();
			if(negative>available+positive) { double scale=(available+positive)/negative; for(var p:group) if(p.mass<0) p.mass*=scale; }
			double energy=sourceEnergy,volume=sourceVolume,nanos=vaporNanos;
			for(var p:group) { energy+=p.mass*p.carried; volume+=p.mass*p.liquidM3PerKg; nanos+=p.mass/p.batchKg*p.nanos; }
			if(!(nanos>0)) return -partial;
			double t=caloric.temperature(energy);
			return nanos*1e-9*GasRegistry.STANDARD_PRESSURE_PA/GasRegistry.STANDARD_TEMPERATURE_K*t/volume-partial;
		};
		double root=GasPhaseBatchSearch.pressure(high,residual); residual.applyAsDouble(root);
	}

	/** Exact inversion of the standard piecewise-linear caloric model; coefficients depend on trial mass. */
    private static final class GasCurve {
        private final List<Caloric> original;
        private final List<Proposal> changes;
        private final ThermalRegistry registry;
        private final double[] knots;
        GasCurve(List<Caloric> original,List<Proposal> changes,ThermalRegistry registry) {
            this.original=original; this.changes=changes; this.registry=registry;
            var materials=new HashSet<ThermalMaterialDefinition>();
            for(var term:original) materials.add(term.material());
            for(var proposal:changes) materials.add(registry.material(proposal.gasHandle));
            double low=ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K,high=Double.POSITIVE_INFINITY;
            for(var material:materials) { low=Math.max(low,material.minimumTemperatureK()); high=Math.min(high,material.maximumTemperatureK()); }
            var points=new TreeSet<Double>(); points.add(low); points.add(high);
            for(var material:materials) for(var segment:material.caloricSegments())
                if(segment.minimumTemperatureK()>low&&segment.minimumTemperatureK()<high) points.add(segment.minimumTemperatureK());
            knots=points.stream().mapToDouble(Double::doubleValue).toArray();
        }
        private double energy(double t) {
            double value=0;
            for(var term:original) value+=term.energy(t);
            for(var proposal:changes) value+=proposal.mass*proposal.gasSpecificEnergy(registry,t);
            return value;
        }
        double temperature(double target) {
            int low=0,high=knots.length-1;
            if(target<=energy(knots[low])) return knots[low];
            if(target>=energy(knots[high])) return knots[high];
            while(low+1<high) { int middle=(low+high)>>>1; if(energy(knots[middle])<target) low=middle; else high=middle; }
            double temperature=(knots[low]+knots[high])*.5,capacity=0;
            for(var term:original) capacity+=term.capacity(temperature);
            for(var proposal:changes) capacity+=proposal.mass*registry.material(proposal.gasHandle).specificHeatAt(temperature);
            if(!(capacity>0)) throw new IllegalArgumentException("Phase gas caloric capacity is not positive");
            return Math.clamp(knots[low]+(target-energy(knots[low]))/capacity,knots[low],knots[high]);
        }
    }

	private GasInventory boil(Proposal p,GasLiquidPhaseModel phase,ThermalStateFunction function) {
		long mass=Math.multiplyExact(p.batches,p.batchMass); var source=p.source;
		var volume=source.volume().subtract(p.batchVolume.multiply(SignedDyadic.of(p.batches)));
		var available=volatileSpecies(source,p.component); Map<String,Long> moved=new TreeMap<>();
		boolean allVolatile=available.size()==(source.mixture()==null?0:source.mixture().entryCount());
		// Whole-volume depletion hands over the actual final integer mass, including batch rounding.
		if(volume.signum()==0&&allVolatile) mass=source.massUnits();
		long availableMass=allVolatile?source.massUnits():speciesMass(available);
		for(var entry:available.entrySet()) { long amount=Mass.portion(entry.getValue(),mass,availableMass); if(amount>0) moved.put(entry.getKey(),amount); }
		var gas=phase.gasBatch(p.transition,p.batches,moved,mass-speciesMass(moved));
		var mixture=subtractMixture(source.mixture(),moved,source.massUnits()-mass);
		setFuture(p,volume,source.massUnits()-mass,mixture,function);
		p.cell.transfer=p.cell.future.stream().noneMatch(s->s.massUnits()>0)&&p.cell.thermal.unchangedSolids().isEmpty()
				?p.cell.thermal.cellEnergy().toDouble():phase.internalEnergyAtTemperature(p.transition,gas,p.cell.temperature);
		return gas;
	}
	private void condense(Proposal p,GasInventory moved,GasLiquidPhaseModel phase,ThermalStateFunction function) {
		var component=moved.components().getFirst(); var amounts=PhaseChangeStageSupport.PhaseComposition.fromGas(component).amounts();
		var source=p.source; long mass=Math.addExact(source.massUnits(),component.massUnits());
		Map<String,Long> combined=source.mixture()==null?new TreeMap<>():new TreeMap<>(source.mixture().stableAmounts(support.liquidCells().chemicalRegistry()));
		for(var entry:amounts.entrySet()) combined.merge(entry.getKey(),entry.getValue(),Math::addExact);
		setFuture(p,source.volume().add(p.batchVolume.multiply(SignedDyadic.of(p.batches))),mass,mixture(combined,mass),function);
		p.cell.transfer=-phase.internalEnergyAtTemperature(p.transition,moved,support.thermal().gasTemperature(p.node,support.gasState().network));
	}
	private void setFuture(Proposal p,SignedDyadic volume,long mass,ChemicalMixtureRecord mixture,ThermalStateFunction function) {
		var result=new ArrayList<LiquidCellInventory.Stock>();
		for(var stock:p.cell.original) if(!stock.key().equals(p.source.key())) result.add(stock);
		if(volume.signum()>0) {
			double u=mass==0?0:function.internalEnergyAtTemperature(p.liquidHandle,Mass.kilograms(mass),mixture,support.liquidCells().chemicalRegistry(),p.cell.temperature);
			result.add(new LiquidCellInventory.Stock(p.source.key(),volume,mass,mixture,u,p.source.indicator()));
		} else if(mass!=0) throw new IllegalStateException("Phase removed liquid volume without its mass");
		result.sort(Comparator.comparing(LiquidCellInventory.Stock::key)); p.cell.future=List.copyOf(result);
	}
	private List<ThermalStateFunction.InventoryPortion> portions(List<LiquidCellInventory.Stock> stocks) {
		var result=new ArrayList<ThermalStateFunction.InventoryPortion>();
		for(var stock:stocks) if(stock.massUnits()>0) result.add(new ThermalStateFunction.InventoryPortion(support.thermal().thermalMaterialHandleForFluid(stock.key().material()),stock.massKilograms(),
				stock.mixture()==null?Map.of():stock.mixture().stableAmounts(support.liquidCells().chemicalRegistry())));
		return result;
	}
	private Map<String,Long> volatileSpecies(LiquidCellInventory.Stock stock,String component) {
		if(stock.mixture()==null) return Map.of();
		var result=new TreeMap<String,Long>(); var definition=support.gasState().network.registry().component(component);
		for(var entry:stock.mixture().stableAmounts(support.liquidCells().chemicalRegistry()).entrySet()) if(definition.allowsSpecies(entry.getKey())) result.put(entry.getKey(),entry.getValue());
		return result;
	}
	private long speciesMass(Map<String,Long> amounts) {
		return amounts.isEmpty()?0:ChemicalMixtureRecord.fromStableAmounts(support.liquidCells().chemicalRegistry(),amounts,0).speciesMassUnits(support.liquidCells().chemicalRegistry());
	}
	private ChemicalMixtureRecord mixture(Map<String,Long> amounts,long mass) {
		return support.liquidCells().chemicalRegistry()==null?null:ChemicalMixtureRecord.fromStableAmounts(support.liquidCells().chemicalRegistry(),amounts,mass-speciesMass(amounts));
	}
	private ChemicalMixtureRecord subtractMixture(ChemicalMixtureRecord source,Map<String,Long> moved,long mass) {
		if(source==null) return null;
		var remaining=new TreeMap<>(source.stableAmounts(support.liquidCells().chemicalRegistry()));
		for(var entry:moved.entrySet()) { long amount=Math.subtractExact(remaining.getOrDefault(entry.getKey(),0L),entry.getValue()); if(amount==0) remaining.remove(entry.getKey()); else remaining.put(entry.getKey(),amount); }
		return mixture(remaining,mass);
	}
	private static long sum(long[] values) { long n=0; for(long v:values) n=Math.addExact(n,v); return n; }
	private static long volumeBatches(SignedDyadic available,SignedDyadic batch,long maximum) {
		if(available.signum()<=0) return 0;
		long count=Math.min(maximum,(long)Math.floor(available.toDouble()/batch.toDouble()));
		while(count>0&&batch.multiply(SignedDyadic.of(count)).compareTo(available)>0) count--;
		return count;
	}
	/** Final simultaneous integer allocation; equal remainders use the stable cell order. */
	private static long[] allocate(long total,long[] desired) {
		long sum=sum(desired); if(sum==0) return new long[desired.length]; if(total>=sum) return desired.clone();
		long[] result=new long[desired.length],remainders=new long[desired.length]; long assigned=0; Integer[] order=new Integer[desired.length];
		for(int i=0;i<result.length;i++) { long product=Math.multiplyExact(total,desired[i]); result[i]=product/sum; remainders[i]=product%sum; assigned+=result[i]; order[i]=i; }
		Arrays.sort(order,Comparator.<Integer>comparingLong(i->remainders[i]).reversed().thenComparingInt(i->i));
		for(int i=0;assigned<total;i++,assigned++) result[order[i]]++;
		return result;
	}
}

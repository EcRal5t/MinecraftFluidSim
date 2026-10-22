package mmm.fluid;

import mmm.inventory.LiquidCellInventory;

import mmm.chemistry.ChemicalMixtureRecord;
import mmm.fluid.air.*;
import mmm.granular.SolidVolume;
import mmm.thermal.*;
import mmm.units.Mass;
import mmm.units.SignedDyadic;

import java.util.*;

/**
 * First-order phase exchange with frozen local heat capacities and eight scalar chamber sweeps.
 * Integer batches carry actual species and latent U through one joint inventory publication.
 */
final class GasLiquidPhaseChangeStage {
	private final FluidDomain domain;
	private GasRuntimeGeometry interfaceFrame;
	private boolean[] finiteInterfaceCells;
	GasLiquidPhaseChangeStage(FluidDomain domain) { this.domain = domain; }

	/** A finite interface lies in a finite gas component's cell or an adjacent coarse cell. */
	private boolean[] finiteInterfaceCells(GasRuntimeGeometry frame) {
		if (interfaceFrame == frame) return finiteInterfaceCells;
		var geometry = frame.geometry();
		int nx = geometry.sizeX(), ny = geometry.sizeY(), nz = geometry.sizeZ(), plane = nx * ny;
		var cells = new boolean[plane * nz];
		for (var component : geometry.components()) {
			if (frame.topology().nodeOfComponent(component.cell(), component.supportMask()) <= 0) continue;
			int cell = component.cell();
			int x = cell % nx, y = cell / nx % ny, z = cell / plane;
			cells[cell] = true;
			if (x > 0) cells[cell - 1] = true;
			if (x + 1 < nx) cells[cell + 1] = true;
			if (y > 0) cells[cell - nx] = true;
			if (y + 1 < ny) cells[cell + nx] = true;
			if (z > 0) cells[cell - plane] = true;
			if (z + 1 < nz) cells[cell + plane] = true;
		}
		interfaceFrame = frame;
		finiteInterfaceCells = cells;
		return cells;
	}

	private static final class Cell {
		final int id;
		final ThermalDomainState.PhaseCandidateCell thermal;
		final List<LiquidCellInventory.Stock> original;
		final double temperature,coldEnergy,heatCapacity;
		List<LiquidCellInventory.Stock> future;
		double transfer;
		Cell(int id, ThermalDomainState.PhaseCandidateCell thermal, List<LiquidCellInventory.Stock> original,
		     double temperature,double coldEnergy,double heatCapacity) {
			this.id=id; this.thermal=thermal; this.original=original; this.temperature=temperature;
			this.coldEnergy=coldEnergy;this.heatCapacity=heatCapacity;future=original;
		}

	}
	private record Candidate(LiquidCellInventory.Stock stock,LiquidCellInventory.Key key,ThermalPhaseTransition transition,int node,
	                         GasLiquidPhaseModel.Saturation saturation,double temperature,double gasTemperature,double gasCapacity,
	                         long batchMass,long nanos,double batchVolumeM3) { }
	private static final class Proposal {
		final Cell cell;
		final LiquidCellInventory.Stock source;
		final LiquidCellInventory.Key key;
		final ThermalPhaseTransition transition;
		final int node,liquidHandle;
		final String component;
		final boolean boiling;
		final long batchMass,nanos,maximumBatches;
		final double batchKg,lower,upper,score,saturationPressure,partialPressure,liquidResponse,gasResponse,gasTemperature;
		final SignedDyadic batchVolume;
		double mass;
		long batches;
		Proposal(Cell cell,Candidate candidate,int liquidHandle,String component,boolean boiling,
		         long maximumBatches,SignedDyadic batchVolume,double lower,double upper,
		         double liquidResponse,double gasResponse) {
			this.cell=cell;source=candidate.stock();key=candidate.key();transition=candidate.transition();node=candidate.node();
			this.liquidHandle=liquidHandle;this.component=component;this.boiling=boiling;
			batchMass=candidate.batchMass();nanos=candidate.nanos();batchKg=Mass.kilograms(batchMass);
			this.maximumBatches=maximumBatches;this.batchVolume=batchVolume;this.lower=lower;this.upper=upper;
			score=Math.abs(candidate.saturation().pressureImbalancePa());
			saturationPressure=candidate.saturation().saturationPressurePa();
			partialPressure=candidate.saturation().componentPartialPressurePa();
			gasTemperature=candidate.gasTemperature();
			this.liquidResponse=liquidResponse;this.gasResponse=gasResponse;
		}
		double extent(double reference,double offset) {
			double difference=(saturationPressure-reference)-offset;
			return liquidResponse>0?Math.clamp(difference/liquidResponse,lower,upper)
					:difference>0?upper:difference<0?lower:0;
		}
	}
	private record Group(int node,String component) implements Comparable<Group> {
		public int compareTo(Group other) { int n=Integer.compare(node,other.node); return n!=0?n:component.compareTo(other.component); }
	}

	FluidDomain.GasThermalPhaseStats step(int maximumOperations) {
		var thermal=domain.thermalState;
		if(thermal==null||!thermal.gasModelBound()) throw new IllegalStateException("Gas phase requires thermal owners");
		if(maximumOperations<0||maximumOperations>ThermalLimits.MAX_PHASE_OPERATIONS_PER_STEP) throw new IllegalArgumentException("Invalid phase operation budget");
		if(maximumOperations==0) return new FluidDomain.GasThermalPhaseStats(0,0,0);
		domain.gasTopologyCoordinator.refresh();
		var network=domain.gasState.network;
		if(network.nodeCount()==0) return new FluidDomain.GasThermalPhaseStats(0,0,0);
		var interfaceCells=finiteInterfaceCells(domain.gasState.frame);
		thermal.preparePhaseScan(domain.domainCapabilities.thermalMarkerContext());
		var phase=thermal.gasLiquidPhase(network); var function=thermal.stateFunction();
		var candidates=collectCandidates(thermal,phase,network,interfaceCells);
		if(candidates.isEmpty()) return new FluidDomain.GasThermalPhaseStats(0,0,0);
		return selectAndCommit(candidates,thermal,phase,function,network,maximumOperations);
	}

	/** Read-only interface screening stays separate from executable phase transactions. */
	private ArrayList<Candidate> collectCandidates(ThermalDomainState thermal,GasLiquidPhaseModel phase,
			AirNetwork network,boolean[] interfaceCells) {
		var liquids=domain.liquidCells;
		// Keep interface candidates until chamber closure establishes executable integer batches.
		var contacts=new HashMap<Integer,List<GasPhaseInterfaceResolver.Contact>>();
		var candidates=new ArrayList<Candidate>();
		var gasStates=new GasEnergyStore.PhaseState[network.nodeCount()+1];
		for(var transition:thermal.registry().transitions()) {
			if(transition.interfaceRequirement()!=ThermalPhaseTransition.InterfaceRequirement.LIQUID_GAS) continue;
			var liquidMaterial=thermal.registry().material(thermal.registry().materialHandle(transition.lowerEnergyMaterialId()));
			var gasMaterial=thermal.registry().material(thermal.registry().materialHandle(transition.upperEnergyMaterialId()));
			if(liquidMaterial.carrierKind()!=ThermalMaterialDefinition.CarrierKind.LIQUID||gasMaterial.carrierKind()!=ThermalMaterialDefinition.CarrierKind.GAS) continue;
			int liquid=domain.materialRegistry.handle(liquidMaterial.carrierId());
			var unitBatch=phase.gasBatch(transition,1);
			long batchMass=unitBatch.totalMassUnits(),nanos=unitBatch.standardVolumeNanos();
			double batchVolumeM3=(double)transition.lowerVolumeSvqPerBatch()/SolidVolume.SVQ_PER_CUBIC_METER;
			for(var stock:liquids.physicalStocks()) {
				int id=stock.key().cell();
				if(!interfaceCells[id]||stock.key().material()!=liquid||domain.pendingPhaseMotion.isPending(id)) continue;
				for(var contact:contacts.computeIfAbsent(id,key->GasPhaseInterfaceResolver.contacts(domain.gasState.frame,key))) {
					if(contact.node()<=0||contact.supportMask()!=stock.key().supportMask()) continue;
					int node=contact.node(); var inventory=network.inventory(node);
					var gasState=gasStates[node];
					if(gasState==null)gasStates[node]=gasState=thermal.gasPhaseState(node,network);
					double temperature=thermal.phaseScanTemperatureInCell(id);
					var saturation=phase.saturation(transition,temperature,inventory,gasState.absolutePressurePa());
					if(saturation.direction()==GasLiquidPhaseModel.Direction.EQUILIBRIUM) continue;
					if(saturation.direction()==GasLiquidPhaseModel.Direction.BOIL&&stock.massUnits()<batchMass) continue;
					if(saturation.direction()==GasLiquidPhaseModel.Direction.CONDENSE) {
						// Condensed liquid occupies this support's dry capacity and removes
						// vapor from that same chamber. Adjacent gas alone is an evaporation contact.
						if(domain.gasState.frame.topology().nodeOfComponent(id,stock.key().supportMask())!=node) continue;
						var vapor=inventory.component(gasMaterial.carrierId());
						if(vapor==null||vapor.massUnits()<batchMass) continue;
					}
					candidates.add(new Candidate(stock,stock.key(),transition,node,saturation,temperature,gasState.temperatureK(),gasState.heatCapacityJPerK(),batchMass,nanos,batchVolumeM3));
				}
			}
			// A supersaturated finite chamber nucleates condensate on its available cell supports.
			for(var support:domain.gasState.frame.capacity().components()) {
				int id=support.cell();if(domain.pendingPhaseMotion.isPending(id)||support.availableVolumeNormalized().signum()<=0)continue;
				var key=new LiquidCellInventory.Key(id,liquid,support.supportMask());
				if(liquids.stock(key)!=null)continue;
				int node=domain.gasState.frame.topology().nodeOfComponent(id,support.supportMask());if(node<=0)continue;
				var inventory=network.inventory(node);var vapor=inventory.component(gasMaterial.carrierId());
				if(vapor==null||vapor.massUnits()<batchMass||vapor.standardVolumeNanos()<nanos)continue;
				var gasState=gasStates[node];if(gasState==null)gasStates[node]=gasState=thermal.gasPhaseState(node,network);
				double temperature=thermal.phaseScanTemperatureInCell(id);if(!Double.isFinite(temperature))temperature=gasState.temperatureK();
				var saturation=phase.saturation(transition,temperature,inventory,gasState.absolutePressurePa());
				if(saturation.direction()==GasLiquidPhaseModel.Direction.CONDENSE)
					candidates.add(new Candidate(null,key,transition,node,saturation,temperature,gasState.temperatureK(),gasState.heatCapacityJPerK(),batchMass,nanos,batchVolumeM3));
			}

		}
		return candidates;
	}

	private FluidDomain.GasThermalPhaseStats selectAndCommit(ArrayList<Candidate> candidates,ThermalDomainState thermal,
			GasLiquidPhaseModel phase,ThermalStateFunction function,AirNetwork network,int maximumOperations) {
		var selected=new TreeMap<Integer,Proposal>();
		var capturedCells=new HashMap<Integer,Cell>();
		var pool=new ArrayList<Proposal>();
		var candidateGroups=new TreeMap<Group,List<Candidate>>();
		for(var candidate:candidates) {
			String component=thermal.registry().material(thermal.registry().materialHandle(candidate.transition().upperEnergyMaterialId())).carrierId();
			candidateGroups.computeIfAbsent(new Group(candidate.node(),component),ignored->new ArrayList<>()).add(candidate);
		}
		candidates.clear();
		for(var entry:candidateGroups.entrySet()) if(hasIntegerExchange(entry.getKey(),entry.getValue())) candidates.addAll(entry.getValue());
		if(candidates.isEmpty()) return new FluidDomain.GasThermalPhaseStats(0,0,0);
		var batchCells=thermal.preparePhaseCandidateCells(candidates.stream().mapToInt(c->c.key().cell()).distinct().toArray(),domain.domainCapabilities.thermalMarkerContext());
		for(var candidate:candidates) {
			int id=candidate.key().cell();
			var cell=capturedCells.computeIfAbsent(id,key->captureCell(key,function,batchCells[key],candidate.temperature()));
			var proposal=propose(cell,candidate,phase,function,maximumOperations);
			if(proposal!=null) pool.add(proposal);
		}
		var groups=new TreeMap<Group,List<Proposal>>();
		while(!pool.isEmpty()) {
			groups.clear();
			for(var p:pool) groups.computeIfAbsent(new Group(p.node,p.component),ignored->new ArrayList<>()).add(p);
			for(var group:groups.values()) closeChamber(group);
			limitGasRepresentation(groups,phase);
			selected.clear();
			for(var p:pool) if(p.batches>0&&(!selected.containsKey(p.cell.id)||selected.get(p.cell.id).score<p.score)) selected.put(p.cell.id,p);
			if(selected.isEmpty()) return new FluidDomain.GasThermalPhaseStats(0,0,0);
			groups.clear();
			for(var p:selected.values()) groups.computeIfAbsent(new Group(p.node,p.component),ignored->new ArrayList<>()).add(p);
			for(var group:groups.values()) closeChamber(group);
			limitGasRepresentation(groups,phase);
			var blocked=selected.values().stream().filter(p->p.batches==0).toList();
			if(blocked.isEmpty()) break;
			pool.removeAll(blocked);
		}
		var proposals=new ArrayList<>(selected.values()); long[] desired=new long[proposals.size()];
		for(int i=0;i<desired.length;i++) {
			var p=proposals.get(i); desired[i]=p.batches;
		}
		long totalDesired=sum(desired);
		if(totalDesired==0) return new FluidDomain.GasThermalPhaseStats(0,0,0);
		long[] counts=allocate(Math.min(maximumOperations,totalDesired),desired);
		for(int i=0;i<counts.length;i++) proposals.get(i).batches=counts[i];
		return commitExchange(proposals,groups,capturedCells,thermal,phase,function,network);
	}

	/** Only executable integer batches enter quantity, energy, momentum and topology publication. */
	private FluidDomain.GasThermalPhaseStats commitExchange(ArrayList<Proposal> proposals,TreeMap<Group,List<Proposal>> groups,
			HashMap<Integer,Cell> capturedCells,ThermalDomainState thermal,GasLiquidPhaseModel phase,
			ThermalStateFunction function,AirNetwork network) {
		var liquids=domain.liquidCells;
		var retainedGas=new ArrayList<GasInventory>(network.nodeCount()); double[] retainedEnergy=new double[network.nodeCount()];
		for(int node:network.nodeIds()) { retainedGas.add(network.inventory(node)); retainedEnergy[node-1]=thermal.gasNodeEnergy(node,network); }
		var births=new ArrayList<GasTopologyCoordinator.PhaseBirth>();var birthNodes=new ArrayList<Integer>();
		// Every component exchange in a chamber shares its actual remaining pore capacity.
		var gasRoom=new SignedDyadic[network.nodeCount()];Arrays.fill(gasRoom,SignedDyadic.ZERO);
		for(var component:domain.gasState.frame.capacity().components()) if(component.availableVolumeNormalized().signum()>0) {
			int node=domain.gasState.frame.topology().nodeOfComponent(component.cell(),component.supportMask());
			if(node>0)gasRoom[node-1]=gasRoom[node-1].add(component.availableVolumeNormalized());
		}
		GasAuditLedger created=GasAuditLedger.EMPTY,removed=GasAuditLedger.EMPTY;
		int operations=0,boiled=0,condensed=0;
		for(var group:groups.values()) {
			int node=group.getFirst().node; var inventory=retainedGas.get(node-1);
			for(var p:group) if(p.boiling&&p.batches>0) {
				var moved=boil(p,phase,function);
				births.add(new GasTopologyCoordinator.PhaseBirth(p.cell.id,p.key.supportMask(),moved,p.cell.transfer));
				birthNodes.add(node);
				created=created.add(moved);
				operations+=Math.toIntExact(p.batches); boiled++;
			}
			// Condensation uses the old chamber stock. Newly born vapor follows its
			// released cell capacity and first becomes a receiver on the next step.
			var receivers=group.stream().filter(p->!p.boiling&&p.batches>0).toList();
			if(!receivers.isEmpty()) {
				var vapor=inventory.component(receivers.getFirst().component);
				admitCondensation(receivers,inventory,vapor,gasRoom[node-1]);
				for(var p:receivers) if(p.batches>0) {
					long nanos=Math.multiplyExact(p.batches,p.nanos),mass=Math.multiplyExact(p.batches,p.batchMass);
					var proportional=inventory.splitComponent(p.component,nanos).first().components().getFirst();
					long residual=mass-speciesMass(gasSpecies(proportional));
					var take=inventory.splitComponent(p.component,nanos,mass,residual);
					inventory=take.second(); removed=removed.add(take.first());
					condense(p,take.first(),phase,function);
					gasRoom[node-1]=gasRoom[node-1].subtract(p.batchVolume.multiply(SignedDyadic.of(p.batches)));
					operations+=Math.toIntExact(p.batches); condensed++;
				}
			}
			SignedDyadic totalEnergy=SignedDyadic.of(retainedEnergy[node-1]); Cell last=null;
			for(var p:group) if(!p.boiling&&p.cell.future!=p.cell.original) { totalEnergy=totalEnergy.add(SignedDyadic.of(p.cell.transfer)); last=p.cell; }
			if(inventory.isEmpty()&&last!=null) { last.transfer-=totalEnergy.toDouble(); totalEnergy=SignedDyadic.ZERO; }
			retainedGas.set(node-1,inventory); retainedEnergy[node-1]=totalEnergy.toDouble();
		}
		if(operations==0) return new FluidDomain.GasThermalPhaseStats(0,0,0);
		var cells=new ArrayList<>(proposals.stream().map(p->p.cell).filter(c->c.future!=c.original).toList());
		var sourceEdits=new ArrayList<LiquidCellInventory.Edit>();
		for(var cell:cells) {
			var keys=new HashSet<LiquidCellInventory.Key>();
			for(var stock:cell.future) { keys.add(stock.key()); sourceEdits.add(new LiquidCellInventory.Edit(stock.key(),stock)); }
			for(var stock:cell.original) if(!keys.contains(stock.key())) sourceEdits.add(new LiquidCellInventory.Edit(stock.key(),null));
		}
		var quantityPlan=liquids.planEdits(sourceEdits);
		var phasePotential=new TreeMap<Integer,SignedDyadic>();
		for(var cell:cells) {
			long before=0,after=0;
			for(var stock:cell.original)before=Math.addExact(before,stock.massUnits());
			for(var stock:cell.future)after=Math.addExact(after,stock.massUnits());
			phasePotential.put(cell.id,SignedDyadic.of(Mass.kilograms(before-after)*potentialPerKilogram(cell.id)));
		}
		var removedMassByCell=new TreeMap<Integer,Long>();
		for(var birth:births)removedMassByCell.merge(birth.cell(),birth.inventory().totalMassUnits(),Math::addExact);
		var momentum=domain.cellMacStage.preparePhaseMomentum(quantityPlan,removedMassByCell);
		for(int cell:momentum.heatByCell().keySet()) if(cells.stream().noneMatch(c->c.id==cell))
			cells.add(capturedCells.computeIfAbsent(cell,key->captureCell(key,function,thermal.preparePhaseCandidateCell(key,domain.domainCapabilities.thermalMarkerContext()))));
		SignedDyadic mechanicalHeat=SignedDyadic.of(momentum.removedKineticEnergy());
		for(var heat:momentum.heatByCell().values()) mechanicalHeat=mechanicalHeat.add(heat);
		for(var heat:phasePotential.values())mechanicalHeat=mechanicalHeat.add(heat);
		// The newborn vapor carries its own source U and thermalized liquid kinetic energy.
		var remainingHeat=new TreeMap<>(momentum.removedKineticEnergyByCell());
		for(int index=0;index<births.size();index++) {
			var birth=births.get(index);
			var heat=remainingHeat.remove(birth.cell());
			if(heat==null)heat=SignedDyadic.ZERO;
			heat=heat.add(phasePotential.getOrDefault(birth.cell(),SignedDyadic.ZERO));
			births.set(index,new GasTopologyCoordinator.PhaseBirth(birth.cell(),birth.supportMask(),birth.inventory(),
					SignedDyadic.of(birth.energy()).add(heat).toDouble()));
		}
		if(!remainingHeat.isEmpty()) throw new IllegalStateException("Removed liquid kinetic energy has no vapor receiver: "+remainingHeat.keySet());
		// The old-node aggregate is the caloric batch baseline; topology remapping
		// receives retained stock and spatially located births as separate inputs.
		var gas=new ArrayList<>(retainedGas);double[] energy=retainedEnergy.clone();
		var aggregateEnergy=new SignedDyadic[energy.length];
		for(int node=0;node<energy.length;node++)aggregateEnergy[node]=SignedDyadic.of(energy[node]);
		for(int index=0;index<births.size();index++) {
			var birth=births.get(index);int node=birthNodes.get(index)-1;
			try {gas.set(node,gas.get(node).merge(birth.inventory()));}
			catch(GasInventory.RepresentationException unavailable){throw new IllegalStateException("Phase vapor merge failed: node="+(node+1)+", cell="+birth.cell()+", support="+birth.supportMask()+", inventory="+birth.inventory(),unavailable);}
			aggregateEnergy[node]=aggregateEnergy[node].add(SignedDyadic.of(birth.energy()));
		}
		for(int node=0;node<energy.length;node++)energy[node]=aggregateEnergy[node].toDouble();
		var trials=new ArrayList<ThermalDomainState.PhaseCellTrial>(); int[] liquidCounts=new int[cells.size()];
		SignedDyadic transfer=SignedDyadic.of(momentum.removedKineticEnergy());
		for(var heat:phasePotential.values())if(heat.signum()>0)transfer=transfer.add(heat);
		for(int i=0;i<cells.size();i++) {
			var cell=cells.get(i); var portions=portions(cell.future); liquidCounts[i]=portions.size();
			transfer=transfer.add(SignedDyadic.of(cell.transfer));
			trials.add(portions.isEmpty()&&cell.thermal.unchangedSolids().isEmpty()
					?thermal.trialVaporizedCellEnergy(cell.thermal,0).thermalTrial()
					:thermal.trialPhaseCellEnergyWithHeat(cell.thermal,portions,cell.transfer,
						momentum.heatByCell().getOrDefault(cell.id,SignedDyadic.ZERO).add(phasePotential.getOrDefault(cell.id,SignedDyadic.ZERO).signum()<0?phasePotential.get(cell.id):SignedDyadic.ZERO)));
		}
		var gasEnergy=thermal.planGasInventoryEnergy(network,network.nodeIds(),gas,energy,transfer);
		var cellEnergy=thermal.planPhaseBatchEnergy(trials,liquidCounts,gasEnergy.roundoff(),mechanicalHeat,domain.domainCapabilities.thermalMarkerContext());
		var finalEnergies=new TreeMap<LiquidCellInventory.Key,Double>();
		for(var cell:cells) {
			double[] energies=cellEnergy.liquidEnergies(cell.id);int ordinal=0;
			for(var stock:cell.future) finalEnergies.put(stock.key(),stock.massUnits()==0?0:energies[ordinal++]);
		}
		var liquidPlan=quantityPlan.withInternalEnergiesByStock(finalEnergies);
		var batch=new GasTopologyCoordinator.PhaseBatch(gas,created,removed,gasEnergy,cellEnergy,
				retainedGas,retainedEnergy,births);
		GasTopologyCoordinator.GasTopologyPlan topology;
		try { topology=domain.gasTopologyCoordinator.planAfterPhaseBatch(liquidPlan.future(),batch); }
		catch(GasInventory.RepresentationException unavailable) { throw new IllegalStateException("Phase topology preparation failed: operations="+operations+", births="+births+", retainedGas="+retainedGas,unavailable); }
		domain.cellMacStage.publishMaterialChange(liquidPlan,()->domain.cellMacStage.commitMomentum(momentum),topology,null,()->{
			for(var cell:cells) {
				SignedDyadic before=SignedDyadic.ZERO,after=SignedDyadic.ZERO;
				for(var stock:cell.original) before=before.add(stock.volume());
				for(var stock:cell.future) after=after.add(stock.volume());
				if(!after.equals(before)) domain.pendingPhaseMotion.mark(cell.id);
			}
			thermal.commitPhaseBatchEnergy(cellEnergy,domain.domainCapabilities.thermalMarkerContext());
		},()->domain.gasTopologyCoordinator.requireAfterPhaseBatchCurrent(topology,batch));
		return new FluidDomain.GasThermalPhaseStats(operations,boiled,condensed);
	}

	/** One common limiter for inventory demand, followed by exact local component splits. */
	private static void admitCondensation(List<Proposal> receivers,GasInventory inventory,GasComponentAmount vapor,SignedDyadic room) {
		if(vapor==null) { for(var p:receivers) p.batches=0; return; }
		long mass=0,nanos=0;var volume=SignedDyadic.ZERO;
		for(var p:receivers) {
			mass=Math.addExact(mass,Math.multiplyExact(p.batches,p.batchMass)); nanos=Math.addExact(nanos,Math.multiplyExact(p.batches,p.nanos));
			volume=volume.add(p.batchVolume.multiply(SignedDyadic.of(p.batches)));
		}
		if(mass==0)return;
		double scale=Math.min(1,Math.min(vapor.massUnits()/(double)mass,vapor.standardVolumeNanos()/(double)nanos));
		scale=Math.min(scale,room.toDouble()/volume.toDouble());
		if(scale>=1&&fitsCondensation(inventory,vapor,mass,nanos,volume,room)) return;
		var order=new ArrayList<Proposal>(receivers); var remainders=new IdentityHashMap<Proposal,Double>();
		long usedMass=0,usedNanos=0;var usedVolume=SignedDyadic.ZERO;
		for(var p:receivers) {
			double target=p.batches*scale; long admitted=(long)Math.floor(target);
			admitted=Math.min(admitted,Math.min((vapor.massUnits()-usedMass)/p.batchMass,(vapor.standardVolumeNanos()-usedNanos)/p.nanos));
			admitted=volumeBatches(room.subtract(usedVolume),p.batchVolume,admitted);
			var nextVolume=usedVolume.add(p.batchVolume.multiply(SignedDyadic.of(admitted)));
			if(admitted>0&&!fitsCondensation(inventory,vapor,usedMass+admitted*p.batchMass,usedNanos+admitted*p.nanos,nextVolume,room)) {
				admitted--;nextVolume=nextVolume.subtract(p.batchVolume);
			}
			remainders.put(p,target-admitted); p.batches=admitted;
			usedMass=Math.addExact(usedMass,Math.multiplyExact(admitted,p.batchMass)); usedNanos=Math.addExact(usedNanos,Math.multiplyExact(admitted,p.nanos));
			usedVolume=nextVolume;
		}
		order.sort(Comparator.<Proposal>comparingDouble(remainders::get).reversed().thenComparingInt(p->p.cell.id));
		for(var p:order) if(remainders.get(p)>0&&fitsCondensation(inventory,vapor,usedMass+p.batchMass,usedNanos+p.nanos,usedVolume.add(p.batchVolume),room)) {
			p.batches++; usedMass+=p.batchMass; usedNanos+=p.nanos;usedVolume=usedVolume.add(p.batchVolume);
		}
	}
	private static boolean fitsCondensation(GasInventory inventory,GasComponentAmount vapor,long mass,long nanos,SignedDyadic volume,SignedDyadic room) {
		if(mass>vapor.massUnits()||nanos>vapor.standardVolumeNanos())return false;
		int remaining=volume.compareTo(room);
		return remaining<0||remaining==0&&inventory.components().size()==1&&mass==vapor.massUnits()&&nanos==vapor.standardVolumeNanos();
	}

	private Cell captureCell(int id,ThermalStateFunction function,ThermalDomainState.PhaseCandidateCell current) {return captureCell(id,function,current,Double.NaN);}
	private Cell captureCell(int id,ThermalStateFunction function,ThermalDomainState.PhaseCandidateCell current,double fallback) {
		var thermal=domain.thermalState;
		if(current==null) throw new IllegalStateException("Phase cell was absent from its source batch");
		var stocks=domain.liquidCells.stocksInCell(id);double temperature=thermal.phaseScanTemperatureInCell(id),cold=0,capacity=0;
		if(!Double.isFinite(temperature))temperature=fallback;
		for(var stock:stocks) if(stock.massUnits()>0) {
			int handle=thermal.thermalMaterialHandleForFluid(stock.key().material());
			var material=thermal.registry().material(handle);double mass=stock.massKilograms();
			cold+=function.baseEnergy(handle,mass,stock.mixture(),domain.liquidCells.chemicalRegistry())
					+mass*material.sensibleSpecificEnergyAt(ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K);
			capacity+=mass*material.specificHeatAt(temperature);
		}
		for(var solid:current.unchangedSolids()) {
			var material=thermal.registry().material(solid.materialHandle());double mass=solid.massKg();
			cold+=function.baseEnergy(solid.materialHandle(),mass,solid.speciesQuanta())
					+mass*material.sensibleSpecificEnergyAt(ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K);
			capacity+=mass*material.specificHeatAt(temperature);
		}
		return new Cell(id,current,stocks,temperature,cold,capacity);
	}

	/** Frozen-temperature upper envelope: a sub-batch vapor deficit publishes no change. */
	private boolean hasIntegerExchange(Group group,List<Candidate> candidates) {
		double maximumSaturation=0,minimumTemperature=Double.POSITIVE_INFINITY,maximumVolume=0;
		long minimumNanos=Long.MAX_VALUE;
		for(var candidate:candidates) {
			if(candidate.saturation().direction()!=GasLiquidPhaseModel.Direction.BOIL) return true;
			maximumSaturation=Math.max(maximumSaturation,candidate.saturation().saturationPressurePa());
			minimumTemperature=Math.min(minimumTemperature,Math.min(candidate.temperature(),candidate.gasTemperature()));
			minimumNanos=Math.min(minimumNanos,candidate.nanos());
			maximumVolume=Math.max(maximumVolume,candidate.batchVolumeM3());
		}
		var network=domain.gasState.network;var vapor=network.inventory(group.node()).component(group.component());
		double nanos=(vapor==null?0:vapor.standardVolumeNanos())+(double)minimumNanos;
		double oneBatchPressure=nanos*1e-9*GasRegistry.STANDARD_PRESSURE_PA/GasRegistry.STANDARD_TEMPERATURE_K
				*minimumTemperature/(network.availableVolume(group.node())+maximumVolume);
		return oneBatchPressure<=maximumSaturation;
	}

	private Proposal propose(Cell cell,Candidate candidate,GasLiquidPhaseModel phase,ThermalStateFunction function,int maximum) {
		var stock=candidate.stock();var transition=candidate.transition();int node=candidate.node();
		var thermal=domain.thermalState;var registry=thermal.registry();var network=domain.gasState.network;var inventory=network.inventory(node);
		int liquid=registry.materialHandle(transition.lowerEnergyMaterialId()),gas=registry.materialHandle(transition.upperEnergyMaterialId());
		String component=registry.material(gas).carrierId();
		boolean boiling=candidate.saturation().direction()==GasLiquidPhaseModel.Direction.BOIL;
		long batchMass=candidate.batchMass(),nanos=candidate.nanos();
		double batchKg=Mass.kilograms(batchMass);
		var normalized=SignedDyadic.of(domain.unitScale.normalizedVolume(candidate.batchVolumeM3()));
		Map<String,Long> species;double selectedMass;long high;
		if(boiling) {
			species=volatileSpecies(stock,component);
			boolean all=stock.mixture()==null||species.size()==stock.mixture().entryCount();
			long selected=all?stock.massUnits():Math.min(stock.massUnits(),speciesMass(species));
			selectedMass=Mass.kilograms(selected);
			high=Math.min(selected/batchMass,volumeBatches(stock.volume(),normalized,maximum));
		} else {
			var vapor=inventory.component(component);if(vapor==null) return null;
			species=gasSpecies(vapor);selectedMass=vapor.mass();
			int mask=domain.geometry.cutCells.mask(cell.id);
			var capacities=CellRemapMicroTopology.capacities(mask,domain.gasState.frame.poreVolume(cell.id));
			int[] masks=CellRemapMicroTopology.components(mask);SignedDyadic room=SignedDyadic.ZERO;
			for(int i=0;i<masks.length;i++) if(masks[i]==candidate.key().supportMask()) room=capacities[i];
			for(var other:cell.original) if(other.key().supportMask()==candidate.key().supportMask()) room=room.subtract(other.volume());
			high=Math.min(Math.min(vapor.massUnits()/batchMass,vapor.standardVolumeNanos()/nanos),volumeBatches(room,normalized,maximum));
		}
		if(high<=0||!(selectedMass>0)) return null;
		double gasBase=function.baseEnergy(gas,selectedMass,species)/selectedMass;
		double liquidBase=function.baseEnergy(liquid,selectedMass,species)/selectedMass;
		double sourceTemperature=boiling?cell.temperature:candidate.gasTemperature();
		double carried=gasBase+registry.material(gas).sensibleSpecificEnergyAt(sourceTemperature);
		double latent=carried-liquidBase-registry.material(liquid).sensibleSpecificEnergyAt(cell.temperature);
		double upper=boiling?high*batchKg:0;
		// Vapor carries its full source-temperature U. The remaining liquid must
		// retain its cold endpoint after that mass and its sensible heat have left.
		double coldCost=carried-liquidBase-registry.material(liquid).sensibleSpecificEnergyAt(ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K);
		if(boiling&&coldCost>0) upper=Math.min(upper,Math.max(0,(cell.thermal.cellEnergy().toDouble()
				-cell.coldEnergy)/coldCost));
		if(!boiling&&coldCost<potentialPerKilogram(cell.id)) {
			double cost=potentialPerKilogram(cell.id)-coldCost;
			high=Math.min(high,(long)Math.floor(Math.max(0,cell.thermal.cellEnergy().toDouble()-cell.coldEnergy)/(cost*batchKg)));
			if(high==0)return null;
		}
		double lower=boiling?0:-high*batchKg;
		if(boiling) {
			high=Math.min(high,(long)Math.floor(upper/batchKg));
			if(high==0) return null;
			upper=high*batchKg;
		}
		boolean gasNucleation=stock==null&&cell.heatCapacity==0;
		if(gasNucleation)carried=liquidBase+registry.material(liquid).sensibleSpecificEnergyAt(candidate.gasTemperature());
		double liquidResponse=phase.saturationSlopePaPerKelvin(transition,cell.temperature,candidate.saturation().saturationPressurePa())
				*Math.max(0,latent-(boiling?0:potentialPerKilogram(cell.id)))/(gasNucleation?candidate.gasCapacity():cell.heatCapacity);
		double gasTemperature=candidate.gasTemperature(),gasCapacity=candidate.gasCapacity();
		double gasVolume=network.availableVolume(node);
		if(gasVolume==0) gasVolume=candidate.batchVolumeM3();
		double gasResponse=nanos*1e-9/batchKg*GasRegistry.STANDARD_PRESSURE_PA/GasRegistry.STANDARD_TEMPERATURE_K
				*(inventory.isEmpty()?sourceTemperature:gasTemperature)/gasVolume;
		// The same phase mass changes both vapor inventory and the liquid-excluded gas volume.
		gasResponse-=candidate.saturation().componentPartialPressurePa()*candidate.batchVolumeM3()/batchKg/gasVolume;
		if(gasCapacity>0) gasResponse+=candidate.saturation().componentPartialPressurePa()/gasTemperature
				*(carried+(boiling?potentialPerKilogram(cell.id):0)-gasBase-registry.material(gas).sensibleSpecificEnergyAt(gasTemperature))/gasCapacity;
		return new Proposal(cell,candidate,liquid,component,boiling,high,normalized,lower,upper,liquidResponse,Math.max(0,gasResponse));
	}

	/** Frozen-Cv chamber closure with inventory and pore-volume pressure response. */
	private static void closeChamber(List<Proposal> group) {
		double reference=group.getFirst().saturationPressure;
		double initial=group.getFirst().partialPressure-reference,low=initial,high=initial;
		double numerator=initial,denominator=1;
		for(var proposal:group) {
			double target=proposal.saturationPressure-reference;
			low=Math.min(low,target);high=Math.max(high,target);
			if(proposal.liquidResponse>0) {
				double response=proposal.gasResponse/proposal.liquidResponse;
				numerator+=response*target;denominator+=response;
			}
		}
		// Offset coordinates retain a tiny reservoir deficit beside a large saturation pressure.
		double minimumOffset=low,maximumOffset=high;
		double offset=Math.clamp(numerator/denominator,low,high);
		for(int sweep=0;sweep<8;sweep++) {
			double residual=initial-offset,slope=1;
			for(var proposal:group) {
				double mass=proposal.extent(reference,offset);
				residual+=proposal.gasResponse*mass;
				if(mass>proposal.lower&&mass<proposal.upper&&proposal.liquidResponse>0)
					slope+=proposal.gasResponse/proposal.liquidResponse;
			}
			if(residual>0) low=offset;else high=offset;
			double next=offset+residual/slope;
			offset=Double.isFinite(next)&&next>=low&&next<=high?next:.5*(low+high);
		}
		for(var proposal:group) proposal.mass=proposal.extent(reference,offset);
		// Saturation endpoints bound the finite chamber demand even when latent U is zero
		// or a caloric continuation makes exchange exothermic. Actual U is settled below.
		double positive=0,negative=0;
		for(var proposal:group) {
			double change=proposal.gasResponse*proposal.mass;
			if(change>0) positive+=change;else negative-=change;
		}
		double positiveLimit=Math.max(0,maximumOffset-initial+negative);
		if(positive>positiveLimit) {
			double scale=positiveLimit/positive;
			for(var proposal:group) if(proposal.mass>0) proposal.mass*=scale;
			positive=positiveLimit;
		}
		double negativeLimit=Math.max(0,initial+positive-minimumOffset);
		if(negative>negativeLimit) {
			double scale=negativeLimit/negative;
			for(var proposal:group) if(proposal.mass<0) proposal.mass*=scale;
		}
		quantize(group,true);quantize(group,false);
	}

	/** All interfaces in a chamber share whole-batch rounding, separately for each direction. */
	private static void quantize(List<Proposal> group,boolean boiling) {
		var order=new ArrayList<Proposal>();var remainders=new IdentityHashMap<Proposal,Double>();
		double requested=0;long assigned=0;
		for(var proposal:group) if(proposal.boiling==boiling) {
			double batches=Math.min(proposal.maximumBatches,Math.abs(proposal.mass)/proposal.batchKg);
			proposal.batches=(long)Math.floor(batches);assigned+=proposal.batches;requested+=batches;
			remainders.put(proposal,batches-proposal.batches);order.add(proposal);
		}
		long total=(long)Math.floor(requested);
		order.sort(Comparator.<Proposal>comparingDouble(remainders::get).reversed().thenComparingInt(p->p.cell.id));
		for(var proposal:order) if(assigned<total&&proposal.batches<proposal.maximumBatches&&remainders.get(proposal)>0) {
			proposal.batches++;assigned++;
		}
	}

	/** Reserve representable vapor columns per existing chamber before choosing an LTE cell. */
	private void limitGasRepresentation(Map<Group,List<Proposal>> groups,GasLiquidPhaseModel phase) {
		var reserved=new HashMap<Integer,GasInventory>();
		for(var group:groups.values()) for(var p:group) if(p.boiling&&p.batches>0) {
			var inventory=reserved.computeIfAbsent(p.node,node->domain.gasState.network.inventory(node));
			try { reserved.put(p.node,inventory.merge(boilInventory(p,p.batches,phase)));continue; }
			catch(GasInventory.RepresentationException unavailable) { /* Reduce only this chamber's batch. */ }
			long low=0,high=p.batches-1;
			while(low<high) {
				long middle=low+(high-low+1)/2;
				try { inventory.merge(boilInventory(p,middle,phase));low=middle; }
				catch(GasInventory.RepresentationException unavailable) { high=middle-1; }
			}
			p.batches=low;
			if(low>0)reserved.put(p.node,inventory.merge(boilInventory(p,low,phase)));
		}
	}
	private GasInventory boilInventory(Proposal p,long batches,GasLiquidPhaseModel phase) {
		long mass=Math.multiplyExact(batches,p.batchMass);var source=p.source;
		var volume=source.volume().subtract(p.batchVolume.multiply(SignedDyadic.of(batches)));
		var available=volatileSpecies(source,p.component);Map<String,Long> moved=new TreeMap<>();
		boolean allVolatile=available.size()==(source.mixture()==null?0:source.mixture().entryCount());
		if(volume.signum()==0&&allVolatile)mass=source.massUnits();
		long availableMass=allVolatile?source.massUnits():speciesMass(available);
		for(var entry:available.entrySet()) { long amount=Mass.portion(entry.getValue(),mass,availableMass);if(amount>0)moved.put(entry.getKey(),amount); }
		var batch=phase.gasBatch(p.transition,batches,moved,mass-speciesMass(moved));
		var component=batch.components().getFirst();
		return new GasInventory(List.of(new GasComponentAmount(component.componentId(),component.standardVolumeNanos(),mass,
				component.species(),component.massResidualUnits())));
	}
	private GasInventory boil(Proposal p,GasLiquidPhaseModel phase,ThermalStateFunction function) {
		var gas=boilInventory(p,p.batches,phase);var source=p.source;long mass=gas.totalMassUnits();
		var volume=source.volume().subtract(p.batchVolume.multiply(SignedDyadic.of(p.batches)));
		var moved=gasSpecies(gas.components().getFirst());
		var mixture=subtractMixture(source.mixture(),moved,source.massUnits()-mass);
		setFuture(p,volume,source.massUnits()-mass,mixture,function);
		p.cell.transfer=p.cell.future.stream().noneMatch(stock->stock.massUnits()>0)&&p.cell.thermal.unchangedSolids().isEmpty()
				?p.cell.thermal.cellEnergy().toDouble():phase.internalEnergyAtTemperature(p.transition,gas,p.cell.temperature);
		return gas;
	}
	private void condense(Proposal p,GasInventory moved,GasLiquidPhaseModel phase,ThermalStateFunction function) {
		var component=moved.components().getFirst(); var amounts=gasSpecies(component);
		var source=p.source; long mass=Math.addExact(source==null?0:source.massUnits(),component.massUnits());
		Map<String,Long> combined=source==null||source.mixture()==null?new TreeMap<>():new TreeMap<>(source.mixture().stableAmounts(domain.liquidCells.chemicalRegistry()));
		for(var entry:amounts.entrySet()) combined.merge(entry.getKey(),entry.getValue(),Math::addExact);
		setFuture(p,(source==null?SignedDyadic.ZERO:source.volume()).add(p.batchVolume.multiply(SignedDyadic.of(p.batches))),mass,mixture(combined,mass),function);
		p.cell.transfer=p.source==null&&p.cell.heatCapacity==0
				?-function.internalEnergyAtTemperature(p.liquidHandle,component.mass(),amounts,p.gasTemperature)
				:-phase.internalEnergyAtTemperature(p.transition,moved,p.gasTemperature);
	}
	private void setFuture(Proposal p,SignedDyadic volume,long mass,ChemicalMixtureRecord mixture,ThermalStateFunction function) {
		var result=new ArrayList<LiquidCellInventory.Stock>();
		for(var stock:p.cell.original) if(!stock.key().equals(p.key)) result.add(stock);
		if(volume.signum()>0) {
			double u=mass==0?0:function.internalEnergyAtTemperature(p.liquidHandle,Mass.kilograms(mass),mixture,domain.liquidCells.chemicalRegistry(),p.cell.temperature);
			result.add(new LiquidCellInventory.Stock(p.key,volume,mass,mixture,u));
		} else if(mass!=0) throw new IllegalStateException("Phase removed liquid volume without its mass");
		result.sort(Comparator.comparing(LiquidCellInventory.Stock::key)); p.cell.future=List.copyOf(result);
	}
	private List<ThermalStateFunction.InventoryPortion> portions(List<LiquidCellInventory.Stock> stocks) {
		var result=new ArrayList<ThermalStateFunction.InventoryPortion>();
		for(var stock:stocks) if(stock.massUnits()>0) result.add(new ThermalStateFunction.InventoryPortion(domain.thermalState.thermalMaterialHandleForFluid(stock.key().material()),stock.massKilograms(),
				stock.mixture()==null?Map.of():stock.mixture().stableAmounts(domain.liquidCells.chemicalRegistry())));
		return result;
	}
	private Map<String,Long> volatileSpecies(LiquidCellInventory.Stock stock,String component) {
		if(stock.mixture()==null) return Map.of();
		var result=new TreeMap<String,Long>(); var definition=domain.gasState.network.registry().component(component);
		for(var entry:stock.mixture().stableAmounts(domain.liquidCells.chemicalRegistry()).entrySet()) if(definition.allowsSpecies(entry.getKey())) result.put(entry.getKey(),entry.getValue());
		return result;
	}
	private long speciesMass(Map<String,Long> amounts) {
		return amounts.isEmpty()?0:ChemicalMixtureRecord.fromStableAmounts(domain.liquidCells.chemicalRegistry(),amounts,0).speciesMassUnits(domain.liquidCells.chemicalRegistry());
	}
	private static Map<String,Long> gasSpecies(GasComponentAmount component){
		var amounts=new TreeMap<String,Long>();
		for(var amount:component.species())amounts.put(amount.speciesId(),amount.amountQuanta());
		return amounts;
	}
	private ChemicalMixtureRecord mixture(Map<String,Long> amounts,long mass) {
		return domain.liquidCells.chemicalRegistry()==null?null:ChemicalMixtureRecord.fromStableAmounts(domain.liquidCells.chemicalRegistry(),amounts,mass-speciesMass(amounts));
	}
	private ChemicalMixtureRecord subtractMixture(ChemicalMixtureRecord source,Map<String,Long> moved,long mass) {
		if(source==null) return null;
		var remaining=new TreeMap<>(source.stableAmounts(domain.liquidCells.chemicalRegistry()));
		for(var entry:moved.entrySet()) { long amount=Math.subtractExact(remaining.getOrDefault(entry.getKey(),0L),entry.getValue()); if(amount==0) remaining.remove(entry.getKey()); else remaining.put(entry.getKey(),amount); }
		return mixture(remaining,mass);
	}
	private double potentialPerKilogram(int cell) {
		return domain.config.gravity()*(cell/domain.sizeX%domain.sizeY+.5)*domain.config.cellSize();
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

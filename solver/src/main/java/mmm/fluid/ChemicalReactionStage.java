package mmm.fluid;

import mmm.inventory.LiquidCellInventory;

import mmm.chemistry.*;
import mmm.thermal.ThermalDomainState;
import mmm.thermal.ThermalStateFunction;
import mmm.units.SignedDyadic;

import java.util.*;

/** Same-support cell chemistry with one inventory and caloric publication per logical pass. */
final class ChemicalReactionStage {
	private final FluidDomain domain;
	private ChemicalRegistry candidateRegistry;
	private boolean[] reactantSpecies;
	private List<ReactionInput> reactionInputs;
	private record ReactionInput(int phase, int[] species, int[] amounts) { }

	private void prepareReactionInputs(ChemicalRegistry registry) {
		if (candidateRegistry == registry) return;
		var inputs = new ArrayList<ReactionInput>();
		reactantSpecies = new boolean[registry.species().size()];
		for (var rule : registry.reactionOrder()) {
			int[] species = new int[rule.reactants().size()], amounts = new int[species.length];
			int index = 0;
			for (var term : rule.reactants().entrySet()) {
				species[index] = registry.speciesHandle(term.getKey());
				amounts[index] = term.getValue();
				reactantSpecies[species[index++]] = true;
			}
			inputs.add(new ReactionInput(registry.phaseHandle(rule.phaseId()), species, amounts));
		}
		reactionInputs = List.copyOf(inputs);
		candidateRegistry = registry;
	}

	private boolean hasReactant(ChemicalMixtureRecord mixture) {
		if (mixture != null) for (int entry = 0; entry < mixture.entryCount(); entry++)
			if (reactantSpecies[mixture.speciesHandle(entry)]) return true;
		return false;
	}
	private boolean hasReactionInputs(LiquidCellInventory store) {
		for (var rule : reactionInputs) {
			boolean complete = true;
			for (int species : rule.species) if (!store.hasSpecies(species)) { complete = false; break; }
			if (complete) return true;
		}
		return false;
	}

	private boolean canReact(Group group, List<LiquidCellInventory.Stock> stocks) {
		for (var rule : reactionInputs) if (rule.phase == group.phase) {
			boolean complete = true;
			for (int term = 0; term < rule.species.length; term++) {
				long amount = 0;
				for (var stock : stocks) if (stock.mixture() != null)
					amount = Math.addExact(amount, stock.mixture().amount(rule.species[term]));
				if (amount < rule.amounts[term]) { complete = false; break; }
			}
			if (complete) return true;
		}
		return false;
	}
	ChemicalReactionStage(FluidDomain domain) { this.domain=domain; }
	int[] captureDirtyCells() {
		var chemistry=domain.chemistry;
		return chemistry==null?new int[0]:Arrays.stream(chemistry.dirtyCells()).filter(domain.activityGroups::thermalActive).toArray();
	}
	private record Group(int support,int phase) implements Comparable<Group> {
		public int compareTo(Group other) { int s=Integer.compare(support,other.support);return s!=0?s:Integer.compare(phase,other.phase); }
	}

	RegionChemistry.ReactionStats step() {
		var scheduler=domain.chemistry;
		if(scheduler==null||!scheduler.hasDirtyCells()) return new RegionChemistry.ReactionStats(0,0,0);
		var store=domain.liquidCells; var registry=store.chemicalRegistry(); var thermal=domain.thermalState;
		prepareReactionInputs(registry);
		if (!hasReactionInputs(store)) {
			scheduler.restoreDirtyCells(Arrays.stream(scheduler.dirtyCells()).filter(cell->!domain.activityGroups.thermalActive(cell)).toArray());
			return new RegionChemistry.ReactionStats(0,0,0);
		}
		var function=thermal==null?null:new ThermalStateFunction(thermal.registry());
		int[] costs=new int[registry.phases().size()];
		for(var rule:registry.reactionOrder()) costs[registry.phaseHandle(rule.phaseId())]++;
		var dirty=new TreeSet<Integer>(); for(int cell:scheduler.dirtyCells()) dirty.add(cell);
		var eligible=new TreeMap<Integer,TreeMap<Group,List<LiquidCellInventory.Stock>>>();
		var consumed=new ArrayList<Integer>();
		for(int cell:dirty) {
			if(!domain.activityGroups.thermalActive(cell)) continue;
			boolean possible=false;
			for(var stock:store.physicalStocksInCell(cell)) if(hasReactant(stock.mixture())) { possible=true;break; }
			if(!possible) { consumed.add(cell);continue; }
			var groups=new TreeMap<Group,List<LiquidCellInventory.Stock>>();
			for(var stock:store.physicalStocksInCell(cell)) {
				int phase=registry.phaseHandleForMaterial(domain.materialRegistry.material(stock.key().material()).id());
				groups.computeIfAbsent(new Group(stock.key().supportMask(),phase),ignored->new ArrayList<>()).add(stock);
			}
			groups.entrySet().removeIf(entry->!canReact(entry.getKey(),entry.getValue()));
			if(groups.isEmpty()) consumed.add(cell);else eligible.put(cell,groups);
		}
		for (int cell : consumed) dirty.remove(cell);
		if(eligible.isEmpty()) {
			scheduler.restoreDirtyCells(dirty.stream().mapToInt(Integer::intValue).toArray());
			return new RegionChemistry.ReactionStats(consumed.size(),0,0);
		}
		var originalThermal=thermal==null?null:thermal.preparePhaseCandidateCells(eligible.keySet().stream().mapToInt(Integer::intValue).toArray(),domain.domainCapabilities.thermalMarkerContext());
		var futureCells=new TreeMap<Integer,List<LiquidCellInventory.Stock>>();
		var trials=new TreeMap<Integer,ThermalDomainState.PhaseCellTrial>();
		long regionQuanta=0;
		for(var stock:store.stocks()) if(stock.mixture()!=null) regionQuanta=Math.addExact(regionQuanta,stock.mixture().totalQuanta());
		int visited=consumed.size(),evaluations=0,changed=0,cursor=scheduler.reactionCursor();
		for(int offset=0;offset<domain.cellCount;offset++) {
			int cell=(scheduler.reactionCursor()+offset)%domain.cellCount;
			if(!dirty.contains(cell) || !domain.activityGroups.thermalActive(cell)) continue;
			var groups=eligible.get(cell);
			int cost=0; for(var group:groups.keySet()) cost=Math.addExact(cost,costs[group.phase]);
			if(cost>ChemicalLimits.MAX_REACTION_EVALUATIONS_PER_STEP-evaluations) { cursor=cell;break; }
			cursor=(cell+1)%domain.cellCount;visited++;evaluations+=cost;
			var future=new TreeMap<LiquidCellInventory.Key,LiquidCellInventory.Stock>();
			for(var stock:store.stocksInCell(cell)) future.put(stock.key(),stock);
			long delta=0; int cellChanged=0;
			boolean pending=false;
			var cold=thermal==null?SignedDyadic.ZERO:coldEnergy(future.values(),function);
			if(thermal!=null)for(var solid:originalThermal[cell].unchangedSolids())cold=cold.add(SignedDyadic.of(
					function.internalEnergyAtTemperature(solid.materialHandle(),solid.massKg(),solid.speciesQuanta(),mmm.thermal.ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K)));
			try {
				for(var entry:groups.entrySet()) {
					long[] amounts=new long[registry.species().size()];
					for(var stock:entry.getValue()) for(int i=0;i<stock.mixture().entryCount();i++)
						amounts[stock.mixture().speciesHandle(i)]=Math.addExact(amounts[stock.mixture().speciesHandle(i)],stock.mixture().amountAt(i));
					var sources=entry.getValue();
					var engine = new ChemicalReactionEngine(registry);
					long baselineQuanta = Arrays.stream(amounts).sum();
					long externalQuanta = regionQuanta + delta - baselineQuanta;
					SignedDyadic otherCold = thermal == null ? SignedDyadic.ZERO : cold.subtract(coldEnergy(sources, function));
					var result = engine.reactInventory(entry.getKey().phase, amounts, (rule, before, complete, maximum) -> {
						long extent = maximum;
						long coefficient = rule.products().values().stream().mapToLong(Integer::longValue).sum()
								- rule.reactants().values().stream().mapToLong(Integer::longValue).sum();
						if (coefficient > 0) extent = Math.min(extent, Math.max(0,
								(ChemicalLimits.MAX_REGION_TOTAL_QUANTA - externalQuanta - Arrays.stream(before).sum()) / coefficient));
						if (thermal == null || extent == 0) return extent;
						long[] candidate = extent == maximum ? complete : engine.advance(before, rule, extent);
						SignedDyadic available = originalThermal[cell].cellEnergy().subtract(otherCold);
						SignedDyadic target = coldEnergyAmounts(sources, candidate, registry, function);
						if (target.compareTo(available) <= 0) return extent;
						SignedDyadic baseline = coldEnergyAmounts(sources, before, registry, function);
						double slope = target.subtract(baseline).toDouble() / extent;
						double allowance = allocationEnergyResolution(sources, before, candidate, registry);
						return slope > 0 ? Math.min(extent, (long) Math.floor(Math.max(0,
								available.subtract(baseline).toDouble() - allowance) / slope)) : 0;
					});
					pending |= result.pending();
					var replacements = distribute(sources, result.inventory(), registry);
					for (var replacement : replacements) {
						var before = future.put(replacement.key(), replacement);
						delta = Math.addExact(delta, replacement.mixture().totalQuanta() - before.mixture().totalQuanta());
					}
					if (thermal != null) cold = otherCold.add(coldEnergy(replacements, function));
				}
				for(var after:future.values())if(!Objects.equals(after.mixture(),store.stock(after.key()).mixture()))cellChanged++;
				if(regionQuanta+delta>ChemicalLimits.MAX_REGION_TOTAL_QUANTA)
					throw new ChemicalReactionEngine.BudgetExceededException("Reaction exceeds region species inventory");
				var rows=List.copyOf(future.values());
				ThermalDomainState.PhaseCellTrial trial=null;
				if(thermal!=null&&cellChanged>0) {
					var portions=new ArrayList<ThermalStateFunction.InventoryPortion>();
					for(var stock:rows) if(stock.massUnits()>0) portions.add(new ThermalStateFunction.InventoryPortion(
							thermal.thermalMaterialHandleForFluid(stock.key().material()),stock.massKilograms(),stock.mixture().stableAmounts(registry)));
					trial=thermal.trialPhaseCellEnergy(originalThermal[cell],portions,0,0);
				}
				if(cellChanged>0) {
					futureCells.put(cell,rows); if(trial!=null) trials.put(cell,trial);
					regionQuanta+=delta;changed+=cellChanged;
				}
				if(!pending)dirty.remove(cell);
			} catch(ChemicalReactionEngine.BudgetExceededException unavailable) {
				// Composition capacity retains the cell until a material transfer changes it.
			}
		}
		ThermalDomainState.PhaseBatchEnergyPlan energy=null;
		if(thermal!=null&&!trials.isEmpty()) {
			int[] counts=futureCells.values().stream().mapToInt(rows->(int)rows.stream().filter(s->s.massUnits()>0).count()).toArray();
			energy=thermal.planPhaseBatchEnergy(List.copyOf(trials.values()),counts,SignedDyadic.ZERO,SignedDyadic.ZERO,domain.domainCapabilities.thermalMarkerContext());
		}
		var edits=new ArrayList<LiquidCellInventory.Edit>();
		for(var entry:futureCells.entrySet()) {
			double[] energies=energy==null?null:energy.liquidEnergies(entry.getKey());int ordinal=0;
			for(var stock:entry.getValue()) {
				double u=stock.massUnits()==0?0:energies==null?stock.internalEnergy():energies[ordinal++];
				edits.add(new LiquidCellInventory.Edit(stock.key(),new LiquidCellInventory.Stock(stock.key(),stock.volume(),
						stock.massUnits(),stock.mixture(),u)));
			}
		}
		if(!edits.isEmpty()) {
			var plan=store.planEdits(edits);plan.requireCurrent();if(energy!=null) thermal.requirePhaseBatchEnergyCurrent(energy);
			plan.commit();if(energy!=null) thermal.commitPhaseBatchEnergy(energy,domain.domainCapabilities.thermalMarkerContext());domain.gridDirty=true;
		}
		scheduler.restoreDirtyCells(dirty.stream().mapToInt(Integer::intValue).toArray());scheduler.restoreReactionCursor(cursor);
		return new RegionChemistry.ReactionStats(visited,evaluations,changed);
	}
	private SignedDyadic coldEnergy(Collection<LiquidCellInventory.Stock> stocks,ThermalStateFunction function){
		var energy=SignedDyadic.ZERO;
		for(var stock:stocks)if(stock.massUnits()>0)energy=energy.add(SignedDyadic.of(function.internalEnergyAtTemperature(
				domain.thermalState.thermalMaterialHandleForFluid(stock.key().material()),stock.massKilograms(),stock.mixture(),domain.liquidCells.chemicalRegistry(),mmm.thermal.ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K)));
		return energy;
	}
	/** Cold feasibility is evaluated on the unconstrained transient species vector. */
	private SignedDyadic coldEnergyAmounts(List<LiquidCellInventory.Stock> stocks, long[] amounts,
	                                      ChemicalRegistry chemistry, ThermalStateFunction function) {
		var allocated = allocate(stocks, amounts);
		var result = SignedDyadic.ZERO;
		for (int i = 0; i < stocks.size(); i++) {
			var stock = stocks.get(i); if (stock.massUnits() == 0) continue;
			var stable = new TreeMap<String, Long>();
			for (var term : allocated.get(i).entrySet()) stable.put(chemistry.species(term.getKey()).id(), term.getValue());
			result = result.add(SignedDyadic.of(function.internalEnergyAtTemperature(
					domain.thermalState.thermalMaterialHandleForFluid(stock.key().material()), stock.massKilograms(), stable,
					mmm.thermal.ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K)));
		}
		return result;
	}

	private double allocationEnergyResolution(List<LiquidCellInventory.Stock> stocks,long[] before,long[] after,ChemicalRegistry chemistry){
		if(stocks.size()==1)return 0;
		double energy=0;
		for(var stock:stocks){
			int material=domain.thermalState.thermalMaterialHandleForFluid(stock.key().material());
			for(int species=0;species<before.length;species++)if(before[species]>0||after[species]>0)
				energy+=Math.abs(domain.thermalState.registry().speciesOffset(material,chemistry.species(species).id()).referenceInternalEnergyJPerMol().toDouble()/ChemicalLimits.QUANTA_PER_MOL);
		}
		return 2*energy;
	}

	private static List<TreeMap<Integer,Long>> allocate(List<LiquidCellInventory.Stock> source,long[] products) {

		double[] weights=source.stream().mapToDouble(LiquidCellInventory.Stock::massUnits).toArray();
		int[] ordinals=new int[source.size()];for(int i=0;i<ordinals.length;i++) ordinals[i]=i;
		var allocations=new ArrayList<TreeMap<Integer,Long>>(source.size());for(int i=0;i<source.size();i++) allocations.add(new TreeMap<>());
		for(int species=0;species<products.length;species++) if(products[species]>0) {
			long[] amounts=BinaryVolumeAllocator.allocate(products[species],weights,ordinals);
			for(int i=0;i<amounts.length;i++) if(amounts[i]>0) allocations.get(i).put(species,amounts[i]);
		}
		return allocations;
	}

	/** Products follow actual SI mass. Mass, cell volume and momentum are unchanged by chemistry. */
	private static List<LiquidCellInventory.Stock> distribute(List<LiquidCellInventory.Stock> source,long[] products,ChemicalRegistry registry) {
		var allocations = allocate(source, products);
		var result=new ArrayList<LiquidCellInventory.Stock>(source.size());
		for(int i=0;i<source.size();i++) {
			var stock=source.get(i);var values=allocations.get(i);
			if(values.size()>ChemicalLimits.MAX_SPECIES_PER_PARCEL) throw new ChemicalReactionEngine.BudgetExceededException("Reaction cell composition exceeds species capacity");
			var mixture=new ChemicalMixtureRecord(values.keySet().stream().mapToInt(Integer::intValue).toArray(),values.values().stream().mapToLong(Long::longValue).toArray(),0);
			mixture=mixture.withMassResidualUnits(Math.subtractExact(stock.massUnits(),mixture.speciesMassUnits(registry)));
			result.add(new LiquidCellInventory.Stock(stock.key(),stock.volume(),stock.massUnits(),mixture,stock.internalEnergy()));
		}
		return result;
	}
}

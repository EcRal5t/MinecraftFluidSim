package mmm.fluid;

import mmm.inventory.LiquidCellInventory;

import mmm.chemistry.*;
import mmm.thermal.ThermalDomainState;
import mmm.thermal.ThermalStateFunction;
import mmm.units.SignedDyadic;

import java.util.*;

/** Same-support cell chemistry with one inventory and caloric publication per logical pass. */
final class ChemicalReactionStage {
	record Workspace(int cellCount, mmm.thermal.ThermalCarrierContext thermalMarkers) { }
	interface Access {
		ChemicalParcelStore chemicalParcels();
		FluidMaterialRegistry materialRegistry();
		LiquidCellInventory liquidCells();
		ThermalDomainState thermalState();
		void markGridDirty();
	}
	private final Workspace state;
	private final Access access;
	ChemicalReactionStage(Workspace state, Access access) { this.state=state; this.access=access; }
	int[] captureDirtyCells() {
		var chemistry=access.chemicalParcels();
		return chemistry==null?new int[0]:chemistry.dirtyCells();
	}
	private record Group(int support,int phase) implements Comparable<Group> {
		public int compareTo(Group other) { int s=Integer.compare(support,other.support);return s!=0?s:Integer.compare(phase,other.phase); }
	}

	ChemicalParcelStore.ReactionStats step() {
		var scheduler=access.chemicalParcels();
		if(scheduler==null||!scheduler.hasDirtyCells()) return new ChemicalParcelStore.ReactionStats(0,0,0);
		var store=access.liquidCells(); var registry=store.chemicalRegistry(); var thermal=access.thermalState();
		var engine=new ChemicalReactionEngine(registry);
		int[] costs=new int[registry.phases().size()];
		for(var rule:registry.reactionOrder()) costs[registry.phaseHandle(rule.phaseId())]++;
		var dirty=new TreeSet<Integer>(); for(int cell:scheduler.dirtyCells()) dirty.add(cell);
		var originalThermal=thermal==null?null:thermal.preparePhaseCandidateCells(dirty.stream().mapToInt(Integer::intValue).toArray(),state.thermalMarkers());
		var futureCells=new TreeMap<Integer,List<LiquidCellInventory.Stock>>();
		var trials=new TreeMap<Integer,ThermalDomainState.PhaseCellTrial>();
		long regionQuanta=0;
		for(var stock:store.stocks()) if(stock.mixture()!=null) regionQuanta=Math.addExact(regionQuanta,stock.mixture().totalQuanta());
		int visited=0,evaluations=0,changed=0,cursor=scheduler.reactionCursor();
		for(int offset=0;offset<state.cellCount();offset++) {
			int cell=(scheduler.reactionCursor()+offset)%state.cellCount();
			if(!dirty.contains(cell)) continue;
			var groups=new TreeMap<Group,List<LiquidCellInventory.Stock>>();
			for(var stock:store.physicalStocksInCell(cell)) {
				int phase=registry.phaseHandleForMaterial(access.materialRegistry().material(stock.key().material()).id());
				groups.computeIfAbsent(new Group(stock.key().supportMask(),phase),ignored->new ArrayList<>()).add(stock);
			}
			int cost=0; for(var group:groups.keySet()) cost=Math.addExact(cost,costs[group.phase]);
			if(cost>ChemicalLimits.MAX_REACTION_EVALUATIONS_PER_STEP-evaluations) { cursor=cell;break; }
			cursor=(cell+1)%state.cellCount();visited++;evaluations+=cost;
			var future=new TreeMap<LiquidCellInventory.Key,LiquidCellInventory.Stock>();
			for(var stock:store.stocksInCell(cell)) future.put(stock.key(),stock);
			long delta=0; int cellChanged=0;
			try {
				for(var entry:groups.entrySet()) {
					long[] amounts=new long[registry.species().size()];
					for(var stock:entry.getValue()) for(int i=0;i<stock.mixture().entryCount();i++)
						amounts[stock.mixture().speciesHandle(i)]=Math.addExact(amounts[stock.mixture().speciesHandle(i)],stock.mixture().amountAt(i));
					if(Arrays.stream(amounts).allMatch(n->n==0)) continue;
					long[] reacted=engine.reactInventory(entry.getKey().phase,amounts).inventory();
					if(Arrays.equals(amounts,reacted)) continue;
					var replacements=distribute(entry.getValue(),reacted,registry);
					for(var replacement:replacements) {
						var before=future.put(replacement.key(),replacement);
						delta=Math.addExact(delta,replacement.mixture().totalQuanta()-before.mixture().totalQuanta());
						if(!replacement.mixture().equals(before.mixture())) cellChanged++;
					}
				}
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
				dirty.remove(cell);
			} catch(ChemicalReactionEngine.BudgetExceededException | ThermalStateFunction.EnergyOutsideValidityException
			        | ThermalStateFunction.EnergyRepresentationException unavailable) {
				// An energy-limited extent is retried after transport changes this cell's available heat.
			}
		}
		ThermalDomainState.PhaseBatchEnergyPlan energy=null;
		if(thermal!=null&&!trials.isEmpty()) {
			int[] counts=futureCells.values().stream().mapToInt(rows->(int)rows.stream().filter(s->s.massUnits()>0).count()).toArray();
			energy=thermal.planPhaseBatchEnergy(List.copyOf(trials.values()),counts,SignedDyadic.ZERO,SignedDyadic.ZERO,state.thermalMarkers());
		}
		var edits=new ArrayList<LiquidCellInventory.Edit>();
		for(var entry:futureCells.entrySet()) {
			double[] energies=energy==null?null:energy.liquidEnergies(entry.getKey());int ordinal=0;
			for(var stock:entry.getValue()) {
				double u=stock.massUnits()==0?0:energies==null?stock.internalEnergy():energies[ordinal++];
				edits.add(new LiquidCellInventory.Edit(stock.key(),new LiquidCellInventory.Stock(stock.key(),stock.volume(),
						stock.massUnits(),stock.mixture(),u,stock.indicator())));
			}
		}
		if(!edits.isEmpty()) {
			var plan=store.planEdits(edits);plan.requireCurrent();if(energy!=null) thermal.requirePhaseBatchEnergyCurrent(energy);
			plan.commit();if(energy!=null) thermal.commitPhaseBatchEnergy(energy,state.thermalMarkers());access.markGridDirty();
		}
		scheduler.restoreDirtyCells(dirty.stream().mapToInt(Integer::intValue).toArray());scheduler.restoreReactionCursor(cursor);
		return new ChemicalParcelStore.ReactionStats(visited,evaluations,changed);
	}

	/** Products follow actual SI mass. Mass, cell volume and momentum are unchanged by chemistry. */
	private static List<LiquidCellInventory.Stock> distribute(List<LiquidCellInventory.Stock> source,long[] products,ChemicalRegistry registry) {
		double[] weights=source.stream().mapToDouble(LiquidCellInventory.Stock::massUnits).toArray();
		int[] ordinals=new int[source.size()];for(int i=0;i<ordinals.length;i++) ordinals[i]=i;
		var allocations=new ArrayList<TreeMap<Integer,Long>>(source.size());for(int i=0;i<source.size();i++) allocations.add(new TreeMap<>());
		for(int species=0;species<products.length;species++) if(products[species]>0) {
			long[] amounts=BinaryVolumeAllocator.allocate(products[species],weights,ordinals);
			for(int i=0;i<amounts.length;i++) if(amounts[i]>0) allocations.get(i).put(species,amounts[i]);
		}
		var result=new ArrayList<LiquidCellInventory.Stock>(source.size());
		for(int i=0;i<source.size();i++) {
			var stock=source.get(i);var values=allocations.get(i);
			if(values.size()>ChemicalLimits.MAX_SPECIES_PER_PARCEL) throw new ChemicalReactionEngine.BudgetExceededException("Reaction cell composition exceeds species capacity");
			var mixture=new ChemicalMixtureRecord(values.keySet().stream().mapToInt(Integer::intValue).toArray(),values.values().stream().mapToLong(Long::longValue).toArray(),0);
			mixture=mixture.withMassResidualUnits(Math.subtractExact(stock.massUnits(),mixture.speciesMassUnits(registry)));
			result.add(new LiquidCellInventory.Stock(stock.key(),stock.volume(),stock.massUnits(),mixture,stock.internalEnergy(),stock.indicator()));
		}
		return result;
	}
}

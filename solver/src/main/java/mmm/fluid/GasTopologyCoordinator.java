package mmm.fluid;

import mmm.inventory.LiquidCellInventory;


import mmm.fluid.air.AirEdge;
import mmm.fluid.air.AirNetwork;
import mmm.fluid.air.GasPartitionWeights;
import mmm.fluid.air.GasAuditLedger;
import mmm.fluid.air.GasInventory;
import mmm.fluid.air.GasRegistry;
import mmm.fluid.air.GasStateSnapshot;
import mmm.granular.GranularCouplingFields;
import mmm.thermal.GasEnergyStore;
import mmm.thermal.GasThermalModel;
import mmm.thermal.ThermalDomainState;
import mmm.units.SignedDyadic;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Joint inventory and energy remapping on captured positive PLIC microbox supports. */
final class GasTopologyCoordinator {
	private final FluidDomain domain;
	private enum VolumeWork { ADIABATIC, MECHANICAL_TRANSPORT, FIXED_ENERGY }
	private record SourceVolumeWork(int node,SignedDyadic volume,double energy,List<GasInventory> parts,
	                                int[] targets,SignedDyadic[] controls,double[] partEnergy,boolean mechanicalMode) { }
	record PhaseBirth(int cell, int supportMask, GasInventory inventory, double energy) { }
	record PhaseBatch(List<GasInventory> inventories,
	                  GasAuditLedger source, GasAuditLedger sink,
	                  GasEnergyStore.InventoryEnergyPlan gasThermal,
	                  ThermalDomainState.PhaseBatchEnergyPlan cellThermal,
	                  List<GasInventory> retainedInventories, double[] retainedEnergies,
	                  List<PhaseBirth> births) {
		PhaseBatch {
			inventories = List.copyOf(inventories);
			retainedInventories = List.copyOf(retainedInventories); retainedEnergies = retainedEnergies.clone();
			births = List.copyOf(births);
		}
	}
	GasTopologyCoordinator(FluidDomain domain) { this.domain = domain; }

	GasStateSnapshot bootstrap(String fingerprint) { refresh(); return export(fingerprint); }
	GasStateSnapshot export(String fingerprint) {
		if (domain.gasState.frame == null) throw new IllegalStateException("Gas topology has not been explicitly initialized");
		if (!domain.gasState.network.auditClosed()) throw new IllegalStateException("Gas component audit does not close");
		return GasStateSnapshot.capture(domain.sizeX, domain.sizeY, domain.sizeZ,
				domain.gasState.frame.pieceNodes(), domain.gasState.network, fingerprint == null ? "" : fingerprint);
	}
	void restore(GasStateSnapshot state, GasRegistry registry) { restoreOwners(state, registry, List.of()); }

	/** Reconstructs capacities before publishing restored gas stock or thermal node columns. */
	void restore(GasStateSnapshot state, GasRegistry registry, FlipThermalParticleState thermal) {
		if (thermal != null) thermal.requireCompatible(domain.sizeX, domain.sizeY, domain.sizeZ, domain.config.cellSize(), domain.liquidCells.physicalSize());
		restoreOwners(state, registry, thermal == null ? List.of() : thermal.coherentGroups());
	}
	void restore(GasStateSnapshot state, GasRegistry registry, mmm.thermal.ThermalOwnerSnapshot thermal) {
		restoreOwners(state, registry, thermal == null ? List.of() : thermal.coherentGroups());
	}
	private void restoreOwners(GasStateSnapshot state, GasRegistry registry, List<mmm.thermal.CoherentPhaseStore.Group> groups) {
		if (state == null || registry == null || state.sizeX() != domain.sizeX
				|| state.sizeY() != domain.sizeY || state.sizeZ() != domain.sizeZ
				|| !state.registryFingerprint().equals(registry.fingerprint()))
			throw new IllegalArgumentException("Gas restore identity/dimensions mismatch");
		if (domain.gasState.frame != null || domain.gasState.network.nodeCount() != 0)
			throw new IllegalStateException("Gas restore requires an uninitialized topology");
		domain.domainFlowFields.ensureInventoryGrid(); long[] occupied = currentOccupied();
		if (!groups.isEmpty()) {
			domain.domainCapabilities.validateCoherentRestoreCapacity(groups);
			var occupancy = new GranularCouplingFields(domain.sizeX, domain.sizeY, domain.sizeZ, domain.config.cellSize());
			long[] fixed = new long[domain.cellCount];
			for (var group : groups) fixed[group.cell()] = Math.addExact(fixed[group.cell()], group.occupiedVolumeSvq());
			occupancy.setFixedCellSvq(fixed);
			if (domain.granularState != null) occupancy.rebuild(domain.granularState.parcels, domain.granularState.deposits);
			occupied = occupancy.copySolidSvq();
		}
		domain.ensurePhaseOccupancy();PhaseOccupancyTransport.requireGeometry(domain,domain.phaseOccupancy,occupied);
		var frame = GasRuntimeGeometry.capture(domain, occupied, domain.gasState.sealedDomainBoundary);
		if (!Arrays.equals(state.pieceNodeOrdinal(), frame.pieceNodes()) || state.nodes().size() != frame.nodeCount())
			throw new IllegalArgumentException("Restored gas microbox membership differs from carrier geometry");
		for (int node = 1; node <= frame.nodeCount(); node++)
			if (state.nodes().get(node - 1).topologyKey() != frame.minimumPiece(node))
				throw new IllegalArgumentException("Restored gas topology key mismatch");
		AirNetwork restored = new AirNetwork(registry);
		restored.restoreNodes(volumes(frame), state.nodes().stream().map(GasStateSnapshot.Node::inventory).toList(),
				state.externalSource(), state.escapedSink());
		for (var edge : frame.topology().connections())
			restored.addOrMergeEdge(edge.firstNode(), edge.secondNode(), edge.area(), 0.65);
		domain.gasState.network = restored; domain.gasState.frame = frame;
		domain.gasState.pressureActive = frame.nodeCount() > 0;
	}

	/** A private candidate continues immutable geometry and an independently owned network. */
	void restoreRuntime(GasRuntimeGeometry frame, AirNetwork.RuntimeSnapshot network) {
		if (domain.gasState.frame != null || domain.gasState.network.nodeCount() != 0)
			throw new IllegalStateException("Runtime gas continuation requires an empty target");
		domain.gasState.network = AirNetwork.fromRuntimeSnapshot(network); domain.gasState.frame = frame;
		domain.gasState.pressureActive = frame.nodeCount() > 0;
	}

	void refresh() {
		domain.domainFlowFields.ensureInventoryGrid();
		var frame = domain.gasState.frame;
		var occupied = currentOccupied();
		if (frame != null && frame.sealedExterior() == domain.gasState.sealedDomainBoundary
				&& frame.matchesCarrierGeometry(domain, occupied)) return;
		if(frame==null)commit(plan(GasRuntimeGeometry.capture(domain,occupied,domain.gasState.sealedDomainBoundary),VolumeWork.ADIABATIC,null,null));
		else commit(planCells(domain.liquidCells,occupied,null,VolumeWork.ADIABATIC,null,null));
	}
	GasTopologyPlan planAfterPhaseBatch(LiquidCellInventory future, PhaseBatch phase) {
		domain.thermalState.requireGasInventoryEnergyCurrent(phase.gasThermal(), domain.gasState.network);
		domain.thermalState.requirePhaseBatchEnergyCurrent(phase.cellThermal());
		return planCells(future,currentOccupied(),null,VolumeWork.FIXED_ENERGY,phase,null);
	}
	/** External carrier edits retain the gas response to their actual volume displacement. */
	GasTopologyPlan planAfterCarrierEdit(LiquidCellInventory future,long[] occupied){
		return planCells(future,occupied,null,VolumeWork.ADIABATIC,null,null);
	}
	/** Internal phase and reaction conversions settle fixed-volume energy before mechanical evolution. */
	GasTopologyPlan planAfterInternalConversion(LiquidCellInventory future,long[] occupied,GasVolumeAllocation allocation){
		return planCells(future,occupied,null,VolumeWork.FIXED_ENERGY,null,allocation);
	}
    GasTopologyPlan planAfterMechanicalTransport(LiquidCellInventory future,long[] occupied,GasVolumeAllocation allocation){
        // A prescribed velocity field without a prepared pressure step retains its
        // explicit displacement work; missing modes never classify gas as unresolved.
        var work=domain.pressureSystem.hasGasMechanicalModes(domain.gasState.frame)
                ?VolumeWork.MECHANICAL_TRANSPORT:VolumeWork.ADIABATIC;
        return planCells(future,occupied,null,work,null,allocation);
    }

	GasTopologyPlan planAfterLiquidDisplacement(LiquidCellInventory future,long[] occupied,GasVolumeAllocation allocation) {
		return planCells(future,occupied,null,VolumeWork.ADIABATIC,null,allocation);
	}
	GasTopologyPlan planAfterCollisionCells(LiquidCellInventory future,long[] occupied,byte[] masks) {
		return planCells(future,occupied,masks,VolumeWork.ADIABATIC,null,null);
	}
	private GasTopologyPlan planCells(LiquidCellInventory future,long[] occupied,byte[] masks,
	                                VolumeWork volumeWork,PhaseBatch phase,GasVolumeAllocation allocation) {
		if(allocation==null)allocation=GasVolumeAllocation.prepare(domain,future,occupied,masks);
		allocation.requireCurrent(domain,future);
		var next=allocation.preparedFrame();
		if(domain.gasState.frame==null)return new GasTopologyPlan(next,null,null,null,domain.gasState.network,domain.gasState.network.revision(),new SignedDyadic[0]);
		return plan(next,volumeWork,phase,allocation);
	}
	private long[] currentOccupied() {
		return domain.granularCoupling == null ? new long[domain.cellCount] : domain.granularCoupling.copySolidSvq();
	}
	void requireAfterPhaseBatchCurrent(GasTopologyPlan plan, PhaseBatch phase) {
		if (plan == null || plan.source() != domain.gasState.frame
				|| plan.frame().sealedExterior() != domain.gasState.sealedDomainBoundary
				|| !plan.frame().matchesSourceCarrierGeometry(domain, currentOccupied()))
			throw new IllegalStateException("Phase gas geometry changed after planning");
		domain.gasState.network.requireCanCommitRemap(plan.gasPlan());
		domain.thermalState.requireGasRemapAfterBatchCurrent(plan.thermalPlan(), phase.gasThermal(),
				phase.cellThermal(), domain.gasState.network);
	}
	private GasInventory inventory(int node, PhaseBatch edit) {
		return edit != null ? edit.retainedInventories().get(node - 1) : domain.gasState.network.inventory(node);
	}
	private double energy(int node, PhaseBatch edit) {
		return edit != null ? edit.retainedEnergies()[node - 1] : domain.thermalState.gasNodeEnergy(node, domain.gasState.network);
	}
	private double[] partitionEnergy(int node, List<GasInventory> parts, PhaseBatch edit) {
		return edit != null
				? new GasThermalModel(domain.thermalState.registry(), domain.gasState.network.registry())
						.partitionEnergy(inventory(node, edit), energy(node, edit), parts)
				: domain.thermalState.partitionGasNodeEnergy(node, parts, domain.gasState.network);
	}
	GasTopologyPlan planAfterOperatorClear(LiquidCellInventory future) {
		domain.domainFlowFields.ensureInventoryGrid();
		return planCells(future,new long[domain.cellCount],null,VolumeWork.ADIABATIC,null,null);
	}
	GasTopologyPlan planAfterCondensedClear(LiquidCellInventory future) {
		var retained=new GranularCouplingFields(domain.sizeX,domain.sizeY,domain.sizeZ,domain.config.cellSize());
		if(domain.granularState!=null)retained.rebuild(domain.granularState.parcels,domain.granularState.deposits);
		return planCells(future,retained.copySolidSvq(),null,VolumeWork.ADIABATIC,null,null);
	}

	private GasTopologyBlockedException missingGasSuccessor(int node,GasInventory inventory,PhaseBatch edit,
	                                                      GasRuntimeGeometry future,GasVolumeAllocation allocation) {
		var old=domain.gasState.frame;var supports=new StringBuilder();int count=0;
		for(var component:old.capacity().components()) {
			if(component.availableVolumeNormalized().signum()==0
					||old.topology().nodeOfComponent(component.cell(),component.supportMask())!=node)continue;
			if(++count>8)continue;
			if(!supports.isEmpty())supports.append(", ");
			supports.append("{cell=").append(component.cell()).append(" support=").append(component.supportMask())
					.append(" oldDry=").append(component.availableVolumeNormalized()).append(" future=[");
			boolean first=true;
			for(var candidate:future.capacity().components())if(candidate.cell()==component.cell()
					&&(candidate.supportMask()&component.supportMask())!=0) {
				if(!first)supports.append(", ");first=false;
				supports.append(candidate.supportMask()).append(':').append(candidate.availableVolumeNormalized())
						.append("/node=").append(future.topology().nodeOfComponent(candidate.cell(),candidate.supportMask()));
			}
			supports.append("]}");
		}
		var shares=new StringBuilder();var sources=allocation.sources();int shown=0;
		for(int entry=sources.first(node+1);entry>=0&&shown<8;entry=sources.next(entry),shown++){
			if(!shares.isEmpty())shares.append(", ");
			shares.append(sources.key(entry)).append(':').append(sources.amount(entry));
		}
		return new GasTopologyBlockedException("Allocated finite gas has no successor: node="+node
				+" operation="+(edit==null?"carrier edit":"phase batch")
				+" oldVolumeM3="+domain.gasState.network.availableVolume(node)+" inventory="+inventory
				+" supports="+count+" normalizedDry=["+supports+"] sourceShares="+shares);
	}

	/** Each allocated gas volume has one origin; inventory, energy and old control volume share it. */
	private GasTopologyPlan plan(GasRuntimeGeometry next,VolumeWork volumeWork,PhaseBatch edit,GasVolumeAllocation allocation) {
		var old=domain.gasState.frame;
		var origins=old==null?null:allocation.sources();

		boolean thermal=domain.thermalState!=null&&domain.thermalState.gasModelBound();
		boolean retainControl=thermal&&volumeWork!=VolumeWork.FIXED_ENERGY;
		var sourceWork=retainControl?new ArrayList<SourceVolumeWork>():null;
		int count=next.nodeCount();double[] available=volumes(next);
		var inventories=new ArrayList<GasInventory>(java.util.Collections.nCopies(count,GasInventory.EMPTY));
		SignedDyadic[] mixed=thermal?new SignedDyadic[count]:null,inherited=retainControl?new SignedDyadic[count]:null;
		if(thermal)Arrays.fill(mixed,SignedDyadic.ZERO);
		if(retainControl)Arrays.fill(inherited,SignedDyadic.ZERO);
		GasAuditLedger sourceDelta=GasAuditLedger.EMPTY,sinkDelta=GasAuditLedger.EMPTY;
		SignedDyadic sourceEnergy=SignedDyadic.ZERO,sinkEnergy=SignedDyadic.ZERO;
		if(old==null) {
			for(int node=1;node<=count;node++) {
				var stock=ambientInventory(available[node-1], sourceDelta.standardVolumeNanos());inventories.set(node-1,stock);sourceDelta=sourceDelta.add(stock);
				if(thermal){mixed[node-1]=SignedDyadic.of(domain.thermalState.ambientGasEnergy(stock));sourceEnergy=sourceEnergy.add(mixed[node-1]);}
				if(retainControl)inherited[node-1]=next.capacity().normalizedVolume(node);
			}
		} else {
			for(int previous=1;previous<=old.nodeCount();previous++) {
				int row=previous+1,first=origins.first(row);var stock=inventory(previous,edit);
				if(first<0) {
					if(!stock.isEmpty())throw missingGasSuccessor(previous,stock,edit,next,allocation);
					continue;
				}
				int destinations=0;for(int entry=first;entry>=0;entry=origins.next(entry))destinations++;
				boolean split=destinations>1;int[] targets=new int[destinations];double[] amounts=split?new double[destinations]:null;
				int part=0;
				for(int entry=first;entry>=0;entry=origins.next(entry)){
					targets[part]=(int)origins.key(entry);if(split)amounts[part]=origins.amount(entry);part++;
				}
				var weights=split?GasPartitionWeights.of(amounts):null;
				var parts=split?stock.partition(weights):List.of(stock);
				double[] energies=!thermal?null:stock.isEmpty()?new double[parts.size()]:split?partitionEnergy(previous,parts,edit):new double[]{energy(previous,edit)};
				SignedDyadic[] control=!retainControl?null:split?GasTopologyVolumePartition.partition(old.capacity().normalizedVolume(previous),weights):new SignedDyadic[]{old.capacity().normalizedVolume(previous)};
				for(part=0;part<targets.length;part++) {
					int node=targets[part];
					if(node==0){sinkDelta=sinkDelta.add(parts.get(part));if(thermal)sinkEnergy=sinkEnergy.add(SignedDyadic.of(energies[part]));}
					else {inventories.set(node-1,inventories.get(node-1).merge(parts.get(part)));
						if(thermal)mixed[node-1]=mixed[node-1].add(SignedDyadic.of(energies[part]));
						if(retainControl){
							inherited[node-1]=inherited[node-1].add(control[part]);
						}}
				}
				if(retainControl)sourceWork.add(new SourceVolumeWork(previous,old.capacity().normalizedVolume(previous),
						stock.isEmpty()?0:energy(previous,edit),parts,targets,control,energies,
                        volumeWork==VolumeWork.ADIABATIC||domain.pressureSystem.sourceGasDoesVolumeWork(old,previous)));
			}
			// Ambient is source row 1. Explicit vacuum (row 0) imports neither stock nor old control.
			for(int entry=origins.first(1);entry>=0;entry=origins.next(entry)) {
				int node=(int)origins.key(entry);if(node==0)continue;
				var normalized=allocation.ambientControl(node);
				double volume=normalized.multiply(SignedDyadic.of(domain.unitScale.cubicMetersPerNormalizedVolume())).toDouble();
				var stock=ambientInventory(volume, sourceDelta.standardVolumeNanos());inventories.set(node-1,inventories.get(node-1).merge(stock));sourceDelta=sourceDelta.add(stock);
				if(thermal){var u=SignedDyadic.of(domain.thermalState.ambientGasEnergy(stock));mixed[node-1]=mixed[node-1].add(u);sourceEnergy=sourceEnergy.add(u);}
				if(retainControl)inherited[node-1]=inherited[node-1].add(normalized);
			}
			if(next.sealedExterior()&&(!sourceDelta.isEmpty()||!sinkDelta.isEmpty()))
				throw new GasTopologyBlockedException("Closed gas topology attempted an external source or sink");
		}
		if(edit!=null)for(var birth:edit.births()) {
			// The liquid source releases this control volume. An adjacent old gas
			// contact may remain behind a resolved liquid seal after reconstruction.
			int node=next.topology().nodeOfComponent(birth.cell(),birth.supportMask());
			if(node<0)throw new GasTopologyBlockedException("Vaporized liquid has no gas capacity in its source support");
			if(node==0) {
				sinkDelta=sinkDelta.add(birth.inventory());
				sinkEnergy=sinkEnergy.add(SignedDyadic.of(birth.energy()));
			}else {
				inventories.set(node-1,inventories.get(node-1).merge(birth.inventory()));
				mixed[node-1]=mixed[node-1].add(SignedDyadic.of(birth.energy()));
			}
		}
		SignedDyadic[] workBySource=new SignedDyadic[old==null?1:old.nodeCount()+1];Arrays.fill(workBySource,SignedDyadic.ZERO);
		SignedDyadic mechanicalWork=retainControl?applySourceVolumeWork(sourceWork,next,inherited,mixed,workBySource):SignedDyadic.ZERO;
		double[] mixedEnergy=doubles(mixed);
		GasEnergyStore.GasRemapPlan thermalPlan=!thermal?null:edit==null
				?domain.thermalState.planGasRemapWithSourceWork(domain.gasState.network,inventories,available,mixedEnergy,sourceEnergy,sinkEnergy,mechanicalWork)
				:domain.thermalState.planGasRemapAfterBatch(domain.gasState.network,edit.gasThermal(),edit.cellThermal(),inventories,available,mixedEnergy,available,sourceEnergy,sinkEnergy);
		var edges=new ArrayList<AirEdge>(next.topology().connections().size());
		for(var edge:next.topology().connections())edges.add(new AirEdge(edge.firstNode(),edge.secondNode(),edge.area(),.65));
		var gasPlan=domain.gasState.network.planRemapNodes(available,inventories,
				edit==null?sourceDelta:sourceDelta.add(edit.source()),edit==null?sinkDelta:sinkDelta.add(edit.sink()),edges);
		return new GasTopologyPlan(next,old,gasPlan,thermalPlan,domain.gasState.network,domain.gasState.network.revision(),
				workBySource);
	}
	/** Receiver volume ratios describe actual deformation, including carried and newly opened vacuum.
	 * Each old pressure owner receives one volume change before conservative split/mixing of its U.
	 * At constant receiver control volume every ratio is one, so topology changes supply no work. */
	private SignedDyadic applySourceVolumeWork(List<SourceVolumeWork> sources,GasRuntimeGeometry next,
	                                        SignedDyadic[] inherited,SignedDyadic[] mixed,SignedDyadic[] workBySource){
		if(sources.isEmpty())return SignedDyadic.ZERO;
		var receiverChange=new SignedDyadic[next.nodeCount()];boolean deformed=false;
		for(int node=0;node<receiverChange.length;node++){
			var actual=next.capacity().normalizedVolume(node+1);var expansion=actual.subtract(inherited[node]);
			var opened=next.openedPoreVolume(node+1);
			if(expansion.signum()>0&&opened.signum()>0)
				inherited[node]=inherited[node].add(opened.compareTo(expansion)<0?opened:expansion);
			receiverChange[node]=inherited[node].signum()==0?SignedDyadic.ZERO:actual.subtract(inherited[node]);deformed|=receiverChange[node].signum()!=0;
		}
		if(!deformed)return SignedDyadic.ZERO;
		int capacity=0;for(var source:sources)capacity+=source.targets().length;
		int[] head=new int[receiverChange.length],links=new int[capacity],owners=new int[capacity];Arrays.fill(head,-1);
		var controls=new SignedDyadic[capacity];var known=new SignedDyadic[receiverChange.length];Arrays.fill(known,SignedDyadic.ZERO);
		int entries=0;
		for(int source=0;source<sources.size();source++){
			var row=sources.get(source);
			for(int part=0;part<row.targets().length;part++){
				int target=row.targets()[part]-1;if(target<0||receiverChange[target].signum()==0)continue;
				owners[entries]=source;controls[entries]=row.controls()[part];links[entries]=head[target];head[target]=entries++;
				known[target]=known[target].add(row.controls()[part]);
			}
		}
		var changes=new SignedDyadic[sources.size()];Arrays.fill(changes,SignedDyadic.ZERO);
		for(int target=0;target<receiverChange.length;target++)if(receiverChange[target].signum()!=0){
			var other=inherited[target].subtract(known[target]);int count=other.signum()>0?1:0;
			if(other.signum()<0)throw new IllegalStateException("Gas source controls exceed receiver inheritance: node="+(target+1));
			for(int entry=head[target];entry>=0;entry=links[entry])count++;
			var weights=new SignedDyadic[count];int at=0;
			for(int entry=head[target];entry>=0;entry=links[entry])weights[at++]=controls[entry];
			if(other.signum()>0)weights[at]=other;
			var parts=count==1?new SignedDyadic[]{receiverChange[target]}:GasTopologyVolumePartition.partitionChange(receiverChange[target],weights);
			at=0;for(int entry=head[target];entry>=0;entry=links[entry]){
				int source=owners[entry];changes[source]=changes[source].add(parts[at++]);
			}
		}
		var model=new GasThermalModel(domain.thermalState.registry(),domain.gasState.network.registry());
		var scale=SignedDyadic.of(domain.unitScale.cubicMetersPerNormalizedVolume());var work=SignedDyadic.ZERO;
		for(int index=0;index<sources.size();index++){
			var source=sources.get(index);
            // An unresolved source has no pressure-work mechanism in this mechanical step.
            // Its complete N/U split remains the conservative remap prepared above.
            if(!source.mechanicalMode()||changes[index].signum()==0)continue;
			var retained=new ArrayList<GasInventory>();var stock=GasInventory.EMPTY;
			var retainedVolume=SignedDyadic.ZERO;var retainedEnergy=SignedDyadic.ZERO;boolean vented=false;
			for(int part=0;part<source.targets().length;part++){
				if(source.targets()[part]==0)vented=true;
				else{retained.add(source.parts().get(part));stock=stock.merge(source.parts().get(part));
					retainedVolume=retainedVolume.add(source.controls()[part]);
					retainedEnergy=retainedEnergy.add(SignedDyadic.of(source.partEnergy()[part]));}
			}
			if(stock.isEmpty())continue;
			var initialVolume=vented?retainedVolume:source.volume();var finalVolume=initialVolume.add(changes[index]);
			double initialSI=initialVolume.multiply(scale).toDouble(),finalSI=finalVolume.multiply(scale).toDouble();
			if(initialSI==finalSI)continue;
			double initialEnergy=vented?retainedEnergy.toDouble():source.energy();
			double finalEnergy=model.compressAdiabatically(stock,initialEnergy,initialSI,finalSI).finalInternalEnergy();
			var sourceWork=SignedDyadic.of(finalEnergy).subtract(SignedDyadic.of(initialEnergy));
			workBySource[source.node()]=workBySource[source.node()].add(sourceWork);work=work.add(sourceWork);
			double[] partitioned=retained.size()==1?new double[]{finalEnergy}:model.partitionEnergy(stock,finalEnergy,retained);int partIndex=0;
			for(int part=0;part<source.targets().length;part++)if(source.targets()[part]>0){
				int target=source.targets()[part]-1;
				mixed[target]=mixed[target].subtract(SignedDyadic.of(source.partEnergy()[part])).add(SignedDyadic.of(partitioned[partIndex++]));
			}
		}
		return work;
	}

	private GasInventory ambientInventory(double volume, long plannedSourceNanos) {
		if (!(volume > 0 && Double.isFinite(volume))) throw new GasTopologyBlockedException("Ambient capture has no positive finite volume");
		long nanos = AirNetwork.nanos(volume);
		// Standard volume rounds to the nearest 10^-9 m³, including zero below half a quantum.
		if (nanos == 0) return GasInventory.EMPTY;
		var network = domain.gasState.network;
		return network.registry().createAmbientInterval(Math.addExact(network.externalSource().standardVolumeNanos(), plannedSourceNanos), nanos);
	}

	private static double[] doubles(SignedDyadic[] values) {
		if (values == null) return null;
		double[] result = new double[values.length];
		for (int i = 0; i < values.length; i++) {
			result[i] = values[i].toDouble();
			if (!Double.isFinite(result[i]) || values[i].signum() > 0 && !(result[i] > 0))
				throw new GasTopologyBlockedException("Gas remap energy or volume is not representable");
		}
		return result;
	}
	private static double[] volumes(GasRuntimeGeometry frame) {
		double[] result = new double[frame.nodeCount()];
		for (var chamber : frame.capacity().chambers()) result[chamber.node() - 1] = chamber.cubicMeters();
		return result;
	}

	/** Operator-clear preflight binds the complete source before condensed inventory changes. */
	void preflight(GasTopologyPlan plan) {
		requireBeforeCarrierCommit(plan);
		var fresh = GasRuntimeGeometry.allDry(domain, domain.gasState.sealedDomainBoundary);
		if (!Arrays.equals(fresh.pieceNodes(), plan.frame().pieceNodes()) || !Arrays.equals(volumes(fresh), volumes(plan.frame())))
			throw new IllegalStateException("Gas clear successor changed after planning");
	}
	void requireBeforeCarrierCommit(GasTopologyPlan plan) {
		requireOwnersCurrent(plan);domain.domainFlowFields.ensureInventoryGrid();
		if (!plan.frame().matchesSourceCarrierGeometry(domain, currentOccupied()))
			throw new IllegalStateException("Gas source geometry changed after carrier edit planning");
	}
	void commit(GasTopologyPlan plan) {
		domain.domainFlowFields.ensureInventoryGrid();commitPrepared(plan,currentOccupied());
	}
	/** Joint publishers already hold the committed solid endpoint; derived cell grids remain lazy. */
	void commitPrepared(GasTopologyPlan plan,long[] occupied) {
		requireOwnersCurrent(plan);
		if (!plan.frame().matchesCarrierInventory(domain, occupied))
			throw new IllegalStateException("Gas successor geometry differs from published carrier stock");
		PhaseOccupancyTransport.publish(domain,plan.frame().occupancy());
		if (plan.thermalPlan() != null) domain.thermalState.commitGasRemap(plan.thermalPlan());
		if(plan.gasPlan()!=null) {
			domain.gasState.network.commitRemapNodes(plan.gasPlan()); domain.gasState.frame = plan.frame();
			domain.gasState.pressureActive = plan.frame().nodeCount() > 0;
		}
	}
	private void requireOwnersCurrent(GasTopologyPlan plan) {
		if (plan == null) throw new IllegalArgumentException("Gas topology plan is missing");
		if (plan.source() != domain.gasState.frame || plan.frame().sealedExterior() != domain.gasState.sealedDomainBoundary)
			throw new IllegalStateException("Gas topology owner changed after planning");
		if(plan.network()!=domain.gasState.network||plan.networkRevision()!=domain.gasState.network.revision())
			throw new IllegalStateException("Gas source inventory changed after planning");
		if(plan.gasPlan()!=null)domain.gasState.network.requireCanCommitRemap(plan.gasPlan());
		if (plan.thermalPlan() != null) {
			if (domain.thermalState == null) throw new IllegalStateException("Gas topology plan lost its thermal owner");
			domain.thermalState.requireCanCommitGasRemap(plan.thermalPlan());
		}
	}
	void step(float dt) {
		refresh(); if (!domain.gasState.pressureActive) return;
		if (domain.thermalState == null || !domain.thermalState.gasModelBound()) domain.gasState.network.stepEdges(dt);
		else domain.gasState.network.stepEdges(dt, domain.thermalState.gasThermodynamics(domain.gasState.network),
				batch -> domain.thermalState.planGasFlow(batch, domain.gasState.network));
	}
	void synchronizeForPersistence() { refresh(); }
	record GasTopologyPlan(GasRuntimeGeometry frame, GasRuntimeGeometry source,
	                       AirNetwork.RemapPlan gasPlan, GasEnergyStore.GasRemapPlan thermalPlan,
	                       AirNetwork network,long networkRevision,SignedDyadic[] workBySource) { }
}

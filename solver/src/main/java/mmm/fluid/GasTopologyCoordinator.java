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
import java.util.TreeMap;

/** Joint inventory and energy remapping on captured positive PLIC microbox supports. */
final class GasTopologyCoordinator {
	private final FluidDomain domain;
	private GasRuntimeGeometry committedFrame;
	private AirNetwork committedNetwork;
	private long committedNetworkRevision;
	private enum VolumeWork { ADIABATIC, FIXED_ENERGY }
	private record SourceVolumeWork(int node,double volume,double energy,List<GasInventory> parts,
	                                int[] targets,double[] controls,double[] partEnergy) { }
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
		rememberCommittedTopology();
	}

	/** A private candidate continues immutable geometry and an independently owned network. */
	void restoreRuntime(GasRuntimeGeometry frame, AirNetwork.RuntimeSnapshot network) {
		if (domain.gasState.frame != null || domain.gasState.network.nodeCount() != 0)
			throw new IllegalStateException("Runtime gas continuation requires an empty target");
		domain.gasState.network = AirNetwork.fromRuntimeSnapshot(network); domain.gasState.frame = frame;
		domain.gasState.pressureActive = frame.nodeCount() > 0; rememberCommittedTopology();
	}

	void refresh() {
		domain.domainFlowFields.ensureInventoryGrid();
		var frame = domain.gasState.frame;
		var occupied = currentOccupied();
		if (frame != null && frame == committedFrame && domain.gasState.network == committedNetwork
				&& committedNetworkRevision == committedNetwork.revision()
				&& frame.sealedExterior() == domain.gasState.sealedDomainBoundary
				&& frame.matchesCarrierGeometry(domain, occupied)) return;
		if(frame==null)commit(plan(GasRuntimeGeometry.capture(domain,occupied,domain.gasState.sealedDomainBoundary),VolumeWork.ADIABATIC,null,null));
		else commit(planCells(domain.liquidCells,occupied,null,VolumeWork.ADIABATIC,null,null));
	}
	private void rememberCommittedTopology() {
		committedFrame = domain.gasState.frame;
		committedNetwork = domain.gasState.network;
		committedNetworkRevision = committedNetwork.revision();
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
	GasTopologyPlan planAfterLiquidCells(LiquidCellInventory future,long[] occupied,GasVolumeAllocation allocation) {
		return planCells(future,occupied,null,VolumeWork.ADIABATIC,null,allocation);
	}
	/** Actual EOS work is shared by the future material in the same pressure-coupled body. */
	LiquidCellInventory.Plan balanceMechanicalWork(LiquidCellInventory.Plan inventory,GasTopologyPlan gas){
		if(gas==null||gas.thermalPlan()==null)return inventory;
		boolean hasWork=false;for(var work:gas.workBySource())hasWork|=work.signum()!=0;
		if(!hasWork)return inventory;
		int gasBase=8*domain.cellCount,particleBase=gasBase+gas.source().nodeCount()+1;
		int parcels=domain.granularState==null?0:domain.granularState.parcels.count();
		int[] body=mechanicalBodies(gas,inventory,particleBase,parcels);
		var exchanges=new TreeMap<Integer,SignedDyadic>();
		for(int node=1;node<gas.workBySource().length;node++)if(gas.workBySource()[node].signum()!=0)
			exchanges.merge(mechanicalRoot(body,gasBase+node),gas.workBySource()[node].negate(),SignedDyadic::add);
		var liquids=new TreeMap<Integer,ArrayList<LiquidCellInventory.Stock>>();
		for(var stock:inventory.future().physicalStocks()){
			int root=mechanicalRoot(body,supportVertex(stock.key()));
			if(exchanges.containsKey(root))liquids.computeIfAbsent(root,ignored->new ArrayList<>()).add(stock);
		}
		var grains=new TreeMap<Integer,ArrayList<Integer>>();
		for(int p=0;p<parcels;p++){
			int root=mechanicalRoot(body,particleBase+p);
			if(exchanges.containsKey(root))grains.computeIfAbsent(root,ignored->new ArrayList<>()).add(p);
		}
		var heat=new TreeMap<LiquidCellInventory.Key,SignedDyadic>();gas.granularWork().clear();
		for(var entry:exchanges.entrySet()){
			var exchange=entry.getValue();if(exchange.signum()==0)continue;
			var targets=liquids.get(entry.getKey());var remaining=exchange;double numericalExchange=exchange.toDouble();
			if(targets!=null&&!targets.isEmpty()){
				double mass=0;LiquidCellInventory.Stock largest=null;
				for(var stock:targets){mass+=stock.massUnits();if(largest==null||stock.massUnits()>largest.massUnits())largest=stock;}
				for(var stock:targets)if(stock!=largest){
					var part=SignedDyadic.of(numericalExchange*(stock.massUnits()/mass));
					heat.merge(stock.key(),part,SignedDyadic::add);remaining=remaining.subtract(part);
				}
				heat.merge(largest.key(),remaining,SignedDyadic::add);continue;
			}
			var active=grains.get(entry.getKey());
			if(active==null||active.isEmpty())throw new IllegalStateException("Gas source work has no connected mechanical carrier: body="+entry.getKey());
			double mass=0;int largest=-1;
			for(int p:active){var parcel=domain.granularState.parcels.parcel(p);mass+=parcel.massUnits();
				if(largest<0||parcel.massUnits()>domain.granularState.parcels.parcel(largest).massUnits())largest=p;}
			for(int p:active)if(p!=largest){
				var part=SignedDyadic.of(numericalExchange*(domain.granularState.parcels.parcel(p).massUnits()/mass));
				gas.granularWork().merge(p,part,SignedDyadic::add);remaining=remaining.subtract(part);
			}
			gas.granularWork().merge(largest,remaining,SignedDyadic::add);
		}
		return heat.isEmpty()?inventory:inventory.withMechanicalHeatByStock(heat);
	}
	/** Reuses the solved wet microfaces, fixed supports and accepted routes; no new geometric intersections. */
	private int[] mechanicalBodies(GasTopologyPlan gas,LiquidCellInventory.Plan inventory,int particleBase,int parcels){
		int gasBase=8*domain.cellCount;int[] body=new int[particleBase+parcels];
		for(int i=0;i<body.length;i++)body[i]=i;
		for(var component:gas.source().capacity().components()){
			int node=gas.source().topology().nodeOfComponent(component.cell(),component.supportMask());
			if(node>0){
				int micro=Integer.numberOfTrailingZeros(component.supportMask());
				int support=CellRemapMicroTopology.support(gas.source().geometry().collisionMask(component.cell()),micro);
				joinMechanical(body,8*component.cell()+Integer.numberOfTrailingZeros(support),gasBase+node);
			}
		}
		var faces=domain.pressureSystem.faces;var cut=domain.geometry.cutCells;
		for(int face=0;face<faces.faceCount;face++){
			if(!(faces.inverseMass[face]>0))continue;
			int a=faces.firstCell[face],b=faces.secondCell[face],axis=faces.axis[face];if(a<0)continue;
			int maskA=cut.mask(a),maskB=cut.mask(b);
			for(int micro=0;micro<8;micro++)if((micro>>>axis&1)!=0){
				int peer=micro^(1<<axis),port=(micro>>>((axis+1)%3)&1)+2*(micro>>>((axis+2)%3)&1);
				if((maskA&1<<micro)!=0||(maskB&1<<peer)!=0||!(faces.pressurePortArea(face,port)>0))continue;
				joinMechanical(body,8*a+Integer.numberOfTrailingZeros(CellRemapMicroTopology.support(maskA,micro)),
						8*b+Integer.numberOfTrailingZeros(CellRemapMicroTopology.support(maskB,peer)));
			}
		}
		for(var transfer:inventory.transfers())if(transfer.massUnits()>0)
			joinMechanical(body,supportVertex(transfer.source()),supportVertex(transfer.target()));
		var solids=domain.pressureSystem.solidFaces;
		for(int p=0;p<parcels;p++)for(int axis=0;axis<3;axis++)for(int slot=0;slot<solids.parcelFaceCount(p,axis);slot++){
			int face=solids.parcelFace(p,axis,slot);if(!(solids.solidArea(face)*solids.parcelPressureShare(p,axis,slot)>0))continue;
			int a=faces.firstCell[face],b=faces.secondCell[face];if(a<0)continue;
			int maskA=cut.mask(a),maskB=cut.mask(b);
			for(int micro=0;micro<8;micro++)if((micro>>>axis&1)!=0){
				int peer=micro^(1<<axis);if((maskA&1<<micro)!=0||(maskB&1<<peer)!=0)continue;
				int sa=CellRemapMicroTopology.support(maskA,micro),sb=CellRemapMicroTopology.support(maskB,peer);
				joinMechanical(body,particleBase+p,8*a+Integer.numberOfTrailingZeros(sa));
				joinMechanical(body,particleBase+p,8*b+Integer.numberOfTrailingZeros(sb));
			}
		}
		return body;
	}
	private static int supportVertex(LiquidCellInventory.Key key){return 8*key.cell()+Integer.numberOfTrailingZeros(key.supportMask());}
	private static int mechanicalRoot(int[] body,int node){
		while(body[node]!=node){body[node]=body[body[node]];node=body[node];}return node;
	}
	private static void joinMechanical(int[] body,int first,int second){
		int a=mechanicalRoot(body,first),b=mechanicalRoot(body,second);if(a!=b)body[Math.max(a,b)]=Math.min(a,b);
	}
	mmm.thermal.ThermalAuditLedger mechanicalWorkAudit(mmm.thermal.ThermalAuditLedger audit,GasTopologyPlan gas){
		if(audit==null||gas==null)return audit;
		for(var entry:gas.granularWork().entrySet()){
			var before=SignedDyadic.of(domain.thermalState.granularColumn().activeEnergy(entry.getKey()));
			var exact=before.add(entry.getValue());var stored=SignedDyadic.of(exact.toDouble());
			audit=audit.withMechanicalWork(entry.getValue()).withRoundoff(stored.subtract(exact));
		}
		return audit;
	}
	void commitMechanicalWork(GasTopologyPlan gas){
		if(gas==null||gas.granularWork().isEmpty())return;
		requireMechanicalWorkCurrent(gas);
		for(var entry:gas.granularWork().entrySet())domain.thermalState.addGranularMechanicalHeat(entry.getKey(),entry.getValue());
	}
	private void requireMechanicalWorkCurrent(GasTopologyPlan gas){
		if(!gas.granularWork().isEmpty()&&domain.thermalState.thermalRevision().granular()!=gas.granularRevision())
			throw new IllegalStateException("Granular energy changed after gas mechanical work preparation");
	}
	GasTopologyPlan planAfterCollisionCells(LiquidCellInventory future,long[] occupied,byte[] masks) {
		return planCells(future,occupied,masks,VolumeWork.ADIABATIC,null,null);
	}
	private GasTopologyPlan planCells(LiquidCellInventory future,long[] occupied,byte[] masks,
	                                VolumeWork volumeWork,PhaseBatch phase,GasVolumeAllocation allocation) {
		if(allocation==null)allocation=GasVolumeAllocation.prepare(domain,future,occupied,masks,phase==null?null:phase.inventories());
		allocation.requireCurrent(domain,future);
		var next=GasRuntimeGeometry.captureCellFuture(domain,future,occupied,domain.gasState.sealedDomainBoundary,masks,allocation.futureOccupancy(),allocation.preparedPhase(),allocation.preparedGeometry(),allocation.preparedCapacity());
		if(domain.gasState.frame==null)return new GasTopologyPlan(next,null,null,null,domain.gasState.network,domain.gasState.network.revision(),new SignedDyadic[0],new TreeMap<>(),-1);
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

	/** Each allocated gas volume has one origin; inventory, energy and old control volume share it. */
	private GasTopologyPlan plan(GasRuntimeGeometry next,VolumeWork volumeWork,PhaseBatch edit,GasVolumeAllocation allocation) {
		var old=domain.gasState.frame;
		TreeMap<Integer,TreeMap<Integer,Double>> origins=old==null||old.nodeCount()==0&&next.nodeCount()==0?new TreeMap<>():origins(next,allocation);

		boolean thermal=domain.thermalState!=null&&domain.thermalState.gasModelBound();
		boolean retainControl=thermal&&volumeWork==VolumeWork.ADIABATIC;
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
				var stock=ambientInventory(available[node-1]);inventories.set(node-1,stock);sourceDelta=sourceDelta.add(stock);
				if(thermal){mixed[node-1]=SignedDyadic.of(domain.thermalState.ambientGasEnergy(stock));sourceEnergy=sourceEnergy.add(mixed[node-1]);}
				if(retainControl)inherited[node-1]=SignedDyadic.of(available[node-1]);
			}
		} else {
			for(int previous=1;previous<=old.nodeCount();previous++) {
				var destinations=origins.get(previous);var stock=inventory(previous,edit);
				if(destinations==null||destinations.isEmpty()) {
					if(!stock.isEmpty())throw new GasTopologyBlockedException("Allocated finite gas has no successor: node="+previous);
					continue;
				}
				boolean split=destinations.size()>1;
				var weights=split?GasPartitionWeights.of(destinations.values().stream().mapToDouble(Double::doubleValue).toArray()):null;
				var parts=split?stock.partition(weights):List.of(stock);
				double[] energies=!thermal?null:stock.isEmpty()?new double[parts.size()]:split?partitionEnergy(previous,parts,edit):new double[]{energy(previous,edit)};
				double[] control=!retainControl?null:split?GasTopologyVolumePartition.partition(domain.gasState.network.availableVolume(previous),weights):new double[]{domain.gasState.network.availableVolume(previous)};
				int part=0;
				for(var entry:destinations.entrySet()) {
					int node=entry.getKey();
					if(node==0){sinkDelta=sinkDelta.add(parts.get(part));if(thermal)sinkEnergy=sinkEnergy.add(SignedDyadic.of(energies[part]));}
					else {inventories.set(node-1,inventories.get(node-1).merge(parts.get(part)));
						if(thermal)mixed[node-1]=mixed[node-1].add(SignedDyadic.of(energies[part]));
						if(retainControl){
							var volume=SignedDyadic.of(control[part]);inherited[node-1]=inherited[node-1].add(volume);
						}}
					part++;
				}
				if(retainControl&&!stock.isEmpty())sourceWork.add(new SourceVolumeWork(previous,domain.gasState.network.availableVolume(previous),
						energy(previous,edit),parts,destinations.keySet().stream().mapToInt(Integer::intValue).toArray(),control,energies));
			}
			for(var origin:origins.entrySet())if(origin.getKey()<=0)for(var entry:origin.getValue().entrySet()) {
				int node=entry.getKey();if(node==0)continue;
				double volume=entry.getValue()*domain.unitScale.cubicMetersPerNormalizedVolume();
				if(origin.getKey()==0) {
					var stock=ambientInventory(volume);inventories.set(node-1,inventories.get(node-1).merge(stock));sourceDelta=sourceDelta.add(stock);
					if(thermal){var u=SignedDyadic.of(domain.thermalState.ambientGasEnergy(stock));mixed[node-1]=mixed[node-1].add(u);sourceEnergy=sourceEnergy.add(u);}
				}
				// Ambient stock imports its own control volume. A new dry support tagged
				// vacuum has no additional old volume: retained cavities already supplied it.
				if(retainControl&&origin.getKey()==0){
					inherited[node-1]=inherited[node-1].add(SignedDyadic.of(volume));
				}
			}
			if(retainControl&&count>0)includeOpenedPoreVolume(old,next,available,inherited);
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
		SignedDyadic mechanicalWork=retainControl?applySourceVolumeWork(sourceWork,available,inherited,mixed,workBySource):SignedDyadic.ZERO;
		double[] mixedEnergy=doubles(mixed);
		GasEnergyStore.GasRemapPlan thermalPlan=!thermal?null:edit==null
				?domain.thermalState.planGasRemapWithSourceWork(domain.gasState.network,inventories,available,mixedEnergy,sourceEnergy,sinkEnergy,mechanicalWork)
				:domain.thermalState.planGasRemapAfterBatch(domain.gasState.network,edit.gasThermal(),edit.cellThermal(),inventories,available,mixedEnergy,available,sourceEnergy,sinkEnergy);
		var edges=new ArrayList<AirEdge>(next.topology().connections().size());
		for(var edge:next.topology().connections())edges.add(new AirEdge(edge.firstNode(),edge.secondNode(),edge.area(),.65));
		var gasPlan=domain.gasState.network.planRemapNodes(available,inventories,
				edit==null?sourceDelta:sourceDelta.add(edit.source()),edit==null?sinkDelta:sinkDelta.add(edit.sink()),edges);
		return new GasTopologyPlan(next,old,gasPlan,thermalPlan,domain.gasState.network,domain.gasState.network.revision(),
				workBySource,new TreeMap<>(),thermal?domain.thermalState.thermalRevision().granular():-1);
	}
	/** Receiver volume ratios describe actual deformation, including carried and newly opened vacuum.
	 * Each old pressure owner receives one volume change before conservative split/mixing of its U.
	 * At constant receiver control volume every ratio is one, so topology changes supply no work. */
	private SignedDyadic applySourceVolumeWork(List<SourceVolumeWork> sources,double[] available,
	                                        SignedDyadic[] inherited,SignedDyadic[] mixed,SignedDyadic[] workBySource){
		if(sources.isEmpty())return SignedDyadic.ZERO;
		double[] ratio=new double[available.length];boolean deformed=false;
		for(int node=0;node<ratio.length;node++){
			double control=inherited[node].toDouble();ratio[node]=control>0?available[node]/control:1;
			deformed|=ratio[node]!=1;
		}
		if(!deformed)return SignedDyadic.ZERO;
		var model=new GasThermalModel(domain.thermalState.registry(),domain.gasState.network.registry());
		var work=SignedDyadic.ZERO;
		for(var source:sources){
			var retained=new ArrayList<GasInventory>();var stock=GasInventory.EMPTY;
			double retainedVolume=0,change=0;var retainedEnergy=SignedDyadic.ZERO;boolean vented=false;
			for(int part=0;part<source.targets().length;part++){
				int target=source.targets()[part];
				if(target==0)vented=true;
				else{retained.add(source.parts().get(part));stock=stock.merge(source.parts().get(part));
					retainedVolume+=source.controls()[part];retainedEnergy=retainedEnergy.add(SignedDyadic.of(source.partEnergy()[part]));
					change+=source.controls()[part]*(ratio[target-1]-1);}
			}
			if(stock.isEmpty()||change==0)continue;
			double initialVolume=vented?retainedVolume:source.volume(),initialEnergy=vented?retainedEnergy.toDouble():source.energy();
			double finalEnergy=model.compressAdiabatically(stock,initialEnergy,
					initialVolume,initialVolume+change).finalInternalEnergy();
			var sourceWork=SignedDyadic.of(finalEnergy).subtract(SignedDyadic.of(initialEnergy));
			workBySource[source.node()]=workBySource[source.node()].add(sourceWork);work=work.add(sourceWork);
			double[] partitioned=model.partitionEnergy(stock,finalEnergy,retained);int next=0;
			for(int part=0;part<source.targets().length;part++)if(source.targets()[part]>0){
				int target=source.targets()[part]-1;
				mixed[target]=mixed[target].subtract(SignedDyadic.of(source.partEnergy()[part])).add(SignedDyadic.of(partitioned[next++]));
			}
		}
		return work;
	}
	/** Newly exposed vacuum belongs to its actual receiving chamber, up to its net expansion. */
	private void includeOpenedPoreVolume(GasRuntimeGeometry old,GasRuntimeGeometry next,
	                                    double[] available,SignedDyadic[] inherited){
		var previous=old.geometry().actualCapacity();
		double[] opened=new double[next.nodeCount()];
		for(var component:next.capacity().components()){
			if(component.availableVolumeNormalized().signum()==0)continue;
			int cell=component.cell();
			// Ordinary liquid motion keeps fixed pores unchanged; source transport already
			// carries its whole old control volume, so no opening calculation is needed.
			if(old.geometry().collisionMask(cell)==next.geometry().collisionMask(cell)
					&&old.phase().poreVolume(cell).equals(next.phase().poreVolume(cell)))continue;
			int node=next.topology().nodeOfComponent(cell,component.supportMask());
			if(node<=0)continue;
			double oldPore=0;
			for(int i=previous.offsets[cell];i<previous.offsets[cell+1];i++){
				var support=previous.components.get(i);
				int overlap=support.supportMask()&component.supportMask();
				oldPore+=support.poreVolumeNormalized().toDouble()*Integer.bitCount(overlap)/Integer.bitCount(support.supportMask());
			}
			double gain=component.poreVolumeNormalized().toDouble()-oldPore;
			if(!(gain>0))continue;
			double volume=0;
			int newOpen=old.geometry().collisionMask(cell)&~next.geometry().collisionMask(cell)&component.supportMask();
			if(newOpen!=0){
				var dry=next.geometry().component(cell,component.supportMask());
				int selected=newOpen&dry.dryMicroMask();if(selected==0)continue;
				volume=component.availableVolumeNormalized().toDouble();
				if(selected!=dry.dryMicroMask()){
					double fraction=0;
					for(int bits=selected;bits!=0;bits&=bits-1)
						fraction+=next.geometry().pieceNormalizedVolume(8*cell+Integer.numberOfTrailingZeros(bits));
					volume*=fraction/dry.normalizedVolume();
				}
			}else{
				// Removing solid occupation releases its share of this fixed support.
				volume=Math.min(component.availableVolumeNormalized().toDouble(),gain);
			}
			opened[node-1]+=volume;
		}
		double scaleToMeters=domain.unitScale.cubicMetersPerNormalizedVolume();
		for(int node=0;node<opened.length;node++){
			// Inherited control already includes all old gas, old vacuum and ambient imports.
			// A solid translation within this chamber therefore adds no duplicate volume.
			double expansion=Math.max(0,available[node]-inherited[node].toDouble());
			double vacuum=Math.min(opened[node]*scaleToMeters,expansion);
			if(vacuum>0)inherited[node]=inherited[node].add(SignedDyadic.of(vacuum));
		}
	}

	private TreeMap<Integer,TreeMap<Integer,Double>> origins(GasRuntimeGeometry next,GasVolumeAllocation allocation) {
		if(allocation==null||allocation.sourceGeometry()!=domain.gasState.frame||allocation.networkRevision()!=domain.gasState.network.revision())
			throw new IllegalStateException("Gas phase transport source changed before remapping");
		var result=new TreeMap<Integer,TreeMap<Integer,Double>>();
		boolean[] owned=new boolean[next.nodeCount()+1];
		for(var share:allocation.shares()) {
			// Source shares belong to a fixed pore component; its PLIC may move the dry micro view.
			int cell=share.targetSupportAnchor()/8,support=CellRemapMicroTopology.support(next.geometry().collisionMask(cell),share.targetSupportAnchor()&7);
			int target=support==0?-1:next.topology().nodeOfComponent(cell,support);
			if(target<0)throw new IllegalStateException("Positive gas phase transfer has no future component capacity");
			owned[target]=true;
			int source=switch(share.kind()){case EXISTING->share.sourceNode();case AMBIENT->0;case EXPLICIT_VACUUM->-1;};
			result.computeIfAbsent(source,ignored->new TreeMap<>()).merge(target,share.volume(),Double::sum);
		}
		for(int node=1;node<owned.length;node++)if(!owned[node])
			throw new IllegalStateException("Future gas occupation has no transported or explicit source: node="+node);
		return result;
	}

	private GasInventory ambientInventory(double volume) {
		if (!(volume > 0 && Double.isFinite(volume))) throw new GasTopologyBlockedException("Ambient capture has no positive finite volume");
		long nanos = AirNetwork.nanos(volume);
		// Standard volume rounds to the nearest 10^-9 m³, including zero below half a quantum.
		if (nanos == 0) return GasInventory.EMPTY;
		return domain.gasState.network.ambientInventory(nanos);
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
		requireOwnersCurrent(plan);requireMechanicalWorkCurrent(plan);domain.domainFlowFields.ensureInventoryGrid();
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
		rememberCommittedTopology();
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
		// Orifice transfers change stock and U on the same nodes, capacities and faces.
		rememberCommittedTopology();
	}
	void synchronizeForPersistence() { refresh(); }
	record GasTopologyPlan(GasRuntimeGeometry frame, GasRuntimeGeometry source,
	                       AirNetwork.RemapPlan gasPlan, GasEnergyStore.GasRemapPlan thermalPlan,
	                       AirNetwork network,long networkRevision,SignedDyadic[] workBySource,
	                       TreeMap<Integer,SignedDyadic> granularWork,long granularRevision) { }
}

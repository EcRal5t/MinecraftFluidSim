package mmm.fluid;



import mmm.fluid.air.AirNetwork;
import mmm.fluid.air.GasInventory;
import mmm.fluid.air.GasRegistry;
import mmm.fluid.air.GasStateSnapshot;
import mmm.granular.GranularCouplingFields;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Captures explicit chamber fixtures and imports their inventory into the local gas state. */
final class GasInitialization {
	private final HostFluidState domain;
	private int[] restoredGasNodes;
	GasInitialization(HostFluidState domain) { this.domain = domain; }

	GasStateSnapshot bootstrap(String fingerprint) { refresh(); var snapshot=export(fingerprint); domain.domainCapabilities.initializeSpatialGas(); return snapshot; }
	GasStateSnapshot export(String fingerprint) {
		if (domain.gasState.frame == null) throw new IllegalStateException("Gas topology has not been explicitly initialized");
		if (!domain.gasState.network.auditClosed()) throw new IllegalStateException("Gas component audit does not close");
		return GasStateSnapshot.capture(domain.sizeX, domain.sizeY, domain.sizeZ,
				domain.gasState.frame.pieceNodes(), domain.gasState.network, fingerprint == null ? "" : fingerprint);
	}
	void restore(GasStateSnapshot state, GasRegistry registry) { restoreOwners(state, registry, List.of()); domain.domainCapabilities.initializeSpatialGas(); }

	/** Reconstructs capacities before publishing restored gas stock or thermal node columns. */
	void restore(GasStateSnapshot state, GasRegistry registry, FlipThermalParticleState thermal) {
		if (thermal != null) thermal.requireCompatible(domain.sizeX, domain.sizeY, domain.sizeZ, domain.config.cellSize(), domain.liquidCells.physicalSize());
		restoreOwners(state, registry, thermal == null ? List.of() : thermal.coherentGroups());
		if(thermal==null)domain.domainCapabilities.initializeSpatialGas();
	}
	void restore(GasStateSnapshot state, GasRegistry registry, mmm.thermal.ThermalOwnerSnapshot thermal) {
		restoreOwners(state, registry, thermal == null ? List.of() : thermal.coherentGroups());
		if(thermal==null)domain.domainCapabilities.initializeSpatialGas();
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
			occupied = domain.entityBodyCoupling.addTo(occupancy.copySolidSvq());
		}
		domain.ensurePhaseOccupancy();PhaseOccupancyTransport.requireGeometry(domain,domain.phaseOccupancy,occupied);
		int[] savedCells=state.cellNodeOrdinal();
		var frame=GasRuntimeGeometry.captureRestored(domain,occupied,domain.gasState.sealedDomainBoundary,savedCells,state.nodes().size());
		int[] pieces=frame.pieceNodes(), mapping=new int[state.nodes().size()];Arrays.fill(mapping,-1);
		for(int cell=0;cell<savedCells.length;cell++)if(savedCells[cell]>0){
			int target=-1;for(int slot=0;slot<8;slot++)if(pieces[8*cell+slot]>=0){target=pieces[8*cell+slot];break;}
			if(target<0)continue;
			int source=savedCells[cell]-1;
			if(target==0||mapping[source]>=0&&mapping[source]!=target)
				throw new IllegalStateException("Restored gas owner has inconsistent geometry: node="+(source+1)+" cell="+cell);
			mapping[source]=target;
		}
		var inventories=new ArrayList<GasInventory>(java.util.Collections.nCopies(frame.nodeCount(),GasInventory.EMPTY));
		for(int source=0;source<mapping.length;source++){
			var stock=state.nodes().get(source).inventory();int target=mapping[source];
			if(target<0){if(!stock.isEmpty())throw new GasTopologyBlockedException("Restored gas owner has no positive geometry: node="+(source+1));continue;}
			inventories.set(target-1,stock);
		}
		AirNetwork restored = new AirNetwork(registry);
		restored.restoreNodes(volumes(frame), inventories, state.externalSource(), state.escapedSink(), state.phaseSource(), state.phaseSink());
		domain.gasState.network = restored; domain.gasState.frame = frame;restoredGasNodes=mapping;
		domain.gasState.pressureActive = frame.nodeCount() > 0;
	}

	private double[] restoreGasEnergies(double[] source) {
		if(restoredGasNodes==null)return source;
		if(source.length!=restoredGasNodes.length)throw new IllegalArgumentException("Restored gas thermal ownership differs from gas inventory");
		double[] result=new double[domain.gasState.network.nodeCount()];
		for(int i=0;i<source.length;i++){
			int target=restoredGasNodes[i];
			if(target<0){if(source[i]!=0)throw new IllegalArgumentException("Gas without geometry carries thermal energy");}
			else result[target-1]=source[i];
		}
		return result;
	}
	mmm.thermal.ThermalOwnerSnapshot remapRestoredThermal(mmm.thermal.ThermalOwnerSnapshot state) {
		if(restoredGasNodes==null)return state;
		return new mmm.thermal.ThermalOwnerSnapshot(state.registryFingerprint(),state.insertionTemperatureK(),
				state.copyActiveParcelEnergy(),state.copyDepositEnergy(),restoreGasEnergies(state.copyGasNodeEnergy()),state.coherentGroups(),state.audit());
	}
	FlipThermalParticleState remapRestoredThermal(FlipThermalParticleState state) {
		if(restoredGasNodes==null)return state;
		return new FlipThermalParticleState(domain.sizeX,domain.sizeY,domain.sizeZ,domain.config.cellSize(),state.particleCount(),
				state.thermalRegistryFingerprint(),state.insertionTemperatureK(),state.copyInternalEnergy(),state.copyActiveParcelEnergy(),
				state.copyDepositEnergy(),state.coherentGroups(),restoreGasEnergies(state.copyGasNodeEnergy()),state.audit());
	}
	void completeThermalRestore(){restoredGasNodes=null;if(domain.gasState.frame!=null)domain.domainCapabilities.initializeSpatialGas();}

	void refresh() {
		if(domain.gasState.spatial!=null||domain.gasState.frame!=null)return;
		domain.domainFlowFields.ensureInventoryGrid();
		var frame=GasRuntimeGeometry.capture(domain,domain.occupiedSolidSvq(),domain.gasState.sealedDomainBoundary);
		var network=domain.gasState.network;
		for(double volume:volumes(frame))network.addNode(volume,volume);
		domain.gasState.frame=frame;domain.gasState.pressureActive=frame.nodeCount()>0;
	}
	private long[] currentOccupied(){return domain.occupiedSolidSvq();}
	private static double[] volumes(GasRuntimeGeometry frame) {
		double[] result=new double[frame.nodeCount()];
		for(var chamber:frame.capacity().chambers())result[chamber.node()-1]=chamber.cubicMeters();
		return result;
	}
	void synchronizeForPersistence(){domain.domainCapabilities.initializeSpatialGas();}
}

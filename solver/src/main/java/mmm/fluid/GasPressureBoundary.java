package mmm.fluid;

import mmm.fluid.air.GasAuditLedger;
import mmm.fluid.air.GasInventory;

import java.util.List;

/** Read-only local gas pressure, inventory and audit view, including explicit initial states. */
public final class GasPressureBoundary {
	private final HostFluidState domain;

	GasPressureBoundary(HostFluidState domain) {
		this.domain = domain;
	}

	public boolean pressureActive() { return domain.gasState.pressureActive; }
	public boolean topologyInitialized() { return domain.gasState.spatial!=null; }

	/** Local gas cell identity for observation; a cell without gas volume returns -1. */
	public int cellNode(int cell) { return domain.gasState.spatial!=null?(DomainGasInventory.mixture(domain,cell,domain.gasState.spatial.cells().get(cell),domain.gasState.spatial.energyAt(cell)).gasVolumeM3()>0?cell+1:-1):-1; }

	public int rawCellNode(int cell) { return cellNode(cell); }
	/** Gas owner at canonical 8*cell+micro: -1 absent, 0 ambient, positive finite node. */
	public int nodeOfPiece(int piece) { int cell=piece/8,bit=piece%8; if(piece<0||cell>=domain.cellCount||(domain.geometry.cutCells.mask(cell)&(1<<bit))!=0)return -1;return cellNode(cell); }

	public int previousSealedNodeCount() { return nodeCount(); }
	public int nodeCount() { return domain.gasState.spatial!=null?nodeIds().size():0; }
	public List<Integer> nodeIds() {
		if(domain.gasState.spatial==null)return List.of();
		var gas=domain.gasState.spatial;long[] occupied=domain.occupiedSolidSvq();var result=new java.util.ArrayList<Integer>();
		for(int cell=0;cell<gas.cellCount();cell++)if(DomainGasInventory.mixture(domain,cell,gas.cells().get(cell),gas.energyAt(cell),domain.liquidCells,occupied,null).gasVolumeM3()>0)result.add(cell+1);
		return List.copyOf(result);
	}
	public GasInventory inventory(int node) { return domain.gasState.spatial.cells().get(node-1); }
	public GasAuditLedger sealedInventory() { return domain.gasState.spatial!=null?mmm.fluid.air.GasRegionAudit.aggregate(domain.gasState.spatial.cells()):domain.gasState.network.sealedInventory(); }
	public long sealedInventoryNanos() { return domain.gasState.spatial!=null?sealedInventory().standardVolumeNanos():domain.gasState.network.sealedInventoryNanos(); }
	public boolean auditClosed() { if(domain.gasState.spatial==null)return domain.gasState.network.auditClosed();var s=domain.gasState.spatial;return mmm.fluid.air.GasRegionAudit.closed(sealedInventory(),s.externalSource().add(s.phaseSource()),s.escapedSink().add(s.phaseSink())); }
	public double standardVolume(int node) { return inventory(node).standardVolumeNanos()*1e-9; }
	public long standardVolumeNanos(int node) { return inventory(node).standardVolumeNanos(); }
	public double availableVolume(int node) { return DomainGasInventory.mixture(domain,node-1,inventory(node),domain.gasState.spatial.energyAt(node-1)).gasVolumeM3(); }
	public double gaugePressure(int node) { return DomainGasInventory.spatialPressure(domain,node-1)-mmm.fluid.air.GasRegistry.STANDARD_PRESSURE_PA; }
	public long ventedNanos() { return domain.gasState.spatial!=null?domain.gasState.spatial.escapedSink().standardVolumeNanos():domain.gasState.network.ventedNanos(); }
	public long drawnFromAmbientNanos() { return domain.gasState.spatial!=null?domain.gasState.spatial.externalSource().standardVolumeNanos():domain.gasState.network.drawnFromAmbientNanos(); }
	public boolean hasPressureDrivenFlow() { return domain.gasState.spatial!=null && domain.gasState.pressureActive; }
	public GasAuditLedger externalSource() { return domain.gasState.spatial!=null?domain.gasState.spatial.externalSource():domain.gasState.network.externalSource(); }
	public GasAuditLedger escapedSink() { return domain.gasState.spatial!=null?domain.gasState.spatial.escapedSink():domain.gasState.network.escapedSink(); }
	public GasAuditLedger phaseSource() { return domain.gasState.spatial!=null?domain.gasState.spatial.phaseSource():domain.gasState.network.phaseSource(); }
	public GasAuditLedger phaseSink() { return domain.gasState.spatial!=null?domain.gasState.spatial.phaseSink():domain.gasState.network.phaseSink(); }

	/** Absolute EOS pressure relative to the declared ambient pressure. */
	public double nodeGaugePressurePa(int node) {
		if (node == mmm.fluid.air.AirNetwork.AMBIENT_NODE) return 0.0;
		return gaugePressure(node);
	}
}

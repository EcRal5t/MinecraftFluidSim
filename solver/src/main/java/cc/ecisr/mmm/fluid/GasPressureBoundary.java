package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.air.GasAuditLedger;
import cc.ecisr.mmm.fluid.air.GasInventory;

import java.util.List;

/**
 * Read-only gas view for the pressure stage and adapter reads: node pressure sources,
 * cell ownership and inventory/audit summaries. There is no mutation path; gas truth
 * changes only through the domain's topology and lifecycle entries, and the demo tuning
 * knob stays a domain method that books its own audit.
 */
public final class GasPressureBoundary {
	private final FluidDomain domain;

	GasPressureBoundary(FluidDomain domain) {
		this.domain = domain;
	}

	public boolean pressureActive() { return domain.gasState.pressureActive; }
	public boolean topologyInitialized() { return frame() != null; }

	/** Unique common gas-piece owner for observation; absent or mixed cells return -1. */
	public int cellNode(int cell) { return frame() == null ? -1 : frame().commonCellNode(cell); }

	public int rawCellNode(int cell) { return cellNode(cell); }
	/** Gas owner at canonical 8*cell+micro: -1 absent, 0 ambient, positive finite node. */
	public int nodeOfPiece(int piece) { return frame() == null ? -1 : frame().nodeOfPiece(piece); }
	GasRuntimeGeometry frame() { return domain.gasState.frame; }

	public int previousSealedNodeCount() { return nodeCount(); }
	public int nodeCount() { return frame() == null ? 0 : frame().nodeCount(); }
	public List<Integer> nodeIds() { return domain.gasState.network.nodeIds(); }
	public GasInventory inventory(int node) { return domain.gasState.network.inventory(node); }
	public GasAuditLedger sealedInventory() { return domain.gasState.network.sealedInventory(); }
	public long sealedInventoryNanos() { return domain.gasState.network.sealedInventoryNanos(); }
	public boolean auditClosed() { return domain.gasState.network.auditClosed(); }
	public double standardVolume(int node) { return domain.gasState.network.standardVolume(node); }
	public long standardVolumeNanos(int node) { return domain.gasState.network.standardVolumeNanos(node); }
	public double availableVolume(int node) { return domain.gasState.network.availableVolume(node); }
	public double gaugePressure(int node) { return domain.gasState.network.gaugePressure(node); }
	public long ventedNanos() { return domain.gasState.network.ventedNanos(); }
	public long drawnFromAmbientNanos() { return domain.gasState.network.drawnFromAmbientNanos(); }
	public int edgeCount() { return domain.gasState.network.edgeCount(); }
	public boolean hasPressureDrivenFlow() {
		return domain.thermalState == null || !domain.thermalState.gasModelBound()
				? domain.gasState.network.hasPressureDrivenFlow(this::nodeGaugePressurePa)
				: domain.gasState.network.hasPressureDrivenFlow(domain.thermalState.gasThermodynamics(domain.gasState.network));
	}
	public GasAuditLedger externalSource() { return domain.gasState.network.externalSource(); }
	public GasAuditLedger escapedSink() { return domain.gasState.network.escapedSink(); }
	public cc.ecisr.mmm.units.SignedDyadic massRoundoff() { return domain.gasState.network.massRoundoff(); }
	public cc.ecisr.mmm.units.SignedDyadic residualRoundoff() { return domain.gasState.network.residualRoundoff(); }

	/** Thermal-aware node pressure source; standard-temperature fallback before the gas model binds. */
	public double nodeGaugePressurePa(int node) {
		if (node == cc.ecisr.mmm.fluid.air.AirNetwork.AMBIENT_NODE) return 0.0;
		return domain.thermalState == null || !domain.thermalState.gasModelBound()
				? domain.gasState.network.gaugePressure(node)
				: domain.thermalState.gasGaugePressure(node, domain.gasState.network);
	}
}

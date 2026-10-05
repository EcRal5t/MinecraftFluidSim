package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.air.AirNetwork;

/**
 * Sole owner of gas truth: the runtime AirNetwork, the sealed boundary mode, the
 * operator-vented flag, the previous sealed node count, the cell membership and lineage
 * arrays and the pressure-active flag. The state exists for the domain's whole lifetime;
 * the network starts registry-less and is replaced when the gas registry binds, and the
 * membership arrays stay null until the topology is bootstrapped or restored.
 */
final class GasDomainState {
	AirNetwork network = new AirNetwork();
	boolean sealedDomainBoundary;
	int previousSealedNodeCount;
	int[] cellNodeId;
	int[] cellLineageNodeId;
	boolean pressureActive;
	cc.ecisr.mmm.fluid.air.GasGeometryState geometry;
}

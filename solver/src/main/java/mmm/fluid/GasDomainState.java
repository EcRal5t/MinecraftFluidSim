package mmm.fluid;

import mmm.fluid.air.AirNetwork;

/**
 * Sole owner of gas truth: the runtime AirNetwork, the sealed boundary mode, the
 * captured microbox geometry and the pressure-active flag. The state exists for the domain's whole lifetime;
 * the network starts registry-less and is replaced when the gas registry binds, and the
 * geometry frame stays null until the topology is bootstrapped or restored.
 */
final class GasDomainState {
	AirNetwork network = new AirNetwork();
	boolean sealedDomainBoundary;
	GasRuntimeGeometry frame;
	boolean pressureActive;
}

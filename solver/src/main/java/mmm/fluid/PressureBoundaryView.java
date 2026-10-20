package mmm.fluid;

import mmm.fluid.air.AirNetwork;

/**
 * Read-only gas-pressure seam for the pressure stage: cavity activity, cell-to-node
 * ownership, thermal-aware node pressure and EoS volume compliance. The stage never touches the mutable
 * network; face incidence uses these state-equation values with the shared geometry.
 */
final class PressureBoundaryView {
	private final FluidDomain domain;
	PressureBoundaryView(FluidDomain domain) {
		this.domain = domain;
	}

	boolean gasNodeHasContinuumState(int node) {
		return node > 0 && !domain.gasState.network.inventory(node).isEmpty();
	}
	double gasNodeVolumeCompliance(int node, double loadGaugePressure) {
		if (!gasNodeHasContinuumState(node)) {
			throw new IllegalArgumentException("Vacuum or ambient has no compressible pressure unknown");
		}
		double volume = domain.gasState.network.availableVolume(node);
		double absolutePressure = AirNetwork.REFERENCE_PRESSURE + domain.gasState.network.gaugePressure(node);
		double exponent = 1;
		if (domain.thermalState != null && domain.thermalState.gasModelBound()) {
			var state = domain.thermalState.gasHydrodynamicState(node, domain.gasState.network);
			absolutePressure = state.absolutePressurePa();
			exponent = state.isentropicExponent();
		}
		double load = Math.max(absolutePressure, AirNetwork.REFERENCE_PRESSURE + loadGaugePressure);
		double difference = load - absolutePressure;
		// The EOS chord spans the available gas volume and the surrounding liquid
		// load. A near-vacuum tangent would permit many complete cavity volumes
		// to enter while its predicted pressure remained close to zero.
		double compliance = difference == 0 ? volume / (exponent * absolutePressure)
				: -volume * Math.expm1(-Math.log1p(difference / absolutePressure) / exponent) / difference;
		if (!(Double.isFinite(compliance) && compliance > 0.0)) {
			throw new IllegalStateException("Gas pressure-volume response is invalid");
		}
		return compliance;
	}
	double gasPressureOfNode(int node) { return domain.gasBoundary.nodeGaugePressurePa(node); }
}

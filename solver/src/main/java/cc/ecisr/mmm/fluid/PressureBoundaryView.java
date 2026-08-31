package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.air.AirNetwork;

/**
 * Read-only gas-pressure seam for the pressure stage: cavity activity, cell-to-node
 * ownership, thermal-aware node pressure and EoS volume compliance. The stage never touches the mutable
 * network; the same reads stay available to the domain for the surface-tension divergence.
 */
final class PressureBoundaryView {
	private final FluidDomain domain;

	PressureBoundaryView(FluidDomain domain) {
		this.domain = domain;
	}

	boolean pressureActive() { return domain.gasState.pressureActive; }
	int gasNodeOfAirCell(int airCell) { return domain.gasNodeOfAirCell(airCell); }
	boolean gasNodeHasContinuumState(int node) {
		return node > 0 && !domain.gasState.network.inventory(node).isEmpty();
	}
	double gasNodeVolumeCompliance(int node) {
		if (!gasNodeHasContinuumState(node)) {
			throw new IllegalArgumentException("Vacuum or ambient has no compressible pressure unknown");
		}
		double volume = domain.gasState.network.availableVolume(node);
		if (domain.thermalState != null && domain.thermalState.gasModelBound()) {
			return FluidThermalBinding.gasVolumeCompliance(domain.thermalState, node, domain.gasState.network);
		}
		double absolutePressure = AirNetwork.REFERENCE_PRESSURE + domain.gasState.network.gaugePressure(node);
		double compliance = volume / absolutePressure;
		if (!(Double.isFinite(compliance) && compliance > 0.0)) {
			throw new IllegalStateException("Isothermal gas-node compliance is invalid");
		}
		return compliance;
	}
	double gasPressureOfNode(int node) { return domain.gasPressureOfNode(node); }
	/** Cell-referenced cavity pressure with the ambient/inactive zero gate. */
	float gasPressureOfAirCell(int airCell) { return domain.gasPressureOfAirCell(airCell); }
}

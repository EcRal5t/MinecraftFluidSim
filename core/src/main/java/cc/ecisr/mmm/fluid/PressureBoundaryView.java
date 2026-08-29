package cc.ecisr.mmm.fluid;

/**
 * Read-only gas-pressure seam for the pressure stage: cavity activity, cell-to-node
 * ownership and the thermal-aware node pressure. The stage never touches the mutable
 * network; the same reads stay available to the domain for the surface-tension divergence.
 */
final class PressureBoundaryView {
	private final FlipFluidDomain domain;

	PressureBoundaryView(FlipFluidDomain domain) {
		this.domain = domain;
	}

	boolean pressureActive() { return domain.gasState.pressureActive; }
	int gasNodeOfAirCell(int airCell) { return domain.gasNodeOfAirCell(airCell); }
	double gasPressureOfNode(int node) { return domain.gasPressureOfNode(node); }
}

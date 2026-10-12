package mmm.fluid;

import mmm.fluid.air.AirNetwork;

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
	int gasNodeCount() { return domain.gasBoundary.nodeCount(); }
	int gasNodeOfAirCell(int airCell) { return wholeCellGasNode(domain, airCell); }
	/** Coarse pressure rows require one actual gas owner; pressure-air fringe retains its derived extension. */
	static int wholeCellGasNode(FluidDomain domain, int cell) {
		var frame = domain.gasState.frame; int owner = -1;
		if (frame == null) return -1;
		for (int micro = 0; micro < 8; micro++) {
			int node = frame.nodeOfPiece(8 * cell + micro); if (node < 0) continue;
			if (owner >= 0 && owner != node)
				throw new GasTopologyBlockedException("Whole-cell pressure coupling cannot resolve multiple gas owners at cell " + cell);
			owner = node;
		}
		return owner >= 0 ? owner : domain.gasNodeOfAirCell(cell);
	}
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
	double gasPressureOfNode(int node) { return domain.gasBoundary.nodeGaugePressurePa(node); }
	float pressureReference(boolean[] seen) {
		if (!pressureActive() || seen[AirNetwork.AMBIENT_NODE]) return 0;
		double largest = 0;
		for (int node = 1; node < seen.length; node++) if (seen[node]) {
			if (!gasNodeHasContinuumState(node)) return checkedReference(gasPressureOfNode(node));
			double compliance = gasNodeVolumeCompliance(node);
			if (!Double.isFinite(compliance)) return checkedReference(gasPressureOfNode(node));
			largest = Math.max(largest, compliance);
		}
		if (largest == 0) return 0;
		double total = 0, reference = 0;
		for (int node = 1; node < seen.length; node++) if (seen[node]) {
			double weight = gasNodeVolumeCompliance(node) / largest;
			if (weight == 0) continue;
			total += weight;
			reference += (gasPressureOfNode(node) - reference) * (weight / total);
		}
		return checkedReference(reference);
	}
	private static float checkedReference(double value) {
		if (!Float.isFinite((float) value)) throw new FluidDomain.PressureSolveBlockedException("Unrepresentable gas pressure reference");
		return (float) value;
	}
	/** Cell-referenced cavity pressure with the ambient/inactive zero gate. */
	float gasPressureOfAirCell(int airCell) {
		int node = wholeCellGasNode(domain, airCell);
		return node > 0 ? (float) gasPressureOfNode(node) : 0;
	}
}

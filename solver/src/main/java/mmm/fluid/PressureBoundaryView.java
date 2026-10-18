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
	record Compression(double volume, double pressure, mmm.thermal.GasThermalModel.PressureVolumeCurve thermal) {
		mmm.thermal.GasThermalModel.PressureVolume atPressure(double absolutePressure) {
			return thermal != null ? thermal.atPressure(absolutePressure)
					: new mmm.thermal.GasThermalModel.PressureVolume(volume * pressure / absolutePressure,
							volume * pressure / (absolutePressure * absolutePressure));
		}
	}
	Compression prepareCompression(int node) {
		var network = domain.gasState.network;
		double volume = network.availableVolume(node);
		if (domain.thermalState == null || !domain.thermalState.gasModelBound())
			return new Compression(volume, AirNetwork.REFERENCE_PRESSURE + network.gaugePressure(node), null);
		var thermal = domain.thermalState;
		var curve = new mmm.thermal.GasThermalModel(thermal.registry(), network.registry())
				.pressureVolumeCurve(network.inventory(node), volume, thermal.gasNodeEnergy(node, network));
		return new Compression(volume, AirNetwork.REFERENCE_PRESSURE + gasPressureOfNode(node), curve);
	}

	double gasPressureOfNode(int node) { return domain.gasBoundary.nodeGaugePressurePa(node); }
}

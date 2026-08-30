package cc.ecisr.mmm.application.result;

import cc.ecisr.mmm.fluid.FluidDomain;
import cc.ecisr.mmm.fluid.FluidThermalStepCandidate;

import java.util.Objects;

public record ThermalStepResult(RegionStepStatus status, FluidDomain.ThermalAxisStats stats) {
	public ThermalStepResult {
		Objects.requireNonNull(status, "status");
		if ((status == RegionStepStatus.ACCEPTED) != (stats != null)) {
			throw new IllegalArgumentException("Thermal step status/outcome disagree");
		}
	}

	public static ThermalStepResult accepted(FluidThermalStepCandidate candidate) {
		return new ThermalStepResult(RegionStepStatus.ACCEPTED,
				Objects.requireNonNull(candidate, "candidate").stats());
	}

	public static ThermalStepResult rejected(RegionStepStatus status) { return new ThermalStepResult(status, null); }
	public boolean accepted() { return status == RegionStepStatus.ACCEPTED; }
}

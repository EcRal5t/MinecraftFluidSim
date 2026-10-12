package mmm.fluid;

import mmm.thermal.ThermalLimits;

import java.util.Objects;

/** Immutable thermal-only backend input for a hydrostatically sleeping region. */
public record FluidThermalStepRequest(
		long baseGeneration,
		FluidDomainState state,
		boolean phaseChangeEnabled,
		int maximumPhaseOperations
) {
	public FluidThermalStepRequest {
		if (baseGeneration < 0L) throw new IllegalArgumentException("Base generation must not be negative");
		Objects.requireNonNull(state, "state");
		if (state.thermal() == null) throw new IllegalArgumentException("Thermal-only request requires thermal state");
		if (phaseChangeEnabled && (maximumPhaseOperations <= 0
				|| maximumPhaseOperations > ThermalLimits.MAX_PHASE_OPERATIONS_PER_STEP)) {
			throw new IllegalArgumentException("Phase-change request is incompatible with the operation budget");
		}
		if (!phaseChangeEnabled && maximumPhaseOperations != 0) {
			throw new IllegalArgumentException("Inactive phase change must not reserve an operation budget");
		}
	}
}

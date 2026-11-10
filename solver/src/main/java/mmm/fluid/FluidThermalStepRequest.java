package mmm.fluid;

import java.util.Objects;

/** Immutable thermal-only backend input for a hydrostatically sleeping region. */
public record FluidThermalStepRequest(
		long baseGeneration,
		FluidStateAccess stateAccess,
		boolean phaseChangeEnabled,
		boolean[] activeCells
) {
	public FluidThermalStepRequest(long generation, FluidStateAccess state, boolean phase) {
		this(generation, state, phase, null);
	}
	@Override public boolean[] activeCells() { return activeCells == null ? null : activeCells.clone(); }
	public FluidThermalStepRequest {
		if (baseGeneration < 0L) throw new IllegalArgumentException("Base generation must not be negative");
		Objects.requireNonNull(stateAccess, "stateAccess");
		if (activeCells != null) {
			var layout = stateAccess.layout();
			if (activeCells.length != layout.grid().cellCount())
				throw new IllegalArgumentException("Activity mask differs from the region layout");
			activeCells = activeCells.clone();
		}
		if (stateAccess.layout().thermalRegistryFingerprint().isEmpty()) throw new IllegalArgumentException("Thermal-only request requires thermal state");
		if (phaseChangeEnabled && stateAccess.layout().chemicalRegistryFingerprint().isEmpty()) {
			throw new IllegalArgumentException("Phase-change request requires chemical state");
		}
	}
}

package mmm.application.command;

import java.util.Objects;
import java.util.UUID;

/** Loader-neutral facts frozen for one hydrostatic thermal-only step. */
public record ThermalStepCommand(
		UUID regionId,
		long expectedSolverGeneration,
		boolean phaseChangeEnabled,
		boolean[] activeCells
) {
	public ThermalStepCommand(UUID id, long generation, boolean phase) {
		this(id, generation, phase, null);
	}
	@Override public boolean[] activeCells() { return activeCells == null ? null : activeCells.clone(); }
	public ThermalStepCommand {
		Objects.requireNonNull(regionId, "regionId");
		if (activeCells != null) activeCells = activeCells.clone();
		if (expectedSolverGeneration < 0L) throw new IllegalArgumentException("Expected solver generation is invalid");
	}
}

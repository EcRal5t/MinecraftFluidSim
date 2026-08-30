package cc.ecisr.mmm.application.command;

import java.util.Objects;
import java.util.UUID;

/** Loader-neutral facts frozen for one hydrostatic thermal-only step. */
public record ThermalStepCommand(
		UUID regionId,
		long expectedSolverGeneration,
		boolean phaseChangeEnabled,
		int maximumPhaseOperations
) {
	public ThermalStepCommand {
		Objects.requireNonNull(regionId, "regionId");
		if (expectedSolverGeneration < 0L) throw new IllegalArgumentException("Expected solver generation is invalid");
		MotionStepCommand.requirePhaseBudget(phaseChangeEnabled, maximumPhaseOperations);
	}
}

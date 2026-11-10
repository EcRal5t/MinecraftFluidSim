package mmm.application.command;

import mmm.world.MovingBodyFluidCoupling;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Loader-neutral facts frozen for one due motion step. */
public record MotionStepCommand(
		UUID regionId,
		long expectedSolverGeneration,
		List<MovingBodyFluidCoupling.Request> movingBodies,
		boolean phaseChangeEnabled,
		boolean mechanicalAuditEnabled,
		boolean pendingPhaseSettlementOnly,
		boolean[] activeCells
) {
	public MotionStepCommand(UUID id, long generation, List<MovingBodyFluidCoupling.Request> bodies,
			boolean phase, boolean audit, boolean settlement) {
		this(id, generation, bodies, phase, audit, settlement, null);
	}
	@Override public boolean[] activeCells() { return activeCells == null ? null : activeCells.clone(); }
	public MotionStepCommand(
			UUID regionId, long expectedSolverGeneration,
			List<MovingBodyFluidCoupling.Request> movingBodies,
			boolean phaseChangeEnabled, boolean mechanicalAuditEnabled
	) {
		this(regionId, expectedSolverGeneration, movingBodies, phaseChangeEnabled,
				mechanicalAuditEnabled, false);
	}

	public static MotionStepCommand pendingPhaseSettlement(UUID regionId, long expectedSolverGeneration) {
		return new MotionStepCommand(regionId, expectedSolverGeneration, List.of(), false, false, true);
	}

	public MotionStepCommand {
		Objects.requireNonNull(regionId, "regionId");
		if (activeCells != null) activeCells = activeCells.clone();
		if (expectedSolverGeneration < 0L) throw new IllegalArgumentException("Expected solver generation is invalid");
		if (movingBodies == null || movingBodies.size() > MovingBodyFluidCoupling.MAXIMUM_REQUESTS_PER_STEP) {
			throw new IllegalArgumentException("Moving-body command budget is invalid");
		}
		movingBodies = List.copyOf(movingBodies);
		if (pendingPhaseSettlementOnly && (!movingBodies.isEmpty() || phaseChangeEnabled
				|| mechanicalAuditEnabled)) {
			throw new IllegalArgumentException("Pending phase settlement command must be mechanics-only");
		}
	}
}

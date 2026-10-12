package mmm.application.command;

import mmm.thermal.ThermalLimits;
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
		int maximumPhaseOperations,
		boolean mechanicalAuditEnabled,
		boolean pendingPhaseSettlementOnly
) {
	public MotionStepCommand(
			UUID regionId, long expectedSolverGeneration,
			List<MovingBodyFluidCoupling.Request> movingBodies,
			boolean phaseChangeEnabled, int maximumPhaseOperations, boolean mechanicalAuditEnabled
	) {
		this(regionId, expectedSolverGeneration, movingBodies, phaseChangeEnabled, maximumPhaseOperations,
				mechanicalAuditEnabled, false);
	}

	public static MotionStepCommand pendingPhaseSettlement(UUID regionId, long expectedSolverGeneration) {
		return new MotionStepCommand(regionId, expectedSolverGeneration, List.of(), false, 0, false, true);
	}

	public MotionStepCommand {
		Objects.requireNonNull(regionId, "regionId");
		if (expectedSolverGeneration < 0L) throw new IllegalArgumentException("Expected solver generation is invalid");
		if (movingBodies == null || movingBodies.size() > MovingBodyFluidCoupling.MAXIMUM_REQUESTS_PER_STEP) {
			throw new IllegalArgumentException("Moving-body command budget is invalid");
		}
		movingBodies = List.copyOf(movingBodies);
		requirePhaseBudget(phaseChangeEnabled, maximumPhaseOperations);
		if (pendingPhaseSettlementOnly && (!movingBodies.isEmpty() || phaseChangeEnabled
				|| maximumPhaseOperations != 0 || mechanicalAuditEnabled)) {
			throw new IllegalArgumentException("Pending phase settlement command must be mechanics-only");
		}
	}

	static void requirePhaseBudget(boolean enabled, int maximumOperations) {
		if (enabled && (maximumOperations <= 0
				|| maximumOperations > ThermalLimits.MAX_PHASE_OPERATIONS_PER_STEP)) {
			throw new IllegalArgumentException("Phase-change command budget is invalid");
		}
		if (!enabled && maximumOperations != 0) {
			throw new IllegalArgumentException("Inactive phase change cannot reserve operations");
		}
	}
}

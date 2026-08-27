package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.thermal.ThermalLimits;
import cc.ecisr.mmm.world.MovingBodyFluidCoupling;
import cc.ecisr.mmm.world.MovingBodySurfaceSnapshot;

import java.util.List;
import java.util.Objects;

/** Immutable backend input; evaluating it must not mutate the committed owner state. */
public record FluidStepRequest(
		long baseGeneration,
		FluidDomainState state,
		List<MovingBodyFluidCoupling.Request> movingBodies,
		MovingBodySurfaceSnapshot movingBodySurfaces,
		boolean phaseChangeEnabled,
		int maximumPhaseOperations
) {
	public FluidStepRequest(long baseGeneration, FluidDomainState state,
			List<MovingBodyFluidCoupling.Request> movingBodies,
			boolean phaseChangeEnabled, int maximumPhaseOperations) {
		this(baseGeneration, state, movingBodies, MovingBodySurfaceSnapshot.EMPTY,
				phaseChangeEnabled, maximumPhaseOperations);
	}

	public FluidStepRequest {
		if (baseGeneration < 0L) throw new IllegalArgumentException("Base generation must not be negative");
		Objects.requireNonNull(state, "state");
		if (movingBodies == null || movingBodies.size() > MovingBodyFluidCoupling.MAXIMUM_REQUESTS_PER_STEP) {
			throw new IllegalArgumentException("Moving-body request budget is invalid");
		}
		movingBodies = List.copyOf(movingBodies);
		Objects.requireNonNull(movingBodySurfaces, "movingBodySurfaces");
		if (phaseChangeEnabled && (state.thermal() == null || maximumPhaseOperations <= 0
				|| maximumPhaseOperations > ThermalLimits.MAX_PHASE_OPERATIONS_PER_STEP)) {
			throw new IllegalArgumentException("Phase-change request is incompatible with the state/budget");
		}
		if (!phaseChangeEnabled && maximumPhaseOperations != 0) {
			throw new IllegalArgumentException("Inactive phase change must not reserve an operation budget");
		}
	}
}

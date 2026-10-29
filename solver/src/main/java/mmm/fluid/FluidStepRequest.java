package mmm.fluid;

import mmm.thermal.ThermalLimits;
import mmm.world.MovingBodyFluidCoupling;

import java.util.List;
import java.util.Objects;

/** Immutable backend input; evaluating it must not mutate the committed owner state. */
public record FluidStepRequest(
		long baseGeneration,
		FluidStateAccess stateAccess,
		List<MovingBodyFluidCoupling.Request> movingBodies,
		boolean phaseChangeEnabled,
		int maximumPhaseOperations,
		boolean mechanicalAuditEnabled,
		boolean pendingPhaseSettlementOnly,
		boolean[] activeCells
) {
	public FluidStepRequest(long generation, FluidStateAccess state, List<MovingBodyFluidCoupling.Request> bodies,
			boolean phase, int budget, boolean audit, boolean settlement) {
		this(generation, state, bodies, phase, budget, audit, settlement, null);
	}
	@Override public boolean[] activeCells() { return activeCells == null ? null : activeCells.clone(); }
	public FluidStepRequest(
			long baseGeneration, FluidDomainState state,
			List<MovingBodyFluidCoupling.Request> movingBodies,
			boolean phaseChangeEnabled, int maximumPhaseOperations
	) {
		this(baseGeneration, state, movingBodies, phaseChangeEnabled, maximumPhaseOperations, false, false);
	}

	public FluidStepRequest(
			long baseGeneration, FluidDomainState state,
			List<MovingBodyFluidCoupling.Request> movingBodies,
			boolean phaseChangeEnabled, int maximumPhaseOperations, boolean mechanicalAuditEnabled
	) {
		this(baseGeneration, state, movingBodies, phaseChangeEnabled, maximumPhaseOperations,
				mechanicalAuditEnabled, false);
	}

	public static FluidStepRequest pendingPhaseSettlement(long baseGeneration, FluidDomainState state) {
		return new FluidStepRequest(baseGeneration, state, List.of(), false, 0, false, true);
	}

	public FluidStepRequest {
		if (baseGeneration < 0L) throw new IllegalArgumentException("Base generation must not be negative");
		Objects.requireNonNull(stateAccess, "stateAccess");
		if (activeCells != null) {
			var layout = stateAccess.layout();
			if (activeCells.length != layout.sizeX() * layout.sizeY() * layout.sizeZ())
				throw new IllegalArgumentException("Activity mask differs from the region layout");
			activeCells = activeCells.clone();
		}
		if (movingBodies == null || movingBodies.size() > MovingBodyFluidCoupling.MAXIMUM_REQUESTS_PER_STEP) {
			throw new IllegalArgumentException("Moving-body request budget is invalid");
		}
		movingBodies = new EntityBodyState(movingBodies).bodies();
		if (phaseChangeEnabled && (stateAccess.layout().thermalRegistryFingerprint().isEmpty()
				|| stateAccess.layout().chemicalRegistryFingerprint().isEmpty() || maximumPhaseOperations <= 0
				|| maximumPhaseOperations > ThermalLimits.MAX_PHASE_OPERATIONS_PER_STEP)) {
			throw new IllegalArgumentException("Phase-change request is incompatible with the state/budget");
		}
		if (!phaseChangeEnabled && maximumPhaseOperations != 0) {
			throw new IllegalArgumentException("Inactive phase change must not reserve an operation budget");
		}
		if (pendingPhaseSettlementOnly && (!movingBodies.isEmpty() || phaseChangeEnabled
				|| maximumPhaseOperations != 0 || mechanicalAuditEnabled)) {
			throw new IllegalArgumentException("Pending phase settlement must be a mechanics-only request");
		}
	}
}

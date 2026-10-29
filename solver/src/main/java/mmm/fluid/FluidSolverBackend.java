package mmm.fluid;

/** Loader-neutral evaluator. Commit authority remains with the region owner, never with a backend. */
public interface FluidSolverBackend extends AutoCloseable {
    final class DeviceLostException extends IllegalStateException {
        public DeviceLostException(String message) { super(message); }
    }
	interface Provider {
		String id();
		FluidSolverBackend create(int deviceIndex);
	}
    default String unavailableReason() { return null; }
	default String executionDescription() { return id(); }
	/** Prepare backend resources for the capabilities of a world before its first step. */
	default void prepare(FluidDomainState state) { }
	@Override default void close() { }
	String id();
    /** Stages an initialization or a complete host edit before the application publishes it. */
    PreparedFluidState prepareState(FluidStateAccess source, FluidDomainState successor,
            mmm.thermal.FiniteThermalDeviceInventory devices, java.util.List<FluidStateAccess.DevicePort> ports);
    /** Stages a same-generation thermal boundary input without exporting the resident inventory. */
    default PreparedFluidState prepareThermalBoundary(ResidentFluidState source, double[] temperatures) {
        throw new UnsupportedOperationException("Thermal boundary candidates are unavailable");
    }
	FluidStepCandidate evaluate(FluidStepRequest request);
	FluidThermalStepCandidate evaluateThermal(FluidThermalStepRequest request);

	/** A frozen authoritative handoff could not be materialized; the live owner remains untouched. */
	final class StateRestoreRejectedException extends IllegalStateException {
		public StateRestoreRejectedException(String message, RuntimeException cause) { super(message, cause); }
	}

	/** A private candidate failed before a complete successor existed; the live owner remains untouched. */
	class CandidateEvaluationRejectedException extends IllegalStateException {
		public CandidateEvaluationRejectedException(String message, RuntimeException cause) { super(message, cause); }
	}

	/**
	 * A private candidate exhausted its mechanical work budget. This reports spent resources, not
	 * damaged physics: no owner mutated, and the state that ran out of budget is left to pacing or
	 * to an operator instead of being settled with a coarser step.
	 */
	final class MechanicalWorkBudgetExceededException extends CandidateEvaluationRejectedException {
		public MechanicalWorkBudgetExceededException(String message) { super(message, null); }
	}
}

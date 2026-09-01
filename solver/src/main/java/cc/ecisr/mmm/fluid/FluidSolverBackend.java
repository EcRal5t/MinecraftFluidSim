package cc.ecisr.mmm.fluid;

/** Loader-neutral evaluator. Commit authority remains with the region owner, never with a backend. */
public interface FluidSolverBackend {
	String id();
	FluidStepCandidate evaluate(FluidStepRequest request);
	FluidThermalStepCandidate evaluateThermal(FluidThermalStepRequest request);

	/** A frozen authoritative handoff could not be materialized; the live owner remains untouched. */
	final class StateRestoreRejectedException extends IllegalStateException {
		public StateRestoreRejectedException(String message, RuntimeException cause) { super(message, cause); }
	}

	/** A private candidate failed before a complete successor existed; the live owner remains untouched. */
	final class CandidateEvaluationRejectedException extends IllegalStateException {
		public CandidateEvaluationRejectedException(String message, RuntimeException cause) { super(message, cause); }
	}
}

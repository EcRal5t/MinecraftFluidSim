package cc.ecisr.mmm.fluid;

/** Loader-neutral evaluator. Commit authority remains with the region owner, never with a backend. */
public interface FluidSolverBackend {
	String id();
	FluidStepCandidate evaluate(FluidStepRequest request);
	FluidThermalStepCandidate evaluateThermal(FluidThermalStepRequest request);
}

package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.world.MovingBodyFluidCoupling;

/** Reference backend that evaluates a request on a private restored domain. */
public final class CpuFluidSolverBackend implements FluidSolverBackend {
	public static final String ID = "mmm:cpu-flip";

	@Override public String id() { return ID; }

	@Override public FluidStepCandidate evaluate(FluidStepRequest request) {
		if (request == null) throw new IllegalArgumentException("Step request is missing");
		FluidDomain candidate = FluidDomain.restoreAuthoritativeState(request.state());
		MovingBodyFluidCoupling.Result movingBodyCoupling = MovingBodyFluidCoupling.Result.NONE;
		for (var movingBody : request.movingBodies()) {
			movingBodyCoupling = movingBodyCoupling.plus(candidate.applyMovingBodyCoupling(movingBody));
		}
		FluidDomain.StepStats stats = request.mechanicalAuditEnabled()
				? candidate.stepWithMechanicalAudit(
						request.phaseChangeEnabled(), request.maximumPhaseOperations())
				: candidate.step(request.phaseChangeEnabled(), request.maximumPhaseOperations());
		return new FluidStepCandidate(id(), request.baseGeneration(),
				Math.addExact(request.baseGeneration(), 1L), candidate.captureAuthoritativeState(), stats,
				movingBodyCoupling);
	}
}

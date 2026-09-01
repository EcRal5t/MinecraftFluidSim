package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.chemistry.ChemicalReactionEngine;
import cc.ecisr.mmm.granular.GranularMechanics;
import cc.ecisr.mmm.granular.GranularPhaseTransferEngine;
import cc.ecisr.mmm.world.MovingBodyFluidCoupling;

/** Reference backend that evaluates a request on a private restored domain. */
public final class CpuFluidSolverBackend implements FluidSolverBackend {
	public static final String ID = "mmm:cpu-flip";

	@Override public String id() { return ID; }

	@Override public FluidStepCandidate evaluate(FluidStepRequest request) {
		if (request == null) throw new IllegalArgumentException("Step request is missing");
		try {
			FluidDomain candidate = restoreCandidate(request.state());
			MovingBodyFluidCoupling.Result movingBodyCoupling = MovingBodyFluidCoupling.Result.NONE;
			for (var movingBody : request.movingBodies()) {
				movingBodyCoupling = movingBodyCoupling.plus(candidate.applyMovingBodyCoupling(movingBody));
			}
			FluidDomain.StepStats stats = request.mechanicalAuditEnabled()
					? candidate.stepWithMechanicalAudit(
							request.phaseChangeEnabled(), request.maximumPhaseOperations())
					: candidate.step(request.phaseChangeEnabled(), request.maximumPhaseOperations());
			return FluidStepCandidate.preparedCpu(
					request.baseGeneration(), candidate, stats, movingBodyCoupling);
		} catch (RuntimeException rejected) {
			throw candidateFailure("mechanical", rejected);
		}
	}

	@Override public FluidThermalStepCandidate evaluateThermal(FluidThermalStepRequest request) {
		if (request == null) throw new IllegalArgumentException("Thermal step request is missing");
		try {
			FluidDomain candidate = restoreCandidate(request.state());
			FluidDomain.ThermalAxisStats stats = candidate.stepThermalAxis(
					request.phaseChangeEnabled(), request.maximumPhaseOperations());
			return FluidThermalStepCandidate.preparedCpu(request.baseGeneration(), candidate, stats);
		} catch (RuntimeException rejected) {
			throw candidateFailure("thermal", rejected);
		}
	}

	private static RuntimeException candidateFailure(String axis, RuntimeException rejected) {
		if (rejected instanceof FluidSolverBackend.StateRestoreRejectedException
				|| rejected instanceof FluidDomain.PressureSolveBlockedException
				|| rejected instanceof GasTopologyBlockedException
				|| rejected instanceof FluidDomain.ThermalTransportBlockedException
				|| rejected instanceof FluidDomain.GasThermalBlockedException
				|| rejected instanceof FluidDomain.ThermalPhaseBlockedException
				|| rejected instanceof ChemicalReactionEngine.BudgetExceededException
				|| rejected instanceof GranularPhaseTransferEngine.BlockedException
				|| rejected instanceof GranularMechanics.ValidityExceededException) return rejected;
		return new FluidSolverBackend.CandidateEvaluationRejectedException(
				"Private " + axis + " candidate evaluation failed: " + rejected.getMessage(), rejected);
	}

	private static FluidDomain restoreCandidate(FluidDomainState state) {
		try {
			return FluidDomain.restoreAuthoritativeState(state);
		} catch (IllegalArgumentException | IllegalStateException | ArithmeticException rejected) {
			throw new FluidSolverBackend.StateRestoreRejectedException(
					"Frozen authoritative state failed private backend restore: " + rejected.getMessage(), rejected);
		}
	}
}

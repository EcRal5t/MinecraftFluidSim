package mmm.fluid;

import mmm.chemistry.ChemicalReactionEngine;
import mmm.granular.GranularMechanics;
import mmm.granular.GranularPhaseTransferEngine;
import mmm.world.MovingBodyFluidCoupling;

/** Reference backend that evaluates a request on a private restored domain. */
public final class CpuFluidSolverBackend implements FluidSolverBackend {
	public static final String ID = "mmm:cpu-flip";
	private static final int MAXIMUM_TIME_REFINEMENTS = 16;
	/**
	 * Mechanical substeps one complete candidate may spend in total. Every time refinement doubles
	 * the substep count and the CFL count itself is unbounded, so counting attempts alone leaves a
	 * synchronous request able to occupy the server thread for far longer than one step budget.
	 */
	public static final long MAXIMUM_CUMULATIVE_SUBSTEPS = 4096L;

	@Override public String id() { return ID; }

	@Override public FluidStepCandidate evaluate(FluidStepRequest request) {
		if (request == null) throw new IllegalArgumentException("Step request is missing");
		try {
			int minimumSubsteps = 1;
			long spentSubsteps = 0L;
			String refinementReason = "";
			for (int refinement = 0; ; refinement++) {
				FluidDomain candidate = restoreCandidate(request.state());
				candidate.minimumMechanicalSubsteps = minimumSubsteps;
				MovingBodyFluidCoupling.Result movingBodyCoupling = candidate.applyMovingBodyCouplings(request.movingBodies());
				int substeps = candidate.calculateSubsteps(candidate.config.timeStep());
				spentSubsteps = Math.addExact(spentSubsteps, substeps);
				if (spentSubsteps > MAXIMUM_CUMULATIVE_SUBSTEPS) {
					// The private candidate is still the only thing that exists here, so the whole
					// request can be refused without leaving a half-stepped owner behind.
					throw new FluidSolverBackend.MechanicalWorkBudgetExceededException(
							"Mechanical candidate needs more than " + MAXIMUM_CUMULATIVE_SUBSTEPS
									+ " cumulative substeps after " + refinement + " time refinement(s): " + refinementReason);
				}
				FluidDomain.StepStats stats;
				try {
					stats = request.pendingPhaseSettlementOnly()
						? candidate.step(false, 0, false, false, substeps)
						: candidate.step(request.phaseChangeEnabled(), request.maximumPhaseOperations(),
								request.mechanicalAuditEnabled(), true, substeps);
				} catch (MechanicalTimeRefinement finer) {
					refinementReason = finer.getMessage();
					if (refinement >= MAXIMUM_TIME_REFINEMENTS) throw finer;
					minimumSubsteps = Math.max(Math.multiplyExact(minimumSubsteps, 2), finer.minimumSubsteps);
					continue;
				}
				candidate.minimumMechanicalSubsteps = 1;
				return FluidStepCandidate.preparedCpu(
						request.baseGeneration(), candidate, stats, movingBodyCoupling,
						new FluidStepCandidate.MechanicalResolution(refinement, spentSubsteps, refinementReason));
			}
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
				|| rejected instanceof GranularMechanics.ValidityExceededException
				|| rejected instanceof FluidSolverBackend.MechanicalWorkBudgetExceededException) return rejected;
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

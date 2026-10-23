package mmm.fluid;

import mmm.chemistry.ChemicalReactionEngine;
import mmm.granular.GranularMechanics;
import mmm.granular.GranularPhaseTransferEngine;
import mmm.world.MovingBodyFluidCoupling;

/** Reference backend that evaluates a request on a private restored domain. */
public final class CpuFluidSolverBackend implements FluidSolverBackend, AutoCloseable {
	public static final String ID = "mmm:cpu-flip";
	public static final int MAXIMUM_TIME_REFINEMENTS = 16;
	/**
	 * Mechanical substeps one complete candidate may spend in total. Every time refinement doubles
	 * the substep count and granular displacement may require many substeps, so counting attempts alone leaves a
	 * synchronous request able to occupy the server thread for far longer than one step budget.
	 */
	public static final long MAXIMUM_CUMULATIVE_SUBSTEPS = 4096L;

	private SparseMatrixCompute compute;
	private final java.util.function.Consumer<String> recoveryNotice;
	private String deviceLossReason;
	private boolean closed;

	public CpuFluidSolverBackend() { this.compute = null; this.recoveryNotice = ignored -> { }; }

	/** Owns the injected provider until close or device-loss isolation. */
	public CpuFluidSolverBackend(SparseMatrixCompute compute) { this(compute, ignored -> { }); }

	public CpuFluidSolverBackend(SparseMatrixCompute compute, java.util.function.Consumer<String> recoveryNotice) {
		this.compute = java.util.Objects.requireNonNull(compute);
		this.recoveryNotice = java.util.Objects.requireNonNull(recoveryNotice);
	}

	public String executionDescription() {
		if (deviceLossReason != null) return "CPU after Vulkan device loss: " + deviceLossReason;
		return compute == null ? "CPU" : "CPU + Vulkan pressure (hybrid)";
	}

	@Override public void close() {
		if (closed) return;
		closed = true;
		var released = compute;
		compute = null;
		if (released != null) released.close();
	}

	private boolean isolateLostDevice(RuntimeException failure) {
		if (compute == null || !isDeviceLoss(failure)) return false;
		var released = compute;
		compute = null;
		deviceLossReason = failure.getMessage();
		try { released.close(); }
		catch (RuntimeException closeFailure) {
			if (!isDeviceLoss(closeFailure)) { closeFailure.addSuppressed(failure); throw closeFailure; }
			failure.addSuppressed(closeFailure);
		}
		recoveryNotice.accept(executionDescription() + "; discarded private candidate; restarting full request on CPU");
		return true;
	}

	private static boolean isDeviceLoss(Throwable failure) {
		for (Throwable cause = failure; cause != null; cause = cause.getCause())
			if (cause instanceof SparseMatrixCompute.DeviceLostException) return true;
		return false;
	}

	private ComputeExecution execution() {
		if (closed) throw new IllegalStateException("Fluid solver backend is closed");
		return compute != null ? ComputeExecution.install(compute)
				: deviceLossReason != null ? ComputeExecution.useCpu() : null;
	}

	@Override public String id() { return ID; }

	@Override public FluidStepCandidate evaluate(FluidStepRequest request) {
		try { return evaluateMechanical(request); }
		catch (RuntimeException failure) {
			if (!isolateLostDevice(failure)) throw failure;
			return evaluateMechanical(request);
		}
	}

	private FluidStepCandidate evaluateMechanical(FluidStepRequest request) {
		if (request == null) throw new IllegalArgumentException("Step request is missing");
		try (var execution = execution()) {
			int minimumSubsteps = 1;
			long spentSubsteps = 0L;
			String refinementReason = "";
			for (int refinement = 0; ; refinement++) {
				FluidDomain candidate = restoreCandidate(request.state());
				candidate.minimumMechanicalSubsteps = minimumSubsteps;
				candidate.entityBodyCoupling.replace(request.pendingPhaseSettlementOnly()
						? candidate.entityBodies.bodies().stream().map(body -> new MovingBodyFluidCoupling.Request(
								body.bodyId(), body.bounds(), 0, 0, 0)).toList()
						: request.movingBodies());
				int substeps = candidate.domainStep.calculateSubsteps(candidate.config.timeStep());
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
						? candidate.domainStep.step(false, 0, false, false, substeps)
						: candidate.domainStep.step(request.phaseChangeEnabled(), request.maximumPhaseOperations(),
								request.mechanicalAuditEnabled(), true, substeps);
				} catch (MechanicalTimeRefinement finer) {
					refinementReason = finer.getMessage();
					if (refinement >= MAXIMUM_TIME_REFINEMENTS) throw finer;
					minimumSubsteps = refinedSubsteps(substeps, finer);
					continue;
				}
				candidate.minimumMechanicalSubsteps = 1;
				MovingBodyFluidCoupling.Result movingBodyCoupling = request.pendingPhaseSettlementOnly()
						? MovingBodyFluidCoupling.Result.NONE : candidate.entityBodyCoupling.result();
				return FluidStepCandidate.preparedCpu(
						request.baseGeneration(), candidate, stats, movingBodyCoupling,
						new FluidStepCandidate.MechanicalResolution(refinement, spentSubsteps, refinementReason));
			}
		} catch (RuntimeException rejected) {
			throw candidateFailure("mechanical", rejected);
		}
	}

	/** Refinement halves the attempted granular-motion interval. */
	static int refinedSubsteps(int attempted, MechanicalTimeRefinement finer) {
		return Math.max(Math.multiplyExact(attempted, 2), finer.minimumSubsteps);
	}

	@Override public FluidThermalStepCandidate evaluateThermal(FluidThermalStepRequest request) {
		try { return evaluateThermalCandidate(request); }
		catch (RuntimeException failure) {
			if (!isolateLostDevice(failure)) throw failure;
			return evaluateThermalCandidate(request);
		}
	}

	private FluidThermalStepCandidate evaluateThermalCandidate(FluidThermalStepRequest request) {
		if (request == null) throw new IllegalArgumentException("Thermal step request is missing");
		try (var execution = execution()) {
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

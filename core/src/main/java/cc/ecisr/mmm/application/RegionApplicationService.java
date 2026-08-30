package cc.ecisr.mmm.application;

import cc.ecisr.mmm.application.command.CheckpointRegionCommand;
import cc.ecisr.mmm.application.command.ControllerSummaryCommand;
import cc.ecisr.mmm.application.command.MotionStepCommand;
import cc.ecisr.mmm.application.command.ThermalStepCommand;
import cc.ecisr.mmm.application.port.ControllerSummaryPort;
import cc.ecisr.mmm.application.port.RegionCheckpointPort;
import cc.ecisr.mmm.application.port.RegionRepository;
import cc.ecisr.mmm.application.result.CheckpointRegionResult;
import cc.ecisr.mmm.application.result.ControllerSummaryResult;
import cc.ecisr.mmm.application.result.MotionStepResult;
import cc.ecisr.mmm.application.result.RegionStepStatus;
import cc.ecisr.mmm.application.result.ThermalStepResult;
import cc.ecisr.mmm.controller.ControllerSummary;
import cc.ecisr.mmm.fluid.FluidSolverBackend;
import cc.ecisr.mmm.fluid.FluidStepCandidate;
import cc.ecisr.mmm.fluid.FluidStepRequest;
import cc.ecisr.mmm.fluid.FluidThermalStepCandidate;
import cc.ecisr.mmm.fluid.FluidThermalStepRequest;

import java.util.Objects;

/** Loader-neutral orchestration for the first migrated region commands and bounded reads. */
public final class RegionApplicationService {
	private final FluidSolverBackend solverBackend;
	private final RegionRepository regions;
	private final RegionCheckpointPort checkpoints;
	private final ControllerSummaryPort controllerSummaries;

	public RegionApplicationService(
			FluidSolverBackend solverBackend,
			RegionRepository regions,
			RegionCheckpointPort checkpoints,
			ControllerSummaryPort controllerSummaries
	) {
		this.solverBackend = Objects.requireNonNull(solverBackend, "solverBackend");
		this.regions = Objects.requireNonNull(regions, "regions");
		this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints");
		this.controllerSummaries = Objects.requireNonNull(controllerSummaries, "controllerSummaries");
	}

	public MotionStepResult stepMotion(MotionStepCommand command) {
		Objects.requireNonNull(command, "command");
		RegionRepository.Capture capture = regions.captureForStep(command.regionId());
		RegionStepStatus unavailable = captureStatus(capture);
		if (unavailable != null) return MotionStepResult.rejected(unavailable);
		if (capture.solverGeneration() != command.expectedSolverGeneration()) {
			return MotionStepResult.rejected(RegionStepStatus.STALE_GENERATION);
		}
		FluidStepCandidate candidate = solverBackend.evaluate(new FluidStepRequest(
				capture.solverGeneration(), capture.state(), command.movingBodies(),
				command.phaseChangeEnabled(), command.maximumPhaseOperations(), command.mechanicalAuditEnabled()));
		RegionStepStatus invalid = validateCandidate(capture, candidate.backendId(), candidate.baseGeneration(),
				candidate.candidateGeneration(), candidate.state().layout());
		if (invalid != null) return MotionStepResult.rejected(invalid);
		RegionStepStatus adoption = regions.adoptMotion(command.regionId(), candidate, solverBackend.id());
		return adoption == RegionStepStatus.ACCEPTED
				? MotionStepResult.accepted(candidate) : MotionStepResult.rejected(adoption);
	}

	public ThermalStepResult stepThermal(ThermalStepCommand command) {
		Objects.requireNonNull(command, "command");
		RegionRepository.Capture capture = regions.captureForStep(command.regionId());
		RegionStepStatus unavailable = captureStatus(capture);
		if (unavailable != null) return ThermalStepResult.rejected(unavailable);
		if (capture.solverGeneration() != command.expectedSolverGeneration()) {
			return ThermalStepResult.rejected(RegionStepStatus.STALE_GENERATION);
		}
		FluidThermalStepCandidate candidate = solverBackend.evaluateThermal(new FluidThermalStepRequest(
				capture.solverGeneration(), capture.state(), command.phaseChangeEnabled(),
				command.maximumPhaseOperations()));
		RegionStepStatus invalid = validateCandidate(capture, candidate.backendId(), candidate.baseGeneration(),
				candidate.candidateGeneration(), candidate.state().layout());
		if (invalid != null) return ThermalStepResult.rejected(invalid);
		RegionStepStatus adoption = regions.adoptThermal(command.regionId(), candidate, solverBackend.id());
		return adoption == RegionStepStatus.ACCEPTED
				? ThermalStepResult.accepted(candidate) : ThermalStepResult.rejected(adoption);
	}

	public CheckpointRegionResult checkpoint(CheckpointRegionCommand command) {
		Objects.requireNonNull(command, "command");
		if (command.containerWalPending()) {
			return new CheckpointRegionResult(CheckpointRegionResult.Status.PENDING_CONTAINER_WAL,
					command.expectedCheckpointGeneration(), null);
		}
		RegionCheckpointPort.Attempt attempt = checkpoints.checkpoint(
				command.regionId(), command.expectedCheckpointGeneration());
		return new CheckpointRegionResult(attempt.status(), attempt.checkpointGeneration(), attempt.diagnostic());
	}

	public ControllerSummaryResult controllerSummary(ControllerSummaryCommand command) {
		Objects.requireNonNull(command, "command");
		return controllerSummaries.read(command.regionId()).map(view -> new ControllerSummaryResult(
				ControllerSummaryResult.Status.OK,
				new ControllerSummary(command.summaryGeneration(), view.regionId(), view.dimensionId(),
						view.profileFingerprint(), view.checkpointGeneration(), view.networkGeneration(),
						view.footprint(), view.selected(), view.operatorIntent(), view.motionState(), view.thermalState(),
						view.availability(), view.usage(), view.inventory(), view.gates(), view.diagnostics())))
				.orElseGet(() -> new ControllerSummaryResult(ControllerSummaryResult.Status.REGION_NOT_FOUND, null));
	}

	private RegionStepStatus validateCandidate(
			RegionRepository.Capture capture, String backendId, long baseGeneration,
			long candidateGeneration, cc.ecisr.mmm.fluid.FluidDomainState.Layout layout
	) {
		if (!solverBackend.id().equals(backendId)) return RegionStepStatus.BACKEND_MISMATCH;
		if (baseGeneration != capture.solverGeneration()
				|| candidateGeneration != Math.addExact(capture.solverGeneration(), 1L)) {
			return RegionStepStatus.STALE_GENERATION;
		}
		return layout.equals(capture.state().layout()) ? null : RegionStepStatus.LAYOUT_MISMATCH;
	}

	private static RegionStepStatus captureStatus(RegionRepository.Capture capture) {
		return switch (capture.status()) {
			case READY -> null;
			case REGION_NOT_FOUND -> RegionStepStatus.REGION_NOT_FOUND;
			case REGION_NOT_LOADED -> RegionStepStatus.REGION_NOT_LOADED;
		};
	}
}

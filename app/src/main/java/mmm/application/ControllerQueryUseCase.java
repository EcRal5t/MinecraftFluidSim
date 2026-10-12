package mmm.application;

import mmm.application.command.ControllerSummaryCommand;
import mmm.application.port.ControllerSummaryPort;
import mmm.application.result.ControllerSummaryResult;
import mmm.controller.ControllerConsoleSessions;
import mmm.controller.ControllerSummary;

import java.util.Objects;
import java.util.UUID;

/** Loader-neutral bounded controller reads and their one-use viewer authorization tokens. */
public final class ControllerQueryUseCase {
	private final ControllerSummaryPort summaries;
	private final ControllerConsoleSessions sessions;

	public ControllerQueryUseCase(ControllerSummaryPort summaries) {
		this.summaries = Objects.requireNonNull(summaries, "summaries");
		this.sessions = new ControllerConsoleSessions();
	}

	public ControllerSummaryResult query(ControllerSummaryCommand command) {
		Objects.requireNonNull(command, "command");
		ControllerSummaryPort.View view = summaries.read(command.regionId()).orElse(null);
		if (view == null) {
			return new ControllerSummaryResult(ControllerSummaryResult.Status.REGION_NOT_FOUND, null);
		}
		if (!view.regionId().equals(command.regionId())) {
			throw new IllegalStateException("Controller summary port returned another region identity");
		}
		ControllerSummary validated = project(1L, view);
		long generation = sessions.issue(command.viewerId(), command.regionId());
		return new ControllerSummaryResult(ControllerSummaryResult.Status.OK,
				generation == 1L ? validated : project(generation, view));
	}

	private static ControllerSummary project(long generation, ControllerSummaryPort.View view) {
		return new ControllerSummary(generation, view.regionId(), view.dimensionId(), view.profileFingerprint(),
				view.checkpointGeneration(), view.networkGeneration(), view.footprint(), view.selected(),
				view.operatorIntent(), view.motionState(), view.thermalState(), view.availability(), view.usage(),
				view.inventory(), view.gates(), view.diagnostics());
	}

	/** Consumes exactly the latest summary token for this viewer/region pair. */
	public boolean consumeActionToken(UUID viewerId, UUID regionId, long summaryGeneration) {
		return sessions.consume(viewerId, regionId, summaryGeneration);
	}

	public void forgetViewer(UUID viewerId) {
		sessions.forget(viewerId);
	}
}

package mmm.application;

import mmm.controller.LifecycleOperationPreview;
import mmm.controller.LifecycleOperationSessions;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Loader-neutral run-intent policy and destructive lifecycle preview authorization. */
public final class RegionLifecycleUseCase {
	private static final long PREVIEW_LIFETIME_TICKS = 200L;

	public enum RunIntentDecision {
		ACCEPTED,
		PERMISSION_DENIED,
		WRITE_NOT_READY,
		UNCHANGED
	}

	public enum CommitDecision { AUTHORIZED, TOKEN_INVALID, BLOCKED }

	public record RunIntentFacts(
			boolean pauseRequested,
			boolean permissionGranted,
			boolean writesReady,
			boolean currentlyPaused
	) { }

	public record PreviewFacts(
			LifecycleOperationSessions.Context context,
			LifecycleOperationPreview.Removal removal,
			LifecycleOperationPreview.GasState gasState,
			int ownershipBlocks,
			List<LifecycleOperationPreview.Blocker> blockers
	) {
		public PreviewFacts {
			Objects.requireNonNull(context, "context");
			Objects.requireNonNull(removal, "removal");
			Objects.requireNonNull(gasState, "gasState");
			if (ownershipBlocks < 0 || blockers == null
					|| blockers.size() > LifecycleOperationPreview.MAX_BLOCKERS
					|| blockers.stream().anyMatch(Objects::isNull)) {
				throw new IllegalArgumentException("Lifecycle preview facts are invalid");
			}
			blockers = List.copyOf(blockers);
		}
	}

	public record CommitAuthorization(
			CommitDecision decision, List<LifecycleOperationPreview.Blocker> blockers
	) {
		public CommitAuthorization {
			Objects.requireNonNull(decision, "decision");
			if (blockers == null || (decision == CommitDecision.BLOCKED) != !blockers.isEmpty()) {
				throw new IllegalArgumentException("Lifecycle commit decision/blockers disagree");
			}
			blockers = List.copyOf(blockers);
		}
	}

	private final LifecycleOperationSessions sessions = new LifecycleOperationSessions();

	public RunIntentDecision decideRunIntent(RunIntentFacts facts) {
		Objects.requireNonNull(facts, "facts");
		if (!facts.permissionGranted()) return RunIntentDecision.PERMISSION_DENIED;
		if (!facts.writesReady()) return RunIntentDecision.WRITE_NOT_READY;
		return facts.currentlyPaused() == facts.pauseRequested()
				? RunIntentDecision.UNCHANGED : RunIntentDecision.ACCEPTED;
	}

	public LifecycleOperationPreview preview(PreviewFacts facts) {
		Objects.requireNonNull(facts, "facts");
		return sessions.issue(facts.context(), Math.addExact(facts.context().serverTick(), PREVIEW_LIFETIME_TICKS),
				facts.removal(), facts.gasState(), facts.ownershipBlocks(), facts.blockers());
	}

	/** Consumes or invalidates the owner token before returning current blockers. */
	public CommitAuthorization authorizeCommit(UUID nonce, PreviewFacts currentFacts) {
		Objects.requireNonNull(currentFacts, "currentFacts");
		if (!sessions.consume(nonce, currentFacts.context())) {
			return new CommitAuthorization(CommitDecision.TOKEN_INVALID, List.of());
		}
		if (!currentFacts.blockers().isEmpty()) {
			return new CommitAuthorization(CommitDecision.BLOCKED, currentFacts.blockers());
		}
		return new CommitAuthorization(CommitDecision.AUTHORIZED, List.of());
	}

	public void forgetViewer(UUID viewerId) {
		sessions.forget(viewerId);
	}
}

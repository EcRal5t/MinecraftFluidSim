package cc.ecisr.mmm.simulation.persistence;

import java.io.IOException;

/** Deterministic admission state machine around world-capability discovery, recovery and first publication. */
public final class WorldCapabilityStartup {
	private WorldCapabilityStartup() { }

	public static Result start(WorldCapabilityBundleStore store, DefinitionSource definitionSource,
			boolean policyAllowsAdmission) {
		if (store == null || definitionSource == null) {
			throw new IllegalArgumentException("World capability startup inputs are incomplete");
		}
		WorldCapabilityBundleStore.Presence presence;
		try {
			presence = store.inspect();
		} catch (IOException invalidLayout) {
			return Result.locked(invalidLayout);
		}

		if (presence == WorldCapabilityBundleStore.Presence.COMMITTED) {
			try {
				WorldCapabilityBundleStore.Bundle bundle = store.readCommitted();
				return admitted(bundle, compareDefinitions(bundle, definitionSource), policyAllowsAdmission);
			} catch (IOException | RuntimeException invalidBundle) {
				return Result.locked(invalidBundle);
			}
		}
		if (presence == WorldCapabilityBundleStore.Presence.COMMIT_CANDIDATE) {
			try {
				WorldCapabilityBundleStore.Bundle bundle = store.recoverCommitCandidate();
				return admitted(bundle, compareDefinitions(bundle, definitionSource), policyAllowsAdmission);
			} catch (IOException | RuntimeException invalidCandidate) {
				return Result.locked(invalidCandidate);
			}
		}

		WorldCapabilityBundleStore.Definitions definitions;
		try {
			definitions = definitionSource.load();
		} catch (IOException | RuntimeException unavailableDefinitions) {
			if (presence == WorldCapabilityBundleStore.Presence.STAGING) {
				return Result.locked(unavailableDefinitions);
			}
			return Result.uninitialized(unavailableDefinitions);
		}
		try {
			WorldCapabilityBundleStore.OpenResult opened = store.openOrPublish(definitions);
			return admitted(opened.bundle(), DefinitionComparison.available(opened.definitionMismatch()),
					policyAllowsAdmission);
		} catch (IOException | RuntimeException publicationFailure) {
			if (presence == WorldCapabilityBundleStore.Presence.EMPTY) {
				return Result.uninitialized(publicationFailure);
			}
			return Result.locked(publicationFailure);
		}
	}

	private static Result admitted(WorldCapabilityBundleStore.Bundle bundle, DefinitionComparison comparison,
			boolean policyAllowsAdmission) {
		State state = policyAllowsAdmission ? State.READY : State.POLICY_RESTRICTED;
		return new Result(state, bundle, comparison.mismatch(), comparison.issue());
	}

	private static DefinitionComparison compareDefinitions(WorldCapabilityBundleStore.Bundle bundle,
			DefinitionSource source) {
		try {
			return DefinitionComparison.available(!source.load().matches(bundle));
		} catch (IOException | RuntimeException unavailable) {
			return new DefinitionComparison(false, message(unavailable));
		}
	}

	private static String message(Throwable failure) {
		String message = failure.getMessage();
		return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
	}

	public enum State {
		UNINITIALIZED,
		READY,
		LOCKED_PROFILE,
		POLICY_RESTRICTED
	}

	public record Result(
			State state,
			WorldCapabilityBundleStore.Bundle bundle,
			boolean definitionMismatch,
			String issue
	) {
		public Result {
			if (state == null || issue == null) throw new IllegalArgumentException("World capability startup result is incomplete");
			boolean admitted = state == State.READY || state == State.POLICY_RESTRICTED;
			if (admitted != (bundle != null)) {
				throw new IllegalArgumentException("World capability startup state and bundle disagree");
			}
			if (!admitted && definitionMismatch) {
				throw new IllegalArgumentException("Unadmitted startup cannot report a definition mismatch");
			}
		}

		private static Result locked(Throwable failure) {
			return new Result(State.LOCKED_PROFILE, null, false, message(failure));
		}

		private static Result uninitialized(Throwable failure) {
			return new Result(State.UNINITIALIZED, null, false, message(failure));
		}
	}

	@FunctionalInterface
	public interface DefinitionSource {
		WorldCapabilityBundleStore.Definitions load() throws IOException;
	}

	private record DefinitionComparison(boolean mismatch, String issue) {
		private DefinitionComparison {
			if (issue == null) throw new IllegalArgumentException("Definition comparison issue is missing");
		}

		private static DefinitionComparison available(boolean mismatch) {
			return new DefinitionComparison(mismatch, "");
		}
	}
}

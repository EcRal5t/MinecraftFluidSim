package cc.ecisr.mmm.simulation.persistence;

import java.io.IOException;

/**
 * Deterministic admission state machine around world-capability discovery, recovery and first publication.
 *
 * <p>Recovery never consults the current definition source: finishing an interrupted publication must not
 * depend on the datapack still decoding. A committed bundle is authoritative even when the datapack
 * disagrees, and a disagreement is reported rather than repaired.
 */
public final class WorldCapabilityStartup {
	private WorldCapabilityStartup() { }

	public static Result start(
			WorldCapabilityBundleStore store, DefinitionSource definitionSource, boolean policyAllowsAdmission
	) {
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
				return admitted(store.readCommitted(), definitionSource, policyAllowsAdmission);
			} catch (IOException | RuntimeException invalidBundle) {
				return Result.locked(invalidBundle);
			}
		}
		if (presence == WorldCapabilityBundleStore.Presence.COMMIT_CANDIDATE) {
			try {
				return admitted(store.recoverCommitCandidate(), definitionSource, policyAllowsAdmission);
			} catch (IOException | RuntimeException invalidCandidate) {
				return Result.locked(invalidCandidate);
			}
		}

		WorldCapabilityBundleStore.Definitions definitions;
		try {
			definitions = definitionSource.load();
		} catch (IOException | RuntimeException unavailableDefinitions) {
			return presence == WorldCapabilityBundleStore.Presence.STAGING
					? Result.locked(unavailableDefinitions)
					: Result.uninitialized(unavailableDefinitions);
		}
		try {
			WorldCapabilityBundleStore.OpenResult opened = store.openOrPublish(definitions);
			return new Result(policyAllowsAdmission ? State.READY : State.POLICY_RESTRICTED,
					opened.bundle(), opened.definitionMismatch(), "");
		} catch (IOException | RuntimeException publicationFailure) {
			return presence == WorldCapabilityBundleStore.Presence.EMPTY
					? Result.uninitialized(publicationFailure)
					: Result.locked(publicationFailure);
		}
	}

	private static Result admitted(
			WorldCapabilityBundleStore.Bundle bundle, DefinitionSource source, boolean policyAllowsAdmission
	) {
		DefinitionComparison comparison = compareDefinitions(bundle, source);
		return new Result(policyAllowsAdmission ? State.READY : State.POLICY_RESTRICTED,
				bundle, comparison.mismatch(), comparison.issue());
	}

	private static DefinitionComparison compareDefinitions(
			WorldCapabilityBundleStore.Bundle bundle, DefinitionSource source
	) {
		try {
			return new DefinitionComparison(!source.load().matches(bundle), "");
		} catch (IOException | RuntimeException unavailable) {
			return new DefinitionComparison(false, message(unavailable));
		}
	}

	private static String message(Throwable failure) {
		String message = failure.getMessage();
		return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
	}

	public enum State {
		/** No bundle exists and no publication could be proven. */
		UNINITIALIZED,
		READY,
		/** A bundle or authority exists but cannot be proven admissible; nothing is reinterpreted. */
		LOCKED_PROFILE,
		/** The bundle is admissible but world policy withholds admission. */
		POLICY_RESTRICTED
	}

	public record Result(
			State state,
			WorldCapabilityBundleStore.Bundle bundle,
			boolean definitionMismatch,
			String issue
	) {
		public Result {
			if (state == null || issue == null) {
				throw new IllegalArgumentException("World capability startup result is incomplete");
			}
			boolean admitted = state == State.READY || state == State.POLICY_RESTRICTED;
			if (admitted != (bundle != null)) {
				throw new IllegalArgumentException("World capability startup state and bundle disagree");
			}
			if (!admitted && definitionMismatch) {
				throw new IllegalArgumentException("Unadmitted startup cannot report a definition mismatch");
			}
		}

		public boolean admitted() {
			return state == State.READY || state == State.POLICY_RESTRICTED;
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

	private record DefinitionComparison(boolean mismatch, String issue) { }
}

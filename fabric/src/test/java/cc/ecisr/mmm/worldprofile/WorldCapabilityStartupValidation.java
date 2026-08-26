package cc.ecisr.mmm.worldprofile;

import cc.ecisr.mmm.fluid.MaterialRegistry;
import cc.ecisr.mmm.simulation.persistence.WorldCapabilityBundleManifest;
import cc.ecisr.mmm.simulation.persistence.WorldCapabilityBundleStore;
import cc.ecisr.mmm.simulation.persistence.WorldCapabilityStartup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/** Admission states stay distinct while committed snapshots remain independent of current definitions. */
public final class WorldCapabilityStartupValidation {
	private static final WorldCapabilityBundleStore.QuarantineAdmission UNBOUNDED_QUARANTINE =
			new WorldCapabilityBundleStore.QuarantineAdmission(Long.MAX_VALUE, Long.MAX_VALUE);

	private WorldCapabilityStartupValidation() { }

	public static void run() {
		missingFirstDefinitionsStayUninitialized();
		committedBundleStaysReadyWithoutCurrentDefinitions();
		policyAndDefinitionMismatchDoNotChangeBundleTruth();
		completeSlotRecoversWithoutCurrentDefinitions();
		knownStagingRestartsButCorruptionLocks();
		committedCorruptionLocks();
	}

	private static void missingFirstDefinitionsStayUninitialized() {
		Path root = null;
		try {
			root = Files.createTempDirectory("mmm-world-capability-uninitialized-");
			WorldCapabilityBundleStore store = store(root);
			WorldCapabilityStartup.Result result = WorldCapabilityStartup.start(store,
					() -> { throw new IOException("definition source unavailable"); }, true);
			require(result.state() == WorldCapabilityStartup.State.UNINITIALIZED && result.bundle() == null,
					"missing first definitions did not remain uninitialized");
			require(store.inspect() == WorldCapabilityBundleStore.Presence.EMPTY,
					"uninitialized startup created persistence authority");
		} catch (IOException failure) {
			throw new AssertionError("uninitialized capability startup validation failed", failure);
		} finally {
			deleteTree(root);
		}
	}

	private static void committedBundleStaysReadyWithoutCurrentDefinitions() {
		Path root = null;
		try {
			root = Files.createTempDirectory("mmm-world-capability-authoritative-startup-");
			WorldCapabilityBundleStore store = store(root);
			WorldCapabilityStartup.Result first = WorldCapabilityStartup.start(store,
					WorldCapabilityBundleStoreValidation::definitions, true);
			require(first.state() == WorldCapabilityStartup.State.READY && first.bundle() != null,
					"valid first publication did not enter READY");
			WorldCapabilityStartup.Result restarted = WorldCapabilityStartup.start(store,
					() -> { throw new IOException("current definitions are unavailable"); }, true);
			require(restarted.state() == WorldCapabilityStartup.State.READY
					&& restarted.bundle().profile().equals(first.bundle().profile())
					&& !restarted.issue().isEmpty(),
					"current definition failure invalidated an authoritative bundle");
		} catch (IOException failure) {
			throw new AssertionError("authoritative capability startup validation failed", failure);
		} finally {
			deleteTree(root);
		}
	}

	private static void policyAndDefinitionMismatchDoNotChangeBundleTruth() {
		Path root = null;
		try {
			root = Files.createTempDirectory("mmm-world-capability-policy-");
			WorldCapabilityBundleStore store = store(root);
			WorldCapabilityBundleStore.Definitions original = WorldCapabilityBundleStoreValidation.definitions();
			WorldCapabilityStartup.Result ready = WorldCapabilityStartup.start(store, () -> original, true);
			MaterialRegistry changedMaterial = new MaterialRegistry(original.material().entries(), List.of(
					new MaterialRegistry.LiquidPairTension("mmm:aqueous", "mmm:oil", Math.nextUp(0.0))));
			WorldCapabilityBundleStore.Definitions changed = new WorldCapabilityBundleStore.Definitions(
					changedMaterial, original.chemical(), original.granular(), original.gas(), original.thermal());
			WorldCapabilityStartup.Result restricted = WorldCapabilityStartup.start(store, () -> changed, false);
			require(restricted.state() == WorldCapabilityStartup.State.POLICY_RESTRICTED
					&& restricted.definitionMismatch()
					&& restricted.bundle().profile().equals(ready.bundle().profile()),
					"policy or current definitions changed committed profile truth");
		} catch (IOException failure) {
			throw new AssertionError("capability startup policy validation failed", failure);
		} finally {
			deleteTree(root);
		}
	}

	private static void completeSlotRecoversWithoutCurrentDefinitions() {
		Path root = null;
		try {
			root = Files.createTempDirectory("mmm-world-capability-candidate-startup-");
			WorldCapabilityBundleStore store = store(root);
			WorldCapabilityBundleStore.Definitions definitions = WorldCapabilityBundleStoreValidation.definitions();
			expectIo(() -> store.openOrPublish(definitions, boundary -> {
				if (boundary.phase() == WorldCapabilityBundleStore.PublicationPhase.SLOT_PUBLISHED) {
					throw new IOException("injected publication interruption");
				}
			}), "slot publication boundary did not interrupt startup fixture");
			require(store.inspect() == WorldCapabilityBundleStore.Presence.COMMIT_CANDIDATE,
					"complete uncommitted slot was not classified as a commit candidate");
			WorldCapabilityStartup.Result recovered = WorldCapabilityStartup.start(store,
					() -> { throw new IOException("current definitions are unavailable"); }, true);
			require(recovered.state() == WorldCapabilityStartup.State.READY
					&& recovered.bundle().profile().equals(definitions.profile())
					&& store.inspect() == WorldCapabilityBundleStore.Presence.COMMITTED,
					"complete slot did not recover HEAD independently of current definitions");
		} catch (IOException failure) {
			throw new AssertionError("capability commit-candidate startup validation failed", failure);
		} finally {
			deleteTree(root);
		}
	}

	private static void knownStagingRestartsButCorruptionLocks() {
		Path recoverableRoot = null;
		Path corruptRoot = null;
		try {
			WorldCapabilityBundleStore.Definitions definitions = WorldCapabilityBundleStoreValidation.definitions();
			recoverableRoot = Files.createTempDirectory("mmm-world-capability-staging-startup-");
			WorldCapabilityBundleStore recoverable = store(recoverableRoot);
			expectIo(() -> recoverable.openOrPublish(definitions, boundary -> {
				if (boundary.phase() == WorldCapabilityBundleStore.PublicationPhase.STAGING_MANIFEST_WRITTEN) {
					throw new IOException("injected publication interruption");
				}
			}), "staging manifest boundary did not interrupt startup fixture");
			WorldCapabilityStartup.Result restarted = WorldCapabilityStartup.start(recoverable, () -> definitions, true);
			require(restarted.state() == WorldCapabilityStartup.State.READY,
					"known valid staging did not quarantine and restart publication");

			corruptRoot = Files.createTempDirectory("mmm-world-capability-corrupt-startup-");
			WorldCapabilityBundleStore corrupt = store(corruptRoot);
			expectIo(() -> corrupt.openOrPublish(definitions, boundary -> {
				if (boundary.phase() == WorldCapabilityBundleStore.PublicationPhase.MEMBER_WRITTEN) {
					throw new IOException("injected publication interruption");
				}
			}), "staging member boundary did not interrupt startup fixture");
			Path member = corruptRoot.resolve("mmm").resolve("world-capabilities").resolve("slot-a.tmp")
					.resolve(WorldCapabilityBundleManifest.REQUIRED_FILES.getFirst());
			byte[] bytes = Files.readAllBytes(member);
			bytes[bytes.length - 1] ^= 1;
			Files.write(member, bytes);
			WorldCapabilityStartup.Result locked = WorldCapabilityStartup.start(corrupt, () -> definitions, true);
			require(locked.state() == WorldCapabilityStartup.State.LOCKED_PROFILE
					&& Files.exists(member), "corrupt staging was not preserved in LOCKED_PROFILE");
		} catch (IOException failure) {
			throw new AssertionError("capability staging startup validation failed", failure);
		} finally {
			deleteTree(recoverableRoot);
			deleteTree(corruptRoot);
		}
	}

	private static void committedCorruptionLocks() {
		Path root = null;
		try {
			root = Files.createTempDirectory("mmm-world-capability-locked-startup-");
			WorldCapabilityBundleStore store = store(root);
			WorldCapabilityBundleStore.Definitions definitions = WorldCapabilityBundleStoreValidation.definitions();
			WorldCapabilityStartup.start(store, () -> definitions, true);
			Path member = root.resolve("mmm").resolve("world-capabilities").resolve("slot-a")
					.resolve(WorldCapabilityBundleManifest.REQUIRED_FILES.getFirst());
			byte[] bytes = Files.readAllBytes(member);
			bytes[bytes.length - 1] ^= 1;
			Files.write(member, bytes);
			WorldCapabilityStartup.Result locked = WorldCapabilityStartup.start(store, () -> definitions, true);
			require(locked.state() == WorldCapabilityStartup.State.LOCKED_PROFILE
					&& locked.bundle() == null && Files.exists(member),
					"corrupt committed bundle did not remain preserved and locked");
		} catch (IOException failure) {
			throw new AssertionError("locked capability startup validation failed", failure);
		} finally {
			deleteTree(root);
		}
	}

	private static WorldCapabilityBundleStore store(Path root) {
		return new WorldCapabilityBundleStore(root, UNBOUNDED_QUARANTINE);
	}

	private static void expectIo(IoAction action, String message) {
		try { action.run(); }
		catch (IOException expected) { return; }
		throw new AssertionError(message);
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}

	private static void deleteTree(Path root) {
		if (root == null) return;
		try (var paths = Files.walk(root)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
		} catch (IOException cleanup) {
			throw new AssertionError("world capability startup validation cleanup failed", cleanup);
		}
	}

	private interface IoAction {
		void run() throws IOException;
	}
}

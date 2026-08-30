package cc.ecisr.mmm.simulation.persistence;

import cc.ecisr.mmm.simulation.ProductionRegistries;
import cc.ecisr.mmm.simulation.WorldStartupCoordinator;
import cc.ecisr.mmm.worldprofile.WorldCapability;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Admission classification for world capability startup: which states admit, which lock, and that
 * recovery never depends on the current datapack still decoding.
 */
public final class WorldCapabilityStartupValidation {
	private static final WorldCapabilityBundleStore.QuarantineAdmission ADMISSION =
			new WorldCapabilityBundleStore.QuarantineAdmission(64L, 64L * 1024L * 1024L);

	private static ProductionRegistries.Set registries;
	private static WorldCapabilityBundleStore.Definitions full;

	private WorldCapabilityStartupValidation() { }

	public static void main(String[] args) throws Exception {
		registries = ProductionRegistries.load();
		full = definitions(EnumSet.allOf(WorldCapability.class));
		emptyWorldPublishesAndAdmits();
		committedWorldAdmitsWithoutRepublishing();
		committedWorldReportsCapabilityDriftWithoutRepair();
		recoveryDoesNotConsultTheDefinitionSource();
		unreadableCommittedBundleLocks();
		authorityWithoutACommittedBundleLocks();
		unknownMmmEntriesLock();
		stalePerKindRegistryDirectoryLocks();
		policyCanWithholdAdmission();
		compositionCoordinatorPreservesStartupMatrix();
		System.out.println("World capability startup validation passed");
	}

	private static void emptyWorldPublishesAndAdmits() throws Exception {
		Path root = Files.createTempDirectory(Path.of("."), "mmm-startup-empty-");
		WorldCapabilityBundleStore store = new WorldCapabilityBundleStore(root, ADMISSION);
		require(store.inspect() == WorldCapabilityBundleStore.Presence.EMPTY,
				"a fresh world root must inspect as EMPTY");
		WorldCapabilityStartup.Result result = WorldCapabilityStartup.start(store, () -> full, true);
		require(result.state() == WorldCapabilityStartup.State.READY && result.admitted(),
				"an empty world must publish and admit: " + result);
		require(!result.definitionMismatch(), "first publication must not report a drift: " + result);
		require(store.inspect() == WorldCapabilityBundleStore.Presence.COMMITTED,
				"publishing must leave the root COMMITTED");
	}

	private static void committedWorldAdmitsWithoutRepublishing() throws Exception {
		Path root = Files.createTempDirectory(Path.of("."), "mmm-startup-committed-");
		WorldCapabilityBundleStore store = new WorldCapabilityBundleStore(root, ADMISSION);
		WorldCapabilityStartup.start(store, () -> full, true);
		WorldCapabilityStartup.Result second = WorldCapabilityStartup.start(store, () -> full, true);
		require(second.state() == WorldCapabilityStartup.State.READY && !second.definitionMismatch(),
				"a committed world must admit again without drift: " + second);
		require(second.bundle().profile().equals(full.profile()),
				"a committed world must return its committed profile");
	}

	private static void committedWorldReportsCapabilityDriftWithoutRepair() throws Exception {
		Path root = Files.createTempDirectory(Path.of("."), "mmm-startup-drift-");
		WorldCapabilityBundleStore store = new WorldCapabilityBundleStore(root, ADMISSION);
		WorldCapabilityStartup.start(store, () -> full, true);
		EnumSet<WorldCapability> reduced = EnumSet.allOf(WorldCapability.class);
		reduced.remove(WorldCapability.PHASECHANGE);
		WorldCapabilityBundleStore.Definitions drifted = definitions(reduced);
		WorldCapabilityStartup.Result result = WorldCapabilityStartup.start(store, () -> drifted, true);
		require(result.state() == WorldCapabilityStartup.State.READY,
				"a drifted datapack must not lock an admitted world: " + result);
		require(result.definitionMismatch(), "a drifted capability set must be reported: " + result);
		require(result.bundle().profile().equals(full.profile()),
				"a drifted datapack must not rewrite the committed profile");
	}

	/** Finishing an interrupted publication must not require the datapack to still decode. */
	private static void recoveryDoesNotConsultTheDefinitionSource() throws Exception {
		Path root = Files.createTempDirectory(Path.of("."), "mmm-startup-recover-");
		WorldCapabilityBundleStore store = new WorldCapabilityBundleStore(root, ADMISSION);
		Path bundleRoot = root.resolve("mmm").resolve("world-capabilities");
		try {
			store.openOrPublish(full, boundary -> {
				if (boundary.phase() == WorldCapabilityBundleStore.PublicationPhase.HEAD_TEMPORARY_VERIFIED) {
					throw new IOException("Injected failure before HEAD publication");
				}
			});
			throw new AssertionError("the fault injector did not abort");
		} catch (IOException aborted) {
			require(true, "");
		}
		require(store.inspect() == WorldCapabilityBundleStore.Presence.COMMIT_CANDIDATE,
				"an interrupted publication must inspect as a commit candidate");
		WorldCapabilityStartup.Result recovered = WorldCapabilityStartup.start(store, () -> {
			throw new IOException("Datapack definitions are unavailable");
		}, true);
		require(recovered.state() == WorldCapabilityStartup.State.READY,
				"recovery must not depend on the definition source: " + recovered);
		require(recovered.bundle().profile().equals(full.profile()),
				"recovered bundle must keep the published profile");
		require(Files.isRegularFile(bundleRoot.resolve("HEAD.nbt")), "recovery must publish HEAD");
	}

	private static void unreadableCommittedBundleLocks() throws Exception {
		Path root = Files.createTempDirectory(Path.of("."), "mmm-startup-corrupt-");
		WorldCapabilityBundleStore store = new WorldCapabilityBundleStore(root, ADMISSION);
		WorldCapabilityStartup.start(store, () -> full, true);
		Path member = root.resolve("mmm").resolve("world-capabilities").resolve("slot-a")
				.resolve("thermal-registry.nbt");
		byte[] original = Files.readAllBytes(member);
		original[original.length - 1] ^= 0x5A;
		Files.write(member, original);
		WorldCapabilityStartup.Result result = WorldCapabilityStartup.start(store, () -> full, true);
		require(result.state() == WorldCapabilityStartup.State.LOCKED_PROFILE && !result.admitted(),
				"an unreadable committed bundle must lock: " + result);
		require(!result.issue().isBlank(), "a locked startup must report why: " + result);
	}

	/** No migration path: authority on disk without a committed bundle is proven, not adopted. */
	private static void authorityWithoutACommittedBundleLocks() throws Exception {
		Path root = Files.createTempDirectory(Path.of("."), "mmm-startup-authority-");
		Files.createDirectories(root.resolve("mmm").resolve("region-state"));
		WorldCapabilityBundleStore store = new WorldCapabilityBundleStore(root, ADMISSION);
		WorldCapabilityStartup.Result result = WorldCapabilityStartup.start(store, () -> full, true);
		require(result.state() == WorldCapabilityStartup.State.LOCKED_PROFILE,
				"existing authority without a committed bundle must lock: " + result);
		require(Files.isDirectory(root.resolve("mmm").resolve("region-state")),
				"a locked world must be left byte for byte");
		require(!Files.exists(root.resolve("mmm").resolve("world-capabilities")),
				"a locked world must not be given a new bundle");
	}

	private static void unknownMmmEntriesLock() throws Exception {
		Path root = Files.createTempDirectory(Path.of("."), "mmm-startup-unknown-");
		Files.createDirectories(root.resolve("mmm").resolve("something-else"));
		WorldCapabilityBundleStore store = new WorldCapabilityBundleStore(root, ADMISSION);
		WorldCapabilityStartup.Result result = WorldCapabilityStartup.start(store, () -> full, true);
		require(result.state() == WorldCapabilityStartup.State.LOCKED_PROFILE,
				"an unknown persistence root must lock: " + result);
	}

	/**
	 * A per-kind registry directory is what earlier builds published. The bundle is now the only registry
	 * root, so it is an unknown entry: the world locks instead of having its registries adopted, merged
	 * or migrated into a bundle.
	 */
	private static void stalePerKindRegistryDirectoryLocks() throws Exception {
		for (String kind : List.of("chemistry", "gas", "granular", "material", "thermal")) {
			Path root = Files.createTempDirectory(Path.of("."), "mmm-startup-stale-registry-");
			Path stale = root.resolve("mmm").resolve(kind);
			Files.createDirectories(stale);
			Files.writeString(stale.resolve("registry.nbt"), "unreadable-on-purpose");
			WorldCapabilityBundleStore store = new WorldCapabilityBundleStore(root, ADMISSION);
			WorldCapabilityStartup.Result result = WorldCapabilityStartup.start(store, () -> full, true);
			require(result.state() == WorldCapabilityStartup.State.LOCKED_PROFILE,
					"a stale " + kind + " registry directory must lock: " + result);
			require(Files.exists(stale.resolve("registry.nbt")),
					"a locked world must keep the stale " + kind + " directory byte for byte");
			require(!Files.exists(root.resolve("mmm").resolve("world-capabilities")),
					"a locked world must not be given a new bundle");
		}
	}

	/** Policy may withhold admission, but the bundle is still proven and returned. */
	private static void policyCanWithholdAdmission() throws Exception {
		Path root = Files.createTempDirectory(Path.of("."), "mmm-startup-policy-");
		WorldCapabilityBundleStore store = new WorldCapabilityBundleStore(root, ADMISSION);
		WorldCapabilityStartup.Result result = WorldCapabilityStartup.start(store, () -> full, false);
		require(result.state() == WorldCapabilityStartup.State.POLICY_RESTRICTED && result.admitted(),
				"withheld admission must be reported as POLICY_RESTRICTED: " + result);
		require(result.bundle() != null && result.bundle().profile().equals(full.profile()),
				"a policy-restricted startup must still expose the proven bundle");
	}

	/** The composition boundary preserves capability truth while adding typed policy and drift issues. */
	private static void compositionCoordinatorPreservesStartupMatrix() throws Exception {
		Path readyRoot = Files.createTempDirectory(Path.of("."), "mmm-coordinator-ready-");
		WorldStartupCoordinator.Result ready = WorldStartupCoordinator.start(readyRoot, () -> full);
		require(ready.state() == WorldStartupCoordinator.State.READY && ready.policyReadable()
				&& ready.profileProven() && ready.authoritativeWritesReady(),
				"composition startup did not expose a proven READY state: " + ready);

		EnumSet<WorldCapability> reduced = EnumSet.allOf(WorldCapability.class);
		reduced.remove(WorldCapability.PHASECHANGE);
		WorldStartupCoordinator.Result drift = WorldStartupCoordinator.start(readyRoot,
				() -> definitions(reduced));
		require(drift.state() == WorldStartupCoordinator.State.READY && drift.issues().stream().anyMatch(issue ->
				issue.code() == WorldStartupCoordinator.Code.DEFINITION_DRIFT && !issue.blocksCreation()),
				"definition drift was not retained as a non-blocking typed issue: " + drift);

		Path policyRoot = Files.createTempDirectory(Path.of("."), "mmm-coordinator-policy-");
		Path policy = policyRoot.resolve("mmm").resolve("server.properties");
		Files.createDirectories(policy.getParent());
		String invalidPolicy = "schema=not-supported\ncontrollersEnabled=true\n";
		Files.writeString(policy, invalidPolicy);
		WorldStartupCoordinator.Result unreadablePolicy = WorldStartupCoordinator.start(policyRoot, () -> full);
		require(unreadablePolicy.state() == WorldStartupCoordinator.State.POLICY_RESTRICTED
				&& !unreadablePolicy.policyReadable() && unreadablePolicy.profileProven()
				&& !unreadablePolicy.authoritativeWritesReady(),
				"unreadable policy did not preserve a proven-but-restricted bundle: " + unreadablePolicy);
		require(unreadablePolicy.issues().stream().anyMatch(issue ->
				issue.code() == WorldStartupCoordinator.Code.POLICY_UNREADABLE && issue.blocksCreation()),
				"unreadable policy lacked a blocking typed issue: " + unreadablePolicy);
		require(Files.readString(policy).equals(invalidPolicy), "startup overwrote the unreadable policy");

		Path unknownRoot = Files.createTempDirectory(Path.of("."), "mmm-coordinator-unknown-");
		Path unknown = unknownRoot.resolve("mmm").resolve("unknown-durable-root");
		Files.createDirectories(unknown);
		WorldStartupCoordinator.Result locked = WorldStartupCoordinator.start(unknownRoot, () -> full);
		require(locked.state() == WorldStartupCoordinator.State.LOCKED_PROFILE && !locked.profileProven()
				&& locked.issues().stream().anyMatch(WorldStartupCoordinator.Issue::blocksCreation),
				"unknown durable content did not fail closed at the composition boundary: " + locked);
		require(Files.isDirectory(unknown), "composition startup removed unknown durable evidence");
	}

	private static WorldCapabilityBundleStore.Definitions definitions(Set<WorldCapability> capabilities) {
		return new WorldCapabilityBundleStore.Definitions(capabilities, registries.material(),
				registries.chemical(), registries.granular(), registries.gas(), registries.thermal());
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}

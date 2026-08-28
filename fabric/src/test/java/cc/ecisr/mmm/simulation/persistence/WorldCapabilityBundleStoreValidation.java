package cc.ecisr.mmm.simulation.persistence;

import cc.ecisr.mmm.simulation.ProductionRegistries;
import cc.ecisr.mmm.worldprofile.WorldCapability;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * HEAD-last publication, strict bundle inventory and interrupted-publication behaviour of the world
 * capability bundle. Every publication boundary is faulted to prove that no crash can present a
 * half-written slot as the committed authority.
 */
public final class WorldCapabilityBundleStoreValidation {
	private static final WorldCapabilityBundleStore.QuarantineAdmission ADMISSION =
			new WorldCapabilityBundleStore.QuarantineAdmission(64L, 64L * 1024L * 1024L);
	private static final Set<Set<String>> STAGED_ROOTS = Set.<Set<String>>of(
			Set.of(), Set.of("slot-a.tmp"), Set.of("slot-a"), Set.of("slot-a", "HEAD.nbt.tmp"));

	private static ProductionRegistries.Set registries;
	private static WorldCapabilityBundleStore.Definitions full;

	private WorldCapabilityBundleStoreValidation() { }

	public static void main(String[] args) throws Exception {
		registries = ProductionRegistries.load();
		full = definitions(EnumSet.allOf(WorldCapability.class));
		firstPublicationIsAtomicAndReadsBack();
		republicationIsStableAndDetectsCapabilityDrift();
		tamperedMemberIsRejectedWithoutRepair();
		everyPublicationBoundaryLeavesEitherNothingOrACompleteBundle();
		everyInterruptedPublicationRecoversOnTheNextOpen();
		stagingWithoutIdentityIsRefusedAndPreserved();
		quarantineAdmissionBudgetIsEnforced();
		System.out.println("World capability bundle store validation passed");
	}

	private static void firstPublicationIsAtomicAndReadsBack() throws Exception {
		Path root = Files.createTempDirectory(Path.of("."), "mmm-bundle-publish-");
		WorldCapabilityBundleStore store = new WorldCapabilityBundleStore(root, ADMISSION);
		WorldCapabilityBundleStore.OpenResult first = store.openOrPublish(full);
		require(first.created() && !first.definitionMismatch() && !first.recovered(),
				"first bundle open must publish: " + first);

		WorldCapabilityBundleStore.Bundle bundle = store.readCommitted();
		require(bundle.profile().equals(full.profile()),
				"committed bundle profile must equal the published definitions");
		require(bundle.material().fingerprint().equals(registries.material().fingerprint())
						&& bundle.chemical().fingerprint().equals(registries.chemical().fingerprint())
						&& bundle.granular().fingerprint().equals(registries.granular().fingerprint())
						&& bundle.gas().fingerprint().equals(registries.gas().fingerprint())
						&& bundle.thermal().fingerprint().equals(registries.thermal().fingerprint()),
				"committed bundle must restore every registry fingerprint exactly");
		Path slot = root.resolve("mmm").resolve("world-capabilities").resolve("slot-a");
		for (String member : WorldCapabilityBundleManifest.REQUIRED_FILES) {
			require(Files.isRegularFile(slot.resolve(member)), "committed slot is missing member " + member);
		}
		require(Files.isRegularFile(root.resolve("mmm").resolve("world-capabilities").resolve("HEAD.nbt")),
				"committed bundle must publish HEAD");
	}

	private static void republicationIsStableAndDetectsCapabilityDrift() throws Exception {
		Path root = Files.createTempDirectory(Path.of("."), "mmm-bundle-stable-");
		WorldCapabilityBundleStore store = new WorldCapabilityBundleStore(root, ADMISSION);
		store.openOrPublish(full);
		WorldCapabilityBundleStore.OpenResult second = store.openOrPublish(full);
		require(!second.created() && !second.definitionMismatch() && !second.recovered(),
				"stable republication must not recreate or drift: " + second);

		EnumSet<WorldCapability> reduced = EnumSet.allOf(WorldCapability.class);
		reduced.remove(WorldCapability.PHASECHANGE);
		WorldCapabilityBundleStore.OpenResult drifted = store.openOrPublish(definitions(reduced));
		require(drifted.definitionMismatch() && !drifted.created(),
				"a changed capability set must report a mismatch instead of republishing: " + drifted);
		require(drifted.bundle().profile().equals(full.profile()),
				"a mismatching open must still return the committed authority");
	}

	/** Rewriting one member must not be repairable: the committed bundle is refused, not rewritten. */
	private static void tamperedMemberIsRejectedWithoutRepair() throws Exception {
		Path root = Files.createTempDirectory(Path.of("."), "mmm-bundle-tamper-");
		WorldCapabilityBundleStore store = new WorldCapabilityBundleStore(root, ADMISSION);
		store.openOrPublish(full);
		Path member = root.resolve("mmm").resolve("world-capabilities").resolve("slot-a")
				.resolve("material-registry.nbt");
		byte[] original = Files.readAllBytes(member);
		original[original.length - 1] ^= 0x5A;
		Files.write(member, original);
		requireRefused(() -> store.readCommitted(), "a tampered bundle member was accepted as committed");
		requireRefused(() -> store.openOrPublish(full), "a tampered bundle was silently repaired");
		require(Files.isRegularFile(member), "a refused bundle must be preserved byte for byte");
	}

	private static void everyPublicationBoundaryLeavesEitherNothingOrACompleteBundle() throws Exception {
		List<String> outcomes = new ArrayList<>();
		for (WorldCapabilityBundleStore.PublicationPhase phase : WorldCapabilityBundleStore.PublicationPhase.values()) {
			for (String member : faultedMembers(phase)) {
				Path root = Files.createTempDirectory(Path.of("."), "mmm-bundle-fault-");
				WorldCapabilityBundleStore store = new WorldCapabilityBundleStore(root, ADMISSION);
				requireRefused(() -> store.openOrPublish(full, abortAt(phase, member)),
						"fault injector at " + phase + "/" + member + " did not abort");
				Set<String> entries = entryNames(root.resolve("mmm").resolve("world-capabilities"));
				boolean complete = entries.equals(Set.of("HEAD.nbt", "slot-a"));
				require(complete || STAGED_ROOTS.contains(entries),
						"fault at " + phase + "/" + member + " left an unprovable bundle root: " + entries);
				outcomes.add(phase + "/" + member + "=" + (complete ? "complete" : entries.toString()));
			}
		}
		System.out.println("  bundle publication faults: " + String.join(", ", outcomes));
	}

	/** A later open must finish the publication or preserve the interruption, never leave both. */
	private static void everyInterruptedPublicationRecoversOnTheNextOpen() throws Exception {
		List<String> outcomes = new ArrayList<>();
		for (WorldCapabilityBundleStore.PublicationPhase phase : WorldCapabilityBundleStore.PublicationPhase.values()) {
			for (String member : faultedMembers(phase)) {
				Path root = Files.createTempDirectory(Path.of("."), "mmm-bundle-recover-");
				WorldCapabilityBundleStore store = new WorldCapabilityBundleStore(root, ADMISSION);
				requireRefused(() -> store.openOrPublish(full, abortAt(phase, member)),
						"fault injector at " + phase + "/" + member + " did not abort");
				WorldCapabilityBundleStore.OpenResult reopened = store.openOrPublish(full);
				require(!reopened.definitionMismatch(),
						"recovery after " + phase + "/" + member + " changed the profile");
				require(reopened.bundle().profile().equals(full.profile()),
						"recovery after " + phase + "/" + member + " did not restore the published profile");
				store.readCommitted();
				outcomes.add(phase + "/" + member + "=" + (reopened.recovered() ? "recovered" : "published"));
			}
		}
		System.out.println("  bundle publication recovery: " + String.join(", ", outcomes));
	}

	private static void stagingWithoutIdentityIsRefusedAndPreserved() throws Exception {
		Path root = Files.createTempDirectory(Path.of("."), "mmm-bundle-identity-");
		WorldCapabilityBundleStore store = new WorldCapabilityBundleStore(root, ADMISSION);
		Path staging = root.resolve("mmm").resolve("world-capabilities").resolve("slot-a.tmp");
		Files.createDirectories(staging);
		Files.write(staging.resolve("material-registry.nbt"), new byte[] {1, 2, 3});
		requireRefused(() -> store.openOrPublish(full), "staging without a publication identity was accepted");
		require(Files.exists(staging.resolve("material-registry.nbt")),
				"refused staging must be preserved, never deleted");
	}

	private static void quarantineAdmissionBudgetIsEnforced() throws Exception {
		Path root = Files.createTempDirectory(Path.of("."), "mmm-bundle-budget-");
		WorldCapabilityBundleStore tight = new WorldCapabilityBundleStore(root,
				new WorldCapabilityBundleStore.QuarantineAdmission(0L, 0L));
		requireRefused(() -> tight.openOrPublish(full),
				"a zero quarantine admission budget allowed a publication");
	}

	private interface RefusedAction {
		void run() throws IOException;
	}

	private static void requireRefused(RefusedAction action, String message) {
		try {
			action.run();
		} catch (IOException expected) {
			return;
		}
		throw new AssertionError(message);
	}

	private static WorldCapabilityBundleStore.FaultInjector abortAt(
			WorldCapabilityBundleStore.PublicationPhase phase, String member
	) {
		return boundary -> {
			if (boundary.phase() == phase && boundary.member().equals(member)) {
				throw new IOException("Injected publication failure at " + phase + "/" + member);
			}
		};
	}

	private static List<String> faultedMembers(WorldCapabilityBundleStore.PublicationPhase phase) {
		return switch (phase) {
			case MEMBER_WRITTEN -> WorldCapabilityBundleManifest.REQUIRED_FILES;
			case PREPARATION_MANIFEST_WRITTEN -> List.of("manifest.nbt");
			case STAGING_MANIFEST_WRITTEN -> List.of("slot-a.tmp");
			case SLOT_PUBLISHED -> List.of("slot-a");
			case HEAD_TEMPORARY_VERIFIED -> List.of("HEAD.nbt.tmp");
			case HEAD_PUBLISHED -> List.of("HEAD.nbt");
			case STAGING_VERIFIED -> List.of("");
		};
	}

	private static Set<String> entryNames(Path directory) throws IOException {
		HashSet<String> names = new HashSet<>();
		try (var entries = Files.newDirectoryStream(directory)) {
			for (Path entry : entries) names.add(entry.getFileName().toString());
		}
		return Set.copyOf(names);
	}

	private static WorldCapabilityBundleStore.Definitions definitions(Set<WorldCapability> capabilities) {
		return new WorldCapabilityBundleStore.Definitions(capabilities, registries.material(),
				registries.chemical(), registries.granular(), registries.gas(), registries.thermal());
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}

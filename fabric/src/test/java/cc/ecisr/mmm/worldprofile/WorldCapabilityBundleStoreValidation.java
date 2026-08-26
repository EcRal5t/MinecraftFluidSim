package cc.ecisr.mmm.worldprofile;

import cc.ecisr.mmm.chemistry.BuiltinChemicalDefinitions;
import cc.ecisr.mmm.fluid.BuiltinFluidMaterials;
import cc.ecisr.mmm.fluid.MaterialRegistry;
import cc.ecisr.mmm.fluid.air.BuiltinGasDefinitions;
import cc.ecisr.mmm.granular.BuiltinGranularDefinitions;
import cc.ecisr.mmm.simulation.persistence.WorldCapabilityBundleManifest;
import cc.ecisr.mmm.simulation.persistence.WorldCapabilityBundleStore;
import cc.ecisr.mmm.thermal.ThermalMaterialDefinition;
import cc.ecisr.mmm.thermal.ThermalRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** HEAD-last publication, restart convergence and strict residue handling for the capability bundle. */
public final class WorldCapabilityBundleStoreValidation {
	private static final WorldCapabilityBundleStore.QuarantineAdmission UNBOUNDED_QUARANTINE =
			new WorldCapabilityBundleStore.QuarantineAdmission(Long.MAX_VALUE, Long.MAX_VALUE);

	private WorldCapabilityBundleStoreValidation() { }

	public static void run() {
		firstPublicationIsStrictAndAuthoritative();
		everyPublicationBoundaryConvergesAfterRestart();
		corruptOrUnknownResidueIsPreservedAndRejected();
		committedSlotTamperingIsPreservedAndRejected();
		authorityWithoutHeadAndForeignCommittedContentAreRejected();
		quarantineAdmissionFailurePreservesStaging();
	}

	private static void firstPublicationIsStrictAndAuthoritative() {
		Path root = null;
		try {
			root = Files.createTempDirectory("mmm-world-capability-bundle-");
			WorldCapabilityBundleStore.Definitions definitions = definitions();
			WorldCapabilityBundleStore store = new WorldCapabilityBundleStore(root, UNBOUNDED_QUARANTINE);
			WorldCapabilityBundleStore.OpenResult first = store.openOrPublish(definitions);
			require(first.created() && !first.recovered() && !first.definitionMismatch(),
					"first world capability bundle publication reported the wrong outcome");
			require(first.bundle().profile().equals(definitions.profile()),
					"published world capability profile does not bind its registry definitions");
			Path bundleRoot = root.resolve("mmm").resolve("world-capabilities");
			require(entryNames(bundleRoot).equals(List.of("HEAD.nbt", "slot-a")),
					"committed world capability root is not exact");
			ArrayList<String> slotEntries = new ArrayList<>(WorldCapabilityBundleManifest.REQUIRED_FILES);
			slotEntries.add("manifest.nbt");
			slotEntries.sort(String::compareTo);
			require(entryNames(bundleRoot.resolve("slot-a")).equals(slotEntries),
					"committed world capability slot is not exact");

			MaterialRegistry changedMaterial = new MaterialRegistry(definitions.material().entries(), List.of(
					new MaterialRegistry.LiquidPairTension("mmm:aqueous", "mmm:oil", Math.nextUp(0.0))));
			WorldCapabilityBundleStore.Definitions changed = new WorldCapabilityBundleStore.Definitions(
					changedMaterial, definitions.chemical(), definitions.granular(), definitions.gas(), definitions.thermal());
			WorldCapabilityBundleStore.OpenResult reopened = store.openOrPublish(changed);
			require(!reopened.created() && reopened.definitionMismatch()
					&& reopened.bundle().profile().equals(definitions.profile()),
					"current definitions replaced or reinterpreted an authoritative bundle");
		} catch (IOException failure) {
			throw new AssertionError("strict world capability bundle publication failed", failure);
		} finally {
			deleteTree(root);
		}
	}

	private static void everyPublicationBoundaryConvergesAfterRestart() {
		WorldCapabilityBundleStore.Definitions definitions = definitions();
		List<WorldCapabilityBundleStore.PublicationBoundary> boundaries = publicationBoundaries(definitions);
		require(boundaries.stream().filter(boundary -> boundary.phase()
				== WorldCapabilityBundleStore.PublicationPhase.MEMBER_WRITTEN).count()
				== WorldCapabilityBundleManifest.REQUIRED_FILES.size(),
				"fault fixture did not observe every bundle member write");
		for (int failureIndex = 0; failureIndex < boundaries.size(); failureIndex++) {
			Path root = null;
			try {
				root = Files.createTempDirectory("mmm-world-capability-restart-");
				WorldCapabilityBundleStore store = new WorldCapabilityBundleStore(root, UNBOUNDED_QUARANTINE);
				int selected = failureIndex;
				int[] observed = {0};
				expectIo(() -> store.openOrPublish(definitions, boundary -> {
					if (observed[0]++ == selected) throw new IOException("injected publication interruption");
				}), "publication boundary did not interrupt the writer: " + boundaries.get(failureIndex));

				WorldCapabilityBundleStore.OpenResult restarted = new WorldCapabilityBundleStore(
						root, UNBOUNDED_QUARANTINE).openOrPublish(definitions);
				require(restarted.bundle().profile().equals(definitions.profile()),
						"restart did not converge to the intended immutable profile at " + boundaries.get(failureIndex));
				require(entryNames(root.resolve("mmm").resolve("world-capabilities"))
						.equals(List.of("HEAD.nbt", "slot-a")),
						"restart left an extra authority candidate at " + boundaries.get(failureIndex));
				if (isQuarantinedBoundary(boundaries.get(failureIndex).phase())) {
					Path quarantine = root.resolve("mmm").resolve("quarantine")
							.resolve("world-capability-staging");
					require(Files.isDirectory(quarantine) && entryNames(quarantine).size() == 1,
							"known temporary staging was not retained in quarantine");
				}
			} catch (IOException failure) {
				throw new AssertionError("world capability restart convergence failed at "
						+ boundaries.get(failureIndex), failure);
			} finally {
				deleteTree(root);
			}
		}
	}

	private static boolean isQuarantinedBoundary(WorldCapabilityBundleStore.PublicationPhase phase) {
		return phase == WorldCapabilityBundleStore.PublicationPhase.PREPARATION_MANIFEST_WRITTEN
				|| phase == WorldCapabilityBundleStore.PublicationPhase.STAGING_MANIFEST_WRITTEN
				|| phase == WorldCapabilityBundleStore.PublicationPhase.MEMBER_WRITTEN
				|| phase == WorldCapabilityBundleStore.PublicationPhase.STAGING_VERIFIED;
	}

	private static List<WorldCapabilityBundleStore.PublicationBoundary> publicationBoundaries(
			WorldCapabilityBundleStore.Definitions definitions) {
		Path root = null;
		try {
			root = Files.createTempDirectory("mmm-world-capability-boundaries-");
			ArrayList<WorldCapabilityBundleStore.PublicationBoundary> boundaries = new ArrayList<>();
			new WorldCapabilityBundleStore(root, UNBOUNDED_QUARANTINE)
					.openOrPublish(definitions, boundaries::add);
			return List.copyOf(boundaries);
		} catch (IOException failure) {
			throw new AssertionError("could not enumerate world capability publication boundaries", failure);
		} finally {
			deleteTree(root);
		}
	}

	private static void corruptOrUnknownResidueIsPreservedAndRejected() {
		Path unknownRoot = null;
		Path corruptRoot = null;
		try {
			WorldCapabilityBundleStore.Definitions definitions = definitions();
			unknownRoot = Files.createTempDirectory("mmm-world-capability-unknown-");
			WorldCapabilityBundleStore unknownStore = new WorldCapabilityBundleStore(
					unknownRoot, UNBOUNDED_QUARANTINE);
			expectIo(() -> unknownStore.openOrPublish(definitions, boundary -> {
				if (boundary.phase() == WorldCapabilityBundleStore.PublicationPhase.STAGING_MANIFEST_WRITTEN) {
					throw new IOException("injected publication interruption");
				}
			}), "manifest boundary did not interrupt publication");
			Path unknownStaging = unknownRoot.resolve("mmm").resolve("world-capabilities").resolve("slot-a.tmp");
			Path foreign = unknownStaging.resolve("foreign.bin");
			Files.writeString(foreign, "preserve");
			expectIo(() -> unknownStore.openOrPublish(definitions), "unknown staging member did not lock publication");
			require(Files.readString(foreign).equals("preserve"), "unknown staging evidence was changed or deleted");

			corruptRoot = Files.createTempDirectory("mmm-world-capability-corrupt-");
			WorldCapabilityBundleStore corruptStore = new WorldCapabilityBundleStore(
					corruptRoot, UNBOUNDED_QUARANTINE);
			expectIo(() -> corruptStore.openOrPublish(definitions, boundary -> {
				if (boundary.phase() == WorldCapabilityBundleStore.PublicationPhase.MEMBER_WRITTEN) {
					throw new IOException("injected publication interruption");
				}
			}), "member boundary did not interrupt publication");
			Path corruptMember = corruptRoot.resolve("mmm").resolve("world-capabilities")
					.resolve("slot-a.tmp").resolve(WorldCapabilityBundleManifest.REQUIRED_FILES.getFirst());
			byte[] bytes = Files.readAllBytes(corruptMember);
			bytes[bytes.length - 1] ^= 1;
			Files.write(corruptMember, bytes);
			expectIo(() -> corruptStore.openOrPublish(definitions), "checksum-corrupt staging did not lock publication");
			require(Files.exists(corruptMember), "checksum-corrupt staging evidence was deleted");
		} catch (IOException failure) {
			throw new AssertionError("world capability residue validation failed", failure);
		} finally {
			deleteTree(unknownRoot);
			deleteTree(corruptRoot);
		}
	}

	private static void quarantineAdmissionFailurePreservesStaging() {
		Path root = null;
		try {
			root = Files.createTempDirectory("mmm-world-capability-quarantine-budget-");
			WorldCapabilityBundleStore.Definitions definitions = definitions();
			WorldCapabilityBundleStore store = new WorldCapabilityBundleStore(root, UNBOUNDED_QUARANTINE);
			expectIo(() -> store.openOrPublish(definitions, boundary -> {
				if (boundary.phase() == WorldCapabilityBundleStore.PublicationPhase.STAGING_MANIFEST_WRITTEN) {
					throw new IOException("injected publication interruption");
				}
			}), "manifest boundary did not interrupt publication");
			Path staging = root.resolve("mmm").resolve("world-capabilities").resolve("slot-a.tmp");
			WorldCapabilityBundleStore constrained = new WorldCapabilityBundleStore(root,
					new WorldCapabilityBundleStore.QuarantineAdmission(0L, 0L));
			expectIo(() -> constrained.openOrPublish(definitions), "quarantine admission budget was ignored");
			require(Files.isDirectory(staging) && Files.exists(staging.resolve("manifest.nbt")),
					"staging was deleted when quarantine admission failed");
		} catch (IOException failure) {
			throw new AssertionError("world capability quarantine admission validation failed", failure);
		} finally {
			deleteTree(root);
		}
	}

	private static void committedSlotTamperingIsPreservedAndRejected() {
		Path root = null;
		try {
			root = Files.createTempDirectory("mmm-world-capability-committed-tamper-");
			WorldCapabilityBundleStore store = new WorldCapabilityBundleStore(root, UNBOUNDED_QUARANTINE);
			store.openOrPublish(definitions());
			Path slot = root.resolve("mmm").resolve("world-capabilities").resolve("slot-a");
			Path extra = slot.resolve("foreign.bin");
			Files.writeString(extra, "preserve");
			expectIo(store::readCommitted, "extra committed slot member was ignored");
			require(Files.readString(extra).equals("preserve"), "extra committed slot evidence was changed");
			Files.delete(extra);

			Path member = slot.resolve(WorldCapabilityBundleManifest.REQUIRED_FILES.getFirst());
			byte[] tampered = Files.readAllBytes(member);
			tampered[tampered.length - 1] ^= 1;
			Files.write(member, tampered);
			expectIo(store::readCommitted, "checksum-corrupt committed member was ignored");
			require(java.util.Arrays.equals(Files.readAllBytes(member), tampered),
					"checksum-corrupt committed evidence was changed");
		} catch (IOException failure) {
			throw new AssertionError("committed world capability tamper validation failed", failure);
		} finally {
			deleteTree(root);
		}
	}

	private static void authorityWithoutHeadAndForeignCommittedContentAreRejected() {
		Path uncommittedRoot = null;
		Path committedRoot = null;
		try {
			WorldCapabilityBundleStore.Definitions definitions = definitions();
			uncommittedRoot = Files.createTempDirectory("mmm-world-capability-prior-authority-");
			Path priorAuthority = uncommittedRoot.resolve("mmm").resolve("region-state");
			Files.createDirectories(priorAuthority);
			Path evidence = priorAuthority.resolve("evidence.bin");
			Files.writeString(evidence, "preserve");
			WorldCapabilityBundleStore uncommitted = new WorldCapabilityBundleStore(
					uncommittedRoot, UNBOUNDED_QUARANTINE);
			expectIo(() -> uncommitted.openOrPublish(definitions),
					"world authority without a capability HEAD did not lock first publication");
			require(Files.readString(evidence).equals("preserve")
					&& !Files.exists(uncommittedRoot.resolve("mmm").resolve("world-capabilities")),
					"prior world authority was changed before first publication was rejected");

			committedRoot = Files.createTempDirectory("mmm-world-capability-foreign-root-");
			WorldCapabilityBundleStore committed = new WorldCapabilityBundleStore(
					committedRoot, UNBOUNDED_QUARANTINE);
			committed.openOrPublish(definitions);
			Path foreign = committedRoot.resolve("mmm").resolve("foreign-domain");
			Files.createDirectory(foreign);
			expectIo(committed::readCommitted, "foreign MMM persistence root was ignored");
			require(Files.isDirectory(foreign), "foreign committed-world evidence was deleted");
		} catch (IOException failure) {
			throw new AssertionError("world capability root allowlist validation failed", failure);
		} finally {
			deleteTree(uncommittedRoot);
			deleteTree(committedRoot);
		}
	}

	private static WorldCapabilityBundleStore.Definitions definitions() {
		var material = BuiltinFluidMaterials.createRegistry();
		var chemical = BuiltinChemicalDefinitions.REGISTRY;
		var granular = BuiltinGranularDefinitions.createRegistry();
		var gas = BuiltinGasDefinitions.createRegistry();
		ThermalMaterialDefinition water = new ThermalMaterialDefinition(
				"test:thermal_water", ThermalMaterialDefinition.CarrierKind.LIQUID, "mmm:water",
				293.15, 0.0, 0.6, List.of(
				new ThermalMaterialDefinition.CaloricSegment(273.15, 373.15, 4_184.0)));
		ThermalRegistry thermal = new ThermalRegistry(List.of(water), List.of(), List.of(), List.of(),
				List.of(), List.of(), chemical.fingerprint(), granular.fingerprint(), gas.fingerprint());
		return new WorldCapabilityBundleStore.Definitions(material, chemical, granular, gas, thermal);
	}

	private static List<String> entryNames(Path directory) throws IOException {
		try (var entries = Files.list(directory)) {
			return entries.map(path -> path.getFileName().toString()).sorted().toList();
		}
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
			throw new AssertionError("world capability validation cleanup failed", cleanup);
		}
	}

	private interface IoAction {
		void run() throws IOException;
	}
}

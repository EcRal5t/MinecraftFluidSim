package cc.ecisr.mmm.worldprofile;

import cc.ecisr.mmm.simulation.persistence.WorldCapabilityProfileCodec;
import cc.ecisr.mmm.simulation.persistence.WorldCapabilityBundleHead;
import cc.ecisr.mmm.simulation.persistence.WorldCapabilityBundleManifest;
import cc.ecisr.mmm.simulation.persistence.WorldCapabilityBundleMetadataCodec;
import net.minecraft.nbt.CompoundTag;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Canonical derivation, fingerprint determinism, dependency and binding strictness for world capability profiles. */
public final class WorldCapabilityProfileValidation {
	private WorldCapabilityProfileValidation() { }

	public static void main(String[] args) {
		fullDefaultProfileIsDeterministic();
		partialCapabilitySetsDeriveUniqueLayouts();
		dependencyViolationsAreRejected();
		bindingMismatchIsRejected();
		malformedFingerprintsAreRejected();
		restoreRejectsTampering();
		canonicalBytesExcludeProfileFingerprint();
		strictNbtCodecRoundTripsAndRejectsTampering();
		bundleMetadataCodecsBindTheOnlyCommitPoint();
		WorldCapabilityBundleStoreValidation.run();
		WorldCapabilityStartupValidation.run();
		System.out.println("World capability profile validation passed");
	}

	private static void fullDefaultProfileIsDeterministic() {
		Set<WorldCapability> forward = EnumSet.allOf(WorldCapability.class);
		List<WorldCapability> reversed = new ArrayList<>(List.of(WorldCapability.values()));
		Collections.reverse(reversed);
		Set<WorldCapability> backward = new LinkedHashSet<>(reversed);
		RegistryFingerprintSet bindings = bindings(RegistryKind.values());
		WorldCapabilityProfile first = WorldCapabilityProfile.of(forward, bindings);
		WorldCapabilityProfile second = WorldCapabilityProfile.of(backward, bindings);
		require(first.regionLayoutId().equals("thermal"), "full default profile must derive the thermal layout");
		require(second.regionLayoutId().equals("thermal"), "reversed input must derive the thermal layout");
		require(first.profileFingerprint().equals(second.profileFingerprint()), "profile fingerprint must be order independent");
		require(first.aggregateFingerprint().equals(second.aggregateFingerprint()), "aggregate fingerprint must be order independent");
		require(java.util.Arrays.equals(first.canonicalBytes(), second.canonicalBytes()), "canonical bytes must be order independent");
		for (WorldCapability capability : WorldCapability.values()) {
			require(first.hasCapability(capability), "full default profile must contain " + capability.stableId());
		}
		System.out.println("full-default aggregate=" + first.aggregateFingerprint()
				+ " profile=" + first.profileFingerprint());
	}

	private static void partialCapabilitySetsDeriveUniqueLayouts() {
		requireLayout("pure", Set.of());
		requireLayout("material", EnumSet.of(WorldCapability.MATERIALS));
		requireLayout("chemical", EnumSet.of(WorldCapability.MATERIALS, WorldCapability.CHEMISTRY));
		requireLayout("granular", EnumSet.of(WorldCapability.MATERIALS, WorldCapability.GRANULAR));
		requireLayout("granular", EnumSet.of(WorldCapability.MATERIALS, WorldCapability.CHEMISTRY,
				WorldCapability.GRANULAR, WorldCapability.PRECIPITATION));
		requireLayout("thermal", EnumSet.of(WorldCapability.MATERIALS, WorldCapability.THERMAL));
		requireLayout("thermal", EnumSet.of(WorldCapability.MATERIALS, WorldCapability.THERMAL, WorldCapability.PHASECHANGE));
	}

	private static void requireLayout(String expected, Set<WorldCapability> capabilities) {
		WorldCapabilityProfile profile = WorldCapabilityProfile.of(capabilities, bindingsFor(capabilities));
		require(profile.regionLayoutId().equals(expected),
				"capability set " + capabilities + " must derive layout " + expected + " but derived "
						+ profile.regionLayoutId());
	}

	private static void dependencyViolationsAreRejected() {
		expectIllegal("requires mmm:materials",
				() -> WorldCapabilityProfile.of(EnumSet.of(WorldCapability.CHEMISTRY), bindingsFor(EnumSet.of(WorldCapability.CHEMISTRY))));
		expectIllegal("requires mmm:materials",
				() -> WorldCapabilityProfile.of(EnumSet.of(WorldCapability.GRANULAR), bindingsFor(EnumSet.of(WorldCapability.GRANULAR))));
		expectIllegal("requires mmm:materials",
				() -> WorldCapabilityProfile.of(EnumSet.of(WorldCapability.THERMAL), bindingsFor(EnumSet.of(WorldCapability.THERMAL))));
		expectIllegal("requires mmm:chemistry",
				() -> WorldCapabilityProfile.of(
						EnumSet.of(WorldCapability.MATERIALS, WorldCapability.PRECIPITATION),
						bindingsFor(EnumSet.of(WorldCapability.MATERIALS, WorldCapability.PRECIPITATION))));
		expectIllegal("requires mmm:thermal",
				() -> WorldCapabilityProfile.of(
						EnumSet.of(WorldCapability.MATERIALS, WorldCapability.CHEMISTRY, WorldCapability.PHASECHANGE),
						bindingsFor(EnumSet.of(WorldCapability.MATERIALS, WorldCapability.CHEMISTRY, WorldCapability.PHASECHANGE))));
	}

	private static void bindingMismatchIsRejected() {
		EnumSet<WorldCapability> full = EnumSet.allOf(WorldCapability.class);
		expectIllegal("gas is required", () -> WorldCapabilityProfile.of(full,
				bindings(RegistryKind.MATERIAL, RegistryKind.CHEMICAL, RegistryKind.GRANULAR, RegistryKind.THERMAL)));
		expectIllegal("chemical must be empty", () -> WorldCapabilityProfile.of(
				EnumSet.of(WorldCapability.MATERIALS),
				bindings(RegistryKind.MATERIAL, RegistryKind.CHEMICAL)));
		expectIllegal("material must be empty", () -> WorldCapabilityProfile.of(
				Set.of(),
				bindings(RegistryKind.MATERIAL)));
		expectIllegal("gas must be empty", () -> WorldCapabilityProfile.of(
				EnumSet.of(WorldCapability.MATERIALS),
				bindings(RegistryKind.MATERIAL, RegistryKind.GAS)));
	}

	private static void malformedFingerprintsAreRejected() {
		expectIllegal("fingerprint", () -> new RegistryFingerprintSet("ABCDEF", "", "", "", ""));
		expectIllegal("fingerprint", () -> new RegistryFingerprintSet("", "0".repeat(63), "", "", ""));
		expectIllegal("fingerprint", () -> new RegistryFingerprintSet("", "", "not-hex", "", ""));
		expectIllegal("fingerprint", () -> new RegistryFingerprintSet("", "", "", null, ""));
	}

	private static void restoreRejectsTampering() {
		Set<WorldCapability> capabilities = EnumSet.allOf(WorldCapability.class);
		RegistryFingerprintSet bindings = bindings(RegistryKind.values());
		WorldCapabilityProfile good = WorldCapabilityProfile.of(capabilities, bindings);
		expectIllegal("schema", () -> new WorldCapabilityProfile("mmm:other-world-capability-profile", WorldCapabilityProfile.UNIT_IDENTITY,
				capabilities, good.regionLayoutId(), bindings, good.aggregateFingerprint(), good.profileFingerprint()));
		expectIllegal("unit identity", () -> new WorldCapabilityProfile(WorldCapabilityProfile.SCHEMA, "mmm:other-units",
				capabilities, good.regionLayoutId(), bindings, good.aggregateFingerprint(), good.profileFingerprint()));
		expectIllegal("Unknown region layout", () -> new WorldCapabilityProfile(WorldCapabilityProfile.SCHEMA, WorldCapabilityProfile.UNIT_IDENTITY,
				capabilities, "future-layout", bindings, good.aggregateFingerprint(), good.profileFingerprint()));
		expectIllegal("does not match capability set", () -> new WorldCapabilityProfile(WorldCapabilityProfile.SCHEMA, WorldCapabilityProfile.UNIT_IDENTITY,
				capabilities, "material", bindings, good.aggregateFingerprint(), good.profileFingerprint()));
		expectIllegal("aggregate fingerprint mismatch", () -> new WorldCapabilityProfile(WorldCapabilityProfile.SCHEMA, WorldCapabilityProfile.UNIT_IDENTITY,
				capabilities, good.regionLayoutId(), bindings, "0".repeat(64), good.profileFingerprint()));
		expectIllegal("fingerprint mismatch", () -> new WorldCapabilityProfile(WorldCapabilityProfile.SCHEMA, WorldCapabilityProfile.UNIT_IDENTITY,
				capabilities, good.regionLayoutId(), bindings, good.aggregateFingerprint(), "1".repeat(64)));
		RegistryFingerprintSet tampered = bindings(RegistryKind.values()).with(RegistryKind.GAS, "2".repeat(64));
		expectIllegal("aggregate fingerprint mismatch", () -> new WorldCapabilityProfile(WorldCapabilityProfile.SCHEMA, WorldCapabilityProfile.UNIT_IDENTITY,
				capabilities, good.regionLayoutId(), tampered, good.aggregateFingerprint(), good.profileFingerprint()));
	}

	private static void canonicalBytesExcludeProfileFingerprint() {
		WorldCapabilityProfile profile = WorldCapabilityProfile.of(EnumSet.allOf(WorldCapability.class), bindings(RegistryKind.values()));
		String canonical = new String(profile.canonicalBytes(), java.nio.charset.StandardCharsets.UTF_8);
		require(!canonical.contains(profile.profileFingerprint()), "canonical bytes must exclude the profile fingerprint");
		require(canonical.contains("schema=" + WorldCapabilityProfile.SCHEMA), "canonical bytes must pin the schema");
		require(canonical.contains("units=" + WorldCapabilityProfile.UNIT_IDENTITY), "canonical bytes must pin the unit identity");
		require(canonical.contains("layout=thermal"), "canonical bytes must pin the layout");
		require(canonical.contains("aggregate=" + profile.aggregateFingerprint()), "canonical bytes must pin the aggregate fingerprint");
		require(canonical.contains("registry=gas|"), "canonical bytes must list the gas binding");
	}

	private static void strictNbtCodecRoundTripsAndRejectsTampering() {
		WorldCapabilityProfile profile = WorldCapabilityProfile.of(
				EnumSet.allOf(WorldCapability.class), bindings(RegistryKind.values()));
		java.nio.file.Path root = null;
		try {
			root = java.nio.file.Files.createTempDirectory("mmm-world-profile-codec-");
			java.nio.file.Path file = root.resolve("profile.nbt");
			WorldCapabilityProfileCodec.write(file, profile);
			require(WorldCapabilityProfileCodec.read(file).equals(profile),
					"world capability profile did not round-trip through strict NBT");

			CompoundTag extraRoot = WorldCapabilityProfileCodec.encode(profile);
			extraRoot.putString("Unknown", "reject");
			expectIo(() -> WorldCapabilityProfileCodec.decode(extraRoot),
					"unknown profile root field was ignored");

			CompoundTag missingBinding = WorldCapabilityProfileCodec.encode(profile);
			missingBinding.getCompoundOrEmpty("RegistryFingerprints").remove("Gas");
			expectIo(() -> WorldCapabilityProfileCodec.decode(missingBinding),
					"missing profile registry binding was ignored");

			WorldCapabilityProfile materialOnly = WorldCapabilityProfile.of(
					EnumSet.of(WorldCapability.MATERIALS), bindings(RegistryKind.MATERIAL));
			CompoundTag duplicateCapability = WorldCapabilityProfileCodec.encode(materialOnly);
			CompoundTag repeated = new CompoundTag();
			repeated.putString("Id", WorldCapability.MATERIALS.stableId());
			duplicateCapability.getListOrEmpty("Capabilities").add(repeated);
			expectIo(() -> WorldCapabilityProfileCodec.decode(duplicateCapability),
					"duplicate world capability was accepted");

			CompoundTag reorderedCapabilities = WorldCapabilityProfileCodec.encode(profile);
			var capabilityList = reorderedCapabilities.getListOrEmpty("Capabilities");
			var firstCapability = capabilityList.remove(0);
			capabilityList.add(firstCapability);
			expectIo(() -> WorldCapabilityProfileCodec.decode(reorderedCapabilities),
					"non-canonical world capability order was accepted");

			CompoundTag unknownCapability = WorldCapabilityProfileCodec.encode(profile);
			unknownCapability.getListOrEmpty("Capabilities").getCompoundOrEmpty(0)
					.putString("Id", "mmm:future_capability");
			expectIo(() -> WorldCapabilityProfileCodec.decode(unknownCapability),
					"unknown world capability was accepted");

			CompoundTag wrongType = WorldCapabilityProfileCodec.encode(profile);
			wrongType.putString("Capabilities", "not-a-list");
			expectIo(() -> WorldCapabilityProfileCodec.decode(wrongType),
					"wrong profile NBT field type was accepted");

			CompoundTag tamperedFingerprint = WorldCapabilityProfileCodec.encode(profile);
			tamperedFingerprint.putString("ProfileFingerprint", "0".repeat(64));
			expectIo(() -> WorldCapabilityProfileCodec.decode(tamperedFingerprint),
					"tampered profile fingerprint was accepted");
		} catch (IOException failure) {
			throw new AssertionError("world capability profile codec validation failed", failure);
		} finally {
			if (root != null) try (var paths = java.nio.file.Files.walk(root)) {
				for (java.nio.file.Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
					java.nio.file.Files.deleteIfExists(path);
				}
			} catch (IOException cleanup) {
				throw new AssertionError("world capability profile codec cleanup failed", cleanup);
			}
		}
	}

	private static void bundleMetadataCodecsBindTheOnlyCommitPoint() {
		WorldCapabilityProfile profile = WorldCapabilityProfile.of(
				EnumSet.allOf(WorldCapability.class), bindings(RegistryKind.values()));
		java.util.UUID transaction = java.util.UUID.fromString("39b7a5b0-e0c1-47b9-9cf2-53a7d93e7386");
		ArrayList<WorldCapabilityBundleManifest.FileEntry> files = new ArrayList<>();
		for (int index = 0; index < WorldCapabilityBundleManifest.REQUIRED_FILES.size(); index++) {
			files.add(new WorldCapabilityBundleManifest.FileEntry(
					WorldCapabilityBundleManifest.REQUIRED_FILES.get(index), index + 1L,
					Integer.toHexString(index + 1).repeat(64).substring(0, 64)));
		}
		WorldCapabilityBundleManifest manifest = new WorldCapabilityBundleManifest(
				WorldCapabilityBundleManifest.SCHEMA, 1L, transaction,
				profile.profileFingerprint(), profile.aggregateFingerprint(), files);
		String manifestChecksum = "a".repeat(64);
		WorldCapabilityBundleHead head = new WorldCapabilityBundleHead(WorldCapabilityBundleHead.SCHEMA,
				1L, transaction, profile.profileFingerprint(), profile.aggregateFingerprint(), manifestChecksum);
		head.requireManifest(manifest, manifestChecksum);

		java.nio.file.Path root = null;
		try {
			root = java.nio.file.Files.createTempDirectory("mmm-world-bundle-metadata-");
			java.nio.file.Path manifestPath = root.resolve("manifest.nbt");
			java.nio.file.Path headPath = root.resolve("HEAD.nbt");
			WorldCapabilityBundleMetadataCodec.writeManifest(manifestPath, manifest);
			WorldCapabilityBundleMetadataCodec.writeHead(headPath, head);
			require(WorldCapabilityBundleMetadataCodec.readManifest(manifestPath).equals(manifest),
					"bundle manifest did not round-trip through strict NBT");
			require(WorldCapabilityBundleMetadataCodec.readHead(headPath).equals(head),
					"bundle HEAD did not round-trip through strict NBT");

			CompoundTag extraManifest = WorldCapabilityBundleMetadataCodec.encodeManifest(manifest);
			extraManifest.putString("Unknown", "reject");
			expectIo(() -> WorldCapabilityBundleMetadataCodec.decodeManifest(extraManifest),
					"unknown bundle manifest field was ignored");

			CompoundTag reorderedManifest = WorldCapabilityBundleMetadataCodec.encodeManifest(manifest);
			var encodedFiles = reorderedManifest.getListOrEmpty("Files");
			var firstFile = encodedFiles.remove(0);
			encodedFiles.add(firstFile);
			expectIo(() -> WorldCapabilityBundleMetadataCodec.decodeManifest(reorderedManifest),
					"non-canonical bundle file order was accepted");

			CompoundTag unknownFile = WorldCapabilityBundleMetadataCodec.encodeManifest(manifest);
			unknownFile.getListOrEmpty("Files").getCompoundOrEmpty(0).putString("Name", "foreign.nbt");
			expectIo(() -> WorldCapabilityBundleMetadataCodec.decodeManifest(unknownFile),
					"unknown bundle member was accepted");

			CompoundTag badHeadType = WorldCapabilityBundleMetadataCodec.encodeHead(head);
			badHeadType.putString("Generation", "one");
			expectIo(() -> WorldCapabilityBundleMetadataCodec.decodeHead(badHeadType),
					"wrong bundle HEAD field type was accepted");

			expectIllegal("does not bind", () -> head.requireManifest(manifest, "b".repeat(64)));
			expectIllegal("file table is incomplete", () -> new WorldCapabilityBundleManifest(
					WorldCapabilityBundleManifest.SCHEMA, 1L, transaction, profile.profileFingerprint(),
					profile.aggregateFingerprint(), files.subList(0, files.size() - 1)));
		} catch (IOException failure) {
			throw new AssertionError("world capability bundle metadata validation failed", failure);
		} finally {
			if (root != null) try (var paths = java.nio.file.Files.walk(root)) {
				for (java.nio.file.Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
					java.nio.file.Files.deleteIfExists(path);
				}
			} catch (IOException cleanup) {
				throw new AssertionError("world capability bundle metadata cleanup failed", cleanup);
			}
		}
	}

	private static RegistryFingerprintSet bindingsFor(Set<WorldCapability> capabilities) {
		boolean condensedThermal = capabilities.contains(WorldCapability.THERMAL)
				|| capabilities.contains(WorldCapability.PHASECHANGE);
		List<RegistryKind> bound = new ArrayList<>();
		if (capabilities.contains(WorldCapability.MATERIALS)) bound.add(RegistryKind.MATERIAL);
		if (capabilities.contains(WorldCapability.CHEMISTRY)) bound.add(RegistryKind.CHEMICAL);
		if (capabilities.contains(WorldCapability.GRANULAR)) bound.add(RegistryKind.GRANULAR);
		if (condensedThermal) {
			bound.add(RegistryKind.GAS);
			bound.add(RegistryKind.THERMAL);
		}
		return bindings(bound.toArray(RegistryKind[]::new));
	}

	private static RegistryFingerprintSet bindings(RegistryKind... kinds) {
		RegistryFingerprintSet bindings = RegistryFingerprintSet.unbound();
		for (RegistryKind kind : kinds) bindings = bindings.with(kind, fingerprintFor(kind));
		return bindings;
	}

	private static String fingerprintFor(RegistryKind kind) {
		long seed = 0x9e3779b97f4a7c15L * kind.name().hashCode();
		return String.format("%064x", seed);
	}

	private static void expectIllegal(String messagePart, ThrowingAction action) {
		try {
			action.run();
			throw new AssertionError("Expected IllegalArgumentException containing '" + messagePart + "'");
		} catch (IllegalArgumentException expected) {
			if (!String.valueOf(expected.getMessage()).contains(messagePart)) {
				throw new AssertionError("Unexpected rejection message: " + expected.getMessage(), expected);
			}
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

	private interface ThrowingAction {
		void run();
	}

	private interface IoAction {
		void run() throws IOException;
	}
}

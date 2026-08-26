package cc.ecisr.mmm.worldprofile;

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
		require(first.aggregateFingerprint().equals("921c99a7f2b80662e8f91038f3f929d967fe9c9ca773f2d609536c7c754f5c21"),
				"full-default aggregate fingerprint drifted from the pinned canonical form");
		require(first.profileFingerprint().equals("c6bc560d43d1f5d099ddee7090dd29d091ccc7935504f4645c6ae6dbfe02fa20"),
				"full-default profile fingerprint drifted from the pinned canonical form");
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
		expectIllegal("schema", () -> new WorldCapabilityProfile("mmm:world-capability-profile/2", WorldCapabilityProfile.UNIT_IDENTITY,
				capabilities, good.regionLayoutId(), bindings, good.aggregateFingerprint(), good.profileFingerprint()));
		expectIllegal("unit identity", () -> new WorldCapabilityProfile(WorldCapabilityProfile.SCHEMA, "mmm:other-units/1",
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

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}

	private interface ThrowingAction {
		void run();
	}
}

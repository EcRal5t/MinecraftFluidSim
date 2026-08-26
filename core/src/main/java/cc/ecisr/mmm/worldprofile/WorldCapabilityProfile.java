package cc.ecisr.mmm.worldprofile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Immutable world capability profile: the single interpretation source for every region payload of a world.
 * The profile fingerprint covers the canonical bytes excluding the fingerprint field itself.
 */
public record WorldCapabilityProfile(
		String schema,
		String unitIdentity,
		Set<WorldCapability> capabilities,
		String regionLayoutId,
		RegistryFingerprintSet registryFingerprints,
		String aggregateFingerprint,
		String profileFingerprint) {

	public static final String SCHEMA = "mmm:world-capability-profile/1";
	public static final String UNIT_IDENTITY = "mmm:canonical-units/1";
	private static final Set<String> LAYOUT_IDS = Set.of("pure", "material", "chemical", "granular", "thermal");
	private static final Comparator<WorldCapability> CANONICAL_ORDER = Comparator.comparing(WorldCapability::stableId);

	public WorldCapabilityProfile {
		if (!SCHEMA.equals(schema)) throw new IllegalArgumentException("Unknown profile schema " + schema);
		if (!UNIT_IDENTITY.equals(unitIdentity)) throw new IllegalArgumentException("Unknown unit identity " + unitIdentity);
		if (capabilities == null) throw new IllegalArgumentException("World capability set is missing");
		TreeSet<WorldCapability> sorted = new TreeSet<>(CANONICAL_ORDER);
		sorted.addAll(capabilities);
		capabilities = Collections.unmodifiableSortedSet(sorted);
		for (WorldCapability capability : sorted) {
			for (WorldCapability dependency : capability.dependencies()) {
				if (!sorted.contains(dependency)) {
					throw new IllegalArgumentException("World capability " + capability.stableId()
							+ " requires " + dependency.stableId());
				}
			}
		}
		if (registryFingerprints == null) throw new IllegalArgumentException("Registry fingerprint set is missing");
		if (regionLayoutId == null || !LAYOUT_IDS.contains(regionLayoutId)) {
			throw new IllegalArgumentException("Unknown region layout id " + regionLayoutId);
		}
		String derived = deriveLayout(sorted);
		if (!derived.equals(regionLayoutId)) {
			throw new IllegalArgumentException("Region layout " + regionLayoutId + " does not match capability set; expected "
					+ derived);
		}
		requireExactBindings(sorted, registryFingerprints);
		if (!registryFingerprints.aggregateFingerprint().equals(aggregateFingerprint)) {
			throw new IllegalArgumentException("Registry aggregate fingerprint mismatch");
		}
		String expected = sha256(encodeCanonical(schema, unitIdentity, sorted, regionLayoutId, registryFingerprints));
		if (!expected.equals(profileFingerprint)) {
			throw new IllegalArgumentException("World capability profile fingerprint mismatch");
		}
		profileFingerprint = expected;
	}

	/** Builds a verified profile from semantic inputs, deriving layout and both fingerprints. */
	public static WorldCapabilityProfile of(Set<WorldCapability> capabilities, RegistryFingerprintSet bindings) {
		TreeSet<WorldCapability> sorted = sorted(capabilities);
		String layout = deriveLayout(sorted);
		return new WorldCapabilityProfile(SCHEMA, UNIT_IDENTITY, capabilities, layout, bindings,
				bindings.aggregateFingerprint(), sha256(encodeCanonical(SCHEMA, UNIT_IDENTITY, sorted, layout, bindings)));
	}

	public byte[] canonicalBytes() {
		return encodeCanonical(schema, unitIdentity, sortedView(), regionLayoutId, registryFingerprints);
	}

	public boolean hasCapability(WorldCapability capability) { return capabilities.contains(capability); }

	private static TreeSet<WorldCapability> sorted(Set<WorldCapability> capabilities) {
		TreeSet<WorldCapability> sorted = new TreeSet<>(CANONICAL_ORDER);
		if (capabilities != null) sorted.addAll(capabilities);
		return sorted;
	}

	private TreeSet<WorldCapability> sortedView() {
		TreeSet<WorldCapability> sorted = new TreeSet<>(CANONICAL_ORDER);
		sorted.addAll(capabilities);
		return sorted;
	}

	private static String deriveLayout(TreeSet<WorldCapability> capabilities) {
		if (capabilities.contains(WorldCapability.THERMAL)) return "thermal";
		if (capabilities.contains(WorldCapability.GRANULAR)) return "granular";
		if (capabilities.contains(WorldCapability.CHEMISTRY)) return "chemical";
		if (capabilities.contains(WorldCapability.MATERIALS)) return "material";
		return "pure";
	}

	private static void requireExactBindings(TreeSet<WorldCapability> capabilities, RegistryFingerprintSet bindings) {
		boolean condensedThermal = capabilities.contains(WorldCapability.THERMAL)
				|| capabilities.contains(WorldCapability.PHASECHANGE);
		Map<RegistryKind, Boolean> required = new TreeMap<>();
		required.put(RegistryKind.MATERIAL, capabilities.contains(WorldCapability.MATERIALS));
		required.put(RegistryKind.CHEMICAL, capabilities.contains(WorldCapability.CHEMISTRY));
		required.put(RegistryKind.GRANULAR, capabilities.contains(WorldCapability.GRANULAR));
		required.put(RegistryKind.GAS, condensedThermal);
		required.put(RegistryKind.THERMAL, condensedThermal);
		for (Map.Entry<RegistryKind, Boolean> entry : required.entrySet()) {
			boolean bound = bindings.isBound(entry.getKey());
			if (entry.getValue() && !bound) {
				throw new IllegalArgumentException("Registry binding " + entry.getKey().token() + " is required by the capability set");
			}
			if (!entry.getValue() && bound) {
				throw new IllegalArgumentException("Registry binding " + entry.getKey().token()
						+ " must be empty for the capability set");
			}
		}
	}

	private static byte[] encodeCanonical(String schema, String unitIdentity, TreeSet<WorldCapability> capabilities,
			String regionLayoutId, RegistryFingerprintSet bindings) {
		StringBuilder out = new StringBuilder(512).append("mmm-world-capability-profile\n")
				.append("schema=").append(schema).append('\n')
				.append("units=").append(unitIdentity).append('\n')
				.append("capabilities=");
		for (WorldCapability capability : capabilities) out.append(capability.stableId()).append(';');
		out.append('\n')
				.append("layout=").append(regionLayoutId).append('\n');
		TreeMap<String, String> sortedBindings = new TreeMap<>();
		for (RegistryKind kind : RegistryKind.values()) {
			String value = bindings.binding(kind);
			if (!value.isEmpty()) sortedBindings.put(kind.token(), value);
		}
		for (Map.Entry<String, String> entry : sortedBindings.entrySet()) {
			out.append("registry=").append(entry.getKey()).append('|').append(entry.getValue()).append('\n');
		}
		out.append("aggregate=").append(bindings.aggregateFingerprint()).append('\n');
		return out.toString().getBytes(StandardCharsets.UTF_8);
	}

	private static String sha256(byte[] payload) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 unavailable", exception);
		}
	}
}

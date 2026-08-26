package cc.ecisr.mmm.worldprofile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Immutable per-kind registry fingerprint bindings; an empty string means the kind is not bound. */
public record RegistryFingerprintSet(String material, String chemical, String granular, String gas, String thermal) {
	public static final Pattern FINGERPRINT = Pattern.compile("^$|[0-9a-f]{64}");

	public RegistryFingerprintSet {
		requireFormat(material, "material");
		requireFormat(chemical, "chemical");
		requireFormat(granular, "granular");
		requireFormat(gas, "gas");
		requireFormat(thermal, "thermal");
	}

	public static RegistryFingerprintSet unbound() {
		return new RegistryFingerprintSet("", "", "", "", "");
	}

	public String binding(RegistryKind kind) {
		return switch (kind) {
			case MATERIAL -> material;
			case CHEMICAL -> chemical;
			case GRANULAR -> granular;
			case GAS -> gas;
			case THERMAL -> thermal;
		};
	}

	public RegistryFingerprintSet with(RegistryKind kind, String fingerprint) {
		requireFormat(fingerprint, kind.token());
		return new RegistryFingerprintSet(
				kind == RegistryKind.MATERIAL ? fingerprint : material,
				kind == RegistryKind.CHEMICAL ? fingerprint : chemical,
				kind == RegistryKind.GRANULAR ? fingerprint : granular,
				kind == RegistryKind.GAS ? fingerprint : gas,
				kind == RegistryKind.THERMAL ? fingerprint : thermal);
	}

	public boolean isBound(RegistryKind kind) { return !binding(kind).isEmpty(); }

	/** SHA-256 over the canonical sorted non-empty bindings; deterministic across JVM runs. */
	public String aggregateFingerprint() {
		TreeMap<String, String> sorted = new TreeMap<>();
		for (RegistryKind kind : RegistryKind.values()) {
			String value = binding(kind);
			if (!value.isEmpty()) sorted.put(kind.token(), value);
		}
		StringBuilder out = new StringBuilder(64 * (sorted.size() + 1)).append("mmm-registry-bindings\n");
		for (Map.Entry<String, String> entry : sorted.entrySet()) {
			out.append(entry.getKey()).append('|').append(entry.getValue()).append('\n');
		}
		return sha256(out.toString().getBytes(StandardCharsets.UTF_8));
	}

	private static void requireFormat(String fingerprint, String label) {
		if (fingerprint == null || !FINGERPRINT.matcher(fingerprint).matches()) {
			throw new IllegalArgumentException("Invalid " + label + " registry fingerprint");
		}
	}

	private static String sha256(byte[] payload) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 unavailable", exception);
		}
	}
}

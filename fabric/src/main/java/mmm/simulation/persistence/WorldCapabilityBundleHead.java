package mmm.simulation.persistence;

import java.util.UUID;

/** The sole commit-point value for one immutable world-capability bundle. */
public record WorldCapabilityBundleHead(
		String schema,
		long generation,
		UUID publicationTransactionId,
		String profileFingerprint,
		String aggregateFingerprint,
		String manifestChecksum
) {
	public static final String SCHEMA = "mmm:world-capability-bundle-head";

	public WorldCapabilityBundleHead {
		if (!SCHEMA.equals(schema)) throw new IllegalArgumentException("Unknown bundle HEAD schema " + schema);
		if (generation <= 0L) throw new IllegalArgumentException("Bundle HEAD generation must be positive");
		if (publicationTransactionId == null) {
			throw new IllegalArgumentException("Bundle HEAD publication transaction is missing");
		}
		WorldCapabilityBundleManifest.requireFingerprint(profileFingerprint, "bundle HEAD profile fingerprint");
		WorldCapabilityBundleManifest.requireFingerprint(aggregateFingerprint, "bundle HEAD aggregate fingerprint");
		WorldCapabilityBundleManifest.requireFingerprint(manifestChecksum, "bundle HEAD manifest checksum");
	}

	public void requireManifest(WorldCapabilityBundleManifest manifest, String actualManifestChecksum) {
		if (manifest == null) throw new IllegalArgumentException("Bundle manifest is missing");
		WorldCapabilityBundleManifest.requireFingerprint(actualManifestChecksum, "actual manifest checksum");
		if (generation != manifest.generation()
				|| !publicationTransactionId.equals(manifest.publicationTransactionId())
				|| !profileFingerprint.equals(manifest.profileFingerprint())
				|| !aggregateFingerprint.equals(manifest.aggregateFingerprint())
				|| !manifestChecksum.equals(actualManifestChecksum)) {
			throw new IllegalArgumentException("Bundle HEAD does not bind the manifest exactly");
		}
	}
}

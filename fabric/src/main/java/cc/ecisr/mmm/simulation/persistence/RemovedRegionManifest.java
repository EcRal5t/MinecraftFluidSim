package cc.ecisr.mmm.simulation.persistence;

import java.util.UUID;

/** Strict inventory-free terminal truth; it can never reconstruct a solver. */
public record RemovedRegionManifest(
		UUID regionId, String capabilityProfileFingerprint, String registryAggregateFingerprint,
		long baseGeneration, long targetGeneration, long oldManifestChecksum,
		long networkTombstoneGeneration, UUID quarantineTransactionId,
		long zeroInventoryDigest, long checksum
) {
	public RemovedRegionManifest {
		if (regionId == null || capabilityProfileFingerprint == null
				|| !capabilityProfileFingerprint.matches("[0-9a-f]{64}")
				|| registryAggregateFingerprint == null || !registryAggregateFingerprint.matches("[0-9a-f]{64}")
				|| baseGeneration <= 0L || !GenerationSequence.isNext(baseGeneration, targetGeneration)
				|| oldManifestChecksum < 0L || networkTombstoneGeneration <= 0L
				|| quarantineTransactionId == null || zeroInventoryDigest != ZERO_INVENTORY_DIGEST) {
			throw new IllegalArgumentException("Removed region manifest identity is invalid");
		}
	}

	public static final long ZERO_INVENTORY_DIGEST = 0x4d4d4d5a45524f31L;
}

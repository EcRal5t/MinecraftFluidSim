package com.example.simulation.ownership;

import java.util.List;
import java.util.Objects;

/**
 * Committed ownership registry for the disposable M6.0 prototype: the "HEAD" analogue that records
 * which positions the prototype owns and at which generation. Publishing this file is the sole
 * commit point of an ownership transition.
 */
record OwnershipCommittedProto(String regionId, String dimensionId, long generation, List<OwnershipJournalProto.BlockEntry> ownedBlocks) {
	static final String PROTO_REGION_ID = "m6.0-ownership-proto";

	OwnershipCommittedProto {
		Objects.requireNonNull(regionId, "regionId");
		Objects.requireNonNull(dimensionId, "dimensionId");
		if (generation < 0L) throw new IllegalArgumentException("generation must not be negative");
		ownedBlocks = List.copyOf(ownedBlocks);
	}
}

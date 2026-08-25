package cc.ecisr.mmm.simulation.ownership;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Committed ownership registry for one controller region: which world positions it owns and the
 * region checkpoint generation at which that ownership last changed. Region-state HEAD remains the
 * sole transaction commit point; this file is written after it and is rebuilt from the WAL by
 * post-commit recovery when a crash lands between the two.
 */
public record OwnershipCommitted(UUID regionId, String dimensionId, long generation, List<OwnershipJournal.BlockEntry> ownedBlocks) {
	public OwnershipCommitted {
		Objects.requireNonNull(regionId, "regionId");
		Objects.requireNonNull(dimensionId, "dimensionId");
		if (generation < 0L) throw new IllegalArgumentException("generation must not be negative");
		ownedBlocks = List.copyOf(ownedBlocks);
	}
}

package mmm.simulation.ownership;

import mmm.simulation.persistence.GenerationSequence;
import net.minecraft.nbt.CompoundTag;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * In-flight world ownership transition WAL for one controller region. Recovery compares the
 * region-state HEAD generation against {@code baseGeneration}/{@code targetGeneration} to decide
 * between rolling the world back and completing committed ownership, never by guessing.
 */
public record OwnershipJournal(
		UUID regionId,
		String dimensionId,
		long baseGeneration,
		long targetGeneration,
		Operation operation,
		List<BlockEntry> blocks,
		UUID operationId,
		long targetManifestChecksum,
		mmm.recovery.CrossDomainRecovery.JournalPhase phase
) {
	public OwnershipJournal(UUID regionId, String dimensionId, long baseGeneration, long targetGeneration,
			Operation operation, List<BlockEntry> blocks) {
		this(regionId, dimensionId, baseGeneration, targetGeneration, operation, blocks, UUID.randomUUID(), -1,
				mmm.recovery.CrossDomainRecovery.JournalPhase.PREPARED);
	}

	public OwnershipJournal committed() {
		return new OwnershipJournal(regionId, dimensionId, baseGeneration, targetGeneration, operation, blocks,
				operationId, targetManifestChecksum, mmm.recovery.CrossDomainRecovery.JournalPhase.REGION_COMMITTED);
	}

	public enum Operation {
		ACQUIRE,
		RELEASE
	}

	public OwnershipJournal {
		Objects.requireNonNull(regionId, "regionId");
		Objects.requireNonNull(dimensionId, "dimensionId");
		Objects.requireNonNull(operation, "operation");
		Objects.requireNonNull(operationId, "operationId");
		Objects.requireNonNull(phase, "phase");
		if (phase == mmm.recovery.CrossDomainRecovery.JournalPhase.REGION_COMMITTED && targetManifestChecksum < 0)
			throw new IllegalArgumentException("Committed ownership WAL requires its staged manifest checksum");
		if (!GenerationSequence.isNext(baseGeneration, targetGeneration)) {
			throw new IllegalArgumentException("target generation must be base generation + 1");
		}
		blocks = List.copyOf(blocks);
	}

	public record BlockEntry(long packedPosition, CompoundTag state) {
		public BlockEntry {
			state = Objects.requireNonNull(state, "state").copy();
		}

		@Override
		public CompoundTag state() {
			return state.copy();
		}
	}
}

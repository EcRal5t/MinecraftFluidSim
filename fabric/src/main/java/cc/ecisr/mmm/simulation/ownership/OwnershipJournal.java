package cc.ecisr.mmm.simulation.ownership;

import cc.ecisr.mmm.simulation.persistence.GenerationSequence;
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
		List<BlockEntry> blocks
) {
	public enum Operation {
		ACQUIRE,
		RELEASE
	}

	public OwnershipJournal {
		Objects.requireNonNull(regionId, "regionId");
		Objects.requireNonNull(dimensionId, "dimensionId");
		Objects.requireNonNull(operation, "operation");
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

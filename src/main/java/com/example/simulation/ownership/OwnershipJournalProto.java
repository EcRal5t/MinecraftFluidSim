package com.example.simulation.ownership;

import net.minecraft.nbt.CompoundTag;

import java.util.List;
import java.util.Objects;

/**
 * In-memory form of the disposable M6.0 ownership transition WAL. It records one world transition
 * (acquire or release) together with the committed generation it is based on, so recovery can decide
 * between rolling the world back and completing the committed ownership without guessing.
 */
record OwnershipJournalProto(
		String dimensionId,
		long baseGeneration,
		long targetGeneration,
		Operation operation,
		List<BlockEntry> blocks
) {
	enum Operation {
		ACQUIRE,
		RELEASE
	}

	OwnershipJournalProto {
		Objects.requireNonNull(dimensionId, "dimensionId");
		Objects.requireNonNull(operation, "operation");
		if (targetGeneration != baseGeneration + 1L) {
			throw new IllegalArgumentException("target generation must be base generation + 1");
		}
		blocks = List.copyOf(blocks);
	}

	record BlockEntry(long packedPosition, CompoundTag state) {
		BlockEntry {
			state = Objects.requireNonNull(state, "state").copy();
		}

		@Override
		public CompoundTag state() {
			return state.copy();
		}
	}
}

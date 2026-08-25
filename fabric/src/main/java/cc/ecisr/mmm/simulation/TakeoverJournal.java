package cc.ecisr.mmm.simulation;

import net.minecraft.nbt.CompoundTag;

import java.util.List;
import java.util.Objects;

/** In-memory takeover journal. The serialized field layout is owned by the codec. */
record TakeoverJournal(String dimensionId, List<BlockEntry> blocks) {
	TakeoverJournal {
		Objects.requireNonNull(dimensionId, "dimensionId");
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

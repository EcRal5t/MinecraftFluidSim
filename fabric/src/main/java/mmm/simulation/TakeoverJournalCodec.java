package mmm.simulation;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Strict codec for the takeover-journal NBT layout. */
final class TakeoverJournalCodec {
	static final String SCHEMA = "takeover-journal";
	static final int MAX_BACKUP_BLOCKS = 65_536;

	CompoundTag encode(TakeoverJournal journal) throws IOException {
		validateDimension(journal.dimensionId());
		validateBlockCount(journal.blocks().size());

		CompoundTag root = new CompoundTag();
		root.putString("Schema", SCHEMA);
		root.putString("Dimension", journal.dimensionId());
		ListTag entries = new ListTag();
		Set<Long> positions = new HashSet<>();
		for (TakeoverJournal.BlockEntry entry : journal.blocks()) {
			if (!positions.add(entry.packedPosition())) {
				throw new IOException("Takeover journal contains duplicate block position " + entry.packedPosition());
			}
			CompoundTag state = entry.state();
			validateState(state, entry.packedPosition());
			CompoundTag block = new CompoundTag();
			block.putLong("Pos", entry.packedPosition());
			block.put("State", state);
			entries.add(block);
		}
		root.put("Blocks", entries);
		return root;
	}

	TakeoverJournal decode(CompoundTag root) throws IOException {
		requireType(root, "Schema", Tag.TAG_STRING);
		String schema = root.getStringOr("Schema", "");
		if (!SCHEMA.equals(schema)) {
			throw new IOException("Unsupported takeover journal schema " + schema);
		}

		requireType(root, "Dimension", Tag.TAG_STRING);
		String dimensionId = root.getStringOr("Dimension", "");
		validateDimension(dimensionId);
		requireType(root, "Blocks", Tag.TAG_LIST);
		ListTag entries = root.getListOrEmpty("Blocks");
		validateBlockCount(entries.size());

		List<TakeoverJournal.BlockEntry> blocks = new ArrayList<>(entries.size());
		Set<Long> positions = new HashSet<>();
		for (int i = 0; i < entries.size(); i++) {
			Tag rawEntry = entries.get(i);
			if (rawEntry.getId() != Tag.TAG_COMPOUND) {
				throw new IOException("Takeover journal block entry " + i + " is not a compound");
			}
			CompoundTag block = (CompoundTag) rawEntry;
			requireType(block, "Pos", Tag.TAG_LONG);
			long position = block.getLongOr("Pos", 0L);
			if (!positions.add(position)) {
				throw new IOException("Takeover journal contains duplicate block position " + position);
			}
			requireType(block, "State", Tag.TAG_COMPOUND);
			CompoundTag state = block.getCompoundOrEmpty("State");
			validateState(state, position);
			blocks.add(new TakeoverJournal.BlockEntry(position, state));
		}
		return new TakeoverJournal(dimensionId, blocks);
	}

	private static void validateDimension(String dimensionId) throws IOException {
		if (Identifier.tryParse(dimensionId) == null) {
			throw new IOException("Invalid takeover journal dimension: " + dimensionId);
		}
	}

	private static void validateBlockCount(int size) throws IOException {
		if (size > MAX_BACKUP_BLOCKS) {
			throw new IOException("Takeover journal contains too many blocks: " + size);
		}
	}

	private static void validateState(CompoundTag state, long position) throws IOException {
		requireType(state, "Name", Tag.TAG_STRING);
		String blockId = state.getStringOr("Name", "");
		if (Identifier.tryParse(blockId) == null) {
			throw new IOException("Invalid block state id at takeover position " + position + ": " + blockId);
		}
	}

	private static void requireType(CompoundTag tag, String name, byte type) throws IOException {
		Tag value = tag.get(name);
		if (value == null || value.getId() != type) {
			throw new IOException("Takeover journal field '" + name + "' is missing or has the wrong NBT type");
		}
	}
}

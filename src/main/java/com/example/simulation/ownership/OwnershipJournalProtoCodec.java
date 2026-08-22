package com.example.simulation.ownership;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Strict codec for the disposable M6.0 ownership-proto journal layout. */
final class OwnershipJournalProtoCodec {
	static final int FORMAT_VERSION = 1;
	static final int MAX_TRANSITION_BLOCKS = 65_536;

	CompoundTag encode(OwnershipJournalProto journal) throws IOException {
		validateDimension(journal.dimensionId());
		validateBlockCount(journal.blocks().size());

		CompoundTag root = new CompoundTag();
		root.putInt("Version", FORMAT_VERSION);
		root.putString("Dimension", journal.dimensionId());
		root.putLong("BaseGeneration", journal.baseGeneration());
		root.putLong("TargetGeneration", journal.targetGeneration());
		root.putString("Operation", operationName(journal.operation()));
		ListTag entries = new ListTag();
		Set<Long> positions = new HashSet<>();
		for (OwnershipJournalProto.BlockEntry entry : journal.blocks()) {
			if (!positions.add(entry.packedPosition())) {
				throw new IOException("Ownership journal contains duplicate block position " + entry.packedPosition());
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

	OwnershipJournalProto decode(CompoundTag root) throws IOException {
		requireType(root, "Version", Tag.TAG_INT);
		int version = root.getIntOr("Version", -1);
		if (version != FORMAT_VERSION) {
			throw new IOException("Unsupported ownership journal version " + version + "; expected v" + FORMAT_VERSION);
		}

		requireType(root, "Dimension", Tag.TAG_STRING);
		String dimensionId = root.getStringOr("Dimension", "");
		validateDimension(dimensionId);

		requireType(root, "BaseGeneration", Tag.TAG_LONG);
		long baseGeneration = root.getLongOr("BaseGeneration", Long.MIN_VALUE);
		requireType(root, "TargetGeneration", Tag.TAG_LONG);
		long targetGeneration = root.getLongOr("TargetGeneration", Long.MIN_VALUE);
		if (baseGeneration < 0L || targetGeneration != baseGeneration + 1L) {
			throw new IOException("Ownership journal generations are inconsistent: base " + baseGeneration
					+ ", target " + targetGeneration);
		}

		requireType(root, "Operation", Tag.TAG_STRING);
		OwnershipJournalProto.Operation operation = parseOperation(root.getStringOr("Operation", ""));

		requireType(root, "Blocks", Tag.TAG_LIST);
		ListTag entries = root.getListOrEmpty("Blocks");
		validateBlockCount(entries.size());
		List<OwnershipJournalProto.BlockEntry> blocks = new ArrayList<>(entries.size());
		Set<Long> positions = new HashSet<>();
		for (int i = 0; i < entries.size(); i++) {
			Tag rawEntry = entries.get(i);
			if (rawEntry.getId() != Tag.TAG_COMPOUND) {
				throw new IOException("Ownership journal block entry " + i + " is not a compound");
			}
			CompoundTag block = (CompoundTag) rawEntry;
			requireType(block, "Pos", Tag.TAG_LONG);
			long position = block.getLongOr("Pos", 0L);
			if (!positions.add(position)) {
				throw new IOException("Ownership journal contains duplicate block position " + position);
			}
			requireType(block, "State", Tag.TAG_COMPOUND);
			CompoundTag state = block.getCompoundOrEmpty("State");
			validateState(state, position);
			blocks.add(new OwnershipJournalProto.BlockEntry(position, state));
		}
		return new OwnershipJournalProto(dimensionId, baseGeneration, targetGeneration, operation, blocks);
	}

	private static String operationName(OwnershipJournalProto.Operation operation) {
		return operation == OwnershipJournalProto.Operation.ACQUIRE ? "ACQUIRE" : "RELEASE";
	}

	private static OwnershipJournalProto.Operation parseOperation(String name) throws IOException {
		return switch (name) {
			case "ACQUIRE" -> OwnershipJournalProto.Operation.ACQUIRE;
			case "RELEASE" -> OwnershipJournalProto.Operation.RELEASE;
			default -> throw new IOException("Unknown ownership journal operation: " + name);
		};
	}

	private static void validateDimension(String dimensionId) throws IOException {
		if (Identifier.tryParse(dimensionId) == null) {
			throw new IOException("Invalid ownership journal dimension: " + dimensionId);
		}
	}

	private static void validateBlockCount(int size) throws IOException {
		if (size > MAX_TRANSITION_BLOCKS) {
			throw new IOException("Ownership journal contains too many blocks: " + size);
		}
	}

	private static void validateState(CompoundTag state, long position) throws IOException {
		requireType(state, "Name", Tag.TAG_STRING);
		String blockId = state.getStringOr("Name", "");
		if (Identifier.tryParse(blockId) == null) {
			throw new IOException("Invalid block state id at ownership position " + position + ": " + blockId);
		}
	}

	private static void requireType(CompoundTag tag, String name, byte type) throws IOException {
		Tag value = tag.get(name);
		if (value == null || value.getId() != type) {
			throw new IOException("Ownership journal field '" + name + "' is missing or has the wrong NBT type");
		}
	}
}

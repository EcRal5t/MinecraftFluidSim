package mmm.simulation.ownership;

import mmm.simulation.persistence.GenerationSequence;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Strict codec for the ownership transition WAL layout. */
final class OwnershipJournalCodec {
	static final String SCHEMA = "ownership-transition";
	static final int MAX_TRANSITION_BLOCKS = 65_536;

	CompoundTag encode(OwnershipJournal journal) throws IOException {
		validateDimension(journal.dimensionId());
		validateBlockCount(journal.blocks().size());

		CompoundTag root = new CompoundTag();
		root.putString("Schema", SCHEMA);
		root.putLong("RegionUuidMost", journal.regionId().getMostSignificantBits());
		root.putLong("RegionUuidLeast", journal.regionId().getLeastSignificantBits());
		root.putString("Dimension", journal.dimensionId());
		root.putLong("BaseGeneration", journal.baseGeneration());
		root.putLong("TargetGeneration", journal.targetGeneration());
		root.putString("Operation", operationName(journal.operation()));
		ListTag entries = new ListTag();
		Set<Long> positions = new HashSet<>();
		for (OwnershipJournal.BlockEntry entry : journal.blocks()) {
			if (!positions.add(entry.packedPosition())) {
				throw new IOException("Ownership WAL contains duplicate block position " + entry.packedPosition());
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

	OwnershipJournal decode(CompoundTag root) throws IOException {
		requireType(root, "Schema", Tag.TAG_STRING);
		String schema = root.getStringOr("Schema", "");
		if (!SCHEMA.equals(schema)) {
			throw new IOException("Unsupported ownership WAL schema " + schema);
		}
		requireType(root, "RegionUuidMost", Tag.TAG_LONG);
		requireType(root, "RegionUuidLeast", Tag.TAG_LONG);
		UUID regionId = new UUID(root.getLongOr("RegionUuidMost", 0L), root.getLongOr("RegionUuidLeast", 0L));

		requireType(root, "Dimension", Tag.TAG_STRING);
		String dimensionId = root.getStringOr("Dimension", "");
		validateDimension(dimensionId);

		requireType(root, "BaseGeneration", Tag.TAG_LONG);
		long baseGeneration = root.getLongOr("BaseGeneration", Long.MIN_VALUE);
		requireType(root, "TargetGeneration", Tag.TAG_LONG);
		long targetGeneration = root.getLongOr("TargetGeneration", Long.MIN_VALUE);
		GenerationSequence.requireNext(baseGeneration, targetGeneration, "Ownership WAL");

		requireType(root, "Operation", Tag.TAG_STRING);
		OwnershipJournal.Operation operation = parseOperation(root.getStringOr("Operation", ""));

		requireType(root, "Blocks", Tag.TAG_LIST);
		ListTag entries = root.getListOrEmpty("Blocks");
		validateBlockCount(entries.size());
		List<OwnershipJournal.BlockEntry> blocks = new ArrayList<>(entries.size());
		Set<Long> positions = new HashSet<>();
		for (int i = 0; i < entries.size(); i++) {
			Tag rawEntry = entries.get(i);
			if (rawEntry.getId() != Tag.TAG_COMPOUND) {
				throw new IOException("Ownership WAL block entry " + i + " is not a compound");
			}
			CompoundTag block = (CompoundTag) rawEntry;
			requireType(block, "Pos", Tag.TAG_LONG);
			long position = block.getLongOr("Pos", 0L);
			if (!positions.add(position)) {
				throw new IOException("Ownership WAL contains duplicate block position " + position);
			}
			requireType(block, "State", Tag.TAG_COMPOUND);
			CompoundTag state = block.getCompoundOrEmpty("State");
			validateState(state, position);
			blocks.add(new OwnershipJournal.BlockEntry(position, state));
		}
		return new OwnershipJournal(regionId, dimensionId, baseGeneration, targetGeneration, operation, blocks);
	}

	private static String operationName(OwnershipJournal.Operation operation) {
		return operation == OwnershipJournal.Operation.ACQUIRE ? "ACQUIRE" : "RELEASE";
	}

	private static OwnershipJournal.Operation parseOperation(String name) throws IOException {
		return switch (name) {
			case "ACQUIRE" -> OwnershipJournal.Operation.ACQUIRE;
			case "RELEASE" -> OwnershipJournal.Operation.RELEASE;
			default -> throw new IOException("Unknown ownership WAL operation: " + name);
		};
	}

	private static void validateDimension(String dimensionId) throws IOException {
		if (Identifier.tryParse(dimensionId) == null) {
			throw new IOException("Invalid ownership WAL dimension: " + dimensionId);
		}
	}

	private static void validateBlockCount(int size) throws IOException {
		if (size > MAX_TRANSITION_BLOCKS) {
			throw new IOException("Ownership WAL contains too many blocks: " + size);
		}
	}

	private static void validateState(CompoundTag state, long position) throws IOException {
		requireType(state, "Name", Tag.TAG_STRING);
		String blockId = state.getStringOr("Name", "");
		if (!"minecraft:water".equals(blockId)) {
			throw new IOException("Ownership position " + position
					+ " is not pure Minecraft water: " + blockId);
		}
	}

	private static void requireType(CompoundTag tag, String name, byte type) throws IOException {
		Tag value = tag.get(name);
		if (value == null || value.getId() != type) {
			throw new IOException("Ownership WAL field '" + name + "' is missing or has the wrong NBT type");
		}
	}
}

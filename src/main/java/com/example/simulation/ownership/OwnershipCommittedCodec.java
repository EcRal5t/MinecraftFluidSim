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
import java.util.UUID;

/** Strict codec for the committed ownership registry v1 layout. */
final class OwnershipCommittedCodec {
	static final int FORMAT_VERSION = 1;
	static final int MAX_OWNED_BLOCKS = 65_536;

	CompoundTag encode(OwnershipCommitted state) throws IOException {
		if (Identifier.tryParse(state.dimensionId()) == null) {
			throw new IOException("Invalid committed ownership dimension: " + state.dimensionId());
		}
		validateOwnedCount(state.ownedBlocks().size());

		CompoundTag root = new CompoundTag();
		root.putInt("Version", FORMAT_VERSION);
		root.putLong("RegionUuidMost", state.regionId().getMostSignificantBits());
		root.putLong("RegionUuidLeast", state.regionId().getLeastSignificantBits());
		root.putString("Dimension", state.dimensionId());
		root.putLong("Generation", state.generation());
		ListTag entries = new ListTag();
		Set<Long> positions = new HashSet<>();
		for (OwnershipJournal.BlockEntry entry : state.ownedBlocks()) {
			if (!positions.add(entry.packedPosition())) {
				throw new IOException("Committed ownership contains duplicate block position " + entry.packedPosition());
			}
			validateWaterState(entry.state(), entry.packedPosition());
			CompoundTag block = new CompoundTag();
			block.putLong("Pos", entry.packedPosition());
			block.put("State", entry.state());
			entries.add(block);
		}
		root.put("Owned", entries);
		return root;
	}

	OwnershipCommitted decode(CompoundTag root) throws IOException {
		requireType(root, "Version", Tag.TAG_INT);
		int version = root.getIntOr("Version", -1);
		if (version != FORMAT_VERSION) {
			throw new IOException("Unsupported committed ownership version " + version + "; expected v" + FORMAT_VERSION);
		}
		requireType(root, "RegionUuidMost", Tag.TAG_LONG);
		requireType(root, "RegionUuidLeast", Tag.TAG_LONG);
		UUID regionId = new UUID(root.getLongOr("RegionUuidMost", 0L), root.getLongOr("RegionUuidLeast", 0L));
		requireType(root, "Dimension", Tag.TAG_STRING);
		String dimensionId = root.getStringOr("Dimension", "");
		if (Identifier.tryParse(dimensionId) == null) {
			throw new IOException("Invalid committed ownership dimension: " + dimensionId);
		}
		requireType(root, "Generation", Tag.TAG_LONG);
		long generation = root.getLongOr("Generation", -1L);
		if (generation < 0L) {
			throw new IOException("Committed ownership generation is negative: " + generation);
		}
		requireType(root, "Owned", Tag.TAG_LIST);
		ListTag entries = root.getListOrEmpty("Owned");
		validateOwnedCount(entries.size());
		List<OwnershipJournal.BlockEntry> owned = new ArrayList<>(entries.size());
		Set<Long> positions = new HashSet<>();
		for (int i = 0; i < entries.size(); i++) {
			Tag rawEntry = entries.get(i);
			if (rawEntry.getId() != Tag.TAG_COMPOUND) {
				throw new IOException("Committed ownership entry " + i + " is not a compound");
			}
			CompoundTag block = (CompoundTag) rawEntry;
			requireType(block, "Pos", Tag.TAG_LONG);
			long position = block.getLongOr("Pos", 0L);
			if (!positions.add(position)) {
				throw new IOException("Committed ownership contains duplicate block position " + position);
			}
			requireType(block, "State", Tag.TAG_COMPOUND);
			CompoundTag state = block.getCompoundOrEmpty("State");
			validateWaterState(state, position);
			owned.add(new OwnershipJournal.BlockEntry(position, state));
		}
		return new OwnershipCommitted(regionId, dimensionId, generation, owned);
	}

	private static void validateOwnedCount(int size) throws IOException {
		if (size > MAX_OWNED_BLOCKS) {
			throw new IOException("Committed ownership contains too many blocks: " + size);
		}
	}

	private static void validateWaterState(CompoundTag state, long position) throws IOException {
		requireType(state, "Name", Tag.TAG_STRING);
		String blockId = state.getStringOr("Name", "");
		if (!"minecraft:water".equals(blockId)) {
			throw new IOException("Committed ownership position " + position
					+ " is not pure Minecraft water: " + blockId);
		}
	}

	private static void requireType(CompoundTag tag, String name, byte type) throws IOException {
		Tag value = tag.get(name);
		if (value == null || value.getId() != type) {
			throw new IOException("Committed ownership field '" + name + "' is missing or has the wrong NBT type");
		}
	}
}

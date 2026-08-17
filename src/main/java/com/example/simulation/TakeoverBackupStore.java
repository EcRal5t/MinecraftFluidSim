package com.example.simulation;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

/** Crash-recovery journal for a bounded vanilla-water takeover. */
final class TakeoverBackupStore {
	private static final String FILE_NAME = "realwater-takeover-backup.nbt";
	private static final int FORMAT_VERSION = 1;
	private static final int MAX_BACKUP_BLOCKS = 65_536;

	private TakeoverBackupStore() { }

	static void write(MinecraftServer server, ResourceKey<Level> dimension, Map<BlockPos, BlockState> blocks) throws IOException {
		CompoundTag root = new CompoundTag();
		root.putInt("Version", FORMAT_VERSION);
		root.putString("Dimension", dimension.identifier().toString());
		ListTag entries = new ListTag();
		for (Map.Entry<BlockPos, BlockState> entry : blocks.entrySet()) {
			CompoundTag block = new CompoundTag();
			block.putLong("Pos", entry.getKey().asLong());
			block.put("State", NbtUtils.writeBlockState(entry.getValue()));
			entries.add(block);
		}
		root.put("Blocks", entries);

		Path target = path(server);
		Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
		Files.createDirectories(target.getParent());
		NbtIo.writeCompressed(root, temporary);
		try {
			Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException ignored) {
			Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	static RecoveryResult recover(MinecraftServer server) throws IOException {
		Path path = path(server);
		if (!Files.exists(path)) return RecoveryResult.NONE;
		CompoundTag root = NbtIo.readCompressed(path, NbtAccounter.create(16L * 1024L * 1024L));
		if (root.getIntOr("Version", -1) != FORMAT_VERSION) throw new IOException("Unsupported takeover backup version");
		Identifier dimensionId = Identifier.tryParse(root.getStringOr("Dimension", ""));
		if (dimensionId == null) throw new IOException("Invalid takeover backup dimension");
		ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, dimensionId);
		ServerLevel level = server.getLevel(dimension);
		if (level == null) throw new IOException("Takeover backup dimension is not loaded: " + dimensionId);
		ListTag entries = root.getListOrEmpty("Blocks");
		if (entries.size() > MAX_BACKUP_BLOCKS) throw new IOException("Takeover backup contains too many blocks: " + entries.size());

		int restored = 0, skipped = 0;
		for (int i = 0; i < entries.size(); i++) {
			CompoundTag block = entries.getCompoundOrEmpty(i);
			BlockPos pos = BlockPos.of(block.getLongOr("Pos", 0L));
			BlockState original = NbtUtils.readBlockState(
					server.registryAccess().lookupOrThrow(Registries.BLOCK),
					block.getCompoundOrEmpty("State")
			);
			BlockState current = level.getBlockState(pos);
			if (!current.isAir() && !current.is(Blocks.WATER)) {
				skipped++;
				continue;
			}
			if (level.setBlock(pos, original, Block.UPDATE_ALL)) restored++;
			else skipped++;
		}
		Files.delete(path);
		return new RecoveryResult(true, restored, skipped);
	}

	static void delete(MinecraftServer server) throws IOException {
		Files.deleteIfExists(path(server));
	}

	private static Path path(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).resolve(FILE_NAME);
	}

	record RecoveryResult(boolean found, int restoredBlocks, int skippedBlocks) {
		private static final RecoveryResult NONE = new RecoveryResult(false, 0, 0);
	}
}

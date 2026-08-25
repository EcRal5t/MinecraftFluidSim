package cc.ecisr.mmm.simulation;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Minecraft adapter for the testable takeover journal/file transaction. */
final class TakeoverBackupStore {
	private TakeoverBackupStore() { }

	static BeginResult begin(
			MinecraftServer server,
			ResourceKey<Level> dimension,
			Map<BlockPos, BlockState> blocks
	) throws IOException {
		List<Map.Entry<BlockPos, BlockState>> ordered = new ArrayList<>(blocks.entrySet());
		ordered.sort(Comparator.comparingLong(entry -> entry.getKey().asLong()));
		List<TakeoverJournal.BlockEntry> entries = new ArrayList<>(ordered.size());
		for (Map.Entry<BlockPos, BlockState> entry : ordered) {
			entries.add(new TakeoverJournal.BlockEntry(
					entry.getKey().asLong(),
					NbtUtils.writeBlockState(entry.getValue())
			));
		}

		Path root = worldRoot(server);
		TakeoverJournal journal = new TakeoverJournal(dimension.identifier().toString(), entries);
		TakeoverTransaction.BeginResult result = TakeoverTransaction.begin(
				new TakeoverJournalFiles(root),
				journal,
				warningText(root, journal),
				new MinecraftWorldAccess(server),
				TakeoverTransaction.FailureInjector.NONE
		);
		List<BlockPos> removed = result.removedPositions().stream().map(BlockPos::of).toList();
		return new BeginResult(removed);
	}

	static RecoveryResult recover(MinecraftServer server) throws IOException {
		TakeoverTransaction.RecoveryResult result = TakeoverTransaction.recover(
				new TakeoverJournalFiles(worldRoot(server)),
				new MinecraftWorldAccess(server),
				TakeoverTransaction.FailureInjector.NONE
		);
		return new RecoveryResult(
				result.found(),
				result.restoredBlocks(),
				result.alreadyRestoredBlocks(),
				result.skippedBlocks()
		);
	}

	static boolean isLocked(MinecraftServer server) {
		return new TakeoverJournalFiles(worldRoot(server)).isLocked();
	}

	static String lockDescription(MinecraftServer server) {
		return new TakeoverJournalFiles(worldRoot(server)).lockDescription();
	}

	static String warningText(Path root, TakeoverJournal journal) {
		return """
				REAL WATER TAKEOVER RECOVERY WARNING / 物质、运动与力学接管恢复警告

				Do not remove or replace the Matter, Motion & Mechanics mod while this file exists.
				此文件存在时，请勿卸载或替换 Matter, Motion & Mechanics Mod。

				World directory / 世界目录: %s
				Dimension / 维度: %s
				Journal schema / 日志布局: takeover-journal
				Backed-up blocks / 备份方块数: %d

				Recovery / 恢复步骤:
				1. Keep a full backup of this world. / 保留完整世界备份。
				2. Restart this world with the same Matter, Motion & Mechanics build.
				   使用相同的 Matter, Motion & Mechanics 构建重新启动世界。
				3. Wait for startup recovery, or run /mmm remove for the active domain.
				   等待启动恢复；若接管域仍活动，执行 /mmm remove。
				4. Confirm both this warning and mmm-takeover-backup.nbt are gone before removing or replacing the mod.
				   确认本警告与 mmm-takeover-backup.nbt 均已消失后，才能卸载或替换 Mod。
				""".formatted(root, journal.dimensionId(), journal.blocks().size());
	}

	private static Path worldRoot(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT);
	}

	private static final class MinecraftWorldAccess implements TakeoverTransaction.WorldAccess {
		private final MinecraftServer server;

		private MinecraftWorldAccess(MinecraftServer server) {
			this.server = server;
		}

		@Override
		public boolean isDimensionLoaded(String dimensionId) throws IOException {
			return level(dimensionId) != null;
		}

		@Override
		public boolean remove(String dimensionId, TakeoverJournal.BlockEntry block) throws IOException {
			ServerLevel level = requireLevel(dimensionId);
			BlockPos position = BlockPos.of(block.packedPosition());
			BlockState original = originalState(block);
			if (!level.getBlockState(position).equals(original)) return false;
			level.setBlock(position, Blocks.AIR.defaultBlockState(),
					Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS);
			if (!level.getBlockState(position).isAir()) {
				throw new IOException("Could not remove vanilla water at " + position.toShortString());
			}
			return true;
		}

		@Override
		public TakeoverTransaction.RestoreOutcome restore(
				String dimensionId,
				TakeoverJournal.BlockEntry block
		) throws IOException {
			ServerLevel level = requireLevel(dimensionId);
			BlockPos position = BlockPos.of(block.packedPosition());
			BlockState original = originalState(block);
			BlockState current = level.getBlockState(position);
			if (current.equals(original)) return TakeoverTransaction.RestoreOutcome.ALREADY_RESTORED;
			if (!current.isAir() && !current.is(Blocks.WATER)) {
				return TakeoverTransaction.RestoreOutcome.SKIPPED_OCCUPIED;
			}
			level.setBlock(position, original, Block.UPDATE_ALL);
			if (!level.getBlockState(position).equals(original)) {
				throw new IOException("Could not restore takeover block at " + position.toShortString());
			}
			return TakeoverTransaction.RestoreOutcome.RESTORED;
		}

		private BlockState originalState(TakeoverJournal.BlockEntry block) throws IOException {
			BlockState original = NbtUtils.readBlockState(
					server.registryAccess().lookupOrThrow(Registries.BLOCK),
					block.state()
			);
			if (!original.is(Blocks.WATER)) {
				throw new IOException("Takeover journal contains a non-water state at " + BlockPos.of(block.packedPosition()).toShortString());
			}
			return original;
		}

		private ServerLevel requireLevel(String dimensionId) throws IOException {
			ServerLevel level = level(dimensionId);
			if (level == null) throw new IOException("Takeover journal dimension is not loaded: " + dimensionId);
			return level;
		}

		private ServerLevel level(String dimensionId) throws IOException {
			Identifier identifier = Identifier.tryParse(dimensionId);
			if (identifier == null) throw new IOException("Invalid takeover journal dimension: " + dimensionId);
			ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, identifier);
			return server.getLevel(dimension);
		}
	}

	record BeginResult(List<BlockPos> removedPositions) {
		int removedBlocks() {
			return removedPositions.size();
		}
	}

	record RecoveryResult(boolean found, int restoredBlocks, int alreadyRestoredBlocks, int skippedBlocks) { }
}

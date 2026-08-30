package cc.ecisr.mmm.simulation;

import cc.ecisr.mmm.MmmMod;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * The vanilla-water takeover transaction: it temporarily removes standalone water blocks inside a
 * region and keeps an on-disk journal so every removed block can be restored. The journal and the
 * WARNING are published before any world mutation, so a crash always yields either the complete
 * previous world state or a complete, provably recoverable takeover.
 */
final class MinecraftTakeoverAdapter {
	private final Map<BlockPos, BlockState> originalBlocks = new HashMap<>();
	private ResourceKey<Level> dimension;
	private boolean maintenanceEnabled;
	private String lastFailure;

	String lastFailure() {
		return lastFailure;
	}

	/** Blocks this process removed and still suppresses; used only for status output. */
	int pendingBlockCount() {
		return originalBlocks.size();
	}

	private boolean isMaintaining(ResourceKey<Level> candidate) {
		return dimension != null && dimension.equals(candidate);
	}

	static String lockDescription(MinecraftServer server) {
		return TakeoverBackupStore.lockDescription(server);
	}

	/**
	 * Removes every standalone vanilla water block inside the region and records its preimage.
	 * A negative {@code removedBlocks} means the transaction was refused and, unless the failure
	 * says otherwise, the world was already rolled back.
	 */
	BeginResult begin(ServerLevel level, FluidRegion region, int cellsPerBlock) {
		Map<BlockPos, BlockState> candidates = new HashMap<>();
		int blocksX = region.sizeX() / cellsPerBlock;
		int blocksY = region.sizeY() / cellsPerBlock;
		int blocksZ = region.sizeZ() / cellsPerBlock;
		for (int blockZ = 0; blockZ < blocksZ; blockZ++) for (int blockY = 0; blockY < blocksY; blockY++) {
			for (int blockX = 0; blockX < blocksX; blockX++) {
				BlockPos position = region.origin().offset(blockX, blockY, blockZ);
				BlockState state = level.getBlockState(position);
				if (!state.is(Blocks.WATER)) continue;
				candidates.put(position.immutable(), state);
			}
		}
		if (candidates.isEmpty()) return BeginResult.EMPTY;
		try {
			TakeoverBackupStore.BeginResult result =
					TakeoverBackupStore.begin(level.getServer(), level.dimension(), candidates);
			dimension = level.dimension();
			for (BlockPos position : result.removedPositions()) {
				originalBlocks.put(position, candidates.get(position));
			}
			maintenanceEnabled = result.removedBlocks() > 0;
			lastFailure = null;
			if (result.removedBlocks() == 0) {
				RestorationStats cleanup = restore(level.getServer());
				if (!cleanup.completed()) return new BeginResult(-1, lastFailure);
			}
			return new BeginResult(result.removedBlocks(), null);
		} catch (IOException | RuntimeException exception) {
			String beginFailure = "接管事务启动失败：" + exception.getMessage();
			MmmMod.LOGGER.error("Refusing water takeover; no recovery file will be overwritten", exception);
			RestorationStats rollback = restore(level.getServer());
			lastFailure = rollback.completed()
					? beginFailure + "；已安全回滚，原版水未保持接管状态"
					: beginFailure + "；自动回滚未完成，journal 已锁定新 takeover：" + rollback.failure();
			return new BeginResult(-1, lastFailure);
		}
	}

	/** Restores every journaled block; the journal is cleared only after the world pass completes. */
	RestorationStats restore(MinecraftServer server) {
		// Once restoration begins, never let the tick hook remove a block that an interrupted pass already restored.
		maintenanceEnabled = false;
		try {
			TakeoverBackupStore.RecoveryResult result = TakeoverBackupStore.recover(server);
			if (!result.found() && !originalBlocks.isEmpty()) {
				lastFailure = "活动接管缺少 on-disk journal；已停止替换域，需从世界备份核对";
				MmmMod.LOGGER.error(lastFailure);
				return RestorationStats.failed(false, 0, 0, 0, lastFailure);
			}
			originalBlocks.clear();
			dimension = null;
			lastFailure = null;
			return new RestorationStats(
					result.found(), result.restoredBlocks(), result.alreadyRestoredBlocks(), result.skippedBlocks(), true, null
			);
		} catch (TakeoverTransaction.RecoveryException exception) {
			if (exception.worldPassCompleted()) {
				originalBlocks.clear();
				dimension = null;
			}
			lastFailure = "恢复未完成，journal 已保留并锁定新 takeover：" + exception.getMessage();
			MmmMod.LOGGER.error("Could not finish the Matter, Motion & Mechanics takeover recovery; recovery files were preserved", exception);
			return RestorationStats.failed(
					true,
					exception.restoredBlocks(),
					exception.alreadyRestoredBlocks(),
					exception.skippedBlocks(),
					lastFailure
			);
		} catch (IOException | RuntimeException exception) {
			lastFailure = "恢复未完成，journal 已保留并锁定新 takeover：" + exception.getMessage();
			MmmMod.LOGGER.error("Could not recover the unfinished Matter, Motion & Mechanics takeover; recovery files were preserved", exception);
			return RestorationStats.failed(TakeoverBackupStore.isLocked(server), 0, 0, 0, lastFailure);
		}
	}

	/** Resolves the owned dimension and keeps its positions free of reflowing vanilla water. */
	void maintainActive(MinecraftServer server) {
		if (dimension == null) return;
		ServerLevel level = server.getLevel(dimension);
		if (level != null) maintain(level);
	}

	/** Keeps owned positions free of reflowing vanilla water while a takeover is active. */
	private void maintain(ServerLevel level) {
		if (!maintenanceEnabled || originalBlocks.isEmpty() || !isMaintaining(level.dimension())) return;
		for (BlockPos position : originalBlocks.keySet()) {
			if (level.getBlockState(position).is(Blocks.WATER)) {
				level.setBlock(position, Blocks.AIR.defaultBlockState(),
						Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS);
			}
		}
	}

	record BeginResult(int removedBlocks, String failure) {
		static final BeginResult EMPTY = new BeginResult(0, null);
	}
}

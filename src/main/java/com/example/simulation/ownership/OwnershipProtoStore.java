package com.example.simulation.ownership;

import com.example.RealWaterMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Minecraft adapter for the disposable M6.0 "air + independent registry" ownership prototype.
 * Only pure {@code Blocks.WATER} positions are ever taken over, player-placed blocks always win,
 * and the steady state after a committed transition is a normal world plus one committed.nbt file.
 */
public final class OwnershipProtoStore {
	public static final int MIN_ACQUIRE_RADIUS = 1;
	public static final int MAX_ACQUIRE_RADIUS = 4;

	private OwnershipProtoStore() { }

	public static AcquireOutcome acquire(ServerPlayer player, int radius) {
		ServerLevel level = player.level();
		String dimensionId = level.dimension().identifier().toString();
		List<OwnershipJournalProto.BlockEntry> candidates = new ArrayList<>();
		BlockPos center = player.blockPosition();
		int minY = Math.max(level.getMinY(), center.getY() - radius);
		int maxY = Math.min(level.getMaxY() - 1, center.getY() + radius);
		for (BlockPos position : BlockPos.betweenClosed(
				center.getX() - radius, minY, center.getZ() - radius,
				center.getX() + radius, maxY, center.getZ() + radius)) {
			BlockState state = level.getBlockState(position);
			if (state.is(Blocks.WATER)) {
				candidates.add(new OwnershipJournalProto.BlockEntry(
						position.asLong(), NbtUtils.writeBlockState(state)));
			}
		}
		if (candidates.isEmpty()) {
			return new AcquireOutcome("半径内没有纯原版水方块（waterlogged 方块不适用）", 0L, 0, 0);
		}
		candidates.sort(Comparator.comparingLong(OwnershipJournalProto.BlockEntry::packedPosition));

		OwnershipProtoFiles files = files(level.getServer());
		try {
			OwnershipProtoTransaction.BeginResult result = OwnershipProtoTransaction.beginAcquire(
					files,
					dimensionId,
					candidates,
					warningText(files, dimensionId, OwnershipJournalProto.Operation.ACQUIRE, candidates.size()),
					new MinecraftWorldAccess(level.getServer()),
					OwnershipProtoTransaction.FailureInjector.NONE
			);
			return new AcquireOutcome(null, result.generation(), result.transitionBlocks(), result.totalOwnedBlocks());
		} catch (IOException exception) {
			RealWaterMod.LOGGER.error("M6.0 ownership prototype acquire failed", exception);
			return new AcquireOutcome(exception.getMessage(), 0L, 0, 0);
		}
	}

	public static ReleaseOutcome release(MinecraftServer server) {
		OwnershipProtoFiles files = files(server);
		try {
			OwnershipCommittedProto base = files.hasCommitted() ? files.readCommitted() : null;
			OwnershipProtoTransaction.BeginResult result = OwnershipProtoTransaction.beginRelease(
					files,
					warningText(files,
							base == null ? "" : base.dimensionId(),
							OwnershipJournalProto.Operation.RELEASE,
							base == null ? 0 : base.ownedBlocks().size()),
					new MinecraftWorldAccess(server),
					OwnershipProtoTransaction.FailureInjector.NONE
			);
			return new ReleaseOutcome(null, result.generation());
		} catch (IOException exception) {
			RealWaterMod.LOGGER.error("M6.0 ownership prototype release failed", exception);
			return new ReleaseOutcome(exception.getMessage(), 0L);
		}
	}

	/** Startup recovery: PRE-COMMIT rolls the world back, POST-COMMIT completes ownership. */
	public static void recoverAtStartup(MinecraftServer server) {
		OwnershipProtoFiles files = files(server);
		if (!files.isLocked()) return;
		try {
			OwnershipProtoTransaction.RecoveryResult result = OwnershipProtoTransaction.recover(
					files, new MinecraftWorldAccess(server), OwnershipProtoTransaction.FailureInjector.NONE);
			if (result.kind() != OwnershipProtoTransaction.RecoveryResult.Kind.NONE) {
				RealWaterMod.LOGGER.warn(
						"Recovered unfinished M6.0 ownership prototype transition: {} ({}, {}, {})",
						result.kind(), result.restored(), result.alreadyRestored(), result.skipped());
			}
		} catch (IOException exception) {
			RealWaterMod.LOGGER.error(
					"M6.0 ownership prototype is LOCKED at startup; new prototype transitions are refused: {}",
					exception.getMessage());
		}
	}

	public static String reconcileReport(MinecraftServer server) {
		OwnershipProtoFiles files = files(server);
		try {
			OwnershipProtoTransaction.ReconcileResult result = OwnershipProtoTransaction.reconcile(
					files, new MinecraftWorldAccess(server));
			if (result.ownedBlocks() == 0) {
				return "原型 B 无已提交 ownership（第 0 代）；journal 锁：" + (result.transitionLocked() ? "有" : "无");
			}
			return String.format(
					"原型 B 已提交 ownership：第 %d 代，持有 %d 个位置（air %d、玩家方块冲突 %d、原版水回流 %d）；journal 锁：%s",
					result.generation(), result.ownedBlocks(), result.ownedAir(),
					result.playerConflicts(), result.waterConflicts(), result.transitionLocked() ? "有" : "无");
		} catch (IOException exception) {
			return "原型 B reconcile 失败（保持锁定）：" + exception.getMessage();
		}
	}

	public static String statusReport(MinecraftServer server) {
		OwnershipProtoFiles files = files(server);
		if (!files.hasCommitted() && !files.isLocked()) {
			return "原型 B 状态：无 committed registry，无未解决事务";
		}
		try {
			OwnershipCommittedProto committed = files.hasCommitted() ? files.readCommitted() : null;
			return "原型 B 状态：committed "
					+ (committed == null
					? "不可读（锁定）"
					: "第 " + committed.generation() + " 代，维度 " + committed.dimensionId()
							+ "，持有 " + committed.ownedBlocks().size() + " 个位置")
					+ "；journal 锁：" + files.lockDescription();
		} catch (IOException exception) {
			return "原型 B 状态：committed 不可读（" + exception.getMessage() + "）；journal 锁：" + files.lockDescription();
		}
	}

	static String warningText(OwnershipProtoFiles files, String dimensionId, OwnershipJournalProto.Operation operation, int entries) {
		return """
				REAL WATER OWNERSHIP PROTOTYPE RECOVERY WARNING / 真实水体所有权原型恢复警告

				This file belongs to the disposable M6.0 "air + independent registry" ownership prototype.
				本文件属于 M6.0"空气 + 独立注册表"所有权原型（可丢弃）。

				Directory / 目录: %s
				Dimension / 维度: %s
				Journal version / 日志版本: 1
				Operation / 操作: %s
				Transition entries / 事务条目数: %d

				Recovery / 恢复步骤:
				1. Keep a full backup of this world. / 保留完整世界备份。
				2. Restart this world with this mod; startup recovery compares the committed generation
				   and either rolls the world back or completes the committed ownership.
				   使用本 Mod 重启世界；启动恢复按已提交代次判定，回滚世界或完成已提交 ownership。
				3. Or run /realwater ownproto reconcile to inspect the committed prototype state.
				   或执行 /realwater ownproto reconcile 检查原型已提交状态。
				4. Confirm journal.nbt and this WARNING are gone before removing the prototype or the mod.
				   确认 journal.nbt 与本 WARNING 消失后，才能删除原型或卸载 Mod。
				""".formatted(files.directoryPath(), dimensionId, operation, entries);
	}

	private static OwnershipProtoFiles files(MinecraftServer server) {
		return new OwnershipProtoFiles(server.getWorldPath(LevelResource.ROOT));
	}

	private static final class MinecraftWorldAccess implements OwnershipProtoTransaction.WorldAccess {
		private final MinecraftServer server;

		private MinecraftWorldAccess(MinecraftServer server) {
			this.server = server;
		}

		@Override
		public boolean isDimensionLoaded(String dimensionId) throws IOException {
			return level(dimensionId) != null;
		}

		@Override
		public OwnershipProtoTransaction.RemoveOutcome removeWater(
				String dimensionId,
				OwnershipJournalProto.BlockEntry block
		) throws IOException {
			ServerLevel level = requireLevel(dimensionId);
			BlockPos position = BlockPos.of(block.packedPosition());
			BlockState original = originalState(block);
			BlockState current = level.getBlockState(position);
			if (current.isAir()) return OwnershipProtoTransaction.RemoveOutcome.ALREADY_CLEAR;
			if (!current.equals(original)) return OwnershipProtoTransaction.RemoveOutcome.SKIPPED_OCCUPIED;
			if (!level.setBlock(position, Blocks.AIR.defaultBlockState(),
					Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS)) {
				throw new IOException("Could not remove owned water at " + position.toShortString());
			}
			if (!level.getBlockState(position).isAir()) {
				throw new IOException("Owned water was not removed at " + position.toShortString());
			}
			return OwnershipProtoTransaction.RemoveOutcome.REMOVED;
		}

		@Override
		public OwnershipProtoTransaction.RestoreOutcome restore(
				String dimensionId,
				OwnershipJournalProto.BlockEntry block
		) throws IOException {
			ServerLevel level = requireLevel(dimensionId);
			BlockPos position = BlockPos.of(block.packedPosition());
			BlockState original = originalState(block);
			BlockState current = level.getBlockState(position);
			if (current.equals(original)) return OwnershipProtoTransaction.RestoreOutcome.ALREADY_RESTORED;
			if (!current.isAir() && !current.is(Blocks.WATER)) {
				return OwnershipProtoTransaction.RestoreOutcome.SKIPPED_OCCUPIED;
			}
			level.setBlock(position, original, Block.UPDATE_ALL);
			if (!level.getBlockState(position).equals(original)) {
				throw new IOException("Could not restore ownership block at " + position.toShortString());
			}
			return OwnershipProtoTransaction.RestoreOutcome.RESTORED;
		}

		@Override
		public OwnershipProtoTransaction.InspectOutcome inspect(
				String dimensionId,
				OwnershipJournalProto.BlockEntry block
		) throws IOException {
			ServerLevel level = requireLevel(dimensionId);
			BlockPos position = BlockPos.of(block.packedPosition());
			BlockState original = originalState(block);
			BlockState current = level.getBlockState(position);
			if (current.isAir()) return OwnershipProtoTransaction.InspectOutcome.OWNED_AIR;
			if (current.equals(original)) return OwnershipProtoTransaction.InspectOutcome.PREIMAGE_PRESENT;
			return OwnershipProtoTransaction.InspectOutcome.OCCUPIED;
		}

		private BlockState originalState(OwnershipJournalProto.BlockEntry block) throws IOException {
			BlockState original = NbtUtils.readBlockState(
					server.registryAccess().lookupOrThrow(Registries.BLOCK),
					block.state()
			);
			if (!original.is(Blocks.WATER)) {
				throw new IOException("Ownership prototype contains a non-water state at "
						+ BlockPos.of(block.packedPosition()).toShortString());
			}
			return original;
		}

		private ServerLevel requireLevel(String dimensionId) throws IOException {
			ServerLevel level = level(dimensionId);
			if (level == null) throw new IOException("Ownership prototype dimension is not loaded: " + dimensionId);
			return level;
		}

		private ServerLevel level(String dimensionId) throws IOException {
			Identifier identifier = Identifier.tryParse(dimensionId);
			if (identifier == null) throw new IOException("Invalid ownership prototype dimension: " + dimensionId);
			ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, identifier);
			return server.getLevel(dimension);
		}
	}

	public record AcquireOutcome(String failure, long generation, int transitionBlocks, int totalOwnedBlocks) { }

	public record ReleaseOutcome(String failure, long generation) { }
}

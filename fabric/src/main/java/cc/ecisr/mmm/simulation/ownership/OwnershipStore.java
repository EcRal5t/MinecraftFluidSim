package cc.ecisr.mmm.simulation.ownership;

import cc.ecisr.mmm.MmmMod;
import cc.ecisr.mmm.chemistry.ChemicalRegistry;
import cc.ecisr.mmm.fluid.air.GasRegistry;
import cc.ecisr.mmm.granular.GranularRegistry;
import cc.ecisr.mmm.simulation.persistence.RegionStateFiles;
import cc.ecisr.mmm.thermal.ThermalRegistry;
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
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Minecraft adapter for controller-region world ownership. Only pure {@code Blocks.WATER} is ever
 * taken over, player-placed blocks always win during recovery, and the steady state after a
 * committed transaction is a vanilla-clean world plus one committed.nbt per owned region.
 */
public final class OwnershipStore {
	private final Path worldRoot;
	private final RegionStateFiles regionFiles;

	public OwnershipStore(Path worldRoot) {
		this(worldRoot, null);
	}

	public OwnershipStore(Path worldRoot, ChemicalRegistry chemicalRegistry) {
		this(worldRoot, chemicalRegistry, null);
	}

	public OwnershipStore(Path worldRoot, ChemicalRegistry chemicalRegistry, GranularRegistry granularRegistry) {
		this(worldRoot, chemicalRegistry, granularRegistry, null, null);
	}

	public OwnershipStore(
			Path worldRoot, ChemicalRegistry chemicalRegistry, GranularRegistry granularRegistry,
			ThermalRegistry thermalRegistry, GasRegistry gasRegistry
	) {
		this.worldRoot = worldRoot.toAbsolutePath().normalize();
		this.regionFiles = thermalRegistry != null || gasRegistry != null
				? new RegionStateFiles(worldRoot, chemicalRegistry, granularRegistry, thermalRegistry, gasRegistry)
				: granularRegistry == null
					? chemicalRegistry == null ? new RegionStateFiles(worldRoot) : new RegionStateFiles(worldRoot, chemicalRegistry)
					: new RegionStateFiles(worldRoot, chemicalRegistry, granularRegistry);
	}

	public OwnershipFiles files(UUID regionId) {
		return new OwnershipFiles(worldRoot, regionId);
	}

	public Path worldRoot() {
		return worldRoot;
	}

	/**
	 * Read-only startup scan. Unresolved WALs lock new controller creation until recovery finishes;
	 * unreadable or inconsistent committed registries stay locked and reported.
	 */
	public List<String> scanIssues() {
		Discovery discovery = discoverRegions();
		List<String> issues = new ArrayList<>(discovery.issues());
		for (UUID regionId : discovery.regions()) {
			OwnershipFiles files = files(regionId);
			try {
				files.validateKnownLayout();
				if (files.hasWAL() || files.hasWarning()) {
					issues.add(regionId + " 存在未解决 ownership 事务（" + files.lockDescription() + "）；等待启动恢复");
					continue;
				}
				if (files.hasCommitted()) {
					OwnershipCommitted committed = files.readCommitted();
					if (!committed.regionId().equals(regionId)) {
						issues.add(regionId + " 的 committed registry 记录了其他区域 " + committed.regionId());
						continue;
					}
					long head = regionFiles.committedGeneration(regionId);
					if (head == 0L && !committed.ownedBlocks().isEmpty()) {
						issues.add(regionId + " 的 ownership 没有对应 region-state；世界水已被持有，需人工核对");
					} else if (committed.generation() > head) {
						issues.add(regionId + " 的 committed generation " + committed.generation()
								+ " 超前于 region HEAD " + head);
					}
				}
			} catch (IOException exception) {
				issues.add(regionId + " 的 ownership 状态不可读（已锁定）：" + exception.getMessage());
			}
		}
		return issues;
	}

	/** Boot-time recovery of unresolved transitions; the world must already be fully started. */
	public List<String> recoverUnfinished(MinecraftServer server) {
		Discovery discovery = discoverRegions();
		List<String> issues = new ArrayList<>(discovery.issues());
		for (UUID regionId : discovery.regions()) {
			OwnershipFiles files = files(regionId);
			try {
				files.validateKnownLayout();
				// A corrupt committed registry is itself a lock even when there is no WAL to replay.
				// Force strict decoding here instead of asking the coordinator to recover a transaction
				// which does not exist.
				if (!files.hasWAL() && !files.hasWarning()) {
					if (files.hasCommitted()) files.readCommitted();
					files.deleteStaleTemporaryFiles();
					continue;
				}
				if (!files.isLocked()) {
					files.deleteStaleTemporaryFiles();
					continue;
				}
				OwnershipCoordinator.RecoveryResult result = OwnershipCoordinator.recover(
						regionFiles, files, worldAccess(server), OwnershipCoordinator.FailureInjector.NONE);
				MmmMod.LOGGER.warn("Recovered unfinished world ownership transition for region {}: {} ({}, {}, {})",
						regionId, result.kind(), result.restored(), result.alreadyRestored(), result.skipped());
			} catch (IOException exception) {
				MmmMod.LOGGER.error(
						"World ownership for region {} is LOCKED; the region stays paused and new transitions are refused: {}",
						regionId, exception.getMessage());
				issues.add(regionId + " ownership LOCKED：" + exception.getMessage());
			}
		}
		return issues;
	}

	private Discovery discoverRegions() {
		Path root = worldRoot.resolve("mmm").resolve(OwnershipFiles.ROOT_DIRECTORY_NAME);
		if (!Files.exists(root)) return new Discovery(List.of(), List.of());
		List<UUID> regions = new ArrayList<>();
		List<String> issues = new ArrayList<>();
		if (!Files.isDirectory(root)) {
			issues.add("ownership root 不是目录，已锁定：" + root);
			return new Discovery(List.of(), issues);
		}
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(root)) {
			for (Path entry : entries) {
				if (!Files.isDirectory(entry)) {
					issues.add("ownership root 含未知文件（已保留并锁定）：" + entry.getFileName());
					continue;
				}
				try {
					regions.add(UUID.fromString(entry.getFileName().toString()));
				} catch (IllegalArgumentException unknown) {
					issues.add("ownership root 含未知目录（已保留并锁定）：" + entry.getFileName());
				}
			}
		} catch (IOException exception) {
			MmmMod.LOGGER.error("Could not scan the ownership directory {}", root, exception);
			issues.add("无法完整扫描 ownership root（已锁定）：" + exception.getMessage());
		}
		return new Discovery(List.copyOf(regions), List.copyOf(issues));
	}

	private record Discovery(List<UUID> regions, List<String> issues) { }

	public static OwnershipCoordinator.WorldAccess worldAccess(MinecraftServer server) {
		return new MinecraftWorldAccess(server);
	}

	public static String warningText(
			Path worldRoot,
			UUID regionId,
			String dimensionId,
			OwnershipJournal.Operation operation,
			long baseGeneration,
			long targetGeneration,
			int entries
	) {
		return """
				REAL WATER WORLD OWNERSHIP RECOVERY WARNING / 物质、运动与力学世界所有权恢复警告

				The Matter, Motion & Mechanics controller region below is in the middle of a world ownership transition.
				下方 Matter, Motion & Mechanics 控制器区域正在进行世界所有权事务。

				World directory / 世界目录: %s
				Region / 区域: %s
				Dimension / 维度: %s
				WAL version / 日志版本: 1
				Operation / 操作: %s
				Base -> target generation / 基础 -> 目标代次: %d -> %d
				Transition entries / 事务条目数: %d

				Recovery / 恢复步骤:
				1. Keep a full backup of this world. / 保留完整世界备份。
				2. Restart this world with this mod; startup recovery compares the region HEAD generation
				   and either rolls the world back or completes the committed ownership.
				   使用本 Mod 重启世界；启动恢复按 region HEAD 代次判定，回滚世界或完成已提交 ownership。
				3. Or approach the controller and run /mmm ownership to inspect the state.
				   或靠近控制器执行 /mmm ownership 检查状态。
				4. Confirm wal.nbt and this WARNING are gone before removing the mod.
				   确认 wal.nbt 与本 WARNING 消失后，才能卸载 Mod。
				""".formatted(worldRoot, regionId, dimensionId, operation, baseGeneration, targetGeneration, entries);
	}

	private static final class MinecraftWorldAccess implements OwnershipCoordinator.WorldAccess {
		private final MinecraftServer server;

		private MinecraftWorldAccess(MinecraftServer server) {
			this.server = server;
		}

		@Override
		public boolean isDimensionLoaded(String dimensionId) throws IOException {
			return level(dimensionId) != null;
		}

		@Override
		public OwnershipCoordinator.RemoveOutcome removeWater(
				String dimensionId,
				OwnershipJournal.BlockEntry block
		) throws IOException {
			ServerLevel level = requireLevel(dimensionId);
			BlockPos position = BlockPos.of(block.packedPosition());
			BlockState original = originalState(block);
			BlockState current = level.getBlockState(position);
			if (current.isAir()) return OwnershipCoordinator.RemoveOutcome.ALREADY_CLEAR;
			if (!current.equals(original)) return OwnershipCoordinator.RemoveOutcome.SKIPPED_OCCUPIED;
			if (!level.setBlock(position, Blocks.AIR.defaultBlockState(),
					Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS)) {
				throw new IOException("Could not remove owned water at " + position.toShortString());
			}
			if (!level.getBlockState(position).isAir()) {
				throw new IOException("Owned water was not removed at " + position.toShortString());
			}
			return OwnershipCoordinator.RemoveOutcome.REMOVED;
		}

		@Override
		public OwnershipCoordinator.RestoreOutcome restore(
				String dimensionId,
				OwnershipJournal.BlockEntry block
		) throws IOException {
			ServerLevel level = requireLevel(dimensionId);
			BlockPos position = BlockPos.of(block.packedPosition());
			BlockState original = originalState(block);
			BlockState current = level.getBlockState(position);
			if (current.equals(original)) return OwnershipCoordinator.RestoreOutcome.ALREADY_RESTORED;
			if (!current.isAir() && !current.is(Blocks.WATER)) {
				return OwnershipCoordinator.RestoreOutcome.SKIPPED_OCCUPIED;
			}
			level.setBlock(position, original, Block.UPDATE_ALL);
			if (!level.getBlockState(position).equals(original)) {
				throw new IOException("Could not restore ownership block at " + position.toShortString());
			}
			return OwnershipCoordinator.RestoreOutcome.RESTORED;
		}

		@Override
		public OwnershipCoordinator.InspectOutcome inspect(
				String dimensionId,
				OwnershipJournal.BlockEntry block
		) throws IOException {
			ServerLevel level = requireLevel(dimensionId);
			BlockPos position = BlockPos.of(block.packedPosition());
			originalState(block);
			BlockState current = level.getBlockState(position);
			if (current.isAir()) return OwnershipCoordinator.InspectOutcome.OWNED_AIR;
			// Any water level counts as vanilla water returning into owned air, matching restore occupancy.
			if (current.is(Blocks.WATER)) return OwnershipCoordinator.InspectOutcome.PREIMAGE_PRESENT;
			return OwnershipCoordinator.InspectOutcome.OCCUPIED;
		}

		private BlockState originalState(OwnershipJournal.BlockEntry block) throws IOException {
			BlockState original = NbtUtils.readBlockState(
					server.registryAccess().lookupOrThrow(Registries.BLOCK),
					block.state()
			);
			if (!original.is(Blocks.WATER)) {
				throw new IOException("Ownership store contains a non-water state at "
						+ BlockPos.of(block.packedPosition()).toShortString());
			}
			return original;
		}

		private ServerLevel requireLevel(String dimensionId) throws IOException {
			ServerLevel level = level(dimensionId);
			if (level == null) throw new IOException("Ownership dimension is not loaded: " + dimensionId);
			return level;
		}

		private ServerLevel level(String dimensionId) throws IOException {
			Identifier identifier = Identifier.tryParse(dimensionId);
			if (identifier == null) throw new IOException("Invalid ownership dimension: " + dimensionId);
			ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, identifier);
			return server.getLevel(dimension);
		}
	}
}

package mmm.simulation;

import mmm.MmmMod;
import mmm.simulation.controller.FluidRegionFootprint;
import mmm.simulation.ownership.OwnershipCommitted;
import mmm.simulation.ownership.OwnershipCoordinator;
import mmm.simulation.ownership.OwnershipFiles;
import mmm.simulation.ownership.OwnershipJournal;
import mmm.simulation.ownership.OwnershipStore;
import mmm.simulation.persistence.GenerationSequence;
import mmm.simulation.persistence.RegionGenerationStore;
import mmm.simulation.persistence.RegionState;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** High-level Minecraft ownership transactions around the region HEAD commit point. */
final class OwnershipTransactionCoordinator {
	private final RegionRepositoryAdapter repository;
	private final RegionGenerationStore generations;
	private final OwnershipStore ownershipStore;

	OwnershipTransactionCoordinator(
			RegionRepositoryAdapter repository,
			RegionGenerationStore generations,
			OwnershipStore ownershipStore
	) {
		this.repository = repository;
		this.generations = generations;
		this.ownershipStore = ownershipStore;
	}

	List<String> scanIssues() {
		return ownershipStore.scanIssues();
	}

	List<String> recoverUnfinished(MinecraftServer server) {
		return ownershipStore.recoverUnfinished(server);
	}

	int committedBlockCount(FluidRegion region) throws IOException {
		if (!region.controllerManaged()) return 0;
		OwnershipFiles files = ownershipStore.files(region.id());
		files.assertUnlockedForTransition();
		if (!files.hasCommitted()) return 0;
		OwnershipCommitted committed = files.readCommitted();
		if (!committed.regionId().equals(region.id())
				|| !committed.dimensionId().equals(region.dimension().identifier().toString())
				|| committed.generation() > region.checkpointGeneration()) {
			throw new IOException("Committed ownership does not match the active region identity");
		}
		return committed.ownedBlocks().size();
	}

	List<OwnershipJournal.BlockEntry> scanPreimage(
			ServerLevel level,
			FluidRegionFootprint footprint
	) throws IOException {
		List<OwnershipJournal.BlockEntry> candidates = new ArrayList<>();
		for (long packed : footprint.interiorBlocks()) {
			BlockPos position = BlockPos.of(packed);
			BlockState state = level.getBlockState(position);
			if (state.is(Blocks.WATER)) {
				candidates.add(new OwnershipJournal.BlockEntry(packed, NbtUtils.writeBlockState(state)));
			}
		}
		candidates.sort(Comparator.comparingLong(OwnershipJournal.BlockEntry::packedPosition));
		if (candidates.size() > 65_536) {
			throw new IOException("Region interior holds too many water blocks to acquire");
		}
		return candidates;
	}

	void acquire(
			ServerLevel level,
			FluidRegion region,
			List<OwnershipJournal.BlockEntry> candidates
	) throws IOException {
		OwnershipFiles ownershipFiles = ownershipStore.files(region.id());
		candidates = List.copyOf(candidates);
		if (candidates.isEmpty()) {
			repository.checkpoint(region);
			return;
		}
		String dimensionId = region.dimension().identifier().toString();
		long baseGeneration = generations.committedGeneration(region.id());
		long generation = GenerationSequence.next(baseGeneration, "Ownership acquire checkpoint");
		if (!GenerationSequence.isNext(region.checkpointGeneration(), generation)) {
			throw new IOException("Region checkpoint " + region.checkpointGeneration()
					+ " is out of sync with its committed HEAD " + baseGeneration);
		}
		RegionState staged = repository.capture(region, generation);
		String warning = OwnershipStore.warningText(ownershipStore.worldRoot(),
				region.id(), dimensionId, OwnershipJournal.Operation.ACQUIRE,
				baseGeneration, generation, candidates.size());
		long publishedGeneration;
		try {
			OwnershipCoordinator.AcquireResult result = OwnershipCoordinator.acquire(
					generations, ownershipFiles, staged, candidates, warning,
					OwnershipStore.worldAccess(level.getServer()), OwnershipCoordinator.FailureInjector.NONE);
			publishedGeneration = result.generation();
		} catch (IOException | RuntimeException failure) {
			OwnershipCoordinator.RecoveryResult recovery;
			try {
				recovery = OwnershipCoordinator.recover(generations, ownershipFiles,
						OwnershipStore.worldAccess(level.getServer()), OwnershipCoordinator.FailureInjector.NONE);
			} catch (IOException recoveryFailure) {
				failure.addSuppressed(recoveryFailure);
				throw failure;
			}
			if (recovery.kind() != OwnershipCoordinator.RecoveryResult.Kind.COMPLETED
					|| generations.committedGeneration(region.id()) != generation) {
				throw failure;
			}
			publishedGeneration = generation;
			MmmMod.LOGGER.warn(
					"Controller ownership acquire for {} completed through in-process post-commit recovery",
					region.id());
		}
		if (!region.recordPublishedCheckpoint(publishedGeneration)) {
			throw new IOException("Acquired ownership checkpoint " + publishedGeneration
					+ " was not accepted by the live region");
		}
	}

	ReleaseAttempt release(MinecraftServer server, FluidRegion region) {
		OwnershipFiles ownershipFiles = ownershipStore.files(region.id());
		long targetGeneration = -1L;
		try {
			if (!region.solverLoaded()) repository.restore(region);
			if (region.domain().particleCount() != 0 || region.domain().totalGranularSolidVolumeSvq() > 0L
					|| region.domain().totalCoherentSolidVolumeSvq() > 0L) {
				return ReleaseAttempt.refused("区域 " + region.id()
						+ " 仍持有流体、颗粒或连续固相库存；请先 empty 后再移除");
			}
			if (!ownershipFiles.exists()) return ReleaseAttempt.NOT_OWNED;
			ownershipFiles.assertUnlockedForTransition();
			if (!ownershipFiles.hasCommitted()) {
				ownershipFiles.deleteStore();
				return ReleaseAttempt.NOT_OWNED;
			}
			OwnershipCommitted committed = ownershipFiles.readCommitted();
			if (committed.ownedBlocks().isEmpty()) {
				ownershipFiles.deleteStore();
				return ReleaseAttempt.NOT_OWNED;
			}
			long baseGeneration = generations.committedGeneration(region.id());
			targetGeneration = GenerationSequence.next(baseGeneration, "Ownership release checkpoint");
			RegionState staged = repository.capture(region, targetGeneration);
			String warning = OwnershipStore.warningText(ownershipStore.worldRoot(),
					region.id(), committed.dimensionId(), OwnershipJournal.Operation.RELEASE,
					baseGeneration, targetGeneration, committed.ownedBlocks().size());
			OwnershipCoordinator.ReleaseResult result = OwnershipCoordinator.release(generations, ownershipFiles,
					staged, warning, OwnershipStore.worldAccess(server), OwnershipCoordinator.FailureInjector.NONE);
			if (!region.recordPublishedCheckpoint(result.generation())) {
				throw new IOException("Release checkpoint " + result.generation()
						+ " was not accepted by the live region");
			}
			ownershipFiles.deleteStore();
			return new ReleaseAttempt(null, result.restoredBlocks(), result.skippedBlocks());
		} catch (IOException | RuntimeException failure) {
			OwnershipCoordinator.RecoveryResult recovery;
			try {
				recovery = OwnershipCoordinator.recover(generations, ownershipFiles,
						OwnershipStore.worldAccess(server), OwnershipCoordinator.FailureInjector.NONE);
			} catch (IOException recoveryFailure) {
				failure.addSuppressed(recoveryFailure);
				MmmMod.LOGGER.error("World ownership release failed for region {}", region.id(), failure);
				return ReleaseAttempt.refused("区域 " + region.id() + " ownership 解除失败：" + failure.getMessage());
			}
			try {
				if (recovery.kind() == OwnershipCoordinator.RecoveryResult.Kind.COMPLETED
						&& targetGeneration > 0L
						&& generations.committedGeneration(region.id()) == targetGeneration) {
					if (!region.recordPublishedCheckpoint(targetGeneration)) {
						throw new IOException("Recovered release checkpoint " + targetGeneration
								+ " was not accepted by the live region");
					}
					ownershipFiles.deleteStore();
					MmmMod.LOGGER.warn(
							"Controller ownership release for {} completed through in-process post-commit recovery",
							region.id());
					return new ReleaseAttempt(null, recovery.restored(), recovery.skipped());
				}
			} catch (IOException recoveryValidationFailure) {
				failure.addSuppressed(recoveryValidationFailure);
			}
			MmmMod.LOGGER.error("World ownership release failed for region {}", region.id(), failure);
			return ReleaseAttempt.refused("区域 " + region.id() + " ownership 解除失败：" + failure.getMessage());
		}
	}

	String summary(MinecraftServer server, FluidRegion region) {
		OwnershipFiles files = ownershipStore.files(region.id());
		if (!files.exists()) return region.id() + ": unowned（无 ownership 记录；原版水保留在世界中）";
		if (files.isLocked()) return region.id() + ": 存在未解决事务（" + files.lockDescription() + "）";
		if (!files.hasCommitted()) return region.id() + ": 无 committed 记录";
		try {
			OwnershipCoordinator.ReconcileResult result = OwnershipCoordinator.reconcile(
					files, OwnershipStore.worldAccess(server));
			if (result.ownedBlocks() == 0) {
				return region.id() + ": 第 " + result.generation() + " 代，持有 0 个位置（已排空）";
			}
			return String.format("%s: 第 %d 代，持有 %d 个位置（air %d、玩家方块冲突 %d、原版水回流 %d）",
					region.id(), result.generation(), result.ownedBlocks(), result.ownedAir(),
					result.playerConflicts(), result.waterConflicts());
		} catch (IOException exception) {
			return region.id() + ": ownership 不可读或维度未加载（已锁定）：" + exception.getMessage();
		}
	}

	record ReleaseAttempt(String failure, int restoredBlocks, int skippedBlocks) {
		static final ReleaseAttempt NOT_OWNED = new ReleaseAttempt(null, 0, 0);

		static ReleaseAttempt refused(String failure) {
			return new ReleaseAttempt(failure, 0, 0);
		}
	}
}

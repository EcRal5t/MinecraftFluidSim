package com.example.simulation;

import com.example.RealWaterMod;
import com.example.fluid.BuiltinFluidMaterials;
import com.example.simulation.controller.FluidRegionFootprint;
import com.example.simulation.ownership.OwnershipCommitted;
import com.example.simulation.ownership.OwnershipCoordinator;
import com.example.simulation.ownership.OwnershipFiles;
import com.example.simulation.ownership.OwnershipJournal;
import com.example.simulation.ownership.OwnershipStore;
import com.example.simulation.persistence.RegionManifest;
import com.example.simulation.persistence.RegionState;
import com.example.simulation.persistence.RegionStateFiles;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Coordinates persistent controller state and its joint world-ownership transactions. */
final class FluidRegionPersistence {
	private final RegionStateFiles files;
	private final OwnershipStore ownershipStore;
	private final boolean materialsEnabled;

	FluidRegionPersistence(Path worldRoot, boolean materialsEnabled) {
		files = new RegionStateFiles(worldRoot);
		ownershipStore = new OwnershipStore(worldRoot);
		this.materialsEnabled = materialsEnabled;
	}

	List<String> scanOwnershipIssues() {
		return ownershipStore.scanIssues();
	}

	List<String> recoverUnfinishedOwnership(MinecraftServer server) {
		return ownershipStore.recoverUnfinished(server);
	}

	List<String> loadMetadata(FluidRegionManager manager) {
		List<String> issues = new ArrayList<>();
		RegionStateFiles.Discovery discovery;
		try {
			discovery = files.discover();
		} catch (IOException exception) {
			return List.of("无法扫描 region-state：" + exception.getMessage());
		}
		for (var issue : discovery.issues().entrySet()) issues.add(issue.getKey().getFileName() + " 已锁定：" + issue.getValue());
		for (RegionManifest manifest : discovery.manifests()) {
			try {
				if (manifest.formatVersion() == 2 && !materialsEnabled) {
					throw new IOException("region-state v2 requires the materials feature gate");
				}
				validateControllerGeometry(manifest);
				Identifier dimensionId = Identifier.tryParse(manifest.dimensionId());
				if (dimensionId == null) throw new IOException("invalid dimension");
				ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, dimensionId);
				FluidRegion region = FluidRegion.unloaded(manifest.regionId(), dimension, manifest.origin(),
						manifest.sizeX(), manifest.sizeY(), manifest.sizeZ(), manifest.solverConfig(),
						manifest.reservedMarkerCapacity(), manifest.footprint(), manifest.networkGeneration(),
						manifest.checkpointGeneration(), manifest.totalMarkerCount(), (float) manifest.totalVolume(), manifest.runtime());
				FluidRegionManager.AdmissionResult admission = manager.add(region);
				if (!admission.accepted()) issues.add(manifest.regionId() + " 未载入：" + admission.failure());
			} catch (IOException | IllegalArgumentException exception) {
				issues.add(manifest.regionId() + " 已锁定：" + exception.getMessage());
			}
		}
		return List.copyOf(issues);
	}

	long checkpoint(FluidRegion region) throws IOException {
		if (!region.controllerManaged() || !region.solverLoaded()) throw new IOException("Region has no persistent live solver");
		long generation = Math.addExact(region.checkpointGeneration(), 1L);
		long published = files.publish(region.domain().hasMaterialState()
				? RegionState.captureMaterial(region, generation)
				: RegionState.capture(region, generation));
		if (!region.recordPublishedCheckpoint(published)) throw new IOException("Published checkpoint was not accepted by the live region");
		return published;
	}

	long committedGeneration(UUID regionId) throws IOException {
		return files.committedGeneration(regionId);
	}


	/**
	 * Joint acquire: samples no fluid state of its own — the caller has already captured markers.
	 * Stages the initial checkpoint, publishes the ownership WAL and WARNING, removes the vanilla
	 * water, and publishes the region HEAD last as the sole commit point.
	 */
	void acquireOwnership(ServerLevel level, FluidRegion region) throws IOException {
		OwnershipFiles ownershipFiles = ownershipStore.files(region.id());
		List<OwnershipJournal.BlockEntry> candidates = scanPureWaterInterior(level, region.footprint());
		if (candidates.isEmpty()) {
			checkpoint(region);
			return;
		}
		String dimensionId = region.dimension().identifier().toString();
		long baseGeneration = files.committedGeneration(region.id());
		long generation = baseGeneration + 1L;
		if (generation != region.checkpointGeneration() + 1L) {
			throw new IOException("Region checkpoint " + region.checkpointGeneration()
					+ " is out of sync with its committed HEAD " + baseGeneration);
		}
		RegionState staged = region.domain().hasMaterialState()
				? RegionState.captureMaterial(region, generation)
				: RegionState.capture(region, generation);
		String warning = OwnershipStore.warningText(ownershipStore.worldRoot(),
				region.id(), dimensionId, OwnershipJournal.Operation.ACQUIRE,
				baseGeneration, generation, candidates.size());
		try {
			OwnershipCoordinator.acquire(files, ownershipFiles, staged, candidates, warning,
					OwnershipStore.worldAccess(level.getServer()), OwnershipCoordinator.FailureInjector.NONE);
		} catch (IOException | RuntimeException failure) {
			try {
				OwnershipCoordinator.recover(files, ownershipFiles,
						OwnershipStore.worldAccess(level.getServer()), OwnershipCoordinator.FailureInjector.NONE);
			} catch (IOException recoveryFailure) {
				failure.addSuppressed(recoveryFailure);
			}
			throw failure;
		}
		if (!region.recordPublishedCheckpoint(generation)) {
			throw new IOException("Acquired ownership checkpoint " + generation + " was not accepted by the live region");
		}
	}

	/**
	 * Conservative release before persistent deletion: refuses while the region still holds marker
	 * volume, restores the vanilla preimage with player blocks winning, and never runs unless the
	 * final region checkpoint can be staged and committed.
	 */
	ReleaseAttempt releaseOwnership(MinecraftServer server, FluidRegion region) {
		OwnershipFiles ownershipFiles = ownershipStore.files(region.id());
		if (!ownershipFiles.exists()) return ReleaseAttempt.NOT_OWNED;
		try {
			ownershipFiles.assertUnlockedForTransition();
			if (region.solverLoaded() ? region.domain().totalVolume() > 0.0F : region.checkpointVolume() > 0.0F) {
				return ReleaseAttempt.refused("区域 " + region.id() + " 仍持有水体；请先排空（drain）后再移除");
			}
			if (!ownershipFiles.hasCommitted()) {
				ownershipFiles.deleteStore();
				return ReleaseAttempt.NOT_OWNED;
			}
			OwnershipCommitted committed = ownershipFiles.readCommitted();
			if (committed.ownedBlocks().isEmpty()) {
				ownershipFiles.deleteStore();
				return ReleaseAttempt.NOT_OWNED;
			}
			if (!region.solverLoaded()) restore(region);
			long baseGeneration = files.committedGeneration(region.id());
			long generation = baseGeneration + 1L;
			RegionState staged = region.domain().hasMaterialState()
					? RegionState.captureMaterial(region, generation)
					: RegionState.capture(region, generation);
			String warning = OwnershipStore.warningText(ownershipStore.worldRoot(),
					region.id(), committed.dimensionId(), OwnershipJournal.Operation.RELEASE,
					baseGeneration, generation, committed.ownedBlocks().size());
			OwnershipCoordinator.ReleaseResult result = OwnershipCoordinator.release(files, ownershipFiles,
					staged, warning, OwnershipStore.worldAccess(server), OwnershipCoordinator.FailureInjector.NONE);
			if (!region.recordPublishedCheckpoint(result.generation())) {
				throw new IOException("Release checkpoint " + result.generation() + " was not accepted by the live region");
			}
			ownershipFiles.deleteStore();
			return new ReleaseAttempt(null, result.restoredBlocks(), result.skippedBlocks());
		} catch (IOException | RuntimeException failure) {
			try {
				OwnershipCoordinator.recover(files, ownershipFiles,
						OwnershipStore.worldAccess(server), OwnershipCoordinator.FailureInjector.NONE);
			} catch (IOException recoveryFailure) {
				failure.addSuppressed(recoveryFailure);
			}
			RealWaterMod.LOGGER.error("World ownership release failed for region {}", region.id(), failure);
			return ReleaseAttempt.refused("区域 " + region.id() + " ownership 解除失败：" + failure.getMessage());
		}
	}

	record ReleaseAttempt(String failure, int restoredBlocks, int skippedBlocks) {
		static final ReleaseAttempt NOT_OWNED = new ReleaseAttempt(null, 0, 0);

		static ReleaseAttempt refused(String failure) {
			return new ReleaseAttempt(failure, 0, 0);
		}
	}

	/** Read-only ownership reconciliation for one region; never mutates the world or the stores. */
	String ownershipSummary(MinecraftServer server, FluidRegion region) {
		OwnershipFiles ownershipFiles = ownershipStore.files(region.id());
		if (!ownershipFiles.exists()) {
			return region.id() + ": legacy（无 ownership 记录；原版水保留在世界中）";
		}
		if (ownershipFiles.isLocked()) {
			return region.id() + ": 存在未解决事务（" + ownershipFiles.lockDescription() + "）";
		}
		if (!ownershipFiles.hasCommitted()) {
			return region.id() + ": 无 committed 记录";
		}
		try {
			OwnershipCoordinator.ReconcileResult result = OwnershipCoordinator.reconcile(
					ownershipFiles, OwnershipStore.worldAccess(server));
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

	private static List<OwnershipJournal.BlockEntry> scanPureWaterInterior(ServerLevel level, FluidRegionFootprint footprint)
			throws IOException {
		List<OwnershipJournal.BlockEntry> candidates = new ArrayList<>();
		for (long packed : footprint.interiorBlocks()) {
			BlockPos position = BlockPos.of(packed);
			BlockState state = level.getBlockState(position);
			if (state.is(Blocks.WATER)) {
				candidates.add(new OwnershipJournal.BlockEntry(packed, NbtUtils.writeBlockState(state)));
			}
		}
		candidates.sort(Comparator.comparingLong(OwnershipJournal.BlockEntry::packedPosition));
		if (candidates.size() > 65_536) throw new IOException("Region interior holds too many water blocks to acquire");
		return candidates;
	}

	void checkpointAndUnload(FluidRegion region) throws IOException {
		long generation = checkpoint(region);
		if (!region.releaseSolverAfterCheckpoint(generation)) throw new IOException("Published checkpoint did not release region solver");
	}

	void restore(FluidRegion region) throws IOException {
		if (region.solverLoaded() || region.lifecycle().state() != FluidRegionLifecycle.State.UNLOADED) return;
		RegionState state = files.read(region.id());
		RegionManifest manifest = state.manifest();
		if (manifest.checkpointGeneration() != region.checkpointGeneration() || manifest.networkGeneration() != region.generation()
				|| !manifest.dimensionId().equals(region.dimension().identifier().toString())
				|| !manifest.origin().equals(region.origin()) || manifest.sizeX() != region.sizeX()
				|| manifest.sizeY() != region.sizeY() || manifest.sizeZ() != region.sizeZ()
				|| manifest.reservedMarkerCapacity() != region.reservedMarkerCapacity()) {
			throw new IOException("Committed checkpoint no longer matches admitted region metadata");
		}
		OwnershipFiles ownershipFiles = ownershipStore.files(region.id());
		if (ownershipFiles.isLocked()) {
			throw new IOException("Unresolved world ownership transition; recovery must complete first");
		}
		if (ownershipFiles.hasCommitted()) {
			OwnershipCommitted committed = ownershipFiles.readCommitted();
			if (!committed.regionId().equals(region.id())
					|| !committed.dimensionId().equals(region.dimension().identifier().toString())
					|| committed.generation() > manifest.checkpointGeneration()) {
				throw new IOException("Committed world ownership does not match the region checkpoint");
			}
		}
		if (materialsEnabled && manifest.formatVersion() == 1) {
			long migratedGeneration = Math.addExact(manifest.checkpointGeneration(), 1L);
			state = RegionState.migrateV1(state, migratedGeneration, BuiltinFluidMaterials.M3_PALETTE);
			files.publish(state);
			if (!region.adoptMigratedCheckpoint(manifest.checkpointGeneration(), migratedGeneration)) {
				throw new IOException("Published v2 migration was not accepted by unloaded region metadata");
			}
			manifest = state.manifest();
		}
		if (manifest.formatVersion() == 2) {
			if (!materialsEnabled) throw new IOException("Material checkpoint cannot restore while the feature gate is disabled");
			region.restoreMaterialSolver(state.reconstructMaterial(), manifest.checkpointGeneration());
		} else {
			region.restoreSolver(state.reconstruct(), manifest.checkpointGeneration());
		}
	}

	void delete(FluidRegion region) throws IOException {
		if (region.controllerManaged() && region.checkpointGeneration() > 0L) files.quarantine(region.id());
	}

	private static void validateControllerGeometry(RegionManifest manifest) throws IOException {
		int cellsPerBlock = Math.round(1.0F / manifest.solverConfig().cellSize());
		if (cellsPerBlock <= 0 || Math.abs(cellsPerBlock * manifest.solverConfig().cellSize() - 1.0F) > 1.0E-6F
				|| manifest.sizeX() != Math.multiplyExact(manifest.footprint().blockSizeX(), cellsPerBlock)
				|| manifest.sizeY() != Math.multiplyExact(manifest.footprint().blockSizeY(), cellsPerBlock)
				|| manifest.sizeZ() != Math.multiplyExact(manifest.footprint().blockSizeZ(), cellsPerBlock)) {
			throw new IOException("Persisted solver grid does not match its visible controller footprint");
		}
	}
}

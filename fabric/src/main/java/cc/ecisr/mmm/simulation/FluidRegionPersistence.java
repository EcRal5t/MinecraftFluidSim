package cc.ecisr.mmm.simulation;

import cc.ecisr.mmm.MmmMod;
import cc.ecisr.mmm.chemistry.ChemicalRegistry;
import cc.ecisr.mmm.fluid.FluidMaterialRegistry;
import cc.ecisr.mmm.fluid.FluidMaterial;
import cc.ecisr.mmm.fluid.air.GasRegistry;
import cc.ecisr.mmm.granular.GranularRegistry;
import cc.ecisr.mmm.simulation.controller.FluidRegionFootprint;
import cc.ecisr.mmm.simulation.ownership.OwnershipCommitted;
import cc.ecisr.mmm.simulation.ownership.OwnershipCoordinator;
import cc.ecisr.mmm.simulation.ownership.OwnershipFiles;
import cc.ecisr.mmm.simulation.ownership.OwnershipJournal;
import cc.ecisr.mmm.simulation.ownership.OwnershipStore;
import cc.ecisr.mmm.simulation.persistence.GenerationSequence;
import cc.ecisr.mmm.simulation.persistence.RegionManifest;
import cc.ecisr.mmm.simulation.persistence.RegionState;
import cc.ecisr.mmm.simulation.persistence.RegionStateFiles;
import cc.ecisr.mmm.simulation.persistence.RegionStateLayout;
import cc.ecisr.mmm.simulation.persistence.WorldCapabilityLayoutResolver;
import cc.ecisr.mmm.thermal.ThermalRegistry;
import cc.ecisr.mmm.thermal.ThermalTransport;
import cc.ecisr.mmm.worldprofile.WorldCapabilityProfile;
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
	private final List<FluidMaterial> expectedMaterialPalette;
	private final WorldCapabilityProfile capabilityProfile;
	private final boolean worldCapabilityRequired;
	private final RegionStateLayout expectedLayout;
	private final boolean chemistryEnabled;
	private final ChemicalRegistry chemicalRegistry;
	private final boolean granularEnabled;
	private final GranularRegistry granularRegistry;
	private final boolean precipitationEnabled;
	private final boolean thermalEnabled;
	private final ThermalRegistry thermalRegistry;
	private final GasRegistry gasRegistry;
	private final int maximumThermalSubsteps;

	FluidRegionPersistence(Path worldRoot, boolean materialsEnabled) {
		this(worldRoot, materialsEnabled, false, null);
	}

	FluidRegionPersistence(
			Path worldRoot, boolean materialsEnabled, boolean chemistryEnabled, ChemicalRegistry chemicalRegistry
	) {
		this(worldRoot, materialsEnabled, chemistryEnabled, chemicalRegistry, false, null);
	}

	FluidRegionPersistence(
			Path worldRoot, boolean materialsEnabled, boolean chemistryEnabled, ChemicalRegistry chemicalRegistry,
			boolean granularEnabled, GranularRegistry granularRegistry
	) {
		this(worldRoot, materialsEnabled, chemistryEnabled, chemicalRegistry, granularEnabled, granularRegistry, false);
	}

	FluidRegionPersistence(
			Path worldRoot, boolean materialsEnabled, boolean chemistryEnabled, ChemicalRegistry chemicalRegistry,
			boolean granularEnabled, GranularRegistry granularRegistry, boolean precipitationEnabled
	) {
		this(worldRoot, materialsEnabled, chemistryEnabled, chemicalRegistry, granularEnabled, granularRegistry,
				precipitationEnabled, false, null, null);
	}

	FluidRegionPersistence(
			Path worldRoot, boolean materialsEnabled, boolean chemistryEnabled, ChemicalRegistry chemicalRegistry,
			boolean granularEnabled, GranularRegistry granularRegistry, boolean precipitationEnabled,
			boolean thermalEnabled, ThermalRegistry thermalRegistry, GasRegistry gasRegistry
	) {
		this(worldRoot, materialsEnabled, chemistryEnabled, chemicalRegistry, granularEnabled, granularRegistry,
				precipitationEnabled, thermalEnabled, thermalRegistry, gasRegistry,
				ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS);
	}

	FluidRegionPersistence(
			Path worldRoot, boolean materialsEnabled, boolean chemistryEnabled, ChemicalRegistry chemicalRegistry,
			boolean granularEnabled, GranularRegistry granularRegistry, boolean precipitationEnabled,
			boolean thermalEnabled, ThermalRegistry thermalRegistry, GasRegistry gasRegistry,
			int maximumThermalSubsteps
	) {
		this(worldRoot, materialsEnabled, chemistryEnabled, chemicalRegistry, granularEnabled, granularRegistry,
				precipitationEnabled, thermalEnabled, thermalRegistry, gasRegistry, maximumThermalSubsteps, null, null, false);
	}

	FluidRegionPersistence(
			Path worldRoot, boolean materialsEnabled, boolean chemistryEnabled, ChemicalRegistry chemicalRegistry,
			boolean granularEnabled, GranularRegistry granularRegistry, boolean precipitationEnabled,
			boolean thermalEnabled, ThermalRegistry thermalRegistry, GasRegistry gasRegistry,
			int maximumThermalSubsteps, List<FluidMaterial> expectedMaterialPalette
	) {
		this(worldRoot, materialsEnabled, chemistryEnabled, chemicalRegistry, granularEnabled, granularRegistry,
				precipitationEnabled, thermalEnabled, thermalRegistry, gasRegistry, maximumThermalSubsteps,
				expectedMaterialPalette, null, false);
	}

	FluidRegionPersistence(
			Path worldRoot, boolean materialsEnabled, boolean chemistryEnabled, ChemicalRegistry chemicalRegistry,
			boolean granularEnabled, GranularRegistry granularRegistry, boolean precipitationEnabled,
			boolean thermalEnabled, ThermalRegistry thermalRegistry, GasRegistry gasRegistry,
			int maximumThermalSubsteps, List<FluidMaterial> expectedMaterialPalette,
			WorldCapabilityProfile capabilityProfile
	) {
		this(worldRoot, materialsEnabled, chemistryEnabled, chemicalRegistry, granularEnabled, granularRegistry,
				precipitationEnabled, thermalEnabled, thermalRegistry, gasRegistry, maximumThermalSubsteps,
				expectedMaterialPalette, capabilityProfile, false);
	}

	FluidRegionPersistence(
			Path worldRoot, boolean materialsEnabled, boolean chemistryEnabled, ChemicalRegistry chemicalRegistry,
			boolean granularEnabled, GranularRegistry granularRegistry, boolean precipitationEnabled,
			boolean thermalEnabled, ThermalRegistry thermalRegistry, GasRegistry gasRegistry,
			int maximumThermalSubsteps, List<FluidMaterial> expectedMaterialPalette,
			WorldCapabilityProfile capabilityProfile, boolean worldCapabilityRequired
	) {
		files = thermalEnabled
				? new RegionStateFiles(worldRoot, chemicalRegistry, granularRegistry, thermalRegistry, gasRegistry)
				: granularRegistry == null
				? chemicalRegistry == null ? new RegionStateFiles(worldRoot) : new RegionStateFiles(worldRoot, chemicalRegistry)
				: new RegionStateFiles(worldRoot, chemicalRegistry, granularRegistry);
		ownershipStore = new OwnershipStore(worldRoot, chemicalRegistry, granularRegistry, thermalRegistry, gasRegistry);
		this.materialsEnabled = materialsEnabled;
		this.expectedMaterialPalette = expectedMaterialPalette == null ? null : List.copyOf(expectedMaterialPalette);
		this.capabilityProfile = capabilityProfile;
		this.worldCapabilityRequired = worldCapabilityRequired;
		this.expectedLayout = capabilityProfile == null
				? WorldCapabilityLayoutResolver.fixtureLayout(
						materialsEnabled, chemistryEnabled, granularEnabled, thermalEnabled)
				: WorldCapabilityLayoutResolver.resolve(capabilityProfile);
		this.chemistryEnabled = chemistryEnabled;
		this.chemicalRegistry = chemicalRegistry;
		this.granularEnabled = granularEnabled;
		this.granularRegistry = granularRegistry;
		this.precipitationEnabled = precipitationEnabled;
		this.thermalEnabled = thermalEnabled;
		this.thermalRegistry = thermalRegistry;
		this.gasRegistry = gasRegistry;
		if (maximumThermalSubsteps <= 0
				|| maximumThermalSubsteps > ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS) {
			throw new IllegalArgumentException("Thermal transport substep budget is invalid");
		}
		this.maximumThermalSubsteps = maximumThermalSubsteps;
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
			files.recoverKnownFirstPublicationStaging();
			discovery = files.discover();
		} catch (IOException exception) {
			return List.of("无法恢复或扫描 region-state：" + exception.getMessage());
		}
		for (var issue : discovery.issues().entrySet()) issues.add(issue.getKey().getFileName() + " 已锁定：" + issue.getValue());
		for (RegionManifest manifest : discovery.manifests()) {
			try {
				validateFeatureShape(manifest);
				if (manifest.hasChemicalState()) {
					if (!chemistryEnabled || chemicalRegistry == null) {
						throw new IOException("chemical region-state requires the chemistry feature gate and authoritative registry snapshot");
					}
					if (!manifest.registryFingerprint().equals(chemicalRegistry.fingerprint())) {
						throw new IOException("region-state chemical registry fingerprint does not match the world snapshot");
					}
				}
				if (manifest.hasGranularState() && (!granularEnabled || granularRegistry == null)) {
					throw new IOException("granular region-state requires the granular feature gate and authoritative registry snapshot");
				}
				if (manifest.hasThermalState() && (!thermalEnabled || thermalRegistry == null || gasRegistry == null)) {
					throw new IOException("thermal region-state requires the thermal feature gate and authoritative thermal/gas registry snapshots");
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
		String ownershipLock = ownershipLockDescription(region);
		if (ownershipLock != null) {
			throw new IOException("Unresolved world ownership state blocks checkpoint: " + ownershipLock);
		}
		long generation = GenerationSequence.next(region.checkpointGeneration(), "Region checkpoint");
		long published = files.publish(captureForFeatureGate(region, generation));
		if (!region.recordPublishedCheckpoint(published)) throw new IOException("Published checkpoint was not accepted by the live region");
		return published;
	}

	long committedGeneration(UUID regionId) throws IOException {
		return files.committedGeneration(regionId);
	}

	long committedManifestChecksum(UUID regionId) throws IOException {
		return files.committedManifestChecksum(regionId);
	}

	StagedCheckpoint stageCheckpoint(FluidRegion region, long targetGeneration) throws IOException {
		if (!GenerationSequence.isNext(region.checkpointGeneration(), targetGeneration)) {
			throw new IOException("Staged checkpoint target does not advance the live region by one generation");
		}
		String ownershipLock = ownershipLockDescription(region);
		if (ownershipLock != null) throw new IOException("Unresolved world ownership state blocks staging: " + ownershipLock);
		RegionStateFiles.PreparedGeneration prepared = files.prepare(
				captureForFeatureGate(region, targetGeneration), (point, path) -> { });
		return new StagedCheckpoint(prepared, prepared.manifestChecksum());
	}

	long commitStagedCheckpoint(FluidRegion region, StagedCheckpoint staged) throws IOException {
		long published = files.commit(staged.prepared(), (point, path) -> { });
		if (!region.recordPublishedCheckpoint(published)) throw new IOException("Published staged checkpoint was not accepted");
		return published;
	}

	void abortStagedCheckpoint(StagedCheckpoint staged) throws IOException {
		files.abort(staged.prepared());
	}

	record StagedCheckpoint(RegionStateFiles.PreparedGeneration prepared, long manifestChecksum) { }

	/** Per-region fail-closed guard used for both loaded stepping and every ordinary checkpoint path. */
	String ownershipLockDescription(FluidRegion region) {
		if (!region.controllerManaged()) return null;
		OwnershipFiles ownershipFiles = ownershipStore.files(region.id());
		if (ownershipFiles.isLocked()) return ownershipFiles.lockDescription();
		if (!ownershipFiles.hasCommitted()) return null;
		try {
			OwnershipCommitted committed = ownershipFiles.readCommitted();
			if (!committed.regionId().equals(region.id())) {
				return "committed ownership 记录了其他区域 " + committed.regionId();
			}
			if (!committed.dimensionId().equals(region.dimension().identifier().toString())) {
				return "committed ownership 维度与区域不一致";
			}
			if (committed.generation() > region.checkpointGeneration()) {
				return "committed ownership generation " + committed.generation()
						+ " 超前于活动区域 checkpoint " + region.checkpointGeneration();
			}
			return null;
		} catch (IOException | RuntimeException invalid) {
			return "ownership committed 状态不可读：" + invalid.getMessage();
		}
	}


	/**
	 * Joint acquire: samples no fluid state of its own — the caller has already captured markers.
	 * Stages the initial checkpoint, publishes the ownership WAL and WARNING, removes the vanilla
	 * water, and publishes the region HEAD last as the sole commit point.
	 */
	List<OwnershipJournal.BlockEntry> scanOwnershipPreimage(
			ServerLevel level, FluidRegionFootprint footprint
	) throws IOException {
		return scanPureWaterInterior(level, footprint);
	}

	void acquireOwnership(
			ServerLevel level, FluidRegion region, List<OwnershipJournal.BlockEntry> candidates
	) throws IOException {
		OwnershipFiles ownershipFiles = ownershipStore.files(region.id());
		candidates = List.copyOf(candidates);
		if (candidates.isEmpty()) {
			checkpoint(region);
			return;
		}
		String dimensionId = region.dimension().identifier().toString();
		long baseGeneration = files.committedGeneration(region.id());
		long generation = GenerationSequence.next(baseGeneration, "Ownership acquire checkpoint");
		if (!GenerationSequence.isNext(region.checkpointGeneration(), generation)) {
			throw new IOException("Region checkpoint " + region.checkpointGeneration()
					+ " is out of sync with its committed HEAD " + baseGeneration);
		}
		RegionState staged = captureForFeatureGate(region, generation);
		String warning = OwnershipStore.warningText(ownershipStore.worldRoot(),
				region.id(), dimensionId, OwnershipJournal.Operation.ACQUIRE,
				baseGeneration, generation, candidates.size());
		long publishedGeneration;
		try {
			OwnershipCoordinator.AcquireResult result = OwnershipCoordinator.acquire(
					files, ownershipFiles, staged, candidates, warning,
					OwnershipStore.worldAccess(level.getServer()), OwnershipCoordinator.FailureInjector.NONE);
			publishedGeneration = result.generation();
		} catch (IOException | RuntimeException failure) {
			OwnershipCoordinator.RecoveryResult recovery;
			try {
				recovery = OwnershipCoordinator.recover(files, ownershipFiles,
						OwnershipStore.worldAccess(level.getServer()), OwnershipCoordinator.FailureInjector.NONE);
			} catch (IOException recoveryFailure) {
				failure.addSuppressed(recoveryFailure);
				throw failure;
			}
			if (recovery.kind() != OwnershipCoordinator.RecoveryResult.Kind.COMPLETED
					|| files.committedGeneration(region.id()) != generation) {
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

	/**
	 * Conservative release before persistent deletion: restores the authoritative solver first,
	 * refuses while any condensed inventory remains, restores the vanilla preimage with player blocks winning, and never runs unless the
	 * final region checkpoint can be staged and committed.
	 */
	ReleaseAttempt releaseOwnership(MinecraftServer server, FluidRegion region) {
		OwnershipFiles ownershipFiles = ownershipStore.files(region.id());
		long targetGeneration = -1L;
		try {
			if (!region.solverLoaded()) restore(region);
			if (region.domain().particleCount() != 0 || region.domain().totalGranularSolidVolumeSvq() > 0L
					|| region.domain().totalCoherentSolidVolumeSvq() > 0L) {
				return ReleaseAttempt.refused("区域 " + region.id() + " 仍持有流体、颗粒或连续固相库存；请先 empty 后再移除");
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
			long baseGeneration = files.committedGeneration(region.id());
			targetGeneration = GenerationSequence.next(baseGeneration, "Ownership release checkpoint");
			RegionState staged = captureForFeatureGate(region, targetGeneration);
			String warning = OwnershipStore.warningText(ownershipStore.worldRoot(),
					region.id(), committed.dimensionId(), OwnershipJournal.Operation.RELEASE,
					baseGeneration, targetGeneration, committed.ownedBlocks().size());
			OwnershipCoordinator.ReleaseResult result = OwnershipCoordinator.release(files, ownershipFiles,
					staged, warning, OwnershipStore.worldAccess(server), OwnershipCoordinator.FailureInjector.NONE);
			if (!region.recordPublishedCheckpoint(result.generation())) {
				throw new IOException("Release checkpoint " + result.generation() + " was not accepted by the live region");
			}
			ownershipFiles.deleteStore();
			return new ReleaseAttempt(null, result.restoredBlocks(), result.skippedBlocks());
		} catch (IOException | RuntimeException failure) {
			OwnershipCoordinator.RecoveryResult recovery;
			try {
				recovery = OwnershipCoordinator.recover(files, ownershipFiles,
						OwnershipStore.worldAccess(server), OwnershipCoordinator.FailureInjector.NONE);
			} catch (IOException recoveryFailure) {
				failure.addSuppressed(recoveryFailure);
				MmmMod.LOGGER.error("World ownership release failed for region {}", region.id(), failure);
				return ReleaseAttempt.refused("区域 " + region.id() + " ownership 解除失败：" + failure.getMessage());
			}
			try {
				if (recovery.kind() == OwnershipCoordinator.RecoveryResult.Kind.COMPLETED
						&& targetGeneration > 0L && files.committedGeneration(region.id()) == targetGeneration) {
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
			return region.id() + ": unowned（无 ownership 记录；原版水保留在世界中）";
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
		validateFeatureShape(manifest);
		if (manifest.checkpointGeneration() != region.checkpointGeneration() || manifest.networkGeneration() != region.networkGeneration()
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
		if (thermalEnabled) {
			restoreThermalGeneration(region, state);
			return;
		}
		if (granularEnabled) {
			restoreGranularGeneration(region, state);
			return;
		}
		if (chemistryEnabled) {
			if (chemicalRegistry == null) throw new IOException("Authoritative chemical registry is unavailable");
			region.restoreChemicalSolver(state.reconstructChemical(chemicalRegistry), chemicalRegistry,
					manifest.checkpointGeneration());
			return;
		}
		if (materialsEnabled) {
			region.restoreMaterialSolver(state.reconstructMaterial(), manifest.checkpointGeneration());
		} else {
			region.restoreSolver(state.reconstruct(), manifest.checkpointGeneration());
		}
	}

	/**
	 * Operator-only recovery for a HEAD that is locally valid but cannot bind its persisted gas
	 * topology to its own marker geometry. The inactive generation is fully reconstructed into a
	 * throwaway solver before HEAD moves. Startup never calls this path and never guesses a fallback.
	 */
	long recoverPreviousGeneration(FluidRegion region, long expectedCurrentGeneration) throws IOException {
		if (!thermalEnabled || thermalRegistry == null || gasRegistry == null) {
			throw new IOException("Previous-generation recovery requires the active thermal/gas registries");
		}
		if (!region.controllerManaged() || region.solverLoaded()
				|| region.lifecycle().state() != FluidRegionLifecycle.State.UNLOADED) {
			throw new IOException("Previous-generation recovery requires a metadata-only unloaded controller region");
		}
		if (!region.paused()) throw new IOException("Previous-generation recovery requires an operator-paused region");
		if (region.checkpointGeneration() != expectedCurrentGeneration) {
			throw new IOException("Selected region metadata is not at expected checkpoint " + expectedCurrentGeneration);
		}
		String ownershipLock = ownershipLockDescription(region);
		if (ownershipLock != null) {
			throw new IOException("Unresolved world ownership state blocks previous-generation recovery: " + ownershipLock);
		}

		RegionStateFiles.PreviousGeneration previous = files.readPrevious(region.id(), expectedCurrentGeneration);
		RegionManifest manifest = previous.state().manifest();
		if (manifest.layout() != RegionStateLayout.THERMAL) {
			throw new IOException("Previous slot does not use the current thermal/gas region layout");
		}
		validateFeatureShape(manifest);
		validateRecoveryIdentity(region, manifest);
		OwnershipFiles ownershipFiles = ownershipStore.files(region.id());
		if (ownershipFiles.hasCommitted()) {
			OwnershipCommitted committed = ownershipFiles.readCommitted();
			if (!committed.regionId().equals(region.id())
					|| !committed.dimensionId().equals(region.dimension().identifier().toString())
					|| committed.generation() > manifest.checkpointGeneration()) {
				throw new IOException("Previous checkpoint predates committed world ownership truth");
			}
		}

		FluidRegion probe = unloadedRegion(manifest);
		restoreThermalGeneration(probe, previous.state());
		if (!probe.solverLoaded()
				|| !probe.domain().exactInternalEnergy().equals(probe.domain().thermalAudit().expectedInventory())) {
			throw new IOException("Previous checkpoint probe did not restore a closed thermal inventory");
		}
		if (!probe.releaseSolverAfterCheckpoint(manifest.checkpointGeneration())) {
			throw new IOException("Previous checkpoint probe could not release its temporary solver");
		}
		return files.activatePrevious(previous);
	}

	private static void validateRecoveryIdentity(FluidRegion region, RegionManifest manifest) throws IOException {
		if (manifest.networkGeneration() != region.networkGeneration()
				|| !manifest.dimensionId().equals(region.dimension().identifier().toString())
				|| !manifest.origin().equals(region.origin()) || manifest.sizeX() != region.sizeX()
				|| manifest.sizeY() != region.sizeY() || manifest.sizeZ() != region.sizeZ()
				|| !manifest.solverConfig().equals(region.solverConfig())
				|| manifest.reservedMarkerCapacity() != region.reservedMarkerCapacity()
				|| !manifest.footprint().equals(region.footprint())) {
			throw new IOException("Previous checkpoint does not match the admitted controller identity");
		}
	}

	private static FluidRegion unloadedRegion(RegionManifest manifest) throws IOException {
		validateControllerGeometry(manifest);
		Identifier dimensionId = Identifier.tryParse(manifest.dimensionId());
		if (dimensionId == null) throw new IOException("Previous checkpoint has an invalid dimension");
		return FluidRegion.unloaded(manifest.regionId(),
				ResourceKey.create(Registries.DIMENSION, dimensionId), manifest.origin(),
				manifest.sizeX(), manifest.sizeY(), manifest.sizeZ(), manifest.solverConfig(),
				manifest.reservedMarkerCapacity(), manifest.footprint(), manifest.networkGeneration(),
				manifest.checkpointGeneration(), manifest.totalMarkerCount(),
				(float) manifest.totalVolume(), manifest.runtime());
	}

	private RegionState captureForFeatureGate(FluidRegion region, long generation) throws IOException {
		if (thermalEnabled) {
			if (thermalRegistry == null || gasRegistry == null) {
				throw new IOException("Authoritative thermal/gas registry is unavailable");
			}
			if (!region.domain().hasMaterialState()) throw new IOException("Thermal state requires a material-bound domain");
			if (chemistryEnabled) {
				if (chemicalRegistry == null) throw new IOException("Authoritative chemical registry is unavailable");
				if (!region.domain().hasChemistryState()) region.domain().bindChemistry(chemicalRegistry);
			}
			if (granularEnabled) {
				if (granularRegistry == null) throw new IOException("Authoritative granular registry is unavailable");
				if (!region.domain().hasGranularState()) {
					region.domain().bindGranular(granularRegistry, region.domain().materialRegistry());
				}
				enablePrecipitationIfConfigured(region);
			}
			if (!region.domain().hasThermalState()) {
				throw new IOException("Thermal state is unavailable for the active feature layout");
			}
			try {
				region.domain().synchronizeGasTopologyForPersistence();
			} catch (RuntimeException invalidTopology) {
				throw new IOException("Current carrier geometry cannot be joined to gas/thermal truth: "
						+ invalidTopology.getMessage() + "; previous HEAD remains authoritative",
						invalidTopology);
			}
			return bindWorldCapability(RegionState.captureThermal(region, generation));
		}
		if (granularEnabled) {
			if (granularRegistry == null) throw new IOException("Authoritative granular registry is unavailable");
			if (!region.domain().hasMaterialState()) throw new IOException("Granular state requires a material-bound domain");
			if (chemistryEnabled) {
				if (chemicalRegistry == null) throw new IOException("Authoritative chemical registry is unavailable");
				if (!region.domain().hasChemistryState()) region.domain().bindChemistry(chemicalRegistry);
			}
			if (!region.domain().hasGranularState()) {
				region.domain().bindGranular(granularRegistry, region.domain().materialRegistry());
			}
			enablePrecipitationIfConfigured(region);
			return bindWorldCapability(RegionState.captureGranular(region, generation));
		}
		if (chemistryEnabled) {
			if (chemicalRegistry == null) throw new IOException("Authoritative chemical registry is unavailable");
			if (!region.domain().hasMaterialState()) throw new IOException("Chemistry requires a material-bound domain");
			if (!region.domain().hasChemistryState()) region.domain().bindChemistry(chemicalRegistry);
			return bindWorldCapability(RegionState.captureChemical(region, generation));
		}
		return bindWorldCapability(region.domain().hasMaterialState()
				? RegionState.captureMaterial(region, generation)
				: RegionState.capture(region, generation));
	}

	private RegionState bindWorldCapability(RegionState state) {
		return capabilityProfile == null ? state : state.bindWorldCapabilityProfile(capabilityProfile);
	}

	private void restoreThermalGeneration(FluidRegion region, RegionState state) throws IOException {
		if (thermalRegistry == null || gasRegistry == null) {
			throw new IOException("Authoritative thermal/gas registry is unavailable");
		}
		RegionManifest manifest = state.manifest();
		if (manifest.layout() != RegionStateLayout.THERMAL) {
			throw new IOException("Checkpoint does not use the current thermal region layout");
		}
		long generation = manifest.checkpointGeneration();
		try {
			if (manifest.hasChemicalState()) {
				if (!chemistryEnabled || chemicalRegistry == null) {
					throw new IOException("Chemical thermal checkpoint requires the chemistry registry and gate");
				}
				region.restoreChemicalSolver(state.reconstructChemical(chemicalRegistry), chemicalRegistry, generation);
			} else {
				region.restoreMaterialSolver(state.reconstructMaterial(), generation);
			}
			boolean hasGranular = manifest.hasGranularState();
			if (hasGranular) {
				if (!granularEnabled || granularRegistry == null) {
					throw new IOException("Granular thermal checkpoint requires the granular registry and gate");
				}
				region.restoreGranularState(state.reconstructGranular(granularRegistry), granularRegistry, generation);
			} else if (granularEnabled) {
				throw new IOException("Thermal checkpoint omits granular truth while the granular gate is enabled");
			}
			region.restoreGasState(state.reconstructGas(gasRegistry, chemicalRegistry), gasRegistry, generation);
			region.restoreThermalState(state.reconstructThermal(thermalRegistry, gasRegistry, chemicalRegistry),
					thermalRegistry, generation, maximumThermalSubsteps);
			region.restoreFiniteThermalDevices(
					state.reconstructFiniteThermalDevices(thermalRegistry), thermalRegistry, generation);
			region.domain().restorePendingPhaseVolumeState(state.reconstructPendingPhaseVolume());
			enablePrecipitationIfConfigured(region);
		} catch (IOException | RuntimeException failure) {
			if (region.solverLoaded() && region.checkpointGeneration() == generation) {
				region.releaseSolverAfterCheckpoint(generation);
			}
			throw failure;
		}
	}

	private void restoreGranularGeneration(FluidRegion region, RegionState state) throws IOException {
		if (granularRegistry == null) throw new IOException("Authoritative granular registry is unavailable");
		RegionManifest manifest = state.manifest();
		long generation = manifest.checkpointGeneration();
		if (manifest.hasChemicalState()) {
			if (chemicalRegistry == null) throw new IOException("Authoritative chemical registry is unavailable");
			region.restoreChemicalSolver(state.reconstructChemical(chemicalRegistry), chemicalRegistry, generation);
		} else {
			region.restoreMaterialSolver(state.reconstructMaterial(), generation);
		}
		region.restoreGranularState(state.reconstructGranular(granularRegistry), granularRegistry, generation);
		enablePrecipitationIfConfigured(region);
	}

	private void enablePrecipitationIfConfigured(FluidRegion region) {
		if (precipitationEnabled) region.domain().setPrecipitationEnabled(true);
	}

	private void validateFeatureShape(RegionManifest manifest) throws IOException {
		WorldCapabilityLayoutResolver.validateManifestBinding(
				capabilityProfile, manifest, worldCapabilityRequired);
		if (manifest.layout() != expectedLayout
				|| manifest.hasChemicalState() != chemistryEnabled
				|| manifest.hasGranularState() != granularEnabled
				|| manifest.hasThermalState() != thermalEnabled
				|| manifest.hasGasState() != thermalEnabled) {
			throw new IOException("Persisted region feature layout does not match the active server feature gates; recreate the region with the current configuration");
		}
		if (expectedMaterialPalette != null && !manifest.materialPalette().equals(expectedMaterialPalette)) {
			throw new IOException("Persisted region material palette does not match the committed world material snapshot");
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

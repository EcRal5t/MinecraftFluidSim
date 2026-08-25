package com.example.simulation;

import com.example.RealWaterMod;
import com.example.chemistry.ChemicalRegistry;
import com.example.fluid.BuiltinFluidMaterials;
import com.example.fluid.FluidMaterialRegistry;
import com.example.fluid.air.GasAuditLedger;
import com.example.fluid.air.GasRegistry;
import com.example.granular.GranularRegistry;
import com.example.simulation.controller.FluidRegionFootprint;
import com.example.simulation.ownership.OwnershipCommitted;
import com.example.simulation.ownership.OwnershipCoordinator;
import com.example.simulation.ownership.OwnershipFiles;
import com.example.simulation.ownership.OwnershipJournal;
import com.example.simulation.ownership.OwnershipStore;
import com.example.simulation.persistence.RegionManifest;
import com.example.simulation.persistence.RegionState;
import com.example.simulation.persistence.RegionStateFiles;
import com.example.simulation.persistence.ThermalInitializationRecord;
import com.example.thermal.ThermalRegistry;
import com.example.thermal.ThermalTransport;
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
	private static final int CURRENT_THERMAL_FORMAT = 7;
	private final RegionStateFiles files;
	private final OwnershipStore ownershipStore;
	private final boolean materialsEnabled;
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
		files = thermalEnabled
				? new RegionStateFiles(worldRoot, chemicalRegistry, granularRegistry, thermalRegistry, gasRegistry)
				: granularRegistry == null
				? chemicalRegistry == null ? new RegionStateFiles(worldRoot) : new RegionStateFiles(worldRoot, chemicalRegistry)
				: new RegionStateFiles(worldRoot, chemicalRegistry, granularRegistry);
		ownershipStore = new OwnershipStore(worldRoot, chemicalRegistry, granularRegistry, thermalRegistry, gasRegistry);
		this.materialsEnabled = materialsEnabled;
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
				if (manifest.formatVersion() >= 2 && !materialsEnabled) {
					throw new IOException("region-state v" + manifest.formatVersion() + " requires the materials feature gate");
				}
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
				if (thermalEnabled && manifest.formatVersion() == 4) {
					throw new IOException("region-state v4 is a gas-only generation and cannot be loaded or guessed as complete thermal state");
				}
				validateControllerGeometry(manifest);
				Identifier dimensionId = Identifier.tryParse(manifest.dimensionId());
				if (dimensionId == null) throw new IOException("invalid dimension");
				ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, dimensionId);
				FluidRegion region = FluidRegion.unloaded(manifest.regionId(), dimension, manifest.origin(),
						manifest.sizeX(), manifest.sizeY(), manifest.sizeZ(), manifest.solverConfig(),
						manifest.reservedMarkerCapacity(), manifest.footprint(), manifest.networkGeneration(),
						manifest.checkpointGeneration(), manifest.totalMarkerCount(), (float) manifest.totalVolume(), manifest.runtime());
				if (thermalEnabled && manifest.formatVersion() != CURRENT_THERMAL_FORMAT) {
					region.requireThermalInitialization(manifest.formatVersion());
				}
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
		long generation = Math.addExact(region.checkpointGeneration(), 1L);
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
		if (targetGeneration != region.checkpointGeneration() + 1L) {
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
			RealWaterMod.LOGGER.warn(
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
	 * refuses while either marker or granular inventory remains, restores the vanilla preimage with player blocks winning, and never runs unless the
	 * final region checkpoint can be staged and committed.
	 */
	ReleaseAttempt releaseOwnership(MinecraftServer server, FluidRegion region) {
		OwnershipFiles ownershipFiles = ownershipStore.files(region.id());
		try {
			if (!region.solverLoaded()) restore(region);
			if (region.domain().particleCount() != 0 || region.domain().totalGranularSolidVolumeSvq() > 0L) {
				return ReleaseAttempt.refused("区域 " + region.id() + " 仍持有流体或颗粒库存；请先 empty 后再移除");
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
			long generation = baseGeneration + 1L;
			RegionState staged = captureForFeatureGate(region, generation);
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
		if (thermalEnabled) {
			restoreThermalGeneration(region, state);
			return;
		}
		if (manifest.hasThermalState()) {
			throw new IOException("Thermal checkpoint cannot restore while the feature gate is disabled");
		}
		if (granularEnabled) {
			restoreGranularGeneration(region, state);
			return;
		}
		if (manifest.formatVersion() == 5) {
			throw new IOException("Granular checkpoint cannot restore while the feature gate is disabled");
		}
		if (chemistryEnabled) {
			if (chemicalRegistry == null) throw new IOException("Authoritative chemical registry is unavailable");
			if (manifest.formatVersion() == 3) {
				region.restoreChemicalSolver(state.reconstructChemical(chemicalRegistry), chemicalRegistry,
						manifest.checkpointGeneration());
				return;
			}
			long previousGeneration = manifest.checkpointGeneration();
			RegionState materialState = state;
			if (manifest.formatVersion() == 1) {
				materialState = RegionState.migrateV1(state, Math.addExact(previousGeneration, 1L),
						BuiltinFluidMaterials.M3_PALETTE);
			}
			region.restoreMaterialSolver(materialState.reconstructMaterial(), previousGeneration);
			try {
				region.domain().bindChemistry(chemicalRegistry);
				long migratedGeneration = Math.addExact(previousGeneration, 1L);
				files.publish(RegionState.captureChemical(region, migratedGeneration));
				if (!region.recordPublishedCheckpoint(migratedGeneration)) {
					throw new IOException("Published v3 migration was not accepted by live region metadata");
				}
				RealWaterMod.LOGGER.info("Atomically migrated controller region {} from region-state v{} to v3 generation {}",
						region.id(), manifest.formatVersion(), migratedGeneration);
				return;
			} catch (IOException | RuntimeException failure) {
				if (region.checkpointGeneration() == previousGeneration) {
					region.releaseSolverAfterCheckpoint(previousGeneration);
				}
				throw failure;
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
		if (manifest.formatVersion() == 3) {
			throw new IOException("Chemical checkpoint cannot restore while the feature gate is disabled");
		} else if (manifest.formatVersion() == 2) {
			if (!materialsEnabled) throw new IOException("Material checkpoint cannot restore while the feature gate is disabled");
			region.restoreMaterialSolver(state.reconstructMaterial(), manifest.checkpointGeneration());
		} else {
			region.restoreSolver(state.reconstruct(), manifest.checkpointGeneration());
		}
	}

	/**
	 * Explicitly assigns the first authoritative U to an old committed controller generation.
	 * The inactive slot is fully reconstructed and verified before HEAD moves; no old HEAD is
	 * rewritten and no ambient/biome temperature is consulted.
	 */
	long initializeThermal(FluidRegion region, double initialTemperatureK, String operatorMetadata) throws IOException {
		if (!thermalEnabled || thermalRegistry == null || gasRegistry == null) {
			throw new IOException("Thermal feature gate and authoritative thermal/gas registries are required");
		}
		if (!region.controllerManaged()) throw new IOException("Only a persistent controller region can be initialized");
		if (region.solverLoaded() || region.lifecycle().state() != FluidRegionLifecycle.State.UNLOADED) {
			throw new IOException("Thermal initialization requires a metadata-only unloaded region");
		}
		if (!region.paused()) throw new IOException("Thermal initialization requires an operator-paused region");
		String ownershipLock = ownershipLockDescription(region);
		if (ownershipLock != null) throw new IOException("Unresolved world ownership state blocks initialization: " + ownershipLock);

		RegionState source = files.read(region.id());
		RegionManifest manifest = source.manifest();
		validateAdmittedIdentity(region, manifest);
		int sourceVersion = manifest.formatVersion();
		if (sourceVersion != 1 && sourceVersion != 2 && sourceVersion != 3
				&& sourceVersion != 5 && sourceVersion != 6) {
			throw new IOException("Only region-state v1/v2/v3/v5/v6 can receive explicit thermal initialization");
		}
		long previousGeneration = manifest.checkpointGeneration();
		long initializedGeneration = Math.addExact(previousGeneration, 1L);
		ThermalInitializationRecord record = new ThermalInitializationRecord(sourceVersion, initialTemperatureK,
				ThermalInitializationRecord.GasBootstrap.CURRENT_GEOMETRY_AMBIENT_V1, operatorMetadata);
		try {
			GasAuditLedger closedGasHistory = restoreLegacyForThermalInitialization(region, source, previousGeneration);
			if (chemistryEnabled && !region.domain().hasChemistryState()) {
				if (chemicalRegistry == null) throw new IOException("Authoritative chemical registry is unavailable");
				region.domain().bindChemistry(chemicalRegistry);
			}
			if (granularEnabled && !region.domain().hasGranularState()) {
				if (granularRegistry == null) throw new IOException("Authoritative granular registry is unavailable");
				region.domain().bindGranular(granularRegistry, region.domain().materialRegistry());
			}
			enablePrecipitationIfConfigured(region);
			region.domain().bindGasRegistry(gasRegistry);
			String chemicalFingerprint = region.domain().hasChemistryState()
					? chemicalRegistry.fingerprint() : "";
			region.domain().bootstrapGasState(chemicalFingerprint);
			region.domain().carryClosedGasAuditHistory(closedGasHistory);
			region.domain().bindThermal(thermalRegistry, region.domain().materialRegistry(), initialTemperatureK,
					maximumThermalSubsteps);
			region.setThermalInitializationRecord(record);
			long published = files.publish(RegionState.captureThermal(region, initializedGeneration));
			if (!region.recordPublishedCheckpoint(published)) {
				throw new IOException("Published v7 initialization was not accepted by live region metadata");
			}
			RealWaterMod.LOGGER.info(
					"Explicitly initialized controller region {} from region-state v{} at raw temperature {} into v7 generation {}",
					region.id(), sourceVersion, Long.toUnsignedString(record.initialTemperatureBits()), published);
			return published;
		} catch (IOException | RuntimeException failure) {
			long committed;
			try {
				committed = files.committedGeneration(region.id());
			} catch (IOException inspectionFailure) {
				failure.addSuppressed(inspectionFailure);
				throw new IOException("Thermal initialization HEAD is unreadable after publication failure; evidence is retained and region is LOCKED", failure);
			}
			if (committed == initializedGeneration) {
				region.setThermalInitializationRecord(record);
				if (region.recordPublishedCheckpoint(initializedGeneration)) return initializedGeneration;
				throw new IOException("v7 initialization HEAD committed but live metadata could not advance; restart recovery is required", failure);
			}
			region.setThermalInitializationRecord(null);
			if (region.solverLoaded() && region.checkpointGeneration() == previousGeneration
					&& !region.releaseSolverAfterCheckpoint(previousGeneration)) {
				throw new IOException("Old HEAD remained authoritative but the transient initialized solver could not unload", failure);
			}
			throw failure;
		}
	}

	private GasAuditLedger restoreLegacyForThermalInitialization(
			FluidRegion region, RegionState state, long generation
	) throws IOException {
		GasAuditLedger closedGasHistory = GasAuditLedger.EMPTY;
		RegionManifest manifest = state.manifest();
		switch (manifest.formatVersion()) {
			case 1 -> {
				RegionState material = RegionState.migrateV1(state, Math.addExact(generation, 1L),
						BuiltinFluidMaterials.M3_PALETTE);
				region.restoreMaterialSolver(material.reconstructMaterial(), generation);
			}
			case 2 -> region.restoreMaterialSolver(state.reconstructMaterial(), generation);
			case 3 -> {
				if (!chemistryEnabled || chemicalRegistry == null) {
					throw new IOException("region-state v3 initialization requires its authoritative chemistry registry");
				}
				region.restoreChemicalSolver(state.reconstructChemical(chemicalRegistry), chemicalRegistry, generation);
			}
			case 5 -> {
				if (manifest.hasChemicalState()) {
					if (!chemistryEnabled || chemicalRegistry == null) {
						throw new IOException("Chemical v5 initialization requires its authoritative chemistry registry");
					}
					region.restoreChemicalSolver(state.reconstructChemical(chemicalRegistry), chemicalRegistry, generation);
				} else region.restoreMaterialSolver(state.reconstructMaterial(), generation);
				if (!granularEnabled || granularRegistry == null) {
					throw new IOException("region-state v5 initialization requires its authoritative granular registry");
				}
				region.restoreGranularState(state.reconstructGranular(granularRegistry), granularRegistry, generation);
			}
			case 6 -> closedGasHistory = restoreLegacyV6ForThermalInitialization(region, state, generation);
			default -> throw new IOException("Unsupported thermal initialization source");
		}
		return closedGasHistory;
	}

	private GasAuditLedger restoreLegacyV6ForThermalInitialization(
			FluidRegion region, RegionState state, long generation
	) throws IOException {
		var pending = state.reconstructPendingPhaseVolume();
		if (!pending.isEmpty()) {
			throw new IOException("region-state v6 has unconsumed phase volume/work and cannot be reinitialized");
		}
		var gas = state.reconstructGas(gasRegistry, chemicalRegistry);
		if (!gas.hasNoLiveInventoryAndClosedTurnover()) {
			throw new IOException("region-state v6 contains gas truth tied to the legacy topology; automatic remapping is forbidden");
		}
		if (state.manifest().hasChemicalState()) {
			if (!chemistryEnabled || chemicalRegistry == null) {
				throw new IOException("Chemical v6 initialization requires its authoritative chemistry registry");
			}
			region.restoreChemicalSolver(state.reconstructChemical(chemicalRegistry), chemicalRegistry, generation);
		} else {
			region.restoreMaterialSolver(state.reconstructMaterial(), generation);
		}
		if (state.manifest().hasGranularState()) {
			if (!granularEnabled || granularRegistry == null) {
				throw new IOException("Granular v6 initialization requires its authoritative granular registry");
			}
			region.restoreGranularState(state.reconstructGranular(granularRegistry), granularRegistry, generation);
		}
		return gas.externalSource();
	}

	private static void validateAdmittedIdentity(FluidRegion region, RegionManifest manifest) throws IOException {
		if (manifest.checkpointGeneration() != region.checkpointGeneration()
				|| manifest.networkGeneration() != region.generation()
				|| !manifest.dimensionId().equals(region.dimension().identifier().toString())
				|| !manifest.origin().equals(region.origin()) || manifest.sizeX() != region.sizeX()
				|| manifest.sizeY() != region.sizeY() || manifest.sizeZ() != region.sizeZ()
				|| manifest.reservedMarkerCapacity() != region.reservedMarkerCapacity()) {
			throw new IOException("Committed checkpoint no longer matches admitted region metadata");
		}
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
				throw new IOException("Thermal state is not initialized; an old controller requires explicit operator temperature migration");
			}
			return RegionState.captureThermal(region, generation);
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
			return RegionState.captureGranular(region, generation);
		}
		if (chemistryEnabled) {
			if (chemicalRegistry == null) throw new IOException("Authoritative chemical registry is unavailable");
			if (!region.domain().hasMaterialState()) throw new IOException("Chemistry requires a material-bound domain");
			if (!region.domain().hasChemistryState()) region.domain().bindChemistry(chemicalRegistry);
			return RegionState.captureChemical(region, generation);
		}
		return region.domain().hasMaterialState()
				? RegionState.captureMaterial(region, generation)
				: RegionState.capture(region, generation);
	}

	private void restoreThermalGeneration(FluidRegion region, RegionState state) throws IOException {
		if (thermalRegistry == null || gasRegistry == null) {
			throw new IOException("Authoritative thermal/gas registry is unavailable");
		}
		RegionManifest manifest = state.manifest();
		if (manifest.formatVersion() == 1 || manifest.formatVersion() == 2
				|| manifest.formatVersion() == 3 || manifest.formatVersion() == 5
				|| manifest.formatVersion() == 6) {
			throw new ThermalInitializationRequiredException(manifest.formatVersion());
		}
		if (manifest.formatVersion() != CURRENT_THERMAL_FORMAT) {
			throw new IOException("region-state v" + manifest.formatVersion()
					+ " cannot be interpreted as complete thermal state");
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
			region.domain().restorePendingPhaseVolumeState(state.reconstructPendingPhaseVolume());
			region.setThermalInitializationRecord(state.reconstructThermalInitialization());
			enablePrecipitationIfConfigured(region);
		} catch (IOException | RuntimeException failure) {
			region.setThermalInitializationRecord(null);
			if (region.solverLoaded() && region.checkpointGeneration() == generation) {
				region.releaseSolverAfterCheckpoint(generation);
			}
			throw failure;
		}
	}

	static final class ThermalInitializationRequiredException extends IOException {
		private final int sourceVersion;

		ThermalInitializationRequiredException(int sourceVersion) {
			super(sourceVersion == 6
					? "region-state v6 uses the legacy condensed-mass energy scale; explicit operator temperature reinitialization is required"
					: "region-state v" + sourceVersion
							+ " has no authoritative internal energy; explicit operator temperature initialization is required");
			this.sourceVersion = sourceVersion;
		}

		int sourceVersion() { return sourceVersion; }
	}

	private void restoreGranularGeneration(FluidRegion region, RegionState state) throws IOException {
		if (granularRegistry == null) throw new IOException("Authoritative granular registry is unavailable");
		RegionManifest manifest = state.manifest();
		long previousGeneration = manifest.checkpointGeneration();
		try {
			if (manifest.formatVersion() == 5) {
				if (manifest.hasChemicalState()) {
					if (!chemistryEnabled || chemicalRegistry == null) {
						throw new IOException("Chemical granular checkpoint requires the chemistry registry and gate");
					}
					region.restoreChemicalSolver(state.reconstructChemical(chemicalRegistry), chemicalRegistry, previousGeneration);
				} else {
					region.restoreMaterialSolver(state.reconstructMaterial(), previousGeneration);
				}
				region.restoreGranularState(state.reconstructGranular(granularRegistry), granularRegistry, previousGeneration);
				if (chemistryEnabled && !manifest.hasChemicalState()) {
					if (chemicalRegistry == null) throw new IOException("Authoritative chemical registry is unavailable");
					region.domain().bindChemistry(chemicalRegistry);
					publishGranularMigration(region, previousGeneration, manifest.formatVersion());
				}
				enablePrecipitationIfConfigured(region);
				return;
			}
			if (manifest.formatVersion() == 1) {
				RegionState material = RegionState.migrateV1(state, Math.addExact(previousGeneration, 1L),
						BuiltinFluidMaterials.M3_PALETTE);
				region.restoreMaterialSolver(material.reconstructMaterial(), previousGeneration);
			} else if (manifest.formatVersion() == 2) {
				region.restoreMaterialSolver(state.reconstructMaterial(), previousGeneration);
			} else if (manifest.formatVersion() == 3) {
				if (!chemistryEnabled || chemicalRegistry == null) {
					throw new IOException("region-state v3 cannot migrate to v5 without its authoritative chemistry");
				}
				region.restoreChemicalSolver(state.reconstructChemical(chemicalRegistry), chemicalRegistry, previousGeneration);
			} else throw new IOException("Unsupported region-state version " + manifest.formatVersion());
			if (chemistryEnabled && !region.domain().hasChemistryState()) {
				if (chemicalRegistry == null) throw new IOException("Authoritative chemical registry is unavailable");
				region.domain().bindChemistry(chemicalRegistry);
			}
			region.domain().bindGranular(granularRegistry, region.domain().materialRegistry());
			enablePrecipitationIfConfigured(region);
			publishGranularMigration(region, previousGeneration, manifest.formatVersion());
		} catch (IOException | RuntimeException failure) {
			if (region.solverLoaded() && region.checkpointGeneration() == previousGeneration) {
				region.releaseSolverAfterCheckpoint(previousGeneration);
			}
			throw failure;
		}
	}

	private void enablePrecipitationIfConfigured(FluidRegion region) {
		if (precipitationEnabled) region.domain().setPrecipitationEnabled(true);
	}

	private void publishGranularMigration(FluidRegion region, long previousGeneration, int sourceVersion) throws IOException {
		long migratedGeneration = Math.addExact(previousGeneration, 1L);
		files.publish(RegionState.captureGranular(region, migratedGeneration));
		if (!region.recordPublishedCheckpoint(migratedGeneration)) {
			throw new IOException("Published v5 migration was not accepted by live region metadata");
		}
		RealWaterMod.LOGGER.info("Atomically migrated controller region {} from region-state v{} to v5 generation {}",
				region.id(), sourceVersion, migratedGeneration);
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

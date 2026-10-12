package mmm.simulation;

import mmm.MmmMod;
import mmm.api.RegionAvailabilityReason;
import mmm.application.RegionCatalog;
import mmm.application.port.RegionCheckpointPort;
import mmm.application.result.CheckpointRegionResult;
import mmm.chemistry.ChemicalRegistry;
import mmm.fluid.FluidDomain;
import mmm.fluid.MaterialRegistry;
import mmm.fluid.air.GasRegistry;
import mmm.granular.GranularRegistry;
import mmm.simulation.controller.FluidBoundaryScanner;
import mmm.simulation.controller.FluidControllerRegions;
import mmm.simulation.controller.FluidRegionFootprint;
import mmm.simulation.ownership.OwnershipJournal;
import mmm.thermal.ThermalRegistry;
import mmm.thermal.ThermalTransport;
import mmm.worldprofile.WorldCapabilityProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;


/** Persistence-backed availability and administration for regions; it never steps physics or changes world blocks. */
final class FluidRegionOperations implements RegionCheckpointPort {
	private final FluidRegionManager manager;
	private final RegionRepositoryAdapter persistence;
	private final OwnershipTransactionCoordinator ownershipTransactions;
	private final boolean worldProfileProven;
	private final List<String> issues = new ArrayList<>();
	private final Map<UUID, Long> retryAt = new HashMap<>();
	private final Map<UUID, String> checkpointFailures = new HashMap<>();
	private boolean creationLocked;
	private long serviceTick;

	FluidRegionOperations(
			Path worldRoot, FluidRegionManager manager, boolean configurationLocked, boolean worldProfileProven,
			WorldCapabilityProfile profile, ChemicalRegistry chemicalRegistry,
			GranularRegistry granularRegistry, ThermalRegistry thermalRegistry, GasRegistry gasRegistry,
			int maximumThermalSubsteps, MaterialRegistry materialDefinitions
	) {
		this.manager = manager;
		this.worldProfileProven = worldProfileProven;
		this.persistence = new RegionRepositoryAdapter(worldRoot, profile, chemicalRegistry, granularRegistry,
				thermalRegistry, gasRegistry, maximumThermalSubsteps, materialDefinitions);
		this.ownershipTransactions = persistence.newOwnershipTransactions();
		creationLocked = configurationLocked || !worldProfileProven;
		if (worldProfileProven) {
			List<String> metadataIssues = persistence.loadMetadata(manager);
			issues.addAll(metadataIssues);
			creationLocked |= !metadataIssues.isEmpty();
		} else {
			issues.add("world capability profile is not proven; region-state metadata was left unread");
		}
	}

	void advanceTick() { serviceTick++; }
	boolean creationLocked() { return creationLocked; }
	List<String> issues() { return List.copyOf(issues); }
	void recordIssue(String issue) {
		issues.add(issue);
		creationLocked = true;
	}
	long checkpoint(FluidRegion region) throws IOException { return persistence.checkpoint(region); }
	long committedGeneration(UUID regionId) throws IOException { return persistence.committedGeneration(regionId); }
	long committedManifestChecksum(UUID regionId) throws IOException { return persistence.committedManifestChecksum(regionId); }
	void adoptCommittedContainerCheckpoint(FluidRegion region, long baseGeneration, long targetGeneration)
			throws IOException {
		persistence.adoptCommittedContainerCheckpoint(region, baseGeneration, targetGeneration);
	}
	void requireRemovalQuarantineCapacity(FluidRegion region, long maximumEntries, long maximumBytes) throws IOException {
		persistence.requireRemovalQuarantineCapacity(region, maximumEntries, maximumBytes);
	}
	mmm.simulation.persistence.RegionGenerationStore.PreparedRemoval prepareRemoval(
			FluidRegion region, long networkGeneration, UUID transactionId, long maximumEntries, long maximumBytes
	) throws IOException {
		return persistence.prepareRemoval(region, networkGeneration, transactionId, maximumEntries, maximumBytes);
	}
	mmm.simulation.persistence.RemovedRegionManifest commitRemoval(
			mmm.simulation.persistence.RegionGenerationStore.PreparedRemoval prepared
	) throws IOException { return persistence.commitRemoval(prepared); }
	mmm.simulation.persistence.RemovedRegionManifest readRemoved(UUID regionId) throws IOException {
		return persistence.readRemoved(regionId);
	}
	void abortRemoval(mmm.simulation.persistence.RegionGenerationStore.PreparedRemoval prepared) throws IOException {
		persistence.abortRemoval(prepared);
	}
	Path convergeRemovalQuarantine(UUID id) throws IOException {
		return persistence.convergeRemovalQuarantine(id);
	}
	void recoverRemovedQuarantines() {
		List<String> failures = persistence.recoverRemovedQuarantines();
		issues.addAll(failures); creationLocked |= !failures.isEmpty();
	}
	RegionRepositoryAdapter.StagedCheckpoint stageCheckpoint(FluidRegion region, long target) throws IOException {
		return persistence.stageCheckpoint(region, target);
	}
	RegionRepositoryAdapter.StagedCheckpoint stageLifecycleCheckpoint(
			FluidRegion region, FluidDomain candidateDomain, long target
	) throws IOException {
		return persistence.stageLifecycleCheckpoint(region, candidateDomain, target);
	}
	RegionRepositoryAdapter.StagedCheckpoint stageContainerCheckpoint(
			FluidRegion region, FluidDomain candidateDomain, long target, boolean operatorPaused
	) throws IOException {
		return persistence.stageCandidateCheckpoint(region, candidateDomain, target, operatorPaused);
	}
	long commitStagedLifecycleCheckpoint(RegionRepositoryAdapter.StagedCheckpoint staged) throws IOException {
		return persistence.commitStagedLifecycleCheckpoint(staged);
	}
	long commitStagedCheckpoint(FluidRegion region, RegionRepositoryAdapter.StagedCheckpoint staged) throws IOException {
		return persistence.commitStagedCheckpoint(region, staged);
	}
	void abortStagedCheckpoint(RegionRepositoryAdapter.StagedCheckpoint staged) throws IOException {
		persistence.abortStagedCheckpoint(staged);
	}
	List<OwnershipJournal.BlockEntry> scanOwnershipPreimage(
			ServerLevel level, FluidRegionFootprint footprint
	) throws IOException {
		return ownershipTransactions.scanPreimage(level, footprint);
	}
	void acquireOwnership(ServerLevel level, FluidRegion region,
			List<OwnershipJournal.BlockEntry> candidates) throws IOException {
		ownershipTransactions.acquire(level, region, candidates);
	}
	List<String> scanOwnershipIssues() {
		return worldProfileProven ? ownershipTransactions.scanIssues()
				: List.of("world capability profile is not proven; ownership metadata was left unread");
	}
	List<String> recoverUnfinishedOwnership(MinecraftServer server) {
		return worldProfileProven ? ownershipTransactions.recoverUnfinished(server)
				: List.of("world capability profile is not proven; ownership recovery was not attempted");
	}
	String ownershipLockDescription(FluidRegion region) { return persistence.ownershipLockDescription(region); }
	int committedOwnershipBlockCount(FluidRegion region) throws IOException {
		return ownershipTransactions.committedBlockCount(region);
	}

	String ownershipSummary(MinecraftServer server, FluidRegion region) {
		return ownershipTransactions.summary(server, region);
	}

	OwnershipTransactionCoordinator.ReleaseAttempt releaseOwnership(MinecraftServer server, FluidRegion region) {
		return ownershipTransactions.release(server, region);
	}

	boolean ensureAvailable(
			ServerLevel level,
			FluidRegion region,
			boolean controllersEnabled,
			CollisionRefresher collisionRefresher,
			SnapshotSender snapshotSender,
			RemovalSender removalSender,
			boolean inventoryDecrease
	) {
		String numericalFailure = null;
		if (region.chemistryBlockFailure() != null) {
			numericalFailure = region.chemistryBlockFailure();
		} else if (region.granularBlockFailure() != null) {
			numericalFailure = region.granularBlockFailure();
		} else if (region.thermalBlockFailure() != null) {
			numericalFailure = region.thermalBlockFailure();
		} else if (region.numericalBlockFailure() != null) {
			numericalFailure = region.numericalBlockFailure();
		}
		boolean numericalPaused = numericalFailure != null;
		if (numericalPaused && !(inventoryDecrease && MinecraftRegionAvailability.inventoryDecreaseMayBypass(
				RegionAvailabilityReason.NUMERICAL_PAUSE))) {
			region.setAvailabilityFailure(RegionAvailabilityReason.NUMERICAL_PAUSE, numericalFailure);
			return false;
		}
		boolean performancePaused = region.performanceBlockFailure() != null;
		if (performancePaused && !(inventoryDecrease && MinecraftRegionAvailability.inventoryDecreaseMayBypass(
				RegionAvailabilityReason.PERFORMANCE_PAUSE))) {
			region.setAvailabilityFailure(RegionAvailabilityReason.PERFORMANCE_PAUSE, region.performanceBlockFailure());
			return false;
		}
		if (!region.controllerManaged()) {
			if (numericalPaused) region.setAvailabilityFailure(
					RegionAvailabilityReason.NUMERICAL_PAUSE, numericalFailure);
			else if (performancePaused) region.setAvailabilityFailure(
					RegionAvailabilityReason.PERFORMANCE_PAUSE, region.performanceBlockFailure());
			else region.setAvailabilityFailure(null);
			return true;
		}
		String ownershipLock = persistence.ownershipLockDescription(region);
		if (ownershipLock != null) {
			region.setAvailabilityFailure(RegionAvailabilityReason.PENDING_WAL,
					"存在未解决 ownership 状态；恢复完成前不能运行或 checkpoint：" + ownershipLock);
			return false;
		}
		if (!controllersEnabled) {
			unloadForFailure(level.getServer(), region, RegionAvailabilityReason.POLICY_DISABLED,
					"controller admission policy is disabled", removalSender);
			return false;
		}
		RegionCatalog.AdmissionResult admission = manager.runtimeAdmission(region);
		if (!admission.accepted()) {
			unloadForFailure(level.getServer(), region, admission.reason(), admission.failure(), removalSender);
			return false;
		}
		FluidBoundaryScanner.AvailabilityResult availability = FluidControllerRegions.availability(level, region.footprint());
		if (!availability.ready()) {
			RegionAvailabilityReason reason = availability.status() == FluidBoundaryScanner.AvailabilityStatus.INCOMPLETE_CHUNKS
					? RegionAvailabilityReason.CHUNK_MISSING : RegionAvailabilityReason.BOUNDARY_OPEN;
			unloadForFailure(level.getServer(), region, reason, availability.failure(), removalSender);
			return false;
		}
		if (!region.solverLoaded()) {
			String lockedFailure = checkpointFailures.get(region.id());
			if (lockedFailure != null) {
				region.setAvailabilityFailure(RegionAvailabilityReason.HEAD_INVALID, lockedFailure);
				return false;
			}
			if (serviceTick < retryAt.getOrDefault(region.id(), 0L)) return false;
			try {
				persistence.restore(region);
				collisionRefresher.refresh(level, region);
				region.markCollisionRefreshed();
				retryAt.remove(region.id());
				checkpointFailures.remove(region.id());
				MmmMod.LOGGER.info("Restored controller region {} from checkpoint {} with {} markers",
						region.id(), region.checkpointGeneration(), region.domain().particleCount());
				snapshotSender.send(level, region);
			} catch (IOException | RuntimeException exception) {
				String message = "checkpoint restore failed: " + exception.getMessage();
				region.setAvailabilityFailure(RegionAvailabilityReason.HEAD_INVALID, message);
				checkpointFailures.put(region.id(), message);
				retryAt.remove(region.id());
				MmmMod.LOGGER.error("Could not restore controller region {}", region.id(), exception);
				return false;
			}
		}
		if (region.checkpointGeneration() == 0L) {
			if (serviceTick < retryAt.getOrDefault(region.id(), 0L)) return false;
			try {
				persistence.checkpoint(region);
				retryAt.remove(region.id());
				checkpointFailures.remove(region.id());
			} catch (IOException | RuntimeException exception) {
				String message = "initial checkpoint failed; live solver retained: " + exception.getMessage();
				region.setAvailabilityFailure(RegionAvailabilityReason.HEAD_INVALID, message);
				checkpointFailures.put(region.id(), message);
				retryAt.put(region.id(), serviceTick + 100L);
				MmmMod.LOGGER.error("Could not publish initial checkpoint for controller region {}", region.id(), exception);
				return false;
			}
		}
		if (region.collisionRefreshRequired()) {
			collisionRefresher.refresh(level, region);
			region.markCollisionRefreshed();
		}
		if (numericalPaused) region.setAvailabilityFailure(
				RegionAvailabilityReason.NUMERICAL_PAUSE, numericalFailure);
		else if (performancePaused) region.setAvailabilityFailure(
				RegionAvailabilityReason.PERFORMANCE_PAUSE, region.performanceBlockFailure());
		else region.setAvailabilityFailure(null);
		return true;
	}

	void unloadForFailure(
			MinecraftServer server, FluidRegion region, RegionAvailabilityReason reason,
			String failure, RemovalSender removalSender
	) {
		if (!region.controllerManaged()) {
			region.setAvailabilityFailure(reason, failure);
			return;
		}
		String checkpointFailure = checkpointFailures.get(region.id());
		String displayedFailure = checkpointFailure == null ? failure : failure + "; " + checkpointFailure;
		if (!displayedFailure.equals(region.availabilityFailure())) MmmMod.LOGGER.warn("Pausing controller region {}: {}", region.id(), failure);
		region.setAvailabilityFailure(reason, displayedFailure);
		if (!region.solverLoaded() || serviceTick < retryAt.getOrDefault(region.id(), 0L)) return;
		try {
			persistence.checkpointAndUnload(region);
			region.setAvailabilityFailure(reason, failure);
			retryAt.remove(region.id());
			checkpointFailures.remove(region.id());
			removalSender.send(server, region);
			MmmMod.LOGGER.info("Checkpointed and unloaded controller region {} because {}", region.id(), failure);
		} catch (IOException | RuntimeException exception) {
			String message = "checkpoint failed; live solver retained: " + exception.getMessage();
			checkpointFailures.put(region.id(), message);
			retryAt.put(region.id(), serviceTick + 100L);
			region.setAvailabilityFailure(reason, failure + "; " + message);
			MmmMod.LOGGER.error("Could not checkpoint controller region {}; solver remains paused in memory", region.id(), exception);
		}
	}

	List<String> summaries() {
		FluidRegion selected = manager.selected();
		List<String> summaries = new ArrayList<>();
		for (FluidRegion region : manager.regions()) {
			summaries.add((region == selected ? "* " : "  ") + region.id() + " "
					+ region.dimension().identifier() + " " + (region.controllerManaged() ? "controller" : "command")
					+ " " + region.lifecycle().state() + "/" + (region.paused() ? "paused" : "running")
					+ " availability=" + region.availabilityDescription()
					+ " origin=" + region.origin().toShortString() + " grid=" + region.sizeX() + "x" + region.sizeY() + "x" + region.sizeZ()
					+ " reserve=" + region.reservedCellCapacity() + "/" + region.reservedMarkerCapacity()
					+ (region.solverLoaded() ? " markers=" + region.domain().particleCount() + " volume=" + region.domain().totalVolume()
					: " checkpoint=" + region.checkpointGeneration() + " markers=" + region.checkpointMarkerCount() + " volume=" + region.checkpointVolume()));
		}
		for (String issue : issues) summaries.add("! locked persistence: " + issue);
		return List.copyOf(summaries);
	}

	SelectionResult select(String prefix) {
		String normalized = prefix.trim().toLowerCase(Locale.ROOT);
		if (normalized.isEmpty()) return SelectionResult.failed("区域 UUID 前缀不能为空");
		List<FluidRegion> matches = manager.regions().stream()
				.filter(region -> region.id().toString().toLowerCase(Locale.ROOT).startsWith(normalized)).toList();
		if (matches.isEmpty()) return SelectionResult.failed("没有匹配的区域：" + prefix);
		if (matches.size() > 1) return SelectionResult.failed("区域前缀不唯一，共匹配 " + matches.size() + " 个区域");
		FluidRegion region = matches.getFirst();
		manager.select(region.id());
		return new SelectionResult(true, region.id(), null);
	}

	@Override public Attempt checkpoint(UUID regionId, long expectedCheckpointGeneration) {
		FluidRegion region = manager.region(regionId);
		if (region == null) return new Attempt(CheckpointRegionResult.Status.REGION_NOT_FOUND, 0L, null);
		if (!region.controllerManaged()) return new Attempt(CheckpointRegionResult.Status.NOT_PERSISTENT,
				region.checkpointGeneration(), null);
		if (region.checkpointGeneration() != expectedCheckpointGeneration) {
			return new Attempt(CheckpointRegionResult.Status.STALE_GENERATION,
					region.checkpointGeneration(), null);
		}
		if (!region.solverLoaded()) return new Attempt(CheckpointRegionResult.Status.ALREADY_UNLOADED,
				region.checkpointGeneration(), null);
		String ownershipLock = persistence.ownershipLockDescription(region);
		if (ownershipLock != null) {
			region.setPaused(true);
			region.setAvailabilityFailure(RegionAvailabilityReason.PENDING_WAL,
					"存在未解决 ownership 状态；拒绝 checkpoint：" + ownershipLock);
			return new Attempt(CheckpointRegionResult.Status.OWNERSHIP_BLOCKED,
					region.checkpointGeneration(), region.availabilityFailure());
		}
		region.setPaused(true);
		try {
			long generation = persistence.checkpoint(region);
			checkpointFailures.remove(region.id());
			retryAt.remove(region.id());
			return new Attempt(CheckpointRegionResult.Status.COMMITTED, generation, null);
		} catch (IOException | RuntimeException exception) {
			String failure = "checkpoint 失败，live solver 已保留：" + exception.getMessage();
			checkpointFailures.put(region.id(), failure);
			return new Attempt(CheckpointRegionResult.Status.PERSISTENCE_FAILED,
					region.checkpointGeneration(), failure);
		}
	}

	String recoverPreviousSelected(long expectedCurrentGeneration) {
		FluidRegion region = manager.selected();
		if (region == null) return "没有选中区域";
		if (!region.controllerManaged()) return "开发命令区域没有持久 checkpoint 可恢复";
		String restoreFailure = checkpointFailures.get(region.id());
		if (restoreFailure == null || !restoreFailure.startsWith("checkpoint restore failed:")) {
			return "拒绝恢复上一代：当前区域尚未出现可复核的 checkpoint restore failure";
		}
		try {
			long restored = persistence.recoverPreviousGeneration(region, expectedCurrentGeneration);
			String restart = "已按 operator 明确指定将 HEAD 从 checkpoint " + expectedCurrentGeneration
					+ " 原子切回完整 checkpoint " + restored
					+ "；被拒绝的当前 slot 仍保留，必须退出并重启世界后才能载入上一代";
			checkpointFailures.put(region.id(), restart);
			region.setAvailabilityFailure(RegionAvailabilityReason.HEAD_INVALID, restart);
			retryAt.remove(region.id());
			return restart;
		} catch (IOException | RuntimeException exception) {
			return "上一代恢复失败，HEAD 未改变：" + exception.getMessage();
		}
	}

	void checkpointForShutdown(Predicate<FluidRegion> shouldCheckpoint) {
		for (FluidRegion region : manager.regions()) {
			if (!region.controllerManaged() || !region.solverLoaded()) continue;
			if (!shouldCheckpoint.test(region)) {
				MmmMod.LOGGER.warn("Skipping shutdown checkpoint for controller region {} because a container transfer WAL is unresolved", region.id());
				continue;
			}

			try {
				long generation = persistence.checkpoint(region);
				MmmMod.LOGGER.info("Published shutdown checkpoint {} for controller region {}", generation, region.id());
			} catch (IOException | RuntimeException exception) {
				MmmMod.LOGGER.error("Could not checkpoint controller region {} during shutdown; previous generation remains authoritative",
						region.id(), exception);
			}
		}
	}

	@FunctionalInterface interface CollisionRefresher { FluidRegion.CollisionStats refresh(ServerLevel level, FluidRegion region); }
	@FunctionalInterface interface SnapshotSender { void send(ServerLevel level, FluidRegion region); }
	@FunctionalInterface interface RemovalSender { void send(MinecraftServer server, FluidRegion region); }
}

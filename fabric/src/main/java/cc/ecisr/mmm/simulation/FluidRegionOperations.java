package cc.ecisr.mmm.simulation;

import cc.ecisr.mmm.MmmMod;
import cc.ecisr.mmm.api.RegionAvailabilityReason;
import cc.ecisr.mmm.application.RegionAdmissionService;
import cc.ecisr.mmm.application.port.RegionCheckpointPort;
import cc.ecisr.mmm.application.result.CheckpointRegionResult;
import cc.ecisr.mmm.chemistry.ChemicalRegistry;
import cc.ecisr.mmm.fluid.FluidDomain;
import cc.ecisr.mmm.fluid.air.GasRegistry;
import cc.ecisr.mmm.granular.GranularRegistry;
import cc.ecisr.mmm.simulation.controller.FluidBoundaryScanner;
import cc.ecisr.mmm.simulation.controller.FluidControllerRegions;
import cc.ecisr.mmm.simulation.controller.FluidRegionFootprint;
import cc.ecisr.mmm.simulation.ownership.OwnershipJournal;
import cc.ecisr.mmm.thermal.ThermalRegistry;
import cc.ecisr.mmm.thermal.ThermalTransport;
import cc.ecisr.mmm.worldprofile.WorldCapabilityProfile;
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
	private final FluidRegionPersistence persistence;
	private final RegionAdmissionService admission = new RegionAdmissionService();
	private final boolean worldProfileProven;
	private final List<String> issues = new ArrayList<>();
	private final Map<UUID, Long> retryAt = new HashMap<>();
	private final Map<UUID, String> checkpointFailures = new HashMap<>();
	private boolean creationLocked;
	private long serviceTick;

	FluidRegionOperations(
			Path worldRoot, FluidRegionManager manager, boolean configurationLocked, boolean worldProfileProven,
			WorldCapabilityProfile profile, ChemicalRegistry chemicalRegistry,
			GranularRegistry granularRegistry, ThermalRegistry thermalRegistry, GasRegistry gasRegistry
	) {
		this(worldRoot, manager, configurationLocked, worldProfileProven, profile, chemicalRegistry, granularRegistry, thermalRegistry,
				gasRegistry, ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS);
	}

	FluidRegionOperations(
			Path worldRoot, FluidRegionManager manager, boolean configurationLocked, boolean worldProfileProven,
			WorldCapabilityProfile profile, ChemicalRegistry chemicalRegistry,
			GranularRegistry granularRegistry, ThermalRegistry thermalRegistry, GasRegistry gasRegistry,
			int maximumThermalSubsteps
	) {
		this.manager = manager;
		this.worldProfileProven = worldProfileProven;
		this.persistence = new FluidRegionPersistence(worldRoot, profile, chemicalRegistry, granularRegistry,
				thermalRegistry, gasRegistry, maximumThermalSubsteps);
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
	void requireRemovalQuarantineCapacity(FluidRegion region, long maximumEntries, long maximumBytes) throws IOException {
		persistence.requireRemovalQuarantineCapacity(region, maximumEntries, maximumBytes);
	}
	cc.ecisr.mmm.simulation.persistence.RegionStateFiles.PreparedRemoval prepareRemoval(
			FluidRegion region, long networkGeneration, UUID transactionId, long maximumEntries, long maximumBytes
	) throws IOException {
		return persistence.prepareRemoval(region, networkGeneration, transactionId, maximumEntries, maximumBytes);
	}
	cc.ecisr.mmm.simulation.persistence.RemovedRegionManifest commitRemoval(
			cc.ecisr.mmm.simulation.persistence.RegionStateFiles.PreparedRemoval prepared
	) throws IOException { return persistence.commitRemoval(prepared); }
	cc.ecisr.mmm.simulation.persistence.RemovedRegionManifest readRemoved(UUID regionId) throws IOException {
		return persistence.readRemoved(regionId);
	}
	void abortRemoval(cc.ecisr.mmm.simulation.persistence.RegionStateFiles.PreparedRemoval prepared) throws IOException {
		persistence.abortRemoval(prepared);
	}
	Path convergeRemovalQuarantine(UUID id, long maximumEntries, long maximumBytes) throws IOException {
		return persistence.convergeRemovalQuarantine(id, maximumEntries, maximumBytes);
	}
	void recoverRemovedQuarantines(long maximumEntries, long maximumBytes) {
		List<String> failures = persistence.recoverRemovedQuarantines(maximumEntries, maximumBytes);
		issues.addAll(failures); creationLocked |= !failures.isEmpty();
	}
	FluidRegionPersistence.StagedCheckpoint stageCheckpoint(FluidRegion region, long target) throws IOException {
		return persistence.stageCheckpoint(region, target);
	}
	FluidRegionPersistence.StagedCheckpoint stageLifecycleCheckpoint(
			FluidRegion region, FluidDomain candidateDomain, long target
	) throws IOException {
		return persistence.stageLifecycleCheckpoint(region, candidateDomain, target);
	}
	long commitStagedLifecycleCheckpoint(FluidRegionPersistence.StagedCheckpoint staged) throws IOException {
		return persistence.commitStagedLifecycleCheckpoint(staged);
	}
	long commitStagedCheckpoint(FluidRegion region, FluidRegionPersistence.StagedCheckpoint staged) throws IOException {
		return persistence.commitStagedCheckpoint(region, staged);
	}
	void abortStagedCheckpoint(FluidRegionPersistence.StagedCheckpoint staged) throws IOException {
		persistence.abortStagedCheckpoint(staged);
	}
	List<OwnershipJournal.BlockEntry> scanOwnershipPreimage(
			ServerLevel level, FluidRegionFootprint footprint
	) throws IOException {
		return persistence.scanOwnershipPreimage(level, footprint);
	}
	void acquireOwnership(ServerLevel level, FluidRegion region,
			List<OwnershipJournal.BlockEntry> candidates) throws IOException {
		persistence.acquireOwnership(level, region, candidates);
	}
	List<String> scanOwnershipIssues() {
		return worldProfileProven ? persistence.scanOwnershipIssues()
				: List.of("world capability profile is not proven; ownership metadata was left unread");
	}
	List<String> recoverUnfinishedOwnership(MinecraftServer server) {
		return worldProfileProven ? persistence.recoverUnfinishedOwnership(server)
				: List.of("world capability profile is not proven; ownership recovery was not attempted");
	}
	String ownershipLockDescription(FluidRegion region) { return persistence.ownershipLockDescription(region); }
	int committedOwnershipBlockCount(FluidRegion region) throws IOException {
		return persistence.committedOwnershipBlockCount(region);
	}

	String ownershipSummary(MinecraftServer server, FluidRegion region) {
		return persistence.ownershipSummary(server, region);
	}

	FluidRegionPersistence.ReleaseAttempt releaseOwnership(MinecraftServer server, FluidRegion region) {
		return persistence.releaseOwnership(server, region);
	}

	boolean ensureAvailable(
			ServerLevel level,
			FluidRegion region,
			boolean controllersEnabled,
			CollisionRefresher collisionRefresher,
			SnapshotSender snapshotSender,
			RemovalSender removalSender
	) {
		RegionAdmissionService.Decision initial = admission.initial(new RegionAdmissionService.InitialFacts(
				region.chemistryBlockFailure(), region.granularBlockFailure(), region.thermalBlockFailure(),
				region.numericalBlockFailure(), region.controllerManaged()));
		if (initial.disposition() == RegionAdmissionService.Disposition.AVAILABLE) {
			region.setAvailabilityFailure(null);
			return true;
		}
		if (initial.disposition() != RegionAdmissionService.Disposition.PROCEED) {
			applyAdmissionBlock(level.getServer(), region, initial, removalSender);
			return false;
		}
		String ownershipLock = persistence.ownershipLockDescription(region);
		RegionAdmissionService.Decision policy = admission.policy(
				new RegionAdmissionService.PolicyFacts(ownershipLock == null ? null
						: "存在未解决 ownership 状态；恢复完成前不能运行或 checkpoint：" + ownershipLock,
						controllersEnabled));
		if (policy.disposition() != RegionAdmissionService.Disposition.PROCEED) {
			applyAdmissionBlock(level.getServer(), region, policy, removalSender);
			return false;
		}
		FluidRegionManager.AdmissionResult catalogFact = manager.runtimeAdmission(region);
		RegionAdmissionService.Decision catalogDecision = admission.catalog(new RegionAdmissionService.CatalogFact(
				catalogFact.accepted(), catalogFact.reason(), catalogFact.failure()));
		if (catalogDecision.disposition() != RegionAdmissionService.Disposition.PROCEED) {
			applyAdmissionBlock(level.getServer(), region, catalogDecision, removalSender);
			return false;
		}
		FluidBoundaryScanner.AvailabilityResult availability = FluidControllerRegions.availability(level, region.footprint());
		RegionAdmissionService.BoundaryStatus boundaryStatus = switch (availability.status()) {
			case READY -> RegionAdmissionService.BoundaryStatus.READY;
			case INCOMPLETE_CHUNKS -> RegionAdmissionService.BoundaryStatus.CHUNK_MISSING;
			case OPEN_BOUNDARY -> RegionAdmissionService.BoundaryStatus.OPEN_BOUNDARY;
		};
		RegionAdmissionService.Decision boundary = admission.boundary(
				new RegionAdmissionService.BoundaryFact(boundaryStatus, availability.failure()));
		if (boundary.disposition() != RegionAdmissionService.Disposition.PROCEED) {
			applyAdmissionBlock(level.getServer(), region, boundary, removalSender);
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
		region.setAvailabilityFailure(null);
		return true;
	}

	private void applyAdmissionBlock(
			MinecraftServer server, FluidRegion region,
			RegionAdmissionService.Decision decision, RemovalSender removalSender
	) {
		switch (decision.disposition()) {
			case BLOCK_IN_PLACE -> region.setAvailabilityFailure(decision.reason(), decision.failure());
			case UNLOAD -> unloadForFailure(server, region, decision.reason(), decision.failure(), removalSender);
			case AVAILABLE, PROCEED -> throw new IllegalArgumentException(
					"Admission decision does not block the region: " + decision.disposition());
		}
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

	FluidSimulationService.SelectionResult select(String prefix) {
		String normalized = prefix.trim().toLowerCase(Locale.ROOT);
		if (normalized.isEmpty()) return FluidSimulationService.SelectionResult.failed("区域 UUID 前缀不能为空");
		List<FluidRegion> matches = manager.regions().stream()
				.filter(region -> region.id().toString().toLowerCase(Locale.ROOT).startsWith(normalized)).toList();
		if (matches.isEmpty()) return FluidSimulationService.SelectionResult.failed("没有匹配的区域：" + prefix);
		if (matches.size() > 1) return FluidSimulationService.SelectionResult.failed("区域前缀不唯一，共匹配 " + matches.size() + " 个区域");
		FluidRegion region = matches.getFirst();
		manager.select(region.id());
		return new FluidSimulationService.SelectionResult(true, region.id(), null);
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

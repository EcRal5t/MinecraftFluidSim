package com.example.simulation;

import com.example.RealWaterMod;
import com.example.chemistry.ChemicalRegistry;
import com.example.fluid.air.GasRegistry;
import com.example.granular.GranularRegistry;
import com.example.thermal.ThermalRegistry;
import com.example.thermal.ThermalTransport;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;


/** Persistence-backed availability and administration for regions; it never steps physics or changes world blocks. */
final class FluidRegionOperations {
	private final FluidRegionManager manager;
	private final FluidRegionPersistence persistence;
	private final List<String> issues = new ArrayList<>();
	private final Map<UUID, Long> retryAt = new HashMap<>();
	private final Map<UUID, String> checkpointFailures = new HashMap<>();
	private final Set<UUID> reportedThermalInitialization = new HashSet<>();
	private final Map<UUID, Set<UUID>> thermalInitializationNotifiedPlayers = new HashMap<>();
	private boolean creationLocked;
	private long serviceTick;

	FluidRegionOperations(
			Path worldRoot, FluidRegionManager manager, boolean configurationLocked, boolean materialsEnabled,
			boolean chemistryEnabled, ChemicalRegistry chemicalRegistry
	) {
		this(worldRoot, manager, configurationLocked, materialsEnabled, chemistryEnabled, chemicalRegistry, false, null);
	}

	FluidRegionOperations(
			Path worldRoot, FluidRegionManager manager, boolean configurationLocked, boolean materialsEnabled,
			boolean chemistryEnabled, ChemicalRegistry chemicalRegistry,
			boolean granularEnabled, GranularRegistry granularRegistry
	) {
		this(worldRoot, manager, configurationLocked, materialsEnabled, chemistryEnabled, chemicalRegistry,
				granularEnabled, granularRegistry, false);
	}

	FluidRegionOperations(
			Path worldRoot, FluidRegionManager manager, boolean configurationLocked, boolean materialsEnabled,
			boolean chemistryEnabled, ChemicalRegistry chemicalRegistry,
			boolean granularEnabled, GranularRegistry granularRegistry, boolean precipitationEnabled
	) {
		this(worldRoot, manager, configurationLocked, materialsEnabled, chemistryEnabled, chemicalRegistry,
				granularEnabled, granularRegistry, precipitationEnabled, false, null, null);
	}

	FluidRegionOperations(
			Path worldRoot, FluidRegionManager manager, boolean configurationLocked, boolean materialsEnabled,
			boolean chemistryEnabled, ChemicalRegistry chemicalRegistry,
			boolean granularEnabled, GranularRegistry granularRegistry, boolean precipitationEnabled,
			boolean thermalEnabled, ThermalRegistry thermalRegistry, GasRegistry gasRegistry
	) {
		this(worldRoot, manager, configurationLocked, materialsEnabled, chemistryEnabled, chemicalRegistry,
				granularEnabled, granularRegistry, precipitationEnabled, thermalEnabled, thermalRegistry, gasRegistry,
				ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS);
	}

	FluidRegionOperations(
			Path worldRoot, FluidRegionManager manager, boolean configurationLocked, boolean materialsEnabled,
			boolean chemistryEnabled, ChemicalRegistry chemicalRegistry,
			boolean granularEnabled, GranularRegistry granularRegistry, boolean precipitationEnabled,
			boolean thermalEnabled, ThermalRegistry thermalRegistry, GasRegistry gasRegistry,
			int maximumThermalSubsteps
	) {
		this.manager = manager;
		this.persistence = new FluidRegionPersistence(worldRoot, materialsEnabled, chemistryEnabled, chemicalRegistry,
				granularEnabled, granularRegistry, precipitationEnabled, thermalEnabled, thermalRegistry, gasRegistry,
				maximumThermalSubsteps);
		creationLocked = configurationLocked;
		List<String> metadataIssues = persistence.loadMetadata(manager);
		issues.addAll(metadataIssues);
		creationLocked |= !metadataIssues.isEmpty();
	}

	void advanceTick() { serviceTick++; }
	boolean creationLocked() { return creationLocked; }
	List<String> issues() { return List.copyOf(issues); }
	void recordIssue(String issue) { issues.add(issue); }
	long checkpoint(FluidRegion region) throws IOException { return persistence.checkpoint(region); }
	long committedGeneration(UUID regionId) throws IOException { return persistence.committedGeneration(regionId); }
	long committedManifestChecksum(UUID regionId) throws IOException { return persistence.committedManifestChecksum(regionId); }
	FluidRegionPersistence.StagedCheckpoint stageCheckpoint(FluidRegion region, long target) throws IOException {
		return persistence.stageCheckpoint(region, target);
	}
	long commitStagedCheckpoint(FluidRegion region, FluidRegionPersistence.StagedCheckpoint staged) throws IOException {
		return persistence.commitStagedCheckpoint(region, staged);
	}
	void abortStagedCheckpoint(FluidRegionPersistence.StagedCheckpoint staged) throws IOException {
		persistence.abortStagedCheckpoint(staged);
	}
	void acquireOwnership(ServerLevel level, FluidRegion region) throws IOException { persistence.acquireOwnership(level, region); }
	List<String> scanOwnershipIssues() { return persistence.scanOwnershipIssues(); }
	List<String> recoverUnfinishedOwnership(MinecraftServer server) { return persistence.recoverUnfinishedOwnership(server); }
	String ownershipLockDescription(FluidRegion region) { return persistence.ownershipLockDescription(region); }

	String ownershipSummary(MinecraftServer server, FluidRegion region) {
		return persistence.ownershipSummary(server, region);
	}

	boolean ensureAvailable(
			ServerLevel level,
			FluidRegion region,
			boolean controllersEnabled,
			CollisionRefresher collisionRefresher,
			SnapshotSender snapshotSender,
			RemovalSender removalSender
	) {
		if (region.chemistryBlockFailure() != null) {
			region.setAvailabilityFailure(region.chemistryBlockFailure());
			return false;
		}
		if (region.granularBlockFailure() != null) {
			region.setAvailabilityFailure(region.granularBlockFailure());
			return false;
		}
		if (region.thermalBlockFailure() != null) {
			region.setAvailabilityFailure(region.thermalBlockFailure());
			return false;
		}
		if (!region.controllerManaged()) {
			region.setAvailabilityFailure(null);
			return true;
		}
		String ownershipLock = persistence.ownershipLockDescription(region);
		if (ownershipLock != null) {
			region.setPaused(true);
			region.setAvailabilityFailure("存在未解决 ownership 状态；恢复完成前不能运行或 checkpoint：" + ownershipLock);
			return false;
		}
		String failure = controllersEnabled
				? com.example.simulation.controller.FluidControllerRegions.availabilityFailure(level, region.footprint())
				: "controller feature flag is disabled";
		if (failure != null) {
			unloadForFailure(level.getServer(), region, failure, removalSender);
			return false;
		}
		if (region.thermalInitializationRequired()) {
			return awaitThermalInitialization(level, region);
		}

		if (!region.solverLoaded()) {
			String lockedFailure = checkpointFailures.get(region.id());
			if (lockedFailure != null) {
				region.setAvailabilityFailure(lockedFailure);
				return false;
			}
			if (serviceTick < retryAt.getOrDefault(region.id(), 0L)) return false;
			try {
				persistence.restore(region);
				collisionRefresher.refresh(level, region);
				region.markCollisionRefreshed();
				retryAt.remove(region.id());
				checkpointFailures.remove(region.id());
				region.clearThermalInitializationRequirement();
				RealWaterMod.LOGGER.info("Restored controller region {} from checkpoint {} with {} markers",
						region.id(), region.checkpointGeneration(), region.domain().particleCount());
				snapshotSender.send(level, region);
			} catch (FluidRegionPersistence.ThermalInitializationRequiredException required) {
				region.requireThermalInitialization(required.sourceVersion());
				return awaitThermalInitialization(level, region);
			} catch (IOException | RuntimeException exception) {
				String message = "checkpoint restore failed: " + exception.getMessage();
				region.setAvailabilityFailure(message);
				checkpointFailures.put(region.id(), message);
				retryAt.remove(region.id());
				RealWaterMod.LOGGER.error("Could not restore controller region {}", region.id(), exception);
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
				region.setAvailabilityFailure(message);
				checkpointFailures.put(region.id(), message);
				retryAt.put(region.id(), serviceTick + 100L);
				RealWaterMod.LOGGER.error("Could not publish initial checkpoint for controller region {}", region.id(), exception);
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

	private boolean awaitThermalInitialization(ServerLevel level, FluidRegion region) {
		String message = String.format(Locale.ROOT,
				"awaiting explicit thermal initialization: region-state v%d checkpoint %d still preserves %d markers / %.3f exact cell volume; "
						+ "the client intentionally receives no thermal snapshot until /realwater regions thermalinit <temperatureK>",
				region.thermalInitializationSourceVersion(), region.checkpointGeneration(),
				region.checkpointMarkerCount(), region.checkpointVolume());
		region.setPaused(true);
		region.setAvailabilityFailure(message);
		retryAt.remove(region.id());
		checkpointFailures.remove(region.id());
		if (reportedThermalInitialization.add(region.id())) {
			RealWaterMod.LOGGER.warn("Controller region {} {}", region.id(), message);
		}
		Set<UUID> notified = thermalInitializationNotifiedPlayers.computeIfAbsent(region.id(), ignored -> new HashSet<>());
		for (var player : level.players()) if (notified.add(player.getUUID())) {
			player.sendSystemMessage(Component.literal("真实水体区域 " + region.id().toString().substring(0, 8)
					+ " 的水仍安全保存在 checkpoint " + region.checkpointGeneration() + "（"
					+ region.checkpointMarkerCount() + " markers / " + region.checkpointVolume()
					+ " cell volume）；旧格式没有内能，因此初始化前有意不渲染。请执行 "
					+ "/realwater regions thermalinit <温度K>，或关闭 thermal 后重启。"));
		}
		return false;
	}

	void unloadForFailure(MinecraftServer server, FluidRegion region, String failure, RemovalSender removalSender) {
		if (!region.controllerManaged()) {
			region.setAvailabilityFailure(failure);
			return;
		}
		String checkpointFailure = checkpointFailures.get(region.id());
		String displayedFailure = checkpointFailure == null ? failure : failure + "; " + checkpointFailure;
		if (!displayedFailure.equals(region.availabilityFailure())) RealWaterMod.LOGGER.warn("Pausing controller region {}: {}", region.id(), failure);
		region.setAvailabilityFailure(displayedFailure);
		if (!region.solverLoaded() || serviceTick < retryAt.getOrDefault(region.id(), 0L)) return;
		try {
			persistence.checkpointAndUnload(region);
			region.setAvailabilityFailure(failure);
			retryAt.remove(region.id());
			checkpointFailures.remove(region.id());
			removalSender.send(server, region);
			RealWaterMod.LOGGER.info("Checkpointed and unloaded controller region {} because {}", region.id(), failure);
		} catch (IOException | RuntimeException exception) {
			String message = "checkpoint failed; live solver retained: " + exception.getMessage();
			checkpointFailures.put(region.id(), message);
			retryAt.put(region.id(), serviceTick + 100L);
			region.setAvailabilityFailure(failure + "; " + message);
			RealWaterMod.LOGGER.error("Could not checkpoint controller region {}; solver remains paused in memory", region.id(), exception);
		}
	}

	RegionRemoval deletePersistentRegions(MinecraftServer server) {
		if (creationLocked) return RegionRemoval.failed("存在未解决的 region-state；拒绝把未登记数据误报为已删除，请先按日志人工恢复或隔离");
		int releasedBlocks = 0;
		int releasedSkipped = 0;
		for (FluidRegion region : manager.regions()) {
			if (!region.controllerManaged()) continue;
			FluidRegionPersistence.ReleaseAttempt release = persistence.releaseOwnership(server, region);
			if (release.failure() != null) {
				for (FluidRegion retained : manager.regions()) retained.setPaused(true);
				return RegionRemoval.failed("ownership 解除未完成，移除已中止：" + release.failure());
			}
			releasedBlocks += release.restoredBlocks();
			releasedSkipped += release.skippedBlocks();
			try {
				persistence.delete(region);
			} catch (IOException exception) {
				for (FluidRegion retained : manager.regions()) retained.setPaused(true);
				return RegionRemoval.failed("持久区域删除未完成，内存 registry 已保留：" + exception.getMessage());
			}
		}
		return new RegionRemoval(null, releasedBlocks, releasedSkipped);
	}

	record RegionRemoval(String failure, int releasedBlocks, int releasedSkipped) {
		static RegionRemoval failed(String failure) {
			return new RegionRemoval(failure, 0, 0);
		}
	}

	List<String> summaries() {
		FluidRegion selected = manager.selected();
		List<String> summaries = new ArrayList<>();
		for (FluidRegion region : manager.regions()) {
			summaries.add((region == selected ? "* " : "  ") + region.id() + " "
					+ region.dimension().identifier() + " " + (region.controllerManaged() ? "controller" : "command")
					+ " " + region.lifecycle().state() + "/" + (region.paused() ? "paused" : "running")
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

	String checkpointSelected() {
		FluidRegion region = manager.selected();
		if (region == null) return "没有选中区域";
		if (!region.controllerManaged()) return "开发命令区域不写入 region-state";
		if (!region.solverLoaded()) return "区域已是 metadata-only UNLOADED；checkpoint " + region.checkpointGeneration();
		String ownershipLock = persistence.ownershipLockDescription(region);
		if (ownershipLock != null) {
			region.setPaused(true);
			region.setAvailabilityFailure("存在未解决 ownership 状态；拒绝 checkpoint：" + ownershipLock);
			return region.availabilityFailure();
		}
		region.setPaused(true);
		try {
			long generation = persistence.checkpoint(region);
			checkpointFailures.remove(region.id());
			retryAt.remove(region.id());
			return "checkpoint " + generation + " 已原子发布";
		} catch (IOException | RuntimeException exception) {
			String failure = "checkpoint 失败，live solver 已保留：" + exception.getMessage();
			checkpointFailures.put(region.id(), failure);
			return failure;
		}
	}

	String initializeSelectedThermal(double initialTemperatureK, String operatorMetadata) {
		FluidRegion region = manager.selected();
		if (region == null) return "没有选中区域";
		if (!region.controllerManaged()) return "开发命令区域不执行持久热初始化";
		if (!region.paused()) return "热初始化失败：区域必须先由 operator 暂停";
		try {
			long generation = persistence.initializeThermal(region, initialTemperatureK, operatorMetadata);
			region.clearThermalInitializationRequirement();
			region.setAvailabilityFailure(null);
			region.enableThermalActivity();
			region.wakeThermal(ThermalActivity.WakeReason.ADMIN_SOURCE);
			checkpointFailures.remove(region.id());
			retryAt.remove(region.id());
			reportedThermalInitialization.remove(region.id());
			thermalInitializationNotifiedPlayers.remove(region.id());
			return "已按显式初温 " + initialTemperatureK + " K 原子初始化 region-state v7；checkpoint "
					+ generation + "，区域保持暂停";
		} catch (IOException | RuntimeException exception) {
			String failure = "热初始化失败或需要恢复确认：" + exception.getMessage();
			checkpointFailures.put(region.id(), failure);
			region.setAvailabilityFailure(failure);
			return failure;
		}
	}

	void checkpointForShutdown(Predicate<FluidRegion> shouldCheckpoint) {
		for (FluidRegion region : manager.regions()) {
			if (!region.controllerManaged() || !region.solverLoaded()) continue;
			if (!shouldCheckpoint.test(region)) {
				RealWaterMod.LOGGER.warn("Skipping shutdown checkpoint for controller region {} because a container transfer WAL is unresolved", region.id());
				continue;
			}

			region.setPaused(true);
			try {
				long generation = persistence.checkpoint(region);
				RealWaterMod.LOGGER.info("Published shutdown checkpoint {} for controller region {}", generation, region.id());
			} catch (IOException | RuntimeException exception) {
				RealWaterMod.LOGGER.error("Could not checkpoint controller region {} during shutdown; previous generation remains authoritative",
						region.id(), exception);
			}
		}
	}

	@FunctionalInterface interface CollisionRefresher { FluidRegion.CollisionStats refresh(ServerLevel level, FluidRegion region); }
	@FunctionalInterface interface SnapshotSender { void send(ServerLevel level, FluidRegion region); }
	@FunctionalInterface interface RemovalSender { void send(MinecraftServer server, FluidRegion region); }
}

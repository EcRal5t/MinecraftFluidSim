package com.example.simulation;

import com.example.RealWaterMod;
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

/** Persistence-backed availability and administration for regions; it never steps physics or changes world blocks. */
final class FluidRegionOperations {
	private final FluidRegionManager manager;
	private final FluidRegionPersistence persistence;
	private final List<String> issues = new ArrayList<>();
	private final Map<UUID, Long> retryAt = new HashMap<>();
	private final Map<UUID, String> checkpointFailures = new HashMap<>();
	private boolean creationLocked;
	private long serviceTick;

	FluidRegionOperations(Path worldRoot, FluidRegionManager manager, boolean configurationLocked, boolean materialsEnabled) {
		this.manager = manager;
		this.persistence = new FluidRegionPersistence(worldRoot, materialsEnabled);
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
	void acquireOwnership(ServerLevel level, FluidRegion region) throws IOException { persistence.acquireOwnership(level, region); }
	List<String> scanOwnershipIssues() { return persistence.scanOwnershipIssues(); }
	List<String> recoverUnfinishedOwnership(MinecraftServer server) { return persistence.recoverUnfinishedOwnership(server); }

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
		if (!region.controllerManaged()) {
			region.setAvailabilityFailure(null);
			return true;
		}
		String failure = controllersEnabled
				? com.example.simulation.controller.FluidControllerRegions.availabilityFailure(level, region.footprint())
				: "controller feature flag is disabled";
		if (failure != null) {
			unloadForFailure(level.getServer(), region, failure, removalSender);
			return false;
		}

		if (!region.solverLoaded()) {
			if (serviceTick < retryAt.getOrDefault(region.id(), 0L)) return false;
			try {
				persistence.restore(region);
				collisionRefresher.refresh(level, region);
				region.markCollisionRefreshed();
				retryAt.remove(region.id());
				checkpointFailures.remove(region.id());
				RealWaterMod.LOGGER.info("Restored controller region {} from checkpoint {} with {} markers",
						region.id(), region.checkpointGeneration(), region.domain().particleCount());
				snapshotSender.send(level, region);
			} catch (IOException | RuntimeException exception) {
				String message = "checkpoint restore failed: " + exception.getMessage();
				region.setAvailabilityFailure(message);
				checkpointFailures.put(region.id(), message);
				retryAt.put(region.id(), serviceTick + 100L);
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

	void checkpointForShutdown() {
		for (FluidRegion region : manager.regions()) {
			if (!region.controllerManaged() || !region.solverLoaded()) continue;
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

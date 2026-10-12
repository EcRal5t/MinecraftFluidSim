package mmm.simulation;

import mmm.MmmMod;
import mmm.api.RegionAvailabilityReason;
import mmm.application.ContainerRecoveryCoordinator;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Owns server-tick advancement, startup recovery and shutdown checkpoint ordering. */
final class MinecraftSimulationLoop {
	private final FluidRegionManager regions;
	private final FluidRegionOperations operations;
	private final FluidSnapshotPublisher snapshots;
	private final ContainerRecoveryCoordinator containerRecovery;
	private final MinecraftTakeoverAdapter takeover;
	private final MinecraftRegionAvailability availability;
	private final MinecraftRegionTick regionTick;
	private final Consumer<List<String>> ownershipIssues;

	MinecraftSimulationLoop(
			FluidRegionManager regions,
			FluidRegionOperations operations,
			FluidSnapshotPublisher snapshots,
			ContainerRecoveryCoordinator containerRecovery,
			MinecraftTakeoverAdapter takeover,
			MinecraftRegionAvailability availability,
			MinecraftRegionTick regionTick,
			Consumer<List<String>> ownershipIssues
	) {
		this.regions = Objects.requireNonNull(regions, "Region manager");
		this.operations = Objects.requireNonNull(operations, "Region operations");
		this.snapshots = Objects.requireNonNull(snapshots, "Snapshot publisher");
		this.containerRecovery = Objects.requireNonNull(containerRecovery, "Container recovery coordinator");
		this.takeover = Objects.requireNonNull(takeover, "Takeover adapter");
		this.availability = Objects.requireNonNull(availability, "Region availability");
		this.regionTick = Objects.requireNonNull(regionTick, "Region tick");
		this.ownershipIssues = Objects.requireNonNull(ownershipIssues, "Ownership issue sink");
	}

	void tick(MinecraftServer server) {
		operations.advanceTick();
		containerRecovery.recoverIndexed();
		takeover.maintainActive(server);
		for (FluidRegion region : regions.regions()) {
			ServerLevel level = server.getLevel(region.dimension());
			if (level == null) {
				if (containerRecovery.hasPending(region.id())) {
					region.setAvailabilityFailure(RegionAvailabilityReason.PENDING_WAL,
							"dimension is not loaded; unresolved container transfer WAL prevents unload checkpoint");
					continue;
				}
				operations.unloadForFailure(server, region, RegionAvailabilityReason.CHUNK_MISSING,
						"dimension is not loaded", snapshots::sendRemoval);
				continue;
			}
			if (availability.ensure(level, region)) regionTick.tickAvailable(level, region);
		}
		regionTick.finishServerTick();
	}

	void recoverWorld(MinecraftServer server) {
		for (String issue : operations.issues()) {
			MmmMod.LOGGER.error("Matter, Motion & Mechanics persistent region issue: {}", issue);
		}
		RestorationStats restoration = takeover.restore(server);
		if (restoration.completed() && restoration.journalFound()) {
			MmmMod.LOGGER.warn(
					"Recovered unfinished Matter, Motion & Mechanics takeover: restored {} blocks, already restored {}, skipped {} occupied positions",
					restoration.restoredBlocks(), restoration.alreadyRestoredBlocks(), restoration.skippedBlocks());
		}
		List<String> failures = operations.recoverUnfinishedOwnership(server);
		List<String> refreshed = new ArrayList<>(operations.scanOwnershipIssues());
		for (String failure : failures) {
			refreshed.add(failure);
			MmmMod.LOGGER.error("Matter, Motion & Mechanics world ownership recovery failed: {}", failure);
		}
		ownershipIssues.accept(List.copyOf(refreshed));
		containerRecovery.recoverIndexed();
	}

	void checkpointForShutdown() {
		operations.checkpointForShutdown(region -> !containerRecovery.hasPending(region.id()));
	}

	void restoreTakeover(MinecraftServer server) {
		takeover.restore(server);
	}
}

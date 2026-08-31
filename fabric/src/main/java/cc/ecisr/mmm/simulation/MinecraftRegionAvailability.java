package cc.ecisr.mmm.simulation;

import cc.ecisr.mmm.application.ContainerRecoveryCoordinator;
import cc.ecisr.mmm.api.RegionAvailabilityReason;
import cc.ecisr.mmm.worldprofile.WorldCapability;
import net.minecraft.server.level.ServerLevel;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Restores one region's live solver and refreshes capability-dependent activity before Minecraft use. */
final class MinecraftRegionAvailability {
	private final ContainerRecoveryCoordinator containerRecovery;
	private final FluidRegionOperations operations;
	private final BooleanSupplier controllerFeatureEnabled;
	private final MinecraftRegionTick regionTick;
	private final FluidSnapshotPublisher snapshots;
	private final CommittedWorldRegistries world;

	MinecraftRegionAvailability(
			ContainerRecoveryCoordinator containerRecovery,
			FluidRegionOperations operations,
			BooleanSupplier controllerFeatureEnabled,
			MinecraftRegionTick regionTick,
			FluidSnapshotPublisher snapshots,
			CommittedWorldRegistries world
	) {
		this.containerRecovery = Objects.requireNonNull(containerRecovery, "Container recovery coordinator");
		this.operations = Objects.requireNonNull(operations, "Region operations");
		this.controllerFeatureEnabled = Objects.requireNonNull(controllerFeatureEnabled, "Controller feature gate");
		this.regionTick = Objects.requireNonNull(regionTick, "Region tick adapter");
		this.snapshots = Objects.requireNonNull(snapshots, "Snapshot publisher");
		this.world = Objects.requireNonNull(world, "Committed world registries");
	}

	boolean ensure(ServerLevel level, FluidRegion region) {
		return ensure(level, region, false);
	}

	/** Runs every ordinary availability gate while allowing only an existing wall-time pause. */
	boolean ensureForInventoryDecrease(ServerLevel level, FluidRegion region) {
		return ensure(level, region, true);
	}

	private boolean ensure(ServerLevel level, FluidRegion region, boolean toleratePerformancePause) {
		if (containerRecovery.hasPending(region.id())) {
			region.setAvailabilityFailure(RegionAvailabilityReason.PENDING_WAL,
					"存在未完成容器转移 WAL；恢复完成后才能继续");
			return false;
		}
		boolean available = operations.ensureAvailable(
				level, region, controllerFeatureEnabled.getAsBoolean(), regionTick::refreshRegionInputs,
				snapshots::sendToLevel, snapshots::sendRemoval, toleratePerformancePause);
		if (available && world.hasCapability(WorldCapability.THERMAL)
				&& region.solverLoaded() && region.domain().hasThermalState()) {
			region.enableThermalActivity();
			if (region.domain().hasPendingPhaseVolumeWork()) region.wakeForActivity();
		}
		return available;
	}

	static boolean inventoryDecreaseMayBypass(RegionAvailabilityReason reason) {
		return reason == RegionAvailabilityReason.PERFORMANCE_PAUSE;
	}
}

package mmm.simulation;

import mmm.application.ContainerRecoveryCoordinator;
import mmm.api.RegionAvailabilityReason;
import mmm.worldprofile.WorldCapability;
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
	private java.util.function.Predicate<java.util.UUID> additionalPending = id -> false;
	void additionalPending(java.util.function.Predicate<java.util.UUID> pending) { additionalPending = pending; }
	boolean hasPending(java.util.UUID id) { return containerRecovery.hasPending(id) || additionalPending.test(id); }

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

	/** Runs every durable availability gate while tolerating an existing pause only for inventory decrease. */
	boolean ensureForInventoryDecrease(ServerLevel level, FluidRegion region) {
		return ensure(level, region, true);
	}

	private boolean ensure(ServerLevel level, FluidRegion region, boolean inventoryDecrease) {
		if (hasPending(region.id())) {
			operations.cancelRestore(region);
			region.setAvailabilityFailure(RegionAvailabilityReason.PENDING_WAL,
					"存在未完成的跨域事务 WAL；恢复完成后才能继续");
			return false;
		}
        if (region.pauseForUnavailableDevice()) return false;
		boolean available;
		try {
			available = operations.ensureAvailable(
					level, region, controllerFeatureEnabled.getAsBoolean(), regionTick::refreshRegionInputs,
					snapshots::sendToLevel, snapshots::sendRemoval, inventoryDecrease);
		} catch (mmm.fluid.FluidDomainContracts.CollisionEditBlockedException blocked) {
			if (region.pauseForUnavailableDevice()) {
				snapshots.sendRemoval(level.getServer(), region);
			} else regionTick.blockCollisionEdit(level, region, blocked);
			return false;
		}
		if (!available) return false;
		try { region.ensureDeviceResident(); }
		catch (RuntimeException failure) {
			if (region.pauseForUnavailableDevice()) {
				snapshots.sendRemoval(level.getServer(), region);
				return false;
			}
			region.blockNumerical("Vulkan 区域初始化失败：" + failure.getMessage());
			return false;
		}
		if (world.hasCapability(WorldCapability.THERMAL)) activateThermalActivity(region);
		return true;
	}

	static void activateThermalActivity(FluidRegion region) {
		if (region.hasThermalState()) {
			region.enableThermalActivity();
			if (region.hasPendingPhaseMotionWork()) region.wakeForActivity();
		}
	}

	static boolean inventoryDecreaseMayBypass(RegionAvailabilityReason reason) {
		return reason == RegionAvailabilityReason.PERFORMANCE_PAUSE
				|| reason == RegionAvailabilityReason.NUMERICAL_PAUSE;
	}
}

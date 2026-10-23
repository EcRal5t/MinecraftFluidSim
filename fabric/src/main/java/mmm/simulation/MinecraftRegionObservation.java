package mmm.simulation;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Objects;

/** Narrow read/publish surface for adapters that must observe the main region channel. */
public final class MinecraftRegionObservation {
	private final FluidRegionManager regions;
	private final FluidSnapshotPublisher snapshots;
	private final ControllerComparatorSignals comparatorSignals;

	MinecraftRegionObservation(FluidRegionManager regions, FluidSnapshotPublisher snapshots, ControllerComparatorSignals comparatorSignals) {
		this.regions = Objects.requireNonNull(regions, "Region manager");
		this.snapshots = Objects.requireNonNull(snapshots, "Snapshot publisher");
		this.comparatorSignals = Objects.requireNonNull(comparatorSignals);
	}

	/** Cached committed state only: no owner discovery, chunk loading, recovery or wake-up. */
	public int controllerComparatorSignal(ServerLevel level, net.minecraft.core.BlockPos position) {
		if (!level.getServer().isSameThread()) return 0;
		return comparatorSignals.signal(level.dimension(), position);
	}

	public boolean exists() {
		return regions.selected() != null;
	}

	public void sendAllTo(ServerPlayer player) {
		for (FluidRegion region : regions.regions()) snapshots.sendTo(player, region);
	}

	public void sendSelectedTo(ServerLevel level) {
		FluidRegion region = regions.selected();
		if (region != null && level.dimension().equals(region.dimension())) snapshots.sendToLevel(level, region);
	}
}

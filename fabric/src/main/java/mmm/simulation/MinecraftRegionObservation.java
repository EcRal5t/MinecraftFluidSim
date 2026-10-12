package mmm.simulation;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Objects;

/** Narrow read/publish surface for adapters that must observe the main region channel. */
public final class MinecraftRegionObservation {
	private final FluidRegionManager regions;
	private final FluidSnapshotPublisher snapshots;

	MinecraftRegionObservation(FluidRegionManager regions, FluidSnapshotPublisher snapshots) {
		this.regions = Objects.requireNonNull(regions, "Region manager");
		this.snapshots = Objects.requireNonNull(snapshots, "Snapshot publisher");
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

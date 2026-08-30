package cc.ecisr.mmm.simulation;

import cc.ecisr.mmm.MmmMod;
import cc.ecisr.mmm.fluid.FluidDomain;
import cc.ecisr.mmm.worldprofile.WorldCapability;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.util.Objects;

/** Owns the server-thread Minecraft workflow for indicator provenance edits. */
public final class MinecraftIndicatorEditing {
	private final CommittedWorldRegistries world;
	private final FluidWorldTargetResolver targets;
	private final FluidRegionOperations operations;
	private final FluidSnapshotPublisher snapshots;
	private final MinecraftRegionAvailability availability;

	MinecraftIndicatorEditing(
			CommittedWorldRegistries world,
			FluidWorldTargetResolver targets,
			FluidRegionOperations operations,
			FluidSnapshotPublisher snapshots,
			MinecraftRegionAvailability availability
	) {
		this.world = Objects.requireNonNull(world, "Committed world registries");
		this.targets = Objects.requireNonNull(targets, "World target resolver");
		this.operations = Objects.requireNonNull(operations, "Region operations");
		this.snapshots = Objects.requireNonNull(snapshots, "Snapshot publisher");
		this.availability = Objects.requireNonNull(availability, "Region availability");
	}

	/** Applies the bounded indicator/eraser primitive to the actually aimed region. */
	public Result editAtAim(ServerPlayer player, boolean indicated) {
		if (!world.hasCapability(WorldCapability.CHEMISTRY)) return Result.rejected("化学功能门本次启动未开启");
		FluidRegion region = targets.aimedRegion(player);
		if (region == null) return Result.rejected("准星方向没有化学流体区域");
		if (!availability.ensure(player.level(), region)) {
			player.sendSystemMessage(Component.literal(
					"物质、运动与力学目标区域不可用：" + region.availabilityFailure()), true);
			return Result.rejected("目标控制器区域当前不可用：" + region.availabilityFailure());
		}
		int[] cell = targets.aimedCellNear(player, region, true);
		if (cell == null) return Result.rejected("准星方向没有化学液体");
		FluidDomain.IndicatorEditResult edit = region.domain().editIndicatorFloodFill(
				cell[0], cell[1], cell[2], indicated, Math.toIntExact(region.reservedCellCapacity()), 100.0);
		if (!edit.accepted()) return Result.rejected(edit.failure());
		String checkpointWarning = null;
		if (edit.changedMarkers() > 0) {
			region.wakeForActivity();
			if (region.controllerManaged()) {
				try {
					operations.checkpoint(region);
				} catch (IOException | RuntimeException failure) {
					checkpointWarning = "live parcel 已修改但 checkpoint 失败：" + failure.getMessage();
					region.setAvailabilityFailure(checkpointWarning);
					MmmMod.LOGGER.error("Could not checkpoint indicator edit for region {}", region.id(), failure);
				}
			}
		}
		snapshots.sendToLevel(player.level(), region);
		return new Result(true, edit.scannedCells(), edit.changedMarkers(),
				edit.affectedBlockVolume(), checkpointWarning, null);
	}

	public record Result(
			boolean accepted, int scannedCells, int changedMarkers, double affectedBlockVolume,
			String checkpointWarning, String failure
	) {
		private static Result rejected(String failure) {
			return new Result(false, 0, 0, 0.0, null, failure);
		}
	}
}

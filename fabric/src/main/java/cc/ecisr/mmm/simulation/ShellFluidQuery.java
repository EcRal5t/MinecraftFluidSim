package cc.ecisr.mmm.simulation;

import cc.ecisr.mmm.api.RegionAvailabilityReason;
import cc.ecisr.mmm.api.fabric.MmmFabricApi;
import cc.ecisr.mmm.api.query.FluidQueryRequest;
import cc.ecisr.mmm.api.query.FluidQueryResult;
import cc.ecisr.mmm.api.query.FluidQueryStatus;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/** Resolves the unique controller interior touching either side of one shell face. */
public final class ShellFluidQuery {
	private static final double NEAR_FACE_OFFSET_METRES = 0.05;
	private static final double THROUGH_SHELL_OFFSET_METRES = 1.05;
	private static final double FACE_EDGE_EPSILON_METRES = 1.0E-4;

	private ShellFluidQuery() { }

	public static FluidQueryResult query(ServerLevel level, BlockPos block, Direction face, Vec3 rawHit) {
		return resolve(level, block, face, rawHit).result();
	}

	public static Resolution resolve(ServerLevel level, BlockPos block, Direction face, Vec3 rawHit) {
		Vec3 hit = clampToFace(rawHit, block, face.getAxis());
		Vec3 nearPoint = offset(hit, face, NEAR_FACE_OFFSET_METRES);
		Vec3 throughPoint = offset(hit, face.getOpposite(), THROUGH_SHELL_OFFSET_METRES);
		FluidQueryResult near = query(level, nearPoint), through = query(level, throughPoint);
		FluidQueryResult selected = select(near, through);
		boolean observable = selected.status() == FluidQueryStatus.OK || selected.status() == FluidQueryStatus.EMPTY;
		Vec3 point = !observable ? null : selected == near ? nearPoint : selected == through ? throughPoint : null;
		return new Resolution(selected, point);
	}

	static FluidQueryResult select(FluidQueryResult near, FluidQueryResult through) {
		if (globalFailure(near)) return near;
		if (globalFailure(through)) return through;
		boolean nearClaimsInterior = claimsInterior(near);
		boolean throughClaimsInterior = claimsInterior(through);
		if (nearClaimsInterior && throughClaimsInterior) {
			return FluidQueryResult.unavailable(RegionAvailabilityReason.OVERLAP,
					"both sides of the shell face resolve to controller interiors");
		}
		if (nearClaimsInterior) return near;
		if (throughClaimsInterior) return through;
		if (near.status() == FluidQueryStatus.WRONG_THREAD || near.status() == FluidQueryStatus.INVALID_REQUEST) {
			return near;
		}
		if (through.status() == FluidQueryStatus.WRONG_THREAD
				|| through.status() == FluidQueryStatus.INVALID_REQUEST) return through;
		return FluidQueryResult.absent(FluidQueryStatus.NO_REGION,
				"neither side of the shell face resolves to a loaded controller interior");
	}

	private static FluidQueryResult query(ServerLevel level, Vec3 point) {
		return MmmFabricApi.query(level.getServer()).query(new FluidQueryRequest(
				level.dimension().identifier().toString(), null, point.x, point.y, point.z));
	}

	public static FluidQueryResult queryPoint(ServerLevel level, Vec3 interiorPoint) {
		return query(level, interiorPoint);
	}

	private static Vec3 offset(Vec3 hit, Direction direction, double distance) {
		return new Vec3(hit.x + direction.getStepX() * distance,
				hit.y + direction.getStepY() * distance,
				hit.z + direction.getStepZ() * distance);
	}

	private static boolean claimsInterior(FluidQueryResult result) {
		return switch (result.status()) {
			case OK, EMPTY, REGION_NOT_LOADED, STATE_NOT_READY -> true;
			case REGION_UNAVAILABLE -> result.availabilityReason() != RegionAvailabilityReason.PROFILE_LOCKED;
			case INVALID_REQUEST, WRONG_THREAD, NO_REGION, OUTSIDE_INTERIOR -> false;
		};
	}

	private static boolean globalFailure(FluidQueryResult result) {
		return result.status() == FluidQueryStatus.REGION_UNAVAILABLE
				&& result.availabilityReason() == RegionAvailabilityReason.PROFILE_LOCKED;
	}

	private static Vec3 clampToFace(Vec3 hit, BlockPos block, Direction.Axis normalAxis) {
		double x = normalAxis == Direction.Axis.X ? hit.x : clamp(hit.x, block.getX());
		double y = normalAxis == Direction.Axis.Y ? hit.y : clamp(hit.y, block.getY());
		double z = normalAxis == Direction.Axis.Z ? hit.z : clamp(hit.z, block.getZ());
		return new Vec3(x, y, z);
	}

	private static double clamp(double coordinate, int blockMinimum) {
		return Math.max(blockMinimum + FACE_EDGE_EPSILON_METRES,
				Math.min(blockMinimum + 1.0 - FACE_EDGE_EPSILON_METRES, coordinate));
	}

	public record Resolution(FluidQueryResult result, Vec3 interiorPoint) {
		public Resolution {
			boolean resolvesInterior = result != null
					&& (result.status() == FluidQueryStatus.OK || result.status() == FluidQueryStatus.EMPTY);
			if (result == null || resolvesInterior != (interiorPoint != null)) {
				throw new IllegalArgumentException("Shell query resolution is inconsistent");
			}
		}
	}
}

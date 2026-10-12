package mmm.compat.jade;

import mmm.MmmMod;
import mmm.api.fabric.MmmFabricApi;
import mmm.api.query.FluidCellObservation;
import mmm.api.query.FluidQueryRequest;
import mmm.api.query.FluidQueryResult;
import mmm.block.MmmBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IServerDataProvider;

/** Samples only the two cells touching the observed shell face through the public query contract. */
final class MmmJadeServerDataProvider implements IServerDataProvider<BlockAccessor> {
	static final MmmJadeServerDataProvider INSTANCE = new MmmJadeServerDataProvider();
	static final Identifier UID = MmmMod.id("fluid_inventory");
	private static final double NEAR_FACE_OFFSET_METRES = 0.05;
	private static final double THROUGH_SHELL_OFFSET_METRES = 1.05;
	private static final double FACE_EDGE_EPSILON_METRES = 1.0E-4;

	private MmmJadeServerDataProvider() { }

	@Override
	public void appendServerData(CompoundTag data, BlockAccessor accessor) {
		FluidCellObservation observation = observe(accessor);
		if (observation != null) data.put(UID.toString(), JadeFluidSummary.from(observation).encode());
	}

	@Override
	public boolean shouldRequestData(BlockAccessor accessor) {
		return MmmBlocks.isBoundary(accessor.getBlockState());
	}

	@Override
	public Identifier getUid() {
		return UID;
	}

	private static FluidCellObservation observe(BlockAccessor accessor) {
		if (!(accessor.getLevel() instanceof ServerLevel level)
				|| !MmmBlocks.isBoundary(accessor.getBlockState())) return null;
		Direction face = accessor.getSide();
		Vec3 hit = clampToFace(accessor.getHitResult().getLocation(), accessor.getPosition(), face.getAxis());
		FluidCellObservation nearPlayer = query(level, hit, face, NEAR_FACE_OFFSET_METRES);
		return nearPlayer != null ? nearPlayer
				: query(level, hit, face.getOpposite(), THROUGH_SHELL_OFFSET_METRES);
	}

	private static FluidCellObservation query(ServerLevel level, Vec3 hit, Direction direction, double offset) {
		FluidQueryResult result = MmmFabricApi.query(level.getServer()).query(new FluidQueryRequest(
				level.dimension().identifier().toString(), null,
				hit.x + direction.getStepX() * offset,
				hit.y + direction.getStepY() * offset,
				hit.z + direction.getStepZ() * offset));
		return result.observation();
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
}

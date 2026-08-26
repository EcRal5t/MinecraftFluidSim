package cc.ecisr.mmm.compat.jade;

import cc.ecisr.mmm.MmmMod;
import cc.ecisr.mmm.api.query.FluidCellObservation;
import cc.ecisr.mmm.api.query.FluidQueryResult;
import cc.ecisr.mmm.block.MmmBlocks;
import cc.ecisr.mmm.simulation.ShellFluidQuery;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IServerDataProvider;

/** Samples only the two cells touching the observed shell face through the public query contract. */
final class MmmJadeServerDataProvider implements IServerDataProvider<BlockAccessor> {
	static final MmmJadeServerDataProvider INSTANCE = new MmmJadeServerDataProvider();
	static final Identifier UID = MmmMod.id("fluid_inventory");

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
		FluidQueryResult result = ShellFluidQuery.query(level, accessor.getPosition(), accessor.getSide(),
				accessor.getHitResult().getLocation());
		return result.observation();
	}
}

package cc.ecisr.mmm.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/** A controller-shell block whose persisted setpoint is an external infinite thermal reservoir. */
final class ThermalReservoirBlock extends Block implements EntityBlock {
	ThermalReservoirBlock(BlockBehaviour.Properties properties) {
		super(properties);
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos position, BlockState state) {
		return new ThermalReservoirBlockEntity(position, state);
	}
}

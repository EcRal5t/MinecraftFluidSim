package mmm.block;

import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;

/** Full-collision controller shell slot; FACING points outward toward its container connection. */
public final class FluidPortBlock extends Block {
	public static final EnumProperty<Direction> FACING=BlockStateProperties.FACING;
	FluidPortBlock(BlockBehaviour.Properties properties) {
		super(properties);
		registerDefaultState(stateDefinition.any().setValue(FACING,Direction.NORTH));
	}
	@Override public BlockState getStateForPlacement(BlockPlaceContext context) {
		return defaultBlockState().setValue(FACING,context.getNearestLookingDirection().getOpposite());
	}
	@Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> builder) { builder.add(FACING); }
	@Override protected BlockState rotate(BlockState state,Rotation rotation) { return state.setValue(FACING,rotation.rotate(state.getValue(FACING))); }
	@Override protected BlockState mirror(BlockState state,Mirror mirror) { return rotate(state,mirror.getRotation(state.getValue(FACING))); }
}

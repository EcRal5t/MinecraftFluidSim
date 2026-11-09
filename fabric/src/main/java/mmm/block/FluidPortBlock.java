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
public final class FluidPortBlock extends Block implements net.minecraft.world.level.block.EntityBlock {
	public static final EnumProperty<Direction> FACING=BlockStateProperties.FACING;
	FluidPortBlock(BlockBehaviour.Properties properties) {
		super(properties);
		registerDefaultState(stateDefinition.any().setValue(FACING,Direction.NORTH));
	}
	@Override public BlockState getStateForPlacement(BlockPlaceContext context) {
		return defaultBlockState().setValue(FACING,context.getNearestLookingDirection().getOpposite());
	}
	@Override public net.minecraft.world.level.block.entity.BlockEntity newBlockEntity(net.minecraft.core.BlockPos position,BlockState state) { return new FluidPortBlockEntity(position,state); }
	@Override public <T extends net.minecraft.world.level.block.entity.BlockEntity> net.minecraft.world.level.block.entity.BlockEntityTicker<T> getTicker(net.minecraft.world.level.Level level,BlockState state,net.minecraft.world.level.block.entity.BlockEntityType<T> type) {
		return level.isClientSide()?null:(world,pos,current,entity)->mmm.simulation.MmmRuntimeRegistry.require(((net.minecraft.server.level.ServerLevel)world).getServer()).industrial().tickPort((net.minecraft.server.level.ServerLevel)world,(FluidPortBlockEntity)entity);
	}
	@Override protected net.minecraft.world.InteractionResult useWithoutItem(BlockState state,net.minecraft.world.level.Level level,net.minecraft.core.BlockPos position,net.minecraft.world.entity.player.Player player,net.minecraft.world.phys.BlockHitResult hit) {
		if(!level.isClientSide()&&player instanceof net.minecraft.server.level.ServerPlayer p) mmm.simulation.MmmRuntimeRegistry.require(p.level().getServer()).industrial().usePort(p,position);
		return net.minecraft.world.InteractionResult.SUCCESS;
	}
	@Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> builder) { builder.add(FACING); }
	@Override protected BlockState rotate(BlockState state,Rotation rotation) { return state.setValue(FACING,rotation.rotate(state.getValue(FACING))); }
	@Override protected BlockState mirror(BlockState state,Mirror mirror) { return rotate(state,mirror.getRotation(state.getValue(FACING))); }
}

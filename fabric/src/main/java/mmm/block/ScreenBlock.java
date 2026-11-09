package mmm.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.*;

public final class ScreenBlock extends Block implements EntityBlock {
    public static final net.minecraft.world.level.block.state.properties.EnumProperty<Direction> FACING=net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING;
    ScreenBlock(Properties properties){super(properties);registerDefaultState(stateDefinition.any().setValue(FACING,Direction.NORTH));}
    @Override protected void createBlockStateDefinition(net.minecraft.world.level.block.state.StateDefinition.Builder<Block,BlockState> builder){builder.add(FACING);}
    @Override public BlockState getStateForPlacement(net.minecraft.world.item.context.BlockPlaceContext context){return defaultBlockState().setValue(FACING,context.getNearestLookingDirection().getOpposite());}
    @Override public BlockEntity newBlockEntity(BlockPos pos,BlockState state){return new ScreenBlockEntity(pos,state);}
    @Override protected VoxelShape getCollisionShape(BlockState state,BlockGetter world,BlockPos pos,CollisionContext context){return Shapes.empty();}
    @Override protected VoxelShape getShape(BlockState state,BlockGetter world,BlockPos pos,CollisionContext context){return switch(state.getValue(FACING).getAxis()){
        case X->Block.box(7,0,0,9,16,16);case Y->Block.box(0,7,0,16,9,16);case Z->Block.box(0,0,7,16,16,9);
    };}
    @Override protected net.minecraft.world.InteractionResult useWithoutItem(BlockState state,Level level,BlockPos pos,net.minecraft.world.entity.player.Player player,net.minecraft.world.phys.BlockHitResult hit){
        if(player instanceof net.minecraft.server.level.ServerPlayer p)mmm.simulation.MmmRuntimeRegistry.require(p.level().getServer()).industrial().probe(p,pos);
        return net.minecraft.world.InteractionResult.SUCCESS;
    }
}

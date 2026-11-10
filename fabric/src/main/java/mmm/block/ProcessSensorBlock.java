package mmm.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

public final class ProcessSensorBlock extends Block implements EntityBlock {
    public static final net.minecraft.world.level.block.state.properties.EnumProperty<Direction> FACING=net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING;
    ProcessSensorBlock(Properties properties){super(properties);registerDefaultState(stateDefinition.any().setValue(FACING,Direction.NORTH));}
    @Override protected void createBlockStateDefinition(net.minecraft.world.level.block.state.StateDefinition.Builder<Block,BlockState> builder){builder.add(FACING);}
    @Override public BlockState getStateForPlacement(net.minecraft.world.item.context.BlockPlaceContext context){return defaultBlockState().setValue(FACING,context.getNearestLookingDirection().getOpposite());}
    @Override public BlockEntity newBlockEntity(BlockPos pos,BlockState state){return new ProcessSensorBlockEntity(pos,state);}
    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level,BlockState state,BlockEntityType<T> type){
        return level.isClientSide()?null:(world,pos,current,entity)->mmm.simulation.MmmRuntimeRegistry.require(((net.minecraft.server.level.ServerLevel)world).getServer()).industrial().tickSensor((net.minecraft.server.level.ServerLevel)world,(ProcessSensorBlockEntity)entity);
    }
    @Override protected boolean isSignalSource(BlockState state){return true;}
    @Override protected int getSignal(BlockState state,BlockGetter level,BlockPos pos,Direction side){return level.getBlockEntity(pos) instanceof ProcessSensorBlockEntity sensor?sensor.output():0;}
    @Override protected net.minecraft.world.InteractionResult useWithoutItem(BlockState state,Level level,BlockPos pos,net.minecraft.world.entity.player.Player player,net.minecraft.world.phys.BlockHitResult hit){
        if(!level.isClientSide()&&level.getBlockEntity(pos) instanceof ProcessSensorBlockEntity sensor)player.sendSystemMessage(net.minecraft.network.chat.Component.literal(sensor.measure().label()+" · "+sensor.reading()+" · "+sensor.output()));
        return net.minecraft.world.InteractionResult.SUCCESS;
    }
}

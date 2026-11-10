package mmm.block;

import mmm.simulation.MmmRuntimeRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** A sealed pipe shell with three openings resolved on the shared microgrid. */
public final class PipeValveBlock extends Block {
    @Override protected void onExplosionHit(BlockState state,ServerLevel level,BlockPos pos,
            net.minecraft.world.level.Explosion explosion,java.util.function.BiConsumer<net.minecraft.world.item.ItemStack,BlockPos> drops) {
        if(mmm.simulation.PipeRuntime.allowsRemoval(level,pos))super.onExplosionHit(state,level,pos,explosion,drops);
    }
    public static final EnumProperty<Direction> FACING=BlockStateProperties.FACING;
    public static final IntegerProperty OPENING=IntegerProperty.create("opening",0,2);
    public static final IntegerProperty SIGNAL=BlockStateProperties.POWER;
    PipeValveBlock(Properties properties) {
        super(properties);registerDefaultState(stateDefinition.any().setValue(FACING,Direction.NORTH).setValue(OPENING,2).setValue(SIGNAL,0));
    }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> builder) { builder.add(FACING,OPENING,SIGNAL); }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) { return defaultBlockState().setValue(FACING,context.getNearestLookingDirection().getOpposite()); }
    @Override protected BlockState rotate(BlockState state,net.minecraft.world.level.block.Rotation rotation) { return state.setValue(FACING,rotation.rotate(state.getValue(FACING))); }
    @Override protected BlockState mirror(BlockState state,net.minecraft.world.level.block.Mirror mirror) { return state.setValue(FACING,mirror.mirror(state.getValue(FACING))); }
    public static VoxelShape collision(BlockState state) { return PipeGeometry.valve(state.getValue(FACING),state.getValue(OPENING)); }
    @Override protected VoxelShape getCollisionShape(BlockState state,BlockGetter world,BlockPos pos,CollisionContext context) { return collision(state); }
    @Override protected VoxelShape getShape(BlockState state,BlockGetter world,BlockPos pos,CollisionContext context) { return collision(state); }
    @Override protected InteractionResult useWithoutItem(BlockState state,Level level,BlockPos pos,Player player,BlockHitResult hit) {
        if(player instanceof ServerPlayer p) {
            String result=MmmRuntimeRegistry.require(p.level().getServer()).industrial().setValve(p.level(),pos,(state.getValue(OPENING)+1)%3);
            p.sendSystemMessage(net.minecraft.network.chat.Component.literal(result));
        }
        return InteractionResult.SUCCESS;
    }
    @Override protected void neighborChanged(BlockState state,Level level,BlockPos pos,Block source,net.minecraft.world.level.redstone.Orientation orientation,boolean moved) {
        if(level instanceof ServerLevel server)applySignal(server,pos);
    }
    @Override protected void tick(BlockState state,ServerLevel level,BlockPos pos,net.minecraft.util.RandomSource random) { applySignal(level,pos); }
    private void applySignal(ServerLevel level,BlockPos pos) {
        var state=level.getBlockState(pos);if(!state.is(this))return;
        int signal=level.getBestNeighborSignal(pos);if(signal==state.getValue(SIGNAL))return;
        int opening=Math.round(signal*2f/15);
        if(state.getValue(OPENING)!=opening) {
            MmmRuntimeRegistry.require(level.getServer()).industrial().setValve(level,pos,opening);
            state=level.getBlockState(pos);if(!state.is(this))return;
            if(state.getValue(OPENING)!=opening){level.scheduleTick(pos,this,1);return;}
        }
        level.setBlock(pos,state.setValue(SIGNAL,signal),Block.UPDATE_CLIENTS);
    }
}

package mmm.block;

import java.util.EnumMap;
import java.util.Map;
import mmm.simulation.MmmRuntimeRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Axis-aligned pipe with automatic compatible ports and independently controlled ends. */
public final class PipeBlock extends Block {
    public enum Port implements StringRepresentable {
        NONE, CONNECTED, OPEN, SEALED;
        @Override public String getSerializedName() { return name().toLowerCase(java.util.Locale.ROOT); }
        public boolean open() { return this==CONNECTED || this==OPEN; }
        boolean automatic() { return this==NONE || this==CONNECTED; }
    }
    private static final Map<Direction,EnumProperty<Port>> PORTS=new EnumMap<>(Direction.class);
    static { for(var face:Direction.values())PORTS.put(face,EnumProperty.create(face.getName(),Port.class)); }
    PipeBlock(Properties properties) {
        super(properties);
        var state=stateDefinition.any(); for(var property:PORTS.values())state=state.setValue(property,Port.NONE);
        registerDefaultState(state);
    }
    public static EnumProperty<Port> port(Direction face) { return PORTS.get(face); }
    public static int openFaces(BlockState state) {
        int mask=0; for(var face:Direction.values())if(state.getValue(port(face)).open())mask|=PipeGeometry.bit(face); return mask;
    }
    public static VoxelShape collision(BlockState state) { return PipeGeometry.shell(openFaces(state)); }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> builder) {
        for(var face:Direction.values())builder.add(port(face));
    }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        var state=defaultBlockState();
        for(var face:Direction.values())state=state.setValue(port(face),automatic(context.getLevel().getBlockState(context.getClickedPos().relative(face)),face));
        return state;
    }
    static Port automatic(BlockState neighbor,Direction face) {
        if(neighbor.getBlock() instanceof PipeBlock)
            return neighbor.getValue(port(face.getOpposite()))==Port.SEALED?Port.NONE:Port.CONNECTED;
        if(neighbor.getBlock() instanceof PipeValveBlock || neighbor.getBlock() instanceof PipeScreenBlock)
            return neighbor.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING).getAxis()==face.getAxis()?Port.CONNECTED:Port.NONE;
        if(neighbor.is(MmmBlocks.FINITE_HEAT_EXCHANGER))return face.getAxis()==Direction.Axis.Z?Port.CONNECTED:Port.NONE;
        if(neighbor.is(MmmBlocks.THERMAL_RESERVOIR))return Port.CONNECTED;
        if(neighbor.is(MmmBlocks.FLUID_PORT))return neighbor.getValue(FluidPortBlock.FACING)==face?Port.CONNECTED:Port.NONE;
        return Port.NONE;
    }
    static Port nextManual(Port current,BlockState neighbor,Direction face) {
        return current.automatic()?Port.OPEN:current==Port.OPEN?Port.SEALED:automatic(neighbor,face);
    }
    @Override protected BlockState updateShape(BlockState state,LevelReader level,ScheduledTickAccess ticks,BlockPos pos,
            Direction direction,BlockPos neighborPos,BlockState neighbor,net.minecraft.util.RandomSource random) {
        var property=port(direction);
        if(!level.isClientSide() && state.getValue(property).automatic()
                && state.getValue(property)!=automatic(neighbor,direction))ticks.scheduleTick(pos,this,1);
        return state;
    }
    @Override protected void tick(BlockState state,ServerLevel level,BlockPos pos,net.minecraft.util.RandomSource random) {
        var next=state;
        for(var face:Direction.values()) {
            var property=port(face);
            if(state.getValue(property).automatic())next=next.setValue(property,automatic(level.getBlockState(pos.relative(face)),face));
        }
        if(next==state)return;
        MmmRuntimeRegistry.require(level.getServer()).industrial().setPipePort(level,pos,next);
        if(level.getBlockState(pos)==state)level.scheduleTick(pos,this,1);
    }
    @Override protected VoxelShape getCollisionShape(BlockState state,BlockGetter world,BlockPos pos,CollisionContext context) { return collision(state); }
    @Override protected VoxelShape getShape(BlockState state,BlockGetter world,BlockPos pos,CollisionContext context) { return collision(state); }
    @Override protected BlockState rotate(BlockState state,Rotation rotation) {
        var result=state;
        for(var face:Direction.values())result=result.setValue(port(rotation.rotate(face)),state.getValue(port(face)));
        return result;
    }
    @Override protected BlockState mirror(BlockState state,Mirror mirror) {
        var result=state;
        for(var face:Direction.values())result=result.setValue(port(mirror.mirror(face)),state.getValue(port(face)));
        return result;
    }
    @Override protected InteractionResult useWithoutItem(BlockState state,Level level,BlockPos pos,Player player,BlockHitResult hit) {
        if(!player.isShiftKeyDown())return InteractionResult.PASS;
        if(player instanceof ServerPlayer serverPlayer) {
            var face=hit.getDirection(); var property=port(face); var mode=state.getValue(property);
            Port next=nextManual(mode,level.getBlockState(pos.relative(face)),face);
            String message=MmmRuntimeRegistry.require(((ServerLevel)level).getServer()).industrial().setPipePort((ServerLevel)level,pos,state.setValue(property,next));
            serverPlayer.sendSystemMessage(net.minecraft.network.chat.Component.literal(message));
        }
        return InteractionResult.SUCCESS;
    }
}

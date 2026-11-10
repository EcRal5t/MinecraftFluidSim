package mmm.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** An axial pipe housing around the existing configurable screen. */
public final class PipeScreenBlock extends ScreenBlock {
    @Override protected void onExplosionHit(BlockState state,net.minecraft.server.level.ServerLevel level,BlockPos pos,
            net.minecraft.world.level.Explosion explosion,java.util.function.BiConsumer<net.minecraft.world.item.ItemStack,BlockPos> drops) {
        if(mmm.simulation.PipeRuntime.allowsRemoval(level,pos))super.onExplosionHit(state,level,pos,explosion,drops);
    }
    PipeScreenBlock(Properties properties) { super(properties); }
    public static VoxelShape collision(BlockState state) { return PipeGeometry.axial(state.getValue(FACING)); }
    @Override protected VoxelShape getCollisionShape(BlockState state,BlockGetter world,BlockPos pos,CollisionContext context) { return collision(state); }
    @Override protected VoxelShape getShape(BlockState state,BlockGetter world,BlockPos pos,CollisionContext context) { return collision(state); }
}

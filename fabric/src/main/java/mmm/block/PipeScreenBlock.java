package mmm.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** An axial pipe housing around the existing configurable screen. */
public final class PipeScreenBlock extends ScreenBlock {
    PipeScreenBlock(Properties properties) { super(properties); }
    public static VoxelShape collision(BlockState state) { return PipeGeometry.axial(state.getValue(FACING)); }
    @Override protected VoxelShape getCollisionShape(BlockState state,BlockGetter world,BlockPos pos,CollisionContext context) { return collision(state); }
    @Override protected VoxelShape getShape(BlockState state,BlockGetter world,BlockPos pos,CollisionContext context) { return collision(state); }
}

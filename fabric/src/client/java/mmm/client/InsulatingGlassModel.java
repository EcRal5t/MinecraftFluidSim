package mmm.client;

import java.util.function.Predicate;
import mmm.block.MmmBlocks;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.model.loading.v1.wrapper.WrapperBlockStateModel;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadAtlas;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** Five compact tiles supply all connected edge and corner combinations. */
final class InsulatingGlassModel extends WrapperBlockStateModel {
    private static final Identifier[] TILES = new Identifier[5];
    static { for(int i=0;i<TILES.length;i++)TILES[i]=Identifier.parse("mmm:block/insulating_glass_ctm_"+i); }
    private InsulatingGlassModel(BlockStateModel wrapped) { super(wrapped); }

    static void register() {
        ModelLoadingPlugin.register(context -> context.modifyBlockModelAfterBake().register((model,ctx) ->
                ctx.state().is(MmmBlocks.INSULATING_GLASS) ? new InsulatingGlassModel(model) : model));
    }

    @Override public Object createGeometryKey(BlockAndTintGetter level,BlockPos pos,BlockState state,RandomSource random) {
        return null;
    }

    @Override public void emitQuads(QuadEmitter emitter,BlockAndTintGetter level,BlockPos pos,BlockState state,
                                    RandomSource random,Predicate<Direction> cullTest) {
        TextureAtlas atlas=Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS);
        for(var face:Direction.values()) {
            if(cullTest.test(face))continue;
            Direction up=up(face),left=left(face);
            for(int y=0;y<2;y++)for(int x=0;x<2;x++) {
                Direction horizontal=x==0?left:left.getOpposite(), vertical=y==0?up:up.getOpposite();
                var sprite=atlas.getSprite(TILES[tileAt(level,pos,face,horizontal,vertical,MmmBlocks.INSULATING_GLASS)]);
                float u0=x*.5f,u1=u0+.5f,v0=y*.5f,v1=v0+.5f;
                emitter.square(face,u0,1-v1,u1,1-v0,0).color(-1,-1,-1,-1)
                        .uv(0,sprite.getU(u0),sprite.getV(v0)).uv(1,sprite.getU(u0),sprite.getV(v1))
                        .uv(2,sprite.getU(u1),sprite.getV(v1)).uv(3,sprite.getU(u1),sprite.getV(v0))
                        .atlas(QuadAtlas.BLOCK).chunkLayer(ChunkSectionLayer.TRANSLUCENT).emit();
            }
        }
    }
    static int tileAt(BlockAndTintGetter level,BlockPos pos,Direction face,Direction horizontal,Direction vertical,
                      Block glass) {
        boolean h=connected(level,pos,face,glass,horizontal),v=connected(level,pos,face,glass,vertical);
        return tile(h,v,h&&v&&connected(level,pos,face,glass,horizontal,vertical));
    }
    private static boolean connected(BlockAndTintGetter level,BlockPos pos,Direction face,
                                     Block glass,Direction... offsets) {
        BlockPos adjacent=pos;
        for(var direction:offsets)adjacent=adjacent.relative(direction);
        return level.getBlockState(adjacent).is(glass) && !level.getBlockState(adjacent.relative(face)).is(glass);
    }
    static int tile(boolean horizontal,boolean vertical,boolean diagonal) {
        if(horizontal && vertical)return diagonal?1:4;
        if(horizontal)return 3;
        if(vertical)return 2;
        return 0;
    }
    static Direction up(Direction face) {
        return face==Direction.UP?Direction.NORTH:face==Direction.DOWN?Direction.SOUTH:Direction.UP;
    }
    static Direction left(Direction face) {
        Direction up=up(face);
        return face.getAxisDirection()==Direction.AxisDirection.NEGATIVE
                ?up.getClockWise(face.getAxis()):up.getCounterClockWise(face.getAxis());
    }
}

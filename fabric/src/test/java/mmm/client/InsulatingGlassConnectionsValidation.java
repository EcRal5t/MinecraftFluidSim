package mmm.client;

import net.minecraft.core.Direction;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import mmm.simulation.InsulatingGlassTestFixture;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Each quadrant preserves its outside edges and fills only a connected diagonal. */
final class InsulatingGlassConnectionsValidation {
    static void run() {
        check(InsulatingGlassModel.tile(false,false,false)==0,"isolated corner");
        check(InsulatingGlassModel.tile(false,true,false)==2,"vertical connection");
        check(InsulatingGlassModel.tile(true,false,false)==3,"horizontal connection");
        check(InsulatingGlassModel.tile(true,true,false)==4,"missing diagonal");
        check(InsulatingGlassModel.tile(true,true,true)==1,"filled corner");
        Direction[][] faces={
                {Direction.DOWN,Direction.SOUTH,Direction.WEST},
                {Direction.UP,Direction.NORTH,Direction.WEST},
                {Direction.NORTH,Direction.UP,Direction.EAST},
                {Direction.SOUTH,Direction.UP,Direction.WEST},
                {Direction.WEST,Direction.UP,Direction.NORTH},
                {Direction.EAST,Direction.UP,Direction.SOUTH}
        };
        for(var face:faces) {
            check(InsulatingGlassModel.up(face[0])==face[1],"texture up for "+face[0]);
            check(InsulatingGlassModel.left(face[0])==face[2],"texture left for "+face[0]);
            for(int y=0;y<2;y++)for(int x=0;x<2;x++)checkQuadrant(face[0],x,y);
        }
        worldNeighbors();
    }
    private static void worldNeighbors() {
        var glass=InsulatingGlassTestFixture.glass();
        glass.defaultBlockState().initCache();
        var positions=new java.util.HashMap<BlockPos,BlockState>();
        var world=(BlockAndTintGetter)java.lang.reflect.Proxy.newProxyInstance(
                InsulatingGlassConnectionsValidation.class.getClassLoader(),new Class<?>[]{BlockAndTintGetter.class},
                (proxy,method,args)->{
                    if(method.getName().equals("getBlockState"))return positions.getOrDefault(args[0],Blocks.AIR.defaultBlockState());
                    throw new UnsupportedOperationException(method.getName());
                });
        var pos=BlockPos.ZERO; var face=Direction.NORTH;
        var left=InsulatingGlassModel.left(face); var up=InsulatingGlassModel.up(face);
        positions.put(pos.relative(left),glass.defaultBlockState());
        positions.put(pos.relative(up),glass.defaultBlockState());
        positions.put(pos.relative(left).relative(up),glass.defaultBlockState());
        check(InsulatingGlassModel.tileAt(world,pos,face,left,up,glass)==1,"2x2 window has a connected corner");
        positions.remove(pos.relative(left).relative(up));
        check(InsulatingGlassModel.tileAt(world,pos,face,left,up,glass)==4,"L shaped window has an inside corner");
        positions.put(pos.relative(left).relative(face),glass.defaultBlockState());
        check(InsulatingGlassModel.tileAt(world,pos,face,left,up,glass)==2,"front glass step exposes a side edge");
        positions.remove(pos.relative(left).relative(face));
        positions.put(pos.relative(left),Blocks.TINTED_GLASS.defaultBlockState());
        check(InsulatingGlassModel.tileAt(world,pos,face,left,up,glass)==2,"different glass keeps a side edge");
        var state=glass.defaultBlockState(); var other=Blocks.TINTED_GLASS.defaultBlockState();
        check(state.skipRendering(state,face),"same glass hides shared faces");
        check(!state.skipRendering(other,face),"different glass retains shared faces");
        check(!state.getCollisionShape(EmptyBlockGetter.INSTANCE,pos).isEmpty(),"glass remains solid");
        check(state.propagatesSkylightDown(),"glass passes skylight");
        check(state.getVisualShape(EmptyBlockGetter.INSTANCE,pos,net.minecraft.world.phys.shapes.CollisionContext.empty()).isEmpty(),"glass leaves vision unobstructed");
    }
    private static void checkQuadrant(Direction face,int x,int y) {
        float[][] points=new float[4][3];
        var emitter=(QuadEmitter)java.lang.reflect.Proxy.newProxyInstance(QuadEmitter.class.getClassLoader(),
                new Class<?>[]{QuadEmitter.class},(proxy,method,args)->{
                    if(method.isDefault())return java.lang.reflect.InvocationHandler.invokeDefault(proxy,method,args);
                    if(method.getName().equals("pos") && args.length==4) {
                        int vertex=(Integer)args[0]; for(int axis=0;axis<3;axis++)points[vertex][axis]=(Float)args[axis+1];
                    }
                    return proxy;
                });
        float u=x*.5f,v=y*.5f;
        emitter.square(face,u,1-v-.5f,u+.5f,1-v,0);
        var right=InsulatingGlassModel.left(face).getOpposite(); var down=InsulatingGlassModel.up(face).getOpposite();
        check(delta(points[0],points[3],right),"quadrant U direction for "+face);
        check(delta(points[0],points[1],down),"quadrant V direction for "+face);
    }
    private static boolean delta(float[] from,float[] to,Direction direction) {
        return to[0]-from[0]==direction.getStepX()*.5f && to[1]-from[1]==direction.getStepY()*.5f
                && to[2]-from[2]==direction.getStepZ()*.5f;
    }
    private static void check(boolean value,String message) { if(!value)throw new AssertionError(message); }
}

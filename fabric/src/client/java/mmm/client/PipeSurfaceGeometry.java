package mmm.client;

import java.util.List;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import com.mojang.blaze3d.vertex.VertexConsumer;
import mmm.network.PipeSnapshotPayload;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Horizontal inventory layers clipped to the accepted pipe bore. */
final class PipeSurfaceGeometry {
    private PipeSurfaceGeometry() { }
    static void build(Vec3 camera,VertexConsumer vertices,PipeSnapshotPayload.Segment segment,boolean textured,
                      Function<String,TextureAtlasSprite> sprites,ToIntFunction<BlockPos> lights) {
        boolean[] bore=new boolean[4096];
        for(var box:segment.bore())for(int z=box.z0();z<box.z1();z++)for(int y=box.y0();y<box.y1();y++)for(int x=box.x0();x<box.x1();x++)bore[index(x,y,z)]=true;
        var pos=BlockPos.of(segment.position());var anchor=FluidRenderCoordinates.at(camera).anchor();
        double start=0;
        for(var layer:segment.layers()) {
            double end=start+layer.volumeM3();float bottom=height(bore,start),top=height(bore,end);
            if(top>bottom)emitLayer(vertices,bore,pos,anchor,bottom,top,layer,textured,sprites,lights);
            start=end;
        }
    }
    static float height(boolean[] bore,double volume) {
        double remaining=Math.max(0,volume);
        for(int y=0;y<16;y++) {
            int open=0;for(int z=0;z<16;z++)for(int x=0;x<16;x++)if(bore[index(x,y,z)])open++;
            if(open==0)continue;
            double slice=open/4096.0;
            if(remaining<=slice)return (float)(y/16.0+remaining*256/open);
            remaining-=slice;
        }
        return 1;
    }
    private static void emitLayer(VertexConsumer out,boolean[] bore,BlockPos pos,BlockPos anchor,float bottom,float top,
            PipeSnapshotPayload.Layer layer,boolean textured,Function<String,TextureAtlasSprite> sprites,ToIntFunction<BlockPos> lights) {
        var sprite=textured?sprites.apply(layer.material()):null;int color=layer.argb();int light=lights.applyAsInt(pos);
        FluidRenderBackend.beginMaterial(out,layer.material(),pos.getX()-anchor.getX(),pos.getY()-anchor.getY(),pos.getZ()-anchor.getZ());
        for(int z=0;z<16;z++)for(int y=0;y<16;y++)for(int x=0;x<16;x++)if(bore[index(x,y,z)]) {
            float x0=x/16F,x1=(x+1)/16F,z0=z/16F,z1=(z+1)/16F;
            float y0=Math.max(bottom,y/16F),y1=Math.min(top,(y+1)/16F);if(y1<=y0)continue;
            if(!open(bore,x-1,y,z))face(out,pos,anchor,0,x0,y0,y1,z0,z1,false,color,sprite,light);
            if(!open(bore,x+1,y,z))face(out,pos,anchor,0,x1,y0,y1,z0,z1,true,color,sprite,light);
            if(!open(bore,x,y,z-1))face(out,pos,anchor,2,z0,x0,x1,y0,y1,false,color,sprite,light);
            if(!open(bore,x,y,z+1))face(out,pos,anchor,2,z1,x0,x1,y0,y1,true,color,sprite,light);
            if(y0>y/16F || !open(bore,x,y-1,z))face(out,pos,anchor,1,y0,z0,z1,x0,x1,false,color,sprite,light);
            if(y1<(y+1)/16F || !open(bore,x,y+1,z))face(out,pos,anchor,1,y1,z0,z1,x0,x1,true,color,sprite,light);
        }
    }
    private static boolean open(boolean[] bore,int x,int y,int z){return x>=0&&x<16&&y>=0&&y<16&&z>=0&&z<16&&bore[index(x,y,z)];}
    private static int index(int x,int y,int z){return (z*16+y)*16+x;}
    private static void face(VertexConsumer out,BlockPos pos,BlockPos anchor,int axis,float plane,float u0,float u1,float v0,float v1,
                             boolean positive,int color,TextureAtlasSprite sprite,int light) {
        for(int i=0;i<4;i++) {
            int corner=positive?i:3-i;boolean highU=corner==1||corner==2,highV=corner>=2;
            float u=highU?u1:u0,v=highV?v1:v0;
            float x=axis==0?plane:axis==1?v:u,y=axis==0?u:axis==1?plane:v,z=axis==0?v:axis==1?u:plane;
            var vertex=out.addVertex(pos.getX()-anchor.getX()+x,pos.getY()-anchor.getY()+y,pos.getZ()-anchor.getZ()+z).setColor(color);
            if(sprite!=null)vertex.setUv(sprite.getU(highU?1:0),sprite.getV(highV?1:0)).setUv2(light&65535,light>>>16)
                    .setNormal(axis==0?(positive?1:-1):0,axis==1?(positive?1:-1):0,axis==2?(positive?1:-1):0);
        }
    }
}

package mmm.client;

import java.util.List;
import mmm.network.FluidProtocol;
import mmm.network.PipeSnapshotPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.world.phys.Vec3;
import com.mojang.blaze3d.vertex.VertexConsumer;

/** Snapshot codec, atomic multipart replacement and accepted-bore liquid geometry. */
public final class PipeSnapshotValidation {
    private PipeSnapshotValidation() { }
    public static void run() {
        var dimension=Identifier.parse("minecraft:overworld");var other=Identifier.parse("minecraft:the_nether");
        var segment=new PipeSnapshotPayload.Segment(new BlockPos(1,2,3).asLong(),
                List.of(new PipeSnapshotPayload.Box(0,4,4,16,12,12)),List.of(new PipeSnapshotPayload.Layer("mmm:water",0xff0088ff,.125)));
        var part=new PipeSnapshotPayload(dimension,7,0,2,List.of(segment));
        var buffer=new FriendlyByteBuf(Unpooled.buffer());PipeSnapshotPayload decoded;
        try {PipeSnapshotPayload.CODEC.encode(buffer,part);decoded=PipeSnapshotPayload.CODEC.decode(buffer);check(part.equals(decoded) && buffer.readableBytes()==0,"pipe packet roundtrip");}
        finally {buffer.release();}
        var store=new ClientPipeSnapshotStore();var negotiation=FluidProtocol.negotiate(FluidProtocol.materialServerOffer());
        store.accept(new PipeSnapshotPayload(dimension,7,1,2,List.of()),negotiation);
        check(store.forDimension(dimension).isEmpty(),"incomplete pipe snapshot published");
        store.accept(decoded,negotiation);check(store.forDimension(dimension).equals(List.of(segment)),"complete snapshot did not publish");
        store.accept(new PipeSnapshotPayload(dimension,6,0,1,List.of()),negotiation);
        check(store.forDimension(dimension).size()==1 && store.forDimension(other).isEmpty(),"stale or foreign dimension changed snapshot");
        var vertices=new Vertices();PipeSurfaceGeometry.build(Vec3.ZERO,vertices,segment,false,id->{throw new AssertionError("unexpected sprite");},p->0);
        check(vertices.count>0 && Math.abs(vertices.maxY-2.5)<1e-5 && vertices.minY>=2.25
                && vertices.minZ>=3.25 && vertices.maxZ<=3.75,"liquid escaped the bore or level differs");
        store.accept(new PipeSnapshotPayload(dimension,8,0,1,List.of()),negotiation);
        check(store.forDimension(dimension).isEmpty(),"pipe clear did not remove geometry");store.clear();
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static final class Vertices implements VertexConsumer {
        int count;float minY=Float.POSITIVE_INFINITY,maxY=Float.NEGATIVE_INFINITY,minZ=Float.POSITIVE_INFINITY,maxZ=Float.NEGATIVE_INFINITY;
        public VertexConsumer addVertex(float x,float y,float z){count++;minY=Math.min(minY,y);maxY=Math.max(maxY,y);minZ=Math.min(minZ,z);maxZ=Math.max(maxZ,z);return this;}
        public VertexConsumer setColor(int r,int g,int b,int a){return this;}
        public VertexConsumer setColor(int color){return this;}
        public VertexConsumer setUv(float u,float v){return this;}
        public VertexConsumer setUv1(int u,int v){return this;}
        public VertexConsumer setUv2(int u,int v){return this;}
        public VertexConsumer setNormal(float x,float y,float z){return this;}
        public VertexConsumer setLineWidth(float width){return this;}
    }
}

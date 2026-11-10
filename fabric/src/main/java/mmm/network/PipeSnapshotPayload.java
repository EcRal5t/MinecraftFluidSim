package mmm.network;

import java.util.ArrayList;
import java.util.List;
import mmm.MmmMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Bounded pieces of one dimension-wide accepted pipe display snapshot. */
public record PipeSnapshotPayload(Identifier dimension,long generation,int part,int parts,List<Segment> segments) implements CustomPacketPayload {
    public static final int MAX_SEGMENTS=64, MAX_PARTS=1563, MAX_BOXES=64, MAX_LAYERS=32;
    public static final Type<PipeSnapshotPayload> TYPE=new Type<>(MmmMod.id("pipe_snapshot"));
    public static final StreamCodec<FriendlyByteBuf,PipeSnapshotPayload> CODEC=StreamCodec.of(PipeSnapshotPayload::write,PipeSnapshotPayload::read);
    public record Box(int x0,int y0,int z0,int x1,int y1,int z1) {
        public Box { if(x0<0||y0<0||z0<0||x1>16||y1>16||z1>16||x0>=x1||y0>=y1||z0>=z1)throw new IllegalArgumentException("Invalid pipe bore box"); }
    }
    public record Layer(String material,int argb,double volumeM3) {
        public Layer { if(material==null || material.isBlank() || material.length()>128 || !(Double.isFinite(volumeM3)&&volumeM3>0))throw new IllegalArgumentException("Invalid pipe display layer"); }
    }
    public record Segment(long position,List<Box> bore,List<Layer> layers) {
        public Segment { bore=List.copyOf(bore);layers=List.copyOf(layers);if(bore.isEmpty()||bore.size()>MAX_BOXES||layers.size()>MAX_LAYERS)throw new IllegalArgumentException("Invalid pipe display segment"); }
    }
    public PipeSnapshotPayload {
        segments=List.copyOf(segments);
        if(dimension==null||generation<0||parts<1||parts>MAX_PARTS||part<0||part>=parts||segments.size()>MAX_SEGMENTS)
            throw new IllegalArgumentException("Invalid pipe snapshot part");
    }
    @Override public Type<PipeSnapshotPayload> type(){return TYPE;}
    private static void write(FriendlyByteBuf b,PipeSnapshotPayload value) {
        b.writeIdentifier(value.dimension);b.writeVarLong(value.generation);b.writeVarInt(value.part);b.writeVarInt(value.parts);b.writeVarInt(value.segments.size());
        for(var segment:value.segments) {
            b.writeLong(segment.position);b.writeVarInt(segment.bore.size());
            for(var box:segment.bore){b.writeByte(box.x0);b.writeByte(box.y0);b.writeByte(box.z0);b.writeByte(box.x1);b.writeByte(box.y1);b.writeByte(box.z1);}
            b.writeVarInt(segment.layers.size());for(var layer:segment.layers){b.writeUtf(layer.material,128);b.writeInt(layer.argb);b.writeDouble(layer.volumeM3);}
        }
    }
    private static PipeSnapshotPayload read(FriendlyByteBuf b) {
        var dimension=b.readIdentifier();long generation=b.readVarLong();int part=b.readVarInt(),parts=b.readVarInt();
        int count=bounded(b.readVarInt(),MAX_SEGMENTS);var segments=new ArrayList<Segment>(count);
        for(int i=0;i<count;i++) {
            long position=b.readLong();int boxes=bounded(b.readVarInt(),MAX_BOXES);var bore=new ArrayList<Box>(boxes);
            for(int j=0;j<boxes;j++)bore.add(new Box(b.readUnsignedByte(),b.readUnsignedByte(),b.readUnsignedByte(),b.readUnsignedByte(),b.readUnsignedByte(),b.readUnsignedByte()));
            int layers=bounded(b.readVarInt(),MAX_LAYERS);var values=new ArrayList<Layer>(layers);
            for(int j=0;j<layers;j++)values.add(new Layer(b.readUtf(128),b.readInt(),b.readDouble()));
            segments.add(new Segment(position,bore,values));
        }
        return new PipeSnapshotPayload(dimension,generation,part,parts,segments);
    }
    private static int bounded(int value,int maximum){if(value<0||value>maximum)throw new IllegalArgumentException("Pipe snapshot exceeds budget");return value;}
}

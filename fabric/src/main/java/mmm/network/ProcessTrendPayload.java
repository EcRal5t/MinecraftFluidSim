package mmm.network;

import java.util.ArrayList;
import java.util.List;
import mmm.MmmMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record ProcessTrendPayload(BlockPos position, String dimension, java.util.UUID session, long sequence, String label, String status, boolean open,
                                  boolean available, List<ProcessTrendHistory.Point> points) implements CustomPacketPayload {
    public static final Type<ProcessTrendPayload> TYPE = new Type<>(MmmMod.id("process_trend"));
    public static final StreamCodec<FriendlyByteBuf, ProcessTrendPayload> CODEC = StreamCodec.of(ProcessTrendPayload::encode, ProcessTrendPayload::decode);
    public ProcessTrendPayload { points = List.copyOf(points); if (points.size() > ProcessTrendHistory.LIMIT) throw new IllegalArgumentException("Too many trend points"); }
    @Override public Type<ProcessTrendPayload> type() { return TYPE; }
    private static void encode(FriendlyByteBuf b, ProcessTrendPayload p) {
        b.writeBlockPos(p.position); b.writeUtf(p.dimension, 256); b.writeUUID(p.session); b.writeVarLong(p.sequence); b.writeUtf(p.label, 128); b.writeUtf(p.status, 64);
        b.writeBoolean(p.open); b.writeBoolean(p.available); b.writeVarInt(p.points.size());
        for (var point : p.points) { b.writeDouble(point.seconds()); b.writeDouble(point.value()); b.writeBoolean(point.connected()); }
    }
    private static ProcessTrendPayload decode(FriendlyByteBuf b) {
        var pos=b.readBlockPos(); var dimension=b.readUtf(256); var session=b.readUUID(); long sequence=b.readVarLong(); var label=b.readUtf(128); var status=b.readUtf(64);
        boolean open=b.readBoolean(), available=b.readBoolean(); int count=b.readVarInt();
        if(count<0 || count>ProcessTrendHistory.LIMIT) throw new IllegalArgumentException("Invalid trend point count");
        var points=new ArrayList<ProcessTrendHistory.Point>(count);
        for(int i=0;i<count;i++) points.add(new ProcessTrendHistory.Point(b.readDouble(),b.readDouble(),b.readBoolean()));
        return new ProcessTrendPayload(pos,dimension,session,sequence,label,status,open,available,points);
    }
}

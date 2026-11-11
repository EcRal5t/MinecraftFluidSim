package mmm.network;

import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import mmm.MmmMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record ProcessTrendRequest(BlockPos position, String dimension, UUID session, long sequence, boolean open, List<ProcessTrendPayload.SeriesKey> selected, boolean close) implements CustomPacketPayload {
    public static final int MAX_SELECTED=16;
    public ProcessTrendRequest(BlockPos position,String dimension,UUID session,long sequence,boolean open) { this(position,dimension,session,sequence,open,List.of(new ProcessTrendPayload.SeriesKey("","",ProcessTrendPayload.Metric.LEVEL)),false); }
    public ProcessTrendRequest(BlockPos position,String dimension,UUID session,long sequence,boolean open,List<ProcessTrendPayload.SeriesKey> selected) { this(position,dimension,session,sequence,open,selected,false); }
    public ProcessTrendRequest { selected=List.copyOf(selected); if(sequence<0 || selected.size()>MAX_SELECTED || selected.stream().distinct().count()!=selected.size()) throw new IllegalArgumentException("Invalid trend request"); }
    public static final Type<ProcessTrendRequest> TYPE = new Type<>(MmmMod.id("process_trend_request_v3"));
    public static final StreamCodec<FriendlyByteBuf, ProcessTrendRequest> CODEC = StreamCodec.of((b,p)->{ b.writeBlockPos(p.position); b.writeUtf(p.dimension,256); b.writeUUID(p.session); b.writeVarLong(p.sequence); b.writeBoolean(p.open); b.writeBoolean(p.close); b.writeVarInt(p.selected.size()); for(var k:p.selected) ProcessTrendPayload.writeKey(b,k); }, b->{ var pos=b.readBlockPos(); var dim=b.readUtf(256); var session=b.readUUID(); long seq=b.readVarLong(); boolean open=b.readBoolean(),close=b.readBoolean(); int n=b.readVarInt(); if(n<0||n>MAX_SELECTED) throw new IllegalArgumentException("Invalid trend selection"); var selected=new ArrayList<ProcessTrendPayload.SeriesKey>(n); for(int i=0;i<n;i++) selected.add(ProcessTrendPayload.readKey(b)); return new ProcessTrendRequest(pos,dim,session,seq,open,selected,close); });
    @Override public Type<ProcessTrendRequest> type() { return TYPE; }
}

package mmm.network;

import java.util.ArrayList;
import java.util.List;
import mmm.MmmMod;
import mmm.api.sensor.ProcessSensorSnapshot.Measure;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record ProcessTrendPayload(BlockPos position, String dimension, java.util.UUID session, long sequence, String label, String status, boolean open,
                                  boolean available, List<SeriesKey> catalog, List<Series> series) implements CustomPacketPayload {
    public static final int MAX_CATALOG = 128;
    public enum Metric {
        LEVEL("1"), PRESSURE("Pa"), LIQUID_TEMPERATURE("K"), GAS_TEMPERATURE("K"), FLOW("L/s"),
        MASS("kg"), AMOUNT("mol"), VOLUME("m³"), TEMPERATURE("K");
        private final String unit;
        Metric(String unit) { this.unit=unit; }
        public String unit() { return unit; }
        public static Metric fromMeasure(Measure measure) { return valueOf(measure.name()); }
    }
    public record SeriesKey(String componentId, String phase, Metric metric, boolean ion, boolean residualMass) {
        public SeriesKey(String componentId, String phase, Metric metric) { this(componentId,phase,metric,false,false); }
        public SeriesKey {
            if (componentId == null || componentId.length()>128 || phase == null || phase.length()>32 || metric == null)
                throw new IllegalArgumentException("Invalid trend key");
            if(componentId.isEmpty()) {
                if(!phase.isEmpty() || ion || residualMass) throw new IllegalArgumentException("Invalid total trend key");
            } else {
                try { mmm.fluid.FluidComposition.Phase.valueOf(phase); } catch(IllegalArgumentException e) { throw new IllegalArgumentException("Invalid trend phase",e); }
                if(metric.ordinal()<Metric.MASS.ordinal()) throw new IllegalArgumentException("Invalid component trend metric");
            }
        }
    }
    public record Series(SeriesKey key, List<ProcessTrendHistory.Point> points) {
        public Series(Measure measure,List<ProcessTrendHistory.Point> points) { this(new SeriesKey("","",Metric.fromMeasure(measure)),points); }
        public Series { points = List.copyOf(points); if (points.size() > ProcessTrendHistory.LIMIT) throw new IllegalArgumentException("Too many trend points"); }
    }
    public static final Type<ProcessTrendPayload> TYPE = new Type<>(MmmMod.id("process_trend_v3"));
    public static final StreamCodec<FriendlyByteBuf, ProcessTrendPayload> CODEC = StreamCodec.of(ProcessTrendPayload::encode, ProcessTrendPayload::decode);
    public ProcessTrendPayload(BlockPos position,String dimension,java.util.UUID session,long sequence,String label,String status,boolean open,boolean available,List<Series> series) {
        this(position,dimension,session,sequence,label,status,open,available,series.stream().map(Series::key).toList(),series);
    }
    public ProcessTrendPayload {
        catalog=List.copyOf(catalog); series=List.copyOf(series);
        if(catalog.size()>MAX_CATALOG || catalog.stream().distinct().count()!=catalog.size() || series.size()>ProcessTrendRequest.MAX_SELECTED || series.stream().map(Series::key).distinct().count()!=series.size()) throw new IllegalArgumentException("Invalid trend series");
    }
    @Override public Type<ProcessTrendPayload> type() { return TYPE; }
    static void writeKey(FriendlyByteBuf b,SeriesKey k) { b.writeUtf(k.componentId(),128); b.writeUtf(k.phase(),32); b.writeVarInt(k.metric().ordinal()); b.writeBoolean(k.ion()); b.writeBoolean(k.residualMass()); }
    static SeriesKey readKey(FriendlyByteBuf b) { String id=b.readUtf(128),phase=b.readUtf(32); int metric=b.readVarInt(); if(metric<0||metric>=Metric.values().length) throw new IllegalArgumentException("Invalid trend metric"); return new SeriesKey(id,phase,Metric.values()[metric],b.readBoolean(),b.readBoolean()); }
    private static void encode(FriendlyByteBuf b, ProcessTrendPayload p) {
        b.writeBlockPos(p.position); b.writeUtf(p.dimension,256); b.writeUUID(p.session); b.writeVarLong(p.sequence); b.writeUtf(p.label,128); b.writeUtf(p.status,64);
        b.writeBoolean(p.open); b.writeBoolean(p.available); b.writeVarInt(p.catalog.size()); for(var k:p.catalog) writeKey(b,k);
        b.writeVarInt(p.series.size()); for(var s:p.series) { writeKey(b,s.key()); b.writeVarInt(s.points.size()); for(var point:s.points) { b.writeDouble(point.seconds()); b.writeDouble(point.value()); b.writeBoolean(point.connected()); } }
    }
    private static ProcessTrendPayload decode(FriendlyByteBuf b) {
        var pos=b.readBlockPos(); var dimension=b.readUtf(256); var session=b.readUUID(); long sequence=b.readVarLong(); var label=b.readUtf(128); var status=b.readUtf(64); boolean open=b.readBoolean(),available=b.readBoolean();
        int count=b.readVarInt(); if(count<0||count>MAX_CATALOG) throw new IllegalArgumentException("Invalid trend catalog"); var catalog=new ArrayList<SeriesKey>(count); for(int i=0;i<count;i++) catalog.add(readKey(b));
        count=b.readVarInt(); if(count<0||count>ProcessTrendRequest.MAX_SELECTED) throw new IllegalArgumentException("Invalid trend series count"); var series=new ArrayList<Series>(count);
        for(int i=0;i<count;i++) { var key=readKey(b); int size=b.readVarInt(); if(size<0||size>ProcessTrendHistory.LIMIT) throw new IllegalArgumentException("Invalid trend points"); var points=new ArrayList<ProcessTrendHistory.Point>(size); for(int j=0;j<size;j++) points.add(new ProcessTrendHistory.Point(b.readDouble(),b.readDouble(),b.readBoolean())); series.add(new Series(key,points)); }
        return new ProcessTrendPayload(pos,dimension,session,sequence,label,status,open,available,catalog,series);
    }
}

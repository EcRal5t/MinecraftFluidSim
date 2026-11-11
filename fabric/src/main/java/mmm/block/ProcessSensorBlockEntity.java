package mmm.block;

import net.minecraft.core.BlockPos;
import mmm.api.sensor.ProcessSensorSnapshot;
import mmm.api.sensor.ProcessSensorSnapshot.Measure;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

public final class ProcessSensorBlockEntity extends BlockEntity {
    private Measure measure=Measure.LEVEL;
    private double low=.25,high=.75;
    private ProcessSensorSnapshot snapshot=ProcessSensorSnapshot.waiting(Measure.LEVEL);
    private boolean below=true,analog;
    private int output;
    private BlockPos first,second;
    private ProcessSensorSnapshot lastSent;
    private long lastSyncTick;
    public record TrendObserver(java.util.UUID player,java.util.UUID session) { }
    private record TrendSubscription(long until, long sequence, java.util.List<mmm.network.ProcessTrendPayload.SeriesKey> keys) { }
    private final java.util.Map<TrendObserver,TrendSubscription> trendSubscriptions=new java.util.HashMap<>();
    private final java.util.Map<mmm.network.ProcessTrendPayload.SeriesKey,mmm.network.ProcessTrendHistory> componentHistories=new java.util.HashMap<>();
    private java.util.List<mmm.network.ProcessTrendPayload.SeriesKey> trendCatalog=java.util.List.of();
    public void subscribeTrend(TrendObserver session,long sequence,long tick,java.util.List<mmm.network.ProcessTrendPayload.SeriesKey> keys,boolean close) {
        pruneTrend(tick);
        var previous=trendSubscriptions.get(session);
        if(previous!=null && sequence<=previous.sequence()) return;
        if(close) trendSubscriptions.remove(session);
        else if(previous!=null || trendSubscriptions.size()<32) trendSubscriptions.put(session,new TrendSubscription(tick+40,sequence,keys));
        pruneTrend(tick);
    }
    public void pruneTrend(long tick) {
        trendSubscriptions.values().removeIf(value->value.until()<tick);
        var selected=trendKeys(); componentHistories.keySet().retainAll(selected);
        if(trendSubscriptions.isEmpty()) trendCatalog=java.util.List.of();
    }
    public java.util.Set<mmm.network.ProcessTrendPayload.SeriesKey> trendKeys() {
        var keys=new java.util.HashSet<mmm.network.ProcessTrendPayload.SeriesKey>();
        for(var value:trendSubscriptions.values()) keys.addAll(value.keys());
        return keys;
    }
    public boolean hasTrendObservers() { return !trendSubscriptions.isEmpty(); }
    public java.util.List<mmm.network.ProcessTrendPayload.SeriesKey> trendCatalog() { return trendCatalog; }
    public void trendCatalog(java.util.List<mmm.network.ProcessTrendPayload.SeriesKey> catalog) { trendCatalog=java.util.List.copyOf(catalog); }
    public mmm.network.ProcessTrendHistory trendHistory(mmm.network.ProcessTrendPayload.SeriesKey key) {
        if(key.componentId().isEmpty()) {
            try { return history(Measure.valueOf(key.metric().name())); } catch(IllegalArgumentException ignored) { }
        }
        return componentHistories.computeIfAbsent(key,k->new mmm.network.ProcessTrendHistory());
    }
    public void breakTrendHistories() { componentHistories.values().forEach(mmm.network.ProcessTrendHistory::breakSeries); trendCatalog=java.util.List.of(); }
    private final java.util.EnumMap<Measure, mmm.network.ProcessTrendHistory> histories = new java.util.EnumMap<>(Measure.class);
    { for (var metric : Measure.values()) histories.put(metric, new mmm.network.ProcessTrendHistory()); }
    public mmm.network.ProcessTrendHistory history(){return history(measure);}
    public mmm.network.ProcessTrendHistory history(Measure metric){return histories.get(metric);}
    public void resetHistories(){histories.values().forEach(mmm.network.ProcessTrendHistory::reset); componentHistories.clear(); trendCatalog=java.util.List.of();}
    public ProcessSensorBlockEntity(BlockPos pos,BlockState state){super(MmmBlocks.PROCESS_SENSOR_ENTITY,pos,state);}
    public Measure measure(){return measure;}
    public double low(){return low;}
    public double high(){return high;}
    public boolean below(){return below;}
    public boolean analog(){return analog;}
    public ProcessSensorSnapshot snapshot(){return snapshot;}
    public int output(){return output;}
    public BlockPos first(){return first;}
    public BlockPos second(){return second;}
    public void configure(Measure measure,double low,double high,boolean below,boolean analog,BlockPos first,BlockPos second){
        if(!Double.isFinite(low)||!Double.isFinite(high)||high<=low)throw new IllegalArgumentException("上限应大于下限");
        if(this.measure!=measure || this.low!=low || this.high!=high || !java.util.Objects.equals(this.first,first) || !java.util.Objects.equals(this.second,second))resetHistories();
        boolean changed=this.measure!=measure || this.low!=low || this.high!=high || this.below!=below || this.analog!=analog
                || !java.util.Objects.equals(this.first,first) || !java.util.Objects.equals(this.second,second);
        this.measure=measure;this.low=low;this.high=high;this.below=below;this.analog=analog;this.first=first.immutable();this.second=second.immutable();setChanged();
        if(changed){signal(0);publish(ProcessSensorSnapshot.waiting(measure),true);}
    }
    public void observe(double value,java.util.UUID region,long incarnation,long generation,double seconds,boolean running){
        var observed=new ProcessSensorSnapshot(measure,running?ProcessSensorSnapshot.Status.RUNNING:
                ProcessSensorSnapshot.Status.PAUSED,value,region,incarnation,generation,seconds);
        int next=output;
        if(analog){double scaled=Math.max(0,Math.min(1,(value-low)/(high-low)));next=(int)Math.round(15*(below?1-scaled:scaled));}
        else if(value<=low)next=below?15:0;
        else if(value>=high)next=below?0:15;
        signal(next); publish(observed);
    }
    public void unavailable(ProcessSensorSnapshot.Status status){
        history().breakSeries(status.label()); signal(0);
        publish(ProcessSensorSnapshot.unavailable(measure,status));
    }
    private void publish(ProcessSensorSnapshot next){publish(next,false);}
    private void publish(ProcessSensorSnapshot next,boolean force){
        if(next.equals(snapshot) && !force)return;
        snapshot=next;
        if(level!=null && !level.isClientSide()) {
            if(force || shouldSync(lastSent,next,lastSyncTick,level.getGameTime())) {
                lastSent=next; lastSyncTick=level.getGameTime();
                level.sendBlockUpdated(worldPosition,getBlockState(),getBlockState(),net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
            }
        }
    }
    static boolean shouldSync(ProcessSensorSnapshot last,ProcessSensorSnapshot next,long lastTick,long tick){
        if(last==null || last.measure()!=next.measure() || last.status()!=next.status()
                || !java.util.Objects.equals(last.value(),next.value()) || !java.util.Objects.equals(last.regionId(),next.regionId())
                || last.regionIncarnation()!=next.regionIncarnation())return true;
        return tick<lastTick || tick-lastTick>=20;
    }
    @Override public net.minecraft.nbt.CompoundTag getUpdateTag(net.minecraft.core.HolderLookup.Provider registries){
        var tag=saveCustomOnly(registries); mmm.sensor.ProcessSensorSnapshotCodec.write(tag,snapshot); return tag;
    }
    @Override public net.minecraft.network.protocol.Packet<net.minecraft.network.protocol.game.ClientGamePacketListener> getUpdatePacket(){
        return net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(this);
    }
    public void signal(int next){
        if(next==output)return;output=next;setChanged();
        if(level!=null)level.updateNeighborsAt(worldPosition,getBlockState().getBlock());
    }
    @Override protected void loadAdditional(ValueInput input){
        super.loadAdditional(input);measure=Measure.valueOf(input.getStringOr("measure","LEVEL"));low=input.getDoubleOr("low",.25);high=input.getDoubleOr("high",.75);
        below=input.getBooleanOr("below",true);analog=input.getBooleanOr("analog",false);output=input.getIntOr("output",0);
        first=input.getLong("first").map(BlockPos::of).orElse(null);second=input.getLong("second").map(BlockPos::of).orElse(null);
        snapshot=mmm.sensor.ProcessSensorSnapshotCodec.read(input,measure);
        resetHistories(); lastSent=null; lastSyncTick=0; trendSubscriptions.clear();
    }
    @Override protected void saveAdditional(ValueOutput output){
        super.saveAdditional(output);output.putString("measure",measure.name());output.putDouble("low",low);output.putDouble("high",high);
        output.putBoolean("below",below);output.putBoolean("analog",analog);output.putInt("output",this.output);
        if(first!=null){output.putLong("first",first.asLong());output.putLong("second",second.asLong());}
    }
}

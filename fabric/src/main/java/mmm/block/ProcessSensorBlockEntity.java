package mmm.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

public final class ProcessSensorBlockEntity extends BlockEntity {
    public enum Measure {
        LEVEL("液位 · 0—1"), LIQUID_TEMPERATURE("液体温度 · K"), GAS_TEMPERATURE("气体温度 · K"),
        PRESSURE("压力 · Pa"), FLOW("液体流量 · L/s");
        private final String label;
        Measure(String label){this.label=label;}
        public String label(){return label;}
    }
    private Measure measure=Measure.LEVEL;
    private double low=.25,high=.75,reading;
    private boolean below=true,analog;
    private int output;
    private BlockPos first,second;
    private final mmm.network.ProcessTrendHistory history = new mmm.network.ProcessTrendHistory();
    public mmm.network.ProcessTrendHistory history(){return history;}
    public ProcessSensorBlockEntity(BlockPos pos,BlockState state){super(MmmBlocks.PROCESS_SENSOR_ENTITY,pos,state);}
    public Measure measure(){return measure;}
    public double low(){return low;}
    public double high(){return high;}
    public boolean below(){return below;}
    public boolean analog(){return analog;}
    public double reading(){return reading;}
    public int output(){return output;}
    public BlockPos first(){return first;}
    public BlockPos second(){return second;}
    public void configure(Measure measure,double low,double high,boolean below,boolean analog,BlockPos first,BlockPos second){
        if(!Double.isFinite(low)||!Double.isFinite(high)||high<=low)throw new IllegalArgumentException("上限应大于下限");
        if(this.measure!=measure || this.low!=low || this.high!=high || !java.util.Objects.equals(this.first,first) || !java.util.Objects.equals(this.second,second))history.reset();
        this.measure=measure;this.low=low;this.high=high;this.below=below;this.analog=analog;this.first=first.immutable();this.second=second.immutable();setChanged();
    }
    public void observe(double value){
        reading=value;int next=output;
        if(analog){double scaled=Math.max(0,Math.min(1,(value-low)/(high-low)));next=(int)Math.round(15*(below?1-scaled:scaled));}
        else if(value<=low)next=below?15:0;
        else if(value>=high)next=below?0:15;
        signal(next);
    }
    public void signal(int next){
        if(next==output)return;output=next;setChanged();
        if(level!=null)level.updateNeighborsAt(worldPosition,getBlockState().getBlock());
    }
    @Override protected void loadAdditional(ValueInput input){
        super.loadAdditional(input);measure=Measure.valueOf(input.getStringOr("measure","LEVEL"));low=input.getDoubleOr("low",.25);high=input.getDoubleOr("high",.75);
        below=input.getBooleanOr("below",true);analog=input.getBooleanOr("analog",false);output=input.getIntOr("output",0);
        first=input.getLong("first").map(BlockPos::of).orElse(null);second=input.getLong("second").map(BlockPos::of).orElse(null);
    }
    @Override protected void saveAdditional(ValueOutput output){
        super.saveAdditional(output);output.putString("measure",measure.name());output.putDouble("low",low);output.putDouble("high",high);
        output.putBoolean("below",below);output.putBoolean("analog",analog);output.putInt("output",this.output);
        if(first!=null){output.putLong("first",first.asLong());output.putLong("second",second.asLong());}
    }
}

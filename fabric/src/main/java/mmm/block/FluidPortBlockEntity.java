package mmm.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/** Online transfer settings; the adjacent material box owns the transferred batches. */
public final class FluidPortBlockEntity extends BlockEntity {
    public enum Mode implements net.minecraft.util.StringRepresentable {
        STOPPED, INPUT, OUTPUT;
        @Override public String getSerializedName(){return name().toLowerCase(java.util.Locale.ROOT);}
    }
    public enum Phase { LIQUID, SOLID, GAS }
    private Mode mode=Mode.STOPPED;
    private Phase phase=Phase.LIQUID;
    private long nanosPerSecond=50_000_000L;
    private boolean redstone;
    private String status="停止";
    public FluidPortBlockEntity(BlockPos position,BlockState state) { super(MmmBlocks.FLUID_PORT_ENTITY,position,state); }
    public Mode mode(){return mode;}
    public Phase phase(){return phase;}
    public long nanosPerSecond(){return nanosPerSecond;}
    public boolean redstone(){return redstone;}
    public String status(){return status;}
    public void status(String value){status=value;}
    public void configure(Mode mode,Phase phase,long rate,boolean redstone){
        if(rate<=0||rate>1_000_000_000L)throw new IllegalArgumentException("速率范围为 0—1000 L/s");
        this.mode=mode;this.phase=phase;this.nanosPerSecond=rate;this.redstone=redstone;
        status=mode==Mode.STOPPED?"停止":"等待运行";setChanged();syncDisplayState();
    }
    /** Publishes the saved transfer mode to block models, including the first tick after loading. */
    public void syncDisplayState(){
        if(level==null||level.isClientSide())return;
        var state=getBlockState();
        if(state.getValue(FluidPortBlock.MODE)!=mode)
            level.setBlock(worldPosition,state.setValue(FluidPortBlock.MODE,mode),net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
    }
    @Override protected void loadAdditional(ValueInput input){
        super.loadAdditional(input);mode=Mode.valueOf(input.getStringOr("mode","STOPPED"));phase=Phase.valueOf(input.getStringOr("phase","LIQUID"));
        nanosPerSecond=input.getLongOr("rate",50_000_000L);redstone=input.getBooleanOr("redstone",false);
        status=mode==Mode.STOPPED?"停止":"等待运行";
    }
    @Override protected void saveAdditional(ValueOutput output){
        super.saveAdditional(output);output.putString("mode",mode.name());output.putString("phase",phase.name());output.putLong("rate",nanosPerSecond);output.putBoolean("redstone",redstone);
    }
}

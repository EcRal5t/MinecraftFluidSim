package mmm.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/** The screen geometry is configured here; deposited solids remain in the region inventory. */
public final class ScreenBlockEntity extends BlockEntity {
    private double poreDiameter=.00025,openFraction=.6;
    public ScreenBlockEntity(BlockPos position,BlockState state){super(MmmBlocks.SCREEN_ENTITY,position,state);}
    public double poreDiameter(){return poreDiameter;}
    public double openFraction(){return openFraction;}
    public void configure(double poreDiameter,double openFraction){
        if(!Double.isFinite(poreDiameter)||poreDiameter<=0||!Double.isFinite(openFraction)||openFraction<=0||openFraction>1)throw new IllegalArgumentException("筛孔应大于零，开放率范围为 0—1");
        this.poreDiameter=poreDiameter;this.openFraction=openFraction;setChanged();
        if(level instanceof net.minecraft.server.level.ServerLevel server)
            mmm.simulation.MmmRuntimeRegistry.require(server.getServer()).worldUpdates().changed(server,worldPosition);
    }
    @Override protected void loadAdditional(ValueInput input){super.loadAdditional(input);poreDiameter=input.getDoubleOr("pore_diameter",.00025);openFraction=input.getDoubleOr("open_fraction",.6);}
    @Override protected void saveAdditional(ValueOutput output){super.saveAdditional(output);output.putDouble("pore_diameter",poreDiameter);output.putDouble("open_fraction",openFraction);}
}

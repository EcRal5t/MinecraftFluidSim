package mmm.block;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;

/** A pipe connects only to an available opposing compatible port. */
public final class PipeConnectionValidation {
    private PipeConnectionValidation() { }
    public static void run() {
        for(var face:Direction.values()) {
            var neighbor=MmmBlocks.METAL_PIPE.defaultBlockState();
            check(PipeBlock.automatic(neighbor,face)==PipeBlock.Port.CONNECTED,"automatic pipe connection");
            check(PipeBlock.automatic(neighbor.setValue(PipeBlock.port(face.getOpposite()),PipeBlock.Port.SEALED),face)==PipeBlock.Port.NONE,"sealed opposing port");
            check(PipeBlock.automatic(Blocks.GLASS.defaultBlockState(),face)==PipeBlock.Port.NONE,"unrelated block connection");
            check(PipeBlock.automatic(MmmBlocks.THERMAL_RESERVOIR.defaultBlockState(),face)==PipeBlock.Port.CONNECTED,"reservoir face");
            check((PipeBlock.automatic(MmmBlocks.FINITE_HEAT_EXCHANGER.defaultBlockState(),face)==PipeBlock.Port.CONNECTED)
                    ==(face.getAxis()==Direction.Axis.Z),"exchanger thermal face");
            check(PipeBlock.nextManual(PipeBlock.Port.NONE,neighbor,face)==PipeBlock.Port.OPEN,"open automatic port");
            check(PipeBlock.nextManual(PipeBlock.Port.OPEN,neighbor,face)==PipeBlock.Port.SEALED,"manually seal port");
            check(PipeBlock.nextManual(PipeBlock.Port.SEALED,neighbor,face)==PipeBlock.Port.CONNECTED,"sealed port returns to compatible automatic connection");
            check(PipeBlock.nextManual(PipeBlock.Port.SEALED,Blocks.AIR.defaultBlockState(),face)==PipeBlock.Port.NONE,"sealed port returns to capped automatic state");
            for(var axis:Direction.values()) {
                boolean aligned=axis.getAxis()==face.getAxis();
                check((PipeBlock.automatic(MmmBlocks.PIPE_VALVE.defaultBlockState().setValue(PipeValveBlock.FACING,axis),face)==PipeBlock.Port.CONNECTED)==aligned,"valve port direction");
                check((PipeBlock.automatic(MmmBlocks.PIPE_SCREEN.defaultBlockState().setValue(ScreenBlock.FACING,axis),face)==PipeBlock.Port.CONNECTED)==aligned,"screen port direction");
                check((PipeBlock.automatic(MmmBlocks.FLUID_PORT.defaultBlockState().setValue(FluidPortBlock.FACING,axis),face)==PipeBlock.Port.CONNECTED)==(axis==face),"container port inner face");
            }
        }
    }
    private static void check(boolean value,String message) { if(!value)throw new AssertionError(message); }
}

package mmm.block;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;

/** A pipe connects only to an available opposing compatible port. */
public final class PipeConnectionValidation {
    private PipeConnectionValidation() { }
    public static void run() {
        placement();
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
                check((PipeBlock.automatic(MmmBlocks.FLUID_PORT.defaultBlockState().setValue(FluidPortBlock.FACING,axis),face)==PipeBlock.Port.CONNECTED)==(axis==face.getOpposite()),"container port outward connection");
            }
        }
    }
    private static void placement() {
        var pipe=(PipeBlock)MmmBlocks.METAL_PIPE;
        var air=Blocks.AIR.defaultBlockState();
        for(var clicked:Direction.values()) {
            var parallel=pipe.placementState(clicked,face -> air);
            for(var face:Direction.values())check(parallel.getValue(PipeBlock.port(face))==
                    (face.getAxis()==clicked.getAxis()?PipeBlock.Port.NONE:PipeBlock.Port.SEALED),"placement axis");
            var side=java.util.Arrays.stream(Direction.values()).filter(f -> f.getAxis()!=clicked.getAxis()).findFirst().orElseThrow();
            var alongside=pipe.placementState(clicked,face -> face==side?parallel:air);
            check(alongside.getValue(PipeBlock.port(side))==PipeBlock.Port.SEALED,"parallel pipes joined sideways");
            var extension=pipe.placementState(clicked,face -> face==clicked.getOpposite()?parallel:air);
            check(extension.getValue(PipeBlock.port(clicked.getOpposite()))==PipeBlock.Port.CONNECTED,"axial extension");
            var branch=alongside.setValue(PipeBlock.port(side),PipeBlock.nextManual(PipeBlock.Port.SEALED,parallel,side));
            check(branch.getValue(PipeBlock.port(side))==PipeBlock.Port.NONE,"manual branch auto mode");
            branch=branch.setValue(PipeBlock.port(side),PipeBlock.nextManual(branch.getValue(PipeBlock.port(side)),parallel,side));
            check(branch.getValue(PipeBlock.port(side))==PipeBlock.Port.OPEN,"manual branch open");
            var rotated=extension.rotate(net.minecraft.world.level.block.Rotation.CLOCKWISE_90);
            for(var face:Direction.values())check(rotated.getValue(PipeBlock.port(net.minecraft.world.level.block.Rotation.CLOCKWISE_90.rotate(face)))==
                    extension.getValue(PipeBlock.port(face)),"placement rotation");
        }
        for(var first:java.util.List.of(MmmBlocks.METAL_PIPE,MmmBlocks.INSULATED_PIPE,MmmBlocks.GLASS_PIPE,MmmBlocks.INSULATED_GLASS_PIPE))
            for(var second:java.util.List.of(MmmBlocks.METAL_PIPE,MmmBlocks.INSULATED_PIPE,MmmBlocks.GLASS_PIPE,MmmBlocks.INSULATED_GLASS_PIPE)) {
                var other=second.defaultBlockState().setValue(PipeBlock.port(Direction.WEST),PipeBlock.Port.OPEN);
                var placed=((PipeBlock)first).placementState(Direction.EAST,face -> face==Direction.EAST?other:air);
                check(placed.getValue(PipeBlock.port(Direction.EAST))==PipeBlock.Port.CONNECTED,"mixed material connection");
            }
    }
    private static void check(boolean value,String message) { if(!value)throw new AssertionError(message); }
}

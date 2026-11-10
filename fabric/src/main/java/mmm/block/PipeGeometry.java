package mmm.block;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** The same quarter-block grid defines pipe walls, openings and shutters. */
public final class PipeGeometry {
    private static final VoxelShape[] SHELLS=new VoxelShape[64];
    static {
        for(int mask=0;mask<64;mask++) {
            VoxelShape cavity=Block.box(4,4,4,12,12,12);
            for(var face:Direction.values())if((mask & 1<<face.ordinal())!=0) {
                VoxelShape arm=switch(face) {
                    case DOWN -> Block.box(4,0,4,12,4,12);
                    case UP -> Block.box(4,12,4,12,16,12);
                    case NORTH -> Block.box(4,4,0,12,12,4);
                    case SOUTH -> Block.box(4,4,12,12,12,16);
                    case WEST -> Block.box(0,4,4,4,12,12);
                    case EAST -> Block.box(12,4,4,16,12,12);
                };
                cavity=Shapes.joinUnoptimized(cavity,arm,BooleanOp.OR);
            }
            SHELLS[mask]=Shapes.joinUnoptimized(Shapes.block(),cavity,BooleanOp.ONLY_FIRST).optimize();
        }
    }
    private PipeGeometry() { }
    public static int bit(Direction face) { return 1<<face.ordinal(); }
    public static VoxelShape shell(int openFaces) {
        if((openFaces&~63)!=0)throw new IllegalArgumentException("Invalid pipe openings");
        return SHELLS[openFaces];
    }
    public static VoxelShape axial(Direction facing) { return shell(bit(facing)|bit(facing.getOpposite())); }
    public static VoxelShape valve(Direction facing,int opening) {
        if(opening<0 || opening>2)throw new IllegalArgumentException("Invalid pipe valve opening");
        var shell=axial(facing); if(opening==2)return shell;
        int upper=opening==0?12:8;
        VoxelShape shutter=switch(facing.getAxis()) {
            case X -> Block.box(4,4,4,8,upper,12);
            case Y -> Block.box(4,4,4,upper,8,12);
            case Z -> Block.box(4,4,4,12,upper,8);
        };
        return Shapes.joinUnoptimized(shell,shutter,BooleanOp.OR).optimize();
    }
}

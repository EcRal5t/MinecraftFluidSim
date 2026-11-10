package mmm.block;

import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Pixel-aligned pipe envelope, enclosed bore and physical wall geometry. */
public final class PipeGeometry {
    private static final VoxelShape[] LEGACY_SHELLS=new VoxelShape[64];
    private static final ConcurrentHashMap<Integer,VoxelShape> ENVELOPES=new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer,VoxelShape> BORES=new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer,VoxelShape> SHELLS=new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer,Measures> MEASURES=new ConcurrentHashMap<>();
    static {
        for(int mask=0;mask<64;mask++) {
            VoxelShape cavity=Block.box(4,4,4,12,12,12);
            for(var face:Direction.values())if((mask&bit(face))!=0)cavity=union(cavity,legacyArm(face));
            LEGACY_SHELLS[mask]=Shapes.joinUnoptimized(Shapes.block(),cavity,BooleanOp.ONLY_FIRST).optimize();
        }
    }
    private PipeGeometry() { }
    public static int bit(Direction face) { return 1<<face.ordinal(); }
    /** Quarter-grid reference shape used by existing tests until the new pipe domain is adopted. */
    public static VoxelShape shell(int openFaces) { check(openFaces,openFaces);return LEGACY_SHELLS[openFaces]; }
    public static VoxelShape envelope(int armFaces) {
        check(armFaces,0);
        return ENVELOPES.computeIfAbsent(armFaces,mask->{
            VoxelShape shape=Block.box(3,3,3,13,13,13);
            for(var face:Direction.values())if((mask&bit(face))!=0)shape=union(shape,outerArm(face));
            return shape.optimize();
        });
    }
    public static VoxelShape bore(int armFaces,int openFaces) {
        check(armFaces,openFaces);
        int key=(armFaces<<6)|openFaces;
        return BORES.computeIfAbsent(key,ignored->{
            VoxelShape shape=Block.box(4,4,4,12,12,12);
            for(var face:Direction.values())if((armFaces&bit(face))!=0)
                shape=union(shape,innerArm(face,(openFaces&bit(face))!=0));
            return shape.optimize();
        });
    }
    public static VoxelShape shell(int armFaces,int openFaces) {
        check(armFaces,openFaces);int key=(armFaces<<6)|openFaces;
        return SHELLS.computeIfAbsent(key,ignored->Shapes.joinUnoptimized(envelope(armFaces),bore(armFaces,openFaces),BooleanOp.ONLY_FIRST).optimize());
    }
    public static VoxelShape axial(Direction facing) {
        int sides=bit(facing)|bit(facing.getOpposite());return shell(sides,sides);
    }
    public static VoxelShape valve(Direction facing,int opening) {
        if(opening<0 || opening>2)throw new IllegalArgumentException("Invalid pipe valve opening");
        var shell=axial(facing);return opening==2?shell:union(shell,shutter(facing,opening)).optimize();
    }
    public static VoxelShape shell(BlockState state) {
        if(state.getBlock() instanceof PipeBlock)return shell(PipeBlock.armFaces(state),PipeBlock.openFaces(state));
        if(state.getBlock() instanceof PipeValveBlock)return valve(state.getValue(PipeValveBlock.FACING),state.getValue(PipeValveBlock.OPENING));
        if(state.getBlock() instanceof PipeScreenBlock)return axial(state.getValue(PipeScreenBlock.FACING));
        throw new IllegalArgumentException("Not a pipe state");
    }
    public static VoxelShape bore(BlockState state) {
        if(state.getBlock() instanceof PipeBlock)return bore(PipeBlock.armFaces(state),PipeBlock.openFaces(state));
        Direction facing=axis(state);int sides=bit(facing)|bit(facing.getOpposite());var cavity=bore(sides,sides);
        if(state.getBlock() instanceof PipeValveBlock && state.getValue(PipeValveBlock.OPENING)<2)
            cavity=Shapes.joinUnoptimized(cavity,shutter(facing,state.getValue(PipeValveBlock.OPENING)),BooleanOp.ONLY_FIRST).optimize();
        return cavity;
    }
    public static VoxelShape envelope(BlockState state) {
        if(state.getBlock() instanceof PipeBlock)return envelope(PipeBlock.armFaces(state));
        Direction facing=axis(state);return envelope(bit(facing)|bit(facing.getOpposite()));
    }
    /** Cubic metres of solid wall, including an installed valve shutter. */
    public static double wallVolume(BlockState state) { return measures(state).volume; }
    /** Square metres of wall facing the pipe bore. */
    public static double innerArea(BlockState state) { return measures(state).inside; }
    /** Square metres of wall facing the surrounding space. */
    public static double outerArea(BlockState state) { return measures(state).outside; }
    private record Measures(double volume,double inside,double outside) { }
    private static Measures measures(BlockState state) {
        int key;
        if(state.getBlock() instanceof PipeBlock)key=(PipeBlock.armFaces(state)<<6)|PipeBlock.openFaces(state);
        else {Direction face=axis(state);int sides=bit(face)|bit(face.getOpposite());
            int opening=state.getBlock() instanceof PipeValveBlock?state.getValue(PipeValveBlock.OPENING):2;
            key=4096+(face.getAxis().ordinal()*3+opening)*4096+(sides<<6)+sides;
        }
        return MEASURES.computeIfAbsent(key,ignored->measure(shell(state),bore(state)));
    }
    private static Measures measure(VoxelShape wall,VoxelShape inside) {
        boolean[] solid=new boolean[4096],cavity=new boolean[4096];var solidBoxes=wall.toAabbs();var boreBoxes=inside.toAabbs();
        int count=0,inner=0,outer=0;
        for(int z=0;z<16;z++)for(int y=0;y<16;y++)for(int x=0;x<16;x++) {
            int i=(z*16+y)*16+x;double xx=(x+.5)/16,yy=(y+.5)/16,zz=(z+.5)/16;
            solid[i]=solidBoxes.stream().anyMatch(b->b.contains(xx,yy,zz));
            cavity[i]=boreBoxes.stream().anyMatch(b->b.contains(xx,yy,zz));
            if(solid[i])count++;
        }
        for(int z=0;z<16;z++)for(int y=0;y<16;y++)for(int x=0;x<16;x++)if(solid[(z*16+y)*16+x])
            for(var face:Direction.values()) {
                int xx=x+face.getStepX(),yy=y+face.getStepY(),zz=z+face.getStepZ();
                if(xx<0||yy<0||zz<0||xx>=16||yy>=16||zz>=16){outer++;continue;}
                int next=(zz*16+yy)*16+xx;
                if(!solid[next]){if(cavity[next])inner++;else outer++;}
            }
        return new Measures(count/4096.0,inner/256.0,outer/256.0);
    }
    private static Direction axis(BlockState state) {
        if(state.getBlock() instanceof PipeValveBlock)return state.getValue(PipeValveBlock.FACING);
        if(state.getBlock() instanceof PipeScreenBlock)return state.getValue(PipeScreenBlock.FACING);
        throw new IllegalArgumentException("Not a pipe state");
    }
    private static VoxelShape shutter(Direction face,int opening) {
        int high=opening==0?12:8;
        return switch(face.getAxis()) {
            case X -> Block.box(7,4,4,8,high,12);
            case Y -> Block.box(4,7,4,high,8,12);
            case Z -> Block.box(4,4,7,12,high,8);
        };
    }
    private static VoxelShape union(VoxelShape a,VoxelShape b) { return Shapes.joinUnoptimized(a,b,BooleanOp.OR); }
    private static void check(int arms,int open) {
        if((arms&~63)!=0 || (open&~arms)!=0)throw new IllegalArgumentException("Invalid pipe ports");
    }
    private static VoxelShape outerArm(Direction d) { return switch(d) {
        case DOWN->Block.box(3,0,3,13,3,13);case UP->Block.box(3,13,3,13,16,13);
        case NORTH->Block.box(3,3,0,13,13,3);case SOUTH->Block.box(3,3,13,13,13,16);
        case WEST->Block.box(0,3,3,3,13,13);case EAST->Block.box(13,3,3,16,13,13);
    }; }
    private static VoxelShape innerArm(Direction d,boolean open) { return switch(d) {
        case DOWN->Block.box(4,open?0:1,4,12,4,12);case UP->Block.box(4,12,4,12,open?16:15,12);
        case NORTH->Block.box(4,4,open?0:1,12,12,4);case SOUTH->Block.box(4,4,12,12,12,open?16:15);
        case WEST->Block.box(open?0:1,4,4,4,12,12);case EAST->Block.box(12,4,4,open?16:15,12,12);
    }; }
    private static VoxelShape legacyArm(Direction d) { return switch(d) {
        case DOWN->Block.box(4,0,4,12,4,12);case UP->Block.box(4,12,4,12,16,12);
        case NORTH->Block.box(4,4,0,12,12,4);case SOUTH->Block.box(4,4,12,12,12,16);
        case WEST->Block.box(0,4,4,4,12,12);case EAST->Block.box(12,4,4,16,12,12);
    }; }
}

package mmm.simulation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import mmm.api.motion.MovingStructureApi.*;
import mmm.fluid.FluidDomainContracts.CollisionEdit;
import mmm.world.CollisionField;
import mmm.world.MovingBodyFluidCoupling;
import net.minecraft.core.BlockPos;

/** Exact fixed microcells owned by a stationary external structure. */
final class ExternalStructureGeometry {
    private ExternalStructureGeometry() { }

    static Map<Integer,Integer> masks(FluidRegion region, Handoff handoff,
            java.util.function.Predicate<FixedBlock> available) {
        var frame=handoff.structure();
        if(handoff.kind()==null || frame.retired() || !frame.source().equals(frame.target()) || handoff.blocks().isEmpty())
            throw new IllegalArgumentException("Handoff requires a stationary structure and its fixed blocks");
        var blocks=new HashSet<BlockPos>(); var keys=new HashSet<String>();
        for(var block:handoff.blocks()) {
            if(block.key()==null || block.key().isBlank() || !keys.add(block.key())
                    || !blocks.add(new BlockPos(block.x(),block.y(),block.z())))
                throw new IllegalArgumentException("Duplicate or missing fixed block identity");
            mmm.api.StableId.require(block.blockId(),128,"fixed block");
            if(!available.test(block)) throw new IllegalArgumentException("Handoff awaits available world blocks");
        }
        double micro=region.solverConfig().cellSize()/2.0;
        var origin=region.origin(); var result=new HashMap<Integer,Integer>();
        for(var piece:frame.pieces()) {
            var b=piece.bounds(); var p=frame.source();
            int x0=aligned((p.x()+b.minX()-origin.getX())/micro), x1=aligned((p.x()+b.maxX()-origin.getX())/micro);
            int y0=aligned((p.y()+b.minY()-origin.getY())/micro), y1=aligned((p.y()+b.maxY()-origin.getY())/micro);
            int z0=aligned((p.z()+b.minZ()-origin.getZ())/micro), z1=aligned((p.z()+b.maxZ()-origin.getZ())/micro);
            if(x0<0 || y0<0 || z0<0 || x1>region.sizeX()*2 || y1>region.sizeY()*2 || z1>region.sizeZ()*2)
                throw new IllegalArgumentException("Handoff structure must lie wholly inside its region");
            for(int z=z0;z<z1;z++)for(int y=y0;y<y1;y++)for(int x=x0;x<x1;x++) {
                int cell=region.grid().cell(x/2,y/2,z/2);
                if(cell<0 || !region.groupAvailable(cell))throw new IllegalArgumentException("Handoff crosses an unavailable cell");
                var pos=BlockPos.containing(origin.getX()+(x+.5)*micro,origin.getY()+(y+.5)*micro,origin.getZ()+(z+.5)*micro);
                if(!blocks.contains(pos))throw new IllegalArgumentException("Structure piece has no fixed block owner");
                int bit=1<<((x&1)+2*((y&1)+2*(z&1))), previous=result.getOrDefault(cell,0);
                if((previous&bit)!=0)throw new IllegalArgumentException("Structure pieces overlap");
                result.put(cell,previous|bit);
            }
        }
        if(result.isEmpty())throw new IllegalArgumentException("Handoff has no region geometry");
        return Map.copyOf(result);
    }

    static List<CollisionEdit> edits(FluidRegion region, CollisionField observed, Map<Integer,Integer> owned,
            HandoffKind kind, boolean replay) {
        var result=new ArrayList<CollisionEdit>(); var grid=region.grid();
        for(var entry:owned.entrySet()) {
            int cell=entry.getKey(), bits=entry.getValue(), x=grid.x(cell), y=grid.y(cell), z=grid.z(cell);
            int before=region.solidMicroMask(x,y,z), after;
            if(kind==HandoffKind.ASSEMBLE) {
                if(replay) continue;
                if((before&bits)!=bits)throw new IllegalArgumentException("Assembly source fixed geometry does not match the structure");
                after=before&~bits;
            } else {
                if(!replay && (before&bits)!=0)throw new IllegalArgumentException("Disassembly overlaps existing fixed geometry");
                after=before|bits;
                if(replay && before!=after)throw new IllegalArgumentException("Disassembly has neither its accepted bodies nor fixed geometry");
            }
            if(observed.microMask(x,y,z)!=after)throw new IllegalArgumentException("Handoff awaits its complete world geometry");
            if(before!=after)result.add(new CollisionEdit(x,y,z,after));
        }
        return List.copyOf(result);
    }

    static boolean touches(FluidRegion region, MovingBodyFluidCoupling.Bounds b, CollisionEdit edit) {
        double h=region.solverConfig().cellSize(), x=edit.x()*h,y=edit.y()*h,z=edit.z()*h;
        return b.maximumX()>x && b.minimumX()<x+h && b.maximumY()>y && b.minimumY()<y+h && b.maximumZ()>z && b.minimumZ()<z+h;
    }

    static void requireWholeThermalBlocks(FluidRegion region,Handoff handoff,Map<Integer,Integer> owned,
            WorldThermalEnvironment environment) {
        int scale=(int)Math.round(1/region.solverConfig().cellSize());
        for(var block:handoff.blocks()) {
            boolean thermal=environment.hasSolid(region.dimension().identifier().toString(),new BlockPos(block.x(),block.y(),block.z()));
            if(!thermal)continue;
            for(int z=0;z<scale;z++)for(int y=0;y<scale;y++)for(int x=0;x<scale;x++) {
                int cx=(block.x()-region.origin().getX())*scale+x,cy=(block.y()-region.origin().getY())*scale+y,cz=(block.z()-region.origin().getZ())*scale+z;
                int cell=region.grid().cell(cx,cy,cz);
                if(cell<0 || region.solidMicroMask(cx,cy,cz)!=owned.getOrDefault(cell,0))
                    throw new IllegalArgumentException("Thermal wall handoff requires its complete fixed geometry");
            }
        }
    }
    private static int aligned(double value) {
        double rounded=Math.rint(value);
        if(!Double.isFinite(value) || Math.abs(value-rounded)>1e-7 || rounded<Integer.MIN_VALUE || rounded>Integer.MAX_VALUE)
            throw new IllegalArgumentException("Handoff shape is not aligned to the fixed microgrid");
        return (int)rounded;
    }
}

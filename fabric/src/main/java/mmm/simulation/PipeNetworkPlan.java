package mmm.simulation;

import java.util.*;
import mmm.block.*;
import mmm.simulation.controller.FluidRegionFootprint;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

/** Discovers physical pipe openings and the existing owners at their endpoints. */
final class PipeNetworkPlan {
    record Plan(List<FluidRegion> sources,FluidRegionFootprint footprint) { }
    private PipeNetworkPlan() { }
    static final class UnloadedChunk extends IllegalStateException {
        UnloadedChunk(BlockPos pos){super("管路需要加载区块："+pos.toShortString());}
    }

    static int openings(BlockState state) {
        if(state.getBlock() instanceof PipeBlock)return PipeBlock.openFaces(state);
        if(state.getBlock() instanceof PipeValveBlock || state.getBlock() instanceof PipeScreenBlock) {
            var facing=state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING);
            return PipeGeometry.bit(facing)|PipeGeometry.bit(facing.getOpposite());
        }
        return 0;
    }
    static boolean pipe(BlockState state) {
        return state.getBlock() instanceof PipeBlock || state.getBlock() instanceof PipeValveBlock || state.getBlock() instanceof PipeScreenBlock;
    }

    static Plan discover(ServerLevel level,FluidRegion initial,FluidRegionManager manager) {
        return discover(level,initial,manager,level::hasChunkAt,pos->level.isInWorldBounds(pos)&&level.isInsideBuildHeight(pos));
    }
    static Plan discover(BlockGetter level,FluidRegion initial,FluidRegionManager manager,
                         java.util.function.Predicate<BlockPos> loaded,java.util.function.Predicate<BlockPos> inBounds) {
        var sources=new LinkedHashSet<FluidRegion>();
        var blocks=new HashSet<Long>();
        var touched=new HashSet<Long>();
        var pending=new ArrayDeque<BlockPos>();
        var visited=new HashSet<Long>();
        addSource(level,initial,sources,blocks,pending,loaded);
        long maximum=manager.limits().maximumCellsPerRegion()/8;
        while(!pending.isEmpty()) {
            var pos=pending.removeFirst();
            if(!visited.add(pos.asLong()))continue;
            if(!loaded.test(pos))throw new UnloadedChunk(pos);
            var state=level.getBlockState(pos);
            if(!pipe(state))continue;
            blocks.add(pos.asLong());touched.add(pos.asLong());
            if(blocks.size()>maximum)throw new FluidRegionFootprint.ExpansionBudgetExceededException();
            var owner=owner(manager,initial,pos);
            if(owner!=null)addSource(level,owner,sources,blocks,pending,loaded);
            int mask=openings(state);
            for(var face:Direction.values())if((mask&PipeGeometry.bit(face))!=0) {
                var next=pos.relative(face);
                if(!inBounds.test(next))continue;
                if(!loaded.test(next))throw new UnloadedChunk(next);
                var neighbor=level.getBlockState(next);
                if(pipe(neighbor)) {
                    if((openings(neighbor)&PipeGeometry.bit(face.getOpposite()))!=0)pending.add(next);
                } else if(openFace(level,next,face.getOpposite())) {
                    touched.add(next.asLong());
                    var endpoint=owner(manager,initial,next);
                    if(endpoint!=null)addSource(level,endpoint,sources,blocks,pending,loaded);
                    else blocks.add(next.asLong());
                }
            }
        }
        if(sources.size()==1 && blocks.equals(initial.footprint().interiorBlocks())
                && Collections.disjoint(touched,initial.footprint().returnedBlocks()))return null;
        if(blocks.size()>maximum)throw new FluidRegionFootprint.ExpansionBudgetExceededException();
        int minX=Integer.MAX_VALUE,minY=minX,minZ=minX,maxX=Integer.MIN_VALUE,maxY=maxX,maxZ=maxX;
        var chunks=new HashSet<Long>();
        for(long packed:blocks) {
            var pos=BlockPos.of(packed);minX=Math.min(minX,pos.getX());minY=Math.min(minY,pos.getY());minZ=Math.min(minZ,pos.getZ());
            maxX=Math.max(maxX,pos.getX());maxY=Math.max(maxY,pos.getY());maxZ=Math.max(maxZ,pos.getZ());
            chunks.add(net.minecraft.world.level.ChunkPos.pack(pos.getX()>>4,pos.getZ()>>4));
        }
        var old=initial.footprint();var returned=new HashSet<Long>();var atmosphere=new HashSet<Long>();var shell=new HashSet<Long>();
        for(var source:sources) {
            var sourceFootprint=source.footprint();
            returned.addAll(sourceFootprint.returnedBlocks());atmosphere.addAll(sourceFootprint.atmosphereBlocks());
            shell.addAll(sourceFootprint.poolShellBlocks());chunks.addAll(sourceFootprint.requiredChunks());
        }
        returned.removeAll(touched);
        for(var source:sources)for(long block:source.footprint().interiorBlocks())
            if(source.footprint().containsInterior(BlockPos.of(block)))returned.remove(block);
        var footprint=new FluidRegionFootprint(new BlockPos(minX,minY,minZ),new BlockPos(maxX,maxY,maxZ),blocks,shell,chunks,
                old.kind(),atmosphere,returned,old.name());
        return new Plan(List.copyOf(sources),footprint);
    }

    private static FluidRegion owner(FluidRegionManager manager,FluidRegion initial,BlockPos pos) {
        return manager.regions().stream().filter(r->r.dimension().equals(initial.dimension()) && r.footprint()!=null
                && r.footprint().containsInterior(pos)).findFirst().orElse(null);
    }
    private static void addSource(BlockGetter level,FluidRegion region,Set<FluidRegion> sources,Set<Long> blocks,Queue<BlockPos> pending,
                                  java.util.function.Predicate<BlockPos> loaded) {
        if(sources.contains(region))return;
        if(!sources.isEmpty() && sources.iterator().next().footprint().kind()!=region.footprint().kind())
            throw new IllegalArgumentException("选区与原版水池须分别连接，不能合并不同类型的区域");
        sources.add(region);
        if(!region.hasSelectedVolume())throw new IllegalStateException("管路端点需要选区区域");
        blocks.addAll(region.footprint().interiorBlocks());
        for(long packed:region.footprint().interiorBlocks()) {
            var pos=BlockPos.of(packed);
            if(!region.footprint().containsInterior(pos) || !loaded.test(pos))continue;
            if(pipe(level.getBlockState(pos)))pending.add(pos);
            for(var face:Direction.values()) {
                var neighbor=pos.relative(face);
                if(region.footprint().containsInterior(neighbor)||!loaded.test(neighbor))continue;
                var state=level.getBlockState(neighbor);
                if(pipe(state) && (openings(state)&PipeGeometry.bit(face.getOpposite()))!=0 && openFace(level,pos,face))pending.add(neighbor);
            }
        }
    }
    private static boolean openFace(BlockGetter level,BlockPos pos,Direction face) {
        var state=level.getBlockState(pos);
        var masks=MinecraftCollisionWorldPort.sampleBlockMasks(state.getCollisionShape(level,pos),2);
        int axis=face.getAxis()==Direction.Axis.X?0:face.getAxis()==Direction.Axis.Y?1:2;
        for(int a=1;a<=2;a++)for(int b=1;b<=2;b++) {
            int[] xyz=new int[3];xyz[axis]=face.getAxisDirection()==Direction.AxisDirection.POSITIVE?3:0;
            xyz[(axis+1)%3]=a;xyz[(axis+2)%3]=b;
            int cell=((xyz[2]/2)*2+xyz[1]/2)*2+xyz[0]/2,bit=(xyz[0]&1)+2*((xyz[1]&1)+2*(xyz[2]&1));
            if((masks[cell]&(1<<bit))==0)return true;
        }
        return false;
    }
}

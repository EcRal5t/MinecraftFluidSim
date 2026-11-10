package mmm.simulation;

import java.lang.reflect.Proxy;
import java.util.*;
import mmm.application.RegionCatalog;
import mmm.block.*;
import mmm.fluid.FluidSolverConfig;
import mmm.fluid.HostFluidState;
import mmm.simulation.controller.FluidRegionFootprint;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Discovery follows actual compatible openings and retains each source footprint. */
public final class PipeNetworkPlanValidation {
    private static final BlockPos START=new BlockPos(0,0,0);
    private PipeNetworkPlanValidation() { }
    public static void main(String[] args) {
        GpuControllerFixture.initializeItems(); GpuControllerFixture.initializeIndustrialBlocks();
        oneAndTwoRegions(); sealedAndIncompatible(); sparseSource(); portTargetsPipe(); unloadedUnchangedRegion();
        returnedPoolPositions(); mixedKindsRejected(); readinessWaits();
        System.out.println("PipeNetworkPlanValidation passed");
    }
    private static void unloadedUnchangedRegion() {
        var first=region(Set.of(START,new BlockPos(32,0,0)));var manager=manager(first);
        var world=(BlockGetter)Proxy.newProxyInstance(PipeNetworkPlanValidation.class.getClassLoader(),new Class<?>[]{BlockGetter.class},
                (proxy,method,args)->{
                    if(method.getName().equals("getBlockState")){
                        check(((BlockPos)args[0]).getX()<16,"read an unloaded chunk");return Blocks.AIR.defaultBlockState();
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        check(PipeNetworkPlan.discover(world,first,manager,p->p.getX()<16,p->true)==null,"unchanged partial region should keep running");
        long revision=manager.topologyRevision();new MinecraftWorldUpdates(manager).chunksChanged();
        check(manager.topologyRevision()>revision,"chunk load must invalidate pipe discovery");
    }
    private static void oneAndTwoRegions() {
        var first=region(Set.of(START));var second=region(Set.of(new BlockPos(4,0,0)));
        var manager=manager(first);var blocks=line();
        var extension=discover(blocks,first,manager);
        check(extension.sources().equals(List.of(first)),"single source");
        check(extension.footprint().interiorBlocks().equals(packed(0,1,2,3,4)),"single-source open end expansion");
        check(manager.add(second).accepted(),"second region admission");
        var merged=discover(blocks,first,manager);
        check(new HashSet<>(merged.sources()).equals(Set.of(first,second)),"both endpoint regions");
        check(merged.footprint().interiorBlocks().equals(packed(0,1,2,3,4)),"joined region footprint");
        check(merged.footprint().minimumInterior().equals(START)&&merged.footprint().maximumInterior().equals(new BlockPos(4,0,0)),"joined bounds");
    }
    private static void sealedAndIncompatible() {
        var first=region(Set.of(START));var second=region(Set.of(new BlockPos(4,0,0)));var manager=manager(first);
        check(manager.add(second).accepted(),"second region admission");
        var blocks=line();var end=new BlockPos(3,0,0);
        blocks.put(end,blocks.get(end).setValue(PipeBlock.port(Direction.WEST),PipeBlock.Port.SEALED));
        var sealed=discover(blocks,first,manager);
        check(sealed.sources().equals(List.of(first)) && sealed.footprint().interiorBlocks().equals(packed(0,1,2)),"sealed end crossed");
        blocks.put(end,MmmBlocks.PIPE_VALVE.defaultBlockState().setValue(PipeValveBlock.FACING,Direction.UP));
        var incompatible=discover(blocks,first,manager);
        check(incompatible.sources().equals(List.of(first)) && incompatible.footprint().interiorBlocks().equals(packed(0,1,2)),"side of valve treated as a port");
        blocks=line();blocks.put(new BlockPos(4,0,0),Blocks.STONE.defaultBlockState());
        var solid=discover(blocks,first,manager);
        check(solid.sources().equals(List.of(first)) && solid.footprint().interiorBlocks().equals(packed(0,1,2,3)),"solid wall treated as an open endpoint");
    }
    private static void sparseSource() {
        var extra=new BlockPos(0,0,2);var first=region(Set.of(START,extra));
        var second=region(Set.of(new BlockPos(4,0,0)));var manager=manager(first);
        check(manager.add(second).accepted(),"sparse second admission");
        var plan=discover(line(),first,manager);
        var expected=new HashSet<>(packed(0,1,2,3,4));expected.add(extra.asLong());
        check(new HashSet<>(plan.sources()).equals(Set.of(first,second)),"sparse source membership");
        check(plan.footprint().interiorBlocks().equals(expected),"sparse interior changed");
        check(plan.footprint().maximumInterior().equals(new BlockPos(4,0,2)),"sparse bounding box");
    }
    private static void portTargetsPipe() {
        var pipePos=new BlockPos(1,0,0);var region=region(Set.of(pipePos));
        int sides=PipeGeometry.bit(Direction.EAST)|PipeGeometry.bit(Direction.WEST);
        byte[] masks=MinecraftCollisionWorldPort.sampleBlockMasks(PipeGeometry.shell(sides),2);
        var host=region.editableHost();
        for(int z=0;z<2;z++)for(int y=0;y<2;y++)for(int x=0;x<2;x++)
            host.setSolidMicroMask(x,y,z,Byte.toUnsignedInt(masks[(z*2+y)*2+x]));
        var target=FluidWorldTargetResolver.resolvePortTarget(List.of(region),Level.OVERWORLD,new BlockPos(2,0,0),Direction.EAST,Direction.WEST);
        check(target!=null && target.interiorBlock().equals(pipePos),"port resolved its inner pipe block");
        int[] cell=target.cell(new mmm.api.transfer.TransferQuantity.Liquid(1L,java.util.OptionalDouble.empty()),false);
        check(cell!=null && cell[0]==1 && (region.solidMicroMask(cell[0],cell[1],cell[2])&0xaa)!=0xaa,
                "port did not select an open pipe contact cell");
    }
    private static void returnedPoolPositions() {
        var returned=new BlockPos(4,0,0);var untouched=new BlockPos(8,0,0);
        long extraChunk=net.minecraft.world.level.ChunkPos.pack(5,0);
        var first=region(Set.of(START,returned,untouched),mmm.model.region.RegionGeometry.RegionKind.POOL,
                Set.of(returned.asLong(),untouched.asLong()),Set.of(0L,extraChunk));
        var manager=manager(first);var plan=discover(line(),first,manager);
        check(!plan.footprint().returnedBlocks().contains(returned.asLong()) && plan.footprint().containsInterior(returned),
                "touched returned pool position was not reacquired");
        check(plan.footprint().returnedBlocks().contains(untouched.asLong()),"untouched returned position was reacquired");
        check(plan.footprint().requiredChunks().contains(extraChunk),"source shell chunk requirement was dropped");
        first.setPaused(false);
        check(!RegionMerge.ready(manager,plan,p->p.getX()!=80),"unloaded shell chunk did not hold the merge");
        var added=RegionMerge.newlyOwnedBlocks(plan.footprint(),plan.sources());
        check(added.contains(returned) && !added.contains(untouched),"newly acquired water positions differ");
        var second=region(Set.of(returned),mmm.model.region.RegionGeometry.RegionKind.POOL,Set.of(),Set.of(0L));
        check(manager.add(second).accepted(),"returned position could not be owned by another pool");
        plan=discover(line(),first,manager);
        check(plan.sources().contains(second) && plan.footprint().containsInterior(returned),
                "other pool's owned position remained marked returned");
        check(plan.footprint().returnedBlocks().contains(untouched.asLong()),"unrelated returned position changed");
        check(!RegionMerge.newlyOwnedBlocks(plan.footprint(),plan.sources()).contains(returned),
                "another owner's water was treated as a new world acquisition");
    }
    private static void mixedKindsRejected() {
        var selected=region(Set.of(START));
        var pool=region(Set.of(new BlockPos(4,0,0)),mmm.model.region.RegionGeometry.RegionKind.POOL,Set.of(),Set.of(0L));
        var manager=manager(selected);check(manager.add(pool).accepted(),"mixed fixture admission");
        for(var source:List.of(selected,pool)) {
            try { discover(line(),source,manager);throw new AssertionError("mixed region kinds were merged"); }
            catch(IllegalArgumentException expected) { check(expected.getMessage().contains("原版水池"),"mixed-kind failure missing explanation"); }
        }
    }
    private static void readinessWaits() {
        var first=region(Set.of(START));var second=region(Set.of(new BlockPos(4,0,0)));var manager=manager(first);
        check(manager.add(second).accepted(),"readiness fixture admission");
        var plan=discover(line(),first,manager);
        first.setPaused(false);second.setPaused(false);
        check(RegionMerge.ready(manager,plan,p->true),"ready regions did not admit joining");
        second.setPaused(true);
        check(!RegionMerge.ready(manager,plan,p->true) && !first.paused(),"paused endpoint changed the running primary");
        second.setPaused(false);
        check(!RegionMerge.ready(manager,plan,p->p.getX()!=4) && !first.paused(),"unloaded endpoint changed the running primary");
        check(RegionMerge.ready(manager,plan,p->true),"ready endpoint did not resume joining");
    }
    private static Map<BlockPos,BlockState> line() {
        var result=new HashMap<BlockPos,BlockState>();
        var state=MmmBlocks.METAL_PIPE.defaultBlockState().setValue(PipeBlock.port(Direction.WEST),PipeBlock.Port.OPEN)
                .setValue(PipeBlock.port(Direction.EAST),PipeBlock.Port.OPEN);
        for(int x=1;x<=3;x++)result.put(new BlockPos(x,0,0),state);
        return result;
    }
    private static PipeNetworkPlan.Plan discover(Map<BlockPos,BlockState> blocks,FluidRegion first,FluidRegionManager manager) {
        var world=(BlockGetter)Proxy.newProxyInstance(PipeNetworkPlanValidation.class.getClassLoader(),new Class<?>[]{BlockGetter.class},
                (proxy,method,args)->{
                    if(method.getName().equals("getBlockState"))return blocks.getOrDefault(args[0],Blocks.AIR.defaultBlockState());
                    if(method.getName().equals("getBlockEntity"))return null;
                    if(method.getName().equals("getFluidState"))return Blocks.AIR.defaultBlockState().getFluidState();
                    throw new UnsupportedOperationException(method.getName());
                });
        return PipeNetworkPlan.discover(world,first,manager,p->true,p->true);
    }
    private static FluidRegionManager manager(FluidRegion first) {
        var manager=new FluidRegionManager(new RegionCatalog.Limits(8,100000,200000));
        check(manager.add(first).accepted(),"first region admission");return manager;
    }
    private static FluidRegion region(Set<BlockPos> positions) {
        return region(positions,mmm.model.region.RegionGeometry.RegionKind.SELECTED,Set.of(),Set.of(0L));
    }
    private static FluidRegion region(Set<BlockPos> positions,mmm.model.region.RegionGeometry.RegionKind kind,
                                      Set<Long> returned,Set<Long> chunks) {
        int maxX=positions.stream().mapToInt(BlockPos::getX).max().orElseThrow();
        int maxZ=positions.stream().mapToInt(BlockPos::getZ).max().orElseThrow();
        int minX=positions.stream().mapToInt(BlockPos::getX).min().orElseThrow();
        int minZ=positions.stream().mapToInt(BlockPos::getZ).min().orElseThrow();
        var cells=new HashSet<Long>();for(var pos:positions)cells.add(pos.asLong());
        var footprint=new FluidRegionFootprint(new BlockPos(minX,0,minZ),new BlockPos(maxX,0,maxZ),cells,Set.of(),chunks,
                kind,Set.of(),returned,"test");
        var config=new FluidSolverConfig(.5f,9.81f,.05f,48,1e-4f,.002f);
        var domain=new HostFluidState(FluidRegionFactory.grid(footprint),config,mmm.fluid.TestFluidDefinitions.load());
        return new FluidRegion(UUID.randomUUID(),Level.OVERWORLD,footprint.minimumInterior(),domain,footprint);
    }
    private static Set<Long> packed(int... xs) { var result=new HashSet<Long>();for(int x:xs)result.add(new BlockPos(x,0,0).asLong());return result; }
    private static void check(boolean condition,String label) { if(!condition)throw new AssertionError(label); }
}

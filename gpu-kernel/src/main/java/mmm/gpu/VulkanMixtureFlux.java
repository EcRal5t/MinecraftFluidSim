package mmm.gpu;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import org.lwjgl.vulkan.VkCommandBuffer;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Finite-step donor and receiver allocation for the shared liquid and gas MAC face velocity. */
final class VulkanMixtureFlux implements AutoCloseable {
    static final class Layout {
        final int sx, sy, sz, cells, faces;
        final mmm.spatial.SpatialGrid grid;
        final VulkanLiquidStateLayout.Column header, scale, acceptedVelocity;
        final VulkanLiquidStateLayout.Column outTarget, inOffsets, inSource, inSide;
        final VulkanLiquidStateLayout.Column destination, exitSide, frontierA, frontierB, routeControl, inCounts, inCursors, scanWorkspace;
        final VulkanLiquidStateLayout.Column faceGeometry, fluxes, mixture, masks, targetSolid, gasStates, activityCells;
        final boolean gasEnabled;
        final boolean sealedExterior;
        final long endOffset;
        Layout(mmm.spatial.SpatialGrid grid,VulkanLiquidStateLayout.Column faceGeometry,VulkanLiquidStateLayout.Column fluxes,
               boolean gasEnabled,VulkanLiquidStateLayout.Column mixture,
               VulkanLiquidStateLayout.Column masks,VulkanLiquidStateLayout.Column targetSolid,VulkanLiquidStateLayout.Column gasStates,VulkanLiquidStateLayout.Column activityCells,boolean sealedExterior,long base,long alignment) {
            this.grid=grid;this.sx=grid.sizeX();this.sy=grid.sizeY();this.sz=grid.sizeZ();this.cells=grid.cellCount();
            this.faces=grid.faceCount();
            this.faceGeometry=faceGeometry;this.fluxes=fluxes;this.gasEnabled=gasEnabled;
            this.mixture=mixture;this.masks=masks;this.targetSolid=targetSolid;this.gasStates=gasStates;this.activityCells=activityCells;this.sealedExterior=sealedExterior;
            long cursor=align(base,alignment);header=new VulkanLiquidStateLayout.Column(cursor,40,4);cursor=header.offset()+header.bindingBytes();
            scale=new VulkanLiquidStateLayout.Column(align(cursor,alignment),2*cells,8);cursor=scale.offset()+scale.bindingBytes();
            acceptedVelocity=new VulkanLiquidStateLayout.Column(align(cursor,alignment),faces,8);cursor=acceptedVelocity.offset()+acceptedVelocity.bindingBytes();
            outTarget=new VulkanLiquidStateLayout.Column(align(cursor,alignment),Math.multiplyExact(cells,6),4);cursor=outTarget.offset()+outTarget.bindingBytes();
            inOffsets=new VulkanLiquidStateLayout.Column(align(cursor,alignment),Math.addExact(cells,1),4);cursor=inOffsets.offset()+inOffsets.bindingBytes();
            inSource=new VulkanLiquidStateLayout.Column(align(cursor,alignment),Math.multiplyExact(cells,6),4);cursor=inSource.offset()+inSource.bindingBytes();
            inSide=new VulkanLiquidStateLayout.Column(align(cursor,alignment),Math.multiplyExact(cells,6),4);cursor=inSide.offset()+inSide.bindingBytes();
            destination=new VulkanLiquidStateLayout.Column(align(cursor,alignment),cells,4);cursor=destination.offset()+destination.bindingBytes();
            exitSide=new VulkanLiquidStateLayout.Column(align(cursor,alignment),cells,4);cursor=exitSide.offset()+exitSide.bindingBytes();
            frontierA=new VulkanLiquidStateLayout.Column(align(cursor,alignment),cells,4);cursor=frontierA.offset()+frontierA.bindingBytes();
            frontierB=new VulkanLiquidStateLayout.Column(align(cursor,alignment),cells,4);cursor=frontierB.offset()+frontierB.bindingBytes();
            routeControl=new VulkanLiquidStateLayout.Column(align(cursor,alignment),3,4);cursor=routeControl.offset()+routeControl.bindingBytes();
            inCounts=new VulkanLiquidStateLayout.Column(align(cursor,alignment),cells,4);cursor=inCounts.offset()+inCounts.bindingBytes();
            inCursors=new VulkanLiquidStateLayout.Column(align(cursor,alignment),cells,4);cursor=inCursors.offset()+inCursors.bindingBytes();
            scanWorkspace=new VulkanLiquidStateLayout.Column(align(cursor,alignment),Math.toIntExact(VulkanPrefixScan.workspaceBytes(cells)/4),4);cursor=scanWorkspace.offset()+scanWorkspace.bindingBytes();
            endOffset=align(cursor,alignment);
        }
        VulkanLiquidStateLayout.Column header(){return header;}
        long endOffset(){return endOffset;}
        ByteBuffer headerBytes(){
            var b=ByteBuffer.allocateDirect(160).order(ByteOrder.LITTLE_ENDIAN);
            int[] values={sx,sy,sz,cells,faces,0,word(faceGeometry),word(fluxes),gasEnabled?1:-1,word(mixture),
                0,0,0,word(scale),word(acceptedVelocity),0,0,sealedExterior?1:0,0,0,word(masks),word(targetSolid),gasStates==null?-1:word(gasStates),word(outTarget),
                word(destination),word(exitSide),word(frontierA),word(frontierB),word(routeControl),word(inCounts),word(inCursors),word(inOffsets),word(inSource),word(inSide),word(activityCells)};
            for(int i=0;i<values.length;i++)b.putInt(i*4,values[i]);return b;
        }
        private static int word(VulkanLiquidStateLayout.Column c){return Math.toIntExact(c.offset()/4);}
        private static long align(long value,long alignment){return Math.addExact(value,alignment-1)&-alignment;}
    }
    private final FlipKernelBindings bindings;
    private final VulkanPrefixScan scan;
    VulkanMixtureFlux(VulkanDeviceContext context){
        String shader=VulkanLiquidPhaseRows.resource("mixture-flux.comp")
                .replace("// LOCAL_GAS_GRID",VulkanLiquidPhaseRows.resource("local-gas-grid.glsl"));
        bindings=new FlipKernelBindings(context,2,shader,32);
        try{scan=new VulkanPrefixScan(context);}catch(RuntimeException|LinkageError failure){bindings.close();throw failure;}
    }
    void rebindRanges(FlipKernelBindings.BufferRange arena,FlipKernelBindings.BufferRange errors){bindings.rebindRanges(List.of(arena,errors));scan.rebindRanges(arena,arena,arena,errors);}
    record RoutingTables(int[] targets,int[] offsets,int[] sources,int[] sides) { }
    static RoutingTables normalRoutes(Layout layout){
        int n=layout.cells;int[] targets=new int[6*n],counts=new int[n];
        for(int cell=0;cell<n;cell++)for(int side=0;side<6;side++){
            int next=layout.grid.neighbor(cell,side);targets[6*cell+side]=next;if(next>=0)counts[next]++;
        }
        int[] offsets=new int[n+1];for(int cell=0;cell<n;cell++)offsets[cell+1]=offsets[cell]+counts[cell];
        int[] sources=new int[6*n],sides=new int[6*n],cursor=offsets.clone();
        for(int cell=0;cell<n;cell++)for(int side=0;side<6;side++){
            int next=targets[6*cell+side];if(next<0)continue;int at=cursor[next]++;sources[at]=cell;sides[at]=side;
        }
        return new RoutingTables(targets,offsets,sources,sides);
    }
    static void initializeRoutes(ByteBuffer output,Layout layout){
        var r=normalRoutes(layout);var columns=new VulkanLiquidStateLayout.Column[]{layout.outTarget,layout.inOffsets,layout.inSource,layout.inSide};
        var rows=new int[][]{r.targets(),r.offsets(),r.sources(),r.sides()};
        for(int c=0;c<columns.length;c++)for(int i=0;i<rows[c].length;i++)output.putInt(Math.toIntExact(columns[c].offset()+4L*i),rows[c][i]);
    }
    /** Seeds closed cells adjacent to an active target pore on the source-open geometry. */
    void recordRouteSeeds(VkCommandBuffer command,Layout layout){
        try(var stack=stackPush()){
            bindings.bind(command,stack);var push=stack.malloc(32);
            stage(command,layout,4,1,0,0,0,stack,push);
            stage(command,layout,5,layout.cells,0,0,0,stack,push);
        }
    }
    /** A frontier is consumed only after its preceding queue count has returned. */
    void recordRouteFrontier(VkCommandBuffer command,Layout layout,int parity,int count){
        try(var stack=stackPush()){
            bindings.bind(command,stack);var push=stack.malloc(32);
            stage(command,layout,6,1,parity,0,0,stack,push);
            stage(command,layout,7,count,parity,0,0,stack,push);
        }
    }
    void recordExteriorSeeds(VkCommandBuffer command,Layout layout){
        try(var stack=stackPush()){
            bindings.bind(command,stack);var push=stack.malloc(32);
            stage(command,layout,4,1,0,0,0,stack,push);
            stage(command,layout,8,layout.cells,0,0,0,stack,push);
        }
    }
    int finishRouteSearch(VulkanRegionStorage.Candidate candidate,Layout layout,int frontier,int exteriorCandidates){
        int error=drainFrontier(candidate,layout,frontier);
        if(error!=0||exteriorCandidates==0)return error;
        var exterior=candidate.continueBatch((command,input,output,errors,staging,stack)->
                recordExteriorSeeds(command,layout),layout.routeControl.offset(),4);
        if(exterior.error()!=0)return exterior.error();
        return drainFrontier(candidate,layout,exterior.feedback().getInt(0));
    }
    private int drainFrontier(VulkanRegionStorage.Candidate candidate,Layout layout,int frontier){
        int parity=0;
        while(frontier>0){
            int current=parity,count=frontier;
            var next=candidate.continueBatch((command,input,output,errors,staging,stack)->
                    recordRouteFrontier(command,layout,current,count),layout.routeControl.offset()+4L*(current^1),4);
            if(next.error()!=0)return next.error();
            frontier=next.feedback().getInt(0);parity^=1;
        }
        return 0;
    }
    /** The same edge list publishes ordinary neighbors and closed-cell destinations. */
    void recordRoutes(VkCommandBuffer command,Layout layout,boolean moving){
        try(var stack=stackPush()){
            bindings.bind(command,stack);var push=stack.malloc(32);
            stage(command,layout,9,layout.cells,0,0,0,stack,push);
            stage(command,layout,10,layout.cells,moving?1:0,0,0,stack,push);
            scan.record(command,layout.cells,Layout.word(layout.inCounts),Layout.word(layout.inOffsets),Layout.word(layout.scanWorkspace));
            bindings.bind(command,stack);
            stage(command,layout,11,layout.cells,0,0,0,stack,push);
            stage(command,layout,12,layout.cells,0,0,0,stack,push);
        }
    }
    void recordAnalyze(VkCommandBuffer command,Layout layout,double h,double dt){
        try(var stack=stackPush()){
            bindings.bind(command,stack);var push=stack.malloc(32);
            stage(command,layout,1,layout.faces,0,h,dt,stack,push);
            stage(command,layout,2,layout.cells,0,h,dt,stack,push);
            stage(command,layout,3,layout.faces,0,h,dt,stack,push);
        }
    }
    private void stage(VkCommandBuffer command,Layout layout,int stage,int count,int argument,double h,double dt,
                       org.lwjgl.system.MemoryStack stack,ByteBuffer push){
        if(count==0)return;
        push.clear().putInt(Math.toIntExact(layout.header.offset()/4)).putInt(stage).putInt(count).putInt(argument).putDouble(h).putDouble(dt).flip();
        vkCmdPushConstants(command,bindings.pipelineLayout(),VK_SHADER_STAGE_COMPUTE_BIT,0,push);
        int groups=(count+127)/128;vkCmdDispatch(command,Math.min(groups,65535),(groups+65534)/65535,1);
        VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);
    }
    @Override public void close(){try{scan.close();}finally{bindings.close();}}
}

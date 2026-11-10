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
            long cursor=align(base,alignment);header=new VulkanLiquidStateLayout.Column(cursor,32,4);cursor=header.offset()+header.bindingBytes();
            scale=new VulkanLiquidStateLayout.Column(align(cursor,alignment),2*cells,8);cursor=scale.offset()+scale.bindingBytes();
            acceptedVelocity=new VulkanLiquidStateLayout.Column(align(cursor,alignment),faces,8);cursor=acceptedVelocity.offset()+acceptedVelocity.bindingBytes();
            outTarget=new VulkanLiquidStateLayout.Column(align(cursor,alignment),Math.multiplyExact(cells,6),4);cursor=outTarget.offset()+outTarget.bindingBytes();
            inOffsets=new VulkanLiquidStateLayout.Column(align(cursor,alignment),Math.addExact(cells,1),4);cursor=inOffsets.offset()+inOffsets.bindingBytes();
            inSource=new VulkanLiquidStateLayout.Column(align(cursor,alignment),Math.multiplyExact(cells,6),4);cursor=inSource.offset()+inSource.bindingBytes();
            inSide=new VulkanLiquidStateLayout.Column(align(cursor,alignment),Math.multiplyExact(cells,6),4);cursor=inSide.offset()+inSide.bindingBytes();
            endOffset=align(cursor,alignment);
        }
        VulkanLiquidStateLayout.Column header(){return header;}
        long endOffset(){return endOffset;}
        ByteBuffer headerBytes(){
            var b=ByteBuffer.allocateDirect(128).order(ByteOrder.LITTLE_ENDIAN);
            int[] values={sx,sy,sz,cells,faces,0,word(faceGeometry),word(fluxes),gasEnabled?1:-1,word(mixture),
                0,0,0,word(scale),word(acceptedVelocity),0,0,sealedExterior?1:0,0,0,word(masks),word(targetSolid),gasStates==null?-1:word(gasStates),word(outTarget)};
            for(int i=0;i<values.length;i++)b.putInt(i*4,values[i]);return b;
        }
        private static int word(VulkanLiquidStateLayout.Column c){return Math.toIntExact(c.offset()/4);}
        private static long align(long value,long alignment){return Math.addExact(value,alignment-1)&-alignment;}
    }
    private final FlipKernelBindings bindings;
    VulkanMixtureFlux(VulkanDeviceContext context){
        String shader=VulkanLiquidPhaseRows.resource("mixture-flux.comp")
                .replace("// LOCAL_GAS_GRID",VulkanLiquidPhaseRows.resource("local-gas-grid.glsl"));
        bindings=new FlipKernelBindings(context,2,shader,32);
    }
    void rebindRanges(FlipKernelBindings.BufferRange arena,FlipKernelBindings.BufferRange errors){bindings.rebindRanges(List.of(arena,errors));}
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
    /** Builds the shared source-to-target routes from the current source openings and target pores. */
    void prepareRoutes(VulkanRegionStorage.Candidate candidate,Layout layout,boolean moving){
        int n=layout.cells;var grid=layout.grid;
        int[] target=new int[Math.multiplyExact(n,6)];java.util.Arrays.fill(target,-1);
        int[] destination=new int[n],sideToExit=new int[n];java.util.Arrays.fill(destination,-1);java.util.Arrays.fill(sideToExit,-1);
        boolean[] closing=new boolean[n];
        if(moving){
            var masks=ByteBuffer.allocate(Math.multiplyExact(n,4)).order(ByteOrder.LITTLE_ENDIAN);
            var occupied=ByteBuffer.allocate(Math.multiplyExact(n,8)).order(ByteOrder.LITTLE_ENDIAN);
            var faces=ByteBuffer.allocate(Math.multiplyExact(layout.faces,32)).order(ByteOrder.LITTLE_ENDIAN);
            var activity=ByteBuffer.allocate(Math.multiplyExact(n,4)).order(ByteOrder.LITTLE_ENDIAN);
            candidate.read(layout.masks.offset(),masks);candidate.read(layout.targetSolid.offset(),occupied);candidate.read(layout.faceGeometry.offset(),faces);candidate.read(layout.activityCells.offset(),activity);
            int[] queue=new int[n];boolean[] reached=new boolean[n];int head=0,tail=0;
            for(int cell=0;cell<n;cell++){
                long pore=((long)Integer.bitCount(~masks.getInt(4*cell)&255)<<21)-occupied.getLong(8*cell);
                closing[cell]=pore==0;
                if(pore>0&&activity.getInt(4*cell)!=0){destination[cell]=cell;reached[cell]=true;queue[tail++]=cell;}
            }
            while(head<tail){
                int cell=queue[head++];
                for(int side=0;side<6;side++){
                    int next=grid.neighbor(cell,side);if(next<0||reached[next]||activity.getInt(4*next)==0)continue;
                    int face=grid.face(cell,side);if(face<0||faces.getDouble(32*face+16)<=0.0)continue;
                    destination[next]=destination[cell];sideToExit[next]=side^1;reached[next]=true;queue[tail++]=next;
                }
            }
            if(!layout.sealedExterior){
                head=0;tail=0;
                for(int cell=0;cell<n;cell++)if(!reached[cell]&&activity.getInt(4*cell)!=0)for(int side=0;side<6;side++){
                    int face=grid.face(cell,side);
                    if(grid.neighbor(cell,side)<0&&face>=0&&faces.getDouble(32*face+16)>0.0){
                        destination[cell]=-2;sideToExit[cell]=side;reached[cell]=true;queue[tail++]=cell;break;
                    }
                }
                while(head<tail){int cell=queue[head++];for(int side=0;side<6;side++){
                    int next=grid.neighbor(cell,side);if(next<0||reached[next]||activity.getInt(4*next)==0)continue;
                    int face=grid.face(cell,side);if(face<0||faces.getDouble(32*face+16)<=0.0)continue;
                    destination[next]=-2;sideToExit[next]=side^1;reached[next]=true;queue[tail++]=next;
                }}
            }
        }
        int[] counts=new int[n];
        for(int cell=0;cell<n;cell++)for(int side=0;side<6;side++){
            int next=closing[cell]?(side==sideToExit[cell]?destination[cell]:-1):grid.neighbor(cell,side);
            if(!closing[cell] && next>=0 && closing[next])next=-1;
            target[6*cell+side]=next;if(next>=0)counts[next]++;
        }
        int[] offsets=new int[n+1];for(int cell=0;cell<n;cell++)offsets[cell+1]=Math.addExact(offsets[cell],counts[cell]);
        int[] sources=new int[6*n],sides=new int[6*n],cursor=offsets.clone();
        for(int cell=0;cell<n;cell++)for(int side=0;side<6;side++){
            int next=target[6*cell+side];if(next<0)continue;int at=cursor[next]++;sources[at]=cell;sides[at]=side;
        }
        candidate.write(layout.outTarget,target);candidate.write(layout.inOffsets,offsets);
        candidate.write(layout.inSource,sources);candidate.write(layout.inSide,sides);
    }
    void recordAnalyze(VkCommandBuffer command,Layout layout,double h,double dt){
        try(var stack=stackPush()){
            bindings.bind(command,stack);var push=stack.malloc(32);
            stage(command,layout,1,layout.faces,h,dt,stack,push);
            stage(command,layout,2,layout.cells,h,dt,stack,push);
            stage(command,layout,3,layout.faces,h,dt,stack,push);
        }
    }
    private void stage(VkCommandBuffer command,Layout layout,int stage,int count,double h,double dt,
                       org.lwjgl.system.MemoryStack stack,ByteBuffer push){
        if(count==0)return;
        push.clear().putInt(Math.toIntExact(layout.header.offset()/4)).putInt(stage).putInt(count).putInt(0).putDouble(h).putDouble(dt).flip();
        vkCmdPushConstants(command,bindings.pipelineLayout(),VK_SHADER_STAGE_COMPUTE_BIT,0,push);
        int groups=(count+127)/128;vkCmdDispatch(command,Math.min(groups,65535),(groups+65534)/65535,1);
        VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);
    }
    @Override public void close(){bindings.close();}
}

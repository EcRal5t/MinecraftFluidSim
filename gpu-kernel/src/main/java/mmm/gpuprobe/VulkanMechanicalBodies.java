package mmm.gpuprobe;

import org.lwjgl.vulkan.VkCommandBuffer;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Connected liquid/gas mechanical bodies sharing force, transport and compression work. */
final class VulkanMechanicalBodies implements AutoCloseable {
    /** Contact components use active particle ordinals; -1 omits particle-particle contact links. */
    record GranularLinks(VulkanGranularStateLayout state,VulkanGranularPressure.Layout pressure,int contactComponentsWordOffset) { }
    record Layout(VulkanLiquidStateLayout source, VulkanLiquidRouteLayout routes,
                  int projectedFacesWordOffset, int gasCellNodesWordOffset, int gasNodeCapacity, int gasCountsWordOffset,
                  GranularLinks granular,
                  VulkanLiquidStateLayout.Column header, VulkanLiquidStateLayout.Column parents,
                  VulkanLiquidStateLayout.Column changed, VulkanLiquidStateLayout.Column counts,
                  VulkanLiquidStateLayout.Column offsets, VulkanLiquidStateLayout.Column cursors,
                  VulkanLiquidStateLayout.Column members, VulkanLiquidStateLayout.Column scanWorkspace, long endOffset) {
        int vertices() { return parents.count(); }
        int headerWordOffset() { return word(header); }
        int faces() { return source.column(VulkanLiquidStateLayout.Field.MOMENTUM_X).count()
                +source.column(VulkanLiquidStateLayout.Field.MOMENTUM_Y).count()+source.column(VulkanLiquidStateLayout.Field.MOMENTUM_Z).count(); }
        /** Body IDs are canonical vertex indices; CSR rows at non-root indices are empty.
         * Vertices [0,cells) are cells, then finite source gas nodes in their existing order.
         * gasCellNodes uses -1 absent, 0 ambient, and positive finite ordinals.
         */
        ByteBuffer headerBytes() { return headerBytes(source); }
        ByteBuffer headerBytes(VulkanLiquidStateLayout source) {
            var target=ByteBuffer.allocateDirect(128).order(ByteOrder.LITTLE_ENDIAN);
            int[] values={source.sizeX(),source.sizeY(),source.sizeZ(),source.cellCount(),vertices(),faces(),
                    word(source.column(VulkanLiquidStateLayout.Field.CELL_OFFSETS)),
                    word(routes.column(VulkanLiquidRouteLayout.Field.MASS_UNITS)),projectedFacesWordOffset,
                    gasCellNodesWordOffset,gasNodeCapacity,word(parents),word(changed),word(counts),word(offsets),word(cursors),word(members),gasCountsWordOffset};
            for(int i=0;i<values.length;i++)target.putInt(4*i,values[i]);
            target.putInt(108,word(routes.column(VulkanLiquidRouteLayout.Field.SOURCE_OFFSETS)));
            target.putInt(112,word(routes.column(VulkanLiquidRouteLayout.Field.SOURCE_MASKS)));
            target.putInt(72,granular==null?0:granular.state().particleCapacity());
            if(granular!=null){var state=granular.state();var pressure=granular.pressure();
                target.putInt(76,state.word(VulkanGranularStateLayout.Field.COUNTS));
                target.putInt(80,pressure.word(VulkanGranularPressure.Field.FACE_IDS));
                target.putInt(84,pressure.word(VulkanGranularPressure.Field.FACE_SHARES));
                target.putInt(88,pressure.word(VulkanGranularPressure.Field.DRAG_WEIGHTS));
                target.putInt(92,state.word(VulkanGranularStateLayout.Field.SUPPORT_CELLS));
                target.putInt(96,state.word(VulkanGranularStateLayout.Field.SUPPORT_QUOTAS));
                target.putInt(100,granular.contactComponentsWordOffset());
                target.putInt(104,pressure.word(VulkanGranularPressure.Field.DRAG_RESISTANCE));
            }
            return target;
        }
        void initializeHeader(ByteBuffer destination) {
            destination.duplicate().position(Math.toIntExact(header.offset())).put(headerBytes());
        }
    }
    static Layout layout(VulkanLiquidStateLayout source, VulkanLiquidRouteLayout routes,
                         int projectedFacesWordOffset, int gasCellNodesWordOffset, int gasNodeCapacity, int gasCountsWordOffset,
                         long scratchBase, long alignment) {
        return layout(source,routes,projectedFacesWordOffset,gasCellNodesWordOffset,gasNodeCapacity,gasCountsWordOffset,null,scratchBase,alignment);
    }
    static Layout layout(VulkanLiquidStateLayout source,VulkanLiquidRouteLayout routes,
                         int projectedFacesWordOffset,int gasCellNodesWordOffset,int gasNodeCapacity,int gasCountsWordOffset,
                         GranularLinks granular,long scratchBase,long alignment){
        if(gasNodeCapacity<0||(gasNodeCapacity>0&&(gasCellNodesWordOffset<0||gasCountsWordOffset<0))||projectedFacesWordOffset<0
                ||scratchBase<routes.endOffset()||alignment<8||(alignment&(alignment-1))!=0)
            throw new IllegalArgumentException("Invalid mechanical body layout");
        if(granular!=null&&(granular.state().cells()!=source.cellCount()||scratchBase<granular.pressure().endOffset()))throw new IllegalArgumentException("Granular mechanical links differ");
        int vertices=Math.addExact(Math.addExact(source.cellCount(),gasNodeCapacity),granular==null?0:granular.state().particleCapacity());long[] cursor={scratchBase};
        var header=column(cursor,32,alignment);var parents=column(cursor,vertices,alignment);
        var changed=column(cursor,1,alignment);var counts=column(cursor,vertices,alignment);
        var offsets=column(cursor,Math.addExact(vertices,1),alignment);var cursors=column(cursor,vertices,alignment);
        var members=column(cursor,vertices,alignment);
        var scan=column(cursor,Math.toIntExact(VulkanPrefixScan.workspaceBytes(vertices)/4),alignment);
        return new Layout(source,routes,projectedFacesWordOffset,gasCellNodesWordOffset,gasNodeCapacity,gasCountsWordOffset,granular,header,
                parents,changed,counts,offsets,cursors,members,scan,align(cursor[0],alignment));
    }
    private final FlipKernelBindings bindings;
    private final VulkanPrefixScan scan;
    VulkanMechanicalBodies(VulkanDeviceContext context) {
        bindings=new FlipKernelBindings(context,3,shader(),12);
        try{scan=new VulkanPrefixScan(context);}catch(RuntimeException|LinkageError failure){bindings.close();throw failure;}
    }
    void rebindRanges(FlipKernelBindings.BufferRange source,FlipKernelBindings.BufferRange candidate,FlipKernelBindings.BufferRange errors) {
        bindings.rebindRanges(List.of(source,candidate,errors));scan.rebindRanges(candidate,candidate,candidate,errors);
    }
    void recordInitialize(VkCommandBuffer command,Layout layout){stage(command,layout,0,layout.vertices());}
    /** Read changed after a batch; zero permits recordMembers. All links come from the same accepted step. */
    void recordConnectivity(VkCommandBuffer command,Layout layout,int iterations) {
        if(iterations<1)throw new IllegalArgumentException("Mechanical connectivity needs a positive batch");
        for(int i=0;i<iterations;i++){
            stage(command,layout,1,1);stage(command,layout,2,layout.faces());
            stage(command,layout,3,layout.source().cellCount());
            if(layout.granular()!=null)stage(command,layout,7,layout.granular().state().particleCapacity());
            stage(command,layout,4,layout.vertices());
        }
    }
    void recordMembers(VkCommandBuffer command,Layout layout) {
        stage(command,layout,5,layout.vertices());
        scan.record(command,layout.vertices(),word(layout.counts()),word(layout.offsets()),word(layout.scanWorkspace()));
        stage(command,layout,6,layout.vertices());
    }
    private void stage(VkCommandBuffer command,Layout layout,int stage,int count) {
        if(count==0)return;
        int groups=(count+127)/128;
        try(var stack=stackPush()){
            bindings.bind(command,stack);
            vkCmdPushConstants(command,bindings.pipelineLayout(),VK_SHADER_STAGE_COMPUTE_BIT,0,stack.ints(layout.headerWordOffset(),stage,count));
            vkCmdDispatch(command,Math.min(groups,65535),(groups+65534)/65535,1);
            VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);
        }
    }
    private static int word(VulkanLiquidStateLayout.Column column){return Math.toIntExact(column.offset()/4);}
    private static long align(long value,long alignment){return Math.addExact(value,alignment-1)&-alignment;}
    private static VulkanLiquidStateLayout.Column column(long[] cursor,int count,long alignment){
        var result=new VulkanLiquidStateLayout.Column(align(cursor[0],alignment),count,4);
        cursor[0]=Math.addExact(result.offset(),result.bindingBytes());return result;
    }
    private static String shader(){
        try(var stream=VulkanMechanicalBodies.class.getResourceAsStream("/assets/mmm_gpu_probe/shaders/mechanical-bodies.comp")){
            if(stream==null)throw new IllegalStateException("Mechanical body shader missing");
            return new String(stream.readAllBytes(),StandardCharsets.UTF_8);
        }catch(IOException failure){throw new IllegalStateException("Mechanical body shader unavailable",failure);}
    }
    @Override public void close(){try{scan.close();}finally{bindings.close();}}
}

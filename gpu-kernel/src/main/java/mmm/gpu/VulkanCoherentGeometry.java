package mmm.gpu;

import org.lwjgl.vulkan.VkCommandBuffer;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Cell membership and exact solid occupancy derived from the same continuous-phase inventory. */
final class VulkanCoherentGeometry implements AutoCloseable {
    private enum Header { CELL_COUNT, GROUP_CAPACITY, COUNTS, CELL, OCCUPIED_VOLUME, CELL_COUNTS, CELL_OFFSETS, CELL_CURSORS, MEMBERS, OCCUPIED_SVQ }
    private static String shaderLayout() { return VulkanShaderLayout.fields("H_", Header.values()); }

    record Layout(VulkanCoherentStateLayout state,VulkanLiquidStateLayout.Column header,VulkanLiquidStateLayout.Column counts,
                  VulkanLiquidStateLayout.Column offsets,VulkanLiquidStateLayout.Column cursors,VulkanLiquidStateLayout.Column members,
                  VulkanLiquidStateLayout.Column occupiedSvq,VulkanLiquidStateLayout.Column scan,long endOffset){
        ByteBuffer headerBytes(){
            var out=ByteBuffer.allocateDirect(64).order(ByteOrder.LITTLE_ENDIAN);
            int[] values = new int[Header.values().length];
            values[Header.CELL_COUNT.ordinal()] = state.cellCount();
            values[Header.GROUP_CAPACITY.ordinal()] = state.groupCapacity();
            values[Header.COUNTS.ordinal()] = state.word(VulkanCoherentStateLayout.Field.COUNTS);
            values[Header.CELL.ordinal()] = state.word(VulkanCoherentStateLayout.Field.CELL);
            values[Header.OCCUPIED_VOLUME.ordinal()] = state.word(VulkanCoherentStateLayout.Field.OCCUPIED_VOLUME_SVQ);
            values[Header.CELL_COUNTS.ordinal()] = word(counts);
            values[Header.CELL_OFFSETS.ordinal()] = word(offsets);
            values[Header.CELL_CURSORS.ordinal()] = word(cursors);
            values[Header.MEMBERS.ordinal()] = word(members);
            values[Header.OCCUPIED_SVQ.ordinal()] = word(occupiedSvq);

            for(int i=0;i<values.length;i++)out.putInt(4*i,values[i]);return out;
        }
        void initializeHeader(ByteBuffer target){target.duplicate().position(Math.toIntExact(header.offset())).put(headerBytes());}
    }
    static Layout layout(VulkanCoherentStateLayout state,long base,long alignment){
        if(base<state.endOffset()||alignment<8||(alignment&(alignment-1))!=0)throw new IllegalArgumentException("Continuous phase geometry overlaps inventory");
        long[] cursor={base};var header=column(cursor,16,4,alignment);int cells=state.cellCount();
        var counts=column(cursor,cells,4,alignment);var offsets=column(cursor,Math.addExact(cells,1),4,alignment);
        var cursors=column(cursor,cells,4,alignment);var members=column(cursor,state.groupCapacity(),4,alignment);
        var occupied=column(cursor,cells,8,alignment);var scan=column(cursor,Math.toIntExact(VulkanPrefixScan.workspaceBytes(cells)/4),4,alignment);
        return new Layout(state,header,counts,offsets,cursors,members,occupied,scan,align(cursor[0],alignment));
    }
    private final FlipKernelBindings bindings;private final VulkanPrefixScan scan;
    VulkanCoherentGeometry(VulkanDeviceContext context){
        bindings=new FlipKernelBindings(context,2,shader(),12);
        try{scan=new VulkanPrefixScan(context);}catch(RuntimeException|LinkageError failure){bindings.close();throw failure;}
    }
    void rebindRanges(FlipKernelBindings.BufferRange arena,FlipKernelBindings.BufferRange errors){
        bindings.rebindRanges(List.of(arena,errors));scan.rebindRanges(arena,arena,arena,errors);
    }
    void record(VkCommandBuffer command,Layout l){
        stage(command,l,0,l.state().cellCount());stage(command,l,1,l.state().groupCapacity());
        scan.record(command,l.state().cellCount(),word(l.counts()),word(l.offsets()),word(l.scan()));
        stage(command,l,2,l.state().groupCapacity());stage(command,l,3,l.state().cellCount());
    }
    private void stage(VkCommandBuffer command,Layout l,int stage,int count){
        if(count==0)return;try(var stack=stackPush()){
            bindings.bind(command,stack);vkCmdPushConstants(command,bindings.pipelineLayout(),VK_SHADER_STAGE_COMPUTE_BIT,0,stack.ints(word(l.header()),stage,count));
            int groups=(count+127)/128;vkCmdDispatch(command,Math.min(groups,65535),(groups+65534)/65535,1);
            VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);
        }
    }
    private static int word(VulkanLiquidStateLayout.Column c){return Math.toIntExact(c.offset()/4);}
    private static long align(long n,long a){return Math.addExact(n,a-1)&-a;}
    private static VulkanLiquidStateLayout.Column column(long[] cursor,int count,int width,long alignment){
        var c=new VulkanLiquidStateLayout.Column(align(cursor[0],alignment),count,width);cursor[0]=Math.addExact(c.offset(),c.bindingBytes());return c;
    }
    private static String shader(){
        try(var input=VulkanCoherentGeometry.class.getResourceAsStream("/assets/mmm_gpu_probe/shaders/coherent-geometry.comp")){
            if(input==null)throw new IllegalStateException("Continuous phase geometry shader missing");return new String(input.readAllBytes(),StandardCharsets.UTF_8).replace("// HOST_LAYOUT", shaderLayout());
        }catch(IOException failure){throw new IllegalStateException("Continuous phase geometry shader unavailable",failure);}
    }
    @Override public void close(){try{scan.close();}finally{bindings.close();}}
}

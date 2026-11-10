package mmm.gpu;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import org.lwjgl.vulkan.VkCommandBuffer;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Cached sparse device-row projection into a private world generation. */
final class VulkanEnvironmentProjection implements AutoCloseable {
    private final int walls,surfaces,air;
    private final FlipGpuResources resources;
    private final GpuStepScheduler scheduler;
    private final GpuBuffer mapping;
    private final FlipKernelBindings bindings;
    VulkanEnvironmentProjection(VulkanDeviceContext context, List<long[]> wallPairs,List<long[]> surfacePairs,long[] airWords) {
        walls=wallPairs.size();surfaces=surfacePairs.size();air=airWords.length;
        int count=2*(walls+surfaces)+air;var data=ByteBuffer.allocateDirect(Math.max(4,4*count)).order(ByteOrder.LITTLE_ENDIAN);
        int at=0;for(var pair:wallPairs){data.putInt(4*at++,Math.toIntExact(pair[0]/4));data.putInt(4*at++,Math.toIntExact(pair[1]/4));}
        for(var pair:surfacePairs){data.putInt(4*at++,Math.toIntExact(pair[0]/4));data.putInt(4*at++,Math.toIntExact(pair[1]/4));}
        for(long word:airWords)data.putInt(4*at++,Math.toIntExact(word/4));
        resources=new FlipGpuResources(context);GpuStepScheduler madeScheduler=null;FlipKernelBindings madeBindings=null;GpuBuffer madeMapping=null;
        try{
            madeScheduler=new GpuStepScheduler(context,resources,1024*1024);madeMapping=resources.addDeviceStorage(data.capacity());
            madeBindings=new FlipKernelBindings(context,4,VulkanLiquidPhaseRows.resource("environment-projection.comp"),32);
            try(var stack=stackPush()){
                var submission=madeScheduler.acquire();var command=madeScheduler.begin(submission,"environment-projection-map");
                for(int offset=0;offset<data.capacity();offset+=65536){int size=Math.min(65536,data.capacity()-offset);vkCmdUpdateBuffer(command,madeMapping.handle(),offset,data.slice(offset,size));}
                VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT,stack);
                madeScheduler.submitAndWait(stack,submission,"environment-projection-map");
            }
        }catch(RuntimeException|Error failure){if(madeBindings!=null)madeBindings.close();if(madeScheduler!=null)madeScheduler.close();resources.close();throw failure;}
        scheduler=madeScheduler;mapping=madeMapping;bindings=madeBindings;
    }
    void record(VkCommandBuffer command,GpuBuffer worldSource,GpuBuffer region,GpuBuffer worldCandidate,
                VulkanEnvironmentThermal.Layout layout,boolean export){
        int count=walls+surfaces+(export?air:0);if(count==0)return;
        bindings.rebind(List.of(worldSource,region,worldCandidate,mapping));
        try(var stack=stackPush()){
            bindings.bind(command,stack);
            vkCmdPushConstants(command,bindings.pipelineLayout(),VK_SHADER_STAGE_COMPUTE_BIT,0,stack.ints(export?1:0,count,walls,surfaces,air,
                    layout.word(VulkanEnvironmentThermal.Field.REGION_BEFORE),layout.word(VulkanEnvironmentThermal.Field.REGION_AFTER),0));
            int groups=(count+127)/128;vkCmdDispatch(command,Math.min(groups,65535),(groups+65534)/65535,1);
            VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT|VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT|VK_ACCESS_TRANSFER_READ_BIT,stack);
        }
    }
    @Override public void close(){try{bindings.close();}finally{try{scheduler.close();}finally{resources.close();}}}
}

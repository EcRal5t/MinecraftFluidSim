package mmm.gpu;

import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkQueryPoolCreateInfo;
import java.util.ArrayList;
import java.util.List;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memAllocLong;
import static org.lwjgl.system.MemoryUtil.memFree;
import static org.lwjgl.vulkan.VK10.*;

/** Optional benchmark-only timestamp pairs, recorded around stages without splitting submissions. */
final class VulkanStageTimings implements AutoCloseable {
    record Sample(String stage,long nanos){double milliseconds(){return nanos*1e-6;}}
    private final VulkanDeviceContext context;
    private final long pool;
    private final int capacity;
    private final ArrayList<String> labels=new ArrayList<>();
    VulkanStageTimings(VulkanDeviceContext context,int capacity){
        if(!context.hasTimestampQueries()||capacity<1)throw new IllegalArgumentException("Stage timings need timestamp support and positive capacity");
        this.context=context;this.capacity=capacity;
        try(var stack=stackPush()){
            var info=VkQueryPoolCreateInfo.calloc(stack).sType$Default().queryType(VK_QUERY_TYPE_TIMESTAMP).queryCount(Math.multiplyExact(2,capacity));
            var result=stack.mallocLong(1);VulkanDeviceContext.check(vkCreateQueryPool(context.device(),info,null,result),"vkCreateQueryPool(stages)");pool=result.get(0);
        }
    }
    /** Reset in the first submitted command buffer of a new measured step, after previous work completed. */
    void reset(VkCommandBuffer command){labels.clear();vkCmdResetQueryPool(command,pool,0,2*capacity);}
    void record(VkCommandBuffer command,String label,Runnable commands){
        if(labels.size()==capacity)throw new IllegalStateException("Stage timestamp capacity exhausted");
        int first=2*labels.size();labels.add(label);
        vkCmdWriteTimestamp(command,VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,pool,first);
        commands.run();
        vkCmdWriteTimestamp(command,VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,pool,first+1);
    }
    /** Call only after the final containing submission has completed. Pair intervals omit CPU submission gaps. */
    List<Sample> read(){
        if(labels.isEmpty())return List.of();
        var timestamps=memAllocLong(2*labels.size());
        try{
            VulkanDeviceContext.check(vkGetQueryPoolResults(context.device(),pool,0,2*labels.size(),timestamps,Long.BYTES,
                    VK_QUERY_RESULT_64_BIT|VK_QUERY_RESULT_WAIT_BIT),"vkGetQueryPoolResults(stages)");
            var result=new ArrayList<Sample>(labels.size());
            for(int i=0;i<labels.size();i++){
                long ticks=timestamps.get(2*i+1)-timestamps.get(2*i);
                if(context.timestampValidBits()!=64)ticks&=(1L<<context.timestampValidBits())-1;
                result.add(new Sample(labels.get(i),Math.max(0,Math.round(ticks*(double)context.timestampPeriodNanos()))));
            }
            return List.copyOf(result);
        }finally{memFree(timestamps);}
    }
    @Override public void close(){vkDestroyQueryPool(context.device(),pool,null);}
}

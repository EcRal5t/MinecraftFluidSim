package mmm.gpuprobe;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import org.lwjgl.vulkan.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Reuses immutable compute command streams while their descriptor buffers and layouts live. */
final class VulkanComputeCommandCache implements AutoCloseable {
    private final VulkanDeviceContext context;
    private final Map<Object,VkCommandBuffer> commands=new HashMap<>();
    private long pool;
    VulkanComputeCommandCache(VulkanDeviceContext context){this.context=context;}
    void record(VkCommandBuffer primary,Object key,Consumer<VkCommandBuffer> recorder){
        try(var stack=stackPush()){
            var secondary=commands.get(key);
            if(secondary==null){
                if(pool==VK_NULL_HANDLE){var info=VkCommandPoolCreateInfo.calloc(stack).sType$Default().queueFamilyIndex(context.queueFamily());
                    var out=stack.mallocLong(1);VulkanDeviceContext.check(vkCreateCommandPool(context.device(),info,null,out),"vkCreateCommandPool(compute-cache)");pool=out.get(0);}
                var allocate=VkCommandBufferAllocateInfo.calloc(stack).sType$Default().commandPool(pool).level(VK_COMMAND_BUFFER_LEVEL_SECONDARY).commandBufferCount(1);
                var pointer=stack.mallocPointer(1);VulkanDeviceContext.check(vkAllocateCommandBuffers(context.device(),allocate,pointer),"vkAllocateCommandBuffers(compute-cache)");
                secondary=new VkCommandBuffer(pointer.get(0),context.device());
                var inheritance=VkCommandBufferInheritanceInfo.calloc(stack).sType$Default();
                var begin=VkCommandBufferBeginInfo.calloc(stack).sType$Default().flags(VK_COMMAND_BUFFER_USAGE_SIMULTANEOUS_USE_BIT).pInheritanceInfo(inheritance);
                VulkanDeviceContext.check(vkBeginCommandBuffer(secondary,begin),"vkBeginCommandBuffer(compute-cache)");
                recorder.accept(secondary);VulkanDeviceContext.check(vkEndCommandBuffer(secondary),"vkEndCommandBuffer(compute-cache)");commands.put(key,secondary);
            }
            vkCmdExecuteCommands(primary,stack.pointers(secondary));
        }
    }
    @Override public void close(){if(pool!=VK_NULL_HANDLE)vkDestroyCommandPool(context.device(),pool,null);pool=VK_NULL_HANDLE;commands.clear();}
}

package mmm.gpuprobe;

import org.lwjgl.vulkan.*;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Immutable secondary recordings for one resident workspace. All submissions finish before disposal. */
final class VulkanCommandTemplates implements AutoCloseable {
    record Key(int stage,FlipKernelBindings.BufferIdentity candidate,FlipKernelBindings.BufferIdentity errors,int count,int maximum) {
        Key(int stage,GpuBuffer candidate,GpuBuffer errors,int count,int maximum){
            this(stage,new FlipKernelBindings.BufferIdentity(candidate),new FlipKernelBindings.BufferIdentity(errors),count,maximum);
        }
    }
    private final VulkanDeviceContext context;
    private final Map<Key,VkCommandBuffer> commands=new HashMap<>();
    private long pool;
    VulkanCommandTemplates(VulkanDeviceContext context){this.context=context;}
    void record(VkCommandBuffer primary,Key key,Consumer<VkCommandBuffer> recorder){
        var command=commands.get(key);
        if(command==null){
            try(var stack=stackPush()){
                if(pool==VK_NULL_HANDLE){
                    var info=VkCommandPoolCreateInfo.calloc(stack).sType$Default().queueFamilyIndex(context.queueFamily());
                    var out=stack.mallocLong(1);VulkanDeviceContext.check(vkCreateCommandPool(context.device(),info,null,out),"vkCreateCommandPool(templates)");pool=out.get(0);
                }
                var info=VkCommandBufferAllocateInfo.calloc(stack).sType$Default().commandPool(pool).level(VK_COMMAND_BUFFER_LEVEL_SECONDARY).commandBufferCount(1);
                var out=stack.mallocPointer(1);VulkanDeviceContext.check(vkAllocateCommandBuffers(context.device(),info,out),"vkAllocateCommandBuffers(template)");
                command=new VkCommandBuffer(out.get(0),context.device());
                var inheritance=VkCommandBufferInheritanceInfo.calloc(stack).sType$Default();
                var begin=VkCommandBufferBeginInfo.calloc(stack).sType$Default().pInheritanceInfo(inheritance);
                VulkanDeviceContext.check(vkBeginCommandBuffer(command,begin),"vkBeginCommandBuffer(template)");
                recorder.accept(command);VulkanDeviceContext.check(vkEndCommandBuffer(command),"vkEndCommandBuffer(template)");
                commands.put(key,command);
            }
        }
        try(var stack=stackPush()){vkCmdExecuteCommands(primary,stack.pointers(command));}
    }
    @Override public void close(){if(pool!=VK_NULL_HANDLE)vkDestroyCommandPool(context.device(),pool,null);pool=VK_NULL_HANDLE;commands.clear();}
}

package mmm.gpuprobe;

import mmm.inventory.LiquidQuantityCompute;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkMemoryBarrier;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Batched actual mass/species partition on the server's shared Vulkan device. */
final class VulkanLiquidQuantityCompute implements LiquidQuantityCompute,AutoCloseable {
    private final VulkanDeviceContext context;
    private final Thread owner=Thread.currentThread();
    private FlipGpuResources resources;
    private FlipKernelBindings bindings;
    private GpuStepScheduler scheduler;
    private int donorCapacity,partCapacity,inputCapacity,outputCapacity;
    private long batches,donors,uploadedBytes,downloadedBytes,gpuNanos;
    private boolean closed;
    VulkanLiquidQuantityCompute(VulkanDeviceContext context){this.context=context;ensureCapacity(1,1,1,1);}
    record Measurements(long batches,long donors,long uploadedBytes,long downloadedBytes,long gpuNanos) { }
    Measurements measurements(){return new Measurements(batches,donors,uploadedBytes,downloadedBytes,gpuNanos);}
    @Override public List<Partition> partition(List<Source> sources){
        checkOwner();if(sources.isEmpty())return List.of();
        int parts=0,inputs=0,outputs=0;
        for(var source:sources){
            int count=source.fractions().length;
            if(count<1||source.remainder()<0||source.remainder()>=count||source.massUnits()<0)
                throw new IllegalArgumentException("Invalid liquid donor partition");
            for(double fraction:source.fractions())if(!(fraction>=0&&fraction<=1))throw new IllegalArgumentException("Invalid liquid fraction");
            for(long amount:source.speciesAmounts())if(amount<0)throw new IllegalArgumentException("Negative liquid species");
            parts=Math.addExact(parts,count);inputs=Math.addExact(inputs,source.speciesAmounts().length+1);
            outputs=Math.addExact(outputs,Math.multiplyExact(count,source.speciesAmounts().length+1));
        }
        ensureCapacity(sources.size(),parts,inputs,outputs);
        try(MemoryStack stack=stackPush()){
            var slot=scheduler.acquire();var command=scheduler.begin(slot,"liquid-quantity-partition");
            var bytes=slot.stagingBuffer().bytes();
            int headerBytes=Math.multiplyExact(32,sources.size()),fractionBytes=Math.multiplyExact(8,parts),inputBytes=Math.multiplyExact(8,inputs);
            int fractionBase=headerBytes,inputBase=Math.addExact(headerBytes,fractionBytes);
            int part=0,input=0,output=0;
            for(int donor=0;donor<sources.size();donor++){
                var source=sources.get(donor);int count=source.fractions().length,header=32*donor;
                bytes.putInt(header,part).putInt(header+4,count).putInt(header+8,source.remainder()).putInt(header+12,input)
                        .putInt(header+16,source.speciesAmounts().length).putInt(header+20,output).putInt(header+24,0).putInt(header+28,0);
                for(double fraction:source.fractions())bytes.putDouble(fractionBase+8*part++,fraction);
                bytes.putLong(inputBase+8*input++,source.massUnits());
                for(long quantity:source.speciesAmounts())bytes.putLong(inputBase+8*input++,quantity);
                output+=count*(source.speciesAmounts().length+1);
            }
            copy(command,stack,slot.stagingBuffer(),resources.buffer(0),0,0,headerBytes);
            copy(command,stack,slot.stagingBuffer(),resources.buffer(1),fractionBase,0,fractionBytes);
            copy(command,stack,slot.stagingBuffer(),resources.buffer(2),inputBase,0,inputBytes);
            barrier(command,stack,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,VK_ACCESS_SHADER_READ_BIT);
            if(context.hasTimestampQueries()){
                vkCmdResetQueryPool(command,slot.timestampQueryPool(),0,2);
                vkCmdWriteTimestamp(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,slot.timestampQueryPool(),0);
            }
            bindings.bind(command,stack);
            vkCmdPushConstants(command,bindings.pipelineLayout(),VK_SHADER_STAGE_COMPUTE_BIT,0,stack.ints(sources.size()));
            vkCmdDispatch(command,(sources.size()+127)/128,1,1);
            if(context.hasTimestampQueries())vkCmdWriteTimestamp(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,slot.timestampQueryPool(),1);
            barrier(command,stack,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_SHADER_WRITE_BIT,VK_ACCESS_TRANSFER_READ_BIT);
            copy(command,stack,resources.buffer(3),slot.stagingBuffer(),0,0,8L*outputs);
            barrier(command,stack,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_PIPELINE_STAGE_HOST_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,VK_ACCESS_HOST_READ_BIT);
            scheduler.submitAndWait(stack,slot,"liquid-quantity-partition");
            var result=new ArrayList<Partition>(sources.size());output=0;
            for(var source:sources){
                int count=source.fractions().length;long[] masses=new long[count];
                for(int i=0;i<count;i++)masses[i]=bytes.getLong(8*output++);
                long[][] species=new long[source.speciesAmounts().length][count];
                for(var amounts:species)for(int i=0;i<count;i++)amounts[i]=bytes.getLong(8*output++);
                result.add(new Partition(masses,species));
            }
            batches++;donors+=sources.size();uploadedBytes+=(long)headerBytes+fractionBytes+inputBytes;downloadedBytes+=8L*outputs;
            if(context.hasTimestampQueries())gpuNanos+=scheduler.readTimestampNanos(slot);
            return List.copyOf(result);
        }
    }
    private void ensureCapacity(int donors,int parts,int inputs,int outputs){
        if(resources!=null&&donors<=donorCapacity&&parts<=partCapacity&&inputs<=inputCapacity&&outputs<=outputCapacity)return;
        releaseBuffers();donorCapacity=grow(donorCapacity,donors);partCapacity=grow(partCapacity,parts);
        inputCapacity=grow(inputCapacity,inputs);outputCapacity=grow(outputCapacity,outputs);
        try{
            resources=new FlipGpuResources(context);
            resources.addDeviceStorage(32L*donorCapacity);resources.addDeviceStorage(8L*partCapacity);
            resources.addDeviceStorage(8L*inputCapacity);resources.addDeviceStorage(8L*outputCapacity);
            if(bindings==null)bindings=new FlipKernelBindings(context,resources,shader(),4);
            else bindings.rebind(resources);
            scheduler=new GpuStepScheduler(context,resources,Math.max(32L*donorCapacity+8L*partCapacity+8L*inputCapacity,8L*outputCapacity));
        }catch(RuntimeException|LinkageError failure){
            try{release();}catch(RuntimeException|Error closing){failure.addSuppressed(closing);}throw failure;
        }
    }
    private static int grow(int capacity,int required){
        return required<=capacity?capacity:(int)Math.max(required,Math.min(Integer.MAX_VALUE,Math.max(64L,2L*capacity)));
    }
    private static String shader(){
        try(var stream=VulkanLiquidQuantityCompute.class.getResourceAsStream("/assets/mmm_gpu_probe/shaders/liquid-quantities.comp")){
            if(stream==null)throw new IllegalStateException("Liquid quantity shader missing");
            return new String(stream.readAllBytes(),StandardCharsets.UTF_8);
        }catch(IOException failure){throw new IllegalStateException("Liquid quantity shader unavailable",failure);}
    }
    private static void copy(VkCommandBuffer command,MemoryStack stack,GpuBuffer from,GpuBuffer to,long source,long target,long bytes){
        var region=VkBufferCopy.calloc(1,stack);region.get(0).srcOffset(source).dstOffset(target).size(bytes);
        vkCmdCopyBuffer(command,from.handle(),to.handle(),region);
    }
    private static void barrier(VkCommandBuffer command,MemoryStack stack,int from,int to,int source,int target){
        var memory=VkMemoryBarrier.calloc(1,stack);memory.get(0).sType$Default().srcAccessMask(source).dstAccessMask(target);
        vkCmdPipelineBarrier(command,from,to,0,memory,null,null);
    }
    private void checkOwner(){
        if(closed||Thread.currentThread()!=owner)throw new IllegalStateException("Liquid quantity compute requires its open owner thread");
    }
    private void release(){
        Throwable failure=null;
        try{if(scheduler!=null)scheduler.close();}catch(RuntimeException|Error closing){failure=closing;}finally{scheduler=null;}
        try{if(bindings!=null)bindings.close();}catch(RuntimeException|Error closing){if(failure==null)failure=closing;else failure.addSuppressed(closing);}finally{bindings=null;}
        try{if(resources!=null)resources.close();}catch(RuntimeException|Error closing){if(failure==null)failure=closing;else failure.addSuppressed(closing);}finally{resources=null;}
        if(failure instanceof RuntimeException runtime)throw runtime;if(failure instanceof Error error)throw error;
    }
    private void releaseBuffers(){
        Throwable failure=null;
        try{if(scheduler!=null)scheduler.close();}catch(RuntimeException|Error closing){failure=closing;}finally{scheduler=null;}
        try{if(resources!=null)resources.close();}catch(RuntimeException|Error closing){if(failure==null)failure=closing;else failure.addSuppressed(closing);}finally{resources=null;}
        if(failure instanceof RuntimeException runtime)throw runtime;if(failure instanceof Error error)throw error;
    }
    @Override public void close(){if(closed)return;checkOwner();try{release();}finally{closed=true;}}
}

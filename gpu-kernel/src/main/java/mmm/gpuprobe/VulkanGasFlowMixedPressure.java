package mmm.gpuprobe;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.List;
import org.lwjgl.vulkan.VkCommandBuffer;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Reliable FP32 correction CG with an FP64 pressure master and authoritative residual. */
final class VulkanGasFlowMixedPressure implements AutoCloseable {
    record Layout(VulkanGasFlow.Layout flow,
                  VulkanLiquidStateLayout.Column header,VulkanLiquidStateLayout.Column vectors,
                  VulkanLiquidStateLayout.Column trueResidual,VulkanLiquidStateLayout.Column coefficients,
                  VulkanLiquidStateLayout.Column tileProducts,VulkanLiquidStateLayout.Column partials,
                  VulkanLiquidStateLayout.Column state,VulkanLiquidStateLayout.Column peers,VulkanLiquidStateLayout.Column gains,long endOffset){
        ByteBuffer headerBytes(){var b=ByteBuffer.allocateDirect(128).order(ByteOrder.LITTLE_ENDIAN);
            int[] h={flow.headerWordOffset(),flow.nodes,word(vectors),word(trueResidual),word(coefficients),word(tileProducts),word(partials),word(state),(flow.nodes+127)/128,word(peers),word(gains)};
            for(int i=0;i<h.length;i++)b.putInt(4*i,h[i]);return b;}
    }
    static Layout layout(VulkanGasFlow.Layout flow,long base,long alignment){
        if(base<flow.endOffset())throw new IllegalArgumentException("Mixed pressure workspace overlaps graph");
        long[] cursor={base};var header=column(cursor,32,4,alignment);var vectors=column(cursor,Math.multiplyExact(flow.nodes,4),4,alignment);
        var residual=column(cursor,flow.nodes,8,alignment);var coefficients=column(cursor,Math.multiplyExact(flow.nodes,2),4,alignment);
        var tiles=column(cursor,flow.column(VulkanGasFlow.Field.TILE_SUMS).count(),4,alignment);
        var partials=column(cursor,Math.multiplyExact((flow.nodes+127)/128,4),8,alignment);var state=column(cursor,8,8,alignment);
        var peers=column(cursor,flow.column(VulkanGasFlow.Field.COLUMNS).count(),4,alignment);var gains=column(cursor,peers.count(),4,alignment);
        return new Layout(flow,header,vectors,residual,coefficients,tiles,partials,state,peers,gains,align(cursor[0],alignment));
    }
    private final FlipKernelBindings bindings;private final VulkanGasFlow flow;private final VulkanComputeCommandCache cache;
    private record Key(FlipKernelBindings.BufferIdentity arena,FlipKernelBindings.BufferIdentity errors,Object layout,long duration,int limit,int count){}
    private FlipKernelBindings.BufferRange arena,errors;
    VulkanGasFlowMixedPressure(VulkanDeviceContext context){bindings=new FlipKernelBindings(context,2,shader(),24);
        try{flow=new VulkanGasFlow(context);}catch(RuntimeException|Error e){bindings.close();throw e;}
        cache=new VulkanComputeCommandCache(context);}
    void rebindRanges(FlipKernelBindings.BufferRange arena,FlipKernelBindings.BufferRange errors){this.arena=arena;this.errors=errors;bindings.rebindRanges(List.of(arena,errors));flow.rebindRanges(arena,errors);}
    /** The caller has assembled the frozen graph and uploaded this header. */
    void recordInitialize(VkCommandBuffer command,Layout l,VulkanGasFlow.Parameters q,int limit){cached(command,l,q,limit,0);}
    void recordIterations(VkCommandBuffer command,Layout l,VulkanGasFlow.Parameters q,int limit,int count){cached(command,l,q,limit,count);}
    void recordFinish(VkCommandBuffer command,Layout l,VulkanGasFlow.Parameters q,int limit){cached(command,l,q,limit,-1);}
    private void cached(VkCommandBuffer primary,Layout l,VulkanGasFlow.Parameters q,int limit,int count){
        var key=new Key(new FlipKernelBindings.BufferIdentity(arena.buffer()),new FlipKernelBindings.BufferIdentity(errors.buffer()),l,Double.doubleToLongBits(q.duration()),limit,count);
        cache.record(primary,key,command->{
            var dispatch=new VulkanPressureKernel.Dispatch(arena.buffer(),arena.offset()+l.flow().column(VulkanGasFlow.Field.CONTROL).offset());
            var operator=flow.pressureOperator(l.flow(),q);
            if(count==0){stage(command,l,q,limit,0,-1);stage(command,l,q,limit,1,-1);stage(command,l,q,limit,19,6);return;}
            if(count<0){stage(command,l,q,limit,15,-1);stage(command,l,q,limit,17,2);stage(command,l,q,limit,18,3);operator.recordProductIndirect(command,false,dispatch);stage(command,l,q,limit,2,2);stage(command,l,q,limit,16,3);return;}
            for(int i=0;i<count;i++){
                stage(command,l,q,limit,17,2);stage(command,l,q,limit,18,3);operator.recordProductIndirect(command,false,dispatch);
                stage(command,l,q,limit,2,2);stage(command,l,q,limit,3,3);stage(command,l,q,limit,4,10);
                stage(command,l,q,limit,6,11);stage(command,l,q,limit,7,0);
                stage(command,l,q,limit,8,4);stage(command,l,q,limit,9,5);stage(command,l,q,limit,10,6);
                stage(command,l,q,limit,12,1);stage(command,l,q,limit,13,0);stage(command,l,q,limit,14,1);
            }
        });
    }
    private void stage(VkCommandBuffer command,Layout l,VulkanGasFlow.Parameters q,int iterations,int stage,int slot){try(var stack=stackPush()){
        bindings.bind(command,stack);var push=stack.malloc(24).putInt(word(l.header())).putInt(stage).putInt(iterations).putInt(0).putDouble(q.duration()).flip();
        vkCmdPushConstants(command,bindings.pipelineLayout(),VK_SHADER_STAGE_COMPUTE_BIT,0,push);
        if(slot>=0)new VulkanPressureKernel.Dispatch(arena.buffer(),arena.offset()+l.flow().column(VulkanGasFlow.Field.CONTROL).offset()).record(command,slot);
        else vkCmdDispatch(command,stage==0?(l.flow().nodes+127)/128:1,1,1);
        VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);}}
    private static int word(VulkanLiquidStateLayout.Column c){return Math.toIntExact(c.offset()/4);}private static long align(long n,long a){return Math.addExact(n,a-1)&-a;}
    private static VulkanLiquidStateLayout.Column column(long[] cursor,int n,int width,long alignment){var c=new VulkanLiquidStateLayout.Column(align(cursor[0],alignment),n,width);cursor[0]=c.offset()+c.bindingBytes();return c;}
    private static String shader(){try(var in=VulkanGasFlowMixedPressure.class.getResourceAsStream("/assets/mmm_gpu_probe/shaders/gas-flow-mixed-pressure.comp")){
        if(in==null)throw new IllegalStateException("Mixed pressure shader missing");return new String(in.readAllBytes(),StandardCharsets.UTF_8);
    }catch(IOException e){throw new IllegalStateException("Mixed pressure shader unavailable",e);}}
    @Override public void close(){try{cache.close();}finally{try{flow.close();}finally{bindings.close();}}}
}

package mmm.gpu;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import static org.lwjgl.vulkan.VK10.*;

/** Immutable device-to-device field transfer. Only equal-address mappings and strictly
 * adjacent spans merge; scratch columns between persistent fields remain outside the plan. */
final class VulkanBufferCopyPlan implements AutoCloseable {
    private record Span(long source,long target,long bytes){}
    static final class Builder {
        private final List<Span> spans=new ArrayList<>();
        Builder copy(VulkanLiquidStateLayout.Column from,VulkanLiquidStateLayout.Column to){
            if(from.elementBytes()!=to.elementBytes()||from.count()>to.count())throw new IllegalArgumentException("Persistent transfer shrinks a field");
            return copy(from.offset(),to.offset(),from.bytes());
        }
        Builder copy(VulkanGasStateLayout.Column from,VulkanGasStateLayout.Column to){
            if(from.elementBytes()!=to.elementBytes()||from.count()>to.count())throw new IllegalArgumentException("Persistent gas transfer shrinks a field");
            return copy(from.offset(),to.offset(),from.bytes());
        }
        Builder copy(long source,long target,long bytes){
            if(source<0||target<0||bytes<0||((source|target|bytes)&3)!=0)throw new IllegalArgumentException("Device transfer requires nonnegative word ranges");
            if(bytes>0){Math.addExact(source,bytes);Math.addExact(target,bytes);spans.add(new Span(source,target,bytes));}return this;
        }
        VulkanBufferCopyPlan build(VulkanDeviceContext context){
            var sorted=new ArrayList<>(spans);sorted.sort(Comparator.comparingLong(Span::target).thenComparingLong(Span::source));var merged=new ArrayList<Span>();
            for(var next:sorted){if(merged.isEmpty()){merged.add(next);continue;}int last=merged.size()-1;var previous=merged.get(last);long end=previous.target()+previous.bytes();
                if(next.target()<=end&&next.source()-next.target()==previous.source()-previous.target()){
                    merged.set(last,new Span(previous.source(),previous.target(),Math.max(end,next.target()+next.bytes())-previous.target()));
                }else{if(next.target()<end)throw new IllegalArgumentException("Persistent transfers assign different sources to the same destination");merged.add(next);}}
            return new VulkanBufferCopyPlan(context,List.copyOf(merged));
        }
    }
    private static final int WORDS_PER_TILE=4096;
    private final FlipGpuResources resources;
    private final FlipKernelBindings bindings;
    private final GpuBuffer tiles;
    private final ByteBuffer table;
    private final int tileCount;
    private final long bytes;
    private final int regions;
    private boolean uploaded;

    private VulkanBufferCopyPlan(VulkanDeviceContext context,List<Span> spans){
        long total=0;int count=0;
        for(var span:spans){total=Math.addExact(total,span.bytes());count=Math.addExact(count,Math.toIntExact((span.bytes()/4+WORDS_PER_TILE-1)/WORDS_PER_TILE));}
        bytes=total;regions=spans.size();tileCount=count;
        table=ByteBuffer.allocateDirect(Math.max(12,Math.multiplyExact(count,12))).order(ByteOrder.LITTLE_ENDIAN);
        for(var span:spans)for(long first=0;first<span.bytes()/4;first+=WORDS_PER_TILE){
            table.putInt(Math.toIntExact(span.source()/4+first)).putInt(Math.toIntExact(span.target()/4+first))
                    .putInt((int)Math.min(WORDS_PER_TILE,span.bytes()/4-first));
        }
        table.flip();resources=new FlipGpuResources(context);
        try{
            tiles=resources.addDeviceStorage(Math.max(12,table.remaining()));
            bindings=new FlipKernelBindings(context,3,"""
                    #version 450
                    layout(local_size_x=128) in;
                    layout(binding=0,std430) readonly buffer Source { uint sourceWords[]; };
                    layout(binding=1,std430) writeonly buffer Target { uint targetWords[]; };
                    layout(binding=2,std430) readonly buffer Tiles { uint tiles[]; };
                    layout(push_constant) uniform Push { uint count; } p;
                    void main(){
                        uint tile=gl_WorkGroupID.y*gl_NumWorkGroups.x+gl_WorkGroupID.x;
                        if(tile>=p.count)return;
                        uint src=tiles[3*tile],dst=tiles[3*tile+1],count=tiles[3*tile+2];
                        for(uint i=gl_LocalInvocationID.x;i<count;i+=128)targetWords[dst+i]=sourceWords[src+i];
                    }
                    """,4);
        }catch(RuntimeException|Error failure){resources.close();throw failure;}
    }
    long bytes(){return bytes;}int regions(){return regions;}
    void record(VkCommandBuffer command,GpuBuffer source,GpuBuffer target,MemoryStack stack){
        if(tileCount==0)return;
        if(!uploaded){
            for(int first=0;first<table.limit();first+=65536)
                vkCmdUpdateBuffer(command,tiles.handle(),first,table.slice(first,Math.min(65536,table.limit()-first)));
            VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT,stack);
            uploaded=true;
        }
        bindings.rebindRanges(List.of(new FlipKernelBindings.BufferRange(source),new FlipKernelBindings.BufferRange(target),new FlipKernelBindings.BufferRange(tiles)));
        bindings.bind(command,stack);vkCmdPushConstants(command,bindings.pipelineLayout(),VK_SHADER_STAGE_COMPUTE_BIT,0,stack.ints(tileCount));
        vkCmdDispatch(command,Math.min(tileCount,65535),(tileCount+65534)/65535,1);
        VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT|VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT|VK_ACCESS_TRANSFER_READ_BIT|VK_ACCESS_TRANSFER_WRITE_BIT,stack);
    }
    @Override public void close(){try{bindings.close();}finally{resources.close();}}
}

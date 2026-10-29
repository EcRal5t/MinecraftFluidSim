package mmm.gpu;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import static org.lwjgl.vulkan.VK10.vkCmdCopyBuffer;

/** Immutable device-to-device field transfer. Only equal-address mappings and strictly
 * adjacent spans merge; scratch columns between persistent fields remain outside the plan. */
final class VulkanBufferCopyPlan {
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
        VulkanBufferCopyPlan build(){
            var sorted=new ArrayList<>(spans);sorted.sort(Comparator.comparingLong(Span::target).thenComparingLong(Span::source));var merged=new ArrayList<Span>();
            for(var next:sorted){if(merged.isEmpty()){merged.add(next);continue;}int last=merged.size()-1;var previous=merged.get(last);long end=previous.target()+previous.bytes();
                if(next.target()<=end&&next.source()-next.target()==previous.source()-previous.target()){
                    merged.set(last,new Span(previous.source(),previous.target(),Math.max(end,next.target()+next.bytes())-previous.target()));
                }else{if(next.target()<end)throw new IllegalArgumentException("Persistent transfers assign different sources to the same destination");merged.add(next);}}
            return new VulkanBufferCopyPlan(List.copyOf(merged));
        }
    }
    private final List<Span> spans;private final long bytes;
    private VulkanBufferCopyPlan(List<Span> spans){this.spans=spans;long total=0;for(var span:spans)total=Math.addExact(total,span.bytes());bytes=total;}
    long bytes(){return bytes;}int regions(){return spans.size();}
    void record(VkCommandBuffer command,GpuBuffer source,GpuBuffer target,MemoryStack stack){
        if(spans.isEmpty())return;var copies=VkBufferCopy.calloc(spans.size(),stack);for(int i=0;i<spans.size();i++){var s=spans.get(i);copies.get(i).srcOffset(s.source()).dstOffset(s.target()).size(s.bytes());}
        vkCmdCopyBuffer(command,source.handle(),target.handle(),copies);
    }
}

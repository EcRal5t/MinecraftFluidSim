package mmm.gpuprobe;

import org.lwjgl.vulkan.VkCommandBuffer;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static mmm.gpuprobe.VulkanRegionStorage.barrier;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Partitions mass, energy and source-indexed species over the accepted compact volume routes. */
final class VulkanLiquidRouteQuantities implements AutoCloseable {
    record Layout(VulkanLiquidStateLayout source,VulkanLiquidRouteLayout routes,
                  int materialReferenceWordOffset,int speciesReferenceWordOffset,int speciesStride,
                  VulkanLiquidStateLayout.Column header,VulkanLiquidStateLayout.Column energyRemainders,long endOffset) {
        int headerWordOffset(){return word(header);}
        long bytes(){return endOffset-header.offset();}
        void initializeHeader(ByteBuffer destination){destination.duplicate().position(Math.toIntExact(header.offset())).put(headerBytes());}
        ByteBuffer headerBytes(){return headerBytes(source);}
        ByteBuffer headerBytes(VulkanLiquidStateLayout source){
            var target=ByteBuffer.allocateDirect(Math.toIntExact(header.bytes())).order(ByteOrder.LITTLE_ENDIAN);int[] values=new int[64];
            values[0]=source.stockCapacity();values[1]=routes.routeCapacity();values[2]=routes.speciesCapacity();
            values[3]=materialReferenceWordOffset;values[4]=speciesReferenceWordOffset;values[5]=speciesStride;
            values[6]=word(routes.column(VulkanLiquidRouteLayout.Field.SPECIES_REQUIRED));values[7]=word(energyRemainders);
            for(var field:VulkanLiquidStateLayout.Field.values())values[16+field.ordinal()]=word(source.column(field));
            for(var field:VulkanLiquidRouteLayout.Field.values())values[35+field.ordinal()]=word(routes.column(field));
            for(int i=0;i<values.length;i++)target.putInt(4*i,values[i]);return target;
        }
    }
    static Layout layout(VulkanLiquidStateLayout source,VulkanLiquidRouteLayout routes,
                         int materialReferenceWordOffset,int speciesReferenceWordOffset,int speciesStride,long scratchBase,long alignment){
        if(routes.sourceStockCapacity()<source.stockCapacity()||routes.speciesCapacity()<Math.multiplyExact(7,source.speciesCapacity())
                ||scratchBase<routes.endOffset()||alignment<8||(alignment&(alignment-1))!=0||speciesStride<0)
            throw new IllegalArgumentException("Invalid liquid quantity route layout");
        var header=new VulkanLiquidStateLayout.Column(align(scratchBase,alignment),64,4);
        var remainders=new VulkanLiquidStateLayout.Column(align(header.offset()+header.bytes(),alignment),Math.multiplyExact(source.stockCapacity(),8),8);
        return new Layout(source,routes,materialReferenceWordOffset,speciesReferenceWordOffset,speciesStride,header,remainders,align(remainders.offset()+remainders.bindingBytes(),alignment));
    }
    private final FlipKernelBindings bindings;
    VulkanLiquidRouteQuantities(VulkanDeviceContext context){bindings=new FlipKernelBindings(context,3,shader(),12);}
    void rebindRanges(FlipKernelBindings.BufferRange source,FlipKernelBindings.BufferRange candidate,FlipKernelBindings.BufferRange errors){bindings.rebindRanges(List.of(source,candidate,errors));}
    void record(VkCommandBuffer command,Layout layout){
        int count=layout.source().stockCapacity();if(count==0)return;int groups=(int)(((long)count+127)/128);
        try(var stack=stackPush()){
            bindings.bind(command,stack);vkCmdPushConstants(command,bindings.pipelineLayout(),VK_SHADER_STAGE_COMPUTE_BIT,0,stack.ints(layout.headerWordOffset(),0,count));
            vkCmdDispatch(command,Math.min(groups,65535),(groups+65534)/65535,1);
            barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);
        }
    }
    private static int word(VulkanLiquidStateLayout.Column column){return Math.toIntExact(column.offset()/4);}
    private static long align(long value,long alignment){return Math.addExact(value,alignment-1)&-alignment;}
    private static String shader(){try(var input=VulkanLiquidRouteQuantities.class.getResourceAsStream("/assets/mmm_gpu_probe/shaders/liquid-route-quantities.comp")){
        if(input==null)throw new IllegalStateException("Liquid route quantities shader missing");return new String(input.readAllBytes(),StandardCharsets.UTF_8);
    }catch(IOException failure){throw new IllegalStateException("Liquid route quantities shader unavailable",failure);}}
    @Override public void close(){bindings.close();}
}
package mmm.gpuprobe;

import org.lwjgl.vulkan.VkCommandBuffer;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Source-local dissipative heat follows accepted integer mass routes before posting to future stocks. */
final class VulkanMechanicalHeatTransport implements AutoCloseable {
    record Layout(VulkanLiquidStateLayout source,VulkanLiquidStateLayout target,VulkanLiquidRouteLayout routes,
                  int sourceHeatWord,VulkanLiquidStateLayout.Column header,VulkanLiquidStateLayout.Column routeHeat,
                  VulkanLiquidStateLayout.Column targetCellHeat,VulkanLiquidStateLayout.Column energyChanges,long endOffset) {
        ByteBuffer headerBytes(){return headerBytes(source);}
        ByteBuffer headerBytes(VulkanLiquidStateLayout sourceLayout){
            var out=ByteBuffer.allocateDirect(256).order(ByteOrder.LITTLE_ENDIAN);int[] data=new int[64];
            data[0]=sourceLayout.sizeX();data[1]=sourceLayout.sizeY();data[2]=sourceLayout.sizeZ();data[3]=sourceLayout.cellCount();
            data[4]=target.stockCapacity();data[5]=sourceHeatWord;data[6]=word(routes.column(VulkanLiquidRouteLayout.Field.MASS_UNITS));
            data[10]=word(routes.column(VulkanLiquidRouteLayout.Field.SOURCE_OFFSETS));data[11]=word(routes.column(VulkanLiquidRouteLayout.Field.SOURCE_MASKS));data[12]=word(routes.column(VulkanLiquidRouteLayout.Field.SLOTS));
            data[7]=word(routeHeat);data[8]=word(targetCellHeat);data[9]=word(energyChanges);
            for(var field:VulkanLiquidStateLayout.Field.values()){data[16+field.ordinal()]=word(sourceLayout.column(field));data[40+field.ordinal()]=word(target.column(field));}
            for(int i=0;i<data.length;i++)out.putInt(4*i,data[i]);return out;
        }
        void initializeHeader(ByteBuffer arena){arena.duplicate().position(Math.toIntExact(header.offset())).put(headerBytes());}
        /** Captures transport/allocation/posting roundoff together, including cancellation of large stored U. */
        List<VulkanEnergyLedger.Stream> roundoffStreams(){return List.of(new VulkanEnergyLedger.Stream(1,sourceHeatWord,source.cellCount(),2,-1),
                new VulkanEnergyLedger.Stream(1,word(energyChanges),energyChanges.count(),2,1));}
    }
    static Layout layout(VulkanLiquidStateLayout source,VulkanLiquidStateLayout target,VulkanLiquidRouteLayout routes,
                         int sourceHeatWord,long scratchBase,long alignment){
        if(source.cellCount()!=target.cellCount()||routes.sourceStockCapacity()<source.stockCapacity()||sourceHeatWord<0||scratchBase<Math.max(target.bytes(),routes.endOffset())
                ||alignment<8||(alignment&(alignment-1))!=0)throw new IllegalArgumentException("Mechanical heat transport layouts differ");
        long[] cursor={scratchBase};var header=column(cursor,64,4,alignment);var routeHeat=column(cursor,routes.routeCapacity(),8,alignment);
        var cellHeat=column(cursor,target.cellCount(),8,alignment);var changes=column(cursor,2*target.stockCapacity(),8,alignment);
        return new Layout(source,target,routes,sourceHeatWord,header,routeHeat,cellHeat,changes,align(cursor[0],alignment));
    }
    private final FlipKernelBindings bindings;
    VulkanMechanicalHeatTransport(VulkanDeviceContext context){bindings=new FlipKernelBindings(context,3,shader(),12);}
    void rebindRanges(FlipKernelBindings.BufferRange source,FlipKernelBindings.BufferRange candidate,FlipKernelBindings.BufferRange errors){bindings.rebindRanges(List.of(source,candidate,errors));}
    /** Run after accepted-route gather, before deriving the future caloric state or common force correction. */
    void record(VkCommandBuffer command,Layout layout){stage(command,layout,0,layout.target().stockCapacity());stage(command,layout,1,layout.source().cellCount());stage(command,layout,2,layout.target().cellCount());}
    private void stage(VkCommandBuffer command,Layout layout,int stage,int count){
        if(count==0)return;int groups=(count+127)/128;
        try(var stack=stackPush()){
            bindings.bind(command,stack);vkCmdPushConstants(command,bindings.pipelineLayout(),VK_SHADER_STAGE_COMPUTE_BIT,0,stack.ints(word(layout.header()),stage,count));
            vkCmdDispatch(command,Math.min(groups,65535),(groups+65534)/65535,1);
            VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);
        }
    }
    private static int word(VulkanLiquidStateLayout.Column column){return Math.toIntExact(column.offset()/4);}
    private static long align(long n,long alignment){return Math.addExact(n,alignment-1)&-alignment;}
    private static VulkanLiquidStateLayout.Column column(long[] cursor,int count,int width,long alignment){var column=new VulkanLiquidStateLayout.Column(align(cursor[0],alignment),count,width);cursor[0]=column.offset()+column.bindingBytes();return column;}
    private static String shader(){try(var input=VulkanMechanicalHeatTransport.class.getResourceAsStream("/assets/mmm_gpu_probe/shaders/mechanical-heat-transport.comp")){
        if(input==null)throw new IllegalStateException("Mechanical heat transport shader missing");return new String(input.readAllBytes(),StandardCharsets.UTF_8);
    }catch(IOException failure){throw new IllegalStateException("Mechanical heat transport shader unavailable",failure);}}
    @Override public void close(){bindings.close();}
}

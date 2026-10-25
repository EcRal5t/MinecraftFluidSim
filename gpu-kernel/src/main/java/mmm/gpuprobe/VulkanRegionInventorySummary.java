package mmm.gpuprobe;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Constant-size inventory/temperature summary; the committed source buffer remains read-only. */
final class VulkanRegionInventorySummary implements AutoCloseable {
    private final FlipGpuResources resources;private final GpuStepScheduler scheduler;private final FlipKernelBindings bindings;
    private GpuBuffer output;private long capacity;
    VulkanRegionInventorySummary(VulkanDeviceContext context){resources=new FlipGpuResources(context);
        try{scheduler=new GpuStepScheduler(context,resources,4096);
            try(var in=getClass().getResourceAsStream("/assets/mmm_gpu_probe/shaders/region-inventory-summary.comp")){
                if(in==null)throw new IllegalStateException("Inventory summary shader missing");bindings=new FlipKernelBindings(context,2,new String(in.readAllBytes(),StandardCharsets.UTF_8),16);
            }catch(java.io.IOException failure){throw new IllegalStateException(failure);}
        }catch(RuntimeException|Error failure){resources.close();throw failure;}}
    synchronized mmm.fluid.ResidentFluidState.InventorySummary capture(VulkanLiquidRegion.Capture capture,double cellSize,boolean thermalBound){
        var in=capture.readoutInputs();var liquid=in.liquid();var gran=in.granular();var coherent=in.coherent();var gas=in.gas();var devices=in.devices();
        int count=Math.max(1,liquid.stockCapacity());if(gran!=null)count=Math.max(count,gran.particleCapacity()+gran.depositCapacity());
        if(coherent!=null)count=Math.max(count,coherent.groupCapacity());if(gas!=null)count=Math.max(count,Math.max(gas.nodeCapacity(),gas.componentCapacity()));if(devices!=null)count=Math.max(count,devices.deviceCapacity());
        int groups=(count+127)/128;long bytes=512L+96L*groups;if(bytes>capacity){if(output!=null)resources.destroy(output);output=resources.addDeviceStorage(bytes);capacity=bytes;}
        int[] h=new int[32];java.util.Arrays.fill(h,-1);h[0]=count;h[1]=groups;
        h[2]=word(liquid.column(VulkanLiquidStateLayout.Field.COUNTS));h[3]=word(liquid.column(VulkanLiquidStateLayout.Field.NUMERIC_VOLUMES));h[4]=word(liquid.column(VulkanLiquidStateLayout.Field.MASS_UNITS));
        h[5]=word(liquid.column(VulkanLiquidStateLayout.Field.SPECIES_OFFSETS));h[6]=word(liquid.column(VulkanLiquidStateLayout.Field.SPECIES_AMOUNTS));
        if(gran!=null){h[7]=gran.word(VulkanGranularStateLayout.Field.COUNTS);h[8]=gran.particleCapacity();h[9]=gran.word(VulkanGranularStateLayout.Field.SOLID_VOLUME_SVQ);h[10]=gran.word(VulkanGranularStateLayout.Field.MASS_UNITS);}
        if(coherent!=null){h[11]=coherent.word(VulkanCoherentStateLayout.Field.COUNTS);h[12]=coherent.word(VulkanCoherentStateLayout.Field.OCCUPIED_VOLUME_SVQ);h[13]=coherent.word(VulkanCoherentStateLayout.Field.MASS_UNITS);}
        if(gas!=null){h[14]=word(gas.column(VulkanGasStateLayout.Field.COUNTS));h[15]=word(gas.column(VulkanGasStateLayout.Field.ROW_COMPONENT_OFFSETS));h[16]=word(gas.column(VulkanGasStateLayout.Field.STANDARD_VOLUME_NANOS));h[17]=word(gas.column(VulkanGasStateLayout.Field.MASS_UNITS));h[18]=word(in.gasThermal().states());}
        if(devices!=null){h[19]=devices.word(VulkanThermalDeviceLayout.Field.COUNTS);h[20]=devices.word(VulkanThermalDeviceLayout.Field.INTERNAL_ENERGY);}
        h[21]=in.liquidThermal()==null?-1:word(in.liquidThermal().header());h[22]=in.granularThermal()==null?-1:word(in.granularThermal().header());
        h[23]=in.coherentThermal()==null?-1:word(in.coherentThermal().header());h[24]=in.deviceThermal()==null?-1:word(in.deviceThermal().header());h[25]=thermalBound?1:0;h[28]=0;
        var header=ByteBuffer.allocateDirect(256).order(ByteOrder.LITTLE_ENDIAN);for(int i=0;i<h.length;i++)header.putInt(4*i,h[i]);header.putDouble(128,cellSize*cellSize*cellSize);
        bindings.rebindRanges(List.of(new FlipKernelBindings.BufferRange(capture.buffer()),new FlipKernelBindings.BufferRange(output)));
        try(var stack=stackPush()){
            var submission=scheduler.acquire();var command=scheduler.begin(submission,"region-inventory-summary");vkCmdUpdateBuffer(command,output.handle(),0,header);
            VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,VK_ACCESS_MEMORY_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT,stack);
            for(int stage=0;stage<2;stage++){bindings.bind(command,stack);vkCmdPushConstants(command,bindings.pipelineLayout(),VK_SHADER_STAGE_COMPUTE_BIT,0,stack.ints(stage,count,groups,0));vkCmdDispatch(command,stage==0?groups:1,1,1);
                VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,stage==0?VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT:VK_PIPELINE_STAGE_TRANSFER_BIT,stage==0?VK_ACCESS_SHADER_READ_BIT:VK_ACCESS_TRANSFER_READ_BIT,stack);}
            VulkanRegionStorage.copy(command,output,256,submission.stagingBuffer(),0,152,stack);scheduler.submitAndWait(stack,submission,"region-inventory-summary");
            var b=submission.stagingBuffer().bytes().order(ByteOrder.LITTLE_ENDIAN);if(b.getLong(136)!=0)throw new ArithmeticException("Device inventory summary overflow or invalid caloric state");
            double min=b.getDouble(112),max=b.getDouble(120),deviceEnergy=b.getDouble(128);if(!Double.isFinite(min)){min=Double.NaN;max=Double.NaN;}
            double total=thermalBound?capture.exactThermalInventory().toDouble()+deviceEnergy:Double.NaN;
            return new mmm.fluid.ResidentFluidState.InventorySummary(b.getDouble(0),b.getLong(8),b.getLong(16),b.getLong(24),b.getLong(32),Math.toIntExact(b.getLong(72)),Math.toIntExact(b.getLong(80)),
                    b.getLong(40),b.getLong(48),Math.toIntExact(b.getLong(88)),b.getLong(56),b.getLong(64),Math.toIntExact(b.getLong(96)),Math.toIntExact(b.getLong(104)),thermalBound,min,max,total,deviceEnergy);
        }
    }
    private static int word(VulkanLiquidStateLayout.Column c){return Math.toIntExact(c.offset()/4);}private static int word(VulkanGasStateLayout.Column c){return Math.toIntExact(c.offset()/4);}
    @Override public synchronized void close(){try{bindings.close();}finally{try{scheduler.close();}finally{resources.close();}}}
}

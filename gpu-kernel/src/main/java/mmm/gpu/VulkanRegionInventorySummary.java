package mmm.gpu;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Constant-size inventory/temperature summary; the committed source buffer remains read-only. */
final class VulkanRegionInventorySummary implements AutoCloseable {
    private enum Header {
        COUNT(0), GROUPS(1), LIQUID_COUNTS(2), LIQUID_VOLUMES(3),
        LIQUID_MASS(4), SPECIES_OFFSETS(5), SPECIES_AMOUNTS(6), GRAIN_COUNTS(7),
        PARTICLE_CAPACITY(8), GRAIN_VOLUME(9), GRAIN_MASS(10), COHERENT_COUNTS(11),
        COHERENT_VOLUME(12), COHERENT_MASS(13), GAS_COUNTS(14), GAS_COMPONENT_OFFSETS(15),
        GAS_VOLUME(16), GAS_MASS(17), GAS_THERMAL(18), DEVICE_COUNTS(19),
        DEVICE_ENERGY(20), LIQUID_THERMAL(21), GRAIN_THERMAL(22), COHERENT_THERMAL(23),
        DEVICE_THERMAL(24), THERMAL_BOUND(25), ERROR(28), CELL_VOLUME(32);
        final int word; Header(int word) { this.word = word; }
    }
    private enum Output { VOLUME, LIQUID_MASS, CHEMICAL_QUANTA, GRAIN_VOLUME, GRAIN_MASS, COHERENT_VOLUME, COHERENT_MASS, GAS_VOLUME, GAS_MASS, PARTICLES, DEPOSITS, COHERENT_GROUPS, GAS_NODES, DEVICES, MIN_TEMPERATURE, MAX_TEMPERATURE, DEVICE_ENERGY, ERROR, LIQUID_STOCKS }
    private static String shaderLayout() {
        var result = new StringBuilder();
        for (var field : Header.values()) result.append("#define H_").append(field.name()).append(' ').append(field.word).append('\n');
        return result + VulkanShaderLayout.fields("O_", Output.values()) + VulkanLiquidThermodynamics.shaderLayout();
    }

    private final FlipGpuResources resources;private final GpuStepScheduler scheduler;private final FlipKernelBindings bindings;
    private GpuBuffer output;private long capacity;
    VulkanRegionInventorySummary(VulkanDeviceContext context){resources=new FlipGpuResources(context);
        try{scheduler=new GpuStepScheduler(context,resources,4096);
            try(var in=getClass().getResourceAsStream("/assets/mmm_gpu_probe/shaders/region-inventory-summary.comp")){
                if(in==null)throw new IllegalStateException("Inventory summary shader missing");bindings=new FlipKernelBindings(context,2,new String(in.readAllBytes(),StandardCharsets.UTF_8).replace("// HOST_LAYOUT", shaderLayout()),16);
            }catch(java.io.IOException failure){throw new IllegalStateException(failure);}
        }catch(RuntimeException|Error failure){resources.close();throw failure;}}
    synchronized mmm.fluid.ResidentFluidState.InventorySummary capture(VulkanLiquidRegion.Capture capture,double cellSize,boolean thermalBound){
        var in=capture.readoutInputs();var liquid=in.liquid();var gran=in.granular();var coherent=in.coherent();var gas=in.gas();var devices=in.devices();
        int count=Math.max(1,liquid.stockCapacity());if(gran!=null)count=Math.max(count,gran.particleCapacity()+gran.depositCapacity());
        if(coherent!=null)count=Math.max(count,coherent.groupCapacity());if(gas!=null)count=Math.max(count,Math.max(gas.nodeCapacity(),gas.componentCapacity()));if(devices!=null)count=Math.max(count,devices.deviceCapacity());
        int groups=(count+127)/128;long bytes=512L+96L*groups;if(bytes>capacity){if(output!=null)resources.destroy(output);output=resources.addDeviceStorage(bytes);capacity=bytes;}
        int[] h=new int[32];java.util.Arrays.fill(h,-1);h[Header.COUNT.word]=count;h[Header.GROUPS.word]=groups;
        h[Header.LIQUID_COUNTS.word]=word(liquid.column(VulkanLiquidStateLayout.Field.COUNTS));h[Header.LIQUID_VOLUMES.word]=word(liquid.column(VulkanLiquidStateLayout.Field.NUMERIC_VOLUMES));h[Header.LIQUID_MASS.word]=word(liquid.column(VulkanLiquidStateLayout.Field.MASS_UNITS));
        h[Header.SPECIES_OFFSETS.word]=word(liquid.column(VulkanLiquidStateLayout.Field.SPECIES_OFFSETS));h[Header.SPECIES_AMOUNTS.word]=word(liquid.column(VulkanLiquidStateLayout.Field.SPECIES_AMOUNTS));
        if(gran!=null){h[Header.GRAIN_COUNTS.word]=gran.word(VulkanGranularStateLayout.Field.COUNTS);h[Header.PARTICLE_CAPACITY.word]=gran.particleCapacity();h[Header.GRAIN_VOLUME.word]=gran.word(VulkanGranularStateLayout.Field.SOLID_VOLUME_SVQ);h[Header.GRAIN_MASS.word]=gran.word(VulkanGranularStateLayout.Field.MASS_UNITS);}
        if(coherent!=null){h[Header.COHERENT_COUNTS.word]=coherent.word(VulkanCoherentStateLayout.Field.COUNTS);h[Header.COHERENT_VOLUME.word]=coherent.word(VulkanCoherentStateLayout.Field.OCCUPIED_VOLUME_SVQ);h[Header.COHERENT_MASS.word]=coherent.word(VulkanCoherentStateLayout.Field.MASS_UNITS);}
        if(gas!=null){h[Header.GAS_COUNTS.word]=word(gas.column(VulkanGasStateLayout.Field.COUNTS));h[Header.GAS_COMPONENT_OFFSETS.word]=word(gas.column(VulkanGasStateLayout.Field.ROW_COMPONENT_OFFSETS));h[Header.GAS_VOLUME.word]=word(gas.column(VulkanGasStateLayout.Field.STANDARD_VOLUME_NANOS));h[Header.GAS_MASS.word]=word(gas.column(VulkanGasStateLayout.Field.MASS_UNITS));h[Header.GAS_THERMAL.word]=word(in.gasThermal().states());}
        if(devices!=null){h[Header.DEVICE_COUNTS.word]=devices.word(VulkanThermalDeviceLayout.Field.COUNTS);h[Header.DEVICE_ENERGY.word]=devices.word(VulkanThermalDeviceLayout.Field.INTERNAL_ENERGY);}
        h[Header.LIQUID_THERMAL.word]=in.liquidThermal()==null?-1:word(in.liquidThermal().header());h[Header.GRAIN_THERMAL.word]=in.granularThermal()==null?-1:word(in.granularThermal().header());
        h[Header.COHERENT_THERMAL.word]=in.coherentThermal()==null?-1:word(in.coherentThermal().header());h[Header.DEVICE_THERMAL.word]=in.deviceThermal()==null?-1:word(in.deviceThermal().header());h[Header.THERMAL_BOUND.word]=thermalBound?1:0;h[Header.ERROR.word]=0;
        var header=ByteBuffer.allocateDirect(256).order(ByteOrder.LITTLE_ENDIAN);for(int i=0;i<h.length;i++)header.putInt(4*i,h[i]);header.putDouble(4*Header.CELL_VOLUME.word,cellSize*cellSize*cellSize);
        bindings.rebindRanges(List.of(new FlipKernelBindings.BufferRange(capture.buffer()),new FlipKernelBindings.BufferRange(output)));
        try(var stack=stackPush()){
            var submission=scheduler.acquire();var command=scheduler.begin(submission,"region-inventory-summary");vkCmdUpdateBuffer(command,output.handle(),0,header);
            VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,VK_ACCESS_MEMORY_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT,stack);
            for(int stage=0;stage<2;stage++){bindings.bind(command,stack);vkCmdPushConstants(command,bindings.pipelineLayout(),VK_SHADER_STAGE_COMPUTE_BIT,0,stack.ints(stage,count,groups,0));vkCmdDispatch(command,stage==0?groups:1,1,1);
                VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,stage==0?VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT:VK_PIPELINE_STAGE_TRANSFER_BIT,stage==0?VK_ACCESS_SHADER_READ_BIT:VK_ACCESS_TRANSFER_READ_BIT,stack);}
            VulkanRegionStorage.copy(command,output,4L*64,submission.stagingBuffer(),0,152,stack);scheduler.submitAndWait(stack,submission,"region-inventory-summary");
            var b=submission.stagingBuffer().bytes().order(ByteOrder.LITTLE_ENDIAN);if(b.getLong(8*Output.ERROR.ordinal())!=0)throw new ArithmeticException("Device inventory summary overflow or invalid caloric state");
            double min=b.getDouble(8*Output.MIN_TEMPERATURE.ordinal()),max=b.getDouble(8*Output.MAX_TEMPERATURE.ordinal()),deviceEnergy=b.getDouble(8*Output.DEVICE_ENERGY.ordinal());if(!Double.isFinite(min)){min=Double.NaN;max=Double.NaN;}
            double total=thermalBound?capture.exactThermalInventory().toDouble()+deviceEnergy:Double.NaN;
            return new mmm.fluid.ResidentFluidState.InventorySummary(b.getDouble(8*Output.VOLUME.ordinal()),b.getLong(8*Output.LIQUID_MASS.ordinal()),b.getLong(8*Output.CHEMICAL_QUANTA.ordinal()),b.getLong(8*Output.GRAIN_VOLUME.ordinal()),b.getLong(8*Output.GRAIN_MASS.ordinal()),Math.toIntExact(b.getLong(8*Output.PARTICLES.ordinal())),Math.toIntExact(b.getLong(8*Output.DEPOSITS.ordinal())),
                    b.getLong(8*Output.COHERENT_VOLUME.ordinal()),b.getLong(8*Output.COHERENT_MASS.ordinal()),Math.toIntExact(b.getLong(8*Output.COHERENT_GROUPS.ordinal())),b.getLong(8*Output.GAS_VOLUME.ordinal()),b.getLong(8*Output.GAS_MASS.ordinal()),Math.toIntExact(b.getLong(8*Output.GAS_NODES.ordinal())),Math.toIntExact(b.getLong(8*Output.DEVICES.ordinal())),thermalBound,min,max,total,deviceEnergy);
        }
    }
    private static int word(VulkanLiquidStateLayout.Column c){return Math.toIntExact(c.offset()/4);}private static int word(VulkanGasStateLayout.Column c){return Math.toIntExact(c.offset()/4);}
    @Override public synchronized void close(){try{bindings.close();}finally{try{scheduler.close();}finally{resources.close();}}}
}

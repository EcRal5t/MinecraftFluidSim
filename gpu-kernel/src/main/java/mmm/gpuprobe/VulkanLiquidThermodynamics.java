package mmm.gpuprobe;

import mmm.fluid.FluidMaterialRegistry;
import mmm.thermal.ThermalLimits;
import mmm.thermal.ThermalMaterialDefinition;
import mmm.thermal.ThermalRegistry;
import org.lwjgl.vulkan.VkCommandBuffer;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Derived caloric coordinates of transported stocks, for common mechanical heat allocation. */
final class VulkanLiquidThermodynamics implements AutoCloseable {
    record Tables(int[] offsets,double[] upper,double[] capacity,double[] referenceTemperature,double[] coldSensible) { }
    static Tables tables(FluidMaterialRegistry fluid,ThermalRegistry thermal) {
        var materials=new ArrayList<ThermalMaterialDefinition>();
        for(int i=0;i<fluid.materials().size();i++)materials.add(thermal.material(thermal.materialHandle(
                ThermalMaterialDefinition.CarrierKind.LIQUID,fluid.material(i).id())));
        return tables(materials);
    }
    static Tables tables(List<ThermalMaterialDefinition> materials) {
        int[] offsets=new int[materials.size()+1];double[] reference=new double[materials.size()],cold=new double[materials.size()];
        var upper=new ArrayList<Double>();var capacity=new ArrayList<Double>();
        for(int i=0;i<materials.size();i++) {
            var material=materials.get(i);reference[i]=material.referenceTemperatureK();
            cold[i]=material.sensibleSpecificEnergyAt(ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K);
            for(var segment:material.caloricSegments()){upper.add(segment.maximumTemperatureK());capacity.add(segment.specificHeatJPerKgK());}
            offsets[i+1]=upper.size();
        }
        return new Tables(offsets,upper.stream().mapToDouble(Double::doubleValue).toArray(),
                capacity.stream().mapToDouble(Double::doubleValue).toArray(),reference,cold);
    }
    record Layout(VulkanLiquidStateLayout liquid,Tables tables,int materialReferenceWordOffset,int speciesReferenceWordOffset,
                  int speciesStride,VulkanLiquidStateLayout.Column header,VulkanLiquidStateLayout.Column offsets,
                  VulkanLiquidStateLayout.Column upper,VulkanLiquidStateLayout.Column capacity,
                  VulkanLiquidStateLayout.Column referenceTemperature,VulkanLiquidStateLayout.Column coldSensible,
                  VulkanLiquidStateLayout.Column temperatures,VulkanLiquidStateLayout.Column heatCapacities,
                  VulkanLiquidStateLayout.Column coldEnergies,VulkanLiquidStateLayout.Column cellTemperatures,
                  VulkanLiquidStateLayout.Column cellHeatCapacities,long endOffset) {
        ByteBuffer headerBytes() {
            var out=ByteBuffer.allocateDirect(192).order(ByteOrder.LITTLE_ENDIAN);int[] data=new int[48];
            data[0]=liquid.stockCapacity();data[1]=tables.referenceTemperature().length;data[2]=speciesStride;
            data[3]=materialReferenceWordOffset;data[4]=speciesReferenceWordOffset;data[5]=word(offsets);data[6]=word(upper);
            data[7]=word(capacity);data[8]=word(referenceTemperature);data[9]=word(coldSensible);
            data[10]=word(temperatures);data[11]=word(heatCapacities);data[12]=word(coldEnergies);
            data[13]=word(cellTemperatures);data[14]=word(cellHeatCapacities);data[15]=liquid.cellCount();
            for(var field:VulkanLiquidStateLayout.Field.values())data[16+field.ordinal()]=word(liquid.column(field));
            for(int i=0;i<data.length;i++)out.putInt(4*i,data[i]);out.putDouble(176,ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K);return out;
        }
        void initializeHeaderAndTables(ByteBuffer destination) {
            destination.duplicate().position(Math.toIntExact(header.offset())).put(headerBytes());
            var out=destination.duplicate().order(ByteOrder.LITTLE_ENDIAN);
            for(int i=0;i<tables.offsets().length;i++)out.putInt(Math.toIntExact(offsets.offset()+4L*i),tables.offsets()[i]);
            write(out,upper,tables.upper());write(out,capacity,tables.capacity());
            write(out,referenceTemperature,tables.referenceTemperature());write(out,coldSensible,tables.coldSensible());
        }
    }
    static Layout layout(VulkanLiquidStateLayout liquid,Tables tables,int materialReferenceWordOffset,int speciesReferenceWordOffset,
                         int speciesStride,long scratchBase,long alignment) {
        if(scratchBase<liquid.bytes()||alignment<8||(alignment&(alignment-1))!=0||materialReferenceWordOffset<0
                ||speciesReferenceWordOffset<0||speciesStride<0)throw new IllegalArgumentException("Invalid liquid caloric layout");
        long[] cursor={scratchBase};var header=column(cursor,48,4,alignment);var offsets=column(cursor,tables.offsets().length,4,alignment);
        var upper=column(cursor,tables.upper().length,8,alignment);var capacity=column(cursor,tables.capacity().length,8,alignment);
        var reference=column(cursor,tables.referenceTemperature().length,8,alignment);var cold=column(cursor,tables.coldSensible().length,8,alignment);
        var temperatures=column(cursor,liquid.stockCapacity(),8,alignment);var capacities=column(cursor,liquid.stockCapacity(),8,alignment);
        var energies=column(cursor,liquid.stockCapacity(),8,alignment);
        var cellTemperatures=column(cursor,liquid.cellCount(),8,alignment);var cellCapacities=column(cursor,liquid.cellCount(),8,alignment);
        return new Layout(liquid,tables,materialReferenceWordOffset,speciesReferenceWordOffset,speciesStride,header,offsets,upper,capacity,
                reference,cold,temperatures,capacities,energies,cellTemperatures,cellCapacities,align(cursor[0],alignment));
    }
    private final FlipKernelBindings bindings;
    VulkanLiquidThermodynamics(VulkanDeviceContext context){bindings=new FlipKernelBindings(context,2,shader(),12);}
    void rebindRanges(FlipKernelBindings.BufferRange candidate,FlipKernelBindings.BufferRange errors){bindings.rebindRanges(List.of(candidate,errors));}
    void record(VkCommandBuffer command,Layout layout) {
        stage(command,layout,0,layout.liquid().stockCapacity());
        stage(command,layout,1,layout.liquid().cellCount());
    }
    private void stage(VkCommandBuffer command,Layout layout,int stage,int count) {
        int groups=(count+127)/128;if(groups==0)return;
        try(var stack=stackPush()) {
            bindings.bind(command,stack);
            vkCmdPushConstants(command,bindings.pipelineLayout(),VK_SHADER_STAGE_COMPUTE_BIT,0,stack.ints(word(layout.header()),count,stage));
            vkCmdDispatch(command,Math.min(groups,65535),(groups+65534)/65535,1);
            VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);
        }
    }
    private static int word(VulkanLiquidStateLayout.Column column){return Math.toIntExact(column.offset()/4);}
    private static long align(long value,long alignment){return Math.addExact(value,alignment-1)&-alignment;}
    private static VulkanLiquidStateLayout.Column column(long[] cursor,int count,int width,long alignment){
        var result=new VulkanLiquidStateLayout.Column(align(cursor[0],alignment),count,width);cursor[0]=result.offset()+result.bindingBytes();return result;
    }
    private static void write(ByteBuffer out,VulkanLiquidStateLayout.Column column,double[] values){
        for(int i=0;i<values.length;i++)out.putDouble(Math.toIntExact(column.offset()+8L*i),values[i]);
    }
    private static String shader(){
        try(var input=VulkanLiquidThermodynamics.class.getResourceAsStream("/assets/mmm_gpu_probe/shaders/liquid-thermodynamics.comp")){
            if(input==null)throw new IllegalStateException("Liquid thermodynamics shader missing");return new String(input.readAllBytes(),StandardCharsets.UTF_8);
        }catch(IOException failure){throw new IllegalStateException("Liquid thermodynamics shader unavailable",failure);}
    }
    @Override public void close(){bindings.close();}
}

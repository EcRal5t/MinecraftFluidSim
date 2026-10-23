package mmm.gpuprobe;

import mmm.fluid.FluidMaterialRegistry;
import mmm.fluid.MaterialRegistry;
import mmm.thermal.LiquidThermalExpansion;
import org.lwjgl.vulkan.VkCommandBuffer;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Material body forces use the same inventory, caloric state and dual face inertia as projection. */
final class VulkanLiquidForces implements AutoCloseable {
    record Tables(double[] referenceTemperatures,double[] expansionCoefficients) { }
    static Tables tables(FluidMaterialRegistry fluids,MaterialRegistry definitions){
        double[] reference=new double[fluids.size()],coefficient=new double[fluids.size()];
        for(int material=0;material<fluids.size();material++){
            String id=fluids.material(material).id();var expansion=definitions==null?LiquidThermalExpansion.require(id):definitions.entry(id).thermalExpansion();
            if(expansion==null)throw new IllegalArgumentException("Liquid expansion definition missing for "+id);
            reference[material]=expansion.referenceTemperatureK();coefficient[material]=expansion.volumetricCoefficientPerK();
        }
        return new Tables(reference,coefficient);
    }
    record Layout(VulkanLiquidStateLayout liquid,Tables tables,int cellTemperatureWordOffset,int facesWordOffset,
                  int flagsWordOffset,VulkanLiquidStateLayout.Column header,VulkanLiquidStateLayout.Column referenceTemperatures,
                  VulkanLiquidStateLayout.Column expansionCoefficients,VulkanLiquidStateLayout.Column cellBuoyancy,
                  VulkanLiquidStateLayout.Column cellMass,long endOffset) {
        ByteBuffer headerBytes(){
            var out=ByteBuffer.allocateDirect(192).order(ByteOrder.LITTLE_ENDIAN);int[] data=new int[48];
            data[0]=liquid.sizeX();data[1]=liquid.sizeY();data[2]=liquid.sizeZ();data[3]=liquid.cellCount();data[4]=tables.referenceTemperatures().length;
            data[5]=cellTemperatureWordOffset;data[6]=facesWordOffset;data[7]=flagsWordOffset;data[8]=word(referenceTemperatures);
            data[9]=word(expansionCoefficients);data[10]=word(cellBuoyancy);data[11]=word(cellMass);
            for(var field:VulkanLiquidStateLayout.Field.values())data[16+field.ordinal()]=word(liquid.column(field));
            for(int i=0;i<data.length;i++)out.putInt(4*i,data[i]);return out;
        }
        void initializeHeaderAndTables(ByteBuffer destination){
            destination.duplicate().position(Math.toIntExact(header.offset())).put(headerBytes());
            var out=destination.duplicate().order(ByteOrder.LITTLE_ENDIAN);
            for(int i=0;i<tables.referenceTemperatures().length;i++){
                out.putDouble(Math.toIntExact(referenceTemperatures.offset()+8L*i),tables.referenceTemperatures()[i]);
                out.putDouble(Math.toIntExact(expansionCoefficients.offset()+8L*i),tables.expansionCoefficients()[i]);
            }
        }
    }
    static Layout layout(VulkanLiquidStateLayout liquid,Tables tables,int cellTemperatureWordOffset,int facesWordOffset,
                         int flagsWordOffset,long scratchBase,long alignment){
        if(scratchBase<liquid.bytes()||alignment<8||(alignment&(alignment-1))!=0||cellTemperatureWordOffset<0||facesWordOffset<0
                ||tables.referenceTemperatures().length!=tables.expansionCoefficients().length)throw new IllegalArgumentException("Invalid resident force layout");
        long[] cursor={scratchBase};var header=column(cursor,48,4,alignment);var reference=column(cursor,tables.referenceTemperatures().length,8,alignment);
        var expansion=column(cursor,tables.expansionCoefficients().length,8,alignment);var buoyancy=column(cursor,liquid.cellCount(),8,alignment);
        var mass=column(cursor,liquid.cellCount(),8,alignment);
        return new Layout(liquid,tables,cellTemperatureWordOffset,facesWordOffset,flagsWordOffset,header,reference,expansion,buoyancy,mass,align(cursor[0],alignment));
    }
    private final FlipKernelBindings bindings;
    VulkanLiquidForces(VulkanDeviceContext context){bindings=new FlipKernelBindings(context,2,shader(),24);}
    void rebindRanges(FlipKernelBindings.BufferRange candidate,FlipKernelBindings.BufferRange errors){bindings.rebindRanges(List.of(candidate,errors));}
    /** Read the current source inventory before transport; this modifies only prepared face velocities. */
    void recordThermalBuoyancy(VkCommandBuffer command,Layout layout,double duration,double gravity){
        if(gravity==0)return;
        stage(command,layout,0,layout.liquid().cellCount(),duration,gravity);
        stage(command,layout,1,layout.liquid().column(VulkanLiquidStateLayout.Field.MOMENTUM_Y).count(),duration,gravity);
    }
    private void stage(VkCommandBuffer command,Layout layout,int stage,int count,double duration,double gravity){
        int groups=(count+127)/128;
        try(var stack=stackPush()){
            bindings.bind(command,stack);var push=stack.malloc(24).putInt(word(layout.header())).putInt(stage).putDouble(duration).putDouble(gravity).flip();
            vkCmdPushConstants(command,bindings.pipelineLayout(),VK_SHADER_STAGE_COMPUTE_BIT,0,push);
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
    private static String shader(){
        try(var input=VulkanLiquidForces.class.getResourceAsStream("/assets/mmm_gpu_probe/shaders/liquid-forces.comp")){
            if(input==null)throw new IllegalStateException("Liquid force shader missing");return new String(input.readAllBytes(),StandardCharsets.UTF_8);
        }catch(IOException failure){throw new IllegalStateException("Liquid force shader unavailable",failure);}
    }
    @Override public void close(){bindings.close();}
}

package mmm.gpu;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.List;
import org.lwjgl.vulkan.VkCommandBuffer;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Real retained gas caloric response in source-node order, before final inventory remapping. */
final class VulkanGasMechanicalHeat implements AutoCloseable {
    private enum Header { CANDIDATE_ENERGY, CANDIDATE_TEMPERATURE, CAPACITY, COLD_ENERGY, ENERGY_CHANGES }
    private static String shaderLayout() {
        return VulkanShaderLayout.fields("WORK_H_", Header.values());
    }

    record Layout(VulkanGasAdiabaticWork.Layout work,VulkanLiquidStateLayout.Column header,
                  VulkanLiquidStateLayout.Column capacity,VulkanLiquidStateLayout.Column coldEnergy,
                  VulkanLiquidStateLayout.Column energyChanges,long endOffset){
        ByteBuffer headerBytes(){var b=ByteBuffer.allocateDirect(32).order(ByteOrder.LITTLE_ENDIAN);
            b.putInt(4 * Header.CANDIDATE_ENERGY.ordinal(),word(work.candidateEnergy())).putInt(4 * Header.CANDIDATE_TEMPERATURE.ordinal(),word(work.candidateTemperature())).putInt(4 * Header.CAPACITY.ordinal(),word(capacity)).putInt(4 * Header.COLD_ENERGY.ordinal(),word(coldEnergy)).putInt(4 * Header.ENERGY_CHANGES.ordinal(),word(energyChanges));return b;}
    }
    static Layout layout(VulkanGasAdiabaticWork.Layout work,long base,long alignment){
        if(base<work.endOffset())throw new IllegalArgumentException("Gas mechanical caloric workspace overlaps work");
        int n=work.thermodynamics().gas().nodeCapacity();long[] cursor={base};var header=column(cursor,8,4,alignment);var capacity=column(cursor,n,8,alignment);var cold=column(cursor,n,8,alignment);var changes=column(cursor,2*n,8,alignment);
        return new Layout(work,header,capacity,cold,changes,align(cursor[0],alignment));
    }
    private final FlipKernelBindings bindings;
    VulkanGasMechanicalHeat(VulkanDeviceContext context){bindings=new FlipKernelBindings(context,2,shader(),16);}
    void rebindRanges(FlipKernelBindings.BufferRange arena,FlipKernelBindings.BufferRange errors){bindings.rebindRanges(List.of(arena,errors));}
    void recordPrepare(VkCommandBuffer command,Layout l){stage(command,l,0);}
    void recordRefresh(VkCommandBuffer command,Layout l){stage(command,l,1);}
    private void stage(VkCommandBuffer command,Layout l,int stage){int count=l.work().thermodynamics().gas().nodeCapacity();if(count==0)return;
        try(var stack=stackPush()){bindings.bind(command,stack);vkCmdPushConstants(command,bindings.pipelineLayout(),VK_SHADER_STAGE_COMPUTE_BIT,0,
                stack.ints(l.work().thermodynamics().headerWordOffset(),word(l.header()),count,stage));int groups=(count+127)/128;vkCmdDispatch(command,Math.min(groups,65535),(groups+65534)/65535,1);
            VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);}}
    private static String shader(){String eos=resource("gas-thermodynamics.comp");int first=eos.indexOf("bool inventory("),last=eos.indexOf("void derive(");
        if(first<0||last<=first)throw new IllegalStateException("Shared gas caloric functions are missing");return resource("gas-mechanical-heat.comp").replace("// HOST_LAYOUT", shaderLayout() + VulkanGasThermodynamics.shaderLayout()).replace("// GAS_CALORIC_FUNCTIONS",eos.substring(first,last));}
    private static String resource(String name){try(var in=VulkanGasMechanicalHeat.class.getResourceAsStream("/assets/mmm_gpu_probe/shaders/"+name)){
        if(in==null)throw new IllegalStateException("Gas mechanical shader missing: "+name);return new String(in.readAllBytes(),StandardCharsets.UTF_8);
    }catch(IOException e){throw new IllegalStateException("Gas mechanical shader unavailable",e);}}
    private static int word(VulkanLiquidStateLayout.Column c){return Math.toIntExact(c.offset()/4);}private static long align(long v,long a){return Math.addExact(v,a-1)&-a;}
    private static VulkanLiquidStateLayout.Column column(long[] cursor,int n,int w,long a){var c=new VulkanLiquidStateLayout.Column(align(cursor[0],a),n,w);cursor[0]=c.offset()+c.bindingBytes();return c;}
    @Override public void close(){bindings.close();}
}

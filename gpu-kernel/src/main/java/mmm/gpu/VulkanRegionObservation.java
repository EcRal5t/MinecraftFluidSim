package mmm.gpu;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Constant-size observations reduced from a pinned committed device generation. */
final class VulkanRegionObservation implements AutoCloseable {
    record Totals(double volume,double mass,double maximumSpeed,double granularSpeed,int stocks,int particles,int deposits,
                  double temperatureChange,double maximumHeat,double viscousHeat,int thermalSubsteps,int thermalContacts,double liquidForce,double granularForce,double potential,double gasCounterWork,
                  double sourceK,double finalK,double absoluteCorrection,double mixingHeat,int dirtyGranularColumns,int viscosityOutsideRange,int expansionDirections) { }
    private final FlipGpuResources resources;
    private final GpuStepScheduler scheduler;
    private final FlipKernelBindings bindings;
    private GpuBuffer output;
    private long capacity;
    VulkanRegionObservation(VulkanDeviceContext context) {
        resources=new FlipGpuResources(context);
        try {
            scheduler=new GpuStepScheduler(context,resources,4096);
            try(var input=getClass().getResourceAsStream("/assets/mmm_gpu_probe/shaders/region-observation.comp")) {
                if(input==null)throw new IllegalStateException("Region observation shader missing");
                bindings=new FlipKernelBindings(context,3,new String(input.readAllBytes(),StandardCharsets.UTF_8),128);
            } catch(java.io.IOException failure){throw new IllegalStateException(failure);}
        }catch(RuntimeException|Error failure){resources.close();throw failure;}
    }
    synchronized Totals capture(VulkanLiquidRegion.Capture capture) {
        var in=capture.readoutInputs();var l=in.liquid();var solid=in.granular();var thermal=in.thermal()==null?null:in.thermal().conduction();
        int faces=l.grid().faceCount();
        var energy=in.mechanicalEnergy();
        int n=Math.max(thermal==null?0:thermal.owners,Math.max(l.cellCount(),Math.max(faces,solid==null?0:solid.particleCapacity())));
        n=Math.max(n,Math.max(in.gas()==null?0:in.gas().nodeCapacity(),energy==null?0:energy.potentialTerms().count()/2));int groups=(n+127)/128;
        long bytes=256L+144L*groups;
        if(bytes>capacity){if(output!=null)resources.destroy(output);output=resources.addDeviceStorage(bytes);capacity=bytes;}
        var fixed=in.geometry().get(VulkanLiquidRegion.GeometryField.FIXED_MASKS);
        bindings.rebindRanges(List.of(new FlipKernelBindings.BufferRange(capture.buffer()),new FlipKernelBindings.BufferRange(output),
                new FlipKernelBindings.BufferRange(capture.buffer(),fixed.offset(),fixed.bindingBytes())));
        try(var stack=stackPush()) {
            var submission=scheduler.acquire();var command=scheduler.begin(submission,"region-observation");
            VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,VK_ACCESS_MEMORY_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT,stack);
            for(int stage=0;stage<2;stage++) {
                bindings.bind(command,stack);
                var push=stack.ints(stage,l.cellCount(),faces,groups,word(l,VulkanLiquidStateLayout.Field.COUNTS),
                        word(l,VulkanLiquidStateLayout.Field.CELL_OFFSETS),word(l,VulkanLiquidStateLayout.Field.NUMERIC_VOLUMES),
                        word(l,VulkanLiquidStateLayout.Field.MASS_UNITS),Math.toIntExact(in.geometry().get(VulkanLiquidRegion.GeometryField.FACES).offset()/4),
                        solid==null?-1:solid.word(VulkanGranularStateLayout.Field.COUNTS),solid==null?0:solid.word(VulkanGranularStateLayout.Field.VELOCITY),
                        capture.sealedExterior()?1:0,thermal==null?-1:thermal.word(VulkanThermalConduction.Field.COUNTS),
                        thermal==null?0:thermal.word(VulkanThermalConduction.Field.OWNER_INITIAL_T),thermal==null?0:thermal.word(VulkanThermalConduction.Field.TEMPERATURE),
                        thermal==null?0:thermal.word(VulkanThermalConduction.Field.ENERGY_CHANGE),thermal==null?0:thermal.word(VulkanThermalConduction.Field.MODE),
                        in.viscosity()==null?-1:Math.toIntExact(in.viscosity().column(VulkanLiquidViscosity.Field.SOURCE_CELL_HEAT).offset()/4),
                        energy==null?-1:Math.toIntExact(energy.faceTerms().offset()/4),energy==null?0:Math.toIntExact(energy.potentialTerms().offset()/4),energy==null?0:energy.potentialTerms().count()/2,
                        energy==null?0:Math.toIntExact(energy.granularTerms().offset()/4),energy==null?0:energy.granularTerms().count()/4,energy==null?-1:energy.gasWorkWordOffset(),
                        in.gas()==null?0:in.gas().nodeCapacity(),in.momentum()==null?-1:Math.toIntExact(in.momentum().cellHeat().offset()/4),solid==null?-1:solid.word(VulkanGranularStateLayout.Field.STABLE_AGE),
                        solid==null?0:solid.word(VulkanGranularStateLayout.Field.DEPOSIT_CELL),Math.toIntExact(in.geometry().get(VulkanLiquidRegion.GeometryField.EXTERIOR_MASKS).offset()/4),solid==null?0:solid.word(VulkanGranularStateLayout.Field.SUPPORT_CELLS),solid==null?0:solid.word(VulkanGranularStateLayout.Field.SUPPORT_QUOTAS),
                        in.viscosity()==null?-1:Math.toIntExact(in.viscosity().column(VulkanLiquidViscosity.Field.EXTRAPOLATED).offset()/4));
                vkCmdPushConstants(command,bindings.pipelineLayout(),VK_SHADER_STAGE_COMPUTE_BIT,0,push);
                vkCmdDispatch(command,stage==0?groups:1,1,1);
                VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,
                        stage==0?VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT:VK_PIPELINE_STAGE_TRANSFER_BIT,
                        stage==0?VK_ACCESS_SHADER_READ_BIT:VK_ACCESS_TRANSFER_READ_BIT,stack);
            }
            VulkanRegionStorage.copy(command,output,0,submission.stagingBuffer(),0,256,stack);
            scheduler.submitAndWait(stack,submission,"region-observation");
            var b=submission.stagingBuffer().bytes().order(ByteOrder.LITTLE_ENDIAN);
            return new Totals(b.getDouble(0),b.getDouble(8),b.getDouble(16),b.getDouble(24),(int)b.getDouble(32),(int)b.getDouble(40),(int)b.getDouble(48),b.getDouble(64),b.getDouble(72),b.getDouble(80),(int)b.getDouble(88),(int)b.getDouble(96),b.getDouble(128),b.getDouble(136),b.getDouble(144),b.getDouble(152),b.getDouble(160),b.getDouble(168),b.getDouble(176),b.getDouble(184),(int)b.getDouble(104),(int)b.getDouble(112),(int)b.getDouble(120));
        }
    }
    private static int word(VulkanLiquidStateLayout l,VulkanLiquidStateLayout.Field f){return Math.toIntExact(l.column(f).offset()/4);}
    @Override public void close(){try{bindings.close();}finally{try{scheduler.close();}finally{resources.close();}}}
}

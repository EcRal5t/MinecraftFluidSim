package mmm.gpuprobe;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Material/species totals and condensed LTE for one requested cell, without a region checkpoint. */
final class VulkanCellObservation implements AutoCloseable {
    private final VulkanDeviceContext context;private final FlipGpuResources resources;private final FlipKernelBindings bindings;
    private GpuStepScheduler scheduler;private GpuBuffer output;private long capacity;
    VulkanCellObservation(VulkanDeviceContext context){this.context=context;resources=new FlipGpuResources(context);
        try(var in=getClass().getResourceAsStream("/assets/mmm_gpu_probe/shaders/cell-observation.comp")){
            if(in==null)throw new IllegalStateException("Cell observation shader missing");bindings=new FlipKernelBindings(context,2,new String(in.readAllBytes(),StandardCharsets.UTF_8),16);
        }catch(java.io.IOException|RuntimeException|Error failure){resources.close();throw new IllegalStateException(failure);}}
    synchronized mmm.fluid.FluidCellInventoryView capture(VulkanLiquidRegion.Capture capture,VulkanFluidStateCodec codec,int x,int y,int z){
        var in=capture.readoutInputs();var l=in.liquid();if(x<0||y<0||z<0||x>=l.sizeX()||y>=l.sizeY()||z>=l.sizeZ())throw new IllegalArgumentException("Cell observation lies outside region");
        var registry=codec.template().chemicalRegistry();int materials=codec.fluids().size(),species=registry==null?0:registry.species().size();long bytes=288L+16L*materials+8L*species;
        if(bytes>capacity){if(scheduler!=null)scheduler.close();if(output!=null)resources.destroy(output);output=resources.addDeviceStorage(bytes);scheduler=new GpuStepScheduler(context,resources,bytes);capacity=bytes;}
        int[] h=new int[32];java.util.Arrays.fill(h,-1);h[0]=x+l.sizeX()*(y+l.sizeY()*z);h[1]=materials;h[2]=species;
        h[3]=word(l.column(VulkanLiquidStateLayout.Field.CELL_OFFSETS));h[4]=word(l.column(VulkanLiquidStateLayout.Field.MATERIALS));h[5]=word(l.column(VulkanLiquidStateLayout.Field.NUMERIC_VOLUMES));h[6]=word(l.column(VulkanLiquidStateLayout.Field.MASS_UNITS));
        h[7]=word(l.column(VulkanLiquidStateLayout.Field.SPECIES_OFFSETS));h[8]=word(l.column(VulkanLiquidStateLayout.Field.SPECIES_HANDLES));h[9]=word(l.column(VulkanLiquidStateLayout.Field.SPECIES_AMOUNTS));
        h[10]=codec.template().thermalRegistry()==null?-1:word(in.liquidThermal().header());
        if(in.granular()!=null){var g=in.granular();h[11]=g.word(VulkanGranularStateLayout.Field.COUNTS);h[12]=g.particleCapacity();h[13]=g.word(VulkanGranularStateLayout.Field.POSITION);h[14]=g.word(VulkanGranularStateLayout.Field.DEPOSIT_CELL);h[15]=word(in.granularThermal().header());}
        if(in.coherent()!=null){var c=in.coherent();h[16]=c.word(VulkanCoherentStateLayout.Field.COUNTS);h[17]=c.word(VulkanCoherentStateLayout.Field.CELL);h[18]=word(in.coherentThermal().header());}
        h[19]=l.sizeX();h[20]=l.sizeY();h[21]=l.sizeZ();h[22]=0;
        double size=codec.template().config().cellSize();var header=ByteBuffer.allocateDirect(256).order(ByteOrder.LITTLE_ENDIAN);for(int i=0;i<h.length;i++)header.putInt(4*i,h[i]);header.putDouble(128,size).putDouble(136,size*size*size);
        bindings.rebindRanges(List.of(new FlipKernelBindings.BufferRange(capture.buffer()),new FlipKernelBindings.BufferRange(output)));
        try(var stack=stackPush()){
            var submission=scheduler.acquire();var command=scheduler.begin(submission,"cell-observation");vkCmdUpdateBuffer(command,output.handle(),0,header);
            VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,VK_ACCESS_MEMORY_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT,stack);
            bindings.bind(command,stack);vkCmdPushConstants(command,bindings.pipelineLayout(),VK_SHADER_STAGE_COMPUTE_BIT,0,stack.ints(materials,species,0,0));vkCmdDispatch(command,(Math.max(1,Math.max(materials,species))+127)/128,1,1);
            VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_READ_BIT,stack);
            VulkanRegionStorage.copy(command,output,0,submission.stagingBuffer(),0,bytes,stack);scheduler.submitAndWait(stack,submission,"cell-observation");var b=submission.stagingBuffer().bytes().order(ByteOrder.LITTLE_ENDIAN);
            if(b.getInt(88)!=0)throw new ArithmeticException("Cell observation inventory overflow or invalid thermal state");double volume=b.getDouble(256),mass=mmm.units.Mass.kilograms(b.getLong(264));if(volume<=0||mass<=0)return null;
            var mat=new java.util.ArrayList<mmm.fluid.FluidCellInventoryView.MaterialAmount>();for(int m=0;m<materials;m++){int off=288+16*m;double v=b.getDouble(off);if(v>0)mat.add(new mmm.fluid.FluidCellInventoryView.MaterialAmount(codec.fluids().material(m).id(),v,mmm.units.Mass.kilograms(b.getLong(off+8))));}
            mat.sort(java.util.Comparator.comparing(mmm.fluid.FluidCellInventoryView.MaterialAmount::materialId));var amounts=new java.util.ArrayList<mmm.fluid.FluidCellInventoryView.SpeciesAmount>();
            for(int s=0;s<species;s++){long q=b.getLong(288+16*materials+8*s);if(q>0)amounts.add(new mmm.fluid.FluidCellInventoryView.SpeciesAmount(registry.species(s).id(),q));}
            amounts.sort(java.util.Comparator.comparing(mmm.fluid.FluidCellInventoryView.SpeciesAmount::speciesId));
            return new mmm.fluid.FluidCellInventoryView(volume,mass,mass/volume,h[10]<0?java.util.OptionalDouble.empty():java.util.OptionalDouble.of(b.getDouble(272)),mat,amounts);
        }
    }
    private static int word(VulkanLiquidStateLayout.Column c){return Math.toIntExact(c.offset()/4);}
    @Override public synchronized void close(){try{bindings.close();}finally{try{if(scheduler!=null)scheduler.close();}finally{resources.close();}}}
}

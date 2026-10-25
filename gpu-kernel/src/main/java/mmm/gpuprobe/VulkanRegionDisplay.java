package mmm.gpuprobe;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** GPU-derived chemistry and caloric display fields, separate from the exact inventories. */
final class VulkanRegionDisplay implements AutoCloseable {
    record Fields(mmm.chemistry.ChemicalDisplaySnapshot chemical,mmm.thermal.ThermalDisplaySnapshot thermal){}
    private final VulkanDeviceContext context;private final FlipGpuResources resources;private final FlipKernelBindings bindings;
    private GpuStepScheduler scheduler;private GpuBuffer output;private long capacity;
    VulkanRegionDisplay(VulkanDeviceContext context){this.context=context;resources=new FlipGpuResources(context);
        try(var in=getClass().getResourceAsStream("/assets/mmm_gpu_probe/shaders/region-display.comp")){
            if(in==null)throw new IllegalStateException("Region display shader missing");bindings=new FlipKernelBindings(context,2,new String(in.readAllBytes(),StandardCharsets.UTF_8),16);
        }catch(java.io.IOException|RuntimeException|Error failure){resources.close();throw new IllegalStateException(failure);}
    }
    synchronized Fields capture(VulkanLiquidRegion.Capture capture,VulkanFluidStateCodec codec){
        var in=capture.readoutInputs();var l=in.liquid();var chemical=codec.template().chemicalRegistry();boolean heat=codec.template().thermalRegistry()!=null;
        int cells=l.cellCount(),materials=codec.fluids().size(),phases=codec.fluids().phaseCount(),nodes=heat&&in.gas()!=null?in.gas().nodeCapacity():0;
        int table=128,chemicalAt=table+materials,phAt=chemicalAt+(chemical==null?0:4*cells),thermalAt=phAt+(chemical==null?0:phases*cells),gasAt=thermalAt+(heat?cells:0);
        long bytes=4L*(gasAt+3*nodes);if(bytes>capacity){if(scheduler!=null)scheduler.close();if(output!=null)resources.destroy(output);output=resources.addDeviceStorage(bytes);scheduler=new GpuStepScheduler(context,resources,bytes);capacity=bytes;}
        int[] h=new int[128];h[0]=cells;h[1]=chemical==null?0:chemical.species().size();h[2]=materials;h[3]=phases;
        h[4]=word(l.column(VulkanLiquidStateLayout.Field.COUNTS));h[5]=word(l.column(VulkanLiquidStateLayout.Field.CELL_OFFSETS));h[6]=word(l.column(VulkanLiquidStateLayout.Field.MATERIALS));h[7]=word(l.column(VulkanLiquidStateLayout.Field.NUMERIC_VOLUMES));h[8]=word(l.column(VulkanLiquidStateLayout.Field.SPECIES_OFFSETS));h[9]=word(l.column(VulkanLiquidStateLayout.Field.SPECIES_HANDLES));h[10]=word(l.column(VulkanLiquidStateLayout.Field.SPECIES_AMOUNTS));
        h[11]=chemical!=null&&chemical.hasSpecies(mmm.chemistry.BuiltinChemicalDefinitions.H_PLUS)?chemical.speciesHandle(mmm.chemistry.BuiltinChemicalDefinitions.H_PLUS):-1;
        h[12]=chemical!=null&&chemical.hasSpecies(mmm.chemistry.BuiltinChemicalDefinitions.OH_MINUS)?chemical.speciesHandle(mmm.chemistry.BuiltinChemicalDefinitions.OH_MINUS):-1;
        h[20]=heat?word(in.liquidThermal().cellTemperatures()):-1;h[21]=heat?word(in.liquidThermal().cellHeatCapacities()):0;h[22]=-1;h[30]=-1;h[40]=-1;
        if(heat&&in.coherent()!=null){var g=in.coherentGeometry();h[22]=word(g.offsets());h[23]=word(g.members());h[24]=word(in.coherentThermal().temperatures());h[25]=word(in.coherentThermal().heatCapacities());h[26]=in.coherent().word(VulkanCoherentStateLayout.Field.OCCUPIED_VOLUME_SVQ);}
        if(heat&&in.granular()!=null){var g=in.granularGeometry();h[30]=g.word(VulkanGranularGeometry.Field.OFFSETS);h[31]=g.word(VulkanGranularGeometry.Field.ENTRIES);h[32]=in.granular().particleCapacity();h[33]=in.granular().word(VulkanGranularStateLayout.Field.SOLID_VOLUME_SVQ);h[34]=in.granular().word(VulkanGranularStateLayout.Field.SUPPORT_QUOTAS);h[35]=word(in.granularThermal().temperatures());h[36]=word(in.granularThermal().heatCapacities());}
        if(nodes>0){h[40]=word(in.gas().column(VulkanGasStateLayout.Field.COUNTS));h[41]=word(in.gas().column(VulkanGasStateLayout.Field.TOPOLOGY_KEYS));h[42]=word(in.gasThermal().states());h[43]=nodes;}
        h[50]=chemicalAt;h[51]=phAt;h[52]=thermalAt;h[53]=gasAt;h[54]=table;
        var header=ByteBuffer.allocateDirect(4*(128+materials)).order(ByteOrder.LITTLE_ENDIAN);for(int v:h)header.putInt(v);
        for(int m=0;m<materials;m++){int value=codec.fluids().phaseHandle(m);if(chemical!=null&&mmm.chemistry.ChemicalReactionEngine.supportsPh(chemical.phase(chemical.phaseHandleForMaterial(codec.fluids().material(m).id()))))value|=65536;header.putInt(value);}header.flip();
        bindings.rebindRanges(List.of(new FlipKernelBindings.BufferRange(capture.buffer()),new FlipKernelBindings.BufferRange(output)));
        try(var stack=stackPush()){
            var submission=scheduler.acquire();var cmd=scheduler.begin(submission,"region-display");vkCmdUpdateBuffer(cmd,output.handle(),0,header);
            VulkanRegionStorage.barrier(cmd,VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,VK_ACCESS_MEMORY_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT,stack);
            bindings.bind(cmd,stack);double cellSize=codec.template().config().cellSize();var push=stack.malloc(16).putInt(Math.max(cells,nodes)).putInt(0).putDouble(cellSize*cellSize*cellSize).flip();vkCmdPushConstants(cmd,bindings.pipelineLayout(),VK_SHADER_STAGE_COMPUTE_BIT,0,push);vkCmdDispatch(cmd,(Math.max(cells,nodes)+127)/128,1,1);
            VulkanRegionStorage.barrier(cmd,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_READ_BIT,stack);
            VulkanRegionStorage.copy(cmd,output,0,submission.stagingBuffer(),0,bytes,stack);scheduler.submitAndWait(stack,submission,"region-display");
            var b=submission.stagingBuffer().bytes().order(ByteOrder.LITTLE_ENDIAN);mmm.chemistry.ChemicalDisplaySnapshot composition=null;mmm.thermal.ThermalDisplaySnapshot thermal=null;
            if(chemical!=null){short[] ids=new short[4*cells];byte[] weights=new byte[4*cells],residual=new byte[cells],ph=new byte[phases*cells];
                for(int c=0;c<cells;c++){int at=4*(chemicalAt+4*c);for(int j=0;j<4;j++){ids[4*c+j]=b.getShort(at+2*j);weights[4*c+j]=b.get(at+8+j);}residual[c]=b.get(at+12);}
                for(int i=0;i<ph.length;i++)ph[i]=(byte)b.getInt(4*(phAt+i));composition=mmm.chemistry.ChemicalDisplaySnapshot.fromDisplayFields(chemical,ids,weights,residual,ph);}
            if(heat){short[] temperatures=new short[cells];byte[] coherent=new byte[cells],flags=new byte[cells];for(int c=0;c<cells;c++){int value=b.getInt(4*(thermalAt+c));temperatures[c]=(short)value;coherent[c]=(byte)(value>>>16);flags[c]=(byte)(value>>>24);}
                var gas=new java.util.ArrayList<mmm.thermal.ThermalDisplaySnapshot.GasNode>();for(int n=0;n<nodes;n++){int at=4*(gasAt+3*n),id=b.getInt(at);if(id>0)gas.add(new mmm.thermal.ThermalDisplaySnapshot.GasNode(id,b.getInt(at+4),b.getInt(at+8)));}
                thermal=new mmm.thermal.ThermalDisplaySnapshot(temperatures,coherent,flags,gas);}
            return new Fields(composition,thermal);
        }
    }
    private static int word(VulkanLiquidStateLayout.Column c){return Math.toIntExact(c.offset()/4);}
    private static int word(VulkanGasStateLayout.Column c){return Math.toIntExact(c.offset()/4);}
    @Override public void close(){try{bindings.close();}finally{try{if(scheduler!=null)scheduler.close();}finally{resources.close();}}}
}

package mmm.gpu;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** GPU-derived chemistry and caloric display fields, separate from the exact inventories. */
final class VulkanRegionDisplay implements AutoCloseable {
    private enum Header {
        CELLS(0), SPECIES_COUNT(1), MATERIAL_COUNT(2), PHASE_COUNT(3),
        LIQUID_COUNTS(4), CELL_OFFSETS(5), MATERIALS(6), VOLUMES(7),
        SPECIES_OFFSETS(8), SPECIES_HANDLES(9), SPECIES_AMOUNTS(10), HYDROGEN(11),
        HYDROXIDE(12), LIQUID_TEMPERATURE(20), LIQUID_CAPACITY(21), COHERENT_OFFSETS(22),
        COHERENT_MEMBERS(23), COHERENT_TEMPERATURE(24), COHERENT_CAPACITY(25), COHERENT_VOLUME(26),
        GRAIN_OFFSETS(30), GRAIN_ENTRIES(31), PARTICLE_CAPACITY(32), GRAIN_VOLUME(33),
        GRAIN_SUPPORT(34), GRAIN_TEMPERATURE(35), GRAIN_CAPACITY(36), GAS_COUNTS(40),
        GAS_THERMAL(42), GAS_CAPACITY(43), CHEMICAL_OUTPUT(50),
        PH_OUTPUT(51), THERMAL_OUTPUT(52), GAS_OUTPUT(53), MATERIAL_TABLE(54);
        final int word; Header(int word) { this.word = word; }
    }
    private static String shaderLayout() {
        var result = new StringBuilder();
        for (var field : Header.values()) result.append("#define H_").append(field.name()).append(' ').append(field.word).append('\n');
        return result.toString();
    }

    record Fields(mmm.chemistry.ChemicalDisplaySnapshot chemical,mmm.thermal.ThermalDisplaySnapshot thermal){}
    private final VulkanDeviceContext context;private final FlipGpuResources resources;private final FlipKernelBindings bindings;
    private GpuStepScheduler scheduler;private GpuBuffer output;private long capacity;
    VulkanRegionDisplay(VulkanDeviceContext context){this.context=context;resources=new FlipGpuResources(context);
        try(var in=getClass().getResourceAsStream("/assets/mmm_gpu_probe/shaders/region-display.comp")){
            if(in==null)throw new IllegalStateException("Region display shader missing");bindings=new FlipKernelBindings(context,2,new String(in.readAllBytes(),StandardCharsets.UTF_8).replace("// HOST_LAYOUT", shaderLayout()),16);
        }catch(java.io.IOException|RuntimeException|Error failure){resources.close();throw new IllegalStateException(failure);}
    }
    synchronized Fields capture(VulkanLiquidRegion.Capture capture,VulkanFluidStateCodec codec){
        var in=capture.readoutInputs();var l=in.liquid();var chemical=codec.template().chemicalRegistry();boolean heat=codec.template().thermalRegistry()!=null;
        int cells=l.cellCount(),materials=codec.fluids().size(),phases=codec.fluids().phaseCount(),nodes=heat&&in.gas()!=null?in.gas().nodeCapacity():0;
        int table=128,chemicalAt=table+materials,phAt=chemicalAt+(chemical==null?0:4*cells),thermalAt=phAt+(chemical==null?0:phases*cells),gasAt=thermalAt+(heat?cells:0);
        long bytes=4L*(gasAt+3*nodes);if(bytes>capacity){if(scheduler!=null)scheduler.close();if(output!=null)resources.destroy(output);output=resources.addDeviceStorage(bytes);scheduler=new GpuStepScheduler(context,resources,bytes);capacity=bytes;}
        int[] h=new int[128];h[Header.CELLS.word]=cells;h[Header.SPECIES_COUNT.word]=chemical==null?0:chemical.species().size();h[Header.MATERIAL_COUNT.word]=materials;h[Header.PHASE_COUNT.word]=phases;
        h[Header.LIQUID_COUNTS.word]=word(l.column(VulkanLiquidStateLayout.Field.COUNTS));h[Header.CELL_OFFSETS.word]=word(l.column(VulkanLiquidStateLayout.Field.CELL_OFFSETS));h[Header.MATERIALS.word]=word(l.column(VulkanLiquidStateLayout.Field.MATERIALS));h[Header.VOLUMES.word]=word(l.column(VulkanLiquidStateLayout.Field.NUMERIC_VOLUMES));h[Header.SPECIES_OFFSETS.word]=word(l.column(VulkanLiquidStateLayout.Field.SPECIES_OFFSETS));h[Header.SPECIES_HANDLES.word]=word(l.column(VulkanLiquidStateLayout.Field.SPECIES_HANDLES));h[Header.SPECIES_AMOUNTS.word]=word(l.column(VulkanLiquidStateLayout.Field.SPECIES_AMOUNTS));
        h[Header.HYDROGEN.word]=chemical!=null&&chemical.hasSpecies(mmm.chemistry.BuiltinChemicalDefinitions.H_PLUS)?chemical.speciesHandle(mmm.chemistry.BuiltinChemicalDefinitions.H_PLUS):-1;
        h[Header.HYDROXIDE.word]=chemical!=null&&chemical.hasSpecies(mmm.chemistry.BuiltinChemicalDefinitions.OH_MINUS)?chemical.speciesHandle(mmm.chemistry.BuiltinChemicalDefinitions.OH_MINUS):-1;
        h[Header.LIQUID_TEMPERATURE.word]=heat?word(in.liquidThermal().cellTemperatures()):-1;h[Header.LIQUID_CAPACITY.word]=heat?word(in.liquidThermal().cellHeatCapacities()):0;h[Header.COHERENT_OFFSETS.word]=-1;h[Header.GRAIN_OFFSETS.word]=-1;h[Header.GAS_COUNTS.word]=-1;
        if(heat&&in.coherent()!=null){var g=in.coherentGeometry();h[Header.COHERENT_OFFSETS.word]=word(g.offsets());h[Header.COHERENT_MEMBERS.word]=word(g.members());h[Header.COHERENT_TEMPERATURE.word]=word(in.coherentThermal().temperatures());h[Header.COHERENT_CAPACITY.word]=word(in.coherentThermal().heatCapacities());h[Header.COHERENT_VOLUME.word]=in.coherent().word(VulkanCoherentStateLayout.Field.OCCUPIED_VOLUME_SVQ);}
        if(heat&&in.granular()!=null){var g=in.granularGeometry();h[Header.GRAIN_OFFSETS.word]=g.word(VulkanGranularGeometry.Field.OFFSETS);h[Header.GRAIN_ENTRIES.word]=g.word(VulkanGranularGeometry.Field.ENTRIES);h[Header.PARTICLE_CAPACITY.word]=in.granular().particleCapacity();h[Header.GRAIN_VOLUME.word]=in.granular().word(VulkanGranularStateLayout.Field.SOLID_VOLUME_SVQ);h[Header.GRAIN_SUPPORT.word]=in.granular().word(VulkanGranularStateLayout.Field.SUPPORT_QUOTAS);h[Header.GRAIN_TEMPERATURE.word]=word(in.granularThermal().temperatures());h[Header.GRAIN_CAPACITY.word]=word(in.granularThermal().heatCapacities());}
        if(nodes>0){h[Header.GAS_COUNTS.word]=word(in.gas().column(VulkanGasStateLayout.Field.COUNTS));h[Header.GAS_THERMAL.word]=word(in.gasThermal().states());h[Header.GAS_CAPACITY.word]=nodes;}
        h[Header.CHEMICAL_OUTPUT.word]=chemicalAt;h[Header.PH_OUTPUT.word]=phAt;h[Header.THERMAL_OUTPUT.word]=thermalAt;h[Header.GAS_OUTPUT.word]=gasAt;h[Header.MATERIAL_TABLE.word]=table;
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

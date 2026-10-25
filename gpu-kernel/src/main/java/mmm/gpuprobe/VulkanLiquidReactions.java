package mmm.gpuprobe;

import mmm.chemistry.ChemicalRegistry;
import mmm.fluid.FluidMaterialRegistry;
import org.lwjgl.vulkan.VkCommandBuffer;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Same-support liquid reactions prepare a private composition CSR before publishing owner columns. */
final class VulkanLiquidReactions implements AutoCloseable {
    static final int READY=2,NEED_CAPACITY=1,ERROR=1<<25;
    record Layout(VulkanLiquidStateLayout liquid,VulkanReactionDefinitions.Layout definitions,
                  VulkanLiquidThermodynamics.Layout caloric,VulkanReactionProgressLayout progress,
                  int[] phases,VulkanLiquidStateLayout.Column header,VulkanLiquidStateLayout.Column phaseTable,
                  VulkanLiquidStateLayout.Column dense,VulkanLiquidStateLayout.Column rowCounts,
                  VulkanLiquidStateLayout.Column rowOffsets,VulkanLiquidStateLayout.Column handles,
                  VulkanLiquidStateLayout.Column amounts,VulkanLiquidStateLayout.Column residuals,
                  VulkanLiquidStateLayout.Column energies,VulkanLiquidStateLayout.Column initialEnergies,
                  VulkanLiquidStateLayout.Column totalQuanta,VulkanLiquidStateLayout.Column scan,long endOffset){
        int word(VulkanLiquidStateLayout.Column c){return Math.toIntExact(c.offset()/4);}
        long feedbackOffset(){return progress.column(VulkanReactionProgressLayout.Field.CONTROL).offset();}
        ByteBuffer headerBytes(){
            var out=ByteBuffer.allocateDirect(256).order(ByteOrder.LITTLE_ENDIAN);int[] h=new int[64];
            h[0]=liquid.cellCount();h[1]=liquid.stockCapacity();h[2]=definitions.speciesCount();h[3]=definitions.word();h[4]=word(caloric.header());
            h[5]=progress.word(VulkanReactionProgressLayout.Field.CONTROL);h[6]=progress.word(VulkanReactionProgressLayout.Field.DIRTY_CELLS);
            h[7]=word(phaseTable);h[8]=word(dense);h[9]=word(rowCounts);h[10]=word(rowOffsets);h[11]=word(handles);h[12]=word(amounts);
            h[13]=word(residuals);h[14]=word(energies);h[15]=liquid.speciesCapacity();
            h[40]=word(totalQuanta);
            h[41]=word(initialEnergies);
            for(var f:VulkanLiquidStateLayout.Field.values())h[16+f.ordinal()]=Math.toIntExact(liquid.column(f).offset()/4);
            for(int i=0;i<h.length;i++)out.putInt(4*i,h[i]);return out;
        }
        List<VulkanGasThermodynamics.TableChunk> tableChunks(){var data=ByteBuffer.allocateDirect(Math.multiplyExact(4,phases.length)).order(ByteOrder.LITTLE_ENDIAN);
            for(int i=0;i<phases.length;i++)data.putInt(4*i,phases[i]);return List.of(new VulkanGasThermodynamics.TableChunk(phaseTable.offset(),data));
        }
        List<VulkanEnergyLedger.Stream> roundoffStreams(){return List.of(new VulkanEnergyLedger.Stream(1,word(energies),energies.count(),2,1),
                new VulkanEnergyLedger.Stream(1,word(initialEnergies),initialEnergies.count(),2,-1));}
        void initializeHeaderAndTables(ByteBuffer arena){
            arena.duplicate().position(Math.toIntExact(header.offset())).put(headerBytes());
            for(var chunk:tableChunks())arena.duplicate().position(Math.toIntExact(chunk.offset())).put(chunk.bytes().duplicate());
        }
    }
    static Layout layout(VulkanLiquidStateLayout liquid,VulkanReactionDefinitions.Layout definitions,
                         VulkanLiquidThermodynamics.Layout caloric,VulkanReactionProgressLayout progress,
                         FluidMaterialRegistry fluid,ChemicalRegistry chemical,long base,long alignment){
        if(base<Math.max(progress.endOffset(),caloric.endOffset())||alignment<8||(alignment&(alignment-1))!=0
                ||definitions.speciesCount()>256||caloric.speciesStride()<definitions.speciesCount()
                ||!definitions.identity().chemicalFingerprint().equals(chemical.fingerprint()))throw new IllegalArgumentException("Invalid liquid reaction workspace");
        int[] phases=new int[fluid.materials().size()];java.util.Arrays.fill(phases,-1);
        for(int i=0;i<phases.length;i++)for(int j=0;j<chemical.phases().size();j++)
            if(chemical.phases().get(j).materialId().equals(fluid.material(i).id()))phases[i]=j;
        long[] c={base};var header=column(c,64,4,alignment);var phase=column(c,phases.length,4,alignment);
        var dense=column(c,Math.multiplyExact(liquid.stockCapacity(),definitions.speciesCount()),8,alignment);
        var counts=column(c,liquid.stockCapacity(),4,alignment);var offsets=column(c,liquid.stockCapacity()+1,4,alignment);
        var handles=column(c,liquid.speciesCapacity(),4,alignment);var amounts=column(c,liquid.speciesCapacity(),8,alignment);
        var residual=column(c,liquid.stockCapacity(),8,alignment);var energy=column(c,liquid.stockCapacity(),8,alignment);
        var initialEnergy=column(c,liquid.stockCapacity(),8,alignment);
        var total=column(c,1,8,alignment);var scan=column(c,Math.toIntExact(VulkanPrefixScan.workspaceBytes(liquid.stockCapacity())/4),4,alignment);
        return new Layout(liquid,definitions,caloric,progress,phases,header,phase,dense,counts,offsets,handles,amounts,residual,energy,initialEnergy,total,scan,align(c[0],alignment));
    }
    private final FlipKernelBindings bindings;private final VulkanPrefixScan scan;
    VulkanLiquidReactions(VulkanDeviceContext context){bindings=new FlipKernelBindings(context,3,shader(),12);
        try{scan=new VulkanPrefixScan(context);}catch(RuntimeException|LinkageError failure){bindings.close();throw failure;}}
    void rebindRanges(FlipKernelBindings.BufferRange arena,FlipKernelBindings.BufferRange errors){bindings.rebindRanges(List.of(arena,errors,arena));scan.rebindRanges(arena,arena,arena,errors);}
    void recordPrepare(VkCommandBuffer command,Layout l){
        stage(command,l,6,1);stage(command,l,0,l.liquid.stockCapacity());stage(command,l,1,l.liquid.cellCount());
        stage(command,l,2,l.liquid.stockCapacity());scan.record(command,l.liquid.stockCapacity(),l.word(l.rowCounts),l.word(l.rowOffsets),l.word(l.scan));
        stage(command,l,3,1);stage(command,l,4,l.liquid.stockCapacity());
    }
    /** CONTROL uses the shared progress schema; index 2 reports required species capacity. */
    void recordPublish(VkCommandBuffer command,Layout l){stage(command,l,5,Math.max(l.liquid.stockCapacity()+1,l.liquid.speciesCapacity()));}
    private void stage(VkCommandBuffer command,Layout l,int stage,int count){if(count==0)return;try(var stack=stackPush()){
        bindings.bind(command,stack);vkCmdPushConstants(command,bindings.pipelineLayout(),VK_SHADER_STAGE_COMPUTE_BIT,0,stack.ints(l.word(l.header),stage,count));
        int groups=(count+127)/128;vkCmdDispatch(command,Math.min(groups,65535),(groups+65534)/65535,1);
        VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);
    }}
    private static VulkanLiquidStateLayout.Column column(long[] c,int n,int width,long alignment){var result=new VulkanLiquidStateLayout.Column(align(c[0],alignment),n,width);c[0]=result.offset()+result.bindingBytes();return result;}
    private static long align(long n,long a){return Math.addExact(n,a-1)&-a;}
    private static String shader(){try(var in=VulkanLiquidReactions.class.getResourceAsStream("/assets/mmm_gpu_probe/shaders/liquid-reactions.comp")){
        if(in==null)throw new IllegalStateException("Liquid reaction shader missing");return new String(in.readAllBytes(),StandardCharsets.UTF_8);
    }catch(IOException failure){throw new IllegalStateException("Liquid reaction shader unavailable",failure);}}
    @Override public void close(){scan.close();bindings.close();}
}

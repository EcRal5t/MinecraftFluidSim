package mmm.gpuprobe;

import java.util.ArrayList;
import java.util.List;
import mmm.fluid.FluidMaterialRegistry;
import mmm.chemistry.ChemicalRegistry;
import mmm.granular.GranularRegistry;
import org.lwjgl.vulkan.VkCommandBuffer;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Chemistry and solid/liquid successors remain private until their complete inventory fits. */
final class VulkanRegionReactions implements AutoCloseable {
    record Configuration(VulkanReactionDefinitions.Tables definitions,FluidMaterialRegistry fluids,ChemicalRegistry chemistry,
                         VulkanReactionProgressLayout.State initial,double gravity,boolean precipitationEnabled,mmm.thermal.ThermalRegistry thermal,mmm.fluid.air.GasRegistry gas){
        Configuration(VulkanReactionDefinitions.Tables definitions,FluidMaterialRegistry fluids,ChemicalRegistry chemistry){this(definitions,fluids,chemistry,VulkanReactionProgressLayout.State.empty(),0,true,null,null);}
        Configuration(VulkanReactionDefinitions.Tables definitions,FluidMaterialRegistry fluids,ChemicalRegistry chemistry,VulkanReactionProgressLayout.State initial){this(definitions,fluids,chemistry,initial,0,true,null,null);}
        Configuration(VulkanReactionDefinitions.Tables definitions,FluidMaterialRegistry fluids,ChemicalRegistry chemistry,VulkanReactionProgressLayout.State initial,double gravity,boolean precipitationEnabled){this(definitions,fluids,chemistry,initial,gravity,precipitationEnabled,null,null);}
        Configuration{if(!Double.isFinite(gravity)||gravity<0)throw new IllegalArgumentException("Invalid reaction gravity");}
        boolean enabled(){return !definitions.identity().rules().isEmpty()||!initial.pending().isEmpty()||initial.phaseMotionCells().length>0;}
    }
    /** A separate transition workspace preserves the earlier mechanical transition ledger. */
    record SolidInputs(VulkanGranularTransitions.Layout transitions,VulkanLiquidThermodynamics.Layout solidCaloric,
                       GranularRegistry registry,double cellSize,int faceGeometryWord){ }
    static final class CapacityGrowth extends IllegalStateException{
        final int species,limbs,particles,deposits,solidSpecies,coherentGroups,coherentSpecies;
        CapacityGrowth(int species){this(species,0,0,0,0);}
        CapacityGrowth(int species,int limbs,int particles,int deposits,int solidSpecies){this(species,limbs,particles,deposits,solidSpecies,0,0);}
        CapacityGrowth(int species,int limbs,int particles,int deposits,int solidSpecies,int coherentGroups,int coherentSpecies){this.species=species;this.limbs=limbs;this.particles=particles;this.deposits=deposits;this.solidSpecies=solidSpecies;this.coherentGroups=coherentGroups;this.coherentSpecies=coherentSpecies;}
    }
    record Layout(VulkanReactionDefinitions.Layout definitions,VulkanReactionProgressLayout progress,VulkanLiquidReactions.Layout liquid,
                  SolidInputs solidInputs,VulkanSolidLiquidReactions.Layout solid,VulkanGasPhaseChange.Layout gasPhase,VulkanCondensedPhaseChange.Layout condensedPhase,long endOffset){
        List<VulkanGasThermodynamics.TableChunk> headers(){
            var result=new ArrayList<VulkanGasThermodynamics.TableChunk>();
            result.add(new VulkanGasThermodynamics.TableChunk(progress.column(VulkanReactionProgressLayout.Field.HEADER).offset(),progress.headerBytes()));
            result.add(new VulkanGasThermodynamics.TableChunk(liquid.header().offset(),liquid.headerBytes()));
            if(solid!=null){result.add(new VulkanGasThermodynamics.TableChunk(solidInputs.transitions().header.offset(),solidInputs.transitions().headerBytes()));
                result.add(new VulkanGasThermodynamics.TableChunk(solid.column(VulkanSolidLiquidReactions.Field.HEADER).offset(),solid.headerBytes()));
                result.add(new VulkanGasThermodynamics.TableChunk(solid.phaseRows.column(VulkanLiquidPhaseRows.Field.HEADER).offset(),solid.phaseRows.headerBytes()));}
            if(gasPhase!=null)result.addAll(gasPhase.headers());if(condensedPhase!=null)result.addAll(condensedPhase.headers());
            return List.copyOf(result);
        }
        List<VulkanGasThermodynamics.TableChunk> tables(){var result=new ArrayList<VulkanGasThermodynamics.TableChunk>();result.add(new VulkanGasThermodynamics.TableChunk(definitions.data().offset(),definitions.bytes()));result.addAll(liquid.tableChunks());
            if(solid!=null){result.add(new VulkanGasThermodynamics.TableChunk(solidInputs.transitions().column(VulkanGranularTransitions.Field.MATERIALS).offset(),solidInputs.transitions().materialBytes(solidInputs.registry())));result.addAll(solid.tableChunks());}if(gasPhase!=null)result.addAll(gasPhase.tables());if(condensedPhase!=null)result.addAll(condensedPhase.tableChunks());return List.copyOf(result);}
        List<VulkanEnergyLedger.Stream> roundoffStreams(){var result=new ArrayList<>(liquid.roundoffStreams());if(solid!=null){result.addAll(solid.roundoffStreams());result.addAll(solidInputs.transitions().roundoffStreams());}if(gasPhase!=null)result.addAll(gasPhase.roundoffStreams());if(condensedPhase!=null)result.addAll(condensedPhase.roundoffStreams());return List.copyOf(result);}
        List<VulkanEnergyLedger.Stream> mechanicalWorkStreams(){var result=new ArrayList<VulkanEnergyLedger.Stream>();if(solid!=null)result.addAll(solid.mechanicalWorkStreams());if(gasPhase!=null)result.addAll(gasPhase.mechanicalWorkStreams());if(condensedPhase!=null)result.addAll(condensedPhase.mechanicalWorkStreams());return List.copyOf(result);}
        List<VulkanLiquidStateLayout.Column> postingColumns(){
            var ranges=new ArrayList<long[]>();var streams=new ArrayList<>(roundoffStreams());streams.addAll(mechanicalWorkStreams());
            for(var stream:streams)if(stream.count()>0){if(stream.bank()!=1)throw new IllegalStateException("Reaction posting refers to source bank");long start=4L*stream.wordOffset(),end=start+4L*((long)(stream.count()-1)*stream.strideWords()+2);ranges.add(new long[]{start,end});}
            ranges.sort(java.util.Comparator.comparingLong(a->a[0]));var result=new ArrayList<VulkanLiquidStateLayout.Column>();long start=-1,end=0;
            for(var range:ranges){if(start<0){start=range[0];end=range[1];}else if(range[0]<=end)end=Math.max(end,range[1]);else{result.add(new VulkanLiquidStateLayout.Column(start,Math.toIntExact((end-start)/4),4));start=range[0];end=range[1];}}
            if(start>=0)result.add(new VulkanLiquidStateLayout.Column(start,Math.toIntExact((end-start)/4),4));return List.copyOf(result);
        }
        List<VulkanLiquidStateLayout.Column> stateColumns(){var result=new ArrayList<VulkanLiquidStateLayout.Column>();for(var f:VulkanReactionProgressLayout.Field.values())if(f.name().startsWith("PENDING_")||f==VulkanReactionProgressLayout.Field.RESIDUAL_MOLES||f==VulkanReactionProgressLayout.Field.CURSOR||f==VulkanReactionProgressLayout.Field.REQUEST_GENERATION||f==VulkanReactionProgressLayout.Field.DIRTY_CELLS||f==VulkanReactionProgressLayout.Field.PHASE_MOTION_CELLS||f==VulkanReactionProgressLayout.Field.PHASE_PENDING_SUMMARY)result.add(progress.column(f));return result;}
        List<VulkanLiquidStateLayout.Column> persistentColumns(){var result=new ArrayList<VulkanLiquidStateLayout.Column>();for(var chunk:headers())result.add(new VulkanLiquidStateLayout.Column(chunk.offset(),chunk.bytes().remaining()/4,4));for(var chunk:tables())result.add(new VulkanLiquidStateLayout.Column(chunk.offset(),chunk.bytes().remaining()/4,4));result.addAll(stateColumns());return result;}
    }
    static Layout layout(Configuration configuration,VulkanLiquidStateLayout liquid,VulkanLiquidThermodynamics.Layout caloric,long base,long alignment){return layout(configuration,liquid,caloric,null,base,alignment);}
    static Layout layout(Configuration configuration,VulkanLiquidStateLayout liquid,VulkanLiquidThermodynamics.Layout caloric,SolidInputs solid,long base,long alignment){return layout(configuration,liquid,caloric,solid,null,base,alignment);}
    static Layout layout(Configuration configuration,VulkanLiquidStateLayout liquid,VulkanLiquidThermodynamics.Layout caloric,SolidInputs solid,VulkanGasPhaseChange.Inputs gas,long base,long alignment){return layout(configuration,liquid,caloric,solid,gas,null,base,alignment);}
    static Layout layout(Configuration configuration,VulkanLiquidStateLayout liquid,VulkanLiquidThermodynamics.Layout caloric,SolidInputs solid,VulkanGasPhaseChange.Inputs gas,VulkanCondensedPhaseChange.Inputs condensed,long base,long alignment){
        var definitions=configuration.definitions().layout(base,alignment);int rules=0;int[] words=configuration.definitions().words();
        if(solid!=null)for(int i=0;i<words[4];i++)if(words[words[5]+2*i]==VulkanReactionDefinitions.Kind.SOLID_LIQUID.ordinal())rules++;
        int proposals=Math.max(1,configuration.initial().pending().size());
        if(rules>0)proposals=Math.max(proposals,Math.multiplyExact(Math.multiplyExact(liquid.cellCount(),liquid.materialCount()),rules));
        var progress=new VulkanReactionProgressLayout(liquid.cellCount(),proposals,0,List.of(),definitions,definitions.endOffset(),alignment);
        var reaction=VulkanLiquidReactions.layout(liquid,definitions,caloric,progress,configuration.fluids(),configuration.chemistry(),progress.endOffset(),alignment);
        var exchange=rules==0?null:new VulkanSolidLiquidReactions.Layout(liquid,solid.transitions().source,definitions,caloric,solid.solidCaloric(),progress,
                configuration.fluids(),configuration.chemistry(),solid.registry(),solid.cellSize(),configuration.gravity(),solid.faceGeometryWord(),configuration.precipitationEnabled(),reaction.endOffset(),alignment);
        long end=exchange==null?reaction.endOffset():exchange.endOffset;VulkanGasPhaseChange.Layout gasPhase=null;
        if(gas!=null&&configuration.thermal()!=null&&configuration.thermal().transitions().stream().anyMatch(t->t.interfaceRequirement()==mmm.thermal.ThermalPhaseTransition.InterfaceRequirement.LIQUID_GAS)){
            var rows=new VulkanLiquidPhaseRows.Layout(liquid,definitions,gas.geometry().cellSize(),end,alignment);
            gasPhase=new VulkanGasPhaseChange.Layout(configuration,rows,gas,progress,rows.endOffset,alignment);end=gasPhase.endOffset;
        }
        VulkanCondensedPhaseChange.Layout condensedPhase=null;
        if(condensed!=null){var input=new VulkanCondensedPhaseChange.Inputs(liquid,condensed.coherent(),caloric,condensed.coherentCaloric(),definitions,progress,condensed.pressureWord(),condensed.fixedMasksWord(),condensed.occupiedAllSvqWord(),condensed.cellSize(),condensed.gravity());
            condensedPhase=new VulkanCondensedPhaseChange.Layout(input,configuration.fluids(),configuration.chemistry(),configuration.thermal(),end,alignment);end=condensedPhase.endOffset();}
        return new Layout(definitions,progress,reaction,solid,exchange,gasPhase,condensedPhase,end);
    }
    private final VulkanLiquidReactions kernel;private final VulkanLiquidThermodynamics caloric;
    private final VulkanGasPhaseChange gasPhaseKernel;private final VulkanCondensedPhaseChange condensedKernel;
    private final VulkanSolidLiquidReactions exchange;private final VulkanGranularTransitions transitions;
    VulkanRegionReactions(VulkanDeviceContext context){
        var allocated=new ArrayList<AutoCloseable>();
        try{kernel=own(allocated,new VulkanLiquidReactions(context));caloric=own(allocated,new VulkanLiquidThermodynamics(context));
            exchange=own(allocated,new VulkanSolidLiquidReactions(context));transitions=own(allocated,new VulkanGranularTransitions(context));
            gasPhaseKernel=own(allocated,new VulkanGasPhaseChange(context));condensedKernel=own(allocated,new VulkanCondensedPhaseChange(context));}
        catch(RuntimeException|Error failure){for(var resource:allocated.reversed())try{resource.close();}catch(Exception|LinkageError closing){failure.addSuppressed(closing);}throw failure;}
    }
    private static <T extends AutoCloseable>T own(List<AutoCloseable> resources,T value){resources.add(value);return value;}
    void recordClearPostings(VkCommandBuffer command,Layout layout,GpuBuffer arena){try(var stack=stackPush()){
        VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,stack);
        var clear=new ArrayList<>(layout.postingColumns());if(layout.gasPhase()!=null)clear.add(layout.gasPhase().column(VulkanGasPhaseChange.Field.CONTROL));if(layout.condensedPhase()!=null)clear.add(layout.condensedPhase().column(VulkanCondensedPhaseChange.Field.CONTROL));
        for(var column:clear)vkCmdFillBuffer(command,arena.handle(),column.offset(),column.bytes(),0);
        VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);
    }}
    int execute(VulkanRegionStorage.Candidate candidate,Layout layout){
        var status=candidate.continueBatch((command,input,output,errors,staging,stack)->{var arena=new FlipKernelBindings.BufferRange(output);var err=new FlipKernelBindings.BufferRange(errors);kernel.rebindRanges(arena,err);caloric.rebindRanges(arena,err);kernel.recordPrepare(command,layout.liquid());},layout.liquid().feedbackOffset(),12);
        if(status.error()!=0)return status.error();
        if(status.feedback().getInt(0)==VulkanLiquidReactions.NEED_CAPACITY)throw new CapacityGrowth(status.feedback().getInt(8));
        if(status.feedback().getInt(0)!=VulkanLiquidReactions.READY)throw new IllegalStateException("Liquid reactions did not prepare a complete successor");
        if(layout.solid()==null)return candidate.continueBatch((command,input,output,errors,staging,stack)->{kernel.recordPublish(command,layout.liquid());caloric.record(command,layout.liquid().caloric());},0,0).error();
        status=candidate.continueBatch((command,input,output,errors,staging,stack)->{
            kernel.recordPublish(command,layout.liquid());caloric.record(command,layout.liquid().caloric());
            var arena=new FlipKernelBindings.BufferRange(output);var err=new FlipKernelBindings.BufferRange(errors);transitions.rebindRanges(arena,err);exchange.rebindRanges(arena,err);
            transitions.recordCopySource(command,layout.solidInputs().transitions(),output);exchange.recordPrepare(command,layout.solid());
        },layout.solid().feedbackOffset(),48);
        if(status.error()!=0)return status.error();var feedback=status.feedback();
        if(feedback.getInt(0)==VulkanLiquidReactions.NEED_CAPACITY)throw new CapacityGrowth(feedback.getInt(8),feedback.getInt(32),0,feedback.getInt(4),feedback.getInt(12));
        if(feedback.getInt(0)!=VulkanLiquidReactions.READY)throw new IllegalStateException("Solid/liquid reactions did not prepare a complete successor");
        status=candidate.continueBatch((command,input,output,errors,staging,stack)->{
            exchange.recordPublish(command,layout.solid());transitions.recordReassemble(command,layout.solidInputs().transitions(),layout.solidInputs().cellSize());
            caloric.record(command,layout.liquid().caloric());caloric.record(command,layout.solidInputs().solidCaloric());
        },layout.solidInputs().transitions().column(VulkanGranularTransitions.Field.CONTROL).offset(),12);
        if(status.error()==64)throw new CapacityGrowth(0,0,status.feedback().getInt(0),status.feedback().getInt(4),status.feedback().getInt(8));
        return status.error();
    }
    int execute(VulkanRegionStorage.Candidate candidate,Layout layout,boolean phaseEnabled,int maximum,VulkanGasThermodynamics.Layout eos,VulkanGasGeometry.Layout geometry){
        int error=execute(candidate,layout);if(error!=0||!phaseEnabled||maximum==0)return error;
        int remaining=maximum;
        if(layout.gasPhase()!=null){error=gasPhaseKernel.execute(candidate,layout.gasPhase(),remaining,eos,geometry);if(error!=0)return error;remaining-=gasPhaseKernel.completedOperations();}
        if(layout.condensedPhase()!=null&&remaining>0){
            error=candidate.continueBatch((command,input,output,errors,staging,stack)->{caloric.record(command,layout.liquid().caloric());caloric.record(command,layout.condensedPhase().input.coherentCaloric());},0,0).error();if(error!=0)return error;
            try{error=condensedKernel.execute(candidate,layout.condensedPhase(),remaining);}
            catch(VulkanCondensedPhaseChange.CapacityGrowth growth){throw new CapacityGrowth(growth.liquidSpecies,growth.liquidLimbs,0,0,0,growth.coherentGroups,growth.coherentSpecies);}
            if(error!=0)return error;
        }
        return candidate.continueBatch((command,input,output,errors,staging,stack)->{caloric.record(command,layout.liquid().caloric());if(layout.condensedPhase()!=null)caloric.record(command,layout.condensedPhase().input.coherentCaloric());},0,0).error();
    }
    @Override public void close(){condensedKernel.close();gasPhaseKernel.close();try{transitions.close();}finally{try{exchange.close();}finally{try{caloric.close();}finally{kernel.close();}}}}
}

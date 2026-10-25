package mmm.gpuprobe;

import java.util.ArrayList;
import java.util.List;
import mmm.fluid.FluidMaterialRegistry;
import mmm.chemistry.ChemicalRegistry;

/** Liquid composition successors and their caloric redistribution stay in the region candidate. */
final class VulkanRegionReactions implements AutoCloseable {
    record Configuration(VulkanReactionDefinitions.Tables definitions,FluidMaterialRegistry fluids,ChemicalRegistry chemistry,VulkanReactionProgressLayout.State initial){
        Configuration(VulkanReactionDefinitions.Tables definitions,FluidMaterialRegistry fluids,ChemicalRegistry chemistry){this(definitions,fluids,chemistry,VulkanReactionProgressLayout.State.empty());}
        boolean enabled(){return !definitions.identity().rules().isEmpty()||!initial.pending().isEmpty()||initial.phaseMotionCells().length>0;}
    }
    static final class CapacityGrowth extends IllegalStateException{final int species;CapacityGrowth(int species){this.species=species;}}
    record Layout(VulkanReactionDefinitions.Layout definitions,VulkanReactionProgressLayout progress,VulkanLiquidReactions.Layout liquid,long endOffset){
        List<VulkanGasThermodynamics.TableChunk> headers(){return List.of(new VulkanGasThermodynamics.TableChunk(progress.column(VulkanReactionProgressLayout.Field.HEADER).offset(),progress.headerBytes()),new VulkanGasThermodynamics.TableChunk(liquid.header().offset(),liquid.headerBytes()));}
        List<VulkanGasThermodynamics.TableChunk> tables(){var result=new ArrayList<VulkanGasThermodynamics.TableChunk>();result.add(new VulkanGasThermodynamics.TableChunk(definitions.data().offset(),definitions.bytes()));result.addAll(liquid.tableChunks());return result;}
        List<VulkanEnergyLedger.Stream> roundoffStreams(){return liquid==null?List.of():liquid.roundoffStreams();}
        List<VulkanLiquidStateLayout.Column> stateColumns(){var result=new ArrayList<VulkanLiquidStateLayout.Column>();for(var f:VulkanReactionProgressLayout.Field.values())if(f.name().startsWith("PENDING_")||f==VulkanReactionProgressLayout.Field.RESIDUAL_MOLES||f==VulkanReactionProgressLayout.Field.CURSOR||f==VulkanReactionProgressLayout.Field.REQUEST_GENERATION||f==VulkanReactionProgressLayout.Field.DIRTY_CELLS||f==VulkanReactionProgressLayout.Field.PHASE_MOTION_CELLS||f==VulkanReactionProgressLayout.Field.PHASE_PENDING_SUMMARY)result.add(progress.column(f));return result;}
        List<VulkanLiquidStateLayout.Column> persistentColumns(){var result=new ArrayList<VulkanLiquidStateLayout.Column>();for(var chunk:headers())result.add(new VulkanLiquidStateLayout.Column(chunk.offset(),chunk.bytes().remaining()/4,4));for(var chunk:tables())result.add(new VulkanLiquidStateLayout.Column(chunk.offset(),chunk.bytes().remaining()/4,4));result.addAll(stateColumns());return result;}
    }
    static Layout layout(Configuration configuration,VulkanLiquidStateLayout liquid,VulkanLiquidThermodynamics.Layout caloric,long base,long alignment){
        var definitions=configuration.definitions().layout(base,alignment);var progress=new VulkanReactionProgressLayout(liquid.cellCount(),Math.max(1,configuration.initial().pending().size()),0,List.of(),definitions,definitions.endOffset(),alignment);
        var reaction=VulkanLiquidReactions.layout(liquid,definitions,caloric,progress,configuration.fluids(),configuration.chemistry(),progress.endOffset(),alignment);return new Layout(definitions,progress,reaction,reaction.endOffset());
    }
    private final VulkanLiquidReactions kernel;private final VulkanLiquidThermodynamics caloric;
    VulkanRegionReactions(VulkanDeviceContext context){kernel=new VulkanLiquidReactions(context);try{caloric=new VulkanLiquidThermodynamics(context);}catch(RuntimeException|Error failure){kernel.close();throw failure;}}
    int execute(VulkanRegionStorage.Candidate candidate,Layout layout){
        var status=candidate.continueBatch((command,input,output,errors,staging,stack)->{var arena=new FlipKernelBindings.BufferRange(output);var err=new FlipKernelBindings.BufferRange(errors);kernel.rebindRanges(arena,err);caloric.rebindRanges(arena,err);kernel.recordPrepare(command,layout.liquid());},layout.liquid().feedbackOffset(),12);
        if(status.error()!=0)return status.error();
        if(status.feedback().getInt(0)==VulkanLiquidReactions.NEED_CAPACITY)throw new CapacityGrowth(status.feedback().getInt(8));
        if(status.feedback().getInt(0)!=VulkanLiquidReactions.READY)throw new IllegalStateException("Liquid reactions did not prepare a complete successor");
        return candidate.continueBatch((command,input,output,errors,staging,stack)->{kernel.recordPublish(command,layout.liquid());caloric.record(command,layout.liquid().caloric());},0,0).error();
    }
    @Override public void close(){try{caloric.close();}finally{kernel.close();}}
}

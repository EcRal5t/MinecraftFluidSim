package mmm.gpuprobe;

import java.util.ArrayList;
import java.util.List;
import org.lwjgl.vulkan.VkCommandBuffer;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Rebuild gas ownership after phase volume changes, preserving the source inventory and U. */
final class VulkanPhaseGasTopology implements AutoCloseable {
    record Layout(VulkanLiquidCellVolumes.Layout totals,VulkanGasGeometry.Layout geometry,
                  VulkanGasStateLayout retained,VulkanGasStateLayout future,VulkanGasLineage.Layout lineage,
                  VulkanGasRemap.Layout remap,long endOffset){
        List<VulkanGasThermodynamics.TableChunk> headers(){return List.of(
                new VulkanGasThermodynamics.TableChunk(totals.header().offset(),totals.headerBytes()),
                new VulkanGasThermodynamics.TableChunk(geometry.header().offset(),geometry.headerBytes()),
                new VulkanGasThermodynamics.TableChunk(lineage.header.offset(),lineage.headerBytes()),
                new VulkanGasThermodynamics.TableChunk(remap.header.offset(),remap.headerBytes()));}
        List<VulkanGasThermodynamics.TableChunk> tables(){return remap.tableChunks();}
        List<VulkanEnergyLedger.Stream> roundoffStreams(){return remap.roundoffStreams();}
        long requiredNodesOffset(){return geometry.componentCount().offset();}
        long requiredGroupsOffset(){return lineage.requiredGroupsOffset();}
        long requiredPartsOffset(){return remap.requiredPartsOffset();}
        List<VulkanLiquidStateLayout.Column> postingColumns(){var result=new ArrayList<VulkanLiquidStateLayout.Column>();
            for(var field:new VulkanGasRemap.Field[]{VulkanGasRemap.Field.ENERGY_ROUNDOFF,VulkanGasRemap.Field.SOURCE_ROUNDOFF,VulkanGasRemap.Field.RETAINED_ROUNDOFF,VulkanGasRemap.Field.MERGE_ROUNDOFF,VulkanGasRemap.Field.AUDIT_ENERGY})result.add(remap.column(field));return List.copyOf(result);}
    }
    static Layout layout(VulkanGasThermodynamics.Layout sourceEOS,VulkanGasGeometry.Layout oldGeometry,
                         int occupiedWord,int cellsWord,int planesWord,int microWord,int birthNodeWord,
                         VulkanGasRemap.AmbientTables ambient,int nodeCapacity,int groupCapacity,int partCapacity,long base,long alignment){
        var liquid=oldGeometry.totals().source();var source=sourceEOS.gas();
        if(base<Math.max(sourceEOS.endOffset(),oldGeometry.endOffset())||nodeCapacity<source.nodeCapacity())throw new IllegalArgumentException("Phase gas topology inputs overlap");
        var totals=VulkanLiquidCellVolumes.layout(liquid,Math.addExact(liquid.limbCapacity(),liquid.cellCount()),oldGeometry.totals().quantumExponent(),base,alignment);
        var geometry=VulkanGasGeometry.layout(totals,oldGeometry.masksWordOffset(),occupiedWord,cellsWord,planesWord,microWord,oldGeometry.sealedExterior(),oldGeometry.cellSize(),totals.endOffset(),alignment);
        var retained=new VulkanGasStateLayout(liquid.sizeX(),liquid.sizeY(),liquid.sizeZ(),source.nodeCapacity(),source.definitions(),geometry.endOffset(),alignment);
        var future=new VulkanGasStateLayout(liquid.sizeX(),liquid.sizeY(),liquid.sizeZ(),nodeCapacity,source.definitions(),retained.endOffset(),alignment);
        var lineage=VulkanGasLineage.geometryLayout(source,oldGeometry,geometry,sourceEOS.nodeVolumesWordOffset(),birthNodeWord,groupCapacity,future.endOffset(),alignment);
        var remap=VulkanGasRemap.layout(lineage,sourceEOS,retained,future,ambient,partCapacity,lineage.endOffset(),alignment);
        return new Layout(totals,geometry,retained,future,lineage,remap,remap.endOffset());
    }
    private final VulkanLiquidCellVolumes volumes;private final VulkanGasGeometry geometry;
    private final VulkanGasLineage lineage;private final VulkanGasRemap remap;
    VulkanPhaseGasTopology(VulkanDeviceContext context){
        volumes=new VulkanLiquidCellVolumes(context);VulkanGasGeometry g=null;VulkanGasLineage l=null;
        try{g=new VulkanGasGeometry(context);l=new VulkanGasLineage(context);remap=new VulkanGasRemap(context);geometry=g;lineage=l;}
        catch(RuntimeException|Error failure){if(l!=null)l.close();if(g!=null)g.close();volumes.close();throw failure;}
    }
    void rebindRanges(FlipKernelBindings.BufferRange arena,FlipKernelBindings.BufferRange errors){volumes.rebindRanges(arena,arena,errors);geometry.rebindRanges(arena,errors);lineage.rebindRanges(arena,errors);remap.rebindRanges(arena,errors);}
    /** Liquid CELLS/PLANES/MICRO and occupied solids must already describe the published phase successor. */
    void recordGeometry(VkCommandBuffer command,Layout layout){volumes.record(command,layout.totals());geometry.recordGeometry(command,layout.geometry());geometry.recordConnectivity(command,layout.geometry());geometry.recordComponents(command,layout.geometry());}
    /** Source EOS must already include phase-created/removed inventory; no compression work is applied. */
    void recordRemap(VkCommandBuffer command,Layout layout){lineage.record(command,layout.lineage());remap.recordPrepare(command,layout.remap());remap.recordFinishFixedEnergy(command,layout.remap());}
    void recordRemap(VkCommandBuffer command,Layout layout,VulkanGasGeometry.Layout previous){lineage.record(command,layout.lineage(),previous);remap.recordPrepare(command,layout.remap());remap.recordFinishFixedEnergy(command,layout.remap());}
    void recordClearPostings(VkCommandBuffer command,Layout layout,GpuBuffer arena){try(var stack=stackPush()){
        VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,stack);
        for(var column:layout.postingColumns())if(column.bytes()>0)vkCmdFillBuffer(command,arena.handle(),column.offset(),column.bytes(),0);
        VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);
    }}
    @Override public void close(){try{remap.close();}finally{try{lineage.close();}finally{try{geometry.close();}finally{volumes.close();}}}}
}

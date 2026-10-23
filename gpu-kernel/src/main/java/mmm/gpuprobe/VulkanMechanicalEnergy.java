package mmm.gpuprobe;

import org.lwjgl.vulkan.VkCommandBuffer;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Closes actual force K, transported PE and gas compression work on connected caloric bodies. */
final class VulkanMechanicalEnergy implements AutoCloseable {
    record GranularEnergy(VulkanLiquidThermodynamics.Layout thermal,int sourceHeatWordOffset,int externalWorkWordOffset) { }
    record Layout(VulkanMechanicalBodies.Layout bodies,VulkanLiquidMomentum.Layout momentum,
                  VulkanLiquidThermodynamics.Layout thermal,int gasWorkWordOffset,int sourceHeatWordOffset,int externalWorkWordOffset,
                  GranularEnergy granular,
                  VulkanLiquidStateLayout.Column header,VulkanLiquidStateLayout.Column faceTerms,
                  VulkanLiquidStateLayout.Column potentialTerms,VulkanLiquidStateLayout.Column vertexData,
                  VulkanLiquidStateLayout.Column blockCounts,VulkanLiquidStateLayout.Column blockOffsets,
                  VulkanLiquidStateLayout.Column blockRoots,VulkanLiquidStateLayout.Column partials,
                  VulkanLiquidStateLayout.Column bodyData,VulkanLiquidStateLayout.Column energyChanges,
                  VulkanLiquidStateLayout.Column dispatch,VulkanLiquidStateLayout.Column scanWorkspace,
                  VulkanLiquidStateLayout.Column granularTerms,VulkanLiquidStateLayout.Column granularChanges,long endOffset) {
        ByteBuffer headerBytes(){return headerBytes(momentum.source());}
        ByteBuffer headerBytes(VulkanLiquidStateLayout source) {
            var out=ByteBuffer.allocateDirect(512).order(ByteOrder.LITTLE_ENDIAN);int[] data=new int[128];
            var target=thermal.liquid();data[0]=source.sizeX();data[1]=source.sizeY();data[2]=source.sizeZ();
            data[3]=source.cellCount();data[4]=bodies.vertices();data[5]=bodies.faces();data[6]=source.stockCapacity();data[7]=target.stockCapacity();
            data[8]=momentum.projectedFacesWordOffset();data[9]=word(momentum.cellMasses());data[10]=word(momentum.routes().column(VulkanLiquidRouteLayout.Field.MASS_UNITS));
            data[11]=gasWorkWordOffset;data[12]=word(bodies.parents());data[13]=word(bodies.offsets());data[14]=word(bodies.members());data[15]=word(bodies.counts());
            data[16]=word(thermal.heatCapacities());data[17]=word(thermal.coldEnergies());
            data[18]=sourceHeatWordOffset;data[19]=externalWorkWordOffset;
            var fields=List.of(faceTerms,potentialTerms,vertexData,blockCounts,blockOffsets,blockRoots,partials,bodyData,energyChanges,dispatch);
            for(int i=0;i<fields.size();i++)data[20+i]=word(fields.get(i));
            for(var field:VulkanLiquidStateLayout.Field.values()){
                data[40+field.ordinal()]=word(source.column(field));data[64+field.ordinal()]=word(target.column(field));
            }
            data[83]=bodies.gasNodeCapacity();
            if(granular!=null){var state=bodies.granular().state();var pressure=bodies.granular().pressure();
                data[84]=state.particleCapacity();data[85]=state.word(VulkanGranularStateLayout.Field.COUNTS);
                data[86]=word(granular.thermal().heatCapacities());data[87]=word(granular.thermal().coldEnergies());
                data[88]=state.word(VulkanGranularStateLayout.Field.INTERNAL_ENERGY);
                data[89]=pressure.word(VulkanGranularPressure.Field.SOURCE_K);data[90]=pressure.word(VulkanGranularPressure.Field.FINAL_K);
                data[91]=pressure.word(VulkanGranularPressure.Field.SOURCE_Y);data[92]=pressure.word(VulkanGranularPressure.Field.FINAL_Y);
                data[93]=state.word(VulkanGranularStateLayout.Field.MASS_UNITS);data[94]=word(granularTerms);data[95]=word(granularChanges);
                data[96]=granular.externalWorkWordOffset();data[97]=granular.sourceHeatWordOffset();
            }
            for(int i=0;i<data.length;i++)out.putInt(4*i,data[i]);return out;
        }
        void initializeHeader(ByteBuffer destination){destination.duplicate().position(Math.toIntExact(header.offset())).put(headerBytes());}
        /** Actual stored U change minus the physical work; exact binary reduction retains all rounding. */
        List<VulkanEnergyLedger.Stream> roundoffStreams() {
            var streams=new java.util.ArrayList<VulkanEnergyLedger.Stream>();
            streams.add(stream(faceTerms,-1));streams.add(stream(potentialTerms,-1));streams.add(stream(energyChanges,1));
            if(gasWorkWordOffset>=0)streams.add(new VulkanEnergyLedger.Stream(1,gasWorkWordOffset,2*bodies.gasNodeCapacity(),2,1));
            if(sourceHeatWordOffset>=0)streams.add(new VulkanEnergyLedger.Stream(1,sourceHeatWordOffset,bodies.source().cellCount(),2,1));
            if(externalWorkWordOffset>=0)streams.add(new VulkanEnergyLedger.Stream(1,externalWorkWordOffset,bodies.source().cellCount(),2,-1));
            if(granular!=null){
                streams.add(stream(granularTerms,-1));streams.add(stream(granularChanges,1));int count=bodies.granular().state().particleCapacity();
                if(granular.sourceHeatWordOffset()>=0)streams.add(new VulkanEnergyLedger.Stream(1,granular.sourceHeatWordOffset(),count,2,1));
                if(granular.externalWorkWordOffset()>=0)streams.add(new VulkanEnergyLedger.Stream(1,granular.externalWorkWordOffset(),count,2,-1));
            }
            return List.copyOf(streams);
        }
    }
    static Layout layout(VulkanMechanicalBodies.Layout bodies,VulkanLiquidMomentum.Layout momentum,
                         VulkanLiquidThermodynamics.Layout thermal,int gasWorkWordOffset,long scratchBase,long alignment) {
        return layout(bodies,momentum,thermal,gasWorkWordOffset,-1,-1,scratchBase,alignment);
    }
    /** Source heat is already carried by accepted material routes; external work enters the system once. */
    static Layout layout(VulkanMechanicalBodies.Layout bodies,VulkanLiquidMomentum.Layout momentum,
                         VulkanLiquidThermodynamics.Layout thermal,int gasWorkWordOffset,int sourceHeatWordOffset,
                         int externalWorkWordOffset,long scratchBase,long alignment) {
        return layout(bodies,momentum,thermal,gasWorkWordOffset,sourceHeatWordOffset,externalWorkWordOffset,null,scratchBase,alignment);
    }
    static Layout layout(VulkanMechanicalBodies.Layout bodies,VulkanLiquidMomentum.Layout momentum,
                         VulkanLiquidThermodynamics.Layout thermal,int gasWorkWordOffset,int sourceHeatWordOffset,
                         int externalWorkWordOffset,GranularEnergy granular,long scratchBase,long alignment){
        if(scratchBase<Math.max(bodies.endOffset(),Math.max(momentum.endOffset(),thermal.endOffset()))
                ||alignment<8||(alignment&(alignment-1))!=0||thermal.liquid()!=momentum.output()
                ||bodies.source().cellCount()!=thermal.liquid().cellCount())throw new IllegalArgumentException("Mechanical energy layouts differ");
        if((granular==null)!=(bodies.granular()==null)||granular!=null&&(scratchBase<granular.thermal().endOffset()
                ||granular.thermal().inventory().countsWord()!=bodies.granular().state().word(VulkanGranularStateLayout.Field.COUNTS)))
            throw new IllegalArgumentException("Granular caloric inventory differs from mechanical bodies");
        int vertices=bodies.vertices();long[] cursor={scratchBase};var header=column(cursor,128,4,alignment);
        var faces=column(cursor,Math.multiplyExact(bodies.faces(),2),8,alignment);
        var potential=column(cursor,Math.multiplyExact(momentum.source().stockCapacity(),2),8,alignment);
        var vertex=column(cursor,Math.multiplyExact(vertices,4),8,alignment);
        var counts=column(cursor,vertices,4,alignment);var offsets=column(cursor,Math.addExact(vertices,1),4,alignment);
        var roots=column(cursor,vertices,4,alignment);var partials=column(cursor,Math.multiplyExact(vertices,4),8,alignment);
        var result=column(cursor,Math.multiplyExact(vertices,4),8,alignment);
        var changes=column(cursor,Math.multiplyExact(thermal.liquid().stockCapacity(),2),8,alignment);
        var dispatch=column(cursor,3,4,alignment);var scan=column(cursor,Math.toIntExact(VulkanPrefixScan.workspaceBytes(vertices)/4),4,alignment);
        int particles=granular==null?0:bodies.granular().state().particleCapacity();
        var granularTerms=column(cursor,Math.multiplyExact(particles,4),8,alignment);var granularChanges=column(cursor,Math.multiplyExact(particles,2),8,alignment);
        return new Layout(bodies,momentum,thermal,gasWorkWordOffset,sourceHeatWordOffset,externalWorkWordOffset,granular,header,faces,potential,vertex,counts,offsets,roots,partials,result,changes,dispatch,scan,granularTerms,granularChanges,align(cursor[0],alignment));
    }
    private final FlipKernelBindings bindings;
    private final VulkanPrefixScan scan;
    private FlipKernelBindings.BufferRange candidate;
    VulkanMechanicalEnergy(VulkanDeviceContext context){
        bindings=new FlipKernelBindings(context,3,shader(),24);
        try{scan=new VulkanPrefixScan(context);}catch(RuntimeException|LinkageError failure){bindings.close();throw failure;}
    }
    void rebindRanges(FlipKernelBindings.BufferRange source,FlipKernelBindings.BufferRange candidate,FlipKernelBindings.BufferRange errors){
        bindings.rebindRanges(List.of(source,candidate,errors));scan.rebindRanges(candidate,candidate,candidate,errors);this.candidate=candidate;
    }
    /** Run after accepted momentum mixing heat and stock caloric fields, with stable body CSR. */
    void record(VkCommandBuffer command,Layout layout,double cellSize,double gravity){
        stage(command,layout,0,layout.bodies().faces(),cellSize,gravity,false);
        stage(command,layout,1,layout.momentum().source().stockCapacity(),cellSize,gravity,false);
        if(layout.granular()!=null)stage(command,layout,8,layout.bodies().granular().state().particleCapacity(),cellSize,gravity,false);
        stage(command,layout,2,layout.bodies().vertices(),cellSize,gravity,false);
        scan.record(command,layout.bodies().vertices(),word(layout.blockCounts()),word(layout.blockOffsets()),word(layout.scanWorkspace()));
        stage(command,layout,3,layout.bodies().vertices(),cellSize,gravity,false);
        stage(command,layout,4,layout.bodies().vertices(),cellSize,gravity,true);
        stage(command,layout,5,layout.bodies().vertices(),cellSize,gravity,true);
        stage(command,layout,6,layout.thermal().liquid().stockCapacity(),cellSize,gravity,false);
        stage(command,layout,7,layout.thermal().liquid().cellCount(),cellSize,gravity,false);
        if(layout.granular()!=null)stage(command,layout,9,layout.bodies().granular().state().particleCapacity(),cellSize,gravity,false);
    }
    private void stage(VkCommandBuffer command,Layout layout,int stage,int count,double cellSize,double gravity,boolean indirect){
        if(count==0)return;int groups=(count+127)/128;
        try(var stack=stackPush()) {
            bindings.bind(command,stack);var push=stack.malloc(24).putInt(word(layout.header())).putInt(stage).putDouble(cellSize).putDouble(gravity).flip();
            vkCmdPushConstants(command,bindings.pipelineLayout(),VK_SHADER_STAGE_COMPUTE_BIT,0,push);
            if(indirect){
                VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,
                        VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT,VK_ACCESS_INDIRECT_COMMAND_READ_BIT,stack);
                vkCmdDispatchIndirect(command,candidate.buffer().handle(),candidate.offset()+layout.dispatch().offset());
            }else vkCmdDispatch(command,Math.min(groups,65535),(groups+65534)/65535,1);
            VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);
        }
    }
    private static VulkanEnergyLedger.Stream stream(VulkanLiquidStateLayout.Column column,int sign){return new VulkanEnergyLedger.Stream(1,word(column),column.count(),2,sign);}
    private static int word(VulkanLiquidStateLayout.Column column){return Math.toIntExact(column.offset()/4);}
    private static long align(long value,long alignment){return Math.addExact(value,alignment-1)&-alignment;}
    private static VulkanLiquidStateLayout.Column column(long[] cursor,int count,int width,long alignment){
        var result=new VulkanLiquidStateLayout.Column(align(cursor[0],alignment),count,width);cursor[0]=result.offset()+result.bindingBytes();return result;
    }
    private static String shader(){
        try(var input=VulkanMechanicalEnergy.class.getResourceAsStream("/assets/mmm_gpu_probe/shaders/mechanical-energy.comp")){
            if(input==null)throw new IllegalStateException("Mechanical energy shader missing");return new String(input.readAllBytes(),StandardCharsets.UTF_8);
        }catch(IOException failure){throw new IllegalStateException("Mechanical energy shader unavailable",failure);}
    }
    @Override public void close(){try{scan.close();}finally{bindings.close();}}
}

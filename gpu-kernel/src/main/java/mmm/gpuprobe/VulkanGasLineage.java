package mmm.gpuprobe;

import org.lwjgl.vulkan.VkCommandBuffer;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.List;
import static mmm.gpuprobe.VulkanRegionStorage.barrier;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Accepted liquid motion transports gas origins; one sparse source/target graph serves N, U and volume work. */
final class VulkanGasLineage implements AutoCloseable {
    enum Field {
        CELL_ROUTES(8), EDGE_SOURCE(4), EDGE_TARGET(4), EDGE_WEIGHT(8), EDGE_GROUP(4),
        HASH(4), LIVE_COUNTS(4), HASH_GROUPS(4), GROUP_SOURCE(4), GROUP_TARGET(4),
        GROUP_WEIGHT(8), GROUP_OLD_CONTROL(8), GROUP_FUTURE_CONTROL(8),
        GROUP_COUNTS(4), GROUP_OFFSETS(4), GROUP_CURSORS(4), GROUP_EDGES(4),
        SOURCE_COUNTS(4), SOURCE_OFFSETS(4), SOURCE_CURSORS(4), SOURCE_GROUPS(4),
        TARGET_COUNTS(4), TARGET_OFFSETS(4), TARGET_CURSORS(4), TARGET_GROUPS(4),
        RETAINED_OLD_VOLUMES(8), FUTURE_CONTROL_VOLUMES(8), ESCAPED_FRACTIONS(8), SCAN(4), DISPATCH(4), LONG_ROWS(4);
        final int width;Field(int width){this.width=width;}
    }
    /** Sources are encoded as 0 explicit vacuum, 1 ambient, 2+i old GasState row i.
     * Targets are 0 ambient, 1+i future finite geometry ordinal. Unique groups share both CSR orders.
     * Geometry and old inventory must be canonical and of the same generation. All addresses belong
     * to the candidate arena; it must still contain the old inventory and geometry while preparing future geometry.
     * Control volumes are geometric doubles. Integer inventories are partitioned downstream with exact remainder ownership.
     */
    static final class Layout {
        final VulkanGasStateLayout gas;
        final VulkanGasGeometry.Layout oldGeometry,futureGeometry;
        final VulkanLiquidAcceptedVolumes.Layout accepted;
        final int faceFluxWordOffset,oldNodeVolumesWordOffset,groupCapacity,hashCapacity,edges;
        final VulkanLiquidStateLayout.Column header;
        final EnumMap<Field,VulkanLiquidStateLayout.Column> columns=new EnumMap<>(Field.class);
        final long endOffset;
        Layout(VulkanGasStateLayout gas,VulkanGasGeometry.Layout oldGeometry,VulkanGasGeometry.Layout futureGeometry,
               VulkanLiquidAcceptedVolumes.Layout accepted,int faceFluxWordOffset,int oldNodeVolumesWordOffset,
               int groupCapacity,long scratchBase,long alignment) {
            this.gas=gas;this.oldGeometry=oldGeometry;this.futureGeometry=futureGeometry;this.accepted=accepted;
            this.faceFluxWordOffset=faceFluxWordOffset;this.oldNodeVolumesWordOffset=oldNodeVolumesWordOffset;
            this.groupCapacity=groupCapacity;edges=Math.multiplyExact(oldGeometry.cells(),7);
            if(oldGeometry.cells()!=futureGeometry.cells()||oldGeometry.cells()!=accepted.cells()||groupCapacity<1
                    ||faceFluxWordOffset<0||oldNodeVolumesWordOffset<0||alignment<8||(alignment&(alignment-1))!=0
                    ||scratchBase<Math.max(Math.max(oldGeometry.endOffset(),futureGeometry.endOffset()),Math.max(gas.endOffset(),accepted.endOffset())))
                throw new IllegalArgumentException("Invalid gas lineage layout");
            int required=Math.multiplyExact(edges,2),hash=1;while(hash<required)hash=Math.multiplyExact(hash,2);hashCapacity=hash;
            long[] cursor={scratchBase};header=VulkanGasLineage.column(cursor,96,4,alignment);
            for(Field field:Field.values()) {
                int count=switch(field) {
                    case CELL_ROUTES,EDGE_SOURCE,EDGE_TARGET,EDGE_WEIGHT,EDGE_GROUP,GROUP_EDGES->edges;
                    case HASH,HASH_GROUPS->hashCapacity;
                    case LIVE_COUNTS->4;
                    case LONG_ROWS->Math.addExact(gas.nodeCapacity()+2,oldGeometry.cells()+1);
                    case SOURCE_COUNTS,SOURCE_CURSORS->Math.addExact(gas.nodeCapacity(),2);
                    case SOURCE_OFFSETS->Math.addExact(gas.nodeCapacity(),3);
                    case TARGET_COUNTS,TARGET_CURSORS->Math.addExact(oldGeometry.cells(),1);
                    case TARGET_OFFSETS->Math.addExact(oldGeometry.cells(),2);
                    case GROUP_OFFSETS->Math.addExact(groupCapacity,1);
                    case RETAINED_OLD_VOLUMES,FUTURE_CONTROL_VOLUMES,ESCAPED_FRACTIONS->gas.nodeCapacity();
                    case DISPATCH->21;
                    case SCAN->Math.toIntExact(VulkanPrefixScan.workspaceBytes(Math.max(oldGeometry.cells()+1,Math.max(gas.nodeCapacity()+2,groupCapacity)))/4);
                    default->groupCapacity;
                };
                columns.put(field,VulkanGasLineage.column(cursor,count,field.width,alignment));
            }
            endOffset=align(cursor[0],alignment);Math.toIntExact(endOffset/4);
        }
        VulkanLiquidStateLayout.Column column(Field field){return columns.get(field);}
        int word(Field field){return VulkanGasLineage.word(column(field));}
        int headerWordOffset(){return VulkanGasLineage.word(header);}
        long endOffset(){return endOffset;}
        /** Actual unique group count, also valid after capacity error 64. */
        long requiredGroupsOffset(){return column(Field.LIVE_COUNTS).offset();}
        ByteBuffer headerBytes(){
            var out=ByteBuffer.allocateDirect(384).order(ByteOrder.LITTLE_ENDIAN);int[] data=new int[96];
            var liquid=oldGeometry.totals().source();data[0]=liquid.sizeX();data[1]=liquid.sizeY();data[2]=liquid.sizeZ();
            data[3]=oldGeometry.cells();data[4]=gas.nodeCapacity();data[5]=groupCapacity;data[6]=hashCapacity;data[7]=edges;
            data[8]=VulkanGasLineage.word(oldGeometry.cellNodes());data[9]=VulkanGasLineage.word(futureGeometry.cellNodes());
            data[10]=VulkanGasLineage.word(oldGeometry.dryVolumes());data[11]=VulkanGasLineage.word(futureGeometry.dryVolumes());
            data[12]=oldGeometry.masksWordOffset();data[13]=faceFluxWordOffset;data[14]=accepted.acceptedWordOffset();
            data[15]=accepted.sourceCellVolumes().quantumExponent();data[16]=oldNodeVolumesWordOffset;
            data[17]=Math.toIntExact(gas.column(VulkanGasStateLayout.Field.COUNTS).offset()/4);
            data[18]=Math.toIntExact(gas.column(VulkanGasStateLayout.Field.ROW_COMPONENT_OFFSETS).offset()/4);
            data[19]=VulkanGasLineage.word(futureGeometry.componentOffsets());data[20]=VulkanGasLineage.word(futureGeometry.componentMembers());
            data[21]=VulkanGasLineage.word(futureGeometry.componentCount());data[22]=VulkanGasLineage.word(oldGeometry.componentCount());data[23]=Math.toIntExact(gas.column(VulkanGasStateLayout.Field.STANDARD_VOLUME_NANOS).offset()/4);
            for(Field field:Field.values())data[32+field.ordinal()]=word(field);
            for(int i=0;i<data.length;i++)out.putInt(4*i,data[i]);
            double size=oldGeometry.cellSize();out.putDouble(4*80,size*size*size);return out;
        }
        void initializeHeader(ByteBuffer arena){arena.duplicate().position(Math.toIntExact(header.offset())).put(headerBytes());}
    }
    static Layout layout(VulkanGasStateLayout gas,VulkanGasGeometry.Layout oldGeometry,VulkanGasGeometry.Layout futureGeometry,
                         VulkanLiquidAcceptedVolumes.Layout accepted,int faceFluxWordOffset,int oldNodeVolumesWordOffset,
                         int groupCapacity,long scratchBase,long alignment) {
        return new Layout(gas,oldGeometry,futureGeometry,accepted,faceFluxWordOffset,oldNodeVolumesWordOffset,groupCapacity,scratchBase,alignment);
    }
    private final FlipKernelBindings bindings;
    private final VulkanPrefixScan scan;
    private FlipKernelBindings.BufferRange arena;
    VulkanGasLineage(VulkanDeviceContext context){
        bindings=new FlipKernelBindings(context,2,shader(),12);
        try{scan=new VulkanPrefixScan(context);}catch(RuntimeException|LinkageError failure){bindings.close();throw failure;}
    }
    void rebindRanges(FlipKernelBindings.BufferRange arena,FlipKernelBindings.BufferRange errors){
        this.arena=arena;bindings.rebindRanges(List.of(arena,errors));scan.rebindRanges(arena,arena,arena,errors);
    }
    void record(VkCommandBuffer command,Layout layout){
        stage(command,layout,0,Math.max(layout.hashCapacity,Math.max(layout.groupCapacity,layout.oldGeometry.cells()+1)),false);
        stage(command,layout,1,layout.oldGeometry.cells(),false);
        stage(command,layout,2,layout.oldGeometry.cells(),false);
        stage(command,layout,3,layout.edges,false);
        stage(command,layout,4,1,false);
        stage(command,layout,5,layout.edges,false);
        prefix(command,layout,layout.groupCapacity,Field.GROUP_COUNTS,Field.GROUP_OFFSETS);
        stage(command,layout,6,layout.edges,false);
        stage(command,layout,13,layout.groupCapacity,false);
        stage(command,layout,12,1,false);
        stage(command,layout,7,layout.groupCapacity,true);
        prefix(command,layout,layout.gas.nodeCapacity()+2,Field.SOURCE_COUNTS,Field.SOURCE_OFFSETS);
        prefix(command,layout,layout.oldGeometry.cells()+1,Field.TARGET_COUNTS,Field.TARGET_OFFSETS);
        stage(command,layout,8,layout.groupCapacity,false);
        stage(command,layout,17,Math.max(layout.gas.nodeCapacity()+2,layout.oldGeometry.cells()+1),false);
        stage(command,layout,12,1,false);
        stage(command,layout,14,layout.gas.nodeCapacity()+2,false);
        stage(command,layout,9,layout.gas.nodeCapacity()+2,true);
        stage(command,layout,15,layout.oldGeometry.cells()+1,false);
        stage(command,layout,10,layout.oldGeometry.cells()+1,true);
        stage(command,layout,16,layout.gas.nodeCapacity(),false);
        stage(command,layout,11,layout.gas.nodeCapacity(),true);
    }
    private void prefix(VkCommandBuffer command,Layout layout,int count,Field input,Field output){
        scan.record(command,count,layout.word(input),layout.word(output),layout.word(Field.SCAN));
    }
    private void stage(VkCommandBuffer command,Layout layout,int stage,int count,boolean perGroup){
        if(count==0)return;int groups=perGroup?count:(int)(((long)count+127)/128);
        try(var stack=stackPush()){
            bindings.bind(command,stack);vkCmdPushConstants(command,bindings.pipelineLayout(),VK_SHADER_STAGE_COMPUTE_BIT,0,
                    stack.ints(layout.headerWordOffset(),stage,count));
            if((stage>=7&&stage<=11)||(stage>=14&&stage<=16)){
                barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,
                        VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT,VK_ACCESS_INDIRECT_COMMAND_READ_BIT,stack);
                int slot=stage>=14?(stage==15?6:5):stage-7;
                vkCmdDispatchIndirect(command,arena.buffer().handle(),arena.offset()+layout.column(Field.DISPATCH).offset()+12L*slot);
            }else vkCmdDispatch(command,Math.min(groups,65535),(groups+65534)/65535,1);
            barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);
        }
    }
    private static int word(VulkanLiquidStateLayout.Column column){return Math.toIntExact(column.offset()/4);}
    private static long align(long n,long a){return Math.addExact(n,a-1)&-a;}
    private static VulkanLiquidStateLayout.Column column(long[] cursor,int count,int width,long alignment){
        var result=new VulkanLiquidStateLayout.Column(align(cursor[0],alignment),count,width);
        cursor[0]=Math.addExact(result.offset(),result.bindingBytes());return result;
    }
    private static String shader(){
        try(var input=VulkanGasLineage.class.getResourceAsStream("/assets/mmm_gpu_probe/shaders/gas-lineage.comp")){
            if(input==null)throw new IllegalStateException("Gas lineage shader missing");
            return new String(input.readAllBytes(),StandardCharsets.UTF_8);
        }catch(IOException failure){throw new IllegalStateException("Gas lineage shader unavailable",failure);}
    }
    @Override public void close(){try{scan.close();}finally{bindings.close();}}
}

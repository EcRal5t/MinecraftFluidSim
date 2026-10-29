package mmm.gpu;

import org.lwjgl.vulkan.VkCommandBuffer;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.List;
import static mmm.gpu.VulkanRegionStorage.barrier;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Accepted liquid motion transports gas origins; one sparse source/target graph serves N, U and volume work. */
final class VulkanGasLineage implements AutoCloseable {
    private enum Header {
        SIZE_X(0),
        SIZE_Y(1),
        SIZE_Z(2),
        CELL_COUNT(3),
        NODE_CAPACITY(4),
        GROUP_CAPACITY(5),
        HASH_CAPACITY(6),
        EDGE_CAPACITY(7),
        OLD_CELL_NODES(8),
        FUTURE_CELL_NODES(9),
        OLD_DRY_VOLUMES(10),
        FUTURE_DRY_VOLUMES(11),
        MASKS(12),
        FACE_FLUX(13),
        ACCEPTED_DESCRIPTORS(14),
        QUANTUM_EXPONENT(15),
        OLD_NODE_VOLUMES(16),
        GAS_COUNTS(17),
        GAS_COMPONENT_OFFSETS(18),
        FUTURE_COMPONENT_OFFSETS(19),
        FUTURE_COMPONENT_MEMBERS(20),
        FUTURE_COMPONENT_COUNT(21),
        OLD_COMPONENT_COUNT(22),
        GAS_STANDARD_VOLUME(23),
        GAS_MASS(24),
        GAS_ENERGY(25),
        GEOMETRY_ONLY(26),
        BIRTH_NODE(27),
        CELL_VOLUME(80);
        final int word;
        Header(int word) { this.word = word; }
    }
    private static final int WORK_FIELDS = 32;
    private static String shaderLayout() {
        var result = new StringBuilder();
        for (var field : Header.values()) result.append("#define H_").append(field.name()).append(' ').append(field.word).append('\n');
        result.append("#define WORK_FIELDS ").append(WORK_FIELDS).append('\n');
        return result + VulkanShaderLayout.fields("FIELD_", Field.values());
    }

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
        final int faceFluxWordOffset,oldNodeVolumesWordOffset,groupCapacity,hashCapacity,edges,birthNodeWordOffset;final boolean geometryOnly;
        final VulkanLiquidStateLayout.Column header;
        final EnumMap<Field,VulkanLiquidStateLayout.Column> columns=new EnumMap<>(Field.class);
        final long endOffset;
        Layout(VulkanGasStateLayout gas,VulkanGasGeometry.Layout oldGeometry,VulkanGasGeometry.Layout futureGeometry,
               VulkanLiquidAcceptedVolumes.Layout accepted,int faceFluxWordOffset,int oldNodeVolumesWordOffset,
               int groupCapacity,long scratchBase,long alignment) {
            this(gas,oldGeometry,futureGeometry,accepted,faceFluxWordOffset,oldNodeVolumesWordOffset,groupCapacity,-1,false,scratchBase,alignment);
        }
        Layout(VulkanGasStateLayout gas,VulkanGasGeometry.Layout oldGeometry,VulkanGasGeometry.Layout futureGeometry,
               VulkanLiquidAcceptedVolumes.Layout accepted,int faceFluxWordOffset,int oldNodeVolumesWordOffset,
               int groupCapacity,int birthNodeWordOffset,boolean geometryOnly,long scratchBase,long alignment){
            this.geometryOnly=geometryOnly;this.birthNodeWordOffset=birthNodeWordOffset;
            this.gas=gas;this.oldGeometry=oldGeometry;this.futureGeometry=futureGeometry;this.accepted=accepted;
            this.faceFluxWordOffset=faceFluxWordOffset;this.oldNodeVolumesWordOffset=oldNodeVolumesWordOffset;
            this.groupCapacity=groupCapacity;edges=Math.multiplyExact(oldGeometry.cells(),geometryOnly?2:7);
            if(oldGeometry.cells()!=futureGeometry.cells()||(!geometryOnly&&(accepted==null||oldGeometry.cells()!=accepted.cells()))||groupCapacity<1
                    ||(!geometryOnly&&faceFluxWordOffset<0)||oldNodeVolumesWordOffset<0||geometryOnly&&birthNodeWordOffset<0||alignment<8||(alignment&(alignment-1))!=0
                    ||scratchBase<Math.max(Math.max(oldGeometry.endOffset(),futureGeometry.endOffset()),Math.max(gas.endOffset(),accepted==null?0:accepted.endOffset())))
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
            var liquid=oldGeometry.totals().source();data[Header.SIZE_X.word]=liquid.sizeX();data[Header.SIZE_Y.word]=liquid.sizeY();data[Header.SIZE_Z.word]=liquid.sizeZ();
            data[Header.CELL_COUNT.word]=oldGeometry.cells();data[Header.NODE_CAPACITY.word]=gas.nodeCapacity();data[Header.GROUP_CAPACITY.word]=groupCapacity;data[Header.HASH_CAPACITY.word]=hashCapacity;data[Header.EDGE_CAPACITY.word]=edges;
            data[Header.OLD_CELL_NODES.word]=VulkanGasLineage.word(oldGeometry.cellNodes());data[Header.FUTURE_CELL_NODES.word]=VulkanGasLineage.word(futureGeometry.cellNodes());
            data[Header.OLD_DRY_VOLUMES.word]=VulkanGasLineage.word(oldGeometry.dryVolumes());data[Header.FUTURE_DRY_VOLUMES.word]=VulkanGasLineage.word(futureGeometry.dryVolumes());
            data[Header.MASKS.word]=oldGeometry.masksWordOffset();data[Header.FACE_FLUX.word]=faceFluxWordOffset;data[Header.ACCEPTED_DESCRIPTORS.word]=accepted==null?0:VulkanGasLineage.word(accepted.descriptors());
            data[Header.QUANTUM_EXPONENT.word]=accepted==null?0:accepted.sourceCellVolumes().quantumExponent();data[Header.OLD_NODE_VOLUMES.word]=oldNodeVolumesWordOffset;
            data[Header.GAS_COUNTS.word]=Math.toIntExact(gas.column(VulkanGasStateLayout.Field.COUNTS).offset()/4);
            data[Header.GAS_COMPONENT_OFFSETS.word]=Math.toIntExact(gas.column(VulkanGasStateLayout.Field.ROW_COMPONENT_OFFSETS).offset()/4);
            data[Header.FUTURE_COMPONENT_OFFSETS.word]=VulkanGasLineage.word(futureGeometry.componentOffsets());data[Header.FUTURE_COMPONENT_MEMBERS.word]=VulkanGasLineage.word(futureGeometry.componentMembers());
            data[Header.FUTURE_COMPONENT_COUNT.word]=VulkanGasLineage.word(futureGeometry.componentCount());data[Header.OLD_COMPONENT_COUNT.word]=VulkanGasLineage.word(oldGeometry.componentCount());data[Header.GAS_STANDARD_VOLUME.word]=Math.toIntExact(gas.column(VulkanGasStateLayout.Field.STANDARD_VOLUME_NANOS).offset()/4);
            data[Header.GAS_MASS.word]=Math.toIntExact(gas.column(VulkanGasStateLayout.Field.MASS_UNITS).offset()/4);data[Header.GAS_ENERGY.word]=Math.toIntExact(gas.column(VulkanGasStateLayout.Field.INTERNAL_ENERGY).offset()/4);
            data[Header.GEOMETRY_ONLY.word]=geometryOnly?1:0;data[Header.BIRTH_NODE.word]=birthNodeWordOffset;
            for(Field field:Field.values())data[WORK_FIELDS+field.ordinal()]=word(field);
            for(int i=0;i<data.length;i++)out.putInt(4*i,data[i]);
            double size=oldGeometry.cellSize();out.putDouble(4 * Header.CELL_VOLUME.word,size*size*size);return out;
        }
        void initializeHeader(ByteBuffer arena){arena.duplicate().position(Math.toIntExact(header.offset())).put(headerBytes());}
    }
    static Layout layout(VulkanGasStateLayout gas,VulkanGasGeometry.Layout oldGeometry,VulkanGasGeometry.Layout futureGeometry,
                         VulkanLiquidAcceptedVolumes.Layout accepted,int faceFluxWordOffset,int oldNodeVolumesWordOffset,
                         int groupCapacity,long scratchBase,long alignment) {
        return new Layout(gas,oldGeometry,futureGeometry,accepted,faceFluxWordOffset,oldNodeVolumesWordOffset,groupCapacity,scratchBase,alignment);
    }
    static Layout geometryLayout(VulkanGasStateLayout gas,VulkanGasGeometry.Layout oldGeometry,VulkanGasGeometry.Layout futureGeometry,
                                 int oldNodeVolumesWordOffset,int birthNodeWordOffset,int groupCapacity,long base,long alignment){
        return new Layout(gas,oldGeometry,futureGeometry,null,-1,oldNodeVolumesWordOffset,groupCapacity,birthNodeWordOffset,true,base,alignment);
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
        if(layout.geometryOnly)stage(command,layout,18,layout.oldGeometry.cells(),false);
        else{stage(command,layout,1,layout.oldGeometry.cells(),false);stage(command,layout,2,layout.oldGeometry.cells(),false);}
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
        if(layout.geometryOnly)stage(command,layout,19,layout.gas.nodeCapacity(),false);
        stage(command,layout,17,Math.max(layout.gas.nodeCapacity()+2,layout.oldGeometry.cells()+1),false);
        stage(command,layout,12,1,false);
        stage(command,layout,14,layout.gas.nodeCapacity()+2,false);
        stage(command,layout,9,layout.gas.nodeCapacity()+2,true);
        stage(command,layout,15,layout.oldGeometry.cells()+1,false);
        stage(command,layout,10,layout.oldGeometry.cells()+1,true);
        stage(command,layout,16,layout.gas.nodeCapacity(),false);
        stage(command,layout,11,layout.gas.nodeCapacity(),true);
    }
    void record(VkCommandBuffer command,Layout layout,VulkanGasGeometry.Layout previous){
        if(!layout.geometryOnly)throw new IllegalArgumentException("Phase overlap route required");
        try(var stack=stackPush()){
            barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,stack);
            vkCmdUpdateBuffer(command,arena.buffer().handle(),arena.offset()+layout.header.offset()+4L*8,stack.ints(word(previous.cellNodes())));
            vkCmdUpdateBuffer(command,arena.buffer().handle(),arena.offset()+layout.header.offset()+4L*10,stack.ints(word(previous.dryVolumes())));
            vkCmdUpdateBuffer(command,arena.buffer().handle(),arena.offset()+layout.header.offset()+4L*22,stack.ints(word(previous.componentCount())));
            barrier(command,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);
        }record(command,layout);
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
            return new String(input.readAllBytes(),StandardCharsets.UTF_8).replace("// HOST_LAYOUT", shaderLayout());
        }catch(IOException failure){throw new IllegalStateException("Gas lineage shader unavailable",failure);}
    }
    @Override public void close(){try{scan.close();}finally{bindings.close();}}
}

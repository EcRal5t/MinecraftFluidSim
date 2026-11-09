package mmm.gpu;

import org.lwjgl.vulkan.VkCommandBuffer;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.List;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Exact integer solid occupancy, derived from resident parcel support and deposit inventory. */
final class VulkanGranularGeometry implements AutoCloseable {
    private enum Header {
        SIZE_X(0),
        SIZE_Y(1),
        SIZE_Z(2),
        PARTICLE_CAPACITY(3),
        DEPOSIT_CAPACITY(4),
        CELL_COUNT(5),
        ENTRY_CAPACITY(6),
        COHERENT_SVQ(7),
        FIXED_MASKS(39);
        final int word;
        Header(int word) { this.word = word; }
    }
    private static final int WORK_FIELDS = 32, STATE_FIELDS = 8;
    private static String shaderLayout() {
        var result = new StringBuilder();
        for (var field : Header.values()) result.append("#define H_").append(field.name()).append(' ').append(field.word).append('\n');
        result.append("#define WORK_FIELDS ").append(WORK_FIELDS).append('\n');
        result.append("#define STATE_FIELDS ").append(STATE_FIELDS).append('\n');
        return result + VulkanShaderLayout.fields("FIELD_", Field.values()) + VulkanShaderLayout.fields("GRANULAR_", VulkanGranularStateLayout.Field.values());
    }

    enum Field { COUNTS(4),OFFSETS(4),CURSORS(4),ENTRIES(4),FIXED_SVQ(8),OCCUPIED_SVQ(8),SCAN(4);
        final int width;Field(int width){this.width=width;}}
    static final class Layout {
        private final VulkanGranularStateLayout state;
        private final VulkanLiquidStateLayout.Column coherentSvq,fixedMasks,header;
        private final EnumMap<Field,VulkanLiquidStateLayout.Column> columns=new EnumMap<>(Field.class);
        private final int entries;private final long endOffset;
        Layout(VulkanGranularStateLayout state,VulkanLiquidStateLayout.Column coherentSvq,VulkanLiquidStateLayout.Column fixedMasks,long scratchBase,long alignment){
            if(coherentSvq.count()!=state.cells()||coherentSvq.elementBytes()!=8||fixedMasks.count()!=state.cells()||fixedMasks.elementBytes()!=4||scratchBase<state.endOffset()
                    ||alignment<8||(alignment&(alignment-1))!=0)throw new IllegalArgumentException("Granular occupancy layout differs");
            this.state=state;this.coherentSvq=coherentSvq;this.fixedMasks=fixedMasks;entries=Math.addExact(8*state.particleCapacity(),state.depositCapacity());
            header=new VulkanLiquidStateLayout.Column(align(scratchBase,alignment),48,4);long cursor=header.offset()+header.bytes();
            for(Field field:Field.values()){
                int count=switch(field){case OFFSETS->state.cells()+1;case ENTRIES->entries;
                    case SCAN->Math.toIntExact(VulkanPrefixScan.workspaceBytes(state.cells())/4);default->state.cells();};
                var column=new VulkanLiquidStateLayout.Column(align(cursor,alignment),count,field.width);columns.put(field,column);cursor=column.offset()+column.bindingBytes();
            }endOffset=align(cursor,alignment);
        }
        VulkanLiquidStateLayout.Column column(Field field){return columns.get(field);}
        int word(Field field){return Math.toIntExact(column(field).offset()/4);}
        int headerWordOffset(){return Math.toIntExact(header.offset()/4);}
        VulkanGranularStateLayout state(){return state;}long endOffset(){return endOffset;}
        ByteBuffer headerBytes(){
            var result=ByteBuffer.allocateDirect(192).order(ByteOrder.LITTLE_ENDIAN);
            result.putInt(4 * Header.SIZE_X.word,state.sizeX()).putInt(4 * Header.SIZE_Y.word,state.sizeY()).putInt(4 * Header.SIZE_Z.word,state.sizeZ()).putInt(4 * Header.PARTICLE_CAPACITY.word,state.particleCapacity())
                    .putInt(4 * Header.DEPOSIT_CAPACITY.word,state.depositCapacity()).putInt(4 * Header.CELL_COUNT.word,state.cells()).putInt(4 * Header.ENTRY_CAPACITY.word,entries).putInt(4 * Header.COHERENT_SVQ.word,Math.toIntExact(coherentSvq.offset()/4)).putInt(4 * Header.FIXED_MASKS.word,Math.toIntExact(fixedMasks.offset()/4));
            for(var field:VulkanGranularStateLayout.Field.values())result.putInt(4*(STATE_FIELDS+field.ordinal()),state.word(field));
            for(var field:Field.values())result.putInt(4*(WORK_FIELDS+field.ordinal()),word(field));return result;
        }
        void initializeHeader(ByteBuffer arena){arena.duplicate().position(Math.toIntExact(header.offset())).put(headerBytes());}
    }
    private final FlipKernelBindings bindings;private final VulkanPrefixScan scan;
    VulkanGranularGeometry(VulkanDeviceContext context){bindings=new FlipKernelBindings(context,2,shader(),24);
        try{scan=new VulkanPrefixScan(context);}catch(RuntimeException|LinkageError failure){bindings.close();throw failure;}}
    void rebindRanges(FlipKernelBindings.BufferRange arena,FlipKernelBindings.BufferRange errors){
        bindings.rebindRanges(List.of(arena,errors));scan.rebindRanges(arena,arena,arena,errors);}
    /** Initializes support for newly imported positions or rebuilds it after accepted motion.
     * Geometry rounding is below one SVQ per cell; integer shares retain the complete parcel volume. */
    void recordSupports(VkCommandBuffer command,Layout layout,double cellSize){
        if(!(Double.isFinite(cellSize)&&cellSize>0))throw new IllegalArgumentException("Invalid particle cell size");
        stage(command,layout,0,layout.state.particleCapacity(),cellSize);
    }
    /** Existing support selections are retained exactly; coherent input excludes all granular inventory. */
    void recordOccupancy(VkCommandBuffer command,Layout layout){
        stage(command,layout,1,layout.state.cells(),1);stage(command,layout,2,layout.entries,1);
        scan.record(command,layout.state.cells(),layout.word(Field.COUNTS),layout.word(Field.OFFSETS),layout.word(Field.SCAN));
        stage(command,layout,3,layout.entries,1);stage(command,layout,4,layout.state.cells(),1);
    }
    private void stage(VkCommandBuffer command,Layout layout,int stage,int count,double cellSize){
        if(count==0)return;try(var stack=stackPush()){
            bindings.bind(command,stack);var push=stack.malloc(24).putInt(layout.headerWordOffset()).putInt(stage).putInt(count).putInt(0).putDouble(cellSize).flip();
            vkCmdPushConstants(command,bindings.pipelineLayout(),VK_SHADER_STAGE_COMPUTE_BIT,0,push);int groups=(count+127)/128;
            vkCmdDispatch(command,Math.min(groups,65535),(groups+65534)/65535,1);
            VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);
        }
    }
    private static long align(long v,long a){return Math.addExact(v,a-1)&-a;}
    private static String shader(){try(var input=VulkanGranularGeometry.class.getResourceAsStream("/assets/mmm_gpu_probe/shaders/granular-geometry.comp")){
        if(input==null)throw new IllegalStateException("Granular geometry shader missing");return new String(input.readAllBytes(),StandardCharsets.UTF_8).replace("// HOST_LAYOUT", shaderLayout());
    }catch(IOException failure){throw new IllegalStateException("Granular geometry shader unavailable",failure);}}
    @Override public void close(){try{scan.close();}finally{bindings.close();}}
}

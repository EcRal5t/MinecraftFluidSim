package mmm.gpu;

import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkBufferCopy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.EnumMap;
import java.util.List;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Resident bed lifecycle. Each column owns its settlement and wake calculation; coloured
 * neighbour pairs own slope transfers. The final gather merges material stocks and rebuilds
 * the complete species CSR before the ordinary joint liquid-capacity calculation. */
final class VulkanGranularTransitions implements AutoCloseable {
    private enum Header {
        SIZE_X(0),
        SIZE_Y(1),
        SIZE_Z(2),
        PARTICLE_CAPACITY(3),
        DEPOSIT_CAPACITY(4),
        INPUT_ROWS(5),
        ROW_CAPACITY(6),
        SPECIES_CAPACITY(7),
        MATERIAL_COUNT(8),
        SPECIES_COUNT(9),
        HASH_SIZE(10),
        MASKS(11),
        CELLS(12),
        FACES(13),
        VISCOSITY(14),
        MOMENTUM_X(15),
        MOMENTUM_Z(16),
        GEOMETRY_OFFSETS(17),
        GEOMETRY_ENTRIES(18),
        FIXED_SVQ(19),
        ACTIVITY_CELLS(20), SCREEN_CAPTURED(21), SCREEN_FACES(22);
        final int word;
        Header(int word) { this.word = word; }
    }
    private static final int WORK_FIELDS = 72, TARGET_FIELDS = 24, SOURCE_FIELDS = 48;
    private static String shaderLayout() {
        var result = new StringBuilder();
        for (var field : Header.values()) result.append("#define H_").append(field.name()).append(' ').append(field.word).append('\n');
        result.append("#define WORK_FIELDS ").append(WORK_FIELDS).append('\n');
        result.append("#define TARGET_FIELDS ").append(TARGET_FIELDS).append('\n');
        result.append("#define SOURCE_FIELDS ").append(SOURCE_FIELDS).append('\n');
        return result + VulkanShaderLayout.fields("FIELD_", Field.values()) + VulkanShaderLayout.fields("GRANULAR_", VulkanGranularStateLayout.Field.values());
    }

    enum Field {
        CONTROL(4),COLUMN_COUNTS(4),COLUMN_OFFSETS(4),COLUMN_CURSORS(4),COLUMN_ENTRIES(4),
        MODE(4),TARGET(4),MOVED_VOLUME(8),MOVED_MASS(8),POSITION(8),HEAT(8),
        CELL_BULK(8),CELL_MASS(8),HASH_KEYS(4),HASH_COUNTS(4),HASH_OFFSETS(4),
        ACTIVE_COUNTS(4),ACTIVE_OFFSETS(4),DESTINATIONS(4),ROW_COUNTS(4),ROW_OFFSETS(4),ROW_CURSORS(4),ROW_ENTRIES(4),
        SPECIES_COUNTS(4),MATERIALS(8),SCAN(4),CELL_SOLID(8),AVAILABLE_BULK(8),REACTION_IMPULSE(8),LIFT_TERMS(8),FINAL_ENERGY(8);
        final int width;Field(int width){this.width=width;}
    }
    static final class Layout {
        final VulkanGranularStateLayout state,source;
        final VulkanGranularGeometry.Layout geometry;
        final VulkanLiquidStateLayout.Column header;
        final EnumMap<Field,VulkanLiquidStateLayout.Column> columns=new EnumMap<>(Field.class);
        final int inputRows,hashSize,columnsCount;
        final long endOffset;
        private final int[] inputs;
        Layout(VulkanGranularStateLayout state,VulkanGranularGeometry.Layout geometry,
               VulkanLiquidStateLayout.Column cells,VulkanLiquidStateLayout.Column faces,VulkanLiquidStateLayout liquid,
               VulkanLiquidStateLayout.Column masks,VulkanLiquidStateLayout.Column viscosity,long base,long alignment){
            if(geometry.state()!=state||liquid.cellCount()!=state.cells()||base<Math.max(state.endOffset(),geometry.endOffset()))
                throw new IllegalArgumentException("Granular transition inputs differ");
            this.state=state;this.geometry=geometry;inputRows=state.particleCapacity()+state.depositCapacity();columnsCount=state.grid().columnCount();
            int hash=1;while(hash<Math.max(2,2*inputRows))hash=Math.multiplyExact(hash,2);hashSize=hash;
            source=new VulkanGranularStateLayout(state.grid(),state.particleCapacity(),state.depositCapacity(),state.speciesCapacity(),state.definitions(),base,alignment);
            header=new VulkanLiquidStateLayout.Column(align(source.endOffset(),alignment),128,4);long cursor=header.offset()+header.bytes();
            for(var f:Field.values()){
                int count=switch(f){
                    case CONTROL->12;
                    case COLUMN_COUNTS,COLUMN_CURSORS->columnsCount;
                    case COLUMN_OFFSETS->columnsCount+1;
                    case COLUMN_ENTRIES,MODE,TARGET,MOVED_VOLUME,MOVED_MASS,HEAT,ACTIVE_COUNTS->inputRows;
                    case POSITION->3*inputRows;
                    case ACTIVE_OFFSETS->inputRows+1;
                    case CELL_BULK,CELL_MASS,CELL_SOLID,AVAILABLE_BULK->state.cells();
                    case REACTION_IMPULSE->3*inputRows;
                    case LIFT_TERMS->17*inputRows;
                    case HASH_KEYS,HASH_COUNTS->hashSize;
                    case HASH_OFFSETS->hashSize+1;
                    case DESTINATIONS->2*inputRows;
                    case ROW_COUNTS,ROW_CURSORS,SPECIES_COUNTS->state.rowCapacity();
                    case FINAL_ENERGY->state.rowCapacity();
                    case ROW_OFFSETS->state.rowCapacity()+1;
                    case ROW_ENTRIES->2*inputRows+2*state.definitions().materialIds().size();
                    case MATERIALS->7*state.definitions().materialIds().size();
                    case SCAN->Math.toIntExact(VulkanPrefixScan.workspaceBytes(Math.max(hashSize,Math.max(state.rowCapacity(),columnsCount)))/4);
                };
                var c=new VulkanLiquidStateLayout.Column(align(cursor,alignment),count,f.width);columns.put(f,c);cursor=c.offset()+c.bindingBytes();
            }
            endOffset=align(cursor,alignment);
            inputs=new int[]{state.sizeX(),state.sizeY(),state.sizeZ(),state.particleCapacity(),state.depositCapacity(),inputRows,
                    state.rowCapacity(),state.speciesCapacity(),state.definitions().materialIds().size(),state.definitions().speciesIds().size(),
                    hashSize,word(masks),word(cells),word(faces),word(viscosity),
                    word(liquid.column(VulkanLiquidStateLayout.Field.MOMENTUM_X)),word(liquid.column(VulkanLiquidStateLayout.Field.MOMENTUM_Z)),
                    geometry.word(VulkanGranularGeometry.Field.OFFSETS),geometry.word(VulkanGranularGeometry.Field.ENTRIES),geometry.word(VulkanGranularGeometry.Field.FIXED_SVQ),-1,-1,-1};
        }
        void bindActivity(int cellWord) { inputs[Header.ACTIVITY_CELLS.word] = cellWord; }
        void bindScreenCaptured(int word) { inputs[Header.SCREEN_CAPTURED.word] = word; }
        void bindScreens(int word) { inputs[Header.SCREEN_FACES.word] = word; }
        VulkanLiquidStateLayout.Column column(Field f){return columns.get(f);}int word(Field f){return word(column(f));}
        int headerWord(){return word(header);}long endOffset(){return endOffset;}
        List<VulkanEnergyLedger.Stream> roundoffStreams(){
            var streams=new java.util.ArrayList<VulkanEnergyLedger.Stream>();
            streams.add(new VulkanEnergyLedger.Stream(1,source.word(VulkanGranularStateLayout.Field.INTERNAL_ENERGY),state.rowCapacity(),2,-1));
            streams.add(new VulkanEnergyLedger.Stream(1,word(Field.FINAL_ENERGY),state.rowCapacity(),2,1));
            streams.add(new VulkanEnergyLedger.Stream(1,word(Field.HEAT),inputRows,2,-1));
            for(int i=0;i<8;i++){
                streams.add(new VulkanEnergyLedger.Stream(1,word(Field.LIFT_TERMS)+2*i,inputRows,34,-1));
                streams.add(new VulkanEnergyLedger.Stream(1,word(Field.LIFT_TERMS)+2*(8+i),inputRows,34,1));
            }
            streams.add(new VulkanEnergyLedger.Stream(1,word(Field.LIFT_TERMS)+32,inputRows,34,1));return List.copyOf(streams);
        }
        ByteBuffer headerBytes(){var b=ByteBuffer.allocateDirect(512).order(ByteOrder.LITTLE_ENDIAN);
            for(int i=0;i<inputs.length;i++)b.putInt(4*i,inputs[i]);
            for(var f:VulkanGranularStateLayout.Field.values()){b.putInt(4*(TARGET_FIELDS+f.ordinal()),state.word(f));b.putInt(4*(SOURCE_FIELDS+f.ordinal()),source.word(f));}
            for(var f:Field.values())b.putInt(4*(WORK_FIELDS+f.ordinal()),word(f));return b;}
        ByteBuffer materialBytes(mmm.granular.GranularRegistry registry){var b=ByteBuffer.allocateDirect(Math.toIntExact(column(Field.MATERIALS).bytes())).order(ByteOrder.LITTLE_ENDIAN);
            for(var id:state.definitions().materialIds()){var m=registry.material(registry.materialHandle(id));
                b.putDouble(m.referenceDensity()).putDouble(m.primaryGrainDiameterMeters()).putDouble((double)m.restPorosityQ()/mmm.granular.GranularLimits.POROSITY_SCALE)
                        .putDouble(Math.tan(m.reposeAngleRadians())).putDouble(m.criticalShields()).putDouble(m.erosionRateSvqPerSecond()).putDouble(m.maximumActiveParcelSvq());}
            return b.flip();}
        private static int word(VulkanLiquidStateLayout.Column c){return Math.toIntExact(c.offset()/4);}
    }
    private final FlipKernelBindings bindings;private final VulkanPrefixScan scan;private final VulkanGranularGeometry geometryKernel;
    VulkanGranularTransitions(VulkanDeviceContext context){bindings=new FlipKernelBindings(context,2,shader(),40);
        VulkanPrefixScan createdScan=null;try{createdScan=new VulkanPrefixScan(context);geometryKernel=new VulkanGranularGeometry(context);scan=createdScan;}
        catch(RuntimeException|LinkageError e){if(createdScan!=null)createdScan.close();bindings.close();throw e;}}
    void rebindRanges(FlipKernelBindings.BufferRange arena,FlipKernelBindings.BufferRange errors){bindings.rebindRanges(List.of(arena,errors));scan.rebindRanges(arena,arena,arena,errors);geometryKernel.rebindRanges(arena,errors);}
    /** Copy the candidate inventory after source flow preparation. All mutations remain candidate-private. */
    void record(VkCommandBuffer command,Layout l,GpuBuffer arena,double h,double dt,double gravity){
        recordCopySource(command,l,arena);
        prepareRows(command,l,h,dt,gravity);
        stage(command,l,4,l.columnsCount,0,h,dt,gravity);
        // X and Z pair sweeps have disjoint receivers within each parity.
        for(int axis=0;axis<2;axis++)for(int parity=0;parity<2;parity++)stage(command,l,5,l.columnsCount,2*axis+parity,h,dt,gravity);
        for(int color=0;color<4;color++)stage(command,l,6,l.columnsCount,color,h,dt,gravity);
        recordGather(command,l,h,dt,gravity);
    }
    /** Prepare the same inventory snapshot and accounting columns for an empty interval. */
    void recordEmptyInterval(VkCommandBuffer command,Layout l,GpuBuffer arena,double h,double dt,double gravity){
        recordCopySource(command,l,arena);
        stage(command,l,16,Math.max(1,l.inputRows),0,h,dt,gravity);
    }
    /** Shared phase-transfer entry: copy first, apply its complete row/species successor to l.source,
     * then call recordReassemble. Zero-volume source rows are removed during the gather. */
    void recordCopySource(VkCommandBuffer command,Layout l,GpuBuffer arena){
        try(var stack=stackPush()){
            var regions=VkBufferCopy.calloc(VulkanGranularStateLayout.Field.values().length,stack);int n=0;
            for(var f:VulkanGranularStateLayout.Field.values()){var a=l.state.column(f);if(a.bytes()>0)regions.get(n++).srcOffset(a.offset()).dstOffset(l.source.column(f).offset()).size(a.bytes());}
            regions.limit(n);VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_READ_BIT,stack);
            vkCmdCopyBuffer(command,arena.handle(),arena.handle(),regions);
            VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);
        }
    }
    void recordReassemble(VkCommandBuffer command,Layout l,double h){
        prepareRows(command,l,h,0,0);recordGather(command,l,h,0,0);
    }
    /** Reapply the already selected transfers after mechanical energy updates their source rows. */
    void recordRefreshAfterEnergy(VkCommandBuffer command,Layout l,double h,double dt,double gravity){
        stage(command,l,13,l.state.rowCapacity(),0,h,dt,gravity);
    }
    private void prepareRows(VkCommandBuffer command,Layout l,double h,double dt,double gravity){
        stage(command,l,0,Math.max(12,Math.max(l.hashSize,Math.max(l.state.rowCapacity(),l.state.cells()))),0,h,dt,gravity);
        stage(command,l,1,l.inputRows,0,h,dt,gravity);
        scan.record(command,l.columnsCount,l.word(Field.COLUMN_COUNTS),l.word(Field.COLUMN_OFFSETS),l.word(Field.SCAN));
        stage(command,l,2,l.inputRows,0,h,dt,gravity);
        stage(command,l,3,l.state.cells(),0,h,dt,gravity);
    }
    private void recordGather(VkCommandBuffer command,Layout l,double h,double dt,double gravity){
        stage(command,l,7,l.inputRows,0,h,dt,gravity);
        scan.record(command,l.inputRows,l.word(Field.ACTIVE_COUNTS),l.word(Field.ACTIVE_OFFSETS),l.word(Field.SCAN));
        stage(command,l,8,l.inputRows,0,h,dt,gravity);
        stage(command,l,9,l.hashSize,0,h,dt,gravity);
        scan.record(command,l.hashSize,l.word(Field.HASH_COUNTS),l.word(Field.HASH_OFFSETS),l.word(Field.SCAN));
        stage(command,l,10,1,0,h,dt,gravity);
        stage(command,l,11,l.inputRows+2*l.state.definitions().materialIds().size(),0,h,dt,gravity);
        scan.record(command,l.state.rowCapacity(),l.word(Field.ROW_COUNTS),l.word(Field.ROW_OFFSETS),l.word(Field.SCAN));
        stage(command,l,12,l.inputRows+2*l.state.definitions().materialIds().size(),0,h,dt,gravity);
        stage(command,l,13,l.state.rowCapacity(),0,h,dt,gravity);
        scan.record(command,l.state.rowCapacity(),l.word(Field.SPECIES_COUNTS),l.state.word(VulkanGranularStateLayout.Field.SPECIES_OFFSETS),l.word(Field.SCAN));
        stage(command,l,14,1,0,h,dt,gravity);
        stage(command,l,15,l.state.rowCapacity(),0,h,dt,gravity);
        geometryKernel.recordSupports(command,l.geometry,h);geometryKernel.recordOccupancy(command,l.geometry);
    }
    private void stage(VkCommandBuffer c,Layout l,int stage,int count,int color,double h,double dt,double gravity){if(count==0)return;
        try(var stack=stackPush()){bindings.bind(c,stack);var push=stack.malloc(40).putInt(l.headerWord()).putInt(stage).putInt(count).putInt(color).putDouble(h).putDouble(dt).putDouble(gravity).flip();
            vkCmdPushConstants(c,bindings.pipelineLayout(),VK_SHADER_STAGE_COMPUTE_BIT,0,push);int groups=(count+127)/128;
            vkCmdDispatch(c,Math.min(groups,65535),(groups+65534)/65535,1);
            VulkanRegionStorage.barrier(c,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);}}
    private static long align(long v,long a){return Math.addExact(v,a-1)&-a;}
    private static String shader(){try(var in=VulkanGranularTransitions.class.getResourceAsStream("/assets/mmm_gpu_probe/shaders/granular-transitions.comp")){
        if(in==null)throw new IllegalStateException("Granular transition shader missing");return new String(in.readAllBytes(),StandardCharsets.UTF_8).replace("// HOST_LAYOUT", shaderLayout());
    }catch(IOException e){throw new IllegalStateException("Granular transition shader unavailable",e);}}
    @Override public void close(){try{geometryKernel.close();}finally{try{scan.close();}finally{bindings.close();}}}
}

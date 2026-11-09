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

/** Builds common fluid pressure incidence and the optional particle drag response. */
final class VulkanMixturePressure implements AutoCloseable {
    private enum Header {
        SIZE_X(0), SIZE_Y(1), SIZE_Z(2), PARTICLE_CAPACITY(3), FACE_COUNT(4),
        LIQUID_COUNT(5), CELLS(6), FACES(7), COUPLED_HEADER(8), MASKS(9),
        VISCOSITY(10), FIXED_SVQ(11), ENTITY_FACE_AREA(12), ACTIVITY_CELLS(13),
        ACTIVITY_FACES(14), MIXTURE_STATES(15);
        final int word;
        Header(int word) { this.word = word; }
    }
    private static final int WORK_FIELDS = 40, STATE_FIELDS = 16;
    private static String shaderLayout() {
        var result = new StringBuilder();
        for (var field : Header.values()) result.append("#define H_").append(field.name()).append(' ').append(field.word).append('\n');
        result.append("#define WORK_FIELDS ").append(WORK_FIELDS).append('\n');
        result.append("#define STATE_FIELDS ").append(STATE_FIELDS).append('\n');
        for (var field : VulkanCoupledPressure.Header.values()) result.append("#define COUPLED_H_").append(field.name()).append(' ').append(field.word).append('\n');
        result.append("#define COUPLED_FIELDS ").append(VulkanCoupledPressure.WORK_FIELDS).append('\n');
        return result + VulkanShaderLayout.fields("FIELD_", Field.values()) + VulkanShaderLayout.fields("GRANULAR_", VulkanGranularStateLayout.Field.values()) + VulkanShaderLayout.fields("Q_", VulkanMixtureState.Quantity.values()) + VulkanShaderLayout.fields("COUPLED_", VulkanCoupledPressure.Field.values());
    }

    enum Field { FACE_IDS(4),FACE_SHARES(8),DRAG_WEIGHTS(8),FACE_COUNTS(4),FACE_OFFSETS(4),FACE_CURSORS(4),FACE_ENTRIES(4),
        FACE_SOLID_VOLUME(8),FIXED_PORE(8),SOURCE_VELOCITY(8),SOURCE_K(8),SOURCE_Y(8),PROJECTED_K(8),
        DRAG_RESISTANCE(8),DRAG_IMPULSE(8),DRAG_HEAT(8),DIAMETERS(8),SCAN(4),FINAL_K(8),FINAL_Y(8);
        final int width;Field(int width){this.width=width;}}
    static final class Layout {
        private final VulkanGranularGeometry.Layout geometry;
        private final VulkanLiquidStateLayout liquid;
        private final VulkanLiquidStateLayout.Column fixedSolid;
        private final VulkanCoupledPressure.Layout coupled;
        private final VulkanLiquidStateLayout.Column cells,facesColumn,mixture,masks,viscosity,entityFaceArea,header;
        private final EnumMap<Field,VulkanLiquidStateLayout.Column> columns=new EnumMap<>(Field.class);
        private final int faces,particles;private final long endOffset;
        private int activityCellWord=-1,activityFaceWord=-1;
        Layout(VulkanLiquidStateLayout liquid,VulkanGranularGeometry.Layout geometry,VulkanLiquidStateLayout.Column fixedSolid,VulkanCoupledPressure.Layout coupled,
               VulkanLiquidStateLayout.Column cells,VulkanLiquidStateLayout.Column facesColumn,VulkanLiquidStateLayout.Column mixture,
               VulkanLiquidStateLayout.Column masks,VulkanLiquidStateLayout.Column viscosity,
               VulkanLiquidStateLayout.Column entityFaceArea,long scratchBase,long alignment){
            this.liquid=liquid;this.geometry=geometry;this.fixedSolid=fixedSolid;this.coupled=coupled;this.cells=cells;this.facesColumn=facesColumn;this.mixture=mixture;
            this.masks=masks;this.viscosity=viscosity;this.entityFaceArea=entityFaceArea;
            particles=geometry==null?0:geometry.state().particleCapacity();int cellCount=liquid.cellCount();
            faces=liquid.grid().faceCount();
            if(coupled.pressureSize()!=cellCount+3*particles||coupled.liquidCount()!=cellCount
                    ||coupled.velocities()<faces+3*particles||coupled.termCapacity()<2L*faces+75L*particles
                    ||cells.count()<4L*cellCount||facesColumn.count()<4L*faces||mixture.count()<7L*cellCount
                    ||masks.count()!=cellCount||viscosity.count()!=cellCount
                    ||entityFaceArea!=null&&entityFaceArea.count()!=faces||scratchBase<coupled.endOffset()
                    ||alignment<8||(alignment&(alignment-1))!=0)throw new IllegalArgumentException("Particle pressure dimensions differ");
            header=new VulkanLiquidStateLayout.Column(align(scratchBase,alignment),96,4);long cursor=header.offset()+header.bytes();
            for(Field field:Field.values()){
                int count=switch(field){case FACE_IDS,FACE_SHARES,DRAG_WEIGHTS,FACE_ENTRIES->24*particles;
                    case FACE_OFFSETS->faces+1;case FACE_COUNTS,FACE_CURSORS,FACE_SOLID_VOLUME,FIXED_PORE->faces;
                    case SOURCE_VELOCITY,DRAG_IMPULSE->3*particles;
                    case DIAMETERS->geometry==null?0:geometry.state().definitions().materialIds().size();
                    case SCAN->Math.toIntExact(VulkanPrefixScan.workspaceBytes(faces)/4);default->particles;};
                var column=new VulkanLiquidStateLayout.Column(align(cursor,alignment),count,field.width);columns.put(field,column);cursor=column.offset()+column.bindingBytes();
            }endOffset=align(cursor,alignment);
        }
        VulkanLiquidStateLayout.Column column(Field field){return columns.get(field);}
        int word(Field field){return Math.toIntExact(column(field).offset()/4);}int headerWordOffset(){return Math.toIntExact(header.offset()/4);}
        long endOffset(){return endOffset;}VulkanCoupledPressure.Layout coupled(){return coupled;}
        void bindActivity(int cells,int faces){activityCellWord=cells;activityFaceWord=faces;}
        ByteBuffer headerBytes(){
            var result=ByteBuffer.allocateDirect(384).order(ByteOrder.LITTLE_ENDIAN);
            result.putInt(4 * Header.SIZE_X.word,liquid.sizeX()).putInt(4 * Header.SIZE_Y.word,liquid.sizeY()).putInt(4 * Header.SIZE_Z.word,liquid.sizeZ()).putInt(4 * Header.PARTICLE_CAPACITY.word,particles)
                    .putInt(4 * Header.FACE_COUNT.word,faces).putInt(4 * Header.LIQUID_COUNT.word,coupled.liquidCount()).putInt(4 * Header.CELLS.word,word(cells)).putInt(4 * Header.FACES.word,word(facesColumn))
                    .putInt(4 * Header.MIXTURE_STATES.word,word(mixture))
                    .putInt(4 * Header.COUPLED_HEADER.word,coupled.headerWordOffset()).putInt(4 * Header.MASKS.word,word(masks)).putInt(4 * Header.VISCOSITY.word,word(viscosity))
                    .putInt(4 * Header.FIXED_SVQ.word,word(fixedSolid)).putInt(4 * Header.ENTITY_FACE_AREA.word,entityFaceArea==null?-1:word(entityFaceArea));
            result.putInt(4 * Header.ACTIVITY_CELLS.word,activityCellWord).putInt(4 * Header.ACTIVITY_FACES.word,activityFaceWord);
            if(geometry!=null)for(var field:VulkanGranularStateLayout.Field.values())result.putInt(4*(STATE_FIELDS+field.ordinal()),geometry.state().word(field));
            for(var field:Field.values())result.putInt(4*(WORK_FIELDS+field.ordinal()),word(field));return result;
        }
        void initializeHeaderAndMaterials(ByteBuffer arena,mmm.granular.GranularRegistry registry){
            arena.duplicate().position(Math.toIntExact(header.offset())).put(headerBytes());var target=arena.duplicate().order(ByteOrder.LITTLE_ENDIAN);
            var ids=geometry==null?List.<String>of():geometry.state().definitions().materialIds();for(int i=0;i<ids.size();i++)target.putDouble(Math.toIntExact(column(Field.DIAMETERS).offset()+8L*i),
                    registry.material(registry.materialHandle(ids.get(i))).primaryGrainDiameterMeters());
        }
        private static int word(VulkanLiquidStateLayout.Column c){return Math.toIntExact(c.offset()/4);}
    }
    private final FlipKernelBindings bindings;private final VulkanPrefixScan scan;
    VulkanMixturePressure(VulkanDeviceContext context){bindings=new FlipKernelBindings(context,2,shader(),36);
        try{scan=new VulkanPrefixScan(context);}catch(RuntimeException|LinkageError failure){bindings.close();throw failure;}}
    void rebindRanges(FlipKernelBindings.BufferRange arena,FlipKernelBindings.BufferRange errors){
        bindings.rebindRanges(List.of(arena,errors));scan.rebindRanges(arena,arena,arena,errors);}
    /** After liquid geometry, before gravity and gas pressure preparation: creates complementary solid/liquid apertures. */
    void recordFaces(VkCommandBuffer command,Layout l,double h,double dt,double gravity){
        stage(command,l,0,l.faces,h,dt,gravity);stage(command,l,1,l.particles,h,dt,gravity);
        scan.record(command,l.faces,l.word(Field.FACE_COUNTS),l.word(Field.FACE_OFFSETS),l.word(Field.SCAN));
        stage(command,l,2,24*l.particles,h,dt,gravity);stage(command,l,3,l.faces,h,dt,gravity);
        stage(command,l,4,l.particles,h,dt,gravity);
    }
    /** After gas prepare, fills the common incidence; coupled.recordPrepare follows. */
    void recordIncidence(VkCommandBuffer command,Layout l,double h,double dt,double gravity,boolean warmValid){
        stage(command,l,5,l.coupled.termCapacity(),h,dt,gravity);stage(command,l,6,l.coupled.pressureSize(),h,dt,gravity,warmValid);
        stage(command,l,7,l.faces,h,dt,gravity);stage(command,l,8,l.particles,h,dt,gravity);
    }
    /** A cold import starts with its current EOS pressure and zero mechanical increment. */
    void recordInitializeWarm(VkCommandBuffer command,Layout l){
        stage(command,l,12,l.coupled.liquidCount(),1,1,0);
    }
    /** Publishes trial projected velocities; position/occupancy and all energy posting await joint capacity acceptance. */
    void recordProjectedVelocities(VkCommandBuffer command,Layout l,double h,double dt,double gravity){
        stage(command,l,9,l.faces,h,dt,gravity);stage(command,l,10,l.particles,h,dt,gravity);
    }
    /** Apply the mixture EOS pressure range before coupled velocity projection. */
    void recordClampPressure(VkCommandBuffer command,Layout l,double h,double dt,double gravity){
        stage(command,l,11,l.coupled.liquidCount(),h,dt,gravity);
    }
    private void stage(VkCommandBuffer command,Layout l,int stage,int count,double h,double dt,double gravity){
        stage(command,l,stage,count,h,dt,gravity,false);
    }
    private void stage(VkCommandBuffer command,Layout l,int stage,int count,double h,double dt,double gravity,boolean warmValid){
        if(count==0)return;try(var stack=stackPush()){
            bindings.bind(command,stack);var push=stack.malloc(36).putInt(l.headerWordOffset()).putInt(stage).putDouble(h).putDouble(dt).putDouble(gravity).putInt(warmValid?1:0).flip();
            vkCmdPushConstants(command,bindings.pipelineLayout(),VK_SHADER_STAGE_COMPUTE_BIT,0,push);int groups=(count+127)/128;
            vkCmdDispatch(command,Math.min(groups,65535),(groups+65534)/65535,1);
            VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);
        }
    }
    private static long align(long v,long a){return Math.addExact(v,a-1)&-a;}
    private static String shader(){try(var input=VulkanMixturePressure.class.getResourceAsStream("/assets/mmm_gpu_probe/shaders/mixture-pressure.comp")){
        if(input==null)throw new IllegalStateException("Granular pressure shader missing");return new String(input.readAllBytes(),StandardCharsets.UTF_8).replace("// HOST_LAYOUT", shaderLayout()+"\n#define MIXTURE_PRESSURE_LIMIT "+mmm.thermal.MixtureVolumeModel.PRESSURE_LIMIT_PA+"LF\n");
    }catch(IOException failure){throw new IllegalStateException("Granular pressure shader unavailable",failure);}}
    @Override public void close(){try{scan.close();}finally{bindings.close();}}
}

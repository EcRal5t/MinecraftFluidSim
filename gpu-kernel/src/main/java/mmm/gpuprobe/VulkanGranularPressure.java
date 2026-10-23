package mmm.gpuprobe;

import org.lwjgl.vulkan.VkCommandBuffer;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.List;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Builds solid volume and mass-weighted drag incidence for the one common resident PCG. */
final class VulkanGranularPressure implements AutoCloseable {
    enum Field { FACE_IDS(4),FACE_SHARES(8),DRAG_WEIGHTS(8),FACE_COUNTS(4),FACE_OFFSETS(4),FACE_CURSORS(4),FACE_ENTRIES(4),
        FACE_SOLID_VOLUME(8),FIXED_PORE(8),SOURCE_VELOCITY(8),SOURCE_K(8),SOURCE_Y(8),PROJECTED_K(8),
        DRAG_RESISTANCE(8),DRAG_IMPULSE(8),DRAG_HEAT(8),DIAMETERS(8),SCAN(4);
        final int width;Field(int width){this.width=width;}}
    static final class Layout {
        private final VulkanGranularGeometry.Layout geometry;
        private final VulkanGasPressure.Layout gas;
        private final VulkanCoupledPressure.Layout coupled;
        private final VulkanLiquidStateLayout.Column masks,viscosity,entityFaceArea,header;
        private final EnumMap<Field,VulkanLiquidStateLayout.Column> columns=new EnumMap<>(Field.class);
        private final int faces,particles;private final long endOffset;
        Layout(VulkanGranularGeometry.Layout geometry,VulkanGasPressure.Layout gas,VulkanCoupledPressure.Layout coupled,
               VulkanLiquidStateLayout.Column masks,VulkanLiquidStateLayout.Column viscosity,
               VulkanLiquidStateLayout.Column entityFaceArea,long scratchBase,long alignment){
            this.geometry=geometry;this.gas=gas;this.coupled=coupled;this.masks=masks;this.viscosity=viscosity;this.entityFaceArea=entityFaceArea;
            particles=geometry.state().particleCapacity();faces=gas.geometry().faces();
            if(coupled.pressureSize()!=gas.liquidCount()+gas.gasCapacity()+3*particles||gas.pressureSize()!=coupled.pressureSize()
                    ||coupled.velocities()!=faces+3*particles||coupled.termCapacity()<2L*faces+75L*particles
                    ||masks.count()!=gas.liquidCount()||viscosity.count()!=gas.liquidCount()
                    ||entityFaceArea!=null&&entityFaceArea.count()!=faces||scratchBase<coupled.endOffset()
                    ||alignment<8||(alignment&(alignment-1))!=0)throw new IllegalArgumentException("Particle pressure dimensions differ");
            header=new VulkanLiquidStateLayout.Column(align(scratchBase,alignment),96,4);long cursor=header.offset()+header.bytes();
            for(Field field:Field.values()){
                int count=switch(field){case FACE_IDS,FACE_SHARES,DRAG_WEIGHTS,FACE_ENTRIES->24*particles;
                    case FACE_OFFSETS->faces+1;case FACE_COUNTS,FACE_CURSORS,FACE_SOLID_VOLUME,FIXED_PORE->faces;
                    case SOURCE_VELOCITY,DRAG_IMPULSE->3*particles;
                    case DIAMETERS->geometry.state().definitions().materialIds().size();
                    case SCAN->Math.toIntExact(VulkanPrefixScan.workspaceBytes(faces)/4);default->particles;};
                var column=new VulkanLiquidStateLayout.Column(align(cursor,alignment),count,field.width);columns.put(field,column);cursor=column.offset()+column.bindingBytes();
            }endOffset=align(cursor,alignment);
        }
        VulkanLiquidStateLayout.Column column(Field field){return columns.get(field);}
        int word(Field field){return Math.toIntExact(column(field).offset()/4);}int headerWordOffset(){return Math.toIntExact(header.offset()/4);}
        long endOffset(){return endOffset;}VulkanCoupledPressure.Layout coupled(){return coupled;}
        ByteBuffer headerBytes(){
            var result=ByteBuffer.allocateDirect(384).order(ByteOrder.LITTLE_ENDIAN);var state=geometry.state();
            result.putInt(0,state.sizeX()).putInt(4,state.sizeY()).putInt(8,state.sizeZ()).putInt(12,particles)
                    .putInt(16,faces).putInt(20,gas.liquidCount()).putInt(24,gas.gasCapacity()).putInt(28,gas.headerWordOffset())
                    .putInt(32,coupled.headerWordOffset()).putInt(36,word(masks)).putInt(40,word(viscosity))
                    .putInt(44,geometry.word(VulkanGranularGeometry.Field.FIXED_SVQ)).putInt(48,entityFaceArea==null?-1:word(entityFaceArea));
            for(var field:VulkanGranularStateLayout.Field.values())result.putInt(4*(16+field.ordinal()),state.word(field));
            for(var field:Field.values())result.putInt(4*(40+field.ordinal()),word(field));return result;
        }
        void initializeHeaderAndMaterials(ByteBuffer arena,mmm.granular.GranularRegistry registry){
            arena.duplicate().position(Math.toIntExact(header.offset())).put(headerBytes());var target=arena.duplicate().order(ByteOrder.LITTLE_ENDIAN);
            var ids=geometry.state().definitions().materialIds();for(int i=0;i<ids.size();i++)target.putDouble(Math.toIntExact(column(Field.DIAMETERS).offset()+8L*i),
                    registry.material(registry.materialHandle(ids.get(i))).primaryGrainDiameterMeters());
        }
        private static int word(VulkanLiquidStateLayout.Column c){return Math.toIntExact(c.offset()/4);}
    }
    private final FlipKernelBindings bindings;private final VulkanPrefixScan scan;
    VulkanGranularPressure(VulkanDeviceContext context){bindings=new FlipKernelBindings(context,2,shader(),32);
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
    void recordIncidence(VkCommandBuffer command,Layout l,double h,double dt,double gravity){
        stage(command,l,5,l.coupled.termCapacity(),h,dt,gravity);stage(command,l,6,l.coupled.pressureSize(),h,dt,gravity);
        stage(command,l,7,l.faces,h,dt,gravity);stage(command,l,8,l.particles,h,dt,gravity);
    }
    /** Publishes trial projected velocities; position/occupancy and all energy posting await joint capacity acceptance. */
    void recordProjectedVelocities(VkCommandBuffer command,Layout l,double h,double dt,double gravity){
        stage(command,l,9,l.faces,h,dt,gravity);stage(command,l,10,l.particles,h,dt,gravity);
    }
    /** After coupled.recordDivergence and gas.recordObserve, includes solid swept volume in gas diagnostics. */
    void recordObserveGas(VkCommandBuffer command,Layout l,double h,double dt,double gravity){
        stage(command,l,11,l.gas.gasCapacity(),h,dt,gravity);
    }
    private void stage(VkCommandBuffer command,Layout l,int stage,int count,double h,double dt,double gravity){
        if(count==0)return;try(var stack=stackPush()){
            bindings.bind(command,stack);var push=stack.malloc(32).putInt(l.headerWordOffset()).putInt(stage).putDouble(h).putDouble(dt).putDouble(gravity).flip();
            vkCmdPushConstants(command,bindings.pipelineLayout(),VK_SHADER_STAGE_COMPUTE_BIT,0,push);int groups=(count+127)/128;
            vkCmdDispatch(command,Math.min(groups,65535),(groups+65534)/65535,1);
            VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);
        }
    }
    private static long align(long v,long a){return Math.addExact(v,a-1)&-a;}
    private static String shader(){try(var input=VulkanGranularPressure.class.getResourceAsStream("/assets/mmm_gpu_probe/shaders/granular-pressure.comp")){
        if(input==null)throw new IllegalStateException("Granular pressure shader missing");return new String(input.readAllBytes(),StandardCharsets.UTF_8);
    }catch(IOException failure){throw new IllegalStateException("Granular pressure shader unavailable",failure);}}
    @Override public void close(){try{scan.close();}finally{bindings.close();}}
}

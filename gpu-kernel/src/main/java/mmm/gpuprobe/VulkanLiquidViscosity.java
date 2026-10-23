package mmm.gpuprobe;

import mmm.fluid.FluidMaterialRegistry;
import mmm.fluid.MaterialRegistry;
import org.lwjgl.vulkan.VkCommandBuffer;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Conservative MAC normal/shear stresses, fixed walls and prescribed-body tangential contact. */
final class VulkanLiquidViscosity implements AutoCloseable {
    record Tables(int[] offsets,double[] segments) { }
    static Tables tables(FluidMaterialRegistry fluids,MaterialRegistry materials) {
        int[] offsets=new int[fluids.materials().size()+1];var values=new ArrayList<Double>();
        for(int material=0;material<fluids.materials().size();material++) {
            var entry=materials.entry(fluids.material(material).id());
            if(!entry.declaresViscosity())throw new IllegalArgumentException("Liquid viscosity is missing for "+entry.material().id());
            for(var s:entry.viscosity()){values.add(s.minimumTemperatureK());values.add(s.maximumTemperatureK());values.add(s.dynamicViscosityPascalSecondsAtMinimum());values.add(s.dynamicViscosityPascalSecondsAtMaximum());}
            offsets[material+1]=values.size()/4;
        }
        return new Tables(offsets,values.stream().mapToDouble(Double::doubleValue).toArray());
    }
    /** Face CSR stores geometry area/distance in metres; body velocity is a three-component SI vector.
     * The contact geometry producer includes only tangential wet area and uses stable body order. */
    record PrescribedContacts(int capacity,int bodies,VulkanLiquidStateLayout.Column faceOffsets,
                              VulkanLiquidStateLayout.Column bodyHandles,VulkanLiquidStateLayout.Column areaOverDistance,
                              VulkanLiquidStateLayout.Column velocities) { }
    enum Field {
        MU(8), CARRIER(4), EXTRAPOLATED(4), WALL_AREAS(8), STRESS_HEAT(8), WALL_HEAT(8), CONTACT_FACE_HEAT(8),
        CONTACT_FACE_WORK(8), SOURCE_CELL_HEAT(8), SOURCE_CELL_WORK(8), SOURCE_FACE_VELOCITY(8), SOURCE_FACE_K(8),
        FINAL_FACE_K(8), CELL_FIXED_IMPULSE(8), WALL_FACE_IMPULSE(8), CONTACT_IMPULSE(8), CONTACT_WORK(8),
        MATERIAL_OFFSETS(4), VISCOSITY_SEGMENTS(8);
        final int width;Field(int width){this.width=width;}
    }
    static final class Layout {
        private final VulkanLiquidStateLayout source;
        private final VulkanLiquidStateLayout.Column header;
        private final EnumMap<Field,VulkanLiquidStateLayout.Column> columns=new EnumMap<>(Field.class);
        private final Tables tables;private final PrescribedContacts contacts;
        private final int cellsWord,facesWord,masksWord,temperatureWord,activityWord,faces;
        private final boolean noSlip;private final long endOffset;
        Layout(VulkanLiquidStateLayout source,VulkanLiquidStateLayout.Column cells,VulkanLiquidStateLayout.Column faces,
               VulkanLiquidStateLayout.Column masks,int cellTemperatureWord,int faceActivityWord,Tables tables,
               PrescribedContacts contacts,boolean noSlip,long scratchBase,long alignment) {
            this.source=source;this.tables=tables;this.contacts=contacts;this.noSlip=noSlip;
            cellsWord=word(cells);facesWord=word(faces);masksWord=word(masks);temperatureWord=cellTemperatureWord;activityWord=faceActivityWord;
            this.faces=faces.count()/4;
            if(scratchBase<source.bytes()||alignment<8||(alignment&(alignment-1))!=0||cells.count()!=4*source.cellCount()||cellTemperatureWord<0)
                throw new IllegalArgumentException("Viscous fields differ from resident liquid");
            long[] cursor={scratchBase};header=VulkanLiquidViscosity.column(cursor,80,4,alignment);int n=source.cellCount();
            for(Field field:Field.values()) {
                int count=switch(field){
                    case WALL_AREAS,CELL_FIXED_IMPULSE->3*n;case STRESS_HEAT->6*n;
                    case WALL_HEAT,CONTACT_FACE_HEAT,CONTACT_FACE_WORK,SOURCE_FACE_VELOCITY,SOURCE_FACE_K,FINAL_FACE_K,WALL_FACE_IMPULSE->this.faces;
                    case CONTACT_IMPULSE,CONTACT_WORK->contacts==null?0:contacts.capacity();
                    case MATERIAL_OFFSETS->tables.offsets().length;case VISCOSITY_SEGMENTS->tables.segments().length;default->n;
                };
                columns.put(field,VulkanLiquidViscosity.column(cursor,count,field.width,alignment));
            }
            endOffset=align(cursor[0],alignment);
        }
        VulkanLiquidStateLayout source(){return source;}
        VulkanLiquidStateLayout.Column column(Field field){return columns.get(field);}
        int faces(){return faces;}long endOffset(){return endOffset;}
        List<VulkanEnergyLedger.Stream> externalWorkRoundoffStreams(){
            return List.of(new VulkanEnergyLedger.Stream(1,word(column(Field.SOURCE_CELL_WORK)),source.cellCount(),2,1),
                    new VulkanEnergyLedger.Stream(1,word(column(Field.CONTACT_WORK)),contacts==null?0:contacts.capacity(),2,-1));
        }
        ByteBuffer headerBytes(){return headerBytes(source);}
        ByteBuffer headerBytes(VulkanLiquidStateLayout sourceLayout){
            var out=ByteBuffer.allocateDirect(320).order(ByteOrder.LITTLE_ENDIAN);int[] data=new int[80];
            data[0]=source.sizeX();data[1]=source.sizeY();data[2]=source.sizeZ();data[3]=source.cellCount();data[4]=faces;
            data[5]=cellsWord;data[6]=facesWord;data[7]=masksWord;data[8]=temperatureWord;data[9]=activityWord;data[10]=noSlip?1:0;
            data[11]=tables.offsets().length-1;
            data[12]=contacts==null?-1:word(contacts.faceOffsets());data[13]=contacts==null?0:word(contacts.bodyHandles());
            data[14]=contacts==null?0:word(contacts.areaOverDistance());data[15]=contacts==null?0:word(contacts.velocities());
            data[16]=contacts==null?0:contacts.capacity();data[17]=contacts==null?0:contacts.bodies();
            for(var field:VulkanLiquidStateLayout.Field.values())data[24+field.ordinal()]=word(sourceLayout.column(field));
            for(var field:Field.values())data[48+field.ordinal()]=word(column(field));
            for(int i=0;i<data.length;i++)out.putInt(4*i,data[i]);return out;
        }
        void initializeHeaderAndTables(ByteBuffer arena){
            arena.duplicate().position(Math.toIntExact(header.offset())).put(headerBytes());
            for(int i=0;i<tables.offsets().length;i++)arena.putInt(Math.toIntExact(column(Field.MATERIAL_OFFSETS).offset()+4L*i),tables.offsets()[i]);
            for(int i=0;i<tables.segments().length;i++)arena.putDouble(Math.toIntExact(column(Field.VISCOSITY_SEGMENTS).offset()+8L*i),tables.segments()[i]);
        }
    }
    private final FlipKernelBindings bindings;
    VulkanLiquidViscosity(VulkanDeviceContext context){bindings=new FlipKernelBindings(context,3,shader(),32);}
    void rebindRanges(FlipKernelBindings.BufferRange source,FlipKernelBindings.BufferRange candidate,FlipKernelBindings.BufferRange errors){bindings.rebindRanges(List.of(source,candidate,errors));}
    void record(VkCommandBuffer command,Layout layout,double cellSize,double duration){
        if(!(cellSize>0&&Double.isFinite(cellSize)&&duration>0&&Double.isFinite(duration)))throw new IllegalArgumentException("Invalid viscous step");
        stage(command,layout,0,0,layout.source.cellCount(),cellSize,duration);
        stage(command,layout,1,0,layout.faces,cellSize,duration);
        if(layout.contacts!=null)stage(command,layout,5,0,layout.contacts.capacity(),cellSize,duration);
        // Every colour owns disjoint MAC faces. The middle wall group receives one full step.
        for(int colour=0;colour<18;colour++)stage(command,layout,2,colour,layout.source.cellCount(),cellSize,.5*duration);
        stage(command,layout,2,18,layout.faces,cellSize,duration);
        for(int colour=17;colour>=0;colour--)stage(command,layout,2,colour,layout.source.cellCount(),cellSize,.5*duration);
        stage(command,layout,3,0,layout.faces,cellSize,duration);
        stage(command,layout,4,0,layout.source.cellCount(),cellSize,duration);
    }
    private void stage(VkCommandBuffer command,Layout layout,int stage,int colour,int count,double h,double dt){
        if(count==0)return;int groups=(count+127)/128;
        try(var stack=stackPush()){
            bindings.bind(command,stack);var push=stack.malloc(32).putInt(word(layout.header)).putInt(stage).putInt(colour).putInt(count).putDouble(h).putDouble(dt).flip();
            vkCmdPushConstants(command,bindings.pipelineLayout(),VK_SHADER_STAGE_COMPUTE_BIT,0,push);
            vkCmdDispatch(command,Math.min(groups,65535),(groups+65534)/65535,1);
            VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);
        }
    }
    private static int word(VulkanLiquidStateLayout.Column column){return Math.toIntExact(column.offset()/4);}
    private static long align(long n,long alignment){return Math.addExact(n,alignment-1)&-alignment;}
    private static VulkanLiquidStateLayout.Column column(long[] cursor,int count,int width,long alignment){var column=new VulkanLiquidStateLayout.Column(align(cursor[0],alignment),count,width);cursor[0]=column.offset()+column.bindingBytes();return column;}
    private static String shader(){try(var input=VulkanLiquidViscosity.class.getResourceAsStream("/assets/mmm_gpu_probe/shaders/liquid-viscosity.comp")){
        if(input==null)throw new IllegalStateException("Viscosity shader missing");return new String(input.readAllBytes(),StandardCharsets.UTF_8);
    }catch(IOException failure){throw new IllegalStateException("Viscosity shader unavailable",failure);}}
    @Override public void close(){bindings.close();}
}

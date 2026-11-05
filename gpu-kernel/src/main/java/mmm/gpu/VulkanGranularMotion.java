package mmm.gpu;

import org.lwjgl.vulkan.VkCommandBuffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.EnumMap;
import java.util.List;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Spatial buckets, sphere contacts and private trial positions over the resident eight-box geometry. */
final class VulkanGranularMotion implements AutoCloseable {
    private enum Header {
        SIZE_X(0),
        SIZE_Y(1),
        SIZE_Z(2),
        PARTICLE_CAPACITY(3),
        CELL_COUNT(4),
        MASKS(5),
        COUNTS(6),
        POSITION(7),
        VELOCITY(8),
        VOLUME(9),
        MATERIAL(10),
        CONTACTS_HEADER(11),
        FINAL_Y(12),
        GEOMETRY_OFFSETS(13),
        GEOMETRY_ENTRIES(14),
        DEPOSIT_POROSITY(15),
        ACTIVITY_CELLS(40);
        final int word;
        Header(int word) { this.word = word; }
    }
    private static final int WORK_FIELDS = 16;
    private static String shaderLayout() {
        var result = new StringBuilder();
        for (var field : Header.values()) result.append("#define H_").append(field.name()).append(' ').append(field.word).append('\n');
        result.append("#define WORK_FIELDS ").append(WORK_FIELDS).append('\n');
        for (var field : VulkanGranularContacts.Header.values()) result.append("#define CONTACT_H_").append(field.name()).append(' ').append(field.word).append('\n');
        result.append("#define CONTACT_FIELDS ").append(VulkanGranularContacts.WORK_FIELDS).append('\n');
        return result + VulkanShaderLayout.fields("FIELD_", Field.values()) + VulkanShaderLayout.fields("CONTACT_", VulkanGranularContacts.Field.values());
    }

    enum Field { COUNTS,OFFSETS,CURSORS,ENTRIES,CONTACT_COUNTS,CONTACT_OFFSETS,SCAN,POSITION,RADIUS,MATERIAL,NEXT_POSITION,BED_HEIGHT,MAX_SPEED }
    static final class Layout {
        final VulkanGranularStateLayout state;
        final VulkanGranularContacts.Layout contacts;
        final EnumMap<Field,VulkanLiquidStateLayout.Column> columns=new EnumMap<>(Field.class);
        final VulkanLiquidStateLayout.Column header;
        final long endOffset;
        Layout(VulkanGranularStateLayout state,VulkanGranularContacts.Layout contacts,VulkanMixturePressure.Layout pressure,
               int masksWord,long base,long alignment){
            this(state,contacts,pressure.column(VulkanMixturePressure.Field.FINAL_Y),masksWord,base,alignment);
        }
        Layout(VulkanGranularStateLayout state,VulkanGranularContacts.Layout contacts,VulkanLiquidStateLayout.Column finalY,
               int masksWord,long base,long alignment){
            this.state=state;this.contacts=contacts;
            if(base<contacts.endOffset()||alignment<8||(alignment&(alignment-1))!=0)throw new IllegalArgumentException("Invalid granular motion layout");
            header=new VulkanLiquidStateLayout.Column(align(base,alignment),64,4);long cursor=header.offset()+header.bytes();
            for(var f:Field.values()){
                int count=switch(f){case COUNTS,CURSORS,BED_HEIGHT->state.cells();case OFFSETS->state.cells()+1;
                    case CONTACT_COUNTS,RADIUS->state.particleCapacity();case CONTACT_OFFSETS->state.particleCapacity()+1;
                    case ENTRIES->state.particleCapacity();case POSITION,NEXT_POSITION->3*state.particleCapacity();
                    case MATERIAL->3*state.definitions().materialIds().size();
                    case MAX_SPEED->1;
                    case SCAN->Math.toIntExact(VulkanPrefixScan.workspaceBytes(Math.max(state.cells(),state.particleCapacity()))/4);};
                var c=new VulkanLiquidStateLayout.Column(align(cursor,alignment),count,f==Field.POSITION||f==Field.NEXT_POSITION||f==Field.RADIUS||f==Field.MATERIAL||f==Field.BED_HEIGHT?8:4);
                columns.put(f,c);cursor=c.offset()+c.bindingBytes();
            }endOffset=align(cursor,alignment);
            headerData=new int[]{state.sizeX(),state.sizeY(),state.sizeZ(),state.particleCapacity(),state.cells(),masksWord,
                    state.word(VulkanGranularStateLayout.Field.COUNTS),state.word(VulkanGranularStateLayout.Field.POSITION),
                    state.word(VulkanGranularStateLayout.Field.VELOCITY),state.word(VulkanGranularStateLayout.Field.SOLID_VOLUME_SVQ),
                    state.word(VulkanGranularStateLayout.Field.MATERIAL),contacts.headerWordOffset(),
                    Math.toIntExact(finalY.offset()/4),-1,-1,state.word(VulkanGranularStateLayout.Field.DEPOSIT_POROSITY)};
        }
        private final int[] headerData;
        private int activityCellWord=-1;
        void bindActivity(int word){activityCellWord=word;}
        int word(Field f){return Math.toIntExact(columns.get(f).offset()/4);}
        long endOffset(){return endOffset;}
        /** Source occupancy must be rebuilt before contacts; bed height is derived from its actual deposit rows. */
        void bindBedGeometry(VulkanGranularGeometry.Layout geometry){
            if(geometry.state()!=state)throw new IllegalArgumentException("Bed geometry uses another granular inventory");
            headerData[Header.GEOMETRY_OFFSETS.word]=geometry.word(VulkanGranularGeometry.Field.OFFSETS);headerData[Header.GEOMETRY_ENTRIES.word]=geometry.word(VulkanGranularGeometry.Field.ENTRIES);
        }
        ByteBuffer headerBytes(){var b=ByteBuffer.allocateDirect(256).order(ByteOrder.LITTLE_ENDIAN);
            for(int i=0;i<headerData.length;i++)b.putInt(4*i,headerData[i]);b.putInt(4 * Header.ACTIVITY_CELLS.word,activityCellWord);
            for(var f:Field.values())b.putInt(4*(WORK_FIELDS+f.ordinal()),word(f));return b;}
        void initializeHeaderAndMaterials(ByteBuffer arena,mmm.granular.GranularRegistry registry){
            arena.duplicate().position(Math.toIntExact(header.offset())).put(headerBytes());
            var b=arena.duplicate().order(ByteOrder.LITTLE_ENDIAN);var ids=state.definitions().materialIds();
            for(int i=0;i<ids.size();i++){var m=registry.material(registry.materialHandle(ids.get(i)));long a=columns.get(Field.MATERIAL).offset()+24L*i;
                b.putDouble(Math.toIntExact(a),m.restitution()).putDouble(Math.toIntExact(a+8),m.staticFriction()).putDouble(Math.toIntExact(a+16),m.dynamicFriction());}
        }
    }
    private final FlipKernelBindings bindings;private final VulkanPrefixScan scan;
    VulkanGranularMotion(VulkanDeviceContext context){bindings=new FlipKernelBindings(context,2,shader(),32);
        try{scan=new VulkanPrefixScan(context);}catch(RuntimeException|LinkageError e){bindings.close();throw e;}}
    void rebindRanges(FlipKernelBindings.BufferRange arena,FlipKernelBindings.BufferRange errors){bindings.rebindRanges(List.of(arena,errors));scan.rebindRanges(arena,arena,arena,errors);}
    /** Record the pressure-corrected maximum particle speed without advancing the candidate. */
    void recordSpeed(VkCommandBuffer command,Layout l,double h){
        stage(command,l,0,Math.max(l.state.cells(),l.state.particleCapacity()),h,0);
        stage(command,l,1,l.state.particleCapacity(),h,0);
    }
    static double maximumSpeed(VulkanRegionStorage.Candidate candidate,Layout layout){
        var data=ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        candidate.read(layout.columns.get(Field.MAX_SPEED).offset(),data);
        float speed=Float.intBitsToFloat(data.getInt(0));
        return speed==0.0f ? 0.0 : Math.nextUp(speed);
    }
    /** Builds actual contacts from current positions. CONTROL[1] reports the required contact capacity. */
    void recordContacts(VkCommandBuffer command,Layout l,double h,double duration){
        stage(command,l,11,l.state.cells(),h,duration);
        stage(command,l,0,Math.max(l.state.cells(),l.state.particleCapacity()),h,duration);
        stage(command,l,1,l.state.particleCapacity(),h,duration);
        scan.record(command,l.state.cells(),l.word(Field.COUNTS),l.word(Field.OFFSETS),l.word(Field.SCAN));
        stage(command,l,2,l.state.particleCapacity(),h,duration);
        stage(command,l,3,l.state.particleCapacity(),h,duration);
        scan.record(command,l.state.particleCapacity(),l.word(Field.CONTACT_COUNTS),l.word(Field.CONTACT_OFFSETS),l.word(Field.SCAN));
        stage(command,l,4,l.state.particleCapacity(),h,duration);stage(command,l,5,1,h,duration);
    }
    /** Integrates the contact-corrected velocity and stops at the first fixed-box impact. */
    void recordTrialPositions(VkCommandBuffer command,Layout l,double h,double duration){
        stage(command,l,6,l.state.particleCapacity(),h,duration);
        for(int i=0;i<8;i++){stage(command,l,8,l.state.particleCapacity(),h,duration);stage(command,l,9,l.state.particleCapacity(),h,duration);}
        stage(command,l,10,l.state.particleCapacity(),h,duration);
    }
    /** Continue projection on the same private trial, retaining its already solved contact velocities. */
    void recordRelaxTrial(VkCommandBuffer command,Layout l,double h,double duration){
        stage(command,l,12,1,h,duration);
        for(int i=0;i<8;i++){stage(command,l,8,l.state.particleCapacity(),h,duration);stage(command,l,9,l.state.particleCapacity(),h,duration);}
        stage(command,l,10,l.state.particleCapacity(),h,duration);
    }
    /** Candidate-only position publication. Rebuild support/occupancy, then accept the joint liquid capacity. */
    void recordPublishPositions(VkCommandBuffer command,Layout l,double h){stage(command,l,7,l.state.particleCapacity(),h,0);}
    private void stage(VkCommandBuffer command,Layout l,int stage,int count,double h,double dt){if(count==0)return;
        try(var stack=stackPush()){bindings.bind(command,stack);var p=stack.malloc(32).putInt(Math.toIntExact(l.header.offset()/4)).putInt(stage).putInt(count).putInt(0).putDouble(h).putDouble(dt).flip();
            vkCmdPushConstants(command,bindings.pipelineLayout(),VK_SHADER_STAGE_COMPUTE_BIT,0,p);int groups=(count+127)/128;
            vkCmdDispatch(command,Math.min(groups,65535),(groups+65534)/65535,1);
            VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);}}
    private static long align(long v,long a){return Math.addExact(v,a-1)&-a;}
    private static String shader(){try(var in=VulkanGranularMotion.class.getResourceAsStream("/assets/mmm_gpu_probe/shaders/granular-motion.comp")){
        if(in==null)throw new IllegalStateException("Granular motion shader missing");return new String(in.readAllBytes(),StandardCharsets.UTF_8).replace("// HOST_LAYOUT", shaderLayout());
    }catch(IOException e){throw new IllegalStateException("Granular motion shader unavailable",e);}}
    @Override public void close(){try{scan.close();}finally{bindings.close();}}
}

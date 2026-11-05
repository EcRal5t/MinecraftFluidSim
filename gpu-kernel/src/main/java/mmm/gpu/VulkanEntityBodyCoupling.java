package mmm.gpu;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.EnumMap;
import java.util.List;
import org.lwjgl.vulkan.VkCommandBuffer;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Prescribed AABBs: union occupation, shared liquid apertures, tangential contact, known
 * pressure flux, and the opposite impulse/external work, all from the same device geometry. */
final class VulkanEntityBodyCoupling implements AutoCloseable {
    private enum Header {
        SIZE_X(0), SIZE_Y(1), SIZE_Z(2), CELLS(3),
        FACES(4), BODY_CAPACITY(5), ENTRY_CAPACITY(6), CONTACT_CAPACITY(7),
        PRESSURE_ROWS(8), MASKS(9), GEOMETRY_CELLS(10), GEOMETRY_FACES(11),
        BASE_SOLID(12), MIXTURE_STATES(13), COUPLED_PRESSURE(14), CONTACT_IMPULSE(15),
        SOURCE_CELL_WORK(17), SOURCE_OCCUPATION(18), ACTIVITY_FACES(24), FIELDS(32);
        final int word; Header(int word) { this.word = word; }
    }


    private enum Stage {
        RESET_GEOMETRY,
        ENUMERATE_BODY_ENTRIES,
        SCATTER_CELL_ENTRIES,
        GATHER_CELL_OCCUPATION,
        COUNT_FACE_CONTACTS,
        SCATTER_FACE_CONTACTS,
        GATHER_FACE_OCCUPATION,
        GATHER_BODY_TOTALS,
        PREPARE_CONTACT_DISTANCES,
        BUILD_PRESSURE_CONTACTS,
        ASSEMBLE_BODY_VOLUME_RATE,
        GATHER_BODY_RESULTS,
        SETTLE_CELL_WORK,
        APPLY_LIQUID_WET_FRACTION,
        COMBINE_SOLID_OCCUPATION
    }
    private enum Control { BODY_COUNT, ENTRY_COUNT, CONTACT_COUNT, RESERVED }
    private enum BodyTotal { WET_AREA_X, WET_AREA_Y, WET_AREA_Z, CONTACT_VOLUME_X, CONTACT_VOLUME_Y, CONTACT_VOLUME_Z, WET_VOLUME }
    private enum BodyResult { IMPULSE_X, IMPULSE_Y, IMPULSE_Z, COUPLED_VOLUME }
    private static final int BODY_TOTAL_STRIDE = BodyTotal.values().length;
    private static final int BODY_RESULT_STRIDE = BodyResult.values().length;
    static final int BODY_RESULT_BYTES = Double.BYTES * BODY_RESULT_STRIDE;

    private static String shaderLayout() {
        var result = new StringBuilder();
        for (var field : Header.values()) result.append("#define H_").append(field.name()).append(' ').append(field.word).append('\n');
        result.append(VulkanShaderLayout.fields("F_", Field.values()));
        result.append(VulkanShaderLayout.fields("STAGE_", Stage.values()));
        result.append(VulkanShaderLayout.fields("CONTROL_", Control.values()));
        result.append(VulkanShaderLayout.fields("BODY_TOTAL_", BodyTotal.values()));
        result.append(VulkanShaderLayout.fields("BODY_RESULT_", BodyResult.values()));
        result.append("#define BODY_TOTAL_STRIDE ").append(BODY_TOTAL_STRIDE).append('\n');
        result.append("#define BODY_RESULT_STRIDE ").append(BODY_RESULT_STRIDE).append('\n');
        for (var field : VulkanCoupledPressure.Header.values()) result.append("#define COUPLED_H_").append(field.name()).append(' ').append(field.word).append('\n');
        result.append("#define COUPLED_WORK_FIELDS ").append(VulkanCoupledPressure.WORK_FIELDS).append('\n');
        return result + VulkanShaderLayout.fields("COUPLED_F_", VulkanCoupledPressure.Field.values());
    }
    record Configuration(mmm.fluid.EntityBodyState bodies,int sizeX,int sizeY,int sizeZ,double cellSize){
        Configuration{if(bodies==null||sizeX<1||sizeY<1||sizeZ<1||!(cellSize>0))throw new IllegalArgumentException("Invalid prescribed-body configuration");}
        int[] cellOffsets(){int[] offsets=new int[bodies.bodies().size()+1];for(int b=0;b<bodies.bodies().size();b++){
            var bounds=bodies.bodies().get(b).bounds();int count=1;
            double[] low={bounds.minimumX(),bounds.minimumY(),bounds.minimumZ()},high={bounds.maximumX(),bounds.maximumY(),bounds.maximumZ()};int[] dims={sizeX,sizeY,sizeZ};
            for(int axis=0;axis<3;axis++){int first=Math.max(0,(int)Math.floor(low[axis]/cellSize)),last=Math.min(dims[axis],(int)Math.ceil(high[axis]/cellSize));count=Math.multiplyExact(count,Math.max(0,last-first));}
            offsets[b+1]=Math.addExact(offsets[b],count);
        }return offsets;}
        int cellEntries(){var offsets=cellOffsets();return offsets[offsets.length-1];}
    }
    enum Field {
        CONTROL(4),BOUNDS(8),VELOCITIES(8),BODY_OFFSETS(4),ENTRY_CELL(4),ENTRY_BODY(4),ENTRY_VOLUME(8),ENTRY_WET_AREA(8),
        CELL_COUNTS(4),CELL_OFFSETS(4),CELL_CURSORS(4),CELL_ENTRIES(4),OCCUPIED_SVQ(8),COMBINED_SVQ(8),
        FACE_COUNTS(4),FACE_OFFSETS(4),FACE_CURSORS(4),CONTACT_BODY(4),CONTACT_ENTRY(4),CONTACT_FACE(4),CONTACT_SHARE(8),CONTACT_DISTANCE(8),FACE_AREA(8),
        BODY_TOTALS(8),PRESSURE_ROW(4),PRESSURE_COEFFICIENT(8),
        BODY_RESULTS(8),SOURCE_WORK(8),SCAN(4),ENTRY_CONTACTS(4),ROW_BODY_RATE(8);
        final int width;Field(int width){this.width=width;}
    }
    static final class Layout {
        final int sizeX,sizeY,sizeZ,cells,faces,bodyCapacity,entryCapacity,contactCapacity,pressureRows;
        final VulkanLiquidStateLayout.Column header;
        final EnumMap<Field,VulkanLiquidStateLayout.Column> columns=new EnumMap<>(Field.class);
        final long endOffset;final int[] inputs=new int[32];
        Layout(int sx,int sy,int sz,int bodyCapacity,int entryCapacity,int pressureRows,
               VulkanLiquidStateLayout.Column masks,VulkanLiquidStateLayout.Column cells,VulkanLiquidStateLayout.Column faces,
               VulkanLiquidStateLayout.Column baseSolid,long base,long alignment){
            sizeX=sx;sizeY=sy;sizeZ=sz;this.cells=Math.multiplyExact(Math.multiplyExact(sx,sy),sz);this.faces=faces.count()/4;
            this.bodyCapacity=bodyCapacity;this.entryCapacity=entryCapacity;this.pressureRows=pressureRows;contactCapacity=Math.multiplyExact(6,entryCapacity);
            if(bodyCapacity<0||bodyCapacity>mmm.world.MovingBodyFluidCoupling.MAXIMUM_REQUESTS_PER_STEP||entryCapacity<0||pressureRows<this.cells
                    ||masks.count()!=this.cells||cells.count()!=4*this.cells||base<0)throw new IllegalArgumentException("Prescribed body layout differs");
            header=new VulkanLiquidStateLayout.Column(align(base,alignment),96,4);long cursor=header.offset()+header.bytes();
            for(var f:Field.values()){
                int count=switch(f){
                    case CONTROL->4;case BOUNDS->6*bodyCapacity;case VELOCITIES->3*bodyCapacity;case BODY_OFFSETS->bodyCapacity+1;
                    case ENTRY_CELL,ENTRY_BODY,ENTRY_VOLUME->entryCapacity;case ENTRY_WET_AREA->3*entryCapacity;case ENTRY_CONTACTS->6*entryCapacity;
                    case CELL_COUNTS,CELL_CURSORS,OCCUPIED_SVQ,COMBINED_SVQ,SOURCE_WORK->this.cells;
                    case CELL_OFFSETS->this.cells+1;case CELL_ENTRIES->entryCapacity;
                    case FACE_COUNTS,FACE_CURSORS,FACE_AREA->this.faces;case FACE_OFFSETS->this.faces+1;
                    case CONTACT_BODY,CONTACT_ENTRY,CONTACT_FACE,CONTACT_SHARE,CONTACT_DISTANCE->contactCapacity;
                    case BODY_TOTALS->BODY_TOTAL_STRIDE*bodyCapacity;case BODY_RESULTS->BODY_RESULT_STRIDE*bodyCapacity;
                    case PRESSURE_ROW,PRESSURE_COEFFICIENT->2*contactCapacity;
                    case ROW_BODY_RATE->pressureRows;
                    case SCAN->Math.toIntExact(VulkanPrefixScan.workspaceBytes(Math.max(this.cells,Math.max(this.faces,pressureRows)))/4);
                };
                var c=new VulkanLiquidStateLayout.Column(align(cursor,alignment),count,f.width);columns.put(f,c);cursor=c.offset()+c.bindingBytes();
            }
            endOffset=align(cursor,alignment);inputs[Header.SIZE_X.word]=sx;inputs[Header.SIZE_Y.word]=sy;inputs[Header.SIZE_Z.word]=sz;inputs[Header.CELLS.word]=this.cells;inputs[Header.FACES.word]=this.faces;
            inputs[Header.BODY_CAPACITY.word]=bodyCapacity;inputs[Header.ENTRY_CAPACITY.word]=entryCapacity;inputs[Header.CONTACT_CAPACITY.word]=contactCapacity;inputs[Header.PRESSURE_ROWS.word]=pressureRows;
            inputs[Header.MASKS.word]=word(masks);inputs[Header.GEOMETRY_CELLS.word]=word(cells);inputs[Header.GEOMETRY_FACES.word]=word(faces);inputs[Header.BASE_SOLID.word]=baseSolid==null?-1:word(baseSolid);
            java.util.Arrays.fill(inputs,13,inputs.length,-1);
        }
        VulkanLiquidStateLayout.Column column(Field f){return columns.get(f);}int word(Field f){return word(column(f));}int headerWord(){return word(header);}
        long endOffset(){return endOffset;}
        VulkanLiquidViscosity.PrescribedContacts prescribedContacts(){return new VulkanLiquidViscosity.PrescribedContacts(contactCapacity,bodyCapacity,
                column(Field.FACE_OFFSETS),column(Field.CONTACT_BODY),column(Field.CONTACT_DISTANCE),column(Field.VELOCITIES));}
        void bindViscosity(VulkanLiquidViscosity.Layout viscosity){
            inputs[Header.CONTACT_IMPULSE.word]=word(viscosity.column(VulkanLiquidViscosity.Field.CONTACT_IMPULSE));
            inputs[Header.SOURCE_CELL_WORK.word]=word(viscosity.column(VulkanLiquidViscosity.Field.SOURCE_CELL_WORK));
        }
        void bindBaseSolid(VulkanLiquidStateLayout.Column base){inputs[Header.BASE_SOLID.word]=word(base);}
        void bindSourceOccupation(Layout source){inputs[Header.SOURCE_OCCUPATION.word]=source.word(Field.OCCUPIED_SVQ);}
        void bindActivity(int faceWord){inputs[Header.ACTIVITY_FACES.word]=faceWord;}
        void bindPressure(VulkanMixtureState.Layout mixture,VulkanCoupledPressure.Layout coupled){
            if(mixture.inputs().cells()!=cells||coupled.pressureSize()!=pressureRows)throw new IllegalArgumentException("Body pressure rows differ");
            inputs[Header.MIXTURE_STATES.word]=mixture.statesWord();
            inputs[Header.COUPLED_PRESSURE.word]=coupled.headerWordOffset();
        }
        ByteBuffer headerBytes(){var b=ByteBuffer.allocateDirect(384).order(ByteOrder.LITTLE_ENDIAN);for(int i=0;i<inputs.length;i++)b.putInt(4*i,inputs[i]);
            for(var f:Field.values())b.putInt(4*(Header.FIELDS.word+f.ordinal()),word(f));return b;}
        ByteBuffer bodiesBytes(Configuration c){if(c.bodies().bodies().size()>bodyCapacity||c.cellEntries()>entryCapacity||c.sizeX()!=sizeX||c.sizeY()!=sizeY||c.sizeZ()!=sizeZ)throw new IllegalArgumentException("Body request exceeds device capacity");
            var b=ByteBuffer.allocateDirect(8+72*c.bodies().bodies().size()+4*(c.bodies().bodies().size()+1)).order(ByteOrder.LITTLE_ENDIAN);
            b.putInt(c.bodies().bodies().size()).putInt(c.cellEntries());for(var body:c.bodies().bodies()){var a=body.bounds();b.putDouble(a.minimumX()).putDouble(a.minimumY()).putDouble(a.minimumZ()).putDouble(a.maximumX()).putDouble(a.maximumY()).putDouble(a.maximumZ());}
            for(var body:c.bodies().bodies())b.putDouble(body.velocityXMetresPerSecond()).putDouble(body.velocityYMetresPerSecond()).putDouble(body.velocityZMetresPerSecond());
            for(int value:c.cellOffsets())b.putInt(value);return b.flip();
        }
        void initializeBodies(ByteBuffer arena,Configuration configuration){
            for(var chunk:bodyChunks(configuration))arena.duplicate().position(Math.toIntExact(chunk.offset())).put(chunk.bytes());
        }
        record Chunk(long offset,ByteBuffer bytes){}
        List<Chunk> bodyChunks(Configuration configuration){var data=bodiesBytes(configuration);int count=configuration.bodies().bodies().size();
            return List.of(new Chunk(column(Field.CONTROL).offset(),data.slice(0,8)),new Chunk(column(Field.BOUNDS).offset(),data.slice(8,48*count)),
                    new Chunk(column(Field.VELOCITIES).offset(),data.slice(8+48*count,24*count)),new Chunk(column(Field.BODY_OFFSETS).offset(),data.slice(8+72*count,4*(count+1))));}
        mmm.world.MovingBodyFluidCoupling.Result result(ByteBuffer buffer,Configuration config,double work){
            var b=buffer.slice().order(ByteOrder.LITTLE_ENDIAN);double ix=0,iy=0,iz=0,volume=0;var impulses=new java.util.ArrayList<mmm.world.MovingBodyFluidCoupling.BodyImpulse>();
            for(int body=0;body<config.bodies().bodies().size();body++){int off=Double.BYTES*BODY_RESULT_STRIDE*body;double x=b.getDouble(off),y=b.getDouble(off+Double.BYTES*BodyResult.IMPULSE_Y.ordinal()),z=b.getDouble(off+Double.BYTES*BodyResult.IMPULSE_Z.ordinal());
                ix+=x;iy+=y;iz+=z;volume+=b.getDouble(off+Double.BYTES*BodyResult.COUPLED_VOLUME.ordinal());
                impulses.add(new mmm.world.MovingBodyFluidCoupling.BodyImpulse(config.bodies().bodies().get(body).bodyId(),-x,-y,-z));}
            return new mmm.world.MovingBodyFluidCoupling.Result(impulses.size(),volume,ix,iy,iz,work,impulses);
        }
        private static int word(VulkanLiquidStateLayout.Column c){return Math.toIntExact(c.offset()/4);}
    }
    private final FlipKernelBindings bindings;private final VulkanPrefixScan scan;
    VulkanEntityBodyCoupling(VulkanDeviceContext context){bindings=new FlipKernelBindings(context,2,shader(),40);
        try{scan=new VulkanPrefixScan(context);}catch(RuntimeException|LinkageError failure){bindings.close();throw failure;}}
    void rebindRanges(FlipKernelBindings.BufferRange arena,FlipKernelBindings.BufferRange errors){bindings.rebindRanges(List.of(arena,errors));scan.rebindRanges(arena,arena,arena,errors);}
    /** Only the externally prescribed pose/velocity table crosses the host/device interface. */
    void recordInputs(VkCommandBuffer command,Layout l,GpuBuffer arena,Configuration configuration){try(var stack=stackPush()){
        for(var chunk:l.bodyChunks(configuration))if(chunk.bytes().hasRemaining())vkCmdUpdateBuffer(command,arena.handle(),chunk.offset(),chunk.bytes());
        VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);
    }}
    void recordGeometry(VkCommandBuffer command,Layout l,double h){
        recordOccupancy(command,l,h);recordContacts(command,l,h);
    }
    void recordOccupancy(VkCommandBuffer command,Layout l,double h){
        stage(command,l,Stage.RESET_GEOMETRY,Math.max(l.cells,Math.max(l.faces,l.pressureRows)),h,1);
        stage(command,l,Stage.ENUMERATE_BODY_ENTRIES,l.entryCapacity,h,1);scan.record(command,l.cells,l.word(Field.CELL_COUNTS),l.word(Field.CELL_OFFSETS),l.word(Field.SCAN));
        stage(command,l,Stage.SCATTER_CELL_ENTRIES,l.entryCapacity,h,1);stage(command,l,Stage.GATHER_CELL_OCCUPATION,l.cells,h,1);
    }
    void recordContacts(VkCommandBuffer command,Layout l,double h){
        stage(command,l,Stage.APPLY_LIQUID_WET_FRACTION,l.entryCapacity,h,1);stage(command,l,Stage.COUNT_FACE_CONTACTS,l.entryCapacity,h,1);
        scan.record(command,l.faces,l.word(Field.FACE_COUNTS),l.word(Field.FACE_OFFSETS),l.word(Field.SCAN));
        stage(command,l,Stage.SCATTER_FACE_CONTACTS,l.entryCapacity,h,1);stage(command,l,Stage.GATHER_FACE_OCCUPATION,l.faces,h,1);stage(command,l,Stage.GATHER_BODY_TOTALS,l.bodyCapacity,h,1);stage(command,l,Stage.PREPARE_CONTACT_DISTANCES,l.contactCapacity,h,1);
    }
    void recordCombinedOccupancy(VkCommandBuffer command,Layout l,double h){stage(command,l,Stage.COMBINE_SOLID_OCCUPATION,l.cells,h,1);}
    /** After source pressure preparation and any granular incidence, before the shared solve. */
    void recordPressure(VkCommandBuffer command,Layout l,double h,double dt){
        stage(command,l,Stage.BUILD_PRESSURE_CONTACTS,l.contactCapacity,h,dt);stage(command,l,Stage.ASSEMBLE_BODY_VOLUME_RATE,l.pressureRows,h,dt);
    }
    /** After projection, before common mechanical-energy posting. */
    void recordResult(VkCommandBuffer command,Layout l,double h,double dt){
        stage(command,l,Stage.GATHER_BODY_RESULTS,l.bodyCapacity,h,dt);stage(command,l,Stage.SETTLE_CELL_WORK,l.cells,h,dt);
    }
    private void stage(VkCommandBuffer command,Layout l,Stage stage,int count,double h,double dt){if(count==0)return;
        try(var stack=stackPush()){bindings.bind(command,stack);var push=stack.malloc(40).putInt(l.headerWord()).putInt(stage.ordinal()).putInt(count).putInt(0).putDouble(h).putDouble(dt).putDouble(0).flip();
            vkCmdPushConstants(command,bindings.pipelineLayout(),VK_SHADER_STAGE_COMPUTE_BIT,0,push);int groups=(count+127)/128;vkCmdDispatch(command,Math.min(groups,65535),(groups+65534)/65535,1);
            VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);}}
    private static long align(long v,long a){return Math.addExact(v,a-1)&-a;}
    private static String shader(){try(var in=VulkanEntityBodyCoupling.class.getResourceAsStream("/assets/mmm_gpu_probe/shaders/entity-body.comp")){
        if(in==null)throw new IllegalStateException("Entity body shader missing");return new String(in.readAllBytes(),StandardCharsets.UTF_8).replace("// HOST_LAYOUT", shaderLayout());
    }catch(IOException e){throw new IllegalStateException("Entity body shader unavailable",e);}}
    @Override public void close(){try{scan.close();}finally{bindings.close();}}
}

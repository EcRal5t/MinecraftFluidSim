package mmm.gpuprobe;

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
        BODY_TOTALS(8),PRESSURE_ROW(4),PRESSURE_COEFFICIENT(8),ROW_COUNTS(4),ROW_OFFSETS(4),ROW_CURSORS(4),ROW_ENTRIES(4),
        BODY_RESULTS(8),SOURCE_WORK(8),SCAN(4),ENTRY_CONTACTS(4),ROW_BODY_RATE(8),GAS_EXTERNAL_WORK(8);
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
                    case BODY_TOTALS->7*bodyCapacity;case BODY_RESULTS->8*bodyCapacity;
                    case PRESSURE_ROW,PRESSURE_COEFFICIENT,ROW_ENTRIES->2*contactCapacity;
                    case ROW_COUNTS,ROW_CURSORS,ROW_BODY_RATE->pressureRows;case ROW_OFFSETS->pressureRows+1;
                    case GAS_EXTERNAL_WORK->pressureRows-this.cells;
                    case SCAN->Math.toIntExact(VulkanPrefixScan.workspaceBytes(Math.max(this.cells,Math.max(this.faces,pressureRows)))/4);
                };
                var c=new VulkanLiquidStateLayout.Column(align(cursor,alignment),count,f.width);columns.put(f,c);cursor=c.offset()+c.bindingBytes();
            }
            endOffset=align(cursor,alignment);inputs[0]=sx;inputs[1]=sy;inputs[2]=sz;inputs[3]=this.cells;inputs[4]=this.faces;
            inputs[5]=bodyCapacity;inputs[6]=entryCapacity;inputs[7]=contactCapacity;inputs[8]=pressureRows;
            inputs[9]=word(masks);inputs[10]=word(cells);inputs[11]=word(faces);inputs[12]=baseSolid==null?-1:word(baseSolid);
            java.util.Arrays.fill(inputs,13,inputs.length,-1);
        }
        VulkanLiquidStateLayout.Column column(Field f){return columns.get(f);}int word(Field f){return word(column(f));}int headerWord(){return word(header);}
        long endOffset(){return endOffset;}
        VulkanLiquidViscosity.PrescribedContacts prescribedContacts(){return new VulkanLiquidViscosity.PrescribedContacts(contactCapacity,bodyCapacity,
                column(Field.FACE_OFFSETS),column(Field.CONTACT_BODY),column(Field.CONTACT_DISTANCE),column(Field.VELOCITIES));}
        void bindPressure(VulkanGasPressure.Layout gas,VulkanCoupledPressure.Layout coupled){
            if(gas.pressureSize()!=pressureRows||coupled!=null&&coupled.pressureSize()!=pressureRows)throw new IllegalArgumentException("Body pressure rows differ");
            inputs[13]=gas.headerWordOffset();inputs[14]=coupled==null?-1:coupled.headerWordOffset();
        }
        void bindViscosity(VulkanLiquidViscosity.Layout viscosity){
            inputs[15]=word(viscosity.column(VulkanLiquidViscosity.Field.CONTACT_IMPULSE));inputs[16]=word(viscosity.column(VulkanLiquidViscosity.Field.CONTACT_WORK));
            inputs[17]=word(viscosity.column(VulkanLiquidViscosity.Field.SOURCE_CELL_WORK));
        }
        void bindBaseSolid(VulkanLiquidStateLayout.Column base){inputs[12]=word(base);}
        void bindPressure(VulkanGasPressure.PressureBuffers pressure){
            inputs[18]=word(pressure.starts());inputs[19]=word(pressure.values());inputs[20]=word(pressure.rows());
            inputs[21]=word(pressure.pressure());inputs[22]=word(pressure.meta());inputs[23]=word(pressure.problem());
        }
        ByteBuffer headerBytes(){var b=ByteBuffer.allocateDirect(384).order(ByteOrder.LITTLE_ENDIAN);for(int i=0;i<inputs.length;i++)b.putInt(4*i,inputs[i]);
            for(var f:Field.values())b.putInt(4*(32+f.ordinal()),word(f));return b;}
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
        List<VulkanEnergyLedger.Stream> externalWorkRoundoffStreams(){
            return List.of(new VulkanEnergyLedger.Stream(1,word(Field.SOURCE_WORK),cells,2,1),
                    new VulkanEnergyLedger.Stream(1,word(Field.GAS_EXTERNAL_WORK),pressureRows-cells,2,1),
                    new VulkanEnergyLedger.Stream(1,word(Field.BODY_RESULTS)+6,bodyCapacity,16,-1),
                    new VulkanEnergyLedger.Stream(1,word(Field.BODY_RESULTS)+8,bodyCapacity,16,-1));
        }
        mmm.world.MovingBodyFluidCoupling.Result result(ByteBuffer buffer,Configuration config){
            var b=buffer.slice().order(ByteOrder.LITTLE_ENDIAN);double ix=0,iy=0,iz=0,work=0,volume=0;var impulses=new java.util.ArrayList<mmm.world.MovingBodyFluidCoupling.BodyImpulse>();
            for(int body=0;body<config.bodies().bodies().size();body++){int off=64*body;double x=b.getDouble(off),y=b.getDouble(off+8),z=b.getDouble(off+16);
                ix+=x;iy+=y;iz+=z;work+=b.getDouble(off+24)+b.getDouble(off+32);volume+=b.getDouble(off+40);
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
        stage(command,l,0,Math.max(l.cells,Math.max(l.faces,l.pressureRows)),h,1);
        stage(command,l,1,l.entryCapacity,h,1);scan.record(command,l.cells,l.word(Field.CELL_COUNTS),l.word(Field.CELL_OFFSETS),l.word(Field.SCAN));
        stage(command,l,2,l.entryCapacity,h,1);stage(command,l,3,l.cells,h,1);
    }
    void recordContacts(VkCommandBuffer command,Layout l,double h){
        stage(command,l,17,l.entryCapacity,h,1);stage(command,l,4,l.entryCapacity,h,1);
        scan.record(command,l.faces,l.word(Field.FACE_COUNTS),l.word(Field.FACE_OFFSETS),l.word(Field.SCAN));
        stage(command,l,5,l.entryCapacity,h,1);stage(command,l,6,l.faces,h,1);stage(command,l,7,l.bodyCapacity,h,1);stage(command,l,8,l.contactCapacity,h,1);
    }
    void recordCombinedOccupancy(VkCommandBuffer command,Layout l,double h){stage(command,l,18,l.cells,h,1);}
    /** For the pure-fluid path. GranularPressure can instead read FACE_AREA directly. */
    void recordApertures(VkCommandBuffer command,Layout l,double h){stage(command,l,9,l.faces,h,1);}
    /** After source pressure preparation and any granular incidence, before the shared solve. */
    void recordPressure(VkCommandBuffer command,Layout l,double h,double dt){
        stage(command,l,10,l.pressureRows,h,dt);stage(command,l,11,l.contactCapacity,h,dt);
        scan.record(command,l.pressureRows,l.word(Field.ROW_COUNTS),l.word(Field.ROW_OFFSETS),l.word(Field.SCAN));
        stage(command,l,12,2*l.contactCapacity,h,dt);stage(command,l,13,l.pressureRows,h,dt);
    }
    /** After projection, before common mechanical-energy posting. */
    void recordResult(VkCommandBuffer command,Layout l,double h,double dt){
        stage(command,l,16,l.pressureRows-l.cells,h,dt);stage(command,l,14,l.bodyCapacity,h,dt);stage(command,l,15,l.cells,h,dt);
    }
    private void stage(VkCommandBuffer command,Layout l,int stage,int count,double h,double dt){if(count==0)return;
        try(var stack=stackPush()){bindings.bind(command,stack);var push=stack.malloc(40).putInt(l.headerWord()).putInt(stage).putInt(count).putInt(0).putDouble(h).putDouble(dt).putDouble(0).flip();
            vkCmdPushConstants(command,bindings.pipelineLayout(),VK_SHADER_STAGE_COMPUTE_BIT,0,push);int groups=(count+127)/128;vkCmdDispatch(command,Math.min(groups,65535),(groups+65534)/65535,1);
            VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);}}
    private static long align(long v,long a){return Math.addExact(v,a-1)&-a;}
    private static String shader(){try(var in=VulkanEntityBodyCoupling.class.getResourceAsStream("/assets/mmm_gpu_probe/shaders/entity-body.comp")){
        if(in==null)throw new IllegalStateException("Entity body shader missing");return new String(in.readAllBytes(),StandardCharsets.UTF_8);
    }catch(IOException e){throw new IllegalStateException("Entity body shader unavailable",e);}}
    @Override public void close(){try{scan.close();}finally{bindings.close();}}
}

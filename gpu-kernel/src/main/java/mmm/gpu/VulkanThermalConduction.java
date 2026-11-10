package mmm.gpu;

import mmm.thermal.ThermalMaterialDefinition;
import org.lwjgl.vulkan.VkCommandBuffer;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.TreeSet;
import static mmm.gpu.VulkanThermalConduction.Field.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Conservative conduction on unique inventory owners. Spatial contacts may share an owner. */
final class VulkanThermalConduction implements AutoCloseable {
    private static final int STAGE_INITIALIZE = 0;
    private static final int STAGE_COUNT_CONTACTS = 1;
    private static final int STAGE_SCATTER_CONTACTS = 2;
    private static final int STAGE_PREPARE_OWNERS = 3;
    private static final int STAGE_NORMALIZE_RATES = 4;
    private static final int STAGE_PREPARE_TILES = 20;
    private static final int STAGE_COUNT_TILES = 21;
    private static final int STAGE_REDUCE_DEGREE_TILES = 22;
    private static final int STAGE_CLEAR_RESERVOIRS = 30;
    private static final int STAGE_CONTACT_DISPATCH = 31;
    private static final int STAGE_REDUCE_FLUX_TILES = 33;
    private static final int STAGE_SPATIAL_GATHER_DISPATCH = 36;
    private static final int STAGE_FINISH_ENERGY = 48;
    private static final int STAGE_INITIAL_FLUX = 51;
    private static String stageDefines() {
        return "#define STAGE_INITIALIZE " + STAGE_INITIALIZE + "\n" +
                "#define STAGE_COUNT_CONTACTS " + STAGE_COUNT_CONTACTS + "\n" +
                "#define STAGE_SCATTER_CONTACTS " + STAGE_SCATTER_CONTACTS + "\n" +
                "#define STAGE_PREPARE_OWNERS " + STAGE_PREPARE_OWNERS + "\n" +
                "#define STAGE_NORMALIZE_RATES " + STAGE_NORMALIZE_RATES + "\n" +
                "#define STAGE_PREPARE_TILES " + STAGE_PREPARE_TILES + "\n" +
                "#define STAGE_COUNT_TILES " + STAGE_COUNT_TILES + "\n" +
                "#define STAGE_REDUCE_DEGREE_TILES " + STAGE_REDUCE_DEGREE_TILES + "\n" +
                "#define STAGE_CLEAR_RESERVOIRS " + STAGE_CLEAR_RESERVOIRS + "\n" +
                "#define STAGE_CONTACT_DISPATCH " + STAGE_CONTACT_DISPATCH + "\n" +
                "#define STAGE_REDUCE_FLUX_TILES " + STAGE_REDUCE_FLUX_TILES + "\n" +
                "#define STAGE_SPATIAL_GATHER_DISPATCH " + STAGE_SPATIAL_GATHER_DISPATCH + "\n" +
                "#define STAGE_FINISH_ENERGY " + STAGE_FINISH_ENERGY + "\n" +
                "#define STAGE_INITIAL_FLUX " + STAGE_INITIAL_FLUX + "\n";
    }
    /** Header word positions shared with the shader. */
    private enum Header {
        OWNER_CAPACITY(0),
        TERM_CAPACITY(1),
        CONTACT_CAPACITY(2),
        MATERIAL_COUNT(3),
        SEGMENT_COUNT(4),
        ENABLED(5),
        BATH_CAPACITY(6),
        CONTACT_MODE(7),
        CONTACT_HEADER(76);
        final int word;
        Header(int word) { this.word = word; }
    }
    private static String headerDefines() {
        var result = new StringBuilder();
        for (var field : Header.values())
            result.append("#define H_").append(field.name()).append(' ').append(field.word).append('\n');
        return result.toString();
    }

    static final int WORK_FIELDS = 8;
    private static String shaderLayout() {
        return headerDefines() + stageDefines() + VulkanLiquidThermodynamics.shaderLayout() + "#define CONTACT_LIQUID_CALORIC " + VulkanThermalContacts.LIQUID_CALORIC_WORD + "\n" + "#define WORK_FIELDS " + WORK_FIELDS + "\n" + VulkanShaderLayout.fields("FIELD_", Field.values());
    }

    enum Field {
        COUNTS(4),OWNER_ENERGY(8),OWNER_INITIAL_T(8),TERM_OFFSETS(4),TERM_MATERIAL(4),TERM_MASS(8),
        CONTACT_A(4),CONTACT_B(4),CONTACT_G(8),CONTACT_BATH_T(8),
        KNOTS(8),MATERIAL_CP(8),MATERIAL_MIN_CP(8),MIN_CP(8),BEFORE_ENERGY(8),TEMPERATURE(8),
        DEGREE_COUNTS(4),DEGREE_OFFSETS(4),DEGREE_CURSORS(4),INCIDENT(4),DEGREE(8),FLUX(8),
        ENERGY_CHANGE(8),RESERVOIR_CHANGE(8),MODE(4),DISPATCH(4),SCAN(4),
        TILE_COUNTS(4),TILE_OFFSETS(4),TILE_OWNERS(4),TILE_PARTIALS(8),BATH_INDEX(4),BATH_EDGES(4);
        final int width;Field(int width){this.width=width;}
    }
    /** Common knots retain every registered caloric break. Cp includes endpoint extension intervals. */
    record Tables(double[] knots,double[] heatCapacity,double[] minimumCapacity,int materials) {
        int segments(){return knots.length+1;}
        static Tables fromMaterials(List<ThermalMaterialDefinition> materials){
            var knots=new TreeSet<Double>();
            for(var material:materials){var segments=material.caloricSegments();
                for(int i=0;i+1<segments.size();i++)knots.add(segments.get(i).maximumTemperatureK());}
            double[] upper=knots.stream().mapToDouble(Double::doubleValue).toArray();
            int segments=upper.length+1;double[] cp=new double[Math.multiplyExact(materials.size(),segments)],minimum=new double[materials.size()];
            for(int m=0;m<materials.size();m++){
                var curve=materials.get(m).caloricSegments();int part=0;minimum[m]=Double.POSITIVE_INFINITY;
                for(int s=0;s<segments;s++){
                    while(part+1<curve.size()&&s>0&&curve.get(part).maximumTemperatureK()<=upper[s-1])part++;
                    cp[m*segments+s]=curve.get(part).specificHeatJPerKgK();
                    minimum[m]=Math.min(minimum[m],cp[m*segments+s]);
                }
            }
            return new Tables(upper,cp,minimum,materials.size());
        }
    }
    static final class Layout {
        final int owners,terms,contacts,tiles,baths;final Tables tables;
        final VulkanLiquidStateLayout.Column header;final EnumMap<Field,VulkanLiquidStateLayout.Column> columns=new EnumMap<>(Field.class);
        final long endOffset;
        Layout(int owners,int terms,int contacts,Tables tables,long base,long alignment,int baths){this(owners,terms,contacts,tables,base,alignment,baths,0);}
        Layout(int owners,int terms,int contacts,Tables tables,long base,long alignment,int baths,int spatialSamples){
            this(owners,terms,contacts,tables,base,alignment,baths,spatialSamples,null);
        }
        Layout(int owners,int terms,int contacts,Tables tables,long base,long alignment,int baths,int spatialSamples,VulkanScratchPool pool){
            if(owners<1||terms<1||contacts<0||baths<0||tables.materials()<1||base<0||alignment<8||(alignment&(alignment-1))!=0)
                throw new IllegalArgumentException("Invalid thermal conduction layout");
            this.owners=owners;this.terms=terms;this.contacts=contacts;this.tables=tables;this.baths=baths;
            // Each long row has d>128 incidents, so ceil(d/128)<=d/64; all rows have at most 2*contacts incidents.
            boolean spatial=spatialSamples>0;
            tiles=spatial?0:Math.toIntExact((contacts+31L)/32);
            long[] cursor={base};header=allocate(cursor,80,4,alignment);
            for(Field field:Field.values()){
                int n=switch(field){
                    case COUNTS->3;
                    case TERM_OFFSETS->Math.addExact(owners,1);
                    case DEGREE_OFFSETS,TILE_OFFSETS->spatial?0:Math.addExact(owners,1);
                    case DEGREE_COUNTS,DEGREE_CURSORS,TILE_COUNTS->spatial?0:owners;
                    case TERM_MATERIAL,TERM_MASS->terms;
                    case CONTACT_A,CONTACT_B,CONTACT_G,CONTACT_BATH_T,BATH_INDEX->contacts;
                    case FLUX->Math.max(contacts,owners);
                    case KNOTS->tables.knots().length;case MATERIAL_CP->tables.heatCapacity().length;
                    case MATERIAL_MIN_CP->tables.materials();
                    case INCIDENT->spatial?0:Math.multiplyExact(contacts,2);
                    case ENERGY_CHANGE->Math.multiplyExact(owners,2);case RESERVOIR_CHANGE->baths;
                    case MODE->2;
                    case BATH_EDGES->baths;case DISPATCH->18;case TILE_OWNERS->tiles;case TILE_PARTIALS->2*tiles;case SCAN->spatial?0:Math.toIntExact(VulkanPrefixScan.workspaceBytes(owners)/4);
                    default->owners;
                };
                boolean scratch=switch(field){case KNOTS,MATERIAL_CP,MATERIAL_MIN_CP,ENERGY_CHANGE,RESERVOIR_CHANGE->false;default->true;};
                var column=pool!=null && scratch?pool.allocate(n,field.width,VulkanScratchPool.THERMAL,cursor[0],alignment)
                    :allocate(cursor,n,field.width,alignment);
                columns.put(field,column);cursor[0]=Math.max(cursor[0],column.offset()+column.bindingBytes());
            }
            endOffset=align(cursor[0],alignment);Math.toIntExact(endOffset/4);
        }
        VulkanLiquidStateLayout.Column column(Field field){return columns.get(field);}
        int word(Field field){return Math.toIntExact(column(field).offset()/4);}
        int headerWordOffset(){return Math.toIntExact(header.offset()/4);}
        long endOffset(){return endOffset;}
        ByteBuffer headerBytes(){
            var bytes=ByteBuffer.allocateDirect(320).order(ByteOrder.LITTLE_ENDIAN);
            bytes.putInt(4 * Header.OWNER_CAPACITY.word,owners).putInt(4 * Header.TERM_CAPACITY.word,terms).putInt(4 * Header.CONTACT_CAPACITY.word,contacts).putInt(4 * Header.MATERIAL_COUNT.word,tables.materials()).putInt(4 * Header.SEGMENT_COUNT.word,tables.segments());
            bytes.putInt(4 * Header.ENABLED.word,1).putInt(4 * Header.BATH_CAPACITY.word,baths);
            for(Field f:Field.values())bytes.putInt(4*(WORK_FIELDS+f.ordinal()),word(f));
            return bytes;
        }
        void initializeHeaderAndTables(ByteBuffer destination){
            destination.duplicate().position(Math.toIntExact(header.offset())).put(headerBytes());
            for(var chunk:tableChunks())destination.duplicate().position(Math.toIntExact(chunk.offset())).put(chunk.bytes().duplicate());
        }
        List<VulkanGasThermodynamics.TableChunk> tableChunks(){
            var result=new ArrayList<VulkanGasThermodynamics.TableChunk>();
            append(result,column(Field.KNOTS),tables.knots());append(result,column(Field.MATERIAL_CP),tables.heatCapacity());
            append(result,column(Field.MATERIAL_MIN_CP),tables.minimumCapacity());return List.copyOf(result);
        }

    }
    static Layout layout(int ownerCapacity,int termCapacity,int contactCapacity,Tables tables,long scratchBase,long alignment,int bathCapacity){
        return new Layout(ownerCapacity,termCapacity,contactCapacity,tables,scratchBase,alignment,bathCapacity);
    }
    static Layout spatialLayout(int owners,int terms,int contacts,int samples,Tables tables,long base,long alignment,int baths,VulkanScratchPool pool){
        return new Layout(owners,terms,contacts,tables,base,alignment,baths,samples,pool);
    }
    private final FlipKernelBindings[] bindings=new FlipKernelBindings[52];private final VulkanPrefixScan scan;private FlipKernelBindings.BufferRange arena;
    VulkanThermalConduction(VulkanDeviceContext context){
        var source=shader();try{
            for(int stage:new int[]{STAGE_INITIALIZE,STAGE_COUNT_CONTACTS,STAGE_SCATTER_CONTACTS,STAGE_PREPARE_OWNERS,STAGE_NORMALIZE_RATES,STAGE_PREPARE_TILES,STAGE_COUNT_TILES,STAGE_REDUCE_DEGREE_TILES,STAGE_CLEAR_RESERVOIRS,STAGE_CONTACT_DISPATCH,STAGE_REDUCE_FLUX_TILES,STAGE_SPATIAL_GATHER_DISPATCH,STAGE_FINISH_ENERGY,STAGE_INITIAL_FLUX})
                bindings[stage]=new FlipKernelBindings(context,2,source,32,stage);
            scan=new VulkanPrefixScan(context);
        }catch(RuntimeException|Error failure){for(var binding:bindings)if(binding!=null)binding.close();throw failure;}
    }
    void rebindRanges(FlipKernelBindings.BufferRange arena,FlipKernelBindings.BufferRange errors){
        this.arena=arena;for(var binding:bindings)if(binding!=null)binding.rebindRanges(List.of(arena,errors));scan.rebindRanges(arena,arena,arena,errors);
    }
    /** Supplied owner graphs use the same finite-step exchange as spatial contacts. */
    void recordGraphPrepare(VkCommandBuffer command,Layout l,double dt){
        if(!(Double.isFinite(dt)&&dt>0))throw new IllegalArgumentException("Invalid conduction duration");
        stage(command,l,dt,STAGE_INITIALIZE,0,0,l.owners,false);
        stage(command,l,dt,STAGE_CONTACT_DISPATCH,0,0,128,false);stage(command,l,dt,STAGE_COUNT_CONTACTS,0,0,Math.max(1,l.contacts),true);
        stage(command,l,dt,STAGE_CLEAR_RESERVOIRS,0,0,l.baths,false);
        scan.record(command,l.owners,l.word(DEGREE_COUNTS),l.word(DEGREE_OFFSETS),l.word(SCAN));
        stage(command,l,dt,STAGE_SCATTER_CONTACTS,0,0,l.contacts,false);recordOwnerSetup(command,l,dt,null);
        stage(command,l,dt,STAGE_INITIAL_FLUX,0,0,l.contacts,false);
    }
    void recordGraphFinish(VkCommandBuffer command,Layout l,double dt){gatherSpatial(command,l,dt);recordFinish(command,l,dt);}
    void recordSpatialInitialize(VkCommandBuffer command,Layout l,double dt){
        stage(command,l,dt,STAGE_INITIALIZE,0,0,l.owners,false);stage(command,l,dt,STAGE_CLEAR_RESERVOIRS,0,0,l.baths,false);
    }
    void recordSpatialOwnerCapacities(VkCommandBuffer command,Layout l,double dt){
        stage(command,l,dt,STAGE_PREPARE_OWNERS,0,0,l.owners,false);
    }
    void recordNormalizeRates(VkCommandBuffer command,Layout l,double dt){
        stage(command,l,dt,STAGE_NORMALIZE_RATES,0,0,l.owners,false);
    }
    private void recordOwnerSetup(VkCommandBuffer command,Layout l,double dt,VulkanStageTimings timings){
        timed(timings,command,"thermal-owner-tiles",()->{
            stage(command,l,dt,STAGE_COUNT_TILES,0,0,l.owners,false);
            scan.record(command,l.owners,l.word(Field.TILE_COUNTS),l.word(Field.TILE_OFFSETS),l.word(Field.SCAN));
            stage(command,l,dt,STAGE_PREPARE_TILES,0,0,Math.max(1,l.tiles),false);
        });
        timed(timings,command,"thermal-owner-degrees",()->{
            stage(command,l,dt,STAGE_REDUCE_DEGREE_TILES,0,0,l.tiles,true);
            stage(command,l,dt,STAGE_PREPARE_OWNERS,0,0,l.owners,false);
        });
    }
    void recordFinish(VkCommandBuffer command,Layout l,double dt){
        stage(command,l,dt,STAGE_FINISH_ENERGY,0,0,l.owners,false);
    }
    private void gatherSpatial(VkCommandBuffer command,Layout l,double dt){
        stage(command,l,dt,STAGE_SPATIAL_GATHER_DISPATCH,0,0,128,false);stage(command,l,dt,STAGE_REDUCE_FLUX_TILES,0,0,l.tiles,true);
    }
    private static void timed(VulkanStageTimings timings,VkCommandBuffer command,String label,Runnable commands){
        if(timings==null)commands.run();else timings.record(command,label,commands);
    }
    private void stage(VkCommandBuffer command,Layout l,double dt,int stage,int step,double fraction,int count,boolean indirect){
        if(count==0)return;int groups=(count+127)/128;
        try(var stack=stackPush()){
            var binding=bindings[stage];binding.bind(command,stack);
            var push=stack.malloc(32).putInt(l.headerWordOffset()).putInt(0).putInt(count).putInt(step).putDouble(dt).putDouble(fraction).flip();
            vkCmdPushConstants(command,binding.pipelineLayout(),VK_SHADER_STAGE_COMPUTE_BIT,0,push);
            if(indirect){
                VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,
                        VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT,VK_ACCESS_INDIRECT_COMMAND_READ_BIT,stack);
                vkCmdDispatchIndirect(command,arena.buffer().handle(),arena.offset()+l.column(Field.DISPATCH).offset()+(stage==STAGE_REDUCE_FLUX_TILES?60:stage==STAGE_COUNT_CONTACTS?48:36));
            }else vkCmdDispatch(command,Math.min(groups,65535),(groups+65534)/65535,1);
            VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);
        }
    }
    private static void append(List<VulkanGasThermodynamics.TableChunk> result,VulkanLiquidStateLayout.Column column,double[] values){
        for(int first=0;first<values.length;first+=8192){int count=Math.min(8192,values.length-first);
            var out=ByteBuffer.allocateDirect(8*count).order(ByteOrder.LITTLE_ENDIAN);for(int i=0;i<count;i++)out.putDouble(8*i,values[first+i]);
            result.add(new VulkanGasThermodynamics.TableChunk(column.offset()+8L*first,out));}
    }
    private static long align(long n,long a){return Math.addExact(n,a-1)&-a;}
    private static VulkanLiquidStateLayout.Column allocate(long[] cursor,int count,int width,long alignment){
        var c=new VulkanLiquidStateLayout.Column(align(cursor[0],alignment),count,width);cursor[0]=Math.addExact(c.offset(),c.bindingBytes());return c;
    }
    private static String shader(){
        try(var input=VulkanThermalConduction.class.getResourceAsStream("/assets/mmm_gpu_probe/shaders/thermal-conduction.comp")){
            if(input==null)throw new IllegalStateException("Thermal conduction shader missing");return new String(input.readAllBytes(),StandardCharsets.UTF_8).replace("// HOST_LAYOUT", shaderLayout());
        }catch(IOException failure){throw new IllegalStateException("Thermal conduction shader unavailable",failure);}
    }
    @Override public void close(){try{scan.close();}finally{for(var binding:bindings)if(binding!=null)binding.close();}}
}

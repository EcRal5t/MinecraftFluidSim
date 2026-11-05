package mmm.gpu;

import mmm.thermal.ThermalMaterialDefinition;
import mmm.thermal.ThermalTransport;
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
    private static final int STAGE_CHOOSE_MODE = 4;
    private static final int STAGE_PREPARE_TILES = 20;
    private static final int STAGE_COUNT_TILES = 21;
    private static final int STAGE_REDUCE_DEGREE_TILES = 22;
    private static final int STAGE_CLEAR_RESERVOIRS = 30;
    private static final int STAGE_CONTACT_DISPATCH = 31;
    private static final int STAGE_REDUCE_FLUX_TILES = 33;
    private static final int STAGE_SPATIAL_GATHER_DISPATCH = 36;
    private static final int STAGE_CLASSIFY_FAST = 40;
    private static final int STAGE_SOLVE_ISOLATED = 41;
    private static final int STAGE_LINEARIZE = 42;
    private static final int STAGE_FINISH_RESIDUAL = 43;
    private static final int STAGE_TRIAL_TEMPERATURE = 44;
    private static final int STAGE_TRIAL_RESIDUAL = 45;
    private static final int STAGE_ACCEPT_TRIAL = 46;
    private static final int STAGE_FAST_PRODUCT = 47;
    private static final int STAGE_FINISH_ENERGY = 48;
    private static final int STAGE_CLEAR_IDLE_RESERVOIRS = 49;
    private static final int STAGE_CLASSIFY_FAST_CONTACTS = 50;
    private static final int STAGE_INITIAL_FLUX = 51;
    private static final int STAGE_FINAL_FAST_FLUX = 52;
    private static final int STAGE_FAST_DISPATCH = 53;
    private static String stageDefines() {
        return "#define STAGE_INITIALIZE " + STAGE_INITIALIZE + "\n" +
                "#define STAGE_COUNT_CONTACTS " + STAGE_COUNT_CONTACTS + "\n" +
                "#define STAGE_SCATTER_CONTACTS " + STAGE_SCATTER_CONTACTS + "\n" +
                "#define STAGE_PREPARE_OWNERS " + STAGE_PREPARE_OWNERS + "\n" +
                "#define STAGE_CHOOSE_MODE " + STAGE_CHOOSE_MODE + "\n" +
                "#define STAGE_PREPARE_TILES " + STAGE_PREPARE_TILES + "\n" +
                "#define STAGE_COUNT_TILES " + STAGE_COUNT_TILES + "\n" +
                "#define STAGE_REDUCE_DEGREE_TILES " + STAGE_REDUCE_DEGREE_TILES + "\n" +
                "#define STAGE_CLEAR_RESERVOIRS " + STAGE_CLEAR_RESERVOIRS + "\n" +
                "#define STAGE_CONTACT_DISPATCH " + STAGE_CONTACT_DISPATCH + "\n" +
                "#define STAGE_REDUCE_FLUX_TILES " + STAGE_REDUCE_FLUX_TILES + "\n" +
                "#define STAGE_SPATIAL_GATHER_DISPATCH " + STAGE_SPATIAL_GATHER_DISPATCH + "\n" +
                "#define STAGE_CLASSIFY_FAST " + STAGE_CLASSIFY_FAST + "\n" +
                "#define STAGE_SOLVE_ISOLATED " + STAGE_SOLVE_ISOLATED + "\n" +
                "#define STAGE_LINEARIZE " + STAGE_LINEARIZE + "\n" +
                "#define STAGE_FINISH_RESIDUAL " + STAGE_FINISH_RESIDUAL + "\n" +
                "#define STAGE_TRIAL_TEMPERATURE " + STAGE_TRIAL_TEMPERATURE + "\n" +
                "#define STAGE_TRIAL_RESIDUAL " + STAGE_TRIAL_RESIDUAL + "\n" +
                "#define STAGE_ACCEPT_TRIAL " + STAGE_ACCEPT_TRIAL + "\n" +
                "#define STAGE_FAST_PRODUCT " + STAGE_FAST_PRODUCT + "\n" +
                "#define STAGE_FINISH_ENERGY " + STAGE_FINISH_ENERGY + "\n" +
                "#define STAGE_CLEAR_IDLE_RESERVOIRS " + STAGE_CLEAR_IDLE_RESERVOIRS + "\n" +
                "#define STAGE_CLASSIFY_FAST_CONTACTS " + STAGE_CLASSIFY_FAST_CONTACTS + "\n" +
                "#define STAGE_INITIAL_FLUX " + STAGE_INITIAL_FLUX + "\n" +
                "#define STAGE_FINAL_FAST_FLUX " + STAGE_FINAL_FAST_FLUX + "\n" +
                "#define STAGE_FAST_DISPATCH " + STAGE_FAST_DISPATCH + "\n";
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
        RESOLUTION(72),
        TOLERANCE(74),
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
        return headerDefines() + stageDefines() + "#define ETHANOL_CP " + mmm.chemistry.BuiltinChemicalDefinitions.ETHANOL_HEAT_CAPACITY_J_KG_K + "LF\n" + "#define WORK_FIELDS " + WORK_FIELDS + "\n" + VulkanShaderLayout.fields("FIELD_", Field.values());
    }

    static final int COMPLETE=10;
    static final double TEMPERATURE_TOLERANCE=1e-4;
    enum Field {
        COUNTS(4),OWNER_ENERGY(8),OWNER_INITIAL_T(8),TERM_OFFSETS(4),TERM_MATERIAL(4),TERM_MASS(8),
        CONTACT_A(4),CONTACT_B(4),CONTACT_G(8),CONTACT_BATH_T(8),
        KNOTS(8),MATERIAL_CP(8),MATERIAL_MIN_CP(8),MIN_CP(8),ROOT_CP(8),BEFORE_ENERGY(8),TEMPERATURE(8),TRIAL_T(8),
        DEGREE_COUNTS(4),DEGREE_OFFSETS(4),DEGREE_CURSORS(4),INCIDENT(4),DEGREE(8),FLUX(8),
        ENERGY_CHANGE(8),RESERVOIR_CHANGE(8),MODE(4),ACCEPTED(4),SUMMARY(8),PARTIALS(8),BATH_PARTIALS(8),DISPATCH(4),SCAN(4),
        STARTS(4),COLUMNS(4),VALUES(8),ROWS(4),PRESSURE(8),WORK(8),PROBLEM(8),CONTROL(8),PCG_PARTIALS(8),
        TILE_COUNTS(4),TILE_OFFSETS(4),TILE_OWNERS(4),TILE_PARTIALS(8),BATH_INDEX(4),BATH_EDGES(4),/* Counts: fast owners, spatial fast samples / graph baths, affected samples, graph SS edges. */ IMEX_COUNTS(4),IMEX_OWNERS(4),IMEX_INDEX(4),IMEX_SOURCE(8),IMEX_COUPLED(4),TERM_ETHANOL_MASS(8);
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
            if(owners<1||terms<1||contacts<0||baths<0||tables.materials()<1||base<0||alignment<8||(alignment&(alignment-1))!=0)
                throw new IllegalArgumentException("Invalid thermal conduction layout");
            this.owners=owners;this.terms=terms;this.contacts=contacts;this.tables=tables;this.baths=baths;
            // Each long row has d>128 incidents, so ceil(d/128)<=d/64; all rows have at most 2*contacts incidents.
            tiles=Math.max(Math.toIntExact((contacts+31L)/32),Math.toIntExact((spatialSamples+63L)/64));
            long[] cursor={base};header=allocate(cursor,80,4,alignment);
            for(Field field:Field.values()){
                int n=switch(field){
                    case COUNTS->3;
                    case TERM_OFFSETS,DEGREE_OFFSETS,STARTS,TILE_OFFSETS->Math.addExact(owners,1);
                    case TERM_MATERIAL,TERM_MASS,TERM_ETHANOL_MASS->terms;
                    case CONTACT_A,CONTACT_B,CONTACT_G,CONTACT_BATH_T,FLUX,BATH_INDEX->contacts;
                    case KNOTS->tables.knots().length;case MATERIAL_CP->tables.heatCapacity().length;
                    case MATERIAL_MIN_CP->tables.materials();
                    case INCIDENT->Math.max(Math.multiplyExact(contacts,2),spatialSamples);
                    case ENERGY_CHANGE->Math.multiplyExact(owners,2);case RESERVOIR_CHANGE->baths;
                    case MODE->2;case ACCEPTED->1;case SUMMARY->6;case IMEX_COUNTS->4;
                    case PARTIALS->Math.multiplyExact((owners+127)/128,4);case BATH_PARTIALS->Math.multiplyExact(Math.max(1,(Math.max(contacts,baths)+127)/128),4);
                    case BATH_EDGES->baths;case DISPATCH->18;case TILE_OWNERS->tiles;case TILE_PARTIALS->2*tiles;case SCAN->Math.toIntExact(VulkanPrefixScan.workspaceBytes(owners)/4);
                    case WORK->Math.multiplyExact(owners,5);
                    case PROBLEM->Math.addExact(Math.multiplyExact(owners,2),1);case CONTROL->16;
                    case PCG_PARTIALS->Math.multiplyExact((owners+255)/256,4);
                    default->owners;
                };columns.put(field,allocate(cursor,n,field.width,alignment));
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
            bytes.putDouble(4 * Header.RESOLUTION.word,ThermalTransport.CONDUCTION_RESOLUTION_K).putDouble(4 * Header.TOLERANCE.word,TEMPERATURE_TOLERANCE);
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
        List<FlipKernelBindings.BufferRange> pressureRanges(FlipKernelBindings.BufferRange arena,FlipKernelBindings.BufferRange errors){
            return List.of(range(arena,Field.STARTS),range(arena,Field.COLUMNS),range(arena,Field.VALUES),range(arena,Field.ROWS),
                    range(arena,Field.PRESSURE),range(arena,Field.WORK),
                    range(arena,Field.PROBLEM),range(arena,Field.CONTROL),range(arena,Field.PCG_PARTIALS),errors);
        }
        private FlipKernelBindings.BufferRange range(FlipKernelBindings.BufferRange arena,Field f){
            var c=column(f);return new FlipKernelBindings.BufferRange(arena.buffer(),arena.offset()+c.offset(),c.bindingBytes());
        }
    }
    static Layout layout(int ownerCapacity,int termCapacity,int contactCapacity,Tables tables,long scratchBase,long alignment,int bathCapacity){
        return new Layout(ownerCapacity,termCapacity,contactCapacity,tables,scratchBase,alignment,bathCapacity);
    }
    static Layout spatialLayout(int owners,int terms,int contacts,int samples,Tables tables,long base,long alignment,int baths){
        return new Layout(owners,terms,contacts,tables,base,alignment,baths,samples);
    }
    private final FlipKernelBindings bindings;private final VulkanPrefixScan scan;private FlipKernelBindings.BufferRange arena;
    VulkanThermalConduction(VulkanDeviceContext context){
        bindings=new FlipKernelBindings(context,2,shader(),32);
        try{scan=new VulkanPrefixScan(context);}catch(RuntimeException|LinkageError failure){bindings.close();throw failure;}
    }
    void rebindRanges(FlipKernelBindings.BufferRange arena,FlipKernelBindings.BufferRange errors){
        this.arena=arena;bindings.rebindRanges(List.of(arena,errors));scan.rebindRanges(arena,arena,arena,errors);
    }
    /** Supplied owner graphs use the same IMEX partition and compact nonlinear solver as spatial sampling. */
    void recordGraphPrepare(VkCommandBuffer command,Layout l,double dt){
        if(!(Double.isFinite(dt)&&dt>0))throw new IllegalArgumentException("Invalid conduction duration");
        stage(command,l,dt,STAGE_INITIALIZE,0,0,l.owners,false);
        stage(command,l,dt,STAGE_CONTACT_DISPATCH,0,0,128,false);stage(command,l,dt,STAGE_COUNT_CONTACTS,0,0,Math.max(1,l.contacts),true);
        stage(command,l,dt,STAGE_CLEAR_RESERVOIRS,0,0,l.baths,false);
        scan.record(command,l.owners,l.word(DEGREE_COUNTS),l.word(DEGREE_OFFSETS),l.word(SCAN));
        stage(command,l,dt,STAGE_SCATTER_CONTACTS,0,0,l.contacts,false);recordOwnerSetup(command,l,dt,null);
        stage(command,l,dt,STAGE_INITIAL_FLUX,0,0,l.contacts,false);recordImexClassify(command,l,dt);
        stage(command,l,dt,STAGE_CLASSIFY_FAST_CONTACTS,0,0,Math.max(1,l.contacts),false);
        stage(command,l,dt,STAGE_FAST_DISPATCH,0,0,1,false);stage(command,l,dt,STAGE_SOLVE_ISOLATED,0,0,l.owners,true);
    }
    int solveGraphImex(VulkanRegionStorage.Candidate candidate,Layout l,VulkanPressureKernel pressure,double dt,int fastOwners,int fastContacts){
        return fastContacts==0?0:solveImex(candidate,l,pressure,dt,fastOwners,new ImexOperator(){
            public void flux(VkCommandBuffer command,boolean trial){}public void product(VkCommandBuffer command,boolean direction){}
        },null);
    }
    void recordGraphFinish(VkCommandBuffer command,Layout l,double dt){stage(command,l,dt,STAGE_FINAL_FAST_FLUX,0,0,l.contacts,false);recordImexFinish(command,l,dt);}
    void recordSpatialInitialize(VkCommandBuffer command,Layout l,double dt){
        stage(command,l,dt,STAGE_INITIALIZE,0,0,l.owners,false);stage(command,l,dt,STAGE_CLEAR_RESERVOIRS,0,0,l.baths,false);
    }
    void recordImexPrepare(VkCommandBuffer command,Layout l,double dt,VulkanStageTimings timings){recordOwnerSetup(command,l,dt,timings);stage(command,l,dt,STAGE_CLEAR_IDLE_RESERVOIRS,0,0,l.baths,false);}
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
        timed(timings,command,"thermal-mode",()->{
            stage(command,l,dt,STAGE_CHOOSE_MODE,0,0,128,false);
        });
    }
    /** Single-step IMEX: slow owners retain their captured temperature in every contact. */
    void recordImexClassify(VkCommandBuffer command,Layout l,double dt){
        gatherSpatial(command,l,dt);
        stage(command,l,dt,STAGE_CLASSIFY_FAST,0,0,l.owners,false);
    }
    void recordImexIsolated(VkCommandBuffer command,Layout l,double dt,int count){stage(command,l,dt,STAGE_SOLVE_ISOLATED,0,0,count,false);}
    void recordImexFinish(VkCommandBuffer command,Layout l,double dt){
        gatherSpatial(command,l,dt);stage(command,l,dt,STAGE_FINISH_ENERGY,0,0,l.owners,false);
    }
    int solveImex(VulkanRegionStorage.Candidate candidate,Layout l,VulkanPressureKernel pressure,double dt,int count,
                   ImexOperator flux,VulkanStageTimings timings){
        var operator=new VulkanPressureKernel.PressureOperator(){
            public int liquidCount(){return count;}public int matrixFreeFromRow(){return 0;}
            public void recordProduct(VkCommandBuffer command,boolean direction){flux.product(command,direction);stage(command,l,dt,STAGE_FAST_PRODUCT,direction?1:0,0,count,false);}
        };
        for(int iteration=0;iteration<8;iteration++){
            var result=candidate.continueBatch((command,in,out,errors,staging,stack)->{
                timed(timings,command,"thermal-imex-linearize",()->{flux.flux(command,false);stage(command,l,dt,STAGE_LINEARIZE,0,0,count,false);stage(command,l,dt,STAGE_FINISH_RESIDUAL,0,0,128,false);});
            },l.column(MODE).offset(),4);require(result,"IMEX linearize");if(result.feedback().getInt(0)==COMPLETE)return iteration;
            result=candidate.continueBatch((command,in,out,errors,staging,stack)->{
                pressure.recordInitialize(command,count,count,200,null,null,operator);
                pressure.recordIterations(command,count,count,200,null,null,8,operator);
            },l.column(CONTROL).offset()+24,8);require(result,"IMEX PCG");int recorded=8;
            while(result.feedback().getDouble(0)!=0){
                if(recorded>=200)throw new IllegalStateException("IMEX conduction PCG exhausted its work budget");
                result=candidate.continueBatch((command,in,out,errors,staging,stack)->pressure.recordIterations(command,count,count,200,null,null,8,operator),l.column(CONTROL).offset()+24,8);require(result,"IMEX PCG");recorded+=8;
            }
            result=candidate.continueBatch((command,in,out,errors,staging,stack)->pressure.recordFinish(command,count,count,200,null,null,operator),0,0);require(result,"IMEX PCG finish");
            boolean accepted=false;
            for(int backtrack=0;backtrack<8;backtrack++){
                double fraction=Math.scalb(1.0,-backtrack);
                result=candidate.continueBatch((command,in,out,errors,staging,stack)->{
                    stage(command,l,dt,STAGE_TRIAL_TEMPERATURE,0,fraction,count,false);flux.flux(command,true);
                    stage(command,l,dt,STAGE_TRIAL_RESIDUAL,0,0,count,false);stage(command,l,dt,STAGE_FINISH_RESIDUAL,1,0,128,false);stage(command,l,dt,STAGE_ACCEPT_TRIAL,0,0,count,false);
                },l.column(ACCEPTED).offset(),4);require(result,"IMEX caloric trial");
                if(result.feedback().getInt(0)!=0){accepted=true;break;}
            }
            if(!accepted)throw new IllegalStateException("IMEX caloric trial did not reduce residual");
        }
        var result=candidate.continueBatch((command,in,out,errors,staging,stack)->{flux.flux(command,false);stage(command,l,dt,STAGE_LINEARIZE,0,0,count,false);stage(command,l,dt,STAGE_FINISH_RESIDUAL,0,0,128,false);},l.column(MODE).offset(),4);
        require(result,"IMEX final residual");if(result.feedback().getInt(0)!=COMPLETE)throw new IllegalStateException("IMEX caloric work budget exhausted");return 8;
    }
    interface ImexOperator {void flux(VkCommandBuffer command,boolean trial);void product(VkCommandBuffer command,boolean direction);}
    private void gatherSpatial(VkCommandBuffer command,Layout l,double dt){
        stage(command,l,dt,STAGE_SPATIAL_GATHER_DISPATCH,0,0,128,false);stage(command,l,dt,STAGE_REDUCE_FLUX_TILES,0,0,l.tiles,true);
    }
    private static void timed(VulkanStageTimings timings,VkCommandBuffer command,String label,Runnable commands){
        if(timings==null)commands.run();else timings.record(command,label,commands);
    }
    private static void require(VulkanRegionStorage.Candidate.BatchResult result,String stage){
        if(result.error()!=0)throw new IllegalStateException("Conduction "+stage+" error="+result.error());
    }
    private void stage(VkCommandBuffer command,Layout l,double dt,int stage,int step,double fraction,int count,boolean indirect){
        if(count==0)return;int groups=(count+127)/128;
        try(var stack=stackPush()){
            bindings.bind(command,stack);
            var push=stack.malloc(32).putInt(l.headerWordOffset()).putInt(stage).putInt(count).putInt(step).putDouble(dt).putDouble(fraction).flip();
            vkCmdPushConstants(command,bindings.pipelineLayout(),VK_SHADER_STAGE_COMPUTE_BIT,0,push);
            if(indirect){
                VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,
                        VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT,VK_ACCESS_INDIRECT_COMMAND_READ_BIT,stack);
                vkCmdDispatchIndirect(command,arena.buffer().handle(),arena.offset()+l.column(Field.DISPATCH).offset()+(stage==STAGE_REDUCE_FLUX_TILES?60:stage==STAGE_SOLVE_ISOLATED?12:stage==STAGE_COUNT_CONTACTS?48:36));
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
    @Override public void close(){try{scan.close();}finally{bindings.close();}}
}

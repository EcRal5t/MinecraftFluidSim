package mmm.gpu;

import org.lwjgl.vulkan.VkCommandBuffer;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.List;
import static mmm.gpu.VulkanRegionStorage.barrier;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Synchronous finite-aperture network. Parallel openings are combined before the shared PCG solve.
 * Flow preferences use SI standard cubic metres. Frozen caloric storage/conductance give a first-order
 * semi-implicit response; integer inventory settlement and actual retained-gas cooling follow the solve.
 */
final class VulkanGasFlow implements AutoCloseable {
    enum Header {
        EOS_HEADER(0),
        GEOMETRY_HEADER(1),
        NODE_CAPACITY(2),
        PAIR_CAPACITY(3),
        HASH_CAPACITY(4),
        FACE_COUNT(5),
        ROUTE_CAPACITY(6),
        SIZE_X(7),
        SIZE_Y(8),
        SIZE_Z(9),
        ACTIVITY_CELLS(10);
        final int word;
        Header(int word) { this.word = word; }
    }
    static final int WORK_FIELDS = 16;
    private static String shaderLayout() {
        var result = new StringBuilder();
        for (var field : Header.values()) result.append("#define H_").append(field.name()).append(' ').append(field.word).append('\n');
        result.append("#define WORK_FIELDS ").append(WORK_FIELDS).append('\n');
        return VulkanGasGeometry.shaderLayout() + VulkanGasThermodynamics.shaderLayout() + result + VulkanShaderLayout.fields("FIELD_", Field.values());
    }

    enum Field {
        FACE_A(4),FACE_B(4),FACE_PAIR(4),HASH(4),HASH_FLAGS(4),HASH_OFFSETS(4),
        PAIR_A(4),PAIR_B(4),PAIR_COUNTS(4),PAIR_OFFSETS(4),PAIR_CURSORS(4),PAIR_FACES(4),PAIR_G(8),PAIR_FLOW(8),
        NODE_COUNTS(4),NODE_OFFSETS(4),NODE_CURSORS(4),NODE_PAIRS(4),STORAGE(8),DIAGONAL_ROOT(8),OUT_SCALE(8),
        START_COUNTS(4),STARTS(4),COLUMNS(4),VALUES(8),ROWS(4),PRESSURE(8),WORK(8),META(4),PROBLEM(8),CONTROL(8),PARTIALS(8),
        ROUTE_COUNT(4),ROUTE_SOURCE(4),ROUTE_TARGET(4),ROUTE_WEIGHT(8),AMBIENT_VOLUME(8),
        SOURCE_COUNTS(4),SOURCE_OFFSETS(4),SOURCE_CURSORS(4),SOURCE_ROUTES(4),TARGET_COUNTS(4),TARGET_OFFSETS(4),TARGET_CURSORS(4),TARGET_ROUTES(4),
        RETAINED_VOLUME(8),MODES(4),STEP(8),DISPATCH(4),SCAN(4),LONG_ROWS(4),LONG_STARTS(4),TILE_OWNER(4),TILE_SUMS(8),LONG_COUNTS(4),LONG_PAIRS(4);
        final int width;Field(int width){this.width=width;}
    }
    /** Ambient density is the actual kg/m³ at ambientPressure and ambientTemperature; step is a committed-step ordinal, reused on retries. */
    record Parameters(double duration,double dischargeCoefficient,double ambientPressure,double ambientTemperature,double ambientDensity,long step){
        Parameters{if(!(Double.isFinite(duration)&&duration>0&&Double.isFinite(dischargeCoefficient)&&dischargeCoefficient>=0
                &&Double.isFinite(ambientPressure)&&ambientPressure>0&&Double.isFinite(ambientTemperature)&&ambientTemperature>0
                &&Double.isFinite(ambientDensity)&&ambientDensity>0))throw new IllegalArgumentException("Invalid gas-flow parameters");}
    }
    static Parameters parameters(double duration,double dischargeCoefficient,VulkanGasRemap.AmbientTables ambient,long step){
        double standardDensity=0.0;
        for(int c=0;c<ambient.handles().length;c++)standardDensity+=(double)ambient.weights()[c]/ambient.totalWeight()
                *((double)ambient.massNumerators()[c]/ambient.massDenominators()[c])*.1;
        return new Parameters(duration,dischargeCoefficient,mmm.fluid.air.GasRegistry.STANDARD_PRESSURE_PA,ambient.temperature(),
                standardDensity*mmm.fluid.air.GasRegistry.STANDARD_TEMPERATURE_K/ambient.temperature(),step);
    }
    static final class Layout {
        final VulkanGasThermodynamics.Layout eos;final VulkanGasGeometry.Layout geometry;final int pairs,hashes,faces,nodes,routes,activityCellWord;
        final VulkanLiquidStateLayout.Column header;final EnumMap<Field,VulkanLiquidStateLayout.Column> columns=new EnumMap<>(Field.class);final long endOffset;
        Layout(VulkanGasThermodynamics.Layout eos,VulkanGasGeometry.Layout geometry,int pairCapacity,long base,long alignment,int activityCellWord){
            this.eos=eos;this.geometry=geometry;pairs=pairCapacity;faces=geometry.faces();nodes=eos.gas().nodeCapacity();routes=Math.addExact(nodes,pairs);
            this.activityCellWord=activityCellWord;
            if(pairCapacity<1||nodes<1||base<Math.max(eos.endOffset(),geometry.endOffset())||alignment<8||(alignment&(alignment-1))!=0)throw new IllegalArgumentException("Invalid gas-flow layout");
            int hash=1;while(hash<Math.multiplyExact(faces,2))hash=Math.multiplyExact(hash,2);hashes=hash;
            long[] cursor={base};header=allocate(cursor,160,4,alignment);
            for(Field field:Field.values()){
                int n=switch(field){
                    case FACE_A,FACE_B,FACE_PAIR,PAIR_FACES->faces;
                    case HASH,HASH_FLAGS->hashes;case HASH_OFFSETS->hashes+1;
                    case PAIR_A,PAIR_B,PAIR_COUNTS,PAIR_CURSORS,PAIR_G,PAIR_FLOW->pairs;case PAIR_OFFSETS->pairs+1;
                    case NODE_COUNTS,NODE_CURSORS,STORAGE,DIAGONAL_ROOT,OUT_SCALE,START_COUNTS,ROWS,PRESSURE,RETAINED_VOLUME,MODES->nodes;
                    case NODE_OFFSETS,STARTS->nodes+1;case NODE_PAIRS->Math.multiplyExact(pairs,2);
                    case COLUMNS,VALUES->Math.addExact(nodes,Math.multiplyExact(pairs,2));
                    case WORK->Math.multiplyExact(nodes,7);case META->Math.multiplyExact(nodes,2);case PROBLEM->Math.addExact(Math.multiplyExact(nodes,3),1);
                    case CONTROL->48;case PARTIALS->Math.multiplyExact((nodes+255)/256,4);
                    case ROUTE_COUNT,STEP->1;case ROUTE_SOURCE,ROUTE_TARGET,ROUTE_WEIGHT,AMBIENT_VOLUME,SOURCE_ROUTES,TARGET_ROUTES->routes;
                    case SOURCE_COUNTS,SOURCE_CURSORS->nodes+2;case SOURCE_OFFSETS->nodes+3;
                    case TARGET_COUNTS,TARGET_CURSORS->nodes+1;case TARGET_OFFSETS->nodes+2;case DISPATCH->24;
                    case LONG_ROWS,LONG_STARTS->nodes;
                    case TILE_OWNER,TILE_SUMS->Math.toIntExact(2L*(nodes+2L*pairs)/129);
                    case LONG_COUNTS->3;case LONG_PAIRS->Math.min(pairs,faces/129);
                    case SCAN->Math.toIntExact(VulkanPrefixScan.workspaceBytes(Math.max(hashes,Math.max(routes,nodes+2)))/4);
                };columns.put(field,allocate(cursor,n,field.width,alignment));
            }endOffset=align(cursor[0],alignment);Math.toIntExact(endOffset/4);
        }
        VulkanLiquidStateLayout.Column column(Field field){return columns.get(field);}int word(Field field){return Math.toIntExact(column(field).offset()/4);}
        int headerWordOffset(){return Math.toIntExact(header.offset()/4);}long endOffset(){return endOffset;}
        long requiredPairsOffset(){return column(Field.HASH_OFFSETS).offset()+4L*hashes;}
        VulkanGasRoutes routes(){return new VulkanGasRoutes(eos.gas(),geometry,routes,word(Field.ROUTE_COUNT),word(Field.ROUTE_SOURCE),word(Field.ROUTE_TARGET),word(Field.ROUTE_WEIGHT),word(Field.SOURCE_OFFSETS),word(Field.SOURCE_ROUTES),word(Field.TARGET_OFFSETS),word(Field.TARGET_ROUTES),word(Field.AMBIENT_VOLUME),endOffset);}
        ByteBuffer headerBytes(){
            var b=ByteBuffer.allocateDirect(640).order(ByteOrder.LITTLE_ENDIAN);int[] data=new int[160];
            var liquid=geometry.totals().source();data[Header.EOS_HEADER.word]=eos.headerWordOffset();data[Header.GEOMETRY_HEADER.word]=geometry.headerWordOffset();data[Header.NODE_CAPACITY.word]=nodes;data[Header.PAIR_CAPACITY.word]=pairs;data[Header.HASH_CAPACITY.word]=hashes;data[Header.FACE_COUNT.word]=faces;data[Header.ROUTE_CAPACITY.word]=routes;
            data[Header.SIZE_X.word]=liquid.sizeX();data[Header.SIZE_Y.word]=liquid.sizeY();data[Header.SIZE_Z.word]=liquid.sizeZ();
            data[Header.ACTIVITY_CELLS.word]=activityCellWord;
            for(Field f:Field.values())data[WORK_FIELDS+f.ordinal()]=word(f);for(int i=0;i<data.length;i++)b.putInt(4*i,data[i]);return b;
        }
        void initializeHeader(ByteBuffer target){target.duplicate().position(Math.toIntExact(header.offset())).put(headerBytes());}
        List<FlipKernelBindings.BufferRange> pressureRanges(FlipKernelBindings.BufferRange arena,FlipKernelBindings.BufferRange errors){
            return List.of(range(arena,Field.STARTS),range(arena,Field.COLUMNS),range(arena,Field.VALUES),range(arena,Field.ROWS),range(arena,Field.PRESSURE),range(arena,Field.PRESSURE),range(arena,Field.WORK),range(arena,Field.META),range(arena,Field.PROBLEM),range(arena,Field.CONTROL),range(arena,Field.PARTIALS),errors);
        }
        private FlipKernelBindings.BufferRange range(FlipKernelBindings.BufferRange arena,Field f){var c=column(f);return new FlipKernelBindings.BufferRange(arena.buffer(),arena.offset()+c.offset(),c.bindingBytes());}
    }
    static Layout layout(VulkanGasThermodynamics.Layout eos,VulkanGasGeometry.Layout geometry,int pairCapacity,long scratchBase,long alignment){return new Layout(eos,geometry,pairCapacity,scratchBase,alignment,-1);}
    static Layout layout(VulkanGasThermodynamics.Layout eos,VulkanGasGeometry.Layout geometry,int pairCapacity,long scratchBase,long alignment,int activityCellWord){return new Layout(eos,geometry,pairCapacity,scratchBase,alignment,activityCellWord);}
    private final FlipKernelBindings bindings;private final VulkanPrefixScan scan;private FlipKernelBindings.BufferRange arena;
    VulkanGasFlow(VulkanDeviceContext context){bindings=new FlipKernelBindings(context,2,shader(),64);try{scan=new VulkanPrefixScan(context);}catch(RuntimeException|LinkageError failure){bindings.close();throw failure;}}
    void rebindRanges(FlipKernelBindings.BufferRange arena,FlipKernelBindings.BufferRange errors){this.arena=arena;bindings.rebindRanges(List.of(arena,errors));scan.rebindRanges(arena,arena,arena,errors);}
    void recordAssemble(VkCommandBuffer command,Layout l,Parameters parameters){
        stage(command,l,parameters,0,Math.max(l.hashes,Math.max(l.pairs,l.nodes+2)));
        stage(command,l,parameters,1,l.faces);stage(command,l,parameters,2,l.faces);
        prefix(command,l,l.hashes,Field.HASH_FLAGS,Field.HASH_OFFSETS);stage(command,l,parameters,3,l.hashes);stage(command,l,parameters,4,l.faces);
        prefix(command,l,l.pairs,Field.PAIR_COUNTS,Field.PAIR_OFFSETS);prefix(command,l,l.nodes,Field.NODE_COUNTS,Field.NODE_OFFSETS);
        prefix(command,l,l.nodes,Field.START_COUNTS,Field.STARTS);stage(command,l,parameters,5,l.faces);
        stage(command,l,parameters,18,l.nodes);stage(command,l,parameters,22,l.pairs);stage(command,l,parameters,19,1);
        stage(command,l,parameters,6,l.pairs);stage(command,l,parameters,23,l.pairs);
        stage(command,l,parameters,7,l.pairs);stage(command,l,parameters,8,l.nodes);stage(command,l,parameters,24,l.nodes);
        stage(command,l,parameters,9,l.nodes);stage(command,l,parameters,25,l.nodes);
    }
    /** Call after the shared PCG has finished successfully; pressure uses the columns from pressureRanges. */
    void recordRoutes(VkCommandBuffer command,Layout l,Parameters parameters){
        stage(command,l,parameters,10,l.pairs);stage(command,l,parameters,11,l.nodes);stage(command,l,parameters,26,l.nodes);stage(command,l,parameters,12,l.routes);
        prefix(command,l,l.nodes+2,Field.SOURCE_COUNTS,Field.SOURCE_OFFSETS);prefix(command,l,l.nodes+1,Field.TARGET_COUNTS,Field.TARGET_OFFSETS);
        stage(command,l,parameters,13,l.routes);
    }
    /** Shares the standard PCG with actual long-row tiles and a second row reduction. */
    VulkanPressureKernel.PressureOperator pressureOperator(Layout l,Parameters q){
        return new VulkanPressureKernel.PressureOperator(){
            public int liquidCount(){return l.nodes;}
            public int matrixFreeFromRow(){return 0;}
            public void recordProduct(VkCommandBuffer command,boolean direction){
                stage(command,l,q,direction?17:16,l.nodes);
                stage(command,l,q,direction?15:14,1);
                stage(command,l,q,direction?21:20,1);
            }
            public void recordProductIndirect(VkCommandBuffer command,boolean direction,VulkanPressureKernel.Dispatch dispatch){
                int first=direction?4:7;
                stage(command,l,q,direction?17:16,l.nodes,dispatch,first);
                stage(command,l,q,direction?15:14,1,dispatch,first+1);
                stage(command,l,q,direction?21:20,1,dispatch,first+2);
            }
        };
    }
    private void prefix(VkCommandBuffer command,Layout l,int n,Field input,Field output){scan.record(command,n,l.word(input),l.word(output),l.word(Field.SCAN));}
    private void stage(VkCommandBuffer cmd,Layout l,Parameters q,int stage,int count){
        stage(cmd,l,q,stage,count,null,0);
    }
    private void stage(VkCommandBuffer cmd,Layout l,Parameters q,int stage,int count,VulkanPressureKernel.Dispatch conditional,int slot){
        if(count==0)return;int groups=(count+127)/128;try(var stack=stackPush()){
            bindings.bind(cmd,stack);var push=stack.malloc(64).putInt(l.headerWordOffset()).putInt(stage).putInt(count).putInt(0)
                    .putDouble(q.duration()).putDouble(q.dischargeCoefficient()).putDouble(q.ambientPressure()).putDouble(q.ambientTemperature()).putDouble(q.ambientDensity()).putLong(q.step()).flip();
            vkCmdPushConstants(cmd,bindings.pipelineLayout(),VK_SHADER_STAGE_COMPUTE_BIT,0,push);
            int indirect=switch(stage){case 6,7,10,22->1;case 8,9,11->3;case 23->7;case 24,25,26->6;case 12,13->4;case 14,15->5;case 20,21->6;default->-1;};
            if(conditional!=null)conditional.record(cmd,slot);
            else if(indirect>=0){barrier(cmd,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT,VK_ACCESS_INDIRECT_COMMAND_READ_BIT,stack);
                vkCmdDispatchIndirect(cmd,arena.buffer().handle(),arena.offset()+l.column(Field.DISPATCH).offset()+12L*indirect);
            }else vkCmdDispatch(cmd,Math.min(groups,65535),(groups+65534)/65535,1);
            barrier(cmd,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);
        }
    }
    private static long align(long n,long a){return Math.addExact(n,a-1)&-a;}
    private static VulkanLiquidStateLayout.Column allocate(long[] cursor,int count,int width,long a){var c=new VulkanLiquidStateLayout.Column(align(cursor[0],a),count,width);cursor[0]=Math.addExact(c.offset(),c.bindingBytes());return c;}
    private static String shader(){try(var input=VulkanGasFlow.class.getResourceAsStream("/assets/mmm_gpu_probe/shaders/gas-flow.comp")){if(input==null)throw new IllegalStateException("Gas flow shader missing");return new String(input.readAllBytes(),StandardCharsets.UTF_8).replace("// HOST_LAYOUT", shaderLayout());}catch(IOException failure){throw new IllegalStateException("Gas flow shader unavailable",failure);}}
    @Override public void close(){try{scan.close();}finally{bindings.close();}}
}

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

/** Finite gas rows of the common pressure operator, with parallel face-incidence products. */
final class VulkanGasPressure implements AutoCloseable {
    /** Header word positions used by host encoding and the shader. */
    enum Header {
        LIQUID_COUNT(0),
        GAS_CAPACITY(1),
        FACE_COUNT(2),
        PRESSURE_SIZE(3),
        CELLS(4),
        FACES(5),
        CELL_NODES(6),
        NODE_VOLUMES(7),
        THERMODYNAMIC_STATES(8),
        GAS_COUNTS(9),
        COMPONENT_OFFSETS(10),
        SIZE_X(11),
        SIZE_Y(12),
        SIZE_Z(13),
        LARGE_COUNT(14),
        SOLVE_COUNT(15),
        STARTS(16),
        COLUMNS(17),
        VALUES(18),
        ROWS(19),
        PRESSURE(20),
        WORK(21),
        META(22),
        PROBLEM(23),
        CONTROL(24),
        SHORT_COUNT(25),
        SCHUR_MODE(27),
        ORIGINAL_SOLVE_COUNT(28),
        REDUCED_COUNT(29),
        HASH_CAPACITY(30),
        PROPOSAL_CAPACITY(31),
        ACTIVITY_CELLS(32),
        ACTIVITY_FACES(33),
        AMBIENT_PRESSURE(56),
        CELL_SIZE(58),
        DURATION(60),
        GRAVITY(62);
        final int word;
        Header(int word) { this.word = word; }
    }
    static final int WORK_FIELDS = 64;
    private static String shaderLayout() {
        var result = new StringBuilder();
        for (var field : Header.values())
            result.append("#define H_").append(field.name()).append(' ').append(field.word).append('\n');
        result.append("#define WORK_FIELDS ").append(WORK_FIELDS).append('\n');
        return result + VulkanShaderLayout.fields("FIELD_", Field.values());
    }

    record PressureBuffers(VulkanLiquidStateLayout.Column starts, VulkanLiquidStateLayout.Column columns,
                           VulkanLiquidStateLayout.Column values, VulkanLiquidStateLayout.Column rows,
                           VulkanLiquidStateLayout.Column pressure, VulkanLiquidStateLayout.Column work,
                           VulkanLiquidStateLayout.Column meta, VulkanLiquidStateLayout.Column problem,
                           VulkanLiquidStateLayout.Column control) { }
    enum Field {
        SOURCE_FACE_K(8), PRE_PRESSURE_FACE_K(8), POST_PRESSURE_FACE_K(8), REFERENCE(8), STORAGE(8),
        DIAGONAL(8), MECHANICAL_MODE(4), CELL_PHYSICAL_PRESSURE(8), NODE_ABSOLUTE_PRESSURE(8),
        NODE_PREDICTED_VOLUME_CHANGE(8), FACE_ROWS(4), FACE_COUNTS(4), FACE_OFFSETS(4), FACE_CURSORS(4),
        NODE_FACES(4), BLOCK_COUNTS(4), BLOCK_OFFSETS(4), BLOCK_NODES(4), PRODUCT_PARTS(8), DATUM(8), SCAN(4), DISPATCH(4), LARGE_NODES(4), PRODUCT_PEERS(4), PRODUCT_WEIGHTS(8), SOLVE_ROWS(4), SHORT_NODES(4), SCHUR_CANDIDATE(4), SCHUR_ELIMINATED(4),
        SCHUR_PEER_A(4), SCHUR_PEER_B(4), SCHUR_WEIGHT_A(8), SCHUR_WEIGHT_B(8), SOURCE_RHS(8), SCHUR_DIAGONAL(8),
        PROPOSAL_A(4),PROPOSAL_B(4),PROPOSAL_WEIGHT(8),PROPOSAL_SLOT(4),PAIR_HASH(4),HASH_GROUP(4),PAIR_LIVE(4),
        GROUP_COUNTS(4),GROUP_OFFSETS(4),GROUP_CURSORS(4),GROUP_PROPOSALS(4),GROUP_A(4),GROUP_B(4),GROUP_WEIGHT(8),
        CORE_COUNTS(4),CORE_OFFSETS(4),CORE_CURSORS(4),CORE_PEERS(4),CORE_WEIGHTS(8);
        final int width; Field(int width) { this.width = width; }
    }
    static final class Layout {
        private final VulkanGasThermodynamics.Layout thermodynamics;
        private final VulkanGasGeometry.Layout geometry;
        private final PressureBuffers pressure;
        private final VulkanLiquidStateLayout.Column cells, faces, header;
        private final EnumMap<Field,VulkanLiquidStateLayout.Column> columns = new EnumMap<>(Field.class);
        private final int liquidCount, gasCapacity, faceCount, pressureSize, blockCapacity,proposalCapacity,hashCapacity;
        private final int activityCellsWord, activityFacesWord;
        private final long endOffset;

        Layout(VulkanGasThermodynamics.Layout thermodynamics, VulkanGasGeometry.Layout geometry,
               VulkanLiquidStateLayout.Column cells, VulkanLiquidStateLayout.Column faces,
               PressureBuffers pressure, long scratchBase, long alignment) {
            this(thermodynamics,geometry,cells,faces,pressure,0,scratchBase,alignment,-1,-1);
        }
        Layout(VulkanGasThermodynamics.Layout thermodynamics, VulkanGasGeometry.Layout geometry,
               VulkanLiquidStateLayout.Column cells, VulkanLiquidStateLayout.Column faces,
               PressureBuffers pressure, int additionalRows, long scratchBase, long alignment) {
            this(thermodynamics, geometry, cells, faces, pressure, additionalRows, scratchBase, alignment, -1, -1);
        }
        Layout(VulkanGasThermodynamics.Layout thermodynamics, VulkanGasGeometry.Layout geometry,
               VulkanLiquidStateLayout.Column cells, VulkanLiquidStateLayout.Column faces,
               PressureBuffers pressure, int additionalRows, long scratchBase, long alignment,
               int activityCellsWord, int activityFacesWord) {
            this.activityCellsWord = activityCellsWord;
            this.activityFacesWord = activityFacesWord;
            if(additionalRows<0)throw new IllegalArgumentException("Negative additional pressure rows");
            this.thermodynamics=thermodynamics;this.geometry=geometry;this.cells=cells;this.faces=faces;this.pressure=pressure;
            liquidCount=geometry.cells();gasCapacity=thermodynamics.gas().nodeCapacity();faceCount=geometry.faces();
            pressureSize=Math.addExact(Math.addExact(liquidCount,gasCapacity),additionalRows);
            proposalCapacity=additionalRows==0?Math.addExact(faceCount,gasCapacity):0;
            int hashes=1;while(hashes<2L*proposalCapacity)hashes=Math.multiplyExact(hashes,2);hashCapacity=proposalCapacity==0?0:hashes;
            blockCapacity=Math.addExact(Math.toIntExact((2L*(proposalCapacity==0?faceCount:proposalCapacity)+127)/128),gasCapacity);
            if(alignment<8||(alignment&(alignment-1))!=0||scratchBase<Math.max(thermodynamics.endOffset(),geometry.endOffset())
                    ||cells.count()<4L*liquidCount||faces.count()<4L*faceCount
                    ||pressure.starts().count()<pressureSize+1||pressure.columns().count()<7L*liquidCount+gasCapacity+additionalRows
                    ||pressure.values().count()<7L*liquidCount+gasCapacity+additionalRows||pressure.rows().count()<pressureSize
                    ||pressure.pressure().count()<pressureSize||pressure.work().count()<7L*pressureSize
                    ||pressure.meta().count()<2L*pressureSize||pressure.problem().count()<3L*pressureSize+1)
                throw new IllegalArgumentException("Finite gas pressure layout differs from its resident buffers");
            long cursor=align(scratchBase,alignment);header=new VulkanLiquidStateLayout.Column(cursor,128,4);cursor+=header.bytes();
            for(Field field:Field.values()) {
                int count=switch(field) {
                    case SOURCE_FACE_K,PRE_PRESSURE_FACE_K,POST_PRESSURE_FACE_K->faceCount;
                    case REFERENCE->pressureSize;
                    case CELL_PHYSICAL_PRESSURE->liquidCount;
                    case FACE_ROWS,NODE_FACES,PRODUCT_PEERS,PRODUCT_WEIGHTS->Math.multiplyExact(2,faceCount);
                    case FACE_OFFSETS,BLOCK_OFFSETS->gasCapacity+1;
                    case BLOCK_NODES,PRODUCT_PARTS->blockCapacity;
                    case DATUM->1;
                    case DISPATCH->42;
                    case PROPOSAL_A,PROPOSAL_B,PROPOSAL_WEIGHT,PROPOSAL_SLOT,GROUP_COUNTS,GROUP_CURSORS,GROUP_PROPOSALS,GROUP_A,GROUP_B,GROUP_WEIGHT->proposalCapacity;
                    case PAIR_HASH,HASH_GROUP->hashCapacity;case PAIR_LIVE->2;
                    case GROUP_OFFSETS->proposalCapacity==0?0:proposalCapacity+1;
                    case CORE_COUNTS,CORE_CURSORS->proposalCapacity==0?0:gasCapacity;
                    case CORE_OFFSETS->proposalCapacity==0?0:gasCapacity+1;
                    case CORE_PEERS,CORE_WEIGHTS->Math.multiplyExact(2,proposalCapacity);
                    case SOLVE_ROWS->pressureSize;
                    case SCAN->Math.toIntExact(VulkanPrefixScan.workspaceBytes(Math.max(gasCapacity,proposalCapacity))/4);
                    default->gasCapacity;
                };
                var column=new VulkanLiquidStateLayout.Column(align(cursor,alignment),count,field.width);
                columns.put(field,column);cursor=Math.addExact(column.offset(),column.bindingBytes());
            }
            endOffset=align(cursor,alignment);Math.toIntExact(endOffset/4);
        }
        VulkanLiquidStateLayout.Column column(Field field){return columns.get(field);}
        int word(Field field){return Math.toIntExact(column(field).offset()/4);}
        int headerWordOffset(){return Math.toIntExact(header.offset()/4);}
        VulkanLiquidStateLayout.Column header(){return header;}
        PressureBuffers pressureBuffers(){return pressure;}
        VulkanGasThermodynamics.Layout thermodynamics(){return thermodynamics;}
        VulkanGasGeometry.Layout geometry(){return geometry;}
        VulkanLiquidStateLayout.Column cells(){return cells;}
        VulkanLiquidStateLayout.Column faces(){return faces;}
        int liquidCount(){return liquidCount;}
        int gasCapacity(){return gasCapacity;}
        int pressureSize(){return pressureSize;}
        long endOffset(){return endOffset;}
        long bytes(){return endOffset-header.offset();}
        ByteBuffer headerBytes() {
            var out=ByteBuffer.allocateDirect(512).order(ByteOrder.LITTLE_ENDIAN);var gas=thermodynamics.gas();
            int[] data=new int[128];
            data[Header.PROPOSAL_CAPACITY.word]=proposalCapacity;data[Header.HASH_CAPACITY.word]=hashCapacity;
            data[Header.ACTIVITY_CELLS.word]=activityCellsWord;data[Header.ACTIVITY_FACES.word]=activityFacesWord;
            data[Header.LIQUID_COUNT.word]=liquidCount;data[Header.GAS_CAPACITY.word]=gasCapacity;data[Header.FACE_COUNT.word]=faceCount;data[Header.PRESSURE_SIZE.word]=pressureSize;
            data[Header.CELLS.word]=VulkanGasPressure.word(cells);data[Header.FACES.word]=VulkanGasPressure.word(faces);data[Header.CELL_NODES.word]=VulkanGasPressure.word(geometry.cellNodes());
            data[Header.NODE_VOLUMES.word]=thermodynamics.nodeVolumesWordOffset();data[Header.THERMODYNAMIC_STATES.word]=VulkanGasPressure.word(thermodynamics.states());
            data[Header.GAS_COUNTS.word]=Math.toIntExact(gas.column(VulkanGasStateLayout.Field.COUNTS).offset()/4);
            data[Header.COMPONENT_OFFSETS.word]=Math.toIntExact(gas.column(VulkanGasStateLayout.Field.ROW_COMPONENT_OFFSETS).offset()/4);
            var liquid=geometry.totals().source();data[Header.SIZE_X.word]=liquid.sizeX();data[Header.SIZE_Y.word]=liquid.sizeY();data[Header.SIZE_Z.word]=liquid.sizeZ();
            var buffers=List.of(pressure.starts(),pressure.columns(),pressure.values(),pressure.rows(),pressure.pressure(),
                    pressure.work(),pressure.meta(),pressure.problem(),pressure.control());
            for(int i=0;i<buffers.size();i++)data[16+i]=VulkanGasPressure.word(buffers.get(i));
            for(Field field:Field.values())data[WORK_FIELDS+field.ordinal()]=word(field);
            for(int i=0;i<data.length;i++)out.putInt(4*i,data[i]);
            out.putDouble(4 * Header.AMBIENT_PRESSURE.word,101325.0);return out;
        }
        void initializeHeader(ByteBuffer arena){arena.duplicate().position(Math.toIntExact(header.offset())).put(headerBytes());}
    }
    private final FlipKernelBindings bindings;
    private final VulkanPrefixScan scan;
    private FlipKernelBindings.BufferRange arena;
    VulkanGasPressure(VulkanDeviceContext context) {
        bindings=new FlipKernelBindings(context,2,shader(),32);
        try{scan=new VulkanPrefixScan(context);}catch(RuntimeException|LinkageError failure){bindings.close();throw failure;}
    }
    void rebindRanges(FlipKernelBindings.BufferRange arena,FlipKernelBindings.BufferRange errors){
        this.arena=arena;bindings.rebindRanges(List.of(arena,errors));scan.rebindRanges(arena,arena,arena,errors);
    }
    void recordCaptureFaces(VkCommandBuffer command,Layout layout,VulkanLiquidProjection.Parameters parameters){stage(command,layout,parameters,0,layout.faceCount,0);}
    void recordPrepare(VkCommandBuffer command,Layout layout,VulkanLiquidProjection.Parameters parameters){recordPrepare(command,layout,parameters,null);}
    void recordPrepare(VkCommandBuffer command,Layout layout,VulkanLiquidProjection.Parameters parameters,Runnable afterRows){
        stage(command,layout,parameters,1,layout.pressureSize,0);
        stage(command,layout,parameters,2,128,0);
        stage(command,layout,parameters,3,layout.faceCount,0);
        scan.record(command,layout.gasCapacity,layout.word(Field.FACE_COUNTS),layout.word(Field.FACE_OFFSETS),layout.word(Field.SCAN));
        stage(command,layout,parameters,4,layout.faceCount,0);
        stage(command,layout,parameters,5,Math.multiplyExact(128,layout.gasCapacity),0);
        stage(command,layout,parameters,6,layout.liquidCount,0);
        if(afterRows!=null)afterRows.run();
        if(layout.pressureSize==layout.liquidCount+layout.gasCapacity){
            stage(command,layout,null,17,1,0);
            stage(command,layout,null,18,layout.gasCapacity,0);
            stage(command,layout,null,19,layout.gasCapacity,0);
            stage(command,layout,null,20,Math.multiplyExact(128,layout.gasCapacity),0);
            stage(command,layout,null,21,layout.liquidCount,0);
            recordReducedAdjacency(command,layout);
        }
        stage(command,layout,parameters,7,layout.gasCapacity,0);
        scan.record(command,layout.gasCapacity,layout.word(Field.BLOCK_COUNTS),layout.word(Field.BLOCK_OFFSETS),layout.word(Field.SCAN));
        stage(command,layout,parameters,8,layout.gasCapacity,0);
        stage(command,layout,parameters,16,1,0);
    }
    /** Coalesces each physical core peer pair once; the original face graph remains available for final certification. */
    private void recordReducedAdjacency(VkCommandBuffer command,Layout layout){
        stage(command,layout,null,25,Math.max(layout.hashCapacity,Math.max(layout.proposalCapacity,layout.gasCapacity)),0);
        stage(command,layout,null,26,layout.faceCount,0);stage(command,layout,null,27,layout.gasCapacity,0);
        stage(command,layout,null,28,1,0);
        stage(command,layout,null,29,layout.proposalCapacity,0);stage(command,layout,null,30,layout.proposalCapacity,0);
        scan.record(command,layout.proposalCapacity,layout.word(Field.GROUP_COUNTS),layout.word(Field.GROUP_OFFSETS),layout.word(Field.SCAN));
        stage(command,layout,null,31,1,0);stage(command,layout,null,32,layout.proposalCapacity,0);
        stage(command,layout,null,33,Math.multiplyExact(128,layout.proposalCapacity),0);
        scan.record(command,layout.gasCapacity,layout.word(Field.CORE_COUNTS),layout.word(Field.CORE_OFFSETS),layout.word(Field.SCAN));
        stage(command,layout,null,34,layout.proposalCapacity,0);
    }
    /** Restores the original KKT operator after exact independent-gas back substitution. */
    void recordRecoverOriginal(VkCommandBuffer command,Layout layout){
        if(layout.pressureSize!=layout.liquidCount+layout.gasCapacity)return;
        stage(command,layout,null,22,layout.gasCapacity,0);
        stage(command,layout,null,24,1,0);
        stage(command,layout,null,23,layout.pressureSize,0);
        stage(command,layout,null,7,layout.gasCapacity,0);
        scan.record(command,layout.gasCapacity,layout.word(Field.BLOCK_COUNTS),layout.word(Field.BLOCK_OFFSETS),layout.word(Field.SCAN));
        stage(command,layout,null,8,layout.gasCapacity,0);
        stage(command,layout,null,16,1,0);
    }
    VulkanPressureKernel.ParallelRows parallelRows(Layout layout){
        var rows=layout.column(Field.SOLVE_ROWS);
        return new VulkanPressureKernel.ParallelRows(new FlipKernelBindings.BufferRange(arena.buffer(),arena.offset()+rows.offset(),rows.bindingBytes()),
                arena.buffer(),arena.offset()+layout.column(Field.DISPATCH).offset()+36);
    }
    FlipKernelBindings.BufferRange productDispatch(Layout layout){var c=layout.column(Field.DISPATCH);return new FlipKernelBindings.BufferRange(arena.buffer(),arena.offset()+c.offset(),c.bindingBytes());}
    /** Writes gas matrix-vector products into the common pressure Q column. */
    void recordProduct(VkCommandBuffer command,Layout layout,boolean direction){
        stage(command,layout,null,9,Math.multiplyExact(128,layout.blockCapacity),direction?1:0);
        stage(command,layout,null,10,Math.multiplyExact(128,layout.gasCapacity),direction?1:0);
        stage(command,layout,null,14,layout.gasCapacity,direction?1:0);
    }
    void recordProject(VkCommandBuffer command,Layout layout,VulkanLiquidProjection.Parameters parameters){
        stage(command,layout,parameters,11,layout.faceCount,0);
        recordObserve(command,layout,parameters);
    }
    /** Recomputes gas diagnostics after a joint liquid/particle projection has published face velocities. */
    void recordObserve(VkCommandBuffer command,Layout layout,VulkanLiquidProjection.Parameters parameters){
        stage(command,layout,parameters,15,layout.faceCount,0);
        stage(command,layout,parameters,12,Math.multiplyExact(128,layout.gasCapacity),0);
        stage(command,layout,parameters,13,layout.liquidCount,0);
    }
    private void stage(VkCommandBuffer command,Layout layout,VulkanLiquidProjection.Parameters parameters,int stage,int count,int argument){
        if(count==0)return;
        int groups=(count+127)/128;
        try(var stack=stackPush()){
            bindings.bind(command,stack);
            var push=stack.malloc(32).putInt(layout.headerWordOffset()).putInt(stage).putInt(count).putInt(argument)
                    .putFloat(parameters==null?1:parameters.cellSize()).putFloat(parameters==null?1:parameters.duration())
                    .putFloat(parameters==null?0:parameters.gravity()).putFloat(parameters==null?0:parameters.tolerance()).flip();
            vkCmdPushConstants(command,bindings.pipelineLayout(),VK_SHADER_STAGE_COMPUTE_BIT,0,push);
            if(stage==5||stage==9||stage==10||stage==12||stage==14||stage==20||stage==29||stage==30||stage==32||stage==33||stage==34) {
                VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,
                        VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT,VK_ACCESS_INDIRECT_COMMAND_READ_BIT,stack);
                vkCmdDispatchIndirect(command,arena.buffer().handle(),arena.offset()+layout.column(Field.DISPATCH).offset()+12L*(stage==9?(argument==1?4:7):stage==10?(argument==1?5:8):stage==14?(argument==1?6:9):stage==29||stage==30||stage==32?11:stage==33?12:stage==34?13:0));
            } else vkCmdDispatch(command,Math.min(groups,65535),(groups+65534)/65535,1);
            VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);
        }
    }
    private static int word(VulkanLiquidStateLayout.Column column){return Math.toIntExact(column.offset()/4);}
    private static long align(long value,long alignment){return Math.addExact(value,alignment-1)&-alignment;}
    private static String shader(){
        try(var input=VulkanGasPressure.class.getResourceAsStream("/assets/mmm_gpu_probe/shaders/gas-pressure.comp")){
            if(input==null)throw new IllegalStateException("Gas pressure shader missing");return new String(input.readAllBytes(),StandardCharsets.UTF_8).replace("// HOST_LAYOUT", shaderLayout());
        }catch(IOException failure){throw new IllegalStateException("Gas pressure shader unavailable",failure);}
    }
    @Override public void close(){try{scan.close();}finally{bindings.close();}}
}

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

/** Common liquid/gas/particle pressure and implicit drag: J M^-1 J^T + S.
 * Incidence coefficients have inverse-length units; velocity inertia is h^3/m.
 * A drag row is (particle - weighted liquid)/h with S=h/(dt*k).
 * Each (row, velocity) pair occurs once. Unused term slots have row=-1.
 * Geometry writes the inputs on device; no iteration assembles a host matrix. */
final class VulkanCoupledPressure implements AutoCloseable {
    enum Field {
        TERM_ROW(4), TERM_VELOCITY(4), TERM_COEFFICIENT(8), INVERSE_MASS(8), PREDICTED_VELOCITY(8),
        FIXED_REFERENCE_FORCE(8), REFERENCE(8), STORAGE(8), ROW_SOURCE(8), ROW_ENABLED(4),
        COUNTS(4), OFFSETS(4), CURSORS(4), ENTRIES(4), BLOCK_COUNTS(4), BLOCK_OFFSETS(4), BLOCK_OWNER(4),
        LARGE_IDS(4), PARTIALS(8), RESPONSE(8), PROJECTED_VELOCITY(8), SCAN(4), DISPATCH(4), ROW_DIVERGENCE(8);
        final int width; Field(int width){this.width=width;}
    }
    static final class Layout {
        private final int liquidCount, rows, velocities, terms, owners, blocks;
        private final VulkanGasPressure.PressureBuffers pressure;
        private final VulkanLiquidStateLayout.Column header;
        private final EnumMap<Field,VulkanLiquidStateLayout.Column> columns=new EnumMap<>(Field.class);
        private final long endOffset;
        Layout(int liquidCount,int rows,int velocities,int terms,VulkanGasPressure.PressureBuffers pressure,
               long scratchBase,long alignment){
            if(liquidCount<0||rows<liquidCount||velocities<0||terms<0||scratchBase<0||alignment<8||(alignment&(alignment-1))!=0)
                throw new IllegalArgumentException("Invalid coupled pressure dimensions");
            if(pressure.starts().count()<rows+1||pressure.rows().count()<rows||pressure.pressure().count()<rows
                    ||pressure.work().count()<7L*rows||pressure.meta().count()<2L*rows||pressure.problem().count()<3L*rows+1)
                throw new IllegalArgumentException("Coupled pressure buffers have a different row stride");
            this.liquidCount=liquidCount;this.rows=rows;this.velocities=velocities;this.terms=terms;this.pressure=pressure;
            owners=Math.addExact(rows,velocities);blocks=Math.addExact(owners,Math.multiplyExact(2,terms)/128);
            header=new VulkanLiquidStateLayout.Column(align(scratchBase,alignment),64,4);long cursor=header.offset()+header.bytes();
            for(Field field:Field.values()){
                int count=switch(field){
                    case TERM_ROW,TERM_VELOCITY,TERM_COEFFICIENT->terms;
                    case INVERSE_MASS,PREDICTED_VELOCITY,FIXED_REFERENCE_FORCE,RESPONSE,PROJECTED_VELOCITY->velocities;
                    case REFERENCE,STORAGE,ROW_SOURCE,ROW_ENABLED,ROW_DIVERGENCE->rows;
                    case OFFSETS,BLOCK_OFFSETS->owners+1;
                    case ENTRIES->Math.multiplyExact(2,terms);
                    case BLOCK_OWNER->blocks;
                    case PARTIALS->Math.multiplyExact(2,blocks);
                    case DISPATCH->6;
                    case SCAN->Math.toIntExact(VulkanPrefixScan.workspaceBytes(owners)/4);
                    default->owners;
                };
                var column=new VulkanLiquidStateLayout.Column(align(cursor,alignment),count,field.width);
                columns.put(field,column);cursor=column.offset()+column.bindingBytes();
            }
            endOffset=align(cursor,alignment);Math.toIntExact(endOffset/4);
        }
        VulkanLiquidStateLayout.Column column(Field field){return columns.get(field);}
        int word(Field field){return Math.toIntExact(column(field).offset()/4);}
        int headerWordOffset(){return Math.toIntExact(header.offset()/4);}
        int liquidCount(){return liquidCount;} int pressureSize(){return rows;} int velocities(){return velocities;}
        int termCapacity(){return terms;} long endOffset(){return endOffset;}
        ByteBuffer headerBytes(){
            var result=ByteBuffer.allocateDirect(256).order(ByteOrder.LITTLE_ENDIAN);
            result.putInt(0,rows).putInt(4,velocities).putInt(8,terms).putInt(12,owners).putInt(16,blocks).putInt(20,liquidCount);
            var buffers=List.of(pressure.starts(),pressure.columns(),pressure.values(),pressure.rows(),pressure.pressure(),
                    pressure.work(),pressure.meta(),pressure.problem(),pressure.control());
            for(int i=0;i<buffers.size();i++)result.putInt(4*(8+i),Math.toIntExact(buffers.get(i).offset()/4));
            for(Field field:Field.values())result.putInt(4*(24+field.ordinal()),word(field));
            return result;
        }
        void initializeHeader(ByteBuffer arena){arena.duplicate().position(Math.toIntExact(header.offset())).put(headerBytes());}
    }
    record Operator(VulkanCoupledPressure kernel,Layout layout) implements VulkanPressureKernel.PressureOperator {
        @Override public int liquidCount(){return layout.liquidCount();}
        @Override public int matrixFreeFromRow(){return 0;}
        @Override public void recordProduct(VkCommandBuffer command,boolean direction){kernel.recordProduct(command,layout,direction);}
    }
    private final FlipKernelBindings bindings;
    private final VulkanPrefixScan scan;
    private FlipKernelBindings.BufferRange arena;
    VulkanCoupledPressure(VulkanDeviceContext context){
        bindings=new FlipKernelBindings(context,2,shader(),24);
        try{scan=new VulkanPrefixScan(context);}catch(RuntimeException|LinkageError failure){bindings.close();throw failure;}
    }
    void rebindRanges(FlipKernelBindings.BufferRange arena,FlipKernelBindings.BufferRange errors){
        this.arena=arena;bindings.rebindRanges(List.of(arena,errors));scan.rebindRanges(arena,arena,arena,errors);
    }
    /** Builds both incidence orders and RHS. Input meta/lower/warm-start and liquid CSR off-diagonals stay caller-owned.
     * Every CSR row starts with its diagonal; preparation replaces that diagonal by the full joint value. */
    void recordPrepare(VkCommandBuffer command,Layout layout,double duration){
        if(!(duration>0&&Double.isFinite(duration)))throw new IllegalArgumentException("Invalid coupled pressure duration");
        stage(command,layout,0,layout.owners,0,duration);
        stage(command,layout,1,layout.terms,0,duration);
        scan.record(command,layout.owners,layout.word(Field.COUNTS),layout.word(Field.OFFSETS),layout.word(Field.SCAN));
        stage(command,layout,2,layout.terms,0,duration);
        stage(command,layout,3,layout.owners,0,duration);
        scan.record(command,layout.owners,layout.word(Field.BLOCK_COUNTS),layout.word(Field.BLOCK_OFFSETS),layout.word(Field.SCAN));
        stage(command,layout,4,layout.owners,0,duration);
        multiply(command,layout,5,2,duration);
        multiply(command,layout,8,2,duration);
    }
    void recordProduct(VkCommandBuffer command,Layout layout,boolean direction){
        multiply(command,layout,5,direction?1:0,1);
        multiply(command,layout,8,direction?1:0,1);
    }
    /** Outputs u*=predicted + dt M^-1(J^T(reference+p)+fixed), including all strong-drag reactions. */
    void recordProject(VkCommandBuffer command,Layout layout,double duration){multiply(command,layout,5,3,duration);}
    /** Geometric volume response of the complete projected velocity, including solid pressure incidence. */
    void recordDivergence(VkCommandBuffer command,Layout layout){multiply(command,layout,8,4,1);}
    private void multiply(VkCommandBuffer command,Layout layout,int first,int argument,double duration){
        stage(command,layout,first,layout.owners,argument,duration);
        stage(command,layout,first+1,layout.blocks,argument,duration);
        stage(command,layout,first+2,layout.owners,argument,duration);
    }
    private void stage(VkCommandBuffer command,Layout layout,int stage,int count,int argument,double duration){
        if(count==0)return;
        try(var stack=stackPush()){
            bindings.bind(command,stack);
            var push=stack.malloc(24).putInt(layout.headerWordOffset()).putInt(stage).putInt(count).putInt(argument).putDouble(duration).flip();
            vkCmdPushConstants(command,bindings.pipelineLayout(),VK_SHADER_STAGE_COMPUTE_BIT,0,push);
            if(stage==6||stage==7||stage==9||stage==10){
                VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,
                        VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT,VK_ACCESS_INDIRECT_COMMAND_READ_BIT,stack);
                vkCmdDispatchIndirect(command,arena.buffer().handle(),arena.offset()+layout.column(Field.DISPATCH).offset()+(stage==7||stage==10?12:0));
            }else{int groups=(count+127)/128;vkCmdDispatch(command,Math.min(groups,65535),(groups+65534)/65535,1);}
            VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);
        }
    }
    private static long align(long value,long alignment){return Math.addExact(value,alignment-1)&-alignment;}
    private static String shader(){try(var input=VulkanCoupledPressure.class.getResourceAsStream("/assets/mmm_gpu_probe/shaders/coupled-pressure.comp")){
        if(input==null)throw new IllegalStateException("Coupled pressure shader missing");return new String(input.readAllBytes(),StandardCharsets.UTF_8);
    }catch(IOException failure){throw new IllegalStateException("Coupled pressure shader unavailable",failure);}}
    @Override public void close(){try{scan.close();}finally{bindings.close();}}
}

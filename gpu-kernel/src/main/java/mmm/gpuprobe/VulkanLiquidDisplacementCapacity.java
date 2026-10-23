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

/** Lower-bounded resident transportation for changed solid occupancy. Uses the same K/bounds,
 * seven FLOW/ACCEPTED routes and completion protocol as normal liquid capacity. Receiver/group
 * storage edges form a direct star; no artificial tree vertex transports physical inventory.
 * Negative vertex balances seed a reverse residual BFS. Every accepted proposal moves strictly
 * down that frozen distance field, with a second quota pass at remaining demand vertices.
 * Inputs retain the cell's exact dyadic tail outside K: bounds already subtract that tail.
 * A failed solve rejects the whole trial candidate, including particle displacement, pressure
 * response, liquid routes and gas work. CUT_CELLS is diagnostic, not a position-only rollback plan.
 */
final class VulkanLiquidDisplacementCapacity implements AutoCloseable {
    enum Field { HEADER(4),STATE(4),TAIL_FLOW(8),EXCESS(8),DISTANCE(4),COUNTS(4),OFFSETS(4),CURSORS(4),ENTRIES(4),
        BLOCK_COUNTS(4),BLOCK_OFFSETS(4),BLOCK_OWNER(4),PARTIALS(8),PREFIX(8),PROPOSAL(8),GRANT(8),
        TOTAL_PARTS(8),TOTAL(8),CUT_CELLS(4),SCAN(4);
        final int width;Field(int width){this.width=width;}}
    static final class Layout {
        private final VulkanLiquidCapacityLayout capacity;
        private final int vertices,edges,blocks,totalBlocks;
        private final long endOffset;
        private final EnumMap<Field,VulkanLiquidStateLayout.Column> columns=new EnumMap<>(Field.class);
        Layout(VulkanLiquidCapacityLayout capacity,long scratchBase,long alignment){
            if(scratchBase<capacity.endOffset()||alignment<8||(alignment&(alignment-1))!=0)throw new IllegalArgumentException("Invalid displacement capacity layout");
            this.capacity=capacity;vertices=Math.addExact(Math.multiplyExact(2,capacity.cells()),capacity.groups()+1);
            edges=Math.addExact(Math.multiplyExact(8,capacity.cells()),capacity.groups());
            blocks=Math.toIntExact(4L*edges/129);totalBlocks=(capacity.cells()+127)/128;
            long cursor=scratchBase;
            for(Field field:Field.values()){
                int count=switch(field){case HEADER->64;case STATE->8;case TAIL_FLOW->capacity.cells()+capacity.groups();
                    case OFFSETS,BLOCK_OFFSETS->vertices+1;case ENTRIES->Math.multiplyExact(2,edges);
                    case BLOCK_OWNER,PARTIALS,PREFIX->blocks;case PROPOSAL,GRANT->edges;
                    case TOTAL_PARTS->totalBlocks;case TOTAL->1;case CUT_CELLS->capacity.cells();
                    case SCAN->Math.toIntExact(VulkanPrefixScan.workspaceBytes(vertices)/4);default->vertices;};
                var c=new VulkanLiquidStateLayout.Column(align(cursor,alignment),count,field.width);columns.put(field,c);cursor=c.offset()+c.bindingBytes();
            }endOffset=align(cursor,alignment);
        }
        VulkanLiquidStateLayout.Column column(Field field){return columns.get(field);}
        int word(Field field){return Math.toIntExact(column(field).offset()/4);}
        VulkanLiquidCapacityLayout capacity(){return capacity;}long endOffset(){return endOffset;}
        ByteBuffer headerBytes(){
            var result=ByteBuffer.allocateDirect(256).order(ByteOrder.LITTLE_ENDIAN);
            result.putInt(0,capacity.word(VulkanLiquidCapacityLayout.Field.HEADER)).putInt(4,capacity.cells()).putInt(8,capacity.groups())
                    .putInt(12,vertices).putInt(16,edges).putInt(20,blocks).putInt(24,totalBlocks);
            for(var f:Field.values())result.putInt(4*(16+f.ordinal()),word(f));return result;
        }
        void initializeHeader(ByteBuffer arena){arena.duplicate().position(Math.toIntExact(column(Field.HEADER).offset())).put(headerBytes());}
    }
    private final FlipKernelBindings bindings;private final VulkanPrefixScan scan;
    VulkanLiquidDisplacementCapacity(VulkanDeviceContext context){bindings=new FlipKernelBindings(context,2,shader(),20);
        try{scan=new VulkanPrefixScan(context);}catch(RuntimeException|LinkageError failure){bindings.close();throw failure;}}
    void rebindRanges(FlipKernelBindings.BufferRange arena,FlipKernelBindings.BufferRange errors){
        bindings.rebindRanges(List.of(arena,errors));scan.rebindRanges(arena,arena,arena,errors);}
    void recordInitialize(VkCommandBuffer command,Layout l,int maximumRounds){
        if(maximumRounds<1)throw new IllegalArgumentException("Invalid displacement iteration limit");
        stage(command,l,0,Math.max(l.vertices,l.edges),maximumRounds,0);
        stage(command,l,1,l.capacity.cells(),0,0);stage(command,l,2,l.capacity.cells(),0,0);
        stage(command,l,3,l.edges,0,0);
        scan.record(command,l.vertices,l.word(Field.COUNTS),l.word(Field.OFFSETS),l.word(Field.SCAN));
        stage(command,l,4,l.edges,0,0);stage(command,l,5,l.vertices,0,0);
        scan.record(command,l.vertices,l.word(Field.BLOCK_COUNTS),l.word(Field.BLOCK_OFFSETS),l.word(Field.SCAN));
        stage(command,l,6,l.vertices,0,0);stage(command,l,7,128*l.totalBlocks,0,0);stage(command,l,8,1,0,0);
        aggregate(command,l,2);stage(command,l,9,l.capacity.groups(),0,0);aggregate(command,l,2);
        stage(command,l,10,1,0,0);
    }
    /** A round advances one BFS layer or one synchronous blocking-flow wave. Both count toward the same limit. */
    void recordIterations(VkCommandBuffer command,Layout l,int rounds){
        if(rounds<0)throw new IllegalArgumentException("Negative displacement round count");
        for(int i=0;i<rounds;i++){
            stage(command,l,11,l.vertices,0,0);stage(command,l,12,1,0,0);
            stage(command,l,13,l.edges,0,0);stage(command,l,14,1,0,0);
            stage(command,l,15,l.edges,0,0);aggregate(command,l,0);aggregate(command,l,1);
            stage(command,l,19,l.edges,0,0);aggregate(command,l,2);stage(command,l,20,1,0,0);
        }
    }
    /** Caller reads the usual CONTROL[status,rounds] before publication. INFEASIBLE exposes CUT_CELLS for trial rejection. */
    void recordFinish(VkCommandBuffer command,Layout l){stage(command,l,21,7*l.capacity.cells(),0,0);}
    private void aggregate(VkCommandBuffer command,Layout l,int mode){
        stage(command,l,16,128*l.blocks,0,mode);stage(command,l,17,l.vertices,0,mode);
        if(mode!=2)stage(command,l,18,128*l.blocks,0,mode);
    }
    private void stage(VkCommandBuffer command,Layout l,int stage,int count,int argument,int mode){
        if(count==0)return;try(var stack=stackPush()){
            bindings.bind(command,stack);vkCmdPushConstants(command,bindings.pipelineLayout(),VK_SHADER_STAGE_COMPUTE_BIT,0,
                    stack.ints(l.word(Field.HEADER),stage,count,argument,mode));int groups=(count+127)/128;
            vkCmdDispatch(command,Math.min(groups,65535),(groups+65534)/65535,1);
            VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);
        }
    }
    private static long align(long v,long a){return Math.addExact(v,a-1)&-a;}
    private static String shader(){try(var input=VulkanLiquidDisplacementCapacity.class.getResourceAsStream("/assets/mmm_gpu_probe/shaders/liquid-displacement-capacity.comp")){
        if(input==null)throw new IllegalStateException("Liquid displacement capacity shader missing");return new String(input.readAllBytes(),StandardCharsets.UTF_8);
    }catch(IOException failure){throw new IllegalStateException("Liquid displacement capacity shader unavailable",failure);}}
    @Override public void close(){try{scan.close();}finally{bindings.close();}}
}

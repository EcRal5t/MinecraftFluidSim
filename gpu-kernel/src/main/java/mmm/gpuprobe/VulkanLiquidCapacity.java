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

/** Common lower-bounded transportation for liquid motion and solid displacement.
 * Donor quantities, seven receiver routes, cell bounds and finite groups share one residual graph. */
final class VulkanLiquidCapacity implements AutoCloseable {
    static final int RUNNING=0,FEASIBLE=1,INFEASIBLE=2,ITERATION_LIMIT=3,INVALID=4;
    static final int ERROR_INVALID=4096,ERROR_UNFINISHED=8192;
    enum Field { HEADER(4),STATE(4),TAIL_FLOW(8),EXCESS(8),DISTANCE(4),COUNTS(4),OFFSETS(4),CURSORS(4),ENTRIES(4),
        BLOCK_COUNTS(4),BLOCK_OFFSETS(4),BLOCK_OWNER(4),PARTIALS(8),PREFIX(8),PROPOSAL(8),RECEIVER_REMAINING(8),
        TOTAL_PARTS(8),TOTAL(8),CUT_CELLS(4),SCAN(4),DISPATCH(4),ACTIVE_EDGES(4),TOUCHED(4),ACTIVE_VERTICES(4),RECEIVERS(4),CHANGED_VERTICES(4),LABEL_A(4),LABEL_B(4),LABEL_CHANGED(4),CELL_INCOMING(8),GROUP_INCOMING(8),GROUP_WITHDRAW(8),GROUP_BASE(8);
        final int width;Field(int width){this.width=width;}}
    static final class Layout {
        private final VulkanLiquidCapacityLayout capacity;
        private final int vertices,edges,blocks,totalBlocks;
        private final long endOffset;
        private final EnumMap<Field,VulkanLiquidStateLayout.Column> columns=new EnumMap<>(Field.class);
        Layout(VulkanLiquidCapacityLayout capacity,long scratchBase,long alignment){
            if(scratchBase<capacity.endOffset()||alignment<8||(alignment&(alignment-1))!=0)throw new IllegalArgumentException("Invalid liquid capacity layout");
            this.capacity=capacity;vertices=Math.addExact(Math.multiplyExact(2,capacity.cells()),capacity.groups()+1);
            edges=Math.addExact(Math.multiplyExact(8,capacity.cells()),capacity.groups());
            blocks=Math.toIntExact(4L*edges/129);totalBlocks=(capacity.cells()+127)/128;
            long cursor=scratchBase;
            for(Field field:Field.values()){
                int count=switch(field){case HEADER->64;case DISPATCH->48;case STATE->17;case TAIL_FLOW->capacity.cells()+capacity.groups();
                    case OFFSETS,BLOCK_OFFSETS->vertices+1;case ENTRIES->Math.multiplyExact(2,edges);
                    case BLOCK_OWNER,PARTIALS,PREFIX->blocks;case PROPOSAL,ACTIVE_EDGES->edges;
                    case CELL_INCOMING->capacity.cells();case GROUP_INCOMING,GROUP_WITHDRAW,GROUP_BASE->capacity.groups();case TOTAL_PARTS->totalBlocks;case TOTAL->1;case CUT_CELLS->capacity.cells();
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
    private FlipKernelBindings.BufferRange arena;
    private final FlipKernelBindings bindings;private final VulkanPrefixScan scan;
    VulkanLiquidCapacity(VulkanDeviceContext context){bindings=new FlipKernelBindings(context,3,shader(),20);
        try{scan=new VulkanPrefixScan(context);}catch(RuntimeException|LinkageError failure){bindings.close();throw failure;}}
    void rebindRanges(FlipKernelBindings.BufferRange arena,FlipKernelBindings.BufferRange errors){
        this.arena=arena;bindings.rebindRanges(List.of(arena,errors,arena));scan.rebindRanges(arena,arena,arena,errors);}
    void recordInitialize(VkCommandBuffer command,Layout l,int maximumRounds){
        if(maximumRounds<1)throw new IllegalArgumentException("Invalid capacity iteration limit");
        stage(command,l,0,Math.max(l.vertices,l.edges),maximumRounds,0);
        stage(command,l,1,l.capacity.cells(),0,0);stage(command,l,2,l.capacity.cells(),0,0);
        stage(command,l,24,l.capacity.groups(),0,0);stage(command,l,25,1,0,0);
    }
    private void buildResidualGraph(VkCommandBuffer command,Layout l){
        stage(command,l,30,1,0,0);
        stage(command,l,3,l.edges,0,0);
        scan.record(command,l.vertices,l.word(Field.COUNTS),l.word(Field.OFFSETS),l.word(Field.SCAN));
        stage(command,l,4,l.edges,0,0);stage(command,l,5,l.vertices,0,0);
        scan.record(command,l.vertices,l.word(Field.BLOCK_COUNTS),l.word(Field.BLOCK_OFFSETS),l.word(Field.SCAN));
        stage(command,l,6,l.vertices,0,0);stage(command,l,7,128*l.totalBlocks,0,0);stage(command,l,8,1,0,0);
        aggregateInitial(command,l);stage(command,l,9,l.capacity.groups(),0,0);aggregateInitial(command,l);
        stage(command,l,10,1,0,0);
    }
    /** One wave of the current phase; completed phases leave the remaining indirect dispatches empty. */
    void recordIterations(VkCommandBuffer command,Layout l,int rounds,int phase){
        if(rounds<0)throw new IllegalArgumentException("Negative capacity round count");
        try(var stack=stackPush()){bindings.bind(command,stack);}
        // Local withdrawal is one initialization pass. Remaining constraints use the common residual graph.
        if(phase==3||phase==6)rounds=Math.min(rounds,1);
        for(int i=0;i<rounds;i++){
            stage(command,l,32,1,phase,0);
            switch(phase){
                case 0,1->{stage(command,l,11,l.vertices,0,0);stage(command,l,12,1,0,0);
                    stage(command,l,13,l.edges,0,0);stage(command,l,14,1,0,0);}
                case 2->{stage(command,l,15,1,0,0);aggregate(command,l,0);stage(command,l,23,1,0,0);stage(command,l,33,l.vertices,0,0);
                    stage(command,l,19,l.edges,0,0);stage(command,l,34,l.vertices,0,0);aggregate(command,l,3);}
                case 3->{stage(command,l,26,7*l.capacity.cells(),0,0);stage(command,l,27,l.capacity.cells(),0,0);
                    stage(command,l,28,l.capacity.cells(),0,0);stage(command,l,29,l.capacity.cells(),0,0);}
                case 6->buildResidualGraph(command,l);
                default->throw new IllegalArgumentException("Unknown capacity phase "+phase);
            }
            stage(command,l,20,1,0,0);
        }
    }
    /** Caller reads the usual CONTROL[status,rounds] before publication. INFEASIBLE exposes CUT_CELLS for trial rejection. */
    void recordFinish(VkCommandBuffer command,Layout l){stage(command,l,21,7*l.capacity.cells(),0,0);}
    private void aggregateInitial(VkCommandBuffer command,Layout l){
        stage(command,l,16,128*l.blocks,-1,2);stage(command,l,17,l.vertices,-1,2);
    }
    private void aggregate(VkCommandBuffer command,Layout l,int mode){
        stage(command,l,16,128*l.blocks,0,mode);stage(command,l,17,l.vertices,0,mode);
        if(mode<2||mode==3)stage(command,l,18,128*l.blocks,0,mode);
    }
    private void stage(VkCommandBuffer command,Layout l,int stage,int count,int argument,int mode){
        if(count==0)return;try(var stack=stackPush()){
            if(stage<=10||stage==21)bindings.bind(command,stack);vkCmdPushConstants(command,bindings.pipelineLayout(),VK_SHADER_STAGE_COMPUTE_BIT,0,
                    stack.ints(l.word(Field.HEADER),stage,count,argument,mode));int groups=(count+127)/128;
            if((stage>=11&&stage<=20||stage==23||stage>=26&&stage<=29||stage==33||stage==34)&&argument!=-1){
                int dispatch=switch(stage){case 11->0;case 13->1;case 19->2;case 33->7;case 34->8;case 17->mode<3?6+mode:3;case 16,18->4;case 26->13;case 27,28,29->9;default->5;};
                vkCmdDispatchIndirect(command,arena.buffer().handle(),arena.offset()+l.column(Field.DISPATCH).offset()+12L*dispatch);
            }else vkCmdDispatch(command,Math.min(groups,65535),(groups+65534)/65535,1);
            VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT|VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT|VK_ACCESS_INDIRECT_COMMAND_READ_BIT,stack);
        }
    }
    private static long align(long v,long a){return Math.addExact(v,a-1)&-a;}
    static String dumpFailure(VulkanRegionStorage.Candidate candidate,VulkanLiquidCapacityLayout layout,String path) {
        if(path==null||path.isBlank())return "";
        var data=java.nio.ByteBuffer.allocate(Math.toIntExact(layout.bytes())).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        candidate.read(layout.baseOffset(),data);
        var header=java.nio.ByteBuffer.allocate(32).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        header.putLong(0x4d4d4d4341503031L).putLong(layout.baseOffset()).putLong(layout.bytes()).putLong(layout.column(VulkanLiquidCapacityLayout.Field.HEADER).offset());
        var target=java.nio.file.Path.of(path);
        try(var output=java.nio.file.Files.newOutputStream(target)){output.write(header.array());output.write(data.array());}
        catch(java.io.IOException failure){throw new IllegalStateException("Cannot preserve capacity failure at "+target,failure);}
        return target.toAbsolutePath().toString();
    }
    private static String shader(){try(var input=VulkanLiquidCapacity.class.getResourceAsStream("/assets/mmm_gpu_probe/shaders/liquid-capacity.comp")){
        if(input==null)throw new IllegalStateException("Liquid capacity shader missing");return new String(input.readAllBytes(),StandardCharsets.UTF_8);
    }catch(IOException failure){throw new IllegalStateException("Liquid capacity shader unavailable",failure);}}
    @Override public void close(){try{scan.close();}finally{bindings.close();}}
}

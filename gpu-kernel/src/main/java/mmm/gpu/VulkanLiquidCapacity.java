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

/** Lower-bounded transportation for liquid motion and solid displacement.
 * A short local withdrawal pass prepares the same flow for the residual graph. */
final class VulkanLiquidCapacity implements AutoCloseable {
    /** Word positions shared by the host encoder and shader. */
    private enum Header {
        CAPACITY_HEADER(0),
        CELL_COUNT(1),
        GROUP_COUNT(2),
        VERTEX_COUNT(3),
        EDGE_COUNT(4),
        RESERVED(5),
        TOTAL_BLOCKS(6);
        final int word;
        Header(int word) { this.word = word; }
    }
    private enum Stage { INITIALIZE, SEED_DONOR, UNUSED_2, BALANCE_RECEIVER, SCATTER_MEMBER, MAP_LONG_BLOCKS, BALANCE_SINK, TOTAL_BLOCK, TOTAL, SEED_GROUP, START, SEED_DISTANCE, BEGIN_BFS, BFS_EDGE, FINISH_BFS, BEGIN_DISCHARGE, UNUSED_16, UNUSED_17, UNUSED_18, UNUSED_19, FINISH_ROUND, FINISH, UNUSED_22, UNUSED_23, CHECK_STATIONARY_GROUP, START_CORRECTION, UNUSED_26, REQUEST_STATIONARY, UNUSED_28, APPLY_RETRACTIONS, PREPARE_GROUPS, UNUSED_31, SELECT_PHASE, UNUSED_33, UNUSED_34, DISCHARGE_SHORT, PREPARE_LONG, PUSH_LONG_BLOCK, FINISH_LONG, NOTIFY_LONG_BLOCK, STATIONARY_GROUPS, STATIONARY_INCOMING, STATIONARY_GROUP_QUEUE, CLEAR_PROPOSALS, STATIONARY_MAP, REQUEST_GROUP_BLOCK, PREPARE_STATIONARY_ROUND, SCATTER_STATIONARY_MEMBER, RESET_RESIDUAL }
    private enum Phase { SEED_DISTANCE, BFS, DISCHARGE, LOCAL_WITHDRAWAL, UNUSED_4, UNUSED_5, BUILD_RESIDUAL }
    private enum State { PHASE, BFS_WAVE, BFS_CHANGED, UNUSED_3, ACTIVE_BALANCES, BFS_REACHED_SURPLUS, BFS_ROUNDS, DISCHARGE_ROUNDS, RESERVED_8, CELL_QUEUE_A_COUNT, CELL_QUEUE_B_COUNT, RETRACTION_QUEUE_COUNT, GROUP_QUEUE_A_COUNT, REQUIRES_RESIDUAL, GROUP_QUEUE_B_COUNT, FLOW_CHANGED, PHASE_ENABLED, LONG_VERTEX_COUNT, SURPLUS_FLAGS, SINK_EDGE_COUNT }
    private enum Status { RUNNING, FEASIBLE, INFEASIBLE, ITERATION_LIMIT, INVALID }
    private enum Control { STATUS, ROUNDS, PHASE, UNUSED_3, UNUSED_4, MAX_ROUNDS }
    private enum Dispatch { SEED_DISTANCE, BFS_EDGE, DISCHARGE_SHORT, PREPARE_LONG, FINISH_LONG, SINGLE, LONG_BLOCK, UNUSED_7, UNUSED_8, REQUEST_STATIONARY, UNUSED_10, APPLY_RETRACTIONS, REQUEST_GROUP_BLOCK }
    private static final int WORK_FIELDS = 16;
    private static String headerDefines() {
        var result = new StringBuilder();
        for (var field : Header.values())
            result.append("#define H_").append(field.name()).append(' ').append(field.word).append('\n');
        result.append("#define ERROR_INVALID ").append(ERROR_INVALID).append("u\n");
        result.append("#define ERROR_UNFINISHED ").append(ERROR_UNFINISHED).append("u\n");
        result.append("#define WORK_FIELDS ").append(WORK_FIELDS).append('\n');
        result.append("#define CAPACITY_FIELDS ").append(VulkanLiquidCapacityLayout.FIELD_WORD).append('\n');
        return result + VulkanShaderLayout.fields("STAGE_", Stage.values()) + VulkanShaderLayout.fields("PHASE_", Phase.values())
            + VulkanShaderLayout.fields("STATUS_", Status.values()) + VulkanShaderLayout.fields("STATE_", State.values())
            + VulkanShaderLayout.fields("CONTROL_", Control.values()) + VulkanShaderLayout.fields("DISPATCH_", Dispatch.values())
            + VulkanLiquidCapacityLayout.shaderHeader() + VulkanShaderLayout.fields("FIELD_", Field.values())
            + VulkanShaderLayout.fields("CAPACITY_", VulkanLiquidCapacityLayout.Field.values());
    }

    static final int RUNNING=Status.RUNNING.ordinal(), FEASIBLE=Status.FEASIBLE.ordinal(),
        INFEASIBLE=Status.INFEASIBLE.ordinal(), ITERATION_LIMIT=Status.ITERATION_LIMIT.ordinal(), INVALID=Status.INVALID.ordinal();
    static final int ERROR_INVALID=4096,ERROR_UNFINISHED=8192;
    enum Field { HEADER(4),STATE(4),TAIL_FLOW(8),EXCESS(8),DISTANCE(4),GROUP_COUNTS(4),GROUP_OFFSETS(4),GROUP_CURSORS(4),GROUP_MEMBERS(4),
        PROPOSAL(8),LONG_BLOCK_COUNTS(4),TOTAL_PARTS(8),TOTAL(8),CUT_CELLS(4),SCAN(4),DISPATCH(4),LONG_VERTICES(4),LABEL_CHANGED(4),
        CELL_INCOMING(8),GROUP_INCOMING(8),GROUP_WITHDRAW(8),GROUP_BASE(8),SINK_EDGES(4),LONG_BLOCK_OFFSETS(4),LONG_BLOCK_OWNER(4),LONG_PARTIAL(4),LONG_WORK(4);
        final int width;Field(int width){this.width=width;}}
    static final class Layout {
        private final VulkanLiquidCapacityLayout capacity;
        private final int vertices,edges,totalBlocks,longBlocks;
        private final long endOffset;
        private final EnumMap<Field,VulkanLiquidStateLayout.Column> columns=new EnumMap<>(Field.class);
        Layout(VulkanLiquidCapacityLayout capacity,long scratchBase,long alignment){
            if(scratchBase<capacity.endOffset()||alignment<8||(alignment&(alignment-1))!=0)throw new IllegalArgumentException("Invalid liquid capacity layout");
            this.capacity=capacity;vertices=Math.addExact(Math.multiplyExact(2,capacity.cells()),capacity.groups()+1);
            edges=Math.addExact(Math.multiplyExact(8,capacity.cells()),capacity.groups());
            totalBlocks=(capacity.cells()+127)/128;
            longBlocks=Math.addExact(totalBlocks,Math.multiplyExact(2,capacity.groups()+1));
            long cursor=scratchBase;
            for(Field field:Field.values()){
                int count=switch(field){case HEADER->64;case DISPATCH->42;case STATE->20;case TAIL_FLOW->capacity.cells()+capacity.groups();
                    case GROUP_OFFSETS,LONG_VERTICES,LONG_BLOCK_COUNTS,LONG_WORK->capacity.groups()+1;
                    case LONG_BLOCK_OFFSETS->capacity.groups()+2;
                    case LONG_BLOCK_OWNER,LONG_PARTIAL->longBlocks;
                    case GROUP_COUNTS,GROUP_CURSORS,GROUP_INCOMING,GROUP_WITHDRAW,GROUP_BASE,SINK_EDGES->capacity.groups();
                    case GROUP_MEMBERS,CELL_INCOMING,CUT_CELLS->capacity.cells();case PROPOSAL->Math.multiplyExact(7,capacity.cells());
                    case TOTAL_PARTS->totalBlocks;case TOTAL->1;
                    case SCAN->Math.toIntExact(VulkanPrefixScan.workspaceBytes(capacity.groups()+1)/4);default->vertices;};
                var c=new VulkanLiquidStateLayout.Column(align(cursor,alignment),count,field.width);columns.put(field,c);cursor=c.offset()+c.bindingBytes();
            }endOffset=align(cursor,alignment);
        }
        VulkanLiquidStateLayout.Column column(Field field){return columns.get(field);}
        int word(Field field){return Math.toIntExact(column(field).offset()/4);}
        VulkanLiquidCapacityLayout capacity(){return capacity;}long endOffset(){return endOffset;}
        ByteBuffer headerBytes(){
            var result=ByteBuffer.allocateDirect(256).order(ByteOrder.LITTLE_ENDIAN);
            result.putInt(4 * Header.CAPACITY_HEADER.word,capacity.word(VulkanLiquidCapacityLayout.Field.HEADER)).putInt(4 * Header.CELL_COUNT.word,capacity.cells()).putInt(4 * Header.GROUP_COUNT.word,capacity.groups())
                    .putInt(4 * Header.VERTEX_COUNT.word,vertices).putInt(4 * Header.EDGE_COUNT.word,edges).putInt(4 * Header.RESERVED.word,0).putInt(4 * Header.TOTAL_BLOCKS.word,totalBlocks);
            for(var f:Field.values())result.putInt(4*(WORK_FIELDS+f.ordinal()),word(f));return result;
        }
        void initializeHeader(ByteBuffer arena){arena.duplicate().position(Math.toIntExact(column(Field.HEADER).offset())).put(headerBytes());}
    }
    private FlipKernelBindings.BufferRange arena;
    private final FlipKernelBindings bindings;private final VulkanPrefixScan scan;
    private final VulkanStageTimings timings;
    private int timingPhase=-1;
    VulkanLiquidCapacity(VulkanDeviceContext context){bindings=new FlipKernelBindings(context,3,shader(),20);
        try{scan=new VulkanPrefixScan(context);}catch(RuntimeException|LinkageError failure){bindings.close();throw failure;}
        try{timings=Boolean.getBoolean("mmm.gpuCapacityStages")?new VulkanStageTimings(context,8192):null;}
        catch(RuntimeException|Error failure){try{scan.close();}finally{bindings.close();}throw failure;}}
    void rebindRanges(FlipKernelBindings.BufferRange arena,FlipKernelBindings.BufferRange errors){
        this.arena=arena;bindings.rebindRanges(List.of(arena,errors,arena));scan.rebindRanges(arena,arena,arena,errors);}
    void recordInitialize(VkCommandBuffer command,Layout l,int maximumRounds){
        if(maximumRounds<1)throw new IllegalArgumentException("Invalid capacity iteration limit");
        if(timings!=null){timings.reset(command);timingPhase=-1;}
        stage(command,l,Stage.INITIALIZE.ordinal(),l.vertices,maximumRounds,0);
        stage(command,l,Stage.SEED_DONOR.ordinal(),l.capacity.cells(),0,0);
        stage(command,l,Stage.CHECK_STATIONARY_GROUP.ordinal(),l.capacity.groups(),0,0);stage(command,l,Stage.START_CORRECTION.ordinal(),1,0,0);
        stage(command,l,Stage.STATIONARY_GROUPS.ordinal(),l.capacity.groups()+1,0,0);
        stage(command,l,Stage.CLEAR_PROPOSALS.ordinal(),7*l.capacity.cells(),0,0);
        stage(command,l,Stage.STATIONARY_INCOMING.ordinal(),l.capacity.cells(),0,0);
        recordGroupScan(command,l);stage(command,l,Stage.SCATTER_STATIONARY_MEMBER.ordinal(),l.capacity.cells(),0,0);
        stage(command,l,Stage.STATIONARY_GROUP_QUEUE.ordinal(),l.capacity.groups(),0,0);
        scan.record(command,l.capacity.groups()+1,l.word(Field.LONG_BLOCK_COUNTS),l.word(Field.LONG_BLOCK_OFFSETS),l.word(Field.SCAN));
        stage(command,l,Stage.STATIONARY_MAP.ordinal(),l.capacity.groups(),0,0);stage(command,l,Stage.PREPARE_STATIONARY_ROUND.ordinal(),1,0,0);
    }
    private void buildResidualGraph(VkCommandBuffer command,Layout l){
        stage(command,l,Stage.RESET_RESIDUAL.ordinal(),l.vertices,0,0);
        stage(command,l,Stage.PREPARE_GROUPS.ordinal(),l.capacity.groups(),0,0);
        stage(command,l,Stage.BALANCE_RECEIVER.ordinal(),l.capacity.cells(),0,0);
        if(timings==null)recordGroupScan(command,l);
        else timings.record(command,"phase="+timingPhase+" stage=group-scan",()->recordGroupScan(command,l));
        stage(command,l,Stage.SCATTER_MEMBER.ordinal(),l.capacity.cells(),0,0);
        stage(command,l,Stage.TOTAL_BLOCK.ordinal(),128*l.totalBlocks,0,0);stage(command,l,Stage.TOTAL.ordinal(),1,0,0);
        stage(command,l,Stage.SEED_GROUP.ordinal(),l.capacity.groups(),0,0);stage(command,l,Stage.BALANCE_SINK.ordinal(),1,0,0);
        scan.record(command,l.capacity.groups()+1,l.word(Field.LONG_BLOCK_COUNTS),l.word(Field.LONG_BLOCK_OFFSETS),l.word(Field.SCAN));
        stage(command,l,Stage.MAP_LONG_BLOCKS.ordinal(),l.capacity.groups()+1,0,0);
        stage(command,l,Stage.START.ordinal(),1,0,0);
    }
    /** One wave of the current phase; completed phases leave the remaining indirect dispatches empty. */
    void recordIterations(VkCommandBuffer command,Layout l,int rounds,int phase){
        if(rounds<0)throw new IllegalArgumentException("Negative capacity round count");
        if(timings!=null)timingPhase=phase;
        try(var stack=stackPush()){bindings.bind(command,stack);}
        // Build the residual graph from the flow left by local withdrawal.
        if(phase==Phase.BUILD_RESIDUAL.ordinal())rounds=Math.min(rounds,1);
        for(int i=0;i<rounds;i++){
            if(phase!=Phase.LOCAL_WITHDRAWAL.ordinal())stage(command,l,Stage.SELECT_PHASE.ordinal(),1,phase,0);
            switch(Phase.values()[phase]){
                case SEED_DISTANCE,BFS->{stage(command,l,Stage.SEED_DISTANCE.ordinal(),l.vertices,0,0);stage(command,l,Stage.BEGIN_BFS.ordinal(),1,0,0);
                    stage(command,l,Stage.BFS_EDGE.ordinal(),l.edges,0,0);stage(command,l,Stage.FINISH_BFS.ordinal(),1,0,0);}
                case DISCHARGE->{stage(command,l,Stage.BEGIN_DISCHARGE.ordinal(),1,0,0);
                    for(int color=0;color<2;color++){
                        stage(command,l,Stage.DISCHARGE_SHORT.ordinal(),l.vertices,0,color);
                        stage(command,l,Stage.PREPARE_LONG.ordinal(),l.capacity.groups()+1,0,color);
                        stage(command,l,Stage.PUSH_LONG_BLOCK.ordinal(),Math.multiplyExact(128,l.longBlocks),0,color);
                        stage(command,l,Stage.FINISH_LONG.ordinal(),Math.multiplyExact(128,l.capacity.groups()+1),0,color);
                        stage(command,l,Stage.NOTIFY_LONG_BLOCK.ordinal(),Math.multiplyExact(128,l.longBlocks),0,color);
                    }}
                case LOCAL_WITHDRAWAL->{stage(command,l,Stage.REQUEST_STATIONARY.ordinal(),Math.addExact(l.capacity.cells(),l.capacity.groups()),0,0);
                    stage(command,l,Stage.REQUEST_GROUP_BLOCK.ordinal(),Math.multiplyExact(128,l.longBlocks),0,0);stage(command,l,Stage.APPLY_RETRACTIONS.ordinal(),l.capacity.cells(),0,0);}
                case BUILD_RESIDUAL->buildResidualGraph(command,l);
                default->throw new IllegalArgumentException("Unknown capacity phase "+phase);
            }
            stage(command,l,Stage.FINISH_ROUND.ordinal(),1,phase,0);
        }
    }
    /** Caller reads the usual CONTROL[status,rounds] before publication. INFEASIBLE exposes CUT_CELLS for trial rejection. */
    void recordFinish(VkCommandBuffer command,Layout l){if(timings!=null)timingPhase=-2;stage(command,l,Stage.FINISH.ordinal(),7*l.capacity.cells(),0,0);}
    private void recordGroupScan(VkCommandBuffer command,Layout l){
        scan.record(command,l.capacity.groups(),l.word(Field.GROUP_COUNTS),l.word(Field.GROUP_OFFSETS),l.word(Field.SCAN));
    }
    /** Read only after all recorded submissions completed; includes zero-work indirect command costs. */
    void printStageTimings(){
        if(timings==null)return;
        var totals=new java.util.LinkedHashMap<String,long[]>();
        for(var sample:timings.read()){
            var value=totals.computeIfAbsent(sample.stage(),ignored->new long[2]);value[0]+=sample.nanos();value[1]++;
        }
        long sum=0;
        for(var entry:totals.entrySet()){
            var value=entry.getValue();sum+=value[0];
            System.out.printf(java.util.Locale.ROOT,"capacity_stage %s samples=%d gpu_ms=%.6f average_us=%.3f%n",entry.getKey(),value[1],value[0]*1e-6,value[0]*1e-3/value[1]);
        }
        System.out.printf(java.util.Locale.ROOT,"capacity_stages_total_ms=%.6f%n",sum*1e-6);
    }
    private void stage(VkCommandBuffer command,Layout l,int stage,int count,int argument,int mode){
        if(count==0)return;
        if(timings==null)recordStage(command,l,stage,count,argument,mode);
        else timings.record(command,"phase="+timingPhase+" stage="+stage+(stage>=Stage.DISCHARGE_SHORT.ordinal()&&stage<=Stage.NOTIFY_LONG_BLOCK.ordinal()?" color="+mode:""),()->recordStage(command,l,stage,count,argument,mode));
    }
    private void recordStage(VkCommandBuffer command,Layout l,int stage,int count,int argument,int mode){
        try(var stack=stackPush()){
            if(stage<=Stage.START.ordinal()||stage==Stage.FINISH.ordinal()||stage==Stage.STATIONARY_MAP.ordinal()||stage==Stage.PREPARE_STATIONARY_ROUND.ordinal()||stage==Stage.SCATTER_STATIONARY_MEMBER.ordinal())bindings.bind(command,stack);vkCmdPushConstants(command,bindings.pipelineLayout(),VK_SHADER_STAGE_COMPUTE_BIT,0,
                    stack.ints(l.word(Field.HEADER),stage,count,argument,mode));int groups=(count+127)/128;
            if(stage>=Stage.SEED_DISTANCE.ordinal()&&stage<=Stage.BEGIN_DISCHARGE.ordinal()||stage==Stage.FINISH_ROUND.ordinal()||stage==Stage.REQUEST_STATIONARY.ordinal()||stage==Stage.APPLY_RETRACTIONS.ordinal()||stage==Stage.REQUEST_GROUP_BLOCK.ordinal()||stage>=Stage.DISCHARGE_SHORT.ordinal()&&stage<=Stage.NOTIFY_LONG_BLOCK.ordinal()){
                int dispatch=switch(Stage.values()[stage]){case SEED_DISTANCE->Dispatch.SEED_DISTANCE.ordinal();case BFS_EDGE->Dispatch.BFS_EDGE.ordinal();case DISCHARGE_SHORT->Dispatch.DISCHARGE_SHORT.ordinal();case PREPARE_LONG->Dispatch.PREPARE_LONG.ordinal();case PUSH_LONG_BLOCK,NOTIFY_LONG_BLOCK->Dispatch.LONG_BLOCK.ordinal();case FINISH_LONG->Dispatch.FINISH_LONG.ordinal();case REQUEST_STATIONARY->Dispatch.REQUEST_STATIONARY.ordinal();case APPLY_RETRACTIONS->Dispatch.APPLY_RETRACTIONS.ordinal();case REQUEST_GROUP_BLOCK->Dispatch.REQUEST_GROUP_BLOCK.ordinal();default->Dispatch.SINGLE.ordinal();};
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
        data.flip();return dump(layout,data,path);
    }
    static String dump(VulkanLiquidCapacityLayout layout,ByteBuffer data,String path){
        var header=java.nio.ByteBuffer.allocate(32).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        header.putLong(0x4d4d4d4341503031L).putLong(layout.baseOffset()).putLong(layout.bytes()).putLong(layout.column(VulkanLiquidCapacityLayout.Field.HEADER).offset());
        var target=java.nio.file.Path.of(path);
        byte[] bytes=new byte[data.remaining()];data.get(bytes);
        try(var output=java.nio.file.Files.newOutputStream(target)){output.write(header.array());output.write(bytes);}
        catch(java.io.IOException failure){throw new IllegalStateException("Cannot preserve capacity failure at "+target,failure);}
        return target.toAbsolutePath().toString();
    }
    private static String shader(){try(var input=VulkanLiquidCapacity.class.getResourceAsStream("/assets/mmm_gpu_probe/shaders/liquid-capacity.comp")){
        if(input==null)throw new IllegalStateException("Liquid capacity shader missing");return new String(input.readAllBytes(),StandardCharsets.UTF_8).replace("// HOST_LAYOUT", headerDefines());
    }catch(IOException failure){throw new IllegalStateException("Liquid capacity shader unavailable",failure);}}
    @Override public void close(){try{if(timings!=null)timings.close();}finally{try{scan.close();}finally{bindings.close();}}}
}

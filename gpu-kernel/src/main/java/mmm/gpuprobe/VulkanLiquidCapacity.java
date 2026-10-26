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
            result.putInt(0,capacity.word(VulkanLiquidCapacityLayout.Field.HEADER)).putInt(4,capacity.cells()).putInt(8,capacity.groups())
                    .putInt(12,vertices).putInt(16,edges).putInt(20,0).putInt(24,totalBlocks);
            for(var f:Field.values())result.putInt(4*(16+f.ordinal()),word(f));return result;
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
        stage(command,l,0,l.vertices,maximumRounds,0);
        stage(command,l,1,l.capacity.cells(),0,0);
        stage(command,l,24,l.capacity.groups(),0,0);stage(command,l,25,1,0,0);
        stage(command,l,40,l.capacity.groups()+1,0,0);
        stage(command,l,43,7*l.capacity.cells(),0,0);
        stage(command,l,41,l.capacity.cells(),0,0);
        recordGroupScan(command,l);stage(command,l,47,l.capacity.cells(),0,0);
        stage(command,l,42,l.capacity.groups(),0,0);
        scan.record(command,l.capacity.groups()+1,l.word(Field.LONG_BLOCK_COUNTS),l.word(Field.LONG_BLOCK_OFFSETS),l.word(Field.SCAN));
        stage(command,l,44,l.capacity.groups(),0,0);stage(command,l,46,1,0,0);
    }
    private void buildResidualGraph(VkCommandBuffer command,Layout l){
        stage(command,l,30,l.capacity.groups(),0,0);
        stage(command,l,3,l.capacity.cells(),0,0);
        if(timings==null)recordGroupScan(command,l);
        else timings.record(command,"phase="+timingPhase+" stage=group-scan",()->recordGroupScan(command,l));
        stage(command,l,4,l.capacity.cells(),0,0);
        stage(command,l,7,128*l.totalBlocks,0,0);stage(command,l,8,1,0,0);
        stage(command,l,9,l.capacity.groups(),0,0);stage(command,l,6,1,0,0);
        scan.record(command,l.capacity.groups()+1,l.word(Field.LONG_BLOCK_COUNTS),l.word(Field.LONG_BLOCK_OFFSETS),l.word(Field.SCAN));
        stage(command,l,5,l.capacity.groups()+1,0,0);
        stage(command,l,10,1,0,0);
    }
    /** One wave of the current phase; completed phases leave the remaining indirect dispatches empty. */
    void recordIterations(VkCommandBuffer command,Layout l,int rounds,int phase){
        if(rounds<0)throw new IllegalArgumentException("Negative capacity round count");
        if(timings!=null)timingPhase=phase;
        try(var stack=stackPush()){bindings.bind(command,stack);}
        // Residual topology is built once; withdrawal stays on device for the whole batch.
        if(phase==6)rounds=Math.min(rounds,1);
        for(int i=0;i<rounds;i++){
            if(phase!=3)stage(command,l,32,1,phase,0);
            switch(phase){
                case 0,1->{stage(command,l,11,l.vertices,0,0);stage(command,l,12,1,0,0);
                    stage(command,l,13,l.edges,0,0);stage(command,l,14,1,0,0);}
                case 2->{stage(command,l,15,1,0,0);
                    for(int color=0;color<2;color++){
                        stage(command,l,35,l.vertices,0,color);
                        stage(command,l,36,l.capacity.groups()+1,0,color);
                        stage(command,l,37,Math.multiplyExact(128,l.longBlocks),0,color);
                        stage(command,l,38,Math.multiplyExact(128,l.capacity.groups()+1),0,color);
                        stage(command,l,39,Math.multiplyExact(128,l.longBlocks),0,color);
                    }}
                case 3->{stage(command,l,27,Math.addExact(l.capacity.cells(),l.capacity.groups()),0,0);
                    stage(command,l,45,Math.multiplyExact(128,l.longBlocks),0,0);stage(command,l,29,l.capacity.cells(),0,0);}
                case 6->buildResidualGraph(command,l);
                default->throw new IllegalArgumentException("Unknown capacity phase "+phase);
            }
            stage(command,l,20,1,phase,0);
        }
    }
    /** Caller reads the usual CONTROL[status,rounds] before publication. INFEASIBLE exposes CUT_CELLS for trial rejection. */
    void recordFinish(VkCommandBuffer command,Layout l){if(timings!=null)timingPhase=-2;stage(command,l,21,7*l.capacity.cells(),0,0);}
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
        else timings.record(command,"phase="+timingPhase+" stage="+stage+(stage>=35&&stage<=39?" color="+mode:""),()->recordStage(command,l,stage,count,argument,mode));
    }
    private void recordStage(VkCommandBuffer command,Layout l,int stage,int count,int argument,int mode){
        try(var stack=stackPush()){
            if(stage<=10||stage==21||stage==44||stage==46||stage==47)bindings.bind(command,stack);vkCmdPushConstants(command,bindings.pipelineLayout(),VK_SHADER_STAGE_COMPUTE_BIT,0,
                    stack.ints(l.word(Field.HEADER),stage,count,argument,mode));int groups=(count+127)/128;
            if(stage>=11&&stage<=15||stage==20||stage==27||stage==29||stage==45||stage>=35&&stage<=39){
                int dispatch=switch(stage){case 11->0;case 13->1;case 35->2;case 36->3;case 37,39->6;case 38->4;case 27->9;case 29->11;case 45->12;default->5;};
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
        if(input==null)throw new IllegalStateException("Liquid capacity shader missing");return new String(input.readAllBytes(),StandardCharsets.UTF_8);
    }catch(IOException failure){throw new IllegalStateException("Liquid capacity shader unavailable",failure);}}
    @Override public void close(){try{if(timings!=null)timings.close();}finally{try{scan.close();}finally{bindings.close();}}}
}

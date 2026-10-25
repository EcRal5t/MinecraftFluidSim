package mmm.gpuprobe;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import org.lwjgl.vulkan.VkCommandBuffer;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Independent degree-two elimination, a sparse retained core, and exact pressure back substitution. */
final class VulkanGasFlowSchurPressure implements AutoCloseable {
    static final int DENSE_ROWS=256;
    enum Field{HEADER(4),DEGREE(4),SOFT(8),SOURCE(8),DIAGONAL(8),PEER_A(4),PEER_B(4),WEIGHT_A(8),WEIGHT_B(8),
        ELIMINATED(4),CORE_FLAGS(4),CORE_OFFSETS(4),CORE_NODES(4),CORE_ID(4),CORE_SOFT(8),CORE_SOURCE(8),CORE_ROOT(8),
        PROPOSAL_A(4),PROPOSAL_B(4),PROPOSAL_G(8),PROPOSAL_SLOT(4),HASH(4),HASH_GROUP(4),LIVE(4),
        GROUP_COUNTS(4),GROUP_OFFSETS(4),GROUP_CURSORS(4),GROUP_PROPOSALS(4),GROUP_A(4),GROUP_B(4),GROUP_G(8),DEST_A(4),DEST_B(4),
        ROW_COUNTS(4),ROW_CURSORS(4),STARTS(4),COLUMNS(4),VALUES(8),ROWS(4),PRESSURE(8),WORK(8),META(4),PROBLEM(8),CONTROL(8),PARTIALS(8),SCAN(4),STATE(8),DENSE(8),FINAL_PARTIALS(8);
        final int width;Field(int width){this.width=width;}}
    static final class Layout{
        final VulkanGasFlow.Layout flow;final int nodes,pairs,proposals,hashes;final EnumMap<Field,VulkanLiquidStateLayout.Column> columns=new EnumMap<>(Field.class);final long endOffset;
        Layout(VulkanGasFlow.Layout flow,long base,long a){this.flow=flow;nodes=flow.nodes;pairs=flow.pairs;proposals=Math.addExact(nodes,pairs);int hashes=1;while(hashes<Math.multiplyExact(2,proposals))hashes=Math.multiplyExact(hashes,2);this.hashes=hashes;
            long cursor=base;for(var f:Field.values()){if(f==Field.CONTROL){columns.put(f,flow.column(VulkanGasFlow.Field.CONTROL));continue;}
                int count=switch(f){case HEADER->128;case CORE_OFFSETS,STARTS->nodes+1;case PROPOSAL_A,PROPOSAL_B,PROPOSAL_G,PROPOSAL_SLOT,GROUP_COUNTS,GROUP_CURSORS,GROUP_PROPOSALS,GROUP_A,GROUP_B,GROUP_G,DEST_A,DEST_B->proposals;
                    case HASH,HASH_GROUP->hashes;case LIVE->1;case GROUP_OFFSETS->proposals+1;
                    case COLUMNS,VALUES->Math.addExact(nodes,Math.multiplyExact(proposals,2));case WORK->Math.multiplyExact(nodes,5);case META->Math.multiplyExact(nodes,2);case PROBLEM->Math.addExact(Math.multiplyExact(nodes,3),1);
                    case PARTIALS->Math.multiplyExact((nodes+255)/256,4);case SCAN->Math.toIntExact(VulkanPrefixScan.workspaceBytes(proposals)/4);case STATE->16;case DENSE->DENSE_ROWS*DENSE_ROWS;case FINAL_PARTIALS->Math.multiplyExact((nodes+127)/128,4);default->nodes;};
                long at=align(cursor,a);var c=new VulkanLiquidStateLayout.Column(at,count,f.width);columns.put(f,c);cursor=at+c.bindingBytes();}endOffset=align(cursor,a);}
        VulkanLiquidStateLayout.Column column(Field f){return columns.get(f);}int word(Field f){return Math.toIntExact(column(f).offset()/4);}
        VulkanLiquidStateLayout.Column header(){return column(Field.HEADER);}long endOffset(){return endOffset;}
        long feedbackOffset(){return column(Field.STATE).offset()+64;}
        ByteBuffer headerBytes(){var out=ByteBuffer.allocateDirect(512).order(ByteOrder.LITTLE_ENDIAN);int[] h=new int[128];h[0]=flow.headerWordOffset();h[1]=nodes;h[2]=pairs;h[3]=proposals;h[4]=hashes;h[5]=DENSE_ROWS;for(var f:Field.values())h[16+f.ordinal()]=word(f);for(int i=0;i<h.length;i++)out.putInt(4*i,h[i]);return out;}
        List<FlipKernelBindings.BufferRange> pressureRanges(FlipKernelBindings.BufferRange arena,FlipKernelBindings.BufferRange errors){var result=new ArrayList<FlipKernelBindings.BufferRange>();for(var f:new Field[]{Field.STARTS,Field.COLUMNS,Field.VALUES,Field.ROWS,Field.PRESSURE,Field.PRESSURE,Field.WORK,Field.META,Field.PROBLEM,Field.CONTROL,Field.PARTIALS}){var c=column(f);result.add(new FlipKernelBindings.BufferRange(arena.buffer(),arena.offset()+c.offset(),c.bindingBytes()));}result.add(errors);return result;}
    }
    static Layout layout(VulkanGasFlow.Layout flow,long base,long a){return new Layout(flow,base,a);}
    private final FlipKernelBindings bindings,factorBindings;private final VulkanPrefixScan scan;private final VulkanPressureKernel corePressure;private final VulkanGasFlow original;private final VulkanComputeCommandCache cache;
    private FlipKernelBindings.BufferRange arena,errors;private final List<AutoCloseable> owned=new ArrayList<>();
    private record Key(FlipKernelBindings.BufferIdentity arena,FlipKernelBindings.BufferIdentity errors,Object layout,long duration,long ambient,int stage,long count){}
    VulkanGasFlowSchurPressure(VulkanDeviceContext context){try{bindings=own(new FlipKernelBindings(context,2,shader(),32));factorBindings=own(new FlipKernelBindings(context,2,resource("gas-flow-schur-factor.comp").replace("DENSE_FIELD",Integer.toString(Field.DENSE.ordinal())).replace("CORE_OFFSETS_FIELD",Integer.toString(Field.CORE_OFFSETS.ordinal())),32));scan=own(new VulkanPrefixScan(context));corePressure=own(new VulkanPressureKernel(context,4096));original=own(new VulkanGasFlow(context));cache=own(new VulkanComputeCommandCache(context));}catch(RuntimeException|LinkageError failure){try{close();}catch(RuntimeException closing){failure.addSuppressed(closing);}throw failure;}}
    private <T extends AutoCloseable>T own(T value){owned.add(value);return value;}
    void rebindRanges(FlipKernelBindings.BufferRange arena,FlipKernelBindings.BufferRange errors,Layout l){this.arena=arena;this.errors=errors;bindings.rebindRanges(List.of(arena,errors));factorBindings.rebindRanges(List.of(arena,errors));scan.rebindRanges(arena,arena,arena,errors);corePressure.rebindRanges(l.pressureRanges(arena,errors));original.rebindRanges(arena,errors);}
    void recordPrepare(VkCommandBuffer primary,Layout l,VulkanGasFlow.Parameters q,java.util.function.BiConsumer<String,Runnable> timed){
        timed.accept("schur-init-source",()->cached(primary,l,q,10,0,command->{stage(command,l,q,0,Math.max(l.hashes,Math.max(l.proposals,l.nodes)));stage(command,l,q,1,l.nodes);stage(command,l,q,21,0);}));
        timed.accept("schur-select-scan",()->cached(primary,l,q,11,0,command->{stage(command,l,q,2,l.nodes);prefix(command,l,l.nodes,Field.CORE_FLAGS,Field.CORE_OFFSETS);}));
        timed.accept("schur-core-rhs",()->cached(primary,l,q,12,0,command->{stage(command,l,q,3,l.nodes);stage(command,l,q,4,l.nodes);}));
        timed.accept("schur-proposals",()->cached(primary,l,q,13,0,command->stage(command,l,q,5,l.proposals)));
        timed.accept("schur-hash",()->cached(primary,l,q,14,0,command->stage(command,l,q,6,l.proposals)));
        timed.accept("schur-count",()->cached(primary,l,q,15,0,command->stage(command,l,q,7,l.proposals)));
    }
    void recordComplete(VkCommandBuffer command,Layout l,VulkanGasFlow.Parameters q,int groupCount){
        prefix(command,l,groupCount,Field.GROUP_COUNTS,Field.GROUP_OFFSETS);stage(command,l,q,8,l.proposals);stage(command,l,q,9,groupCount);
        prefix(command,l,l.nodes,Field.ROW_COUNTS,Field.STARTS);stage(command,l,q,10,groupCount);stage(command,l,q,11,l.nodes);stage(command,l,q,12,groupCount);
    }
    void recordDense(VkCommandBuffer primary,Layout l,VulkanGasFlow.Parameters q){cached(primary,l,q,1,0,command->{stage(command,l,q,13,DENSE_ROWS*DENSE_ROWS);stage(command,l,q,14,l.nodes);stage(command,l,q,15,1);stage(command,l,q,16,1);});}
    void recordCoreInitialize(VkCommandBuffer command,Layout l,int count){corePressure.recordInitialize(command,l.nodes,count,200,null,null);}
    void recordCoreIterations(VkCommandBuffer primary,Layout l,VulkanGasFlow.Parameters q,int count,int iterations){cached(primary,l,q,2,((long)count<<32)|iterations,command->corePressure.recordIterations(command,l.nodes,count,200,null,null,iterations));}
    void recordCoreFinish(VkCommandBuffer command,Layout l,int count){corePressure.recordFinish(command,l.nodes,count,200,null,null);}
    void recordFinish(VkCommandBuffer primary,Layout l,VulkanGasFlow.Parameters q){cached(primary,l,q,3,0,command->{stage(command,l,q,17,l.nodes);stage(command,l,q,18,1);original.pressureOperator(l.flow,q).recordProduct(command,false);stage(command,l,q,19,l.nodes);stage(command,l,q,20,1);});}
    private void prefix(VkCommandBuffer command,Layout l,int n,Field in,Field out){scan.record(command,n,l.word(in),l.word(out),l.word(Field.SCAN));}
    private void cached(VkCommandBuffer command,Layout l,VulkanGasFlow.Parameters q,int kind,long count,java.util.function.Consumer<VkCommandBuffer> recorder){cache.record(command,new Key(new FlipKernelBindings.BufferIdentity(arena.buffer()),new FlipKernelBindings.BufferIdentity(errors.buffer()),l,Double.doubleToLongBits(q.duration()),Double.doubleToLongBits(q.ambientPressure()),kind,count),recorder);}
    private void stage(VkCommandBuffer command,Layout l,VulkanGasFlow.Parameters q,int stage,int count){if(count==0&&stage!=4&&stage!=9&&stage!=11&&stage!=21)return;try(var stack=stackPush()){
        var pipeline=stage==15?factorBindings:bindings;pipeline.bind(command,stack);var push=stack.malloc(32).putInt(l.word(Field.HEADER)).putInt(stage).putInt(count).putInt(0).putDouble(q.duration()).putDouble(q.ambientPressure()).flip();vkCmdPushConstants(command,pipeline.pipelineLayout(),VK_SHADER_STAGE_COMPUTE_BIT,0,push);
        if(stage==21){VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT,VK_ACCESS_INDIRECT_COMMAND_READ_BIT,stack);vkCmdDispatchIndirect(command,arena.buffer().handle(),arena.offset()+l.flow.column(VulkanGasFlow.Field.DISPATCH).offset()+72);}
        else if(stage==4||stage==9||stage==11){VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT,VK_ACCESS_INDIRECT_COMMAND_READ_BIT,stack);vkCmdDispatchIndirect(command,arena.buffer().handle(),arena.offset()+l.column(Field.STATE).offset()+(stage==9?48:32));}
        else{int groups=(stage==15||stage==16||stage==18||stage==20?1:(count+127)/128);vkCmdDispatch(command,Math.min(groups,65535),(groups+65534)/65535,1);}
        VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);}}
    private static long align(long n,long a){return Math.addExact(n,a-1)&-a;}
    private static String shader(){return resource("gas-flow-schur-pressure.comp");}
    private static String resource(String name){try(var in=VulkanGasFlowSchurPressure.class.getResourceAsStream("/assets/mmm_gpu_probe/shaders/"+name)){if(in==null)throw new IllegalStateException("Gas Schur shader missing: "+name);return new String(in.readAllBytes(),StandardCharsets.UTF_8);}catch(IOException e){throw new IllegalStateException("Gas Schur shader unavailable",e);}}
    @Override public void close(){RuntimeException failure=null;for(int i=owned.size()-1;i>=0;i--)try{owned.get(i).close();}catch(Exception closing){if(failure==null)failure=new IllegalStateException("Gas Schur close failed",closing);else failure.addSuppressed(closing);}owned.clear();if(failure!=null)throw failure;}
}

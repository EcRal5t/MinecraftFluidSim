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
        final VulkanGasFlow.Layout flow;final VulkanGasSchurGraph source;final int nodes,pairs,proposals,hashes;final EnumMap<Field,VulkanLiquidStateLayout.Column> columns=new EnumMap<>(Field.class);final long endOffset;
        Layout(VulkanGasFlow.Layout flow,long base,long a){this(flow,null,base,a);}
        Layout(VulkanGasFlow.Layout flow,VulkanGasSchurGraph source,long base,long a){this.flow=flow;this.source=source;nodes=source==null?flow.nodes:source.nodes;pairs=source==null?flow.pairs:source.pairs;proposals=Math.addExact(nodes,pairs);int hashes=1;while(hashes<Math.multiplyExact(2,proposals))hashes=Math.multiplyExact(hashes,2);this.hashes=hashes;
            long cursor=base;for(var f:Field.values()){if(f==Field.CONTROL){columns.put(f,source==null?flow.column(VulkanGasFlow.Field.CONTROL):source.column(VulkanGasSchurGraph.Field.CONTROL));continue;}
                int count=switch(f){case HEADER->128;case CORE_OFFSETS,STARTS->nodes+1;case PROPOSAL_A,PROPOSAL_B,PROPOSAL_G,PROPOSAL_SLOT,GROUP_COUNTS,GROUP_CURSORS,GROUP_PROPOSALS,GROUP_A,GROUP_B,GROUP_G,DEST_A,DEST_B->proposals;
                    case HASH,HASH_GROUP->hashes;case LIVE->1;case GROUP_OFFSETS->proposals+1;
                    case COLUMNS,VALUES->Math.addExact(nodes,Math.multiplyExact(proposals,2));case WORK->Math.multiplyExact(nodes,7);case META->Math.multiplyExact(nodes,2);case PROBLEM->Math.addExact(Math.multiplyExact(nodes,3),1);
                    case PARTIALS->Math.multiplyExact((nodes+255)/256,4);case SCAN->Math.toIntExact(VulkanPrefixScan.workspaceBytes(proposals)/4);case STATE->16;case DENSE->DENSE_ROWS*DENSE_ROWS;case FINAL_PARTIALS->Math.multiplyExact((nodes+127)/128,4);default->nodes;};
                long at=align(cursor,a);var c=new VulkanLiquidStateLayout.Column(at,count,f.width);columns.put(f,c);cursor=at+c.bindingBytes();}endOffset=align(cursor,a);}
        VulkanLiquidStateLayout.Column column(Field f){return columns.get(f);}int word(Field f){return Math.toIntExact(column(f).offset()/4);}
        VulkanLiquidStateLayout.Column header(){return column(Field.HEADER);}long endOffset(){return endOffset;}
        long feedbackOffset(){return column(Field.STATE).offset()+64;}
        ByteBuffer headerBytes(){var out=ByteBuffer.allocateDirect(512).order(ByteOrder.LITTLE_ENDIAN);int[] h=new int[128];h[0]=source==null?flow.headerWordOffset():source.word(VulkanGasSchurGraph.Field.HEADER);h[1]=nodes;h[2]=pairs;h[3]=proposals;h[4]=hashes;h[5]=DENSE_ROWS;h[6]=source==null?0:1;if(source!=null){h[7]=source.word(VulkanGasSchurGraph.Field.SOURCE);h[8]=source.word(VulkanGasSchurGraph.Field.INITIAL);h[9]=source.word(VulkanGasSchurGraph.Field.COUNTS);h[10]=h[9]+1;h[11]=source.word(VulkanGasSchurGraph.Field.TARGET);}for(var f:Field.values())h[16+f.ordinal()]=word(f);for(int i=0;i<h.length;i++)out.putInt(4*i,h[i]);return out;}
        List<FlipKernelBindings.BufferRange> pressureRanges(FlipKernelBindings.BufferRange arena,FlipKernelBindings.BufferRange errors){var result=new ArrayList<FlipKernelBindings.BufferRange>();for(var f:new Field[]{Field.STARTS,Field.COLUMNS,Field.VALUES,Field.ROWS,Field.PRESSURE,Field.PRESSURE,Field.WORK,Field.META,Field.PROBLEM,Field.CONTROL,Field.PARTIALS}){var c=column(f);result.add(new FlipKernelBindings.BufferRange(arena.buffer(),arena.offset()+c.offset(),c.bindingBytes()));}result.add(errors);return result;}
    }
    static Layout layout(VulkanGasFlow.Layout flow,long base,long a){return new Layout(flow,base,a);}
    private final FlipKernelBindings bindings,factorBindings;private final VulkanPrefixScan scan;private final VulkanPressureKernel corePressure;private final VulkanGasFlow original;private final VulkanComputeCommandCache cache;
    private FlipKernelBindings.BufferRange arena,errors;private final List<AutoCloseable> owned=new ArrayList<>();
    private final VulkanDeviceContext context;private final FlipGpuResources scratchResources;private final List<SmallLayer> layers=new ArrayList<>();
    private long headerUploads,graphCopies;
    private static final class SmallLayer{final VulkanGasSchurGraph source;final Layout layout;final GpuBuffer buffer;boolean cold=true;SmallLayer(VulkanGasSchurGraph source,Layout layout,GpuBuffer buffer){this.source=source;this.layout=layout;this.buffer=buffer;}}
    private record Frame(GpuBuffer buffer,Layout layout,int inputNodes,int core,int edges,boolean noReduction){}
    @FunctionalInterface interface Timing{void record(VkCommandBuffer command,String label,Runnable body);}
    long headerUploadBytes(){return headerUploads;}long graphCopyBytes(){return graphCopies;}long scratchBytes(){return layers.stream().mapToLong(layer->layer.buffer.size()).sum();}

    private record Key(FlipKernelBindings.BufferIdentity arena,FlipKernelBindings.BufferIdentity errors,Object layout,long duration,long ambient,int stage,long count){}
    VulkanGasFlowSchurPressure(VulkanDeviceContext context){this.context=context;try{scratchResources=own(new FlipGpuResources(context));bindings=own(new FlipKernelBindings(context,2,shader(),32));factorBindings=own(new FlipKernelBindings(context,2,resource("gas-flow-schur-factor.comp").replace("DENSE_FIELD",Integer.toString(Field.DENSE.ordinal())).replace("CORE_OFFSETS_FIELD",Integer.toString(Field.CORE_OFFSETS.ordinal())),32));scan=own(new VulkanPrefixScan(context));corePressure=own(new VulkanPressureKernel(context,4096));original=own(new VulkanGasFlow(context));cache=own(new VulkanComputeCommandCache(context));}catch(RuntimeException|LinkageError failure){try{close();}catch(RuntimeException closing){failure.addSuppressed(closing);}throw failure;}}
    private <T extends AutoCloseable>T own(T value){owned.add(value);return value;}
    void rebindRanges(FlipKernelBindings.BufferRange arena,FlipKernelBindings.BufferRange errors,Layout l){this.arena=arena;this.errors=errors;bindings.rebindRanges(List.of(arena,errors));factorBindings.rebindRanges(List.of(arena,errors));scan.rebindRanges(arena,arena,arena,errors);corePressure.rebindRanges(l.pressureRanges(arena,errors));original.rebindRanges(arena,errors);}
    private SmallLayer layer(int depth,int nodes,int edges){
        var old=depth<layers.size()?layers.get(depth):null;if(old!=null&&old.source.nodes>=nodes&&old.source.pairs>=edges)return old;
        int nc=reserve(nodes),ec=reserve(edges);var source=new VulkanGasSchurGraph(nc,ec,context.storageOffsetAlignment());var layout=new Layout(null,source,source.endOffset,context.storageOffsetAlignment());
        var buffer=scratchResources.addDeviceStorage(layout.endOffset);if(old!=null)scratchResources.destroy(old.buffer);var result=new SmallLayer(source,layout,buffer);if(depth==layers.size())layers.add(result);else layers.set(depth,result);return result;
    }
    private static int reserve(int required){int n=1;while(n<required)n=Math.multiplyExact(n,2);return n;}
    private Frame frame(GpuBuffer buffer,Layout layout,int inputNodes,ByteBuffer feedback){int packed=feedback.getInt(0);return new Frame(buffer,layout,inputNodes,packed&0x7fffffff,feedback.getInt(4),packed<0);}
    private void copy(VkCommandBuffer command,GpuBuffer from,long fromOffset,GpuBuffer to,long toOffset,long bytes,org.lwjgl.system.MemoryStack stack){if(bytes==0)return;VulkanRegionStorage.copy(command,from,fromOffset,to,toOffset,bytes,stack);graphCopies+=bytes;}
    private void feedback(VkCommandBuffer command,Frame from,GpuBuffer root,long rootOffset,org.lwjgl.system.MemoryStack stack){if(from.buffer==root&&from.layout.feedbackOffset()==rootOffset)return;
        VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_READ_BIT|VK_ACCESS_TRANSFER_WRITE_BIT,stack);copy(command,from.buffer,from.layout.feedbackOffset(),root,rootOffset,8,stack);
        VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);}
    private void importGraph(VkCommandBuffer command,Frame from,SmallLayer target,org.lwjgl.system.MemoryStack stack){
        VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_READ_BIT|VK_ACCESS_TRANSFER_WRITE_BIT,stack);
        if(target.cold){var sourceHeader=target.source.headerBytes();var header=target.layout.headerBytes();vkCmdUpdateBuffer(command,target.buffer.handle(),target.source.column(VulkanGasSchurGraph.Field.HEADER).offset(),sourceHeader);vkCmdUpdateBuffer(command,target.buffer.handle(),target.layout.header().offset(),header);headerUploads+=sourceHeader.remaining()+header.remaining();target.cold=false;}
        var l=from.layout;var source=target.source;copy(command,from.buffer,l.column(Field.STATE).offset()+80,target.buffer,source.column(VulkanGasSchurGraph.Field.COUNTS).offset(),8,stack);
        for(var pair:List.of(new Object[]{Field.CORE_SOFT,VulkanGasSchurGraph.Field.SOFT},new Object[]{Field.CORE_SOURCE,VulkanGasSchurGraph.Field.SOURCE},new Object[]{Field.CORE_ROOT,VulkanGasSchurGraph.Field.ROOT}))copy(command,from.buffer,l.column((Field)pair[0]).offset(),target.buffer,source.column((VulkanGasSchurGraph.Field)pair[1]).offset(),8L*from.core,stack);
        copy(command,from.buffer,l.column(Field.PROBLEM).offset()+16L*l.nodes,target.buffer,source.column(VulkanGasSchurGraph.Field.INITIAL).offset(),8L*from.core,stack);
        copy(command,from.buffer,l.column(Field.PROBLEM).offset()+24L*l.nodes,target.buffer,source.column(VulkanGasSchurGraph.Field.TARGET).offset(),8,stack);
        copy(command,from.buffer,l.column(Field.GROUP_A).offset(),target.buffer,source.column(VulkanGasSchurGraph.Field.PAIR_A).offset(),4L*from.edges,stack);copy(command,from.buffer,l.column(Field.GROUP_B).offset(),target.buffer,source.column(VulkanGasSchurGraph.Field.PAIR_B).offset(),4L*from.edges,stack);copy(command,from.buffer,l.column(Field.GROUP_G).offset(),target.buffer,source.column(VulkanGasSchurGraph.Field.WEIGHTS).offset(),8L*from.edges,stack);
        VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);
    }
    int execute(VulkanRegionStorage.Candidate candidate,Layout root,VulkanGasFlow.Parameters q,Timing timed,VulkanRegionStorage.ComputeCommands tail){
        GpuBuffer rootBuffer=arena.buffer(),errorBuffer=errors.buffer();long feedbackOffset=root.feedbackOffset();var stackFrames=new ArrayList<Frame>();
        var batch=candidate.continueBatch((command,input,output,error,staging,stack)->{rebindRanges(new FlipKernelBindings.BufferRange(rootBuffer),new FlipKernelBindings.BufferRange(errorBuffer),root);timed.record(command,"aperture-schur-prepare",()->recordPrepare(command,root,q,(label,body)->timed.record(command,label,body)));},feedbackOffset,8);
        if(batch.error()!=0)return batch.error();Frame current=frame(rootBuffer,root,0,batch.feedback());stackFrames.add(current);
        while(current.core>DENSE_ROWS&&!current.noReduction){var from=current;var target=layer(stackFrames.size()-1,from.core,from.edges);
            batch=candidate.continueBatch((command,input,output,error,staging,stack)->{
                rebindRanges(new FlipKernelBindings.BufferRange(from.buffer),new FlipKernelBindings.BufferRange(errorBuffer),from.layout);timed.record(command,"aperture-schur-csr",()->recordComplete(command,from.layout,q,from.edges));
                importGraph(command,from,target,stack);rebindRanges(new FlipKernelBindings.BufferRange(target.buffer),new FlipKernelBindings.BufferRange(errorBuffer),target.layout);
                timed.record(command,"schur-layer-adjacency",()->recordGraphAdjacency(command,target.layout,q));timed.record(command,"schur-layer-prepare",()->recordPrepare(command,target.layout,q,(label,body)->body.run()));
                feedback(command,new Frame(target.buffer,target.layout,from.core,0,0,false),rootBuffer,feedbackOffset,stack);
            },feedbackOffset,8);
            if(batch.error()!=0)return batch.error();current=frame(target.buffer,target.layout,from.core,batch.feedback());stackFrames.add(current);
        }
        var terminal=current;if(Boolean.getBoolean("mmm.gpuMechanicalStages"))for(int i=0;i<stackFrames.size();i++){var frame=stackFrames.get(i);System.out.printf(java.util.Locale.ROOT,"gas_schur layer=%d core=%d edges=%d solver=%s%n",i,frame.core,frame.edges,i+1<stackFrames.size()?"schur":frame.core<=DENSE_ROWS?"dense":"pcg");}
        if(terminal.core>DENSE_ROWS){
            batch=candidate.continueBatch((command,input,output,error,staging,stack)->{rebindRanges(new FlipKernelBindings.BufferRange(terminal.buffer),new FlipKernelBindings.BufferRange(errorBuffer),terminal.layout);timed.record(command,"aperture-schur-csr",()->recordComplete(command,terminal.layout,q,terminal.edges));recordCoreInitialize(command,terminal.layout,terminal.core);recordCoreIterations(command,terminal.layout,q,terminal.core,8);copyRun(command,terminal,rootBuffer,feedbackOffset,stack);},feedbackOffset,8);
            int scheduled=8;while(batch.error()==0&&batch.feedback().getDouble(0)!=0&&scheduled<200){int count=Math.min(8,200-scheduled);batch=candidate.continueBatch((command,input,output,error,staging,stack)->{recordCoreIterations(command,terminal.layout,q,terminal.core,count);copyRun(command,terminal,rootBuffer,feedbackOffset,stack);},feedbackOffset,8);scheduled+=count;}if(batch.error()!=0)return batch.error();
        }
        return candidate.continueBatch((command,input,output,error,staging,stack)->{
            rebindRanges(new FlipKernelBindings.BufferRange(terminal.buffer),new FlipKernelBindings.BufferRange(errorBuffer),terminal.layout);
            if(terminal.core<=DENSE_ROWS){timed.record(command,"aperture-schur-csr",()->recordComplete(command,terminal.layout,q,terminal.edges));if(terminal.core>0)timed.record(command,"aperture-schur-factor-solve",()->recordDense(command,terminal.layout,q));}else recordCoreFinish(command,terminal.layout,terminal.core);
            for(int i=stackFrames.size()-1;i>0;i--){var child=stackFrames.get(i);var parent=stackFrames.get(i-1);rebindRanges(new FlipKernelBindings.BufferRange(child.buffer),new FlipKernelBindings.BufferRange(errorBuffer),child.layout);timed.record(command,"schur-layer-backsub",()->recordBackSubstitute(command,child.layout,q));
                VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_READ_BIT|VK_ACCESS_TRANSFER_WRITE_BIT,stack);copy(command,child.buffer,child.layout.source.column(VulkanGasSchurGraph.Field.RESULT).offset(),parent.buffer,parent.layout.column(Field.PRESSURE).offset(),8L*child.inputNodes,stack);
                if(i==stackFrames.size()-1&&terminal.core>DENSE_ROWS)copy(command,child.buffer,child.layout.column(Field.CONTROL).offset(),rootBuffer,root.column(Field.CONTROL).offset(),8,stack);
                VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);
            }
            rebindRanges(new FlipKernelBindings.BufferRange(rootBuffer),new FlipKernelBindings.BufferRange(errorBuffer),root);timed.record(command,"aperture-schur-backsub-residual",()->recordFinish(command,root,q));tail.record(command,input,output,error,staging,stack);
        },0,0).error();
    }
    private void copyRun(VkCommandBuffer command,Frame from,GpuBuffer root,long rootOffset,org.lwjgl.system.MemoryStack stack){VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_READ_BIT|VK_ACCESS_TRANSFER_WRITE_BIT,stack);copy(command,from.buffer,from.layout.column(Field.CONTROL).offset()+24,root,rootOffset,8,stack);VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);}
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
    void recordGraphAdjacency(VkCommandBuffer command,Layout l,VulkanGasFlow.Parameters q){stage(command,l,q,22,l.nodes);stage(command,l,q,23,l.pairs);scan.record(command,l.nodes,l.source.word(VulkanGasSchurGraph.Field.NODE_COUNTS),l.source.word(VulkanGasSchurGraph.Field.NODE_OFFSETS),l.source.word(VulkanGasSchurGraph.Field.SCAN));stage(command,l,q,24,l.pairs);stage(command,l,q,25,l.nodes);stage(command,l,q,26,1);}
    void recordBackSubstitute(VkCommandBuffer command,Layout l,VulkanGasFlow.Parameters q){stage(command,l,q,17,l.nodes);}
    void recordDense(VkCommandBuffer primary,Layout l,VulkanGasFlow.Parameters q){cached(primary,l,q,1,0,command->{stage(command,l,q,13,DENSE_ROWS*DENSE_ROWS);stage(command,l,q,14,l.nodes);stage(command,l,q,15,1);stage(command,l,q,16,1);});}
    void recordCoreInitialize(VkCommandBuffer command,Layout l,int count){corePressure.recordInitialize(command,l.nodes,count,200,null,null);}
    void recordCoreIterations(VkCommandBuffer primary,Layout l,VulkanGasFlow.Parameters q,int count,int iterations){cached(primary,l,q,2,((long)count<<32)|iterations,command->corePressure.recordIterations(command,l.nodes,count,200,null,null,iterations));}
    void recordCoreFinish(VkCommandBuffer command,Layout l,int count){corePressure.recordFinish(command,l.nodes,count,200,null,null);}
    void recordFinish(VkCommandBuffer primary,Layout l,VulkanGasFlow.Parameters q){cached(primary,l,q,3,0,command->{stage(command,l,q,17,l.nodes);stage(command,l,q,18,1);original.pressureOperator(l.flow,q).recordProduct(command,false);stage(command,l,q,19,l.nodes);stage(command,l,q,20,1);});}
    private void prefix(VkCommandBuffer command,Layout l,int n,Field in,Field out){scan.record(command,n,l.word(in),l.word(out),l.word(Field.SCAN));}
    private void cached(VkCommandBuffer command,Layout l,VulkanGasFlow.Parameters q,int kind,long count,java.util.function.Consumer<VkCommandBuffer> recorder){cache.record(command,new Key(new FlipKernelBindings.BufferIdentity(arena.buffer()),new FlipKernelBindings.BufferIdentity(errors.buffer()),l,Double.doubleToLongBits(q.duration()),Double.doubleToLongBits(q.ambientPressure()),kind,count),recorder);}
    private void stage(VkCommandBuffer command,Layout l,VulkanGasFlow.Parameters q,int stage,int count){if(count==0&&stage!=4&&stage!=9&&stage!=11&&stage!=21)return;try(var stack=stackPush()){
        var pipeline=stage==15?factorBindings:bindings;pipeline.bind(command,stack);var push=stack.malloc(32).putInt(l.word(Field.HEADER)).putInt(stage).putInt(count).putInt(0).putDouble(q.duration()).putDouble(q.ambientPressure()).flip();vkCmdPushConstants(command,pipeline.pipelineLayout(),VK_SHADER_STAGE_COMPUTE_BIT,0,push);
        if(stage==21){VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT,VK_ACCESS_INDIRECT_COMMAND_READ_BIT,stack);vkCmdDispatchIndirect(command,arena.buffer().handle(),arena.offset()+(l.source==null?l.flow.column(VulkanGasFlow.Field.DISPATCH).offset():l.source.column(VulkanGasSchurGraph.Field.DISPATCH).offset())+72);}
        else if(stage==4||stage==9||stage==11){VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT,VK_ACCESS_INDIRECT_COMMAND_READ_BIT,stack);vkCmdDispatchIndirect(command,arena.buffer().handle(),arena.offset()+l.column(Field.STATE).offset()+(stage==9?48:32));}
        else{int groups=(stage==15||stage==16||stage==18||stage==20?1:(count+127)/128);vkCmdDispatch(command,Math.min(groups,65535),(groups+65534)/65535,1);}
        VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);}}
    private static long align(long n,long a){return Math.addExact(n,a-1)&-a;}
    private static String shader(){return resource("gas-flow-schur-pressure.comp");}
    private static String resource(String name){try(var in=VulkanGasFlowSchurPressure.class.getResourceAsStream("/assets/mmm_gpu_probe/shaders/"+name)){if(in==null)throw new IllegalStateException("Gas Schur shader missing: "+name);return new String(in.readAllBytes(),StandardCharsets.UTF_8);}catch(IOException e){throw new IllegalStateException("Gas Schur shader unavailable",e);}}
    @Override public void close(){RuntimeException failure=null;for(int i=owned.size()-1;i>=0;i--)try{owned.get(i).close();}catch(Exception closing){if(failure==null)failure=new IllegalStateException("Gas Schur close failed",closing);else failure.addSuppressed(closing);}owned.clear();if(failure!=null)throw failure;}
}

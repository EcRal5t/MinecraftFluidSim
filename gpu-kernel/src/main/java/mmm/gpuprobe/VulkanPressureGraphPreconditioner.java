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

/** A fixed symmetric hierarchy over the complete physical pressure graph. */
final class VulkanPressureGraphPreconditioner implements AutoCloseable {
    record Common(int liquidCount,VulkanLiquidStateLayout.Column offsets,VulkanLiquidStateLayout.Column peers,
                  VulkanLiquidStateLayout.Column weights,VulkanLiquidStateLayout.Column diagonal) { }
    record Source(int rows,VulkanLiquidStateLayout.Column starts,VulkanLiquidStateLayout.Column columns,
                  VulkanLiquidStateLayout.Column values,VulkanLiquidStateLayout.Column identities,
                  VulkanLiquidStateLayout.Column work,VulkanLiquidStateLayout.Column meta,
                  VulkanLiquidStateLayout.Column control,VulkanLiquidStateLayout.Column feedback,Common common) {
        Source(int rows,VulkanLiquidStateLayout.Column starts,VulkanLiquidStateLayout.Column columns,
               VulkanLiquidStateLayout.Column values,VulkanLiquidStateLayout.Column identities,
               VulkanLiquidStateLayout.Column work,VulkanLiquidStateLayout.Column meta,
               VulkanLiquidStateLayout.Column control,VulkanLiquidStateLayout.Column feedback){
            this(rows,starts,columns,values,identities,work,meta,control,feedback,null);
        }
    }

    private enum P { FLAGS,PREFIX,DEGREE,EDGE_OFFSETS,ACTUAL,SCAN,LONG_ROWS,LONG_COUNT,DISPATCH,PRESSURE_DISPATCH }
    private enum L { STARTS,COLUMNS,VALUES,DIAGONAL,ROW_KEY,SELECT,FLAGS,PREFIX,PARENT,
        PROPOSAL_COUNTS,PROPOSAL_OFFSETS,MEMBER_COUNTS,MEMBER_STARTS,MEMBER_CURSORS,MEMBERS,
        R,U,TEMP,FACTOR,LOW_FACTOR,ACTUAL,SCAN,DISPATCH,LEVEL_COUNTS,
        EDGE_ROWS,EDGE_FLAGS,EDGE_OFFSETS,TILE_COUNTS,TILE_OFFSETS,TILE_ROWS,LONG_ROWS,TILE_PARTIALS,
        MEMBER_TILE_COUNTS,MEMBER_TILE_OFFSETS,MEMBER_TILE_ROWS,MEMBER_LONG_ROWS,MEMBER_PARTIALS,
        ROW_FLOAT,ROW_DOUBLE,VECTOR,LOCAL_DISPATCH,JOIN }
    private enum S { PAIR_A,PAIR_B,VALUE,HASH,SLOT,FLAGS,PREFIX,COUNTS,OFFSETS,CURSORS,LIST,
        GROUP_A,GROUP_B,GROUP_VALUE,ACTUAL,SCAN,LONG_GROUPS,DISPATCH }
    private enum T { BASES,ANCESTORS,INVERSE,COUNTS,OFFSETS,CURSORS,MEMBERS,R,Z,TEMP,FACTOR,
        TILE_COUNTS,TILE_OFFSETS,TILE_ROWS,LONG_ROWS,PARTIALS,SCAN,DISPATCH }
    private static final int MAX_LEVELS=32;
    private static final class Memory<E extends Enum<E>> {
        final EnumMap<E,VulkanLiquidStateLayout.Column> columns;
        final GpuBuffer buffer;final long bytes;final int[] header=new int[128];
        Memory(Class<E> type,FlipGpuResources resources,long alignment,int[] counts,int[] widths,int headerBase){
            columns=new EnumMap<>(type);long cursor=512;int i=0;
            for(var f:type.getEnumConstants()){
                cursor=align(cursor,alignment);var c=new VulkanLiquidStateLayout.Column(cursor,counts[i],widths[i]);
                columns.put(f,c);header[headerBase+i]=Math.toIntExact(cursor/4);cursor+=c.bindingBytes();i++;
            }
            bytes=align(cursor,alignment);buffer=resources.addDeviceStorage(bytes);
        }
        VulkanLiquidStateLayout.Column column(E f){return columns.get(f);}
        int word(E f){return Math.toIntExact(column(f).offset()/4);}
        ByteBuffer headerBytes(){var out=ByteBuffer.allocateDirect(512).order(ByteOrder.LITTLE_ENDIAN);for(int i=0;i<header.length;i++)out.putInt(4*i,header[i]);return out;}
    }
    private static final class Level {
        final Memory<L> memory;final int nodeCapacity,edgeCapacity,memberCapacity;int nodes;boolean eliminating;
        Level(Memory<L> memory,int nodes,int edges,int members){this.memory=memory;nodeCapacity=nodes;edgeCapacity=edges;memberCapacity=members;}
    }
    private final VulkanDeviceContext context;
    private final FlipGpuResources resources;
    private FlipKernelBindings sourceBindings,graphBindings,factorBindings;
    private VulkanPrefixScan scan;
    private final List<Level> levels=new ArrayList<>();
    private Memory<P> prelude;private int preludeCapacity;
    private Memory<S> scratch;private int proposalCapacity,hashCapacity;
    private Memory<T> additive;private int additiveRows,additiveMembers,coreIndex,coreNodes;private boolean additiveReady;
    private Source source;private GpuBuffer sourceArena,errorBuffer;private int depth;
    private long uploads,copies;
    private final VulkanStageTimings timings;
    VulkanPressureGraphPreconditioner(VulkanDeviceContext context){
        this.context=context;resources=new FlipGpuResources(context);
        try{
            sourceBindings=new FlipKernelBindings(context,4,resource("pressure-graph-source.comp"),8);
            graphBindings=new FlipKernelBindings(context,5,resource("pressure-graph.comp"),32);
            factorBindings=new FlipKernelBindings(context,5,resource("pressure-graph.comp").replace("#version 450","#version 450\n#define FACTOR_ONLY"),32);
            scan=new VulkanPrefixScan(context);
        timings=Boolean.getBoolean("mmm.gpuPressureGraphProfile")?new VulkanStageTimings(context,32768):null;
        }catch(RuntimeException|LinkageError failure){try{close();}catch(RuntimeException closing){failure.addSuppressed(closing);}throw failure;}
    }
    private static long align(long value,long a){return (value+a-1)&-a;}
    private static int reserve(int n){int value=1;while(value<n)value=Math.multiplyExact(value,2);return value;}
    private static int word(VulkanLiquidStateLayout.Column c){return Math.toIntExact(c.offset()/4);}
    private Memory<P> prelude(int rows){
        if(prelude!=null&&preludeCapacity>=rows)return prelude;
        if(prelude!=null)resources.destroy(prelude.buffer);preludeCapacity=reserve(rows);
        int n=preludeCapacity;int[] count={n,n+1,n,n+1,2,Math.toIntExact(VulkanPrefixScan.workspaceBytes(n)/4),n,1,3,3},width={4,4,4,4,4,4,4,4,4,4};
        return prelude=new Memory<>(P.class,resources,context.storageOffsetAlignment(),count,width,32);
    }
    private Level level(int index,int nodes,int edges,int members){
        var old=index<levels.size()?levels.get(index):null;
        if(old!=null&&old.nodeCapacity>=nodes&&old.edgeCapacity>=edges&&old.memberCapacity>=members
                &&(nodes>64||old.memory.column(L.FACTOR).count()>=4096)){old.nodes=nodes;return old;}
        if(old!=null)resources.destroy(old.memory.buffer);
        int n=reserve(nodes),e=reserve(edges),m=reserve(members);
        int[] count=new int[L.values().length],width=new int[count.length];
        for(var f:L.values()){
            count[f.ordinal()]=switch(f){case STARTS,PREFIX,PROPOSAL_OFFSETS,MEMBER_STARTS,TILE_OFFSETS,MEMBER_TILE_OFFSETS->n+1;
                case COLUMNS,VALUES,EDGE_ROWS,EDGE_FLAGS->e;case EDGE_OFFSETS->e+1;
                case MEMBERS->m;case FACTOR,LOW_FACTOR->4096;
                case ACTUAL->2;case SCAN->Math.toIntExact(VulkanPrefixScan.workspaceBytes(Math.max(n,e))/4);
                case DISPATCH->15*MAX_LEVELS;case LEVEL_COUNTS->5*MAX_LEVELS;case LOCAL_DISPATCH->12;
                case TILE_ROWS,TILE_PARTIALS->Math.max(1,(e+63)/64);
                case MEMBER_TILE_ROWS,MEMBER_PARTIALS->Math.max(1,(m+63)/64);default->n;};
            width[f.ordinal()]=switch(f){case VALUES,DIAGONAL,LOW_FACTOR,TILE_PARTIALS,MEMBER_PARTIALS,ROW_DOUBLE->8;default->4;};
        }
        var result=new Level(new Memory<>(L.class,resources,context.storageOffsetAlignment(),count,width,16),n,e,m);result.nodes=nodes;
        if(index==levels.size())levels.add(result);else levels.set(index,result);return result;
    }
    private void scratch(int proposals){
        int h=reserve(Math.multiplyExact(2,Math.max(1,proposals)));
        if(scratch!=null&&proposalCapacity>=proposals&&hashCapacity>=h){scratch.header[0]=proposals;scratch.header[1]=h;return;}
        if(scratch!=null)resources.destroy(scratch.buffer);proposalCapacity=reserve(proposals);hashCapacity=h;
        int[] count=new int[S.values().length],width=new int[count.length];
        for(var f:S.values()){
            count[f.ordinal()]=switch(f){case HASH,FLAGS->h;case PREFIX->h+1;case OFFSETS->proposalCapacity+1;
                case ACTUAL->2;case DISPATCH->3;case SCAN->Math.toIntExact(VulkanPrefixScan.workspaceBytes(h)/4);default->proposalCapacity;};
            width[f.ordinal()]=f==S.VALUE||f==S.GROUP_VALUE?8:4;
        }
        scratch=new Memory<>(S.class,resources,context.storageOffsetAlignment(),count,width,16);scratch.header[0]=proposals;scratch.header[1]=h;
    }
    private void header(VkCommandBuffer command,Memory<?> m){var bytes=m.headerBytes();vkCmdUpdateBuffer(command,m.buffer.handle(),0,bytes);uploads+=bytes.remaining();transferBarrier(command);}
    private void transferBarrier(VkCommandBuffer command){try(var stack=stackPush()){VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);}}
    private void computeBarrier(VkCommandBuffer command){try(var stack=stackPush()){VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);}}
    private void feedback(VkCommandBuffer command,Memory<?> m,long offset){try(var stack=stackPush()){
        VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_READ_BIT,stack);
        VulkanRegionStorage.copy(command,m.buffer,offset,sourceArena,source.feedback.offset(),8,stack);copies+=8;
        transferBarrier(command);
    }}
    /** Records source compaction and copies [actual nodes, directed off-diagonal entries] to feedback. */
    void recordCount(VkCommandBuffer command,GpuBuffer arena,GpuBuffer errors,Source input){
        if(timings!=null)timings.reset(command);
        source=input;sourceArena=arena;errorBuffer=errors;var pre=prelude(input.rows);
        pre.header[0]=input.rows;pre.header[1]=input.common==null?-1:input.common.liquidCount;
        pre.header[2]=word(input.starts);pre.header[3]=word(input.columns);pre.header[4]=word(input.values);pre.header[5]=word(input.identities);
        pre.header[6]=word(input.work);pre.header[7]=word(input.meta);pre.header[8]=word(input.control);pre.header[9]=word(input.feedback);
        if(input.common!=null){pre.header[10]=word(input.common.offsets);pre.header[11]=word(input.common.peers);pre.header[12]=word(input.common.weights);pre.header[13]=word(input.common.diagonal);}
        vkCmdFillBuffer(command,pre.buffer.handle(),pre.column(P.LONG_COUNT).offset(),4,0);
        header(command,pre);sourceStage(command,pre,0,input.rows);
        sourceStage(command,pre,5,1);sourceStage(command,pre,3,input.rows);
        prefix(command,pre, input.rows,P.FLAGS,P.PREFIX,P.SCAN);
        prefix(command,pre, input.rows,P.DEGREE,P.EDGE_OFFSETS,P.SCAN);
        sourceStage(command,pre,1,1);feedback(command,pre,pre.column(P.ACTUAL).offset());
    }
    private void sourceStage(VkCommandBuffer command,Memory<?> root,int stage,int count){
        if(timings==null)sourceDispatch(command,root,stage,count);
        else timings.record(command,"source="+stage,()->sourceDispatch(command,root,stage,count));
        if(stage==2)sourceStage(command,root,4,count);
    }
    private void sourceDispatch(VkCommandBuffer command,Memory<?> root,int stage,int count){
        sourceBindings.rebindRanges(List.of(new FlipKernelBindings.BufferRange(sourceArena),new FlipKernelBindings.BufferRange(prelude.buffer),new FlipKernelBindings.BufferRange(root.buffer),new FlipKernelBindings.BufferRange(errorBuffer)));
        try(var stack=stackPush()){
            sourceBindings.bind(command,stack);vkCmdPushConstants(command,sourceBindings.pipelineLayout(),VK_SHADER_STAGE_COMPUTE_BIT,0,stack.ints(stage,count));
            if(stage==3||stage==4){
                VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT,VK_ACCESS_INDIRECT_COMMAND_READ_BIT,stack);
                vkCmdDispatchIndirect(command,prelude.buffer.handle(),prelude.column(P.DISPATCH).offset());
            }else vkCmdDispatch(command,Math.max(1,(count+127)/128),1,1);
        }computeBarrier(command);
    }
    private <E extends Enum<E>> void prefix(VkCommandBuffer command,Memory<E> memory,int count,E input,E output,E workspace){
        var range=new FlipKernelBindings.BufferRange(memory.buffer);scan.rebindRanges(range,range,range,new FlipKernelBindings.BufferRange(errorBuffer));
        scan.record(command,count,memory.word(input),memory.word(output),memory.word(workspace));
    }
    private FlipKernelBindings bind(VkCommandBuffer command,Level fine,Level coarse,boolean building,boolean factor){
        var a=new FlipKernelBindings.BufferRange(fine.memory.buffer);var b=new FlipKernelBindings.BufferRange(coarse.memory.buffer);
        var bindings=factor?factorBindings:graphBindings;
        bindings.rebindRanges(List.of(a,b,building?new FlipKernelBindings.BufferRange(scratch.buffer):a,new FlipKernelBindings.BufferRange(sourceArena),new FlipKernelBindings.BufferRange(errorBuffer)));
        try(var stack=stackPush()){bindings.bind(command,stack);}return bindings;
    }
    private void stage(VkCommandBuffer command,Level fine,Level coarse,int stage,int count,int mode,boolean building,int indirect){
        if(timings==null)recordStage(command,fine,coarse,stage,count,mode,building,indirect);
        else timings.record(command,"stage="+stage+" rows="+fine.nodes+" coarse="+coarse.nodes,()->recordStage(command,fine,coarse,stage,count,mode,building,indirect));
    }
    private void recordStage(VkCommandBuffer command,Level fine,Level coarse,int stage,int count,int mode,boolean building,int indirect){
        var bindings=bind(command,fine,coarse,building,stage==25||stage==26);
        if(stage>=56){bindings.rebindRanges(List.of(new FlipKernelBindings.BufferRange(fine.memory.buffer),new FlipKernelBindings.BufferRange(coarse.memory.buffer),
                new FlipKernelBindings.BufferRange(additive.buffer),new FlipKernelBindings.BufferRange(sourceArena),new FlipKernelBindings.BufferRange(errorBuffer)));try(var stack=stackPush()){bindings.bind(command,stack);}}
        try(var stack=stackPush()){
            vkCmdPushConstants(command,bindings.pipelineLayout(),VK_SHADER_STAGE_COMPUTE_BIT,0,
                    stack.ints(stage,count,mode,word(source.work),word(source.meta),word(source.control),source.rows,indirect));
            if(indirect!=-1){VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT,VK_ACCESS_INDIRECT_COMMAND_READ_BIT,stack);
                if(indirect<=-7)vkCmdDispatchIndirect(command,additive.buffer.handle(),additive.column(T.DISPATCH).offset()+12L*(-7-indirect));
                else if(indirect==-6)vkCmdDispatchIndirect(command,scratch.buffer.handle(),scratch.column(S.DISPATCH).offset());
                else if(indirect<=-2){var local=indirect<=-4?coarse:fine;vkCmdDispatchIndirect(command,local.memory.buffer.handle(),local.memory.column(L.LOCAL_DISPATCH).offset()+12L*(-2-indirect));}
                else
                vkCmdDispatchIndirect(command,levels.getFirst().memory.buffer.handle(),levels.getFirst().memory.column(L.DISPATCH).offset()+12L*indirect);
            }else vkCmdDispatch(command,Math.max(1,(count+127)/128),1,1);
        }
        computeBarrier(command);
    }
    private void prepareLevel(VkCommandBuffer command,Level level,int index){
        level.eliminating=index==0;
        if(index==0){
            stage(command,level,level,0,level.nodes,0,false,-1);
            prefix(command,level.memory,level.nodes,L.FLAGS,L.PREFIX,L.SCAN);
            stage(command,level,level,30,level.nodes,0,false,-1);
        }else{
            stage(command,level,level,2,level.nodes,1,false,-1);stage(command,level,level,3,level.nodes,1,false,-1);
            stage(command,level,level,55,level.nodes,1,false,-1);
            prefix(command,level.memory,level.nodes,L.FLAGS,L.PREFIX,L.SCAN);
            stage(command,level,level,4,level.nodes,1,false,-1);stage(command,level,level,30,level.nodes,1,false,-1);
        }
        stage(command,level,level,37,level.edgeCapacity,index==0?0:1,false,-1);
        prefix(command,level.memory,level.edgeCapacity,L.EDGE_FLAGS,L.EDGE_OFFSETS,L.SCAN);
        prefix(command,level.memory,level.nodes,L.PROPOSAL_COUNTS,L.PROPOSAL_OFFSETS,L.SCAN);
        stage(command,level,level,1,1,index==0?0:1,false,-1);feedback(command,level.memory,level.memory.column(L.ACTUAL).offset());
    }
    private void initializeLevel(VkCommandBuffer command,Level level,int edges){
        level.memory.header[0]=level.nodes;level.memory.header[1]=edges;level.memory.header[2]=0;level.memory.header[3]=0;level.memory.header[4]=0;
        level.memory.header[6]=0;level.memory.header[7]=0;level.memory.header[8]=level.edgeCapacity;header(command,level.memory);
    }
    private void rowStructure(VkCommandBuffer command,Level level){
        stage(command,level,level,32,level.nodes,0,false,-1);
        prefix(command,level.memory,level.nodes,L.TILE_COUNTS,L.TILE_OFFSETS,L.SCAN);
        stage(command,level,level,33,level.nodes,0,false,-1);
        stage(command,level,level,36,0,0,false,-2);
    }
    private void memberStructure(VkCommandBuffer command,Level level){
        stage(command,level,level,34,level.nodes,0,false,-1);
        prefix(command,level.memory,level.nodes,L.MEMBER_TILE_COUNTS,L.MEMBER_TILE_OFFSETS,L.SCAN);
        stage(command,level,level,35,level.nodes,0,false,-1);
    }
    private void rowProduct(VkCommandBuffer command,Level fine,Level coarse,int task,int index,boolean building){
        int rows=building?-1:5*index,tiles=building?-2:5*index+1,longRows=building?-3:5*index+2;
        if(task==0)stage(command,fine,coarse,39,fine.nodes,task,false,rows);
        stage(command,fine,coarse,40,fine.nodes,task,false,rows);
        stage(command,fine,coarse,41,0,task,false,tiles);
        stage(command,fine,coarse,42,0,task,false,longRows);
    }
    private void memberReduce(VkCommandBuffer command,Level fine,Level coarse,int task,int index,boolean building){
        stage(command,fine,coarse,45,coarse.nodes,task,false,building?-1:5*index);
        stage(command,fine,coarse,46,0,task,false,building?-4:5*index+3);
        stage(command,fine,coarse,47,0,task,false,building?-5:5*index+4);
    }
    private void additiveMemory(int rows,int members){
        if(additive!=null&&additiveRows>=rows&&additiveMembers>=members)return;
        if(additive!=null)resources.destroy(additive.buffer);additiveRows=reserve(rows);additiveMembers=reserve(members);
        int[] count=new int[T.values().length],width=new int[count.length];
        for(var f:T.values()){
            count[f.ordinal()]=switch(f){case BASES->MAX_LEVELS;case ANCESTORS,MEMBERS->additiveMembers;
                case OFFSETS,TILE_OFFSETS->additiveRows+1;case FACTOR->4096;case TEMP->64;
                case TILE_ROWS,PARTIALS->Math.max(1,(additiveMembers+63)/64);
                case SCAN->Math.toIntExact(VulkanPrefixScan.workspaceBytes(additiveRows)/4);case DISPATCH->15;default->additiveRows;};
            width[f.ordinal()]=4;
        }
        additive=new Memory<>(T.class,resources,context.storageOffsetAlignment(),count,width,16);
    }
    /** One core residual feeds every Galerkin level; positive spectral-normalized diagonal terms add. */
    private void buildAdditive(VkCommandBuffer command){
        coreIndex=levels.getFirst().eliminating&&depth>0?1:0;coreNodes=levels.get(coreIndex).nodes;additiveReady=depth>coreIndex;
        if(!additiveReady)return;
        int layers=depth-coreIndex+1,total=0;int[] bases=new int[layers];
        for(int i=0;i<layers;i++){bases[i]=total;total=Math.addExact(total,levels.get(coreIndex+i).nodes);}
        int memberships=Math.multiplyExact(coreNodes,layers);additiveMemory(total,memberships);
        var last=levels.get(depth);additive.header[0]=coreNodes;additive.header[1]=total;additive.header[2]=layers;
        additive.header[3]=bases[layers-1];additive.header[4]=last.nodes;additive.header[5]=0;additive.header[6]=0;additive.header[7]=last.nodes<=64?1:0;
        header(command,additive);var bytes=ByteBuffer.allocateDirect(4*layers).order(ByteOrder.LITTLE_ENDIAN);for(int i=0;i<layers;i++)bytes.putInt(4*i,bases[i]);
        vkCmdUpdateBuffer(command,additive.buffer.handle(),additive.column(T.BASES).offset(),bytes);uploads+=bytes.remaining();transferBarrier(command);
        var core=levels.get(coreIndex);stage(command,core,core,58,total,0,false,-1);
        for(int i=0;i<layers;i++){
            var level=levels.get(coreIndex+i);var previous=levels.get(coreIndex+Math.max(0,i-1));
            stage(command,previous,level,57,coreNodes,i,false,-1);
            stage(command,level,level,61,level.nodes,i,false,-1);
        }
        stage(command,core,core,59,memberships,0,false,-1);prefix(command,additive,total,T.COUNTS,T.OFFSETS,T.SCAN);
        stage(command,core,core,60,memberships,0,false,-1);stage(command,core,core,62,total,0,false,-1);
        prefix(command,additive,total,T.TILE_COUNTS,T.TILE_OFFSETS,T.SCAN);stage(command,core,core,63,total,0,false,-1);
        if(last.nodes<=64)try(var stack=stackPush()){
            VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_READ_BIT,stack);
            VulkanRegionStorage.copy(command,last.memory.buffer,last.memory.column(L.FACTOR).offset(),additive.buffer,additive.column(T.FACTOR).offset(),16384,stack);copies+=16384;transferBarrier(command);
        }
    }
    /** Builds once per physical operator; no hierarchy application performs a host read. */
    int build(VulkanRegionStorage.Candidate candidate,int nodes,int edges){
        depth=0;var root=level(0,nodes,edges,1);
        var status=candidate.continueBatch((command,input,output,errors,staging,stack)->{
            initializeLevel(command,root,edges);sourceStage(command,root.memory,2,source.rows);rowStructure(command,root);prepareLevel(command,root,0);
        },source.feedback.offset(),8);
        if(status.error()!=0)return status.error();
        int retained=status.feedback().getInt(0),proposals=status.feedback().getInt(4);
        if(root.nodes>64&&retained>=root.nodes&&proposals>0){
            status=candidate.continueBatch((command,input,output,errors,staging,stack)->prepareLevel(command,root,1),source.feedback.offset(),8);
            if(status.error()!=0)return status.error();retained=status.feedback().getInt(0);proposals=status.feedback().getInt(4);
        }
        while(true){
            var fine=levels.get(depth);
            if(fine.nodes<=64||retained==0||retained>=fine.nodes)break;
            if(depth+1>=MAX_LEVELS)throw new IllegalStateException("Pressure graph aggregation did not reduce the physical graph");
            int mode=fine.eliminating?0:1;
            // A diagonal or disconnected source can retain no unknowns after exact elimination.
            var coarse=level(depth+1,retained,Math.multiplyExact(2,proposals),fine.nodes);scratch(proposals);
            int currentDepth=depth;int count=proposals;
            status=candidate.continueBatch((command,input,output,errors,staging,stack)->{
                initializeLevel(command,coarse,0);header(command,scratch);
                if(mode==0)stage(command,fine,coarse,5,fine.nodes,mode,false,-1);
                stage(command,fine,coarse,6,Math.max(scratch.header[1],count),mode,true,-1);
                stage(command,fine,coarse,7,fine.edgeCapacity,mode,true,-1);stage(command,fine,coarse,38,fine.nodes,mode,true,-1);
                stage(command,fine,coarse,8,count,mode,true,-1);stage(command,fine,coarse,9,scratch.header[1],mode,true,-1);
                prefix(command,scratch,scratch.header[1],S.FLAGS,S.PREFIX,S.SCAN);
                stage(command,fine,coarse,10,count,mode,true,-1);
                prefix(command,scratch,count,S.COUNTS,S.OFFSETS,S.SCAN);
                stage(command,fine,coarse,11,count,mode,true,-1);stage(command,fine,coarse,52,count,mode,true,-1);
                stage(command,fine,coarse,12,count,mode,true,-1);stage(command,fine,coarse,53,0,mode,true,-6);
                stage(command,fine,coarse,13,coarse.nodes,mode,true,-1);stage(command,fine,coarse,14,count,mode,true,-1);
                prefix(command,coarse.memory,coarse.nodes,L.FLAGS,L.STARTS,L.SCAN);
                stage(command,fine,coarse,15,count,mode,true,-1);stage(command,fine,coarse,16,Math.max(fine.nodes,coarse.nodes),mode,true,-1);
                prefix(command,coarse.memory,coarse.nodes,L.MEMBER_COUNTS,L.MEMBER_STARTS,L.SCAN);
                stage(command,fine,coarse,17,fine.nodes,mode,true,-1);rowStructure(command,coarse);memberStructure(command,coarse);
                rowProduct(command,fine,coarse,2,0,true);memberReduce(command,fine,coarse,2,0,true);
                rowProduct(command,coarse,coarse,3,0,true);stage(command,coarse,coarse,51,coarse.nodes,0,false,-1);
                prepareLevel(command,coarse,currentDepth+1);
            },source.feedback.offset(),8);
            if(status.error()!=0)return status.error();depth++;retained=status.feedback().getInt(0);proposals=status.feedback().getInt(4);
        }
        var last=levels.get(depth);int builtDepth=depth+1;
        status=candidate.continueBatch((command,input,output,errors,staging,stack)->{
            if(last.nodes<=64){stage(command,last,last,25,128,0,false,-1);stage(command,last,last,26,64,0,false,-1);}
            buildAdditive(command);
            var counts=ByteBuffer.allocateDirect(20*builtDepth).order(ByteOrder.LITTLE_ENDIAN);for(int i=0;i<builtDepth;i++)counts.putInt(20*i,levels.get(i).nodes);
            var countHeader=ByteBuffer.allocateDirect(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0,builtDepth);
            vkCmdUpdateBuffer(command,root.memory.buffer.handle(),20,countHeader);
            vkCmdUpdateBuffer(command,root.memory.buffer.handle(),root.memory.column(L.LEVEL_COUNTS).offset(),counts);uploads+=4L+counts.remaining();transferBarrier(command);
            VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,VK_ACCESS_MEMORY_WRITE_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_READ_BIT|VK_ACCESS_TRANSFER_WRITE_BIT,stack);
            for(int i=0;i<builtDepth;i++){
                var level=levels.get(i);long target=root.memory.column(L.LEVEL_COUNTS).offset()+20L*i;
                VulkanRegionStorage.copy(command,level.memory.buffer,level.memory.column(L.TILE_OFFSETS).offset()+4L*level.nodes,root.memory.buffer,target+4,4,stack);
                VulkanRegionStorage.copy(command,level.memory.buffer,24,root.memory.buffer,target+8,4,stack);copies+=8;
                if(i>0){VulkanRegionStorage.copy(command,level.memory.buffer,level.memory.column(L.MEMBER_TILE_OFFSETS).offset()+4L*level.nodes,root.memory.buffer,target+12,4,stack);
                    VulkanRegionStorage.copy(command,level.memory.buffer,28,root.memory.buffer,target+16,4,stack);copies+=8;}
            }
            transferBarrier(command);
        },0,0);
        if(timings!=null||Boolean.getBoolean("mmm.gpuMechanicalStages"))System.out.printf(java.util.Locale.ROOT,"common_graph levels=%d rows=%s device_MiB=%.3f%n",builtDepth,levels.subList(0,builtDepth).stream().map(l->l.nodes).toList(),deviceBytes()/1048576.0);
        return status.error();
    }
    private void recordApply(VkCommandBuffer command){
        var root=levels.getFirst();var core=levels.get(coreIndex);
        stage(command,root,root,additiveReady?70:20,1,0,false,-1);stage(command,root,root,21,root.nodes,0,false,0);
        if(coreIndex>0){rowProduct(command,root,core,0,0,false);stage(command,root,core,54,core.nodes,0,false,5*coreIndex);}
        if(additiveReady){
            stage(command,core,core,64,0,0,false,-7);stage(command,core,core,65,0,0,false,-8);stage(command,core,core,66,0,0,false,-9);
            stage(command,core,core,67,0,0,false,-11);stage(command,core,core,68,0,0,false,-11);stage(command,core,core,69,0,0,false,-10);
        }else{stage(command,core,core,27,core.nodes,0,false,5*coreIndex);stage(command,core,core,28,core.nodes,0,false,5*coreIndex);}
        if(coreIndex>0)stage(command,root,core,24,root.nodes,0,false,0);
        stage(command,root,root,22,root.nodes,0,false,0);
    }
    VulkanPressureKernel.PressureOperator operator(VulkanPressureKernel.PressureOperator physical){
        var identity=new CacheIdentity(source,new FlipKernelBindings.BufferIdentity(prelude.buffer),levels.subList(0,depth+1).stream().map(l->new FlipKernelBindings.BufferIdentity(l.memory.buffer)).toList(),
                additiveReady?new FlipKernelBindings.BufferIdentity(additive.buffer):null,coreIndex,depth,levels.getFirst().eliminating,additiveReady);
        return new VulkanPressureKernel.PressureOperator(){
            public int liquidCount(){return physical==null?source.rows:physical.liquidCount();}
            public int matrixFreeFromRow(){return physical==null?source.rows:physical.matrixFreeFromRow();}
            public void recordProduct(VkCommandBuffer command,boolean direction){if(physical!=null)physical.recordProduct(command,direction);}
            public FlipKernelBindings.BufferRange productDispatch(){return physical==null?null:physical.productDispatch();}
            public boolean recordRecoverOriginal(VkCommandBuffer command){return physical!=null&&physical.recordRecoverOriginal(command);}
            public boolean hasPreconditioner(){return true;}
            public VulkanPressureKernel.ParallelRows parallelRows(){
                // The physical operator owns the Schur core and restores its full row list at completion.
                if(physical!=null&&physical.parallelRows()!=null)return physical.parallelRows();
                var root=levels.getFirst().memory;var keys=root.column(L.ROW_KEY);
                return new VulkanPressureKernel.ParallelRows(new FlipKernelBindings.BufferRange(root.buffer,keys.offset(),keys.bindingBytes()),
                        prelude.buffer,prelude.column(P.PRESSURE_DISPATCH).offset());
            }
            public void recordPreconditioner(VkCommandBuffer command){recordApply(command);}
            public Object preconditionerKey(){return identity;}
        };
    }
    private record CacheIdentity(Source source,FlipKernelBindings.BufferIdentity prelude,List<FlipKernelBindings.BufferIdentity> levels,FlipKernelBindings.BufferIdentity additive,int core,int depth,boolean eliminating,boolean additiveReady) { }
    void reportTimings(){
        if(timings==null)return;var totals=new java.util.LinkedHashMap<String,Long>();
        for(var sample:timings.read())totals.merge(sample.stage(),sample.nanos(),Long::sum);
        totals.forEach((label,nanos)->System.out.printf(java.util.Locale.ROOT,"pressure_graph %s gpu_ms=%.3f%n",label,nanos*1e-6));
    }
    long deviceBytes(){return (prelude==null?0:prelude.bytes)+(scratch==null?0:scratch.bytes)+(additive==null?0:additive.bytes)+levels.stream().mapToLong(l->l.memory.bytes).sum();}
    long uploadBytes(){return uploads;}
    long copyBytes(){return copies;}
    private static String resource(String name){
        try(var in=VulkanPressureGraphPreconditioner.class.getResourceAsStream("/assets/mmm_gpu_probe/shaders/"+name)){
            if(in==null)throw new IllegalStateException("Missing pressure graph shader "+name);
            var defines=new StringBuilder();for(var f:P.values())defines.append("#define P_").append(f.name()).append(' ').append(f.ordinal()).append('\n');
            for(var f:L.values())defines.append("#define L_").append(f.name()).append(' ').append(f.ordinal()).append('\n');
            for(var f:S.values())defines.append("#define S_").append(f.name()).append(' ').append(f.ordinal()).append('\n');
            for(var f:T.values())defines.append("#define T_").append(f.name()).append(' ').append(f.ordinal()).append('\n');
            return new String(in.readAllBytes(),StandardCharsets.UTF_8).replace("/*FIELDS*/",defines);
        }catch(IOException failure){throw new IllegalStateException("Loading pressure graph shader",failure);}
    }
    @Override public void close(){try{if(timings!=null)timings.close();if(scan!=null)scan.close();if(sourceBindings!=null)sourceBindings.close();if(graphBindings!=null)graphBindings.close();if(factorBindings!=null)factorBindings.close();}finally{resources.close();}}
}

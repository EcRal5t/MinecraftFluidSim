package mmm.gpuprobe;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.List;
import org.lwjgl.vulkan.VkCommandBuffer;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Exact small solve on the final three-dimensional geometric aggregate space. */
final class VulkanGasFlowCoarseCorrection implements AutoCloseable {
    record Layout(VulkanGasFlow.Layout flow,int sx,int sy,int sz,int shift,int rows,
                  VulkanLiquidStateLayout.Column parent,VulkanLiquidStateLayout.Column solution,
                  VulkanLiquidStateLayout.Column counts,VulkanLiquidStateLayout.Column offsets,
                  VulkanLiquidStateLayout.Column members,VulkanLiquidStateLayout.Column cursors,
                  VulkanLiquidStateLayout.Column conductance,VulkanLiquidStateLayout.Column storage,
                  VulkanLiquidStateLayout.Column factor,VulkanLiquidStateLayout.Column rhs,
                  VulkanLiquidStateLayout.Column scan,long endOffset){
        void writeHeader(ByteBuffer header){int[] values={word(parent),word(solution),word(counts),word(offsets),word(members),word(cursors),word(conductance),word(storage),word(factor),word(rhs),sx,sy,sz,shift,rows};
            for(int i=0;i<values.length;i++)header.putInt(4*(11+i),values[i]);}
    }
    static Layout layout(VulkanGasFlow.Layout flow,long base,long alignment){int sx=flow.geometry.totals().source().sizeX(),sy=flow.geometry.totals().source().sizeY(),sz=flow.geometry.totals().source().sizeZ(),shift=0;
        while(Math.max(sx,Math.max(sy,sz))>4){sx=(sx+1)/2;sy=(sy+1)/2;sz=(sz+1)/2;shift++;}
        int rows=sx*sy*sz;long[] cursor={base};var parent=column(cursor,flow.nodes,4,alignment);var solution=column(cursor,rows,4,alignment);
        var counts=column(cursor,rows,4,alignment);var offsets=column(cursor,rows+1,4,alignment);var members=column(cursor,flow.nodes,4,alignment);var cursors=column(cursor,rows,4,alignment);
        var conductance=column(cursor,rows*(rows+1),4,alignment);var storage=column(cursor,rows,8,alignment);var factor=column(cursor,4096,4,alignment);var rhs=column(cursor,rows,4,alignment);
        var scan=column(cursor,Math.toIntExact(VulkanPrefixScan.workspaceBytes(rows)/4),4,alignment);
        return new Layout(flow,sx,sy,sz,shift,rows,parent,solution,counts,offsets,members,cursors,conductance,storage,factor,rhs,scan,align(cursor[0],alignment));}
    private final FlipKernelBindings buildBindings,applyBindings;private final VulkanPrefixScan scan;private FlipKernelBindings.BufferRange arena;
    VulkanGasFlowCoarseCorrection(VulkanDeviceContext context){buildBindings=new FlipKernelBindings(context,2,shader(true),12);try{applyBindings=new FlipKernelBindings(context,2,shader(false),12);}catch(RuntimeException|Error e){buildBindings.close();throw e;}try{scan=new VulkanPrefixScan(context);}catch(RuntimeException|Error e){applyBindings.close();buildBindings.close();throw e;}}
    void rebindRanges(FlipKernelBindings.BufferRange arena,FlipKernelBindings.BufferRange errors){this.arena=arena;buildBindings.rebindRanges(List.of(arena,errors));applyBindings.rebindRanges(List.of(arena,errors));scan.rebindRanges(arena,arena,arena,errors);}
    void recordBuild(VkCommandBuffer command,Layout l,int header){stage(command,l,header,0,l.rows()*(l.rows()+1),false);stage(command,l,header,1,l.flow().nodes,false);
        scan.record(command,l.rows(),word(l.counts()),word(l.offsets()),word(l.scan()));stage(command,l,header,2,l.flow().nodes,false);
        stage(command,l,header,3,l.rows(),true);stage(command,l,header,4,l.flow().nodes,false);stage(command,l,header,5,0,true);stage(command,l,header,6,1,true);}
    void recordApply(VkCommandBuffer command,Layout l,int header){stage(command,l,header,7,l.rows(),true);stage(command,l,header,8,1,true);}
    private void stage(VkCommandBuffer command,Layout l,int header,int stage,int count,boolean groups){try(var stack=stackPush()){
        var bindings=stage<=6?buildBindings:applyBindings;bindings.bind(command,stack);vkCmdPushConstants(command,bindings.pipelineLayout(),VK_SHADER_STAGE_COMPUTE_BIT,0,stack.ints(header,stage,count));
        if(stage==7||stage==8)new VulkanPressureKernel.Dispatch(arena.buffer(),arena.offset()+l.flow().column(VulkanGasFlow.Field.CONTROL).offset()).record(command,stage==7?11:1);
        else if(stage==5){VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT,VK_ACCESS_INDIRECT_COMMAND_READ_BIT,stack);vkCmdDispatchIndirect(command,arena.buffer().handle(),arena.offset()+l.flow().column(VulkanGasFlow.Field.DISPATCH).offset()+72);}
        else{int n=groups?count:Math.max(1,(count+127)/128);vkCmdDispatch(command,Math.min(n,65535),(n+65534)/65535,1);}
        VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);}}
    private static int word(VulkanLiquidStateLayout.Column c){return Math.toIntExact(c.offset()/4);}private static long align(long n,long a){return Math.addExact(n,a-1)&-a;}
    private static VulkanLiquidStateLayout.Column column(long[] cursor,int n,int width,long a){var c=new VulkanLiquidStateLayout.Column(align(cursor[0],a),n,width);cursor[0]=c.offset()+c.bindingBytes();return c;}
    private static String shader(boolean build){try(var in=VulkanGasFlowCoarseCorrection.class.getResourceAsStream("/assets/mmm_gpu_probe/shaders/gas-flow-coarse-correction.comp")){
        if(in==null)throw new IllegalStateException("Gas geometric coarse shader missing");String source=new String(in.readAllBytes(),StandardCharsets.UTF_8);return build?source.replace("#version 450","#version 450\n#define COARSE_BUILD"):source;
    }catch(IOException e){throw new IllegalStateException("Gas geometric coarse shader unavailable",e);}}
    @Override public void close(){try{scan.close();}finally{try{applyBindings.close();}finally{buildBindings.close();}}}
}

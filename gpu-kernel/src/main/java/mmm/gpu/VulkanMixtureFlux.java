package mmm.gpu;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import org.lwjgl.vulkan.VkCommandBuffer;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Finite-step donor and receiver allocation for the shared liquid and gas MAC face velocity. */
final class VulkanMixtureFlux implements AutoCloseable {
    static final class Layout {
        final int sx, sy, sz, cells, faces;
        final VulkanLiquidStateLayout.Column header, scale, acceptedVelocity, control;
        final VulkanLiquidStateLayout.Column faceGeometry, fluxes, mixture, masks, targetSolid, gasStates;
        final boolean gasEnabled;
        final boolean sealedExterior;
        final long endOffset;
        Layout(int sx,int sy,int sz,VulkanLiquidStateLayout.Column faceGeometry,VulkanLiquidStateLayout.Column fluxes,
               boolean gasEnabled,VulkanLiquidStateLayout.Column mixture,
               VulkanLiquidStateLayout.Column masks,VulkanLiquidStateLayout.Column targetSolid,VulkanLiquidStateLayout.Column gasStates,boolean sealedExterior,long base,long alignment) {
            this.sx=sx;this.sy=sy;this.sz=sz;this.cells=Math.multiplyExact(Math.multiplyExact(sx,sy),sz);
            this.faces=Math.addExact(Math.addExact((sx+1)*sy*sz,sx*(sy+1)*sz),sx*sy*(sz+1));
            this.faceGeometry=faceGeometry;this.fluxes=fluxes;this.gasEnabled=gasEnabled;
            this.mixture=mixture;this.masks=masks;this.targetSolid=targetSolid;this.gasStates=gasStates;this.sealedExterior=sealedExterior;
            long cursor=align(base,alignment);header=new VulkanLiquidStateLayout.Column(cursor,32,4);cursor=header.offset()+header.bindingBytes();
            scale=new VulkanLiquidStateLayout.Column(align(cursor,alignment),2*cells,8);cursor=scale.offset()+scale.bindingBytes();
            acceptedVelocity=new VulkanLiquidStateLayout.Column(align(cursor,alignment),faces,8);cursor=acceptedVelocity.offset()+acceptedVelocity.bindingBytes();
            control=new VulkanLiquidStateLayout.Column(align(cursor,alignment),2,4);cursor=control.offset()+control.bindingBytes();
            endOffset=align(cursor,alignment);
        }
        VulkanLiquidStateLayout.Column header(){return header;}
        VulkanLiquidStateLayout.Column control(){return control;}
        long endOffset(){return endOffset;}
        ByteBuffer headerBytes(){
            var b=ByteBuffer.allocateDirect(128).order(ByteOrder.LITTLE_ENDIAN);
            int[] values={sx,sy,sz,cells,faces,0,word(faceGeometry),word(fluxes),gasEnabled?1:-1,word(mixture),
                0,0,0,word(scale),word(acceptedVelocity),0,0,sealedExterior?1:0,0,word(control),word(masks),word(targetSolid),gasStates==null?-1:word(gasStates)};
            for(int i=0;i<values.length;i++)b.putInt(i*4,values[i]);return b;
        }
        private static int word(VulkanLiquidStateLayout.Column c){return Math.toIntExact(c.offset()/4);}
        private static long align(long value,long alignment){return Math.addExact(value,alignment-1)&-alignment;}
    }
    private final FlipKernelBindings bindings;
    VulkanMixtureFlux(VulkanDeviceContext context){
        String shader=VulkanLiquidPhaseRows.resource("mixture-flux.comp")
                .replace("// LOCAL_GAS_GRID",VulkanLiquidPhaseRows.resource("local-gas-grid.glsl"))
                .replace("// VOLUME_RESOLUTION", "#define VOLUME_RESOLUTION "+mmm.inventory.PhaseOccupancyState.VOLUME_RESOLUTION+"LF\n");
        bindings=new FlipKernelBindings(context,2,shader,32);
    }
    void rebindRanges(FlipKernelBindings.BufferRange arena,FlipKernelBindings.BufferRange errors){bindings.rebindRanges(List.of(arena,errors));}
    void recordAnalyze(VkCommandBuffer command,Layout layout,double h,double dt){
        try(var stack=stackPush()){
            bindings.bind(command,stack);var push=stack.malloc(32);
            stage(command,layout,1,1,h,dt,stack,push);
            stage(command,layout,2,layout.cells,h,dt,stack,push);
            stage(command,layout,3,layout.faces,h,dt,stack,push);
        }
    }
    private void stage(VkCommandBuffer command,Layout layout,int stage,int count,double h,double dt,
                       org.lwjgl.system.MemoryStack stack,ByteBuffer push){
        if(count==0)return;
        push.clear().putInt(Math.toIntExact(layout.header.offset()/4)).putInt(stage).putInt(count).putInt(0).putDouble(h).putDouble(dt).flip();
        vkCmdPushConstants(command,bindings.pipelineLayout(),VK_SHADER_STAGE_COMPUTE_BIT,0,push);
        int groups=(count+127)/128;vkCmdDispatch(command,Math.min(groups,65535),(groups+65534)/65535,1);
        VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);
    }
    @Override public void close(){bindings.close();}
}

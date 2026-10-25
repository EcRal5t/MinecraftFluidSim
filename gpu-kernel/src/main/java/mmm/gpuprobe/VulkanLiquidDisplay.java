package mmm.gpuprobe;

import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Derived display fields in independent buffers, read from one retained committed generation. */
final class VulkanLiquidDisplay implements AutoCloseable {
    private final VulkanDeviceContext context;private final FlipGpuResources resources;private final FlipKernelBindings bindings;
    private GpuStepScheduler scheduler;private GpuBuffer output;private long capacity;
    VulkanLiquidDisplay(VulkanDeviceContext context){this.context=context;resources=new FlipGpuResources(context);
        try(var in=getClass().getResourceAsStream("/assets/mmm_gpu_probe/shaders/liquid-display.comp")){
            if(in==null)throw new IllegalStateException("Liquid display shader missing");
            bindings=new FlipKernelBindings(context,2,new String(in.readAllBytes(),StandardCharsets.UTF_8),48);
        }catch(java.io.IOException|RuntimeException|Error failure){resources.close();throw new IllegalStateException(failure);}
    }
    synchronized mmm.fluid.FluidMaterialRenderSnapshot capture(VulkanLiquidRegion.Capture capture,mmm.fluid.FluidMaterialRegistry fluids,float h){
        var input=capture.readoutInputs();var l=input.liquid();int cells=l.cellCount(),m=fluids.size();
        int plane=0,layers=4*cells,table=layers+4*cells*m;long bytes=4L*(table+m);
        var order=new java.util.ArrayList<Integer>();for(int material=0;material<m;material++)order.add(material);order.sort(java.util.Comparator.<Integer>comparingDouble(material->-fluids.material(material).referenceDensity()).thenComparingInt(Integer::intValue));
        if(bytes>capacity){if(scheduler!=null)scheduler.close();if(output!=null)resources.destroy(output);output=resources.addDeviceStorage(bytes);scheduler=new GpuStepScheduler(context,resources,bytes);capacity=bytes;}
        bindings.rebindRanges(List.of(new FlipKernelBindings.BufferRange(capture.buffer()),new FlipKernelBindings.BufferRange(output)));
        try(var stack=stackPush()){
            var submission=scheduler.acquire();var cmd=scheduler.begin(submission,"liquid-display");
            var orderBytes=stack.malloc(4*m);for(int material:order)orderBytes.putInt(material);orderBytes.flip();vkCmdUpdateBuffer(cmd,output.handle(),4L*table,orderBytes);
            VulkanRegionStorage.barrier(cmd,VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,VK_ACCESS_MEMORY_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT,stack);
            bindings.bind(cmd,stack);vkCmdPushConstants(cmd,bindings.pipelineLayout(),VK_SHADER_STAGE_COMPUTE_BIT,0,stack.ints(cells,m,
                word(l.column(VulkanLiquidStateLayout.Field.CELL_OFFSETS)),word(l.column(VulkanLiquidStateLayout.Field.MATERIALS)),word(l.column(VulkanLiquidStateLayout.Field.NUMERIC_VOLUMES)),
                word(input.geometry().get(VulkanLiquidRegion.GeometryField.PLANES)),word(input.geometry().get(VulkanLiquidRegion.GeometryField.LIQUID_MICRO)),
                word(input.geometry().get(VulkanLiquidRegion.GeometryField.FIXED_MASKS)),plane,layers,table,0));
            vkCmdDispatch(cmd,(cells+127)/128,1,1);
            VulkanRegionStorage.barrier(cmd,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_READ_BIT,stack);
            VulkanRegionStorage.copy(cmd,output,0,submission.stagingBuffer(),0,bytes,stack);scheduler.submitAndWait(stack,submission,"liquid-display");
            var b=submission.stagingBuffer().bytes().order(ByteOrder.LITTLE_ENDIAN);byte[][] fractions=new byte[m][cells],phases=new byte[fluids.phaseCount()][cells];int count=0;
            for(int material=0;material<m;material++)for(int c=0;c<cells;c++){int at=4*(layers+4*(material*cells+c));int q=b.getInt(at+12);fractions[material][c]=(byte)q;int phaseHandle=fluids.phaseHandle(material);phases[phaseHandle][c]=(byte)(Byte.toUnsignedInt(phases[phaseHandle][c])+q);if(b.getInt(at+8)!=0&&b.getFloat(at+4)>b.getFloat(at))count++;}
            var encoded=java.nio.ByteBuffer.allocate(8+20*cells+16*count).order(ByteOrder.LITTLE_ENDIAN);encoded.putInt(cells).putInt(count);int row=0;
            for(int c=0;c<cells;c++){for(int k=0;k<4;k++)encoded.putFloat(8+20*c+4*k,b.getFloat(4*(plane+4*c+k)));
                for(int material:order){int at=4*(layers+4*(material*cells+c));float lower=b.getFloat(at),upper=b.getFloat(at+4);int mask=b.getInt(at+8);if(mask==0||upper<=lower)continue;int dst=8+20*cells+16*row++;encoded.putShort(dst,(short)material).putShort(dst+2,(short)fluids.phaseHandle(material)).putFloat(dst+4,lower).putFloat(dst+8,upper).putInt(dst+12,mask);}
                encoded.putInt(8+20*c+16,row);
            }
            return mmm.fluid.FluidMaterialRenderSnapshot.fromDisplayFields(l.sizeX(),l.sizeY(),l.sizeZ(),h,fluids.materials(),fluids.phaseGroups(),fractions,phases,
                new mmm.fluid.FluidSurfaceInput(l.sizeX(),l.sizeY(),l.sizeZ(),h,new mmm.fluid.CompactFluidSurface(encoded.array())));
        }
    }
    private static int word(VulkanLiquidStateLayout.Column c){return Math.toIntExact(c.offset()/4);}
    @Override public void close(){try{bindings.close();}finally{try{if(scheduler!=null)scheduler.close();}finally{resources.close();}}}
}

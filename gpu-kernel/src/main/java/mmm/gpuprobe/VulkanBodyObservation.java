package mmm.gpuprobe;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Thirteen-value wet-body query from a pinned generation, with its own derived buffers. */
final class VulkanBodyObservation implements AutoCloseable {
    private final FlipGpuResources resources;
    private final GpuStepScheduler scheduler;
    private final FlipKernelBindings bindings;
    private GpuBuffer output;
    private long capacity;
    VulkanBodyObservation(VulkanDeviceContext context){resources=new FlipGpuResources(context);
        try{scheduler=new GpuStepScheduler(context,resources,4096);
            try(var in=getClass().getResourceAsStream("/assets/mmm_gpu_probe/shaders/body-observation.comp")){
                if(in==null)throw new IllegalStateException("Body observation shader missing");bindings=new FlipKernelBindings(context,2,new String(in.readAllBytes(),StandardCharsets.UTF_8),16);
            }catch(java.io.IOException failure){throw new IllegalStateException(failure);}
        }catch(RuntimeException|Error failure){resources.close();throw failure;}}
    synchronized mmm.fluid.FluidDomain.BodyFluidSample capture(VulkanLiquidRegion.Capture capture,
            double minX,double minY,double minZ,double maxX,double maxY,double maxZ,int waterMaterial,double cellSize,double gravity){
        var bounds=new mmm.world.MovingBodyFluidCoupling.Bounds(minX,minY,minZ,maxX,maxY,maxZ);var in=capture.readoutInputs();var liquid=in.liquid();
        int[] dims={liquid.sizeX(),liquid.sizeY(),liquid.sizeZ()},first=new int[3],extent=new int[3];double[] low={minX,minY,minZ},high={maxX,maxY,maxZ};int count=1;
        for(int axis=0;axis<3;axis++){first[axis]=Math.max(0,Math.min(dims[axis],(int)Math.floor(low[axis]/cellSize)));int last=Math.max(first[axis],Math.min(dims[axis],(int)Math.ceil(high[axis]/cellSize)));extent[axis]=last-first[axis];count=Math.multiplyExact(count,extent[axis]);}
        if(count==0)return new mmm.fluid.FluidDomain.BodyFluidSample(0,0,0,0,0,0,0,0,0,0,0,0,0);
        int groups=(count+127)/128;long bytes=384L+104L*groups;if(bytes>capacity){if(output!=null)resources.destroy(output);output=resources.addDeviceStorage(bytes);capacity=bytes;}
        var header=ByteBuffer.allocateDirect(256).order(ByteOrder.LITTLE_ENDIAN);int[] words=new int[28];
        words[0]=dims[0];words[1]=dims[1];words[2]=dims[2];for(int axis=0;axis<3;axis++){words[3+axis]=first[axis];words[6+axis]=extent[axis];}
        words[9]=count;words[10]=groups;
        words[11]=word(in.geometry().get(VulkanLiquidRegion.GeometryField.FIXED_MASKS));words[12]=word(in.geometry().get(VulkanLiquidRegion.GeometryField.CELLS));
        words[13]=word(in.geometry().get(VulkanLiquidRegion.GeometryField.PLANES));words[14]=word(in.geometry().get(VulkanLiquidRegion.GeometryField.FACES));
        words[15]=word(liquid.column(VulkanLiquidStateLayout.Field.CELL_OFFSETS));words[16]=word(liquid.column(VulkanLiquidStateLayout.Field.NUMERIC_VOLUMES));
        words[17]=word(liquid.column(VulkanLiquidStateLayout.Field.MATERIALS));words[18]=word(liquid.column(VulkanLiquidStateLayout.Field.MASS_UNITS));words[19]=waterMaterial;
        words[20]=in.gasPressure()==null?-1:in.gasPressure().word(VulkanGasPressure.Field.CELL_PHYSICAL_PRESSURE);
        words[21]=in.gasThermal()==null?-1:word(in.gasThermal().states());words[22]=in.gasGeometry()==null?-1:word(in.gasGeometry().cellNodes());
        for(int i=0;i<words.length;i++)header.putInt(4*i,words[i]);
        for(int axis=0;axis<3;axis++){header.putDouble(128+8*axis,low[axis]/cellSize);header.putDouble(152+8*axis,high[axis]/cellSize);}
        header.putDouble(176,cellSize).putDouble(184,gravity);
        bindings.rebindRanges(List.of(new FlipKernelBindings.BufferRange(capture.buffer()),new FlipKernelBindings.BufferRange(output)));
        try(var stack=stackPush()){
            var submission=scheduler.acquire();var command=scheduler.begin(submission,"body-observation");vkCmdUpdateBuffer(command,output.handle(),0,header);
            VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,VK_ACCESS_MEMORY_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT,stack);
            for(int stage=0;stage<2;stage++){
                bindings.bind(command,stack);vkCmdPushConstants(command,bindings.pipelineLayout(),VK_SHADER_STAGE_COMPUTE_BIT,0,stack.ints(stage,count,groups,0));vkCmdDispatch(command,stage==0?groups:1,1,1);
                VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,stage==0?VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT:VK_PIPELINE_STAGE_TRANSFER_BIT,stage==0?VK_ACCESS_SHADER_READ_BIT:VK_ACCESS_TRANSFER_READ_BIT,stack);
            }
            VulkanRegionStorage.copy(command,output,256,submission.stagingBuffer(),0,104,stack);scheduler.submitAndWait(stack,submission,"body-observation");
            var result=submission.stagingBuffer().bytes().order(ByteOrder.LITTLE_ENDIAN);return new mmm.fluid.FluidDomain.BodyFluidSample(result.getDouble(0),result.getDouble(8),result.getDouble(16),result.getDouble(24),result.getDouble(32),result.getDouble(40),result.getDouble(48),result.getDouble(56),result.getDouble(64),result.getDouble(72),result.getDouble(80),result.getDouble(88),result.getDouble(96));
        }
    }
    private static int word(VulkanLiquidStateLayout.Column c){return Math.toIntExact(c.offset()/4);}
    @Override public synchronized void close(){try{bindings.close();}finally{try{scheduler.close();}finally{resources.close();}}}
}

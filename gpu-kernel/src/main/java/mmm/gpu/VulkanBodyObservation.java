package mmm.gpu;

import mmm.fluid.FluidDomainContracts;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Thirteen-value wet-body query from a pinned generation, with its own derived buffers. */
final class VulkanBodyObservation implements AutoCloseable {
    private enum Header {
        SIZE_X, SIZE_Y, SIZE_Z, FIRST_X, FIRST_Y, FIRST_Z,
        EXTENT_X, EXTENT_Y, EXTENT_Z, CELL_COUNT, GROUP_COUNT, MASKS,
        GEOMETRY_CELLS, PLANES, FACES, CELL_OFFSETS, VOLUMES, MATERIALS,
        MASS_UNITS, WATER_MATERIAL, GAS_PRESSURE, GAS_THERMAL, GAS_CELL_NODES
    }

    private final FlipGpuResources resources;
    private final GpuStepScheduler scheduler;
    private final FlipKernelBindings bindings;
    private GpuBuffer output;
    private long capacity;
    VulkanBodyObservation(VulkanDeviceContext context){resources=new FlipGpuResources(context);
        try{scheduler=new GpuStepScheduler(context,resources,4096);
            try(var in=getClass().getResourceAsStream("/assets/mmm_gpu_probe/shaders/body-observation.comp")){
                if(in==null)throw new IllegalStateException("Body observation shader missing");bindings=new FlipKernelBindings(context,2,new String(in.readAllBytes(),StandardCharsets.UTF_8).replace("// HOST_LAYOUT", VulkanShaderLayout.fields("H_", Header.values())),16);
            }catch(java.io.IOException failure){throw new IllegalStateException(failure);}
        }catch(RuntimeException|Error failure){resources.close();throw failure;}}
    synchronized mmm.fluid.FluidDomainContracts.BodyFluidSample capture(VulkanLiquidRegion.Capture capture,
            double minX,double minY,double minZ,double maxX,double maxY,double maxZ,int waterMaterial,double cellSize,double gravity){
        var bounds=new mmm.world.MovingBodyFluidCoupling.Bounds(minX,minY,minZ,maxX,maxY,maxZ);var in=capture.readoutInputs();var liquid=in.liquid();
        int[] dims={liquid.sizeX(),liquid.sizeY(),liquid.sizeZ()},first=new int[3],extent=new int[3];double[] low={minX,minY,minZ},high={maxX,maxY,maxZ};int count=1;
        for(int axis=0;axis<3;axis++){first[axis]=Math.max(0,Math.min(dims[axis],(int)Math.floor(low[axis]/cellSize)));int last=Math.max(first[axis],Math.min(dims[axis],(int)Math.ceil(high[axis]/cellSize)));extent[axis]=last-first[axis];count=Math.multiplyExact(count,extent[axis]);}
        if(count==0)return new mmm.fluid.FluidDomainContracts.BodyFluidSample(0,0,0,0,0,0,0,0,0,0,0,0,0);
        int groups=(count+127)/128;long bytes=384L+104L*groups;if(bytes>capacity){if(output!=null)resources.destroy(output);output=resources.addDeviceStorage(bytes);capacity=bytes;}
        var header=ByteBuffer.allocateDirect(256).order(ByteOrder.LITTLE_ENDIAN);int[] words=new int[28];
        words[Header.SIZE_X.ordinal()]=dims[0];words[Header.SIZE_Y.ordinal()]=dims[1];words[Header.SIZE_Z.ordinal()]=dims[2];for(int axis=0;axis<3;axis++){words[Header.FIRST_X.ordinal()+axis]=first[axis];words[Header.EXTENT_X.ordinal()+axis]=extent[axis];}
        words[Header.CELL_COUNT.ordinal()]=count;words[Header.GROUP_COUNT.ordinal()]=groups;
        words[Header.MASKS.ordinal()]=word(in.geometry().get(VulkanLiquidRegion.GeometryField.FIXED_MASKS));words[Header.GEOMETRY_CELLS.ordinal()]=word(in.geometry().get(VulkanLiquidRegion.GeometryField.CELLS));
        words[Header.PLANES.ordinal()]=word(in.geometry().get(VulkanLiquidRegion.GeometryField.PLANES));words[Header.FACES.ordinal()]=word(in.geometry().get(VulkanLiquidRegion.GeometryField.FACES));
        words[Header.CELL_OFFSETS.ordinal()]=word(liquid.column(VulkanLiquidStateLayout.Field.CELL_OFFSETS));words[Header.VOLUMES.ordinal()]=word(liquid.column(VulkanLiquidStateLayout.Field.NUMERIC_VOLUMES));
        words[Header.MATERIALS.ordinal()]=word(liquid.column(VulkanLiquidStateLayout.Field.MATERIALS));words[Header.MASS_UNITS.ordinal()]=word(liquid.column(VulkanLiquidStateLayout.Field.MASS_UNITS));words[Header.WATER_MATERIAL.ordinal()]=waterMaterial;
        words[Header.GAS_PRESSURE.ordinal()]=in.gasPressure()==null?-1:in.gasPressure().word(VulkanGasPressure.Field.CELL_PHYSICAL_PRESSURE);
        words[Header.GAS_THERMAL.ordinal()]=in.gasThermal()==null?-1:word(in.gasThermal().states());words[Header.GAS_CELL_NODES.ordinal()]=in.gasGeometry()==null?-1:word(in.gasGeometry().cellNodes());
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
            var result=submission.stagingBuffer().bytes().order(ByteOrder.LITTLE_ENDIAN);return new mmm.fluid.FluidDomainContracts.BodyFluidSample(result.getDouble(0),result.getDouble(8),result.getDouble(16),result.getDouble(24),result.getDouble(32),result.getDouble(40),result.getDouble(48),result.getDouble(56),result.getDouble(64),result.getDouble(72),result.getDouble(80),result.getDouble(88),result.getDouble(96));
        }
    }
    private static int word(VulkanLiquidStateLayout.Column c){return Math.toIntExact(c.offset()/4);}
    @Override public synchronized void close(){try{bindings.close();}finally{try{scheduler.close();}finally{resources.close();}}}
}

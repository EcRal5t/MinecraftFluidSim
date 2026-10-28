package mmm.gpuprobe;

import org.lwjgl.vulkan.VkCommandBuffer;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Converts projected face preferences into a resident ambient liquid capacity problem. */
final class VulkanLiquidCapacityInputs implements AutoCloseable {
    record Layout(VulkanLiquidCellVolumes.Layout volumes, VulkanLiquidCapacityLayout capacity,
                  int masksWordOffset, int occupiedWordOffset, int facesWordOffset, int fluxesWordOffset,
                  VulkanGasGeometry.Layout gasGeometry,VulkanGasStateLayout gas,VulkanGasThermodynamics.Layout gasThermodynamics,
                  VulkanGasPressure.Layout gasPressure,VulkanLiquidStateLayout.Column header,VulkanLiquidStateLayout.Column groupDispatch,long endOffset) {
        int headerWordOffset() { return Math.toIntExact(header.offset() / 4); }
        void initializeHeader(ByteBuffer destination) {
            destination.duplicate().position(Math.toIntExact(header.offset())).put(headerBytes());
        }
        ByteBuffer headerBytes() {
            var bytes = ByteBuffer.allocateDirect(Math.toIntExact(header.bytes())).order(ByteOrder.LITTLE_ENDIAN);
            int[] data = new int[40];
            var source = volumes.source();
            data[0] = source.sizeX(); data[1] = source.sizeY(); data[2] = source.sizeZ();
            data[3] = source.cellCount(); data[4] = volumes.quantumExponent();
            data[5] = word(volumes.quantized()); data[6] = word(volumes.totalSigns());
            data[7] = word(volumes.totalExponents());
            data[34] = word(volumes.totalOffsets()); data[35] = word(volumes.totalLimbs());
            data[8] = masksWordOffset; data[9] = occupiedWordOffset;
            data[10] = facesWordOffset; data[11] = fluxesWordOffset;
            data[16] = capacity.word(VulkanLiquidCapacityLayout.Field.K);
            data[17] = capacity.word(VulkanLiquidCapacityLayout.Field.LOWER);
            data[18] = capacity.word(VulkanLiquidCapacityLayout.Field.UPPER);
            data[19] = capacity.word(VulkanLiquidCapacityLayout.Field.GROUP_ID);
            data[20] = capacity.word(VulkanLiquidCapacityLayout.Field.PREFERRED);
            data[21] = capacity.word(VulkanLiquidCapacityLayout.Field.OPEN_NEIGHBORS);
            data[22]=capacity.gasGroups();
            if(gas!=null) {
                data[23]=word(gasGeometry.cellNodes());data[24]=word(gasGeometry.componentOffsets());data[25]=word(gasGeometry.componentMembers());
                data[26]=Math.toIntExact(gas.column(VulkanGasStateLayout.Field.COUNTS).offset()/4);
                data[27]=Math.toIntExact(gas.column(VulkanGasStateLayout.Field.ROW_COMPONENT_OFFSETS).offset()/4);
                data[28]=Math.toIntExact(gas.column(VulkanGasStateLayout.Field.STANDARD_VOLUME_NANOS).offset()/4);
                data[29]=capacity.word(VulkanLiquidCapacityLayout.Field.GROUP_CAPACITY);
                data[30]=word(groupDispatch);
                data[12]=gasThermodynamics.nodeVolumesWordOffset();data[13]=word(gasThermodynamics.states());
                data[14]=word(gasPressure.column(VulkanGasPressure.Field.NODE_ABSOLUTE_PRESSURE));
                data[15]=word(gasPressure.column(VulkanGasPressure.Field.MECHANICAL_MODE));
            }
            for (int i = 0; i < data.length; i++) bytes.putInt(Math.toIntExact(0 + 4L*i), data[i]);
            if(gasGeometry!=null){double size=gasGeometry.cellSize();bytes.putDouble(4*32,size*size*size);}
        
            return bytes;
        }
    }
    static Layout layout(VulkanLiquidCellVolumes.Layout volumes, VulkanLiquidCapacityLayout capacity,
                         int masksWordOffset, int occupiedWordOffset, int facesWordOffset, int fluxesWordOffset,
                         long scratchBase, long alignment) {
        return layout(volumes,capacity,masksWordOffset,occupiedWordOffset,facesWordOffset,fluxesWordOffset,null,null,null,null,scratchBase,alignment);
    }
    static Layout layout(VulkanLiquidCellVolumes.Layout volumes,VulkanLiquidCapacityLayout capacity,int masksWordOffset,
                         int occupiedWordOffset,int facesWordOffset,int fluxesWordOffset,VulkanGasGeometry.Layout gasGeometry,
                         VulkanGasStateLayout gas,VulkanGasThermodynamics.Layout gasThermodynamics,
                         VulkanGasPressure.Layout gasPressure,long scratchBase,long alignment) {
        if(capacity.cells()!=volumes.source().cellCount()||(gas==null?capacity.gasGroups()!=0:
                gasGeometry==null||gasThermodynamics==null||gasPressure==null||capacity.gasGroups()!=gas.nodeCapacity()||gasGeometry.cells()!=capacity.cells()))
            throw new IllegalArgumentException("Capacity inputs differ from their liquid/gas layout");
        if (volumes.quantumExponent() > -24 || volumes.quantumExponent() < -50)
            throw new IllegalArgumentException("Capacity quantum must resolve solid volume and fit integer cell capacity");
        long base = Math.addExact(scratchBase, alignment-1) & -alignment;
        long dispatch=Math.addExact(base,Math.addExact(160,alignment-1)&-alignment);
        return new Layout(volumes, capacity, masksWordOffset, occupiedWordOffset, facesWordOffset, fluxesWordOffset,gasGeometry,gas,gasThermodynamics,gasPressure,
                new VulkanLiquidStateLayout.Column(base,40,4),new VulkanLiquidStateLayout.Column(dispatch,3,4),
                Math.addExact(dispatch,Math.max(12,alignment)));
    }
    private final FlipKernelBindings bindings;
    private FlipKernelBindings.BufferRange arena;
    VulkanLiquidCapacityInputs(VulkanDeviceContext context) { bindings = new FlipKernelBindings(context, 2, shader(), 8); }
    void rebindRanges(FlipKernelBindings.BufferRange arena, FlipKernelBindings.BufferRange errors) {
        this.arena=arena;bindings.rebindRanges(List.of(arena, errors));
    }
    void record(VkCommandBuffer command, Layout layout) {
        record(command,layout,false);
    }
    /** Future occupied SVQ may displace the source K. The lower-bounded solver accepts all routes together. */
    void recordDisplacement(VkCommandBuffer command,Layout layout){record(command,layout,true);}
    private void record(VkCommandBuffer command,Layout layout,boolean displacement){
        stage(command,layout,displacement?3:0,(layout.capacity().cells()+127)/128);
        if(layout.capacity().gasGroups()>0) {
            stage(command,layout,2,1);
            try(var stack=stackPush()) {
                VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,
                        VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT,VK_ACCESS_INDIRECT_COMMAND_READ_BIT,stack);
                bindings.bind(command,stack);
                vkCmdPushConstants(command,bindings.pipelineLayout(),VK_SHADER_STAGE_COMPUTE_BIT,0,stack.ints(layout.headerWordOffset(),displacement?4:1));
                vkCmdDispatchIndirect(command,arena.buffer().handle(),arena.offset()+layout.groupDispatch().offset());
                VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,
                        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);
            }
        }
    }
    private void stage(VkCommandBuffer command,Layout layout,int stage,int groups) {
        try (var stack = stackPush()) {
            bindings.bind(command, stack);
            vkCmdPushConstants(command, bindings.pipelineLayout(), VK_SHADER_STAGE_COMPUTE_BIT, 0, stack.ints(layout.headerWordOffset(),stage));
            vkCmdDispatch(command, Math.min(groups,65535), (groups+65534)/65535, 1);
            VulkanRegionStorage.barrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT, stack);
        }
    }
    private static int word(VulkanLiquidStateLayout.Column column) { return Math.toIntExact(column.offset()/4); }
    private static String shader() {
        try (var input = VulkanLiquidCapacityInputs.class.getResourceAsStream("/assets/mmm_gpu_probe/shaders/liquid-capacity-inputs.comp")) {
            if (input == null) throw new IllegalStateException("Liquid capacity input shader missing");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) { throw new IllegalStateException("Liquid capacity input shader unavailable", failure); }
    }
    @Override public void close() { bindings.close(); }
}

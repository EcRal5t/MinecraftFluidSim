package mmm.gpu;

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
    /** Word positions shared by the host encoder and shader. */
    private enum Header {
        SIZE_X(0),
        SIZE_Y(1),
        SIZE_Z(2),
        CELL_COUNT(3),
        QUANTUM_EXPONENT(4),
        QUANTIZED(5),
        TOTAL_SIGNS(6),
        TOTAL_EXPONENTS(7),
        MASKS(8),
        OCCUPIED(9),
        FACES(10),
        FLUXES(11),
        GAS_VOLUMES(12),
        GAS_STATES(13),
        GAS_PRESSURE(14),
        GAS_MECHANICAL_MODE(15),
        CAPACITY_K(16),
        CAPACITY_LOWER(17),
        CAPACITY_UPPER(18),
        CAPACITY_GROUP_ID(19),
        CAPACITY_PREFERRED(20),
        CAPACITY_OPEN_NEIGHBORS(21),
        GAS_GROUP_COUNT(22),
        GAS_CELL_NODES(23),
        GAS_COMPONENT_OFFSETS(24),
        GAS_COMPONENT_MEMBERS(25),
        GAS_COUNTS(26),
        GAS_ROW_COMPONENT_OFFSETS(27),
        GAS_STANDARD_VOLUME(28),
        GROUP_CAPACITY(29),
        GROUP_DISPATCH(30),
        CELL_VOLUME(32),
        TOTAL_OFFSETS(34),
        TOTAL_LIMBS(35),
        ACTIVITY_CELLS(36),
        ACTIVITY_FACES(37);
        final int word;
        Header(int word) { this.word = word; }
    }
    private static String headerDefines() {
        var result = new StringBuilder();
        for (var field : Header.values())
            result.append("#define H_").append(field.name()).append(' ').append(field.word).append('\n');
        return result.toString();
    }

    record Layout(VulkanLiquidCellVolumes.Layout volumes, VulkanLiquidCapacityLayout capacity,
                  int masksWordOffset, int occupiedWordOffset, int facesWordOffset, int fluxesWordOffset, int activityCellsWordOffset, int activityFacesWordOffset,
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
            data[Header.SIZE_X.word] = source.sizeX(); data[Header.SIZE_Y.word] = source.sizeY(); data[Header.SIZE_Z.word] = source.sizeZ();
            data[Header.CELL_COUNT.word] = source.cellCount(); data[Header.QUANTUM_EXPONENT.word] = volumes.quantumExponent();
            data[Header.QUANTIZED.word] = word(volumes.quantized()); data[Header.TOTAL_SIGNS.word] = word(volumes.totalSigns());
            data[Header.TOTAL_EXPONENTS.word] = word(volumes.totalExponents());
            data[Header.TOTAL_OFFSETS.word] = word(volumes.totalOffsets()); data[Header.TOTAL_LIMBS.word] = word(volumes.totalLimbs());
            data[Header.MASKS.word] = masksWordOffset; data[Header.OCCUPIED.word] = occupiedWordOffset;
            data[Header.FACES.word] = facesWordOffset; data[Header.FLUXES.word] = fluxesWordOffset;
            data[Header.ACTIVITY_CELLS.word] = activityCellsWordOffset; data[Header.ACTIVITY_FACES.word] = activityFacesWordOffset;
            data[Header.CAPACITY_K.word] = capacity.word(VulkanLiquidCapacityLayout.Field.K);
            data[Header.CAPACITY_LOWER.word] = capacity.word(VulkanLiquidCapacityLayout.Field.LOWER);
            data[Header.CAPACITY_UPPER.word] = capacity.word(VulkanLiquidCapacityLayout.Field.UPPER);
            data[Header.CAPACITY_GROUP_ID.word] = capacity.word(VulkanLiquidCapacityLayout.Field.GROUP_ID);
            data[Header.CAPACITY_PREFERRED.word] = capacity.word(VulkanLiquidCapacityLayout.Field.PREFERRED);
            data[Header.CAPACITY_OPEN_NEIGHBORS.word] = capacity.word(VulkanLiquidCapacityLayout.Field.OPEN_NEIGHBORS);
            data[Header.GAS_GROUP_COUNT.word]=capacity.gasGroups();
            if(gas!=null) {
                data[Header.GAS_CELL_NODES.word]=word(gasGeometry.cellNodes());data[Header.GAS_COMPONENT_OFFSETS.word]=word(gasGeometry.componentOffsets());data[Header.GAS_COMPONENT_MEMBERS.word]=word(gasGeometry.componentMembers());
                data[Header.GAS_COUNTS.word]=Math.toIntExact(gas.column(VulkanGasStateLayout.Field.COUNTS).offset()/4);
                data[Header.GAS_ROW_COMPONENT_OFFSETS.word]=Math.toIntExact(gas.column(VulkanGasStateLayout.Field.ROW_COMPONENT_OFFSETS).offset()/4);
                data[Header.GAS_STANDARD_VOLUME.word]=Math.toIntExact(gas.column(VulkanGasStateLayout.Field.STANDARD_VOLUME_NANOS).offset()/4);
                data[Header.GROUP_CAPACITY.word]=capacity.word(VulkanLiquidCapacityLayout.Field.GROUP_CAPACITY);
                data[Header.GROUP_DISPATCH.word]=word(groupDispatch);
                data[Header.GAS_VOLUMES.word]=gasThermodynamics.nodeVolumesWordOffset();data[Header.GAS_STATES.word]=word(gasThermodynamics.states());
                data[Header.GAS_PRESSURE.word]=word(gasPressure.column(VulkanGasPressure.Field.NODE_ABSOLUTE_PRESSURE));
                data[Header.GAS_MECHANICAL_MODE.word]=word(gasPressure.column(VulkanGasPressure.Field.MECHANICAL_MODE));
            }
            for (int i = 0; i < data.length; i++) bytes.putInt(Math.toIntExact(0 + 4L*i), data[i]);
            if(gasGeometry!=null){double size=gasGeometry.cellSize();bytes.putDouble(4 * Header.CELL_VOLUME.word,size*size*size);}

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
        return layout(volumes,capacity,masksWordOffset,occupiedWordOffset,facesWordOffset,fluxesWordOffset,gasGeometry,gas,gasThermodynamics,gasPressure,-1,-1,scratchBase,alignment);
    }
    static Layout layout(VulkanLiquidCellVolumes.Layout volumes,VulkanLiquidCapacityLayout capacity,int masksWordOffset,
                         int occupiedWordOffset,int facesWordOffset,int fluxesWordOffset,VulkanGasGeometry.Layout gasGeometry,
                         VulkanGasStateLayout gas,VulkanGasThermodynamics.Layout gasThermodynamics,
                         VulkanGasPressure.Layout gasPressure,int activityCellsWordOffset,int activityFacesWordOffset,long scratchBase,long alignment) {
        if(capacity.cells()!=volumes.source().cellCount()||(gas==null?capacity.gasGroups()!=0:
                gasGeometry==null||gasThermodynamics==null||gasPressure==null||capacity.gasGroups()!=gas.nodeCapacity()||gasGeometry.cells()!=capacity.cells()))
            throw new IllegalArgumentException("Capacity inputs differ from their liquid/gas layout");
        if (volumes.quantumExponent() > -24 || volumes.quantumExponent() < -50)
            throw new IllegalArgumentException("Capacity quantum must resolve solid volume and fit integer cell capacity");
        long base = Math.addExact(scratchBase, alignment-1) & -alignment;
        long dispatch=Math.addExact(base,Math.addExact(160,alignment-1)&-alignment);
        return new Layout(volumes, capacity, masksWordOffset, occupiedWordOffset, facesWordOffset, fluxesWordOffset,activityCellsWordOffset,activityFacesWordOffset,gasGeometry,gas,gasThermodynamics,gasPressure,
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
                vkCmdPushConstants(command,bindings.pipelineLayout(),VK_SHADER_STAGE_COMPUTE_BIT,0,stack.ints(layout.headerWordOffset(),1));
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
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("// HOST_LAYOUT", headerDefines());
        } catch (IOException failure) { throw new IllegalStateException("Liquid capacity input shader unavailable", failure); }
    }
    @Override public void close() { bindings.close(); }
}

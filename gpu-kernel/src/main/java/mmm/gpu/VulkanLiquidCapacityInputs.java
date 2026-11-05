package mmm.gpu;

import org.lwjgl.vulkan.VkCommandBuffer;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Converts physical face sweeps into donor-bounded reference-volume routes. */
final class VulkanLiquidCapacityInputs implements AutoCloseable {
    private enum Header {
        SIZE_X, SIZE_Y, SIZE_Z, CELL_COUNT, QUANTUM_EXPONENT, QUANTIZED,
        TOTAL_SIGNS, TOTAL_EXPONENTS, MASKS, OCCUPIED, FACES, FLUXES,
        PHYSICAL_PER_REFERENCE, PHYSICAL_SCALE_STRIDE, ACCEPTED, TOTAL_OFFSETS, TOTAL_LIMBS,
        ACTIVITY_CELLS, ACTIVITY_FACES
    }
    private static String headerDefines() {
        return VulkanShaderLayout.fields("H_", Header.values());
    }
    record Layout(VulkanLiquidCellVolumes.Layout volumes, VulkanLiquidStateLayout.Column accepted,
                  int masksWordOffset, int occupiedWordOffset, int facesWordOffset, int fluxesWordOffset,
                  int physicalPerReferenceWordOffset, int physicalScaleStride, int activityCellsWordOffset, int activityFacesWordOffset,
                  VulkanLiquidStateLayout.Column header, long endOffset) {
        int headerWordOffset() { return word(header); }
        void initializeHeader(ByteBuffer destination) {
            destination.duplicate().position(Math.toIntExact(header.offset())).put(headerBytes());
        }
        ByteBuffer headerBytes() {
            var bytes = ByteBuffer.allocateDirect(Math.toIntExact(header.bytes())).order(ByteOrder.LITTLE_ENDIAN);
            int[] data = new int[Header.values().length];
            var source = volumes.source();
            data[Header.SIZE_X.ordinal()] = source.sizeX();
            data[Header.SIZE_Y.ordinal()] = source.sizeY();
            data[Header.SIZE_Z.ordinal()] = source.sizeZ();
            data[Header.CELL_COUNT.ordinal()] = source.cellCount();
            data[Header.QUANTUM_EXPONENT.ordinal()] = volumes.quantumExponent();
            data[Header.QUANTIZED.ordinal()] = word(volumes.quantized());
            data[Header.TOTAL_SIGNS.ordinal()] = word(volumes.totalSigns());
            data[Header.TOTAL_EXPONENTS.ordinal()] = word(volumes.totalExponents());
            data[Header.TOTAL_OFFSETS.ordinal()] = word(volumes.totalOffsets());
            data[Header.TOTAL_LIMBS.ordinal()] = word(volumes.totalLimbs());
            data[Header.MASKS.ordinal()] = masksWordOffset;
            data[Header.OCCUPIED.ordinal()] = occupiedWordOffset;
            data[Header.FACES.ordinal()] = facesWordOffset;
            data[Header.FLUXES.ordinal()] = fluxesWordOffset;
            data[Header.PHYSICAL_PER_REFERENCE.ordinal()] = physicalPerReferenceWordOffset;
            data[Header.PHYSICAL_SCALE_STRIDE.ordinal()] = physicalScaleStride;
            data[Header.ACTIVITY_CELLS.ordinal()] = activityCellsWordOffset;
            data[Header.ACTIVITY_FACES.ordinal()] = activityFacesWordOffset;
            data[Header.ACCEPTED.ordinal()] = word(accepted);
            for (int i = 0; i < data.length; i++) bytes.putInt(4 * i, data[i]);
            return bytes;
        }
    }
    static Layout layout(VulkanLiquidCellVolumes.Layout volumes,
                         int masksWordOffset, int occupiedWordOffset, int facesWordOffset, int fluxesWordOffset,
                         int physicalPerReferenceWordOffset, int physicalScaleStride, int activityCellsWordOffset, int activityFacesWordOffset,
                         long scratchBase, long alignment) {
        if (physicalPerReferenceWordOffset < 0 || physicalScaleStride < 1 ||
                scratchBase < 0 || alignment < 8 || (alignment & (alignment - 1)) != 0)
            throw new IllegalArgumentException("Invalid liquid route layout");
        if (volumes.quantumExponent() > -24 || volumes.quantumExponent() < -50)
            throw new IllegalArgumentException("Invalid liquid route quantum");
        long base = Math.addExact(scratchBase, alignment - 1) & -alignment;
        var accepted = new VulkanLiquidStateLayout.Column(base, Math.multiplyExact(7,volumes.source().cellCount()), 8);
        long headerBase=Math.addExact(Math.addExact(base,accepted.bindingBytes()),alignment-1)&-alignment;
        var header = new VulkanLiquidStateLayout.Column(headerBase, Header.values().length, 4);
        long end = Math.addExact(Math.addExact(headerBase, header.bindingBytes()), alignment - 1) & -alignment;
        return new Layout(volumes, accepted, masksWordOffset, occupiedWordOffset, facesWordOffset, fluxesWordOffset,
                physicalPerReferenceWordOffset, physicalScaleStride, activityCellsWordOffset, activityFacesWordOffset, header, end);
    }
    private final FlipKernelBindings bindings;
    VulkanLiquidCapacityInputs(VulkanDeviceContext context) { bindings = new FlipKernelBindings(context, 2, shader(), 4); }
    void rebindRanges(FlipKernelBindings.BufferRange arena, FlipKernelBindings.BufferRange errors) {
        bindings.rebindRanges(List.of(arena, errors));
    }
    void record(VkCommandBuffer command, Layout layout) {
        int groups = (layout.volumes().source().cellCount() + 127) / 128;
        try (var stack = stackPush()) {
            bindings.bind(command, stack);
            vkCmdPushConstants(command, bindings.pipelineLayout(), VK_SHADER_STAGE_COMPUTE_BIT, 0, stack.ints(layout.headerWordOffset()));
            vkCmdDispatch(command, Math.min(groups, 65535), (groups + 65534) / 65535, 1);
            VulkanRegionStorage.barrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT, stack);
        }
    }
    private static int word(VulkanLiquidStateLayout.Column column) { return Math.toIntExact(column.offset() / 4); }
    private static String shader() {
        try (var input = VulkanLiquidCapacityInputs.class.getResourceAsStream("/assets/mmm_gpu_probe/shaders/liquid-capacity-inputs.comp")) {
            if (input == null) throw new IllegalStateException("Liquid route shader missing");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("// HOST_LAYOUT", headerDefines());
        } catch (IOException failure) { throw new IllegalStateException("Liquid route shader unavailable", failure); }
    }
    @Override public void close() { bindings.close(); }
}

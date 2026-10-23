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
                  VulkanLiquidStateLayout.Column header, long endOffset) {
        int headerWordOffset() { return Math.toIntExact(header.offset() / 4); }
        void initializeHeader(ByteBuffer destination) {
            var bytes = destination.duplicate().order(ByteOrder.LITTLE_ENDIAN);
            int[] data = new int[32];
            var source = volumes.source();
            data[0] = source.sizeX(); data[1] = source.sizeY(); data[2] = source.sizeZ();
            data[3] = source.cellCount(); data[4] = volumes.quantumExponent();
            data[5] = word(volumes.quantized()); data[6] = word(volumes.totalSigns());
            data[7] = word(volumes.totalExponents());
            data[8] = masksWordOffset; data[9] = occupiedWordOffset;
            data[10] = facesWordOffset; data[11] = fluxesWordOffset;
            data[16] = capacity.word(VulkanLiquidCapacityLayout.Field.K);
            data[17] = capacity.word(VulkanLiquidCapacityLayout.Field.LOWER);
            data[18] = capacity.word(VulkanLiquidCapacityLayout.Field.UPPER);
            data[19] = capacity.word(VulkanLiquidCapacityLayout.Field.GROUP_ID);
            data[20] = capacity.word(VulkanLiquidCapacityLayout.Field.PREFERRED);
            data[21] = capacity.word(VulkanLiquidCapacityLayout.Field.OPEN_NEIGHBORS);
            for (int i = 0; i < data.length; i++) bytes.putInt(Math.toIntExact(header.offset() + 4L*i), data[i]);
        }
    }
    static Layout layout(VulkanLiquidCellVolumes.Layout volumes, VulkanLiquidCapacityLayout capacity,
                         int masksWordOffset, int occupiedWordOffset, int facesWordOffset, int fluxesWordOffset,
                         long scratchBase, long alignment) {
        if (capacity.gasGroups() != 0 || capacity.cells() != volumes.source().cellCount())
            throw new IllegalArgumentException("Ambient capacity inputs require matching cells and no finite gas groups");
        if (volumes.quantumExponent() > -24 || volumes.quantumExponent() < -50)
            throw new IllegalArgumentException("Capacity quantum must resolve solid volume and fit integer cell capacity");
        long base = Math.addExact(scratchBase, alignment-1) & -alignment;
        return new Layout(volumes, capacity, masksWordOffset, occupiedWordOffset, facesWordOffset, fluxesWordOffset,
                new VulkanLiquidStateLayout.Column(base, 32, 4), Math.addExact(base, Math.max(128, alignment)));
    }
    private final FlipKernelBindings bindings;
    VulkanLiquidCapacityInputs(VulkanDeviceContext context) { bindings = new FlipKernelBindings(context, 2, shader(), 4); }
    void rebindRanges(FlipKernelBindings.BufferRange arena, FlipKernelBindings.BufferRange errors) {
        bindings.rebindRanges(List.of(arena, errors));
    }
    void record(VkCommandBuffer command, Layout layout) {
        int groups = (layout.capacity().cells()+127)/128;
        try (var stack = stackPush()) {
            bindings.bind(command, stack);
            vkCmdPushConstants(command, bindings.pipelineLayout(), VK_SHADER_STAGE_COMPUTE_BIT, 0, stack.ints(layout.headerWordOffset()));
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

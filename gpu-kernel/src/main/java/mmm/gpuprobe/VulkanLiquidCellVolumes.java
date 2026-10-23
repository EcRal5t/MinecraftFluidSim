package mmm.gpuprobe;

import org.lwjgl.vulkan.VkCommandBuffer;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static mmm.gpuprobe.VulkanRegionStorage.barrier;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Exact per-cell volume totals and integer capacity supplies, recorded entirely on device. */
final class VulkanLiquidCellVolumes implements AutoCloseable {
    record Layout(VulkanLiquidStateLayout source, int limbCapacity, int quantumExponent,
                  VulkanLiquidStateLayout.Column header, VulkanLiquidStateLayout.Column totalOffsets,
                  VulkanLiquidStateLayout.Column totalSigns, VulkanLiquidStateLayout.Column totalExponents,
                  VulkanLiquidStateLayout.Column totalLimbs, VulkanLiquidStateLayout.Column quantized,
                  VulkanLiquidStateLayout.Column measurements, VulkanLiquidStateLayout.Column counts,
                  VulkanLiquidStateLayout.Column scanWorkspace, long endOffset) {
        int headerWordOffset() { return word(header); }
        long bytes() { return endOffset - header.offset(); }
        void initializeHeader(ByteBuffer destination) {
            var target = destination.duplicate().order(ByteOrder.LITTLE_ENDIAN);
            int[] data = new int[32];
            data[0] = source.cellCount(); data[1] = limbCapacity; data[2] = quantumExponent;
            data[3] = word(totalOffsets); data[4] = word(totalSigns); data[5] = word(totalExponents);
            data[6] = word(totalLimbs); data[7] = word(quantized); data[8] = word(measurements); data[9] = word(counts);
            data[16] = word(source.column(VulkanLiquidStateLayout.Field.CELL_OFFSETS));
            data[17] = word(source.column(VulkanLiquidStateLayout.Field.VOLUME_OFFSETS));
            data[18] = word(source.column(VulkanLiquidStateLayout.Field.VOLUME_SIGNS));
            data[19] = word(source.column(VulkanLiquidStateLayout.Field.VOLUME_EXPONENTS));
            data[20] = word(source.column(VulkanLiquidStateLayout.Field.VOLUME_LIMBS));
            for (int i = 0; i < data.length; i++) target.putInt(Math.toIntExact(header.offset() + 4L * i), data[i]);
        }
    }
    /** quantumExponent=-40 gives floor(totalVolume * 2^40). */
    static Layout layout(VulkanLiquidStateLayout source, int limbCapacity, int quantumExponent,
                         long scratchBase, long alignment) {
        if (limbCapacity < 0 || scratchBase < 0 || alignment < 8 || (alignment & (alignment - 1)) != 0)
            throw new IllegalArgumentException("Invalid cell volume layout");
        long[] cursor = {scratchBase};int cells = source.cellCount();
        var header = column(cursor, 32, 4, alignment);
        var offsets = column(cursor, Math.addExact(cells, 1), 4, alignment);
        var signs = column(cursor, cells, 4, alignment);
        var exponents = column(cursor, cells, 4, alignment);
        var limbs = column(cursor, limbCapacity, 4, alignment);
        var quantized = column(cursor, cells, 8, alignment);
        var measurements = column(cursor, Math.multiplyExact(cells, 8), 4, alignment);
        var counts = column(cursor, cells, 4, alignment);
        var scan = column(cursor, Math.toIntExact(VulkanPrefixScan.workspaceBytes(cells) / 4), 4, alignment);
        return new Layout(source, limbCapacity, quantumExponent, header, offsets, signs, exponents, limbs,
                quantized, measurements, counts, scan, align(cursor[0], alignment));
    }
    private final FlipKernelBindings bindings;
    private final VulkanPrefixScan scan;
    VulkanLiquidCellVolumes(VulkanDeviceContext context) {
        bindings = new FlipKernelBindings(context, 3, shader(), 12);
        try { scan = new VulkanPrefixScan(context); }
        catch (RuntimeException | LinkageError failure) {
            try { bindings.close(); } catch (RuntimeException | LinkageError closing) { failure.addSuppressed(closing); }
            throw failure;
        }
    }
    void rebindRanges(FlipKernelBindings.BufferRange source, FlipKernelBindings.BufferRange candidate,
                      FlipKernelBindings.BufferRange errors) {
        bindings.rebindRanges(List.of(source, candidate, errors));
        scan.rebindRanges(candidate, candidate, candidate, errors);
    }
    void record(VkCommandBuffer command, Layout layout) {
        stage(command, layout, 0);
        scan.record(command, layout.source().cellCount(), word(layout.counts()), word(layout.totalOffsets()), word(layout.scanWorkspace()));
        stage(command, layout, 1);
    }
    private void stage(VkCommandBuffer command, Layout layout, int stage) {
        int count = layout.source().cellCount(), groups = (int) (((long) count + 127) / 128);
        try (var stack = stackPush()) {
            bindings.bind(command, stack);
            vkCmdPushConstants(command, bindings.pipelineLayout(), VK_SHADER_STAGE_COMPUTE_BIT, 0,
                    stack.ints(layout.headerWordOffset(), stage, count));
            vkCmdDispatch(command, Math.min(groups, 65535), (groups + 65534) / 65535, 1);
            barrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT, stack);
        }
    }
    private static int word(VulkanLiquidStateLayout.Column column) { return Math.toIntExact(column.offset() / 4); }
    private static long align(long value, long alignment) { return Math.addExact(value, alignment - 1) & -alignment; }
    private static VulkanLiquidStateLayout.Column column(long[] cursor, int count, int width, long alignment) {
        var result = new VulkanLiquidStateLayout.Column(align(cursor[0], alignment), count, width);
        cursor[0] = Math.addExact(result.offset(), result.bindingBytes()); return result;
    }
    private static String shader() {
        try (var input = VulkanLiquidCellVolumes.class.getResourceAsStream("/assets/mmm_gpu_probe/shaders/liquid-cell-volumes.comp")) {
            if (input == null) throw new IllegalStateException("Cell volume shader missing");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) { throw new IllegalStateException("Cell volume shader unavailable", failure); }
    }
    @Override public void close() { try { scan.close(); } finally { bindings.close(); } }
}

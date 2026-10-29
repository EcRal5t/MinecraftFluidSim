package mmm.gpu;

import org.lwjgl.vulkan.VkCommandBuffer;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static mmm.gpu.VulkanRegionStorage.barrier;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Exact per-cell volume totals and integer capacity supplies, recorded entirely on device. */
final class VulkanLiquidCellVolumes implements AutoCloseable {
    /** Word offsets of the shared exact-volume header. */
    private enum Header {
        CELL_COUNT(0), LIMB_CAPACITY(1), QUANTUM_EXPONENT(2), TOTAL_OFFSETS(3),
        TOTAL_SIGNS(4), TOTAL_EXPONENTS(5), TOTAL_LIMBS(6), QUANTIZED(7),
        MEASUREMENTS(8), COUNTS(9), SOURCE_CELL_OFFSETS(16), SOURCE_VOLUME_OFFSETS(17),
        SOURCE_VOLUME_COUNTS(18), SOURCE_VOLUME_EXPONENTS(19), SOURCE_VOLUME_LIMBS(20);

        final int word;
        Header(int word) { this.word = word; }
    }

    private static String shaderLayout() {
        var definitions = new StringBuilder();
        for (var field : Header.values())
            definitions.append("#define H_").append(field.name()).append(' ').append(field.word).append('\n');
        return definitions.toString();
    }
    record Layout(VulkanLiquidStateLayout source, int limbCapacity, int quantumExponent,
                  VulkanLiquidStateLayout.Column header, VulkanLiquidStateLayout.Column totalOffsets,
                  VulkanLiquidStateLayout.Column totalSigns, VulkanLiquidStateLayout.Column totalExponents,
                  VulkanLiquidStateLayout.Column totalLimbs, VulkanLiquidStateLayout.Column quantized,
                  VulkanLiquidStateLayout.Column measurements, VulkanLiquidStateLayout.Column counts,
                  VulkanLiquidStateLayout.Column scanWorkspace, long endOffset) {
        int headerWordOffset() { return word(header); }
        long bytes() { return endOffset - header.offset(); }
        void initializeHeader(ByteBuffer destination) {
            destination.duplicate().position(Math.toIntExact(header.offset())).put(headerBytes());
        }
        ByteBuffer headerBytes() { return headerBytes(source); }
        ByteBuffer headerBytes(VulkanLiquidStateLayout source) {
            var target = ByteBuffer.allocateDirect(Math.toIntExact(header.bytes())).order(ByteOrder.LITTLE_ENDIAN);
            int[] data = new int[32];
            data[Header.CELL_COUNT.word] = source.cellCount();
            data[Header.LIMB_CAPACITY.word] = limbCapacity;
            data[Header.QUANTUM_EXPONENT.word] = quantumExponent;
            data[Header.TOTAL_OFFSETS.word] = word(totalOffsets);
            data[Header.TOTAL_SIGNS.word] = word(totalSigns);
            data[Header.TOTAL_EXPONENTS.word] = word(totalExponents);
            data[Header.TOTAL_LIMBS.word] = word(totalLimbs);
            data[Header.QUANTIZED.word] = word(quantized);
            data[Header.MEASUREMENTS.word] = word(measurements);
            data[Header.COUNTS.word] = word(counts);
            data[Header.SOURCE_CELL_OFFSETS.word] = word(source.column(VulkanLiquidStateLayout.Field.CELL_OFFSETS));
            data[Header.SOURCE_VOLUME_OFFSETS.word] = word(source.column(VulkanLiquidStateLayout.Field.VOLUME_OFFSETS));
            data[Header.SOURCE_VOLUME_COUNTS.word] = word(source.column(VulkanLiquidStateLayout.Field.VOLUME_COUNTS));
            data[Header.SOURCE_VOLUME_EXPONENTS.word] = word(source.column(VulkanLiquidStateLayout.Field.VOLUME_EXPONENTS));
            data[Header.SOURCE_VOLUME_LIMBS.word] = word(source.column(VulkanLiquidStateLayout.Field.VOLUME_LIMBS));
            for (int i = 0; i < data.length; i++) target.putInt(Math.toIntExact(0 + 4L * i), data[i]);

            return target;
        }
    }
    /** quantumExponent=-40 gives ceil(totalVolume * 2^40) for capacity accounting. */
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
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("// HOST_LAYOUT", shaderLayout());
        } catch (IOException failure) { throw new IllegalStateException("Cell volume shader unavailable", failure); }
    }
    @Override public void close() { try { scan.close(); } finally { bindings.close(); } }
}

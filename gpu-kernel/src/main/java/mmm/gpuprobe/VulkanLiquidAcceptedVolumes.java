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

/** Converts accepted integer cell routes to exact volumes, retaining the entire sub-quantum tail. */
final class VulkanLiquidAcceptedVolumes implements AutoCloseable {
    record Layout(VulkanLiquidCellVolumes.Layout sourceCellVolumes, int acceptedWordOffset, int limbCapacity,
                  VulkanLiquidStateLayout.Column header, VulkanLiquidStateLayout.Column descriptors,
                  VulkanLiquidStateLayout.Column volumeOffsets, VulkanLiquidStateLayout.Column volumeSigns,
                  VulkanLiquidStateLayout.Column volumeExponents, VulkanLiquidStateLayout.Column volumeLimbs,
                  VulkanLiquidStateLayout.Column counts, VulkanLiquidStateLayout.Column measurements,
                  VulkanLiquidStateLayout.Column scanWorkspace, long endOffset) {
        int headerWordOffset() { return word(header); }
        int cells() { return sourceCellVolumes.source().cellCount(); }
        int routeCount() { return counts.count(); }
        long bytes() { return endOffset - header.offset(); }
        /** Descriptors are {bank=1, absolute candidate word offset, limb count, exponent, sign}. */
        void initializeHeader(ByteBuffer destination) {
            var target = destination.duplicate().order(ByteOrder.LITTLE_ENDIAN);
            int[] data = new int[32];
            data[0] = cells(); data[1] = acceptedWordOffset; data[2] = sourceCellVolumes.quantumExponent();
            data[3] = word(sourceCellVolumes.quantized()); data[4] = word(sourceCellVolumes.totalOffsets());
            data[5] = word(sourceCellVolumes.totalSigns()); data[6] = word(sourceCellVolumes.totalExponents());
            data[7] = word(sourceCellVolumes.totalLimbs()); data[8] = word(volumeOffsets); data[9] = word(volumeSigns);
            data[10] = word(volumeExponents); data[11] = word(volumeLimbs); data[12] = word(descriptors);
            data[13] = word(counts); data[14] = word(measurements); data[15] = limbCapacity;
            for (int i = 0; i < data.length; i++) target.putInt(Math.toIntExact(header.offset() + 4L * i), data[i]);
        }
    }
    static Layout layout(VulkanLiquidCellVolumes.Layout sourceCellVolumes, int acceptedWordOffset,
                         long scratchBase, long alignment, int limbCapacity) {
        if (acceptedWordOffset < 0 || limbCapacity < 0 || scratchBase < sourceCellVolumes.endOffset()
                || alignment < 8 || (alignment & (alignment - 1)) != 0)
            throw new IllegalArgumentException("Invalid accepted cell volume layout");
        long[] cursor = {scratchBase};int cells = sourceCellVolumes.source().cellCount(), routes = Math.multiplyExact(cells, 7);
        var header = column(cursor, 32, 4, alignment);
        var descriptors = column(cursor, Math.multiplyExact(routes, 5), 4, alignment);
        var offsets = column(cursor, Math.addExact(routes, 1), 4, alignment);
        var signs = column(cursor, routes, 4, alignment);
        var exponents = column(cursor, routes, 4, alignment);
        var limbs = column(cursor, limbCapacity, 4, alignment);
        var counts = column(cursor, routes, 4, alignment);
        var measurements = column(cursor, Math.multiplyExact(cells, 8), 4, alignment);
        var scan = column(cursor, Math.toIntExact(VulkanPrefixScan.workspaceBytes(routes) / 4), 4, alignment);
        return new Layout(sourceCellVolumes, acceptedWordOffset, limbCapacity, header, descriptors, offsets, signs,
                exponents, limbs, counts, measurements, scan, align(cursor[0], alignment));
    }
    private final FlipKernelBindings bindings;
    private final VulkanPrefixScan scan;
    VulkanLiquidAcceptedVolumes(VulkanDeviceContext context) {
        bindings = new FlipKernelBindings(context, 2,
                resource("liquid-accepted-volumes.comp").replace("// DYADIC_LIBRARY", resource("liquid-dyadic.glsl")), 12);
        try { scan = new VulkanPrefixScan(context); }
        catch (RuntimeException | LinkageError failure) {
            try { bindings.close(); } catch (RuntimeException | LinkageError closing) { failure.addSuppressed(closing); }
            throw failure;
        }
    }
    void rebindRanges(FlipKernelBindings.BufferRange candidate, FlipKernelBindings.BufferRange errors) {
        bindings.rebindRanges(List.of(candidate, errors));
        scan.rebindRanges(candidate, candidate, candidate, errors);
    }
    void record(VkCommandBuffer command, Layout layout) {
        stage(command, layout, 0);
        scan.record(command, layout.routeCount(), word(layout.counts()), word(layout.volumeOffsets()), word(layout.scanWorkspace()));
        stage(command, layout, 1);
    }
    private void stage(VkCommandBuffer command, Layout layout, int stage) {
        int count = layout.cells(), groups = (int) (((long) count + 127) / 128);
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
    private static String resource(String name) {
        try (var input = VulkanLiquidAcceptedVolumes.class.getResourceAsStream("/assets/mmm_gpu_probe/shaders/" + name)) {
            if (input == null) throw new IllegalStateException("Accepted volume shader missing: " + name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) { throw new IllegalStateException("Accepted volume shader unavailable", failure); }
    }
    @Override public void close() { try { scan.close(); } finally { bindings.close(); } }
}

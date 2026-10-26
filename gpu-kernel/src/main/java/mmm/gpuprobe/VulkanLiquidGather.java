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

/** Gathers already accepted stock routes into a canonical resident liquid inventory. */
final class VulkanLiquidGather implements AutoCloseable {
    private static final int HEADER_WORDS = 80;

    record Layout(VulkanLiquidStateLayout source, VulkanLiquidStateLayout output,
                  VulkanLiquidRouteLayout routes, int fixedMaskWordOffset, int speciesMassWordOffset,
                  VulkanLiquidStateLayout.Column header,
                  VulkanLiquidStateLayout.Column members, VulkanLiquidStateLayout.Column speciesCounts,
                  VulkanLiquidStateLayout.Column dyadicTerms, VulkanLiquidStateLayout.Column dyadicResults,
                  VulkanLiquidStateLayout.Column energyRemainders,
                  VulkanLiquidStateLayout.Column scanWorkspace, long endOffset) {
        int headerWordOffset() { return word(header); }
        long bytes() { return endOffset - header.offset(); }

        /** Writes immutable descriptors into the candidate arena without changing its buffer position. */
        void initializeHeader(ByteBuffer destination) {
            destination.duplicate().position(Math.toIntExact(header.offset())).put(headerBytes());
        }
        ByteBuffer headerBytes() { return headerBytes(source); }
        ByteBuffer headerBytes(VulkanLiquidStateLayout source) {
            var target = ByteBuffer.allocateDirect(Math.toIntExact(header.bytes())).order(ByteOrder.LITTLE_ENDIAN);
            int[] values = new int[HEADER_WORDS];
            values[0] = output.sizeX(); values[1] = output.sizeY(); values[2] = output.sizeZ();
            values[3] = output.cellCount(); values[4] = output.stockCapacity();
            values[5] = output.speciesCapacity(); values[6] = output.limbCapacity();
            values[7] = fixedMaskWordOffset; values[8] = speciesMassWordOffset;
            values[9] = output.materialCount(); values[10] = word(members); values[11] = word(speciesCounts);
            values[12] = word(dyadicTerms); values[13] = word(dyadicResults); values[14] = word(energyRemainders);
            values[15] = Math.multiplyExact(output.cellCount(),output.materialCount());
            for (var field : VulkanLiquidStateLayout.Field.values()) {
                values[16 + field.ordinal()] = word(source.column(field));
                values[35 + field.ordinal()] = word(output.column(field));
            }
            for (var field : VulkanLiquidRouteLayout.Field.values()) values[54 + field.ordinal()] = word(routes.column(field));
            for (int i = 0; i < values.length; i++) target.putInt(Math.toIntExact(0 + 4L * i), values[i]);
        
            return target;
        }

        VulkanDyadicSum.Layout sums() {
            return new VulkanDyadicSum.Layout(output.stockCapacity(), word(dyadicTerms), word(dyadicResults),
                    word(output.column(VulkanLiquidStateLayout.Field.VOLUME_COUNTS)), word(output.column(VulkanLiquidStateLayout.Field.VOLUME_OFFSETS)),
                    word(output.column(VulkanLiquidStateLayout.Field.VOLUME_LIMBS)), output.limbCapacity(), word(scanWorkspace));
        }
    }

    static Layout layout(VulkanLiquidStateLayout source, VulkanLiquidStateLayout output, VulkanLiquidRouteLayout routes,
                         int fixedMaskWordOffset, int speciesMassWordOffset, long scratchBase, long alignment) {
        if (source.sizeX() != output.sizeX() || source.sizeY() != output.sizeY() || source.sizeZ() != output.sizeZ()
                || output.materialCount()<1 || source.materialCount()!=output.materialCount()
                || routes.sourceStockCapacity() < source.stockCapacity() || scratchBase < output.bytes()
                || scratchBase < routes.endOffset() || alignment < 8 || (alignment & (alignment - 1)) != 0)
            throw new IllegalArgumentException("Liquid gather layouts overlap or differ in grid/capacity");
        int stocks = output.stockCapacity();
        long[] cursor = {scratchBase};
        var header = column(cursor, HEADER_WORDS, 4, alignment);
        var members = column(cursor, Math.multiplyExact(stocks, 7), 4, alignment);
        var speciesCounts = column(cursor, stocks, 4, alignment);
        var terms = column(cursor, Math.multiplyExact(stocks, 35), 4, alignment);
        var results = column(cursor, Math.multiplyExact(stocks, 8), 4, alignment);
        var remainders = column(cursor, Math.multiplyExact(stocks, 7), 8, alignment);
        long scanBytes = VulkanPrefixScan.workspaceBytes(stocks);
        var workspace = column(cursor, Math.toIntExact(scanBytes / 4), 4, alignment);
        return new Layout(source, output, routes, fixedMaskWordOffset, speciesMassWordOffset, header,
                members, speciesCounts, terms, results, remainders, workspace, align(cursor[0], alignment));
    }

    private final FlipKernelBindings bindings;
    private final VulkanPrefixScan scan;
    private final VulkanDyadicSum sums;

    VulkanLiquidGather(VulkanDeviceContext context) {
        bindings = new FlipKernelBindings(context, 3, shader(), 12);
        VulkanPrefixScan createdScan = null;
        try {
            createdScan = new VulkanPrefixScan(context);
            sums = new VulkanDyadicSum(context);
            scan = createdScan;
        } catch (RuntimeException | LinkageError failure) {
            if (createdScan != null) try { createdScan.close(); } catch (RuntimeException | LinkageError closing) { failure.addSuppressed(closing); }
            try { bindings.close(); } catch (RuntimeException | LinkageError closing) { failure.addSuppressed(closing); }
            throw failure;
        }
    }

    void rebindRanges(FlipKernelBindings.BufferRange source, FlipKernelBindings.BufferRange candidate,
                      FlipKernelBindings.BufferRange errors) {
        bindings.rebindRanges(List.of(source, candidate, errors));
        scan.rebindRanges(candidate, candidate, candidate, errors);
        sums.rebindRanges(source, candidate, errors);
    }

    /** Capacity acceptance precedes this pass. Momentum and the retained energy remainders have separate owners. */
    void record(VkCommandBuffer command, Layout layout) {
        int stocks = layout.output().stockCapacity(), slots=Math.multiplyExact(layout.output().cellCount(),layout.output().materialCount());
        stage(command, layout, 0, stocks);
        stage(command, layout, 1, slots);
        scan.record(command, stocks, word(layout.speciesCounts()), word(layout.output().column(VulkanLiquidStateLayout.Field.SPECIES_OFFSETS)),
                word(layout.scanWorkspace()));
        sums.recordSlots(command, layout.sums());
        stage(command, layout, 3, Math.max(1, stocks));
    }

    private void stage(VkCommandBuffer command, Layout layout, int stage, int count) {
        if (count == 0) return;
        int groups = (int) (((long) count + 127) / 128);
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
        var column = new VulkanLiquidStateLayout.Column(align(cursor[0], alignment), count, width);
        cursor[0] = Math.addExact(column.offset(), column.bindingBytes());
        return column;
    }
    private static String shader() {
        try (var input = VulkanLiquidGather.class.getResourceAsStream("/assets/mmm_gpu_probe/shaders/liquid-gather.comp")) {
            if (input == null) throw new IllegalStateException("Liquid gather shader missing");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) { throw new IllegalStateException("Liquid gather shader unavailable", failure); }
    }
    @Override public void close() {
        try { sums.close(); } finally { try { scan.close(); } finally { bindings.close(); } }
    }
}

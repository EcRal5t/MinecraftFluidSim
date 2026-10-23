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

/** Partitions stock quantities over already accepted exact route volumes without changing those volumes. */
final class VulkanLiquidRouteQuantities implements AutoCloseable {
    record Layout(VulkanLiquidStateLayout source, VulkanLiquidRouteLayout routes,
                  int materialReferenceWordOffset, int speciesReferenceWordOffset, int speciesStride,
                  VulkanLiquidStateLayout.Column header, VulkanLiquidStateLayout.Column speciesCounts,
                  VulkanLiquidStateLayout.Column energyRemainders, VulkanLiquidStateLayout.Column scanWorkspace,
                  long endOffset) {
        int headerWordOffset() { return word(header); }
        long bytes() { return endOffset - header.offset(); }

        /** Reference tables contain double J/kg and material-major double J/species quantum. */
        void initializeHeader(ByteBuffer destination) {
            destination.duplicate().position(Math.toIntExact(header.offset())).put(headerBytes());
        }
        ByteBuffer headerBytes() { return headerBytes(source); }
        ByteBuffer headerBytes(VulkanLiquidStateLayout source) {
            var target = ByteBuffer.allocateDirect(Math.toIntExact(header.bytes())).order(ByteOrder.LITTLE_ENDIAN);
            int[] values = new int[48];
            values[0] = source.stockCapacity(); values[1] = routes.routeCapacity(); values[2] = routes.speciesCapacity();
            values[3] = materialReferenceWordOffset; values[4] = speciesReferenceWordOffset; values[5] = speciesStride;
            values[6] = word(speciesCounts); values[7] = word(energyRemainders);
            for (var field : VulkanLiquidStateLayout.Field.values()) values[16 + field.ordinal()] = word(source.column(field));
            for (var field : VulkanLiquidRouteLayout.Field.values()) values[35 + field.ordinal()] = word(routes.column(field));
            for (int i = 0; i < values.length; i++) target.putInt(Math.toIntExact(0 + 4L * i), values[i]);
        
            return target;
        }
    }

    static Layout layout(VulkanLiquidStateLayout source, VulkanLiquidRouteLayout routes,
                         int materialReferenceWordOffset, int speciesReferenceWordOffset, int speciesStride,
                         long scratchBase, long alignment) {
        if (routes.sourceStockCapacity() < source.stockCapacity() || scratchBase < routes.endOffset()
                || alignment < 8 || (alignment & (alignment - 1)) != 0 || speciesStride < 0)
            throw new IllegalArgumentException("Invalid liquid quantity route layout");
        long[] cursor = {scratchBase};
        var header = column(cursor, 48, 4, alignment);
        var counts = column(cursor, routes.routeCapacity(), 4, alignment);
        var remainders = column(cursor, Math.multiplyExact(source.stockCapacity(), 8), 8, alignment);
        var workspace = column(cursor, Math.toIntExact(VulkanPrefixScan.workspaceBytes(routes.routeCapacity()) / 4), 4, alignment);
        return new Layout(source, routes, materialReferenceWordOffset, speciesReferenceWordOffset, speciesStride,
                header, counts, remainders, workspace, align(cursor[0], alignment));
    }

    private final FlipKernelBindings bindings;
    private final VulkanPrefixScan scan;

    VulkanLiquidRouteQuantities(VulkanDeviceContext context) {
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
        stage(command, layout, 0, Math.max(layout.routes().routeCapacity(), layout.energyRemainders().count()));
        stage(command, layout, 1, layout.source().stockCapacity());
        scan.record(command, layout.routes().routeCapacity(), word(layout.speciesCounts()),
                word(layout.routes().column(VulkanLiquidRouteLayout.Field.SPECIES_OFFSETS)), word(layout.scanWorkspace()));
        stage(command, layout, 2, layout.source().stockCapacity());
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
        try (var input = VulkanLiquidRouteQuantities.class.getResourceAsStream("/assets/mmm_gpu_probe/shaders/liquid-route-quantities.comp")) {
            if (input == null) throw new IllegalStateException("Liquid route quantities shader missing");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) { throw new IllegalStateException("Liquid route quantities shader unavailable", failure); }
    }
    @Override public void close() { try { scan.close(); } finally { bindings.close(); } }
}

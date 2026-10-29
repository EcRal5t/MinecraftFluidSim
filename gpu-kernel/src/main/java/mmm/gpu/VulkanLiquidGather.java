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

/** Gathers already accepted stock routes into a canonical resident liquid inventory. */
final class VulkanLiquidGather implements AutoCloseable {
    /** Header words and shared stock/route columns used by the shader. */
    private enum Header {
        SIZE_X(0),
        SIZE_Y(1),
        SIZE_Z(2),
        CELL_COUNT(3),
        STOCK_CAPACITY(4),
        SPECIES_CAPACITY(5),
        LIMB_CAPACITY(6),
        FIXED_MASK(7),
        SPECIES_MASS(8),
        MATERIAL_COUNT(9),
        MEMBERS(10),
        SPECIES_COUNTS(11),
        ENERGY_REMAINDERS(14),
        SLOT_COUNT(15);
        final int word;
        Header(int word) { this.word = word; }
    }
    private static final int SOURCE_FIELDS = 16, TARGET_FIELDS = 35, ROUTE_FIELDS = 54;
    private static String shaderLayout() {
        var result = new StringBuilder();
        for (var field : Header.values())
            result.append("#define H_").append(field.name()).append(' ').append(field.word).append('\n');
        result.append("#define SOURCE_FIELDS ").append(SOURCE_FIELDS).append('\n');
        result.append("#define TARGET_FIELDS ").append(TARGET_FIELDS).append('\n');
        result.append("#define ROUTE_FIELDS ").append(ROUTE_FIELDS).append('\n');
        return result + VulkanShaderLayout.fields("STOCK_", VulkanLiquidStateLayout.Field.values())
            + VulkanShaderLayout.fields("ROUTE_", VulkanLiquidRouteLayout.Field.values());
    }

    private static final int HEADER_WORDS = 80;

    record Layout(VulkanLiquidStateLayout source, VulkanLiquidStateLayout output,
                  VulkanLiquidRouteLayout routes, int fixedMaskWordOffset, int speciesMassWordOffset,
                  VulkanLiquidStateLayout.Column header,
                  VulkanLiquidStateLayout.Column members, VulkanLiquidStateLayout.Column speciesCounts,
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
            values[Header.SIZE_X.word] = output.sizeX(); values[Header.SIZE_Y.word] = output.sizeY(); values[Header.SIZE_Z.word] = output.sizeZ();
            values[Header.CELL_COUNT.word] = output.cellCount(); values[Header.STOCK_CAPACITY.word] = output.stockCapacity();
            values[Header.SPECIES_CAPACITY.word] = output.speciesCapacity(); values[Header.LIMB_CAPACITY.word] = output.limbCapacity();
            values[Header.FIXED_MASK.word] = fixedMaskWordOffset; values[Header.SPECIES_MASS.word] = speciesMassWordOffset;
            values[Header.MATERIAL_COUNT.word] = output.materialCount(); values[Header.MEMBERS.word] = word(members); values[Header.SPECIES_COUNTS.word] = word(speciesCounts);
            values[Header.ENERGY_REMAINDERS.word] = word(energyRemainders);
            values[Header.SLOT_COUNT.word] = Math.multiplyExact(output.cellCount(),output.materialCount());
            for (var field : VulkanLiquidStateLayout.Field.values()) {
                values[SOURCE_FIELDS + field.ordinal()] = word(source.column(field));
                values[TARGET_FIELDS + field.ordinal()] = word(output.column(field));
            }
            for (var field : VulkanLiquidRouteLayout.Field.values()) values[ROUTE_FIELDS + field.ordinal()] = word(routes.column(field));
            for (int i = 0; i < values.length; i++) target.putInt(Math.toIntExact(0 + 4L * i), values[i]);

            return target;
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
        var remainders = column(cursor, Math.multiplyExact(stocks, 7), 8, alignment);
        long scanBytes = VulkanPrefixScan.workspaceBytes(stocks);
        var workspace = column(cursor, Math.toIntExact(scanBytes / 4), 4, alignment);
        return new Layout(source, output, routes, fixedMaskWordOffset, speciesMassWordOffset, header,
                members, speciesCounts, remainders, workspace, align(cursor[0], alignment));
    }

    private final FlipKernelBindings bindings;
    private final VulkanPrefixScan scan;

    VulkanLiquidGather(VulkanDeviceContext context) {
        bindings = new FlipKernelBindings(context, 3, shader(), 12);
        try { scan=new VulkanPrefixScan(context); }
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

    /** Capacity acceptance precedes this pass. Momentum and the retained energy remainders have separate owners. */
    void record(VkCommandBuffer command, Layout layout) {
        int stocks = layout.output().stockCapacity(), slots=Math.multiplyExact(layout.output().cellCount(),layout.output().materialCount());
        stage(command, layout, 0, stocks);
        stage(command, layout, 1, slots);
        scan.record(command, stocks, word(layout.speciesCounts()), word(layout.output().column(VulkanLiquidStateLayout.Field.SPECIES_OFFSETS)),
                word(layout.scanWorkspace()));
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
            try(var library=VulkanLiquidGather.class.getResourceAsStream("/assets/mmm_gpu_probe/shaders/liquid-dyadic.glsl")){
                if(library==null)throw new IllegalStateException("Liquid dyadic shader missing");
                return new String(input.readAllBytes(),StandardCharsets.UTF_8).replace("// HOST_LAYOUT", shaderLayout())
                        .replace("// DYADIC_LIBRARY",new String(library.readAllBytes(),StandardCharsets.UTF_8));
            }
        } catch (IOException failure) { throw new IllegalStateException("Liquid gather shader unavailable", failure); }
    }
    @Override public void close() {
        try { scan.close(); } finally { bindings.close(); }
    }
}

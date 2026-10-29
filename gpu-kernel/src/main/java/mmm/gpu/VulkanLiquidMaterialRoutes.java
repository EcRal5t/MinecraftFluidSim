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

/** Capacity-lattice material shares; exact source tails cross only when a layer or source exhausts. */
final class VulkanLiquidMaterialRoutes implements AutoCloseable {
    private static final int STAGE_CLEAR_ROUTES = 0;
    private static final int STAGE_PREPARE_CANDIDATES = 1;
    private static final int STAGE_RESERVE = 5;
    private static final int STAGE_SPLIT_CELL = 2;
    private static String stageDefines() {
        return "#define STAGE_CLEAR_ROUTES " + STAGE_CLEAR_ROUTES + "\n" +
                "#define STAGE_PREPARE_CANDIDATES " + STAGE_PREPARE_CANDIDATES + "\n" +
                "#define STAGE_RESERVE " + STAGE_RESERVE + "\n" +
                "#define STAGE_SPLIT_CELL " + STAGE_SPLIT_CELL + "\n";
    }
    /** Header words and shared stock/route columns used by the shader. */
    private enum Header {
        CELL_COUNT(0),
        STOCK_CAPACITY(1),
        ROUTE_CAPACITY(2),
        ACCEPTED_DESCRIPTORS(3),
        MATERIAL_PHASE(4),
        GRAVITY_SIGN(5),
        SCRATCH_LIMB_CAPACITY(6),
        REMAINING_QUANTA(7),
        RESERVE_COUNTS(8),
        RESERVE_OFFSETS(9),
        SCRATCH_LIMBS(10),
        WEIGHTS(13),
        DENSITIES(14),
        DENSITY_EXPONENTS(15),
        WINDOW_COUNT(60),
        ACTIVE_COUNTS(62),
        QUANTUM_EXPONENT(63);
        final int word;
        Header(int word) { this.word = word; }
    }
    private static final int SOURCE_FIELDS = 16, ROUTE_FIELDS = 35;
    private static String shaderLayout() {
        var result = new StringBuilder(stageDefines());
        for (var field : Header.values())
            result.append("#define H_").append(field.name()).append(' ').append(field.word).append('\n');
        result.append("#define SOURCE_FIELDS ").append(SOURCE_FIELDS).append('\n');
        result.append("#define ROUTE_FIELDS ").append(ROUTE_FIELDS).append('\n');
        return result + VulkanShaderLayout.fields("STOCK_", VulkanLiquidStateLayout.Field.values())
            + VulkanShaderLayout.fields("ROUTE_", VulkanLiquidRouteLayout.Field.values());
    }

    record Layout(VulkanLiquidStateLayout source, VulkanLiquidRouteLayout routes,
                  int acceptedDescriptorsWordOffset, int materialPhaseWordOffset, int gravitySign,
                  int quantumExponent, int scratchLimbCapacity, VulkanLiquidStateLayout.Column header,
                  VulkanLiquidStateLayout.Column reserveCounts, VulkanLiquidStateLayout.Column reserveOffsets,
                  VulkanLiquidStateLayout.Column scratchLimbs,
                  VulkanLiquidStateLayout.Column weights, VulkanLiquidStateLayout.Column remainingQuanta,
                  VulkanLiquidStateLayout.Column densities, VulkanLiquidStateLayout.Column densityExponents,
                  VulkanLiquidStateLayout.Column scanWorkspace,
                  VulkanLiquidStateLayout.Column activeCounts, long endOffset) {
        int headerWordOffset() { return word(header); }
        int windowCount() { return reserveCounts.count(); }
        long bytes() { return endOffset - header.offset(); }
        /** Accepted descriptors are 7 per cell, each {bank, absolute word offset, count, exponent, sign}. */
        void initializeHeader(ByteBuffer destination) {
            destination.duplicate().position(Math.toIntExact(header.offset())).put(headerBytes());
        }
        ByteBuffer headerBytes() { return headerBytes(source); }
        ByteBuffer headerBytes(VulkanLiquidStateLayout source) {
            var target = ByteBuffer.allocateDirect(Math.toIntExact(header.bytes())).order(ByteOrder.LITTLE_ENDIAN);
            int[] data = new int[64];
            data[Header.CELL_COUNT.word] = source.cellCount(); data[Header.STOCK_CAPACITY.word] = source.stockCapacity(); data[Header.ROUTE_CAPACITY.word] = routes.routeCapacity();
            data[Header.ACCEPTED_DESCRIPTORS.word] = acceptedDescriptorsWordOffset; data[Header.MATERIAL_PHASE.word] = materialPhaseWordOffset; data[Header.GRAVITY_SIGN.word] = gravitySign;
            data[Header.SCRATCH_LIMB_CAPACITY.word] = scratchLimbCapacity;data[Header.REMAINING_QUANTA.word]=word(remainingQuanta);
            data[Header.RESERVE_COUNTS.word] = word(reserveCounts); data[Header.RESERVE_OFFSETS.word] = word(reserveOffsets); data[Header.SCRATCH_LIMBS.word] = word(scratchLimbs);
            data[Header.WEIGHTS.word] = word(weights);
            data[Header.DENSITIES.word] = word(densities); data[Header.DENSITY_EXPONENTS.word] = word(densityExponents);
            for (var field : VulkanLiquidStateLayout.Field.values()) data[SOURCE_FIELDS + field.ordinal()] = word(source.column(field));
            for (var field : VulkanLiquidRouteLayout.Field.values()) data[ROUTE_FIELDS + field.ordinal()] = word(routes.column(field));
            data[Header.WINDOW_COUNT.word] = windowCount();data[Header.ACTIVE_COUNTS.word]=word(activeCounts);data[Header.QUANTUM_EXPONENT.word]=quantumExponent;
            for (int i = 0; i < data.length; i++) target.putInt(Math.toIntExact(0 + 4L * i), data[i]);

            return target;
        }
    }

    /** gravitySign is the Y acceleration sign: -1 sinks toward -Y, +1 toward +Y.
     * CPU config.gravity uses positive downward acceleration, so callers pass -signum(config.gravity).
     */
    static Layout layout(VulkanLiquidStateLayout source, VulkanLiquidRouteLayout routes,
                         int acceptedDescriptorsWordOffset, int materialPhaseWordOffset, int gravitySign,
                         int capacityQuantumExponent, int scratchLimbCapacity, long scratchBase, long alignment) {
        if (routes.sourceStockCapacity() < source.stockCapacity() || scratchBase < routes.endOffset()
                || alignment < 8 || (alignment & (alignment - 1)) != 0 || scratchLimbCapacity < 0
                || Math.abs(gravitySign) > 1)
            throw new IllegalArgumentException("Invalid material route layout");
        long[] cursor = {scratchBase};
        int windows = Math.addExact(routes.routeCapacity(), Math.multiplyExact(source.cellCount(), 3));
        var header = column(cursor, 64, 4, alignment);
        var counts = column(cursor, windows, 4, alignment);
        var offsets = column(cursor, Math.addExact(windows, 1), 4, alignment);
        var limbs = column(cursor, scratchLimbCapacity, 4, alignment);
        var weights = column(cursor, source.stockCapacity(), 8, alignment);
        var remainingQuanta=column(cursor,source.stockCapacity(),8,alignment);
        var densities = column(cursor, source.stockCapacity(), 8, alignment);
        var densityExponents = column(cursor, source.stockCapacity(), 8, alignment);
        var activeCounts=column(cursor,source.stockCapacity(),4,alignment);
        var scan = column(cursor, Math.toIntExact(VulkanPrefixScan.workspaceBytes(windows) / 4), 4, alignment);
        return new Layout(source, routes, acceptedDescriptorsWordOffset, materialPhaseWordOffset, gravitySign,
                capacityQuantumExponent,scratchLimbCapacity, header, counts, offsets, limbs, weights, remainingQuanta,
                densities, densityExponents, scan, activeCounts, align(cursor[0], alignment));
    }

    private final FlipKernelBindings bindings;
    private final VulkanPrefixScan scan;
    VulkanLiquidMaterialRoutes(VulkanDeviceContext context) {
        bindings = new FlipKernelBindings(context, 3,
                resource("liquid-material-routes.comp").replace("// HOST_LAYOUT", shaderLayout()).replace("// DYADIC_LIBRARY", resource("liquid-dyadic.glsl").replaceAll("\\b(for|while)\\s*\\(", "[[dont_unroll]] $1(")), 16);
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
    void record(VkCommandBuffer command, Layout layout) { record(command, layout, layout.gravitySign()); }
    void record(VkCommandBuffer command, Layout layout, int gravitySign) {
        record(command,layout,gravitySign,null);
    }
    void record(VkCommandBuffer command, Layout layout, int gravitySign,VulkanStageTimings timings) {
        if (gravitySign < -1 || gravitySign > 1) throw new IllegalArgumentException("Invalid gravity direction");
        timed(command,timings,"material-candidates",()->{
            stage(command,layout,STAGE_CLEAR_ROUTES,layout.source().stockCapacity(),gravitySign);
            stage(command,layout,STAGE_PREPARE_CANDIDATES,layout.source().cellCount(),gravitySign);
            scan.record(command,layout.source().stockCapacity(),word(layout.activeCounts()),word(layout.routes().column(VulkanLiquidRouteLayout.Field.SOURCE_OFFSETS)),word(layout.scanWorkspace()));
        });
        timed(command,timings,"material-reserve",()->stage(command,layout,STAGE_RESERVE,layout.source().cellCount(),gravitySign));
        timed(command,timings,"material-scratch-scan",()->scan.record(command,layout.windowCount(),word(layout.reserveCounts()),word(layout.reserveOffsets()),word(layout.scanWorkspace()),word(layout.routes().column(VulkanLiquidRouteLayout.Field.COUNTS))+1));
        timed(command,timings,"material-split",()->stage(command,layout,STAGE_SPLIT_CELL,layout.source().cellCount(),gravitySign));
    }
    private static void timed(VkCommandBuffer command,VulkanStageTimings timings,String label,Runnable action){if(timings==null)action.run();else timings.record(command,label,action);}
    private void stage(VkCommandBuffer command, Layout layout, int stage, int count, int gravitySign) {
        if (count == 0) return;
        int groups = (int) (((long) count + 127) / 128);
        try (var stack = stackPush()) {
            bindings.bind(command, stack);
            vkCmdPushConstants(command, bindings.pipelineLayout(), VK_SHADER_STAGE_COMPUTE_BIT, 0,
                    stack.ints(layout.headerWordOffset(), stage, count, gravitySign));
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
        try (var input = VulkanLiquidMaterialRoutes.class.getResourceAsStream("/assets/mmm_gpu_probe/shaders/" + name)) {
            if (input == null) throw new IllegalStateException("Material route shader missing: " + name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) { throw new IllegalStateException("Material route shader unavailable", failure); }
    }
    @Override public void close() { try { scan.close(); } finally { bindings.close(); } }
}

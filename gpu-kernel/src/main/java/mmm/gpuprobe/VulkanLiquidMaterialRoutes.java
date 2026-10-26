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

/** Fixed-precision proportional targets split accepted volumes; every stock remainder stays exact. */
final class VulkanLiquidMaterialRoutes implements AutoCloseable {
    record Layout(VulkanLiquidStateLayout source, VulkanLiquidRouteLayout routes,
                  int acceptedDescriptorsWordOffset, int materialPhaseWordOffset, int gravitySign,
                  int proposalFloorExponent, int scratchLimbCapacity, VulkanLiquidStateLayout.Column header,
                  VulkanLiquidStateLayout.Column reserveCounts, VulkanLiquidStateLayout.Column reserveOffsets,
                  VulkanLiquidStateLayout.Column scratchLimbs,
                  VulkanLiquidStateLayout.Column weights,
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
            data[0] = source.cellCount(); data[1] = source.stockCapacity(); data[2] = routes.routeCapacity();
            data[3] = acceptedDescriptorsWordOffset; data[4] = materialPhaseWordOffset; data[5] = gravitySign;
            data[6] = scratchLimbCapacity;
            data[8] = word(reserveCounts); data[9] = word(reserveOffsets); data[10] = word(scratchLimbs);
            data[13] = word(weights);
            data[14] = word(densities); data[15] = word(densityExponents);
            for (var field : VulkanLiquidStateLayout.Field.values()) data[16 + field.ordinal()] = word(source.column(field));
            for (var field : VulkanLiquidRouteLayout.Field.values()) data[35 + field.ordinal()] = word(routes.column(field));
            data[60] = windowCount();data[62]=word(activeCounts);data[63]=proposalFloorExponent;
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
        var densities = column(cursor, source.stockCapacity(), 8, alignment);
        var densityExponents = column(cursor, source.stockCapacity(), 8, alignment);
        var activeCounts=column(cursor,source.stockCapacity(),4,alignment);
        var scan = column(cursor, Math.toIntExact(VulkanPrefixScan.workspaceBytes(windows) / 4), 4, alignment);
        return new Layout(source, routes, acceptedDescriptorsWordOffset, materialPhaseWordOffset, gravitySign,
                Math.subtractExact(capacityQuantumExponent,53),scratchLimbCapacity, header, counts, offsets, limbs, weights,
                densities, densityExponents, scan, activeCounts, align(cursor[0], alignment));
    }

    private final FlipKernelBindings bindings;
    private final VulkanPrefixScan scan;
    VulkanLiquidMaterialRoutes(VulkanDeviceContext context) {
        bindings = new FlipKernelBindings(context, 3,
                resource("liquid-material-routes.comp").replace("// DYADIC_LIBRARY", resource("liquid-dyadic.glsl").replaceAll("\\b(for|while)\\s*\\(", "[[dont_unroll]] $1(")), 16);
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
            stage(command,layout,0,layout.source().stockCapacity(),gravitySign);
            stage(command,layout,1,layout.source().cellCount(),gravitySign);
            scan.record(command,layout.source().stockCapacity(),word(layout.activeCounts()),word(layout.routes().column(VulkanLiquidRouteLayout.Field.SOURCE_OFFSETS)),word(layout.scanWorkspace()));
        });
        timed(command,timings,"material-reserve",()->stage(command,layout,5,layout.source().cellCount(),gravitySign));
        timed(command,timings,"material-scratch-scan",()->scan.record(command,layout.windowCount(),word(layout.reserveCounts()),word(layout.reserveOffsets()),word(layout.scanWorkspace()),word(layout.routes().column(VulkanLiquidRouteLayout.Field.COUNTS))+1));
        timed(command,timings,"material-split",()->stage(command,layout,2,layout.source().cellCount(),gravitySign));
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

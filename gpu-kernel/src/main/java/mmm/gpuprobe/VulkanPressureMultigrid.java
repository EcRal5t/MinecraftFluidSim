package mmm.gpuprobe;

import org.lwjgl.vulkan.VkCommandBuffer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static mmm.gpuprobe.VulkanRegionStorage.barrier;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Symmetric aggregation V-cycle over the resident liquid seven-point pressure operator. */
final class VulkanPressureMultigrid implements AutoCloseable {
    record Level(int sizeX, int sizeY, int sizeZ, int offset) {
        int cells() { return Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ); }
    }

    record Layout(List<Level> levels, int workspaceDoubles) {
        Layout { levels = List.copyOf(levels); }
        long workspaceBytes() { return Math.multiplyExact(8L, workspaceDoubles); }
    }

    private final FlipKernelBindings bindings;

    VulkanPressureMultigrid(VulkanDeviceContext context) {
        bindings = new FlipKernelBindings(context, 8, shader(), 48);
    }

    /** Each level stores seven matrix entries, the residual, correction and smoothing scratch. */
    static Layout layout(int sx, int sy, int sz) {
        if (sx <= 0 || sy <= 0 || sz <= 0) throw new IllegalArgumentException("Invalid multigrid shape");
        var levels = new ArrayList<Level>();
        int cursor = 0;
        while (true) {
            var level = new Level(sx, sy, sz, cursor);
            levels.add(level);
            cursor = Math.addExact(cursor, Math.multiplyExact(10, level.cells()));
            if (Math.max(sx, Math.max(sy, sz)) <= 4) break;
            sx = (sx + 1) / 2; sy = (sy + 1) / 2; sz = (sz + 1) / 2;
        }
        return new Layout(levels, cursor);
    }

    /** Bind starts, columns, values, rows, pressure metadata, pressure work, control and this workspace. */
    void rebindRanges(List<FlipKernelBindings.BufferRange> buffers) { bindings.rebindRanges(buffers); }

    /** Call after FREE has been prepared; the device rebuilds only when PCG restarts. */
    void recordBuild(VkCommandBuffer command, Layout layout) {
        bind(command);
        var levels = layout.levels();
        stage(command, layout, levels.getFirst(), levels.getFirst(), 0);
        for (int i = 1; i < levels.size(); i++) stage(command, layout, levels.get(i), levels.get(i - 1), 1);
    }

    /** A zero-start, fixed linear V-cycle; all work stays inside the caller's submission. */
    void recordApply(VkCommandBuffer command, Layout layout) {
        bind(command);
        var levels = layout.levels();
        stage(command, layout, levels.getFirst(), levels.getFirst(), 2);
        for (int i = 1; i < levels.size(); i++) stage(command, layout, levels.get(i), levels.get(i - 1), 3);
        stage(command, layout, levels.getLast(), levels.getLast(), 4);
        for (int i = levels.size() - 2; i >= 0; i--) {
            stage(command, layout, levels.get(i), levels.get(i + 1), 5);
            stage(command, layout, levels.get(i), levels.get(i), 6);
        }
        stage(command, layout, levels.getFirst(), levels.getFirst(), 7);
    }

    private void bind(VkCommandBuffer command) {
        try (var stack = stackPush()) { bindings.bind(command, stack); }
    }

    private void stage(VkCommandBuffer command, Layout layout, Level level, Level peer, int stage) {
        try (var stack = stackPush()) {
            vkCmdPushConstants(command, bindings.pipelineLayout(), VK_SHADER_STAGE_COMPUTE_BIT, 0,
                    stack.ints(level.sizeX(), level.sizeY(), level.sizeZ(), stage,
                            level.offset(), peer.offset(), peer.sizeX(), peer.sizeY(), peer.sizeZ(),
                            layout.levels().getFirst().cells(), 0, 0));
            vkCmdDispatch(command, stage == 4 ? 1 : (level.cells() + 127) / 128, 1, 1);
            barrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT, stack);
        }
    }

    private static String shader() {
        try (var stream = VulkanPressureMultigrid.class.getResourceAsStream("/assets/mmm_gpu_probe/shaders/pressure-multigrid.comp")) {
            if (stream == null) throw new IllegalStateException("Pressure multigrid shader missing");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) { throw new IllegalStateException("Pressure multigrid shader unavailable", failure); }
    }

    @Override public void close() { bindings.close(); }
}

package mmm.gpuprobe;

import org.lwjgl.vulkan.VkCommandBuffer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static mmm.gpuprobe.VulkanRegionStorage.barrier;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Records the whole pressure solve into its caller's submission using resident buffer ranges. */
final class VulkanPressureKernel implements AutoCloseable {
    private final FlipKernelBindings bindings;
    private final int singleWorkgroupRows;

    VulkanPressureKernel(VulkanDeviceContext context, int singleWorkgroupRows) {
        this.singleWorkgroupRows = singleWorkgroupRows;
        bindings = new FlipKernelBindings(context, 12, shader(), 24);
    }

    void rebind(FlipGpuResources resources) { bindings.rebind(resources); }
    void rebindRanges(List<FlipKernelBindings.BufferRange> buffers) { bindings.rebindRanges(buffers); }

    void record(VkCommandBuffer command, int size, int count, int maximumIterations) {
        record(command, size, count, maximumIterations, null, null);
    }

    void record(VkCommandBuffer command, int size, int count, int maximumIterations,
                VulkanPressureMultigrid multigrid, VulkanPressureMultigrid.Layout hierarchy) {
        if (multigrid == null && size <= singleWorkgroupRows) {
            bind(command);
            stage(command, size, count, maximumIterations, -1, 1, false);
            return;
        }
        recordInitialize(command, size, count, maximumIterations, multigrid, hierarchy);
        recordIterations(command, size, count, maximumIterations, multigrid, hierarchy, maximumIterations);
        recordFinish(command, size, count, maximumIterations, multigrid, hierarchy);
    }

    /** Initializes one solve; later batches retain its pressure, residual and conjugate direction. */
    void recordInitialize(VkCommandBuffer command, int size, int count, int maximumIterations,
                          VulkanPressureMultigrid multigrid, VulkanPressureMultigrid.Layout hierarchy) {
        bind(command);
        int groups = groups(size, multigrid);
        for (int stage = 0; stage <= 3; stage++)
            stage(command, size, count, maximumIterations, stage, groups, multigrid != null);
        precondition(command, size, count, maximumIterations, groups, multigrid, hierarchy);
    }

    /** Records a bounded continuation; the shader retains the original solve-wide iteration limit. */
    void recordIterations(VkCommandBuffer command, int size, int count, int maximumIterations,
                          VulkanPressureMultigrid multigrid, VulkanPressureMultigrid.Layout hierarchy,
                          int iterations) {
        bind(command);
        int groups = groups(size, multigrid);
        for (int iteration = 0; iteration < iterations; iteration++) {
            for (int stage = 7; stage <= 10; stage++)
                stage(command, size, count, maximumIterations, stage, groups, multigrid != null);
            for (int stage = 2; stage <= 3; stage++)
                stage(command, size, count, maximumIterations, stage, groups, multigrid != null);
            precondition(command, size, count, maximumIterations, groups, multigrid, hierarchy);
        }
    }

    /** Recomputes the final residual and publishes the existing pressure completion status. */
    void recordFinish(VkCommandBuffer command, int size, int count, int maximumIterations,
                      VulkanPressureMultigrid multigrid, VulkanPressureMultigrid.Layout hierarchy) {
        bind(command);
        int groups = groups(size, multigrid);
        stage(command, size, count, maximumIterations, 11, groups, multigrid != null);
        stage(command, size, count, maximumIterations, 2, groups, multigrid != null);
        stage(command, size, count, maximumIterations, 3, groups, multigrid != null);
        stage(command, size, count, maximumIterations, 12, groups, multigrid != null);
    }

    private int groups(int size, VulkanPressureMultigrid multigrid) {
        return multigrid == null && size <= singleWorkgroupRows ? 1 : (size + 255) / 256;
    }

    private void bind(VkCommandBuffer command) {
        try (var stack = stackPush()) { bindings.bind(command, stack); }
    }

    private void precondition(VkCommandBuffer command, int size, int count, int maximumIterations, int groups,
                              VulkanPressureMultigrid multigrid, VulkanPressureMultigrid.Layout hierarchy) {
        if (multigrid != null) {
            stage(command, size, count, maximumIterations, 13, groups, true);
            multigrid.recordBuild(command, hierarchy);
            multigrid.recordApply(command, hierarchy);
            bind(command);
        }
        for (int stage = 4; stage <= 6; stage++)
            stage(command, size, count, maximumIterations, stage, groups, multigrid != null);
    }

    private void stage(VkCommandBuffer command, int size, int count, int maximumIterations, int stage, int groups, boolean external) {
        try (var stack = stackPush()) {
            vkCmdPushConstants(command, bindings.pipelineLayout(), VK_SHADER_STAGE_COMPUTE_BIT, 0,
                    stack.ints(count, size, maximumIterations, stage, groups, external ? 1 : 0));
            boolean parallel = stage == 0 || stage == 2 || stage == 4 || stage == 6 || stage == 7 || stage == 9 || stage == 13;
            vkCmdDispatch(command, parallel ? groups : 1, 1, 1);
            barrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT, stack);
        }
    }

    private static String shader() {
        try (var stream = VulkanPressureKernel.class.getResourceAsStream("/assets/mmm_gpu_probe/shaders/pressure.comp")) {
            if (stream == null) throw new IllegalStateException("Pressure shader missing");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) { throw new IllegalStateException("Pressure shader unavailable", failure); }
    }

    @Override public void close() { bindings.close(); }
}

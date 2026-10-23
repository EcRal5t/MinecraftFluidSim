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
    /** One matrix product for the common PCG; the multigrid liquid block is shared by every model. */
    interface PressureOperator {
        int liquidCount();
        int matrixFreeFromRow();
        void recordProduct(VkCommandBuffer command, boolean direction);
    }
    record GasOperator(VulkanGasPressure kernel, VulkanGasPressure.Layout layout) implements PressureOperator {
        @Override public int liquidCount() { return layout.liquidCount(); }
        @Override public int matrixFreeFromRow() { return layout.liquidCount(); }
        @Override public void recordProduct(VkCommandBuffer command, boolean direction) {
            kernel.recordProduct(command, layout, direction);
        }
    }
    private final FlipKernelBindings bindings;
    private final int singleWorkgroupRows;

    VulkanPressureKernel(VulkanDeviceContext context, int singleWorkgroupRows) {
        this.singleWorkgroupRows = singleWorkgroupRows;
        bindings = new FlipKernelBindings(context, 12, shader(), 32);
    }

    void rebind(FlipGpuResources resources) { bindings.rebind(resources); }
    void rebindRanges(List<FlipKernelBindings.BufferRange> buffers) { bindings.rebindRanges(buffers); }

    void record(VkCommandBuffer command, int size, int count, int maximumIterations) {
        record(command, size, count, maximumIterations, null, null);
    }

    void record(VkCommandBuffer command, int size, int count, int maximumIterations,
                VulkanPressureMultigrid multigrid, VulkanPressureMultigrid.Layout hierarchy) {
        record(command, size, count, maximumIterations, multigrid, hierarchy, null);
    }

    void record(VkCommandBuffer command, int size, int count, int maximumIterations,
                VulkanPressureMultigrid multigrid, VulkanPressureMultigrid.Layout hierarchy, PressureOperator gas) {
        if (gas == null && multigrid == null && size <= singleWorkgroupRows) {
            bind(command);
            stage(command, size, count, maximumIterations, -1, 1, false, null);
            return;
        }
        recordInitialize(command, size, count, maximumIterations, multigrid, hierarchy, gas);
        recordIterations(command, size, count, maximumIterations, multigrid, hierarchy, maximumIterations, gas);
        recordFinish(command, size, count, maximumIterations, multigrid, hierarchy, gas);
    }

    /** Initializes one solve; later batches retain its pressure, residual and conjugate direction. */
    void recordInitialize(VkCommandBuffer command, int size, int count, int maximumIterations,
                          VulkanPressureMultigrid multigrid, VulkanPressureMultigrid.Layout hierarchy) {
        recordInitialize(command, size, count, maximumIterations, multigrid, hierarchy, null);
    }

    void recordInitialize(VkCommandBuffer command, int size, int count, int maximumIterations,
                          VulkanPressureMultigrid multigrid, VulkanPressureMultigrid.Layout hierarchy, PressureOperator gas) {
        bind(command);
        int groups = groups(size, multigrid);
        for (int stage = 0; stage <= 3; stage++)
            stage(command, size, count, maximumIterations, stage, groups, multigrid != null, gas);
        precondition(command, size, count, maximumIterations, groups, multigrid, hierarchy, gas);
    }

    /** Records a bounded continuation; the shader retains the original solve-wide iteration limit. */
    void recordIterations(VkCommandBuffer command, int size, int count, int maximumIterations,
                          VulkanPressureMultigrid multigrid, VulkanPressureMultigrid.Layout hierarchy,
                          int iterations) {
        recordIterations(command, size, count, maximumIterations, multigrid, hierarchy, iterations, null);
    }

    void recordIterations(VkCommandBuffer command, int size, int count, int maximumIterations,
                          VulkanPressureMultigrid multigrid, VulkanPressureMultigrid.Layout hierarchy,
                          int iterations, PressureOperator gas) {
        bind(command);
        int groups = groups(size, multigrid);
        for (int iteration = 0; iteration < iterations; iteration++) {
            for (int stage = 7; stage <= 10; stage++)
                stage(command, size, count, maximumIterations, stage, groups, multigrid != null, gas);
            for (int stage = 2; stage <= 3; stage++)
                stage(command, size, count, maximumIterations, stage, groups, multigrid != null, gas);
            precondition(command, size, count, maximumIterations, groups, multigrid, hierarchy, gas);
        }
    }

    /** Recomputes the final residual and publishes the existing pressure completion status. */
    void recordFinish(VkCommandBuffer command, int size, int count, int maximumIterations,
                      VulkanPressureMultigrid multigrid, VulkanPressureMultigrid.Layout hierarchy) {
        recordFinish(command, size, count, maximumIterations, multigrid, hierarchy, null);
    }

    void recordFinish(VkCommandBuffer command, int size, int count, int maximumIterations,
                          VulkanPressureMultigrid multigrid, VulkanPressureMultigrid.Layout hierarchy, PressureOperator gas) {
        bind(command);
        int groups = groups(size, multigrid);
        stage(command, size, count, maximumIterations, 11, groups, multigrid != null, gas);
        stage(command, size, count, maximumIterations, 2, groups, multigrid != null, gas);
        stage(command, size, count, maximumIterations, 3, groups, multigrid != null, gas);
        stage(command, size, count, maximumIterations, 12, groups, multigrid != null, gas);
    }

    private int groups(int size, VulkanPressureMultigrid multigrid) {
        return multigrid == null && size <= singleWorkgroupRows ? 1 : (size + 255) / 256;
    }

    private void bind(VkCommandBuffer command) {
        try (var stack = stackPush()) { bindings.bind(command, stack); }
    }

    private void precondition(VkCommandBuffer command, int size, int count, int maximumIterations, int groups,
                              VulkanPressureMultigrid multigrid, VulkanPressureMultigrid.Layout hierarchy, PressureOperator gas) {
        if (multigrid != null) {
            stage(command, size, count, maximumIterations, 13, groups, true, gas);
            multigrid.recordBuild(command, hierarchy);
            multigrid.recordApply(command, hierarchy);
            bind(command);
        }
        for (int stage = 4; stage <= 6; stage++)
            stage(command, size, count, maximumIterations, stage, groups, multigrid != null, gas);
    }

    private void stage(VkCommandBuffer command, int size, int count, int maximumIterations, int stage, int groups, boolean external, PressureOperator gas) {
        if (gas != null && (stage == 2 || stage == 7)) {
            gas.recordProduct(command, stage == 7);
            bind(command);
        }
        try (var stack = stackPush()) {
            vkCmdPushConstants(command, bindings.pipelineLayout(), VK_SHADER_STAGE_COMPUTE_BIT, 0,
                    stack.ints(count, size, maximumIterations, stage, groups, external ? 1 : 0, gas == null ? size : gas.liquidCount(), gas == null ? size : gas.matrixFreeFromRow()));
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

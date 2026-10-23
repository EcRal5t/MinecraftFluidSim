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
        bindings = new FlipKernelBindings(context, 12, shader(), 20);
    }

    void rebind(FlipGpuResources resources) { bindings.rebind(resources); }
    void rebindRanges(List<FlipKernelBindings.BufferRange> buffers) { bindings.rebindRanges(buffers); }

    void record(VkCommandBuffer command, int size, int count, int maximumIterations) {
        try (var stack = stackPush()) { bindings.bind(command, stack); }
        int groups = size <= singleWorkgroupRows ? 1 : (size + 255) / 256;
        if (groups == 1) stage(command, size, count, maximumIterations, -1, groups);
        else {
            for (int stage = 0; stage <= 6; stage++) stage(command, size, count, maximumIterations, stage, groups);
            for (int iteration = 0; iteration < maximumIterations; iteration++) {
                for (int stage = 7; stage <= 10; stage++) stage(command, size, count, maximumIterations, stage, groups);
                for (int stage = 2; stage <= 6; stage++) stage(command, size, count, maximumIterations, stage, groups);
            }
            stage(command, size, count, maximumIterations, 11, groups);
            stage(command, size, count, maximumIterations, 2, groups);
            stage(command, size, count, maximumIterations, 3, groups);
            stage(command, size, count, maximumIterations, 12, groups);
        }
    }

    private void stage(VkCommandBuffer command, int size, int count, int maximumIterations, int stage, int groups) {
        try (var stack = stackPush()) {
            vkCmdPushConstants(command, bindings.pipelineLayout(), VK_SHADER_STAGE_COMPUTE_BIT, 0,
                    stack.ints(count, size, maximumIterations, stage, groups));
            boolean parallel = stage == 0 || stage == 2 || stage == 4 || stage == 6 || stage == 7 || stage == 9;
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

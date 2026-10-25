package mmm.gpuprobe;

import org.lwjgl.vulkan.VkCommandBuffer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;
import static mmm.gpuprobe.VulkanRegionStorage.barrier;

/** Resident stock totals, coarse PLIC reconstruction and MAC face geometry. */
final class VulkanLiquidGeometry implements AutoCloseable {
    private final FlipKernelBindings bindings;
    VulkanLiquidGeometry(VulkanDeviceContext context) {
        bindings = new FlipKernelBindings(context, 14, shader(), 24);
    }
    void rebindRanges(List<FlipKernelBindings.BufferRange> ranges) { bindings.rebindRanges(ranges); }
    void record(VkCommandBuffer command, int sizeX, int sizeY, int sizeZ) {
        for (int stage = 0; stage < 3; stage++) recordStage(command, sizeX, sizeY, sizeZ, stage, 0, 0);
    }

    /** Reuses reconstructed cell geometry and refreshes velocities from current MAC momentum. */
    void recordFaces(VkCommandBuffer command, int sizeX, int sizeY, int sizeZ) {
        recordStage(command, sizeX, sizeY, sizeZ, 2, 0, 0);
    }

    /** Integrates donor PLIC slabs from projected face velocities; inventory settlement follows separately. */
    void recordTransportPreferences(VkCommandBuffer command, int sizeX, int sizeY, int sizeZ, float h, float dt) {
        recordStage(command, sizeX, sizeY, sizeZ, 3, h, dt);
    }

    private void recordStage(VkCommandBuffer command, int sizeX, int sizeY, int sizeZ, int stage, float h, float dt) {
        int cells = Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ);
        int faces = Math.addExact(Math.addExact(Math.multiplyExact(sizeX + 1, sizeY * sizeZ),
                Math.multiplyExact(sizeY + 1, sizeX * sizeZ)), Math.multiplyExact(sizeZ + 1, sizeX * sizeY));
        try (var stack = stackPush()) {
            bindings.bind(command, stack);
            var constants = stack.malloc(24);
            constants.putInt(sizeX).putInt(sizeY).putInt(sizeZ).putInt(stage).putFloat(h).putFloat(dt).flip();
            vkCmdPushConstants(command, bindings.pipelineLayout(), VK_SHADER_STAGE_COMPUTE_BIT, 0, constants);
            vkCmdDispatch(command, ((stage >= 2 ? faces : cells) + 127) / 128, 1, 1);
            barrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT, stack);
        }
    }
    private static String shader() {
        try (var input = VulkanLiquidGeometry.class.getResourceAsStream("/assets/mmm_gpu_probe/shaders/liquid-geometry.comp")) {
            if (input == null) throw new IllegalStateException("Liquid geometry shader missing");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) { throw new IllegalStateException("Liquid geometry shader unavailable", failure); }
    }
    @Override public void close() { bindings.close(); }
}

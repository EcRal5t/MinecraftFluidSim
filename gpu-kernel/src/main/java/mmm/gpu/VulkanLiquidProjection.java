package mmm.gpu;

import org.lwjgl.vulkan.VkCommandBuffer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static mmm.gpu.VulkanRegionStorage.barrier;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Common face gravity prediction and the regular-grid pressure preconditioner. */
final class VulkanLiquidProjection implements AutoCloseable {
    record Parameters(float cellSize, float duration, float gravity, float tolerance, int iterations) {
        Parameters {
            if (!(Float.isFinite(cellSize) && cellSize > 0 && Float.isFinite(duration) && duration > 0
                    && Float.isFinite(gravity) && Float.isFinite(tolerance) && tolerance > 0 && iterations > 0))
                throw new IllegalArgumentException("Invalid resident mechanical parameters");
        }
    }

    private final FlipKernelBindings bindings;
    VulkanLiquidProjection(VulkanDeviceContext context) {
        bindings = new FlipKernelBindings(context, 12, shader(), 36);
    }
    void rebindRanges(List<FlipKernelBindings.BufferRange> buffers) { bindings.rebindRanges(buffers); }

    /** Coupled incidence applies the reference-pressure force itself. */
    void prepareCoupled(VkCommandBuffer command, int sx, int sy, int sz, Parameters parameters,int pressureRows) {
        stage(command, sx, sy, sz, parameters, 3,pressureRows);
        stage(command, sx, sy, sz, parameters, 1,pressureRows);
    }
    private void stage(VkCommandBuffer command, int sx, int sy, int sz, Parameters p, int stage,int pressureRows) {
        int count = stage == 1 ? bindings.grid().cellCount() : bindings.grid().faceCount();
        try (var stack = stackPush()) {
            bindings.bind(command, stack);
            var constants = stack.malloc(36);
            constants.putInt(sx).putInt(sy).putInt(sz).putInt(stage)
                    .putFloat(p.cellSize()).putFloat(p.duration()).putFloat(p.gravity()).putFloat(p.tolerance()).putInt(pressureRows).flip();
            vkCmdPushConstants(command, bindings.pipelineLayout(), VK_SHADER_STAGE_COMPUTE_BIT, 0, constants);
            vkCmdDispatch(command, (count + 127) / 128, 1, 1);
            barrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT, stack);
        }
    }
    private static String shader() {
        try (var stream = VulkanLiquidProjection.class.getResourceAsStream("/assets/mmm_gpu_probe/shaders/liquid-projection.comp")) {
            if (stream == null) throw new IllegalStateException("Liquid projection shader missing");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) { throw new IllegalStateException("Liquid projection shader unavailable", failure); }
    }
    @Override public void close() { bindings.close(); }
}

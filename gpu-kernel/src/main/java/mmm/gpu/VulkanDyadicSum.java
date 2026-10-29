package mmm.gpu;

import org.lwjgl.vulkan.VkCommandBuffer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static mmm.gpu.VulkanRegionStorage.barrier;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Measures, allocates and writes batches of exact dyadic sums inside the caller's device arenas. */
final class VulkanDyadicSum implements AutoCloseable {
    /** All offsets are uint32 indices relative to the bound candidate arena. Input limbs may use either bank. */
    record Layout(int operations, int termsWordOffset, int resultWordOffset, int countsWordOffset,
                  int offsetsWordOffset, int limbsWordOffset, int limbCapacity, int scanWorkspaceWordOffset) { }

    private final FlipKernelBindings bindings;
    private final VulkanPrefixScan scan;

    VulkanDyadicSum(VulkanDeviceContext context) {
        String library = resource("liquid-dyadic.glsl");
        bindings = new FlipKernelBindings(context, 3,
                resource("liquid-dyadic-sum.comp").replace("// DYADIC_LIBRARY", library), 32);
        try { scan = new VulkanPrefixScan(context); }
        catch (RuntimeException | LinkageError failure) {
            try { bindings.close(); } catch (RuntimeException | LinkageError closing) { failure.addSuppressed(closing); }
            throw failure;
        }
    }

    /** The candidate's terms, results, counts, offsets, limbs and scan scratch occupy disjoint windows. */
    void rebindRanges(FlipKernelBindings.BufferRange source, FlipKernelBindings.BufferRange candidate,
                      FlipKernelBindings.BufferRange errors) {
        bindings.rebindRanges(List.of(source, candidate, errors));
        scan.rebindRanges(candidate, candidate, candidate, errors);
    }

    void record(VkCommandBuffer command, Layout layout) {
        stage(command, layout, 0);
        scan.record(command, layout.operations(), layout.countsWordOffset(), layout.offsetsWordOffset(),
                layout.scanWorkspaceWordOffset());
        stage(command, layout, 1);
    }

    private void stage(VkCommandBuffer command, Layout layout, int stage) {
        if (layout.operations() == 0) return;
        int groups = (int) (((long) layout.operations() + 127) / 128);
        try (var stack = stackPush()) {
            bindings.bind(command, stack);
            vkCmdPushConstants(command, bindings.pipelineLayout(), VK_SHADER_STAGE_COMPUTE_BIT, 0,
                    stack.ints(layout.operations(), layout.termsWordOffset(), layout.resultWordOffset(),
                            layout.countsWordOffset(), layout.offsetsWordOffset(), layout.limbsWordOffset(),
                            layout.limbCapacity(), stage));
            vkCmdDispatch(command, Math.min(groups, 65535), (groups + 65534) / 65535, 1);
            barrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT, stack);
        }
    }

    private static String resource(String name) {
        try (var input = VulkanDyadicSum.class.getResourceAsStream("/assets/mmm_gpu_probe/shaders/" + name)) {
            if (input == null) throw new IllegalStateException("Dyadic shader resource missing: " + name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) { throw new IllegalStateException("Dyadic shader resource unavailable: " + name, failure); }
    }

    @Override public void close() {
        try { scan.close(); } finally { bindings.close(); }
    }
}

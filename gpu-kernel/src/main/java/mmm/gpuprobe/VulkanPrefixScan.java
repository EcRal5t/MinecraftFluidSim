package mmm.gpuprobe;

import org.lwjgl.vulkan.VkCommandBuffer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;
import static mmm.gpuprobe.VulkanRegionStorage.barrier;

/** Records an exclusive uint scan. offsets[n] holds the total; error bit 16 marks index overflow. */
final class VulkanPrefixScan implements AutoCloseable {
    private static final int BLOCK = 256;
    private final FlipKernelBindings bindings;

    VulkanPrefixScan(VulkanDeviceContext context) {
        bindings = new FlipKernelBindings(context, 4, shader(), 36);
    }

    /** Rebind only after commands using the previous descriptors have completed. The addressed input, output and workspace windows must be disjoint. */
    void rebindRanges(FlipKernelBindings.BufferRange input, FlipKernelBindings.BufferRange output,
                      FlipKernelBindings.BufferRange workspace, FlipKernelBindings.BufferRange errors) {
        bindings.rebindRanges(List.of(input, output, workspace, errors));
    }

    /** Scratch storage includes each block-total level. Vulkan descriptors require a nonempty range. */
    static long workspaceBytes(int n) {
        if (n < 0) throw new IllegalArgumentException("Negative scan length");
        long words = 0;
        while (n > BLOCK) { n = blocks(n); words += n; }
        return Math.max(4L, words * Integer.BYTES);
    }

    /** Input: n uints; output: n+1 uints. Caller prepares input visibility and retains all buffers. */
    void record(VkCommandBuffer command, int n) { record(command, n, 0, 0, 0); }

    /** Word offsets allow several scans of disjoint columns without updating recorded descriptors. */
    void record(VkCommandBuffer command, int n, int inputWordOffset, int outputWordOffset, int workspaceWordOffset) {
        if (n < 0 || inputWordOffset < 0 || outputWordOffset < 0 || workspaceWordOffset < 0)
            throw new IllegalArgumentException("Negative scan length or offset");
        var lengths = new ArrayList<Integer>();
        var bases = new ArrayList<Integer>();
        lengths.add(n); bases.add(0);
        int words = 0, length = n;
        while (length > BLOCK) {
            length = blocks(length);
            bases.add(words); lengths.add(length); words = Math.addExact(words, length);
        }
        try (var stack = stackPush()) {
            bindings.bind(command, stack);
            for (int level = 0; level < lengths.size(); level++) {
                int count = lengths.get(level), groups = Math.max(1, blocks(count));
                vkCmdPushConstants(command, bindings.pipelineLayout(), VK_SHADER_STAGE_COMPUTE_BIT, 0,
                        stack.ints(0, count, bases.get(level), level + 1 < lengths.size() ? bases.get(level + 1) : -1,
                                level == 0 ? 1 : 0, n, inputWordOffset, outputWordOffset, workspaceWordOffset));
                vkCmdDispatch(command, Math.min(groups, 65535), (groups + 65534) / 65535, 1);
                barrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT, stack);
            }
            for (int level = lengths.size() - 2; level >= 0; level--) {
                int count = lengths.get(level), groups = blocks(count);
                vkCmdPushConstants(command, bindings.pipelineLayout(), VK_SHADER_STAGE_COMPUTE_BIT, 0,
                        stack.ints(1, count, bases.get(level), bases.get(level + 1), level == 0 ? 1 : 0, n, inputWordOffset, outputWordOffset, workspaceWordOffset));
                vkCmdDispatch(command, Math.min(groups, 65535), (groups + 65534) / 65535, 1);
                barrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT, stack);
            }
        }
    }

    private static int blocks(int n) { return (int) (((long) n + BLOCK - 1) / BLOCK); }
    private static String shader() {
        try (var input = VulkanPrefixScan.class.getResourceAsStream("/assets/mmm_gpu_probe/shaders/prefix-scan.comp")) {
            if (input == null) throw new IllegalStateException("Prefix scan shader missing");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) { throw new IllegalStateException("Prefix scan shader unavailable", failure); }
    }
    @Override public void close() { bindings.close(); }
}

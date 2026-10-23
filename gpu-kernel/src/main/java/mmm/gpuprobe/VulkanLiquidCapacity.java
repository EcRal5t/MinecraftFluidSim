package mmm.gpuprobe;

import org.lwjgl.vulkan.VkCommandBuffer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static mmm.gpuprobe.VulkanRegionStorage.barrier;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Limits six-neighbor preferred flow against cell and finite-group volume budgets. */
final class VulkanLiquidCapacity implements AutoCloseable {
    static final int RUNNING = 0, FEASIBLE = 1, INFEASIBLE = 2, ITERATION_LIMIT = 3, INVALID = 4;
    static final int ERROR_INVALID = 4096, ERROR_UNFINISHED = 8192;
    // CONTROL: status, completed waves, next active scratch, active count,
    // reserved, maximum waves, unbalanced cells, pending group budget,
    // phase (cell/group), reserved x3. Group budgets run after cell constraints settle.
    private final FlipKernelBindings bindings;
    private final VulkanPrefixScan scan;
    private FlipKernelBindings.BufferRange arena;

    VulkanLiquidCapacity(VulkanDeviceContext context) {
        bindings = new FlipKernelBindings(context, 2, shader(), 20);
        try { scan = new VulkanPrefixScan(context); }
        catch (RuntimeException | LinkageError failure) {
            try { bindings.close(); } catch (RuntimeException | LinkageError closing) { failure.addSuppressed(closing); }
            throw failure;
        }
    }

    void rebindRanges(FlipKernelBindings.BufferRange arena, FlipKernelBindings.BufferRange errors) {
        this.arena=arena;
        bindings.rebindRanges(List.of(arena, errors));
        scan.rebindRanges(arena, arena, arena, errors);
    }

    void recordInitialize(VkCommandBuffer command, VulkanLiquidCapacityLayout layout, int maximumIterations) {
        if (maximumIterations < 1) throw new IllegalArgumentException("Capacity iteration limit must be positive");
        stage(command, layout, 0, layout.edges(), maximumIterations);
        stage(command, layout, 1, layout.cells(), 0);
        scan.record(command, layout.groups(), layout.word(VulkanLiquidCapacityLayout.Field.GROUP_COUNTS),
                layout.word(VulkanLiquidCapacityLayout.Field.GROUP_OFFSETS),
                layout.word(VulkanLiquidCapacityLayout.Field.SCAN_WORKSPACE));
        stage(command, layout, 2, layout.cells(), 0);
        stage(command, layout, 3, layout.groups(), 0);
        scan.record(command, layout.groups(), layout.word(VulkanLiquidCapacityLayout.Field.BLOCK_COUNTS),
                layout.word(VulkanLiquidCapacityLayout.Field.BLOCK_OFFSETS),
                layout.word(VulkanLiquidCapacityLayout.Field.SCAN_WORKSPACE));
        stage(command, layout, 4, layout.groups(), 0);
    }

    /** Each wave reduces conflicting deviations from the feasible baseline on device frontiers. */
    void recordIterations(VkCommandBuffer command, VulkanLiquidCapacityLayout layout, int rounds) {
        if (rounds < 0) throw new IllegalArgumentException("Negative capacity round count");
        try(var stack=stackPush()){bindings.bind(command,stack);}
        for (int round = 0; round < rounds; round++) {
            if (layout.gasGroups() > 0) {
                stage(command, layout, 5, Math.multiplyExact(128, layout.blockCapacity()), 0);
                stage(command, layout, 6, Math.multiplyExact(128, layout.gasGroups()), 0);
                stage(command, layout, 7, Math.multiplyExact(128, layout.blockCapacity()), 0);
            }
            stage(command, layout, 10, layout.vertices(), 0);
            stage(command, layout, 11, Math.max(layout.vertices(), layout.edges()), 0);
            stage(command, layout, 12, Math.max(layout.vertices(), layout.edges()), 0);
            stage(command, layout, 13, 1, 0);
        }
    }

    /** Publishes seven accepted integer shares per cell only after a feasible solve. */
    void recordFinish(VkCommandBuffer command, VulkanLiquidCapacityLayout layout) {
        stage(command, layout, 14, Math.multiplyExact(7, layout.cells()), 0);
    }

    private void stage(VkCommandBuffer command, VulkanLiquidCapacityLayout layout, int stage, int count, int argument) {
        int groups = Math.max(1, (int) (((long) count + 127) / 128));
        if (stage >= 10 && stage <= 12) groups = Math.min(groups, 256);
        try (var stack = stackPush()) {
            if(stage<5||stage==14)bindings.bind(command, stack);
            vkCmdPushConstants(command, bindings.pipelineLayout(), VK_SHADER_STAGE_COMPUTE_BIT, 0,
                    stack.ints(layout.word(VulkanLiquidCapacityLayout.Field.HEADER), stage, count, argument, 0));
            if((stage>=5&&stage<=7)||stage==10) {
                barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,
                        VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT,VK_ACCESS_INDIRECT_COMMAND_READ_BIT,stack);
                vkCmdDispatchIndirect(command,arena.buffer().handle(),arena.offset()+layout.column(VulkanLiquidCapacityLayout.Field.DISPATCH).offset()+12L*(stage==10?3:stage-5));
            } else vkCmdDispatch(command, Math.min(groups, 65535), (groups + 65534) / 65535, 1);
            barrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT, stack);
        }
    }

    private static String shader() {
        try (var stream = VulkanLiquidCapacity.class.getResourceAsStream("/assets/mmm_gpu_probe/shaders/liquid-capacity.comp")) {
            if (stream == null) throw new IllegalStateException("Liquid capacity shader missing");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) { throw new IllegalStateException("Liquid capacity shader unavailable", failure); }
    }
    @Override public void close() { try { scan.close(); } finally { bindings.close(); } }
}

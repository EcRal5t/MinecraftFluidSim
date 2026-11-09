package mmm.gpu;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.lwjgl.vulkan.VkCommandBuffer;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Clears settled phase motion in active cells and preserves blocked pending work. */
final class VulkanActivityPending implements AutoCloseable {
    private final FlipKernelBindings bindings;
    VulkanActivityPending(VulkanDeviceContext context) { bindings = new FlipKernelBindings(context, 1, shader(), 16); }
    void record(VkCommandBuffer command, GpuBuffer arena, VulkanLiquidStateLayout.Column activity,
            VulkanLiquidStateLayout.Column pending, VulkanLiquidStateLayout.Column summary) {
        try (var stack = stackPush()) {
            VulkanRegionStorage.barrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT, stack);
            vkCmdFillBuffer(command, arena.handle(), summary.offset(), summary.bytes(), 0);
            VulkanRegionStorage.barrier(command, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT, stack);
            bindings.rebindRanges(List.of(new FlipKernelBindings.BufferRange(arena)));
            bindings.bind(command, stack);
            var push = stack.malloc(16).putInt(pending.count()).putInt(Math.toIntExact(activity.offset()/4))
                    .putInt(Math.toIntExact(pending.offset()/4)).putInt(Math.toIntExact(summary.offset()/4)).flip();
            vkCmdPushConstants(command, bindings.pipelineLayout(), VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            vkCmdDispatch(command, (pending.count()+127)/128, 1, 1);
            VulkanRegionStorage.barrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT, stack);
        }
    }
    static void recordInput(VulkanLiquidRegion region, VulkanLiquidStateLayout liquid, VulkanLiquidStateLayout.Column activityCells, VulkanLiquidStateLayout.Column activityFaces, org.lwjgl.vulkan.VkCommandBuffer command, GpuBuffer output, org.lwjgl.system.MemoryStack stack) {
        boolean[] active = region.activeInputs == null ? null : region.activeInputs.activeCells();
        VulkanRegionStorage.barrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_ACCESS_TRANSFER_WRITE_BIT, stack);
        if (active == null) {
            vkCmdFillBuffer(command, output.handle(), activityCells.offset(), activityCells.bytes(), 1);
            vkCmdFillBuffer(command, output.handle(), activityFaces.offset(), activityFaces.bytes(), 1);
        } else {
            var grid = liquid.grid();
            var cells = ByteBuffer.allocateDirect(4 * active.length).order(ByteOrder.LITTLE_ENDIAN);
            for (boolean value : active) cells.putInt(value ? 1 : 0);
            cells.flip();
            var faces = ByteBuffer.allocateDirect(Math.toIntExact(activityFaces.bytes())).order(ByteOrder.LITTLE_ENDIAN);
            for (int face = 0; face < grid.faceCount(); face++) {
                int left = grid.faceLeft(face), right = grid.faceRight(face);
                boolean enabled = (left < 0 || active[left]) && (right < 0 || active[right]);
                faces.putInt(enabled ? 1 : 0);
            }
            faces.flip();
            updateActivity(command, output, activityCells.offset(), cells);
            updateActivity(command, output, activityFaces.offset(), faces);
        }
        VulkanRegionStorage.barrier(command, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT, stack);
    }
    private static void updateActivity(org.lwjgl.vulkan.VkCommandBuffer command, GpuBuffer output, long offset, ByteBuffer values) {
        while (values.hasRemaining()) {
            int size = Math.min(values.remaining(), 65536);
            var part = values.slice(values.position(), size);
            vkCmdUpdateBuffer(command, output.handle(), offset, part);
            offset += size;
            values.position(values.position() + size);
        }
    }
    private static String shader() {
        try (var input = VulkanActivityPending.class.getResourceAsStream("/assets/mmm_gpu_probe/shaders/activity-pending.comp")) {
            if (input == null) throw new IllegalStateException("Activity pending shader missing");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) { throw new IllegalStateException("Activity pending shader unavailable", failure); }
    }
    @Override public void close() { bindings.close(); }
}

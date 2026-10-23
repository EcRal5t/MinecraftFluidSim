package mmm.gpuprobe;

import org.lwjgl.vulkan.VkCommandBuffer;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static mmm.gpuprobe.VulkanRegionStorage.barrier;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Isentropic work from actual source control volumes, before gas inventory remapping. */
final class VulkanGasAdiabaticWork implements AutoCloseable {
    static final int UNRESOLVED = 0, RESOLVED = 1;

    /** The EOS cache, inventory and both volume columns must use the same source-node ordering.
     * Future volumes describe actual accepted geometry assigned to each old gas source.
     * Record thermodynamics for this inventory/U before recording work. No authoritative U is changed.
     * workBySource stores two doubles per node whose exact sum equals candidateEnergy - original U;
     * positive work enters gas and must leave the common mechanical account.
     */
    record Layout(VulkanGasThermodynamics.Layout thermodynamics, int futureControlVolumesWordOffset,
                  int mechanicalModesWordOffset, VulkanLiquidStateLayout.Column header,
                  VulkanLiquidStateLayout.Column candidateEnergy, VulkanLiquidStateLayout.Column candidateTemperature,
                  VulkanLiquidStateLayout.Column workBySource, long endOffset) {
        int headerWordOffset() { return word(header); }
        ByteBuffer headerBytes() {
            var result = ByteBuffer.allocateDirect(Math.toIntExact(header.bytes())).order(ByteOrder.LITTLE_ENDIAN);
            result.putInt(0, futureControlVolumesWordOffset);result.putInt(4, mechanicalModesWordOffset);
            result.putInt(8, word(candidateEnergy));result.putInt(12, word(candidateTemperature));
            result.putInt(16, word(workBySource));return result;
        }
        void initializeHeader(ByteBuffer destination) {
            destination.duplicate().position(Math.toIntExact(header.offset())).put(headerBytes());
        }
    }
    static Layout layout(VulkanGasThermodynamics.Layout thermodynamics, int futureControlVolumesWordOffset,
                         int mechanicalModesWordOffset, long scratchBase, long alignment) {
        if (futureControlVolumesWordOffset < 0 || mechanicalModesWordOffset < 0
                || scratchBase < thermodynamics.endOffset() || alignment < 8 || (alignment & (alignment - 1)) != 0)
            throw new IllegalArgumentException("Invalid gas adiabatic work layout");
        long[] cursor = {scratchBase};int nodes = thermodynamics.gas().nodeCapacity();
        var header = column(cursor, 8, 4, alignment);
        var energy = column(cursor, nodes, 8, alignment);
        var temperature = column(cursor, nodes, 8, alignment);
        var work = column(cursor, Math.multiplyExact(nodes, 2), 8, alignment);
        return new Layout(thermodynamics, futureControlVolumesWordOffset, mechanicalModesWordOffset,
                header, energy, temperature, work, align(cursor[0], alignment));
    }
    private final FlipKernelBindings bindings;
    VulkanGasAdiabaticWork(VulkanDeviceContext context) {
        bindings = new FlipKernelBindings(context, 2, shader(), 12);
    }
    void rebindRanges(FlipKernelBindings.BufferRange candidate, FlipKernelBindings.BufferRange errors) {
        bindings.rebindRanges(List.of(candidate, errors));
    }
    void record(VkCommandBuffer command, Layout layout) {
        int count = layout.thermodynamics().gas().nodeCapacity();if (count == 0) return;
        int groups = (int) (((long) count + 127) / 128);
        try (var stack = stackPush()) {
            bindings.bind(command, stack);
            vkCmdPushConstants(command, bindings.pipelineLayout(), VK_SHADER_STAGE_COMPUTE_BIT, 0,
                    stack.ints(layout.thermodynamics().headerWordOffset(), layout.headerWordOffset(), count));
            vkCmdDispatch(command, Math.min(groups, 65535), (groups + 65534) / 65535, 1);
            barrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT, stack);
        }
    }
    private static int word(VulkanLiquidStateLayout.Column column) { return Math.toIntExact(column.offset() / 4); }
    private static long align(long value, long alignment) { return Math.addExact(value, alignment - 1) & -alignment; }
    private static VulkanLiquidStateLayout.Column column(long[] cursor, int count, int width, long alignment) {
        var result = new VulkanLiquidStateLayout.Column(align(cursor[0], alignment), count, width);
        cursor[0] = Math.addExact(result.offset(), result.bindingBytes());return result;
    }
    private static String shader() {
        // Both kernels compile the same inventory/reference and segment traversal functions.
        String eos = resource("gas-thermodynamics.comp");
        int start = eos.indexOf("bool inventory("), end = eos.indexOf("void derive(");
        if (start < 0 || end <= start) throw new IllegalStateException("Gas caloric shader functions missing");
        return resource("gas-adiabatic-work.comp").replace("// GAS_CALORIC_FUNCTIONS", eos.substring(start, end));
    }
    private static String resource(String name) {
        try (var input = VulkanGasAdiabaticWork.class.getResourceAsStream("/assets/mmm_gpu_probe/shaders/" + name)) {
            if (input == null) throw new IllegalStateException("Gas work shader missing: " + name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) { throw new IllegalStateException("Gas work shader unavailable", failure); }
    }
    @Override public void close() { bindings.close(); }
}

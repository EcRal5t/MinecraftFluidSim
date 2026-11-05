package mmm.gpu;

import org.lwjgl.vulkan.VkCommandBuffer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import mmm.gpu.VulkanLiquidRegion.GeometryField;
import static mmm.gpu.VulkanLiquidStateLayout.Field.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;
import static mmm.gpu.VulkanRegionStorage.barrier;

/** Resident stock totals, coarse PLIC reconstruction and MAC face geometry. */
final class VulkanLiquidGeometry implements AutoCloseable {
    enum Binding {
        OFFSETS, VOLUMES, MASSES, MASKS, SOLIDS, MOMENTUM_X, MOMENTUM_Y, MOMENTUM_Z,
        CELLS, PLANES, MICRO, FACES, ERRORS, FLUX_PREFERENCES, WALL_GEOMETRY
    }

    private final FlipKernelBindings bindings;
    private boolean sealedExterior=true;
    private float ambientCellMass;
    VulkanLiquidGeometry(VulkanDeviceContext context) {
        bindings = new FlipKernelBindings(context, Binding.values().length, shader(), 32);
    }
    void rebindRanges(List<FlipKernelBindings.BufferRange> ranges) { bindings.rebindRanges(ranges); }
    void setExterior(boolean sealed,double ambientCellMass){
        if(!(Double.isFinite(ambientCellMass)&&ambientCellMass>=0&&ambientCellMass<=Float.MAX_VALUE))throw new IllegalArgumentException("Invalid ambient cell mass");
        this.sealedExterior=sealed;this.ambientCellMass=(float)ambientCellMass;
    }
    static List<FlipKernelBindings.BufferRange> ranges(VulkanLiquidStateLayout liquid,
            Map<GeometryField, VulkanLiquidStateLayout.Column> geometry, GpuBuffer buffer, GpuBuffer errors,
            VulkanLiquidStateLayout.Column faces, VulkanLiquidStateLayout.Column solid) {
        var ranges = new ArrayList<FlipKernelBindings.BufferRange>(Binding.values().length);
        for (var binding : Binding.values()) {
            var column = switch (binding) {
                case OFFSETS -> liquid.column(CELL_OFFSETS);
                case VOLUMES -> liquid.column(NUMERIC_VOLUMES);
                case MASSES -> liquid.column(MASS_UNITS);
                case MASKS -> geometry.get(GeometryField.FIXED_MASKS);
                case SOLIDS -> solid;
                case MOMENTUM_X -> liquid.column(MOMENTUM_X);
                case MOMENTUM_Y -> liquid.column(MOMENTUM_Y);
                case MOMENTUM_Z -> liquid.column(MOMENTUM_Z);
                case CELLS -> geometry.get(GeometryField.CELLS);
                case PLANES -> geometry.get(GeometryField.PLANES);
                case MICRO -> geometry.get(GeometryField.LIQUID_MICRO);
                case WALL_GEOMETRY -> geometry.get(GeometryField.WALL_GEOMETRY);
                case FACES -> faces;
                case FLUX_PREFERENCES -> geometry.get(GeometryField.FLUXES);
                case ERRORS -> null;
            };
            ranges.add(column == null ? new FlipKernelBindings.BufferRange(errors)
                    : new FlipKernelBindings.BufferRange(buffer, column.offset(), column.bindingBytes()));
        }
        return ranges;
    }
    void record(VkCommandBuffer command, int sizeX, int sizeY, int sizeZ) {
        for (int stage = 0; stage < 3; stage++) recordStage(command, sizeX, sizeY, sizeZ, stage, 0, 0);
    }

    /** Reads conserved reference liquid volume and pore capacity before the local mixture EOS. */
    void recordTotals(VkCommandBuffer command,int sizeX,int sizeY,int sizeZ){
        recordStage(command,sizeX,sizeY,sizeZ,0,0,0);
    }
    /** Uses EOS-derived physical cell volumes and total fluid mass to reconstruct PLIC and MAC faces. */
    void recordShapes(VkCommandBuffer command,int sizeX,int sizeY,int sizeZ){
        recordStage(command,sizeX,sizeY,sizeZ,1,0,0);
        recordStage(command,sizeX,sizeY,sizeZ,2,0,0);
    }

    /** Reuses completed face geometry and refreshes only velocities from current MAC momentum. */
    void recordVelocities(VkCommandBuffer command, int sizeX, int sizeY, int sizeZ) {
        recordStage(command, sizeX, sizeY, sizeZ, 4, 0, 0);
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
            var constants = stack.malloc(32);
            constants.putInt(sizeX).putInt(sizeY).putInt(sizeZ).putInt(stage).putFloat(h).putFloat(dt)
                    .putInt(sealedExterior?1:0).putFloat(ambientCellMass).flip();
            vkCmdPushConstants(command, bindings.pipelineLayout(), VK_SHADER_STAGE_COMPUTE_BIT, 0, constants);
            vkCmdDispatch(command, ((stage >= 2 ? faces : cells) + 127) / 128, 1, 1);
            barrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT, stack);
        }
    }
    private static String shader() {
        try (var input = VulkanLiquidGeometry.class.getResourceAsStream("/assets/mmm_gpu_probe/shaders/liquid-geometry.comp")) {
            if (input == null) throw new IllegalStateException("Liquid geometry shader missing");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8)
                .replace("// HOST_LAYOUT", VulkanShaderLayout.fields("B_", Binding.values()))
                .replace("// PLIC_FACE_LIBRARY", VulkanLiquidPhaseRows.resource("plic-face.glsl"));
        } catch (IOException failure) { throw new IllegalStateException("Liquid geometry shader unavailable", failure); }
    }
    @Override public void close() { bindings.close(); }
}

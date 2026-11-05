package mmm.gpu;

import org.lwjgl.vulkan.VkCommandBuffer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static mmm.gpu.VulkanRegionStorage.barrier;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Records the whole pressure solve into its caller's submission using resident buffer ranges. */
final class VulkanPressureKernel implements AutoCloseable {
    private enum Stage {
        INITIALIZE_ROWS,
        INITIALIZE_CONTROL,
        REFRESH_RESIDUAL,
        FINISH_REFRESH,
        PRECONDITION,
        FINISH_PRECONDITION,
        UPDATE_DIRECTION,
        DIRECTION_PRODUCT,
        FINISH_PRODUCT,
        UPDATE_SOLUTION,
        FINISH_UPDATE,
        FINALIZE_CONTROL,
        CHECK_SUCCESS,
        CHECK_MECHANICAL
    }

    private enum Control { ITERATION, RESIDUAL_RMS, SUCCESS, RUN, RESIDUAL_DOT_PRECONDITIONED, STEP_LENGTH, RESTART, REFRESH, DIRECTION_SCALE, TOLERANCE, MAX_PRESSURE, RESIDUAL_REFRESHES }
    /** Counts are sampled before the final certification refresh; initialization is included. */
    record Progress(int iterations,double residualRms,int residualRefreshes) {
        static Progress read(java.nio.ByteBuffer control) {
            return new Progress((int)control.getDouble(Double.BYTES * Control.ITERATION.ordinal()),
                    control.getDouble(Double.BYTES * Control.RESIDUAL_RMS.ordinal()),
                    (int)control.getDouble(Double.BYTES * Control.RESIDUAL_REFRESHES.ordinal()));
        }
    }
    /** One matrix product for the common PCG; the multigrid liquid block is shared by every model. */
    interface PressureOperator {
        int liquidCount();
        int matrixFreeFromRow();
        void recordProduct(VkCommandBuffer command, boolean direction);
    }
    private final FlipKernelBindings bindings;
    private final FlipGpuResources dispatchResources;
    private final GpuBuffer stageDispatch;
    private final int singleWorkgroupRows;

    VulkanPressureKernel(VulkanDeviceContext context, int singleWorkgroupRows) {
        this.singleWorkgroupRows = singleWorkgroupRows;
        dispatchResources = new FlipGpuResources(context);
        try {
            stageDispatch = dispatchResources.addDeviceStorage(16L * 10);
            bindings = new FlipKernelBindings(context, 11, shader(), 32);
        } catch (RuntimeException | Error failure) { dispatchResources.close(); throw failure; }
    }

    void rebind(FlipGpuResources resources) { rebindRanges(java.util.stream.IntStream.range(0,resources.size()).mapToObj(i->new FlipKernelBindings.BufferRange(resources.buffer(i))).toList()); }
    void rebindRanges(List<FlipKernelBindings.BufferRange> buffers) {
        var ranges=new java.util.ArrayList<>(buffers);ranges.add(new FlipKernelBindings.BufferRange(stageDispatch));
        bindings.rebindRanges(ranges);
    }

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
        if(multigrid!=null)multigrid.beginTimings(command);
        bind(command);
        int groups = groups(size, multigrid);
        for (int stage = Stage.INITIALIZE_ROWS.ordinal(); stage <= Stage.FINISH_REFRESH.ordinal(); stage++)
            stage(command, size, count, maximumIterations, stage, groups, multigrid != null, gas);
        if(multigrid!=null){multigrid.recordBuild(command,hierarchy);bind(command);}
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
            for (int stage = Stage.DIRECTION_PRODUCT.ordinal(); stage <= Stage.FINISH_UPDATE.ordinal(); stage++)
                stage(command, size, count, maximumIterations, stage, groups, multigrid != null, gas);
            for (int stage = Stage.REFRESH_RESIDUAL.ordinal(); stage <= Stage.FINISH_REFRESH.ordinal(); stage++)
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
        stage(command, size, count, maximumIterations, Stage.FINALIZE_CONTROL.ordinal(), groups, multigrid != null, gas);
        stage(command, size, count, maximumIterations, Stage.REFRESH_RESIDUAL.ordinal(), groups, multigrid != null, gas);
        stage(command, size, count, maximumIterations, Stage.FINISH_REFRESH.ordinal(), groups, multigrid != null, gas);
        stage(command, size, count, maximumIterations, Stage.CHECK_SUCCESS.ordinal(), groups, multigrid != null, gas);
    }

    /** Publish a bounded finite mechanical iterate and its true residual. */
    void recordFinishMechanical(VkCommandBuffer command,int size,int count,int maximumIterations,
                                VulkanPressureMultigrid multigrid,VulkanPressureMultigrid.Layout hierarchy,PressureOperator operator){
        bind(command);
        int groups=groups(size,multigrid);
        stage(command,size,count,maximumIterations,Stage.FINALIZE_CONTROL.ordinal(),groups,multigrid!=null,operator);
        stage(command,size,count,maximumIterations,Stage.REFRESH_RESIDUAL.ordinal(),groups,multigrid!=null,operator);
        stage(command,size,count,maximumIterations,Stage.FINISH_REFRESH.ordinal(),groups,multigrid!=null,operator);
        stage(command,size,count,maximumIterations,Stage.CHECK_MECHANICAL.ordinal(),groups,multigrid!=null,operator);
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
            multigrid.recordApply(command, hierarchy);
            bind(command);
        }
        for (int stage = Stage.PRECONDITION.ordinal(); stage <= Stage.UPDATE_DIRECTION.ordinal(); stage++)
            stage(command, size, count, maximumIterations, stage, groups, multigrid != null, gas);
    }

    private void stage(VkCommandBuffer command, int size, int count, int maximumIterations, int stage, int groups, boolean external, PressureOperator gas) {
        if (gas != null && gas.matrixFreeFromRow()<size
                && (stage == Stage.REFRESH_RESIDUAL.ordinal() || stage == Stage.DIRECTION_PRODUCT.ordinal())) {
            gas.recordProduct(command, stage == Stage.DIRECTION_PRODUCT.ordinal());
            bind(command);
        }
        try (var stack = stackPush()) {
            vkCmdPushConstants(command, bindings.pipelineLayout(), VK_SHADER_STAGE_COMPUTE_BIT, 0,
                    stack.ints(count, size, maximumIterations, stage, groups,external?1:0, gas == null ? size : gas.liquidCount(), gas == null ? size : gas.matrixFreeFromRow()));
            boolean parallel = stage == Stage.INITIALIZE_ROWS.ordinal() || stage == Stage.REFRESH_RESIDUAL.ordinal() || stage == Stage.PRECONDITION.ordinal() || stage == Stage.UPDATE_DIRECTION.ordinal() || stage == Stage.DIRECTION_PRODUCT.ordinal() || stage == Stage.UPDATE_SOLUTION.ordinal() || stage==Stage.CHECK_MECHANICAL.ordinal();
            if(parallel&&stage!=Stage.INITIALIZE_ROWS.ordinal()&&stage!=Stage.CHECK_MECHANICAL.ordinal()){
                barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT,VK_ACCESS_INDIRECT_COMMAND_READ_BIT,stack);
                vkCmdDispatchIndirect(command,stageDispatch.handle(),16L*stage);
            }else vkCmdDispatch(command, parallel ? groups : 1, 1, 1);
            barrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT,
                    VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_INDIRECT_COMMAND_READ_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT, stack);
        }
    }

    private static String shader() {
        try (var stream = VulkanPressureKernel.class.getResourceAsStream("/assets/mmm_gpu_probe/shaders/pressure.comp")) {
            if (stream == null) throw new IllegalStateException("Pressure shader missing");
            var definitions = new StringBuilder();
            for (var stage : Stage.values())
                definitions.append("#define STAGE_").append(stage.name()).append(' ').append(stage.ordinal()).append('\n');
            for (var control : Control.values())
                definitions.append("#define CONTROL_").append(control.name()).append(' ').append(control.ordinal()).append('\n');
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).replace("// HOST_LAYOUT", definitions.toString());
        } catch (IOException failure) { throw new IllegalStateException("Pressure shader unavailable", failure); }
    }

    @Override public void close() { try { bindings.close(); } finally { dispatchResources.close(); } }
}

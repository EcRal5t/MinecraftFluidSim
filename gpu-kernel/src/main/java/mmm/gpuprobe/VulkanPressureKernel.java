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
    /** Counts are sampled before the final certification refresh; initialization is included. */
    record Progress(int iterations,double residualRms,int constraintTrials,int activeSetUpdates,int residualRefreshes) {
        static Progress read(java.nio.ByteBuffer control) {
            return new Progress((int)control.getDouble(0),control.getDouble(8),(int)control.getDouble(64),
                    (int)control.getDouble(104),(int)control.getDouble(120));
        }
    }
    /** One matrix product for the common PCG; the multigrid liquid block is shared by every model. */
    interface PressureOperator {
        int liquidCount();
        int matrixFreeFromRow();
        void recordProduct(VkCommandBuffer command, boolean direction);
        default void recordProductIndirect(VkCommandBuffer command,boolean direction,Dispatch dispatch){recordProduct(command,direction);}
        default boolean hasPreconditioner(){return false;}
        default void recordPreconditioner(VkCommandBuffer command){ }
        default Object preconditionerKey(){return null;}
        default ParallelRows parallelRows(){return null;}
    }
    /** Compact physical row keys and the device-resident VkDispatchIndirectCommand for parallel stages. */
    record ParallelRows(FlipKernelBindings.BufferRange rows,GpuBuffer buffer,long offset) { }
    /** Conditional compute streams publish packed dispatches in the pore-flow control column. */
    record Dispatch(GpuBuffer buffer,long controlOffset){
        void record(VkCommandBuffer command,int slot){try(var stack=stackPush()){
            barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT,VK_ACCESS_INDIRECT_COMMAND_READ_BIT,stack);
            vkCmdDispatchIndirect(command,buffer.handle(),controlOffset+128L+16L*slot);
        }}
    }
    record GasOperator(VulkanGasPressure kernel, VulkanGasPressure.Layout layout) implements PressureOperator {
        @Override public int liquidCount() { return layout.liquidCount(); }
        @Override public int matrixFreeFromRow() { return layout.liquidCount(); }
        @Override public ParallelRows parallelRows() { return kernel.parallelRows(layout); }
        @Override public void recordProduct(VkCommandBuffer command, boolean direction) {
            kernel.recordProduct(command, layout, direction);
        }
    }
    private final FlipKernelBindings bindings;
    private final FlipGpuResources dispatchResources;
    private final GpuBuffer stageDispatch;
    private final int singleWorkgroupRows;
    private List<FlipKernelBindings.BufferRange> baseRanges;
    private ParallelRows parallelRows;

    VulkanPressureKernel(VulkanDeviceContext context, int singleWorkgroupRows) {
        this.singleWorkgroupRows = singleWorkgroupRows;
        dispatchResources = new FlipGpuResources(context);
        try {
            stageDispatch = dispatchResources.addDeviceStorage(16L * 10);
            bindings = new FlipKernelBindings(context, 13, shader(), 32);
        } catch (RuntimeException | Error failure) { dispatchResources.close(); throw failure; }
    }

    void rebind(FlipGpuResources resources) { rebindRanges(java.util.stream.IntStream.range(0,resources.size()).mapToObj(i->new FlipKernelBindings.BufferRange(resources.buffer(i))).toList()); }
    void rebindRanges(List<FlipKernelBindings.BufferRange> buffers) {
        var ranges=new java.util.ArrayList<>(buffers);ranges.add(new FlipKernelBindings.BufferRange(stageDispatch));
        baseRanges=List.copyOf(ranges);parallelRows=null;bindings.rebindRanges(baseRanges);
    }
    private void selectRows(PressureOperator operator){
        parallelRows=operator==null?null:operator.parallelRows();
        if(parallelRows==null)bindings.rebindRanges(baseRanges);
        else{var ranges=new java.util.ArrayList<>(baseRanges);ranges.set(3,parallelRows.rows());bindings.rebindRanges(ranges);}
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
            selectRows(null);bind(command);
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
        selectRows(gas);
        if(multigrid!=null&&(gas==null||!gas.hasPreconditioner()))multigrid.beginTimings(command);
        bind(command);
        int groups = groups(size, multigrid);
        for (int stage = 0; stage <= 3; stage++)
            stage(command, size, count, maximumIterations, stage, groups, multigrid != null, gas);
        if(multigrid!=null&&(gas==null||!gas.hasPreconditioner())){multigrid.recordBuild(command,hierarchy);bind(command);}
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
            for (int stage = 7; stage <= 10; stage++)
                stage(command, size, count, maximumIterations, stage, groups, multigrid != null, gas);
            for (int stage = 2; stage <= 3; stage++)
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
        stage(command, size, count, maximumIterations, 11, groups, multigrid != null, gas);
        stage(command, size, count, maximumIterations, 2, groups, multigrid != null, gas);
        stage(command, size, count, maximumIterations, 3, groups, multigrid != null, gas);
        stage(command, size, count, maximumIterations, 12, groups, multigrid != null, gas);
    }

    private int groups(int size, VulkanPressureMultigrid multigrid) {
        return multigrid == null && size <= singleWorkgroupRows ? 1 : (size + 255) / 256;
    }

    private void bind(VkCommandBuffer command) {
        try (var stack = stackPush()) { bindings.bind(command, stack); }
    }

    private void precondition(VkCommandBuffer command, int size, int count, int maximumIterations, int groups,
                              VulkanPressureMultigrid multigrid, VulkanPressureMultigrid.Layout hierarchy, PressureOperator gas) {
        if(gas!=null&&gas.hasPreconditioner()){
            gas.recordPreconditioner(command);bind(command);
        }else if (multigrid != null) {
            multigrid.recordApply(command, hierarchy);
            bind(command);
        }
        for (int stage = 4; stage <= 6; stage++)
            stage(command, size, count, maximumIterations, stage, groups, multigrid != null, gas);
    }

    private void stage(VkCommandBuffer command, int size, int count, int maximumIterations, int stage, int groups, boolean external, PressureOperator gas) {
        if (gas != null && (stage == 2 || stage == 7)) {
            gas.recordProduct(command, stage == 7);
            bind(command);
        }
        try (var stack = stackPush()) {
            vkCmdPushConstants(command, bindings.pipelineLayout(), VK_SHADER_STAGE_COMPUTE_BIT, 0,
                    stack.ints(count, size, maximumIterations, stage, groups,(gas!=null&&gas.hasPreconditioner()?2:external?1:0)|(parallelRows!=null?4:0), gas == null ? size : gas.liquidCount(), gas == null ? size : gas.matrixFreeFromRow()));
            boolean parallel = stage == 0 || stage == 2 || stage == 4 || stage == 6 || stage == 7 || stage == 9;
            if(parallel&&stage!=0){
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
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) { throw new IllegalStateException("Pressure shader unavailable", failure); }
    }

    @Override public void close() { try { bindings.close(); } finally { dispatchResources.close(); } }
}

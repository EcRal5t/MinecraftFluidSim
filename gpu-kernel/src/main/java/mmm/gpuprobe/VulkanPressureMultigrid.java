package mmm.gpuprobe;

import org.lwjgl.vulkan.VkCommandBuffer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static mmm.gpuprobe.VulkanRegionStorage.barrier;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Symmetric aggregation V-cycle over the resident liquid seven-point pressure operator. */
final class VulkanPressureMultigrid implements AutoCloseable {
    record Level(int sizeX, int sizeY, int sizeZ, int offset) {
        int cells() { return Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ); }
    }

    record Layout(List<Level> levels, int workspaceDoubles, int pressureStride, int coarseFactorOffset, int applyDispatchOffset) {
        Layout { levels = List.copyOf(levels); }
        long workspaceBytes() { return Math.multiplyExact(8L, workspaceDoubles); }
    }

    private final FlipKernelBindings bindings;
    private final VulkanStageTimings timings;
    private FlipKernelBindings.BufferRange workspace;

    VulkanPressureMultigrid(VulkanDeviceContext context) {this(context,false);}
    VulkanPressureMultigrid(VulkanDeviceContext context,boolean profile) {
        bindings = new FlipKernelBindings(context, 9, shader(), 52);
        try {timings=profile?new VulkanStageTimings(context,1024):null;}
        catch(RuntimeException|Error failure){bindings.close();throw failure;}
    }
    void beginTimings(VkCommandBuffer command){if(timings!=null)timings.reset(command);}
    List<VulkanStageTimings.Sample> stageTimings(){return timings==null?List.of():timings.read();}

    /** Seven FP64 matrix entries and three packed FP32 application vectors per cell. */
    static Layout layout(int sx, int sy, int sz) {
        return layout(sx, sy, sz, Math.multiplyExact(Math.multiplyExact(sx, sy), sz));
    }

    static Layout layout(int sx, int sy, int sz, int pressureStride) {
        if (pressureStride < Math.multiplyExact(Math.multiplyExact(sx, sy), sz))
            throw new IllegalArgumentException("Pressure stride is smaller than the liquid grid");
        if (sx <= 0 || sy <= 0 || sz <= 0) throw new IllegalArgumentException("Invalid multigrid shape");
        var levels = new ArrayList<Level>();
        int cursor = 0;
        while (true) {
            var level = new Level(sx, sy, sz, cursor);
            levels.add(level);
            cursor = Math.addExact(cursor,Math.addExact(Math.multiplyExact(7,level.cells()),Math.toIntExact((3L*level.cells()+1)/2)));
            if (Math.max(sx, Math.max(sy, sz)) <= 4) break;
            sx = (sx + 1) / 2; sy = (sy + 1) / 2; sz = (sz + 1) / 2;
        }
        int factor=cursor,coarse=levels.getLast().cells();
        int dense=Math.multiplyExact(coarse,coarse);
        cursor=Math.addExact(cursor,Math.addExact(dense,(dense+1)/2));
        int dispatch=cursor;cursor=Math.addExact(cursor,Math.multiplyExact(2,levels.size()));
        return new Layout(levels, cursor, pressureStride,factor,dispatch);
    }

    /** Bind starts, columns, values, rows, pressure metadata, pressure work, control and this workspace. */
    void rebindRanges(List<FlipKernelBindings.BufferRange> buffers) {
        workspace=buffers.getLast();var ranges=new ArrayList<>(buffers);
        ranges.add(workspace);bindings.rebindRanges(ranges);
    }

    /** The full physical liquid block is fixed for one pressure solve, independently of FREE. */
    void recordBuild(VkCommandBuffer command, Layout layout) {
        if(timings==null)recordBuildCommands(command,layout);else timings.record(command,"mg-build",()->recordBuildCommands(command,layout));
    }
    private void recordBuildCommands(VkCommandBuffer command, Layout layout) {
        bind(command);
        var levels = layout.levels();
        stage(command, layout, levels.getFirst(), levels.getFirst(), 0);
        for (int i = 1; i < levels.size(); i++) stage(command, layout, levels.get(i), levels.get(i - 1), 1);
        stage(command,layout,levels.getLast(),levels.getLast(),8);
        stage(command,layout,levels.getLast(),levels.getLast(),9);
    }

    /** A zero-start, fixed linear V-cycle; all work stays inside the caller's submission. */
    void recordApply(VkCommandBuffer command, Layout layout) {
        if(timings==null)recordApplyCommands(command,layout);else timings.record(command,"mg-apply",()->recordApplyCommands(command,layout));
    }
    private void recordApplyCommands(VkCommandBuffer command, Layout layout) {
        bind(command);
        var levels = layout.levels();
        stage(command,layout,levels.getFirst(),levels.getFirst(),10);
        stage(command, layout, levels.getFirst(), levels.getFirst(), 2,0);
        for (int i = 1; i < levels.size(); i++) stage(command, layout, levels.get(i), levels.get(i - 1), 3,i);
        stage(command, layout, levels.getLast(), levels.getLast(), 4,levels.size()-1);
        for (int i = levels.size() - 2; i >= 0; i--) {
            stage(command, layout, levels.get(i), levels.get(i + 1), 6,i);
        }
        stage(command, layout, levels.getFirst(), levels.getFirst(), 7,0);
    }

    private void bind(VkCommandBuffer command) {
        try (var stack = stackPush()) { bindings.bind(command, stack); }
    }

    private void stage(VkCommandBuffer command, Layout layout, Level level, Level peer, int stage) {
        stage(command,layout,level,peer,stage,-1);
    }
    private void stage(VkCommandBuffer command, Layout layout, Level level, Level peer, int stage,int dispatchIndex) {
        try (var stack = stackPush()) {
            vkCmdPushConstants(command, bindings.pipelineLayout(), VK_SHADER_STAGE_COMPUTE_BIT, 0,
                    stack.ints(level.sizeX(), level.sizeY(), level.sizeZ(), stage,
                            level.offset(), peer.offset(), peer.sizeX(), peer.sizeY(), peer.sizeZ(),
                            layout.levels().getFirst().cells(), layout.pressureStride(), layout.coarseFactorOffset(),2*layout.applyDispatchOffset()));
            if(dispatchIndex>=0)vkCmdDispatchIndirect(command,workspace.buffer().handle(),workspace.offset()+8L*layout.applyDispatchOffset()+16L*dispatchIndex);
            else vkCmdDispatch(command, stage==9?level.cells():stage==4||stage==8||stage==10?1:(level.cells()+127)/128,1,1);
            barrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | (stage==10?VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT:0),
                    VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT | (stage==10?VK_ACCESS_INDIRECT_COMMAND_READ_BIT:0), stack);
        }
    }

    private static String shader() {
        try (var stream = VulkanPressureMultigrid.class.getResourceAsStream("/assets/mmm_gpu_probe/shaders/pressure-multigrid.comp")) {
            if (stream == null) throw new IllegalStateException("Pressure multigrid shader missing");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) { throw new IllegalStateException("Pressure multigrid shader unavailable", failure); }
    }

    @Override public void close() {try{if(timings!=null)timings.close();}finally{bindings.close();}}
}

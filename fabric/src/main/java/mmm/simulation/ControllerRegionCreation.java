package mmm.simulation;

import mmm.application.RegionCatalog;
import mmm.simulation.controller.FluidRegionFootprint;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import java.io.IOException;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Controller candidate initialization and first durable publication, shared with the world adapter. */
final class ControllerRegionCreation {
    private ControllerRegionCreation() { }
    @FunctionalInterface interface Persist { void commit(FluidRegion candidate) throws IOException; }
    record Result(FluidRegion region, String failure, boolean retainShell) { }

    static Result create(FluidRegionManager regions, ResourceKey<Level> dimension, FluidRegionFootprint footprint,
                         boolean entityPhysics, boolean overlays, CommittedWorldRegistries world, Supplier<String> availability,
                         Consumer<FluidRegion> initialize, Persist persist) {
        long required;
        try { required = FluidRegionFactory.grid(footprint).cellCount(); }
        catch (ArithmeticException invalid) { return new Result(null, "控制器区域尺寸溢出资源计数", false); }
        var preview = regions.previewAdd(required);
        if (!preview.accepted()) return new Result(null, preview.failure(), false);
        String unavailable = availability.get();
        if (unavailable != null) return new Result(null, unavailable, false);
        FluidRegion candidate;
        try { candidate = FluidRegionFactory.createControllerRegion(dimension, footprint, world); }
        catch (IllegalArgumentException | ArithmeticException invalid) {
            return new Result(null, "无法分配控制器区域：" + invalid.getMessage(), false);
        }
        candidate.setEntityPhysicsEnabled(entityPhysics);
        candidate.setDebugOverlays(overlays);
        preview = regions.previewAdd(candidate);
        if (!preview.accepted()) return new Result(null, preview.failure(), false);
        try { initialize.accept(candidate); }
        catch (RuntimeException invalid) { return new Result(null, "无法构造完整控制器区域候选：" + invalid.getMessage(), false); }
        candidate.setPaused(false);
        candidate.wakeForActivity();
        try { persist.commit(candidate); }
        catch (IOException | RuntimeException invalid) {
            return new Result(null, "初始 checkpoint/ownership 事务失败：" + invalid.getMessage(), true);
        }
        RegionCatalog.AdmissionResult admission = regions.addAndSelect(candidate);
        if (!admission.accepted()) return new Result(null,
                "首个 region HEAD 已提交，但 live owner 登记违反已通过的 admission 预检：" + admission.failure(), true);
        return new Result(candidate, null, false);
    }
}

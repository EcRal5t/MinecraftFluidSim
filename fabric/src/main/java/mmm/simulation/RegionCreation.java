package mmm.simulation;

import mmm.application.RegionCatalog;
import mmm.simulation.controller.FluidRegionFootprint;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import java.io.IOException;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Region candidate initialization and first durable publication, shared with the world adapter. */
final class RegionCreation {
    private RegionCreation() { }
    @FunctionalInterface interface Persist { void commit(FluidRegion candidate) throws IOException; }
    record Result(FluidRegion region, String failure, boolean retainShell) { }

    static Result create(FluidRegionManager regions, ResourceKey<Level> dimension, FluidRegionFootprint footprint,
                         boolean entityPhysics, boolean overlays, CommittedWorldRegistries world, Supplier<String> availability,
                         Consumer<FluidRegion> initialize, Persist persist) {
        long required;
        try { required = FluidRegionFactory.grid(footprint).cellCount(); }
        catch (ArithmeticException invalid) { return new Result(null, "区域尺寸溢出资源计数", false); }
        var preview = regions.previewAdd(required);
        if (!preview.accepted()) return new Result(null, preview.failure(), false);
        String unavailable = availability.get();
        if (unavailable != null) return new Result(null, unavailable, false);
        FluidRegion candidate;
        try { candidate = FluidRegionFactory.createSelectedRegion(dimension, footprint, world); }
        catch (IllegalArgumentException | ArithmeticException invalid) {
            return new Result(null, "无法分配区域：" + invalid.getMessage(), false);
        }
        candidate.setEntityPhysicsEnabled(entityPhysics);
        candidate.setDebugOverlays(overlays);
        preview = regions.previewAdd(candidate);
        if (!preview.accepted()) {
            candidate.releaseAfterReplacement();
            return new Result(null, preview.failure(), false);
        }
        try { initialize.accept(candidate); }
        catch (RuntimeException invalid) {
            candidate.releaseAfterReplacement();
            return new Result(null, "无法构造完整区域候选：" + invalid.getMessage(), false);
        }
        candidate.setPaused(false);
        candidate.wakeForActivity();
        try { persist.commit(candidate); }
        catch (IOException | RuntimeException invalid) {
            retainPublishedOrRelease(regions, candidate, invalid.getMessage());
            return new Result(null, "初始 checkpoint/ownership 事务失败：" + invalid.getMessage(), true);
        }
        RegionCatalog.AdmissionResult admission;
        try { admission = regions.addAndSelect(candidate); }
        catch (RuntimeException failure) {
            if (regions.region(candidate.id()) == candidate)
                candidate.blockNumerical("已保存的区域等待恢复：" + failure.getMessage());
            else retainPublishedOrRelease(regions, candidate, failure.getMessage());
            return new Result(null, "已保存的区域登记失败：" + failure.getMessage(), true);
        }
        if (!admission.accepted()) {
            retainPublishedOrRelease(regions, candidate, admission.failure());
            return new Result(null, "已保存的区域登记失败：" + admission.failure(), true);
        }
        return new Result(candidate, null, false);
    }

    private static void retainPublishedOrRelease(FluidRegionManager regions, FluidRegion candidate, String failure) {
        if (regions.region(candidate.id()) == candidate) return;
        if (candidate.checkpointGeneration() > 0) {
            candidate.blockNumerical("已保存的区域等待恢复：" + failure);
            try {
                if (regions.registerDiscovered(candidate).accepted()) return;
            } catch (RuntimeException registrationFailure) {
                if (regions.region(candidate.id()) == candidate) return;
                mmm.MmmMod.LOGGER.error("Could not register published region {}", candidate.id(), registrationFailure);
            }
        }
        candidate.releaseAfterReplacement();
    }
}

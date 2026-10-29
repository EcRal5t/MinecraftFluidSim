package mmm.simulation;

import mmm.fluid.HostFluidState;

/** Access to unresident fixture state during test setup. */
public final class TestHostAccess {
    private TestHostAccess() {}
    public static HostFluidState editable(FluidRegion region) { return region.editableHost(); }
    public static void edit(FluidRegion region, java.util.function.Consumer<HostFluidState> mutation) {
        long generation = region.solverGeneration();
        var candidate = region.beginHostEdit();
        mutation.accept(candidate);
        region.commitHostEdit(generation, candidate);
    }
}

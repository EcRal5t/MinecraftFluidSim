package mmm.simulation;

import mmm.block.MmmBlocks;
import net.minecraft.world.level.block.Block;

/** Loads the registered block in the existing loader-neutral registry fixture. */
public final class InsulatingGlassTestFixture {
    private InsulatingGlassTestFixture() { }
    public static Block glass() {
        GpuControllerFixture.initializeItems();
        GpuControllerFixture.initializeIndustrialBlocks();
        return MmmBlocks.INSULATING_GLASS;
    }
}

package mmm.simulation;

import mmm.simulation.persistence.RegionCheckpointAssembler;
import mmm.simulation.persistence.RegionState;

import java.io.IOException;

/** Test-side shorthand that keeps every fixture on the production checkpoint source boundary. */
public final class RegionCheckpointFixtures {
	private RegionCheckpointFixtures() { }

    public static void validateWorldChunkMask() {
        boolean[] mask=MinecraftGroupAvailability.sampleWorldCells(new net.minecraft.core.BlockPos(14,1,1),6,1,1,.5,
                (x,z)->x==0 && z==0);
        if(!mask[0] || !mask[1] || mask[2] || mask[3] || mask[4] || mask[5])
            throw new AssertionError("World chunk mask lost six-neighbor input requirements");
        boolean[] restored=MinecraftGroupAvailability.sampleWorldCells(new net.minecraft.core.BlockPos(14,1,1),6,1,1,.5,
                (x,z)->(x==0 || x==1) && z==0);
        for(boolean available:restored) if(!available) throw new AssertionError("Loaded neighbor chunk did not restore inputs");
    }

	public static RegionState capture(FluidRegion region, long generation) throws IOException {
		return new RegionState(RegionCheckpointAssembler.capture(new MinecraftRegionCheckpointSource(region), generation));
	}

	public static RegionState captureMaterial(FluidRegion region, long generation) throws IOException {
		return new RegionState(RegionCheckpointAssembler.captureMaterial(new MinecraftRegionCheckpointSource(region), generation));
	}

	public static RegionState captureChemical(FluidRegion region, long generation) throws IOException {
		return new RegionState(RegionCheckpointAssembler.captureChemical(new MinecraftRegionCheckpointSource(region), generation));
	}

	public static RegionState captureGranular(FluidRegion region, long generation) throws IOException {
		return new RegionState(RegionCheckpointAssembler.captureGranular(new MinecraftRegionCheckpointSource(region), generation));
	}

	public static RegionState captureThermal(FluidRegion region, long generation) throws IOException {
		return new RegionState(RegionCheckpointAssembler.captureThermal(new MinecraftRegionCheckpointSource(region), generation));
	}
}

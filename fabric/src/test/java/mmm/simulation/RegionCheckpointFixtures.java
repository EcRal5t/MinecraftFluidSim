package mmm.simulation;

import mmm.simulation.persistence.RegionCheckpointAssembler;
import mmm.simulation.persistence.RegionState;

import java.io.IOException;

/** Test-side shorthand that keeps every fixture on the production checkpoint source boundary. */
public final class RegionCheckpointFixtures {
	private RegionCheckpointFixtures() { }

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

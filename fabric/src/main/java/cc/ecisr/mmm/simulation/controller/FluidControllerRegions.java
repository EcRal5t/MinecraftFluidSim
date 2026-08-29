package cc.ecisr.mmm.simulation.controller;

import cc.ecisr.mmm.block.MmmBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Non-loading Minecraft adapter for controller scans and runtime boundary/load validation. */
public final class FluidControllerRegions {
	private FluidControllerRegions() { }

	public static FluidBoundaryScanner.ScanResult scan(
			ServerLevel level,
			BlockPos controllerPosition,
			int maximumInteriorBlocks
	) {
		return FluidBoundaryScanner.scan(controllerPosition, worldView(level), maximumInteriorBlocks);
	}

	/** Returns null only when every associated chunk is loaded and the original visible shell remains intact. */
	public static String availabilityFailure(ServerLevel level, FluidRegionFootprint footprint) {
		return availability(level, footprint).failure();
	}

	public static FluidBoundaryScanner.AvailabilityResult availability(
			ServerLevel level, FluidRegionFootprint footprint
	) {
		return FluidBoundaryScanner.validateAvailability(footprint, worldView(level));
	}

	private static FluidBoundaryScanner.ScanWorld worldView(ServerLevel level) {
		return new FluidBoundaryScanner.ScanWorld() {
			@Override
			public boolean isInsideBuildHeight(BlockPos position) {
				return level.isInWorldBounds(position) && level.isInsideBuildHeight(position);
			}

			@Override
			public boolean isChunkLoaded(int chunkX, int chunkZ) {
				return level.getChunkSource().getChunkNow(chunkX, chunkZ) != null;
			}

			@Override
			public boolean isBoundary(BlockPos position) {
				return MmmBlocks.isBoundary(level.getBlockState(position));
			}
		};
	}
}

package mmm.simulation.controller;

import mmm.block.MmmBlocks;
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
		return footprint.kind() == mmm.model.region.RegionGeometry.RegionKind.POOL
				? NativePoolScanner.validateAvailability(footprint, poolWorld(level))
				: FluidBoundaryScanner.validateAvailability(footprint, worldView(level));
	}

	public static NativePoolScanner.ScanResult scanPool(ServerLevel level, BlockPos seed, int maximumInteriorBlocks) {
        return NativePoolScanner.scan(seed, poolWorld(level), maximumInteriorBlocks);
    }

    private static NativePoolScanner.PoolWorld poolWorld(ServerLevel level) {
        return new NativePoolScanner.PoolWorld() {
            public boolean isInsideBuildHeight(BlockPos pos) { return level.isInWorldBounds(pos) && level.isInsideBuildHeight(pos); }
            public boolean isChunkLoaded(int x, int z) { return level.getChunkSource().getChunkNow(x,z) != null; }
            public NativePoolScanner.BlockKind blockKind(BlockPos pos) {
                var state=level.getBlockState(pos);
                if (state.is(net.minecraft.world.level.block.Blocks.WATER) && state.getFluidState().isSource()) return NativePoolScanner.BlockKind.SOURCE_WATER;
                if (state.isAir()) return NativePoolScanner.BlockKind.AIR;
                if (state.isCollisionShapeFullBlock(level,pos)) return NativePoolScanner.BlockKind.FULL_SOLID;
                return NativePoolScanner.BlockKind.OTHER;
            }
        };
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

package mmm.simulation.controller;

import mmm.block.MmmBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Non-loading Minecraft adapter for controller scans and runtime boundary/load validation. */
public final class RegionWorldAvailability {
	private RegionWorldAvailability() { }

    public static String availabilityFailure(ServerLevel level, FluidRegionFootprint footprint) {
        return availability(level, footprint).failure();
    }

    public static RegionAvailability.Result availability(ServerLevel level, FluidRegionFootprint footprint) {
        for (long packed : footprint.requiredChunks()) {
            var chunk = net.minecraft.world.level.ChunkPos.unpack(packed);
            if (level.getChunkSource().getChunkNow(chunk.x(), chunk.z()) == null)
                return new RegionAvailability.Result(RegionAvailability.Status.INCOMPLETE_CHUNKS, "区域所需区块未加载");
        }
        return new RegionAvailability.Result(RegionAvailability.Status.READY, null);
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

}

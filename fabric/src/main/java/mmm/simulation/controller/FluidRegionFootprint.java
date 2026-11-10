package mmm.simulation.controller;

import mmm.model.region.RegionGeometry;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;

import java.util.Objects;
import java.util.Set;

/** Selected block-space topology and native pool ownership metadata. */
public record FluidRegionFootprint(
		BlockPos minimumInterior,
		BlockPos maximumInterior,
		Set<Long> interiorBlocks,
		Set<Long> poolShellBlocks,
		Set<Long> requiredChunks,
		RegionGeometry.RegionKind kind,
		Set<Long> atmosphereBlocks,
		Set<Long> returnedBlocks,
        String name
) {
    public static final class ExpansionBudgetExceededException extends IllegalArgumentException {
        public ExpansionBudgetExceededException() { super("扩容超出区域单元预算"); }
    }
    public static FluidRegionFootprint box(BlockPos minimum, BlockPos maximum) {
        var blocks = new java.util.HashSet<Long>();
        var chunks = new java.util.HashSet<Long>();
        for (int x = minimum.getX(); x <= maximum.getX(); x++)
            for (int y = minimum.getY(); y <= maximum.getY(); y++)
                for (int z = minimum.getZ(); z <= maximum.getZ(); z++) {
                    blocks.add(new BlockPos(x, y, z).asLong());
                    chunks.add(ChunkPos.pack(x >> 4, z >> 4));
                }
        return selected(minimum, maximum, blocks, chunks);
    }

    public static FluidRegionFootprint selected(BlockPos minimum, BlockPos maximum, Set<Long> blocks, Set<Long> chunks) {
        return new FluidRegionFootprint(minimum, maximum, blocks, Set.of(), chunks,
                RegionGeometry.RegionKind.SELECTED, Set.of(), Set.of(), defaultName(minimum));
    }


	public FluidRegionFootprint {
		minimumInterior = Objects.requireNonNull(minimumInterior, "minimumInterior").immutable();
		maximumInterior = Objects.requireNonNull(maximumInterior, "maximumInterior").immutable();
		interiorBlocks = Set.copyOf(interiorBlocks);
		poolShellBlocks = Set.copyOf(poolShellBlocks);
		requiredChunks = Set.copyOf(requiredChunks);
		Objects.requireNonNull(kind, "kind");
		atmosphereBlocks = Set.copyOf(atmosphereBlocks);
		returnedBlocks = Set.copyOf(returnedBlocks);
        if (name == null || name.isBlank() || name.length() > 64) throw new IllegalArgumentException("Invalid region name");
		if (!interiorBlocks.containsAll(returnedBlocks)) throw new IllegalArgumentException("Returned blocks must belong to the region");
		if (interiorBlocks.isEmpty()) throw new IllegalArgumentException("A region needs at least one block");

		if (requiredChunks.isEmpty()) throw new IllegalArgumentException("A region needs loaded chunks");
		if (minimumInterior.getX() > maximumInterior.getX()
				|| minimumInterior.getY() > maximumInterior.getY()
				|| minimumInterior.getZ() > maximumInterior.getZ()) {
			throw new IllegalArgumentException("Invalid region bounds");
		}

	}

	public FluidRegionFootprint withReturnedBlocks(Set<Long> returned) {
		return new FluidRegionFootprint(minimumInterior, maximumInterior, interiorBlocks, poolShellBlocks, requiredChunks, kind, atmosphereBlocks, returned, name);
	}

    public FluidRegionFootprint withName(String value) {
        return new FluidRegionFootprint(minimumInterior, maximumInterior, interiorBlocks, poolShellBlocks,
                requiredChunks, kind, atmosphereBlocks, returnedBlocks, value);
    }

    /** Extends the selected block volume by one 8-cell solver block in each requested direction. */
    public FluidRegionFootprint expanded(int directions, long maximumCells) {
        if (directions <= 0 || (directions & ~63) != 0) throw new IllegalArgumentException("Invalid expansion directions");
        int minX = Math.subtractExact(minimumInterior.getX(), (directions & 1) == 0 ? 0 : 4);
        int maxX = Math.addExact(maximumInterior.getX(), (directions & 2) == 0 ? 0 : 4);
        int minY = Math.subtractExact(minimumInterior.getY(), (directions & 4) == 0 ? 0 : 4);
        int maxY = Math.addExact(maximumInterior.getY(), (directions & 8) == 0 ? 0 : 4);
        int minZ = Math.subtractExact(minimumInterior.getZ(), (directions & 16) == 0 ? 0 : 4);
        int maxZ = Math.addExact(maximumInterior.getZ(), (directions & 32) == 0 ? 0 : 4);
        long blocks = Math.multiplyExact(Math.multiplyExact((long) maxX - minX + 1, (long) maxY - minY + 1),
                (long) maxZ - minZ + 1);
        if (Math.multiplyExact(blocks, 8L) > maximumCells) throw new ExpansionBudgetExceededException();
        var newBlocks = new java.util.HashSet<Long>();
        var newChunks = new java.util.HashSet<Long>();
        for (int x = minX; x <= maxX; x++) for (int y = minY; y <= maxY; y++) for (int z = minZ; z <= maxZ; z++) {
            newBlocks.add(new BlockPos(x, y, z).asLong());
            newChunks.add(ChunkPos.pack(x >> 4, z >> 4));
        }
        return new FluidRegionFootprint(new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ),
                newBlocks, poolShellBlocks, newChunks, kind, atmosphereBlocks, returnedBlocks, name);
    }

    public static String defaultName(BlockPos minimum) { return "区域 " + minimum.toShortString(); }

	public boolean containsInterior(BlockPos position) {
		return interiorBlocks.contains(position.asLong()) && !returnedBlocks.contains(position.asLong());
	}

	/**
	 * Resolves a shell block to its sole face-adjacent interior block. Corners, edges and stale
	 * positions deliberately return {@code null}; container routing must never guess diagonally.
	 */
	public BlockPos uniqueAdjacentInterior(BlockPos boundaryPosition) {
		if (kind != RegionGeometry.RegionKind.SELECTED && !poolShellBlocks.contains(boundaryPosition.asLong())) return null;
		BlockPos resolved = null;
		for (Direction direction : Direction.values()) {
			BlockPos candidate = boundaryPosition.relative(direction);
			if (!containsInterior(candidate)) continue;
			if (resolved != null) return null;
			resolved = candidate.immutable();
		}
		return resolved;
	}

	/** Returns the interior immediately beyond a dispenser-facing shell block, if there is one. */
	public BlockPos adjacentInteriorAlong(BlockPos boundaryPosition, Direction direction) {
		if (kind != RegionGeometry.RegionKind.SELECTED && !poolShellBlocks.contains(boundaryPosition.asLong())) return null;
		BlockPos candidate = boundaryPosition.relative(direction);
		return containsInterior(candidate) ? candidate.immutable() : null;
	}

	public int blockSizeX() {
		return Math.addExact(Math.subtractExact(maximumInterior.getX(), minimumInterior.getX()), 1);
	}

	public int blockSizeY() {
		return Math.addExact(Math.subtractExact(maximumInterior.getY(), minimumInterior.getY()), 1);
	}

	public int blockSizeZ() {
		return Math.addExact(Math.subtractExact(maximumInterior.getZ(), minimumInterior.getZ()), 1);
	}

	public boolean requiresChunk(int chunkX, int chunkZ) {
		return requiredChunks.contains(ChunkPos.pack(chunkX, chunkZ));
	}
}

package com.example.simulation.controller;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;

import java.util.Objects;
import java.util.Set;

/** Immutable block-space topology discovered around one visible fluid controller boundary. */
public record FluidRegionFootprint(
		BlockPos controllerPosition,
		BlockPos minimumInterior,
		BlockPos maximumInterior,
		Set<Long> interiorBlocks,
		Set<Long> boundaryBlocks,
		Set<Long> requiredChunks
) {
	public FluidRegionFootprint {
		controllerPosition = Objects.requireNonNull(controllerPosition, "controllerPosition").immutable();
		minimumInterior = Objects.requireNonNull(minimumInterior, "minimumInterior").immutable();
		maximumInterior = Objects.requireNonNull(maximumInterior, "maximumInterior").immutable();
		interiorBlocks = Set.copyOf(interiorBlocks);
		boundaryBlocks = Set.copyOf(boundaryBlocks);
		requiredChunks = Set.copyOf(requiredChunks);
		if (interiorBlocks.isEmpty()) throw new IllegalArgumentException("A controller region needs at least one interior block");
		if (!boundaryBlocks.contains(controllerPosition.asLong())) {
			throw new IllegalArgumentException("The controller must be part of the visible region boundary");
		}
		if (requiredChunks.isEmpty()) throw new IllegalArgumentException("A controller region needs loaded chunks");
		if (minimumInterior.getX() > maximumInterior.getX()
				|| minimumInterior.getY() > maximumInterior.getY()
				|| minimumInterior.getZ() > maximumInterior.getZ()) {
			throw new IllegalArgumentException("Invalid controller-region bounds");
		}
		for (int chunkZ = minimumInterior.getZ() >> 4; chunkZ <= maximumInterior.getZ() >> 4; chunkZ++) {
			for (int chunkX = minimumInterior.getX() >> 4; chunkX <= maximumInterior.getX() >> 4; chunkX++) {
				if (!requiredChunks.contains(ChunkPos.pack(chunkX, chunkZ))) {
					throw new IllegalArgumentException("Required chunks must cover the complete dense solver bounds");
				}
			}
		}
	}

	public boolean containsInterior(BlockPos position) {
		return interiorBlocks.contains(position.asLong());
	}

	/**
	 * Resolves a shell block to its sole face-adjacent interior block. Corners, edges and stale
	 * positions deliberately return {@code null}; container routing must never guess diagonally.
	 */
	public BlockPos uniqueAdjacentInterior(BlockPos boundaryPosition) {
		if (!boundaryBlocks.contains(boundaryPosition.asLong())) return null;
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
		if (!boundaryBlocks.contains(boundaryPosition.asLong())) return null;
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

	public long denseBlockCapacity() {
		return Math.multiplyExact(Math.multiplyExact((long) blockSizeX(), blockSizeY()), blockSizeZ());
	}

	public boolean requiresChunk(int chunkX, int chunkZ) {
		return requiredChunks.contains(ChunkPos.pack(chunkX, chunkZ));
	}
}

package com.example.simulation;

import com.example.fluid.FlipFluidDomain;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable spatial identity and capacity reservation for one in-memory FLIP domain.
 * Reserved marker capacity is scheduling policy, never a replacement for particle-volume truth.
 */
public record FluidRegion(
		UUID id,
		ResourceKey<Level> dimension,
		BlockPos origin,
		FlipFluidDomain domain,
		long reservedMarkerCapacity
) {
	public FluidRegion {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(dimension, "dimension");
		origin = Objects.requireNonNull(origin, "origin").immutable();
		Objects.requireNonNull(domain, "domain");
		if (reservedMarkerCapacity < domain.particleCount()) {
			throw new IllegalArgumentException("Reserved marker capacity cannot be below the current marker count");
		}
	}

	public long reservedCellCapacity() {
		return Math.multiplyExact(Math.multiplyExact((long) domain.sizeX(), domain.sizeY()), domain.sizeZ());
	}
}

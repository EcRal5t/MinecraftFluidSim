package com.example.simulation.persistence;

import com.example.fluid.FluidSolverConfig;
import com.example.simulation.FluidRegion;
import com.example.simulation.controller.FluidRegionFootprint;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Metadata admitted at startup without allocating a solver. */
public record RegionManifest(
		UUID regionId,
		String dimensionId,
		long checkpointGeneration,
		long networkGeneration,
		BlockPos origin,
		int sizeX,
		int sizeY,
		int sizeZ,
		FluidSolverConfig solverConfig,
		long reservedMarkerCapacity,
		FluidRegion.PersistedRuntime runtime,
		FluidRegionFootprint footprint,
		int totalMarkerCount,
		long totalVolumeBits,
		List<SectionDescriptor> sections,
		List<RegionExtension> extensions,
		long checksum
) {
	public RegionManifest {
		Objects.requireNonNull(regionId, "regionId");
		Objects.requireNonNull(dimensionId, "dimensionId");
		origin = Objects.requireNonNull(origin, "origin").immutable();
		Objects.requireNonNull(solverConfig, "solverConfig");
		Objects.requireNonNull(runtime, "runtime");
		Objects.requireNonNull(footprint, "footprint");
		sections = List.copyOf(sections);
		extensions = List.copyOf(extensions);
		if (checkpointGeneration <= 0L || networkGeneration <= 0L || sizeX <= 0 || sizeY <= 0 || sizeZ <= 0
				|| reservedMarkerCapacity <= 0L || reservedMarkerCapacity > Integer.MAX_VALUE
				|| totalMarkerCount < 0 || totalMarkerCount > reservedMarkerCapacity || sections.isEmpty()) {
			throw new IllegalArgumentException("Invalid region manifest");
		}
		double totalVolume = Double.longBitsToDouble(totalVolumeBits);
		if (!Double.isFinite(totalVolume) || totalVolume < 0.0) throw new IllegalArgumentException("Invalid total marker volume");
	}

	public double totalVolume() { return Double.longBitsToDouble(totalVolumeBits); }

	public record SectionDescriptor(
			int sectionX,
			int sectionY,
			int sectionZ,
			int markerCount,
			long volumeBits,
			long checksum
	) implements Comparable<SectionDescriptor> {
		public SectionDescriptor {
			if (markerCount < 0) throw new IllegalArgumentException("Negative marker count");
			double volume = Double.longBitsToDouble(volumeBits);
			if (!Double.isFinite(volume) || volume < 0.0) throw new IllegalArgumentException("Invalid section volume");
		}

		public RegionSectionState.SectionKey key() {
			return new RegionSectionState.SectionKey(sectionX, sectionY, sectionZ);
		}

		@Override public int compareTo(SectionDescriptor other) { return key().compareTo(other.key()); }
	}
}

package com.example.simulation.persistence;

import com.example.fluid.FluidSolverConfig;
import com.example.fluid.FluidMaterial;
import com.example.fluid.FluidMaterialRegistry;
import com.example.simulation.FluidRegion;
import com.example.simulation.controller.FluidRegionFootprint;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Metadata admitted at startup without allocating a solver. */
public record RegionManifest(
		int formatVersion,
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
		List<FluidMaterial> materialPalette,
		long totalMassBits,
		List<MaterialLedger> materialLedgers,
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
		materialPalette = List.copyOf(materialPalette);
		materialLedgers = List.copyOf(materialLedgers);
		sections = List.copyOf(sections);
		extensions = List.copyOf(extensions);
		if (checkpointGeneration <= 0L || networkGeneration <= 0L || sizeX <= 0 || sizeY <= 0 || sizeZ <= 0
				|| reservedMarkerCapacity <= 0L || reservedMarkerCapacity > Integer.MAX_VALUE
				|| totalMarkerCount < 0 || totalMarkerCount > reservedMarkerCapacity || sections.isEmpty()) {
			throw new IllegalArgumentException("Invalid region manifest");
		}
		double totalVolume = Double.longBitsToDouble(totalVolumeBits);
		if (!Double.isFinite(totalVolume) || totalVolume < 0.0) throw new IllegalArgumentException("Invalid total marker volume");
		if (formatVersion == 1) {
			if (!materialPalette.isEmpty() || !materialLedgers.isEmpty() || totalMassBits != 0L
					|| sections.stream().anyMatch(section -> section.massBits() != 0L)) {
				throw new IllegalArgumentException("region-state v1 cannot contain material state");
			}
		} else if (formatVersion == 2) {
			FluidMaterialRegistry registry = new FluidMaterialRegistry(materialPalette);
			double densityTolerance = Math.max(1.0E-9, Math.abs(solverConfig.density()) * 1.0E-12);
			if (Math.abs(registry.material(0).referenceDensity() - solverConfig.density()) > densityTolerance) {
				throw new IllegalArgumentException("Material palette entry 0 must match the solver density");
			}
			if (materialLedgers.size() != materialPalette.size()) {
				throw new IllegalArgumentException("Material palette and ledger counts disagree");
			}
			double totalMass = Double.longBitsToDouble(totalMassBits);
			if (!Double.isFinite(totalMass) || totalMass < 0.0) throw new IllegalArgumentException("Invalid total marker mass");
			int ledgerMarkers = 0;
			for (MaterialLedger ledger : materialLedgers) {
				ledgerMarkers = Math.addExact(ledgerMarkers, ledger.markerCount());
			}
			if (ledgerMarkers != totalMarkerCount) {
				throw new IllegalArgumentException("Material marker counts do not match manifest total");
			}
		} else {
			throw new IllegalArgumentException("Unsupported region-state version " + formatVersion);
		}
	}

	/** Compatibility constructor for the byte-stable pure-water v1 format. */
	public RegionManifest(
			UUID regionId, String dimensionId, long checkpointGeneration, long networkGeneration, BlockPos origin,
			int sizeX, int sizeY, int sizeZ, FluidSolverConfig solverConfig, long reservedMarkerCapacity,
			FluidRegion.PersistedRuntime runtime, FluidRegionFootprint footprint, int totalMarkerCount,
			long totalVolumeBits, List<SectionDescriptor> sections, List<RegionExtension> extensions, long checksum
	) {
		this(1, regionId, dimensionId, checkpointGeneration, networkGeneration, origin, sizeX, sizeY, sizeZ,
				solverConfig, reservedMarkerCapacity, runtime, footprint, totalMarkerCount, totalVolumeBits,
				List.of(), 0L, List.of(), sections, extensions, checksum);
	}

	public double totalVolume() { return Double.longBitsToDouble(totalVolumeBits); }
	public double totalMass() { return Double.longBitsToDouble(totalMassBits); }

	public record MaterialLedger(int markerCount, long volumeBits, long massBits) {
		public MaterialLedger {
			if (markerCount < 0) throw new IllegalArgumentException("Negative material marker count");
			double volume = Double.longBitsToDouble(volumeBits), mass = Double.longBitsToDouble(massBits);
			if (!Double.isFinite(volume) || volume < 0.0 || !Double.isFinite(mass) || mass < 0.0) {
				throw new IllegalArgumentException("Invalid material ledger");
			}
		}

		public double volume() { return Double.longBitsToDouble(volumeBits); }
		public double mass() { return Double.longBitsToDouble(massBits); }
	}

	public record SectionDescriptor(
			int sectionX,
			int sectionY,
			int sectionZ,
			int markerCount,
			long volumeBits,
			long massBits,
			long checksum
	) implements Comparable<SectionDescriptor> {
		public SectionDescriptor {
			if (markerCount < 0) throw new IllegalArgumentException("Negative marker count");
			double volume = Double.longBitsToDouble(volumeBits);
			if (!Double.isFinite(volume) || volume < 0.0) throw new IllegalArgumentException("Invalid section volume");
			double mass = Double.longBitsToDouble(massBits);
			if (!Double.isFinite(mass) || mass < 0.0) throw new IllegalArgumentException("Invalid section mass");
		}

		public SectionDescriptor(int sectionX, int sectionY, int sectionZ, int markerCount, long volumeBits, long checksum) {
			this(sectionX, sectionY, sectionZ, markerCount, volumeBits, 0L, checksum);
		}

		public RegionSectionState.SectionKey key() {
			return new RegionSectionState.SectionKey(sectionX, sectionY, sectionZ);
		}

		@Override public int compareTo(SectionDescriptor other) { return key().compareTo(other.key()); }
	}
}

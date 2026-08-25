package com.example.simulation.persistence;

import com.example.fluid.FluidSolverConfig;
import com.example.fluid.FluidMaterial;
import com.example.fluid.FluidMaterialRegistry;
import com.example.fluid.FlipChemicalParticleState;
import com.example.chemistry.ChemicalLimits;
import com.example.simulation.FluidRegion;
import com.example.simulation.controller.FluidRegionFootprint;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Arrays;
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
		String registryFingerprint,
		long auScale,
		List<String> speciesPalette,
		List<FlipChemicalParticleState.StableMixture> chemicalMixtures,
		long[] chemicalSpeciesTotals,
		long chemicalResidualLedgerBits,
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
		registryFingerprint = Objects.requireNonNull(registryFingerprint, "registryFingerprint");
		speciesPalette = List.copyOf(speciesPalette);
		chemicalMixtures = List.copyOf(chemicalMixtures);
		chemicalSpeciesTotals = chemicalSpeciesTotals.clone();
		if (checkpointGeneration <= 0L || networkGeneration <= 0L || sizeX <= 0 || sizeY <= 0 || sizeZ <= 0
				|| reservedMarkerCapacity <= 0L || reservedMarkerCapacity > Integer.MAX_VALUE
				|| totalMarkerCount < 0 || totalMarkerCount > reservedMarkerCapacity || sections.isEmpty()) {
			throw new IllegalArgumentException("Invalid region manifest");
		}
		double totalVolume = Double.longBitsToDouble(totalVolumeBits);
		if (!Double.isFinite(totalVolume) || totalVolume < 0.0) throw new IllegalArgumentException("Invalid total marker volume");
		if (formatVersion == 1) {
			if (!materialPalette.isEmpty() || !materialLedgers.isEmpty() || totalMassBits != 0L
					|| sections.stream().anyMatch(section -> section.massBits() != 0L)
					|| !registryFingerprint.isEmpty() || auScale != 0L || !speciesPalette.isEmpty()
					|| !chemicalMixtures.isEmpty() || chemicalSpeciesTotals.length != 0
					|| chemicalResidualLedgerBits != 0L) {
				throw new IllegalArgumentException("region-state v1 cannot contain material state");
			}
		} else if (formatVersion == 2 || formatVersion == 3 || formatVersion == 5 || formatVersion == 7) {
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
			if (formatVersion == 2 || (formatVersion == 5 || formatVersion == 7) && registryFingerprint.isEmpty()) {
				if (!registryFingerprint.isEmpty() || auScale != 0L || !speciesPalette.isEmpty()
						|| !chemicalMixtures.isEmpty() || chemicalSpeciesTotals.length != 0
						|| chemicalResidualLedgerBits != 0L) {
					throw new IllegalArgumentException("region-state v2 cannot contain chemical state");
				}
			} else {
				if (!registryFingerprint.matches("[0-9a-f]{64}") || auScale != ChemicalLimits.QUANTA_PER_AU
						|| speciesPalette.isEmpty() || speciesPalette.size() > ChemicalLimits.MAX_SPECIES
						|| chemicalSpeciesTotals.length != speciesPalette.size()
						|| chemicalMixtures.size() > totalMarkerCount
						|| (totalMarkerCount > 0 && chemicalMixtures.isEmpty())
						|| !Double.isFinite(Double.longBitsToDouble(chemicalResidualLedgerBits))) {
					throw new IllegalArgumentException("Invalid region-state v3 chemical manifest");
				}
				long quanta = 0L;
				for (long amount : chemicalSpeciesTotals) {
					if (amount < 0L) throw new IllegalArgumentException("Negative chemical species ledger");
					quanta = Math.addExact(quanta, amount);
				}
				if (quanta > ChemicalLimits.MAX_REGION_TOTAL_QUANTA) {
					throw new IllegalArgumentException("Region chemical ledger is over budget");
				}
			}
		} else {
			throw new IllegalArgumentException("Unsupported region-state version " + formatVersion);
		}
	}

	/** Convenience constructor for the pure-water feature layout. */
	public RegionManifest(
			UUID regionId, String dimensionId, long checkpointGeneration, long networkGeneration, BlockPos origin,
			int sizeX, int sizeY, int sizeZ, FluidSolverConfig solverConfig, long reservedMarkerCapacity,
			FluidRegion.PersistedRuntime runtime, FluidRegionFootprint footprint, int totalMarkerCount,
			long totalVolumeBits, List<SectionDescriptor> sections, List<RegionExtension> extensions, long checksum
	) {
		this(1, regionId, dimensionId, checkpointGeneration, networkGeneration, origin, sizeX, sizeY, sizeZ,
				solverConfig, reservedMarkerCapacity, runtime, footprint, totalMarkerCount, totalVolumeBits,
				List.of(), 0L, List.of(), sections, extensions, "", 0L, List.of(), List.of(), new long[0], 0L, checksum);
	}

	/** Convenience constructor for non-chemical feature layouts. */
	public RegionManifest(
			int formatVersion, UUID regionId, String dimensionId, long checkpointGeneration, long networkGeneration,
			BlockPos origin, int sizeX, int sizeY, int sizeZ, FluidSolverConfig solverConfig,
			long reservedMarkerCapacity, FluidRegion.PersistedRuntime runtime, FluidRegionFootprint footprint,
			int totalMarkerCount, long totalVolumeBits, List<FluidMaterial> materialPalette, long totalMassBits,
			List<MaterialLedger> materialLedgers, List<SectionDescriptor> sections,
			List<RegionExtension> extensions, long checksum
	) {
		this(formatVersion, regionId, dimensionId, checkpointGeneration, networkGeneration, origin, sizeX, sizeY, sizeZ,
				solverConfig, reservedMarkerCapacity, runtime, footprint, totalMarkerCount, totalVolumeBits,
				materialPalette, totalMassBits, materialLedgers, sections, extensions,
				"", 0L, List.of(), List.of(), new long[0], 0L, checksum);
	}

	@Override public long[] chemicalSpeciesTotals() { return chemicalSpeciesTotals.clone(); }

	@Override
	public boolean equals(Object other) {
		if (this == other) return true;
		if (!(other instanceof RegionManifest that)) return false;
		return formatVersion == that.formatVersion
				&& checkpointGeneration == that.checkpointGeneration && networkGeneration == that.networkGeneration
				&& sizeX == that.sizeX && sizeY == that.sizeY && sizeZ == that.sizeZ
				&& reservedMarkerCapacity == that.reservedMarkerCapacity && totalMarkerCount == that.totalMarkerCount
				&& totalVolumeBits == that.totalVolumeBits && totalMassBits == that.totalMassBits
				&& auScale == that.auScale && chemicalResidualLedgerBits == that.chemicalResidualLedgerBits
				&& checksum == that.checksum && regionId.equals(that.regionId) && dimensionId.equals(that.dimensionId)
				&& origin.equals(that.origin) && solverConfig.equals(that.solverConfig) && runtime.equals(that.runtime)
				&& footprint.equals(that.footprint) && materialPalette.equals(that.materialPalette)
				&& materialLedgers.equals(that.materialLedgers) && sections.equals(that.sections)
				&& extensions.equals(that.extensions) && registryFingerprint.equals(that.registryFingerprint)
				&& speciesPalette.equals(that.speciesPalette) && chemicalMixtures.equals(that.chemicalMixtures)
				&& Arrays.equals(chemicalSpeciesTotals, that.chemicalSpeciesTotals);
	}

	@Override
	public int hashCode() {
		int result = Objects.hash(formatVersion, regionId, dimensionId, checkpointGeneration, networkGeneration,
				origin, sizeX, sizeY, sizeZ, solverConfig, reservedMarkerCapacity, runtime, footprint,
				totalMarkerCount, totalVolumeBits, materialPalette, totalMassBits, materialLedgers, sections,
				extensions, registryFingerprint, auScale, speciesPalette, chemicalMixtures,
				chemicalResidualLedgerBits, checksum);
		return 31 * result + Arrays.hashCode(chemicalSpeciesTotals);
	}

	public double totalVolume() { return Double.longBitsToDouble(totalVolumeBits); }
	public double totalMass() { return Double.longBitsToDouble(totalMassBits); }
	public boolean hasChemicalState() {
		return formatVersion == 3 || (formatVersion == 5 || formatVersion == 7) && !registryFingerprint.isEmpty();
	}
	public boolean hasGasState() {
		return formatVersion == 7 && extensions.stream()
				.anyMatch(extension -> GasRegionStateCodec.MANIFEST_EXTENSION.equals(extension.type()));
	}
	public boolean hasGranularState() {
		return formatVersion == 5 || formatVersion == 7 && extensions.stream()
				.anyMatch(extension -> GranularRegionStateCodec.MANIFEST_EXTENSION.equals(extension.type()));
	}
	public boolean hasThermalState() { return formatVersion == 7; }

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

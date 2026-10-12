package mmm.model.region;

import mmm.chemistry.ChemicalLimits;
import mmm.fluid.FlipChemicalParticleState;
import mmm.fluid.FluidMaterial;
import mmm.fluid.FluidMaterialRegistry;
import mmm.fluid.FluidSolverConfig;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Complete logical manifest admitted before any solver or Minecraft runtime object is allocated. */
public record RegionManifest(
		RegionStateLayout layout,
		RegionIdentity identity,
		RegionGeometry geometry,
		RegionRuntimeIntent runtime,
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
		Objects.requireNonNull(layout, "layout");
		Objects.requireNonNull(identity, "identity");
		Objects.requireNonNull(geometry, "geometry");
		Objects.requireNonNull(runtime, "runtime");
		materialPalette = List.copyOf(materialPalette);
		materialLedgers = List.copyOf(materialLedgers);
		sections = List.copyOf(sections);
		extensions = List.copyOf(extensions);
		registryFingerprint = Objects.requireNonNull(registryFingerprint, "registryFingerprint");
		speciesPalette = List.copyOf(speciesPalette);
		chemicalMixtures = List.copyOf(chemicalMixtures);
		chemicalSpeciesTotals = chemicalSpeciesTotals.clone();
		if (totalMarkerCount < 0 || totalMarkerCount > geometry.reservedMarkerCapacity() || sections.isEmpty()) {
			throw new IllegalArgumentException("Invalid region manifest");
		}
		double totalVolume = Double.longBitsToDouble(totalVolumeBits);
		if (!Double.isFinite(totalVolume) || totalVolume < 0.0) throw new IllegalArgumentException("Invalid total marker volume");
		validateFeatureLedger(layout, geometry, totalMarkerCount, totalMassBits, materialPalette,
				materialLedgers, sections, registryFingerprint, auScale, speciesPalette,
				chemicalMixtures, chemicalSpeciesTotals, chemicalResidualLedgerBits);
	}

	/** Flattened constructor used by assemblers and strict codecs at the adapter boundary. */
	public RegionManifest(
			RegionStateLayout layout, UUID regionId, String dimensionId, long checkpointGeneration,
			long networkGeneration, RegionGeometry.BlockPosition origin, int sizeX, int sizeY, int sizeZ,
			FluidSolverConfig solverConfig, long reservedMarkerCapacity, RegionRuntimeIntent runtime,
			RegionGeometry.ControllerFootprint footprint, int totalMarkerCount, long totalVolumeBits,
			List<FluidMaterial> materialPalette, long totalMassBits, List<MaterialLedger> materialLedgers,
			List<SectionDescriptor> sections, List<RegionExtension> extensions, String registryFingerprint,
			long auScale, List<String> speciesPalette,
			List<FlipChemicalParticleState.StableMixture> chemicalMixtures, long[] chemicalSpeciesTotals,
			long chemicalResidualLedgerBits, long checksum
	) {
		this(layout, new RegionIdentity(regionId, dimensionId, checkpointGeneration, networkGeneration),
				new RegionGeometry(origin, sizeX, sizeY, sizeZ, solverConfig, reservedMarkerCapacity, footprint),
				runtime, totalMarkerCount, totalVolumeBits, materialPalette, totalMassBits, materialLedgers,
				sections, extensions, registryFingerprint, auScale, speciesPalette, chemicalMixtures,
				chemicalSpeciesTotals, chemicalResidualLedgerBits, checksum);
	}

	/** Convenience constructor for the pure-water feature layout. */
	public RegionManifest(
			UUID regionId, String dimensionId, long checkpointGeneration, long networkGeneration,
			RegionGeometry.BlockPosition origin, int sizeX, int sizeY, int sizeZ, FluidSolverConfig solverConfig,
			long reservedMarkerCapacity, RegionRuntimeIntent runtime, RegionGeometry.ControllerFootprint footprint,
			int totalMarkerCount, long totalVolumeBits, List<SectionDescriptor> sections,
			List<RegionExtension> extensions, long checksum
	) {
		this(RegionStateLayout.PURE, regionId, dimensionId, checkpointGeneration, networkGeneration, origin,
				sizeX, sizeY, sizeZ, solverConfig, reservedMarkerCapacity, runtime, footprint,
				totalMarkerCount, totalVolumeBits, List.of(), 0L, List.of(), sections, extensions,
				"", 0L, List.of(), List.of(), new long[0], 0L, checksum);
	}

	/** Convenience constructor for non-chemical feature layouts. */
	public RegionManifest(
			RegionStateLayout layout, UUID regionId, String dimensionId, long checkpointGeneration,
			long networkGeneration, RegionGeometry.BlockPosition origin, int sizeX, int sizeY, int sizeZ,
			FluidSolverConfig solverConfig, long reservedMarkerCapacity, RegionRuntimeIntent runtime,
			RegionGeometry.ControllerFootprint footprint, int totalMarkerCount, long totalVolumeBits,
			List<FluidMaterial> materialPalette, long totalMassBits, List<MaterialLedger> materialLedgers,
			List<SectionDescriptor> sections, List<RegionExtension> extensions, long checksum
	) {
		this(layout, regionId, dimensionId, checkpointGeneration, networkGeneration, origin, sizeX, sizeY, sizeZ,
				solverConfig, reservedMarkerCapacity, runtime, footprint, totalMarkerCount, totalVolumeBits,
				materialPalette, totalMassBits, materialLedgers, sections, extensions,
				"", 0L, List.of(), List.of(), new long[0], 0L, checksum);
	}

	private static void validateFeatureLedger(
			RegionStateLayout layout, RegionGeometry geometry, int totalMarkerCount, long totalMassBits,
			List<FluidMaterial> materialPalette, List<MaterialLedger> materialLedgers,
			List<SectionDescriptor> sections, String registryFingerprint, long auScale,
			List<String> speciesPalette, List<FlipChemicalParticleState.StableMixture> chemicalMixtures,
			long[] chemicalSpeciesTotals, long chemicalResidualLedgerBits
	) {
		if (!layout.hasMaterials()) {
			if (!materialPalette.isEmpty() || !materialLedgers.isEmpty() || totalMassBits != 0L
					|| sections.stream().anyMatch(section -> section.massBits() != 0L)
					|| !registryFingerprint.isEmpty() || auScale != 0L || !speciesPalette.isEmpty()
					|| !chemicalMixtures.isEmpty() || chemicalSpeciesTotals.length != 0
					|| chemicalResidualLedgerBits != 0L) {
				throw new IllegalArgumentException("Pure region state cannot contain material state");
			}
			return;
		}
		FluidMaterialRegistry registry = new FluidMaterialRegistry(materialPalette);
		double densityTolerance = Math.max(1.0E-9, Math.abs(geometry.solverConfig().density()) * 1.0E-12);
		if (Math.abs(registry.material(0).referenceDensity() - geometry.solverConfig().density()) > densityTolerance) {
			throw new IllegalArgumentException("Material palette entry 0 must match the solver density");
		}
		if (materialLedgers.size() != materialPalette.size()) {
			throw new IllegalArgumentException("Material palette and ledger counts disagree");
		}
		double totalMass = Double.longBitsToDouble(totalMassBits);
		if (!Double.isFinite(totalMass) || totalMass < 0.0) throw new IllegalArgumentException("Invalid total marker mass");
		int ledgerMarkers = 0;
		for (MaterialLedger ledger : materialLedgers) ledgerMarkers = Math.addExact(ledgerMarkers, ledger.markerCount());
		if (ledgerMarkers != totalMarkerCount) throw new IllegalArgumentException("Material marker counts do not match manifest total");

		boolean chemicalState = layout.requiresChemistry()
				|| layout.permitsOptionalChemistry() && !registryFingerprint.isEmpty();
		if (!chemicalState) {
			if (!registryFingerprint.isEmpty() || auScale != 0L || !speciesPalette.isEmpty()
					|| !chemicalMixtures.isEmpty() || chemicalSpeciesTotals.length != 0
					|| chemicalResidualLedgerBits != 0L) {
				throw new IllegalArgumentException("Non-chemical region state cannot contain chemical state");
			}
			return;
		}
		if (!registryFingerprint.matches("[0-9a-f]{64}") || auScale != ChemicalLimits.QUANTA_PER_AU
				|| speciesPalette.isEmpty() || speciesPalette.size() > ChemicalLimits.MAX_SPECIES
				|| chemicalSpeciesTotals.length != speciesPalette.size()
				|| chemicalMixtures.size() > totalMarkerCount
				|| totalMarkerCount > 0 && chemicalMixtures.isEmpty()
				|| !Double.isFinite(Double.longBitsToDouble(chemicalResidualLedgerBits))) {
			throw new IllegalArgumentException("Invalid chemical region manifest");
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

	public UUID regionId() { return identity.regionId(); }
	public String dimensionId() { return identity.dimensionId(); }
	public long checkpointGeneration() { return identity.checkpointGeneration(); }
	public long networkGeneration() { return identity.networkGeneration(); }
	public RegionGeometry.BlockPosition origin() { return geometry.origin(); }
	public int sizeX() { return geometry.sizeX(); }
	public int sizeY() { return geometry.sizeY(); }
	public int sizeZ() { return geometry.sizeZ(); }
	public FluidSolverConfig solverConfig() { return geometry.solverConfig(); }
	public long reservedMarkerCapacity() { return geometry.reservedMarkerCapacity(); }
	public RegionGeometry.ControllerFootprint footprint() { return geometry.footprint(); }
	@Override public long[] chemicalSpeciesTotals() { return chemicalSpeciesTotals.clone(); }

	@Override
	public boolean equals(Object other) {
		if (this == other) return true;
		if (!(other instanceof RegionManifest that)) return false;
		return totalMarkerCount == that.totalMarkerCount && totalVolumeBits == that.totalVolumeBits
				&& totalMassBits == that.totalMassBits && auScale == that.auScale
				&& chemicalResidualLedgerBits == that.chemicalResidualLedgerBits && checksum == that.checksum
				&& layout == that.layout && identity.equals(that.identity) && geometry.equals(that.geometry)
				&& runtime.equals(that.runtime) && materialPalette.equals(that.materialPalette)
				&& materialLedgers.equals(that.materialLedgers) && sections.equals(that.sections)
				&& extensions.equals(that.extensions) && registryFingerprint.equals(that.registryFingerprint)
				&& speciesPalette.equals(that.speciesPalette) && chemicalMixtures.equals(that.chemicalMixtures)
				&& Arrays.equals(chemicalSpeciesTotals, that.chemicalSpeciesTotals);
	}

	@Override
	public int hashCode() {
		int result = Objects.hash(layout, identity, geometry, runtime, totalMarkerCount, totalVolumeBits,
				materialPalette, totalMassBits, materialLedgers, sections, extensions, registryFingerprint,
				auScale, speciesPalette, chemicalMixtures, chemicalResidualLedgerBits, checksum);
		return 31 * result + Arrays.hashCode(chemicalSpeciesTotals);
	}

	public double totalVolume() { return Double.longBitsToDouble(totalVolumeBits); }
	public double totalMass() { return Double.longBitsToDouble(totalMassBits); }
	public boolean hasChemicalState() {
		return layout.requiresChemistry() || layout.permitsOptionalChemistry() && !registryFingerprint.isEmpty();
	}
	public boolean hasGasState() { return layout.requiresThermal() && hasExtension("mmm:gas_manifest"); }
	public boolean hasGranularState() {
		return layout.requiresGranular() || layout.requiresThermal() && hasExtension("mmm:granular_manifest");
	}
	public boolean hasThermalState() { return layout.requiresThermal(); }
	private boolean hasExtension(String type) { return extensions.stream().anyMatch(value -> type.equals(value.type())); }

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
			int sectionX, int sectionY, int sectionZ, int markerCount,
			long volumeBits, long massBits, long checksum
	) implements Comparable<SectionDescriptor> {
		public SectionDescriptor {
			if (markerCount < 0) throw new IllegalArgumentException("Negative marker count");
			double volume = Double.longBitsToDouble(volumeBits), mass = Double.longBitsToDouble(massBits);
			if (!Double.isFinite(volume) || volume < 0.0 || !Double.isFinite(mass) || mass < 0.0) {
				throw new IllegalArgumentException("Invalid section ledger");
			}
		}
		public SectionDescriptor(int x, int y, int z, int count, long volumeBits, long checksum) {
			this(x, y, z, count, volumeBits, 0L, checksum);
		}
		public RegionSectionState.SectionKey key() { return new RegionSectionState.SectionKey(sectionX, sectionY, sectionZ); }
		@Override public int compareTo(SectionDescriptor other) { return key().compareTo(other.key()); }
	}
}

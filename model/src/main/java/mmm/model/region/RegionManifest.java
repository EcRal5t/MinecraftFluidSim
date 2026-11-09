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
		long totalMassUnits,
		List<MaterialLedger> materialLedgers,
		List<SectionDescriptor> sections,
		List<RegionExtension> extensions,
		String registryFingerprint,
		long quantaPerMol,
		List<String> speciesPalette,
		List<FlipChemicalParticleState.StableMixture> chemicalMixtures,
		long[] chemicalSpeciesTotals,
		long chemicalResidualLedgerUnits,
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
		if (totalMarkerCount < 0 || sections.isEmpty()) {
			throw new IllegalArgumentException("Invalid region manifest");
		}
		if (totalMassUnits < 0 || mmm.units.Mass.sum(sections.stream().mapToLong(SectionDescriptor::massUnits).toArray()) != totalMassUnits)
			throw new IllegalArgumentException("Manifest mass counts do not close");
		double totalVolume = Double.longBitsToDouble(totalVolumeBits);
		if (!Double.isFinite(totalVolume) || totalVolume < 0.0) throw new IllegalArgumentException("Invalid total marker volume");
		validateFeatureLedger(layout, geometry, totalMarkerCount, totalMassUnits, materialPalette,
				materialLedgers, sections, registryFingerprint, quantaPerMol, speciesPalette,
				chemicalMixtures, chemicalSpeciesTotals, chemicalResidualLedgerUnits);
	}

	/** Flattened constructor used by assemblers and strict codecs at the adapter boundary. */
	public RegionManifest(
			RegionStateLayout layout, UUID regionId, String dimensionId, long checkpointGeneration,
			long networkGeneration, RegionGeometry.BlockPosition origin, int sizeX, int sizeY, int sizeZ,
			FluidSolverConfig solverConfig, RegionRuntimeIntent runtime,
			RegionGeometry.ControllerFootprint footprint, int totalMarkerCount, long totalVolumeBits,
			List<FluidMaterial> materialPalette, long totalMassUnits, List<MaterialLedger> materialLedgers,
			List<SectionDescriptor> sections, List<RegionExtension> extensions, String registryFingerprint,
			long quantaPerMol, List<String> speciesPalette,
			List<FlipChemicalParticleState.StableMixture> chemicalMixtures, long[] chemicalSpeciesTotals,
			long chemicalResidualLedgerUnits, long checksum
	) {
		this(layout, new RegionIdentity(regionId, dimensionId, checkpointGeneration, networkGeneration),
				new RegionGeometry(origin, sizeX, sizeY, sizeZ, solverConfig, footprint),
				runtime, totalMarkerCount, totalVolumeBits, materialPalette, totalMassUnits, materialLedgers,
				sections, extensions, registryFingerprint, quantaPerMol, speciesPalette, chemicalMixtures,
				chemicalSpeciesTotals, chemicalResidualLedgerUnits, checksum);
	}

	/** Convenience constructor for the pure-water feature layout. */
	public RegionManifest(
			UUID regionId, String dimensionId, long checkpointGeneration, long networkGeneration,
			RegionGeometry.BlockPosition origin, int sizeX, int sizeY, int sizeZ, FluidSolverConfig solverConfig, RegionRuntimeIntent runtime, RegionGeometry.ControllerFootprint footprint,
			int totalMarkerCount, long totalVolumeBits, List<SectionDescriptor> sections,
			List<RegionExtension> extensions, long checksum
	) {
		this(RegionStateLayout.PURE, regionId, dimensionId, checkpointGeneration, networkGeneration, origin,
				sizeX, sizeY, sizeZ, solverConfig, runtime, footprint,
				totalMarkerCount, totalVolumeBits, List.of(), mmm.units.Mass.sum(sections.stream().mapToLong(SectionDescriptor::massUnits).toArray()), List.of(), sections, extensions,
				"", 0L, List.of(), List.of(), new long[0], 0L, checksum);
	}

	/** Convenience constructor for non-chemical feature layouts. */
	public RegionManifest(
			RegionStateLayout layout, UUID regionId, String dimensionId, long checkpointGeneration,
			long networkGeneration, RegionGeometry.BlockPosition origin, int sizeX, int sizeY, int sizeZ,
			FluidSolverConfig solverConfig, RegionRuntimeIntent runtime,
			RegionGeometry.ControllerFootprint footprint, int totalMarkerCount, long totalVolumeBits,
			List<FluidMaterial> materialPalette, long totalMassUnits, List<MaterialLedger> materialLedgers,
			List<SectionDescriptor> sections, List<RegionExtension> extensions, long checksum
	) {
		this(layout, regionId, dimensionId, checkpointGeneration, networkGeneration, origin, sizeX, sizeY, sizeZ,
				solverConfig, runtime, footprint, totalMarkerCount, totalVolumeBits,
				materialPalette, totalMassUnits, materialLedgers, sections, extensions,
				"", 0L, List.of(), List.of(), new long[0], 0L, checksum);
	}

	private static void validateFeatureLedger(
			RegionStateLayout layout, RegionGeometry geometry, int totalMarkerCount, long totalMassUnits,
			List<FluidMaterial> materialPalette, List<MaterialLedger> materialLedgers,
			List<SectionDescriptor> sections, String registryFingerprint, long quantaPerMol,
			List<String> speciesPalette, List<FlipChemicalParticleState.StableMixture> chemicalMixtures,
			long[] chemicalSpeciesTotals, long chemicalResidualLedgerUnits
	) {
		if (!layout.hasMaterials()) {
			if (!materialPalette.isEmpty() || !materialLedgers.isEmpty()
					|| !registryFingerprint.isEmpty() || quantaPerMol != 0L || !speciesPalette.isEmpty()
					|| !chemicalMixtures.isEmpty() || chemicalSpeciesTotals.length != 0
					|| chemicalResidualLedgerUnits != 0L) {
				throw new IllegalArgumentException("Pure region state cannot contain material state");
			}
			return;
		}
		FluidMaterialRegistry registry = new FluidMaterialRegistry(materialPalette);
		if (!registry.material(0).id().equals("mmm:water"))
			throw new IllegalArgumentException("Material palette entry 0 must be water");
		if (materialLedgers.size() != materialPalette.size()) {
			throw new IllegalArgumentException("Material palette and ledger counts disagree");
		}
		if (totalMassUnits < 0) throw new IllegalArgumentException("Invalid total marker mass");
		int ledgerMarkers = 0;
		for (MaterialLedger ledger : materialLedgers) ledgerMarkers = Math.addExact(ledgerMarkers, ledger.markerCount());
		if (ledgerMarkers != totalMarkerCount) throw new IllegalArgumentException("Material marker counts do not match manifest total");

		boolean chemicalState = layout.requiresChemistry()
				|| layout.permitsOptionalChemistry() && !registryFingerprint.isEmpty();
		if (!chemicalState) {
			if (!registryFingerprint.isEmpty() || quantaPerMol != 0L || !speciesPalette.isEmpty()
					|| !chemicalMixtures.isEmpty() || chemicalSpeciesTotals.length != 0
					|| chemicalResidualLedgerUnits != 0L) {
				throw new IllegalArgumentException("Non-chemical region state cannot contain chemical state");
			}
			return;
		}
		if (!registryFingerprint.matches("[0-9a-f]{64}") || quantaPerMol != ChemicalLimits.QUANTA_PER_MOL
				|| speciesPalette.isEmpty() || speciesPalette.size() > ChemicalLimits.MAX_SPECIES
				|| chemicalSpeciesTotals.length != speciesPalette.size()
				|| chemicalMixtures.size() > totalMarkerCount
				) {
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
	public RegionGeometry.ControllerFootprint footprint() { return geometry.footprint(); }
	@Override public long[] chemicalSpeciesTotals() { return chemicalSpeciesTotals.clone(); }

	@Override
	public boolean equals(Object other) {
		if (this == other) return true;
		if (!(other instanceof RegionManifest that)) return false;
		return totalMarkerCount == that.totalMarkerCount && totalVolumeBits == that.totalVolumeBits
				&& totalMassUnits == that.totalMassUnits && quantaPerMol == that.quantaPerMol
				&& chemicalResidualLedgerUnits == that.chemicalResidualLedgerUnits && checksum == that.checksum
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
				materialPalette, totalMassUnits, materialLedgers, sections, extensions, registryFingerprint,
				quantaPerMol, speciesPalette, chemicalMixtures, chemicalResidualLedgerUnits, checksum);
		return 31 * result + Arrays.hashCode(chemicalSpeciesTotals);
	}

	public double totalVolume() { return Double.longBitsToDouble(totalVolumeBits); }
	public double totalMass() { return mmm.units.Mass.kilograms(totalMassUnits); }
	public boolean hasChemicalState() {
		return layout.requiresChemistry() || layout.permitsOptionalChemistry() && !registryFingerprint.isEmpty();
	}
	public boolean hasGasState() { return hasExtension("mmm:gas_manifest"); }
	public boolean hasGranularState() {
		return layout.requiresGranular() || layout.requiresThermal() && hasExtension("mmm:granular_manifest");
	}
	public boolean hasThermalState() { return layout.requiresThermal(); }
	private boolean hasExtension(String type) { return extensions.stream().anyMatch(value -> type.equals(value.type())); }

	public record MaterialLedger(int markerCount, long volumeBits, long massUnits) {
		public MaterialLedger {
			if (markerCount < 0) throw new IllegalArgumentException("Negative material marker count");
			double volume = Double.longBitsToDouble(volumeBits);
			if (!Double.isFinite(volume) || volume < 0.0 || massUnits < 0) {
				throw new IllegalArgumentException("Invalid material ledger");
			}
		}
		public double volume() { return Double.longBitsToDouble(volumeBits); }
		public double mass() { return mmm.units.Mass.kilograms(massUnits); }
	}

	public record SectionDescriptor(
			int sectionX, int sectionY, int sectionZ, int markerCount,
			long volumeBits, long massUnits, long checksum
	) implements Comparable<SectionDescriptor> {
		public SectionDescriptor {
			if (markerCount < 0) throw new IllegalArgumentException("Negative marker count");
			double volume = Double.longBitsToDouble(volumeBits);
			if (!Double.isFinite(volume) || volume < 0.0 || massUnits < 0) {
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

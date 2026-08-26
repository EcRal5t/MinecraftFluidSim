package cc.ecisr.mmm.simulation.persistence;

import cc.ecisr.mmm.fluid.FluidMaterial;
import cc.ecisr.mmm.fluid.FlipChemicalParticleState;
import cc.ecisr.mmm.chemistry.ChemicalLimits;
import cc.ecisr.mmm.fluid.FluidSolverConfig;
import cc.ecisr.mmm.simulation.FluidRegion;
import cc.ecisr.mmm.simulation.FluidRegionLifecycle;
import cc.ecisr.mmm.simulation.controller.FluidRegionFootprint;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Strict NBT codec for the feature layouts written by the current runtime. */
final class RegionStateCodec {
	static final int MAX_MATERIALS = 256;
	static final int MAX_SECTIONS = 4_096;

	CompoundTag encodeManifest(RegionManifest manifest) {
		CompoundTag root = identity(manifest.regionId(), manifest.dimensionId(), manifest.checkpointGeneration(), manifest.layout());
		root.putString("CapabilityProfileFingerprint", manifest.capabilityProfileFingerprint());
		root.putString("RegistryAggregateFingerprint", manifest.registryAggregateFingerprint());
		root.putString("MaterialRegistryFingerprint", manifest.materialRegistryFingerprint());
		root.putLong("NetworkGeneration", manifest.networkGeneration());
		root.putLong("Origin", manifest.origin().asLong());
		root.putInt("SizeX", manifest.sizeX()); root.putInt("SizeY", manifest.sizeY()); root.putInt("SizeZ", manifest.sizeZ());
		root.put("Solver", encodeConfig(manifest.solverConfig()));
		root.putLong("MarkerBudget", manifest.reservedMarkerCapacity());
		root.put("Runtime", encodeRuntime(manifest.runtime()));
		root.put("Footprint", encodeFootprint(manifest.footprint()));
		root.putInt("MarkerCount", manifest.totalMarkerCount());
		root.putLong("VolumeBits", manifest.totalVolumeBits());
		if (manifest.layout().hasMaterials()) {
			root.put("Materials", encodeMaterials(manifest.materialPalette()));
			root.putLong("MassBits", manifest.totalMassBits());
			root.put("MaterialLedgers", encodeMaterialLedgers(manifest.materialLedgers()));
		}
		if (manifest.layout().permitsOptionalChemistry()) {
			root.putBoolean("ChemistryPresent", manifest.hasChemicalState());
		}
		if (manifest.hasChemicalState()) {
			root.putString("RegistryFingerprint", manifest.registryFingerprint());
			root.putLong("AuScale", manifest.auScale());
			root.put("SpeciesPalette", encodeStrings(manifest.speciesPalette()));
			root.put("ChemicalMixtures", encodeChemicalMixtures(manifest.chemicalMixtures(), manifest.speciesPalette()));
			root.putLongArray("ChemicalSpeciesTotals", manifest.chemicalSpeciesTotals());
			root.putLong("ChemicalResidualBits", manifest.chemicalResidualLedgerBits());
		}
		ListTag sections = new ListTag();
		for (RegionManifest.SectionDescriptor descriptor : manifest.sections()) {
			CompoundTag section = new CompoundTag();
			section.putInt("X", descriptor.sectionX()); section.putInt("Y", descriptor.sectionY()); section.putInt("Z", descriptor.sectionZ());
			section.putInt("MarkerCount", descriptor.markerCount()); section.putLong("VolumeBits", descriptor.volumeBits());
			if (manifest.layout().hasMaterials()) section.putLong("MassBits", descriptor.massBits());
			section.putLong("Checksum", descriptor.checksum()); sections.add(section);
		}
		root.put("Sections", sections);
		root.put("Extensions", encodeExtensions(manifest.extensions()));
		root.putLong("Checksum", manifest.checksum());
		return root;
	}

	RegionManifest decodeManifest(CompoundTag root) throws IOException {
		Identity identity = decodeIdentity(root, "manifest");
		require(root, "CapabilityProfileFingerprint", Tag.TAG_STRING);
		require(root, "RegistryAggregateFingerprint", Tag.TAG_STRING);
		require(root, "MaterialRegistryFingerprint", Tag.TAG_STRING);
		require(root, "NetworkGeneration", Tag.TAG_LONG); require(root, "Origin", Tag.TAG_LONG);
		require(root, "SizeX", Tag.TAG_INT); require(root, "SizeY", Tag.TAG_INT); require(root, "SizeZ", Tag.TAG_INT);
		require(root, "Solver", Tag.TAG_COMPOUND); require(root, "MarkerBudget", Tag.TAG_LONG);
		require(root, "Runtime", Tag.TAG_COMPOUND); require(root, "Footprint", Tag.TAG_COMPOUND);
		require(root, "MarkerCount", Tag.TAG_INT); require(root, "VolumeBits", Tag.TAG_LONG);
		require(root, "Sections", Tag.TAG_LIST); require(root, "Extensions", Tag.TAG_LIST); require(root, "Checksum", Tag.TAG_LONG);
		ListTag encodedSections = root.getListOrEmpty("Sections");
		if (encodedSections.isEmpty() || encodedSections.size() > MAX_SECTIONS) throw new IOException("Invalid manifest section count");
		List<RegionManifest.SectionDescriptor> sections = new ArrayList<>(encodedSections.size());
		for (int i = 0; i < encodedSections.size(); i++) {
			CompoundTag section = compoundAt(encodedSections, i, "section descriptor");
			require(section, "X", Tag.TAG_INT); require(section, "Y", Tag.TAG_INT); require(section, "Z", Tag.TAG_INT);
			require(section, "MarkerCount", Tag.TAG_INT); require(section, "VolumeBits", Tag.TAG_LONG); require(section, "Checksum", Tag.TAG_LONG);
			long massBits = 0L;
			if (identity.layout().hasMaterials()) {
				require(section, "MassBits", Tag.TAG_LONG);
				massBits = section.getLongOr("MassBits", 0L);
			}
			sections.add(new RegionManifest.SectionDescriptor(section.getIntOr("X", 0), section.getIntOr("Y", 0), section.getIntOr("Z", 0),
					section.getIntOr("MarkerCount", -1), section.getLongOr("VolumeBits", 0L), massBits,
					section.getLongOr("Checksum", -1L)));
		}
		List<FluidMaterial> materials = List.of();
		long totalMassBits = 0L;
		List<RegionManifest.MaterialLedger> materialLedgers = List.of();
		if (identity.layout().hasMaterials()) {
			require(root, "Materials", Tag.TAG_LIST); require(root, "MassBits", Tag.TAG_LONG);
			require(root, "MaterialLedgers", Tag.TAG_LIST);
			materials = decodeMaterials(root.getListOrEmpty("Materials"));
			totalMassBits = root.getLongOr("MassBits", 0L);
			materialLedgers = decodeMaterialLedgers(root.getListOrEmpty("MaterialLedgers"));
		}
		String registryFingerprint = "";
		long auScale = 0L;
		List<String> speciesPalette = List.of();
		List<FlipChemicalParticleState.StableMixture> chemicalMixtures = List.of();
		long[] chemicalSpeciesTotals = new long[0];
		long chemicalResidualBits = 0L;
		boolean chemicalState = identity.layout().requiresChemistry();
		if (identity.layout().permitsOptionalChemistry()) {
			require(root, "ChemistryPresent", Tag.TAG_BYTE);
			chemicalState = root.getBooleanOr("ChemistryPresent", false);
		}
		if (chemicalState) {
			require(root, "RegistryFingerprint", Tag.TAG_STRING); require(root, "AuScale", Tag.TAG_LONG);
			require(root, "SpeciesPalette", Tag.TAG_LIST); require(root, "ChemicalMixtures", Tag.TAG_LIST);
			require(root, "ChemicalSpeciesTotals", Tag.TAG_LONG_ARRAY); require(root, "ChemicalResidualBits", Tag.TAG_LONG);
			registryFingerprint = root.getStringOr("RegistryFingerprint", "");
			auScale = root.getLongOr("AuScale", -1L);
			speciesPalette = decodeStrings(root.getListOrEmpty("SpeciesPalette"), ChemicalLimits.MAX_SPECIES, "species palette");
			chemicalMixtures = decodeChemicalMixtures(root.getListOrEmpty("ChemicalMixtures"), speciesPalette);
			chemicalSpeciesTotals = root.getLongArray("ChemicalSpeciesTotals").orElseThrow();
			chemicalResidualBits = root.getLongOr("ChemicalResidualBits", 0L);
		}
		try {
			return new RegionManifest(identity.layout(), root.getStringOr("CapabilityProfileFingerprint", ""),
					root.getStringOr("RegistryAggregateFingerprint", ""), root.getStringOr("MaterialRegistryFingerprint", ""),
					identity.id(), identity.dimension(), identity.generation(), root.getLongOr("NetworkGeneration", -1L),
					BlockPos.of(root.getLongOr("Origin", 0L)), root.getIntOr("SizeX", -1), root.getIntOr("SizeY", -1), root.getIntOr("SizeZ", -1),
					decodeConfig(root.getCompoundOrEmpty("Solver")), root.getLongOr("MarkerBudget", -1L),
					decodeRuntime(root.getCompoundOrEmpty("Runtime")), decodeFootprint(root.getCompoundOrEmpty("Footprint")),
					root.getIntOr("MarkerCount", -1), root.getLongOr("VolumeBits", 0L), materials, totalMassBits, materialLedgers, sections,
					decodeExtensions(root.getListOrEmpty("Extensions")), registryFingerprint, auScale, speciesPalette,
					chemicalMixtures, chemicalSpeciesTotals, chemicalResidualBits, root.getLongOr("Checksum", -1L));
		} catch (IllegalArgumentException exception) {
			throw new IOException("Invalid region manifest: " + exception.getMessage(), exception);
		}
	}

	CompoundTag encodeSection(RegionSectionState section) {
		CompoundTag root = identity(section.regionId(), section.dimensionId(), section.checkpointGeneration(), section.layout());
		root.putInt("SectionX", section.sectionX()); root.putInt("SectionY", section.sectionY()); root.putInt("SectionZ", section.sectionZ());
		root.putInt("StartX", section.localStartX()); root.putInt("StartY", section.localStartY()); root.putInt("StartZ", section.localStartZ());
		root.putInt("SizeX", section.sizeX()); root.putInt("SizeY", section.sizeY()); root.putInt("SizeZ", section.sizeZ());
		root.putIntArray("Ordinals", section.markerOrdinals());
		root.putIntArray("PositionVolumeBits", floatBits(section.positionVolume()));
		root.putIntArray("VelocityBits", floatBits(section.velocities()));
		if (section.layout().hasMaterials()) {
			root.putLongArray("MassBits", doubleBits(section.markerMass()));
			root.putIntArray("MaterialPaletteIndices", section.materialPaletteIndex());
		}
		if (section.layout().permitsOptionalChemistry()) {
			root.putBoolean("ChemistryPresent", section.hasChemicalState());
		}
		if (section.hasChemicalState()) {
			root.putIntArray("ChemicalMixtureIndices", section.chemicalMixtureIndex());
			root.putByteArray("Indicator", section.indicator());
			root.putLongArray("ChemicalSpeciesTotals", section.chemicalSpeciesTotals());
			root.putLong("ChemicalResidualBits", section.chemicalResidualLedgerBits());
		}
		root.putByteArray("Solids", section.solidMask());
		root.put("Extensions", encodeExtensions(section.extensions()));
		root.putLong("Checksum", section.checksum());
		return root;
	}

	RegionSectionState decodeSection(CompoundTag root) throws IOException {
		Identity identity = decodeIdentity(root, "section");
		for (String field : List.of("SectionX", "SectionY", "SectionZ", "StartX", "StartY", "StartZ", "SizeX", "SizeY", "SizeZ")) {
			require(root, field, Tag.TAG_INT);
		}
		require(root, "Ordinals", Tag.TAG_INT_ARRAY); require(root, "PositionVolumeBits", Tag.TAG_INT_ARRAY);
		require(root, "VelocityBits", Tag.TAG_INT_ARRAY); require(root, "Solids", Tag.TAG_BYTE_ARRAY);
		require(root, "Extensions", Tag.TAG_LIST); require(root, "Checksum", Tag.TAG_LONG);
		double[] markerMass = new double[0];
		int[] materialPaletteIndex = new int[0];
		if (identity.layout().hasMaterials()) {
			require(root, "MassBits", Tag.TAG_LONG_ARRAY); require(root, "MaterialPaletteIndices", Tag.TAG_INT_ARRAY);
			markerMass = doubles(root.getLongArray("MassBits").orElseThrow());
			materialPaletteIndex = root.getIntArray("MaterialPaletteIndices").orElseThrow();
		}
		int[] chemicalMixtureIndex = new int[0]; byte[] indicator = new byte[0];
		long[] chemicalSpeciesTotals = new long[0]; long chemicalResidualBits = 0L;
		boolean chemicalState = identity.layout().requiresChemistry();
		if (identity.layout().permitsOptionalChemistry()) {
			require(root, "ChemistryPresent", Tag.TAG_BYTE);
			chemicalState = root.getBooleanOr("ChemistryPresent", false);
		}
		if (chemicalState) {
			require(root, "ChemicalMixtureIndices", Tag.TAG_INT_ARRAY); require(root, "Indicator", Tag.TAG_BYTE_ARRAY);
			require(root, "ChemicalSpeciesTotals", Tag.TAG_LONG_ARRAY); require(root, "ChemicalResidualBits", Tag.TAG_LONG);
			chemicalMixtureIndex = root.getIntArray("ChemicalMixtureIndices").orElseThrow();
			indicator = root.getByteArray("Indicator").orElseThrow();
			chemicalSpeciesTotals = root.getLongArray("ChemicalSpeciesTotals").orElseThrow();
			chemicalResidualBits = root.getLongOr("ChemicalResidualBits", 0L);
		}
		try {
			return new RegionSectionState(identity.layout(), identity.id(), identity.dimension(), identity.generation(),
					root.getIntOr("SectionX", 0), root.getIntOr("SectionY", 0), root.getIntOr("SectionZ", 0),
					root.getIntOr("StartX", -1), root.getIntOr("StartY", -1), root.getIntOr("StartZ", -1),
					root.getIntOr("SizeX", -1), root.getIntOr("SizeY", -1), root.getIntOr("SizeZ", -1),
					root.getIntArray("Ordinals").orElseThrow(), floats(root.getIntArray("PositionVolumeBits").orElseThrow()),
					floats(root.getIntArray("VelocityBits").orElseThrow()), markerMass, materialPaletteIndex,
					root.getByteArray("Solids").orElseThrow(),
					decodeExtensions(root.getListOrEmpty("Extensions")), chemicalMixtureIndex, indicator,
					chemicalSpeciesTotals, chemicalResidualBits, root.getLongOr("Checksum", -1L));
		} catch (IllegalArgumentException exception) {
			throw new IOException("Invalid region section: " + exception.getMessage(), exception);
		}
	}

	private static CompoundTag identity(UUID id, String dimension, long generation, RegionStateLayout layout) {
		CompoundTag tag = new CompoundTag();
		tag.putString("Layout", layout.persistedId());
		tag.putLong("UuidMost", id.getMostSignificantBits()); tag.putLong("UuidLeast", id.getLeastSignificantBits());
		tag.putString("Dimension", dimension); tag.putLong("Generation", generation);
		return tag;
	}

	private static Identity decodeIdentity(CompoundTag tag, String context) throws IOException {
		require(tag, "Layout", Tag.TAG_STRING);
		RegionStateLayout layout = RegionStateLayout.decode(tag.getStringOr("Layout", ""));
		require(tag, "UuidMost", Tag.TAG_LONG); require(tag, "UuidLeast", Tag.TAG_LONG);
		require(tag, "Dimension", Tag.TAG_STRING); require(tag, "Generation", Tag.TAG_LONG);
		String dimension = tag.getStringOr("Dimension", "");
		if (Identifier.tryParse(dimension) == null) throw new IOException("Invalid persisted dimension " + dimension);
		long generation = tag.getLongOr("Generation", -1L);
		if (generation <= 0L) throw new IOException("Invalid checkpoint generation");
		return new Identity(layout, new UUID(tag.getLongOr("UuidMost", 0L), tag.getLongOr("UuidLeast", 0L)), dimension, generation);
	}

	private static ListTag encodeMaterials(List<FluidMaterial> materials) {
		ListTag list = new ListTag();
		for (FluidMaterial material : materials) {
			CompoundTag tag = new CompoundTag();
			tag.putString("Id", material.id()); tag.putLong("DensityBits", Double.doubleToRawLongBits(material.referenceDensity()));
			tag.putString("MiscibilityGroup", material.miscibilityGroup()); list.add(tag);
		}
		return list;
	}

	private static List<FluidMaterial> decodeMaterials(ListTag list) throws IOException {
		if (list.isEmpty() || list.size() > MAX_MATERIALS) throw new IOException("Invalid material palette size");
		List<FluidMaterial> result = new ArrayList<>(list.size());
		for (int i = 0; i < list.size(); i++) {
			CompoundTag tag = compoundAt(list, i, "material palette");
			require(tag, "Id", Tag.TAG_STRING); require(tag, "DensityBits", Tag.TAG_LONG); require(tag, "MiscibilityGroup", Tag.TAG_STRING);
			try {
				result.add(new FluidMaterial(tag.getStringOr("Id", ""), Double.longBitsToDouble(tag.getLongOr("DensityBits", 0L)),
						tag.getStringOr("MiscibilityGroup", ""), 0.0, Math.PI / 2.0));
			} catch (IllegalArgumentException exception) {
				throw new IOException("Invalid material palette entry", exception);
			}
		}
		return List.copyOf(result);
	}

	private static ListTag encodeStrings(List<String> values) {
		ListTag list = new ListTag();
		for (String value : values) list.add(StringTag.valueOf(value));
		return list;
	}

	private static List<String> decodeStrings(ListTag list, int maximum, String label) throws IOException {
		if (list.isEmpty() || list.size() > maximum) throw new IOException("Invalid " + label + " size");
		List<String> result = new ArrayList<>(list.size());
		Set<String> unique = new HashSet<>();
		for (int i = 0; i < list.size(); i++) {
			Tag value = list.get(i);
			if (!(value instanceof StringTag text)) throw new IOException("Invalid " + label + " entry");
			String decoded = text.asString().orElseThrow();
			if (!unique.add(decoded)) throw new IOException("Duplicate " + label + " entry");
			result.add(decoded);
		}
		return List.copyOf(result);
	}

	private static ListTag encodeChemicalMixtures(
			List<FlipChemicalParticleState.StableMixture> mixtures, List<String> speciesPalette
	) {
		Map<String, Integer> indices = new java.util.HashMap<>();
		for (int i = 0; i < speciesPalette.size(); i++) indices.put(speciesPalette.get(i), i);
		ListTag list = new ListTag();
		for (FlipChemicalParticleState.StableMixture mixture : mixtures) {
			CompoundTag tag = new CompoundTag();
			int[] species = new int[mixture.components().size()]; long[] amounts = new long[species.length];
			for (int entry = 0; entry < species.length; entry++) {
				Integer palette = indices.get(mixture.components().get(entry).speciesId());
				if (palette == null) throw new IllegalArgumentException("Mixture references species outside stable palette");
				species[entry] = palette; amounts[entry] = mixture.components().get(entry).amountQuanta();
			}
			tag.putIntArray("Species", species); tag.putLongArray("Amounts", amounts);
			tag.putLong("ResidualBits", mixture.massResidualBits()); list.add(tag);
		}
		return list;
	}

	private static List<FlipChemicalParticleState.StableMixture> decodeChemicalMixtures(
			ListTag list, List<String> speciesPalette
	) throws IOException {
		if (list.size() > Integer.MAX_VALUE - 1) throw new IOException("Invalid chemical mixture table size");
		List<FlipChemicalParticleState.StableMixture> result = new ArrayList<>(list.size());
		for (int i = 0; i < list.size(); i++) {
			CompoundTag tag = compoundAt(list, i, "chemical mixture");
			require(tag, "Species", Tag.TAG_INT_ARRAY); require(tag, "Amounts", Tag.TAG_LONG_ARRAY);
			require(tag, "ResidualBits", Tag.TAG_LONG);
			int[] species = tag.getIntArray("Species").orElseThrow();
			long[] amounts = tag.getLongArray("Amounts").orElseThrow();
			if (species.length == 0 || species.length != amounts.length
					|| species.length > ChemicalLimits.MAX_SPECIES_PER_PARCEL) throw new IOException("Invalid chemical mixture entries");
			List<FlipChemicalParticleState.Component> components = new ArrayList<>(species.length);
			for (int entry = 0; entry < species.length; entry++) {
				if (species[entry] < 0 || species[entry] >= speciesPalette.size() || amounts[entry] <= 0L) {
					throw new IOException("Invalid chemical mixture palette index/amount");
				}
				components.add(new FlipChemicalParticleState.Component(speciesPalette.get(species[entry]), amounts[entry]));
			}
			try { result.add(new FlipChemicalParticleState.StableMixture(components, tag.getLongOr("ResidualBits", 0L))); }
			catch (IllegalArgumentException invalid) { throw new IOException("Invalid chemical mixture", invalid); }
		}
		for (int i = 1; i < result.size(); i++) {
			// The handoff constructor performs the authoritative canonical-order check as well.
			if (result.get(i - 1).equals(result.get(i))) throw new IOException("Duplicate chemical mixture table entry");
		}
		return List.copyOf(result);
	}

	private static ListTag encodeMaterialLedgers(List<RegionManifest.MaterialLedger> ledgers) {
		ListTag list = new ListTag();
		for (RegionManifest.MaterialLedger ledger : ledgers) {
			CompoundTag tag = new CompoundTag();
			tag.putInt("MarkerCount", ledger.markerCount()); tag.putLong("VolumeBits", ledger.volumeBits());
			tag.putLong("MassBits", ledger.massBits()); list.add(tag);
		}
		return list;
	}

	private static List<RegionManifest.MaterialLedger> decodeMaterialLedgers(ListTag list) throws IOException {
		if (list.isEmpty() || list.size() > MAX_MATERIALS) throw new IOException("Invalid material ledger count");
		List<RegionManifest.MaterialLedger> result = new ArrayList<>(list.size());
		for (int i = 0; i < list.size(); i++) {
			CompoundTag tag = compoundAt(list, i, "material ledger");
			require(tag, "MarkerCount", Tag.TAG_INT); require(tag, "VolumeBits", Tag.TAG_LONG); require(tag, "MassBits", Tag.TAG_LONG);
			try {
				result.add(new RegionManifest.MaterialLedger(tag.getIntOr("MarkerCount", -1), tag.getLongOr("VolumeBits", 0L),
						tag.getLongOr("MassBits", 0L)));
			} catch (IllegalArgumentException exception) {
				throw new IOException("Invalid material ledger", exception);
			}
		}
		return List.copyOf(result);
	}

	private static CompoundTag encodeConfig(FluidSolverConfig config) {
		CompoundTag tag = new CompoundTag();
		tag.putInt("CellSize", Float.floatToRawIntBits(config.cellSize())); tag.putInt("Density", Float.floatToRawIntBits(config.density()));
		tag.putInt("Gravity", Float.floatToRawIntBits(config.gravity())); tag.putInt("TimeStep", Float.floatToRawIntBits(config.timeStep()));
		tag.putInt("PressureIterations", config.maxPressureIterations()); tag.putInt("PressureTolerance", Float.floatToRawIntBits(config.pressureTolerance()));
		tag.putInt("Courant", Float.floatToRawIntBits(config.maxCourantNumber())); tag.putInt("FluidEpsilon", Float.floatToRawIntBits(config.fluidEpsilon()));
		tag.putInt("InterfaceCompression", Float.floatToRawIntBits(config.interfaceCompression()));
		return tag;
	}

	private static FluidSolverConfig decodeConfig(CompoundTag tag) throws IOException {
		for (String field : List.of("CellSize", "Density", "Gravity", "TimeStep", "PressureIterations", "PressureTolerance", "Courant", "FluidEpsilon", "InterfaceCompression")) require(tag, field, Tag.TAG_INT);
		return new FluidSolverConfig(bits(tag, "CellSize"), bits(tag, "Density"), bits(tag, "Gravity"), bits(tag, "TimeStep"),
				tag.getIntOr("PressureIterations", -1), bits(tag, "PressureTolerance"), bits(tag, "Courant"), bits(tag, "FluidEpsilon"), bits(tag, "InterfaceCompression"));
	}

	private static CompoundTag encodeRuntime(FluidRegion.PersistedRuntime runtime) {
		CompoundTag tag = new CompoundTag();
		tag.putString("ResumeState", runtime.resumeState().name()); tag.putBoolean("OperatorPaused", runtime.operatorPaused());
		tag.putBoolean("EntityPhysics", runtime.entityPhysicsEnabled());
		tag.putBoolean("Debug", runtime.debugOverlays()); tag.putInt("StepInterval", runtime.stepIntervalTicks()); tag.putLong("Tick", runtime.tick());
		return tag;
	}

	private static FluidRegion.PersistedRuntime decodeRuntime(CompoundTag tag) throws IOException {
		require(tag, "ResumeState", Tag.TAG_STRING); require(tag, "OperatorPaused", Tag.TAG_BYTE);
		require(tag, "EntityPhysics", Tag.TAG_BYTE); require(tag, "Debug", Tag.TAG_BYTE);
		require(tag, "StepInterval", Tag.TAG_INT); require(tag, "Tick", Tag.TAG_LONG);
		try {
			return new FluidRegion.PersistedRuntime(FluidRegionLifecycle.State.valueOf(tag.getStringOr("ResumeState", "")),
					tag.getBooleanOr("OperatorPaused", true), tag.getBooleanOr("EntityPhysics", false), tag.getBooleanOr("Debug", false),
					tag.getIntOr("StepInterval", -1), tag.getLongOr("Tick", -1L));
		} catch (IllegalArgumentException exception) {
			throw new IOException("Invalid persisted runtime", exception);
		}
	}

	private static CompoundTag encodeFootprint(FluidRegionFootprint footprint) {
		CompoundTag tag = new CompoundTag();
		tag.putLong("Controller", footprint.controllerPosition().asLong()); tag.putLong("Minimum", footprint.minimumInterior().asLong());
		tag.putLong("Maximum", footprint.maximumInterior().asLong()); tag.putLongArray("Interior", longs(footprint.interiorBlocks()));
		tag.putLongArray("Boundary", longs(footprint.boundaryBlocks())); tag.putLongArray("Chunks", longs(footprint.requiredChunks()));
		return tag;
	}

	private static FluidRegionFootprint decodeFootprint(CompoundTag tag) throws IOException {
		require(tag, "Controller", Tag.TAG_LONG); require(tag, "Minimum", Tag.TAG_LONG); require(tag, "Maximum", Tag.TAG_LONG);
		require(tag, "Interior", Tag.TAG_LONG_ARRAY); require(tag, "Boundary", Tag.TAG_LONG_ARRAY); require(tag, "Chunks", Tag.TAG_LONG_ARRAY);
		return new FluidRegionFootprint(BlockPos.of(tag.getLongOr("Controller", 0L)), BlockPos.of(tag.getLongOr("Minimum", 0L)),
				BlockPos.of(tag.getLongOr("Maximum", 0L)), set(tag.getLongArray("Interior").orElseThrow()),
				set(tag.getLongArray("Boundary").orElseThrow()), set(tag.getLongArray("Chunks").orElseThrow()));
	}

	private static ListTag encodeExtensions(List<RegionExtension> extensions) {
		ListTag list = new ListTag();
		for (RegionExtension extension : extensions) {
			CompoundTag tag = new CompoundTag(); tag.putString("Type", extension.type()); tag.putByteArray("Payload", extension.payload()); list.add(tag);
		}
		return list;
	}

	private static List<RegionExtension> decodeExtensions(ListTag list) throws IOException {
		List<RegionExtension> extensions = new ArrayList<>(list.size());
		for (int i = 0; i < list.size(); i++) {
			CompoundTag tag = compoundAt(list, i, "extension"); require(tag, "Type", Tag.TAG_STRING); require(tag, "Payload", Tag.TAG_BYTE_ARRAY);
			try { extensions.add(new RegionExtension(tag.getStringOr("Type", ""), tag.getByteArray("Payload").orElseThrow())); }
			catch (IllegalArgumentException exception) { throw new IOException("Invalid extension", exception); }
		}
		return extensions;
	}

	private static CompoundTag compoundAt(ListTag list, int index, String context) throws IOException {
		Tag tag = list.get(index);
		if (tag.getId() != Tag.TAG_COMPOUND) throw new IOException("Invalid " + context + " at index " + index);
		return (CompoundTag) tag;
	}

	private static void require(CompoundTag tag, String field, byte type) throws IOException {
		Tag value = tag.get(field);
		if (value == null || value.getId() != type) throw new IOException("Missing or invalid region-state field '" + field + "'");
	}

	private static float bits(CompoundTag tag, String field) { return Float.intBitsToFloat(tag.getIntOr(field, 0)); }
	private static int[] floatBits(float[] floats) { int[] bits = new int[floats.length]; for (int i = 0; i < floats.length; i++) bits[i] = Float.floatToRawIntBits(floats[i]); return bits; }
	private static float[] floats(int[] bits) { float[] floats = new float[bits.length]; for (int i = 0; i < bits.length; i++) floats[i] = Float.intBitsToFloat(bits[i]); return floats; }
	private static long[] doubleBits(double[] doubles) { long[] bits = new long[doubles.length]; for (int i = 0; i < doubles.length; i++) bits[i] = Double.doubleToRawLongBits(doubles[i]); return bits; }
	private static double[] doubles(long[] bits) { double[] doubles = new double[bits.length]; for (int i = 0; i < bits.length; i++) doubles[i] = Double.longBitsToDouble(bits[i]); return doubles; }
	private static long[] longs(Set<Long> values) { return values.stream().mapToLong(Long::longValue).sorted().toArray(); }
	private static Set<Long> set(long[] values) { Set<Long> result = new HashSet<>(); Arrays.stream(values).forEach(result::add); return result; }

	private record Identity(RegionStateLayout layout, UUID id, String dimension, long generation) { }
}

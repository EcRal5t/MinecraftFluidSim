package com.example.simulation.persistence;

import com.example.fluid.FlipParticleState;
import com.example.fluid.FlipMaterialParticleState;
import com.example.fluid.FlipChemicalParticleState;
import com.example.fluid.FluidMaterial;
import com.example.chemistry.ChemicalRegistry;
import com.example.chemistry.ChemicalAuditLedgerCodec;
import com.example.chemistry.ChemicalTransferLedger;
import com.example.granular.GranularLimits;
import com.example.granular.GranularRegistry;
import com.example.granular.GranularStateSnapshot;
import com.example.granular.SolidVolume;
import com.example.simulation.FluidRegion;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Complete, validated checkpoint generation. */
public record RegionState(RegionManifest manifest, List<RegionSectionState> sections) {
	private static final String CHEMICAL_AUDIT_EXTENSION = "realwater:chemical_audit_v1";
	public RegionState {
		sections = List.copyOf(sections);
	}

	public static RegionState capture(FluidRegion region, long checkpointGeneration) throws IOException {
		requireCaptureable(region, checkpointGeneration);
		FlipParticleState particles = region.domain().exportParticleState();
		return capture(region, checkpointGeneration, new CapturedParticles(1, List.of(), particles.copyPositionVolume(),
				particles.copyParticleVelocities(), particles.copySolidMask(), new double[0], new int[0],
				"", List.of(), List.of(), new int[0], new byte[0], new long[0], 0L,
				ChemicalTransferLedger.EMPTY, ChemicalTransferLedger.EMPTY), null);
	}

	/** Captures an explicitly material-bound CPU domain without routing it through the pure-water handoff. */
	public static RegionState captureMaterial(FluidRegion region, long checkpointGeneration) throws IOException {
		requireCaptureable(region, checkpointGeneration);
		FlipMaterialParticleState particles = region.domain().exportMaterialParticleState();
		return capture(region, checkpointGeneration, new CapturedParticles(2, particles.materials(), particles.copyPositionVolume(),
				particles.copyParticleVelocities(), particles.copySolidMask(), particles.copyParticleMass(),
				particles.copyMaterialPaletteIndex(), "", List.of(), List.of(), new int[0], new byte[0], new long[0], 0L,
				ChemicalTransferLedger.EMPTY, ChemicalTransferLedger.EMPTY), null);
	}

	public static RegionState captureChemical(FluidRegion region, long checkpointGeneration) throws IOException {
		requireCaptureable(region, checkpointGeneration);
		FlipChemicalParticleState particles = region.domain().exportChemicalParticleState();
		FlipMaterialParticleState materials = particles.materials();
		return capture(region, checkpointGeneration, new CapturedParticles(3, materials.materials(),
				materials.copyPositionVolume(), materials.copyParticleVelocities(), materials.copySolidMask(),
				materials.copyParticleMass(), materials.copyMaterialPaletteIndex(), particles.registryFingerprint(),
				particles.speciesPalette(), particles.mixtures(), particles.copyMarkerMixtureIndex(),
				particles.copyIndicator(), particles.copySpeciesTotals(), particles.residualLedgerBits(),
				particles.externalSource(), particles.escapedSink()), null);
	}

	public static RegionState captureGranular(FluidRegion region, long checkpointGeneration) throws IOException {
		requireCaptureable(region, checkpointGeneration);
		if (!region.domain().hasGranularState() || !region.domain().hasMaterialState()) {
			throw new IOException("region-state v5 capture requires material and granular state");
		}
		GranularStateSnapshot granular = region.domain().exportGranularState();
		CapturedParticles particles;
		if (region.domain().hasChemistryState()) {
			FlipChemicalParticleState chemical = region.domain().exportChemicalParticleState();
			FlipMaterialParticleState materials = chemical.materials();
			particles = new CapturedParticles(5, materials.materials(), materials.copyPositionVolume(),
					materials.copyParticleVelocities(), materials.copySolidMask(), materials.copyParticleMass(),
					materials.copyMaterialPaletteIndex(), chemical.registryFingerprint(), chemical.speciesPalette(),
					chemical.mixtures(), chemical.copyMarkerMixtureIndex(), chemical.copyIndicator(),
					chemical.copySpeciesTotals(), chemical.residualLedgerBits(), chemical.externalSource(), chemical.escapedSink());
		} else {
			FlipMaterialParticleState materials = region.domain().exportMaterialParticleState();
			particles = new CapturedParticles(5, materials.materials(), materials.copyPositionVolume(),
					materials.copyParticleVelocities(), materials.copySolidMask(), materials.copyParticleMass(),
					materials.copyMaterialPaletteIndex(), "", List.of(), List.of(), new int[0], new byte[0],
					new long[0], 0L, ChemicalTransferLedger.EMPTY, ChemicalTransferLedger.EMPTY);
		}
		return capture(region, checkpointGeneration, particles, granular);
	}

	private static RegionState capture(
			FluidRegion region, long checkpointGeneration, CapturedParticles particles,
			GranularStateSnapshot granular
	) throws IOException {
		requireCaptureable(region, checkpointGeneration);
		int cellsPerBlock = Math.round(1.0F / region.solverConfig().cellSize());
		if (cellsPerBlock <= 0 || Math.abs(cellsPerBlock * region.solverConfig().cellSize() - 1.0F) > 1.0E-6F
				|| region.sizeX() % cellsPerBlock != 0 || region.sizeY() % cellsPerBlock != 0 || region.sizeZ() % cellsPerBlock != 0) {
			throw new IOException("region-state v1 requires an integer number of solver cells per Minecraft block");
		}

		float[] positionVolume = particles.positionVolume();
		float[] velocities = particles.velocities();
		byte[] solids = particles.solidMask();
		Map<RegionSectionState.SectionKey, List<Integer>> markersBySection = new HashMap<>();
		for (int ordinal = 0; ordinal < particles.particleCount(); ordinal++) {
			int base = ordinal * 4;
			RegionSectionState.SectionKey key = new RegionSectionState.SectionKey(
					Math.floorDiv((int) Math.floor(region.origin().getX() + positionVolume[base]), 16),
					Math.floorDiv((int) Math.floor(region.origin().getY() + positionVolume[base + 1]), 16),
					Math.floorDiv((int) Math.floor(region.origin().getZ() + positionVolume[base + 2]), 16));
			markersBySection.computeIfAbsent(key, ignored -> new ArrayList<>()).add(ordinal);
		}
		Map<RegionSectionState.SectionKey, List<GranularRegionStateCodec.ActiveEntry>> granularActiveBySection = new HashMap<>();
		Map<RegionSectionState.SectionKey, List<GranularStateSnapshot.DepositGroup>> granularDepositsBySection = new HashMap<>();
		if (granular != null) {
			for (int ordinal = 0; ordinal < granular.activeParcels().size(); ordinal++) {
				GranularStateSnapshot.ActiveParcel parcel = granular.activeParcels().get(ordinal);
				RegionSectionState.SectionKey key = sectionKey(region, parcel.x(), parcel.y(), parcel.z());
				granularActiveBySection.computeIfAbsent(key, ignored -> new ArrayList<>())
						.add(new GranularRegionStateCodec.ActiveEntry(ordinal, parcel));
			}
			for (GranularStateSnapshot.DepositGroup group : granular.depositGroups()) {
				int cellX = group.cell() % region.sizeX(), yz = group.cell() / region.sizeX();
				int cellY = yz % region.sizeY(), cellZ = yz / region.sizeY();
				RegionSectionState.SectionKey key = sectionKey(region,
						(cellX + 0.5F) * region.solverConfig().cellSize(),
						(cellY + 0.5F) * region.solverConfig().cellSize(),
						(cellZ + 0.5F) * region.solverConfig().cellSize());
				granularDepositsBySection.computeIfAbsent(key, ignored -> new ArrayList<>()).add(group);
			}
		}

		List<RegionSectionState> sectionStates = new ArrayList<>();
		int minimumSectionX = Math.floorDiv(region.origin().getX(), 16);
		int minimumSectionY = Math.floorDiv(region.origin().getY(), 16);
		int minimumSectionZ = Math.floorDiv(region.origin().getZ(), 16);
		int blockSizeX = region.sizeX() / cellsPerBlock, blockSizeY = region.sizeY() / cellsPerBlock;
		int blockSizeZ = region.sizeZ() / cellsPerBlock;
		int maximumSectionX = Math.floorDiv(region.origin().getX() + blockSizeX - 1, 16);
		int maximumSectionY = Math.floorDiv(region.origin().getY() + blockSizeY - 1, 16);
		int maximumSectionZ = Math.floorDiv(region.origin().getZ() + blockSizeZ - 1, 16);
		for (int sectionX = minimumSectionX; sectionX <= maximumSectionX; sectionX++) {
			for (int sectionY = minimumSectionY; sectionY <= maximumSectionY; sectionY++) {
				for (int sectionZ = minimumSectionZ; sectionZ <= maximumSectionZ; sectionZ++) {
					RegionSectionState.SectionKey key = new RegionSectionState.SectionKey(sectionX, sectionY, sectionZ);
					int startX = Math.max(0, (sectionX * 16 - region.origin().getX()) * cellsPerBlock);
					int startY = Math.max(0, (sectionY * 16 - region.origin().getY()) * cellsPerBlock);
					int startZ = Math.max(0, (sectionZ * 16 - region.origin().getZ()) * cellsPerBlock);
					int endX = Math.min(region.sizeX(), ((sectionX + 1) * 16 - region.origin().getX()) * cellsPerBlock);
					int endY = Math.min(region.sizeY(), ((sectionY + 1) * 16 - region.origin().getY()) * cellsPerBlock);
					int endZ = Math.min(region.sizeZ(), ((sectionZ + 1) * 16 - region.origin().getZ()) * cellsPerBlock);
					int sliceX = endX - startX, sliceY = endY - startY, sliceZ = endZ - startZ;
					byte[] solidSlice = new byte[Math.multiplyExact(Math.multiplyExact(sliceX, sliceY), sliceZ)];
					int target = 0;
					for (int z = startZ; z < endZ; z++) for (int y = startY; y < endY; y++) for (int x = startX; x < endX; x++) {
						solidSlice[target++] = solids[x + region.sizeX() * (y + region.sizeY() * z)];
					}
					List<Integer> ordinals = markersBySection.getOrDefault(key, List.of());
					int[] savedOrdinals = new int[ordinals.size()];
					float[] savedPositions = new float[ordinals.size() * 4];
					float[] savedVelocities = new float[ordinals.size() * 3];
					double[] savedMass = particles.formatVersion() >= 2 ? new double[ordinals.size()] : new double[0];
					int[] savedMaterials = particles.formatVersion() >= 2 ? new int[ordinals.size()] : new int[0];
					int[] savedMixtures = particles.hasChemicalState() ? new int[ordinals.size()] : new int[0];
					byte[] savedIndicator = particles.hasChemicalState() ? new byte[ordinals.size()] : new byte[0];
					for (int i = 0; i < ordinals.size(); i++) {
						int ordinal = ordinals.get(i);
						savedOrdinals[i] = ordinal;
						System.arraycopy(positionVolume, ordinal * 4, savedPositions, i * 4, 4);
						System.arraycopy(velocities, ordinal * 3, savedVelocities, i * 3, 3);
						if (particles.formatVersion() >= 2) {
							savedMass[i] = particles.markerMass()[ordinal];
							savedMaterials[i] = particles.materialPaletteIndex()[ordinal];
						}
						if (particles.hasChemicalState()) {
							savedMixtures[i] = particles.chemicalMixtureIndex()[ordinal];
							savedIndicator[i] = particles.indicator()[ordinal];
						}
					}
					long[] sectionSpecies = particles.hasChemicalState()
							? chemicalTotals(particles, savedMixtures) : new long[0];
					double sectionResidual = 0.0;
					if (particles.hasChemicalState()) for (int mixture : savedMixtures) {
						sectionResidual += Double.longBitsToDouble(particles.chemicalMixtures().get(mixture).massResidualBits());
					}
					List<RegionExtension> sectionExtensions = List.of();
					if (granular != null) {
						GranularRegionStateCodec.SectionData data = new GranularRegionStateCodec.SectionData(
								List.copyOf(granularActiveBySection.getOrDefault(key, List.of())),
								List.copyOf(granularDepositsBySection.getOrDefault(key, List.of())));
						sectionExtensions = List.of(new RegionExtension(GranularRegionStateCodec.SECTION_EXTENSION,
								new GranularRegionStateCodec().encodeSection(data)));
					}
					RegionSectionState unsigned = new RegionSectionState(particles.formatVersion(), region.id(), region.dimension().identifier().toString(),
							checkpointGeneration, sectionX, sectionY, sectionZ, startX, startY, startZ,
							sliceX, sliceY, sliceZ, savedOrdinals, savedPositions, savedVelocities, savedMass, savedMaterials,
							solidSlice, sectionExtensions, savedMixtures, savedIndicator, sectionSpecies,
							particles.hasChemicalState() ? Double.doubleToRawLongBits(sectionResidual) : 0L, 0L);
					sectionStates.add(withChecksum(unsigned));
				}
			}
		}
		sectionStates.sort(Comparator.comparing(RegionSectionState::key));

		List<RegionManifest.SectionDescriptor> descriptors = new ArrayList<>(sectionStates.size());
		for (RegionSectionState section : sectionStates) {
			descriptors.add(new RegionManifest.SectionDescriptor(section.sectionX(), section.sectionY(), section.sectionZ(),
					section.markerCount(), Double.doubleToRawLongBits(sumVolumes(section.positionVolume())),
					section.formatVersion() >= 2 ? Double.doubleToRawLongBits(sum(section.markerMass())) : 0L, section.checksum()));
		}
		List<RegionManifest.MaterialLedger> materialLedgers = materialLedgers(particles);
		List<RegionExtension> manifestExtensions = new ArrayList<>();
		if (particles.hasChemicalState()) manifestExtensions.add(new RegionExtension(CHEMICAL_AUDIT_EXTENSION,
				ChemicalAuditLedgerCodec.encode(particles.externalSource(), particles.escapedSink())));
		if (granular != null) manifestExtensions.add(new RegionExtension(GranularRegionStateCodec.MANIFEST_EXTENSION,
				new GranularRegionStateCodec().encodeManifest(granularManifest(granular, sectionStates,
						particles.hasChemicalState()))));
		RegionManifest unsigned = new RegionManifest(particles.formatVersion(), region.id(), region.dimension().identifier().toString(), checkpointGeneration,
				region.generation(), region.origin(), region.sizeX(), region.sizeY(), region.sizeZ(), region.solverConfig(),
				region.reservedMarkerCapacity(), region.persistedRuntime(), region.footprint(), particles.particleCount(),
				Double.doubleToRawLongBits(sumVolumes(positionVolume)), particles.materialPalette(),
				particles.formatVersion() >= 2 ? Double.doubleToRawLongBits(sum(particles.markerMass())) : 0L,
				materialLedgers, descriptors, List.copyOf(manifestExtensions), particles.registryFingerprint(),
				particles.hasChemicalState() ? com.example.chemistry.ChemicalLimits.QUANTA_PER_AU : 0L,
				particles.speciesPalette(), particles.chemicalMixtures(), particles.chemicalSpeciesTotals(),
				particles.chemicalResidualLedgerBits(), 0L);
		return new RegionState(withChecksum(unsigned), sectionStates);
	}

	/** Explicit pure-water v1 to one-material v2 conversion; publication remains a separate atomic operation. */
	public static RegionState migrateV1(
			RegionState source, long checkpointGeneration, FluidMaterial defaultMaterial
	) throws IOException {
		return migrateV1(source, checkpointGeneration, List.of(defaultMaterial));
	}

	public static RegionState migrateV1(
			RegionState source, long checkpointGeneration, List<FluidMaterial> materialPalette
	) throws IOException {
		if (source.manifest().formatVersion() != 1) throw new IOException("Only region-state v1 can be migrated to v2");
		if (checkpointGeneration <= source.manifest().checkpointGeneration()) throw new IOException("Migration generation must advance");
		if (materialPalette == null || materialPalette.isEmpty()) throw new IOException("Migration requires a material palette");
		FluidMaterial defaultMaterial = materialPalette.getFirst();
		FlipParticleState particles = source.reconstruct();
		float[] allPositions = particles.copyPositionVolume();
		double[] allMass = new double[particles.particleCount()];
		double totalMass = 0.0;
		for (int ordinal = 0; ordinal < allMass.length; ordinal++) {
			allMass[ordinal] = allPositions[ordinal * 4 + 3] * defaultMaterial.referenceDensity();
			totalMass += allMass[ordinal];
		}
		List<RegionSectionState> migratedSections = new ArrayList<>(source.sections().size());
		List<RegionManifest.SectionDescriptor> descriptors = new ArrayList<>(source.sections().size());
		for (RegionSectionState section : source.sections()) {
			int[] ordinals = section.markerOrdinals();
			double[] markerMass = new double[ordinals.length];
			for (int marker = 0; marker < ordinals.length; marker++) markerMass[marker] = allMass[ordinals[marker]];
			RegionSectionState unsigned = new RegionSectionState(2, section.regionId(), section.dimensionId(), checkpointGeneration,
					section.sectionX(), section.sectionY(), section.sectionZ(), section.localStartX(), section.localStartY(), section.localStartZ(),
					section.sizeX(), section.sizeY(), section.sizeZ(), ordinals, section.positionVolume(), section.velocities(), markerMass,
					new int[ordinals.length], section.solidMask(), section.extensions(), 0L);
			RegionSectionState migrated = withChecksum(unsigned);
			migratedSections.add(migrated);
			descriptors.add(new RegionManifest.SectionDescriptor(migrated.sectionX(), migrated.sectionY(), migrated.sectionZ(),
					migrated.markerCount(), Double.doubleToRawLongBits(sumVolumes(migrated.positionVolume())),
					Double.doubleToRawLongBits(sum(migrated.markerMass())), migrated.checksum()));
		}
		migratedSections.sort(Comparator.comparing(RegionSectionState::key));
		descriptors.sort(Comparator.naturalOrder());
		RegionManifest base = source.manifest();
		List<RegionManifest.MaterialLedger> ledgers = new ArrayList<>(materialPalette.size());
		ledgers.add(new RegionManifest.MaterialLedger(particles.particleCount(), base.totalVolumeBits(), Double.doubleToRawLongBits(totalMass)));
		for (int material = 1; material < materialPalette.size(); material++) {
			ledgers.add(new RegionManifest.MaterialLedger(0, Double.doubleToRawLongBits(0.0), Double.doubleToRawLongBits(0.0)));
		}
		RegionManifest unsigned = new RegionManifest(2, base.regionId(), base.dimensionId(), checkpointGeneration,
				base.networkGeneration(), base.origin(), base.sizeX(), base.sizeY(), base.sizeZ(), base.solverConfig(),
				base.reservedMarkerCapacity(), base.runtime(), base.footprint(), base.totalMarkerCount(), base.totalVolumeBits(),
				materialPalette, Double.doubleToRawLongBits(totalMass), ledgers, descriptors, base.extensions(), 0L);
		return new RegionState(withChecksum(unsigned), migratedSections);
	}

	private static void requireCaptureable(FluidRegion region, long checkpointGeneration) throws IOException {
		if (!region.controllerManaged() || !region.solverLoaded()) {
			throw new IOException("Only a loaded controller region can be checkpointed");
		}
		if (checkpointGeneration <= region.checkpointGeneration()) {
			throw new IOException("Checkpoint generation must advance");
		}
	}

	public FlipParticleState reconstruct() throws IOException {
		if (manifest.formatVersion() != 1) {
			throw new IOException("Material region-state v2 cannot use the pure-water reconstruction path");
		}
		ReconstructedParticles particles = reconstructParticles();
		double volume = sumVolumes(particles.positionVolume());
		return new FlipParticleState(manifest.sizeX(), manifest.sizeY(), manifest.sizeZ(), manifest.solverConfig().cellSize(),
				manifest.totalMarkerCount(), (float) volume, particles.positionVolume(), particles.velocities(), particles.solidMask());
	}

	public FlipMaterialParticleState reconstructMaterial() throws IOException {
		if (manifest.formatVersion() != 2 && !(manifest.formatVersion() == 5 && !manifest.hasChemicalState())) {
			throw new IOException("This region-state cannot use the material reconstruction path");
		}
		ReconstructedParticles particles = reconstructParticles();
		return new FlipMaterialParticleState(manifest.sizeX(), manifest.sizeY(), manifest.sizeZ(), manifest.solverConfig().cellSize(),
				manifest.totalMarkerCount(), manifest.totalVolume(), manifest.totalMass(), manifest.materialPalette(),
				particles.positionVolume(), particles.velocities(), particles.solidMask(), particles.markerMass(),
				particles.materialPaletteIndex());
	}

	public FlipChemicalParticleState reconstructChemical(ChemicalRegistry registry) throws IOException {
		if (!manifest.hasChemicalState()) throw new IOException("This region-state has no chemical state");
		if (registry == null || !manifest.registryFingerprint().equals(registry.fingerprint())
				|| !manifest.speciesPalette().equals(registry.species().stream()
				.map(com.example.chemistry.ChemicalSpecies::id).toList())) {
			throw new IOException("Region-state v3 registry fingerprint/palette mismatch");
		}
		ReconstructedParticles particles = reconstructParticles();
		FlipMaterialParticleState materials = new FlipMaterialParticleState(
				manifest.sizeX(), manifest.sizeY(), manifest.sizeZ(), manifest.solverConfig().cellSize(),
				manifest.totalMarkerCount(), manifest.totalVolume(), manifest.totalMass(), manifest.materialPalette(),
				particles.positionVolume(), particles.velocities(), particles.solidMask(), particles.markerMass(),
				particles.materialPaletteIndex());
		ChemicalAuditLedgerCodec.AuditLedgers audit = decodeChemicalAuditExtension();
		return new FlipChemicalParticleState(materials, registry, manifest.chemicalMixtures(),
				particles.chemicalMixtureIndex(), particles.indicator(), manifest.chemicalSpeciesTotals(),
				manifest.chemicalResidualLedgerBits(), audit.externalSource(), audit.escapedSink());
	}

	public GranularStateSnapshot reconstructGranular(GranularRegistry registry) throws IOException {
		if (manifest.formatVersion() != 5) throw new IOException("Only region-state v5 has granular state");
		if (registry == null) throw new IOException("Authoritative granular registry is unavailable");
		validate();
		GranularRegionStateCodec codec = new GranularRegionStateCodec();
		GranularRegionStateCodec.ManifestData granular = codec.decodeManifest(
				requiredExtension(manifest.extensions(), GranularRegionStateCodec.MANIFEST_EXTENSION).payload());
		if (!granular.registryFingerprint().equals(registry.fingerprint())
				|| !granular.materialPalette().equals(registry.materials().stream()
				.map(com.example.granular.ParticulateMaterial::id).toList())) {
			throw new IOException("Region-state v5 granular registry fingerprint/palette mismatch");
		}
		boolean reactivePayload = !granular.solidCompositions().isEmpty()
				|| granular.externalSource().requiresChemistry() || granular.escapedSink().requiresChemistry();
		if (reactivePayload
				&& !granular.chemicalRegistryFingerprint().equals(registry.chemicalRegistryFingerprint())) {
			throw new IOException("Region-state v5 reactive granular registry dependency mismatch");
		}
		GranularStateSnapshot.ActiveParcel[] active = new GranularStateSnapshot.ActiveParcel[granular.activeCount()];
		boolean[] seenActive = new boolean[active.length];
		List<GranularStateSnapshot.DepositGroup> deposits = new ArrayList<>(granular.depositGroupCount());
		for (RegionSectionState section : sections) {
			GranularRegionStateCodec.SectionData data = codec.decodeSection(
					requiredExtension(section.extensions(), GranularRegionStateCodec.SECTION_EXTENSION).payload(),
					granular.activeBudget(), granular.depositGroupBudget());
			int previousActiveOrdinal = -1;
			for (GranularRegionStateCodec.ActiveEntry entry : data.active()) {
				if (entry.ordinal() < 0 || entry.ordinal() >= active.length || seenActive[entry.ordinal()]) {
					throw new IOException("Duplicate or invalid granular active ordinal");
				}
				if (entry.ordinal() <= previousActiveOrdinal) throw new IOException("Granular section active ordinals are not sorted");
				previousActiveOrdinal = entry.ordinal();
				validateGranularSectionPosition(section, entry.parcel().x(), entry.parcel().y(), entry.parcel().z());
				seenActive[entry.ordinal()] = true; active[entry.ordinal()] = entry.parcel();
			}
			for (GranularStateSnapshot.DepositGroup group : data.deposits()) {
				validateGranularDepositSection(section, group.cell());
				deposits.add(group);
			}
		}
		for (boolean seen : seenActive) if (!seen) throw new IOException("Missing granular active ordinal");
		deposits.sort(Comparator.comparingInt(GranularStateSnapshot.DepositGroup::cell)
				.thenComparingInt(GranularStateSnapshot.DepositGroup::materialPaletteIndex)
				.thenComparingInt(GranularStateSnapshot.DepositGroup::solidCompositionIndex));
		long totalSvq = Math.addExact(granular.activeSvq(), granular.depositSvq());
		double totalMass = Double.longBitsToDouble(granular.activeMassBits())
				+ Double.longBitsToDouble(granular.depositMassBits());
		try {
			return new GranularStateSnapshot(manifest.sizeX(), manifest.sizeY(), manifest.sizeZ(),
					granular.registryFingerprint(), granular.chemicalRegistryFingerprint(), granular.materialPalette(),
					granular.solidCompositions(), granular.solidSpeciesTotals(), Arrays.asList(active), deposits,
					totalSvq, Double.doubleToRawLongBits(totalMass), granular.externalSource(), granular.escapedSink());
		} catch (IllegalArgumentException invalid) {
			throw new IOException("Invalid region-state v5 granular inventory: " + invalid.getMessage(), invalid);
		}
	}

	private ChemicalAuditLedgerCodec.AuditLedgers decodeChemicalAuditExtension() throws IOException {
		RegionExtension found = null;
		for (RegionExtension extension : manifest.extensions()) {
			if (!CHEMICAL_AUDIT_EXTENSION.equals(extension.type())) continue;
			if (found != null) throw new IOException("Duplicate region-state v3 chemical audit ledger");
			found = extension;
		}
		if (found == null) throw new IOException("Region-state v3 chemical audit ledger is missing");
		return ChemicalAuditLedgerCodec.decode(found.payload());
	}

	private ReconstructedParticles reconstructParticles() throws IOException {
		validate();
		int count = manifest.totalMarkerCount();
		float[] positions = new float[Math.multiplyExact(count, 4)];
		float[] velocities = new float[Math.multiplyExact(count, 3)];
		double[] markerMass = manifest.formatVersion() >= 2 ? new double[count] : new double[0];
		int[] materialPaletteIndex = manifest.formatVersion() >= 2 ? new int[count] : new int[0];
		int[] chemicalMixtureIndex = manifest.hasChemicalState() ? new int[count] : new int[0];
		byte[] indicator = manifest.hasChemicalState() ? new byte[count] : new byte[0];
		byte[] solids = new byte[Math.multiplyExact(Math.multiplyExact(manifest.sizeX(), manifest.sizeY()), manifest.sizeZ())];
		boolean[] seenMarkers = new boolean[count];
		boolean[] seenSolids = new boolean[solids.length];
		for (RegionSectionState section : sections) {
			int[] ordinals = section.markerOrdinals();
			float[] sectionPositions = section.positionVolume();
			float[] sectionVelocities = section.velocities();
			double[] sectionMass = section.markerMass();
			int[] sectionMaterials = section.materialPaletteIndex();
			int[] sectionMixtures = section.chemicalMixtureIndex();
			byte[] sectionIndicator = section.indicator();
			for (int i = 0; i < ordinals.length; i++) {
				int ordinal = ordinals[i];
				if (ordinal < 0 || ordinal >= count || seenMarkers[ordinal]) throw new IOException("Duplicate or invalid marker ordinal");
				seenMarkers[ordinal] = true;
				System.arraycopy(sectionPositions, i * 4, positions, ordinal * 4, 4);
				System.arraycopy(sectionVelocities, i * 3, velocities, ordinal * 3, 3);
				if (manifest.formatVersion() >= 2) {
					markerMass[ordinal] = sectionMass[i];
					materialPaletteIndex[ordinal] = sectionMaterials[i];
				}
				if (manifest.hasChemicalState()) {
					chemicalMixtureIndex[ordinal] = sectionMixtures[i];
					indicator[ordinal] = sectionIndicator[i];
				}
			}
			byte[] slice = section.solidMask();
			int source = 0;
			for (int z = 0; z < section.sizeZ(); z++) for (int y = 0; y < section.sizeY(); y++) for (int x = 0; x < section.sizeX(); x++) {
				int localX = section.localStartX() + x, localY = section.localStartY() + y, localZ = section.localStartZ() + z;
				if (localX < 0 || localX >= manifest.sizeX() || localY < 0 || localY >= manifest.sizeY()
						|| localZ < 0 || localZ >= manifest.sizeZ()) throw new IOException("Solid slice exceeds region bounds");
				int index = localX + manifest.sizeX() * (localY + manifest.sizeY() * localZ);
				if (seenSolids[index]) throw new IOException("Overlapping solid slices");
				seenSolids[index] = true;
				solids[index] = slice[source++];
			}
		}
		for (boolean seen : seenMarkers) if (!seen) throw new IOException("Missing marker ordinal");
		for (boolean seen : seenSolids) if (!seen) throw new IOException("Incomplete solid-mask coverage");
		double volume = sumVolumes(positions);
		if (Double.doubleToRawLongBits(volume) != manifest.totalVolumeBits()) throw new IOException("Marker volume ledger mismatch");
		return new ReconstructedParticles(positions, velocities, solids, markerMass, materialPaletteIndex,
				chemicalMixtureIndex, indicator);
	}

	public void validate() throws IOException {
		if (RegionStateIntegrity.manifestChecksum(manifest) != manifest.checksum()) throw new IOException("Manifest checksum mismatch");
		if (sections.size() != manifest.sections().size()) throw new IOException("Section count mismatch");
		Map<RegionSectionState.SectionKey, RegionManifest.SectionDescriptor> expected = new HashMap<>();
		RegionManifest.SectionDescriptor previous = null;
		for (RegionManifest.SectionDescriptor descriptor : manifest.sections()) {
			if (previous != null && previous.compareTo(descriptor) >= 0) throw new IOException("Section descriptors are not strictly sorted");
			if (expected.put(descriptor.key(), descriptor) != null) throw new IOException("Duplicate section descriptor");
			previous = descriptor;
		}
		Set<RegionSectionState.SectionKey> actual = new HashSet<>();
		int markerCount = 0;
		for (RegionSectionState section : sections) {
			if (!actual.add(section.key())) throw new IOException("Duplicate section payload");
			if (!section.regionId().equals(manifest.regionId()) || !section.dimensionId().equals(manifest.dimensionId())
					|| section.checkpointGeneration() != manifest.checkpointGeneration()
					|| section.formatVersion() != manifest.formatVersion()) throw new IOException("Mixed section ownership or format");
			if (RegionStateIntegrity.sectionChecksum(section) != section.checksum()) throw new IOException("Section checksum mismatch");
			validateSectionOwnership(section);
			RegionManifest.SectionDescriptor descriptor = expected.get(section.key());
			long volumeBits = Double.doubleToRawLongBits(sumVolumes(section.positionVolume()));
			long massBits = manifest.formatVersion() >= 2 ? Double.doubleToRawLongBits(sum(section.markerMass())) : 0L;
			if (descriptor == null || descriptor.markerCount() != section.markerCount()
					|| descriptor.volumeBits() != volumeBits || descriptor.massBits() != massBits
					|| descriptor.checksum() != section.checksum()) {
				throw new IOException("Section does not match its manifest descriptor");
			}
			markerCount = Math.addExact(markerCount, section.markerCount());
		}
		if (markerCount != manifest.totalMarkerCount() || markerCount > manifest.reservedMarkerCapacity()) {
			throw new IOException("Manifest marker count/budget mismatch");
		}
		if (manifest.formatVersion() >= 2) validateMaterialLedgers();
		if (manifest.hasChemicalState()) validateChemicalLedgers();
		if (manifest.formatVersion() == 5) validateGranularLedgers();
	}

	private void validateChemicalLedgers() throws IOException {
		decodeChemicalAuditExtension();
		int count = manifest.totalMarkerCount();
		int[] mixtureByOrdinal = new int[count];
		boolean[] seen = new boolean[count];
		Map<String, Integer> speciesHandles = new HashMap<>();
		for (int species = 0; species < manifest.speciesPalette().size(); species++) {
			if (speciesHandles.put(manifest.speciesPalette().get(species), species) != null) {
				throw new IOException("Duplicate stable species palette entry");
			}
		}
		for (RegionSectionState section : sections) {
			long[] sectionTotals = new long[manifest.speciesPalette().size()];
			double sectionResidual = 0.0;
			int[] ordinals = section.markerOrdinals(), mixtures = section.chemicalMixtureIndex();
			for (int marker = 0; marker < ordinals.length; marker++) {
				int ordinal = ordinals[marker], mixtureIndex = mixtures[marker];
				if (ordinal < 0 || ordinal >= count || seen[ordinal]
						|| mixtureIndex < 0 || mixtureIndex >= manifest.chemicalMixtures().size()) {
					throw new IOException("Invalid v3 marker ordinal/mixture index");
				}
				seen[ordinal] = true; mixtureByOrdinal[ordinal] = mixtureIndex;
				FlipChemicalParticleState.StableMixture mixture = manifest.chemicalMixtures().get(mixtureIndex);
				sectionResidual += Double.longBitsToDouble(mixture.massResidualBits());
				for (FlipChemicalParticleState.Component component : mixture.components()) {
					Integer species = speciesHandles.get(component.speciesId());
					if (species == null) throw new IOException("Mixture references species outside stable palette");
					sectionTotals[species] = Math.addExact(sectionTotals[species], component.amountQuanta());
				}
			}
			if (!Arrays.equals(sectionTotals, section.chemicalSpeciesTotals())
					|| Double.doubleToRawLongBits(sectionResidual) != section.chemicalResidualLedgerBits()) {
				throw new IOException("Section chemical ledger mismatch");
			}
		}
		long[] totals = new long[manifest.speciesPalette().size()];
		double residual = 0.0;
		for (int ordinal = 0; ordinal < count; ordinal++) {
			if (!seen[ordinal]) throw new IOException("Missing v3 marker ordinal");
			FlipChemicalParticleState.StableMixture mixture = manifest.chemicalMixtures().get(mixtureByOrdinal[ordinal]);
			residual += Double.longBitsToDouble(mixture.massResidualBits());
			for (FlipChemicalParticleState.Component component : mixture.components()) {
				int species = speciesHandles.get(component.speciesId());
				totals[species] = Math.addExact(totals[species], component.amountQuanta());
			}
		}
		if (!Arrays.equals(totals, manifest.chemicalSpeciesTotals())
				|| Double.doubleToRawLongBits(residual) != manifest.chemicalResidualLedgerBits()) {
			throw new IOException("Manifest chemical ledger mismatch");
		}
	}

	private void validateGranularLedgers() throws IOException {
		GranularRegionStateCodec codec = new GranularRegionStateCodec();
		GranularRegionStateCodec.ManifestData expected = codec.decodeManifest(
				requiredExtension(manifest.extensions(), GranularRegionStateCodec.MANIFEST_EXTENSION).payload());
		boolean capabilityChemistry = (expected.capabilities() & GranularRegionStateCodec.CAP_CHEMISTRY) != 0;
		boolean capabilityPrecipitation = (expected.capabilities() & GranularRegionStateCodec.CAP_PRECIPITATION) != 0;
		boolean reactivePayload = !expected.solidCompositions().isEmpty()
				|| expected.externalSource().requiresChemistry() || expected.escapedSink().requiresChemistry();
		if (capabilityChemistry != manifest.hasChemicalState()
				|| capabilityPrecipitation != reactivePayload
				|| reactivePayload && (!capabilityChemistry
				|| !expected.chemicalRegistryFingerprint().equals(manifest.registryFingerprint()))) {
			throw new IOException("Region-state v5 capability set does not match its complete payload");
		}
		if (expected.materialLedgers().size() != expected.materialPalette().size()
				|| expected.sectionLedgers().size() != sections.size()) {
			throw new IOException("Region-state v5 granular ledger cardinality mismatch");
		}
		Map<RegionSectionState.SectionKey, GranularRegionStateCodec.SectionLedger> sectionLedgers = new HashMap<>();
		for (GranularRegionStateCodec.SectionLedger ledger : expected.sectionLedgers()) {
			if (sectionLedgers.put(ledger.key(), ledger) != null) throw new IOException("Duplicate granular section ledger");
		}
		GranularStateSnapshot.ActiveParcel[] active = new GranularStateSnapshot.ActiveParcel[expected.activeCount()];
		boolean[] seenActive = new boolean[active.length];
		List<GranularStateSnapshot.DepositGroup> deposits = new ArrayList<>(expected.depositGroupCount());
		int materialCount = expected.materialPalette().size();
		int[] activeCounts = new int[materialCount], depositCounts = new int[materialCount];
		long[] activeSvq = new long[materialCount], depositSvq = new long[materialCount];
		double[] activeMass = new double[materialCount], depositMass = new double[materialCount];
		for (RegionSectionState section : sections) {
			GranularRegionStateCodec.SectionData data = codec.decodeSection(
					requiredExtension(section.extensions(), GranularRegionStateCodec.SECTION_EXTENSION).payload(),
					expected.activeBudget(), expected.depositGroupBudget());
			long sectionActiveSvq = 0L, sectionDepositSvq = 0L;
			double sectionActiveMass = 0.0, sectionDepositMass = 0.0;
			int depositCells = 0, previousCell = -1;
			for (GranularRegionStateCodec.ActiveEntry entry : data.active()) {
				if (entry.ordinal() < 0 || entry.ordinal() >= active.length || seenActive[entry.ordinal()]) {
					throw new IOException("Duplicate or invalid granular active ordinal");
				}
				validateGranularSectionPosition(section, entry.parcel().x(), entry.parcel().y(), entry.parcel().z());
				seenActive[entry.ordinal()] = true; active[entry.ordinal()] = entry.parcel();
				int material = entry.parcel().materialPaletteIndex();
				if (material < 0 || material >= materialCount) throw new IOException("Granular active material is outside palette");
				sectionActiveSvq = Math.addExact(sectionActiveSvq, entry.parcel().solidVolumeSvq());
				sectionActiveMass += entry.parcel().mass();
			}
			GranularStateSnapshot.DepositGroup previousGroup = null;
			for (GranularStateSnapshot.DepositGroup group : data.deposits()) {
				if (previousGroup != null && (previousGroup.cell() > group.cell()
						|| previousGroup.cell() == group.cell() && (previousGroup.materialPaletteIndex() > group.materialPaletteIndex()
						|| previousGroup.materialPaletteIndex() == group.materialPaletteIndex()
						&& previousGroup.solidCompositionIndex() >= group.solidCompositionIndex()))) {
					throw new IOException("Granular section deposit groups are not canonical");
				}
				previousGroup = group;
				validateGranularDepositSection(section, group.cell()); deposits.add(group);
				int material = group.materialPaletteIndex();
				if (material < 0 || material >= materialCount) throw new IOException("Granular deposit material is outside palette");
				sectionDepositSvq = Math.addExact(sectionDepositSvq, group.solidVolumeSvq());
				sectionDepositMass += group.mass();
				if (group.cell() != previousCell) { depositCells++; previousCell = group.cell(); }
			}
			GranularRegionStateCodec.SectionLedger ledger = sectionLedgers.remove(section.key());
			if (ledger == null || ledger.activeCount() != data.active().size()
					|| ledger.depositGroupCount() != data.deposits().size() || ledger.depositCellCount() != depositCells
					|| ledger.activeSvq() != sectionActiveSvq || ledger.depositSvq() != sectionDepositSvq
					|| ledger.activeMassBits() != Double.doubleToRawLongBits(sectionActiveMass)
					|| ledger.depositMassBits() != Double.doubleToRawLongBits(sectionDepositMass)) {
				throw new IOException("Granular section does not match its v5 ledger");
			}
		}
		if (!sectionLedgers.isEmpty()) throw new IOException("Granular manifest names missing sections");
		for (boolean seen : seenActive) if (!seen) throw new IOException("Missing granular active ordinal");
		deposits.sort(Comparator.comparingInt(GranularStateSnapshot.DepositGroup::cell)
				.thenComparingInt(GranularStateSnapshot.DepositGroup::materialPaletteIndex)
				.thenComparingInt(GranularStateSnapshot.DepositGroup::solidCompositionIndex));
		for (GranularStateSnapshot.ActiveParcel parcel : active) {
			int material = parcel.materialPaletteIndex(); activeCounts[material]++;
			activeSvq[material] = Math.addExact(activeSvq[material], parcel.solidVolumeSvq());
			activeMass[material] += parcel.mass();
		}
		for (GranularStateSnapshot.DepositGroup group : deposits) {
			int material = group.materialPaletteIndex(); depositCounts[material]++;
			depositSvq[material] = Math.addExact(depositSvq[material], group.solidVolumeSvq());
			depositMass[material] += group.mass();
		}
		long totalActiveSvq = 0L, totalDepositSvq = 0L;
		for (int material = 0; material < materialCount; material++) {
			GranularRegionStateCodec.MaterialLedger ledger = expected.materialLedgers().get(material);
			if (ledger.activeCount() != activeCounts[material] || ledger.activeSvq() != activeSvq[material]
					|| ledger.activeMassBits() != Double.doubleToRawLongBits(activeMass[material])
					|| ledger.depositGroupCount() != depositCounts[material] || ledger.depositSvq() != depositSvq[material]
					|| ledger.depositMassBits() != Double.doubleToRawLongBits(depositMass[material])) {
				throw new IOException("Granular material ledger mismatch at palette index " + material);
			}
			totalActiveSvq = Math.addExact(totalActiveSvq, activeSvq[material]);
			totalDepositSvq = Math.addExact(totalDepositSvq, depositSvq[material]);
		}
		double totalActiveMass = 0.0, totalDepositMass = 0.0;
		for (GranularStateSnapshot.ActiveParcel parcel : active) totalActiveMass += parcel.mass();
		for (GranularStateSnapshot.DepositGroup group : deposits) totalDepositMass += group.mass();
		if (expected.activeSvq() != totalActiveSvq || expected.depositSvq() != totalDepositSvq
				|| expected.activeMassBits() != Double.doubleToRawLongBits(totalActiveMass)
				|| expected.depositMassBits() != Double.doubleToRawLongBits(totalDepositMass)) {
			throw new IOException("Granular manifest total ledger mismatch");
		}
		try {
			new GranularStateSnapshot(manifest.sizeX(), manifest.sizeY(), manifest.sizeZ(), expected.registryFingerprint(),
					expected.chemicalRegistryFingerprint(), expected.materialPalette(), expected.solidCompositions(),
					expected.solidSpeciesTotals(), Arrays.asList(active), deposits,
					Math.addExact(totalActiveSvq, totalDepositSvq),
					Double.doubleToRawLongBits(totalActiveMass + totalDepositMass),
					expected.externalSource(), expected.escapedSink());
		} catch (IllegalArgumentException invalid) {
			throw new IOException("Invalid v5 granular state: " + invalid.getMessage(), invalid);
		}
	}

	private void validateMaterialLedgers() throws IOException {
		int count = manifest.totalMarkerCount(), paletteSize = manifest.materialPalette().size();
		double[] volumesByOrdinal = new double[count], massByOrdinal = new double[count];
		int[] materialByOrdinal = new int[count];
		boolean[] seen = new boolean[count];
		for (RegionSectionState section : sections) {
			int[] ordinals = section.markerOrdinals(), materials = section.materialPaletteIndex();
			float[] positionVolume = section.positionVolume();
			double[] mass = section.markerMass();
			for (int marker = 0; marker < ordinals.length; marker++) {
				int ordinal = ordinals[marker], material = materials[marker];
				if (ordinal < 0 || ordinal >= count || seen[ordinal]) throw new IOException("Duplicate or invalid marker ordinal");
				if (material < 0 || material >= paletteSize) throw new IOException("Material palette index out of range");
				seen[ordinal] = true;
				volumesByOrdinal[ordinal] = positionVolume[marker * 4 + 3];
				massByOrdinal[ordinal] = mass[marker];
				materialByOrdinal[ordinal] = material;
			}
		}
		int[] markerCounts = new int[paletteSize];
		double[] volumes = new double[paletteSize], masses = new double[paletteSize];
		double totalVolume = 0.0, totalMass = 0.0;
		for (int ordinal = 0; ordinal < count; ordinal++) {
			if (!seen[ordinal]) throw new IOException("Missing marker ordinal");
			int material = materialByOrdinal[ordinal];
			markerCounts[material]++;
			volumes[material] += volumesByOrdinal[ordinal];
			masses[material] += massByOrdinal[ordinal];
			totalVolume += volumesByOrdinal[ordinal];
			totalMass += massByOrdinal[ordinal];
		}
		if (Double.doubleToRawLongBits(totalVolume) != manifest.totalVolumeBits()
				|| Double.doubleToRawLongBits(totalMass) != manifest.totalMassBits()) {
			throw new IOException("Material manifest total ledger mismatch");
		}
		for (int material = 0; material < paletteSize; material++) {
			RegionManifest.MaterialLedger expected = manifest.materialLedgers().get(material);
			if (expected.markerCount() != markerCounts[material]
					|| expected.volumeBits() != Double.doubleToRawLongBits(volumes[material])
					|| expected.massBits() != Double.doubleToRawLongBits(masses[material])) {
				throw new IOException("Material marker ledger mismatch at palette index " + material);
			}
		}
	}

	private void validateSectionOwnership(RegionSectionState section) throws IOException {
		int cellsPerBlock = Math.round(1.0F / manifest.solverConfig().cellSize());
		if (cellsPerBlock <= 0 || Math.abs(cellsPerBlock * manifest.solverConfig().cellSize() - 1.0F) > 1.0E-6F) {
			throw new IOException("Unsupported persisted cell-to-block ratio");
		}
		int expectedStartX = Math.max(0, (section.sectionX() * 16 - manifest.origin().getX()) * cellsPerBlock);
		int expectedStartY = Math.max(0, (section.sectionY() * 16 - manifest.origin().getY()) * cellsPerBlock);
		int expectedStartZ = Math.max(0, (section.sectionZ() * 16 - manifest.origin().getZ()) * cellsPerBlock);
		int expectedEndX = Math.min(manifest.sizeX(), ((section.sectionX() + 1) * 16 - manifest.origin().getX()) * cellsPerBlock);
		int expectedEndY = Math.min(manifest.sizeY(), ((section.sectionY() + 1) * 16 - manifest.origin().getY()) * cellsPerBlock);
		int expectedEndZ = Math.min(manifest.sizeZ(), ((section.sectionZ() + 1) * 16 - manifest.origin().getZ()) * cellsPerBlock);
		if (section.localStartX() != expectedStartX || section.localStartY() != expectedStartY || section.localStartZ() != expectedStartZ
				|| section.sizeX() != expectedEndX - expectedStartX || section.sizeY() != expectedEndY - expectedStartY
				|| section.sizeZ() != expectedEndZ - expectedStartZ) throw new IOException("Section solid slice is not aligned to its Minecraft section");
		float[] positions = section.positionVolume();
		for (int marker = 0; marker < section.markerCount(); marker++) {
			int base = marker * 4;
			int sectionX = Math.floorDiv((int) Math.floor(manifest.origin().getX() + positions[base]), 16);
			int sectionY = Math.floorDiv((int) Math.floor(manifest.origin().getY() + positions[base + 1]), 16);
			int sectionZ = Math.floorDiv((int) Math.floor(manifest.origin().getZ() + positions[base + 2]), 16);
			if (sectionX != section.sectionX() || sectionY != section.sectionY() || sectionZ != section.sectionZ()) {
				throw new IOException("Marker is stored in the wrong section");
			}
		}
	}

	private void validateGranularSectionPosition(RegionSectionState section, float x, float y, float z) throws IOException {
		int sectionX = Math.floorDiv((int) Math.floor(manifest.origin().getX() + x), 16);
		int sectionY = Math.floorDiv((int) Math.floor(manifest.origin().getY() + y), 16);
		int sectionZ = Math.floorDiv((int) Math.floor(manifest.origin().getZ() + z), 16);
		if (sectionX != section.sectionX() || sectionY != section.sectionY() || sectionZ != section.sectionZ()) {
			throw new IOException("Granular active parcel is stored in the wrong section");
		}
	}

	private void validateGranularDepositSection(RegionSectionState section, int cell) throws IOException {
		int cellCount = Math.multiplyExact(Math.multiplyExact(manifest.sizeX(), manifest.sizeY()), manifest.sizeZ());
		if (cell < 0 || cell >= cellCount) throw new IOException("Granular deposit cell is outside region bounds");
		int x = cell % manifest.sizeX(), yz = cell / manifest.sizeX();
		int y = yz % manifest.sizeY(), z = yz / manifest.sizeY();
		float h = manifest.solverConfig().cellSize();
		validateGranularSectionPosition(section, (x + 0.5F) * h, (y + 0.5F) * h, (z + 0.5F) * h);
	}

	private static RegionSectionState withChecksum(RegionSectionState section) {
		long checksum = RegionStateIntegrity.sectionChecksum(section);
		return new RegionSectionState(section.formatVersion(), section.regionId(), section.dimensionId(), section.checkpointGeneration(),
				section.sectionX(), section.sectionY(), section.sectionZ(), section.localStartX(), section.localStartY(), section.localStartZ(),
				section.sizeX(), section.sizeY(), section.sizeZ(), section.markerOrdinals(), section.positionVolume(),
				section.velocities(), section.markerMass(), section.materialPaletteIndex(), section.solidMask(), section.extensions(),
				section.chemicalMixtureIndex(), section.indicator(), section.chemicalSpeciesTotals(),
				section.chemicalResidualLedgerBits(), checksum);
	}

	private static RegionManifest withChecksum(RegionManifest manifest) {
		long checksum = RegionStateIntegrity.manifestChecksum(manifest);
		return new RegionManifest(manifest.formatVersion(), manifest.regionId(), manifest.dimensionId(), manifest.checkpointGeneration(), manifest.networkGeneration(),
				manifest.origin(), manifest.sizeX(), manifest.sizeY(), manifest.sizeZ(), manifest.solverConfig(),
				manifest.reservedMarkerCapacity(), manifest.runtime(), manifest.footprint(), manifest.totalMarkerCount(),
				manifest.totalVolumeBits(), manifest.materialPalette(), manifest.totalMassBits(), manifest.materialLedgers(),
				manifest.sections(), manifest.extensions(), manifest.registryFingerprint(), manifest.auScale(),
				manifest.speciesPalette(), manifest.chemicalMixtures(), manifest.chemicalSpeciesTotals(),
				manifest.chemicalResidualLedgerBits(), checksum);
	}

	private static List<RegionManifest.MaterialLedger> materialLedgers(CapturedParticles particles) {
		if (particles.formatVersion() == 1) return List.of();
		int paletteSize = particles.materialPalette().size();
		int[] markerCounts = new int[paletteSize];
		double[] volumes = new double[paletteSize], masses = new double[paletteSize];
		for (int ordinal = 0; ordinal < particles.particleCount(); ordinal++) {
			int material = particles.materialPaletteIndex()[ordinal];
			markerCounts[material]++;
			volumes[material] += particles.positionVolume()[ordinal * 4 + 3];
			masses[material] += particles.markerMass()[ordinal];
		}
		List<RegionManifest.MaterialLedger> result = new ArrayList<>(paletteSize);
		for (int material = 0; material < paletteSize; material++) {
			result.add(new RegionManifest.MaterialLedger(markerCounts[material], Double.doubleToRawLongBits(volumes[material]),
					Double.doubleToRawLongBits(masses[material])));
		}
		return List.copyOf(result);
	}

	private static double sumVolumes(float[] positionVolume) {
		double result = 0.0;
		for (int index = 3; index < positionVolume.length; index += 4) result += positionVolume[index];
		return result;
	}

	private static double sum(double[] values) {
		double result = 0.0;
		for (double value : values) result += value;
		return result;
	}

	private static long[] chemicalTotals(CapturedParticles particles, int[] mixtureIndices) {
		long[] totals = new long[particles.speciesPalette().size()];
		Map<String, Integer> handles = new HashMap<>();
		for (int species = 0; species < particles.speciesPalette().size(); species++) {
			handles.put(particles.speciesPalette().get(species), species);
		}
		for (int mixtureIndex : mixtureIndices) {
			FlipChemicalParticleState.StableMixture mixture = particles.chemicalMixtures().get(mixtureIndex);
			for (FlipChemicalParticleState.Component component : mixture.components()) {
				Integer handle = handles.get(component.speciesId());
				if (handle == null) throw new IllegalArgumentException("Chemical mixture references species outside palette");
				totals[handle] = Math.addExact(totals[handle], component.amountQuanta());
			}
		}
		return totals;
	}

	private static RegionSectionState.SectionKey sectionKey(FluidRegion region, float x, float y, float z) {
		return new RegionSectionState.SectionKey(
				Math.floorDiv((int) Math.floor(region.origin().getX() + x), 16),
				Math.floorDiv((int) Math.floor(region.origin().getY() + y), 16),
				Math.floorDiv((int) Math.floor(region.origin().getZ() + z), 16));
	}

	private static GranularRegionStateCodec.ManifestData granularManifest(
			GranularStateSnapshot snapshot, List<RegionSectionState> sections, boolean chemicalState
	) throws IOException {
		int materials = snapshot.materialPalette().size();
		int[] activeCounts = new int[materials], depositCounts = new int[materials];
		long[] activeSvq = new long[materials], depositSvq = new long[materials];
		double[] activeMass = new double[materials], depositMass = new double[materials];
		double canonicalActiveMass = 0.0, canonicalDepositMass = 0.0;
		for (GranularStateSnapshot.ActiveParcel parcel : snapshot.activeParcels()) {
			int material = parcel.materialPaletteIndex(); activeCounts[material]++;
			activeSvq[material] = Math.addExact(activeSvq[material], parcel.solidVolumeSvq());
			activeMass[material] += parcel.mass();
			canonicalActiveMass += parcel.mass();
		}
		for (GranularStateSnapshot.DepositGroup group : snapshot.depositGroups()) {
			int material = group.materialPaletteIndex(); depositCounts[material]++;
			depositSvq[material] = Math.addExact(depositSvq[material], group.solidVolumeSvq());
			depositMass[material] += group.mass();
			canonicalDepositMass += group.mass();
		}
		List<GranularRegionStateCodec.MaterialLedger> materialLedgers = new ArrayList<>(materials);
		for (int material = 0; material < materials; material++) {
			materialLedgers.add(new GranularRegionStateCodec.MaterialLedger(activeCounts[material], activeSvq[material],
					Double.doubleToRawLongBits(activeMass[material]), depositCounts[material], depositSvq[material],
					Double.doubleToRawLongBits(depositMass[material])));
		}
		List<GranularRegionStateCodec.SectionLedger> sectionLedgers = new ArrayList<>(sections.size());
		long allActiveSvq = 0L, allDepositSvq = 0L;
		int depositCells = 0;
		GranularRegionStateCodec codec = new GranularRegionStateCodec();
		for (RegionSectionState section : sections) {
			GranularRegionStateCodec.SectionData data = codec.decodeSection(
					requiredExtension(section.extensions(), GranularRegionStateCodec.SECTION_EXTENSION).payload(),
					GranularLimits.MAX_ACTIVE_PARCELS_PER_REGION, GranularLimits.MAX_DEPOSIT_GROUPS_PER_REGION);
			long sectionActiveSvq = 0L, sectionDepositSvq = 0L;
			double sectionActiveMass = 0.0, sectionDepositMass = 0.0;
			int sectionDepositCells = 0, previousCell = -1;
			for (GranularRegionStateCodec.ActiveEntry entry : data.active()) {
				sectionActiveSvq = Math.addExact(sectionActiveSvq, entry.parcel().solidVolumeSvq());
				sectionActiveMass += entry.parcel().mass();
			}
			for (GranularStateSnapshot.DepositGroup group : data.deposits()) {
				sectionDepositSvq = Math.addExact(sectionDepositSvq, group.solidVolumeSvq());
				sectionDepositMass += group.mass();
				if (group.cell() != previousCell) { sectionDepositCells++; previousCell = group.cell(); }
			}
			sectionLedgers.add(new GranularRegionStateCodec.SectionLedger(section.key(), data.active().size(),
					data.deposits().size(), sectionDepositCells, sectionActiveSvq,
					Double.doubleToRawLongBits(sectionActiveMass), sectionDepositSvq,
					Double.doubleToRawLongBits(sectionDepositMass)));
			allActiveSvq = Math.addExact(allActiveSvq, sectionActiveSvq);
			allDepositSvq = Math.addExact(allDepositSvq, sectionDepositSvq);
			depositCells += sectionDepositCells;
		}
		int capabilities = GranularRegionStateCodec.CAP_MATERIALS | GranularRegionStateCodec.CAP_GRANULAR
				| (chemicalState ? GranularRegionStateCodec.CAP_CHEMISTRY : 0)
				| (snapshot.requiresChemistry() ? GranularRegionStateCodec.CAP_PRECIPITATION : 0);
		return new GranularRegionStateCodec.ManifestData(capabilities, snapshot.registryFingerprint(),
				SolidVolume.SVQ_PER_BV, GranularLimits.POROSITY_SCALE,
				GranularLimits.MAX_ACTIVE_PARCELS_PER_REGION, GranularLimits.MAX_DEPOSIT_GROUPS_PER_REGION,
				snapshot.materialPalette(), snapshot.chemicalRegistryFingerprint(), snapshot.solidCompositions(),
				snapshot.solidSpeciesTotals(), snapshot.externalSource(), snapshot.escapedSink(),
				snapshot.activeParcels().size(), snapshot.depositGroups().size(), depositCells,
				allActiveSvq, Double.doubleToRawLongBits(canonicalActiveMass), allDepositSvq,
				Double.doubleToRawLongBits(canonicalDepositMass), List.copyOf(materialLedgers), List.copyOf(sectionLedgers));
	}

	private static RegionExtension requiredExtension(List<RegionExtension> extensions, String type) throws IOException {
		RegionExtension found = null;
		for (RegionExtension extension : extensions) if (type.equals(extension.type())) {
			if (found != null) throw new IOException("Duplicate required region extension " + type);
			found = extension;
		}
		if (found == null) throw new IOException("Missing required region extension " + type);
		return found;
	}

	private record CapturedParticles(
			int formatVersion,
			List<FluidMaterial> materialPalette,
			float[] positionVolume,
			float[] velocities,
			byte[] solidMask,
			double[] markerMass,
			int[] materialPaletteIndex,
			String registryFingerprint,
			List<String> speciesPalette,
			List<FlipChemicalParticleState.StableMixture> chemicalMixtures,
			int[] chemicalMixtureIndex,
			byte[] indicator,
			long[] chemicalSpeciesTotals,
			long chemicalResidualLedgerBits,
			ChemicalTransferLedger externalSource,
			ChemicalTransferLedger escapedSink
	) {
		int particleCount() { return positionVolume.length / 4; }
		boolean hasChemicalState() { return !registryFingerprint.isEmpty(); }
	}

	private record ReconstructedParticles(
			float[] positionVolume,
			float[] velocities,
			byte[] solidMask,
			double[] markerMass,
			int[] materialPaletteIndex,
			int[] chemicalMixtureIndex,
			byte[] indicator
	) { }
}

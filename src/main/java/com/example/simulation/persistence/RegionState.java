package com.example.simulation.persistence;

import com.example.fluid.FlipParticleState;
import com.example.fluid.FlipMaterialParticleState;
import com.example.fluid.FlipChemicalParticleState;
import com.example.fluid.FluidMaterial;
import com.example.chemistry.ChemicalRegistry;
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
	public RegionState {
		sections = List.copyOf(sections);
	}

	public static RegionState capture(FluidRegion region, long checkpointGeneration) throws IOException {
		requireCaptureable(region, checkpointGeneration);
		FlipParticleState particles = region.domain().exportParticleState();
		return capture(region, checkpointGeneration, new CapturedParticles(1, List.of(), particles.copyPositionVolume(),
				particles.copyParticleVelocities(), particles.copySolidMask(), new double[0], new int[0],
				"", List.of(), List.of(), new int[0], new byte[0], new long[0], 0L));
	}

	/** Captures an explicitly material-bound CPU domain without routing it through the pure-water handoff. */
	public static RegionState captureMaterial(FluidRegion region, long checkpointGeneration) throws IOException {
		requireCaptureable(region, checkpointGeneration);
		FlipMaterialParticleState particles = region.domain().exportMaterialParticleState();
		return capture(region, checkpointGeneration, new CapturedParticles(2, particles.materials(), particles.copyPositionVolume(),
				particles.copyParticleVelocities(), particles.copySolidMask(), particles.copyParticleMass(),
				particles.copyMaterialPaletteIndex(), "", List.of(), List.of(), new int[0], new byte[0], new long[0], 0L));
	}

	public static RegionState captureChemical(FluidRegion region, long checkpointGeneration) throws IOException {
		requireCaptureable(region, checkpointGeneration);
		FlipChemicalParticleState particles = region.domain().exportChemicalParticleState();
		FlipMaterialParticleState materials = particles.materials();
		return capture(region, checkpointGeneration, new CapturedParticles(3, materials.materials(),
				materials.copyPositionVolume(), materials.copyParticleVelocities(), materials.copySolidMask(),
				materials.copyParticleMass(), materials.copyMaterialPaletteIndex(), particles.registryFingerprint(),
				particles.speciesPalette(), particles.mixtures(), particles.copyMarkerMixtureIndex(),
				particles.copyIndicator(), particles.copySpeciesTotals(), particles.residualLedgerBits()));
	}

	private static RegionState capture(FluidRegion region, long checkpointGeneration, CapturedParticles particles) throws IOException {
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
					int[] savedMixtures = particles.formatVersion() == 3 ? new int[ordinals.size()] : new int[0];
					byte[] savedIndicator = particles.formatVersion() == 3 ? new byte[ordinals.size()] : new byte[0];
					for (int i = 0; i < ordinals.size(); i++) {
						int ordinal = ordinals.get(i);
						savedOrdinals[i] = ordinal;
						System.arraycopy(positionVolume, ordinal * 4, savedPositions, i * 4, 4);
						System.arraycopy(velocities, ordinal * 3, savedVelocities, i * 3, 3);
						if (particles.formatVersion() >= 2) {
							savedMass[i] = particles.markerMass()[ordinal];
							savedMaterials[i] = particles.materialPaletteIndex()[ordinal];
						}
						if (particles.formatVersion() == 3) {
							savedMixtures[i] = particles.chemicalMixtureIndex()[ordinal];
							savedIndicator[i] = particles.indicator()[ordinal];
						}
					}
					long[] sectionSpecies = particles.formatVersion() == 3
							? chemicalTotals(particles, savedMixtures) : new long[0];
					double sectionResidual = 0.0;
					if (particles.formatVersion() == 3) for (int mixture : savedMixtures) {
						sectionResidual += Double.longBitsToDouble(particles.chemicalMixtures().get(mixture).massResidualBits());
					}
					RegionSectionState unsigned = new RegionSectionState(particles.formatVersion(), region.id(), region.dimension().identifier().toString(),
							checkpointGeneration, sectionX, sectionY, sectionZ, startX, startY, startZ,
							sliceX, sliceY, sliceZ, savedOrdinals, savedPositions, savedVelocities, savedMass, savedMaterials,
							solidSlice, List.of(), savedMixtures, savedIndicator, sectionSpecies,
							particles.formatVersion() == 3 ? Double.doubleToRawLongBits(sectionResidual) : 0L, 0L);
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
		RegionManifest unsigned = new RegionManifest(particles.formatVersion(), region.id(), region.dimension().identifier().toString(), checkpointGeneration,
				region.generation(), region.origin(), region.sizeX(), region.sizeY(), region.sizeZ(), region.solverConfig(),
				region.reservedMarkerCapacity(), region.persistedRuntime(), region.footprint(), particles.particleCount(),
				Double.doubleToRawLongBits(sumVolumes(positionVolume)), particles.materialPalette(),
				particles.formatVersion() >= 2 ? Double.doubleToRawLongBits(sum(particles.markerMass())) : 0L,
				materialLedgers, descriptors, List.of(), particles.registryFingerprint(),
				particles.formatVersion() == 3 ? com.example.chemistry.ChemicalLimits.QUANTA_PER_AU : 0L,
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
		if (manifest.formatVersion() != 2) {
			throw new IOException("Pure-water region-state v1 cannot use the material reconstruction path");
		}
		ReconstructedParticles particles = reconstructParticles();
		return new FlipMaterialParticleState(manifest.sizeX(), manifest.sizeY(), manifest.sizeZ(), manifest.solverConfig().cellSize(),
				manifest.totalMarkerCount(), manifest.totalVolume(), manifest.totalMass(), manifest.materialPalette(),
				particles.positionVolume(), particles.velocities(), particles.solidMask(), particles.markerMass(),
				particles.materialPaletteIndex());
	}

	public FlipChemicalParticleState reconstructChemical(ChemicalRegistry registry) throws IOException {
		if (manifest.formatVersion() != 3) throw new IOException("Only region-state v3 has chemical state");
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
		return new FlipChemicalParticleState(materials, registry, manifest.chemicalMixtures(),
				particles.chemicalMixtureIndex(), particles.indicator(), manifest.chemicalSpeciesTotals(),
				manifest.chemicalResidualLedgerBits());
	}

	private ReconstructedParticles reconstructParticles() throws IOException {
		validate();
		int count = manifest.totalMarkerCount();
		float[] positions = new float[Math.multiplyExact(count, 4)];
		float[] velocities = new float[Math.multiplyExact(count, 3)];
		double[] markerMass = manifest.formatVersion() >= 2 ? new double[count] : new double[0];
		int[] materialPaletteIndex = manifest.formatVersion() >= 2 ? new int[count] : new int[0];
		int[] chemicalMixtureIndex = manifest.formatVersion() == 3 ? new int[count] : new int[0];
		byte[] indicator = manifest.formatVersion() == 3 ? new byte[count] : new byte[0];
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
				if (manifest.formatVersion() == 3) {
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
		if (manifest.formatVersion() == 3) validateChemicalLedgers();
	}

	private void validateChemicalLedgers() throws IOException {
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
			long chemicalResidualLedgerBits
	) {
		int particleCount() { return positionVolume.length / 4; }
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

package mmm.simulation.persistence;

import mmm.application.checkpoint.RegionCheckpointSource;
import mmm.model.region.RegionCheckpoint;
import mmm.model.region.RegionExtension;
import mmm.model.region.RegionGeometry;
import mmm.model.region.RegionIdentity;
import mmm.model.region.RegionManifest;
import mmm.model.region.RegionSectionState;
import mmm.model.region.RegionStateLayout;
import mmm.fluid.FlipParticleState;
import mmm.fluid.FlipMaterialParticleState;
import mmm.fluid.FlipChemicalParticleState;
import mmm.fluid.FlipThermalParticleState;
import mmm.fluid.PendingPhaseVolumeState;
import mmm.fluid.FluidMaterial;
import mmm.fluid.air.GasStateSnapshot;
import mmm.chemistry.ChemicalAuditLedgerCodec;
import mmm.chemistry.ChemicalTransferLedger;
import mmm.granular.GranularLimits;
import mmm.granular.GranularStateSnapshot;
import mmm.granular.SolidVolume;
import mmm.units.SignedDyadic;
import mmm.thermal.CoherentPhaseStore;
import mmm.thermal.FiniteThermalDeviceInventory;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
/** Assembles one complete logical checkpoint from an immutable-export boundary. */
public final class RegionCheckpointAssembler {
	private static final String CHEMICAL_AUDIT_EXTENSION = "mmm:chemical_audit";
	private RegionCheckpointAssembler() { }

	public static RegionCheckpoint capture(RegionCheckpointSource source, long checkpointGeneration) throws IOException {
		requireCaptureable(source, checkpointGeneration);
		FlipParticleState particles = source.pureParticles();
		return capture(source, checkpointGeneration, new CapturedParticles(RegionStateLayout.PURE, List.of(), particles.copyPositionVolume(),
				particles.copyParticleVelocities(), particles.copySolidMask(), new double[0], new int[0],
				"", List.of(), List.of(), new int[0], new byte[0], new long[0], 0L,
				ChemicalTransferLedger.EMPTY, ChemicalTransferLedger.EMPTY), null, null, null);
	}

	/** Captures an explicitly material-bound CPU domain without routing it through the pure-water handoff. */
	public static RegionCheckpoint captureMaterial(RegionCheckpointSource source, long checkpointGeneration) throws IOException {
		requireCaptureable(source, checkpointGeneration);
		FlipMaterialParticleState particles = source.materialParticles();
		return capture(source, checkpointGeneration, new CapturedParticles(RegionStateLayout.MATERIAL, particles.materials(), particles.copyPositionVolume(),
				particles.copyParticleVelocities(), particles.copySolidMask(), particles.copyParticleMass(),
				particles.copyMaterialPaletteIndex(), "", List.of(), List.of(), new int[0], new byte[0], new long[0], 0L,
				ChemicalTransferLedger.EMPTY, ChemicalTransferLedger.EMPTY), null, null, null);
	}

	public static RegionCheckpoint captureChemical(RegionCheckpointSource source, long checkpointGeneration) throws IOException {
		requireCaptureable(source, checkpointGeneration);
		FlipChemicalParticleState particles = source.chemicalParticles();
		FlipMaterialParticleState materials = particles.materials();
		return capture(source, checkpointGeneration, new CapturedParticles(RegionStateLayout.CHEMICAL, materials.materials(),
				materials.copyPositionVolume(), materials.copyParticleVelocities(), materials.copySolidMask(),
				materials.copyParticleMass(), materials.copyMaterialPaletteIndex(), particles.registryFingerprint(),
				particles.speciesPalette(), particles.mixtures(), particles.copyMarkerMixtureIndex(),
				particles.copyIndicator(), particles.copySpeciesTotals(), particles.residualLedgerBits(),
				particles.externalSource(), particles.escapedSink()), null, null, null);
	}

	public static RegionCheckpoint captureGranular(RegionCheckpointSource source, long checkpointGeneration) throws IOException {
		requireCaptureable(source, checkpointGeneration);
		if (!source.hasGranularState() || !source.hasMaterialState()) {
			throw new IOException("Granular region capture requires material and granular state");
		}
		GranularStateSnapshot granular = source.granularState();
		CapturedParticles particles;
		if (source.hasChemicalState()) {
			FlipChemicalParticleState chemical = source.chemicalParticles();
			FlipMaterialParticleState materials = chemical.materials();
			particles = new CapturedParticles(RegionStateLayout.GRANULAR, materials.materials(), materials.copyPositionVolume(),
					materials.copyParticleVelocities(), materials.copySolidMask(), materials.copyParticleMass(),
					materials.copyMaterialPaletteIndex(), chemical.registryFingerprint(), chemical.speciesPalette(),
					chemical.mixtures(), chemical.copyMarkerMixtureIndex(), chemical.copyIndicator(),
					chemical.copySpeciesTotals(), chemical.residualLedgerBits(), chemical.externalSource(), chemical.escapedSink());
		} else {
			FlipMaterialParticleState materials = source.materialParticles();
			particles = new CapturedParticles(RegionStateLayout.GRANULAR, materials.materials(), materials.copyPositionVolume(),
					materials.copyParticleVelocities(), materials.copySolidMask(), materials.copyParticleMass(),
					materials.copyMaterialPaletteIndex(), "", List.of(), List.of(), new int[0], new byte[0],
					new long[0], 0L, ChemicalTransferLedger.EMPTY, ChemicalTransferLedger.EMPTY);
		}
		return capture(source, checkpointGeneration, particles, granular, null, null);
	}

	/** Captures one complete material/optional-chemistry/optional-granular thermal generation. */
	public static RegionCheckpoint captureThermal(RegionCheckpointSource source, long checkpointGeneration) throws IOException {
		requireCaptureable(source, checkpointGeneration);
		if (!source.hasThermalState() || !source.hasMaterialState()) {
			throw new IOException("Thermal region capture requires material and thermal state");
		}
		GranularStateSnapshot granular = source.hasGranularState()
				? source.granularState() : null;
		CapturedParticles particles;
		String chemicalFingerprint;
		if (source.hasChemicalState()) {
			FlipChemicalParticleState chemical = source.chemicalParticles();
			chemicalFingerprint = chemical.registryFingerprint();
			FlipMaterialParticleState materials = chemical.materials();
			particles = new CapturedParticles(RegionStateLayout.THERMAL, materials.materials(), materials.copyPositionVolume(),
					materials.copyParticleVelocities(), materials.copySolidMask(), materials.copyParticleMass(),
					materials.copyMaterialPaletteIndex(), chemical.registryFingerprint(), chemical.speciesPalette(),
					chemical.mixtures(), chemical.copyMarkerMixtureIndex(), chemical.copyIndicator(),
					chemical.copySpeciesTotals(), chemical.residualLedgerBits(), chemical.externalSource(), chemical.escapedSink());
		} else {
			chemicalFingerprint = "";
			FlipMaterialParticleState materials = source.materialParticles();
			particles = new CapturedParticles(RegionStateLayout.THERMAL, materials.materials(), materials.copyPositionVolume(),
					materials.copyParticleVelocities(), materials.copySolidMask(), materials.copyParticleMass(),
					materials.copyMaterialPaletteIndex(), "", List.of(), List.of(), new int[0], new byte[0],
					new long[0], 0L, ChemicalTransferLedger.EMPTY, ChemicalTransferLedger.EMPTY);
		}
		GasStateSnapshot gas;
		try { gas = source.gasState(chemicalFingerprint); }
		catch (IllegalStateException invalid) {
			throw new IOException("Thermal region state requires complete gas topology/inventory", invalid);
		}
		FlipThermalParticleState thermal = source.thermalState();
		if (thermal.copyGasNodeEnergy().length != gas.nodes().size()) {
			throw new IOException("Thermal region gas inventory and energy ordinals disagree");
		}
		return capture(source, checkpointGeneration, particles, granular, thermal, gas);
	}

	private static RegionCheckpoint capture(
			RegionCheckpointSource source, long checkpointGeneration, CapturedParticles particles,
			GranularStateSnapshot granular, FlipThermalParticleState thermal, GasStateSnapshot gas
	) throws IOException {
		requireCaptureable(source, checkpointGeneration);
		RegionIdentity identity = source.identity(checkpointGeneration);
		RegionGeometry geometry = source.geometry();
		int cellsPerBlock = Math.round(1.0F / geometry.solverConfig().cellSize());
		if (cellsPerBlock <= 0 || Math.abs(cellsPerBlock * geometry.solverConfig().cellSize() - 1.0F) > 1.0E-6F
				|| geometry.sizeX() % cellsPerBlock != 0 || geometry.sizeY() % cellsPerBlock != 0 || geometry.sizeZ() % cellsPerBlock != 0) {
			throw new IOException("Region state requires an integer number of solver cells per Minecraft block");
		}

		float[] positionVolume = particles.positionVolume();
		float[] velocities = particles.velocities();
		byte[] solids = particles.solidMask();
		int[] gasMembership = gas == null ? new int[0] : gas.pieceNodeOrdinal();
		if (gas != null && (gas.sizeX() != geometry.sizeX() || gas.sizeY() != geometry.sizeY() || gas.sizeZ() != geometry.sizeZ())) {
			throw new IOException("Gas topology dimensions do not match region-state");
		}
		Map<RegionSectionState.SectionKey, List<Integer>> markersBySection = new HashMap<>();
		for (int ordinal = 0; ordinal < particles.particleCount(); ordinal++) {
			int base = ordinal * 4;
			RegionSectionState.SectionKey key = new RegionSectionState.SectionKey(
					Math.floorDiv((int) Math.floor(geometry.origin().x() + positionVolume[base]), 16),
					Math.floorDiv((int) Math.floor(geometry.origin().y() + positionVolume[base + 1]), 16),
					Math.floorDiv((int) Math.floor(geometry.origin().z() + positionVolume[base + 2]), 16));
			markersBySection.computeIfAbsent(key, ignored -> new ArrayList<>()).add(ordinal);
		}
		Map<RegionSectionState.SectionKey, List<GranularRegionStateCodec.ActiveEntry>> granularActiveBySection = new HashMap<>();
		Map<RegionSectionState.SectionKey, List<GranularStateSnapshot.DepositGroup>> granularDepositsBySection = new HashMap<>();
		Map<RegionSectionState.SectionKey, List<Double>> granularDepositEnergyBySection = new HashMap<>();
		Map<RegionSectionState.SectionKey, List<ThermalRegionStateCodec.CoherentEntry>> coherentBySection = new HashMap<>();
		double[] allActiveEnergy = thermal == null ? new double[0] : thermal.copyActiveParcelEnergy();
		double[] allDepositEnergy = thermal == null ? new double[0] : thermal.copyDepositEnergy();
		if (thermal != null && (granular == null && (allActiveEnergy.length != 0 || allDepositEnergy.length != 0)
				|| granular != null && (allActiveEnergy.length != granular.activeParcels().size()
				|| allDepositEnergy.length != granular.depositGroups().size()))) {
			throw new IOException("Thermal granular carrier ordinals do not match the complete granular inventory");
		}
		if (granular != null) {
			for (int ordinal = 0; ordinal < granular.activeParcels().size(); ordinal++) {
				GranularStateSnapshot.ActiveParcel parcel = granular.activeParcels().get(ordinal);
				RegionSectionState.SectionKey key = sectionKey(geometry, parcel.x(), parcel.y(), parcel.z());
				granularActiveBySection.computeIfAbsent(key, ignored -> new ArrayList<>())
						.add(new GranularRegionStateCodec.ActiveEntry(ordinal, parcel));
			}
			for (int ordinal = 0; ordinal < granular.depositGroups().size(); ordinal++) {
				GranularStateSnapshot.DepositGroup group = granular.depositGroups().get(ordinal);
				int cellX = group.cell() % geometry.sizeX(), yz = group.cell() / geometry.sizeX();
				int cellY = yz % geometry.sizeY(), cellZ = yz / geometry.sizeY();
				RegionSectionState.SectionKey key = sectionKey(geometry,
						(cellX + 0.5F) * geometry.solverConfig().cellSize(),
						(cellY + 0.5F) * geometry.solverConfig().cellSize(),
						(cellZ + 0.5F) * geometry.solverConfig().cellSize());
				granularDepositsBySection.computeIfAbsent(key, ignored -> new ArrayList<>()).add(group);
				if (thermal != null) granularDepositEnergyBySection.computeIfAbsent(key, ignored -> new ArrayList<>())
						.add(allDepositEnergy[ordinal]);
			}
		}
		if (thermal != null) for (CoherentPhaseStore.Group group : thermal.coherentGroups()) {
			int cellX = group.cell() % geometry.sizeX(), yz = group.cell() / geometry.sizeX();
			int cellY = yz % geometry.sizeY(), cellZ = yz / geometry.sizeY();
			RegionSectionState.SectionKey key = sectionKey(geometry,
					(cellX + 0.5F) * geometry.solverConfig().cellSize(),
					(cellY + 0.5F) * geometry.solverConfig().cellSize(),
					(cellZ + 0.5F) * geometry.solverConfig().cellSize());
			coherentBySection.computeIfAbsent(key, ignored -> new ArrayList<>()).add(
					new ThermalRegionStateCodec.CoherentEntry(group.cell(), group.phaseId(),
							group.occupiedVolumeSvq(), Double.doubleToRawLongBits(group.mass()),
							group.composition(), Double.doubleToRawLongBits(group.internalEnergy())));
		}

		List<RegionSectionState> sectionStates = new ArrayList<>();
		int minimumSectionX = Math.floorDiv(geometry.origin().x(), 16);
		int minimumSectionY = Math.floorDiv(geometry.origin().y(), 16);
		int minimumSectionZ = Math.floorDiv(geometry.origin().z(), 16);
		int blockSizeX = geometry.sizeX() / cellsPerBlock, blockSizeY = geometry.sizeY() / cellsPerBlock;
		int blockSizeZ = geometry.sizeZ() / cellsPerBlock;
		int maximumSectionX = Math.floorDiv(geometry.origin().x() + blockSizeX - 1, 16);
		int maximumSectionY = Math.floorDiv(geometry.origin().y() + blockSizeY - 1, 16);
		int maximumSectionZ = Math.floorDiv(geometry.origin().z() + blockSizeZ - 1, 16);
		for (int sectionX = minimumSectionX; sectionX <= maximumSectionX; sectionX++) {
			for (int sectionY = minimumSectionY; sectionY <= maximumSectionY; sectionY++) {
				for (int sectionZ = minimumSectionZ; sectionZ <= maximumSectionZ; sectionZ++) {
					RegionSectionState.SectionKey key = new RegionSectionState.SectionKey(sectionX, sectionY, sectionZ);
					int startX = Math.max(0, (sectionX * 16 - geometry.origin().x()) * cellsPerBlock);
					int startY = Math.max(0, (sectionY * 16 - geometry.origin().y()) * cellsPerBlock);
					int startZ = Math.max(0, (sectionZ * 16 - geometry.origin().z()) * cellsPerBlock);
					int endX = Math.min(geometry.sizeX(), ((sectionX + 1) * 16 - geometry.origin().x()) * cellsPerBlock);
					int endY = Math.min(geometry.sizeY(), ((sectionY + 1) * 16 - geometry.origin().y()) * cellsPerBlock);
					int endZ = Math.min(geometry.sizeZ(), ((sectionZ + 1) * 16 - geometry.origin().z()) * cellsPerBlock);
					int sliceX = endX - startX, sliceY = endY - startY, sliceZ = endZ - startZ;
					byte[] solidSlice = new byte[Math.multiplyExact(Math.multiplyExact(sliceX, sliceY), sliceZ)];
					int[] gasSlice = gas == null ? new int[0] : new int[Math.multiplyExact(8, solidSlice.length)];
					int target = 0;
					for (int z = startZ; z < endZ; z++) for (int y = startY; y < endY; y++) for (int x = startX; x < endX; x++) {
						int cell = x + geometry.sizeX() * (y + geometry.sizeY() * z);
						solidSlice[target] = solids[cell];
						if (gas != null) System.arraycopy(gasMembership, cell * 8, gasSlice, target * 8, 8);
						target++;
					}
					List<Integer> ordinals = markersBySection.getOrDefault(key, List.of());
					int[] savedOrdinals = new int[ordinals.size()];
					float[] savedPositions = new float[ordinals.size() * 4];
					float[] savedVelocities = new float[ordinals.size() * 3];
					double[] savedMass = particles.layout().hasMaterials() ? new double[ordinals.size()] : new double[0];
					int[] savedMaterials = particles.layout().hasMaterials() ? new int[ordinals.size()] : new int[0];
					int[] savedMixtures = particles.hasChemicalState() ? new int[ordinals.size()] : new int[0];
					byte[] savedIndicator = particles.hasChemicalState() ? new byte[ordinals.size()] : new byte[0];
					for (int i = 0; i < ordinals.size(); i++) {
						int ordinal = ordinals.get(i);
						savedOrdinals[i] = ordinal;
						System.arraycopy(positionVolume, ordinal * 4, savedPositions, i * 4, 4);
						System.arraycopy(velocities, ordinal * 3, savedVelocities, i * 3, 3);
						if (particles.layout().hasMaterials()) {
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
					ArrayList<RegionExtension> sectionExtensions = new ArrayList<>();
					if (gas != null) sectionExtensions.add(new RegionExtension(GasRegionStateCodec.SECTION_EXTENSION,
							new GasRegionStateCodec().encodeSection(new GasRegionStateCodec.SectionData(gasSlice))));
					if (granular != null) {
						GranularRegionStateCodec.SectionData data = new GranularRegionStateCodec.SectionData(
								List.copyOf(granularActiveBySection.getOrDefault(key, List.of())),
								List.copyOf(granularDepositsBySection.getOrDefault(key, List.of())));
						sectionExtensions.add(new RegionExtension(GranularRegionStateCodec.SECTION_EXTENSION,
								new GranularRegionStateCodec().encodeSection(data)));
					}
					if (thermal != null) {
						double[] allEnergy = thermal.copyInternalEnergy();
						long[] markerEnergyBits = new long[ordinals.size()];
						for (int index = 0; index < ordinals.size(); index++) markerEnergyBits[index] =
								Double.doubleToRawLongBits(allEnergy[ordinals.get(index)]);
						List<ThermalRegionStateCodec.CoherentEntry> coherent = List.copyOf(
								coherentBySection.getOrDefault(key, List.of()));
						List<GranularRegionStateCodec.ActiveEntry> activeEntries =
								granularActiveBySection.getOrDefault(key, List.of());
						long[] activeEnergyBits = new long[activeEntries.size()];
						for (int index = 0; index < activeEntries.size(); index++) activeEnergyBits[index] =
								Double.doubleToRawLongBits(allActiveEnergy[activeEntries.get(index).ordinal()]);
						List<Double> sectionDepositEnergy = granularDepositEnergyBySection.getOrDefault(key, List.of());
						long[] depositEnergyBits = new long[sectionDepositEnergy.size()];
						for (int index = 0; index < sectionDepositEnergy.size(); index++) depositEnergyBits[index] =
								Double.doubleToRawLongBits(sectionDepositEnergy.get(index));
						SignedDyadic sectionEnergy = SignedDyadic.sum(toDoubles(markerEnergyBits));
						sectionEnergy = sectionEnergy.add(SignedDyadic.sum(toDoubles(activeEnergyBits)))
								.add(SignedDyadic.sum(toDoubles(depositEnergyBits)));
						for (ThermalRegionStateCodec.CoherentEntry group : coherent) {
							sectionEnergy = sectionEnergy.add(SignedDyadic.of(group.internalEnergy()));
						}
						ThermalRegionStateCodec.SectionData data = new ThermalRegionStateCodec.SectionData(
								markerEnergyBits, activeEnergyBits, depositEnergyBits, coherent, sectionEnergy);
						sectionExtensions.add(new RegionExtension(ThermalRegionStateCodec.SECTION_EXTENSION,
								new ThermalRegionStateCodec().encodeSection(data)));
					}
					RegionSectionState unsigned = new RegionSectionState(particles.layout(), identity.regionId(), identity.dimensionId(),
							checkpointGeneration, sectionX, sectionY, sectionZ, startX, startY, startZ,
							sliceX, sliceY, sliceZ, savedOrdinals, savedPositions, savedVelocities, savedMass, savedMaterials,
							solidSlice, List.copyOf(sectionExtensions), savedMixtures, savedIndicator, sectionSpecies,
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
					section.layout().hasMaterials() ? Double.doubleToRawLongBits(sum(section.markerMass())) : 0L, section.checksum()));
		}
		List<RegionManifest.MaterialLedger> materialLedgers = materialLedgers(particles);
		List<RegionExtension> manifestExtensions = new ArrayList<>();
		if (particles.hasChemicalState()) manifestExtensions.add(new RegionExtension(CHEMICAL_AUDIT_EXTENSION,
				ChemicalAuditLedgerCodec.encode(particles.externalSource(), particles.escapedSink())));
		if (gas != null) manifestExtensions.add(new RegionExtension(GasRegionStateCodec.MANIFEST_EXTENSION,
				new GasRegionStateCodec().encodeManifest(gasManifest(gas, sectionStates, particles.hasChemicalState()))));
		if (granular != null) manifestExtensions.add(new RegionExtension(GranularRegionStateCodec.MANIFEST_EXTENSION,
				new GranularRegionStateCodec().encodeManifest(granularManifest(granular, sectionStates,
						particles.hasChemicalState()))));
		if (thermal != null) {
			ThermalRegionStateCodec codec = new ThermalRegionStateCodec();
			ArrayList<ThermalRegionStateCodec.SectionLedger> ledgers = new ArrayList<>(sectionStates.size());
			for (RegionSectionState section : sectionStates) {
				ThermalRegionStateCodec.SectionData data = codec.decodeSection(
						requiredExtension(section.extensions(), ThermalRegionStateCodec.SECTION_EXTENSION).payload(),
						section.markerCount(), GranularLimits.MAX_ACTIVE_PARCELS_PER_REGION,
						GranularLimits.MAX_DEPOSIT_GROUPS_PER_REGION);
				ledgers.add(new ThermalRegionStateCodec.SectionLedger(section.key(), data.liquidMarkerEnergyBits().length,
						data.activeParcelEnergyBits().length, data.depositGroupEnergyBits().length,
						data.coherentGroups().size(), data.energy()));
			}
			SignedDyadic liquidEnergy = SignedDyadic.sum(thermal.copyInternalEnergy());
			SignedDyadic activeEnergy = SignedDyadic.sum(allActiveEnergy);
			SignedDyadic depositEnergy = SignedDyadic.sum(allDepositEnergy);
			SignedDyadic coherentEnergy = SignedDyadic.ZERO;
			for (CoherentPhaseStore.Group group : thermal.coherentGroups()) {
				coherentEnergy = coherentEnergy.add(SignedDyadic.of(group.internalEnergy()));
			}
			int capabilities = ThermalRegionStateCodec.CAP_MATERIALS | ThermalRegionStateCodec.CAP_THERMAL
					| (particles.hasChemicalState() ? ThermalRegionStateCodec.CAP_CHEMISTRY : 0)
					| (granular != null ? ThermalRegionStateCodec.CAP_GRANULAR : 0)
					| ThermalRegionStateCodec.CAP_GAS;
			double[] gasNodeEnergy = thermal.copyGasNodeEnergy();
			long[] gasNodeEnergyBits = new long[gasNodeEnergy.length];
			for (int node = 0; node < gasNodeEnergy.length; node++) {
				gasNodeEnergyBits[node] = Double.doubleToRawLongBits(gasNodeEnergy[node]);
			}
			SignedDyadic gasEnergy = SignedDyadic.sum(gasNodeEnergy);
			ThermalRegionStateCodec.ManifestData data = new ThermalRegionStateCodec.ManifestData(capabilities,
					thermal.thermalRegistryFingerprint(), thermal.insertionTemperatureK(),
					particles.particleCount(), allActiveEnergy.length, allDepositEnergy.length,
					thermal.coherentGroups().size(), gasNodeEnergy.length,
					liquidEnergy, activeEnergy, depositEnergy, coherentEnergy, gasEnergy,
					liquidEnergy.add(activeEnergy).add(depositEnergy).add(coherentEnergy).add(gasEnergy),
					thermal.audit(), List.copyOf(ledgers), gasNodeEnergyBits);
			manifestExtensions.add(new RegionExtension(ThermalRegionStateCodec.MANIFEST_EXTENSION,
					codec.encodeManifest(data)));
			FiniteThermalDeviceInventory finiteDevices = source.finiteThermalDevices(
					thermal.thermalRegistryFingerprint());
			if (!finiteDevices.devices().isEmpty()) manifestExtensions.add(new RegionExtension(
					FiniteThermalDeviceCodec.MANIFEST_EXTENSION,
					new FiniteThermalDeviceCodec().encode(finiteDevices)));
			if (particles.layout().requiresThermal()) manifestExtensions.add(new RegionExtension(
					ThermalBuoyancyCodec.MANIFEST_EXTENSION, new ThermalBuoyancyCodec().encodeCurrent()));
			PendingPhaseVolumeState pendingPhaseVolume = source.pendingPhaseVolume();
			if (!pendingPhaseVolume.isEmpty()) manifestExtensions.add(new RegionExtension(
					PendingPhaseVolumeCodec.MANIFEST_EXTENSION,
					new PendingPhaseVolumeCodec().encode(pendingPhaseVolume)));
		}
		RegionManifest unsigned = new RegionManifest(particles.layout(), identity.regionId(), identity.dimensionId(), checkpointGeneration,
				identity.networkGeneration(), geometry.origin(), geometry.sizeX(), geometry.sizeY(), geometry.sizeZ(), geometry.solverConfig(),
				geometry.reservedMarkerCapacity(), source.runtimeIntent(), geometry.footprint(), particles.particleCount(),
				Double.doubleToRawLongBits(sumVolumes(positionVolume)), particles.materialPalette(),
				particles.layout().hasMaterials() ? Double.doubleToRawLongBits(sum(particles.markerMass())) : 0L,
				materialLedgers, descriptors, List.copyOf(manifestExtensions), particles.registryFingerprint(),
				particles.hasChemicalState() ? mmm.chemistry.ChemicalLimits.QUANTA_PER_AU : 0L,
				particles.speciesPalette(), particles.chemicalMixtures(), particles.chemicalSpeciesTotals(),
				particles.chemicalResidualLedgerBits(), 0L);
		return new RegionCheckpoint(withChecksum(unsigned), sectionStates);
	}

	private static void requireCaptureable(RegionCheckpointSource source, long checkpointGeneration) throws IOException {
		source.identity(checkpointGeneration);
	}
	private static RegionSectionState withChecksum(RegionSectionState section) {
		long checksum = RegionStateIntegrity.sectionChecksum(section);
		return new RegionSectionState(section.layout(), section.regionId(), section.dimensionId(), section.checkpointGeneration(),
				section.sectionX(), section.sectionY(), section.sectionZ(), section.localStartX(), section.localStartY(), section.localStartZ(),
				section.sizeX(), section.sizeY(), section.sizeZ(), section.markerOrdinals(), section.positionVolume(),
				section.velocities(), section.markerMass(), section.materialPaletteIndex(), section.solidMask(), section.extensions(),
				section.chemicalMixtureIndex(), section.indicator(), section.chemicalSpeciesTotals(),
				section.chemicalResidualLedgerBits(), checksum);
	}

	private static RegionManifest withChecksum(RegionManifest manifest) {
		long checksum = RegionStateIntegrity.manifestChecksum(manifest);
		return new RegionManifest(manifest.layout(), manifest.regionId(), manifest.dimensionId(), manifest.checkpointGeneration(), manifest.networkGeneration(),
				manifest.origin(), manifest.sizeX(), manifest.sizeY(), manifest.sizeZ(), manifest.solverConfig(),
				manifest.reservedMarkerCapacity(), manifest.runtime(), manifest.footprint(), manifest.totalMarkerCount(),
				manifest.totalVolumeBits(), manifest.materialPalette(), manifest.totalMassBits(), manifest.materialLedgers(),
				manifest.sections(), manifest.extensions(), manifest.registryFingerprint(), manifest.auScale(),
				manifest.speciesPalette(), manifest.chemicalMixtures(), manifest.chemicalSpeciesTotals(),
				manifest.chemicalResidualLedgerBits(), checksum);
	}

	private static List<RegionManifest.MaterialLedger> materialLedgers(CapturedParticles particles) {
		if (!particles.layout().hasMaterials()) return List.of();
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

	private static double[] toDoubles(long[] bits) {
		double[] result = new double[bits.length];
		for (int index = 0; index < bits.length; index++) {
			result[index] = Double.longBitsToDouble(bits[index]);
		}
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

	private static RegionSectionState.SectionKey sectionKey(RegionGeometry geometry, float x, float y, float z) {
		return new RegionSectionState.SectionKey(
				Math.floorDiv((int) Math.floor(geometry.origin().x() + x), 16),
				Math.floorDiv((int) Math.floor(geometry.origin().y() + y), 16),
				Math.floorDiv((int) Math.floor(geometry.origin().z() + z), 16));
	}

	private static GasRegionStateCodec.ManifestData gasManifest(
			GasStateSnapshot snapshot, List<RegionSectionState> sections, boolean chemicalState
	) throws IOException {
		GasRegionStateCodec codec = new GasRegionStateCodec();
		ArrayList<GasRegionStateCodec.SectionLedger> ledgers = new ArrayList<>(sections.size());
		for (RegionSectionState section : sections) {
			GasRegionStateCodec.SectionData data = codec.decodeSection(
					requiredExtension(section.extensions(), GasRegionStateCodec.SECTION_EXTENSION).payload(),
					Math.multiplyExact(Math.multiplyExact(section.sizeX(), section.sizeY()), section.sizeZ()),
					snapshot.nodes().size());
			int sealed = 0;
			for (int value : data.pieceNodeOrdinal()) if (value > 0) sealed++;
			ledgers.add(new GasRegionStateCodec.SectionLedger(section.key(), sealed));
		}
		int capabilities = GasRegionStateCodec.CAP_MATERIALS | GasRegionStateCodec.CAP_GAS
				| (chemicalState ? GasRegionStateCodec.CAP_CHEMISTRY : 0);
		if (chemicalState != !snapshot.chemicalRegistryFingerprint().isEmpty()) {
			throw new IOException("Gas snapshot chemical identity disagrees with liquid generation");
		}
		return new GasRegionStateCodec.ManifestData(capabilities, snapshot.registryFingerprint(),
				snapshot.chemicalRegistryFingerprint(), snapshot.nodes().stream()
				.map(node -> new GasRegionStateCodec.NodeData(node.topologyKey(), node.inventory())).toList(),
				snapshot.externalSource(), snapshot.escapedSink(), snapshot.massRoundoff(), snapshot.residualRoundoff(),
				List.copyOf(ledgers));
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
			RegionStateLayout layout,
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

}

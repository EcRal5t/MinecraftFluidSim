package cc.ecisr.mmm.simulation.persistence;

import cc.ecisr.mmm.model.region.RegionCheckpoint;
import cc.ecisr.mmm.model.region.RegionExtension;
import cc.ecisr.mmm.model.region.RegionManifest;
import cc.ecisr.mmm.model.region.RegionSectionState;
import cc.ecisr.mmm.model.region.RegionStateLayout;

import cc.ecisr.mmm.fluid.FlipParticleState;
import cc.ecisr.mmm.fluid.FlipMaterialParticleState;
import cc.ecisr.mmm.fluid.FlipChemicalParticleState;
import cc.ecisr.mmm.fluid.FlipThermalParticleState;
import cc.ecisr.mmm.fluid.PendingPhaseVolumeState;
import cc.ecisr.mmm.fluid.FluidMaterial;
import cc.ecisr.mmm.fluid.air.AirNetwork;
import cc.ecisr.mmm.fluid.air.GasComponentAmount;
import cc.ecisr.mmm.fluid.air.GasInventory;
import cc.ecisr.mmm.fluid.air.GasRegistry;
import cc.ecisr.mmm.fluid.air.GasStateSnapshot;
import cc.ecisr.mmm.chemistry.ChemicalRegistry;
import cc.ecisr.mmm.chemistry.ChemicalAuditLedgerCodec;
import cc.ecisr.mmm.chemistry.ChemicalTransferLedger;
import cc.ecisr.mmm.granular.GranularLimits;
import cc.ecisr.mmm.granular.GranularRegistry;
import cc.ecisr.mmm.granular.GranularStateSnapshot;
import cc.ecisr.mmm.granular.SolidVolume;
import cc.ecisr.mmm.units.SignedDyadic;
import cc.ecisr.mmm.thermal.CoherentPhaseStore;
import cc.ecisr.mmm.thermal.GasThermalModel;
import cc.ecisr.mmm.thermal.FiniteThermalDeviceInventory;
import cc.ecisr.mmm.thermal.ThermalRegistry;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;


/** Reconstructs immutable solver truth from one validated logical checkpoint. */
public final class RegionCheckpointRestorer {
	private static final String CHEMICAL_AUDIT_EXTENSION = "mmm:chemical_audit";
	private final RegionManifest manifest;
	private final List<RegionSectionState> sections;
	private boolean validated;

	public RegionCheckpointRestorer(RegionCheckpoint checkpoint) {
		this.manifest = checkpoint.manifest();
		this.sections = checkpoint.sections();
	}

	public FlipParticleState reconstruct() throws IOException {
		if (manifest.layout() != RegionStateLayout.PURE) {
			throw new IOException("Material region state cannot use the pure-water reconstruction path");
		}
		ReconstructedParticles particles = reconstructParticles();
		double volume = sumVolumes(particles.positionVolume());
		return new FlipParticleState(manifest.sizeX(), manifest.sizeY(), manifest.sizeZ(), manifest.solverConfig().cellSize(),
				manifest.totalMarkerCount(), (float) volume, particles.positionVolume(), particles.velocities(), particles.solidMask());
	}

	public FlipMaterialParticleState reconstructMaterial() throws IOException {
		if (manifest.layout() != RegionStateLayout.MATERIAL
				&& !((manifest.layout() == RegionStateLayout.GRANULAR || manifest.layout() == RegionStateLayout.THERMAL)
				&& !manifest.hasChemicalState())) {
			throw new IOException("This region-state cannot use the material reconstruction path");
		}
		ReconstructedParticles particles = reconstructParticles();
		return new FlipMaterialParticleState(manifest.sizeX(), manifest.sizeY(), manifest.sizeZ(), manifest.solverConfig().cellSize(),
				manifest.totalMarkerCount(), manifest.totalVolume(), manifest.totalMass(), manifest.materialPalette(),
				particles.positionVolume(), particles.velocities(), particles.solidMask(), particles.markerMass(),
				particles.materialPaletteIndex());
	}

	public GasStateSnapshot reconstructGas(GasRegistry registry, ChemicalRegistry chemicalRegistry) throws IOException {
		if (!manifest.hasGasState()) throw new IOException("This region-state has no gas state");
		if (registry == null) throw new IOException("Authoritative gas registry is unavailable");
		validateCheckpoint();
		return reconstructGasPayload(registry, chemicalRegistry);
	}

	public FlipThermalParticleState reconstructThermal(ThermalRegistry registry) throws IOException {
		if (!manifest.hasThermalState()) throw new IOException("This region-state has no thermal state");
		if (registry == null) throw new IOException("Authoritative thermal registry is unavailable");
		validateCheckpoint();
		ThermalRegionStateCodec codec = new ThermalRegionStateCodec();
		ThermalRegionStateCodec.ManifestData thermal = codec.decodeManifest(
				requiredExtension(manifest.extensions(), ThermalRegionStateCodec.MANIFEST_EXTENSION).payload());
		if (!thermal.registryFingerprint().equals(registry.fingerprint())) {
			throw new IOException("Region-state thermal registry fingerprint mismatch");
		}
		boolean granularCapability = (thermal.capabilities() & ThermalRegionStateCodec.CAP_GRANULAR) != 0;
		GranularRegionStateCodec granularCodec = new GranularRegionStateCodec();
		GranularRegionStateCodec.ManifestData granular = granularCapability
				? granularCodec.decodeManifest(requiredExtension(manifest.extensions(),
				GranularRegionStateCodec.MANIFEST_EXTENSION).payload()) : null;
		double[] energy = new double[manifest.totalMarkerCount()];
		boolean[] seen = new boolean[energy.length];
		double[] activeEnergy = new double[thermal.activeParcelCount()];
		boolean[] seenActive = new boolean[activeEnergy.length];
		ArrayList<ThermalDepositEnergy> deposits = new ArrayList<>(thermal.depositGroupCount());
		ArrayList<CoherentPhaseStore.Group> coherent = new ArrayList<>(thermal.coherentGroupCount());
		for (RegionSectionState section : sections) {
			GranularRegionStateCodec.SectionData granularSection = granularCapability
					? granularCodec.decodeSection(requiredExtension(section.extensions(),
					GranularRegionStateCodec.SECTION_EXTENSION).payload(), granular.activeBudget(),
					granular.depositGroupBudget())
					: new GranularRegionStateCodec.SectionData(List.of(), List.of());
			ThermalRegionStateCodec.SectionData data = codec.decodeSection(
					requiredExtension(section.extensions(), ThermalRegionStateCodec.SECTION_EXTENSION).payload(),
					section.markerCount(), granularSection.active().size(), granularSection.deposits().size());
			long[] bits = data.liquidMarkerEnergyBits(); int[] ordinals = section.markerOrdinals();
			if (bits.length != ordinals.length
					|| data.activeParcelEnergyBits().length != granularSection.active().size()
					|| data.depositGroupEnergyBits().length != granularSection.deposits().size()) {
				throw new IOException("Thermal carrier ordinal cardinality mismatch");
			}
			for (int index = 0; index < ordinals.length; index++) {
				int ordinal = ordinals[index];
				if (ordinal < 0 || ordinal >= energy.length || seen[ordinal]) {
					throw new IOException("Duplicate or invalid thermal marker ordinal");
				}
				seen[ordinal] = true; energy[ordinal] = Double.longBitsToDouble(bits[index]);
			}
			long[] activeBits = data.activeParcelEnergyBits();
			for (int index = 0; index < activeBits.length; index++) {
				int ordinal = granularSection.active().get(index).ordinal();
				if (ordinal < 0 || ordinal >= activeEnergy.length || seenActive[ordinal]) {
					throw new IOException("Duplicate or invalid granular thermal active ordinal");
				}
				seenActive[ordinal] = true; activeEnergy[ordinal] = Double.longBitsToDouble(activeBits[index]);
			}
			long[] depositBits = data.depositGroupEnergyBits();
			for (int index = 0; index < depositBits.length; index++) deposits.add(new ThermalDepositEnergy(
					granularSection.deposits().get(index), Double.longBitsToDouble(depositBits[index])));
			for (ThermalRegionStateCodec.CoherentEntry group : data.coherentGroups()) {
				coherent.add(new CoherentPhaseStore.Group(group.cell(), group.phaseId(),
						group.occupiedVolumeSvq(), group.mass(), group.composition(), group.internalEnergy()));
			}
		}
		for (boolean value : seen) if (!value) throw new IOException("Missing thermal marker ordinal");
		for (boolean value : seenActive) if (!value) throw new IOException("Missing granular thermal active ordinal");
		deposits.sort(ThermalDepositEnergy.ORDER);
		double[] depositEnergy = new double[deposits.size()];
		for (int index = 0; index < depositEnergy.length; index++) depositEnergy[index] = deposits.get(index).energy();
		coherent.sort(Comparator.comparingInt(CoherentPhaseStore.Group::cell)
				.thenComparing(CoherentPhaseStore.Group::phaseId));
		try {
			double[] gasEnergy = toDoubles(thermal.gasNodeEnergyBits());
			return new FlipThermalParticleState(manifest.sizeX(), manifest.sizeY(), manifest.sizeZ(),
					manifest.solverConfig().cellSize(), manifest.totalMarkerCount(), thermal.registryFingerprint(),
					energy, activeEnergy, depositEnergy, coherent, gasEnergy, thermal.audit());
		} catch (IllegalArgumentException invalid) {
			throw new IOException("Invalid region-state thermal inventory: " + invalid.getMessage(), invalid);
		}
	}

	/** Sparse absence is the canonical empty record; presence is valid only in a complete thermal generation. */
	public PendingPhaseVolumeState reconstructPendingPhaseVolume() throws IOException {
		int cellCount = Math.multiplyExact(Math.multiplyExact(manifest.sizeX(), manifest.sizeY()), manifest.sizeZ());
		if (!hasExtension(manifest.extensions(), PendingPhaseVolumeCodec.MANIFEST_EXTENSION)) {
			return new PendingPhaseVolumeState(cellCount, List.of());
		}
		if (manifest.layout() != RegionStateLayout.THERMAL) {
			throw new IOException("Pending phase volume is only valid in a thermal region-state");
		}
		return new PendingPhaseVolumeCodec().decode(requiredExtension(
				manifest.extensions(), PendingPhaseVolumeCodec.MANIFEST_EXTENSION).payload(), cellCount);
	}

	/** Sparse absence is the canonical empty inventory; device energy exists only in thermal state. */
	public FiniteThermalDeviceInventory reconstructFiniteThermalDevices(ThermalRegistry registry) throws IOException {
		if (registry == null) throw new IOException("Authoritative thermal registry is unavailable");
		if (!hasExtension(manifest.extensions(), FiniteThermalDeviceCodec.MANIFEST_EXTENSION)) {
			return FiniteThermalDeviceInventory.empty(registry.fingerprint());
		}
		if (manifest.layout() != RegionStateLayout.THERMAL) {
			throw new IOException("Finite thermal devices are only valid in thermal region-state");
		}
		return new FiniteThermalDeviceCodec().decode(requiredExtension(
				manifest.extensions(), FiniteThermalDeviceCodec.MANIFEST_EXTENSION).payload(), registry);
	}

	/** Full thermal validation additionally binds every saved node U to its authoritative gas inventory. */
	public FlipThermalParticleState reconstructThermal(
			ThermalRegistry thermalRegistry, GasRegistry gasRegistry, ChemicalRegistry chemicalRegistry
	) throws IOException {
		FlipThermalParticleState thermal = reconstructThermal(thermalRegistry);
		GasStateSnapshot gas = reconstructGas(gasRegistry, chemicalRegistry);
		double[] energy = thermal.copyGasNodeEnergy();
		if (energy.length != gas.nodes().size()) {
			throw new IOException("Region-state gas energy/inventory cardinality mismatch");
		}
		try {
			GasThermalModel model = new GasThermalModel(thermalRegistry, gasRegistry);
			for (int node = 0; node < energy.length; node++) {
				model.requireInventoryEnergy(gas.nodes().get(node).inventory(), energy[node]);
			}
		} catch (IllegalArgumentException invalid) {
			throw new IOException("Invalid region-state gas thermal inventory: " + invalid.getMessage(), invalid);
		}
		return thermal;
	}

	public FlipChemicalParticleState reconstructChemical(ChemicalRegistry registry) throws IOException {
		if (!manifest.hasChemicalState()) throw new IOException("This region-state has no chemical state");
		if (registry == null || !manifest.registryFingerprint().equals(registry.fingerprint())
				|| !manifest.speciesPalette().equals(registry.species().stream()
				.map(cc.ecisr.mmm.chemistry.ChemicalSpecies::id).toList())) {
			throw new IOException("Chemical region registry fingerprint/palette mismatch");
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
		if (!hasExtension(manifest.extensions(), GranularRegionStateCodec.MANIFEST_EXTENSION)) {
			throw new IOException("This region-state has no granular state");
		}
		if (registry == null) throw new IOException("Authoritative granular registry is unavailable");
		validateCheckpoint();
		GranularRegionStateCodec codec = new GranularRegionStateCodec();
		GranularRegionStateCodec.ManifestData granular = codec.decodeManifest(
				requiredExtension(manifest.extensions(), GranularRegionStateCodec.MANIFEST_EXTENSION).payload());
		if (!granular.registryFingerprint().equals(registry.fingerprint())
				|| !granular.materialPalette().equals(registry.materials().stream()
				.map(cc.ecisr.mmm.granular.ParticulateMaterial::id).toList())) {
			throw new IOException("Region-state granular registry fingerprint/palette mismatch");
		}
		boolean reactivePayload = !granular.solidCompositions().isEmpty()
				|| granular.externalSource().requiresChemistry() || granular.escapedSink().requiresChemistry();
		if (reactivePayload
				&& !granular.chemicalRegistryFingerprint().equals(registry.chemicalRegistryFingerprint())) {
			throw new IOException("Region-state reactive granular registry dependency mismatch");
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
			throw new IOException("Invalid region-state granular inventory: " + invalid.getMessage(), invalid);
		}
	}

	private record ThermalDepositEnergy(GranularStateSnapshot.DepositGroup group, double energy) {
		private static final Comparator<ThermalDepositEnergy> ORDER = Comparator
				.comparingInt((ThermalDepositEnergy value) -> value.group().cell())
				.thenComparingInt(value -> value.group().materialPaletteIndex())
				.thenComparingInt(value -> value.group().solidCompositionIndex());
	}

	private ChemicalAuditLedgerCodec.AuditLedgers decodeChemicalAuditExtension() throws IOException {
		RegionExtension found = null;
		for (RegionExtension extension : manifest.extensions()) {
			if (!CHEMICAL_AUDIT_EXTENSION.equals(extension.type())) continue;
			if (found != null) throw new IOException("Duplicate chemical audit ledger");
			found = extension;
		}
		if (found == null) throw new IOException("Chemical audit ledger is missing");
		return ChemicalAuditLedgerCodec.decode(found.payload());
	}

	private ReconstructedParticles reconstructParticles() throws IOException {
		validateCheckpoint();
		int count = manifest.totalMarkerCount();
		float[] positions = new float[Math.multiplyExact(count, 4)];
		float[] velocities = new float[Math.multiplyExact(count, 3)];
		double[] markerMass = manifest.layout().hasMaterials() ? new double[count] : new double[0];
		int[] materialPaletteIndex = manifest.layout().hasMaterials() ? new int[count] : new int[0];
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
				if (manifest.layout().hasMaterials()) {
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

	private GasStateSnapshot reconstructGasPayload(GasRegistry registry, ChemicalRegistry chemicalRegistry) throws IOException {
		GasRegionStateCodec codec = new GasRegionStateCodec();
		GasRegionStateCodec.ManifestData data = codec.decodeManifest(
				requiredExtension(manifest.extensions(), GasRegionStateCodec.MANIFEST_EXTENSION).payload());
		boolean chemicalCapability = (data.capabilities() & GasRegionStateCodec.CAP_CHEMISTRY) != 0;
		if (chemicalCapability != manifest.hasChemicalState()
				|| chemicalCapability && !data.chemicalRegistryFingerprint().equals(manifest.registryFingerprint())) {
			throw new IOException("Gas capability/chemical identity disagrees with the complete generation");
		}
		Map<RegionSectionState.SectionKey, GasRegionStateCodec.SectionLedger> ledgers = new HashMap<>();
		for (GasRegionStateCodec.SectionLedger ledger : data.sectionLedgers()) {
			if (ledgers.put(ledger.key(), ledger) != null) throw new IOException("Duplicate gas section ledger");
		}
		if (ledgers.size() != sections.size()) throw new IOException("Gas section ledger cardinality mismatch");
		int[] memberships = new int[Math.multiplyExact(Math.multiplyExact(manifest.sizeX(), manifest.sizeY()), manifest.sizeZ())];
		for (RegionSectionState section : sections) {
			GasRegionStateCodec.SectionData sectionData = codec.decodeSection(
					requiredExtension(section.extensions(), GasRegionStateCodec.SECTION_EXTENSION).payload(),
					Math.multiplyExact(Math.multiplyExact(section.sizeX(), section.sizeY()), section.sizeZ()), data.nodes().size());
			int sealed = 0, source = 0;
			int[] slice = sectionData.cellNodeOrdinal();
			for (int z = 0; z < section.sizeZ(); z++) for (int y = 0; y < section.sizeY(); y++) for (int x = 0; x < section.sizeX(); x++) {
				int value = slice[source++];
				if (value > 0) sealed++;
				int cell = section.localStartX() + x + manifest.sizeX() *
						(section.localStartY() + y + manifest.sizeY() * (section.localStartZ() + z));
				memberships[cell] = value;
			}
			GasRegionStateCodec.SectionLedger ledger = ledgers.remove(section.key());
			if (ledger == null || ledger.sealedCellCount() != sealed) {
				throw new IOException("Gas section does not match its topology ledger");
			}
		}
		if (!ledgers.isEmpty()) throw new IOException("Gas manifest names missing sections");
		List<GasStateSnapshot.Node> nodes = data.nodes().stream()
				.map(node -> new GasStateSnapshot.Node(node.topologyKey(), node.inventory())).toList();
		GasStateSnapshot snapshot;
		try {
			snapshot = new GasStateSnapshot(manifest.sizeX(), manifest.sizeY(), manifest.sizeZ(),
					data.registryFingerprint(), data.chemicalRegistryFingerprint(), nodes, memberships,
					data.externalSource(), data.escapedSink(),
					data.massRoundoff(), data.residualRoundoff());
			AirNetwork audit = new AirNetwork(registry == null
					? cc.ecisr.mmm.fluid.air.BuiltinGasDefinitions.createRegistry() : registry);
			double[] available = new double[nodes.size()]; java.util.Arrays.fill(available, 1.0);
			audit.restoreNodes(available, nodes.stream().map(GasStateSnapshot.Node::inventory).toList(),
					snapshot.externalSource(), snapshot.escapedSink(), snapshot.massRoundoff(), snapshot.residualRoundoff());
		} catch (IllegalArgumentException invalid) {
			throw new IOException("Invalid gas inventory/topology: " + invalid.getMessage(), invalid);
		}
		if (registry != null) {
			if (!snapshot.registryFingerprint().equals(registry.fingerprint())) {
				throw new IOException("Gas registry fingerprint does not match the world snapshot");
			}
			for (GasStateSnapshot.Node node : snapshot.nodes()) validateGasInventoryRegistry(node.inventory(), registry, chemicalRegistry);
			validateGasAuditRegistry(snapshot.externalSource(), registry, chemicalRegistry);
			validateGasAuditRegistry(snapshot.escapedSink(), registry, chemicalRegistry);
		}
		if (chemicalCapability) {
			if (chemicalRegistry == null || !data.chemicalRegistryFingerprint().equals(chemicalRegistry.fingerprint())) {
				if (registry != null) throw new IOException("Gas species require the authoritative chemical registry");
			} else if (!manifest.speciesPalette().equals(chemicalRegistry.species().stream()
					.map(cc.ecisr.mmm.chemistry.ChemicalSpecies::id).toList())) {
				throw new IOException("Gas chemical palette does not match the authoritative registry");
			}
		}
		return snapshot;
	}

	void validateGasLedgers() throws IOException { reconstructGasPayload(null, null); }

	private void validateCheckpoint() throws IOException {
		if (validated) return;
		new RegionCheckpointValidator(new RegionCheckpoint(manifest, sections)).validate();
		validated = true;
	}

	private void validateGranularSectionPosition(RegionSectionState section, float x, float y, float z) throws IOException {
		int sectionX = Math.floorDiv((int) Math.floor(manifest.origin().x() + x), 16);
		int sectionY = Math.floorDiv((int) Math.floor(manifest.origin().y() + y), 16);
		int sectionZ = Math.floorDiv((int) Math.floor(manifest.origin().z() + z), 16);
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

	private static void validateGasInventoryRegistry(
			GasInventory inventory, GasRegistry registry, ChemicalRegistry chemicalRegistry
	) throws IOException {
		try { registry.requireCompatible(inventory); }
		catch (IllegalArgumentException invalid) { throw new IOException(invalid.getMessage(), invalid); }
		for (GasComponentAmount amount : inventory.components()) validateGasSpecies(amount.species(), chemicalRegistry);
	}

	private static void validateGasAuditRegistry(
			cc.ecisr.mmm.fluid.air.GasAuditLedger ledger, GasRegistry registry, ChemicalRegistry chemicalRegistry
	) throws IOException {
		for (cc.ecisr.mmm.fluid.air.GasAuditLedger.Entry entry : ledger.entries()) {
			try { registry.requireCompatible(entry.componentId(), entry.species()); }
			catch (IllegalArgumentException invalid) { throw new IOException(invalid.getMessage(), invalid); }
			validateGasSpecies(entry.species(), chemicalRegistry);
		}
	}

	private static void validateGasSpecies(
			List<GasComponentAmount.SpeciesAmount> species, ChemicalRegistry registry
	) throws IOException {
		if (!species.isEmpty() && registry == null) throw new IOException("Gas species registry is unavailable");
		for (GasComponentAmount.SpeciesAmount amount : species) if (!registry.hasSpecies(amount.speciesId())) {
			throw new IOException("Unknown gas species " + amount.speciesId());
		}
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

	private static RegionExtension requiredExtension(List<RegionExtension> extensions, String type) throws IOException {
		RegionExtension found = null;
		for (RegionExtension extension : extensions) if (type.equals(extension.type())) {
			if (found != null) throw new IOException("Duplicate required region extension " + type);
			found = extension;
		}
		if (found == null) throw new IOException("Missing required region extension " + type);
		return found;
	}

	private static boolean hasExtension(List<RegionExtension> extensions, String type) {
		for (RegionExtension extension : extensions) if (type.equals(extension.type())) return true;
		return false;
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

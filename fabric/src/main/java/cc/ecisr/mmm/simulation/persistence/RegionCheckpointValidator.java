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


/** Enforces cross-section identity, ownership, extension and ledger closure. */
final class RegionCheckpointValidator {
	private static final String CHEMICAL_AUDIT_EXTENSION = "mmm:chemical_audit";
	private final RegionManifest manifest;
	private final List<RegionSectionState> sections;

	RegionCheckpointValidator(RegionCheckpoint checkpoint) {
		this.manifest = checkpoint.manifest();
		this.sections = checkpoint.sections();
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
					|| section.layout() != manifest.layout()) throw new IOException("Mixed section ownership or layout");
			if (RegionStateIntegrity.sectionChecksum(section) != section.checksum()) throw new IOException("Section checksum mismatch");
			validateSectionOwnership(section);
			RegionManifest.SectionDescriptor descriptor = expected.get(section.key());
			long volumeBits = Double.doubleToRawLongBits(sumVolumes(section.positionVolume()));
			long massBits = manifest.layout().hasMaterials() ? Double.doubleToRawLongBits(sum(section.markerMass())) : 0L;
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
		if (manifest.layout().hasMaterials()) validateMaterialLedgers();
		if (manifest.hasChemicalState()) validateChemicalLedgers();
		boolean gasPayload = hasExtension(manifest.extensions(), GasRegionStateCodec.MANIFEST_EXTENSION);
		boolean pendingPhaseVolumePayload = hasExtension(manifest.extensions(),
				PendingPhaseVolumeCodec.MANIFEST_EXTENSION);
		boolean thermalBuoyancyPayload = hasExtension(manifest.extensions(),
				ThermalBuoyancyCodec.MANIFEST_EXTENSION);
		boolean finiteDevicePayload = hasExtension(manifest.extensions(),
				FiniteThermalDeviceCodec.MANIFEST_EXTENSION);
		for (RegionSectionState section : sections) if (hasExtension(section.extensions(),
				PendingPhaseVolumeCodec.MANIFEST_EXTENSION)) {
			throw new IOException("Pending-volume evidence is manifest-only");
		}
		if (pendingPhaseVolumePayload) validatePendingPhaseVolumeCells();
		if (finiteDevicePayload) {
			if (manifest.layout() != RegionStateLayout.THERMAL) {
				throw new IOException("Finite thermal devices are only valid in thermal region-state");
			}
			new FiniteThermalDeviceCodec().decode(requiredExtension(manifest.extensions(),
					FiniteThermalDeviceCodec.MANIFEST_EXTENSION).payload(), null);
		}
		if (manifest.layout() == RegionStateLayout.THERMAL) {
			if (!thermalBuoyancyPayload) throw new IOException("Thermal region state requires buoyancy identity");
			new ThermalBuoyancyCodec().requireCurrent(requiredExtension(manifest.extensions(),
					ThermalBuoyancyCodec.MANIFEST_EXTENSION).payload());
		} else if (thermalBuoyancyPayload) {
			throw new IOException("Thermal buoyancy identity is only valid in thermal region state");
		}
		if (gasPayload && manifest.layout() != RegionStateLayout.THERMAL) {
			throw new IOException("Gas payload is only valid in a thermal region-state");
		} else if (manifest.layout() == RegionStateLayout.THERMAL && (!gasPayload
				|| !hasExtension(manifest.extensions(), ThermalRegionStateCodec.MANIFEST_EXTENSION))) {
			throw new IOException("Complete thermal region-state requires gas and thermal payloads");
		}
		if (gasPayload) validateGasLedgers();
		if (hasExtension(manifest.extensions(), GranularRegionStateCodec.MANIFEST_EXTENSION)) validateGranularLedgers();
		if (manifest.hasThermalState()) validateThermalLedgers();
	}

	private void validatePendingPhaseVolumeCells() throws IOException {
		PendingPhaseVolumeState pending = new RegionCheckpointRestorer(
				new RegionCheckpoint(manifest, sections)).reconstructPendingPhaseVolume();
		boolean[] carrier = new boolean[pending.cellCount()];
		float cellSize = manifest.solverConfig().cellSize();
		for (RegionSectionState section : sections) {
			float[] positionVolume = section.positionVolume();
			for (int marker = 0; marker < section.markerCount(); marker++) {
				int base = marker * 4;
				int x = (int) Math.floor(positionVolume[base] / cellSize);
				int y = (int) Math.floor(positionVolume[base + 1] / cellSize);
				int z = (int) Math.floor(positionVolume[base + 2] / cellSize);
				if (x < 0 || x >= manifest.sizeX() || y < 0 || y >= manifest.sizeY()
						|| z < 0 || z >= manifest.sizeZ()) {
					throw new IOException("Pending phase-volume validation found an out-of-domain marker");
				}
				carrier[x + manifest.sizeX() * (y + manifest.sizeY() * z)] = true;
			}
			ThermalRegionStateCodec.SectionData thermal = new ThermalRegionStateCodec().decodeSection(
					requiredExtension(section.extensions(), ThermalRegionStateCodec.SECTION_EXTENSION).payload(),
					section.markerCount(), GranularLimits.MAX_ACTIVE_PARCELS_PER_REGION,
					GranularLimits.MAX_DEPOSIT_GROUPS_PER_REGION);
			for (ThermalRegionStateCodec.CoherentEntry group : thermal.coherentGroups()) carrier[group.cell()] = true;
			if (hasExtension(section.extensions(), GranularRegionStateCodec.SECTION_EXTENSION)) {
				GranularRegionStateCodec.SectionData granular = new GranularRegionStateCodec().decodeSection(
						requiredExtension(section.extensions(), GranularRegionStateCodec.SECTION_EXTENSION).payload(),
						GranularLimits.MAX_ACTIVE_PARCELS_PER_REGION, GranularLimits.MAX_DEPOSIT_GROUPS_PER_REGION);
				for (GranularRegionStateCodec.ActiveEntry entry : granular.active()) {
					GranularStateSnapshot.ActiveParcel parcel = entry.parcel();
					int x = (int) Math.floor(parcel.x() / cellSize);
					int y = (int) Math.floor(parcel.y() / cellSize);
					int z = (int) Math.floor(parcel.z() / cellSize);
					if (x < 0 || x >= manifest.sizeX() || y < 0 || y >= manifest.sizeY()
							|| z < 0 || z >= manifest.sizeZ()) {
						throw new IOException("Pending phase-volume validation found an out-of-domain granular parcel");
					}
					carrier[x + manifest.sizeX() * (y + manifest.sizeY() * z)] = true;
				}
				for (GranularStateSnapshot.DepositGroup group : granular.deposits()) carrier[group.cell()] = true;
			}
		}
		for (PendingPhaseVolumeState.Entry entry : pending.entries()) if (!carrier[entry.cell()]) {
			throw new IOException("Pending phase volume names a cell without a thermal carrier");
		}
	}

	private void validateGasLedgers() throws IOException {
		new RegionCheckpointRestorer(new RegionCheckpoint(manifest, sections)).validateGasLedgers();
	}

	private void validateThermalLedgers() throws IOException {
		ThermalRegionStateCodec codec = new ThermalRegionStateCodec();
		ThermalRegionStateCodec.ManifestData expected = codec.decodeManifest(
				requiredExtension(manifest.extensions(), ThermalRegionStateCodec.MANIFEST_EXTENSION).payload());
		boolean chemicalCapability = (expected.capabilities() & ThermalRegionStateCodec.CAP_CHEMISTRY) != 0;
		boolean granularCapability = (expected.capabilities() & ThermalRegionStateCodec.CAP_GRANULAR) != 0;
		boolean gasCapability = (expected.capabilities() & ThermalRegionStateCodec.CAP_GAS) != 0;
		boolean granularPayload = hasExtension(manifest.extensions(), GranularRegionStateCodec.MANIFEST_EXTENSION);
		boolean gasPayload = hasExtension(manifest.extensions(), GasRegionStateCodec.MANIFEST_EXTENSION);
		if (chemicalCapability != manifest.hasChemicalState() || granularCapability != granularPayload
				|| gasCapability != gasPayload) {
			throw new IOException("Thermal region-state capabilities disagree with complete payload");
		}
		GasRegionStateCodec.ManifestData gas = new GasRegionStateCodec().decodeManifest(
				requiredExtension(manifest.extensions(), GasRegionStateCodec.MANIFEST_EXTENSION).payload());
		if (expected.gasNodeCount() != gas.nodes().size()) {
			throw new IOException("Thermal gas energy ordinals disagree with gas node inventory");
		}
		Map<RegionSectionState.SectionKey, ThermalRegionStateCodec.SectionLedger> ledgers = new HashMap<>();
		for (ThermalRegionStateCodec.SectionLedger ledger : expected.sectionLedgers()) {
			if (ledgers.put(ledger.key(), ledger) != null) throw new IOException("Duplicate thermal section ledger");
		}
		if (ledgers.size() != sections.size()) throw new IOException("Thermal section ledger cardinality mismatch");
		int liquidCount = 0, activeCount = 0, depositCount = 0, coherentCount = 0;
		SignedDyadic liquidEnergy = SignedDyadic.ZERO, activeEnergy = SignedDyadic.ZERO;
		SignedDyadic depositEnergy = SignedDyadic.ZERO, coherentEnergy = SignedDyadic.ZERO;
		for (RegionSectionState section : sections) {
			ThermalRegionStateCodec.SectionData data = codec.decodeSection(
					requiredExtension(section.extensions(), ThermalRegionStateCodec.SECTION_EXTENSION).payload(),
					section.markerCount(), Integer.MAX_VALUE, Integer.MAX_VALUE);
			if (granularPayload) {
				GranularRegionStateCodec.SectionData granular = new GranularRegionStateCodec().decodeSection(
						requiredExtension(section.extensions(), GranularRegionStateCodec.SECTION_EXTENSION).payload(),
						GranularLimits.MAX_ACTIVE_PARCELS_PER_REGION, GranularLimits.MAX_DEPOSIT_GROUPS_PER_REGION);
				if (data.activeParcelEnergyBits().length != granular.active().size()
						|| data.depositGroupEnergyBits().length != granular.deposits().size()) {
					throw new IOException("Thermal section does not bind one energy to each granular carrier");
				}
			}
			ThermalRegionStateCodec.SectionLedger ledger = ledgers.remove(section.key());
			if (ledger == null || ledger.liquidMarkerCount() != data.liquidMarkerEnergyBits().length
					|| ledger.activeParcelCount() != data.activeParcelEnergyBits().length
					|| ledger.depositGroupCount() != data.depositGroupEnergyBits().length
					|| ledger.coherentGroupCount() != data.coherentGroups().size() || !ledger.energy().equals(data.energy())
					|| data.liquidMarkerEnergyBits().length != section.markerCount()) {
				throw new IOException("Thermal section does not match its manifest ledger");
			}
			for (ThermalRegionStateCodec.CoherentEntry group : data.coherentGroups()) {
				validateGranularDepositSection(section, group.cell());
				if (group.composition() != null) {
					if (!chemicalCapability) throw new IOException("Coherent composition lacks chemistry capability");
					for (CoherentPhaseStore.Component component : group.composition().components()) {
						if (!manifest.speciesPalette().contains(component.speciesId())) {
							throw new IOException("Coherent composition species is outside the stable palette");
						}
					}
				}
			}
			liquidCount = Math.addExact(liquidCount, data.liquidMarkerEnergyBits().length);
			activeCount = Math.addExact(activeCount, data.activeParcelEnergyBits().length);
			depositCount = Math.addExact(depositCount, data.depositGroupEnergyBits().length);
			coherentCount = Math.addExact(coherentCount, data.coherentGroups().size());
			liquidEnergy = liquidEnergy.add(SignedDyadic.sum(toDoubles(data.liquidMarkerEnergyBits())));
			activeEnergy = activeEnergy.add(SignedDyadic.sum(toDoubles(data.activeParcelEnergyBits())));
			depositEnergy = depositEnergy.add(SignedDyadic.sum(toDoubles(data.depositGroupEnergyBits())));
			for (ThermalRegionStateCodec.CoherentEntry group : data.coherentGroups()) {
				coherentEnergy = coherentEnergy.add(SignedDyadic.of(group.internalEnergy()));
			}
		}
		if (!ledgers.isEmpty() || liquidCount != expected.liquidMarkerCount()
				|| activeCount != expected.activeParcelCount() || depositCount != expected.depositGroupCount()
				|| coherentCount != expected.coherentGroupCount() || !liquidEnergy.equals(expected.liquidEnergy())
				|| !activeEnergy.equals(expected.activeEnergy()) || !depositEnergy.equals(expected.depositEnergy())
				|| !coherentEnergy.equals(expected.coherentEnergy())) {
			throw new IOException("Thermal carrier ledger does not close");
		}
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
					throw new IOException("Invalid chemical marker ordinal/mixture index");
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
			if (!seen[ordinal]) throw new IOException("Missing chemical marker ordinal");
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
			throw new IOException("Granular region capabilities do not match the complete payload");
		}
		if (expected.materialLedgers().size() != expected.materialPalette().size()
				|| expected.sectionLedgers().size() != sections.size()) {
			throw new IOException("Granular region ledger cardinality mismatch");
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
				throw new IOException("Granular section does not match its ledger");
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
			throw new IOException("Invalid granular region state: " + invalid.getMessage(), invalid);
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
		for (int index = 0; index < bits.length; index++) result[index] = Double.longBitsToDouble(bits[index]);
		return result;
	}

}

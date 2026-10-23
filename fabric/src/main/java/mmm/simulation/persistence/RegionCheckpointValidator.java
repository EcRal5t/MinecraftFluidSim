package mmm.simulation.persistence;

import mmm.model.region.RegionCheckpoint;
import mmm.model.region.RegionExtension;
import mmm.model.region.RegionManifest;
import mmm.model.region.RegionSectionState;
import mmm.model.region.RegionStateLayout;

import mmm.fluid.PendingPhaseMotionState;
import mmm.chemistry.ChemicalAuditLedgerCodec;
import mmm.granular.GranularLimits;
import mmm.granular.GranularStateSnapshot;
import mmm.units.SignedDyadic;
import mmm.thermal.CoherentPhaseStore;

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
		validateManifest(manifest);
		if (sections.size() != manifest.sections().size()) throw new IOException("Section count mismatch");
		Set<RegionSectionState.SectionKey> actual = new HashSet<>();
		int markerCount = 0;
		for (RegionSectionState section : sections) {
			if (!actual.add(section.key())) throw new IOException("Duplicate section payload");
			validateSection(section);
			markerCount = Math.addExact(markerCount, section.markerCount());
		}
		if (markerCount != manifest.totalMarkerCount()) {
			throw new IOException("Manifest marker count/budget mismatch");
		}
		validateCellAndFaceOwnership();
		if (manifest.layout().hasMaterials()) validateMaterialLedgers();
		if (manifest.hasChemicalState()) validateChemicalLedgers();
		boolean gasPayload = hasExtension(manifest.extensions(), GasRegionStateCodec.MANIFEST_EXTENSION);
		boolean pendingPhaseMotionPayload = hasExtension(manifest.extensions(),
				PendingPhaseMotionCodec.MANIFEST_EXTENSION);
		for (RegionSectionState section : sections) if (hasExtension(section.extensions(),
				PendingPhaseMotionCodec.MANIFEST_EXTENSION)) {
			throw new IOException("Pending-volume evidence is manifest-only");
		}
		if (pendingPhaseMotionPayload) validatePendingPhaseMotionCells();
		if (gasPayload) validateGasLedgers();
		if (hasExtension(manifest.extensions(), GranularRegionStateCodec.MANIFEST_EXTENSION)) validateGranularLedgers();
		if (manifest.hasThermalState()) validateThermalLedgers();
	}

	/** Checks the independently admitted manifest without allocating or reading section payloads. */
	static void validateManifest(RegionManifest manifest) throws IOException {
		if(RegionStateIntegrity.manifestChecksum(manifest)!=manifest.checksum()) throw new IOException("Manifest checksum mismatch");
		if(manifest.sections().isEmpty() || manifest.sections().size()>RegionStateCodec.MAX_SECTIONS)
			throw new IOException("Invalid manifest section count");
		RegionManifest.SectionDescriptor previous=null; long count=0;
		for(var descriptor:manifest.sections()) {
			if(previous!=null && previous.compareTo(descriptor)>=0) throw new IOException("Section descriptors are not strictly sorted");
			count+=descriptor.markerCount(); previous=descriptor;
		}
		if(count!=manifest.totalMarkerCount()) throw new IOException("Manifest marker count/budget mismatch");
		boolean gasPayload=hasExtension(manifest.extensions(),GasRegionStateCodec.MANIFEST_EXTENSION);
		boolean thermalBuoyancyPayload=hasExtension(manifest.extensions(),ThermalBuoyancyCodec.MANIFEST_EXTENSION);
		boolean finiteDevicePayload=hasExtension(manifest.extensions(),FiniteThermalDeviceCodec.MANIFEST_EXTENSION);
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
		if (manifest.layout() == RegionStateLayout.THERMAL && (!gasPayload
				|| !hasExtension(manifest.extensions(), ThermalRegionStateCodec.MANIFEST_EXTENSION))) {
			throw new IOException("Complete thermal region-state requires gas and thermal payloads");
		}
	}

	/** The same ownership and descriptor rules apply before a partial read enters resident memory. */
	void validateSection(RegionSectionState section) throws IOException {
		if (!section.regionId().equals(manifest.regionId()) || !section.dimensionId().equals(manifest.dimensionId())
				|| section.checkpointGeneration()!=manifest.checkpointGeneration() || section.layout()!=manifest.layout())
			throw new IOException("Mixed section ownership or layout");
		if (RegionStateIntegrity.sectionChecksum(section)!=section.checksum()) throw new IOException("Section checksum mismatch");
		validateSectionOwnership(section);
		var descriptor=manifest.sections().stream().filter(value->value.key().equals(section.key())).findFirst().orElse(null);
		if (descriptor==null || descriptor.markerCount()!=section.markerCount()
				|| descriptor.volumeBits()!=Double.doubleToRawLongBits(section.exactVolume().toDouble())
				|| descriptor.massUnits()!=section.massUnits() || descriptor.checksum()!=section.checksum())
			throw new IOException("Section does not match its manifest descriptor");
	}

	private void validatePendingPhaseMotionCells() throws IOException {
        new RegionCheckpointRestorer(new RegionCheckpoint(manifest,sections)).reconstructPendingPhaseMotion();
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
			if (ledger == null || ledger.liquidMarkerCount() != section.stockCount()
					|| ledger.activeParcelCount() != data.activeParcelEnergyBits().length
					|| ledger.depositGroupCount() != data.depositGroupEnergyBits().length
					|| ledger.coherentGroupCount() != data.coherentGroups().size() || !ledger.energy().equals(data.energy().add(liquidEnergy(section)))
					) {
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
			liquidCount = Math.addExact(liquidCount, section.stockCount());
			activeCount = Math.addExact(activeCount, data.activeParcelEnergyBits().length);
			depositCount = Math.addExact(depositCount, data.depositGroupEnergyBits().length);
			coherentCount = Math.addExact(coherentCount, data.coherentGroups().size());
			liquidEnergy = liquidEnergy.add(liquidEnergy(section));
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
		decodeChemicalAuditExtension();long[] totals=new long[manifest.speciesPalette().size()];long residual=0;
		for(var section:sections)for(var stock:section.liquidStocks()) {
			var mixture=stock.mixture();if(mixture==null)throw new IOException("Chemical cell has no species inventory");
			residual=Math.addExact(residual,mixture.massResidualUnits());
			for(int i=0;i<mixture.entryCount();i++){int species=mixture.speciesHandle(i);if(species>=totals.length)throw new IOException("Chemical handle is outside the pinned registry palette");totals[species]=Math.addExact(totals[species],mixture.amountAt(i));}
		}
		if(!Arrays.equals(totals,manifest.chemicalSpeciesTotals())||residual!=manifest.chemicalResidualLedgerUnits())throw new IOException("Cell chemical inventory summary differs");
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
		long[] activeMass = new long[materialCount], depositMass = new long[materialCount];
		for (RegionSectionState section : sections) {
			GranularRegionStateCodec.SectionData data = codec.decodeSection(
					requiredExtension(section.extensions(), GranularRegionStateCodec.SECTION_EXTENSION).payload(),
					expected.activeBudget(), expected.depositGroupBudget());
			long sectionActiveSvq = 0L, sectionDepositSvq = 0L;
			long sectionActiveMass = 0, sectionDepositMass = 0;
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
				sectionActiveMass = Math.addExact(sectionActiveMass, entry.parcel().massUnits());
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
				sectionDepositMass = Math.addExact(sectionDepositMass, group.massUnits());
				if (group.cell() != previousCell) { depositCells++; previousCell = group.cell(); }
			}
			GranularRegionStateCodec.SectionLedger ledger = sectionLedgers.remove(section.key());
			if (ledger == null || ledger.activeCount() != data.active().size()
					|| ledger.depositGroupCount() != data.deposits().size() || ledger.depositCellCount() != depositCells
					|| ledger.activeSvq() != sectionActiveSvq || ledger.depositSvq() != sectionDepositSvq
					|| ledger.activeMassUnits() != sectionActiveMass
					|| ledger.depositMassUnits() != sectionDepositMass) {
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
			activeMass[material] = Math.addExact(activeMass[material], parcel.massUnits());
		}
		for (GranularStateSnapshot.DepositGroup group : deposits) {
			int material = group.materialPaletteIndex(); depositCounts[material]++;
			depositSvq[material] = Math.addExact(depositSvq[material], group.solidVolumeSvq());
			depositMass[material] = Math.addExact(depositMass[material], group.massUnits());
		}
		long totalActiveSvq = 0L, totalDepositSvq = 0L;
		for (int material = 0; material < materialCount; material++) {
			GranularRegionStateCodec.MaterialLedger ledger = expected.materialLedgers().get(material);
			if (ledger.activeCount() != activeCounts[material] || ledger.activeSvq() != activeSvq[material]
					|| ledger.activeMassUnits() != activeMass[material]
					|| ledger.depositGroupCount() != depositCounts[material] || ledger.depositSvq() != depositSvq[material]
					|| ledger.depositMassUnits() != depositMass[material]) {
				throw new IOException("Granular material ledger mismatch at palette index " + material);
			}
			totalActiveSvq = Math.addExact(totalActiveSvq, activeSvq[material]);
			totalDepositSvq = Math.addExact(totalDepositSvq, depositSvq[material]);
		}
		long totalActiveMass = 0, totalDepositMass = 0;
		for (GranularStateSnapshot.ActiveParcel parcel : active) totalActiveMass = Math.addExact(totalActiveMass, parcel.massUnits());
		for (GranularStateSnapshot.DepositGroup group : deposits) totalDepositMass = Math.addExact(totalDepositMass, group.massUnits());
		if (expected.activeSvq() != totalActiveSvq || expected.depositSvq() != totalDepositSvq
				|| expected.activeMassUnits() != totalActiveMass
				|| expected.depositMassUnits() != totalDepositMass) {
			throw new IOException("Granular manifest total ledger mismatch");
		}
		try {
			new GranularStateSnapshot(manifest.sizeX(), manifest.sizeY(), manifest.sizeZ(), expected.registryFingerprint(),
					expected.chemicalRegistryFingerprint(), expected.materialPalette(), expected.solidCompositions(),
					expected.solidSpeciesTotals(), Arrays.asList(active), deposits,
					Math.addExact(totalActiveSvq, totalDepositSvq),
					GranularStateSnapshot.canonicalTotalMassUnits(Arrays.asList(active), deposits),
					expected.externalSource(), expected.escapedSink());
		} catch (IllegalArgumentException invalid) {
			throw new IOException("Invalid granular region state: " + invalid.getMessage(), invalid);
		}
	}

	private static SignedDyadic liquidEnergy(RegionSectionState section) {var total=SignedDyadic.ZERO;for(var stock:section.liquidStocks())total=total.add(SignedDyadic.of(stock.internalEnergy()));return total;}
	private void validateCellAndFaceOwnership() throws IOException {
		int nx=manifest.sizeX(),ny=manifest.sizeY(),nz=manifest.sizeZ(),cells=nx*ny*nz;
		boolean[] covered=new boolean[cells];
		boolean[][] faces={new boolean[(nx+1)*ny*nz],new boolean[nx*(ny+1)*nz],new boolean[nx*ny*(nz+1)]};
		SignedDyadic totalVolume=SignedDyadic.ZERO;long totalMass=0;
		var keys=new java.util.HashSet<mmm.inventory.LiquidCellInventory.Key>();
		for(var section:sections) {
			byte[] masks=section.solidMask();int local=0;
			for(int z=section.localStartZ();z<section.localStartZ()+section.sizeZ();z++)for(int y=section.localStartY();y<section.localStartY()+section.sizeY();y++)for(int x=section.localStartX();x<section.localStartX()+section.sizeX();x++) {
				int cell=x+nx*(y+ny*z);if(covered[cell])throw new IOException("Overlapping collision slices");covered[cell]=true;local++;
			}
			var componentVolume=new HashMap<Long,SignedDyadic>();
			for(var stock:section.liquidStocks()) {
				int cell=stock.key().cell();if(cell>=cells||!keys.add(stock.key())||!RegionCheckpointAssembler.sectionOfCell(manifest.geometry(),cell).equals(section.key()))throw new IOException("Liquid key is repeated or stored in the wrong section");
				int x=cell%nx-section.localStartX(),y=cell/nx%ny-section.localStartY(),z=cell/(nx*ny)-section.localStartZ();int mask=Byte.toUnsignedInt(masks[x+section.sizeX()*(y+section.sizeY()*z)]);
				boolean found=false;for(int support:mmm.inventory.LiquidCellInventory.connectedSupports(mask))found|=support==stock.key().supportMask();if(!found)throw new IOException("Liquid stock crosses a fixed collision component");
				long component=((long)cell<<8)|stock.key().supportMask();var amount=componentVolume.getOrDefault(component,SignedDyadic.ZERO).add(stock.volume());componentVolume.put(component,amount);
				if(amount.compareTo(SignedDyadic.of(Integer.bitCount(stock.key().supportMask())*.125))>0)throw new IOException("Liquid stock exceeds its collision component capacity");
				if(!manifest.hasChemicalState()&&stock.mixture()!=null)throw new IOException("Unbound chemical stock");
				if(manifest.materialPalette().isEmpty()&&stock.key().material()!=0)throw new IOException("Unbound liquid material");
				totalVolume=totalVolume.add(stock.volume());totalMass=Math.addExact(totalMass,stock.massUnits());
			}
			for(var face:section.faceMomentum()) {
				int axis=face.axis(),index=face.face();if(index>=faces[axis].length||faces[axis][index])throw new IOException("Repeated or out-of-grid MAC face");faces[axis][index]=true;
				int sx=nx+(axis==0?1:0),sy=ny+(axis==1?1:0);int x=Math.min(index%sx,nx-1),y=Math.min(index/sx%sy,ny-1),z=Math.min(index/(sx*sy),nz-1);
				if(!RegionCheckpointAssembler.sectionOfCell(manifest.geometry(),x+nx*(y+ny*z)).equals(section.key()))throw new IOException("MAC face is stored in the wrong section");
			}
		}
		for(boolean seen:covered)if(!seen)throw new IOException("Incomplete collision coverage");for(var axis:faces)for(boolean seen:axis)if(!seen)throw new IOException("Incomplete MAC momentum coverage");
		if(totalMass!=manifest.totalMassUnits()||Double.doubleToRawLongBits(totalVolume.toDouble())!=manifest.totalVolumeBits())throw new IOException("Liquid inventory summary differs");
	}

	private void validateMaterialLedgers() throws IOException {
		int n=manifest.materialPalette().size(); int[] counts=new int[n];long[] mass=new long[n];SignedDyadic[] volume=new SignedDyadic[n];Arrays.fill(volume,SignedDyadic.ZERO);
		for(var section:sections)for(var stock:section.liquidStocks()) {int material=stock.key().material();if(material>=n)throw new IOException("Liquid material is outside its palette");counts[material]++;mass[material]=Math.addExact(mass[material],stock.massUnits());volume[material]=volume[material].add(stock.volume());}
		for(int i=0;i<n;i++){var ledger=manifest.materialLedgers().get(i);if(ledger.markerCount()!=counts[i]||ledger.massUnits()!=mass[i]||ledger.volumeBits()!=Double.doubleToRawLongBits(volume[i].toDouble()))throw new IOException("Liquid material inventory summary differs");}
	}

	private void validateSectionOwnership(RegionSectionState section) throws IOException {
		int localCells=Math.multiplyExact(Math.multiplyExact(section.sizeX(),section.sizeY()),section.sizeZ());
		double[] phase=new PhaseOccupancyCodec().decode(requiredExtension(section.extensions(),PhaseOccupancyCodec.SECTION_EXTENSION).payload(),localCells);
		byte[] masks=section.solidMask();
		for(int cell=0;cell<localCells;cell++)for(int micro=0;micro<8;micro++)
			if((Byte.toUnsignedInt(masks[cell])&1<<micro)!=0&&phase[8*cell+micro]!=0)
				throw new IOException("Phase occupancy intersects a fixed solid microbox");
		var stockVolumes=new HashMap<Long,SignedDyadic>();
		for(var stock:section.liquidStocks()){
			long key=((long)stock.key().cell()<<8)|stock.key().supportMask();
			stockVolumes.merge(key,stock.volume(),SignedDyadic::add);
		}
		int local=0;
		for(int z=section.localStartZ();z<section.localStartZ()+section.sizeZ();z++)
			for(int y=section.localStartY();y<section.localStartY()+section.sizeY();y++)
				for(int x=section.localStartX();x<section.localStartX()+section.sizeX();x++,local++){
					int cell=x+manifest.sizeX()*(y+manifest.sizeY()*z);
					for(int support:mmm.inventory.LiquidCellInventory.connectedSupports(Byte.toUnsignedInt(masks[local]))){
						double sum=0;for(int micro=0;micro<8;micro++)if((support&1<<micro)!=0)sum+=phase[8*local+micro];
						double expected=stockVolumes.getOrDefault(((long)cell<<8)|support,SignedDyadic.ZERO).toDouble();
						if(Math.abs(sum-expected)>mmm.inventory.PhaseOccupancyState.VOLUME_RESOLUTION)
							throw new IOException("Phase occupancy volume differs from cell inventory");
					}
				}
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
	}

	private void validateGranularSectionPosition(RegionSectionState section, float x, float y, float z) throws IOException {
		var key = RegionCheckpointAssembler.sectionKey(manifest.geometry(), x, y, z);
		if (!key.equals(section.key())) {
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
		SignedDyadic result = SignedDyadic.ZERO;
		for (int index = 3; index < positionVolume.length; index += 4) result = result.add(SignedDyadic.of(positionVolume[index]));
		return result.toDouble();
	}

	private static double sum(double[] values) {
		return SignedDyadic.sum(values).toDouble();
	}

	private static double[] toDoubles(long[] bits) {
		double[] result = new double[bits.length];
		for (int index = 0; index < bits.length; index++) result[index] = Double.longBitsToDouble(bits[index]);
		return result;
	}

}

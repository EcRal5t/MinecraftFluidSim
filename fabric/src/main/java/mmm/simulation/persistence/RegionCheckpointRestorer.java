package mmm.simulation.persistence;

import mmm.inventory.LiquidCellInventory;
import mmm.inventory.PhaseOccupancyState;

import mmm.model.region.*;
import mmm.fluid.*;
import mmm.fluid.air.*;
import mmm.chemistry.*;
import mmm.granular.*;
import mmm.thermal.*;
import mmm.units.SignedDyadic;
import java.io.IOException;
import java.util.*;

/** Reconstructs cell inventories and MAC momentum from a single validated region generation. */
public final class RegionCheckpointRestorer {
	private static final String CHEMICAL_AUDIT_EXTENSION="mmm:chemical_audit";
	private final RegionManifest manifest; private final List<RegionSectionState> sections; private boolean validated;
	public RegionCheckpointRestorer(RegionCheckpoint checkpoint){manifest=checkpoint.manifest();sections=checkpoint.sections();}

	public FluidDomainState reconstructAuthoritativeState(ChemicalRegistry chemistry,GranularRegistry granular,
			ThermalRegistry thermal,GasRegistry gas,MaterialRegistry materials) throws IOException {
		validateCheckpoint(); int cells=manifest.sizeX()*manifest.sizeY()*manifest.sizeZ();
		var runtime=runtime();var liquid=reconstructLiquid(chemistry);var momentum=reconstructMomentum();
		var chemicalAudit=manifest.hasChemicalState()?decodeChemicalAuditExtension():new ChemicalAuditLedgerCodec.AuditLedgers(ChemicalTransferLedger.EMPTY,ChemicalTransferLedger.EMPTY);
		var solids=manifest.hasGranularState()?reconstructGranular(granular):null;
		var gasRegistry=gas==null?BuiltinGasDefinitions.createRegistry():gas;
		boolean local=manifest.hasGasState()&&spatialGasPayload();
		var localGas=local?reconstructSpatialGas(gasRegistry,chemistry):null;
		if(manifest.hasGasState()&&!local)throw new IOException("Gas checkpoint requires local spatial inventory");
		var heat=manifest.hasThermalState()?reconstructThermalOwners(thermal):null;
		if(localGas!=null&&thermal!=null){var model=new GasThermalModel(thermal,gasRegistry);try{
			for(int cell=0;cell<localGas.cellCount();cell++)model.requireInventoryEnergy(localGas.cells().get(cell),localGas.energyAt(cell));
		}catch(IllegalArgumentException invalid){throw new IOException("Spatial gas thermal state is invalid",invalid);}}
		if(heat!=null){var liquidU=SignedDyadic.ZERO;for(var stock:liquid.stocks())liquidU=liquidU.add(SignedDyadic.of(stock.internalEnergy()));if(localGas!=null)liquidU=liquidU.add(SignedDyadic.sum(localGas.internalEnergy()));heat.requireClosed(liquidU,cells);}
		try{return new FluidDomainState(manifest.sizeX(),manifest.sizeY(),manifest.sizeZ(),manifest.solverConfig(),runtime.sealed(),liquid,reconstructPhaseOccupancy(),momentum,reconstructFixedGeometry(),runtime.entityBodies(),manifest.materialPalette(),
				manifest.hasChemicalState()?chemistry:null,chemicalAudit.externalSource(),chemicalAudit.escapedSink(),manifest.hasChemicalState()?runtime.dirty():null,runtime.cursor(),
				runtime.massTransferPending(),
				solids==null?null:granular,solids,runtime.granular(),runtime.precipitation(),gasRegistry,FluidDomainState.GasContinuation.spatial(localGas),heat==null?null:thermal,heat,
				heat==null?0:runtime.maximumThermalSubsteps(),heat==null?null:reconstructPendingPhaseMotion(),heat==null?null:runtime.boundary(),heat==null?null:runtime.pressure(),manifest.materialPalette().isEmpty()?null:materials);
		}catch(IllegalArgumentException invalid){throw new IOException("Invalid complete cell/MAC region state",invalid);}
	}
	private SolverRuntimeCodec.Data runtime()throws IOException{return new SolverRuntimeCodec().decode(requiredExtension(manifest.extensions(),SolverRuntimeCodec.MANIFEST_EXTENSION).payload(),manifest.sizeX()*manifest.sizeY()*manifest.sizeZ());}
	public LiquidCellInventory.Snapshot reconstructLiquid(ChemicalRegistry chemistry)throws IOException {
		validateCheckpoint();
		// Saved mixture entries use registry ordinals. Every reader must establish the
		// same definition identity before assigning meaning to those integer handles.
		if(manifest.hasChemicalState()&&(chemistry==null
				||!manifest.registryFingerprint().equals(chemistry.fingerprint())
				||!manifest.speciesPalette().equals(chemistry.species().stream().map(ChemicalSpecies::id).toList())))
			throw new IOException("Chemical registry fingerprint or species palette differs from checkpoint");
		var rows=new ArrayList<List<LiquidCellInventory.Stock>>();int cells=manifest.sizeX()*manifest.sizeY()*manifest.sizeZ();for(int i=0;i<cells;i++)rows.add(new ArrayList<>());
		for(var section:sections)for(var stock:section.liquidStocks())rows.get(stock.key().cell()).add(stock);
		try{return new LiquidCellInventory(new LiquidCellInventory.Snapshot(rows),chemistry).snapshot();}catch(IllegalArgumentException invalid){throw new IOException("Invalid liquid cell quantities",invalid);}
	}
	public MacMomentumState reconstructMomentum()throws IOException {
		validateCheckpoint();int nx=manifest.sizeX(),ny=manifest.sizeY(),nz=manifest.sizeZ();double[][] values={new double[(nx+1)*ny*nz],new double[nx*(ny+1)*nz],new double[nx*ny*(nz+1)]};
		for(var section:sections)for(var face:section.faceMomentum())values[face.axis()][face.face()]=face.kilogramMetersPerSecond();return new MacMomentumState(nx,ny,nz,values[0],values[1],values[2]);
	}
	public PhaseOccupancyState reconstructPhaseOccupancy() throws IOException {
		validateCheckpoint();
		int nx=manifest.sizeX(),ny=manifest.sizeY();
		double[] volumes=new double[Math.multiplyExact(8,Math.multiplyExact(Math.multiplyExact(nx,ny),manifest.sizeZ()))];
		var codec=new PhaseOccupancyCodec();
		for(var section:sections){
			int count=Math.multiplyExact(Math.multiplyExact(section.sizeX(),section.sizeY()),section.sizeZ());
			double[] slice=codec.decode(requiredExtension(section.extensions(),PhaseOccupancyCodec.SECTION_EXTENSION).payload(),count);
			int local=0;
			for(int z=section.localStartZ();z<section.localStartZ()+section.sizeZ();z++)
				for(int y=section.localStartY();y<section.localStartY()+section.sizeY();y++)
					for(int x=section.localStartX();x<section.localStartX()+section.sizeX();x++){
						int cell=x+nx*(y+ny*z);System.arraycopy(slice,8*local++,volumes,8*cell,8);
					}
		}
		try{return new PhaseOccupancyState(volumes);}
		catch(IllegalArgumentException invalid){throw new IOException("Invalid persisted phase occupancy",invalid);}
	}
	public FixedGeometrySnapshot reconstructFixedGeometry()throws IOException {
		validateCheckpoint();int nx=manifest.sizeX(),ny=manifest.sizeY();byte[] masks=new byte[nx*ny*manifest.sizeZ()];
		for(var section:sections){byte[] slice=section.solidMask();int local=0;for(int z=section.localStartZ();z<section.localStartZ()+section.sizeZ();z++)for(int y=section.localStartY();y<section.localStartY()+section.sizeY();y++)for(int x=section.localStartX();x<section.localStartX()+section.sizeX();x++)masks[x+nx*(y+ny*z)]=slice[local++];}return FixedGeometrySnapshot.fromMasks(nx,ny,manifest.sizeZ(),masks);
	}
	/** Diagnostic sampling views; restore services use reconstructAuthoritativeState. */
	public FlipParticleState reconstruct()throws IOException{if(manifest.layout().hasMaterials())throw new IOException("Material state requires its palette");return debugState(null).particles();}
	public FlipMaterialParticleState reconstructMaterial()throws IOException{if(!manifest.layout().hasMaterials()||manifest.hasChemicalState())throw new IOException("Material diagnostic layout differs");return debugState(null).materials();}
	public FlipChemicalParticleState reconstructChemical(ChemicalRegistry chemistry)throws IOException{if(!manifest.hasChemicalState())throw new IOException("Chemical diagnostic layout differs");return debugState(chemistry).chemicals();}
	private FluidDomainState debugState(ChemicalRegistry chemistry)throws IOException {
		var run=runtime();var audit=chemistry==null?new ChemicalAuditLedgerCodec.AuditLedgers(ChemicalTransferLedger.EMPTY,ChemicalTransferLedger.EMPTY):decodeChemicalAuditExtension();
		return new FluidDomainState(manifest.sizeX(),manifest.sizeY(),manifest.sizeZ(),manifest.solverConfig(),run.sealed(),reconstructLiquid(chemistry),reconstructPhaseOccupancy(),reconstructMomentum(),reconstructFixedGeometry(),run.entityBodies(),manifest.materialPalette(),chemistry,audit.externalSource(),audit.escapedSink(),chemistry==null?null:run.dirty(),run.cursor(),run.massTransferPending(),null,null,null,false,BuiltinGasDefinitions.createRegistry(),manifest.hasGasState()?FluidDomainState.GasContinuation.spatial(reconstructSpatialGasPayload(null,null)):null,null,null,0,null,null,null,null);
	}
	public SpatialGasState reconstructSpatialGas(GasRegistry gas,ChemicalRegistry chemistry)throws IOException {validateCheckpoint();return reconstructSpatialGasPayload(gas,chemistry);}
	private boolean spatialGasPayload() throws IOException {return GasRegionStateCodec.isSpatial(requiredExtension(manifest.extensions(),GasRegionStateCodec.MANIFEST_EXTENSION).payload());}
	public boolean hasSpatialGas() throws IOException { return manifest.hasGasState() && spatialGasPayload(); }
	public ThermalOwnerSnapshot reconstructThermalOwners(ThermalRegistry registry)throws IOException {
		validateCheckpoint();var codec=new ThermalRegionStateCodec();var thermal=codec.decodeManifest(requiredExtension(manifest.extensions(),ThermalRegionStateCodec.MANIFEST_EXTENSION).payload());
		if(registry==null||!registry.fingerprint().equals(thermal.registryFingerprint()))throw new IOException("Thermal registry differs");
		double[] active=new double[thermal.activeParcelCount()];boolean[] seen=new boolean[active.length];var deposits=new ArrayList<ThermalDepositEnergy>();var coherent=new ArrayList<CoherentPhaseStore.Group>();
		for(var section:sections){var data=codec.decodeSection(requiredExtension(section.extensions(),ThermalRegionStateCodec.SECTION_EXTENSION).payload(),section.stockCount(),GranularLimits.MAX_ACTIVE_PARCELS_PER_REGION,GranularLimits.MAX_DEPOSIT_GROUPS_PER_REGION);
			if(manifest.hasGranularState()) {var solid=new GranularRegionStateCodec().decodeSection(requiredExtension(section.extensions(),GranularRegionStateCodec.SECTION_EXTENSION).payload(),GranularLimits.MAX_ACTIVE_PARCELS_PER_REGION,GranularLimits.MAX_DEPOSIT_GROUPS_PER_REGION);
				long[] au=data.activeParcelEnergyBits(),du=data.depositGroupEnergyBits();for(int i=0;i<solid.active().size();i++){int ordinal=solid.active().get(i).ordinal();if(ordinal>=active.length||seen[ordinal])throw new IOException("Thermal granular ordinal differs");seen[ordinal]=true;active[ordinal]=Double.longBitsToDouble(au[i]);}
				for(int i=0;i<solid.deposits().size();i++)deposits.add(new ThermalDepositEnergy(solid.deposits().get(i),Double.longBitsToDouble(du[i])));
			}
			for(var group:data.coherentGroups())coherent.add(new CoherentPhaseStore.Group(group.cell(),group.phaseId(),group.occupiedVolumeSvq(),group.massUnits(),group.composition(),group.internalEnergy()));
		}
		for(boolean entry:seen)if(!entry)throw new IOException("Missing granular thermal owner");deposits.sort(ThermalDepositEnergy.ORDER);coherent.sort(Comparator.comparingInt(CoherentPhaseStore.Group::cell).thenComparing(CoherentPhaseStore.Group::phaseId));
		return new ThermalOwnerSnapshot(registry.fingerprint(),thermal.insertionTemperatureK(),active,deposits.stream().mapToDouble(ThermalDepositEnergy::energy).toArray(),toDoubles(thermal.gasNodeEnergyBits()),coherent,thermal.audit());
	}
	public FlipThermalParticleState reconstructThermal(ThermalRegistry registry)throws IOException {
		var owners=reconstructThermalOwners(registry);double[] liquid=sections.stream().flatMap(section->section.liquidStocks().stream()).sorted(Comparator.comparing(LiquidCellInventory.Stock::key)).filter(stock->stock.massUnits()>0).mapToDouble(LiquidCellInventory.Stock::internalEnergy).toArray();
		return new FlipThermalParticleState(manifest.sizeX(),manifest.sizeY(),manifest.sizeZ(),manifest.solverConfig().cellSize(),liquid.length,registry.fingerprint(),owners.insertionTemperatureK(),liquid,owners.copyActiveParcelEnergy(),owners.copyDepositEnergy(),owners.coherentGroups(),reconstructSpatialGasPayload(null,null).internalEnergy(),owners.audit());
	}
	public FlipThermalParticleState reconstructThermal(ThermalRegistry thermal,GasRegistry gas,ChemicalRegistry chemistry)throws IOException {
		var result=reconstructThermal(thermal);var model=new GasThermalModel(thermal,gas);
		var state=reconstructSpatialGas(gas,chemistry);for(int i=0;i<state.cellCount();i++)model.requireInventoryEnergy(state.cells().get(i),state.energyAt(i));
		return result;
	}
	private record ThermalDepositEnergy(GranularStateSnapshot.DepositGroup group,double energy){private static final Comparator<ThermalDepositEnergy> ORDER=Comparator.comparingInt((ThermalDepositEnergy entry)->entry.group().cell()).thenComparingInt(entry->entry.group().materialPaletteIndex()).thenComparingInt(entry->entry.group().solidCompositionIndex());}
	/** Sparse absence is the canonical empty record; presence is valid only in a complete thermal generation. */
	public PendingPhaseMotionState reconstructPendingPhaseMotion() throws IOException {
		int cellCount = Math.multiplyExact(Math.multiplyExact(manifest.sizeX(), manifest.sizeY()), manifest.sizeZ());
		if (!hasExtension(manifest.extensions(), PendingPhaseMotionCodec.MANIFEST_EXTENSION)) {
			return new PendingPhaseMotionState(cellCount, List.of());
		}
		if (manifest.layout() != RegionStateLayout.THERMAL) {
			throw new IOException("Pending phase volume is only valid in a thermal region-state");
		}
		return new PendingPhaseMotionCodec().decode(requiredExtension(
				manifest.extensions(), PendingPhaseMotionCodec.MANIFEST_EXTENSION).payload(), cellCount);
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
				.map(mmm.granular.ParticulateMaterial::id).toList())) {
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
		long totalMassUnits = GranularStateSnapshot.canonicalTotalMassUnits(Arrays.asList(active), deposits);
		try {
			return new GranularStateSnapshot(manifest.sizeX(), manifest.sizeY(), manifest.sizeZ(),
					granular.registryFingerprint(), granular.chemicalRegistryFingerprint(), granular.materialPalette(),
					granular.solidCompositions(), granular.solidSpeciesTotals(), Arrays.asList(active), deposits,
					totalSvq, totalMassUnits, granular.externalSource(), granular.escapedSink());
		} catch (IllegalArgumentException invalid) {
			throw new IOException("Invalid region-state granular inventory: " + invalid.getMessage(), invalid);
		}
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
	private SpatialGasState reconstructSpatialGasPayload(GasRegistry registry,ChemicalRegistry chemicalRegistry) throws IOException {
		var codec=new GasRegionStateCodec();var data=codec.decodeSpatialManifest(requiredExtension(manifest.extensions(),GasRegionStateCodec.MANIFEST_EXTENSION).payload());
		if(((data.capabilities()&GasRegionStateCodec.CAP_CHEMISTRY)!=0)!=manifest.hasChemicalState()
				||manifest.hasChemicalState()&&!data.chemicalRegistryFingerprint().equals(manifest.registryFingerprint()))
			throw new IOException("Spatial gas chemical identity differs from region");
		if(registry!=null&&!data.registryFingerprint().equals(registry.fingerprint()))throw new IOException("Spatial gas registry differs");
		if(chemicalRegistry!=null&&!data.chemicalRegistryFingerprint().equals(chemicalRegistry.fingerprint()))throw new IOException("Spatial gas chemistry differs");
		int sx=manifest.sizeX(),sy=manifest.sizeY(),sz=manifest.sizeZ(),cells=Math.multiplyExact(Math.multiplyExact(sx,sy),sz);
		var inventory=new ArrayList<GasInventory>(Collections.nCopies(cells,GasInventory.EMPTY));double[] energy=new double[cells];boolean[] seen=new boolean[cells];
		for(var section:sections){
			int nx=section.sizeX(),ny=section.sizeY(),nz=section.sizeZ(),count=Math.multiplyExact(Math.multiplyExact(nx,ny),nz);
			var entries=codec.decodeSpatialSection(requiredExtension(section.extensions(),GasRegionStateCodec.SECTION_EXTENSION).payload(),count);
			for(int z=0;z<nz;z++)for(int y=0;y<ny;y++)for(int x=0;x<nx;x++){
				int cell=section.localStartX()+x+sx*(section.localStartY()+y+sy*(section.localStartZ()+z));
				if(cell<0||cell>=cells||seen[cell])throw new IOException("Spatial gas section coverage overlaps");seen[cell]=true;
			}
			for(var entry:entries){int local=entry.cell(),x=local%nx,y=local/nx%ny,z=local/(nx*ny);
				int cell=section.localStartX()+x+sx*(section.localStartY()+y+sy*(section.localStartZ()+z));
				inventory.set(cell,entry.inventory());energy[cell]=entry.energy();
				if(registry!=null)validateGasInventoryRegistry(entry.inventory(),registry,chemicalRegistry);
			}
		}
		for(boolean present:seen)if(!present)throw new IOException("Spatial gas section coverage is incomplete");
		try {
			var total=GasRegionAudit.aggregate(inventory);
			if(!total.equals(data.inventory())||!GasRegionAudit.closed(total,data.externalSource().add(data.phaseSource()),data.escapedSink().add(data.phaseSink()))
					||!SignedDyadic.sum(energy).equals(data.energy()))throw new IOException("Spatial gas inventory or energy audit differs");
			if(registry!=null){validateGasAuditRegistry(data.externalSource(),registry,chemicalRegistry);validateGasAuditRegistry(data.escapedSink(),registry,chemicalRegistry);
				validateGasAuditRegistry(data.phaseSource(),registry,chemicalRegistry);validateGasAuditRegistry(data.phaseSink(),registry,chemicalRegistry);}
			return new SpatialGasState(sx,sy,sz,data.registryFingerprint(),data.chemicalRegistryFingerprint(),inventory,energy,
					data.externalSource(),data.escapedSink(),data.phaseSource(),data.phaseSink());
		} catch(IllegalArgumentException|ArithmeticException invalid){throw new IOException("Invalid spatial gas inventory",invalid);}
	}

	void validateGasLedgers() throws IOException { if(!spatialGasPayload())throw new IOException("Gas checkpoint requires local spatial inventory");reconstructSpatialGasPayload(null,null); }

	private void validateCheckpoint() throws IOException {
		if (validated) return;
		new RegionCheckpointValidator(new RegionCheckpoint(manifest, sections)).validate();
		validated = true;
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

	private static void validateGasInventoryRegistry(
			GasInventory inventory, GasRegistry registry, ChemicalRegistry chemicalRegistry
	) throws IOException {
		try { registry.requireCompatible(inventory); }
		catch (IllegalArgumentException invalid) { throw new IOException(invalid.getMessage(), invalid); }
		for (GasComponentAmount amount : inventory.components()) {
			validateGasSpecies(amount.species(), chemicalRegistry);
			if(chemicalRegistry!=null){long mass=amount.massResidualUnits();
				try{for(var species:amount.species())mass=Math.addExact(mass,chemicalRegistry.speciesMassUnits(chemicalRegistry.speciesHandle(species.speciesId()),species.amountQuanta()));}
				catch(ArithmeticException invalid){throw new IOException("Gas species mass exceeds representation",invalid);}
				if(mass!=amount.massUnits())throw new IOException("Gas component mass differs from species and residual: component="
						+amount.componentId()+", mass="+amount.massUnits()+", accounted="+mass+", residual="+amount.massResidualUnits()+", species="+amount.species());}
		}
	}

	private static void validateGasAuditRegistry(
			mmm.fluid.air.GasAuditLedger ledger, GasRegistry registry, ChemicalRegistry chemicalRegistry
	) throws IOException {
		for (mmm.fluid.air.GasAuditLedger.Entry entry : ledger.entries()) {
			try { registry.requireCompatible(entry.componentId(), entry.species()); }
			catch (IllegalArgumentException invalid) { throw new IOException(invalid.getMessage(), invalid); }
			validateGasSpecies(entry.species(), chemicalRegistry);
			if(chemicalRegistry!=null){long mass=entry.massResidualUnits();
				try{for(var species:entry.species())mass=Math.addExact(mass,chemicalRegistry.speciesMassUnits(chemicalRegistry.speciesHandle(species.speciesId()),species.amountQuanta()));}
				catch(ArithmeticException invalid){throw new IOException("Gas audit mass exceeds representation",invalid);}
				if(mass!=entry.massUnits())throw new IOException("Gas audit mass differs from species and residual");}
		}
	}

	private static void validateGasSpecies(
			List<GasComponentAmount.SpeciesAmount> species, ChemicalRegistry registry
	) throws IOException {
		if (registry == null) return; // Gas component definitions already bind their molecular species.
		for (GasComponentAmount.SpeciesAmount amount : species) if (!registry.hasSpecies(amount.speciesId())) {
			throw new IOException("Unknown gas species " + amount.speciesId());
		}
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

}

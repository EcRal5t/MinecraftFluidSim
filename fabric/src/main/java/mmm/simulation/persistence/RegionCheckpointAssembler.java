package mmm.simulation.persistence;

import mmm.inventory.LiquidCellInventory;

import mmm.application.checkpoint.RegionCheckpointSource;
import mmm.model.region.*;
import mmm.fluid.*;
import mmm.fluid.air.GasStateSnapshot;
import mmm.chemistry.*;
import mmm.granular.*;
import mmm.thermal.*;
import mmm.units.SignedDyadic;
import java.io.IOException;
import java.util.*;

/** Captures cell inventories, unique MAC faces and orthogonal owners in one durable generation. */
public final class RegionCheckpointAssembler {
	private RegionCheckpointAssembler() { }
	public static RegionCheckpoint capture(RegionCheckpointSource source,long generation) throws IOException { return captureCells(source,generation); }
	public static RegionCheckpoint captureMaterial(RegionCheckpointSource source,long generation) throws IOException { return captureCells(source,generation); }
	public static RegionCheckpoint captureChemical(RegionCheckpointSource source,long generation) throws IOException { return captureCells(source,generation); }
	public static RegionCheckpoint captureGranular(RegionCheckpointSource source,long generation) throws IOException { return captureCells(source,generation); }
	public static RegionCheckpoint captureThermal(RegionCheckpointSource source,long generation) throws IOException { return captureCells(source,generation); }

	private static RegionCheckpoint captureCells(RegionCheckpointSource source,long generation) throws IOException {
		var identity = source.identity(generation); var geometry = source.geometry(); var state = source.authoritativeState();
		int nx=geometry.sizeX(),ny=geometry.sizeY(),nz=geometry.sizeZ(); float h=geometry.solverConfig().cellSize();
		int cellsPerBlock = Math.round(1/h);
		if (cellsPerBlock <= 0 || Math.abs(cellsPerBlock*h-1)>1e-6 || nx%cellsPerBlock!=0 || ny%cellsPerBlock!=0 || nz%cellsPerBlock!=0)
			throw new IOException("Region state requires integral solver cells per Minecraft block");
		RegionStateLayout layout = state.thermalOwner()!=null ? RegionStateLayout.THERMAL : state.granular()!=null ? RegionStateLayout.GRANULAR
				: state.chemicalRegistry()!=null ? RegionStateLayout.CHEMICAL : state.materialPalette().isEmpty() ? RegionStateLayout.PURE : RegionStateLayout.MATERIAL;
		var granular = state.granular(); var thermal = state.thermalOwner(); var gas = state.gas();
		byte[] collision=state.copyCollisionMasks(); int[] membership=gas==null?null:gas.pieceNodeOrdinal();
		double[] phaseVolumes=state.phaseOccupancy().copyLiquidMicroVolumes();
		var facesBySection = new HashMap<RegionSectionState.SectionKey,List<RegionSectionState.FaceMomentum>>();
		double[][] momenta={state.momentum().copyX(),state.momentum().copyY(),state.momentum().copyZ()};
		for(int axis=0;axis<3;axis++) {
			int sx=nx+(axis==0?1:0),sy=ny+(axis==1?1:0),sz=nz+(axis==2?1:0);
			for(int z=0;z<sz;z++)for(int y=0;y<sy;y++)for(int x=0;x<sx;x++) {
				var key=sectionKey(geometry,(Math.min(x,nx-1)+.5F)*h,(Math.min(y,ny-1)+.5F)*h,(Math.min(z,nz-1)+.5F)*h);
				int face=x+sx*(y+sy*z); facesBySection.computeIfAbsent(key,k->new ArrayList<>()).add(new RegionSectionState.FaceMomentum(axis,face,momenta[axis][face]));
			}
		}
		var sections = new ArrayList<RegionSectionState>();
		int minX=Math.floorDiv(geometry.origin().x(),16),minY=Math.floorDiv(geometry.origin().y(),16),minZ=Math.floorDiv(geometry.origin().z(),16);
		int maxX=Math.floorDiv(geometry.origin().x()+nx/cellsPerBlock-1,16),maxY=Math.floorDiv(geometry.origin().y()+ny/cellsPerBlock-1,16),maxZ=Math.floorDiv(geometry.origin().z()+nz/cellsPerBlock-1,16);
		double[] activeU=thermal==null?new double[0]:thermal.copyActiveParcelEnergy(),depositU=thermal==null?new double[0]:thermal.copyDepositEnergy();
		var thermalLedgers=new ArrayList<ThermalRegionStateCodec.SectionLedger>();
		for(int secX=minX;secX<=maxX;secX++)for(int secY=minY;secY<=maxY;secY++)for(int secZ=minZ;secZ<=maxZ;secZ++) {
			var key=new RegionSectionState.SectionKey(secX,secY,secZ);
			int x0=Math.max(0,(16*secX-geometry.origin().x())*cellsPerBlock),y0=Math.max(0,(16*secY-geometry.origin().y())*cellsPerBlock),z0=Math.max(0,(16*secZ-geometry.origin().z())*cellsPerBlock);
			int sx=Math.min(nx,((secX+1)*16-geometry.origin().x())*cellsPerBlock)-x0,sy=Math.min(ny,((secY+1)*16-geometry.origin().y())*cellsPerBlock)-y0,sz=Math.min(nz,((secZ+1)*16-geometry.origin().z())*cellsPerBlock)-z0;
			var stocks=new ArrayList<LiquidCellInventory.Stock>(); byte[] mask=new byte[sx*sy*sz]; int[] gasSlice=gas==null?null:new int[8*mask.length];
			double[] phaseSlice=new double[8*mask.length];
			int local=0; for(int z=z0;z<z0+sz;z++)for(int y=y0;y<y0+sy;y++)for(int x=x0;x<x0+sx;x++) {
				int cell=x+nx*(y+ny*z); stocks.addAll(state.liquid().rows().get(cell)); mask[local]=collision[cell];
				System.arraycopy(phaseVolumes,8*cell,phaseSlice,8*local,8);
				if(gas!=null)System.arraycopy(membership,8*cell,gasSlice,8*local,8); local++;
			}
			stocks.sort(Comparator.comparing(LiquidCellInventory.Stock::key));
			var extensions=new ArrayList<RegionExtension>();
			extensions.add(new RegionExtension(PhaseOccupancyCodec.SECTION_EXTENSION,new PhaseOccupancyCodec().encode(phaseSlice)));
			if(gas!=null)extensions.add(new RegionExtension(GasRegionStateCodec.SECTION_EXTENSION,new GasRegionStateCodec().encodeSection(new GasRegionStateCodec.SectionData(gasSlice))));
			var active=new ArrayList<GranularRegionStateCodec.ActiveEntry>(); var deposits=new ArrayList<GranularStateSnapshot.DepositGroup>();
			var activeBits=new ArrayList<Long>(); var depositBits=new ArrayList<Long>(); var coherent=new ArrayList<ThermalRegionStateCodec.CoherentEntry>();
			if(granular!=null) {
				for(int i=0;i<granular.activeParcels().size();i++) { var parcel=granular.activeParcels().get(i);
					if(sectionKey(geometry,parcel.x(),parcel.y(),parcel.z()).equals(key)) { active.add(new GranularRegionStateCodec.ActiveEntry(i,parcel)); if(thermal!=null)activeBits.add(Double.doubleToRawLongBits(activeU[i])); } }
				for(int i=0;i<granular.depositGroups().size();i++) { var group=granular.depositGroups().get(i);
					if(sectionOfCell(geometry,group.cell()).equals(key)) { deposits.add(group); if(thermal!=null)depositBits.add(Double.doubleToRawLongBits(depositU[i])); } }
				extensions.add(new RegionExtension(GranularRegionStateCodec.SECTION_EXTENSION,new GranularRegionStateCodec().encodeSection(new GranularRegionStateCodec.SectionData(active,deposits))));
			}
			if(thermal!=null) {
				for(var group:thermal.coherentGroups()) if(sectionOfCell(geometry,group.cell()).equals(key)) coherent.add(new ThermalRegionStateCodec.CoherentEntry(group.cell(),group.phaseId(),group.occupiedVolumeSvq(),group.massUnits(),group.composition(),Double.doubleToRawLongBits(group.internalEnergy())));
				long[] au=activeBits.stream().mapToLong(Long::longValue).toArray(),du=depositBits.stream().mapToLong(Long::longValue).toArray();
				SignedDyadic nonLiquid=energyBits(au).add(energyBits(du)); for(var group:coherent)nonLiquid=nonLiquid.add(SignedDyadic.of(group.internalEnergy()));
				var data=new ThermalRegionStateCodec.SectionData(au,du,coherent,nonLiquid);
				extensions.add(new RegionExtension(ThermalRegionStateCodec.SECTION_EXTENSION,new ThermalRegionStateCodec().encodeSection(data)));
				SignedDyadic total=nonLiquid; for(var stock:stocks)total=total.add(SignedDyadic.of(stock.internalEnergy()));
				thermalLedgers.add(new ThermalRegionStateCodec.SectionLedger(key,stocks.size(),active.size(),deposits.size(),coherent.size(),total));
			}
			var unsigned=new RegionSectionState(layout,identity.regionId(),identity.dimensionId(),generation,secX,secY,secZ,x0,y0,z0,sx,sy,sz,stocks,facesBySection.getOrDefault(key,List.of()),mask,extensions,0);
			sections.add(withChecksum(unsigned));
		}
		sections.sort(Comparator.comparing(RegionSectionState::key)); thermalLedgers.sort(Comparator.comparing(ThermalRegionStateCodec.SectionLedger::key));
		var descriptors=sections.stream().map(s->new RegionManifest.SectionDescriptor(s.sectionX(),s.sectionY(),s.sectionZ(),s.stockCount(),Double.doubleToRawLongBits(s.exactVolume().toDouble()),s.massUnits(),s.checksum())).toList();
		var extensions=new ArrayList<RegionExtension>();
		extensions.add(new RegionExtension(SolverRuntimeCodec.MANIFEST_EXTENSION,new SolverRuntimeCodec().encode(state)));
		if(state.chemicalRegistry()!=null)extensions.add(new RegionExtension("mmm:chemical_audit",ChemicalAuditLedgerCodec.encode(state.chemicalSource(),state.chemicalSink())));
		if(gas!=null)extensions.add(new RegionExtension(GasRegionStateCodec.MANIFEST_EXTENSION,new GasRegionStateCodec().encodeManifest(gasManifest(gas,sections,state.chemicalRegistry()!=null))));
		if(granular!=null)extensions.add(new RegionExtension(GranularRegionStateCodec.MANIFEST_EXTENSION,new GranularRegionStateCodec().encodeManifest(granularManifest(granular,sections,state.chemicalRegistry()!=null))));
		if(thermal!=null) {
			SignedDyadic liquidU=SignedDyadic.ZERO;for(var stock:state.liquid().stocks())liquidU=liquidU.add(SignedDyadic.of(stock.internalEnergy()));
			SignedDyadic coherentU=SignedDyadic.ZERO;for(var group:thermal.coherentGroups())coherentU=coherentU.add(SignedDyadic.of(group.internalEnergy()));
			var gasU=thermal.copyGasNodeEnergy();
			int cap=ThermalRegionStateCodec.CAP_MATERIALS|ThermalRegionStateCodec.CAP_GAS|ThermalRegionStateCodec.CAP_THERMAL
					|(state.chemicalRegistry()==null?0:ThermalRegionStateCodec.CAP_CHEMISTRY)|(granular==null?0:ThermalRegionStateCodec.CAP_GRANULAR);
			var data=new ThermalRegionStateCodec.ManifestData(cap,thermal.registryFingerprint(),thermal.insertionTemperatureK(),state.liquid().size(),activeU.length,depositU.length,
					thermal.coherentGroups().size(),gasU.length,liquidU,SignedDyadic.sum(activeU),SignedDyadic.sum(depositU),coherentU,SignedDyadic.sum(gasU),thermal.audit().expectedInventory(),thermal.audit(),thermalLedgers,bits(gasU));
			extensions.add(new RegionExtension(ThermalRegionStateCodec.MANIFEST_EXTENSION,new ThermalRegionStateCodec().encodeManifest(data)));
			extensions.add(new RegionExtension(ThermalBuoyancyCodec.MANIFEST_EXTENSION,new ThermalBuoyancyCodec().encodeCurrent()));
			if(!state.pendingPhaseVolume().isEmpty())extensions.add(new RegionExtension(PendingPhaseVolumeCodec.MANIFEST_EXTENSION,new PendingPhaseVolumeCodec().encode(state.pendingPhaseVolume())));
			var devices=source.finiteThermalDevices(thermal.registryFingerprint());
			if(!devices.devices().isEmpty())extensions.add(new RegionExtension(FiniteThermalDeviceCodec.MANIFEST_EXTENSION,new FiniteThermalDeviceCodec().encode(devices)));
		}
		var palette=state.materialPalette(); var counts=new int[palette.size()]; var volume=new SignedDyadic[palette.size()];var mass=new long[palette.size()];Arrays.fill(volume,SignedDyadic.ZERO);
		SignedDyadic totalVolume=SignedDyadic.ZERO;long totalMass=0,residual=0; var chemistry=state.chemicalRegistry();long[] species=chemistry==null?new long[0]:new long[chemistry.species().size()];
		for(var stock:state.liquid().stocks()) { totalVolume=totalVolume.add(stock.volume());totalMass=Math.addExact(totalMass,stock.massUnits());
			if(!palette.isEmpty()){int mat=stock.key().material();counts[mat]++;volume[mat]=volume[mat].add(stock.volume());mass[mat]=Math.addExact(mass[mat],stock.massUnits());}
			if(stock.mixture()!=null){residual=Math.addExact(residual,stock.mixture().massResidualUnits());for(int i=0;i<stock.mixture().entryCount();i++)species[stock.mixture().speciesHandle(i)]=Math.addExact(species[stock.mixture().speciesHandle(i)],stock.mixture().amountAt(i));}
		}
		var materialLedgers=new ArrayList<RegionManifest.MaterialLedger>();for(int i=0;i<palette.size();i++)materialLedgers.add(new RegionManifest.MaterialLedger(counts[i],Double.doubleToRawLongBits(volume[i].toDouble()),mass[i]));
		var unsigned=new RegionManifest(layout,identity,geometry,source.runtimeIntent(),state.liquid().size(),Double.doubleToRawLongBits(totalVolume.toDouble()),palette,totalMass,materialLedgers,descriptors,extensions,
				chemistry==null?"":chemistry.fingerprint(),chemistry==null?0:ChemicalLimits.QUANTA_PER_MOL,chemistry==null?List.of():chemistry.species().stream().map(ChemicalSpecies::id).toList(),List.of(),species,residual,0);
		return new RegionCheckpoint(withChecksum(unsigned),sections);
	}
	static RegionSectionState withChecksum(RegionSectionState s) { return new RegionSectionState(s.layout(),s.regionId(),s.dimensionId(),s.checkpointGeneration(),s.sectionX(),s.sectionY(),s.sectionZ(),s.localStartX(),s.localStartY(),s.localStartZ(),s.sizeX(),s.sizeY(),s.sizeZ(),s.liquidStocks(),s.faceMomentum(),s.solidMask(),s.extensions(),RegionStateIntegrity.sectionChecksum(s)); }
	private static long[] bits(double[] values) {long[] bits=new long[values.length];for(int i=0;i<bits.length;i++)bits[i]=Double.doubleToRawLongBits(values[i]);return bits;}
	private static SignedDyadic energyBits(long[] bits) {var sum=SignedDyadic.ZERO;for(long value:bits)sum=sum.add(SignedDyadic.of(Double.longBitsToDouble(value)));return sum;}
	static RegionSectionState.SectionKey sectionOfCell(RegionGeometry geometry,int cell) {return sectionKey(geometry,(cell%geometry.sizeX()+.5F)*geometry.solverConfig().cellSize(),(cell/geometry.sizeX()%geometry.sizeY()+.5F)*geometry.solverConfig().cellSize(),(cell/(geometry.sizeX()*geometry.sizeY())+.5F)*geometry.solverConfig().cellSize());}
	private static RegionSectionState.SectionKey sectionKey(RegionGeometry g,float x,float y,float z) {return new RegionSectionState.SectionKey(Math.floorDiv((int)Math.floor(g.origin().x()+x),16),Math.floorDiv((int)Math.floor(g.origin().y()+y),16),Math.floorDiv((int)Math.floor(g.origin().z()+z),16));}

	private static RegionManifest withChecksum(RegionManifest manifest) {
		long checksum = RegionStateIntegrity.manifestChecksum(manifest);
		return new RegionManifest(manifest.layout(), manifest.regionId(), manifest.dimensionId(), manifest.checkpointGeneration(), manifest.networkGeneration(),
				manifest.origin(), manifest.sizeX(), manifest.sizeY(), manifest.sizeZ(), manifest.solverConfig(),
				manifest.runtime(), manifest.footprint(), manifest.totalMarkerCount(),
				manifest.totalVolumeBits(), manifest.materialPalette(), manifest.totalMassUnits(), manifest.materialLedgers(),
				manifest.sections(), manifest.extensions(), manifest.registryFingerprint(), manifest.quantaPerMol(),
				manifest.speciesPalette(), manifest.chemicalMixtures(), manifest.chemicalSpeciesTotals(),
				manifest.chemicalResidualLedgerUnits(), checksum);
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
		int capabilities = GasRegionStateCodec.CAP_GAS | (sections.getFirst().layout().hasMaterials() ? GasRegionStateCodec.CAP_MATERIALS : 0)
				| (chemicalState ? GasRegionStateCodec.CAP_CHEMISTRY : 0);
		if (chemicalState != !snapshot.chemicalRegistryFingerprint().isEmpty()) {
			throw new IOException("Gas snapshot chemical identity disagrees with liquid generation");
		}
		return new GasRegionStateCodec.ManifestData(capabilities, snapshot.registryFingerprint(),
				snapshot.chemicalRegistryFingerprint(), snapshot.nodes().stream()
				.map(node -> new GasRegionStateCodec.NodeData(node.topologyKey(), node.inventory())).toList(),
				snapshot.externalSource(), snapshot.escapedSink(),
				List.copyOf(ledgers));
	}

	private static GranularRegionStateCodec.ManifestData granularManifest(
			GranularStateSnapshot snapshot, List<RegionSectionState> sections, boolean chemicalState
	) throws IOException {
		int materials = snapshot.materialPalette().size();
		int[] activeCounts = new int[materials], depositCounts = new int[materials];
		long[] activeSvq = new long[materials], depositSvq = new long[materials];
		long[] activeMass = new long[materials], depositMass = new long[materials];
		long canonicalActiveMass = 0, canonicalDepositMass = 0;
		for (GranularStateSnapshot.ActiveParcel parcel : snapshot.activeParcels()) {
			int material = parcel.materialPaletteIndex(); activeCounts[material]++;
			activeSvq[material] = Math.addExact(activeSvq[material], parcel.solidVolumeSvq());
			activeMass[material] = Math.addExact(activeMass[material], parcel.massUnits());
			canonicalActiveMass = Math.addExact(canonicalActiveMass, parcel.massUnits());
		}
		for (GranularStateSnapshot.DepositGroup group : snapshot.depositGroups()) {
			int material = group.materialPaletteIndex(); depositCounts[material]++;
			depositSvq[material] = Math.addExact(depositSvq[material], group.solidVolumeSvq());
			depositMass[material] = Math.addExact(depositMass[material], group.massUnits());
			canonicalDepositMass = Math.addExact(canonicalDepositMass, group.massUnits());
		}
		List<GranularRegionStateCodec.MaterialLedger> materialLedgers = new ArrayList<>(materials);
		for (int material = 0; material < materials; material++) {
			materialLedgers.add(new GranularRegionStateCodec.MaterialLedger(activeCounts[material], activeSvq[material],
					activeMass[material], depositCounts[material], depositSvq[material],
					depositMass[material]));
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
			long sectionActiveMass = 0, sectionDepositMass = 0;
			int sectionDepositCells = 0, previousCell = -1;
			for (GranularRegionStateCodec.ActiveEntry entry : data.active()) {
				sectionActiveSvq = Math.addExact(sectionActiveSvq, entry.parcel().solidVolumeSvq());
				sectionActiveMass = Math.addExact(sectionActiveMass, entry.parcel().massUnits());
			}
			for (GranularStateSnapshot.DepositGroup group : data.deposits()) {
				sectionDepositSvq = Math.addExact(sectionDepositSvq, group.solidVolumeSvq());
				sectionDepositMass = Math.addExact(sectionDepositMass, group.massUnits());
				if (group.cell() != previousCell) { sectionDepositCells++; previousCell = group.cell(); }
			}
			sectionLedgers.add(new GranularRegionStateCodec.SectionLedger(section.key(), data.active().size(),
					data.deposits().size(), sectionDepositCells, sectionActiveSvq,
					sectionActiveMass, sectionDepositSvq,
					sectionDepositMass));
			allActiveSvq = Math.addExact(allActiveSvq, sectionActiveSvq);
			allDepositSvq = Math.addExact(allDepositSvq, sectionDepositSvq);
			depositCells += sectionDepositCells;
		}
		int capabilities = GranularRegionStateCodec.CAP_MATERIALS | GranularRegionStateCodec.CAP_GRANULAR
				| (chemicalState ? GranularRegionStateCodec.CAP_CHEMISTRY : 0)
				| (snapshot.requiresChemistry() ? GranularRegionStateCodec.CAP_PRECIPITATION : 0);
		return new GranularRegionStateCodec.ManifestData(capabilities, snapshot.registryFingerprint(),
				SolidVolume.SVQ_PER_CUBIC_METER, GranularLimits.POROSITY_SCALE,
				GranularLimits.MAX_ACTIVE_PARCELS_PER_REGION, GranularLimits.MAX_DEPOSIT_GROUPS_PER_REGION,
				snapshot.materialPalette(), snapshot.chemicalRegistryFingerprint(), snapshot.solidCompositions(),
				snapshot.solidSpeciesTotals(), snapshot.externalSource(), snapshot.escapedSink(),
				snapshot.activeParcels().size(), snapshot.depositGroups().size(), depositCells,
				allActiveSvq, canonicalActiveMass, allDepositSvq,
				canonicalDepositMass, List.copyOf(materialLedgers), List.copyOf(sectionLedgers));
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

}

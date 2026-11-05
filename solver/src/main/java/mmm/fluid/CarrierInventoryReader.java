package mmm.fluid;

import mmm.chemistry.ChemicalDisplaySnapshot;
import mmm.chemistry.ChemicalMixtureRecord;
import mmm.granular.GranularDisplaySnapshot;
import mmm.granular.GranularStateSnapshot;
import mmm.thermal.ThermalDisplaySnapshot;
import mmm.fluid.air.GasRegistry;

import java.util.ArrayList;

/**
 * Read-only carrier state export and display snapshot construction. Every method allocates a
 * fresh immutable snapshot from the authoritative arrays and never exposes the domain's own
 * storage; capture/restore and renderer adapters consume these as values.
 */
final class CarrierInventoryReader {
	private final HostFluidState domain;

	CarrierInventoryReader(HostFluidState domain) {
		this.domain = domain;
	}

	/** Disposable point view for diagnostics and existing GPU/render adapters. */
	private record Points(float[] packed, float[] velocity, float[] x, float[] y, float[] z, float[] volume,
	                      int[] material, long[] mass, double total) { }
	private Points points() {
		domain.domainFlowFields.ensureVelocityGrid();
		int count = domain.liquidCells.physicalSize();
		float[] packed = new float[4 * count], velocity = new float[3 * count];
		float[] x = new float[count], y = new float[count], z = new float[count], volume = new float[count];
		int[] material = new int[count]; long[] mass = new long[count];
		for (int i = 0; i < count; i++) {
			var stock = domain.liquidCells.physicalStockAt(i); int cell = stock.key().cell();
			int micro = Integer.numberOfTrailingZeros(stock.key().supportMask());
			float h = domain.config.cellSize();
			x[i] = (cell % domain.sizeX + .25F + .5F * (micro & 1)) * h;
			y[i] = (cell / domain.sizeX % domain.sizeY + .25F + .5F * (micro >> 1 & 1)) * h;
			z[i] = (cell / (domain.sizeX * domain.sizeY) + .25F + .5F * (micro >> 2 & 1)) * h;
			volume[i] = (float) stock.volumeFraction(); material[i] = stock.key().material(); mass[i] = stock.massUnits();
			packed[4 * i] = x[i]; packed[4 * i + 1] = y[i]; packed[4 * i + 2] = z[i]; packed[4 * i + 3] = volume[i];
			var flow = domain.sampleVelocity(x[i], y[i], z[i]);
			velocity[3 * i] = flow.x(); velocity[3 * i + 1] = flow.y(); velocity[3 * i + 2] = flow.z();
		}
		return new Points(packed, velocity, x, y, z, volume, material, mass, mmm.units.SignedDyadic.sum(volume, count).toDouble());
	}
	FlipParticleState exportParticleState() {
		var view = points();
		return new FlipParticleState(domain.sizeX, domain.sizeY, domain.sizeZ, domain.config.cellSize(), view.mass.length,
				(float) view.total, view.packed, view.velocity, view.mass, domain.geometry.cutCells.copyMicroMasks());
	}
	FlipMaterialParticleState exportMaterialParticleState() {
		if (domain.materialRegistry == null) throw new IllegalStateException("Material view requires a palette");
		var view = points();
		return new FlipMaterialParticleState(domain.sizeX, domain.sizeY, domain.sizeZ, domain.config.cellSize(), view.mass.length,
				view.total, domain.totalMassUnits(), domain.materialRegistry.materials(), view.packed, view.velocity,
				domain.geometry.cutCells.copyMicroMasks(), view.mass, view.material);
	}
	FlipChemicalParticleState exportChemicalParticleState() {
		if (domain.chemistry == null) throw new IllegalStateException("Chemical view requires chemistry");
		var mixtures = new ArrayList<ChemicalMixtureRecord>();
		for (int i = 0; i < domain.liquidCells.physicalSize(); i++) {
			var stock = domain.liquidCells.physicalStockAt(i); mixtures.add(stock.mixture());
		}
		return FlipChemicalParticleState.capture(exportMaterialParticleState(), domain.chemistry.registry(), mixtures,
				domain.chemistry.externalSource(), domain.chemistry.escapedSink());
	}

	FlipThermalParticleState exportThermalParticleState() {
		if (domain.thermalState == null) {
			throw new IllegalStateException("Thermal particle handoff requires thermal state");
		}
		return domain.captureAuthoritativeState().thermal();
	}

	GranularStateSnapshot exportGranularState() {
		if (domain.granularState == null) throw new IllegalStateException("Granular state is not bound");
		return GranularStateSnapshot.capture(domain.sizeX, domain.sizeY, domain.sizeZ, domain.granularState.registry,
				domain.granularState.parcels, domain.granularState.deposits, domain.granularState.compositions,
				domain.granularState.externalSource, domain.granularState.escapedSink);
	}

	GranularDisplaySnapshot copyGranularDisplaySnapshot() {
		if (domain.granularState == null) throw new IllegalStateException("Granular state is not bound");
		return GranularDisplaySnapshot.build(domain.sizeX, domain.sizeY, domain.sizeZ, domain.config.cellSize(),
				domain.granularState.registry, domain.granularState.parcels, domain.granularState.deposits);
	}

	ChemicalDisplaySnapshot copyChemicalDisplaySnapshot() {
		if (domain.chemistry == null) throw new IllegalStateException("Chemistry is not bound");
		var registry = domain.chemistry.registry();
		long[][] totals = new long[domain.cellCount][registry.species().size()];
		int fields = domain.cellCount * domain.materialRegistry.phaseCount();
		double[] aqueousVolume = new double[fields];
		long[] acid = new long[fields], base = new long[fields];
		boolean[] aqueous = new boolean[domain.materialRegistry.size()];
		for (int material = 0; material < aqueous.length; material++) {
			var definition = registry.phase(registry.phaseHandleForMaterial(domain.materialRegistry.material(material).id()));
			aqueous[material] = mmm.chemistry.ChemicalReactionEngine.supportsPh(definition);
		}
		int h = registry.hasSpecies(mmm.chemistry.BuiltinChemicalDefinitions.H_PLUS)
				? registry.speciesHandle(mmm.chemistry.BuiltinChemicalDefinitions.H_PLUS) : -1;
		int oh = registry.hasSpecies(mmm.chemistry.BuiltinChemicalDefinitions.OH_MINUS)
				? registry.speciesHandle(mmm.chemistry.BuiltinChemicalDefinitions.OH_MINUS) : -1;
		for (var stock : domain.liquidCells.stocks()) {
			int cell = stock.key().cell(), material = stock.key().material();
			var mixture = stock.mixture();
			for (int entry = 0; entry < mixture.entryCount(); entry++) {
				int species = mixture.speciesHandle(entry);
				totals[cell][species] = Math.addExact(totals[cell][species], mixture.amountAt(entry));
			}
			if (aqueous[material]) {
				int offset = domain.materialRegistry.phaseHandle(material) * domain.cellCount + cell;
				aqueousVolume[offset] += stock.volumeFraction();
				acid[offset] = Math.addExact(acid[offset], mixture.amount(h));
				base[offset] = Math.addExact(base[offset], mixture.amount(oh));
			}
		}
		byte[] ph = new byte[fields];
		java.util.Arrays.fill(ph, (byte) ChemicalDisplaySnapshot.PH_NO_DATA);
		var units = mmm.units.SolverUnitScale.forCellSize(domain.config.cellSize());
		for (int offset = 0; offset < fields; offset++) if (aqueousVolume[offset] > 0.0) {
			ph[offset] = (byte) Math.round(ChemicalDisplaySnapshot.PH_SCALE * mmm.chemistry.ChemicalReactionEngine.ph(
					acid[offset], base[offset], units.cubicMeters(aqueousVolume[offset])));
		}
		return ChemicalDisplaySnapshot.fromTotals(registry, totals, ph);
	}

	ThermalDisplaySnapshot copyThermalDisplaySnapshot() {
		if (domain.thermalState == null) throw new IllegalStateException("Thermal state is not bound");
		var display=domain.thermalState.displaySnapshot(domain.domainCapabilities.thermalMarkerContext(), domain.gasState.network);
		if(domain.gasState.spatial==null)return display;
		var local=domain.gasState.spatial;long[] occupied=domain.occupiedSolidSvq();var model=domain.domainCapabilities.spatialGasModel();
		var nodes=new ArrayList<ThermalDisplaySnapshot.GasNode>();
		for(int cell=0;cell<domain.cellCount;cell++){
			var gas=local.cells().get(cell);if(gas.isEmpty())continue;
			double temperature=model.state(gas,local.energyAt(cell)).temperatureK();
			double pressure=DomainGasInventory.mixture(domain,cell,gas,local.energyAt(cell),domain.liquidCells,occupied,null).absolutePressurePa()-GasRegistry.STANDARD_PRESSURE_PA;
			int tq=(int)Math.clamp(Math.round(temperature*16),1,ThermalDisplaySnapshot.MAX_TEMPERATURE_Q);
			int pq=(int)Math.clamp(Math.round(pressure/10),Integer.MIN_VALUE,Integer.MAX_VALUE);
			nodes.add(new ThermalDisplaySnapshot.GasNode(cell+1,tq,pq));
		}
		return new ThermalDisplaySnapshot(display.cellTemperatureQ(),display.coherentFraction(),display.debugFlags(),nodes);
	}

	/** Read-only material rendering reconstruction; it contains no exact inventory and is not a wire format. */
	FluidMaterialRenderSnapshot copyMaterialRenderSnapshot() {
		domain.domainFlowFields.ensureInventoryGrid();
		var registry = domain.materialRegistry == null ? new FluidMaterialRegistry(java.util.List.of(
				new FluidMaterial("mmm:water", domain.config.density(), "mmm:aqueous", 0, Math.PI / 2))) : domain.materialRegistry;
		domain.pressureSystem.prepareGeometry();
		var inventory = domain.liquidCells.snapshot();
		var phase = domain.pressureSystem.phaseGeometry();
		var geometry = FluidSurfaceProjection.capture(domain.sizeX, domain.sizeY, domain.sizeZ, domain.config.cellSize(),
				registry, inventory, domain.geometry.cutCells.snapshot(), phase);
		return FluidMaterialRenderSnapshot.capture(domain.sizeX, domain.sizeY, domain.sizeZ, domain.config.cellSize(),
				registry, inventory, geometry);
	}

	byte[] copyQuantizedVolume() {
		domain.domainFlowFields.ensureInventoryGrid();
		byte[] result = new byte[domain.cellCount];
		for (int i = 0; i < domain.cellCount; i++) {
			result[i] = (byte) Math.round(HostFluidState.clamp01(domain.volume[i]) * 255.0F);
		}
		return result;
	}

	/** Binary mask: 1 where a cell is entirely solid. Not interchangeable with copyCollisionMicroMasks(). */
	byte[] copyBinarySolidMask() {
		return domain.geometry.cutCells.copyBinaryFullMask();
	}

	/**
	 * The 2x2x2 cut-cell occupancy this domain actually resolves against. A particle state round-trips
	 * collision through this encoding, so handing it a binary mask silently drops every solid cell:
	 * the collision field is decoded as micro bits and nothing reads back as solid.
	 */
	byte[] copyCollisionMicroMasks() {
		return domain.geometry.cutCells.copyMicroMasks();
	}

	/** Actual pressure-phase occupancy, separate from the smooth P2G density. */
	byte[] copyMarkerMask() {
		domain.domainFlowFields.ensureInventoryGrid();
		byte[] result = new byte[domain.cellCount];
		for (int i = 0; i < domain.cellCount; i++) result[i] = domain.liquidGrid.stockCount[i] > 0 ? (byte) 1 : (byte) 0;
		return result;
	}
}

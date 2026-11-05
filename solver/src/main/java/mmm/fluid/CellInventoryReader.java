package mmm.fluid;

import mmm.chemistry.ChemicalMixtureRecord;
import mmm.granular.GranularParcelStore;
import mmm.thermal.CoherentPhaseStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalDouble;
import java.util.TreeMap;

/**
 * Read-only per-cell observation of liquid and solid carriers plus the committed gas topology.
 * It scans authoritative marker/store truth without rebuilding grids or changing activity;
 * empty cells return {@code null}. This is the core-side source for loader query adapters.
 */
final class CellInventoryReader {
	private final HostFluidState domain;

	CellInventoryReader(HostFluidState domain) {
		this.domain = domain;
	}

	int nextMarkerInCell(int cell, int after) {
		var stocks = domain.liquidCells.physicalStocks();
		int low = after < 0 ? 0 : after + 1, high = stocks.size();
		while (low < high) { int middle = (low + high) >>> 1;
			if (stocks.get(middle).key().cell() < cell) low = middle + 1; else high = middle;
		}
		return low < stocks.size() && stocks.get(low).key().cell() == cell ? low : -1;
	}

	CellCarrierInventoryView observeCellCarriers(int x, int y, int z) {
		int requested = domain.domainFlowFields.cellIndexChecked(x, y, z);
		TreeMap<String, SolidObservationAccumulator> accumulated = new TreeMap<>();
		if (domain.granularState != null) for (int ordinal = 0; ordinal < domain.granularState.parcels.count(); ordinal++) {
			GranularParcelStore.Parcel parcel = domain.granularState.parcels.parcel(ordinal);
			if (domain.domainInventoryGeometry.granularParcelCell(parcel) == requested) addObservedSolid(accumulated,
					CellCarrierInventoryView.SolidKind.ACTIVE,
					domain.granularState.registry.material(parcel.materialHandle()).id(), parcel.solidVolumeSvq(), parcel.mass());
		}
		if (domain.granularState != null) domain.granularState.deposits.forEachGroup(
				(cell, ignoredPorosity, material, ignoredComposition, svq, mass) -> {
			if (cell == requested) addObservedSolid(accumulated, CellCarrierInventoryView.SolidKind.DEPOSIT,
					domain.granularState.registry.material(material).id(), svq, mmm.units.Mass.kilograms(mass));
		});
		if (domain.thermalState != null) {
			for (CoherentPhaseStore.Group group : domain.thermalState.coherentPhases().snapshot()) {
				if (group.cell() == requested) addObservedSolid(accumulated,
						CellCarrierInventoryView.SolidKind.COHERENT,
						group.phaseId(), group.occupiedVolumeSvq(), group.mass());
			}
		}
		ArrayList<CellCarrierInventoryView.SolidAmount> solids =
				new ArrayList<>(accumulated.size());
		for (SolidObservationAccumulator value : accumulated.values()) {
			solids.add(new CellCarrierInventoryView.SolidAmount(
					value.kind, value.stableId, value.svq, value.mass));
		}

		CellCarrierInventoryView.GasAmount gas = null;
		if(domain.gasState.spatial!=null){
			var local=domain.gasState.spatial.cells().get(requested);
			if(domain.gasBoundary.cellNode(requested)>0){
				var components=local.components().stream().map(component->new CellCarrierInventoryView.ComponentAmount(component.componentId(),component.standardVolumeNanos(),component.mass())).toList();
				gas=new CellCarrierInventoryView.GasAmount(false,requested+1,DomainGasInventory.spatialPressure(domain,requested),DomainGasInventory.spatialTemperature(domain,requested),components);
			}
		}

		return solids.isEmpty() && gas == null ? null : new CellCarrierInventoryView(solids, gas);
	}

	boolean hasCondensedInventoryInCell(int x, int y, int z) {
		int requested = domain.domainFlowFields.cellIndexChecked(x, y, z);
		if (nextMarkerInCell(requested, -1) >= 0) return true;
		if (domain.granularState != null) {
			for (int ordinal = 0; ordinal < domain.granularState.parcels.count(); ordinal++) {
				if (domain.domainInventoryGeometry.granularParcelCell(domain.granularState.parcels.parcel(ordinal)) == requested) return true;
			}
		}
		if (domain.granularState != null && domain.granularState.deposits.cellSolidVolumeSvq(requested) > 0L) return true;
		return domain.thermalState != null && domain.thermalState.coherentPhases().cellOccupiedSvq(requested) > 0L;
	}

	FluidCellInventoryView observeCellInventory(int x, int y, int z) {
		int requested = domain.domainFlowFields.cellIndexChecked(x, y, z);
		double normalizedVolume = 0.0, solverMass = 0.0;
		double[] materialVolume = domain.materialRegistry == null
				? new double[]{0.0} : new double[domain.materialRegistry.size()];
		double[] materialMass = new double[materialVolume.length];
		TreeMap<String, Long> species = new TreeMap<>();
		for (var stock : domain.liquidCells.stocksInCell(requested)) {
			normalizedVolume += stock.volumeFraction();
			double mass = domain.unitScale.normalizedMass(stock.massUnits()); solverMass += mass;
			int material = stock.key().material();
			materialVolume[material] += stock.volumeFraction(); materialMass[material] += mass;
			if (stock.mixture() != null) {
				var mixture = stock.mixture();
				for (int entry = 0; entry < mixture.entryCount(); entry++) species.merge(
						domain.liquidCells.chemicalRegistry().species(mixture.speciesHandle(entry)).id(), mixture.amountAt(entry), Math::addExact);
			}
		}
		if (!(normalizedVolume > 0.0) || !(solverMass > 0.0)) return null;
		double cubicMetresPerCell = domain.unitScale.cubicMetersPerNormalizedVolume();
		ArrayList<FluidCellInventoryView.MaterialAmount> materials = new ArrayList<>();
		for (int material = 0; material < materialVolume.length; material++) if (materialVolume[material] > 0.0) {
			String id = domain.materialRegistry == null ? BuiltinFluidMaterials.WATER.id()
					: domain.materialRegistry.material(material).id();
			materials.add(new FluidCellInventoryView.MaterialAmount(id,
					materialVolume[material] * cubicMetresPerCell,
					materialMass[material] * cubicMetresPerCell));
		}
		materials.sort(Comparator.comparing(FluidCellInventoryView.MaterialAmount::materialId));
		ArrayList<FluidCellInventoryView.SpeciesAmount> stableSpecies = new ArrayList<>();
		species.forEach((id, amount) -> stableSpecies.add(
				new FluidCellInventoryView.SpeciesAmount(id, amount)));
		OptionalDouble temperature = domain.thermalState == null ? OptionalDouble.empty()
				: OptionalDouble.of(domain.thermalState.observeTemperatureInCell(requested, domain.domainCapabilities.thermalMarkerContext()));
		return new FluidCellInventoryView(normalizedVolume * cubicMetresPerCell,
				solverMass * cubicMetresPerCell, solverMass / normalizedVolume,
				temperature, materials, stableSpecies);
	}

	double thermalTemperatureInCell(int x, int y, int z) {
		if (domain.thermalState == null) throw new IllegalStateException("Thermal state is not bound");
		return domain.thermalState.temperatureInCell(domain.domainFlowFields.cellIndexChecked(x, y, z), domain.domainCapabilities.thermalMarkerContext());
	}

	long granularSolidVolumeSvq(int cellX, int cellY, int cellZ) {
		if (domain.granularCoupling == null) return 0L;
		int cell = domain.domainFlowFields.cellIndexChecked(cellX, cellY, cellZ);
		domain.domainFlowFields.ensureInventoryGrid();
		return domain.granularCoupling.solidSvq(cell);
	}

	private static void addObservedSolid(
			TreeMap<String, SolidObservationAccumulator> accumulated,
			CellCarrierInventoryView.SolidKind kind, String stableId, long svq, double mass
	) {
		String key = kind.name() + '\0' + stableId;
		SolidObservationAccumulator current = accumulated.get(key);
		if (current == null) accumulated.put(key, new SolidObservationAccumulator(kind, stableId, svq, mass));
		else {
			current.svq = Math.addExact(current.svq, svq);
			current.mass += mass;
			if (!Double.isFinite(current.mass)) throw new ArithmeticException("Observed solid mass overflow");
		}
	}

	private static final class SolidObservationAccumulator {
		private final CellCarrierInventoryView.SolidKind kind;
		private final String stableId;
		private long svq;
		private double mass;

		private SolidObservationAccumulator(
				CellCarrierInventoryView.SolidKind kind, String stableId, long svq, double mass
		) {
			this.kind = kind;
			this.stableId = stableId;
			this.svq = svq;
			this.mass = mass;
		}
	}
}

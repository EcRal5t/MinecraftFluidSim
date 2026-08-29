package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.chemistry.ChemicalMixtureRecord;
import cc.ecisr.mmm.fluid.air.AirNetwork;
import cc.ecisr.mmm.fluid.air.GasRegistry;
import cc.ecisr.mmm.granular.GranularParcelStore;
import cc.ecisr.mmm.thermal.CoherentPhaseStore;

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
	private final FlipFluidDomain domain;

	CellInventoryReader(FlipFluidDomain domain) {
		this.domain = domain;
	}

	CellCarrierInventoryView observeCellCarriers(int x, int y, int z) {
		int requested = domain.cellIndexChecked(x, y, z);
		TreeMap<String, SolidObservationAccumulator> accumulated = new TreeMap<>();
		if (domain.granularState != null) for (int ordinal = 0; ordinal < domain.granularState.parcels.count(); ordinal++) {
			GranularParcelStore.Parcel parcel = domain.granularState.parcels.parcel(ordinal);
			if (domain.granularParcelCell(parcel) == requested) addObservedSolid(accumulated,
					CellCarrierInventoryView.SolidKind.ACTIVE,
					domain.granularState.registry.material(parcel.materialHandle()).id(), parcel.solidVolumeSvq(), parcel.mass());
		}
		if (domain.granularState != null) domain.granularState.deposits.forEachGroup(
				(cell, ignoredPorosity, material, ignoredComposition, svq, mass) -> {
			if (cell == requested) addObservedSolid(accumulated, CellCarrierInventoryView.SolidKind.DEPOSIT,
					domain.granularState.registry.material(material).id(), svq, mass);
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
		if (domain.airCellNodeId != null) {
			int node = domain.airCellNodeId[requested];
			if (node == AirNetwork.AMBIENT_NODE) {
				gas = new CellCarrierInventoryView.GasAmount(true, node, AirNetwork.REFERENCE_PRESSURE,
						GasRegistry.STANDARD_TEMPERATURE_K, List.of());
			} else if (node > 0) {
				List<CellCarrierInventoryView.ComponentAmount> components =
						domain.airNetwork.inventory(node).components()
						.stream().map(component -> new CellCarrierInventoryView.ComponentAmount(
								component.componentId(), component.standardVolumeNanos(), component.mass())).toList();
				gas = new CellCarrierInventoryView.GasAmount(false, node,
						AirNetwork.REFERENCE_PRESSURE + observedGasNodeGaugePressurePa(node),
						domain.gasNodeTemperatureK(node), components);
			}
		}
		return solids.isEmpty() && gas == null ? null : new CellCarrierInventoryView(solids, gas);
	}

	double observedGasNodeGaugePressurePa(int nodeId) {
		return domain.thermalState == null || !domain.thermalState.gasModelBound()
				? domain.airNetwork.observedGaugePressure(nodeId)
				: domain.thermalState.gasGaugePressure(nodeId, domain.airNetwork);
	}

	boolean hasCondensedInventoryInCell(int x, int y, int z) {
		int requested = domain.cellIndexChecked(x, y, z);
		for (int marker = 0; marker < domain.particles.count(); marker++) {
			if (domain.particleCellIndex(marker) == requested) return true;
		}
		if (domain.granularState != null) {
			for (int ordinal = 0; ordinal < domain.granularState.parcels.count(); ordinal++) {
				if (domain.granularParcelCell(domain.granularState.parcels.parcel(ordinal)) == requested) return true;
			}
		}
		if (domain.granularState != null && domain.granularState.deposits.cellSolidVolumeSvq(requested) > 0L) return true;
		return domain.thermalState != null && domain.thermalState.coherentPhases().cellOccupiedSvq(requested) > 0L;
	}

	FluidCellInventoryView observeCellInventory(int x, int y, int z) {
		int requested = domain.cellIndexChecked(x, y, z);
		double normalizedVolume = 0.0, solverMass = 0.0;
		double[] materialVolume = domain.materialRegistry == null
				? new double[]{0.0} : new double[domain.materialRegistry.size()];
		double[] materialMass = new double[materialVolume.length];
		TreeMap<String, Long> species = new TreeMap<>();
		for (int marker = 0; marker < domain.particles.count(); marker++) {
			if (domain.particleCellIndex(marker) != requested) continue;
			normalizedVolume += domain.particles.volume(marker);
			solverMass += domain.particles.mass(marker);
			int material = domain.materialRegistry == null ? 0 : domain.particles.materialHandle(marker);
			materialVolume[material] += domain.particles.volume(marker);
			materialMass[material] += domain.particles.mass(marker);
			if (domain.chemicalParcels != null) {
				ChemicalMixtureRecord mixture = domain.chemicalParcels.record(marker);
				for (int entry = 0; entry < mixture.entryCount(); entry++) {
					species.merge(domain.chemicalParcels.registry().species(mixture.speciesHandle(entry)).id(),
							mixture.amountAt(entry), Math::addExact);
				}
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
				: OptionalDouble.of(domain.thermalState.observeTemperatureInCell(requested, domain.thermalMarkerContext()));
		return new FluidCellInventoryView(normalizedVolume * cubicMetresPerCell,
				solverMass * cubicMetresPerCell, solverMass / normalizedVolume,
				temperature, materials, stableSpecies);
	}

	double thermalTemperatureInCell(int x, int y, int z) {
		if (domain.thermalState == null) throw new IllegalStateException("Thermal state is not bound");
		return domain.thermalState.temperatureInCell(domain.cellIndexChecked(x, y, z), domain.thermalMarkerContext());
	}

	long granularSolidVolumeSvq(int cellX, int cellY, int cellZ) {
		if (domain.granularCoupling == null) return 0L;
		int cell = domain.cellIndexChecked(cellX, cellY, cellZ);
		domain.ensureGrid();
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

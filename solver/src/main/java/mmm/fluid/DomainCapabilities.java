package mmm.fluid;

import mmm.inventory.LiquidCellInventory;
import mmm.thermal.ThermalDomainState;
import mmm.thermal.ThermalCarrierContext;
import mmm.chemistry.ChemicalParcelStore;
import mmm.granular.GranularCouplingFields;
import mmm.granular.GranularPhaseTransferEngine;
import java.util.List;
import mmm.units.SignedDyadic;
import mmm.chemistry.ChemicalMixtureRecord;
import mmm.chemistry.ChemicalTransferLedger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import mmm.granular.GranularInventory;
import mmm.granular.GranularParcelStore;
import mmm.granular.SolidVolume;
import mmm.thermal.GranularEnergyColumn;
import mmm.thermal.ThermalLimits;
import mmm.thermal.ThermalTransport;
import mmm.granular.GranularDepositStore;
import mmm.thermal.CoherentPhaseStore;
import mmm.chemistry.ChemicalRegistry;
import java.util.Map;
import mmm.thermal.FiniteDeviceExchangeCoordinator;
import mmm.thermal.ThermalOwnerSnapshot;
import mmm.chemistry.ChemicalDisplaySnapshot;
import mmm.granular.GranularLimits;
import mmm.granular.SolidCompositionPool;
import mmm.thermal.FiniteThermalExchange;
import mmm.thermal.ThermalRegistry;
import mmm.thermal.ThermalDisplaySnapshot;
import java.util.ArrayDeque;
import static mmm.fluid.FluidDomain.*;

/** Binds chemistry and thermal capabilities and exposes their actual inventory owners. */
final class DomainCapabilities {
	private final FluidDomain domain;
	DomainCapabilities(FluidDomain domain) { this.domain = domain; }
	public FluidMaterialRegistry materialRegistry() { return domain.materialRegistry; }
	public ChemicalParcelStore chemicalParcels() {
		if (domain.chemicalParcels == null) throw new IllegalStateException("Chemistry is not bound");
		return domain.chemicalParcels;
	}
	public ChemicalDisplaySnapshot copyChemicalDisplaySnapshot() { return domain.carriers.copyChemicalDisplaySnapshot(); }
	public ChemicalTransferLedger chemicalLedgerForMarkerRange(int fromInclusive, int toExclusive) {
		if (domain.chemicalParcels == null) throw new IllegalStateException("Chemistry is not bound");
		if (fromInclusive < 0 || toExclusive < fromInclusive || toExclusive > domain.liquidCells.physicalSize()) {
			throw new IndexOutOfBoundsException("Invalid chemical marker range");
		}
		ChemicalTransferLedger.Builder result = ChemicalTransferLedger.builder(domain.chemicalParcels.registry());
		for (int marker = fromInclusive; marker < toExclusive; marker++) {
			result.add(domain.materialRegistry.material(domain.liquidCells.physicalStockAt(marker).key().material()).id(), domain.liquidCells.physicalStockAt(marker).indicator(),
					(float) domain.liquidCells.physicalStockAt(marker).volumeFraction(), domain.liquidCells.physicalStockAt(marker).massUnits(), domain.liquidCells.physicalStockAt(marker).mixture());
		}
		return result.build();
	}
	public IndicatorEditResult editIndicatorFloodFill(
			int startX, int startY, int startZ, boolean indicated, int maxScannedCells, double maximumAddedVolumeCubicMeters
	) {
		if (domain.chemicalParcels == null || domain.materialRegistry == null) {
			return IndicatorEditResult.rejected("Chemistry is not bound");
		}
		if (maxScannedCells <= 0 || maxScannedCells > domain.cellCount) {
			throw new IllegalArgumentException("Indicator scan budget must be within the region cell budget");
		}
		if (indicated && !(Double.isFinite(maximumAddedVolumeCubicMeters) && maximumAddedVolumeCubicMeters > 0.0)) {
			throw new IllegalArgumentException("Indicator volume budget must be finite and positive");
		}
		int requestedStart = domain.domainFlowFields.cellIndexChecked(startX, startY, startZ);
		int[] dominantPhase = new int[domain.cellCount];
		float[] dominantVolume = new float[domain.cellCount];
		int[] dominantMarker = new int[domain.cellCount];
		Arrays.fill(dominantPhase, -1);
		Arrays.fill(dominantVolume, -1.0F);
		Arrays.fill(dominantMarker, Integer.MAX_VALUE);
		for (int marker = 0; marker < domain.liquidCells.physicalSize(); marker++) {
			int cell = domain.liquidCells.physicalStockAt(marker).key().cell();
			float candidate = (float) domain.liquidCells.physicalStockAt(marker).volumeFraction();
			if (candidate > dominantVolume[cell]
					|| candidate == dominantVolume[cell] && marker < dominantMarker[cell]) {
				dominantVolume[cell] = candidate;
				dominantMarker[cell] = marker;
				dominantPhase[cell] = domain.chemicalParcels.registry().phaseHandleForMaterial(
						domain.materialRegistry.material(domain.liquidCells.physicalStockAt(marker).key().material()).id());
			}
		}
		IndicatorAimSeed seed = resolveIndicatorAimSeed(requestedStart, dominantPhase, maxScannedCells);
		if (seed.cell() < 0) return IndicatorEditResult.rejected(seed.failure());
		int start = seed.cell();
		int targetPhase = dominantPhase[start];
		if (indicated && !domain.chemicalParcels.registry().phase(targetPhase).acceptsIndicator()) {
			return IndicatorEditResult.rejected("Target chemical phase rejects indicator");
		}

		int[] cellHead = new int[domain.cellCount];
		int[] markerNext = new int[domain.liquidCells.physicalSize()];
		boolean[] compatible = new boolean[domain.cellCount];
		Arrays.fill(cellHead, -1);
		for (int marker = domain.liquidCells.physicalSize() - 1; marker >= 0; marker--) {
			String materialId = domain.materialRegistry.material(domain.liquidCells.physicalStockAt(marker).key().material()).id();
			if (domain.chemicalParcels.registry().phaseHandleForMaterial(materialId) != targetPhase) continue;
			int cell = domain.liquidCells.physicalStockAt(marker).key().cell();
			markerNext[marker] = cellHead[cell];
			cellHead[cell] = marker;
			compatible[cell] = true;
		}

		boolean[] visited = new boolean[domain.cellCount];
		ArrayDeque<Integer> queue = new ArrayDeque<>();
		ArrayList<Integer> affected = new ArrayList<>();
		visited[start] = true;
		queue.add(start);
		int scanned = seed.scannedCells() - 1;
		double cellVolume = 0.0;
		while (!queue.isEmpty()) {
			int cell = queue.removeFirst();
			if (++scanned > maxScannedCells) return IndicatorEditResult.rejected("Indicator scan budget exceeded");
			for (int marker = cellHead[cell]; marker >= 0; marker = markerNext[marker]) {
				affected.add(marker);
				cellVolume += (float) domain.liquidCells.physicalStockAt(marker).volumeFraction();
			}
			double cubicMeters = domain.unitScale.cubicMeters(cellVolume);
			if (indicated && cubicMeters > maximumAddedVolumeCubicMeters) {
				return IndicatorEditResult.rejected("Indicator volume exceeds " + maximumAddedVolumeCubicMeters + " m³");
			}
			int x = cell % domain.sizeX;
			int yz = cell / domain.sizeX;
			int y = yz % domain.sizeY;
			int z = yz / domain.sizeY;
			if (x > 0) enqueueIndicatorNeighbor(cell, domain.domainFlowFields.cellIndex(x - 1, y, z), -1, 0, 0, compatible, visited, queue);
			if (x + 1 < domain.sizeX) enqueueIndicatorNeighbor(cell, domain.domainFlowFields.cellIndex(x + 1, y, z), 1, 0, 0, compatible, visited, queue);
			if (y > 0) enqueueIndicatorNeighbor(cell, domain.domainFlowFields.cellIndex(x, y - 1, z), 0, -1, 0, compatible, visited, queue);
			if (y + 1 < domain.sizeY) enqueueIndicatorNeighbor(cell, domain.domainFlowFields.cellIndex(x, y + 1, z), 0, 1, 0, compatible, visited, queue);
			if (z > 0) enqueueIndicatorNeighbor(cell, domain.domainFlowFields.cellIndex(x, y, z - 1), 0, 0, -1, compatible, visited, queue);
			if (z + 1 < domain.sizeZ) enqueueIndicatorNeighbor(cell, domain.domainFlowFields.cellIndex(x, y, z + 1), 0, 0, 1, compatible, visited, queue);
		}
		int changed = 0;
		var edits = new ArrayList<LiquidCellInventory.Edit>();
		for (int ordinal : affected) {
			var stock = domain.liquidCells.physicalStockAt(ordinal);
			if (stock.indicator() != indicated) {
				edits.add(new LiquidCellInventory.Edit(stock.key(), new LiquidCellInventory.Stock(stock.key(), stock.volume(), stock.massUnits(),
						stock.mixture(), stock.internalEnergy(), indicated)));
				changed++;
			}
		}
		domain.liquidCells.planEdits(edits).commit();

		double cubicMeters = domain.unitScale.cubicMeters(cellVolume);
		return new IndicatorEditResult(true, scanned, changed, cubicMeters, null);
	}
	IndicatorAimSeed resolveIndicatorAimSeed(int requested, int[] dominantPhase, int maxScannedCells) {
		int[] distance = new int[domain.cellCount];
		Arrays.fill(distance, -1);
		ArrayDeque<Integer> queue = new ArrayDeque<>();
		distance[requested] = 0;
		queue.add(requested);
		int scanned = 0;
		while (!queue.isEmpty()) {
			int cell = queue.removeFirst();
			if (++scanned > maxScannedCells) {
				return new IndicatorAimSeed(-1, scanned, "Indicator aim scan budget exceeded");
			}
			if (dominantPhase[cell] >= 0) return new IndicatorAimSeed(cell, scanned, null);
			if (distance[cell] >= FluidDomain.INDICATOR_AIM_SNAP_CELLS) continue;
			int x = cell % domain.sizeX;
			int yz = cell / domain.sizeX;
			int y = yz % domain.sizeY;
			int z = yz / domain.sizeY;
			if (x > 0) enqueueIndicatorAimNeighbor(cell, domain.domainFlowFields.cellIndex(x - 1, y, z), -1, 0, 0, distance, queue);
			if (x + 1 < domain.sizeX) enqueueIndicatorAimNeighbor(cell, domain.domainFlowFields.cellIndex(x + 1, y, z), 1, 0, 0, distance, queue);
			if (y > 0) enqueueIndicatorAimNeighbor(cell, domain.domainFlowFields.cellIndex(x, y - 1, z), 0, -1, 0, distance, queue);
			if (y + 1 < domain.sizeY) enqueueIndicatorAimNeighbor(cell, domain.domainFlowFields.cellIndex(x, y + 1, z), 0, 1, 0, distance, queue);
			if (z > 0) enqueueIndicatorAimNeighbor(cell, domain.domainFlowFields.cellIndex(x, y, z - 1), 0, 0, -1, distance, queue);
			if (z + 1 < domain.sizeZ) enqueueIndicatorAimNeighbor(cell, domain.domainFlowFields.cellIndex(x, y, z + 1), 0, 0, 1, distance, queue);
		}
		return new IndicatorAimSeed(-1, scanned, "Target surface has no nearby chemical liquid marker");
	}
	void enqueueIndicatorAimNeighbor(
			int from, int neighbor, int dx, int dy, int dz, int[] distance, ArrayDeque<Integer> queue
	) {
		if (distance[neighbor] < 0 && domain.domainFlowFields.openFaceFromCell(from, dx, dy, dz) > 0.0F) {
			distance[neighbor] = distance[from] + 1;
			queue.addLast(neighbor);
		}
	}
	void enqueueIndicatorNeighbor(
			int from, int neighbor, int dx, int dy, int dz, boolean[] compatible,
			boolean[] visited, ArrayDeque<Integer> queue
	) {
		if (!visited[neighbor] && compatible[neighbor] && domain.domainFlowFields.openFaceFromCell(from, dx, dy, dz) > 0.0F) {
			visited[neighbor] = true;
			queue.addLast(neighbor);
		}
	}
	public void bindChemistry(ChemicalRegistry registry) {
		if (domain.thermalState != null) {
			throw new IllegalStateException("Chemistry must be bound before transient thermal initialization");
		}
		if (domain.materialRegistry == null) throw new IllegalStateException("Chemistry requires a material registry");
		if (domain.chemicalParcels != null) {
			if (!domain.chemicalParcels.registry().fingerprint().equals(registry.fingerprint())) {
				throw new IllegalArgumentException("A domain cannot change its chemical registry");
			}
			return;
		}
		if (domain.granularState != null && domain.granularState.registry.hasReactiveRules()
				&& !domain.granularState.registry.chemicalRegistryFingerprint().equals(registry.fingerprint())) {
			throw new IllegalArgumentException("Granular registry chemical dependency mismatch");
		}
		ChemicalParcelStore candidate = new ChemicalParcelStore(
				registry, domain.unitScale, domain.maximumParticleCount, domain.cellCount);
		var additions = new ArrayList<LiquidCellInventory.Stock>();
		for (var stock : domain.liquidCells.stocks()) {
			var mixture = candidate.defaultRecord(domain.materialRegistry.material(stock.key().material()).id(),
					stock.volumeFraction(), domain.config.cellSize(), stock.massUnits(), stock.indicator());
			additions.add(new LiquidCellInventory.Stock(stock.key(), stock.volume(), stock.massUnits(), mixture, stock.internalEnergy(), stock.indicator()));
		}
		var inventory = new LiquidCellInventory(domain.cellCount, registry);
		inventory.planAdditions(additions).commit();
		domain.liquidCells = inventory;
		domain.chemicalParcels = candidate;
		for (var stock : domain.liquidCells.stocks()) domain.chemicalParcels.markDirtyCell(stock.key().cell());
		domain.markCellInventoryChanged();
		bindSolidCompositionPoolIfReady();
	}
	public void bindThermal(
			ThermalRegistry registry, FluidMaterialRegistry liquidRegistry, double initialTemperatureK
	) {
		bindThermal(registry, liquidRegistry, initialTemperatureK, ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS);
	}
	public void bindThermal(
			ThermalRegistry registry, FluidMaterialRegistry liquidRegistry, double initialTemperatureK,
			int maximumTransportSubsteps
	) {
		if (!prepareThermalBinding(registry, liquidRegistry, initialTemperatureK)) return;
		domain.gasTopologyCoordinator.refresh();
		domain.thermalState = FluidThermalBinding.create(registry, domain.materialRegistry, initialTemperatureK,
				domain.liquidCells.physicalSize(), domain.sizeX, domain.sizeY, domain.sizeZ, domain.config.cellSize(), thermalMarkerContext(), domain.gasState.network,
				maximumTransportSubsteps);
		finishThermalBinding();
	}
	private boolean prepareThermalBinding(
			ThermalRegistry registry, FluidMaterialRegistry liquidRegistry, double initialTemperatureK
	) {
		if (registry == null || liquidRegistry == null) throw new IllegalArgumentException("Thermal/liquid registry is missing");
		if (!Double.isFinite(initialTemperatureK)) throw new IllegalArgumentException("Initial thermal temperature must be finite");
		if (Float.floatToRawIntBits(domain.config.cellSize()) != Float.floatToRawIntBits(0.5F)) {
			throw new IllegalArgumentException("Thermal state requires a 0.5 m solver cell");
		}
		domain.bindMaterialRegistry(liquidRegistry);
		if (domain.thermalState != null) {
			if (!domain.thermalState.registry().fingerprint().equals(registry.fingerprint())) {
				throw new IllegalArgumentException("A domain cannot change its thermal registry");
			}
			return false;
		}
		if (domain.chemicalParcels != null && !registry.chemicalRegistryFingerprint()
				.equals(domain.chemicalParcels.registry().fingerprint())) {
			throw new IllegalArgumentException("Thermal registry chemical dependency mismatch");
		}
		return true;
	}
	private void finishThermalBinding() {
		if (domain.granularCoupling == null) {
			domain.granularCoupling = new GranularCouplingFields(domain.sizeX, domain.sizeY, domain.sizeZ, domain.config.cellSize());
			domain.granularNextCoupling = new GranularCouplingFields(domain.sizeX, domain.sizeY, domain.sizeZ, domain.config.cellSize());
		}
		refreshCoherentOccupancy();
		domain.gridDirty = domain.pressureGeometryDirty = domain.velocityGridDirty = true;
		if (domain.granularState != null) domain.granularState.inventory.bindThermalEnergy(thermalGranularAdapter());
	}
	GranularInventory.EnergyAdapter thermalGranularAdapter() {
		return new GranularInventory.EnergyAdapter() {
			@Override public GranularInventory.EnergyPlan planActiveToDeposits(
					java.util.List<GranularInventory.DepositTransfer> transfers) {
				return domain.thermalState.planActiveToDeposits(transfers);
			}
			GranularEnergyColumn.DepositKey key(int cell, int material, int composition) {
				return new GranularEnergyColumn.DepositKey(cell, material, composition);
			}
			@Override public boolean canActiveMerge(int first, int second, GranularParcelStore.Parcel merged,
					mmm.granular.SolidCompositionRecord composition, SignedDyadic heat) {
				return domain.thermalState.canActiveMerge(first, second, merged.materialHandle(), merged.mass(),
						composition == null ? Map.of() : composition.stableAmounts(domain.granularState.compositions.registry()), heat);
			}
			@Override public void commitActiveMerge(int first, int second, SignedDyadic heat) {
				domain.thermalState.commitActiveMerge(first, second, heat);
			}
			@Override public GranularInventory.EnergyPlan planDepositWake(
					int cell, int material, int composition, long takenMassUnits, long totalMassUnits
			) {
				return domain.thermalState.planDepositWake(key(cell, material, composition), takenMassUnits, totalMassUnits);
			}
			@Override public void clearExternal() { domain.thermalState.clearGranularExternal(); }
		};
	}
	public void restoreThermalParticleState(
			FlipThermalParticleState state, ThermalRegistry registry
	) {
		restoreThermalParticleState(state, registry, ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS);
	}
	public void restoreThermalParticleState(
			FlipThermalParticleState state, ThermalRegistry registry, int maximumTransportSubsteps
	) {
		if (state == null || registry == null) throw new IllegalArgumentException("Thermal restore state/registry is missing");
		if (domain.materialRegistry == null) throw new IllegalStateException("Thermal restore requires restored material state");
		state.requireCompatible(domain.sizeX, domain.sizeY, domain.sizeZ, domain.config.cellSize(), domain.liquidCells.physicalSize());
		if (!state.thermalRegistryFingerprint().equals(registry.fingerprint())) {
			throw new IllegalArgumentException("Thermal handoff registry identity mismatch");
		}
		validateCoherentRestoreCapacity(state.coherentGroups());
		double initialTemperature = state.insertionTemperatureK();
		// Restore must retain the gas ordinals already derived from the complete checkpoint.
		if (!prepareThermalBinding(registry, domain.materialRegistry, initialTemperature)) {
			throw new IllegalStateException("Thermal restore target is already bound");
		}
		var liquidEnergy = domain.liquidCells.planPhysicalEnergyUpdate(state.copyInternalEnergy());
		ThermalDomainState candidate = FluidThermalBinding.createOwnerRestoreTarget(registry, domain.materialRegistry, initialTemperature,
				domain.liquidCells.physicalSize(), domain.sizeX, domain.sizeY, domain.sizeZ, domain.config.cellSize(), thermalMarkerContext(), domain.gasState.network,
				maximumTransportSubsteps);
		FluidThermalBinding.restore(candidate, state, thermalMarkerContext(), domain.gasState.network);
		liquidEnergy.commit();
		domain.thermalState = candidate;
		try { finishThermalBinding(); }
		catch (RuntimeException failure) {
			domain.thermalState = null;
			try { refreshCoherentOccupancy(); }
			catch (RuntimeException rollbackFailure) { failure.addSuppressed(rollbackFailure); }
			domain.gridDirty = true;
			throw failure;
		}
	}
	public void restoreThermalOwnerState(ThermalOwnerSnapshot state, ThermalRegistry registry, int maximumTransportSubsteps) {
		if (state == null || registry == null || domain.materialRegistry == null)
			throw new IllegalArgumentException("Thermal owner restore requires liquid cells and registries");
		if (!state.registryFingerprint().equals(registry.fingerprint()))
			throw new IllegalArgumentException("Thermal owner registry identity differs");
		validateCoherentRestoreCapacity(state.coherentGroups());
		if (!prepareThermalBinding(registry, domain.materialRegistry, state.insertionTemperatureK()))
			throw new IllegalStateException("Thermal restore target is already bound");
		var candidate = FluidThermalBinding.createOwnerRestoreTarget(registry, domain.materialRegistry, state.insertionTemperatureK(),
				domain.liquidCells.physicalSize(), domain.sizeX, domain.sizeY, domain.sizeZ, domain.config.cellSize(), thermalMarkerContext(), domain.gasState.network,
				maximumTransportSubsteps);
		candidate.restoreOwnerState(state, thermalMarkerContext(), domain.gasState.network);
		domain.thermalState = candidate;
		finishThermalBinding();
	}
	public void restoreThermalOwnerState(ThermalOwnerSnapshot state, ThermalRegistry registry) {
		restoreThermalOwnerState(state, registry, ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS);
	}
	public ThermalOwnerSnapshot exportThermalOwnerState() {
		if (domain.thermalState == null) throw new IllegalStateException("Thermal owners are not bound");
		return domain.thermalState.exportOwnerState();
	}
	void validateCoherentRestoreCapacity(List<CoherentPhaseStore.Group> groups) {
		long[] fixed = new long[domain.cellCount];
		for (CoherentPhaseStore.Group group : groups) {
			if (group.cell() < 0 || group.cell() >= domain.cellCount) {
				throw new IllegalArgumentException("Restored coherent phase cell is outside the domain");
			}
			fixed[group.cell()] = Math.addExact(fixed[group.cell()], group.occupiedVolumeSvq());
		}
		GranularCouplingFields restoredOccupancy = new GranularCouplingFields(
				domain.sizeX, domain.sizeY, domain.sizeZ, domain.config.cellSize());
		restoredOccupancy.setFixedCellSvq(fixed);
		if (domain.granularState == null) restoredOccupancy.rebuildFixed();
		else restoredOccupancy.rebuild(domain.granularState.parcels, domain.granularState.deposits);
		for (int cell = 0; cell < domain.cellCount; cell++) {
			long geometric = SolidVolume.fromCellVolumeExact(domain.geometry.cutCells.openCellVolume(cell));
			// FLIP marker clustering may transiently overfill a cell and is resolved by pressure after restore.
			// Solid occupancy must use the same exact positioned-parcel projection as runtime pressure coupling.
			if (restoredOccupancy.solidSvq(cell) > geometric) {
				throw new IllegalArgumentException("Restored coherent/granular inventory exceeds exact cell capacity");
			}
		}
	}
	public FlipThermalParticleState exportThermalParticleState() { return domain.carriers.exportThermalParticleState(); }
	public void setThermalTemperatureInCell(int x, int y, int z, double temperatureK) {
		if (domain.thermalState == null) throw new IllegalStateException("Thermal state is not bound");
		domain.thermalState.setTemperatureInCell(domain.domainFlowFields.cellIndexChecked(x, y, z), temperatureK, thermalMarkerContext());
	}
	public double thermalTemperatureInCell(int x, int y, int z) { return domain.cellInventory.thermalTemperatureInCell(x, y, z); }
	public List<FiniteThermalExchange.Contact> finiteThermalContacts(List<ThermalContactRequest> requests) {
		if (domain.thermalState == null) throw new IllegalStateException("Thermal state is not bound");
		if (requests == null) throw new IllegalArgumentException("Finite thermal contact requests are missing");
		ArrayList<FiniteDeviceExchangeCoordinator.CellContactRequest> mapped = new ArrayList<>(requests.size());
		for (ThermalContactRequest request : requests) {
			if (request == null) throw new IllegalArgumentException("Finite thermal contact requests contain null");
			mapped.add(new FiniteDeviceExchangeCoordinator.CellContactRequest(
					domain.domainFlowFields.cellIndexChecked(request.cellX(), request.cellY(), request.cellZ()), request.ordinal(),
					request.conductanceWPerK()));
		}
		return domain.thermalState.finiteDeviceContacts(mapped, thermalMarkerContext());
	}
	public FiniteThermalCellPlan planFiniteThermalCellExchange(List<ThermalCellEnergyDelta> deltas) {
		if (domain.thermalState == null) throw new IllegalStateException("Thermal state is not bound");
		if (deltas == null) throw new IllegalArgumentException("Finite thermal cell deltas are missing");
		ArrayList<FiniteDeviceExchangeCoordinator.CellEnergyDelta> mapped = new ArrayList<>(deltas.size());
		for (ThermalCellEnergyDelta delta : deltas) {
			if (delta == null) throw new IllegalArgumentException("Finite thermal cell deltas contain null");
			mapped.add(new FiniteDeviceExchangeCoordinator.CellEnergyDelta(
					domain.domainFlowFields.cellIndexChecked(delta.cellX(), delta.cellY(), delta.cellZ()), delta.internalEnergyDeltaJ()));
		}
		return new FiniteThermalCellPlan(domain.thermalState.planFiniteDeviceCellExchange(mapped, thermalMarkerContext()));
	}
	public void commitFiniteThermalCellExchange(FiniteThermalCellPlan plan) {
		if (domain.thermalState == null || plan == null) {
			throw new IllegalArgumentException("Finite thermal cell exchange is unavailable");
		}
		domain.thermalState.commitFiniteDeviceCellExchange(plan.delegate, thermalMarkerContext());
	}
	public ThermalDisplaySnapshot copyThermalDisplaySnapshot() { return domain.carriers.copyThermalDisplaySnapshot(); }
	public void addCoherentPhaseExternal(
			int cellX, int cellY, int cellZ, String phaseId, long occupiedVolumeSvq,
			long massUnits, CoherentPhaseStore.Composition composition, double internalEnergy
	) {
		if (domain.thermalState == null) throw new IllegalStateException("Thermal state is not bound");
		int cell = domain.domainFlowFields.cellIndexChecked(cellX, cellY, cellZ);
		if (occupiedVolumeSvq <= 0L) throw new IllegalArgumentException("Coherent phase volume is invalid");
		domain.domainFlowFields.ensureInventoryGrid();
		long geometric = SolidVolume.fromCellVolumeExact(domain.geometry.cutCells.openCellVolume(cell));
		long previousCoherent = domain.thermalState.coherentPhases().cellOccupiedSvq(cell);
		long occupied = domain.granularCoupling.solidSvq(cell);
		long granular = occupied - previousCoherent;
		if (granular < 0L || Math.addExact(occupied, occupiedVolumeSvq) > geometric) {
			throw new IllegalStateException("Coherent phase exceeds exact joint cell occupancy");
		}
		if (!domain.domainCollision.canAddCarrierVolume(occupiedVolumeSvq / (double) SolidVolume.SVQ_PER_CELL)) {
			throw new IllegalStateException("Sealed region has no combined liquid/solid capacity");
		}
		var addition = new CoherentPhaseStore.Group(cell, phaseId, occupiedVolumeSvq, massUnits, composition, internalEnergy);
		addition.requireCompositionMass(domain.chemicalParcels == null ? null : domain.chemicalParcels.registry());
		var thermal = domain.thermalState.planCoherentExternal(addition);
		long[] before = domain.granularCoupling.copySolidSvq(), after = before.clone();
		after[cell] = Math.addExact(after[cell], occupiedVolumeSvq);
		var displacement = GranularLiquidDisplacement.prepareInsertion(domain, before, after);
		var audit = thermal.auditAfter().withMechanicalWork(displacement.inventory.mechanicalHeat())
				.withRoundoff(displacement.energyRoundoff);
		var gas = displacement.gasTopology == null ? null : displacement.gasTopology.thermalPlan();
		if (gas != null) audit = audit.withExternalSourceEnergy(gas.sourceEnergy()).withExternalSinkEnergy(gas.sinkEnergy())
				.withMechanicalWork(gas.mechanicalWork()).withRoundoff(gas.roundoff());
		audit.expectedInventory();
		thermal.requireCurrent();
		GranularLiquidDisplacement.publish(domain, displacement, () -> {
			thermal.commit();
			refreshCoherentOccupancy();
		});
	}
	public long totalCoherentSolidVolumeSvq() {
		return domain.thermalState == null ? 0L : domain.thermalState.coherentPhases().totalOccupiedSvq();
	}
	public double totalCoherentMass() {
		return domain.thermalState == null ? 0.0 : domain.thermalState.coherentPhases().totalMass();
	}
	public boolean replaceThermalBoundaryTemperatures(double[] temperaturesK) {
		if (domain.thermalState == null) throw new IllegalStateException("Thermal state is not bound");
		if (temperaturesK == null || temperaturesK.length != Math.multiplyExact(domain.cellCount, 6)) {
			throw new IllegalArgumentException("Thermal boundary field dimensions changed");
		}
		for (double temperature : temperaturesK) if (!Double.isNaN(temperature)
				&& (!(Double.isFinite(temperature)) || temperature < ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K)) {
			throw new IllegalArgumentException("Thermal boundary temperature is invalid");
		}
		if (domain.thermalBoundaryTemperatureK != null
				&& java.util.Arrays.equals(domain.thermalBoundaryTemperatureK, temperaturesK)) return false;
		domain.thermalBoundaryTemperatureK = temperaturesK.clone();
		return true;
	}
	public ThermalTransport.Stats stepThermalOnly(double dtSeconds) {
		return domain.thermochemistryPipeline.stepTransport(dtSeconds);
	}
	public ThermalPhaseStats stepInertPhaseChangeOnly(int maximumOperations) {
		return domain.condensedPhaseChangeStage.step(maximumOperations);
	}
	public GasThermalPhaseStats stepGasPhaseChangeOnly(int maximumOperations) {
		return domain.gasLiquidPhaseChangeStage.step(maximumOperations);
	}
	static long minimumPhaseBatchesForCapacity(
			long occupiedSvq, long capacitySvq, long signedVolumeSvqPerBatch
	) {
		return CondensedPhaseChangeStage.minimumBatchesForCapacity(
				occupiedSvq, capacitySvq, signedVolumeSvqPerBatch);
	}
	ThermalCarrierContext thermalMarkerContext() { return domain.thermalMarkers; }
	ThermalCarrierContext createThermalMarkerContext() {
		return new ThermalCarrierContext() {
			private LiquidCellInventory.Stock stock(int ordinal) { return domain.liquidCells.physicalStocks().get(ordinal); }
			@Override public int markerCount() { return domain.liquidCells.physicalStocks().size(); }
			@Override public double liquidInternalEnergy(int ordinal) { return stock(ordinal).internalEnergy(); }
			@Override public long liquidInventoryRevision() { return domain.liquidCells.revision(); }
			@Override public void setLiquidInternalEnergy(int ordinal, double energy) {
				domain.liquidCells.planPhysicalEnergyUpdate(ordinal, energy).commit();
			}
			@Override public void setLiquidInternalEnergies(double[] energies) {
				if (energies == null) throw new IllegalArgumentException("Liquid energy column is missing");
				domain.liquidCells.planPhysicalEnergyUpdate(energies).commit();
			}
			@Override public int cell(int ordinal) { return stock(ordinal).key().cell(); }
			@Override public int nextMarkerInCell(int cell, int after) {
				var stocks = domain.liquidCells.physicalStocks();
				int low = Math.max(0, after + 1), high = stocks.size();
				if (after < 0) while (low < high) {
					int middle = (low + high) >>> 1;
					if (stocks.get(middle).key().cell() < cell) low = middle + 1; else high = middle;
				}
				return low < stocks.size() && stocks.get(low).key().cell() == cell ? low : -1;
			}
			@Override public int fluidMaterialHandle(int ordinal) { return stock(ordinal).key().material(); }
			@Override public double mass(int ordinal) { return domain.unitScale.normalizedMass(stock(ordinal).massUnits()); }
			@Override public double volume(int ordinal) { return stock(ordinal).volumeFraction(); }
			@Override public ChemicalMixtureRecord mixture(int ordinal) { return stock(ordinal).mixture(); }
			@Override public ChemicalRegistry chemicalRegistry() { return domain.liquidCells.chemicalRegistry(); }
			@Override public int granularMaterialCount() {
				return domain.granularState == null ? 0 : domain.granularState.registry.materials().size();
			}
			@Override public String granularRegistryFingerprint() {
				return domain.granularState == null ? "" : domain.granularState.registry.fingerprint();
			}
			@Override public String granularMaterialId(int materialHandle) {
				if (domain.granularState == null) throw new IllegalArgumentException("Granular state is not bound");
				return domain.granularState.registry.material(materialHandle).id();
			}
			@Override public int activeParcelCount() {
				return domain.granularState == null ? 0 : domain.granularState.parcels.count();
			}
			@Override public int activeParcelCell(int ordinal) {
				return domain.domainStep.granularParcelCell(domain.granularState.parcels.parcel(ordinal));
			}
			@Override public int activeParcelMaterial(int ordinal) {
				return domain.granularState.parcels.parcel(ordinal).materialHandle();
			}
			@Override public int activeParcelComposition(int ordinal) {
				return domain.granularState.parcels.parcel(ordinal).solidCompositionHandle();
			}
			@Override public long activeParcelSvq(int ordinal) {
				return domain.granularState.parcels.parcel(ordinal).solidVolumeSvq();
			}
			@Override public double activeParcelMass(int ordinal) {
				return domain.granularState.parcels.parcel(ordinal).mass();
			}
			@Override public List<GranularEnergyColumn.DepositKey> depositKeys() {
				if (domain.granularState == null) return List.of();
				ArrayList<GranularEnergyColumn.DepositKey> keys = new ArrayList<>(domain.granularState.deposits.groupCount());
				domain.granularState.deposits.forEachGroup((cell, porosity, material, composition, volume, mass) ->
						keys.add(new GranularEnergyColumn.DepositKey(cell, material, composition)));
				keys.sort(Comparator.naturalOrder());
				return List.copyOf(keys);
			}
			@Override public List<GranularEnergyColumn.DepositKey> depositKeys(int cell) {
				if (domain.granularState == null) return List.of();
				ArrayList<GranularEnergyColumn.DepositKey> keys = new ArrayList<>();
				domain.granularState.deposits.forEachGroupInCell(cell,
						(groupCell, porosity, material, composition, volume, mass) ->
								keys.add(new GranularEnergyColumn.DepositKey(groupCell, material, composition)));
				keys.sort(Comparator.naturalOrder());
				return List.copyOf(keys);
			}
			@Override public long depositSvq(GranularEnergyColumn.DepositKey key) {
				return thermalDepositGroup(key).solidVolumeSvq();
			}
			@Override public double depositMass(GranularEnergyColumn.DepositKey key) {
				return thermalDepositGroup(key).mass();
			}
			@Override public Map<String, Long> solidComposition(int compositionHandle) {
				if (compositionHandle == 0) return Map.of();
				if (domain.granularState.compositions == null || domain.chemicalParcels == null) {
					throw new IllegalArgumentException("Solid thermal composition state is unavailable");
				}
				return domain.granularState.compositions.record(compositionHandle).stableAmounts(domain.chemicalParcels.registry());
			}
		};
	}
	GranularDepositStore.Group thermalDepositGroup(GranularEnergyColumn.DepositKey key) {
		if (domain.granularState == null || key == null) throw new IllegalArgumentException("Granular deposit state is unavailable");
		GranularDepositStore.Group group = domain.granularState.deposits.matchingGroup(key.cell(), key.materialHandle(),
				candidate -> candidate.solidCompositionHandle() == key.compositionHandle());
		if (group == null) throw new IllegalArgumentException("Unknown granular thermal deposit identity");
		return group;
	}
	double thermalMarkerTemperature(int marker) {
		return domain.thermalState.temperature(marker, thermalMarkerContext());
	}
	double thermalOccupiedFaceFraction(int cell, int direction) {
		domain.domainFlowFields.ensurePressureGeometry();
		double geometricOpen = domain.geometry.cutCells.openCellVolume(cell);
		if (!(geometricOpen > 0)) return 0;
		double solidVolume = domain.granularCoupling == null ? 0 : SolidVolume.toCellVolume(domain.granularCoupling.solidSvq(cell));
		double fallback = Math.max(0, 1 - solidVolume / geometricOpen);
		int axis = direction / 2, side = direction & 1, first = (axis + 1) % 3, second = (axis + 2) % 3;
		int pieces = 0; double occupied = 0;
		for (int micro = 0; micro < 8; micro++) if ((micro >> axis & 1) == side && (domain.geometry.cutCells.mask(cell) & 1 << micro) == 0) {
			pieces++;
			double pore = domain.pressurePhaseGeometry.componentPoreScale(cell, micro, fallback);
			double fraction = domain.pressurePhaseGeometry.liquidFractionExact(cell, micro), wet;
			if (fraction <= 0) wet = 0;
			else if (fraction >= 1) wet = 1;
			else {
				double a = domain.pressurePhaseGeometry.planeNormal(cell, micro, first), b = domain.pressurePhaseGeometry.planeNormal(cell, micro, second);
				double offset = domain.pressurePhaseGeometry.planeOffset(cell, micro) - side * domain.pressurePhaseGeometry.planeNormal(cell, micro, axis)
						- .5 * (micro >> first & 1) * a - .5 * (micro >> second & 1) * b;
				wet = FlipFreeSurfaceGeometry.unitCubeLiquidFraction(0.0, .5 * a, .5 * b, offset);
			}
			occupied += 1 - pore + pore * wet;
		}
		return pieces == 0 ? 0 : occupied / pieces;
	}
	ThermalTransport.FixedBoundary thermalFixedBoundary(int x, int y, int z, int direction) {
		if (domain.thermalBoundaryTemperatureK == null) return null;
		int cell = domain.domainFlowFields.cellIndex(x, y, z);
		double temperature = domain.thermalBoundaryTemperatureK[cell * 6 + direction];
		if (Double.isNaN(temperature)) return null;
		double blockedFraction = 1.0 - thermalOpenFaceFraction(x, y, z, direction);
		double contactArea = thermalOccupiedFaceFraction(cell, direction) * blockedFraction
				* (double) domain.config.cellSize() * domain.config.cellSize();
		return contactArea > 0.0 ? new ThermalTransport.FixedBoundary(contactArea, temperature) : null;
	}
	double thermalOpenFaceFraction(int x, int y, int z, int direction) {
		return switch (direction) {
			case 0 -> domain.geometry.cutCells.xFaceOpen(x, y, z);
			case 1 -> domain.geometry.cutCells.xFaceOpen(x + 1, y, z);
			case 2 -> domain.geometry.cutCells.yFaceOpen(x, y, z);
			case 3 -> domain.geometry.cutCells.yFaceOpen(x, y + 1, z);
			case 4 -> domain.geometry.cutCells.zFaceOpen(x, y, z);
			case 5 -> domain.geometry.cutCells.zFaceOpen(x, y, z + 1);
			default -> throw new IllegalArgumentException("Unknown thermal boundary direction");
		};
	}
	void clearThermalBinding() {
		domain.thermalState = null;
		domain.thermalBoundaryTemperatureK = null;
		domain.pendingPhaseVolume.clear();
		if (domain.granularState == null) {
			domain.granularCoupling = null; domain.granularNextCoupling = null; domain.pressureGranularCoupling = null;
		}
	}
	void refreshCoherentOccupancy() {
		if (domain.granularCoupling == null || domain.granularNextCoupling == null) return;
		long[] fixed = new long[domain.cellCount];
		if (domain.thermalState != null) for (CoherentPhaseStore.Group group : domain.thermalState.coherentPhases().snapshot()) {
			fixed[group.cell()] = Math.addExact(fixed[group.cell()], group.occupiedVolumeSvq());
		}
		domain.granularCoupling.setFixedCellSvq(fixed);
		domain.granularNextCoupling.setFixedCellSvq(fixed);
		if (domain.domainPhaseInventory.hasGranularInventory()) {
			domain.granularCoupling.rebuild(domain.granularState.parcels, domain.granularState.deposits);
			domain.granularNextCoupling.rebuild(domain.granularState.parcels, domain.granularState.deposits);
		}
	}
	void bindSolidCompositionPoolIfReady() {
		if (domain.granularState == null || domain.granularState.compositions != null || domain.chemicalParcels == null
				|| !domain.granularState.registry.hasReactiveRules()) return;
		if (!domain.granularState.registry.chemicalRegistryFingerprint().equals(domain.chemicalParcels.registry().fingerprint())) {
			throw new IllegalArgumentException("Granular registry chemical dependency mismatch");
		}
		int depositCapacity = Math.min(GranularLimits.MAX_DEPOSIT_GROUPS_PER_REGION,
				Math.multiplyExact(domain.cellCount, GranularLimits.MAX_DEPOSIT_GROUPS_PER_CELL));
		domain.granularState.bindCompositions(new SolidCompositionPool(domain.chemicalParcels.registry(),
				Math.addExact(GranularLimits.MAX_ACTIVE_PARCELS_PER_REGION, depositCapacity)));
		domain.granularPhaseTransfer = new GranularPhaseTransferEngine(domain.chemicalParcels.registry(), domain.unitScale);
	}
}

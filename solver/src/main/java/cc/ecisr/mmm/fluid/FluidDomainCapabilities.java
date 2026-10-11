package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.thermal.FiniteDeviceExchangeCoordinator;
import cc.ecisr.mmm.thermal.LiquidEnergyColumn;
import cc.ecisr.mmm.thermal.ThermalCarrierContext;

import cc.ecisr.mmm.chemistry.ChemicalMixtureRecord;
import cc.ecisr.mmm.chemistry.ChemicalParcelStore;
import cc.ecisr.mmm.chemistry.ChemicalDisplaySnapshot;
import cc.ecisr.mmm.chemistry.ChemicalRegistry;
import cc.ecisr.mmm.chemistry.ChemicalTransferLedger;
import cc.ecisr.mmm.granular.GranularCouplingFields;
import cc.ecisr.mmm.granular.GranularDepositStore;
import cc.ecisr.mmm.granular.GranularInventory;
import cc.ecisr.mmm.granular.GranularLimits;
import cc.ecisr.mmm.granular.GranularParcelStore;
import cc.ecisr.mmm.granular.GranularPhaseTransferEngine;
import cc.ecisr.mmm.granular.SolidCompositionPool;
import cc.ecisr.mmm.granular.SolidVolume;
import cc.ecisr.mmm.units.SignedDyadic;
import cc.ecisr.mmm.thermal.CoherentPhaseStore;
import cc.ecisr.mmm.thermal.GranularEnergyColumn;
import cc.ecisr.mmm.thermal.FiniteThermalExchange;
import cc.ecisr.mmm.thermal.ThermalMaterialDefinition;
import cc.ecisr.mmm.thermal.ThermalLimits;
import cc.ecisr.mmm.thermal.ThermalRegistry;
import cc.ecisr.mmm.thermal.ThermalDomainState;
import cc.ecisr.mmm.thermal.ThermalDisplaySnapshot;
import cc.ecisr.mmm.thermal.ThermalTransport;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Binds chemistry, granular and thermal capability state to the carrier aggregate. */
abstract class FluidDomainCapabilities extends FluidDomainCollisionState {
	FluidDomainCapabilities(int sizeX, int sizeY, int sizeZ, FluidSolverConfig config, int maximumParticleCount) {
		super(sizeX, sizeY, sizeZ, config, maximumParticleCount);
	}

	public FluidMaterialRegistry materialRegistry() { return materialRegistry; }

	public ChemicalParcelStore chemicalParcels() {
		if (chemicalParcels == null) throw new IllegalStateException("Chemistry is not bound");
		return chemicalParcels;
	}

	public ChemicalDisplaySnapshot copyChemicalDisplaySnapshot() { return carriers.copyChemicalDisplaySnapshot(); }

	/** Auditable exact ledger for a stable ordinal range, used immediately after planned external insertion. */
	public ChemicalTransferLedger chemicalLedgerForMarkerRange(int fromInclusive, int toExclusive) {
		if (chemicalParcels == null) throw new IllegalStateException("Chemistry is not bound");
		if (fromInclusive < 0 || toExclusive < fromInclusive || toExclusive > particles.count()) {
			throw new IndexOutOfBoundsException("Invalid chemical marker range");
		}
		ChemicalTransferLedger.Builder result = ChemicalTransferLedger.builder(chemicalParcels.registry());
		for (int marker = fromInclusive; marker < toExclusive; marker++) {
			result.add(materialRegistry.material(particles.materialHandle(marker)).id(), chemicalParcels.indicator(marker),
					particles.volume(marker), unitScale.kilograms(particles.mass(marker)), chemicalParcels.record(marker));
		}
		return result.build();
	}

	/**
	 * Plans and commits an indicator/eraser flood fill through positive-area cut faces. The fill is
	 * isolated by chemical phase, while the indicator bit itself never isolates reactions.
	 */
	public IndicatorEditResult editIndicatorFloodFill(
			int startX, int startY, int startZ, boolean indicated, int maxScannedCells, double maxAddedBlockVolume
	) {
		if (chemicalParcels == null || materialRegistry == null) {
			return IndicatorEditResult.rejected("Chemistry is not bound");
		}
		if (maxScannedCells <= 0 || maxScannedCells > cellCount) {
			throw new IllegalArgumentException("Indicator scan budget must be within the region cell budget");
		}
		if (indicated && !(Double.isFinite(maxAddedBlockVolume) && maxAddedBlockVolume > 0.0)) {
			throw new IllegalArgumentException("Indicator volume budget must be finite and positive");
		}
		int requestedStart = cellIndexChecked(startX, startY, startZ);
		int[] dominantPhase = new int[cellCount];
		float[] dominantVolume = new float[cellCount];
		int[] dominantMarker = new int[cellCount];
		Arrays.fill(dominantPhase, -1);
		Arrays.fill(dominantVolume, -1.0F);
		Arrays.fill(dominantMarker, Integer.MAX_VALUE);
		for (int marker = 0; marker < particles.count(); marker++) {
			int cell = particleCellIndex(marker);
			float candidate = particles.volume(marker);
			if (candidate > dominantVolume[cell]
					|| candidate == dominantVolume[cell] && marker < dominantMarker[cell]) {
				dominantVolume[cell] = candidate;
				dominantMarker[cell] = marker;
				dominantPhase[cell] = chemicalParcels.registry().phaseHandleForMaterial(
						materialRegistry.material(particles.materialHandle(marker)).id());
			}
		}
		IndicatorAimSeed seed = resolveIndicatorAimSeed(requestedStart, dominantPhase, maxScannedCells);
		if (seed.cell() < 0) return IndicatorEditResult.rejected(seed.failure());
		int start = seed.cell();
		int targetPhase = dominantPhase[start];
		if (indicated && !chemicalParcels.registry().phase(targetPhase).acceptsIndicator()) {
			return IndicatorEditResult.rejected("Target chemical phase rejects indicator");
		}

		int[] cellHead = new int[cellCount];
		int[] markerNext = new int[particles.count()];
		boolean[] compatible = new boolean[cellCount];
		Arrays.fill(cellHead, -1);
		for (int marker = particles.count() - 1; marker >= 0; marker--) {
			String materialId = materialRegistry.material(particles.materialHandle(marker)).id();
			if (chemicalParcels.registry().phaseHandleForMaterial(materialId) != targetPhase) continue;
			int cell = particleCellIndex(marker);
			markerNext[marker] = cellHead[cell];
			cellHead[cell] = marker;
			compatible[cell] = true;
		}

		boolean[] visited = new boolean[cellCount];
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
				cellVolume += particles.volume(marker);
			}
			double blockVolume = cellVolume * config.cellSize() * config.cellSize() * config.cellSize();
			if (indicated && blockVolume > maxAddedBlockVolume) {
				return IndicatorEditResult.rejected("Indicator volume exceeds " + maxAddedBlockVolume + " BV");
			}
			int x = cell % sizeX;
			int yz = cell / sizeX;
			int y = yz % sizeY;
			int z = yz / sizeY;
			if (x > 0) enqueueIndicatorNeighbor(cell, cellIndex(x - 1, y, z), -1, 0, 0, compatible, visited, queue);
			if (x + 1 < sizeX) enqueueIndicatorNeighbor(cell, cellIndex(x + 1, y, z), 1, 0, 0, compatible, visited, queue);
			if (y > 0) enqueueIndicatorNeighbor(cell, cellIndex(x, y - 1, z), 0, -1, 0, compatible, visited, queue);
			if (y + 1 < sizeY) enqueueIndicatorNeighbor(cell, cellIndex(x, y + 1, z), 0, 1, 0, compatible, visited, queue);
			if (z > 0) enqueueIndicatorNeighbor(cell, cellIndex(x, y, z - 1), 0, 0, -1, compatible, visited, queue);
			if (z + 1 < sizeZ) enqueueIndicatorNeighbor(cell, cellIndex(x, y, z + 1), 0, 0, 1, compatible, visited, queue);
		}
		int changed = 0;
		for (int marker : affected) if (chemicalParcels.updateIndicator(marker, indicated)) changed++;
		double blockVolume = cellVolume * config.cellSize() * config.cellSize() * config.cellSize();
		return new IndicatorEditResult(true, scanned, changed, blockVolume, null);
	}

	/**
	 * Display density may put the first ray-hit cell one or two cells away from the marker truth in
	 * shallow water. Snap only through open cut faces and only within that bounded stencil; the
	 * subsequent phase flood still begins from an actual chemical marker cell.
	 */
	IndicatorAimSeed resolveIndicatorAimSeed(int requested, int[] dominantPhase, int maxScannedCells) {
		int[] distance = new int[cellCount];
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
			if (distance[cell] >= INDICATOR_AIM_SNAP_CELLS) continue;
			int x = cell % sizeX;
			int yz = cell / sizeX;
			int y = yz % sizeY;
			int z = yz / sizeY;
			if (x > 0) enqueueIndicatorAimNeighbor(cell, cellIndex(x - 1, y, z), -1, 0, 0, distance, queue);
			if (x + 1 < sizeX) enqueueIndicatorAimNeighbor(cell, cellIndex(x + 1, y, z), 1, 0, 0, distance, queue);
			if (y > 0) enqueueIndicatorAimNeighbor(cell, cellIndex(x, y - 1, z), 0, -1, 0, distance, queue);
			if (y + 1 < sizeY) enqueueIndicatorAimNeighbor(cell, cellIndex(x, y + 1, z), 0, 1, 0, distance, queue);
			if (z > 0) enqueueIndicatorAimNeighbor(cell, cellIndex(x, y, z - 1), 0, 0, -1, distance, queue);
			if (z + 1 < sizeZ) enqueueIndicatorAimNeighbor(cell, cellIndex(x, y, z + 1), 0, 0, 1, distance, queue);
		}
		return new IndicatorAimSeed(-1, scanned, "Target surface has no nearby chemical liquid marker");
	}

	void enqueueIndicatorAimNeighbor(
			int from, int neighbor, int dx, int dy, int dz, int[] distance, ArrayDeque<Integer> queue
	) {
		if (distance[neighbor] < 0 && openFaceFromCell(from, dx, dy, dz) > 0.0F) {
			distance[neighbor] = distance[from] + 1;
			queue.addLast(neighbor);
		}
	}

	record IndicatorAimSeed(int cell, int scannedCells, String failure) { }

	void enqueueIndicatorNeighbor(
			int from, int neighbor, int dx, int dy, int dz, boolean[] compatible,
			boolean[] visited, ArrayDeque<Integer> queue
	) {
		if (!visited[neighbor] && compatible[neighbor] && openFaceFromCell(from, dx, dy, dz) > 0.0F) {
			visited[neighbor] = true;
			queue.addLast(neighbor);
		}
	}

	public record IndicatorEditResult(
			boolean accepted, int scannedCells, int changedMarkers, double affectedBlockVolume, String failure
	) {
		static IndicatorEditResult rejected(String failure) {
			return new IndicatorEditResult(false, 0, 0, 0.0, failure);
		}
	}

	/** Converts every current material marker to the registry-defined default carrier. */
	public void bindChemistry(ChemicalRegistry registry) {
		if (thermalState != null) {
			throw new IllegalStateException("Chemistry must be bound before transient thermal initialization");
		}
		if (materialRegistry == null) throw new IllegalStateException("Chemistry requires a material registry");
		if (chemicalParcels != null) {
			if (!chemicalParcels.registry().fingerprint().equals(registry.fingerprint())) {
				throw new IllegalArgumentException("A domain cannot change its chemical registry");
			}
			return;
		}
		if (granularState != null && granularState.registry.hasReactiveRules()
				&& !granularState.registry.chemicalRegistryFingerprint().equals(registry.fingerprint())) {
			throw new IllegalArgumentException("Granular registry chemical dependency mismatch");
		}
		ChemicalParcelStore candidate = new ChemicalParcelStore(
				registry, unitScale, maximumParticleCount, cellCount);
		for (FluidMaterial material : materialRegistry.materials()) registry.phaseHandleForMaterial(material.id());
		for (int marker = 0; marker < particles.count(); marker++) {
			candidate.assignDefault(marker, materialRegistry.material(particles.materialHandle(marker)).id(),
					particles.volume(marker), config.cellSize(), particles.mass(marker), false);
		}
		chemicalParcels = candidate;
		chemicalPreviousCells = new int[maximumParticleCount];
		chemicalMovingMarkers = new int[maximumParticleCount];
		bindSolidCompositionPoolIfReady();
	}

	/** Binds authoritative thermal state at an explicit initial temperature. */
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
		gasTopologyCoordinator.refresh();
		thermalState = FluidThermalBinding.create(registry, materialRegistry, initialTemperatureK,
				particles.capacity(), sizeX, sizeY, sizeZ, config.cellSize(), thermalMarkerContext(), gasState.network,
				maximumTransportSubsteps);
		finishThermalBinding();
	}

	private boolean prepareThermalBinding(
			ThermalRegistry registry, FluidMaterialRegistry liquidRegistry, double initialTemperatureK
	) {
		if (registry == null || liquidRegistry == null) throw new IllegalArgumentException("Thermal/liquid registry is missing");
		if (!Double.isFinite(initialTemperatureK)) throw new IllegalArgumentException("Initial thermal temperature must be finite");
		if (Float.floatToRawIntBits(config.cellSize()) != Float.floatToRawIntBits(0.5F)) {
			throw new IllegalArgumentException("Thermal state requires a 0.5 m solver cell");
		}
		bindMaterialRegistry(liquidRegistry);
		if (thermalState != null) {
			if (!thermalState.registry().fingerprint().equals(registry.fingerprint())) {
				throw new IllegalArgumentException("A domain cannot change its thermal registry");
			}
			return false;
		}
		if (chemicalParcels != null && !registry.chemicalRegistryFingerprint()
				.equals(chemicalParcels.registry().fingerprint())) {
			throw new IllegalArgumentException("Thermal registry chemical dependency mismatch");
		}
		ensureInventoryGrid();
		return true;
	}

	private void finishThermalBinding() {
		if (granularCoupling == null) {
			granularCoupling = new GranularCouplingFields(sizeX, sizeY, sizeZ, config.cellSize());
			granularNextCoupling = new GranularCouplingFields(sizeX, sizeY, sizeZ, config.cellSize());
		}
		refreshCoherentOccupancy();
		if (thermalState.coherentPhases().groupCount() > 0) gridDirty = true;
		else particleToGridStage.rebuildPressureGeometry();
		if (granularState != null) granularState.inventory.bindThermalEnergy(thermalGranularAdapter());
	}

	GranularInventory.EnergyAdapter thermalGranularAdapter() {
		return new GranularInventory.EnergyAdapter() {
			@Override public GranularInventory.EnergyPlan planActiveToDeposits(
					java.util.List<GranularInventory.DepositTransfer> transfers) {
				return thermalState.planActiveToDeposits(transfers);
			}
			GranularEnergyColumn.DepositKey key(int cell, int material, int composition) {
				return new GranularEnergyColumn.DepositKey(cell, material, composition);
			}
			@Override public boolean canActiveMerge(int first, int second, GranularParcelStore.Parcel merged,
					cc.ecisr.mmm.granular.SolidCompositionRecord composition) {
				return thermalState.canActiveMerge(first, second, merged.materialHandle(), merged.mass(),
						composition == null ? Map.of() : composition.stableAmounts(granularState.compositions.registry()));
			}
			@Override public void commitActiveMerge(int first, int second) {
				thermalState.commitActiveMerge(first, second);
			}
			@Override public void requireDepositWake(
					int cell, int material, int composition, long takenSvq, long totalSvq
			) {
				thermalState.requireDepositWake(key(cell, material, composition), takenSvq, totalSvq);
			}
			@Override public void commitDepositWake(
					int cell, int material, int composition, long takenSvq, long totalSvq
			) {
				thermalState.commitDepositWake(key(cell, material, composition), takenSvq, totalSvq);
			}
			@Override public void clearExternal() { thermalState.clearGranularExternal(); }
		};
	}

	/** Restores only the orthogonal energy side after material/chemical marker truth is already restored. */
	public void restoreThermalParticleState(
			FlipThermalParticleState state, ThermalRegistry registry
	) {
		restoreThermalParticleState(state, registry, ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS);
	}

	public void restoreThermalParticleState(
			FlipThermalParticleState state, ThermalRegistry registry, int maximumTransportSubsteps
	) {
		if (state == null || registry == null) throw new IllegalArgumentException("Thermal restore state/registry is missing");
		if (materialRegistry == null) throw new IllegalStateException("Thermal restore requires restored material state");
		state.requireCompatible(sizeX, sizeY, sizeZ, config.cellSize(), particles.count());
		if (!state.thermalRegistryFingerprint().equals(registry.fingerprint())) {
			throw new IllegalArgumentException("Thermal handoff registry identity mismatch");
		}
		validateCoherentRestoreCapacity(state.coherentGroups());
		double initialTemperature = state.insertionTemperatureK();
		// Restore must retain the gas ordinals already derived from the complete checkpoint.
		if (!prepareThermalBinding(registry, materialRegistry, initialTemperature)) {
			throw new IllegalStateException("Thermal restore target is already bound");
		}
		ThermalDomainState candidate = FluidThermalBinding.createRestoreTarget(registry, materialRegistry, initialTemperature,
				particles.capacity(), sizeX, sizeY, sizeZ, config.cellSize(), thermalMarkerContext(), gasState.network,
				maximumTransportSubsteps, state.copyInternalEnergy());
		FluidThermalBinding.restore(candidate, state, thermalMarkerContext(), gasState.network);
		thermalState = candidate;
		try { finishThermalBinding(); }
		catch (RuntimeException failure) {
			thermalState = null;
			try { refreshCoherentOccupancy(); }
			catch (RuntimeException rollbackFailure) { failure.addSuppressed(rollbackFailure); }
			gridDirty = true;
			throw failure;
		}
	}

	void validateCoherentRestoreCapacity(List<CoherentPhaseStore.Group> groups) {
		long[] fixed = new long[cellCount];
		for (CoherentPhaseStore.Group group : groups) {
			if (group.cell() < 0 || group.cell() >= cellCount) {
				throw new IllegalArgumentException("Restored coherent phase cell is outside the domain");
			}
			fixed[group.cell()] = Math.addExact(fixed[group.cell()], group.occupiedVolumeSvq());
		}
		GranularCouplingFields restoredOccupancy = new GranularCouplingFields(
				sizeX, sizeY, sizeZ, config.cellSize());
		restoredOccupancy.setFixedCellSvq(fixed);
		if (granularState == null) restoredOccupancy.rebuildFixed();
		else restoredOccupancy.rebuild(granularState.parcels, granularState.deposits);
		for (int cell = 0; cell < cellCount; cell++) {
			long geometric = SolidVolume.fromCellVolumeExact(geometry.cutCells.openCellVolume(cell));
			// FLIP marker clustering may transiently overfill a cell and is resolved by pressure after restore.
			// Solid occupancy must use the same exact positioned-parcel projection as runtime pressure coupling.
			if (restoredOccupancy.solidSvq(cell) > geometric) {
				throw new IllegalArgumentException("Restored coherent/granular inventory exceeds exact cell capacity");
			}
		}
	}

	public FlipThermalParticleState exportThermalParticleState() { return carriers.exportThermalParticleState(); }

	/** Explicit cell-temperature edit used by validation and administrator tools. */
	public void setThermalTemperatureInCell(int x, int y, int z, double temperatureK) {
		if (thermalState == null) throw new IllegalStateException("Thermal state is not bound");
		thermalState.setTemperatureInCell(cellIndexChecked(x, y, z), temperatureK, thermalMarkerContext());
	}

	public double thermalTemperatureInCell(int x, int y, int z) { return cellInventory.thermalTemperatureInCell(x, y, z); }

	public record ThermalContactRequest(
			int ordinal, int cellX, int cellY, int cellZ, double conductanceEuPerSecondK
	) {
		public ThermalContactRequest {
			if (ordinal < 0 || !(Double.isFinite(conductanceEuPerSecondK) && conductanceEuPerSecondK > 0.0)) {
				throw new IllegalArgumentException("Finite thermal contact request is invalid");
			}
		}
	}

	public List<FiniteThermalExchange.Contact> finiteThermalContacts(List<ThermalContactRequest> requests) {
		if (thermalState == null) throw new IllegalStateException("Thermal state is not bound");
		if (requests == null) throw new IllegalArgumentException("Finite thermal contact requests are missing");
		ArrayList<FiniteDeviceExchangeCoordinator.CellContactRequest> mapped = new ArrayList<>(requests.size());
		for (ThermalContactRequest request : requests) {
			if (request == null) throw new IllegalArgumentException("Finite thermal contact requests contain null");
			mapped.add(new FiniteDeviceExchangeCoordinator.CellContactRequest(
					cellIndexChecked(request.cellX(), request.cellY(), request.cellZ()), request.ordinal(),
					request.conductanceEuPerSecondK()));
		}
		return thermalState.finiteDeviceContacts(mapped, thermalMarkerContext());
	}

	public record ThermalCellEnergyDelta(int cellX, int cellY, int cellZ, double internalEnergyDeltaEu) {
		public ThermalCellEnergyDelta {
			if (!Double.isFinite(internalEnergyDeltaEu)) {
				throw new IllegalArgumentException("Finite thermal cell energy delta is invalid");
			}
		}
	}

	public static final class FiniteThermalCellPlan {
		final FiniteDeviceExchangeCoordinator.FiniteDeviceCellPlan delegate;
		FiniteThermalCellPlan(FiniteDeviceExchangeCoordinator.FiniteDeviceCellPlan delegate) {
			this.delegate = java.util.Objects.requireNonNull(delegate, "delegate");
		}
		public SignedDyadic requestedDelta() { return delegate.requestedDelta(); }
	}

	public FiniteThermalCellPlan planFiniteThermalCellExchange(List<ThermalCellEnergyDelta> deltas) {
		if (thermalState == null) throw new IllegalStateException("Thermal state is not bound");
		if (deltas == null) throw new IllegalArgumentException("Finite thermal cell deltas are missing");
		ArrayList<FiniteDeviceExchangeCoordinator.CellEnergyDelta> mapped = new ArrayList<>(deltas.size());
		for (ThermalCellEnergyDelta delta : deltas) {
			if (delta == null) throw new IllegalArgumentException("Finite thermal cell deltas contain null");
			mapped.add(new FiniteDeviceExchangeCoordinator.CellEnergyDelta(
					cellIndexChecked(delta.cellX(), delta.cellY(), delta.cellZ()), delta.internalEnergyDeltaEu()));
		}
		return new FiniteThermalCellPlan(thermalState.planFiniteDeviceCellExchange(mapped, thermalMarkerContext()));
	}

	public void commitFiniteThermalCellExchange(FiniteThermalCellPlan plan) {
		if (thermalState == null || plan == null) {
			throw new IllegalArgumentException("Finite thermal cell exchange is unavailable");
		}
		thermalState.commitFiniteDeviceCellExchange(plan.delegate, thermalMarkerContext());
	}

	public ThermalDisplaySnapshot copyThermalDisplaySnapshot() { return carriers.copyThermalDisplaySnapshot(); }

	/** Explicit external coherent inventory insertion used before the phase planner owns liquid/solid conversion. */
	public void addCoherentPhaseExternal(
			int cellX, int cellY, int cellZ, String phaseId, long occupiedVolumeSvq,
			double mass, CoherentPhaseStore.Composition composition, double internalEnergy
	) {
		if (thermalState == null) throw new IllegalStateException("Thermal state is not bound");
		int cell = cellIndexChecked(cellX, cellY, cellZ);
		if (occupiedVolumeSvq <= 0L) throw new IllegalArgumentException("Coherent phase volume is invalid");
		ensureInventoryGrid();
		long geometric = SolidVolume.fromCellVolumeExact(geometry.cutCells.openCellVolume(cell));
		long previousCoherent = thermalState.coherentPhases().cellOccupiedSvq(cell);
		long occupied = granularCoupling.solidSvq(cell);
		long granular = occupied - previousCoherent;
		if (granular < 0L || Math.addExact(occupied, occupiedVolumeSvq) > geometric) {
			throw new IllegalStateException("Coherent phase exceeds exact joint cell occupancy");
		}
		if (!canAddCarrierVolume(occupiedVolumeSvq / (double) SolidVolume.SVQ_PER_CELL)) {
			throw new IllegalStateException("Sealed region has no combined liquid/solid capacity");
		}
		thermalState.addCoherentExternal(new CoherentPhaseStore.Group(
				cell, phaseId, occupiedVolumeSvq, mass, composition, internalEnergy));
		refreshCoherentOccupancy();
		gridDirty = true;
	}

	public long totalCoherentSolidVolumeSvq() {
		return thermalState == null ? 0L : thermalState.coherentPhases().totalOccupiedSvq();
	}

	public double totalCoherentMass() {
		return thermalState == null ? 0.0 : thermalState.coherentPhases().totalMass();
	}

	long phaseGranularSvq(int cell) {
		long total = 0L;
		if (granularState != null) {
			for (int parcel = 0; parcel < granularState.parcels.count(); parcel++) {
				GranularParcelStore.Parcel carrier = granularState.parcels.parcel(parcel);
				if (granularParcelCell(carrier) == cell) {
					total = Math.addExact(total, carrier.solidVolumeSvq());
				}
			}
			total = Math.addExact(total, granularState.deposits.cellSolidVolumeSvq(cell));
		}
		return total;
	}

	/** Replaces the bounded world-derived face field; the field is geometry, not persisted thermal inventory. */
	public boolean replaceThermalBoundaryTemperatures(double[] temperaturesK) {
		if (thermalState == null) throw new IllegalStateException("Thermal state is not bound");
		if (temperaturesK == null || temperaturesK.length != Math.multiplyExact(cellCount, 6)) {
			throw new IllegalArgumentException("Thermal boundary field dimensions changed");
		}
		for (double temperature : temperaturesK) if (!Double.isNaN(temperature)
				&& (!(Double.isFinite(temperature)) || temperature < ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K)) {
			throw new IllegalArgumentException("Thermal boundary temperature is invalid");
		}
		if (thermalBoundaryTemperatureK != null
				&& java.util.Arrays.equals(thermalBoundaryTemperatureK, temperaturesK)) return false;
		thermalBoundaryTemperatureK = temperaturesK.clone();
		return true;
	}

	/** Runs the production thermal axis without advancing marker motion. */
	public ThermalTransport.Stats stepThermalOnly(double dtSeconds) {
		return thermalTransportStage.step(dtSeconds);
	}

	/** Exact-batch condensed melt/freeze; species and same-cell granular heat baths join the conserved LTE plan. */
	public ThermalPhaseStats stepInertPhaseChangeOnly(int maximumOperations) {
		return condensedPhaseChangeStage.step(maximumOperations, false);
	}

	/** Sealed-interface liquid/gas phase transfer, bounded by saturation, energy, inventory and one shared batch budget. */
	public GasThermalPhaseStats stepGasPhaseChangeOnly(int maximumOperations) {
		return gasLiquidPhaseChangeStage.step(maximumOperations, false);
	}

	static long minimumPhaseBatchesForCapacity(
			long occupiedSvq, long capacitySvq, long signedVolumeSvqPerBatch
	) {
		return CondensedPhaseChangeStage.minimumBatchesForCapacity(
				occupiedSvq, capacitySvq, signedVolumeSvqPerBatch);
	}

	public record ThermalPhaseStats(int operations, int frozenCells, int meltedCells, long signedBatches) { }
	public record GasThermalPhaseStats(int operations, int boiledCells, int condensedCells) { }

	public static final class ThermalPhaseBlockedException extends IllegalStateException {
		public ThermalPhaseBlockedException(String message) { super(message); }
		ThermalPhaseBlockedException(String message, Throwable cause) { super(message, cause); }
	}
	static final class PhaseCandidateUnavailableException extends IllegalArgumentException {
		PhaseCandidateUnavailableException(String message) { super(message); }
		PhaseCandidateUnavailableException(String message, Throwable cause) { super(message, cause); }
	}
	public static final class ThermalTransportBlockedException extends IllegalStateException {
		ThermalTransportBlockedException(String message, Throwable cause) { super(message, cause); }
	}
	public static final class GasThermalBlockedException extends IllegalStateException {
		GasThermalBlockedException(String message, Throwable cause) { super(message, cause); }
	}

	ThermalCarrierContext thermalMarkerContext() { return thermalMarkers; }

	ThermalCarrierContext createThermalMarkerContext() {
		return new ThermalCarrierContext() {
			@Override public int markerCount() { return particles.count(); }
			@Override public int cell(int marker) { return particleCellIndex(marker); }
			@Override public void prepareObservationSupport() { ensureInventoryGrid(); }
			@Override public void observationSupport(int marker, SupportConsumer consumer) {
				LiquidThermalSupport.observe(FluidDomainCapabilities.this, marker, consumer);
			}
			@Override public int nextMarkerInCell(int cell, int after) { return cellInventory.nextMarkerInCell(cell, after); }
			@Override public int fluidMaterialHandle(int marker) { return particles.materialHandle(marker); }
			@Override public double mass(int marker) { return particles.mass(marker); }
			@Override public double volume(int marker) { return particles.volume(marker); }
			@Override public ChemicalMixtureRecord mixture(int marker) {
				return chemicalParcels == null ? null : chemicalParcels.record(marker);
			}
			@Override public ChemicalRegistry chemicalRegistry() {
				return chemicalParcels == null ? null : chemicalParcels.registry();
			}
			@Override public int granularMaterialCount() {
				return granularState == null ? 0 : granularState.registry.materials().size();
			}
			@Override public String granularRegistryFingerprint() {
				return granularState == null ? "" : granularState.registry.fingerprint();
			}
			@Override public String granularMaterialId(int materialHandle) {
				if (granularState == null) throw new IllegalArgumentException("Granular state is not bound");
				return granularState.registry.material(materialHandle).id();
			}
			@Override public int activeParcelCount() {
				return granularState == null ? 0 : granularState.parcels.count();
			}
			@Override public int activeParcelCell(int ordinal) {
				return granularParcelCell(granularState.parcels.parcel(ordinal));
			}
			@Override public int activeParcelMaterial(int ordinal) {
				return granularState.parcels.parcel(ordinal).materialHandle();
			}
			@Override public int activeParcelComposition(int ordinal) {
				return granularState.parcels.parcel(ordinal).solidCompositionHandle();
			}
			@Override public long activeParcelSvq(int ordinal) {
				return granularState.parcels.parcel(ordinal).solidVolumeSvq();
			}
			@Override public double activeParcelMass(int ordinal) {
				return granularState.parcels.parcel(ordinal).mass();
			}
			@Override public List<GranularEnergyColumn.DepositKey> depositKeys() {
				if (granularState == null) return List.of();
				ArrayList<GranularEnergyColumn.DepositKey> keys = new ArrayList<>(granularState.deposits.groupCount());
				granularState.deposits.forEachGroup((cell, porosity, material, composition, volume, mass) ->
						keys.add(new GranularEnergyColumn.DepositKey(cell, material, composition)));
				keys.sort(Comparator.naturalOrder());
				return List.copyOf(keys);
			}
			@Override public List<GranularEnergyColumn.DepositKey> depositKeys(int cell) {
				if (granularState == null) return List.of();
				ArrayList<GranularEnergyColumn.DepositKey> keys = new ArrayList<>();
				granularState.deposits.forEachGroupInCell(cell,
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
				if (granularState.compositions == null || chemicalParcels == null) {
					throw new IllegalArgumentException("Solid thermal composition state is unavailable");
				}
				return granularState.compositions.record(compositionHandle).stableAmounts(chemicalParcels.registry());
			}
		};
	}

	GranularDepositStore.Group thermalDepositGroup(GranularEnergyColumn.DepositKey key) {
		if (granularState == null || key == null) throw new IllegalArgumentException("Granular deposit state is unavailable");
		GranularDepositStore.Group group = granularState.deposits.matchingGroup(key.cell(), key.materialHandle(),
				candidate -> candidate.solidCompositionHandle() == key.compositionHandle());
		if (group == null) throw new IllegalArgumentException("Unknown granular thermal deposit identity");
		return group;
	}

	double thermalMarkerTemperature(int marker) {
		return thermalState.temperature(marker, thermalMarkerContext());
	}

	FlipParticleReseeder.ThermalAdapter thermalReseedAdapter() {
		return FluidThermalBinding.reseedAdapter(thermalState, thermalMarkerContext());
	}

	double thermalFaceArea(int x, int y, int z, int axis) {
		float aperture = switch (axis) {
			case 0 -> geometry.cutCells.xFaceOpen(x + 1, y, z);
			case 1 -> geometry.cutCells.yFaceOpen(x, y + 1, z);
			case 2 -> geometry.cutCells.zFaceOpen(x, y, z + 1);
			default -> throw new IllegalArgumentException("Unknown thermal face axis");
		};
		if (aperture <= 0.0F) return 0.0;
		int first = cellIndex(x, y, z);
		int second = switch (axis) {
			case 0 -> first + 1;
			case 1 -> first + sizeX;
			case 2 -> first + sizeX * sizeY;
			default -> throw new IllegalStateException("Unreachable thermal face axis");
		};
		double firstCoverage = thermalOccupiedFaceFraction(first, axis * 2 + 1);
		double secondCoverage = thermalOccupiedFaceFraction(second, axis * 2);
		double contactFraction = Math.min(firstCoverage, secondCoverage);
		return aperture * contactFraction * (double) config.cellSize() * config.cellSize();
	}

	double thermalOccupiedFaceFraction(int cell, int direction) {
		double geometricOpen = geometry.cutCells.openCellVolume(cell);
		if (!(geometricOpen > 0.0)) return 0.0;
		double solidVolume = granularCoupling == null ? 0.0
				: SolidVolume.toCellVolume(granularCoupling.solidSvq(cell));
		double solidFraction = Math.max(0.0, Math.min(1.0, solidVolume / geometricOpen));
		double liquidCoverage = freeSurfaceGeometry.liquidFaceFraction(cell, direction);
		return solidFraction + (1.0 - solidFraction) * liquidCoverage;
	}

	ThermalTransport.FixedBoundary thermalFixedBoundary(int x, int y, int z, int direction) {
		if (thermalBoundaryTemperatureK == null) return null;
		int cell = cellIndex(x, y, z);
		double temperature = thermalBoundaryTemperatureK[cell * 6 + direction];
		if (Double.isNaN(temperature)) return null;
		double blockedFraction = 1.0 - thermalOpenFaceFraction(x, y, z, direction);
		double contactArea = thermalOccupiedFaceFraction(cell, direction) * blockedFraction
				* (double) config.cellSize() * config.cellSize();
		return contactArea > 0.0 ? new ThermalTransport.FixedBoundary(contactArea, temperature) : null;
	}

	double thermalOpenFaceFraction(int x, int y, int z, int direction) {
		return switch (direction) {
			case 0 -> geometry.cutCells.xFaceOpen(x, y, z);
			case 1 -> geometry.cutCells.xFaceOpen(x + 1, y, z);
			case 2 -> geometry.cutCells.yFaceOpen(x, y, z);
			case 3 -> geometry.cutCells.yFaceOpen(x, y + 1, z);
			case 4 -> geometry.cutCells.zFaceOpen(x, y, z);
			case 5 -> geometry.cutCells.zFaceOpen(x, y, z + 1);
			default -> throw new IllegalArgumentException("Unknown thermal boundary direction");
		};
	}

	LiquidEnergyColumn.Truncation planThermalTruncate(
			int marker, double newMass, ChemicalMixtureRecord keptMixture, double previousTemperatureK
	) {
		return thermalState == null ? null : thermalState.planTruncate(
				marker, newMass, keptMixture, previousTemperatureK, thermalMarkerContext());
	}

	void commitThermalTruncate(int marker, LiquidEnergyColumn.Truncation plan) {
		if (thermalState != null) thermalState.commitTruncate(marker, plan);
	}

	void clearThermalBinding() {
		thermalState = null;
		thermalBoundaryTemperatureK = null;
		Arrays.fill(pendingPhaseCellVolume, SignedDyadic.ZERO);
		if (granularState == null) {
			granularCoupling = null; granularNextCoupling = null; pressureGranularCoupling = null;
		}
	}

	void refreshCoherentOccupancy() {
		if (granularCoupling == null || granularNextCoupling == null) return;
		long[] fixed = new long[cellCount];
		if (thermalState != null) for (CoherentPhaseStore.Group group : thermalState.coherentPhases().snapshot()) {
			fixed[group.cell()] = Math.addExact(fixed[group.cell()], group.occupiedVolumeSvq());
		}
		granularCoupling.setFixedCellSvq(fixed);
		granularNextCoupling.setFixedCellSvq(fixed);
		if (domain().hasGranularInventory()) {
			granularCoupling.rebuild(granularState.parcels, granularState.deposits);
			granularNextCoupling.rebuild(granularState.parcels, granularState.deposits);
		}
	}

	void bindSolidCompositionPoolIfReady() {
		if (granularState == null || granularState.compositions != null || chemicalParcels == null
				|| !granularState.registry.hasReactiveRules()) return;
		if (!granularState.registry.chemicalRegistryFingerprint().equals(chemicalParcels.registry().fingerprint())) {
			throw new IllegalArgumentException("Granular registry chemical dependency mismatch");
		}
		int depositCapacity = Math.min(GranularLimits.MAX_DEPOSIT_GROUPS_PER_REGION,
				Math.multiplyExact(cellCount, GranularLimits.MAX_DEPOSIT_GROUPS_PER_CELL));
		granularState.bindCompositions(new SolidCompositionPool(chemicalParcels.registry(),
				Math.addExact(GranularLimits.MAX_ACTIVE_PARCELS_PER_REGION, depositCapacity)));
		granularPhaseTransfer = new GranularPhaseTransferEngine(chemicalParcels.registry(), unitScale);
	}

	/** Test/command reagent injection on one marker using absolute species quanta. */
}

package cc.ecisr.mmm.thermal;

import cc.ecisr.mmm.chemistry.ChemicalMixtureRecord;
import cc.ecisr.mmm.chemistry.ChemicalRegistry;
import cc.ecisr.mmm.fluid.air.AirNetwork;
import cc.ecisr.mmm.fluid.air.GasInventory;
import cc.ecisr.mmm.fluid.air.GasRegistry;
import cc.ecisr.mmm.units.SolverUnitScale;
import cc.ecisr.mmm.units.SignedDyadic;

import java.util.List;

/**
 * The thermal aggregate of one region: the carrier energy owners (liquid, gas,
 * granular/coherent), the audit state, the derived LTE cell grid, the transport service and
 * the finite-device coordinator. All thermal truth lives here; the fluid facade only
 * delegates, and every truth mutation bumps one of the owner revisions that compose
 * {@link #thermalRevision()}.
 */
public final class ThermalDomainState {
	/** Exact generation token for the four independently mutable thermal truth owners. */
	public record Revision(long liquid, long granular, long gas, long audit) { }
	public record ReactionPlan(int cell, SignedDyadic cellEnergy, SignedDyadic inventoryBefore, double equilibriumTemperatureK) { }
	public record PhaseMechanicalWorkPlan(
			double[] targetCellEnergy, SignedDyadic inventoryBefore, SignedDyadic mechanicalWork
	) {
		public PhaseMechanicalWorkPlan {
			targetCellEnergy = targetCellEnergy.clone();
		}
		@Override public double[] targetCellEnergy() { return targetCellEnergy.clone(); }
	}
	public record PhaseCandidateCell(
			int cell, SignedDyadic cellEnergy,
			List<ThermalStateFunction.InventoryPortion> unchangedSolids
	) { }

	private final ThermalRegistry registry;
	private final ThermalStateFunction stateFunction;
	private final SolverUnitScale unitScale;
	private final int[] materialByFluidHandle;
	private final int[] materialByGranularHandle;
	private final ThermalCellGrid cellGrid;
	private final ThermalTransport transport;
	private final ThermalPhaseChangeEngine phaseChange;
	private final double insertionTemperatureK;
	private final ThermalAuditState auditState;
	private final LiquidEnergyColumn liquidEnergy;
	private final GranularEnergyState granularState;
	private final GasEnergyStore gasState;
	private final ThermalCellOperations cellOperations;
	private final FiniteDeviceExchangeCoordinator devices;
	private ThermalTransport.Stats lastTransport = new ThermalTransport.Stats(
			0, 0, 0.0, SignedDyadic.ZERO, SignedDyadic.ZERO);

	public ThermalDomainState(
			ThermalRegistry registry, int[] materialByFluidHandle, int[] materialByGranularHandle,
			double initialTemperatureK, int initialCapacity, int sizeX, int sizeY, int sizeZ,
			float cellSize, ThermalCarrierContext markers, AirNetwork airNetwork, int maximumTransportSubsteps
	) {
		this(registry, materialByFluidHandle, materialByGranularHandle, initialTemperatureK, initialCapacity,
				sizeX, sizeY, sizeZ, cellSize, markers, airNetwork, maximumTransportSubsteps, null);
	}

	/** Builds a private restore target without first modeling energies that the snapshot will replace. */
	public static ThermalDomainState restoreTarget(
			ThermalRegistry registry, int[] materialByFluidHandle, int[] materialByGranularHandle,
			double initialTemperatureK, int initialCapacity, int sizeX, int sizeY, int sizeZ,
			float cellSize, ThermalCarrierContext markers, AirNetwork airNetwork, int maximumTransportSubsteps,
			double[] restoredLiquidEnergy
	) {
		if (restoredLiquidEnergy == null) throw new IllegalArgumentException("Restored liquid energy is missing");
		return new ThermalDomainState(registry, materialByFluidHandle, materialByGranularHandle,
				initialTemperatureK, initialCapacity, sizeX, sizeY, sizeZ, cellSize, markers, airNetwork,
				maximumTransportSubsteps, restoredLiquidEnergy);
	}

	private ThermalDomainState(
			ThermalRegistry registry, int[] materialByFluidHandle, int[] materialByGranularHandle,
			double initialTemperatureK, int initialCapacity, int sizeX, int sizeY, int sizeZ,
			float cellSize, ThermalCarrierContext markers, AirNetwork airNetwork, int maximumTransportSubsteps,
			double[] restoredLiquidEnergy
	) {
		if (registry == null || materialByFluidHandle == null || materialByGranularHandle == null || markers == null) {
			throw new IllegalArgumentException("Thermal binding input is missing");
		}
		if (!Double.isFinite(initialTemperatureK)) throw new IllegalArgumentException("Initial temperature must be finite");
		this.registry = registry;
		this.stateFunction = new ThermalStateFunction(registry);
		this.unitScale = SolverUnitScale.forCellSize(cellSize);
		this.materialByFluidHandle = materialByFluidHandle;
		this.materialByGranularHandle = materialByGranularHandle;
		this.cellGrid = new ThermalCellGrid(Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ));
		this.transport = new ThermalTransport(sizeX, sizeY, sizeZ, cellSize, maximumTransportSubsteps);
		CoherentPhaseStore coherentPhases = new CoherentPhaseStore(
				Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ),
				Math.min(ThermalLimits.MAX_COHERENT_GROUPS_PER_REGION,
						Math.multiplyExact(Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ), 8)));
		this.phaseChange = new ThermalPhaseChangeEngine(registry);
		this.insertionTemperatureK = initialTemperatureK;
		this.auditState = new ThermalAuditState(ThermalAuditLedger.initialized(SignedDyadic.ZERO));
		this.liquidEnergy = new LiquidEnergyColumn(registry, stateFunction, unitScale, materialByFluidHandle,
				initialTemperatureK, auditState, initialCapacity, markers, restoredLiquidEnergy);
		GranularEnergyColumn granularColumn = new GranularEnergyColumn(
				cc.ecisr.mmm.granular.GranularLimits.MAX_ACTIVE_PARCELS_PER_REGION,
				cc.ecisr.mmm.granular.GranularLimits.MAX_DEPOSIT_GROUPS_PER_REGION);
		this.granularState = new GranularEnergyState(registry, granularColumn, coherentPhases, stateFunction,
				materialByGranularHandle, initialTemperatureK, auditState);
		for (int parcel = 0; parcel < markers.activeParcelCount(); parcel++) {
			granularColumn.addActive(granularState.modeledGranularEnergy(markers.activeParcelMaterial(parcel),
					markers.activeParcelComposition(parcel), markers.activeParcelMass(parcel),
					initialTemperatureK, markers));
		}
		for (GranularEnergyColumn.DepositKey key : markers.depositKeys()) {
			granularColumn.addDeposit(key, granularState.modeledGranularEnergy(key.materialHandle(), key.compositionHandle(),
					markers.depositMass(key), initialTemperatureK, markers));
		}
		boolean supportsGas = airNetwork != null;
		if (supportsGas) for (GasRegistry.AmbientFraction fraction : airNetwork.registry().ambient()) {
			if (!registry.hasMaterial(ThermalMaterialDefinition.CarrierKind.GAS, fraction.componentId())) supportsGas = false;
		}
		if (supportsGas) for (int node = 1; node <= airNetwork.nodeCount(); node++) {
			for (var component : airNetwork.inventory(node).components()) if (!registry.hasMaterial(
					ThermalMaterialDefinition.CarrierKind.GAS, component.componentId())) supportsGas = false;
		}
		if (!supportsGas && airNetwork != null && airNetwork.nodeCount() > 0) {
			throw new IllegalArgumentException("Sealed gas inventory requires complete gas thermal definitions");
		}
		this.gasState = new GasEnergyStore(supportsGas ? new GasThermalModel(registry, airNetwork.registry()) : null,
				initialTemperatureK, auditState, airNetwork);
		this.cellOperations = new ThermalCellOperations(
				registry, stateFunction, unitScale, materialByFluidHandle, materialByGranularHandle,
				cellGrid, auditState, liquidEnergy, granularState, gasState);
		this.devices = new FiniteDeviceExchangeCoordinator(this);
		if (restoredLiquidEnergy == null) {
			auditState.replace(ThermalAuditLedger.initialized(exactEnergy(markers.markerCount())));
		}
	}

	public Revision thermalRevision() {
		return new Revision(
				liquidEnergy.revision(), granularState.revision(), gasState.revision(), auditState.revision());
	}

	public ThermalRegistry registry() { return registry; }
	public ThermalAuditState audit() { return auditState; }
	public ThermalAuditLedger auditLedger() { return auditState.ledger(); }

	// ---- fluid reseeder / restore / capture seams (all revision-bumping coordinated edits) ----

	public void mergeReseedLiquidEnergy(int target, int source) { liquidEnergy.mergeReseedEnergy(target, source); }

	public void assignReseededLiquidEnergies(int source, double sourceEnergy, int destination, double destinationEnergy) {
		liquidEnergy.assignReseededEnergies(source, sourceEnergy, destination, destinationEnergy);
	}

	public void moveReseedLiquidEnergy(int source, int destination) { liquidEnergy.moveEnergy(source, destination); }

	public void restoreLiquidEnergies(double[] restored) { liquidEnergy.adoptRestored(restored); }

	public void restoreGasEnergies(AirNetwork network, double[] restored) {
		gasState.validateRestoredNodeEnergies(network, restored);
		gasState.replaceNodeEnergies(restored);
	}

	public void restoreThermalCoherent(List<CoherentPhaseStore.Group> groups) { granularState.restoreCoherent(groups); }

	public void restoreThermalGranular(double[] activeEnergy, List<GranularEnergyColumn.DepositEnergy> deposits) {
		granularState.restoreGranular(activeEnergy, deposits);
	}

	public void restoreAuditLedger(ThermalAuditLedger ledger) { auditState.replace(ledger); }
	public LiquidEnergyColumn liquid() { return liquidEnergy; }
	public GasEnergyStore gas() { return gasState; }
	public CoherentPhaseStore coherentPhases() { return granularState.coherentPhases(); }
	public GranularEnergyColumn granularColumn() { return granularState.column(); }
	public ThermalPhaseChangeEngine phaseChange() { return phaseChange; }
	public ThermalStateFunction stateFunction() { return stateFunction; }
	ThermalCellGrid grid() { return cellGrid; }
	ThermalTransport transport() { return transport; }

	ThermalCellGrid.CarrierAccess carrierAccess(ThermalCarrierContext markers) {
		return cellOperations.carrierAccess(markers);
	}

	// ---- composed energy sums ----

	public double totalEnergy(int count) {
		double total = liquidEnergy.totalEnergy(count);
		for (CoherentPhaseStore.Group group : granularState.coherentPhases().snapshot()) total += group.internalEnergy();
		total += granularState.column().exactEnergy().toDouble();
		for (double energy : gasState.nodeEnergiesRaw()) total += energy;
		return total;
	}

	public SignedDyadic exactEnergy(int count) {
		return liquidEnergy.exactLiquidEnergy(count).add(granularState.coherentPhases().exactEnergy())
				.add(granularState.column().exactEnergy()).add(gasState.exactGasEnergy());
	}

	public SignedDyadic exactGasEnergy() { return gasState.exactGasEnergy(); }
	public SignedDyadic exactLiquidEnergy(int count) { return liquidEnergy.exactLiquidEnergy(count); }
	public void ensureCapacity(int capacity) { liquidEnergy.ensureCapacity(capacity); }

	public ThermalCellGrid.TemperatureRange observedCarrierTemperatureRange() {
		return cellGrid.observedTemperatureRange();
	}

	public GasLiquidPhaseModel gasLiquidPhase(AirNetwork network) {
		if (!gasState.gasModelBound()) throw new IllegalStateException("Gas thermal model is not bound");
		return new GasLiquidPhaseModel(registry, network.registry());
	}

	// ---- liquid pass-throughs ----

	public double liquidInternalEnergy(int marker) { return liquidEnergy.energy(marker); }
	public double temperature(int marker, ThermalCarrierContext markers) { return liquidEnergy.temperature(marker, markers); }

	public void requireReactionState(
			int marker, ChemicalMixtureRecord candidateMixture, double candidateMass, ThermalCarrierContext markers
	) {
		liquidEnergy.requireReactionState(marker, candidateMixture, candidateMass, markers);
	}

	public void addExternalMarker(int marker, ThermalCarrierContext markers) {
		liquidEnergy.addExternalMarker(marker, markers);
	}

	public LiquidEnergyColumn.ExternalLiquidInsertionPlan planExternalLiquidInsertions(
			List<LiquidEnergyColumn.LiquidInsertion> insertions, double totalEnergy, ThermalCarrierContext markers
	) {
		return liquidEnergy.planExternalLiquidInsertions(insertions, totalEnergy, markers);
	}

	public void commitExternalLiquidInsertions(int firstOrdinal, LiquidEnergyColumn.ExternalLiquidInsertionPlan plan) {
		liquidEnergy.commitExternalLiquidInsertions(firstOrdinal, plan);
	}

	public void removeExternalAndSwap(int index, int last) { liquidEnergy.removeExternalAndSwap(index, last); }
	public void addInternalMarker(int marker, double energy) { liquidEnergy.addInternalMarker(marker, energy); }
	public double removeInternalAndSwap(int index, int last) { return liquidEnergy.removeInternalAndSwap(index, last); }
	public void addLiquidEnergy(int marker, double energy) { liquidEnergy.addLiquidEnergy(marker, energy); }

	public LiquidEnergyColumn.Truncation planTruncate(
			int marker, double newMass, ChemicalMixtureRecord keptMixture,
			double previousTemperatureK, ThermalCarrierContext markers
	) {
		return liquidEnergy.planTruncate(marker, newMass, keptMixture, previousTemperatureK, markers);
	}

	public void commitTruncate(int marker, LiquidEnergyColumn.Truncation plan) { liquidEnergy.commitTruncate(marker, plan); }
	public void commitInternalTruncate(int marker, LiquidEnergyColumn.Truncation plan) {
		liquidEnergy.commitInternalTruncate(marker, plan);
	}

	public void resetMarkerTemperature(int marker, double temperatureK, ThermalCarrierContext markers, SignedDyadic inventoryBefore) {
		liquidEnergy.resetMarkerTemperature(marker, temperatureK, markers);
		auditState.update(ledger -> ledger.withHeatExchange(exactEnergy(markers.markerCount()).subtract(inventoryBefore)));
	}

	public int thermalMaterialHandleForFluid(int fluidHandle) {
		if (fluidHandle < 0 || fluidHandle >= materialByFluidHandle.length) {
			throw new IllegalArgumentException("Unknown liquid phase carrier");
		}
		return materialByFluidHandle[fluidHandle];
	}

	// ---- gas pass-throughs ----

	public boolean hasGasEnergy(int nodeCount) { return gasState.hasGasEnergy(nodeCount); }
	public boolean gasModelBound() { return gasState.gasModelBound(); }
	public double gasNodeEnergy(int nodeId, AirNetwork network) { return gasState.nodeEnergy(nodeId, network); }
	public double gasTemperature(int nodeId, AirNetwork network) { return gasState.temperature(nodeId, network); }
	public double gasGaugePressure(int nodeId, AirNetwork network) { return gasState.gaugePressure(nodeId, network); }
	public double gasDensity(int nodeId, AirNetwork network) { return gasState.density(nodeId, network); }
	public GasThermalModel.HydrodynamicState gasHydrodynamicState(int nodeId, AirNetwork network) {
		return gasState.hydrodynamicState(nodeId, network);
	}

	public double requireGasNodeState(int nodeId, GasInventory inventory, double energy, AirNetwork network) {
		return gasState.requireNodeState(nodeId, inventory, energy, network);
	}

	public void commitGasNodeEnergy(int nodeId, double energy) { gasState.commitNodeEnergy(nodeId, energy); }

	public double[] partitionGasNodeEnergy(int nodeId, List<GasInventory> children, AirNetwork network) {
		return gasState.partitionNodeEnergy(nodeId, children, network);
	}

	public double ambientGasEnergy(GasInventory inventory) { return gasState.ambientGasEnergy(inventory); }

	public GasEnergyStore.GasRemapPlan planGasRemap(
			AirNetwork oldNetwork, List<GasInventory> inventories, double[] availableVolumes,
			double[] mixedEnergy, double[] inheritedAvailableVolumes,
			double sourceEnergy, double sinkEnergy
	) {
		return gasState.planRemap(oldNetwork, inventories, availableVolumes, mixedEnergy,
				inheritedAvailableVolumes, sourceEnergy, sinkEnergy);
	}

	public void requireCanCommitGasRemap(GasEnergyStore.GasRemapPlan plan) {
		gasState.requireCanCommitRemap(plan);
	}

	public void commitGasRemap(GasEnergyStore.GasRemapPlan plan) { gasState.commitRemap(plan); }

	/**
	 * Validates the complete operator-clear audit chain against one thermal generation.
	 * This is deliberately calculation-only: every possible exact-ledger overflow is found
	 * before liquid, coherent, granular or gas truth begins to publish.
	 */
	public void requireCanCommitOperatorClear(
			Revision baseRevision, SignedDyadic liquidEnergy, SignedDyadic coherentEnergy,
			SignedDyadic granularEnergy, GasEnergyStore.GasRemapPlan gasPlan
	) {
		if (!thermalRevision().equals(baseRevision)) {
			throw new IllegalStateException("Thermal inventory changed after operator clear planning");
		}
		if (liquidEnergy == null || coherentEnergy == null || granularEnergy == null) {
			throw new IllegalArgumentException("Operator clear thermal ledger is incomplete");
		}
		ThermalAuditLedger candidate = auditState.ledger()
				.withExternalSinkEnergy(liquidEnergy)
				.withExternalSinkEnergy(coherentEnergy)
				.withExternalSinkEnergy(granularEnergy);
		if (gasPlan != null) {
			gasState.requireCanCommitRemap(gasPlan);
			candidate = candidate.withExternalSourceEnergy(gasPlan.sourceEnergy())
					.withExternalSinkEnergy(gasPlan.sinkEnergy())
					.withMechanicalWork(gasPlan.mechanicalWork())
					.withRoundoff(gasPlan.roundoff());
		}
	}

	/** Prevalidates removal of every sealed-node energy as one external sink against the joint inventory. */
	public GasEnergyStore.GasVentPlan planGasVent(AirNetwork network, int markerCount) {
		if (!hasGasEnergy(network.nodeCount()) || !exactEnergy(markerCount).equals(auditState.ledger().expectedInventory())) {
			throw new IllegalStateException("Gas energy is not bound to a closed thermal inventory");
		}
		return gasState.planVent();
	}

	/** Publishes an already validated all-node gas-energy removal using assignments only. */
	public void commitGasVent(GasEnergyStore.GasVentPlan plan) { gasState.commitVent(plan); }

	public AirNetwork.TransferCommit planGasTransfer(
			int sourceNode, int targetNode, GasInventory moved,
			GasInventory sourceAfter, GasInventory targetAfter, AirNetwork network
	) {
		return gasState.planTransfer(sourceNode, targetNode, moved, sourceAfter, targetAfter, network);
	}

	// ---- granular/coherent pass-throughs ----

	public void addGranularReactionActive() { granularState.addGranularReactionActive(); }
	public double takeGranularReactionActive(int active, long takenSvq, long totalSvq) {
		return granularState.takeGranularReactionActive(active, takenSvq, totalSvq);
	}
	public double takeGranularReactionDeposit(GranularEnergyColumn.DepositKey key, long takenSvq, long totalSvq) {
		return granularState.takeGranularReactionDeposit(key, takenSvq, totalSvq);
	}
	public void requireActiveToDeposit(int active, GranularEnergyColumn.DepositKey target) {
		granularState.requireActiveToDeposit(active, target);
	}
	public void commitActiveToDeposit(int active, GranularEnergyColumn.DepositKey target) {
		granularState.commitActiveToDeposit(active, target);
	}
	public void requireActiveMerge(int first, int second) { granularState.requireActiveMerge(first, second); }
	public void commitActiveMerge(int first, int second) { granularState.commitActiveMerge(first, second); }
	public void requireDepositWake(GranularEnergyColumn.DepositKey source, long takenSvq, long totalSvq) {
		granularState.requireDepositWake(source, takenSvq, totalSvq);
	}
	public void commitDepositWake(GranularEnergyColumn.DepositKey source, long takenSvq, long totalSvq) {
		granularState.commitDepositWake(source, takenSvq, totalSvq);
	}
	public void requireDepositMove(
			GranularEnergyColumn.DepositKey source, GranularEnergyColumn.DepositKey target,
			long movedSvq, long sourceTotalSvq
	) {
		granularState.requireDepositMove(source, target, movedSvq, sourceTotalSvq);
	}
	public void commitDepositMove(
			GranularEnergyColumn.DepositKey source, GranularEnergyColumn.DepositKey target,
			long movedSvq, long sourceTotalSvq
	) {
		granularState.commitDepositMove(source, target, movedSvq, sourceTotalSvq);
	}
	public void clearGranularExternal() { granularState.clearGranularExternal(); }
	public double[] planExternalActiveInsertions(int firstOrdinal, int count, ThermalCarrierContext markers) {
		return granularState.planExternalActiveInsertions(firstOrdinal, count, markers);
	}
	public double planExternalGranularEnergy(int material, int composition, double mass, ThermalCarrierContext markers) {
		return granularState.planExternalGranularEnergy(material, composition, mass, markers);
	}
	public void commitExternalActiveInsertions(double[] energies) { granularState.commitExternalActiveInsertions(energies); }
	public double previewActiveTake(int active, long takenSvq, long totalSvq) {
		return granularState.previewActiveTake(active, takenSvq, totalSvq);
	}
	public double previewDepositTake(GranularEnergyColumn.DepositKey key, long takenSvq, long totalSvq) {
		return granularState.previewDepositTake(key, takenSvq, totalSvq);
	}
	public double commitExternalActiveTake(int active, long takenSvq, long totalSvq) {
		return granularState.commitExternalActiveTake(active, takenSvq, totalSvq);
	}
	public double commitExternalDepositTake(GranularEnergyColumn.DepositKey key, long takenSvq, long totalSvq) {
		return granularState.commitExternalDepositTake(key, takenSvq, totalSvq);
	}
	public void addCoherentExternal(CoherentPhaseStore.Group group) { granularState.addCoherentExternal(group); }
	public void addCoherentInternal(CoherentPhaseStore.Group group) { granularState.addCoherentInternal(group); }
	public CoherentPhaseStore.Group takeCoherentInternal(int cell, String phaseId, long occupiedSvq) {
		return granularState.takeCoherentInternal(cell, phaseId, occupiedSvq);
	}
	public void clearCoherentExternal(List<CoherentPhaseStore.Group> expected, SignedDyadic expectedEnergy) {
		granularState.clearCoherentExternal(expected, expectedEnergy);
	}

	// ---- audit pass-throughs ----

	public void recordExternalSink(SignedDyadic energy) {
		auditState.update(ledger -> ledger.withExternalSinkEnergy(energy));
	}

	public void settleExternalExtraction(double removedCarrierEnergy, double coupledItemEnergy) {
		if (!Double.isFinite(removedCarrierEnergy) || !Double.isFinite(coupledItemEnergy)) {
			throw new IllegalArgumentException("Container thermal settlement is not finite");
		}
		SignedDyadic delta = SignedDyadic.of(coupledItemEnergy).subtract(SignedDyadic.of(removedCarrierEnergy));
		auditState.update(ledger -> ledger.withExternalSinkEnergy(delta).withRoundoff(delta));
	}

	public void recordRoundoff(SignedDyadic delta) { auditState.update(ledger -> ledger.withRoundoff(delta)); }

	// ---- granular reaction plan ----

	public ReactionPlan requireGranularReaction(
			int cell, List<GranularEnergyState.ReactionMarker> markerReplacements,
			GranularEnergyState.GranularReactionEdit granularEdit,
			ThermalCarrierContext markers
	) {
		return cellOperations.requireGranularReaction(cell, markerReplacements, granularEdit, markers);
	}

	public void commitGranularReaction(ReactionPlan plan, ThermalCarrierContext markers) {
		cellOperations.commitGranularReaction(plan, markers);
	}

	// ---- pending phase mechanical work ----

	public PhaseMechanicalWorkPlan planPhaseMechanicalWork(
			SignedDyadic[] pendingCellVolume, float[] relativeGaugePressurePa,
			float pressureReferencePa, float cellSize, ThermalCarrierContext markers
	) {
		return cellOperations.planPhaseMechanicalWork(
				pendingCellVolume, relativeGaugePressurePa, pressureReferencePa, cellSize, markers);
	}

	public void requirePendingPhaseCells(SignedDyadic[] pendingCellVolume, ThermalCarrierContext markers) {
		cellOperations.requirePendingPhaseCells(pendingCellVolume, markers);
	}

	public void commitPhaseMechanicalWork(PhaseMechanicalWorkPlan plan, ThermalCarrierContext markers) {
		cellOperations.commitPhaseMechanicalWork(plan, markers);
	}

	// ---- cell grid observations and repartitions ----

	public double temperatureInCell(int cell, ThermalCarrierContext markers) {
		return cellOperations.temperatureInCell(cell, markers);
	}

	/** Rebuilds the derived LTE grid once before a phase scan over many cells. */
	public void preparePhaseScan(ThermalCarrierContext markers) {
		cellOperations.preparePhaseScan(markers);
	}

	/** Reads one cell from the phase-scan grid; callers must rebuild after every committed carrier edit. */
	public double phaseScanTemperatureInCell(int cell) {
		return cellOperations.phaseScanTemperatureInCell(cell);
	}

	/**
	 * Derives one cell's LTE temperature directly from authoritative carrier inventory and energy.
	 * Unlike {@link #temperatureInCell(int, ThermalCarrierContext)}, this observation path does not rebuild or
	 * mutate the reusable transport grid.
	 */
	public double observeTemperatureInCell(int cell, ThermalCarrierContext markers) {
		return cellOperations.observeTemperatureInCell(cell, markers);
	}

	/** Solves a prospective liquid/coherent phase edit while retaining every same-cell granular heat-bath carrier. */
	public double phaseCandidateTemperature(
			int cell, List<ThermalStateFunction.InventoryPortion> phaseInventory, ThermalCarrierContext markers
	) {
		return cellOperations.phaseCandidateTemperature(cell, phaseInventory, markers);
	}

	/** Freezes exact same-cell energy and unchanged solid heat baths for a gas/liquid candidate search. */
	public PhaseCandidateCell preparePhaseCandidateCell(int cell, ThermalCarrierContext markers) {
		return cellOperations.preparePhaseCandidateCell(cell, markers);
	}

	/** Validates one prospective liquid inventory against a frozen same-cell energy/solid context. */
	public double requirePhaseCandidateState(
			PhaseCandidateCell current, List<ThermalStateFunction.InventoryPortion> liquidInventory,
			double cellEnergyDelta
	) {
		return cellOperations.requirePhaseCandidateState(current, liquidInventory, cellEnergyDelta);
	}

	public void setTemperatureInCell(int cell, double temperatureK, ThermalCarrierContext markers) {
		cellOperations.setTemperatureInCell(cell, temperatureK, markers);
	}

	public void repartitionCellEnergy(int cell, double targetEnergy, ThermalCarrierContext markers, SignedDyadic inventoryBefore) {
		cellOperations.repartitionCellEnergy(cell, targetEnergy, markers, inventoryBefore);
	}

	/**
	 * Closes an inventory-preserving structural commit by assigning the exact pre-commit remainder to one cell.
	 * Gas and non-target carrier energy are already authoritative when this is called; the cell remains the LTE
	 * degree of freedom. This avoids losing a physically meaningful amount through rounded local-energy arithmetic.
	 */
	public void repartitionCellEnergyConserving(int cell, ThermalCarrierContext markers, SignedDyadic inventoryBefore) {
		cellOperations.repartitionCellEnergyConserving(cell, markers, inventoryBefore);
	}

	/** Closes a gas/liquid transfer over its exact two-owner energy boundary without rescanning the region. */
	public void repartitionPhaseCellAndGasEnergyConserving(
			PhaseCandidateCell beforeCell, double gasEnergyBefore, double gasEnergyAfter,
			ThermalCarrierContext markers, boolean carrierTopologyChanged
	) {
		cellOperations.repartitionPhaseCellAndGasEnergyConserving(
				beforeCell, gasEnergyBefore, gasEnergyAfter, markers, carrierTopologyChanged);
	}

	// ---- display ----

	public ThermalDisplaySnapshot displaySnapshot(ThermalCarrierContext markers, AirNetwork network) {
		return cellOperations.displaySnapshot(markers, network);
	}

	// ---- transport ----

	public ThermalTransport.Stats lastTransport() { return lastTransport; }
	public int maximumTransportSubsteps() { return transport.maximumSubsteps(); }

	public ThermalTransport.Stats step(
			ThermalCarrierContext markers, ThermalTransport.FaceArea faceArea, double dtSeconds
	) {
		return step(markers, faceArea, (x, y, z, direction) -> null, dtSeconds);
	}

	public ThermalTransport.Stats step(
			ThermalCarrierContext markers, ThermalTransport.FaceArea faceArea,
			ThermalTransport.BoundaryFace boundaryFace, double dtSeconds
	) {
		SignedDyadic before = exactEnergy(markers.markerCount());
		cellGrid.rebuild(carrierAccess(markers));
		lastTransport = transport.step(cellGrid, faceArea, boundaryFace, dtSeconds);
		SignedDyadic physicalDelta = exactEnergy(markers.markerCount()).subtract(before);
		auditState.update(ledger -> ledger.withHeatExchange(lastTransport.reservoirExchange())
				.withRoundoff(physicalDelta.subtract(lastTransport.reservoirExchange())));
		return lastTransport;
	}

	// ---- finite device delegation ----

	public List<FiniteThermalExchange.Contact> finiteDeviceContacts(
			List<FiniteDeviceExchangeCoordinator.CellContactRequest> requests, ThermalCarrierContext markers
	) {
		return devices.finiteDeviceContacts(requests, markers);
	}

	public FiniteDeviceExchangeCoordinator.FiniteDeviceCellPlan planFiniteDeviceCellExchange(
			List<FiniteDeviceExchangeCoordinator.CellEnergyDelta> deltas, ThermalCarrierContext markers
	) {
		return devices.planFiniteDeviceCellExchange(deltas, markers);
	}

	public void commitFiniteDeviceCellExchange(
			FiniteDeviceExchangeCoordinator.FiniteDeviceCellPlan plan, ThermalCarrierContext markers
	) {
		devices.commitFiniteDeviceCellExchange(plan, markers);
	}

}

package mmm.thermal;

import mmm.chemistry.ChemicalMixtureRecord;
import mmm.granular.GranularInventory;
import mmm.fluid.air.AirNetwork;
import mmm.fluid.air.GasInventory;
import mmm.fluid.air.GasRegistry;
import mmm.units.SolverUnitScale;
import mmm.units.SignedDyadic;

import java.util.List;

/**
 * The thermal aggregate of one region: the carrier energy owners (liquid, gas,
 * granular/coherent), the audit state, the derived LTE cell grid, the transport service and
 * the finite-device coordinator. All thermal truth lives here; the fluid facade only
 * delegates, and every truth mutation bumps one of the owner revisions that compose
 * {@link #thermalRevision()}.
 */
public final class ThermalDomainState {
	/** A phase candidate exhausted its last condensed heat bath before its energy could close. */
	public static final class EmptyPhaseCandidateEnergyException extends IllegalArgumentException {
		public EmptyPhaseCandidateEnergyException() {
			super("Empty phase cell retains internal energy");
		}
	}
	/** Exact generation token for the four independently mutable thermal truth owners. */
	public record Revision(long liquid, long granular, long gas, long audit) { }
	public static final class ReactionPlan {
		private final ThermalDomainState owner;
		private final Revision revision;
		private final ThermalCellOperations.PhaseCellAssignment assignment;
		private ReactionPlan(ThermalDomainState owner, Revision revision, ThermalCellOperations.PhaseCellAssignment assignment) {
			this.owner = owner; this.revision = revision; this.assignment = assignment;
		}
	}
	public static final class PhaseCandidateCell {
		private final ThermalDomainState owner;
		private final Revision revision;
		private final int cell;
		private final SignedDyadic cellEnergy;
		private final List<ThermalStateFunction.InventoryPortion> unchangedSolids;
		private final ThermalCellOperations.PhaseOwner[] unchangedOwners;
		PhaseCandidateCell(ThermalDomainState owner, Revision revision, int cell, SignedDyadic cellEnergy,
				List<ThermalStateFunction.InventoryPortion> unchangedSolids, ThermalCellOperations.PhaseOwner[] unchangedOwners) {
			this.owner = owner; this.revision = revision; this.cell = cell; this.cellEnergy = cellEnergy;
			this.unchangedSolids = List.copyOf(unchangedSolids); this.unchangedOwners = unchangedOwners;
		}
		public int cell() { return cell; }
		public SignedDyadic cellEnergy() { return cellEnergy; }
		public List<ThermalStateFunction.InventoryPortion> unchangedSolids() { return unchangedSolids; }
		ThermalCellOperations.PhaseOwner[] unchangedOwners() { return unchangedOwners; }
	}

	private final ThermalRegistry registry;
	private final ThermalStateFunction stateFunction;
	private final SolverUnitScale unitScale;
	private final int[] materialByFluidHandle;
	private final int[] materialByGranularHandle;
	private final ThermalCellGrid cellGrid;
	private ThermalCellGrid observationGrid;
	private ThermalSpatialObservation spatialObservation;
	private boolean spatialObservationPrepared;
	private final int maximumTransportSubsteps;
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
				sizeX, sizeY, sizeZ, cellSize, markers, airNetwork, maximumTransportSubsteps, null, true);
	}

	/** Builds a private restore target with zero granular placeholders and the snapshot's liquid energies. */
	public static ThermalDomainState restoreTarget(
			ThermalRegistry registry, int[] materialByFluidHandle, int[] materialByGranularHandle,
			double initialTemperatureK, int initialCapacity, int sizeX, int sizeY, int sizeZ,
			float cellSize, ThermalCarrierContext markers, AirNetwork airNetwork, int maximumTransportSubsteps,
			double[] restoredLiquidEnergy
	) {
		if (restoredLiquidEnergy == null) throw new IllegalArgumentException("Restored liquid energy is missing");
		return new ThermalDomainState(registry, materialByFluidHandle, materialByGranularHandle,
				initialTemperatureK, initialCapacity, sizeX, sizeY, sizeZ, cellSize, markers, airNetwork,
				maximumTransportSubsteps, restoredLiquidEnergy, false);
	}

	/** Rebinds caloric caches while retaining the already stored liquid-cell internal energies. */
	public static ThermalDomainState restoreOwnerTarget(ThermalRegistry registry, int[] materialByFluidHandle, int[] materialByGranularHandle,
			double initialTemperatureK, int initialCapacity, int sizeX, int sizeY, int sizeZ,
			float cellSize, ThermalCarrierContext owners, AirNetwork network, int maximumTransportSubsteps) {
		return new ThermalDomainState(registry,materialByFluidHandle,materialByGranularHandle,initialTemperatureK,initialCapacity,
				sizeX,sizeY,sizeZ,cellSize,owners,network,maximumTransportSubsteps,null,false);
	}

	private ThermalDomainState(
			ThermalRegistry registry, int[] materialByFluidHandle, int[] materialByGranularHandle,
			double initialTemperatureK, int initialCapacity, int sizeX, int sizeY, int sizeZ,
			float cellSize, ThermalCarrierContext markers, AirNetwork airNetwork, int maximumTransportSubsteps,
			double[] restoredLiquidEnergy, boolean initializeOwners
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
		if(maximumTransportSubsteps<=0||maximumTransportSubsteps>ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS)
			throw new IllegalArgumentException("Thermal substep budget is invalid");
		this.maximumTransportSubsteps=maximumTransportSubsteps;
		CoherentPhaseStore coherentPhases = new CoherentPhaseStore(
				Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ),
				Math.min(ThermalLimits.MAX_COHERENT_GROUPS_PER_REGION,
						Math.multiplyExact(Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ), 8)));
		this.insertionTemperatureK = initialTemperatureK;
		this.auditState = new ThermalAuditState(ThermalAuditLedger.initialized(SignedDyadic.ZERO));
		this.liquidEnergy = new LiquidEnergyColumn(registry, stateFunction, unitScale, materialByFluidHandle,
				initialTemperatureK, auditState, initialCapacity, markers, restoredLiquidEnergy, initializeOwners);
		GranularEnergyColumn granularColumn = new GranularEnergyColumn(
				mmm.granular.GranularLimits.MAX_ACTIVE_PARCELS_PER_REGION,
				mmm.granular.GranularLimits.MAX_DEPOSIT_GROUPS_PER_REGION);
		this.granularState = new GranularEnergyState(registry, granularColumn, coherentPhases, stateFunction,
				materialByGranularHandle, initialTemperatureK, auditState);
		for (int parcel = 0; parcel < markers.activeParcelCount(); parcel++) {
			granularColumn.addActive(!initializeOwners ? 0 : granularState.modeledGranularEnergy(markers.activeParcelMaterial(parcel),
					markers.activeParcelComposition(parcel), markers.activeParcelMass(parcel),
					initialTemperatureK, markers));
		}
		for (GranularEnergyColumn.DepositKey key : markers.depositKeys()) {
			granularColumn.addDeposit(key, !initializeOwners ? 0 : granularState.modeledGranularEnergy(key.materialHandle(), key.compositionHandle(),
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
		GasThermalModel gasModel = supportsGas ? new GasThermalModel(registry, airNetwork.registry()) : null;
		this.gasState = initializeOwners
				? new GasEnergyStore(gasModel, initialTemperatureK, auditState, airNetwork)
				: GasEnergyStore.restoreTarget(gasModel, initialTemperatureK, auditState, airNetwork);
		this.cellOperations = new ThermalCellOperations(
				registry, stateFunction, unitScale, materialByFluidHandle, materialByGranularHandle,
				cellGrid, auditState, liquidEnergy, granularState, gasState);
		this.devices = new FiniteDeviceExchangeCoordinator(this);
		if (initializeOwners) {
			auditState.replace(ThermalAuditLedger.initialized(exactEnergy(markers.markerCount())));
		}
	}

	public Revision thermalRevision() {
		return new Revision(
				liquidEnergy.revision(), granularState.revision(), gasState.revision(), auditState.revision());
	}

	/** Tests the complete owner revision tuple without allocating an observation snapshot. */
	public boolean matchesThermalRevision(Revision revision) {
		return revision != null && revision.liquid() == liquidEnergy.revision()
				&& revision.granular() == granularState.revision()
				&& revision.gas() == gasState.revision() && revision.audit() == auditState.revision();
	}
	/** Condensed caloric observations depend on liquid and solid owners. */
	public boolean matchesCondensedRevision(Revision revision) {
		return revision!=null&&revision.liquid()==liquidEnergy.revision()&&revision.granular()==granularState.revision();
	}

	public ThermalRegistry registry() { return registry; }
	public double insertionTemperatureK() { return insertionTemperatureK; }
	public ThermalAuditState audit() { return auditState; }
	public ThermalAuditLedger auditLedger() { return auditState.ledger(); }
	/** Transfers the parcel's discrete mechanical work to its carried internal energy. */
	public void addGranularMechanicalHeat(int ordinal,SignedDyadic joules) { granularState.addMechanicalHeat(ordinal,joules); }

	// ---- restore / capture ----

	public void restoreLiquidEnergies(double[] restored) { liquidEnergy.adoptRestored(restored); }

	public void restoreGasEnergies(AirNetwork network, double[] restored) {
		gasState.validateRestoredNodeEnergies(network, restored);
		gasState.replaceNodeEnergies(restored);
	}

	public void restoreThermalCoherent(List<CoherentPhaseStore.Group> groups) { granularState.restoreCoherent(groups); }

	public void restoreThermalGranular(double[] activeEnergy, List<GranularEnergyColumn.DepositEnergy> deposits) {
		granularState.restoreGranular(activeEnergy, deposits);
	}

	/** Validates the actual restored carrier inventories before replacing either granular energy column. */
	public void restoreThermalGranular(double[] activeEnergy, List<GranularEnergyColumn.DepositEnergy> deposits,
			ThermalCarrierContext markers) {
		if (activeEnergy == null || deposits == null || markers == null
				|| activeEnergy.length != markers.activeParcelCount()) {
			throw new IllegalArgumentException("Restored granular thermal inventory is incompatible");
		}
		List<GranularEnergyColumn.DepositKey> keys = markers.depositKeys();
		if (deposits.size() != keys.size()) throw new IllegalArgumentException("Restored granular deposit energies are incompatible");
		for (int parcel = 0; parcel < activeEnergy.length; parcel++) {
			stateFunction.stateForInternalEnergy(materialByGranularHandle[markers.activeParcelMaterial(parcel)],
					markers.activeParcelMass(parcel), markers.solidComposition(markers.activeParcelComposition(parcel)), activeEnergy[parcel]);
		}
		GranularEnergyColumn.DepositKey previous = null;
		for (int index = 0; index < deposits.size(); index++) {
			GranularEnergyColumn.DepositEnergy deposit = deposits.get(index);
			if (deposit == null || !deposit.key().equals(keys.get(index)) || previous != null && previous.compareTo(deposit.key()) >= 0)
				throw new IllegalArgumentException("Restored granular deposit keys are not canonical");
			stateFunction.stateForInternalEnergy(materialByGranularHandle[deposit.key().materialHandle()],
					markers.depositMass(deposit.key()), markers.solidComposition(deposit.key().compositionHandle()), deposit.internalEnergy());
			previous = deposit.key();
		}
		granularState.restoreGranular(activeEnergy, deposits);
	}

	public void restoreAuditLedger(ThermalAuditLedger ledger) { auditState.replace(ledger); }
	public LiquidEnergyColumn liquid() { return liquidEnergy; }
	public GasEnergyStore gas() { return gasState; }
	public CoherentPhaseStore coherentPhases() { return granularState.coherentPhases(); }
	public GranularEnergyColumn granularColumn() { return granularState.column(); }
	public ThermalStateFunction stateFunction() { return stateFunction; }
	ThermalCellGrid grid() { return cellGrid; }

	public ThermalCellGrid.CarrierAccess carrierAccess(ThermalCarrierContext markers) {
		return cellOperations.carrierAccess(markers);
	}

	/** Spatial exchange samples include finite gas; phase LTE keeps its condensed inventory view. */
	public ThermalCellGrid.CarrierAccess exchangeCarrierAccess(ThermalCarrierContext markers) {
		var condensed=carrierAccess(markers);
		return gasState.gasModelBound() && markers.gasNetwork()!=null
				? new ThermalGasCarriers(condensed,gasState,registry,markers) : condensed;
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

	/** Validates explicit liquid source, energy rounding and gas work in their publication order. */
	public void requireGasRemapAfterExternalLiquidInsertion(
			LiquidEnergyColumn.ExternalLiquidInsertionPlan liquid, GasEnergyStore.GasRemapPlan gas) {
		var after = liquidEnergy.requireExternalInsertionCurrent(liquid);
		gasState.requireCanCommitRemap(gas);
		after.withExternalSourceEnergy(gas.sourceEnergy()).withExternalSinkEnergy(gas.sinkEnergy())
				.withMechanicalWork(gas.mechanicalWork()).withRoundoff(gas.roundoff()).expectedInventory();
	}

	/** Checks each external truncation, container rounding and gas work in their publication order. */
	public void requireCanCommitLiquidExtraction(List<SignedDyadic> removedEnergy, Double itemEnergy,
	                                           GasEnergyStore.GasRemapPlan gas) {
		if (removedEnergy == null || itemEnergy != null && !Double.isFinite(itemEnergy))
			throw new IllegalArgumentException("Liquid extraction energy is invalid");
		var after = auditState.ledger();
		SignedDyadic removed = SignedDyadic.ZERO;
		for (var energy : removedEnergy) {
			after = after.withExternalSinkEnergy(energy); after.expectedInventory();
			removed = removed.add(energy);
		}
		if (itemEnergy != null) {
			var delta = SignedDyadic.of(itemEnergy).subtract(removed);
			after = after.withExternalSinkEnergy(delta).withRoundoff(delta); after.expectedInventory();
		}
		if (gas != null) {
			gasState.requireCanCommitRemap(gas);
			after.withExternalSourceEnergy(gas.sourceEnergy()).withExternalSinkEnergy(gas.sinkEnergy())
					.withMechanicalWork(gas.mechanicalWork()).withRoundoff(gas.roundoff()).expectedInventory();
		}
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

	public int thermalMaterialHandleForGranular(int granularHandle) {
        if(granularHandle<0||granularHandle>=materialByGranularHandle.length)
            throw new IllegalArgumentException("Unknown granular phase carrier");
        return materialByGranularHandle[granularHandle];
    }

	// ---- gas pass-throughs ----

	public boolean hasGasEnergy(int nodeCount) { return gasState.hasGasEnergy(nodeCount); }
	public boolean gasModelBound() { return gasState.gasModelBound(); }
	public double gasNodeEnergy(int nodeId, AirNetwork network) { return gasState.nodeEnergy(nodeId, network); }
	public double gasTemperature(int nodeId, AirNetwork network) { return gasState.temperature(nodeId, network); }
	public GasEnergyStore.PhaseState gasPhaseState(int nodeId,AirNetwork network) { return gasState.phaseState(nodeId,network); }
	public double gasGaugePressure(int nodeId, AirNetwork network) { return gasState.gaugePressure(nodeId, network); }
	public AirNetwork.NodeThermodynamics gasThermodynamics(AirNetwork network) { return gasState.thermodynamics(network); }
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
	public double gasEnergyAtTemperature(GasInventory inventory, double temperatureK) {
		return gasState.energyAtTemperature(inventory, temperatureK);
	}
	/** External fixed-volume gas edits prepare inventory, energy and source/sink audit together. */
	public GasEnergyStore.InventoryEnergyPlan planExternalGasInventoryEnergy(AirNetwork network,
			List<Integer> nodes, List<GasInventory> inventories, double[] energies,
			SignedDyadic source, SignedDyadic sink) {
		return gasState.planInventoryEnergy(network, nodes, inventories, energies, source, sink, SignedDyadic.ZERO);
	}
	public void commitExternalGasInventoryEnergy(GasEnergyStore.InventoryEnergyPlan plan) {
		gasState.commitInventoryEnergy(plan);
	}

	public GasEnergyStore.GasRemapPlan planGasRemap(
			AirNetwork oldNetwork, List<GasInventory> inventories, double[] availableVolumes,
			double[] mixedEnergy, double[] inheritedAvailableVolumes,
			SignedDyadic sourceEnergy, SignedDyadic sinkEnergy
	) {
		return gasState.planRemap(oldNetwork, inventories, availableVolumes, mixedEnergy,
				inheritedAvailableVolumes, sourceEnergy, sinkEnergy);
	}
	public GasEnergyStore.GasRemapPlan planGasRemapWithSourceWork(AirNetwork old,List<GasInventory> stocks,
			double[] volumes,double[] mixed,SignedDyadic source,SignedDyadic sink,SignedDyadic work){
		return gasState.planRemapWithSourceWork(old,stocks,volumes,mixed,source,sink,work);
	}

	/** Plans the topology successor against the complete prepared phase energy and audit. */
	public GasEnergyStore.GasRemapPlan planGasRemapAfterPhase(PhaseCellEnergyPlan phase, int node, double energy,
			AirNetwork network, List<GasInventory> inventories, double[] volumes, double[] mixed,
			double[] inherited, SignedDyadic source, SignedDyadic sink) {
		requirePhaseCellEnergyCurrent(phase);
		return gasState.planRemapAfterPhase(network, node, energy, phase.assignment.afterAudit(),
				inventories, volumes, mixed, inherited, source, sink);
	}

	public void requireGasRemapAfterPhaseCurrent(GasEnergyStore.GasRemapPlan remap, PhaseCellEnergyPlan phase) {
		requirePhaseCellEnergyCurrent(phase);
		gasState.requireRemapAfterPhaseCurrent(remap, phase.assignment.afterAudit());
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
	public void requireCanCommitGasVent(GasEnergyStore.GasVentPlan plan) {
		gasState.requireCanCommitVent(plan);
	}

	/** Publishes an already validated all-node gas-energy removal using assignments only. */
	public void commitGasVent(GasEnergyStore.GasVentPlan plan) { gasState.commitVent(plan); }

	public AirNetwork.TransferCommit planGasFlow(AirNetwork.FlowBatch batch, AirNetwork network) {
		return gasState.planFlow(batch, network);
	}
	public GasEnergyStore.InventoryEnergyPlan planGasInventoryEnergy(AirNetwork network, List<Integer> nodes,
	                                                              List<GasInventory> inventories, double[] energies,
	                                                              SignedDyadic internalTransfer) {
		return gasState.planPhaseInventoryEnergy(network, nodes, inventories, energies, internalTransfer);
	}
	public void requireGasInventoryEnergyCurrent(GasEnergyStore.InventoryEnergyPlan plan, AirNetwork network) {
		gasState.requireInventoryEnergyCurrent(plan, network);
	}
	public void commitGasInventoryEnergyValues(GasEnergyStore.InventoryEnergyPlan plan) {
		gasState.commitInventoryEnergyValues(plan);
	}
	public GasEnergyStore.GasRemapPlan planGasRemapAfterBatch(AirNetwork network,
			GasEnergyStore.InventoryEnergyPlan gasPhase, PhaseBatchEnergyPlan cells,
			List<GasInventory> inventories, double[] volumes, double[] mixed, double[] inherited,
			SignedDyadic source, SignedDyadic sink) {
		requirePhaseBatchEnergyCurrent(cells);
		return gasState.planRemapAfterInventoryBatch(network, gasPhase, cells.assignment.afterAudit(),
				inventories, volumes, mixed, inherited, source, sink);
	}
	public void requireGasRemapAfterBatchCurrent(GasEnergyStore.GasRemapPlan remap,
			GasEnergyStore.InventoryEnergyPlan gasPhase, PhaseBatchEnergyPlan cells, AirNetwork network) {
		requirePhaseBatchEnergyCurrent(cells);
		gasState.requireRemapAfterInventoryBatchCurrent(remap, gasPhase, network, cells.assignment.afterAudit());
	}

	// ---- granular/coherent pass-throughs ----

	public void addGranularReactionActive() { granularState.addGranularReactionActive(); }
	public double takeGranularReactionActive(int active, long takenMassUnits, long totalMassUnits) {
		return granularState.takeGranularReactionActive(active, takenMassUnits, totalMassUnits);
	}
	public double takeGranularReactionDeposit(GranularEnergyColumn.DepositKey key, long takenMassUnits, long totalMassUnits) {
		return granularState.takeGranularReactionDeposit(key, takenMassUnits, totalMassUnits);
	}
	public mmm.granular.GranularInventory.EnergyPlan planActiveToDeposits(
			List<mmm.granular.GranularInventory.DepositTransfer> transfers
	) {
		return granularState.planActiveToDeposits(transfers);
	}
	public boolean canActiveMerge(int first, int second, int material, double mass, java.util.Map<String, Long> composition, SignedDyadic heat) {
		return granularState.canActiveMerge(first, second, material, mass, composition, heat);
	}
	public void commitActiveMerge(int first, int second, SignedDyadic heat) { granularState.commitActiveMerge(first, second, heat); }
	public GranularInventory.EnergyPlan planDepositWake(GranularEnergyColumn.DepositKey source, long takenMassUnits, long totalMassUnits) {
		Revision before = thermalRevision(); var plan = granularState.planDepositWake(source, takenMassUnits, totalMassUnits);
		return new GranularInventory.EnergyPlan() {
			@Override public void requireCurrent() {
				if (!matchesThermalRevision(before)) throw new IllegalStateException("Thermal inventory changed after wake planning");
				plan.requireCurrent();
			}
			@Override public void commit() { requireCurrent(); plan.commit(); }
		};
	}
	public GranularInventory.EnergyPlan planDepositMove(GranularEnergyColumn.DepositKey source, GranularEnergyColumn.DepositKey target,
			long movedMassUnits, long sourceMassUnits, SignedDyadic heat) {
		return granularState.planDepositMove(source, target, movedMassUnits, sourceMassUnits, heat);
	}
	public void clearGranularExternal() { granularState.clearGranularExternal(); }
	public double[] planExternalActiveInsertions(int firstOrdinal, int count, ThermalCarrierContext markers) {
		return granularState.planExternalActiveInsertions(firstOrdinal, count, markers);
	}
	public double planExternalGranularEnergy(int material, int composition, double mass, ThermalCarrierContext markers) {
		return granularState.planExternalGranularEnergy(material, composition, mass, markers);
	}
	public void commitExternalActiveInsertions(double[] energies) { granularState.commitExternalActiveInsertions(energies); }
	public double previewActiveTake(int active, long takenMassUnits, long totalMassUnits) {
		return granularState.previewActiveTake(active, takenMassUnits, totalMassUnits);
	}
	public double previewDepositTake(GranularEnergyColumn.DepositKey key, long takenMassUnits, long totalMassUnits) {
		return granularState.previewDepositTake(key, takenMassUnits, totalMassUnits);
	}
	public double commitExternalActiveTake(int active, long takenMassUnits, long totalMassUnits) {
		return granularState.commitExternalActiveTake(active, takenMassUnits, totalMassUnits);
	}
	public double commitExternalDepositTake(GranularEnergyColumn.DepositKey key, long takenMassUnits, long totalMassUnits) {
		return granularState.commitExternalDepositTake(key, takenMassUnits, totalMassUnits);
	}
	public mmm.granular.GranularInventory.EnergyPlan planGranularEnergyReplacement(double[] active,
			List<GranularEnergyColumn.DepositEnergy> deposits) {
		return granularState.planEnergyReplacement(active,deposits,null);
	}
	public mmm.granular.GranularInventory.EnergyPlan planGranularEnergyReplacement(double[] active,
			List<GranularEnergyColumn.DepositEnergy> deposits,List<CoherentPhaseStore.Group> coherent) {
		return granularState.planEnergyReplacement(active,deposits,coherent);
	}

	public GranularEnergyState.CoherentExternalPlan planCoherentExternal(CoherentPhaseStore.Group group) {
		return granularState.planCoherentExternal(group);
	}
	public void addCoherentInternal(CoherentPhaseStore.Group group) { granularState.addCoherentInternal(group); }
	public CoherentPhaseStore.Group takeCoherentInternal(int cell, String phaseId, long occupiedSvq,
	                                                  mmm.chemistry.ChemicalRegistry chemistry) {
		return granularState.takeCoherentInternal(cell, phaseId, occupiedSvq, chemistry);
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
		settleExternalExtraction(SignedDyadic.of(removedCarrierEnergy), coupledItemEnergy);
	}

	public void settleExternalExtraction(SignedDyadic bookedBoundaryEnergy, double coupledItemEnergy) {
		if (bookedBoundaryEnergy == null || !Double.isFinite(coupledItemEnergy)) {
			throw new IllegalArgumentException("Container thermal settlement is not finite");
		}
		SignedDyadic delta = SignedDyadic.of(coupledItemEnergy).subtract(bookedBoundaryEnergy);
		auditState.update(ledger -> ledger.withExternalSinkEnergy(delta).withRoundoff(delta));
	}

	public void settleExternalInsertion(double insertedCarrierEnergy, double coupledItemEnergy) {
		if (!Double.isFinite(insertedCarrierEnergy) || !Double.isFinite(coupledItemEnergy)) {
			throw new IllegalArgumentException("Container thermal settlement is not finite");
		}
		SignedDyadic delta = SignedDyadic.of(coupledItemEnergy).subtract(SignedDyadic.of(insertedCarrierEnergy));
		auditState.update(ledger -> ledger.withExternalSourceEnergy(delta).withRoundoff(delta.negate()));
	}

	public void recordRoundoff(SignedDyadic delta) { auditState.update(ledger -> ledger.withRoundoff(delta)); }

	// ---- granular reaction plan ----

	public ReactionPlan requireGranularReaction(
			int cell, List<GranularEnergyState.ReactionMarker> markerReplacements,
			GranularEnergyState.GranularReactionEdit granularEdit,
			ThermalCarrierContext markers
	) {
		try { return new ReactionPlan(this, thermalRevision(),
				cellOperations.requireGranularReaction(cell, markerReplacements, granularEdit, markers)); }
		catch (ThermalCellEnergyAssignment.UnavailableException unavailable) {
			throw new PhaseCellEnergyUnavailableException(unavailable);
		}
	}

	public void requireGranularReactionCurrent(ReactionPlan plan) {
		if (plan == null || plan.owner != this || !matchesThermalRevision(plan.revision))
			throw new IllegalStateException("Thermal reaction plan is stale or belongs to another domain");
	}

	public void commitGranularReaction(ReactionPlan plan, ThermalCarrierContext markers) {
		if (plan == null || plan.owner != this) throw new IllegalStateException("Thermal reaction plan belongs to another domain");
		cellOperations.commitPhaseCellEnergy(plan.assignment, markers);
	}

	// ---- pending phase mechanical work ----




	// ---- cell grid observations and repartitions ----

	public double temperatureInCell(int cell, ThermalCarrierContext markers) {
		return cellOperations.temperatureInCell(cell, markers);
	}

	/** Rebuilds the derived LTE grid once before a phase scan over many cells. */
	public void preparePhaseScan(ThermalCarrierContext markers) {
		cellOperations.preparePhaseScan(markers, thermalRevision());
	}
	/** Shares the final conduction sample while no intervening reaction changed its owner revision. */
	public void preparePhaseScanAfterConduction(ThermalCarrierContext markers,ThermalCarrierTransport.Batch sample,Revision source){
		var current=thermalRevision();
		cellOperations.preparePhaseScan(markers,current,sample!=null&&current.equals(source)?sample:null);
	}

	/** Rebuilds a read-only spatial caloric observation without changing the phase/transport LTE grid. */
	public void prepareSpatialObservation(ThermalCarrierContext markers) {
		spatialObservationPrepared = false;
		markers.prepareObservationSupport();
		if (observationGrid == null) {
			observationGrid = new ThermalCellGrid(cellGrid.cellCount());
			spatialObservation = new ThermalSpatialObservation();
		}
		spatialObservation.prepare(carrierAccess(markers), markers);
		observationGrid.rebuild(spatialObservation);
		spatialObservationPrepared = true;
	}

	/** Valid within the prepared batch; rebuild after position, composition or energy changes. */
	public double spatialTemperatureInCell(int cell) {
		if (!spatialObservationPrepared) throw new IllegalStateException("Thermal spatial observation has not been prepared");
		return observationGrid.temperature(cell);
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


	/** Freezes exact same-cell energy and unchanged solid heat baths for a gas/liquid candidate search. */
	public PhaseCandidateCell preparePhaseCandidateCell(int cell, ThermalCarrierContext markers) {
		return preparePhaseCandidateCells(new int[]{cell}, markers)[cell];
	}
	public PhaseCandidateCell[] preparePhaseCandidateCells(int[] cells, ThermalCarrierContext owners) {
		return cellOperations.preparePhaseCandidateCells(cells, owners, this, thermalRevision());
	}

	/** A cell-local raw-energy assignment and its complete audit, prepared before either phase writes. */
	public static final class PhaseCellEnergyPlan {
		private final ThermalDomainState owner;
		private final Revision revision;
		private final ThermalCellOperations.PhaseCellAssignment assignment;
		private PhaseCellEnergyPlan(ThermalDomainState owner, Revision revision, ThermalCellOperations.PhaseCellAssignment assignment) {
			this.owner = owner; this.revision = revision; this.assignment = assignment;
		}
		public double temperatureK() { return assignment.raw().actualState() == null ? Double.NaN
				: assignment.raw().actualState().temperatureK(); }
	}

	public static final class PhaseCellEnergyUnavailableException extends IllegalArgumentException {
		private PhaseCellEnergyUnavailableException(ThermalCellEnergyAssignment.UnavailableException cause) { super(cause.getMessage(), cause); }
	}

	/** Common-temperature evaluation of the future real owner set, before energy publication. */
	public static final class PhaseCellTrial {
		private final ThermalDomainState owner;
		private final Revision revision;
		private final int cell;
		private final ThermalCellEnergyAssignment.Trial raw;
		private final SignedDyadic emptyEnergyRemainder;
		private PhaseCellTrial(ThermalDomainState owner, Revision revision, int cell,
				ThermalCellEnergyAssignment.Trial raw, SignedDyadic emptyEnergyRemainder) {
			this.owner = owner; this.revision = revision; this.cell = cell; this.raw = raw;
			this.emptyEnergyRemainder = emptyEnergyRemainder;
		}
		public double temperatureK() { return raw == null ? Double.NaN : raw.state.temperatureK(); }
	}

	public PhaseCellTrial trialPhaseCellEnergy(PhaseCandidateCell current,
			List<ThermalStateFunction.InventoryPortion> liquidInventory, double gasEnergyBefore, double gasEnergyAfter) {
		requirePhaseCandidateCurrent(current);
		return new PhaseCellTrial(this, current.revision, current.cell(),
				cellOperations.trialPhaseCellEnergy(current, liquidInventory, gasEnergyBefore, gasEnergyAfter, false), SignedDyadic.ZERO);
	}
	public PhaseCellTrial trialPhaseCellEnergyWithHeat(PhaseCandidateCell current,
			List<ThermalStateFunction.InventoryPortion> liquidInventory, double gasEnergyChange, SignedDyadic mechanicalHeat) {
		requirePhaseCandidateCurrent(current);
		return new PhaseCellTrial(this, current.revision, current.cell(),
				cellOperations.trialPhaseCellEnergy(current, liquidInventory, 0, gasEnergyChange, false, mechanicalHeat), SignedDyadic.ZERO);
	}

	/** Replaces liquid/coherent owners while keeping the prepared granular heat baths. */
	public PhaseCellTrial trialPhaseCellEnergy(PhaseCandidateCell current,
			List<ThermalStateFunction.InventoryPortion> liquidAndCoherentInventory) {
		requirePhaseCandidateCurrent(current);
		return new PhaseCellTrial(this, current.revision, current.cell(),
				cellOperations.trialPhaseCellEnergy(current, liquidAndCoherentInventory, 0, 0, true), SignedDyadic.ZERO);
	}

	public PhaseCellTrial trialCondensedCellEnergyWithHeat(PhaseCandidateCell current,
			List<ThermalStateFunction.InventoryPortion> inventory,SignedDyadic mechanicalHeat) {
		requirePhaseCandidateCurrent(current);
		return new PhaseCellTrial(this,current.revision,current.cell(),
				cellOperations.trialPhaseCellEnergy(current,inventory,0,0,true,mechanicalHeat),SignedDyadic.ZERO);
	}

	/** Complete future-owner LTE projection; the enclosing transaction owns all metadata and publication. */
	public PhaseCellTrial trialOwnedCellEnergy(PhaseCandidateCell current,
			List<ThermalStateFunction.InventoryPortion> inventory,SignedDyadic mechanicalHeat) {
		requirePhaseCandidateCurrent(current);
		return new PhaseCellTrial(this,current.revision,current.cell(),
				cellOperations.trialOwnedCellEnergy(current,inventory,mechanicalHeat),SignedDyadic.ZERO);
	}

	public record VaporizedCellEnergyTrial(double gasEnergyAfter, PhaseCellTrial thermalTrial) { }

	/** The final liquid heat bath transfers its whole U to gas, followed by one storage rounding. */
	public VaporizedCellEnergyTrial trialVaporizedCellEnergy(PhaseCandidateCell current, double gasEnergyBefore) {
		requirePhaseCandidateCurrent(current);
		if (!current.unchangedSolids().isEmpty() || !Double.isFinite(gasEnergyBefore))
			throw new IllegalArgumentException("Complete vaporization requires an empty final condensed cell");
		SignedDyadic total = current.cellEnergy().add(SignedDyadic.of(gasEnergyBefore));
		double gasEnergyAfter = total.toDouble();
		if (!Double.isFinite(gasEnergyAfter)) throw new ThermalStateFunction.EnergyRepresentationException();
		var trial = new PhaseCellTrial(this, current.revision, current.cell(), null,
				total.subtract(SignedDyadic.of(gasEnergyAfter)));
		return new VaporizedCellEnergyTrial(gasEnergyAfter, trial);
	}

	public PhaseCellEnergyPlan planPhaseCellEnergy(PhaseCellTrial trial) {
		if (trial == null || trial.owner != this || !matchesThermalRevision(trial.revision))
			throw new IllegalStateException("Thermal phase trial is stale or belongs to another domain");
		try { return new PhaseCellEnergyPlan(this, trial.revision, cellOperations.preparePhaseCellEnergy(trial.cell, trial.raw, trial.emptyEnergyRemainder)); }
		catch (ThermalCellEnergyAssignment.UnavailableException unavailable) { throw new PhaseCellEnergyUnavailableException(unavailable); }
	}

	private void requirePhaseCandidateCurrent(PhaseCandidateCell current) {
		if (current == null || current.owner != this || !matchesThermalRevision(current.revision))
			throw new IllegalStateException("Thermal phase cell snapshot is stale or belongs to another domain");
	}

	public void requirePhaseCellEnergyCurrent(PhaseCellEnergyPlan plan) {
		if (plan == null || plan.owner != this || !matchesThermalRevision(plan.revision))
			throw new IllegalStateException("Thermal phase energy plan is stale or belongs to another domain");
	}

	/** Called after the prechecked phase inventory edits, with their exact prepared caloric owner set. */
	public void commitPhaseCellEnergy(PhaseCellEnergyPlan plan, ThermalCarrierContext markers) {
		if (plan == null || plan.owner != this) throw new IllegalStateException("Thermal phase energy plan belongs to another domain");
		cellOperations.commitPhaseCellEnergy(plan.assignment, markers);
	}

	/** All liquid/gas interfaces use one source generation and one final thermal audit. */
	public static final class PhaseBatchEnergyPlan {
		private final ThermalDomainState owner;
		private final Revision revision;
		private final ThermalCellOperations.PhaseBatchAssignment assignment;
		private PhaseBatchEnergyPlan(ThermalDomainState owner, Revision revision, ThermalCellOperations.PhaseBatchAssignment assignment) {
			this.owner = owner; this.revision = revision; this.assignment = assignment;
		}
		/** Energies follow the supplied future liquid inventory order in this cell. */
		public double[] ownerEnergies(int cell) { return liquidEnergies(cell); }
		public double[] liquidEnergies(int cell) {
			for (int index = 0; index < assignment.cells().size(); index++) if (assignment.cells().get(index).cell() == cell) {
				var entries = assignment.cells().get(index).raw().entries();
				double[] result = new double[assignment.liquidCounts()[index]];
				for (int owner = 0; owner < result.length; owner++) result[owner] = entries.get(owner).energy();
				return result;
			}
			throw new IllegalArgumentException("Cell is absent from phase batch");
		}
	}

	public PhaseBatchEnergyPlan planPhaseBatchEnergy(List<PhaseCellTrial> trials, int[] liquidCounts,
	                                               SignedDyadic gasRoundoff, SignedDyadic mechanicalHeat, ThermalCarrierContext markers) {
		return planPhaseBatchEnergy(trials,liquidCounts,gasRoundoff,mechanicalHeat,markers,null);
	}
	/** Environmental mass transfer carries signed reference energy through the shared batch audit. */
	public PhaseBatchEnergyPlan planMassTransferBatchEnergy(List<PhaseCellTrial> trials, int[] liquidCounts,
			SignedDyadic gasRoundoff, SignedDyadic mechanicalHeat, SignedDyadic externalSource,
			SignedDyadic externalSink, ThermalCarrierContext markers) {
		var plan = planPhaseBatchEnergy(trials, liquidCounts, gasRoundoff, mechanicalHeat, markers);
		var a = plan.assignment;
		var after = a.afterAudit().withExternalSourceEnergy(externalSource).withExternalSinkEnergy(externalSink);
		after.expectedInventory();
		var assignment = new ThermalCellOperations.PhaseBatchAssignment(a.cells(), a.liquidCounts(),
				a.beforeAudit(), after, a.coherent(), a.callerPublishesAll(), a.solidEnergies());
		return new PhaseBatchEnergyPlan(this, plan.revision, assignment);
	}
	public PhaseBatchEnergyPlan planCondensedBatchEnergy(List<PhaseCellTrial> trials,int[] liquidCounts,
	                                                   SignedDyadic mechanicalHeat,ThermalCarrierContext markers,
	                                                   List<CoherentPhaseStore.Group> futureCoherent) {
		return planPhaseBatchEnergy(trials,liquidCounts,SignedDyadic.ZERO,mechanicalHeat,markers,futureCoherent);
	}
	private PhaseBatchEnergyPlan planPhaseBatchEnergy(List<PhaseCellTrial> trials,int[] liquidCounts,
	                                                SignedDyadic gasRoundoff,SignedDyadic mechanicalHeat,ThermalCarrierContext markers,
	                                                List<CoherentPhaseStore.Group> futureCoherent) {
		if (trials == null || liquidCounts == null || trials.size() != liquidCounts.length)
			throw new IllegalArgumentException("Phase batch owners differ");
		var revision = thermalRevision();
		var assignments = new java.util.ArrayList<ThermalCellOperations.PhaseCellAssignment>(trials.size());
		for (var trial : trials) {
			if (trial == null || trial.owner != this || !revision.equals(trial.revision))
				throw new IllegalStateException("Phase batch source changed during planning");
			assignments.add(cellOperations.prepareBatchCellEnergy(trial.cell, trial.raw, trial.emptyEnergyRemainder));
		}
		return new PhaseBatchEnergyPlan(this, revision, cellOperations.preparePhaseBatch(assignments, liquidCounts, gasRoundoff, mechanicalHeat, markers,futureCoherent));
	}

	/** Every entry is published by the enclosing Stock/solid transaction; this plan publishes its audit. */
	public PhaseBatchEnergyPlan planOwnedBatchEnergy(List<PhaseCellTrial> trials,ThermalCarrierContext markers,
	                                               SignedDyadic mechanicalHeat) {
		var revision=thermalRevision();
		var assignments=new java.util.ArrayList<ThermalCellOperations.PhaseCellAssignment>();
		int[] counts=new int[trials.size()];
		for(int i=0;i<trials.size();i++) {
			var trial=trials.get(i);
			if(trial.owner!=this||!revision.equals(trial.revision)) throw new IllegalStateException("Owned cell source changed");
			var assignment=cellOperations.prepareBatchCellEnergy(trial.cell,trial.raw,trial.emptyEnergyRemainder);
			assignments.add(assignment);counts[i]=assignment.raw().entries().size();
		}
		return new PhaseBatchEnergyPlan(this,revision,cellOperations.prepareOwnedBatch(assignments,counts,mechanicalHeat));
	}
	public void commitOwnedBatchEnergy(PhaseBatchEnergyPlan plan) {
		if(plan==null||plan.owner!=this||!plan.assignment.callerPublishesAll()) throw new IllegalStateException("Owned batch belongs to another thermal domain");
		cellOperations.commitOwnedBatch(plan.assignment);
	}

	public void requirePhaseBatchEnergyCurrent(PhaseBatchEnergyPlan plan) {
		if (plan == null || plan.owner != this || !matchesThermalRevision(plan.revision))
			throw new IllegalStateException("Phase batch thermal source changed");
	}

	/** Liquid rows already carry liquidEnergies; publish unchanged solid baths and the shared audit once. */
	public void commitPhaseBatchEnergy(PhaseBatchEnergyPlan plan, ThermalCarrierContext markers) {
		if (plan == null || plan.owner != this) throw new IllegalStateException("Phase batch belongs to another thermal domain");
		cellOperations.commitPhaseBatch(plan.assignment, markers);
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


	// ---- display ----

	public ThermalDisplaySnapshot displaySnapshot(ThermalCarrierContext markers, AirNetwork network) {
		return cellOperations.displaySnapshot(markers, network);
	}

	// ---- transport ----

	public ThermalTransport.Stats lastTransport() { return lastTransport; }
	public int maximumTransportSubsteps() { return maximumTransportSubsteps; }

	/** Finite conduction on real carrier contacts supplied by the spatial discretization. */
	public ThermalTransport.Stats stepCarrierConduction(
			ThermalCarrierTransport.Batch batch, List<ThermalCarrierTransport.Contact> contacts, double dtSeconds
	) {
		var result = ThermalCarrierTransport.solve(batch, contacts, dtSeconds, maximumTransportSubsteps);
		var physicalDelta=batch.commit();
		result=new ThermalTransport.Stats(result.substeps(),result.evaluatedContacts(),result.maximumTemperatureChangeK(),
				result.maximumAbsoluteTransferJ(),physicalDelta.subtract(result.reservoirExchange()),result.reservoirExchange());
		var ledger = auditState.ledger().withHeatExchange(result.reservoirExchange()).withRoundoff(result.roundoffResidual());
		auditState.replace(ledger);
		spatialObservationPrepared = false;
		lastTransport = result;
		return result;
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

	public ThermalOwnerSnapshot exportOwnerState() {
		return ThermalOwnerSnapshot.prepared(registry.fingerprint(),insertionTemperatureK,granularState.column().copyActiveEnergy(),
				granularState.column().depositSnapshot().stream().mapToDouble(GranularEnergyColumn.DepositEnergy::internalEnergy).toArray(),
				gasState.nodeEnergies(),granularState.coherentPhases().snapshot(),auditState.ledger());
	}
	public void restoreOwnerState(ThermalOwnerSnapshot snapshot, ThermalCarrierContext owners, AirNetwork network) {
		if (!snapshot.registryFingerprint().equals(registry.fingerprint()) || snapshot.insertionTemperatureK() != insertionTemperatureK)
			throw new IllegalArgumentException("Thermal owner registry or insertion state differs");
		if (!snapshot.prepared()) for (var group : snapshot.coherentGroups()) {
			group.requireCompositionMass(owners.chemicalRegistry());
			int material = registry.materialHandle(group.phaseId());
			if (registry.material(material).carrierKind() != ThermalMaterialDefinition.CarrierKind.COHERENT_SOLID)
				throw new IllegalArgumentException("Restored coherent owner material differs");
			stateFunction.stateForInternalEnergy(material,group.mass(),
					group.composition() == null ? java.util.Map.of() : group.composition().stableAmounts(),group.internalEnergy());
		}
		restoreThermalCoherent(snapshot.coherentGroups());
		double[] deposit = snapshot.copyDepositEnergy(); var keys = owners.depositKeys();
		if (deposit.length != keys.size()) throw new IllegalArgumentException("Thermal deposit identities differ");
		var entries = new java.util.ArrayList<GranularEnergyColumn.DepositEnergy>();
		for (int i = 0; i < deposit.length; i++) entries.add(new GranularEnergyColumn.DepositEnergy(keys.get(i),deposit[i]));
		if (snapshot.prepared()) {
			restoreThermalGranular(snapshot.copyActiveParcelEnergy(),entries);
			gasState.replaceNodeEnergies(snapshot.copyGasNodeEnergy());
		} else {
			restoreThermalGranular(snapshot.copyActiveParcelEnergy(),entries,owners);
			restoreGasEnergies(network,snapshot.copyGasNodeEnergy());
		}
		restoreAuditLedger(snapshot.audit());
		if (!snapshot.prepared() && !exactEnergy(owners.markerCount()).equals(snapshot.audit().expectedInventory()))
			throw new IllegalArgumentException("Thermal restored owners do not close the shared inventory");
	}

}

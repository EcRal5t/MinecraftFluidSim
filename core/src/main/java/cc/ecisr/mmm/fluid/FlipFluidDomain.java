package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.thermal.FiniteDeviceExchangeCoordinator;
import cc.ecisr.mmm.thermal.GasEnergyStore;
import cc.ecisr.mmm.thermal.GranularEnergyState;
import cc.ecisr.mmm.thermal.ThermalDomainState;
import cc.ecisr.mmm.thermal.LiquidEnergyColumn;
import cc.ecisr.mmm.thermal.ThermalCarrierContext;

import cc.ecisr.mmm.chemistry.ChemicalMixtureRecord;
import cc.ecisr.mmm.chemistry.ChemicalParcelStore;
import cc.ecisr.mmm.chemistry.ChemicalDisplaySnapshot;
import cc.ecisr.mmm.chemistry.ChemicalRegistry;
import cc.ecisr.mmm.chemistry.ChemicalTransferLedger;
import cc.ecisr.mmm.chemistry.ChemicalLimits;
import cc.ecisr.mmm.chemistry.ExactRational;
import cc.ecisr.mmm.fluid.air.AirEdge;
import cc.ecisr.mmm.fluid.air.AirNetwork;
import cc.ecisr.mmm.fluid.air.GasAuditLedger;
import cc.ecisr.mmm.fluid.air.GasComponentAmount;
import cc.ecisr.mmm.fluid.air.GasInventory;
import cc.ecisr.mmm.fluid.air.GasRegistry;
import cc.ecisr.mmm.fluid.air.GasStateSnapshot;
import cc.ecisr.mmm.granular.GranularCarrier;
import cc.ecisr.mmm.granular.GranularAuditLedger;
import cc.ecisr.mmm.granular.GranularCouplingFields;
import cc.ecisr.mmm.granular.GranularDepositDynamics;
import cc.ecisr.mmm.granular.GranularDepositStore;
import cc.ecisr.mmm.granular.GranularDisplaySnapshot;
import cc.ecisr.mmm.granular.GranularInventory;
import cc.ecisr.mmm.granular.GranularLimits;
import cc.ecisr.mmm.granular.GranularMechanics;
import cc.ecisr.mmm.granular.GranularParcelStore;
import cc.ecisr.mmm.granular.GranularPhaseTransferEngine;
import cc.ecisr.mmm.granular.GranularRegistry;
import cc.ecisr.mmm.granular.GranularStateSnapshot;
import cc.ecisr.mmm.granular.GranularTransferLedger;
import cc.ecisr.mmm.granular.SolidCompositionPool;
import cc.ecisr.mmm.granular.SolidCompositionRecord;
import cc.ecisr.mmm.granular.SolidLiquidReaction;
import cc.ecisr.mmm.granular.SolidVolume;
import cc.ecisr.mmm.units.SignedDyadic;
import cc.ecisr.mmm.thermal.CoherentPhaseStore;
import cc.ecisr.mmm.units.SolverUnitScale;
import cc.ecisr.mmm.thermal.GranularEnergyColumn;
import cc.ecisr.mmm.thermal.GasLiquidPhaseModel;
import cc.ecisr.mmm.thermal.FiniteThermalExchange;
import cc.ecisr.mmm.thermal.LiquidThermalExpansion;
import cc.ecisr.mmm.thermal.ThermalAuditLedger;
import cc.ecisr.mmm.thermal.ThermalCellGrid;
import cc.ecisr.mmm.thermal.ThermalMaterialDefinition;
import cc.ecisr.mmm.thermal.ThermalLimits;
import cc.ecisr.mmm.thermal.ThermalPhaseTransition;
import cc.ecisr.mmm.thermal.ThermalRegistry;
import cc.ecisr.mmm.thermal.ThermalStateFunction;
import cc.ecisr.mmm.thermal.ThermalDisplaySnapshot;
import cc.ecisr.mmm.thermal.ThermalTransport;
import cc.ecisr.mmm.world.MovingBodyFluidCoupling;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.math.BigInteger;

/**
 * A bounded three-dimensional PIC/FLIP liquid domain.
 *
 * <p>The update order mirrors the user's Godot compute implementation: P2G,
 * grid forces, pressure projection, G2P, then particle advection with solid
 * collision. Eight marker particles represent one full grid cell. Particles
	 * carry exact volume and mass inventories, so liquid cannot diffuse into a low-density VOF haze.</p>
 */
public final class FlipFluidDomain {
	private static final int PARTICLES_PER_FULL_CELL = 8;
	static final float PARTICLE_VOLUME = 1.0F / PARTICLES_PER_FULL_CELL;
	private static final float FLIP_RATIO = 0.90F;
	private static final int DEFAULT_MAX_PARTICLES = 262_144;
	private static final int NO_PHASE = Integer.MIN_VALUE;
	private static final int INDICATOR_AIM_SNAP_CELLS = 2;

	final int sizeX;
	final int sizeY;
	final int sizeZ;
	final int cellCount;
	final FluidSolverConfig config;
	final SolverUnitScale unitScale;
	final int maximumParticleCount;
	final DomainGeometry geometry;
	final MarkerGridIndex markerGrid;
	final MacVelocityGrid mac;
	final PressureProjectionWorkspace projection;
	final SurfaceTensionWorkspace tension;
	private final CondensedInventoryClearPlanner clearPlanner;
	private final MaterialTransferPlanner transferPlanner;
	private final GranularTransferPlanner granularTransferPlanner;
	private final DomainInventoryLifecyclePlanner lifecyclePlanner;
	final GasTopologyCoordinator gasTopologyCoordinator;
	private final GasLifecyclePlanner gasLifecyclePlanner;
	private final PressureBoundaryView pressureBoundaryView;
	private final PressureSystem pressureSystem;
	private final PressureProjectionStage pressureStage;
	private final GranularMechanicalStage granularMechanicalStage;
	private final BodyForceStage bodyForceStage;
	private final GridToParticleStage gridToParticleStage;
	private final ParticleAdvectionStage particleAdvectionStage;
	private final ParticleToGridStage particleToGridStage;
	private final MechanicalStepPipeline mechanicalPipeline;
	private final ThermalTransportStage thermalTransportStage;
	private final CondensedPhaseChangeStage condensedPhaseChangeStage;
	private final GasLiquidPhaseChangeStage gasLiquidPhaseChangeStage;
	private final ThermochemistryPipeline thermochemistryPipeline;
	final FlipPressurePhaseGeometry pressurePhaseGeometry;
	final FlipFreeSurfaceGeometry freeSurfaceGeometry;
	private final FlipParticleReseeder particleReseeder;
	final FlipParticleCollision particleCollision;
	final FlipVelocityExtrapolator velocityExtrapolator;
	final float[] volume;
	boolean surfaceTensionActive;
	boolean liquidPairTensionActive;
	private static final int[][] SURFACE_TENSION_DIRECTIONS = {{-1,0,0}, {1,0,0}, {0,-1,0}, {0,1,0}, {0,0,-1}, {0,0,1}};

	/** Frozen P2G face mass/volume weights; extrapolation reuses the live weight arrays as scratch. */


	final LiquidParticleStore particles;
	private long carrierMutationRevision;
	private final CarrierMutationCoordinator coordinator;
	FluidMaterialRegistry materialRegistry;
	ChemicalParcelStore chemicalParcels;
	int[] chemicalPreviousCells;
	int[] chemicalMovingMarkers;
	final GasDomainState gasState = new GasDomainState();
	private final GasPressureBoundary gasBoundary = new GasPressureBoundary(this);
	private CollisionEditPlan activeCollisionPlan;
	private int activeCollisionEditIndex;
	GranularDomainState granularState;
	private GranularPhaseTransferEngine granularPhaseTransfer;
	private boolean precipitationEnabled;
	private PhaseTransferStats lastPhaseTransferStats = new PhaseTransferStats(0, 0, 0, 0L);
	private ThermalPhaseStats lastThermalPhaseStats = new ThermalPhaseStats(0, 0, 0, 0L);
	private GasThermalPhaseStats lastGasThermalPhaseStats = new GasThermalPhaseStats(0, 0, 0);
	private ChemicalParcelStore.ReactionStats lastChemicalReactionStats =
			new ChemicalParcelStore.ReactionStats(0, 0, 0);
	private GranularDepositDynamics granularDepositDynamics;
	private GranularDepositDynamics.Stats lastGranularDepositStats =
			new GranularDepositDynamics.Stats(0, 0, 0, 0, 0);
	private double lastGranularBoundaryImpulseX, lastGranularBoundaryImpulseY, lastGranularBoundaryImpulseZ;
	private int[] granularReactivePreviousCells;
	GranularCouplingFields granularCoupling;
	GranularCouplingFields granularNextCoupling;
	GranularCouplingFields pressureGranularCoupling;
	FlipGranularCollision granularCollision;
	int[] granularCarrierMaterialByCell;
	double[] granularCarrierVolumeByCell;
	boolean gridDirty = true;
	int pressureAnchor = -1;
	float pressureReference;
	int lastPressureIterations;
	float lastPressureResidual;
	boolean variableDensityActive;
	FlipThermalState thermalState;
	private final ThermalCarrierContext thermalMarkers;
	double[] thermalBuoyancySum;
	double[] thermalBuoyancyVolume;
	/** Runtime world-derived fixed-reservoir temperatures; NaN is the canonical adiabatic face. */
	private double[] thermalBoundaryTemperatureK;
	final SignedDyadic[] pendingPhaseCellVolume;
	private final DomainMetrics metrics;
	private final CellInventoryReader cellInventory;
	private final CarrierInventoryReader carriers;

	public FlipFluidDomain(int sizeX, int sizeY, int sizeZ, FluidSolverConfig config) {
		this(sizeX, sizeY, sizeZ, config, DEFAULT_MAX_PARTICLES);
	}

	public FlipFluidDomain(int sizeX, int sizeY, int sizeZ, FluidSolverConfig config, int maximumParticleCount) {
		if (sizeX < 2 || sizeY < 2 || sizeZ < 2) throw new IllegalArgumentException("All dimensions must be at least two cells");
		if (maximumParticleCount <= 0) throw new IllegalArgumentException("Maximum particle count must be positive");
		this.sizeX = sizeX;
		this.sizeY = sizeY;
		this.sizeZ = sizeZ;
		this.cellCount = Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ);
		this.config = config;
		this.unitScale = SolverUnitScale.forCellSize(config.cellSize());
		this.maximumParticleCount = maximumParticleCount;
		this.particles = new LiquidParticleStore(maximumParticleCount);
		int initialParticleCapacity = Math.min(16_384, maximumParticleCount);
		this.geometry = new DomainGeometry(sizeX, sizeY, sizeZ, cellCount);
		this.markerGrid = new MarkerGridIndex(cellCount, initialParticleCapacity);
		this.pressurePhaseGeometry = new FlipPressurePhaseGeometry(sizeX, sizeY, sizeZ);
		this.freeSurfaceGeometry = new FlipFreeSurfaceGeometry(sizeX, sizeY, sizeZ);
		this.particleReseeder = new FlipParticleReseeder(sizeX, sizeY, sizeZ, config.cellSize(), maximumParticleCount);
		this.particleCollision = new FlipParticleCollision(sizeX, sizeY, sizeZ, config.cellSize(), geometry.solid, geometry.cutCells);
		this.velocityExtrapolator = new FlipVelocityExtrapolator(sizeX, sizeY, sizeZ, geometry.cutCells);
		this.volume = new float[cellCount];
		this.mac = new MacVelocityGrid(sizeX, sizeY, sizeZ);
		this.projection = new PressureProjectionWorkspace(cellCount);
		this.tension = new SurfaceTensionWorkspace(cellCount);
		this.pendingPhaseCellVolume = new SignedDyadic[cellCount];
		Arrays.fill(this.pendingPhaseCellVolume, SignedDyadic.ZERO);
		this.thermalMarkers = createThermalMarkerContext();
		this.metrics = new DomainMetrics(this);
		this.cellInventory = new CellInventoryReader(this);
		this.carriers = new CarrierInventoryReader(this);
		this.coordinator = new CarrierMutationCoordinator(this);
		this.transferPlanner = new MaterialTransferPlanner(this);
		this.clearPlanner = new CondensedInventoryClearPlanner(this);
		this.granularTransferPlanner = new GranularTransferPlanner(this);
		this.lifecyclePlanner = new DomainInventoryLifecyclePlanner(this);
		this.gasTopologyCoordinator = new GasTopologyCoordinator(this);
		this.gasLifecyclePlanner = new GasLifecyclePlanner(this);
		this.pressureBoundaryView = new PressureBoundaryView(this);
		this.pressureSystem = new PressureSystem(this);
		MechanicalStageAssembly.Stages stages = MechanicalStageAssembly.create(
				this, pressureSystem, pressureBoundaryView);
		this.pressureStage = stages.pressure();
		this.granularMechanicalStage = stages.granular();
		this.bodyForceStage = stages.bodyForce();
		this.gridToParticleStage = stages.gridToParticle();
		this.particleAdvectionStage = stages.advection();
		this.particleToGridStage = stages.particleToGrid();
		this.mechanicalPipeline = stages.pipeline();
		ThermochemistryStageAssembly.Stages thermalStages = ThermochemistryStageAssembly.create(this);
		this.thermalTransportStage = thermalStages.transport();
		this.condensedPhaseChangeStage = thermalStages.condensedPhase();
		this.gasLiquidPhaseChangeStage = thermalStages.gasPhase();
		this.thermochemistryPipeline = thermalStages.pipeline();
	}

	public int sizeX() { return sizeX; }
	public int sizeY() { return sizeY; }
	public int sizeZ() { return sizeZ; }
	public int particleCount() { return particles.count(); }

	/** Read-only gas view for the pressure stage and adapter reads; no mutable network escapes. */
	public GasPressureBoundary gasBoundary() { return gasBoundary; }

	/** Demo/setup tuning entry: the network books the ambient source or sink for the change itself. */
	public void setGasNodeStandardVolume(int nodeId, double standardVolume) {
		gasState.network.setStandardVolume(nodeId, standardVolume);
	}

	/**
	 * Marks the solver extent as the interior of an independently validated closed controller shell.
	 * Command/developer domains retain an ambient external boundary. The mode is immutable after gas
	 * topology exists because changing it would reinterpret authoritative gas inventory.
	 */
	public void sealDomainBoundary() {
		if (gasState.cellNodeId != null || gasState.network.nodeCount() != 0 || gasState.network.drawnFromAmbientNanos() != 0L) {
			throw new IllegalStateException("Gas domain boundary cannot change after topology initialization");
		}
		gasState.sealedDomainBoundary = true;
	}

	/** Explicitly selects the authoritative gas registry before a first topology bootstrap. */
	public void bindGasRegistry(GasRegistry registry) {
		if (registry == null) throw new IllegalArgumentException("Gas registry is missing");
		if (gasState.cellNodeId != null || gasState.network.nodeCount() != 0 || gasState.network.drawnFromAmbientNanos() != 0L) {
			throw new IllegalStateException("Gas registry cannot change after topology initialization");
		}
		gasState.network = new AirNetwork(registry);
	}

	/** Initializes gas topology from current carrier geometry and books sealed ambient source once. */
	public GasStateSnapshot bootstrapGasState(String chemicalRegistryFingerprint) {
		return gasTopologyCoordinator.bootstrap(chemicalRegistryFingerprint);
	}

	public GasStateSnapshot exportGasState(String chemicalRegistryFingerprint) {
		return gasTopologyCoordinator.export(chemicalRegistryFingerprint);
	}

	/** Restores complete gas truth after its matching liquid/material state. */
	public void restoreGasState(GasStateSnapshot state, GasRegistry registry) {
		gasTopologyCoordinator.restore(state, registry);
	}

	/** True when the current grid contains at least one sealed gas cavity with finite inventory. */
	public boolean gasPressureActive() { return gasState.pressureActive; }
	public boolean operatorVentedGasTopology() { return gasState.operatorVentedTopology; }
	public double gasNodeTemperatureK(int nodeId) {
		return thermalState == null || !thermalState.gasModelBound()
				? GasRegistry.STANDARD_TEMPERATURE_K : thermalState.gasTemperature(nodeId, gasState.network);
	}
	public double gasNodeGaugePressurePa(int nodeId) {
		return gasBoundary.nodeGaugePressurePa(nodeId);
	}

	/**
	 * Gas node owning the air cell at a grid position: 0 ambient, at least 1 sealed, -1 when the
	 * cell is not air or no scan has run yet.
	 */
	public int airNodeAt(int x, int y, int z) {
		if (gasState.cellNodeId == null) return -1;
		return gasState.cellNodeId[cellIndexChecked(x, y, z)];
	}

	/**
	 * Read-only gas node mask for debug snapshots: one unsigned byte per cell holding the sealed
	 * cavity id (clamped to 255; 0 for ambient air, non-air cells and before the first scan).
	 * Display data only; it never feeds the solver.
	 */
	public byte[] copyGasNodeMask() {
		byte[] mask = new byte[cellCount];
		if (gasState.cellNodeId == null) return mask;
		for (int cell = 0; cell < cellCount; cell++) {
			int node = gasState.cellNodeId[cell];
			mask[cell] = node >= 1 ? (byte) Math.min(255, node) : 0;
		}
		return mask;
	}
	public int maximumParticleCount() { return maximumParticleCount; }
	public int markerCellCount() { return metrics.markerCellCount(); }
	public FluidSolverConfig config() { return config; }
	public double physicalMassKilograms(double solverMass) { return unitScale.kilograms(solverMass); }
	public double solverMassForKilograms(double kilograms) { return unitScale.solverMass(kilograms); }
	public double physicalVolumeCubicMetres(double normalizedVolume) { return unitScale.cubicMeters(normalizedVolume); }
	public boolean hasMaterialState() { return materialRegistry != null; }
	public boolean hasChemistryState() { return chemicalParcels != null; }
	public boolean hasGranularState() { return granularState != null; }
	public boolean hasThermalState() { return thermalState != null; }
	public boolean hasAmbientGasCells() { return metrics.hasAmbientGasCells(); }
	/** Last committed liquid/solid LTE range; constant-time and absent until the thermal grid was observed. */
	public ThermalCellGrid.TemperatureRange observedCarrierTemperatureRange() {
		return thermalState == null ? null : thermalState.observedCarrierTemperatureRange();
	}
	/** Constant-time live chemical-parcel count; does not scan markers. */
	public int chemicalParcelCount() { return chemicalParcels == null ? 0 : chemicalParcels.pool().liveCount(); }
	/** Constant-time exact species inventory ledger. */
	public long totalChemicalSpeciesQuanta() { return chemicalParcels == null ? 0L : chemicalParcels.totalQuanta(); }
	/** Constant-time sparse deposit-group count. */
	public int granularDepositGroupCount() { return granularState == null ? 0 : granularState.deposits.groupCount(); }
	/** Constant-time coherent phase-group count. */
	public int coherentPhaseGroupCount() {
		return thermalState == null ? 0 : thermalState.coherentPhases().groupCount();
	}
	public boolean hasPendingPhaseVolumeWork() { return metrics.hasPendingPhaseVolumeWork(); }
	/** Derived local mass/volume density for observation and acceptance; dry cells return NaN. */
	public double densityInCell(int x, int y, int z) { return metrics.densityInCell(x, y, z); }

	/**
	 * Scans authoritative marker truth without rebuilding grids or changing activity. Empty cells return null.
	 * This is the core-side source for loader query adapters.
	 */
	public FluidCellInventoryView observeCellInventory(int x, int y, int z) { return cellInventory.observeCellInventory(x, y, z); }

	/** Scans authoritative solid carriers and the already committed gas topology without rebuilding either. */
	public CellCarrierInventoryView observeCellCarriers(int x, int y, int z) { return cellInventory.observeCellCarriers(x, y, z); }

	/** Bounded-ray predicate over authoritative condensed carriers; does not build grids or display payloads. */
	public boolean hasCondensedInventoryInCell(int x, int y, int z) { return cellInventory.hasCondensedInventoryInCell(x, y, z); }

	public PendingPhaseVolumeState exportPendingPhaseVolumeState() {
		if (thermalState == null) throw new IllegalStateException("Pending phase volume requires thermal state");
		return PendingPhaseVolumeState.capture(pendingPhaseCellVolume);
	}

	/**
	 * Captures every authoritative inventory needed to evaluate a generation off-owner.
	 * An initialized gas owner is first reconciled to the current condensed carrier geometry so
	 * a direct world/actor edit cannot publish stale topology beside new markers.
	 */
	public FluidDomainState captureAuthoritativeState() {
		if (activeCollisionPlan != null) {
			throw new IllegalStateException("Cannot capture while a collision transaction is active");
		}
		if (gasState.cellNodeId != null) gasTopologyCoordinator.synchronizeForPersistence();
		FlipParticleState pure = null;
		FlipMaterialParticleState materials = null;
		FlipChemicalParticleState chemicals = null;
		ChemicalRegistry chemistry = null;
		if (chemicalParcels != null) {
			chemicals = exportChemicalParticleState();
			chemistry = chemicalParcels.registry();
		} else if (materialRegistry != null) {
			materials = exportMaterialParticleState();
		} else {
			pure = exportParticleState();
		}
		GranularStateSnapshot granular = granularState == null ? null : exportGranularState();
		String chemicalFingerprint = chemistry == null ? "" : chemistry.fingerprint();
		GasStateSnapshot gas = gasState.cellNodeId == null ? null : exportGasState(chemicalFingerprint);
		FlipThermalParticleState thermal = thermalState == null ? null : exportThermalParticleState();
		return new FluidDomainState(config, maximumParticleCount, gasState.sealedDomainBoundary,
				pure, materials, chemicals, chemistry, granularState == null ? null : granularState.registry,
			granular, precipitationEnabled,
				gasState.network.registry(), gas, thermalState == null ? null : thermalState.registry(), thermal,
				thermalState == null ? 0 : thermalState.maximumTransportSubsteps(),
				thermalState == null ? null : exportPendingPhaseVolumeState());
	}

	/** Restores a private solver instance without consulting Minecraft/Fabric state. */
	public static FlipFluidDomain restoreAuthoritativeState(FluidDomainState state) {
		if (state == null) throw new IllegalArgumentException("Authoritative state is missing");
		FlipFluidDomain domain = new FlipFluidDomain(state.sizeX(), state.sizeY(), state.sizeZ(),
				state.config(), state.maximumParticleCount());
		if (state.sealedDomainBoundary()) domain.sealDomainBoundary();
		switch (state.carrierKind()) {
			case PURE -> domain.restoreParticleState(state.particles());
			case MATERIAL -> domain.restoreMaterialParticleState(state.materials());
			case CHEMICAL -> domain.restoreChemicalParticleState(state.chemicals(), state.chemicalRegistry());
		}
		if (state.granular() != null) {
			domain.restoreGranularState(state.granular(), state.granularRegistry(), domain.materialRegistry());
		}
		if (state.gas() == null) domain.bindGasRegistry(state.gasRegistry());
		else domain.restoreGasState(state.gas(), state.gasRegistry());
		if (state.thermal() != null) {
			domain.restoreThermalParticleState(state.thermal(), state.thermalRegistry(),
					state.maximumThermalTransportSubsteps());
			domain.restorePendingPhaseVolumeState(state.pendingPhaseVolume());
		}
		if (state.precipitationEnabled()) domain.setPrecipitationEnabled(true);
		return domain;
	}
	public void restorePendingPhaseVolumeState(PendingPhaseVolumeState state) {
		if (thermalState == null || state == null || state.cellCount() != cellCount
				|| hasPendingPhaseVolumeWork()) {
			throw new IllegalArgumentException("Pending phase-volume restore is incompatible");
		}
		SignedDyadic[] restored = state.toDense();
		thermalState.requirePendingPhaseCells(restored, thermalMarkerContext());
		System.arraycopy(restored, 0, pendingPhaseCellVolume, 0, cellCount);
	}
	public ThermalTransport.Stats lastThermalTransportStats() {
		return thermalState == null
				? new ThermalTransport.Stats(0, 0, 0.0, SignedDyadic.ZERO, SignedDyadic.ZERO)
				: thermalState.lastTransport();
	}
	public ThermalAuditLedger thermalAudit() {
		if (thermalState == null) throw new IllegalStateException("Thermal state is not bound");
		return thermalState.audit();
	}
	public void settleExternalContainerExtraction(double removedCarrierEnergy, double coupledItemEnergy) {
		if (thermalState == null) throw new IllegalStateException("Thermal state is not bound");
		thermalState.settleExternalExtraction(removedCarrierEnergy, coupledItemEnergy);
	}
	public double totalInternalEnergy() {
		return thermalState == null ? 0.0 : thermalState.totalEnergy(particles.count());
	}
	public SignedDyadic exactInternalEnergy() {
		return thermalState == null ? SignedDyadic.ZERO : thermalState.exactEnergy(particles.count());
	}
	public SignedDyadic exactGasInternalEnergy() {
		return thermalState == null ? SignedDyadic.ZERO : thermalState.exactGasEnergy();
	}
	public int granularParcelCount() { return granularState == null ? 0 : granularState.parcels.count(); }
	public int granularDepositCellCount() { return granularState == null ? 0 : granularState.deposits.occupiedCellCount(); }
	public int granularSleepingColumnCount() {
		return granularDepositDynamics == null ? 0 : granularDepositDynamics.sleepingColumnCount();
	}
	public boolean granularReadyForHydrostaticSleep() {
		if (granularState == null) return true;
		if (granularState.parcels.count() != 0 || granularDepositDynamics.dirtyColumnCount() != 0) return false;
		return !precipitationEnabled || chemicalParcels == null || !chemicalParcels.hasDirtyCells();
	}
	public GranularDepositDynamics.Stats lastGranularDepositStats() { return lastGranularDepositStats; }
	public double lastGranularBoundaryImpulseX() { return lastGranularBoundaryImpulseX; }
	public double lastGranularBoundaryImpulseY() { return lastGranularBoundaryImpulseY; }
	public double lastGranularBoundaryImpulseZ() { return lastGranularBoundaryImpulseZ; }
	public int lastGranularCapacityHeld() {
		return granularCollision == null ? 0 : granularCollision.lastCapacityHeld();
	}
	public GranularStateSnapshot exportGranularState() { return carriers.exportGranularState(); }
	public GranularDisplaySnapshot copyGranularDisplaySnapshot() { return carriers.copyGranularDisplaySnapshot(); }
	public GranularAuditLedger granularExternalSource() { return granularState.externalSource; }
	public GranularAuditLedger granularEscapedSink() { return granularState.escapedSink; }
	public long totalGranularSolidVolumeSvq() { return granularState == null ? 0L : granularState.inventory.totalSolidVolumeSvq(); }
	public double totalGranularMass() { return granularState == null ? 0.0 : granularState.inventory.totalMass(); }
	boolean hasGranularInventory() {
		return granularState != null && (granularState.parcels.count() != 0 || granularState.deposits.groupCount() != 0);
	}

	/** Erases all granular truth as one auditable external sink and releases every owned composition. */
	public GranularClear removeAllGranularExact() {
		return commitRemoveAllGranularExact(planRemoveAllGranularExact());
	}

	/** Completes every fallible granular-clear check without changing inventory or audit truth. */
	public GranularClearPlan planRemoveAllGranularExact() {
		return lifecyclePlanner.planGranularClear();
	}

	/** Commits a previously validated granular clear; no new ledger candidate is constructed here. */
	public GranularClear commitRemoveAllGranularExact(GranularClearPlan plan) {
		preflightGranularClear(plan);
		return applyGranularClear(plan);
	}

	/** Publishes an already preflighted granular clear; the mutation phase has no rejections. */
	private GranularClear applyGranularClear(GranularClearPlan plan) {
		if (granularState == null) return new GranularClear(0L, 0.0, 0, 0);
		if (!hasGranularInventory()) {
			granularDepositDynamics.clear(); granularCoupling.clear(); granularNextCoupling.clear();
			pressureGranularCoupling = null;
			return new GranularClear(0L, 0.0, 0, 0);
		}
		GranularInventory.ClearStats cleared = granularState.inventory.clear();
		granularState.escapedSink = plan.sinkAfter(); granularDepositDynamics.clear();
		granularCoupling.clear(); granularNextCoupling.clear(); pressureGranularCoupling = null;
		lastGranularDepositStats = new GranularDepositDynamics.Stats(0, 0, 0, 0, 0);
		gridDirty = true;
		return new GranularClear(cleared.solidVolumeSvq(), cleared.mass(),
				cleared.activeParcels(), cleared.depositGroups());
	}

	/**
	 * Reruns every fallible granular-clear check against live truth without mutating anything,
	 * including the removed-ledger totals: {@link GranularInventory#clear} captures its stats
	 * before erasing, so validating the ledger against the plan totals here is bit-for-bit the
	 * check the commit used to run after the inventory was already cleared.
	 */
	private void preflightGranularClear(GranularClearPlan plan) {
		if (plan == null) throw new IllegalArgumentException("Granular clear plan is missing");
		if ((granularState == null ? 0L : granularState.mutationRevision()) != plan.granularRevision()) {
			throw new IllegalStateException("Granular inventory changed after clear planning");
		}
		if (granularState == null) {
			if (plan.solidVolumeSvq() != 0L || plan.activeParcels() != 0 || plan.depositGroups() != 0) {
				throw new IllegalStateException("Granular inventory changed after clear planning");
			}
			return;
		}
		if (!hasGranularInventory()) {
			if (plan.solidVolumeSvq() != 0L || plan.activeParcels() != 0 || plan.depositGroups() != 0) {
				throw new IllegalStateException("Granular inventory changed after clear planning");
			}
			return;
		}
		granularState.inventory.requireCanClear();
		if (granularState.inventory.totalSolidVolumeSvq() != plan.solidVolumeSvq()
				|| Double.doubleToRawLongBits(granularState.inventory.totalMass()) != plan.totalMassBits()
				|| !(thermalState == null ? SignedDyadic.ZERO : thermalState.granularEnergy().exactEnergy())
				.equals(plan.internalEnergy())
				|| granularState.parcels.count() != plan.activeParcels()
				|| granularState.deposits.groupCount() != plan.depositGroups()) {
			throw new IllegalStateException("Granular inventory changed after clear planning");
		}
		long auditSvq = 0L; double auditMass = 0.0;
		for (GranularAuditLedger.Entry entry : plan.removed().entries()) {
			auditSvq = Math.addExact(auditSvq, entry.solidVolumeSvq()); auditMass += entry.mass();
		}
		if (auditSvq != plan.solidVolumeSvq() || !massClose(auditMass, Double.longBitsToDouble(plan.totalMassBits()))) {
			throw new IllegalStateException("Granular clear audit differs from removed inventory");
		}
	}

	public record GranularClear(long solidVolumeSvq, double mass, int activeParcels, int depositGroups) { }
	public record GranularClearPlan(
			GranularAuditLedger removed, GranularAuditLedger sinkAfter,
			long solidVolumeSvq, long totalMassBits, int activeParcels, int depositGroups,
			SignedDyadic internalEnergy, long granularRevision
	) { }
	/** Read-only cell inventory used to aim the dry-container scoop at particle/deposit truth. */
	public long granularSolidVolumeSvq(int cellX, int cellY, int cellZ) { return cellInventory.granularSolidVolumeSvq(cellX, cellY, cellZ); }
	public SolidCompositionPool solidCompositions() {
		if (granularState == null || granularState.compositions == null) {
			throw new IllegalStateException("Solid composition state is not bound");
		}
		return granularState.compositions;
	}
	public boolean precipitationEnabled() { return precipitationEnabled; }
	public PhaseTransferStats lastPhaseTransferStats() { return lastPhaseTransferStats; }
	public ThermalPhaseStats lastThermalPhaseStats() { return lastThermalPhaseStats; }
	public GasThermalPhaseStats lastGasThermalPhaseStats() { return lastGasThermalPhaseStats; }
	public ChemicalParcelStore.ReactionStats lastChemicalReactionStats() { return lastChemicalReactionStats; }
	public void setPrecipitationEnabled(boolean enabled) {
		if (enabled && (chemicalParcels == null || granularState == null || granularState.compositions == null
				|| !granularState.registry.hasReactiveRules())) {
			throw new IllegalStateException("Precipitation requires bound chemistry, granular state and reactive rules");
		}
		precipitationEnabled = enabled;
	}

	/** Binds the runtime granular registry without changing liquid or chemical carrier truth. */
	public void bindGranular(GranularRegistry registry, FluidMaterialRegistry liquidRegistry) {
		if (registry == null || liquidRegistry == null) throw new IllegalArgumentException("Granular/liquid registry is missing");
		if (thermalState != null) {
			throw new IllegalStateException("Granular state must be bound before its joint thermal inventory");
		}
		if (Float.floatToRawIntBits(config.cellSize()) != Float.floatToRawIntBits(0.5F)) {
			throw new IllegalArgumentException("Granular SVQ requires a 0.5 m solver cell");
		}
		bindMaterialRegistry(liquidRegistry);
		if (granularState != null) {
			if (!granularState.registry.fingerprint().equals(registry.fingerprint())) {
				throw new IllegalArgumentException("A domain cannot change its granular registry");
			}
			return;
		}
		for (FluidMaterial material : liquidRegistry.materials()) {
			if (!registry.hasCarrier(material.id())) {
				throw new IllegalArgumentException("No granular carrier definition for liquid " + material.id());
			}
		}
		if (chemicalParcels != null && registry.hasReactiveRules()
				&& !registry.chemicalRegistryFingerprint().equals(chemicalParcels.registry().fingerprint())) {
			throw new IllegalArgumentException("Granular registry chemical dependency mismatch");
		}
		granularState = new GranularDomainState(registry, cellCount);
		granularDepositDynamics = new GranularDepositDynamics(registry, granularState.parcels, granularState.deposits,
				granularState.inventory, sizeX, sizeY, sizeZ, config.cellSize());
		granularCoupling = new GranularCouplingFields(sizeX, sizeY, sizeZ, config.cellSize());
		granularNextCoupling = new GranularCouplingFields(sizeX, sizeY, sizeZ, config.cellSize());
		granularCollision = new FlipGranularCollision(sizeX, sizeY, sizeZ, config.cellSize(), geometry.cutCells, granularState.deposits,
				GranularLimits.MAX_ACTIVE_PARCELS_PER_REGION);
		granularCarrierMaterialByCell = new int[cellCount];
		granularCarrierVolumeByCell = new double[cellCount];
		granularReactivePreviousCells = new int[GranularLimits.MAX_ACTIVE_PARCELS_PER_REGION];
		bindSolidCompositionPoolIfReady();
		gridDirty = true;
	}

	public void restoreGranularState(
			GranularStateSnapshot snapshot, GranularRegistry registry, FluidMaterialRegistry liquidRegistry
	) {
		if (snapshot == null) throw new IllegalArgumentException("Granular snapshot is missing");
		if (snapshot.sizeX() != sizeX || snapshot.sizeY() != sizeY || snapshot.sizeZ() != sizeZ) {
			throw new IllegalArgumentException("Granular snapshot dimensions changed");
		}
		if (granularState != null) throw new IllegalStateException("Granular state is already bound");
		if (materialRegistry != null) {
			if (!materialRegistry.materials().equals(liquidRegistry.materials())) {
				throw new IllegalArgumentException("Granular snapshot liquid palette changed");
			}
			liquidRegistry = materialRegistry;
		}
		bindGranular(registry, liquidRegistry);
		snapshot.restoreInto(registry, granularState.parcels, granularState.deposits, granularState.compositions,
				cell -> SolidVolume.fromCellVolumeExact(geometry.cutCells.openCellVolume(cell)));
		snapshot.externalSource().validateAgainst(registry, granularState.compositions == null ? null : granularState.compositions.registry());
		snapshot.escapedSink().validateAgainst(registry, granularState.compositions == null ? null : granularState.compositions.registry());
		granularState.externalSource = snapshot.externalSource(); granularState.escapedSink = snapshot.escapedSink();
		granularState.deposits.forEachCell((cell, ignoredVolume, ignoredPorosity) -> granularDepositDynamics.disturbCell(cell));
		gridDirty = true;
	}

	/** Inventory-only exact insertion used by fixtures and transactional service adapters. */
	public GranularInsertion tryInsertExactGranular(
			int cellX, int cellY, int cellZ, int materialHandle, int compositionHandle,
			long solidVolumeSvq, double mass
	) {
		return granularTransferPlanner.insertExactInventory(cellX, cellY, cellZ, materialHandle,
				compositionHandle, solidVolumeSvq, mass);
	}

	/** All-or-nothing dry-container insertion. The ledger is already stable-ID validated by the item adapter. */
	public GranularInsertion tryInsertExactGranular(
			int cellX, int cellY, int cellZ, GranularTransferLedger ledger
	) {
		return tryInsertExactGranular(cellX, cellY, cellZ, ledger, 3);
	}

	public GranularInsertion tryInsertExactGranular(
			int cellX, int cellY, int cellZ, GranularTransferLedger ledger, int maxRadius
	) {
		return tryInsertExactGranular(cellX, cellY, cellZ, ledger, maxRadius, false);
	}

	public GranularInsertion tryInsertExactGranular(
			int cellX, int cellY, int cellZ, GranularTransferLedger ledger, int maxRadius, boolean externalSource
	) {
		return granularTransferPlanner.insertExact(cellX, cellY, cellZ, ledger, maxRadius, externalSource);
	}

	/** Extracts only same-cell active parcels and the one exposed deposit group, never deep inventory. */
	public GranularExtraction tryExtractExactGranular(
			int cellX, int cellY, int cellZ, long maximumSolidVolumeSvq
	) {
		return tryExtractExactGranular(cellX, cellY, cellZ, maximumSolidVolumeSvq, 3);
	}

	public GranularExtraction tryExtractExactGranular(
			int cellX, int cellY, int cellZ, long maximumSolidVolumeSvq, int maxRadius
	) {
		return tryExtractExactGranular(cellX, cellY, cellZ, maximumSolidVolumeSvq, maxRadius, false);
	}

	public GranularExtraction tryExtractExactGranular(
			int cellX, int cellY, int cellZ, long maximumSolidVolumeSvq, int maxRadius, boolean escapedSink
	) {
		return tryExtractExactGranular(cellX, cellY, cellZ, maximumSolidVolumeSvq, maxRadius,
				escapedSink, ignored -> true);
	}

	public GranularExtraction tryExtractExactGranular(
			int cellX, int cellY, int cellZ, long maximumSolidVolumeSvq, int maxRadius, boolean escapedSink,
			java.util.function.Predicate<GranularTransferLedger> itemAcceptance
	) {
		return granularTransferPlanner.extractExact(cellX, cellY, cellZ, maximumSolidVolumeSvq, maxRadius,
				escapedSink, itemAcceptance);
	}

	void markGranularExternalChange(int cell) {
		granularDepositDynamics.disturbCell(cell);
		if (precipitationEnabled) chemicalParcels.markDirtyCell(cell);
		gridDirty = true;
	}

	public record GranularInsertion(boolean inserted, long solidVolumeSvq, double mass, String failure) {
		static GranularInsertion rejected(String reason) { return new GranularInsertion(false, 0L, 0.0, reason); }
	}
	public record GranularExtraction(
			boolean transferred, GranularTransferLedger ledger, double internalEnergy, String failure
	) {
		static GranularExtraction rejected(String reason) { return new GranularExtraction(false, null, 0.0, reason); }
	}

	/** Returns the bound material registry, or null for a pure-water domain. */
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
	private IndicatorAimSeed resolveIndicatorAimSeed(int requested, int[] dominantPhase, int maxScannedCells) {
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

	private void enqueueIndicatorAimNeighbor(
			int from, int neighbor, int dx, int dy, int dz, int[] distance, ArrayDeque<Integer> queue
	) {
		if (distance[neighbor] < 0 && openFaceFromCell(from, dx, dy, dz) > 0.0F) {
			distance[neighbor] = distance[from] + 1;
			queue.addLast(neighbor);
		}
	}

	private record IndicatorAimSeed(int cell, int scannedCells, String failure) { }

	private void enqueueIndicatorNeighbor(
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
		private static IndicatorEditResult rejected(String failure) {
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
		if (registry == null || liquidRegistry == null) throw new IllegalArgumentException("Thermal/liquid registry is missing");
		if (!Double.isFinite(initialTemperatureK)) throw new IllegalArgumentException("Initial thermal temperature must be finite");
		if (Float.floatToRawIntBits(config.cellSize()) != Float.floatToRawIntBits(0.5F)) {
			throw new IllegalArgumentException("Thermal state requires a 0.5 m solver cell");
		}
		bindMaterialRegistry(liquidRegistry);
		if (thermalState != null) {
			if (!thermalState.fingerprint().equals(registry.fingerprint())) {
				throw new IllegalArgumentException("A domain cannot change its thermal registry");
			}
			return;
		}
		if (chemicalParcels != null && !registry.chemicalRegistryFingerprint()
				.equals(chemicalParcels.registry().fingerprint())) {
			throw new IllegalArgumentException("Thermal registry chemical dependency mismatch");
		}
		ensureGrid();
		if (!gasState.operatorVentedTopology) gasTopologyCoordinator.refresh();
		thermalState = new FlipThermalState(registry, materialRegistry, initialTemperatureK,
				particles.capacity(), sizeX, sizeY, sizeZ, config.cellSize(), thermalMarkerContext(), gasState.network,
				maximumTransportSubsteps);
		thermalBuoyancySum = new double[cellCount];
		thermalBuoyancyVolume = new double[cellCount];
		if (granularState != null) granularState.inventory.bindThermalEnergy(thermalGranularAdapter());
		if (granularCoupling == null) {
			granularCoupling = new GranularCouplingFields(sizeX, sizeY, sizeZ, config.cellSize());
			granularNextCoupling = new GranularCouplingFields(sizeX, sizeY, sizeZ, config.cellSize());
		}
		refreshCoherentOccupancy();
		gridDirty = true;
	}

	private GranularInventory.EnergyAdapter thermalGranularAdapter() {
		return new GranularInventory.EnergyAdapter() {
			private GranularEnergyColumn.DepositKey key(int cell, int material, int composition) {
				return new GranularEnergyColumn.DepositKey(cell, material, composition);
			}
			@Override public void requireActiveToDeposit(int active, int cell, int material, int composition) {
				thermalState.requireActiveToDeposit(active, key(cell, material, composition));
			}
			@Override public void commitActiveToDeposit(int active, int cell, int material, int composition) {
				thermalState.commitActiveToDeposit(active, key(cell, material, composition));
			}
			@Override public void requireActiveMerge(int first, int second) {
				thermalState.requireActiveMerge(first, second);
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
		bindThermal(registry, materialRegistry, registry.material(
				registry.materialHandle(ThermalMaterialDefinition.CarrierKind.LIQUID,
						materialRegistry.material(0).id())).referenceTemperatureK(), maximumTransportSubsteps);
		thermalState.restore(state, thermalMarkerContext(), gasState.network);
		refreshCoherentOccupancy();
		gridDirty = true;
	}

	private void validateCoherentRestoreCapacity(List<CoherentPhaseStore.Group> groups) {
		long[] occupied = new long[cellCount];
		for (CoherentPhaseStore.Group group : groups) {
			if (group.cell() < 0 || group.cell() >= cellCount) {
				throw new IllegalArgumentException("Restored coherent phase cell is outside the domain");
			}
			occupied[group.cell()] = Math.addExact(occupied[group.cell()], group.occupiedVolumeSvq());
		}
		ensureGrid();
		for (int cell = 0; cell < cellCount; cell++) {
			long geometric = SolidVolume.fromCellVolumeExact(geometry.cutCells.openCellVolume(cell));
			// FLIP marker clustering may transiently overfill a cell and is resolved by pressure after restore.
			// Only fixed solid occupancy is required to fit before the solver can safely resume that projection.
			long fixedSolid = Math.addExact(occupied[cell], phaseGranularSvq(cell));
			if (fixedSolid > geometric) {
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
		private final FiniteDeviceExchangeCoordinator.FiniteDeviceCellPlan delegate;
		private FiniteThermalCellPlan(FiniteDeviceExchangeCoordinator.FiniteDeviceCellPlan delegate) {
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
		ensureGrid();
		long geometric = SolidVolume.fromCellVolumeExact(geometry.cutCells.openCellVolume(cell));
		long previousCoherent = thermalState.coherentPhases().cellOccupiedSvq(cell);
		long occupied = granularCoupling.solidSvq(cell);
		long granular = occupied - previousCoherent;
		if (granular < 0L || Math.addExact(occupied, occupiedVolumeSvq) > geometric) {
			throw new IllegalStateException("Coherent phase exceeds exact joint cell occupancy");
		}
		if (totalVolumeExact() + SolidVolume.toCellVolume(totalGranularSolidVolumeSvq())
				+ SolidVolume.toCellVolume(Math.addExact(thermalState.coherentPhases().totalOccupiedSvq(), occupiedVolumeSvq))
				> totalGeometricOpenVolumeExact() + 1.0E-9) {
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

	ThermalPhaseStats stepInertPhaseChangeOnly(int maximumOperations, boolean phaseGridPrepared) {
		if (thermalState == null) throw new IllegalStateException("Thermal state is not bound");
		if (maximumOperations < 0 || maximumOperations > ThermalLimits.MAX_PHASE_OPERATIONS_PER_STEP) {
			throw new IllegalArgumentException("Thermal phase operation budget is invalid");
		}
		if (!phaseGridPrepared) thermalState.preparePhaseScan(thermalMarkerContext());
		int operations = 0, frozen = 0, melted = 0; long signedBatches = 0L;
		for (ThermalPhaseTransition transition : thermalState.registry().transitions()) {
			if (transition.interfaceRequirement() != ThermalPhaseTransition.InterfaceRequirement.NONE) continue;
			ThermalMaterialDefinition lower = thermalState.registry().material(
					thermalState.registry().materialHandle(transition.lowerEnergyMaterialId()));
			ThermalMaterialDefinition upper = thermalState.registry().material(
					thermalState.registry().materialHandle(transition.upperEnergyMaterialId()));
			if (lower.carrierKind() != ThermalMaterialDefinition.CarrierKind.COHERENT_SOLID
					|| upper.carrierKind() != ThermalMaterialDefinition.CarrierKind.LIQUID) continue;
			int liquidHandle = fluidMaterialHandle(upper.carrierId());
			for (int cell = 0; cell < cellCount && operations < maximumOperations; cell++) {
				long coherentSvq = thermalState.coherentPhases().cellOccupiedSvq(cell);
				if (markerGrid.markerCount[cell] == 0 && coherentSvq == 0L) continue;
				double absolutePressure = AirNetwork.REFERENCE_PRESSURE + pressureReference + projection.pressure[cell];
				if (!(Double.isFinite(absolutePressure)
						&& absolutePressure >= transition.minimumPressurePa()
						&& absolutePressure <= transition.maximumPressurePa())) {
					throw new ThermalPhaseBlockedException("Condensed phase pressure " + absolutePressure
							+ " Pa is outside " + transition.id() + " curve "
							+ transition.minimumPressurePa() + ".." + transition.maximumPressurePa() + " Pa");
				}
				double temperature = thermalState.phaseScanTemperatureInCell(cell);
				double equilibrium = transition.equilibriumTemperatureK(absolutePressure);
				if (temperature == equilibrium
						|| temperature < equilibrium && markerGrid.markerCount[cell] == 0
						|| temperature > equilibrium && coherentSvq == 0L) continue;
				PhaseCellInventory inventory = phaseCellInventory(cell, liquidHandle, lower.id());
				if (inventory == null) continue;
				long remainingBudget = maximumOperations - operations;
				JointPhasePlan plan = planJointPhaseChange(
						cell, liquidHandle, transition, inventory, remainingBudget, temperature, equilibrium);
				if (plan == null) continue;
				long magnitude = plan.batches();
				if (plan.freezing()) commitJointFreeze(cell, plan);
				else commitJointMelt(cell, liquidHandle, transition, plan);
				operations = Math.addExact(operations, Math.toIntExact(magnitude));
				signedBatches = Math.addExact(signedBatches, plan.freezing() ? magnitude : -magnitude);
				if (plan.freezing()) frozen++; else melted++;
			}
		}
		return new ThermalPhaseStats(operations, frozen, melted, signedBatches);
	}

	/** Sealed-interface liquid/gas phase transfer, bounded by saturation, energy, inventory and one shared batch budget. */
	public GasThermalPhaseStats stepGasPhaseChangeOnly(int maximumOperations) {
		return gasLiquidPhaseChangeStage.step(maximumOperations, false);
	}

	GasThermalPhaseStats stepGasPhaseChangeOnly(int maximumOperations, boolean phaseGridPrepared) {
		if (thermalState == null || !thermalState.gasModelBound()) {
			throw new IllegalStateException("Gas phase change requires bound gas thermal state");
		}
		if (maximumOperations < 0 || maximumOperations > ThermalLimits.MAX_PHASE_OPERATIONS_PER_STEP) {
			throw new IllegalArgumentException("Gas phase operation budget is invalid");
		}
		ensureGrid(); gasTopologyCoordinator.refresh();
		if (!phaseGridPrepared) thermalState.preparePhaseScan(thermalMarkerContext());
		GasLiquidPhaseModel phase = thermalState.gasLiquidPhase(gasState.network);
		HashMap<GasPhaseDirectionKey, GasLiquidPhaseModel.Direction> stepDirections = new HashMap<>();
		int operations = 0, boiled = 0, condensed = 0;
		for (ThermalPhaseTransition transition : thermalState.registry().transitions()) {
			if (transition.interfaceRequirement() != ThermalPhaseTransition.InterfaceRequirement.LIQUID_GAS) continue;
			ThermalMaterialDefinition liquid = thermalState.registry().material(
					thermalState.registry().materialHandle(transition.lowerEnergyMaterialId()));
			ThermalMaterialDefinition gas = thermalState.registry().material(
					thermalState.registry().materialHandle(transition.upperEnergyMaterialId()));
			if (liquid.carrierKind() != ThermalMaterialDefinition.CarrierKind.LIQUID
					|| gas.carrierKind() != ThermalMaterialDefinition.CarrierKind.GAS) continue;
			int liquidHandle = fluidMaterialHandle(liquid.carrierId());
			int gasThermalHandle = thermalState.registry().materialHandle(gas.id());
			boolean[] volatileSpecies = gasVolatileSpecies(gasThermalHandle);
			for (int cell = 0; cell < cellCount && operations < maximumOperations; cell++) {
				if (!pendingPhaseCellVolume[cell].equals(SignedDyadic.ZERO)) continue;
				int node = sealedInterfaceNode(cell);
				if (node <= 0) continue;
				if (!gasLiquidCellMatches(cell, liquidHandle)) continue;
				double absolutePressure = GasRegistry.STANDARD_PRESSURE_PA
						+ thermalState.gasGaugePressure(node, gasState.network);
				double liquidTemperature = thermalState.phaseScanTemperatureInCell(cell);
				GasLiquidPhaseModel.Direction direction = phase.saturation(
						transition, liquidTemperature, gasState.network.inventory(node), absolutePressure).direction();
				if (direction == GasLiquidPhaseModel.Direction.EQUILIBRIUM) continue;
				GasPhaseDirectionKey directionKey = new GasPhaseDirectionKey(
						transition.id(), node, Double.doubleToRawLongBits(liquidTemperature));
				GasLiquidPhaseModel.Direction firstDirection = stepDirections.putIfAbsent(directionKey, direction);
				if (firstDirection != null && firstDirection != direction) continue;
				PhaseCellInventory inventory = gasLiquidCellInventory(cell, liquidHandle);
				if (inventory == null) continue;
				long remainingBudget = maximumOperations - operations;
				ThermalDomainState.PhaseCandidateCell thermalCell =
						thermalState.preparePhaseCandidateCell(cell, thermalMarkerContext());
				if (direction == GasLiquidPhaseModel.Direction.BOIL) {
					GasBoilPlan plan = planGasBoil(cell, node, liquidHandle, gasThermalHandle,
							transition, inventory, phase, thermalCell, volatileSpecies,
							liquidTemperature, remainingBudget);
					if (plan == null) continue;
					boolean carrierTopologyChanged = plan.removal().carrierTopologyChanged();
					double markerVolumeDelta = carrierTopologyChanged
							? 0.0 : phaseMarkerVolumeDelta(plan.removal().allocations());
					commitGasBoil(cell, node, plan, thermalCell);
					refreshGasPhaseGeometry(carrierTopologyChanged, cell, markerVolumeDelta);
					operations = Math.addExact(operations, Math.toIntExact(plan.batches())); boiled++;
				} else if (direction == GasLiquidPhaseModel.Direction.CONDENSE) {
					GasCondensePlan plan = planGasCondense(cell, node, liquidHandle, transition,
							inventory, phase, thermalCell, gas.carrierId(), remainingBudget);
					if (plan == null) continue;
					boolean carrierTopologyChanged = plan.target() < 0;
					double markerVolumeDelta = carrierTopologyChanged
							? 0.0 : (double) plan.targetVolume() - particles.volume(plan.target());
					commitGasCondense(cell, node, liquidHandle, plan, thermalCell);
					refreshGasPhaseGeometry(carrierTopologyChanged, cell, markerVolumeDelta);
					operations = Math.addExact(operations, Math.toIntExact(plan.batches())); condensed++;
				}
			}
		}
		return new GasThermalPhaseStats(operations, boiled, condensed);
	}

	private double phaseMarkerVolumeDelta(List<PhaseMarkerAllocation> allocations) {
		double delta = 0.0;
		for (PhaseMarkerAllocation allocation : allocations) {
			delta += (double) allocation.newVolume() - particles.volume(allocation.marker());
		}
		return delta;
	}

	private GasBoilPlan planGasBoil(
			int cell, int node, int liquidHandle, int gasThermalHandle,
			ThermalPhaseTransition transition, PhaseCellInventory inventory,
			GasLiquidPhaseModel phase, ThermalDomainState.PhaseCandidateCell thermalCell,
			boolean[] volatileSpecies,
			double liquidTemperature, long maximumBatches
	) {
		double batchMass = transition.massMuPerBatch().toDouble();
		long byVolume = inventory.freezableUpperSvq() / transition.lowerVolumeSvqPerBatch();
		long byMass = (long) Math.floor((inventory.freezableUpperMass()
				+ massTolerance(inventory.freezableUpperMass())) / batchMass);
		long high = Math.min(maximumBatches, Math.min(byVolume, byMass));
		if (high <= 0L) return null;
		GasBoilPlan first = gasBoilCandidate(cell, node, liquidHandle, gasThermalHandle,
				transition, inventory, phase, thermalCell, volatileSpecies, liquidTemperature, 1L);
		if (first == null || first.directionAfter() != GasLiquidPhaseModel.Direction.BOIL) return first;
		GasBoilPlan best = first;
		long low = 2L;
		while (low <= high) {
			long middle = low + ((high - low) >>> 1);
			GasBoilPlan candidate = gasBoilCandidate(cell, node, liquidHandle, gasThermalHandle,
					transition, inventory, phase, thermalCell, volatileSpecies, liquidTemperature, middle);
			if (candidate == null || candidate.directionAfter() != GasLiquidPhaseModel.Direction.BOIL) {
				high = middle - 1L;
			} else {
				best = candidate;
				low = middle + 1L;
			}
		}
		return best;
	}

	private GasBoilPlan gasBoilCandidate(
			int cell, int node, int liquidHandle, int gasThermalHandle,
			ThermalPhaseTransition transition, PhaseCellInventory before, GasLiquidPhaseModel phase,
			ThermalDomainState.PhaseCandidateCell thermalCell, boolean[] volatileSpecies,
			double liquidTemperature, long batches
	) {
		try {
			long removedSvq = Math.multiplyExact(batches, transition.lowerVolumeSvqPerBatch());
			double removedMass = transition.massMuPerBatch().multiply(batches).toDouble();
			PhaseRemovalPlan removal = planGasPhaseRemoval(
					cell, liquidHandle, removedSvq, removedMass, liquidTemperature, volatileSpecies);
			GasInventory batch = chemicalParcels == null ? phase.gasBatch(transition, batches)
					: phase.gasBatch(transition, batches, removal.movedComposition().amounts(),
							removal.movedComposition().massResidual());
			double batchEnergy = phase.internalEnergyAtTemperature(transition, batch, liquidTemperature);
			double remainingMass = before.upperMass() - removedMass;
			if (remainingMass < 0.0 && remainingMass >= -massTolerance(before.upperMass())) remainingMass = 0.0;
			PhaseComposition remainingComposition = before.upperComposition().minus(removal.movedComposition());
			List<ThermalStateFunction.InventoryPortion> remainingLiquid = remainingMass == 0.0
					? List.of() : List.of(new ThermalStateFunction.InventoryPortion(
							thermalState.thermalMaterialHandleForFluid(liquidHandle), remainingMass,
							chemicalParcels == null ? Map.of() : remainingComposition.amounts()));
			double candidateLiquidTemperature = thermalState.requirePhaseCandidateState(
					thermalCell, remainingLiquid, -batchEnergy);
			GasInventory candidateGas = gasState.network.inventory(node).merge(batch);
			double candidateGasEnergy = thermalState.gasNodeEnergy(node, gasState.network) + batchEnergy;
			GasLiquidPhaseModel.Direction directionAfter = prospectiveGasPhaseDirection(
					node, transition, phase, candidateLiquidTemperature, candidateGas, candidateGasEnergy);
			return new GasBoilPlan(batches, removedSvq, removal, batch, candidateGasEnergy, directionAfter);
		} catch (ThermalStateFunction.EnergyOutsideValidityException | PhaseCandidateUnavailableException unavailable) {
			return null;
		}
	}

	private void commitGasBoil(
			int cell, int node, GasBoilPlan plan, ThermalDomainState.PhaseCandidateCell thermalCell
	) {
		double gasEnergyBefore = thermalState.gasNodeEnergy(node, gasState.network);
		for (PhaseMarkerAllocation allocation : plan.removal().allocations()) {
			if (allocation.fullRemoval()) {
				removeParticleInternalPhase(allocation.marker());
			}
			else {
				if (chemicalParcels != null) {
					chemicalParcels.markDirtyCell(cell);
					chemicalParcels.commitTruncate(allocation.marker(), allocation.chemicalPlan());
				}
				particles.setVolume(allocation.marker(), allocation.newVolume());
				particles.setMass(allocation.marker(), allocation.newMass());
				thermalState.commitInternalTruncate(allocation.marker(), allocation.thermalPlan());
			}
		}
		gasState.network.commitPhaseSource(node, plan.batch());
		thermalState.commitGasNodeEnergy(node, plan.candidateGasEnergy());
		commitPendingPhaseVolume(cell, -plan.removedSvq());
		gridDirty = true;
		thermalState.repartitionPhaseCellAndGasEnergyConserving(
				thermalCell, gasEnergyBefore, plan.candidateGasEnergy(), thermalMarkerContext(),
				plan.removal().carrierTopologyChanged());
	}

	private GasCondensePlan planGasCondense(
			int cell, int node, int liquidHandle, ThermalPhaseTransition transition,
			PhaseCellInventory before, GasLiquidPhaseModel phase,
			ThermalDomainState.PhaseCandidateCell thermalCell, String gasCarrierId, long maximumBatches
	) {
		GasComponentAmount vapor = gasState.network.inventory(node).component(gasCarrierId);
		if (vapor == null) return null;
		long nanosPerBatch = phase.gasBatch(transition, 1L).standardVolumeNanos();
		long byGas = vapor.standardVolumeNanos() / nanosPerBatch;
		long occupied = Math.addExact(Math.addExact(
				Math.addExact(before.upperVolumeSvq(), before.lowerVolumeSvq()), phaseGranularSvq(cell)),
				thermalState.coherentPhases().cellOccupiedSvq(cell));
		long capacity = SolidVolume.fromCellVolumeExact(geometry.cutCells.openCellVolume(cell));
		long byCapacity = (capacity - occupied) / transition.lowerVolumeSvqPerBatch();
		long high = Math.min(maximumBatches, Math.min(byGas, byCapacity));
		if (high <= 0L) return null;
		GasCondensePlan first = gasCondenseCandidate(
				cell, node, liquidHandle, transition, before, phase, thermalCell, 1L);
		if (first == null || first.directionAfter() != GasLiquidPhaseModel.Direction.CONDENSE) return first;
		GasCondensePlan best = first;
		long low = 2L;
		while (low <= high) {
			long middle = low + ((high - low) >>> 1);
			GasCondensePlan candidate = gasCondenseCandidate(
					cell, node, liquidHandle, transition, before, phase, thermalCell, middle);
			if (candidate == null || candidate.directionAfter() != GasLiquidPhaseModel.Direction.CONDENSE) {
				high = middle - 1L;
			} else {
				best = candidate;
				low = middle + 1L;
			}
		}
		return best;
	}

	private GasCondensePlan gasCondenseCandidate(
			int cell, int node, int liquidHandle, ThermalPhaseTransition transition,
			PhaseCellInventory before, GasLiquidPhaseModel phase,
			ThermalDomainState.PhaseCandidateCell thermalCell, long batches
	) {
		try {
			GasInventory requestedBatch = phase.gasBatch(transition, batches);
			String componentId = requestedBatch.components().getFirst().componentId();
			GasInventory.Split proportionalTake = gasState.network.previewComponentTake(
					node, componentId, requestedBatch.standardVolumeNanos());
			double requestedMass = transition.massMuPerBatch().multiply(batches).toDouble();
			GasComponentAmount proportionalMoved = proportionalTake.first().components().getFirst();
			double requestedResidual = requestedBatch.components().getFirst().massResidual();
			if (chemicalParcels != null) {
				requestedResidual = requestedMass - PhaseComposition.fromGas(proportionalMoved)
						.exactSpeciesMass(chemicalParcels.registry()).toDouble();
			}
			GasInventory.Split take;
			try {
				take = gasState.network.previewComponentTake(node, componentId,
						requestedBatch.standardVolumeNanos(), requestedMass, requestedResidual);
			} catch (IllegalArgumentException unrepresentableColumns) {
				throw new PhaseCandidateUnavailableException(
						"Condensation has no complete representable gas column quantum", unrepresentableColumns);
			}
			GasComponentAmount moved = take.first().components().getFirst();
			if (!massClose(moved.mass(), requestedMass)) {
				throw new ThermalPhaseBlockedException("Condensation gas batch mass differs from its exact transition");
			}
			long addedSvq = Math.multiplyExact(batches, transition.lowerVolumeSvqPerBatch());
			long occupied = Math.addExact(Math.addExact(
					Math.addExact(before.upperVolumeSvq(), before.lowerVolumeSvq()), phaseGranularSvq(cell)),
					thermalState.coherentPhases().cellOccupiedSvq(cell));
			long capacity = SolidVolume.fromCellVolumeExact(geometry.cutCells.openCellVolume(cell));
			if (Math.addExact(occupied, addedSvq) > capacity) {
				throw new PhaseCandidateUnavailableException("Condensation exceeds interface-cell capacity");
			}
			double gasTemperature = thermalState.gasTemperature(node, gasState.network);
			double batchEnergy = phase.internalEnergyAtTemperature(transition, take.first(), gasTemperature);
			double candidateGasEnergy = thermalState.gasNodeEnergy(node, gasState.network) - batchEnergy;
			int target = -1;
			for (int marker = markerGrid.cellMarkerHead[cell]; marker >= 0; marker = markerGrid.particleCellNext[marker]) if (
					particles.materialHandle(marker) == liquidHandle
					&& (chemicalParcels == null || !chemicalParcels.indicator(marker))) { target = marker; break; }
			PhaseComposition movedComposition = PhaseComposition.fromGas(moved);
			ChemicalMixtureRecord condensedMixture = null;
			if (chemicalParcels != null) {
				if (movedComposition.isEmpty()) {
					throw new ThermalPhaseBlockedException("Composition-bearing condensation has no gas species truth");
				}
				condensedMixture = target >= 0
						? PhaseComposition.fromMixture(chemicalParcels.record(target), chemicalParcels.registry())
							.plus(movedComposition).toMixture(chemicalParcels.registry())
						: movedComposition.toMixture(chemicalParcels.registry());
				chemicalParcels.requireCompatible(condensedMixture,
						materialRegistry.material(liquidHandle).id(), false);
				if (target >= 0) chemicalParcels.requireCanCommitPlannedReplacements(
						new int[]{target}, new ChemicalMixtureRecord[]{condensedMixture});
			} else if (!moved.species().isEmpty()) {
				throw new ThermalPhaseBlockedException("Gas species require liquid chemistry during condensation");
			}
			float targetVolume = 0.0F; double targetMass = 0.0; int slot = -1;
			if (target >= 0) {
				targetVolume = phaseAdjustedMarkerVolume(particles.volume(target), addedSvq, "Condensation");
				targetMass = particles.mass(target) + unitScale.solverMass(moved.mass());
			} else {
				if (particles.count() >= maximumParticleCount) {
					throw new PhaseCandidateUnavailableException("Condensation exceeds marker budget");
				}
				slot = firstOpenSlot(cell, 0);
				if (slot < 0) {
					throw new PhaseCandidateUnavailableException("Condensation has no collision-valid marker slot");
				}
			}
			double candidateLiquidTemperature = thermalState.requirePhaseCandidateState(thermalCell,
					liquidInventoryAfterCondensation(cell, liquidHandle, target, targetMass,
							condensedMixture, moved.mass()), batchEnergy);
			GasLiquidPhaseModel.Direction directionAfter = prospectiveGasPhaseDirection(
					node, transition, phase, candidateLiquidTemperature, take.second(), candidateGasEnergy);
			return new GasCondensePlan(batches, take.first(), candidateGasEnergy, target,
					condensedMixture, targetVolume, targetMass, slot, addedSvq, moved.mass(), directionAfter);
		} catch (ThermalStateFunction.EnergyOutsideValidityException | PhaseCandidateUnavailableException unavailable) {
			return null;
		}
	}

	/** Converts only prospective gas EoS/saturation validity failures into a shrinkable phase extent. */
	private GasLiquidPhaseModel.Direction prospectiveGasPhaseDirection(
			int node, ThermalPhaseTransition transition, GasLiquidPhaseModel phase,
			double liquidTemperature, GasInventory gasInventory, double gasEnergy
	) {
		try {
			double pressure = thermalState.requireGasNodeState(node, gasInventory, gasEnergy, gasState.network);
			return phase.saturation(transition, liquidTemperature, gasInventory, pressure).direction();
		} catch (ThermalStateFunction.EnergyOutsideValidityException outsideThermalRange) {
			throw outsideThermalRange;
		} catch (IllegalArgumentException outsideGasPhaseRange) {
			throw new PhaseCandidateUnavailableException(
					"Gas phase candidate is outside its pressure or saturation validity range",
					outsideGasPhaseRange);
		}
	}

	private void commitGasCondense(
			int cell, int node, int liquidHandle, GasCondensePlan plan,
			ThermalDomainState.PhaseCandidateCell thermalCell
	) {
		double gasEnergyBefore = thermalState.gasNodeEnergy(node, gasState.network);
		gasState.network.commitPhaseSink(node, plan.removedGas());
		thermalState.commitGasNodeEnergy(node, plan.candidateGasEnergy());
		if (plan.target() >= 0) {
			if (chemicalParcels != null) chemicalParcels.commitPlannedReplacements(
					new int[]{plan.target()}, new ChemicalMixtureRecord[]{plan.condensedMixture()});
			particles.setVolume(plan.target(), plan.targetVolume());
			particles.setMass(plan.target(), plan.targetMass());
		} else {
			int x = cell % sizeX, yz = cell / sizeX, y = yz % sizeY, z = yz / sizeY;
			spawnParticle(x, y, z, plan.slot(), SolidVolume.toCellVolume(plan.addedSvq()), liquidHandle,
					unitScale.solverMass(plan.movedMass()),
					plan.condensedMixture(), false, false);
		}
		commitPendingPhaseVolume(cell, plan.addedSvq());
		gridDirty = true;
		thermalState.repartitionPhaseCellAndGasEnergyConserving(
				thermalCell, gasEnergyBefore, plan.candidateGasEnergy(), thermalMarkerContext(),
				plan.target() < 0);
	}

	private PhaseCellInventory gasLiquidCellInventory(int cell, int liquidHandle) {
		double mass = 0.0, energy = 0.0, freezableMass = 0.0;
		long svq = 0L, freezableSvq = 0L; boolean found = false;
		PhaseCompositionBuilder composition = chemicalParcels == null ? null : new PhaseCompositionBuilder();
		for (int marker = markerGrid.cellMarkerHead[cell]; marker >= 0; marker = markerGrid.particleCellNext[marker]) {
			if (particles.materialHandle(marker) != liquidHandle) {
				throw new ThermalPhaseBlockedException("Gas phase cell contains another liquid carrier");
			}
			svq = Math.addExact(svq, SolidVolume.cellVolumeSvqAtLeast(particles.volume(marker)));
			mass += unitScale.kilograms(particles.mass(marker));
			energy += thermalState.liquidInternalEnergy(marker); found = true;
			if (chemicalParcels != null) composition.add(chemicalParcels.record(marker));
			if (chemicalParcels == null || !chemicalParcels.indicator(marker)) {
				freezableSvq = Math.addExact(freezableSvq,
						SolidVolume.cellVolumeSvqAtMost(particles.volume(marker)));
				freezableMass += unitScale.kilograms(particles.mass(marker));
			}
		}
		return found ? new PhaseCellInventory(0L, svq, 0.0, mass, energy,
				PhaseComposition.EMPTY, chemicalParcels == null ? PhaseComposition.EMPTY : composition.build(),
				freezableSvq, freezableMass) : null;
	}

	private boolean gasLiquidCellMatches(int cell, int liquidHandle) {
		boolean found = false;
		for (int marker = markerGrid.cellMarkerHead[cell]; marker >= 0; marker = markerGrid.particleCellNext[marker]) {
			if (particles.materialHandle(marker) != liquidHandle) {
				throw new ThermalPhaseBlockedException("Gas phase cell contains another liquid carrier");
			}
			found = true;
		}
		return found;
	}

	private boolean[] gasVolatileSpecies(int gasThermalHandle) {
		if (chemicalParcels == null) return null;
		var component = gasState.network.registry().component(
				thermalState.registry().material(gasThermalHandle).carrierId());
		boolean[] result = new boolean[chemicalParcels.registry().species().size()];
		for (int species = 0; species < result.length; species++) {
			String stableId = chemicalParcels.registry().species(species).id();
			result[species] = component.allowsSpecies(stableId)
					&& thermalState.registry().hasSpeciesOffset(gasThermalHandle, stableId);
		}
		return result;
	}

	private int sealedInterfaceNode(int cell) {
		if (gasState.cellNodeId == null || markerGrid.markerCount[cell] == 0) return -1;
		int x = cell % sizeX, yz = cell / sizeX, y = yz % sizeY, z = yz / sizeY;
		int found = -1;
		for (int[] direction : SURFACE_TENSION_DIRECTIONS) {
			int nx = x + direction[0], ny = y + direction[1], nz = z + direction[2];
			if (!inside(nx, ny, nz)) continue;
			int node = gasState.cellNodeId[cellIndex(nx, ny, nz)];
			if (node <= 0) continue;
			if (found < 0) found = node;
			else if (found != node) throw new ThermalPhaseBlockedException(
					"Liquid/gas interface touches multiple sealed nodes");
		}
		return found;
	}

	private PhaseCellInventory phaseCellInventory(int cell, int upperLiquidHandle, String lowerPhaseId) {
		double upperMass = 0.0, lowerMass = 0.0, energy = 0.0;
		long upperSvq = 0L, lowerSvq = 0L, freezableUpperSvq = 0L;
		double freezableUpperMass = 0.0;
		PhaseCompositionBuilder upperComposition = chemicalParcels == null ? null : new PhaseCompositionBuilder();
		PhaseComposition lowerComposition = null;
		boolean found = false;
		for (int marker = 0; marker < particles.count(); marker++) if (particleCellIndex(marker) == cell) {
			if (particles.materialHandle(marker) != upperLiquidHandle) {
				throw new ThermalPhaseBlockedException("Phase cell contains an unsupported liquid heat-bath carrier");
			}
			upperSvq = Math.addExact(upperSvq, SolidVolume.cellVolumeSvqAtLeast(particles.volume(marker)));
			upperMass += unitScale.kilograms(particles.mass(marker));
			energy += thermalState.liquidInternalEnergy(marker); found = true;
			if (chemicalParcels != null) upperComposition.add(chemicalParcels.record(marker));
			if (chemicalParcels == null || !chemicalParcels.indicator(marker)) {
				freezableUpperSvq = Math.addExact(freezableUpperSvq,
						SolidVolume.cellVolumeSvqAtMost(particles.volume(marker)));
				freezableUpperMass += unitScale.kilograms(particles.mass(marker));
			}
		}
		for (CoherentPhaseStore.Group group : thermalState.coherentPhases().snapshot()) if (group.cell() == cell) {
			if (!group.phaseId().equals(lowerPhaseId)) {
				throw new ThermalPhaseBlockedException("Phase cell contains an unsupported coherent heat-bath carrier");
			}
			if (lowerComposition != null) throw new ThermalPhaseBlockedException("Phase cell contains duplicate coherent groups");
			if (chemicalParcels != null && group.composition() == null
					|| chemicalParcels == null && group.composition() != null) {
				throw new ThermalPhaseBlockedException("Coherent composition/chemistry capability mismatch");
			}
			lowerComposition = PhaseComposition.fromCoherent(group.composition());
			lowerSvq = Math.addExact(lowerSvq, group.occupiedVolumeSvq());
			lowerMass += group.mass(); energy += group.internalEnergy(); found = true;
		}
		return found ? new PhaseCellInventory(lowerSvq, upperSvq, lowerMass, upperMass, energy,
				lowerComposition == null ? PhaseComposition.EMPTY : lowerComposition,
				upperComposition == null ? PhaseComposition.EMPTY : upperComposition.build(),
				freezableUpperSvq, freezableUpperMass) : null;
	}

	private JointPhasePlan planJointPhaseChange(
			int cell, int liquidHandle, ThermalPhaseTransition transition,
			PhaseCellInventory before, long maximumBatches, double temperature, double equilibrium
	) {
		if (temperature == equilibrium || maximumBatches == 0L) return null;
		double batchMass = transition.massMuPerBatch().toDouble();
		if (!(Double.isFinite(batchMass) && batchMass > 0.0)) {
			throw new ThermalPhaseBlockedException("Condensed phase batch mass is not representable");
		}
		boolean freezing = temperature < equilibrium;
		long volumeInventory = freezing ? before.freezableUpperSvq() : before.lowerVolumeSvq();
		long volumeBatch = freezing ? transition.upperVolumeSvqPerBatch()
				: transition.lowerVolumeSvqPerBatch();
		double massInventory = freezing ? before.freezableUpperMass() : before.lowerMass();
		long byVolume = volumeInventory / volumeBatch;
		long byMass = (long) Math.floor((massInventory + massTolerance(massInventory)) / batchMass);
		long high = Math.min(maximumBatches, Math.min(byVolume, byMass));
		long signedVolumePerBatch = freezing
				? transition.lowerVolumeSvqPerBatch() - transition.upperVolumeSvqPerBatch()
				: transition.upperVolumeSvqPerBatch() - transition.lowerVolumeSvqPerBatch();
		long occupied = Math.addExact(Math.addExact(before.lowerVolumeSvq(), before.upperVolumeSvq()),
				phaseGranularSvq(cell));
		long capacity = SolidVolume.fromCellVolumeExact(geometry.cutCells.openCellVolume(cell));
		if (signedVolumePerBatch > 0L) {
			high = Math.min(high, (capacity - occupied) / signedVolumePerBatch);
		}
		long minimumBatches = minimumPhaseBatchesForCapacity(occupied, capacity, signedVolumePerBatch);
		if (high < minimumBatches) return null;
		boolean capacityForced = occupied > capacity;

		JointPhasePlan first;
		try {
			first = jointPhaseCandidate(cell, liquidHandle, transition, before, freezing, minimumBatches);
		} catch (ThermalStateFunction.EnergyOutsideValidityException | PhaseCandidateUnavailableException unavailable) {
			// The exact quantum cannot fit this cell's thermal range or its independent marker mass/volume truth.
			return null;
		}
		if (!capacityForced && !strictlyCloserToEquilibrium(
				first.temperatureAfterK(), temperature, equilibrium)) return null;
		if (crossedEquilibrium(first.temperatureAfterK(), equilibrium, freezing)) return first;
		JointPhasePlan best = first;
		long low = minimumBatches + 1L;
		while (low <= high) {
			long middle = low + ((high - low) >>> 1);
			JointPhasePlan candidate;
			try {
				candidate = jointPhaseCandidate(cell, liquidHandle, transition, before, freezing, middle);
			} catch (ThermalStateFunction.EnergyOutsideValidityException | PhaseCandidateUnavailableException unavailable) {
				high = middle - 1L;
				continue;
			}
			if (crossedEquilibrium(candidate.temperatureAfterK(), equilibrium, freezing)) {
				if (strictlyCloserToEquilibrium(
						candidate.temperatureAfterK(), best.temperatureAfterK(), equilibrium)) best = candidate;
				high = middle - 1L;
			} else {
				best = candidate;
				low = middle + 1L;
			}
		}
		return best;
	}

	static long minimumPhaseBatchesForCapacity(long occupiedSvq, long capacitySvq, long signedVolumeSvqPerBatch) {
		if (occupiedSvq < 0L || capacitySvq < 0L) {
			throw new IllegalArgumentException("Phase capacity inputs are invalid");
		}
		if (occupiedSvq <= capacitySvq) return 1L;
		if (signedVolumeSvqPerBatch >= 0L) return Long.MAX_VALUE;
		long shrink = Math.negateExact(signedVolumeSvqPerBatch);
		long excess = occupiedSvq - capacitySvq;
		return excess / shrink + (excess % shrink == 0L ? 0L : 1L);
	}

	private static boolean crossedEquilibrium(double candidateK, double equilibriumK, boolean freezing) {
		return freezing ? candidateK > equilibriumK : candidateK < equilibriumK;
	}

	private static boolean strictlyCloserToEquilibrium(
			double candidateK, double acceptedK, double equilibriumK
	) {
		double threshold = Math.max(1.0E-12,
				Math.max(Math.ulp(candidateK), Math.ulp(equilibriumK)) * 16.0);
		return Math.abs(candidateK - equilibriumK) < Math.abs(acceptedK - equilibriumK) - threshold;
	}

	private JointPhasePlan jointPhaseCandidate(
			int cell, int liquidHandle, ThermalPhaseTransition transition,
			PhaseCellInventory before, boolean freezing, long batches
	) {
		long lowerDelta = Math.multiplyExact(batches, transition.lowerVolumeSvqPerBatch());
		long upperDelta = Math.multiplyExact(batches, transition.upperVolumeSvqPerBatch());
		double requestedMass = transition.massMuPerBatch().toDouble() * batches;
		if (!(Double.isFinite(requestedMass) && requestedMass > 0.0)) {
			throw new ThermalPhaseBlockedException("Condensed phase extent mass is not representable");
		}
		long finalLowerSvq, finalUpperSvq;
		double finalLowerMass, finalUpperMass;
		PhaseComposition finalLowerComposition, finalUpperComposition;
		PhaseRemovalPlan removal = null;
		CoherentPhaseStore.Group coherentTransfer;
		ChemicalMixtureRecord meltedMixture = null;
		int meltSlot = -1;
		if (freezing) {
			if (upperDelta > before.freezableUpperSvq()
					|| requestedMass > before.freezableUpperMass() + massTolerance(before.freezableUpperMass())) {
				throw new ThermalPhaseBlockedException("Freeze extent exceeds non-indicator liquid inventory");
			}
			removal = planJointPhaseRemoval(cell, liquidHandle, upperDelta, requestedMass);
			finalLowerSvq = Math.addExact(before.lowerVolumeSvq(), lowerDelta);
			finalUpperSvq = before.upperVolumeSvq() - upperDelta;
			finalLowerMass = before.lowerMass() + requestedMass;
			finalUpperMass = before.upperMass() - requestedMass;
			if (finalUpperMass < 0.0 && finalUpperMass >= -massTolerance(before.upperMass())) finalUpperMass = 0.0;
			finalLowerComposition = before.lowerComposition().plus(removal.movedComposition());
			finalUpperComposition = before.upperComposition().minus(removal.movedComposition());
			coherentTransfer = new CoherentPhaseStore.Group(cell, transition.lowerEnergyMaterialId(),
					lowerDelta, requestedMass, removal.movedComposition().toCoherent(), removal.movedEnergy());
			thermalState.coherentPhases().previewAdd(coherentTransfer);
		} else {
			if (lowerDelta > before.lowerVolumeSvq()
					|| requestedMass > before.lowerMass() + massTolerance(before.lowerMass())) {
				throw new ThermalPhaseBlockedException("Melt extent exceeds coherent inventory");
			}
			coherentTransfer = thermalState.coherentPhases().previewTake(
					cell, transition.lowerEnergyMaterialId(), lowerDelta);
			if (!massClose(coherentTransfer.mass(), requestedMass)) {
				throw new ThermalPhaseBlockedException("Melt exact batch mass differs from coherent inventory");
			}
			PhaseComposition moved = PhaseComposition.fromCoherent(coherentTransfer.composition());
			finalLowerSvq = before.lowerVolumeSvq() - lowerDelta;
			finalUpperSvq = Math.addExact(before.upperVolumeSvq(), upperDelta);
			finalLowerMass = before.lowerMass() - coherentTransfer.mass();
			finalUpperMass = before.upperMass() + coherentTransfer.mass();
			if (finalLowerMass < 0.0 && finalLowerMass >= -massTolerance(before.lowerMass())) finalLowerMass = 0.0;
			finalLowerComposition = before.lowerComposition().minus(moved);
			finalUpperComposition = before.upperComposition().plus(moved);
			if (chemicalParcels != null) {
				meltedMixture = moved.toMixture(chemicalParcels.registry());
				chemicalParcels.requireCompatible(meltedMixture,
						materialRegistry.material(liquidHandle).id(), false);
			} else if (!moved.isEmpty()) {
				throw new ThermalPhaseBlockedException("Chemical coherent phase cannot melt without chemistry state");
			}
			if (particles.count() >= maximumParticleCount) {
				throw new ThermalPhaseBlockedException("Melt exceeds the liquid marker budget");
			}
			meltSlot = firstOpenSlot(cell, 0);
			if (meltSlot < 0) throw new ThermalPhaseBlockedException(
					"Melt has no collision-valid liquid marker slot");
		}

		long occupied = Math.addExact(Math.addExact(finalLowerSvq, finalUpperSvq), phaseGranularSvq(cell));
		long capacity = SolidVolume.fromCellVolumeExact(geometry.cutCells.openCellVolume(cell));
		if (occupied > capacity) {
			throw new ThermalPhaseBlockedException("Condensed phase extent exceeds joint cell capacity");
		}
		ArrayList<ThermalStateFunction.InventoryPortion> phaseInventory = new ArrayList<>(2);
		if (finalLowerMass > 0.0) phaseInventory.add(new ThermalStateFunction.InventoryPortion(
				thermalState.registry().materialHandle(transition.lowerEnergyMaterialId()), finalLowerMass,
				finalLowerComposition.amounts()));
		if (finalUpperMass > 0.0) phaseInventory.add(new ThermalStateFunction.InventoryPortion(
				thermalState.thermalMaterialHandleForFluid(liquidHandle), finalUpperMass,
				finalUpperComposition.amounts()));
		double candidateTemperature = thermalState.phaseCandidateTemperature(
				cell, phaseInventory, thermalMarkerContext());
		long signedVolumeSvq = freezing ? lowerDelta - upperDelta : upperDelta - lowerDelta;
		return new JointPhasePlan(batches, freezing, removal, coherentTransfer,
				meltedMixture, meltSlot, candidateTemperature, signedVolumeSvq);
	}

	private long phaseGranularSvq(int cell) {
		long total = 0L;
		if (granularState != null) for (int parcel = 0; parcel < granularState.parcels.count(); parcel++) {
			var carrier = granularState.parcels.parcel(parcel);
			if (granularParcelCell(carrier) == cell) total = Math.addExact(total, carrier.solidVolumeSvq());
		}
		if (granularState != null) total = Math.addExact(total, granularState.deposits.cellSolidVolumeSvq(cell));
		return total;
	}

	private PhaseRemovalPlan planJointPhaseRemoval(
			int cell, int liquidHandle, long requestedSvq, double requestedMass
	) {
		ArrayList<PhaseMarkerAllocation> allocations = new ArrayList<>();
		PhaseCompositionBuilder moved = new PhaseCompositionBuilder();
		SignedDyadic movedEnergy = SignedDyadic.ZERO;
		long remainingSvq = requestedSvq;
		double remainingMass = requestedMass;
		double cellTemperature = thermalState.temperatureInCell(cell, thermalMarkerContext());
		for (int marker = particles.count() - 1; marker >= 0 && remainingSvq > 0L; marker--) {
			if (particleCellIndex(marker) != cell || particles.materialHandle(marker) != liquidHandle
					|| chemicalParcels != null && chemicalParcels.indicator(marker)) continue;
			long markerSvq = SolidVolume.cellVolumeSvqAtMost(particles.volume(marker));
			if (markerSvq == 0L) continue;
			long takeSvq = Math.min(markerSvq, remainingSvq);
			double takeMass = takeSvq == remainingSvq ? remainingMass
					: unitScale.kilograms(particles.mass(marker)) * ((double) takeSvq / markerSvq);
			double takeSolverMass = unitScale.solverMass(takeMass);
			boolean full = takeSvq == markerSvq
					&& markerSvq == SolidVolume.cellVolumeSvqAtLeast(particles.volume(marker));
			float newVolume = full ? 0.0F
					: phaseAdjustedMarkerVolume(particles.volume(marker), -takeSvq, "Freeze");
			double newMass = particles.mass(marker) - takeSolverMass;
			if (!(takeMass > 0.0) || full && Math.abs(newMass) > massTolerance(particles.mass(marker))
					|| !full && !(newMass > 0.0)) {
				throw new PhaseCandidateUnavailableException("Freeze batch mass/volume split is not representable");
			}
			ChemicalParcelStore.SplitPlan chemicalPlan = chemicalParcels == null ? null
					: chemicalParcels.planTruncate(marker, newVolume,
						SolidVolume.toCellVolume(takeSvq), full ? 0.0 : newMass, takeSolverMass);
			ChemicalMixtureRecord kept = chemicalPlan == null ? null : chemicalPlan.kept();
			LiquidEnergyColumn.Truncation thermalPlan = planThermalTruncate(
					marker, full ? 0.0 : newMass, kept, cellTemperature);
			if (chemicalPlan != null) moved.add(chemicalPlan.removed());
			if (thermalPlan != null) movedEnergy = movedEnergy.add(SignedDyadic.of(thermalPlan.removedEnergy()));
			allocations.add(new PhaseMarkerAllocation(marker, newVolume, full ? 0.0 : newMass,
					full, chemicalPlan, thermalPlan));
			remainingSvq -= takeSvq;
			remainingMass -= takeMass;
		}
		if (remainingSvq != 0L || Math.abs(remainingMass) > massTolerance(requestedMass)) {
			throw new PhaseCandidateUnavailableException("Freeze exact batch cannot be allocated to liquid markers");
		}
		return new PhaseRemovalPlan(List.copyOf(allocations),
				chemicalParcels == null ? PhaseComposition.EMPTY : moved.build(), movedEnergy.toDouble());
	}

	private PhaseRemovalPlan planGasPhaseRemoval(
			int cell, int liquidHandle, long requestedSvq, double requestedMass,
			double cellTemperature, boolean[] volatileSpecies
	) {
		ArrayList<PhaseMarkerAllocation> allocations = new ArrayList<>();
		PhaseCompositionBuilder moved = new PhaseCompositionBuilder();
		SignedDyadic movedEnergy = SignedDyadic.ZERO;
		long remainingSvq = requestedSvq;
		double remainingMass = requestedMass;
		for (int marker = markerGrid.cellMarkerTail[cell]; marker >= 0 && remainingSvq > 0L;
				marker = markerGrid.particleCellPrevious[marker]) {
			if (particles.materialHandle(marker) != liquidHandle
					|| chemicalParcels != null && chemicalParcels.indicator(marker)) continue;
			long markerSvq = SolidVolume.cellVolumeSvqAtMost(particles.volume(marker));
			if (markerSvq == 0L) continue;
			long takeSvq = Math.min(markerSvq, remainingSvq);
			if (chemicalParcels != null) {
				boolean movedSpecies = false, nonVolatile = false;
				ChemicalMixtureRecord mixture = chemicalParcels.record(marker);
				for (int entry = 0; entry < mixture.entryCount(); entry++) {
					if (volatileSpecies[mixture.speciesHandle(entry)]) movedSpecies = true;
					else nonVolatile = true;
				}
				if (!movedSpecies) continue;
				if (takeSvq == markerSvq && nonVolatile) {
					if (markerSvq <= 1L) continue;
					takeSvq = markerSvq - 1L;
				}
			}
			double takeMass = takeSvq == remainingSvq ? remainingMass
					: unitScale.kilograms(particles.mass(marker)) * ((double) takeSvq / markerSvq);
			double takeSolverMass = unitScale.solverMass(takeMass);
			boolean full = takeSvq == markerSvq
					&& markerSvq == SolidVolume.cellVolumeSvqAtLeast(particles.volume(marker));
			float newVolume = full ? 0.0F
					: phaseAdjustedMarkerVolume(particles.volume(marker), -takeSvq, "Boiling");
			double newMass = particles.mass(marker) - takeSolverMass;
			if (!(takeMass > 0.0) || full && Math.abs(newMass) > massTolerance(particles.mass(marker))
					|| !full && !(newMass > 0.0)) {
				throw new PhaseCandidateUnavailableException("Boiling batch mass/volume split is not representable");
			}
			ChemicalParcelStore.SplitPlan chemicalPlan = chemicalParcels == null ? null
					: chemicalParcels.planSelectivePhaseTruncate(marker, newVolume,
							SolidVolume.toCellVolume(takeSvq), full ? 0.0 : newMass, takeSolverMass,
							volatileSpecies);
			ChemicalMixtureRecord kept = chemicalPlan == null ? null : chemicalPlan.kept();
			LiquidEnergyColumn.Truncation thermalPlan = planThermalTruncate(
					marker, full ? 0.0 : newMass, kept, cellTemperature);
			if (chemicalPlan != null) moved.add(chemicalPlan.removed());
			if (thermalPlan != null) movedEnergy = movedEnergy.add(SignedDyadic.of(thermalPlan.removedEnergy()));
			allocations.add(new PhaseMarkerAllocation(marker, newVolume, full ? 0.0 : newMass,
					full, chemicalPlan, thermalPlan));
			remainingSvq -= takeSvq;
			remainingMass -= takeMass;
		}
		if (remainingSvq != 0L || Math.abs(remainingMass) > massTolerance(requestedMass)) {
			throw new PhaseCandidateUnavailableException("Boiling exact batch cannot be allocated to volatile liquid inventory");
		}
		return new PhaseRemovalPlan(List.copyOf(allocations),
				chemicalParcels == null ? PhaseComposition.EMPTY : moved.build(), movedEnergy.toDouble());
	}

	private List<ThermalStateFunction.InventoryPortion> liquidInventoryAfterCondensation(
			int cell, int liquidHandle, int target, double targetMass,
			ChemicalMixtureRecord condensedMixture, double appendedMass
	) {
		ArrayList<ThermalStateFunction.InventoryPortion> result = new ArrayList<>();
		for (int marker = markerGrid.cellMarkerHead[cell]; marker >= 0; marker = markerGrid.particleCellNext[marker]) {
			if (particles.materialHandle(marker) != liquidHandle) {
				throw new ThermalPhaseBlockedException("Gas phase cell contains another liquid carrier");
			}
			double mass = marker == target ? targetMass : particles.mass(marker);
			ChemicalMixtureRecord mixture = chemicalParcels == null ? null
					: marker == target ? condensedMixture : chemicalParcels.record(marker);
			result.add(liquidInventoryPortion(liquidHandle,
					unitScale.kilograms(mass), mixture));
		}
		if (target < 0) result.add(liquidInventoryPortion(liquidHandle, appendedMass, condensedMixture));
		return List.copyOf(result);
	}

	private ThermalStateFunction.InventoryPortion liquidInventoryPortion(
			int liquidHandle, double physicalMass, ChemicalMixtureRecord mixture
	) {
		return new ThermalStateFunction.InventoryPortion(
				thermalState.thermalMaterialHandleForFluid(liquidHandle), physicalMass,
				mixture == null ? Map.of() : mixture.stableAmounts(chemicalParcels.registry()));
	}

	private void commitJointFreeze(int cell, JointPhasePlan plan) {
		SignedDyadic inventoryBefore = exactInternalEnergy();
		for (PhaseMarkerAllocation allocation : plan.removal().allocations()) {
			if (allocation.fullRemoval()) {
				removeParticleInternalPhase(allocation.marker());
			} else {
				if (chemicalParcels != null) {
					chemicalParcels.markDirtyCell(cell);
					chemicalParcels.commitTruncate(allocation.marker(), allocation.chemicalPlan());
				}
				particles.setVolume(allocation.marker(), allocation.newVolume());
				particles.setMass(allocation.marker(), allocation.newMass());
				thermalState.commitInternalTruncate(allocation.marker(), allocation.thermalPlan());
			}
		}
		thermalState.addCoherentInternal(plan.coherentTransfer());
		commitPendingPhaseVolume(cell, plan.signedVolumeSvq());
		refreshCoherentOccupancy();
		gridDirty = true;
		thermalState.repartitionCellEnergyConserving(cell, thermalMarkerContext(), inventoryBefore);
	}

	private void commitJointMelt(
			int cell, int liquidHandle, ThermalPhaseTransition transition, JointPhasePlan plan
	) {
		long removedSvq = plan.coherentTransfer().occupiedVolumeSvq();
		long addedSvq = Math.multiplyExact(plan.batches(), transition.upperVolumeSvqPerBatch());
		SignedDyadic inventoryBefore = exactInternalEnergy();
		CoherentPhaseStore.Group removed = thermalState.takeCoherentInternal(
				cell, transition.lowerEnergyMaterialId(), removedSvq);
		int x = cell % sizeX, yz = cell / sizeX, y = yz % sizeY, z = yz / sizeY;
		int marker = particles.count();
		spawnParticle(x, y, z, plan.meltSlot(), SolidVolume.toCellVolume(addedSvq), liquidHandle,
				unitScale.solverMass(removed.mass()), plan.meltedMixture(), false, false);
		thermalState.addLiquidEnergy(marker, removed.internalEnergy());
		commitPendingPhaseVolume(cell, plan.signedVolumeSvq());
		refreshCoherentOccupancy();
		gridDirty = true;
		thermalState.repartitionCellEnergyConserving(cell, thermalMarkerContext(), inventoryBefore);
	}

	private int fluidMaterialHandle(String stableId) {
		for (int handle = 0; handle < materialRegistry.size(); handle++) {
			if (materialRegistry.material(handle).id().equals(stableId)) return handle;
		}
		throw new ThermalPhaseBlockedException("Thermal transition liquid carrier is not bound");
	}

	private static double massTolerance(double mass) {
		return Math.max(1.0E-12, Math.ulp(mass) * 32.0);
	}

	private static float phaseAdjustedMarkerVolume(float volume, long signedSvq, String operation) {
		try {
			return SolidVolume.addCellVolumeSvqExact(volume, signedSvq);
		} catch (IllegalArgumentException unrepresentableTail) {
			throw new PhaseCandidateUnavailableException(
					operation + " SVQ is not exactly representable in the marker volume", unrepresentableTail);
		}
	}

	public record ThermalPhaseStats(int operations, int frozenCells, int meltedCells, long signedBatches) { }
	public record GasThermalPhaseStats(int operations, int boiledCells, int condensedCells) { }
	private record PhaseCellInventory(
			long lowerVolumeSvq, long upperVolumeSvq, double lowerMass, double upperMass, double internalEnergy,
			PhaseComposition lowerComposition, PhaseComposition upperComposition,
			long freezableUpperSvq, double freezableUpperMass
	) {
		private PhaseCellInventory(
				long lowerVolumeSvq, long upperVolumeSvq,
				double lowerMass, double upperMass, double internalEnergy
		) {
			this(lowerVolumeSvq, upperVolumeSvq, lowerMass, upperMass, internalEnergy,
					PhaseComposition.EMPTY, PhaseComposition.EMPTY, upperVolumeSvq, upperMass);
		}
	}
	private record PhaseMarkerAllocation(
			int marker, float newVolume, double newMass, boolean fullRemoval,
			ChemicalParcelStore.SplitPlan chemicalPlan, LiquidEnergyColumn.Truncation thermalPlan
	) {
		private PhaseMarkerAllocation(int marker, float newVolume, double newMass, boolean fullRemoval) {
			this(marker, newVolume, newMass, fullRemoval, null, null);
		}
	}
	private record PhaseRemovalPlan(
			List<PhaseMarkerAllocation> allocations, PhaseComposition movedComposition, double movedEnergy
	) {
		private boolean carrierTopologyChanged() {
			for (PhaseMarkerAllocation allocation : allocations) if (allocation.fullRemoval()) return true;
			return false;
		}
	}
	private record GasBoilPlan(
			long batches, long removedSvq, PhaseRemovalPlan removal, GasInventory batch, double candidateGasEnergy,
			GasLiquidPhaseModel.Direction directionAfter
	) { }
	private record GasCondensePlan(
			long batches, GasInventory removedGas, double candidateGasEnergy, int target,
			ChemicalMixtureRecord condensedMixture, float targetVolume, double targetMass, int slot,
			long addedSvq, double movedMass, GasLiquidPhaseModel.Direction directionAfter
	) { }
	private record GasPhaseDirectionKey(String transitionId, int node, long liquidTemperatureBits) { }
	private record JointPhasePlan(
			long batches, boolean freezing, PhaseRemovalPlan removal,
			CoherentPhaseStore.Group coherentTransfer, ChemicalMixtureRecord meltedMixture,
			int meltSlot, double temperatureAfterK, long signedVolumeSvq
	) { }

	/** Exact species quanta plus the independently carried mass residual across a condensed phase boundary. */
	private record PhaseComposition(Map<String, Long> amounts, double massResidual) {
		private static final PhaseComposition EMPTY = new PhaseComposition(Map.of(), 0.0);

		private PhaseComposition {
			if (amounts == null || amounts.size() > ChemicalLimits.MAX_SPECIES_PER_PARCEL
					|| !Double.isFinite(massResidual)) {
				throw new IllegalArgumentException("Condensed phase composition is invalid");
			}
			TreeMap<String, Long> canonical = new TreeMap<>();
			long total = 0L;
			for (Map.Entry<String, Long> entry : amounts.entrySet()) {
				if (entry.getKey() == null || entry.getValue() == null || entry.getValue() <= 0L) {
					throw new IllegalArgumentException("Condensed phase species amount is invalid");
				}
				total = Math.addExact(total, entry.getValue());
				canonical.put(entry.getKey(), entry.getValue());
			}
			if (total > ChemicalLimits.MAX_REGION_TOTAL_QUANTA) {
				throw new IllegalArgumentException("Condensed phase composition is over budget");
			}
			amounts = Map.copyOf(canonical);
		}

		private boolean isEmpty() { return amounts.isEmpty(); }

		private PhaseComposition plus(PhaseComposition other) {
			TreeMap<String, Long> combined = new TreeMap<>(amounts);
			for (Map.Entry<String, Long> entry : other.amounts.entrySet()) {
				combined.merge(entry.getKey(), entry.getValue(), Math::addExact);
			}
			double residual = massResidual + other.massResidual;
			if (!Double.isFinite(residual)) throw new ArithmeticException("Condensed phase residual overflow");
			return combined.isEmpty() && residual == 0.0 ? EMPTY : new PhaseComposition(combined, residual);
		}

		private PhaseComposition minus(PhaseComposition other) {
			TreeMap<String, Long> remaining = new TreeMap<>(amounts);
			for (Map.Entry<String, Long> entry : other.amounts.entrySet()) {
				long current = remaining.getOrDefault(entry.getKey(), 0L);
				if (current < entry.getValue()) {
					throw new ThermalPhaseBlockedException("Condensed phase species subtraction underflow");
				}
				long result = current - entry.getValue();
				if (result == 0L) remaining.remove(entry.getKey());
				else remaining.put(entry.getKey(), result);
			}
			double residual = massResidual - other.massResidual;
			if (!Double.isFinite(residual)) throw new ArithmeticException("Condensed phase residual overflow");
			if (remaining.isEmpty()) {
				if (Math.abs(residual) > massTolerance(Math.max(Math.abs(massResidual), Math.abs(other.massResidual)))) {
					throw new ThermalPhaseBlockedException("Empty condensed phase retains a mass residual");
				}
				return EMPTY;
			}
			return new PhaseComposition(remaining, residual);
		}

		private CoherentPhaseStore.Composition toCoherent() {
			if (isEmpty()) return null;
			ArrayList<CoherentPhaseStore.Component> components = new ArrayList<>(amounts.size());
			for (Map.Entry<String, Long> entry : new TreeMap<>(amounts).entrySet()) {
				components.add(new CoherentPhaseStore.Component(entry.getKey(), entry.getValue()));
			}
			return new CoherentPhaseStore.Composition(
					components, Double.doubleToRawLongBits(massResidual));
		}

		private ChemicalMixtureRecord toMixture(ChemicalRegistry registry) {
			if (isEmpty()) throw new ThermalPhaseBlockedException("Chemical melt produced an empty liquid parcel");
			return ChemicalMixtureRecord.fromStableAmounts(registry, amounts, massResidual);
		}

		private static PhaseComposition fromCoherent(CoherentPhaseStore.Composition composition) {
			return composition == null ? EMPTY
					: new PhaseComposition(composition.stableAmounts(), composition.massResidual());
		}

		private static PhaseComposition fromMixture(ChemicalMixtureRecord mixture, ChemicalRegistry registry) {
			if (mixture == null || registry == null) {
				throw new IllegalArgumentException("Liquid phase composition is missing");
			}
			return new PhaseComposition(mixture.stableAmounts(registry), mixture.massResidualKilograms());
		}

		private static PhaseComposition fromGas(GasComponentAmount component) {
			if (component == null) throw new IllegalArgumentException("Gas phase composition is missing");
			TreeMap<String, Long> amounts = new TreeMap<>();
			for (GasComponentAmount.SpeciesAmount amount : component.species()) {
				amounts.put(amount.speciesId(), amount.amountQuanta());
			}
			double residual = component.massResidual();
			return amounts.isEmpty() && residual == 0.0 ? EMPTY : new PhaseComposition(amounts, residual);
		}

		private ExactRational exactSpeciesMass(ChemicalRegistry registry) {
			ExactRational result = ExactRational.ZERO;
			for (Map.Entry<String, Long> entry : amounts.entrySet()) {
				result = result.add(registry.species(registry.speciesHandle(entry.getKey())).massKilogramsPerAu()
						.multiply(entry.getValue()).divide(ChemicalLimits.QUANTA_PER_AU));
			}
			return result;
		}
	}

	private final class PhaseCompositionBuilder {
		private final TreeMap<String, Long> amounts = new TreeMap<>();
		private double massResidual;

		private void add(ChemicalMixtureRecord record) {
			if (record == null || chemicalParcels == null) {
				throw new IllegalArgumentException("Chemical condensed phase record is missing");
			}
			for (Map.Entry<String, Long> entry : record.stableAmounts(chemicalParcels.registry()).entrySet()) {
				amounts.merge(entry.getKey(), entry.getValue(), Math::addExact);
			}
			massResidual += record.massResidualKilograms();
			if (!Double.isFinite(massResidual)) throw new ArithmeticException("Condensed phase residual overflow");
		}

		private PhaseComposition build() {
			return amounts.isEmpty() && massResidual == 0.0
					? PhaseComposition.EMPTY : new PhaseComposition(amounts, massResidual);
		}
	}
	public static final class ThermalPhaseBlockedException extends IllegalStateException {
		public ThermalPhaseBlockedException(String message) { super(message); }
		ThermalPhaseBlockedException(String message, Throwable cause) { super(message, cause); }
	}
	private static final class PhaseCandidateUnavailableException extends IllegalArgumentException {
		private PhaseCandidateUnavailableException(String message) { super(message); }
		private PhaseCandidateUnavailableException(String message, Throwable cause) { super(message, cause); }
	}
	public static final class ThermalTransportBlockedException extends IllegalStateException {
		ThermalTransportBlockedException(String message, Throwable cause) { super(message, cause); }
	}
	public static final class GasThermalBlockedException extends IllegalStateException {
		GasThermalBlockedException(String message, Throwable cause) { super(message, cause); }
	}

	ThermalCarrierContext thermalMarkerContext() { return thermalMarkers; }

	private ThermalCarrierContext createThermalMarkerContext() {
		return new ThermalCarrierContext() {
			@Override public int markerCount() { return particles.count(); }
			@Override public int cell(int marker) { return particleCellIndex(marker); }
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

	private GranularDepositStore.Group thermalDepositGroup(GranularEnergyColumn.DepositKey key) {
		if (granularState == null || key == null) throw new IllegalArgumentException("Granular deposit state is unavailable");
		GranularDepositStore.Group group = granularState.deposits.matchingGroup(key.cell(), key.materialHandle(),
				candidate -> candidate.solidCompositionHandle() == key.compositionHandle());
		if (group == null) throw new IllegalArgumentException("Unknown granular thermal deposit identity");
		return group;
	}

	double thermalMarkerTemperature(int marker) {
		return thermalState.temperature(marker, thermalMarkerContext());
	}

	private FlipParticleReseeder.ThermalAdapter thermalReseedAdapter() {
		return thermalState.reseedAdapter(thermalMarkerContext());
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

	private double thermalOccupiedFaceFraction(int cell, int direction) {
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

	private double thermalOpenFaceFraction(int x, int y, int z, int direction) {
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

	private void clearThermalBinding() {
		thermalState = null;
		thermalBuoyancySum = null;
		thermalBuoyancyVolume = null;
		thermalBoundaryTemperatureK = null;
		Arrays.fill(pendingPhaseCellVolume, SignedDyadic.ZERO);
		if (granularState == null) {
			granularCoupling = null; granularNextCoupling = null; pressureGranularCoupling = null;
		}
	}

	private void refreshCoherentOccupancy() {
		if (granularCoupling == null || granularNextCoupling == null) return;
		long[] fixed = new long[cellCount];
		if (thermalState != null) for (CoherentPhaseStore.Group group : thermalState.coherentPhases().snapshot()) {
			fixed[group.cell()] = Math.addExact(fixed[group.cell()], group.occupiedVolumeSvq());
		}
		granularCoupling.setFixedCellSvq(fixed);
		granularNextCoupling.setFixedCellSvq(fixed);
	}

	private void bindSolidCompositionPoolIfReady() {
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
	public void setMarkerChemical(int marker, Map<String, Long> stableAmounts, boolean indicator) {
		if (marker < 0 || marker >= particles.count()) throw new IllegalArgumentException("Unknown marker " + marker);
		ChemicalMixtureRecord zeroResidual = ChemicalMixtureRecord.fromStableAmounts(
				chemicalParcels().registry(), stableAmounts, 0.0);
		ChemicalMixtureRecord record = ChemicalMixtureRecord.fromStableAmounts(
				chemicalParcels.registry(), stableAmounts,
				unitScale.kilograms(particles.mass(marker))
						- zeroResidual.exactSpeciesMassKilograms(chemicalParcels.registry()).toDouble());
		chemicalParcels.requireCompatible(record,
				materialRegistry.material(particles.materialHandle(marker)).id(), indicator);
		if (thermalState != null) thermalState.requireReactionState(
				marker, record, particles.mass(marker), thermalMarkerContext());
		chemicalParcels.assign(marker, record, indicator);
		chemicalParcels.markDirtyCell(particleCellIndex(marker));
	}

	/** Resolves a stable material id to the runtime handle of the bound registry. */
	public int materialHandle(String materialId) {
		if (materialRegistry == null) throw new IllegalStateException("Material registry is not bound");
		return materialRegistry.handle(materialId);
	}


	/** Replaces this domain from one validated, immutable in-memory marker state. */
	public void restoreParticleState(FlipParticleState state) {
		if (state == null) throw new IllegalArgumentException("Particle state must not be null");
		state.requireCompatible(sizeX, sizeY, sizeZ, config.cellSize());
		int restoredCount = state.particleCount();
		if (restoredCount > maximumParticleCount) throw new IllegalArgumentException("Particle state exceeds domain marker budget");
		ensureParticleCapacity(restoredCount);
		float[] positionVolume = state.positionVolume();
		float[] particleVelocities = state.particleVelocities();
		byte[] collisionMasks = state.collisionMicroMasks();
		geometry.cutCells.restoreMicroMasks(collisionMasks);
		for (int i = 0; i < cellCount; i++) geometry.solid[i] = Byte.toUnsignedInt(collisionMasks[i]) == 0xff;
		for (int particle = 0; particle < restoredCount; particle++) {
			int positionBase = particle * 4, velocityBase = particle * 3;
			particles.setPositionX(particle, positionVolume[positionBase]);
			particles.setPositionY(particle, positionVolume[positionBase + 1]);
			particles.setPositionZ(particle, positionVolume[positionBase + 2]);
			particles.setVolume(particle, positionVolume[positionBase + 3]);
			particles.setVelocityX(particle, particleVelocities[velocityBase]);
			particles.setVelocityY(particle, particleVelocities[velocityBase + 1]);
			particles.setVelocityZ(particle, particleVelocities[velocityBase + 2]);
			setDefaultMaterialState(particle);
		}
		coordinator.adoptWholesaleCount(
				CarrierMutationPlan.wholesaleCount(carrierMutationRevision(), particles.count()), restoredCount);
		clearThermalBinding();
		materialRegistry = null;
		chemicalParcels = null;
		chemicalPreviousCells = null;
		chemicalMovingMarkers = null;
		Arrays.fill(projection.pressure, 0.0F);
		Arrays.fill(projection.divergence, 0.0F);
		pressureAnchor = -1;
		lastPressureIterations = 0;
		lastPressureResidual = 0.0F;
		gridDirty = true;
	}

	/** Replaces this domain from a validated multi-material CPU handoff without changing world state. */
	public void restoreMaterialParticleState(FlipMaterialParticleState state) {
		if (state == null) throw new IllegalArgumentException("Material particle state must not be null");
		state.requireCompatible(sizeX, sizeY, sizeZ, config.cellSize());
		if (state.particleCount() > maximumParticleCount) {
			throw new IllegalArgumentException("Material particle state exceeds domain marker budget");
		}
		FluidMaterialRegistry registry = new FluidMaterialRegistry(state.materials());
		double defaultDensity = registry.material(0).referenceDensity();
		double tolerance = Math.max(1.0E-9, Math.abs(config.density()) * 1.0E-12);
		if (Math.abs(defaultDensity - config.density()) > tolerance) {
			throw new IllegalArgumentException("Material handle 0 must match the domain default density");
		}
		restoreParticleState(state.particles());
		double[] restoredMass = state.particleMass();
		int[] restoredMaterial = state.materialPaletteIndex();
		for (int particle = 0; particle < particles.count(); particle++) {
			particles.setMass(particle, restoredMass[particle]);
			particles.setMaterialHandle(particle, restoredMaterial[particle]);
			particles.setPhaseLabel(particle, registry.phaseHandle(restoredMaterial[particle]));
		}
		materialRegistry = registry;
		gridDirty = true;
	}

	/** Restores a validated chemical handoff against the authoritative world registry. */
	public void restoreChemicalParticleState(FlipChemicalParticleState state, ChemicalRegistry registry) {
		if (state == null || registry == null) throw new IllegalArgumentException("Chemical restore state/registry is missing");
		if (!state.registryFingerprint().equals(registry.fingerprint())
				|| !state.speciesPalette().equals(registry.species().stream()
				.map(cc.ecisr.mmm.chemistry.ChemicalSpecies::id).toList())) {
			throw new IllegalArgumentException("Chemical handoff registry identity mismatch");
		}
		restoreMaterialParticleState(state.materials());
		for (FluidMaterial material : materialRegistry.materials()) registry.phaseHandleForMaterial(material.id());
		ChemicalParcelStore restored = new ChemicalParcelStore(
				registry, unitScale, maximumParticleCount, cellCount);
		int[] tableIndex = state.copyMarkerMixtureIndex();
		byte[] indicator = state.copyIndicator();
		for (int marker = 0; marker < particles.count(); marker++) {
			ChemicalMixtureRecord mixture = state.mixtures().get(tableIndex[marker]).toRuntime(registry);
			restored.requireCompatible(mixture, materialRegistry.material(particles.materialHandle(marker)).id(), indicator[marker] != 0);
			restored.assign(marker, mixture, indicator[marker] != 0);
		}
		if (!Arrays.equals(restored.speciesTotals(particles.count()), state.copySpeciesTotals())) {
			throw new IllegalArgumentException("Restored chemical species ledger mismatch");
		}
		chemicalParcels = restored;
		chemicalParcels.restoreAuditLedgers(state.externalSource(), state.escapedSink());
		chemicalPreviousCells = new int[maximumParticleCount];
		chemicalMovingMarkers = new int[maximumParticleCount];
	}

	/** Exports an immutable CPU marker/velocity/solid handoff without serializing reconstructed density. */
	public FlipParticleState exportParticleState() { return carriers.exportParticleState(); }

	/** Exports exact multi-material marker state; runtime handles are replaced by the stable ordered palette. */
	public FlipMaterialParticleState exportMaterialParticleState() { return carriers.exportMaterialParticleState(); }

	/** Sets collision occupancy and returns the exact particle volume displaced. */
	public float setSolid(int x, int y, int z, boolean value) {
		return setSolidMicroMask(x, y, z, value ? FlipCutCellGeometry.FULL_MASK : 0);
	}

	/**
	 * Replaces one runtime 2x2x2 cut-cell mask and returns exact marker volume
	 * whose centers became occupied or exceeds the new open-cell capacity. This
	 * geometry is part of the authoritative in-memory/checkpoint collision state.
	 */
	public float setSolidMicroMask(int x, int y, int z, int mask) {
		ChemicalTransferLedger.Builder displaced = chemicalParcels == null ? null
				: ChemicalTransferLedger.builder(chemicalParcels.registry());
		float volume = setSolidMicroMaskInternal(x, y, z, mask, displaced, null, null);
		if (displaced != null) chemicalParcels.recordEscapedSink(displaced.build());
		return volume;
	}

	/** Exports canonical stable-ID chemical state; runtime mixture handles never cross this boundary. */
	public FlipChemicalParticleState exportChemicalParticleState() { return carriers.exportChemicalParticleState(); }

	/** Collision edit that returns the complete parcel ledger for conservative nearby restoration. */
	public ChemicalDisplacement setSolidMicroMaskChemical(int x, int y, int z, int mask) {
		if (chemicalParcels == null) throw new IllegalStateException("Chemical collision transfer requires chemistry");
		ChemicalTransferLedger.Builder displaced = ChemicalTransferLedger.builder(chemicalParcels.registry());
		RemovedThermalEnergy energy = new RemovedThermalEnergy();
		float volume = setSolidMicroMaskInternal(x, y, z, mask, displaced, null, energy);
		return new ChemicalDisplacement(volume, displaced.build(), energy.value().toDouble());
	}

	/** Collision edit that returns every displaced material ledger plus its independently carried U. */
	public MaterialDisplacement setSolidMicroMaskMaterial(int x, int y, int z, int mask) {
		if (materialRegistry == null || chemicalParcels != null) {
			throw new IllegalStateException("Material collision transfer requires non-chemical material state");
		}
		RemovedMaterialLedger material = new RemovedMaterialLedger(materialRegistry.size());
		RemovedThermalEnergy energy = new RemovedThermalEnergy();
		float volume = setSolidMicroMaskInternal(x, y, z, mask, null, material, energy);
		return new MaterialDisplacement(volume, material.build(), energy.value().toDouble());
	}

	/**
	 * Preflights a complete world collision refresh before any solver owner is mutated. Coherent
	 * solids reject the whole batch; granular parcels/deposits touching several edited cells are
	 * selected once and their composition/thermal sidecars are validated as one removal.
	 */
	public CollisionEditPlan planCollisionEdits(List<CollisionEdit> edits) {
		if (edits == null || edits.isEmpty()) throw new IllegalArgumentException("Collision edit batch is missing or empty");
		ensureGrid();
		ArrayList<CollisionEdit> canonical = new ArrayList<>(edits.size());
		int[] previousMasks = new int[edits.size()];
		boolean[] granularCells = granularState == null ? null : new boolean[cellCount];
		Set<Integer> uniqueCells = new HashSet<>();
		for (int editIndex = 0; editIndex < edits.size(); editIndex++) {
			CollisionEdit edit = edits.get(editIndex);
			if (edit == null || edit.mask() < 0 || edit.mask() > FlipCutCellGeometry.FULL_MASK) {
				throw new IllegalArgumentException("Collision edit is invalid");
			}
			int cell = cellIndexChecked(edit.x(), edit.y(), edit.z());
			if (!uniqueCells.add(cell)) throw new IllegalArgumentException("Collision batch edits one cell twice");
			int previous = geometry.cutCells.mask(cell);
			if (previous == edit.mask()) throw new IllegalArgumentException("Collision batch contains a no-op edit");
			previousMasks[editIndex] = previous;
			canonical.add(edit);
			if ((edit.mask() & ~previous) == 0) continue;
			if (thermalState != null && thermalState.coherentPhases().cellOccupiedSvq(cell) > 0L) {
				throw new CollisionEditBlockedException(
						"Collision edit intersects authoritative coherent phase inventory", null);
			}
			if (granularCells != null && (granularCoupling.solidSvq(cell) > 0L
					|| granularState.deposits.cellSolidVolumeSvq(cell) > 0L)) granularCells[cell] = true;
		}
		GranularCollisionPlan granularPlan;
		try {
			granularPlan = granularCells == null ? null : planGranularCollision(granularCells);
		} catch (RuntimeException failure) {
			throw new CollisionEditBlockedException("Granular collision displacement could not be planned", failure);
		}
		return new CollisionEditPlan(this, List.copyOf(canonical), previousMasks, particles.count(),
				Double.doubleToRawLongBits(totalVolumeExact()), Double.doubleToRawLongBits(totalMass()), granularPlan);
	}

	/** Commits the already validated cross-owner prelude; the supplied masks may then be applied without refusal. */
	public void beginCollisionEdits(CollisionEditPlan plan) {
		if (plan == null || plan.owner != this || plan.consumed || activeCollisionPlan != null) {
			throw new IllegalArgumentException("Collision edit plan is missing, foreign or already consumed");
		}
		if (particles.count() != plan.planMarkerCount
				|| Double.doubleToRawLongBits(totalVolumeExact()) != plan.totalVolumeBits
				|| Double.doubleToRawLongBits(totalMass()) != plan.totalMassBits) {
			throw new IllegalStateException("Liquid inventory changed after collision planning");
		}
		for (int index = 0; index < plan.edits.size(); index++) {
			CollisionEdit edit = plan.edits.get(index);
			if (solidMicroMask(edit.x(), edit.y(), edit.z()) != plan.previousMasks[index]) {
				throw new IllegalStateException("Collision geometry changed after planning");
			}
		}
		if (plan.granular != null) try {
			commitGranularCollision(plan.granular);
		} catch (RuntimeException failure) {
			throw new CollisionEditBlockedException("Granular collision displacement could not be committed", failure);
		}
		plan.consumed = true;
		activeCollisionPlan = plan;
		activeCollisionEditIndex = 0;
	}

	private float setSolidMicroMaskInternal(
			int x, int y, int z, int mask, ChemicalTransferLedger.Builder chemicalDisplacement,
			RemovedMaterialLedger materialDisplacement, RemovedThermalEnergy thermalDisplacement
	) {
		int index = cellIndexChecked(x, y, z);
		boolean plannedBatch = activeCollisionPlan != null;
		if (plannedBatch) {
			CollisionEdit expected = activeCollisionPlan.edits.get(activeCollisionEditIndex);
			if (expected.x() != x || expected.y() != y || expected.z() != z || expected.mask() != mask) {
				throw new IllegalStateException("Collision edits differ from their committed batch order");
			}
		}
		int previous = geometry.cutCells.mask(index);
		if (previous == mask) return 0.0F;
		int newlyOccupied = mask & ~previous;
		if (newlyOccupied != 0 && granularCoupling != null && !plannedBatch) {
			ensureGrid();
			if (thermalState != null && thermalState.coherentPhases().cellOccupiedSvq(index) > 0L) {
				throw new IllegalStateException("Collision edit intersects authoritative coherent phase inventory");
			}
			if (granularState != null
					&& (granularCoupling.solidSvq(index) > 0L || granularState.deposits.cellSolidVolumeSvq(index) > 0L)) {
				try { displaceGranularForCollisionCell(index); }
				catch (RuntimeException failure) {
					throw new CollisionEditBlockedException(
							"Granular collision displacement could not be committed", failure);
				}
			}
		}
		geometry.cutCells.replaceMask(index, mask);
		geometry.solid[index] = mask == FlipCutCellGeometry.FULL_MASK;
		projection.pressure[index] = 0.0F;
		float displaced = newlyOccupied == 0 ? 0.0F
				: removeParticlesInMicroMask(x, y, z, newlyOccupied, chemicalDisplacement,
						materialDisplacement, thermalDisplacement);
		float excess = Math.max(0.0F, exactVolumeInCell(x, y, z) - effectiveOpenCellVolume(index));
		if (excess > 1.0E-6F) displaced += removeParticlesInCell(x, y, z, excess, chemicalDisplacement,
				materialDisplacement, thermalDisplacement);
		volume[index] = 0.0F;
		if (granularDepositDynamics != null) granularDepositDynamics.disturbCell(index);
		gridDirty = true;
		if (plannedBatch && ++activeCollisionEditIndex == activeCollisionPlan.edits.size()) {
			activeCollisionPlan = null;
			activeCollisionEditIndex = 0;
		}
		return displaced;
	}

	/**
	 * Minecraft collision edits win. Every active parcel whose exact 8-cell stencil touches the
	 * edited cell, plus every deposit group in that cell, is planned into the escaped sink before
	 * runtime geometry changes. Whole entities are used so reactive composition ownership is never guessed or split.
	 */
	private void displaceGranularForCollisionCell(int cell) {
		boolean[] cells = new boolean[cellCount];
		cells[cell] = true;
		GranularCollisionPlan plan = planGranularCollision(cells);
		if (plan == null) throw new IllegalStateException("Granular collision occupancy has no removable authoritative owner");
		commitGranularCollision(plan);
	}

	private GranularCollisionPlan planGranularCollision(boolean[] affectedCells) {
		exportGranularState();
		granularCoupling.rebuild(granularState.parcels, granularState.deposits);
		boolean[] selectedParcels = new boolean[granularState.parcels.count()];
		int selectedCount = 0;
		for (int parcel = 0; parcel < granularState.parcels.count(); parcel++) {
			GranularParcelStore.Parcel state = granularState.parcels.parcel(parcel);
			for (int cell = 0; cell < affectedCells.length; cell++) {
				if (!affectedCells[cell] || granularCoupling.parcelShareAtCell(
						parcelPosition(state), state.solidVolumeSvq(), cell) <= 0L) continue;
				selectedParcels[parcel] = true;
				selectedCount++;
				break;
			}
		}
		boolean[] selectedDepositCells = new boolean[cellCount];
		boolean anyDeposit = false;
		for (int cell = 0; cell < cellCount; cell++) if (affectedCells[cell]
				&& granularState.deposits.cellSolidVolumeSvq(cell) > 0L) {
			selectedDepositCells[cell] = true;
			anyDeposit = true;
		}
		if (selectedCount == 0 && !anyDeposit) return null;
		GranularAuditLedger removed = GranularAuditLedger.captureSelected(
				granularState.registry, granularState.parcels, parcel -> selectedParcels[parcel],
				granularState.deposits, selectedCell -> selectedDepositCells[selectedCell], granularState.compositions);
		GranularAuditLedger sinkAfter = granularState.escapedSink.plus(removed);
		ArrayList<CollisionDeposit> depositGroups = new ArrayList<>();
		if (anyDeposit) granularState.deposits.forEachGroup((groupCell, porosity, material, composition, svq, mass) -> {
			if (selectedDepositCells[groupCell]) depositGroups.add(new CollisionDeposit(groupCell,
					new GranularDepositStore.Group(material, composition, svq, mass)));
		});
		if (thermalState != null) {
			for (int parcel = 0; parcel < selectedParcels.length; parcel++) if (selectedParcels[parcel]) {
				GranularParcelStore.Parcel state = granularState.parcels.parcel(parcel);
				thermalState.previewActiveTake(parcel, state.solidVolumeSvq(), state.solidVolumeSvq());
			}
			for (CollisionDeposit deposit : depositGroups) {
				GranularDepositStore.Group group = deposit.group();
				thermalState.previewDepositTake(new GranularEnergyColumn.DepositKey(
						deposit.cell(), group.materialHandle(), group.solidCompositionHandle()),
						group.solidVolumeSvq(), group.solidVolumeSvq());
			}
		}

		long plannedSvq = 0L;
		double plannedMass = 0.0;
		for (int parcel = 0; parcel < selectedParcels.length; parcel++) if (selectedParcels[parcel]) {
			GranularParcelStore.Parcel state = granularState.parcels.parcel(parcel);
			plannedSvq = Math.addExact(plannedSvq, state.solidVolumeSvq());
			plannedMass += state.mass();
		}
		for (CollisionDeposit deposit : depositGroups) {
			plannedSvq = Math.addExact(plannedSvq, deposit.group().solidVolumeSvq());
			plannedMass += deposit.group().mass();
		}
		long auditSvq = 0L;
		double auditMass = 0.0;
		for (GranularAuditLedger.Entry entry : removed.entries()) {
			auditSvq = Math.addExact(auditSvq, entry.solidVolumeSvq());
			auditMass += entry.mass();
		}
		if (auditSvq != plannedSvq || !massClose(auditMass, plannedMass)) {
			throw new IllegalStateException("Granular collision plan differs from its preflight audit");
		}
		return new GranularCollisionPlan(selectedParcels, List.copyOf(depositGroups), sinkAfter,
				affectedCells.clone(), granularState.parcels.count(), granularState.inventory.totalSolidVolumeSvq(),
				Double.doubleToRawLongBits(granularState.inventory.totalMass()));
	}

	private void commitGranularCollision(GranularCollisionPlan plan) {
		if (granularState.parcels.count() != plan.parcelCount()
				|| granularState.inventory.totalSolidVolumeSvq() != plan.totalSvq()
				|| Double.doubleToRawLongBits(granularState.inventory.totalMass()) != plan.totalMassBits()) {
			throw new IllegalStateException("Granular inventory changed after collision planning");
		}
		for (int parcel = plan.selectedParcels().length - 1; parcel >= 0; parcel--) if (plan.selectedParcels()[parcel]) {
			GranularParcelStore.Parcel state = granularState.parcels.removeSwap(parcel);
			if (thermalState != null) thermalState.commitExternalActiveTake(
					parcel, state.solidVolumeSvq(), state.solidVolumeSvq());
			if (state.solidCompositionHandle() != 0) granularState.compositions.release(state.solidCompositionHandle());
		}
		for (CollisionDeposit deposit : plan.depositGroups()) {
			GranularDepositStore.Group group = deposit.group();
			GranularDepositStore.SolidPortion taken = granularState.deposits.take(
					deposit.cell(), group.materialHandle(), group.solidCompositionHandle(), group.solidVolumeSvq());
			if (thermalState != null) thermalState.commitExternalDepositTake(new GranularEnergyColumn.DepositKey(
					deposit.cell(), group.materialHandle(), group.solidCompositionHandle()),
					group.solidVolumeSvq(), group.solidVolumeSvq());
			if (taken.solidCompositionHandle() != 0) granularState.compositions.release(taken.solidCompositionHandle());
		}
		granularState.escapedSink = plan.sinkAfter();
		granularCoupling.rebuild(granularState.parcels, granularState.deposits);
		pressureGranularCoupling = hasGranularInventory() ? granularCoupling : null;
		for (int cell = 0; cell < plan.affectedCells().length; cell++) {
			if (plan.affectedCells()[cell]) markGranularExternalChange(cell);
		}
	}

	public boolean isSolid(int x, int y, int z) { return geometry.solid[cellIndexChecked(x, y, z)]; }
	public int solidMicroMask(int x, int y, int z) { return geometry.cutCells.mask(cellIndexChecked(x, y, z)); }
	public float solidVolumeFraction(int x, int y, int z) {
		return 1.0F - geometry.cutCells.openCellVolume(cellIndexChecked(x, y, z));
	}
	public float openFaceFractionX(int x, int y, int z) { return geometry.cutCells.xFaceOpen(x, y, z); }
	public float openFaceFractionY(int x, int y, int z) { return geometry.cutCells.yFaceOpen(x, y, z); }
	public float openFaceFractionZ(int x, int y, int z) { return geometry.cutCells.zFaceOpen(x, y, z); }

	public void setVolume(int x, int y, int z, float value) {
		if (granularCoupling != null) ensureGrid();
		int index = cellIndexChecked(x, y, z);
		float requested = clamp01(value);
		if (geometry.solid[index] && requested > 0.0F) throw new IllegalStateException("Cannot place fluid in a solid cell");
		float clamped = Math.min(requested, effectiveOpenCellVolume(index));
		int existingParticles = particleCountInCell(x, y, z);
		int requiredParticles = particlesRequired(clamped);
		if ((long) particles.count() - existingParticles + requiredParticles > maximumParticleCount) {
			throw new ParticleBudgetExceededException(requiredParticles, maximumParticleCount - particles.count() + existingParticles);
		}
		removeParticlesInCell(x, y, z, Float.POSITIVE_INFINITY);
		spawnCellVolume(x, y, z, clamped);
		gridDirty = true;
	}

	public float volume(int x, int y, int z) {
		ensureGrid();
		return volume[cellIndexChecked(x, y, z)];
	}

	public float pressure(int x, int y, int z) {
		int cell = cellIndexChecked(x, y, z);
		return isFluid(cell) ? pressureReference + projection.pressure[cell] : projection.pressure[cell];
	}

	/** Package-private test accessor for the free-surface pressure jump {@code sigma * kappa} at a cell. */
	float surfaceTensionPressureAt(int x, int y, int z) { return tension.surfaceTensionPressure[cellIndexChecked(x, y, z)]; }

	public float totalVolume() { return metrics.totalVolume(); }

	/** Exact double sum of the float marker-volume truth, in stable marker ordinal order. */
	public double totalVolumeExact() { return metrics.totalVolumeExact(); }

	/** Total open cell volume across the domain; global capacity for exact bucket insertion. */
	public float totalOpenVolume() { return metrics.totalOpenVolume(); }

	public double totalOpenVolumeExact() { return metrics.totalOpenVolumeExact(); }

	double totalGeometricOpenVolumeExact() {
		double result = 0.0;
		for (int cell = 0; cell < cellCount; cell++) result += geometry.cutCells.openCellVolume(cell);
		return result;
	}

	float effectiveOpenCellVolume(int cell) {
		float geometric = geometry.cutCells.openCellVolume(cell);
		return granularCoupling == null ? geometric : granularCoupling.effectiveOpenCellVolume(cell, geometric);
	}

	float pressureOpenCellVolume(int cell) {
		float geometric = geometry.cutCells.openCellVolume(cell);
		return pressureGranularCoupling == null ? geometric
				: pressureGranularCoupling.effectiveOpenCellVolume(cell, geometric);
	}


	/** Exact marker-mass inventory; reconstructed grid density is never used for this ledger. */
	public double totalMass() { return metrics.totalMass(); }

	/** Returns the material handle with the largest exact marker volume in the given cell. */
	public int dominantMaterialInCell(int x, int y, int z) { return metrics.dominantMaterialInCell(x, y, z); }

	/** Exact nearby marker volume for planning a partial container extraction; -1 selects all materials. */
	public double exactVolumeNear(int centerX, int centerY, int centerZ, int maxRadius, int materialHandle) { return metrics.exactVolumeNear(centerX, centerY, centerZ, maxRadius, materialHandle); }


	MaterialInventory materialInventory(int materialHandle) {
		double volume = 0.0, mass = 0.0, weightedY = 0.0;
		for (int particle = 0; particle < particles.count(); particle++) {
			if (particles.materialHandle(particle) != materialHandle) continue;
			volume += particles.volume(particle);
			mass += particles.mass(particle);
			weightedY += particles.positionY(particle) / config.cellSize() * particles.volume(particle);
		}
		return new MaterialInventory(volume, mass, volume == 0.0 ? Double.NaN : weightedY / volume);
	}

	int phaseCountInCell(int x, int y, int z) {
		ensureGrid();
		int result = 0;
		for (int entry = markerGrid.cellPhaseHead[cellIndexChecked(x, y, z)]; entry >= 0; entry = markerGrid.phaseEntryNext[entry]) result++;
		return result;
	}

	/** In-memory material assignment only; callers outside the fluid package cannot create world material state. */
	void setMaterialInCell(int x, int y, int z, FluidMaterialRegistry registry, int materialHandle) {
		if (registry == null) throw new IllegalArgumentException("Material registry must not be null");
		registry.material(materialHandle);
		int cell = cellIndexChecked(x, y, z);
		boolean found = false;
		for (int particle = 0; particle < particles.count(); particle++) {
			if (particleCellIndex(particle) == cell) found = true;
		}
		if (!found) throw new IllegalArgumentException("Cannot assign material to an empty pressure cell");
		bindMaterialRegistry(registry);
		for (int particle = 0; particle < particles.count(); particle++) {
			if (particleCellIndex(particle) == cell) assignMarkerMaterial(particle, registry, materialHandle);
		}
		gridDirty = true;
	}

	/** Marker-granular package test injection; indices are intentionally not exposed as a public state contract. */
	void setMarkerMaterial(int particle, FluidMaterialRegistry registry, int materialHandle) {
		if (particle < 0 || particle >= particles.count()) throw new IllegalArgumentException("Unknown marker index " + particle);
		if (registry == null) throw new IllegalArgumentException("Material registry must not be null");
		registry.material(materialHandle);
		bindMaterialRegistry(registry);
		assignMarkerMaterial(particle, registry, materialHandle);
		gridDirty = true;
	}

	private void bindMaterialRegistry(FluidMaterialRegistry registry) {
		if (registry == null) throw new IllegalArgumentException("Material registry must not be null");
		if (materialRegistry != null && materialRegistry != registry) {
			throw new IllegalArgumentException("A domain cannot mix runtime handles from different material registries");
		}
		if (materialRegistry != null) return;
		double defaultDensity = registry.material(0).referenceDensity();
		double tolerance = Math.max(1.0E-9, Math.abs(config.density()) * 1.0E-12);
		if (Math.abs(defaultDensity - config.density()) > tolerance) {
			throw new IllegalArgumentException("Material handle 0 must match the domain default density");
		}
		materialRegistry = registry;
	}

	private void assignMarkerMaterial(int particle, FluidMaterialRegistry registry, int materialHandle) {
		if (chemicalParcels != null) {
			throw new IllegalStateException("Material relabeling requires an exact chemical transfer");
		}
		double previousTemperature = thermalState == null ? Double.NaN : thermalMarkerTemperature(particle);
		SignedDyadic thermalBefore = thermalState == null ? SignedDyadic.ZERO : exactInternalEnergy();
		FluidMaterial material = registry.material(materialHandle);
		double mass = (double) particles.volume(particle) * material.referenceDensity();
		if (!(mass <= Float.MAX_VALUE)) throw new IllegalArgumentException("Material mass exceeds P2G range");
		particles.setMass(particle, mass);
		particles.setMaterialHandle(particle, materialHandle);
		particles.setPhaseLabel(particle, registry.phaseHandle(materialHandle));
		if (thermalState != null) thermalState.resetMarkerTemperature(
				particle, previousTemperature, thermalMarkerContext(), thermalBefore);
	}

	boolean hasNonDefaultMaterialState() {
		if (materialRegistry != null) return true;
		for (int particle = 0; particle < particles.count(); particle++) {
			if (particles.materialHandle(particle) != 0 || particles.phaseLabel(particle) != 0) return true;
			float referenceMass = particles.volume(particle) * config.density();
			float tolerance = Math.max(1.0E-5F, Math.ulp(referenceMass) * 8.0F);
			if (Math.abs(particles.mass(particle) - referenceMass) > tolerance) return true;
		}
		return false;
	}

	boolean usesMassWeightedP2G() {
		for (int particle = 0; particle < particles.count(); particle++) {
			double referenceMass = (double) particles.volume(particle) * config.density();
			double tolerance = Math.max(1.0E-9, Math.abs(referenceMass) * 1.0E-12);
			if (Math.abs(particles.mass(particle) - referenceMass) > tolerance) return true;
		}
		return false;
	}

	/** Maximum marker speed used only as a scheduling observation; it is not a water-volume estimate. */
	public float maximumParticleSpeed() { return metrics.maximumParticleSpeed(); }

	/**
	 * Commits a validated hydrostatic scheduling transition by removing residual marker momentum.
	 * Marker positions, counts, particleVolume and particleMass are unchanged, so both exact ledgers are unchanged.
	 */
	public void commitHydrostaticSleep() {
		particles.clearVelocities();
		gridDirty = true;
	}

	public float maximumPressure() { return metrics.maximumPressure(); }

	public byte[] copyQuantizedVolume() { return carriers.copyQuantizedVolume(); }

	/** Binary mask: 1 where a cell is entirely solid. Not interchangeable with {@link #copyCollisionMicroMasks()}. */
	public byte[] copyBinarySolidMask() { return carriers.copyBinarySolidMask(); }

	/**
	 * The 2x2x2 cut-cell occupancy this domain actually resolves against. A particle state round-trips
	 * collision through this encoding, so handing it a binary mask silently drops every solid cell:
	 * the collision field is decoded as micro bits and nothing reads back as solid.
	 */
	public byte[] copyCollisionMicroMasks() { return carriers.copyCollisionMicroMasks(); }

	/** Actual pressure-phase occupancy, separate from the smooth P2G density. */
	public byte[] copyMarkerMask() { return carriers.copyMarkerMask(); }

	/** Read-only material rendering reconstruction; it contains no exact inventory and is not a wire format. */
	public FluidMaterialRenderSnapshot copyMaterialRenderSnapshot() { return carriers.copyMaterialRenderSnapshot(); }

	public VolumeDistribution volumeDistribution(float visibleThreshold) { return metrics.volumeDistribution(visibleThreshold); }

	public SolidDistribution solidDistribution() { return metrics.solidDistribution(); }

	public boolean containsFluidAt(float localX, float localY, float localZ) { return metrics.containsFluidAt(localX, localY, localZ); }

	/** Conservative AABB overlap with the current cut-micro-cell, horizontal-slab fluid observation. */
	public BodyFluidSample sampleAxisAlignedBody(
			double minimumX, double minimumY, double minimumZ,
			double maximumX, double maximumY, double maximumZ
	) {
		if (!(Double.isFinite(minimumX) && Double.isFinite(minimumY) && Double.isFinite(minimumZ)
				&& Double.isFinite(maximumX) && Double.isFinite(maximumY) && Double.isFinite(maximumZ)
				&& maximumX > minimumX && maximumY > minimumY && maximumZ > minimumZ)) {
			throw new IllegalArgumentException("Axis-aligned body bounds are invalid");
		}
		ensureGrid();
		double h = config.cellSize();
		int firstX = Math.max(0, (int) Math.floor(minimumX / h));
		int firstY = Math.max(0, (int) Math.floor(minimumY / h));
		int firstZ = Math.max(0, (int) Math.floor(minimumZ / h));
		int lastX = Math.min(sizeX - 1, (int) Math.ceil(maximumX / h) - 1);
		int lastY = Math.min(sizeY - 1, (int) Math.ceil(maximumY / h) - 1);
		int lastZ = Math.min(sizeZ - 1, (int) Math.ceil(maximumZ / h) - 1);
		if (lastX < firstX || lastY < firstY || lastZ < firstZ) return BodyFluidSample.EMPTY;
		double microSize = h / FlipCutCellGeometry.MICRO_CELLS_PER_AXIS;
		double displaced = 0.0, centerXSum = 0.0, centerYSum = 0.0, centerZSum = 0.0;
		double velocityXSum = 0.0, velocityYSum = 0.0, velocityZSum = 0.0;
		double[] layerFill = new double[FlipCutCellGeometry.MICRO_CELLS_PER_AXIS];
		for (int z = firstZ; z <= lastZ; z++) for (int y = firstY; y <= lastY; y++) {
			for (int x = firstX; x <= lastX; x++) {
				int cell = cellIndex(x, y, z);
				if (geometry.solid[cell] || volume[cell] <= config.fluidEpsilon()) continue;
				double geometricOpen = geometry.cutCells.openCellVolume(cell);
				double effectiveOpen = effectiveOpenCellVolume(cell);
				if (!(geometricOpen > 0.0 && effectiveOpen > 0.0)) continue;
				double poreScale = effectiveOpen / geometricOpen;
				Arrays.fill(layerFill, 0.0);
				double remainingFluid = Math.min(volume[cell], effectiveOpen);
				for (int my = 0; my < FlipCutCellGeometry.MICRO_CELLS_PER_AXIS; my++) {
					int openInLayer = 0;
					for (int mz = 0; mz < FlipCutCellGeometry.MICRO_CELLS_PER_AXIS; mz++) {
						for (int mx = 0; mx < FlipCutCellGeometry.MICRO_CELLS_PER_AXIS; mx++) {
							if (geometry.cutCells.microCellOpen(cell, mx, my, mz)) openInLayer++;
						}
					}
					double layerCapacity = openInLayer * poreScale
							/ (FlipCutCellGeometry.MICRO_CELLS_PER_AXIS
							* FlipCutCellGeometry.MICRO_CELLS_PER_AXIS
							* FlipCutCellGeometry.MICRO_CELLS_PER_AXIS);
					if (!(layerCapacity > 0.0)) continue;
					layerFill[my] = Math.min(1.0, remainingFluid / layerCapacity);
					remainingFluid -= layerFill[my] * layerCapacity;
				}
				for (int mz = 0; mz < FlipCutCellGeometry.MICRO_CELLS_PER_AXIS; mz++) {
					for (int my = 0; my < FlipCutCellGeometry.MICRO_CELLS_PER_AXIS; my++) {
						if (!(layerFill[my] > 0.0)) continue;
						for (int mx = 0; mx < FlipCutCellGeometry.MICRO_CELLS_PER_AXIS; mx++) {
							if (!geometry.cutCells.microCellOpen(cell, mx, my, mz)) continue;
							double lowX = Math.max(minimumX, x * h + mx * microSize);
							double lowY = Math.max(minimumY, y * h + my * microSize);
							double lowZ = Math.max(minimumZ, z * h + mz * microSize);
							double highX = Math.min(maximumX, x * h + (mx + 1) * microSize);
							double highY = Math.min(maximumY,
									y * h + (my + layerFill[my]) * microSize);
							double highZ = Math.min(maximumZ, z * h + (mz + 1) * microSize);
							if (!(highX > lowX && highY > lowY && highZ > lowZ)) continue;
							double overlap = (highX - lowX) * (highY - lowY) * (highZ - lowZ) * poreScale;
							double centerSampleX = 0.5 * (lowX + highX);
							double centerSampleY = 0.5 * (lowY + highY);
							double centerSampleZ = 0.5 * (lowZ + highZ);
							displaced += overlap;
							centerXSum += overlap * centerSampleX;
							centerYSum += overlap * centerSampleY;
							centerZSum += overlap * centerSampleZ;
							velocityXSum += overlap * sampleX(this.mac.velocityX,
									(float) centerSampleX, (float) centerSampleY, (float) centerSampleZ);
							velocityYSum += overlap * sampleY(this.mac.velocityY,
									(float) centerSampleX, (float) centerSampleY, (float) centerSampleZ);
							velocityZSum += overlap * sampleZ(this.mac.velocityZ,
									(float) centerSampleX, (float) centerSampleY, (float) centerSampleZ);
						}
					}
				}
			}
		}
		if (!(displaced > 0.0)) return BodyFluidSample.EMPTY;
		double inverse = 1.0 / displaced;
		return new BodyFluidSample(displaced,
				centerXSum * inverse, centerYSum * inverse, centerZSum * inverse,
				velocityXSum * inverse, velocityYSum * inverse, velocityZSum * inverse);
	}

	public VelocitySample sampleVelocity(float localX, float localY, float localZ) {
		ensureGrid();
		if (localX < 0.0F || localX > sizeX * config.cellSize()
				|| localY < 0.0F || localY > sizeY * config.cellSize()
				|| localZ < 0.0F || localZ > sizeZ * config.cellSize()) return VelocitySample.ZERO;
		return new VelocitySample(
				sampleX(mac.velocityX, localX, localY, localZ),
				sampleY(mac.velocityY, localX, localY, localZ),
				sampleZ(mac.velocityZ, localX, localY, localZ)
		);
	}

	public float centerOfMassX() { return metrics.centerOfMassX(); }

	public float addVolumeNear(int centerX, int centerY, int centerZ, float amount, int maxRadius) {
		return addVolumeNearBudgeted(centerX, centerY, centerZ, amount, maxRadius).addedVolume();
	}

	/** Operator pour: inserts as much as fits, with every capacity and audit rejection in planning. */
	public VolumeAddition addVolumeNearBudgeted(int centerX, int centerY, int centerZ, float amount, int maxRadius) {
		return transferPlanner.insertVolumeAtMost(centerX, centerY, centerZ, amount, maxRadius);
	}

	/** Removes every marker; the exact marker-volume ledger becomes zero immediately. */
	public float removeVolumeNear(int centerX, int centerY, int centerZ, float amount, int maxRadius) {
		return transferPlanner.removeVolumeNear(centerX, centerY, centerZ, amount, maxRadius);
	}

	public void removeAllWaterExact() {
		commitRemoveAllWaterExact(planRemoveAllWaterExact());
	}

	/** Completes every fallible liquid-clear audit check without changing marker truth. */
	public WaterClearPlan planRemoveAllWaterExact() {
		return clearPlanner.planWater();
	}

	/** Commits a previously validated liquid clear; no new ledger candidate is constructed here. */
	public void commitRemoveAllWaterExact(WaterClearPlan plan) {
		if (plan == null || particles.count() != plan.markerCount()
				|| carrierMutationRevision() != plan.mutationRevision()
				|| (chemicalParcels == null ? 0L : chemicalParcels.mutationRevision()) != plan.chemicalRevision()
				|| Double.doubleToRawLongBits(totalVolumeExact()) != plan.totalVolumeBits()
				|| Double.doubleToRawLongBits(totalMass()) != plan.totalMassBits()
				|| !(thermalState == null ? SignedDyadic.ZERO : thermalState.exactLiquidEnergy(particles.count()))
				.equals(plan.internalEnergy())) {
			throw new IllegalStateException("Liquid inventory changed after clear planning");
		}
		if (chemicalParcels != null) {
			chemicalParcels.recordEscapedSink(plan.drained());
		}
		if (thermalState != null) thermalState.recordExternalSink(plan.internalEnergy());
		coordinator.clearLiquidMarkers(
				CarrierMutationPlan.wholesaleCount(carrierMutationRevision(), particles.count()));
		gridDirty = true;
	}

	/** Plans one operator clear across liquid markers and every coherent condensed phase. */
	public CondensedClearPlan planRemoveAllCondensedExact() {
		return clearPlanner.planCondensed();
	}

	/** Commits a jointly prevalidated liquid/coherent clear and records only the energy actually removed. */
	public CondensedClear commitRemoveAllCondensedExact(CondensedClearPlan plan) {
		preflightCondensedClear(plan);
		return applyCondensedClear(plan);
	}

	/** Reruns every fallible condensed-clear check against live truth without mutating anything. */
	private void preflightCondensedClear(CondensedClearPlan plan) {
		if (plan == null) throw new IllegalArgumentException("Condensed clear plan is missing");
		if (carrierMutationRevision() != plan.mutationRevision()
				|| (chemicalParcels == null ? 0L : chemicalParcels.mutationRevision()) != plan.chemicalRevision()) {
			throw new IllegalStateException("Liquid inventory changed after condensed clear planning");
		}
		WaterClearPlan liquid = plan.liquid();
		if (particles.count() != liquid.markerCount()
				|| Double.doubleToRawLongBits(totalVolumeExact()) != liquid.totalVolumeBits()
				|| Double.doubleToRawLongBits(totalMass()) != liquid.totalMassBits()
				|| !(thermalState == null ? SignedDyadic.ZERO : thermalState.exactLiquidEnergy(particles.count()))
				.equals(liquid.internalEnergy())) {
			throw new IllegalStateException("Liquid inventory changed after condensed clear planning");
		}
		List<CoherentPhaseStore.Group> currentGroups = thermalState == null
				? List.of() : thermalState.coherentPhases().snapshot();
		if (!currentGroups.equals(plan.coherentGroups())
				|| (thermalState == null ? SignedDyadic.ZERO : thermalState.coherentPhases().exactEnergy())
				.compareTo(plan.coherentEnergy()) != 0
				|| (thermalState == null ? 0L : thermalState.coherentPhases().totalOccupiedSvq())
				!= plan.coherentVolumeSvq()
				|| Double.doubleToRawLongBits(thermalState == null ? 0.0
				: thermalState.coherentPhases().totalMass()) != plan.coherentMassBits()) {
			throw new IllegalStateException("Coherent phase inventory changed after condensed clear planning");
		}
	}

	/** Publishes an already preflighted condensed clear; the mutation phase has no rejections. */
	private CondensedClear applyCondensedClear(CondensedClearPlan plan) {
		if (chemicalParcels != null) {
			chemicalParcels.recordEscapedSink(plan.drained());
		}
		if (thermalState != null) {
			thermalState.recordExternalSink(plan.liquid().internalEnergy());
			thermalState.clearCoherentExternal(plan.coherentGroups(), plan.coherentEnergy());
		}
		coordinator.clearLiquidMarkers(
				CarrierMutationPlan.wholesaleCount(carrierMutationRevision(), particles.count()));
		refreshCoherentOccupancy();
		gridDirty = true;
		return new CondensedClear(plan.coherentVolumeSvq(),
				Double.longBitsToDouble(plan.coherentMassBits()), plan.coherentGroups().size());
	}

	/** Prevalidates every truth removed by the operator-level condensed inventory reset. */
	public OperatorClearPlan planOperatorClear() {
		return lifecyclePlanner.planOperatorClear();
	}

	/**
	 * Clears all condensed owners and publishes the already-planned retained-gas topology.
	 * Every sub-plan is preflighted against live truth before the first owner mutates, so a
	 * stale granular or gas plan can never leave the condensed side already cleared.
	 */
	public OperatorClear commitOperatorClear(OperatorClearPlan plan) {
		if (plan == null) throw new IllegalArgumentException("Operator clear plan is missing");
		preflightCondensedClear(plan.condensed());
		preflightGranularClear(plan.granular());
		if (thermalState == null ? plan.thermalRevision != null
				: !thermalState.thermalRevision().equals(plan.thermalRevision)) {
			throw new IllegalStateException("Thermal inventory changed after operator clear planning");
		}
		if (thermalState != null) thermalState.requireCanCommitOperatorClear(
				plan.thermalRevision, plan.condensed.liquid().internalEnergy(), plan.condensed.coherentEnergy(),
				plan.granular.internalEnergy(), plan.gasTopology == null ? null : plan.gasTopology.thermalPlan());
		if (plan.gasTopology != null) gasTopologyCoordinator.preflight(plan.gasTopology);
		CondensedClear condensed = applyCondensedClear(plan.condensed());
		GranularClear granular = applyGranularClear(plan.granular());
		if (plan.gasTopology != null) gasTopologyCoordinator.commit(plan.gasTopology);
		return new OperatorClear(condensed, granular);
	}

	/** Prevalidates an operator vent without treating ambient as inventory. */
	public OperatorGasVentPlan planOperatorVentSealedGas() {
		return gasLifecyclePlanner.planVent();
	}

	/** Converts every sealed cell to inventory-free ambient topology and books gas plus U to sinks. */
	public OperatorGasVent commitOperatorVentSealedGas(OperatorGasVentPlan plan) {
		return gasLifecyclePlanner.commitVent(plan);
	}


	public record WaterClearPlan(
			int markerCount, long totalVolumeBits, long totalMassBits, ChemicalTransferLedger drained,
			SignedDyadic internalEnergy, long mutationRevision, long chemicalRevision
	) { }
	public record CondensedClear(long coherentVolumeSvq, double coherentMass, int coherentGroups) { }
	public record CondensedClearPlan(
			WaterClearPlan liquid, List<CoherentPhaseStore.Group> coherentGroups,
			ChemicalTransferLedger drained, SignedDyadic coherentEnergy,
			long coherentVolumeSvq, long coherentMassBits, long mutationRevision, long chemicalRevision
	) {
		public CondensedClearPlan { coherentGroups = List.copyOf(coherentGroups); }
	}
	public record OperatorClear(CondensedClear condensed, GranularClear granular) { }
	public record OperatorGasVent(GasInventory removed, SignedDyadic internalEnergy, int sealedNodes) { }
	public record OperatorGasVentPlan(
			int nodeCount, GasInventory removed, AirNetwork.RemapPlan gasPlan,
			GasEnergyStore.GasVentPlan thermalPlan
	) { }
	public static final class OperatorClearPlan {
		private final CondensedClearPlan condensed;
		private final GranularClearPlan granular;
		private final GasTopologyCoordinator.GasTopologyPlan gasTopology;
		private final ThermalDomainState.Revision thermalRevision;

		OperatorClearPlan(
				CondensedClearPlan condensed, GranularClearPlan granular,
				GasTopologyCoordinator.GasTopologyPlan gasTopology, ThermalDomainState.Revision thermalRevision
		) {
			this.condensed = condensed;
			this.granular = granular;
			this.gasTopology = gasTopology;
			this.thermalRevision = thermalRevision;
		}

		public CondensedClearPlan condensed() { return condensed; }
		public GranularClearPlan granular() { return granular; }
	}
	public record CollisionEdit(int x, int y, int z, int mask) { }

	/** Opaque, single-use permit returned only after every collision owner accepts the batch. */
	public static final class CollisionEditPlan {
		private final FlipFluidDomain owner;
		private final List<CollisionEdit> edits;
		private final int[] previousMasks;
		private final int planMarkerCount;
		private final long totalVolumeBits;
		private final long totalMassBits;
		private final GranularCollisionPlan granular;
		private boolean consumed;

		private CollisionEditPlan(
				FlipFluidDomain owner, List<CollisionEdit> edits, int[] previousMasks,
				int planMarkerCount, long totalVolumeBits, long totalMassBits, GranularCollisionPlan granular
		) {
			this.owner = owner;
			this.edits = edits;
			this.previousMasks = previousMasks.clone();
			this.planMarkerCount = planMarkerCount;
			this.totalVolumeBits = totalVolumeBits;
			this.totalMassBits = totalMassBits;
			this.granular = granular;
		}
	}

	private record CollisionDeposit(int cell, GranularDepositStore.Group group) { }
	private record GranularCollisionPlan(
			boolean[] selectedParcels, List<CollisionDeposit> depositGroups,
			GranularAuditLedger sinkAfter, boolean[] affectedCells,
			int parcelCount, long totalSvq, long totalMassBits
	) { }

	public static final class CollisionEditBlockedException extends IllegalStateException {
		public CollisionEditBlockedException(String message, Throwable cause) { super(message, cause); }
	}


	/**
	 * Container transfer primitive: all-or-nothing insertion of an exact volume near the
	 * target. Cells, pieces and the marker budget are planned before any state changes, so a
	 * refusal leaves the domain untouched. Pieces are {@code PARTICLE_VOLUME} chunks plus one
	 * float remainder; the returned volume is the exact double sum of the stored pieces.
	 */
	public ExactVolumeTransfer tryInsertExactVolume(int centerX, int centerY, int centerZ, float volume, int maxRadius) {
		return tryInsertExactVolume(centerX, centerY, centerZ, volume, maxRadius, 0);
	}

	public ExactVolumeTransfer tryInsertExactVolume(int centerX, int centerY, int centerZ, float volume, int maxRadius, int materialHandle) {
		return tryInsertExactVolume(centerX, centerY, centerZ, volume, maxRadius, materialHandle, false);
	}

	public ExactVolumeTransfer tryInsertExactVolume(
			int centerX, int centerY, int centerZ, float volume, int maxRadius, int materialHandle,
			boolean externalSource
	) {
		return tryInsertExactVolume(centerX, centerY, centerZ, volume, maxRadius, materialHandle,
				externalSource, null);
	}

	public ExactVolumeTransfer tryInsertExactVolume(
			int centerX, int centerY, int centerZ, float volume, int maxRadius, int materialHandle,
			boolean externalSource, double internalEnergy
	) {
		return tryInsertExactVolume(centerX, centerY, centerZ, volume, maxRadius, materialHandle,
				externalSource, Double.valueOf(internalEnergy));
	}

	private ExactVolumeTransfer tryInsertExactVolume(
			int centerX, int centerY, int centerZ, float volume, int maxRadius, int materialHandle,
			boolean externalSource, Double internalEnergy
	) {
		validateVolumeEdit(volume, maxRadius);
		if (volume <= 0.0F) return new ExactVolumeTransfer(volume == 0.0F, 0.0, 0.0);
		double mass = materialRegistry == null
				? (double) volume * config.density()
				: (double) volume * materialRegistry.material(materialHandle).referenceDensity();
		return tryInsertExactMaterial(centerX, centerY, centerZ, volume, mass, maxRadius, materialHandle,
				externalSource, internalEnergy);
	}

	/** Inserts independent material volume and mass ledgers without deriving either from the other. */
	public ExactVolumeTransfer tryInsertExactMaterial(
			int centerX, int centerY, int centerZ, float volume, double mass, int maxRadius, int materialHandle
	) {
		return tryInsertExactMaterial(centerX, centerY, centerZ, volume, mass, maxRadius, materialHandle, false);
	}

	private ExactVolumeTransfer tryInsertExactMaterial(
			int centerX, int centerY, int centerZ, float volume, double mass, int maxRadius, int materialHandle,
			boolean externalSource
	) {
		return tryInsertExactMaterial(centerX, centerY, centerZ, volume, mass, maxRadius, materialHandle,
				externalSource, null);
	}

	private ExactVolumeTransfer tryInsertExactMaterial(
			int centerX, int centerY, int centerZ, float volume, double mass, int maxRadius, int materialHandle,
			boolean externalSource, Double internalEnergy
	) {
		if (volume == 0.0F && mass == 0.0) return new ExactVolumeTransfer(true, 0.0, 0.0);
		ExactMixtureTransfer transfer = tryInsertExactMixture(centerX, centerY, centerZ,
				List.of(new MaterialPortion(materialHandle, volume, mass)), maxRadius, externalSource, internalEnergy);
		return transfer.transferred()
				? new ExactVolumeTransfer(true, transfer.portions().getFirst().volume(),
						transfer.portions().getFirst().mass(), transfer.internalEnergy())
				: new ExactVolumeTransfer(false, 0.0, 0.0);
	}

	/** Plans every mixture component against shared capacity before inserting any marker. */
	public ExactMixtureTransfer tryInsertExactMixture(
			int centerX, int centerY, int centerZ, List<MaterialPortion> portions, int maxRadius
	) {
		return tryInsertExactMixture(centerX, centerY, centerZ, portions, maxRadius, false);
	}

	public ExactMixtureTransfer tryInsertExactMixture(
			int centerX, int centerY, int centerZ, List<MaterialPortion> portions, int maxRadius,
			boolean externalSource
	) {
		return tryInsertExactMixture(centerX, centerY, centerZ, portions, maxRadius, externalSource, null);
	}

	public ExactMixtureTransfer tryInsertExactMixture(
			int centerX, int centerY, int centerZ, List<MaterialPortion> portions, int maxRadius,
			boolean externalSource, double internalEnergy
	) {
		return tryInsertExactMixture(centerX, centerY, centerZ, portions, maxRadius, externalSource,
				Double.valueOf(internalEnergy));
	}

	private FlipFluidDomain.ExactMixtureTransfer tryInsertExactMixture(
			int centerX, int centerY, int centerZ, List<MaterialPortion> portions, int maxRadius,
			boolean externalSource, Double internalEnergy
	) {
		return transferPlanner.insertMixture(centerX, centerY, centerZ, portions, maxRadius,
				externalSource, internalEnergy);
	}

	/** Plans and restores an exact chemical ledger without substituting a default carrier. */
	public ExactChemicalTransfer tryInsertExactChemical(
			int centerX, int centerY, int centerZ, ChemicalTransferLedger ledger, int maxRadius
	) {
		return tryInsertExactChemical(centerX, centerY, centerZ, ledger, maxRadius, false);
	}

	public ExactChemicalTransfer tryInsertExactChemical(
			int centerX, int centerY, int centerZ, ChemicalTransferLedger ledger, int maxRadius,
			boolean externalSource
	) {
		return tryInsertExactChemical(centerX, centerY, centerZ, ledger, maxRadius, externalSource, null);
	}

	public ExactChemicalTransfer tryInsertExactChemical(
			int centerX, int centerY, int centerZ, ChemicalTransferLedger ledger, int maxRadius,
			boolean externalSource, double internalEnergy
	) {
		return tryInsertExactChemical(centerX, centerY, centerZ, ledger, maxRadius, externalSource,
				Double.valueOf(internalEnergy));
	}

	private ExactChemicalTransfer tryInsertExactChemical(
			int centerX, int centerY, int centerZ, ChemicalTransferLedger ledger, int maxRadius,
			boolean externalSource, Double internalEnergy
	) {
		return transferPlanner.insertChemical(centerX, centerY, centerZ, ledger, maxRadius,
				externalSource, internalEnergy);
	}

	/** All-or-nothing extraction of full species/residual/indicator truth for a chemical container. */
	public ExactChemicalTransfer tryExtractExactChemical(
			int centerX, int centerY, int centerZ, float volume, int maxRadius
	) {
		return tryExtractExactChemical(centerX, centerY, centerZ, volume, maxRadius, ignored -> true);
	}

	/** Applies the caller's bounded representation check to the complete ledger before committing removal. */
	public ExactChemicalTransfer tryExtractExactChemical(
			int centerX, int centerY, int centerZ, float volume, int maxRadius,
			java.util.function.Predicate<ChemicalTransferLedger> acceptsLedger
	) {
		return transferPlanner.extractChemical(centerX, centerY, centerZ, volume, maxRadius, acceptsLedger);
	}

	/**
	 * Container transfer primitive: all-or-nothing extraction of an exact volume near the
	 * target. Availability is verified in double precision first; removal takes whole markers and
	 * splits the last one, so the returned volume is the exact double sum of real marker
	 * decreases and exceeds the request by at most one ulp of the split marker.
	 */
public ExactVolumeTransfer tryExtractExactVolume(int centerX, int centerY, int centerZ, float volume, int maxRadius) {
return tryExtractExactVolume(centerX, centerY, centerZ, volume, maxRadius, 0);
}

public ExactVolumeTransfer tryExtractExactVolume(int centerX, int centerY, int centerZ, float volume, int maxRadius, int materialHandle) {
	validateVolumeEdit(volume, maxRadius);
	if (volume <= 0.0F) return new ExactVolumeTransfer(volume == 0.0F, 0.0, 0.0);
	ExactMixtureTransfer transfer = transferPlanner.extractMixture(
			centerX, centerY, centerZ, volume, maxRadius, materialHandle);
	if (!transfer.transferred()) return new ExactVolumeTransfer(false, 0.0, 0.0);
	MaterialPortion portion = transfer.portions().getFirst();
	return new ExactVolumeTransfer(true, portion.volume(), portion.mass(), transfer.internalEnergy());
}

	/** Extracts nearby materials in deterministic cell/marker order and returns every component. */
	public ExactMixtureTransfer tryExtractExactMixture(
			int centerX, int centerY, int centerZ, float volume, int maxRadius
	) {
		validateVolumeEdit(volume, maxRadius);
		if (volume <= 0.0F) return new ExactMixtureTransfer(volume == 0.0F, List.of());
		return transferPlanner.extractMixture(centerX, centerY, centerZ, volume, maxRadius, -1);
	}

	private static final class RemovedMaterialLedger {
		private final double[] volume;
		private final double[] mass;
		private RemovedMaterialLedger(int materialCount) {
			volume = new double[materialCount]; mass = new double[materialCount];
		}
		private void add(int material, double addedVolume, double addedMass) {
			volume[material] += addedVolume; mass[material] += addedMass;
		}
		private List<MaterialPortion> build() {
			ArrayList<MaterialPortion> result = new ArrayList<>();
			for (int material = 0; material < volume.length; material++) if (volume[material] > 0.0) {
				if (!(Double.isFinite(mass[material]) && mass[material] > 0.0)) {
					throw new IllegalStateException("Displaced material has no positive finite mass");
				}
				result.add(new MaterialPortion(material, volume[material], mass[material]));
			}
			return List.copyOf(result);
		}
	}

	private static final class RemovedThermalEnergy {
		private SignedDyadic value = SignedDyadic.ZERO;
		private void add(double energy) { value = value.add(SignedDyadic.of(energy)); }
		private SignedDyadic value() { return value; }
	}

	public record ExactVolumeTransfer(boolean transferred, double volume, double mass, double internalEnergy) {
		public ExactVolumeTransfer(boolean transferred, double volume, double mass) {
			this(transferred, volume, mass, 0.0);
		}
	}
	public record ExactMixtureTransfer(boolean transferred, List<MaterialPortion> portions, double internalEnergy) {
		public ExactMixtureTransfer(boolean transferred, List<MaterialPortion> portions) {
			this(transferred, portions, 0.0);
		}
	}
	public record ExactChemicalTransfer(boolean transferred, ChemicalTransferLedger ledger, double internalEnergy) {
		public ExactChemicalTransfer(boolean transferred, ChemicalTransferLedger ledger) {
			this(transferred, ledger, 0.0);
		}
	}
	public record ChemicalDisplacement(float volume, ChemicalTransferLedger ledger, double internalEnergy) {
		public ChemicalDisplacement(float volume, ChemicalTransferLedger ledger) { this(volume, ledger, 0.0); }
	}
	public record MaterialDisplacement(float volume, List<MaterialPortion> portions, double internalEnergy) { }
	public record MaterialPortion(int materialHandle, double volume, double mass) { }

	public StepStats step() { return step(false, 0); }

	public StepStats step(boolean phaseChangeEnabled, int maximumPhaseOperations) {
		return step(phaseChangeEnabled, maximumPhaseOperations, false);
	}

	/** Diagnostic production step with a staged mechanical-energy audit; authoritative physics is unchanged. */
	public StepStats stepWithMechanicalAudit(boolean phaseChangeEnabled, int maximumPhaseOperations) {
		return step(phaseChangeEnabled, maximumPhaseOperations, true);
	}

	private StepStats step(
			boolean phaseChangeEnabled, int maximumPhaseOperations, boolean captureMechanicalAudit
	) {
		if (phaseChangeEnabled && (maximumPhaseOperations <= 0
				|| maximumPhaseOperations > ThermalLimits.MAX_PHASE_OPERATIONS_PER_STEP)) {
			throw new IllegalArgumentException("Production phase operation budget is invalid");
		}
		float before = totalVolume();
		double massBefore = totalMass();
		MechanicalAuditAccumulator mechanicalAudit = captureMechanicalAudit
				? new MechanicalAuditAccumulator(particleKineticEnergyJoules(), gravitationalPotentialEnergyJoules())
				: null;
		SignedDyadic thermalMechanicalWorkBefore = !captureMechanicalAudit || thermalState == null ? SignedDyadic.ZERO
				: thermalState.audit().mechanicalWork();
		lastGranularBoundaryImpulseX = 0.0;
		lastGranularBoundaryImpulseY = 0.0;
		lastGranularBoundaryImpulseZ = 0.0;
		int substeps = calculateSubsteps(config.timeStep());
		float dt = config.timeStep() / substeps;
		int granularParcelCountBeforeAdvection = precipitationEnabled ? captureReactiveGranularCells() : 0;
		int pressureIterations = 0;
		for (int substep = 0; substep < substeps; substep++) {
			MechanicalStepPipeline.SubstepOutcome outcome = mechanicalPipeline.substep(
					dt, substep, captureMechanicalAudit, mechanicalAudit);
			pressureIterations += outcome.pressureIterations();
			if (outcome.granularMechanics() != null) {
				lastGranularBoundaryImpulseX += outcome.granularMechanics().boundaryImpulseX();
				lastGranularBoundaryImpulseY += outcome.granularMechanics().boundaryImpulseY();
				lastGranularBoundaryImpulseZ += outcome.granularMechanics().boundaryImpulseZ();
			}
		}
		double carrierTransferInitialKineticEnergy = captureMechanicalAudit ? particleKineticEnergyJoules() : 0.0;
		if (precipitationEnabled) markMovedReactiveGranularCells(granularParcelCountBeforeAdvection);
		ThermalAxisStats thermalAxis = runThermalReactionPhaseAxis(phaseChangeEnabled, maximumPhaseOperations);
		ChemicalParcelStore.ReactionStats chemistry = thermalAxis.chemistry();
		if (granularState != null && granularState.parcels.count() > 1) {
			granularCoupling.rebuild(granularState.parcels, granularState.deposits);
			long[] compactedSolid = granularCoupling.copySolidSvq();
			granularState.inventory.compactActive(
					index -> granularParcelCell(granularState.parcels.parcel(index)),
					(first, second, merged) -> planGranularMerge(compactedSolid, first, second, merged),
					granularMergeVelocityDelta(), GranularLimits.MAX_OPERATIONS_PER_LOGICAL_STEP);
			granularCoupling.rebuild(granularState.parcels, granularState.deposits);
			if (!granularCoupling.matchesSolidSvq(compactedSolid)) {
				throw new IllegalStateException("Granular active compaction differs from its exact occupancy plan");
			}
		}
		if (granularDepositDynamics != null) {
			long[] dynamicsSolid = null;
			if (hasGranularInventory() || granularDepositDynamics.dirtyColumnCount() != 0) {
				granularCoupling.rebuild(granularState.parcels, granularState.deposits);
				dynamicsSolid = granularCoupling.copySolidSvq();
				long[] plannedDynamicsSolid = dynamicsSolid;
				lastGranularDepositStats = granularDepositDynamics.step(
						granularDepositEnvironment(plannedDynamicsSolid), config.timeStep());
			} else {
				lastGranularDepositStats = new GranularDepositDynamics.Stats(0, 0, 0, 0, 0);
			}
			if (hasGranularInventory()) {
				granularCoupling.rebuild(granularState.parcels, granularState.deposits);
				if (!granularCoupling.matchesSolidSvq(dynamicsSolid)) {
					throw new IllegalStateException("Granular deposit transition differs from its exact occupancy plan");
				}
				if (!granularOccupancyFitsGeometry(granularCoupling)) {
					throw new IllegalStateException("Granular deposit transition exceeds geometry");
				}
				pressureGranularCoupling = granularCoupling;
			} else {
				granularCoupling.clear(); pressureGranularCoupling = null;
			}
		}
		double reseedInitialKineticEnergy = captureMechanicalAudit ? particleKineticEnergyJoules() : 0.0;
		if (captureMechanicalAudit) mechanicalAudit.carrierInventoryTransferJoules +=
				reseedInitialKineticEnergy - carrierTransferInitialKineticEnergy;
		ensureParticleCapacity(particleReseeder.capacityUpperBound(particles.count()));
		SignedDyadic thermalBeforeReseed = thermalState == null ? SignedDyadic.ZERO : exactInternalEnergy();
		LiquidParticleStore.DirectArrays markerArrays = particles.directArrays();
		FlipParticleReseeder.Result reseeding = particleReseeder.reseed(
				geometry.solid,
				markerArrays.positionX, markerArrays.positionY, markerArrays.positionZ,
				markerArrays.velocityX, markerArrays.velocityY, markerArrays.velocityZ,
				markerArrays.volume, markerArrays.mass, markerArrays.materialHandle, markerArrays.phaseLabel, chemicalParcels,
				thermalState == null ? null : thermalReseedAdapter(),
				particles.count(), usesMassWeightedP2G(),
				this::markerPositionAllowed
		);
		coordinator.adoptWholesaleCount(
				CarrierMutationPlan.wholesaleCount(carrierMutationRevision(), particles.count()),
				reseeding.particleCount());
		double finalParticleKineticEnergy = captureMechanicalAudit ? particleKineticEnergyJoules() : 0.0;
		if (captureMechanicalAudit) mechanicalAudit.reseedTransferJoules +=
				finalParticleKineticEnergy - reseedInitialKineticEnergy;
		if (thermalState != null) thermalState.recordRoundoff(exactInternalEnergy().subtract(thermalBeforeReseed));
		gridDirty = true;
		rebuildGridFromParticles();
		return new StepStats(
				substeps, pressureIterations, lastPressureResidual, before, totalVolume(), massBefore, totalMass(),
				reseeding.merged(), reseeding.split(), reseeding.budgetSkipped(), reseeding.geometrySkipped(),
				reseeding.ledgerError(), reseeding.massLedgerError(),
				chemistry.dirtyCells(), chemistry.evaluations(), chemistry.changedMarkers(),
				thermalAxis.condensedPhase(), thermalAxis.gasPhase(),
				captureMechanicalAudit ? mechanicalAudit.finish(
						finalParticleKineticEnergy, gravitationalPotentialEnergyJoules(),
						(thermalState == null ? SignedDyadic.ZERO : thermalState.audit().mechanicalWork())
								.subtract(thermalMechanicalWorkBefore)) : MechanicalStepAudit.notCaptured()
		);
	}

	double particleKineticEnergyJoules() {
		double energy = 0.0;
		for (int marker = 0; marker < particles.count(); marker++) {
			double speedSquared = particles.velocityX(marker) * (double) particles.velocityX(marker)
					+ particles.velocityY(marker) * (double) particles.velocityY(marker)
					+ particles.velocityZ(marker) * (double) particles.velocityZ(marker);
			energy += 0.5 * unitScale.kilograms(particles.mass(marker)) * speedSquared;
		}
		if (!(Double.isFinite(energy) && energy >= 0.0)) {
			throw new ArithmeticException("Particle kinetic-energy audit is invalid");
		}
		return energy;
	}

	private double gravitationalPotentialEnergyJoules() {
		double energy = 0.0;
		for (int marker = 0; marker < particles.count(); marker++) {
			energy += unitScale.kilograms(particles.mass(marker)) * config.gravity() * particles.positionY(marker);
		}
		if (!(Double.isFinite(energy) && energy >= 0.0)) {
			throw new ArithmeticException("Particle gravitational-potential audit is invalid");
		}
		return energy;
	}

	double gridKineticEnergyJoules() {
		double energy = faceKineticEnergyJoules(mac.velocityX, mac.p2gWeightX)
				+ faceKineticEnergyJoules(mac.velocityY, mac.p2gWeightY)
				+ faceKineticEnergyJoules(mac.velocityZ, mac.p2gWeightZ);
		if (!(Double.isFinite(energy) && energy >= 0.0)) {
			throw new ArithmeticException("Grid kinetic-energy audit is invalid");
		}
		return energy;
	}

	private double faceKineticEnergyJoules(float[] faceVelocity, float[] p2gWeight) {
		double energy = 0.0;
		for (int face = 0; face < faceVelocity.length; face++) {
			double solverMass = variableDensityActive ? p2gWeight[face]
					: p2gWeight[face] * config.density();
			energy += 0.5 * unitScale.kilograms(solverMass)
					* faceVelocity[face] * (double) faceVelocity[face];
		}
		return energy;
	}

	void rebuildGridFromParticles() { particleToGridStage.rebuild(); }

	/** Rebuilds only the derived carrier geometry consumed by an in-progress gas phase scan. */
	private void rebuildGasPhaseGeometryFromParticles() {
		Arrays.fill(volume, 0.0F);
		Arrays.fill(markerGrid.markerCount, 0);
		Arrays.fill(markerGrid.cellMarkerHead, -1);
		Arrays.fill(markerGrid.cellMarkerTail, -1);
		Arrays.fill(markerGrid.exactCellMarkerVolume, 0.0);
		for (int marker = 0; marker < particles.count(); marker++) {
			int cell = particleCellIndex(marker);
			int previous = markerGrid.cellMarkerTail[cell];
			markerGrid.particleCellPrevious[marker] = previous;
			markerGrid.particleCellNext[marker] = -1;
			if (previous < 0) markerGrid.cellMarkerHead[cell] = marker;
			else markerGrid.particleCellNext[previous] = marker;
			markerGrid.cellMarkerTail[cell] = marker;
			markerGrid.markerCount[cell]++;
			markerGrid.exactCellMarkerVolume[cell] += particles.volume(marker);
			splatCenter(particles.positionX(marker), particles.positionY(marker), particles.positionZ(marker), particles.volume(marker));
		}
	}

	private void refreshGasPhaseGeometry(
			boolean carrierTopologyChanged, int changedCell, double markerVolumeDelta
	) {
		if (carrierTopologyChanged) {
			rebuildGasPhaseGeometryFromParticles();
			gasTopologyCoordinator.refresh();
			return;
		}
		if (changedCell < 0 || changedCell >= cellCount || !Double.isFinite(markerVolumeDelta)) {
			throw new IllegalArgumentException("Stable gas phase volume edit is invalid");
		}
		double updatedVolume = markerGrid.exactCellMarkerVolume[changedCell] + markerVolumeDelta;
		if (!(Double.isFinite(updatedVolume) && updatedVolume >= 0.0)) {
			throw new IllegalStateException("Stable gas phase edit produced invalid liquid volume");
		}
		markerGrid.exactCellMarkerVolume[changedCell] = updatedVolume;
		gasTopologyCoordinator.refreshAvailableVolume();
	}

	/** Runs the non-motion logical axis while a region is hydrostatically asleep. */
	public ThermalAxisStats stepThermalAxis(boolean phaseChangeEnabled, int maximumPhaseOperations) {
		if (thermalState == null) throw new IllegalStateException("Thermal state is not bound");
		if (phaseChangeEnabled && (maximumPhaseOperations <= 0
				|| maximumPhaseOperations > ThermalLimits.MAX_PHASE_OPERATIONS_PER_STEP)) {
			throw new IllegalArgumentException("Production phase operation budget is invalid");
		}
		ThermalAxisStats result = runThermalReactionPhaseAxis(phaseChangeEnabled, maximumPhaseOperations);
		gridDirty = true;
		rebuildGridFromParticles();
		return result;
	}

	private ThermalAxisStats runThermalReactionPhaseAxis(boolean phaseChangeEnabled, int maximumPhaseOperations) {
		ThermalAxisStats result = thermochemistryPipeline.step(
				thermalState != null, precipitationEnabled, config.timeStep(),
				phaseChangeEnabled, maximumPhaseOperations);
		lastChemicalReactionStats = result.chemistry();
		lastPhaseTransferStats = result.granularPhase();
		lastThermalPhaseStats = result.condensedPhase();
		lastGasThermalPhaseStats = result.gasPhase();
		return result;
	}

	public record ThermalAxisStats(
			ThermalTransport.Stats transport,
			ChemicalParcelStore.ReactionStats chemistry,
			PhaseTransferStats granularPhase,
			ThermalPhaseStats condensedPhase,
			GasThermalPhaseStats gasPhase
	) { }

	/**
	 * Reports which previous nodes still own at least one open cell in the new topology.
	 *
	 * <p>A previous node that still owns an open cell is matched by membership overlap, so only a
	 * node reduced entirely to interface residuals may hand its inventory to a neighbouring new
	 * component. That keeps a transient marker void in the liquid bulk from being treated as a
	 * descendant of the headspace; a new cavity with no old owner is an explicit vacuum node.</p>
	 */
	/**
	 * Brings the derived gas topology to the same carrier geometry that a checkpoint will encode:
	 * persistence must either commit this jointly planned remap or keep the previous HEAD.
	 */
	public void synchronizeGasTopologyForPersistence() {
		gasTopologyCoordinator.synchronizeForPersistence();
	}

	/** Gauge pressure of the sealed cavity owning an air cell; zero for ambient and inactive gas. */
	float gasPressureOfAirCell(int airCell) {
		if (!gasState.pressureActive) return 0.0F;
		int node = gasNodeOfAirCell(airCell);
		if (node <= 0) return 0.0F;
		return gasPressureOfNode(node);
	}

	int gasNodeOfAirCell(int airCell) {
		int node = projection.pressureAirNodeId[airCell];
		return node >= 0 ? node : rawGasNodeOfCell(airCell);
	}

	int rawGasNodeOfCell(int cell) {
		return gasBoundary.rawCellNode(cell);
	}

	/** Extends gas lineage through pressure-air fringe without crossing pressure-fluid bulk. */
	void rebuildPressureBoundaryLineage() {
		Arrays.fill(projection.pressureAirNodeId, -1);
		if (!gasBoundary.pressureActive() || !gasBoundary.topologyInitialized()) return;
		for (int node = AirNetwork.AMBIENT_NODE; node <= gasBoundary.previousSealedNodeCount(); node++) {
			int head = 0, tail = 0;
			for (int cell = 0; cell < cellCount; cell++) if (isAir(cell)
					&& rawGasNodeOfCell(cell) == node
					&& (projection.pressureAirNodeId[cell] < 0 || node < projection.pressureAirNodeId[cell])) {
				projection.pressureAirNodeId[cell] = node;
				projection.pressureAirQueue[tail++] = cell;
			}
			while (head < tail) {
				int cell = projection.pressureAirQueue[head++];
				int x = cell % sizeX;
				int yz = cell / sizeX;
				int y = yz % sizeY;
				int z = yz / sizeY;
				tail = visitPressureAir(cell, x - 1, y, z, -1, 0, 0, node, tail);
				tail = visitPressureAir(cell, x + 1, y, z, 1, 0, 0, node, tail);
				tail = visitPressureAir(cell, x, y - 1, z, 0, -1, 0, node, tail);
				tail = visitPressureAir(cell, x, y + 1, z, 0, 1, 0, node, tail);
				tail = visitPressureAir(cell, x, y, z - 1, 0, 0, -1, node, tail);
				tail = visitPressureAir(cell, x, y, z + 1, 0, 0, 1, node, tail);
			}
		}
	}

	private int visitPressureAir(
			int from, int x, int y, int z, int directionX, int directionY, int directionZ,
			int node, int tail
	) {
		if (!inside(x, y, z)) return tail;
		int cell = cellIndex(x, y, z);
		if (!isAir(cell) || projection.pressureAirNodeId[cell] >= 0 && projection.pressureAirNodeId[cell] <= node
				|| !(pressureOpenFaceFromCell(from, cell, directionX, directionY, directionZ) > 0.0F)) {
			return tail;
		}
		projection.pressureAirNodeId[cell] = node;
		projection.pressureAirQueue[tail] = cell;
		return tail + 1;
	}

	float gasPressureOfNode(int node) {
		return (float) gasBoundary.nodeGaugePressurePa(node);
	}

	int particleCellIndex(int particle) {
		return particleCellIndex(particles.positionX(particle), particles.positionY(particle), particles.positionZ(particle));
	}

	int particleCellIndex(float positionX, float positionY, float positionZ) {
		float h = config.cellSize();
		int x = Math.min(sizeX - 1, Math.max(0, (int) Math.floor(positionX / h)));
		int y = Math.min(sizeY - 1, Math.max(0, (int) Math.floor(positionY / h)));
		int z = Math.min(sizeZ - 1, Math.max(0, (int) Math.floor(positionZ / h)));
		return cellIndex(x, y, z);
	}

	long granularGeometricOpenSvq(int cell) {
		return SolidVolume.fromCellVolumeExact(geometry.cutCells.openCellVolume(cell));
	}

	private GranularDepositDynamics.Environment granularDepositEnvironment(long[] plannedSolid) {
		return new GranularDepositDynamics.Environment() {
			@Override public long geometricOpenSvq(int cell) {
				return SolidVolume.fromCellVolumeExact(geometry.cutCells.openCellVolume(cell));
			}

			@Override public boolean horizontalPathOpen(int fromCell, int directionX, int directionZ) {
				if (directionX == 0 && directionZ == 0 || Math.abs(directionX) > 1 || Math.abs(directionZ) > 1) {
					throw new IllegalArgumentException("Granular slope direction is invalid");
				}
				int x = fromCell % sizeX, yz = fromCell / sizeX, y = yz % sizeY, z = yz / sizeY;
				int targetX = x + directionX, targetZ = z + directionZ;
				if (!inside(targetX, y, targetZ)) return false;
				if (directionX == 0 || directionZ == 0) {
					return openFaceFromCell(fromCell, directionX, 0, directionZ) > 0.0F;
				}
				boolean xThenZ = openFaceFromCell(fromCell, directionX, 0, 0) > 0.0F;
				int afterX = cellIndex(targetX, y, z);
				xThenZ &= openFaceFromCell(afterX, 0, 0, directionZ) > 0.0F;
				boolean zThenX = openFaceFromCell(fromCell, 0, 0, directionZ) > 0.0F;
				int afterZ = cellIndex(x, y, targetZ);
				zThenX &= openFaceFromCell(afterZ, directionX, 0, 0) > 0.0F;
				return xThenZ || zThenX;
			}

			@Override public double shields(int surfaceCell, cc.ecisr.mmm.granular.ParticulateMaterial material) {
				int x = surfaceCell % sizeX, yz = surfaceCell / sizeX, y = yz % sizeY, z = yz / sizeY;
				int fluidCell = y + 1 < sizeY ? cellIndex(x, y + 1, z) : surfaceCell;
				float px = (x + 0.5F) * config.cellSize();
				float py = (Math.min(sizeY - 1, y + 1) + 0.5F) * config.cellSize();
				float pz = (z + 0.5F) * config.cellSize();
				double speed = Math.hypot(sampleX(mac.velocityX, px, py, pz), sampleZ(mac.velocityZ, px, py, pz));
				CarrierProperties carrier = granularCarrierProperties(fluidCell);
				double denominator = Math.abs(material.referenceDensity() - carrier.density())
						* config.gravity() * material.primaryGrainDiameterMeters();
				if (!(denominator > 1.0e-18)) return speed > 0.0 ? Double.POSITIVE_INFINITY : 0.0;
				double bedShear = carrier.viscosity() * speed / config.cellSize();
				return bedShear / denominator;
			}

			@Override public GranularDepositDynamics.Velocity wakeVelocity(int surfaceCell) {
				int x = surfaceCell % sizeX, yz = surfaceCell / sizeX, y = yz % sizeY, z = yz / sizeY;
				float px = (x + 0.5F) * config.cellSize(), py = (y + 0.5F) * config.cellSize();
				float pz = (z + 0.5F) * config.cellSize();
				return new GranularDepositDynamics.Velocity(sampleX(mac.velocityX, px, py, pz),
						sampleY(mac.velocityY, px, py, pz), sampleZ(mac.velocityZ, px, py, pz));
			}

			@Override public boolean planSettlement(GranularParcelStore.Parcel parcel, int cell) {
				GranularCouplingFields.Position position = parcelPosition(parcel);
				granularCoupling.removePlannedParcel(plannedSolid, position, parcel.solidVolumeSvq());
				if (granularCoupling.tryPlanCell(plannedSolid, cell, parcel.solidVolumeSvq(),
						FlipFluidDomain.this::granularGeometricOpenSvq)) return true;
				restorePlannedParcel(plannedSolid, position, parcel.solidVolumeSvq(), "settlement");
				return false;
			}

			@Override public void cancelSettlement(GranularParcelStore.Parcel parcel, int cell) {
				granularCoupling.removePlannedCell(plannedSolid, cell, parcel.solidVolumeSvq());
				restorePlannedParcel(plannedSolid, parcelPosition(parcel), parcel.solidVolumeSvq(), "settlement rollback");
			}

			@Override public boolean planRelaxation(int fromCell, int toCell, long solidSvq) {
				granularCoupling.removePlannedCell(plannedSolid, fromCell, solidSvq);
				if (granularCoupling.tryPlanCell(plannedSolid, toCell, solidSvq,
						FlipFluidDomain.this::granularGeometricOpenSvq)) return true;
				restorePlannedCell(plannedSolid, fromCell, solidSvq, "relaxation");
				return false;
			}

			@Override public void cancelRelaxation(int fromCell, int toCell, long solidSvq) {
				granularCoupling.removePlannedCell(plannedSolid, toCell, solidSvq);
				restorePlannedCell(plannedSolid, fromCell, solidSvq, "relaxation rollback");
			}

			@Override public void requireThermalRelaxation(
					int fromCell, int toCell, int material, int composition, long movedSvq, long sourceTotalSvq
			) {
				if (thermalState == null) return;
				thermalState.requireDepositMove(
						new GranularEnergyColumn.DepositKey(fromCell, material, composition),
						new GranularEnergyColumn.DepositKey(toCell, material, composition), movedSvq, sourceTotalSvq);
			}

			@Override public void commitThermalRelaxation(
					int fromCell, int toCell, int material, int composition, long movedSvq, long sourceTotalSvq
			) {
				if (thermalState == null) return;
				thermalState.commitDepositMove(
						new GranularEnergyColumn.DepositKey(fromCell, material, composition),
						new GranularEnergyColumn.DepositKey(toCell, material, composition), movedSvq, sourceTotalSvq);
			}

			@Override public boolean planWake(int cell, long solidSvq, float x, float y, float z) {
				granularCoupling.removePlannedCell(plannedSolid, cell, solidSvq);
				GranularCouplingFields.Position position = new GranularCouplingFields.Position(x, y, z);
				if (granularCoupling.tryPlanParcel(plannedSolid, position, solidSvq,
						FlipFluidDomain.this::granularGeometricOpenSvq)) return true;
				restorePlannedCell(plannedSolid, cell, solidSvq, "wake");
				return false;
			}

			@Override public void cancelWake(int cell, long solidSvq, float x, float y, float z) {
				granularCoupling.removePlannedParcel(plannedSolid,
						new GranularCouplingFields.Position(x, y, z), solidSvq);
				restorePlannedCell(plannedSolid, cell, solidSvq, "wake rollback");
			}

			@Override public void onSolidChanged(int cell) {
				if (precipitationEnabled) chemicalParcels.markDirtyCell(cell);
			}
		};
	}

	private static GranularCouplingFields.Position parcelPosition(GranularParcelStore.Parcel parcel) {
		return new GranularCouplingFields.Position(parcel.x(), parcel.y(), parcel.z());
	}

	private void restorePlannedParcel(
			long[] plannedSolid, GranularCouplingFields.Position position, long solidSvq, String transition
	) {
		if (!granularCoupling.tryPlanParcel(plannedSolid, position, solidSvq, this::granularGeometricOpenSvq)) {
			throw new IllegalStateException("Granular " + transition + " could not restore parcel occupancy");
		}
	}

	private void restorePlannedCell(long[] plannedSolid, int cell, long solidSvq, String transition) {
		if (!granularCoupling.tryPlanCell(plannedSolid, cell, solidSvq, this::granularGeometricOpenSvq)) {
			throw new IllegalStateException("Granular " + transition + " could not restore deposit occupancy");
		}
	}

	private int captureReactiveGranularCells() {
		int count = granularState.parcels.count();
		for (int parcel = 0; parcel < count; parcel++) {
			GranularParcelStore.Parcel state = granularState.parcels.parcel(parcel);
			granularReactivePreviousCells[parcel] = state.solidCompositionHandle() == 0
					? -1 : granularParcelCell(state);
		}
		return count;
	}

	private void markMovedReactiveGranularCells(int expectedCount) {
		if (granularState.parcels.count() != expectedCount) {
			throw new IllegalStateException("Granular advection changed the exact active parcel inventory");
		}
		for (int parcel = 0; parcel < expectedCount; parcel++) {
			int previous = granularReactivePreviousCells[parcel];
			if (previous < 0) continue;
			int current = granularParcelCell(granularState.parcels.parcel(parcel));
			if (current != previous) {
				chemicalParcels.markDirtyCell(previous);
				chemicalParcels.markDirtyCell(current);
			}
		}
	}

	CarrierProperties granularCarrierProperties(int cell) {
		int liquidMaterial = granularCarrierMaterialByCell == null ? -1 : granularCarrierMaterialByCell[cell];
		if (markerGrid.markerCount[cell] <= 0 || liquidMaterial < 0) return new CarrierProperties(1.225, 1.81e-5);
		String materialId = materialRegistry.material(liquidMaterial).id();
		GranularCarrier carrier = granularState.registry.carrier(granularState.registry.carrierHandle(materialId));
		return new CarrierProperties(markerGrid.cellDensity[cell], carrier.dynamicViscosity());
	}

	void splatCenter(float px, float py, float pz, float particleAmount) {
		float h = config.cellSize();
		float gx = px / h - 0.5F, gy = py / h - 0.5F, gz = pz / h - 0.5F;
		int x0 = (int) Math.floor(gx), y0 = (int) Math.floor(gy), z0 = (int) Math.floor(gz);
		float fx = gx - x0, fy = gy - y0, fz = gz - z0;
		float validWeight = 0.0F;
		for (int oz = 0; oz <= 1; oz++) for (int oy = 0; oy <= 1; oy++) for (int ox = 0; ox <= 1; ox++) {
			int x = x0 + ox, y = y0 + oy, z = z0 + oz;
			if (!inside(x, y, z) || geometry.solid[cellIndex(x, y, z)]) continue;
			validWeight += trilinearWeight(ox, oy, oz, fx, fy, fz);
		}
		if (validWeight <= 1.0E-8F) return;
		float scale = particleAmount / validWeight;
		for (int oz = 0; oz <= 1; oz++) for (int oy = 0; oy <= 1; oy++) for (int ox = 0; ox <= 1; ox++) {
			int x = x0 + ox, y = y0 + oy, z = z0 + oz;
			if (!inside(x, y, z)) continue;
			int index = cellIndex(x, y, z);
			if (!geometry.solid[index]) volume[index] += scale * trilinearWeight(ox, oy, oz, fx, fy, fz);
		}
	}

	void splatPressureVolume(float px, float py, float pz, float particleAmount) {
		double h = config.cellSize();
		double gridX = px / h, gridY = py / h, gridZ = pz / h;
		int centerX = (int) Math.floor(gridX), centerY = (int) Math.floor(gridY), centerZ = (int) Math.floor(gridZ);
		int neighborX = gridX - centerX < 0.5 ? centerX - 1 : centerX + 1;
		int neighborY = gridY - centerY < 0.5 ? centerY - 1 : centerY + 1;
		int neighborZ = gridZ - centerZ < 0.5 ? centerZ - 1 : centerZ + 1;
		double centerWeightX = pressureKernelWeight(gridX, centerX);
		double centerWeightY = pressureKernelWeight(gridY, centerY);
		double centerWeightZ = pressureKernelWeight(gridZ, centerZ);
		double neighborWeightX = pressureKernelWeight(gridX, neighborX);
		double neighborWeightY = pressureKernelWeight(gridY, neighborY);
		double neighborWeightZ = pressureKernelWeight(gridZ, neighborZ);
		double available = 0.0;
		for (int oz = 0; oz < 2; oz++) {
			int z = oz == 0 ? centerZ : neighborZ;
			double weightZ = oz == 0 ? centerWeightZ : neighborWeightZ;
			if (z < 0 || z >= sizeZ || !(weightZ > 0.0)) continue;
			for (int oy = 0; oy < 2; oy++) {
				int y = oy == 0 ? centerY : neighborY;
				double weightY = oy == 0 ? centerWeightY : neighborWeightY;
				if (y < 0 || y >= sizeY || !(weightY > 0.0)) continue;
				for (int ox = 0; ox < 2; ox++) {
					int x = ox == 0 ? centerX : neighborX;
					double weightX = ox == 0 ? centerWeightX : neighborWeightX;
					if (x < 0 || x >= sizeX || !(weightX > 0.0)) continue;
					int cell = cellIndex(x, y, z);
					if (geometry.solid[cell]) continue;
					available += weightX * weightY * weightZ;
				}
			}
		}
		if (!(available > 0.0)) {
			throw new IllegalStateException("Marker pressure footprint has no open cell overlap");
		}
		double scale = particleAmount / available;
		for (int oz = 0; oz < 2; oz++) {
			int z = oz == 0 ? centerZ : neighborZ;
			double weightZ = oz == 0 ? centerWeightZ : neighborWeightZ;
			if (z < 0 || z >= sizeZ || !(weightZ > 0.0)) continue;
			for (int oy = 0; oy < 2; oy++) {
				int y = oy == 0 ? centerY : neighborY;
				double weightY = oy == 0 ? centerWeightY : neighborWeightY;
				if (y < 0 || y >= sizeY || !(weightY > 0.0)) continue;
				for (int ox = 0; ox < 2; ox++) {
					int x = ox == 0 ? centerX : neighborX;
					double weightX = ox == 0 ? centerWeightX : neighborWeightX;
					if (x < 0 || x >= sizeX || !(weightX > 0.0)) continue;
					int cell = cellIndex(x, y, z);
					if (geometry.solid[cell]) continue;
					projection.pressureVolume[cell] += weightX * weightY * weightZ * scale;
				}
			}
		}
	}

	/** C1 partition induced by the canonical quarter-cell spacing of the 2x2x2 marker lattice. */
	private static double pressureKernelWeight(double coordinate, int cell) {
		double distance = Math.abs(coordinate - (cell + 0.5));
		if (distance <= 0.25) return 1.0;
		if (distance >= 0.75) return 0.0;
		double transition = (distance - 0.25) * 2.0;
		return 1.0 - transition * transition * (3.0 - 2.0 * transition);
	}

	static float trilinearWeight(int ox, int oy, int oz, float fx, float fy, float fz) {
		return (ox == 0 ? 1.0F - fx : fx) * (oy == 0 ? 1.0F - fy : fy) * (oz == 0 ? 1.0F - fz : fz);
	}

	void splatFaceField(float[] sums, float[] weights, int axis, float px, float py, float pz, float value, float particleAmount) {
		float h = config.cellSize();
		int nx = axis == 0 ? sizeX + 1 : sizeX;
		int ny = axis == 1 ? sizeY + 1 : sizeY;
		int nz = axis == 2 ? sizeZ + 1 : sizeZ;
		float gx = px / h - (axis == 0 ? 0.0F : 0.5F);
		float gy = py / h - (axis == 1 ? 0.0F : 0.5F);
		float gz = pz / h - (axis == 2 ? 0.0F : 0.5F);
		int x0 = (int) Math.floor(gx), y0 = (int) Math.floor(gy), z0 = (int) Math.floor(gz);
		float fx = gx - x0, fy = gy - y0, fz = gz - z0;
		for (int oz = 0; oz <= 1; oz++) for (int oy = 0; oy <= 1; oy++) for (int ox = 0; ox <= 1; ox++) {
			int x = x0 + ox, y = y0 + oy, z = z0 + oz;
			if (x < 0 || x >= nx || y < 0 || y >= ny || z < 0 || z >= nz) continue;
			float weight = (ox == 0 ? 1.0F - fx : fx) * (oy == 0 ? 1.0F - fy : fy) * (oz == 0 ? 1.0F - fz : fz);
			int index = (z * ny + y) * nx + x;
			float weighted = weight * particleAmount;
			sums[index] += value * weighted;
			weights[index] += weighted;
		}
	}

	static void normalize(float[] values, float[] weights) {
		for (int i = 0; i < values.length; i++) values[i] = weights[i] > 1.0E-8F ? values[i] / weights[i] : 0.0F;
	}

	private int calculateSubsteps(float dt) {
		float maximum = 0.0F;
		for (int i = 0; i < particles.count(); i++) {
			maximum = Math.max(maximum, Math.abs(particles.velocityX(i)) + Math.abs(particles.velocityY(i)) + Math.abs(particles.velocityZ(i)));
		}
		if (granularState != null) for (int index = 0; index < granularState.parcels.count(); index++) {
			GranularParcelStore.Parcel parcel = granularState.parcels.parcel(index);
			maximum = Math.max(maximum, Math.abs(parcel.velocityX())
					+ Math.abs(parcel.velocityY()) + Math.abs(parcel.velocityZ()));
		}
		maximum += config.gravity() * dt;
		return Math.max(1, (int) Math.ceil(maximum * dt / (config.cellSize() * config.maxCourantNumber())));
	}

	/**
	 * Applies a bounded immersed moving-body constraint to authoritative marker momentum.
	 * Volume, mass, composition and energy are unchanged; the returned SI impulse is calculated from
	 * the actual float velocity writes, including their representational roundoff.
	 */
	public MovingBodyFluidCoupling.Result applyMovingBodyCoupling(MovingBodyFluidCoupling.Request request) {
		if (request == null) throw new IllegalArgumentException("Moving-body coupling request is missing");
		requireFiniteMarkerVelocity(request.velocityXMetresPerSecond());
		requireFiniteMarkerVelocity(request.velocityYMetresPerSecond());
		requireFiniteMarkerVelocity(request.velocityZMetresPerSecond());
		double cellVolumeM3 = (double) config.cellSize() * config.cellSize() * config.cellSize();
		double requestedCellVolume = request.displacedVolumeCubicMetres() / cellVolumeM3;
		if (!(Double.isFinite(requestedCellVolume) && requestedCellVolume > 0.0)) {
			throw new IllegalArgumentException("Moving-body displaced volume is outside the solver scale");
		}
		double weightedVolume = 0.0;
		int coupledMarkers = 0;
		for (int marker = 0; marker < particles.count(); marker++) {
			double weight = movingBodyWeight(request, marker);
			if (weight <= 0.0) continue;
			weightedVolume += weight * particles.volume(marker);
			coupledMarkers++;
		}
		if (!(weightedVolume > 0.0)) return MovingBodyFluidCoupling.Result.NONE;
		double volumeScale = Math.min(1.0, requestedCellVolume / weightedVolume);
		double impulseX = 0.0, impulseY = 0.0, impulseZ = 0.0, coupledVolume = 0.0;
		boolean changed = false;
		for (int marker = 0; marker < particles.count(); marker++) {
			double weight = movingBodyWeight(request, marker);
			double blend = volumeScale * weight;
			if (blend <= 0.0) continue;
			float oldX = particles.velocityX(marker), oldY = particles.velocityY(marker), oldZ = particles.velocityZ(marker);
			float newX = (float) (oldX + blend * (request.velocityXMetresPerSecond() - oldX));
			float newY = (float) (oldY + blend * (request.velocityYMetresPerSecond() - oldY));
			float newZ = (float) (oldZ + blend * (request.velocityZMetresPerSecond() - oldZ));
			particles.setVelocityX(marker, newX); particles.setVelocityY(marker, newY); particles.setVelocityZ(marker, newZ);
			impulseX += particles.mass(marker) * (newX - oldX);
			impulseY += particles.mass(marker) * (newY - oldY);
			impulseZ += particles.mass(marker) * (newZ - oldZ);
			coupledVolume += blend * particles.volume(marker);
			changed |= newX != oldX || newY != oldY || newZ != oldZ;
		}
		if (changed) gridDirty = true;
		return new MovingBodyFluidCoupling.Result(coupledMarkers, coupledVolume * cellVolumeM3,
				impulseX * cellVolumeM3, impulseY * cellVolumeM3, impulseZ * cellVolumeM3);
	}

	private double movingBodyWeight(MovingBodyFluidCoupling.Request request, int marker) {
		return MovingBodyFluidCoupling.kernelWeight(
				particles.positionX(marker) - request.localXMetres(),
				particles.positionY(marker) - request.localYMetres(),
				particles.positionZ(marker) - request.localZMetres(), config.cellSize());
	}

	private static void requireFiniteMarkerVelocity(double velocity) {
		if (!Double.isFinite(velocity) || !Float.isFinite((float) velocity)) {
			throw new IllegalArgumentException("Moving-body coupling velocity exceeds marker representation");
		}
	}

	PhaseTransferStats transferGranularPhases(int[] dirtyCells) {
		if (dirtyCells.length == 0) return new PhaseTransferStats(0, 0, 0, 0L);
		boolean[] dirty = new boolean[cellCount];
		for (int cell : dirtyCells) {
			if (cell < 0 || cell >= cellCount) throw new IllegalStateException("Chemical dirty cell is outside the domain");
			dirty[cell] = true;
		}
		@SuppressWarnings("unchecked")
		TreeMap<Integer, List<Integer>>[] liquidByPhase = new TreeMap[cellCount];
		for (int marker = 0; marker < particles.count(); marker++) {
			int cell = particleCellIndex(marker); if (!dirty[cell]) continue;
			int phase = chemicalParcels.registry().phaseHandleForMaterial(
					materialRegistry.material(particles.materialHandle(marker)).id());
			if (liquidByPhase[cell] == null) liquidByPhase[cell] = new TreeMap<>();
			liquidByPhase[cell].computeIfAbsent(phase, ignored -> new ArrayList<>()).add(marker);
		}
		int transfers = 0, precipitated = 0, dissolved = 0, evaluations = 0; long signedSvq = 0L;
		for (int cell : dirtyCells) {
			if (liquidByPhase[cell] == null) continue;
			for (SolidLiquidReaction rule : granularState.registry.reactions()) {
				if (++evaluations > GranularLimits.MAX_OPERATIONS_PER_LOGICAL_STEP) {
					throw new GranularPhaseTransferEngine.BlockedException("Phase-transfer operation budget exceeded");
				}
				int phase = chemicalParcels.registry().phaseHandle(rule.liquidPhaseId());
				List<Integer> markers = liquidByPhase[cell].get(phase); if (markers == null || markers.isEmpty()) continue;
				AccessibleSolid accessible = accessibleSolid(cell, rule);
				long existingExtent = accessible == null ? 0L : accessible.totalExtent();
				if (existingExtent == 0L && !hasCompleteAqueousExtent(markers, rule)) continue;
				long perStepBatches = Math.min(rule.accessibleSurfaceSvqPerCellStep(),
						granularState.registry.material(granularState.registry.materialHandle(rule.solidMaterialId())).maximumActiveParcelSvq())
						/ rule.solidVolumeSvqPerBatch();
				long perStepExtent = Math.multiplyExact(perStepBatches, rule.solidExtentPerBatch());
				if (perStepExtent <= 0L) throw new GranularPhaseTransferEngine.BlockedException(
						"Solid-liquid rule is below one representable transfer extent");
				List<GranularPhaseTransferEngine.LiquidParcel> liquid = new ArrayList<>(markers.size());
				for (int marker : markers) liquid.add(new GranularPhaseTransferEngine.LiquidParcel(marker,
						particles.volume(marker), particles.mass(marker), chemicalParcels.record(marker)));
				GranularPhaseTransferEngine.Plan plan = granularPhaseTransfer.plan(rule, liquid, existingExtent, perStepExtent);
				if (plan.changed() && !plan.precipitation() && accessible != null
						&& -plan.signedExtent() > accessible.selected().extent()) {
					plan = granularPhaseTransfer.plan(rule, liquid, existingExtent,
							Math.min(perStepExtent, accessible.selected().extent()));
				}
				if (!plan.changed()) continue;
				if (plan.precipitation()) commitPrecipitation(cell, rule, plan);
				else {
					if (accessible == null || accessible.selected() == null) throw new GranularPhaseTransferEngine.BlockedException(
							"Dissolution target has no accessible solid inventory");
					commitDissolution(cell, rule, plan, accessible.selected());
				}
				transfers++; if (plan.precipitation()) precipitated++; else dissolved++;
				signedSvq = Math.addExact(signedSvq, plan.signedSolidSvq());
			}
		}
		if (transfers > 0) gridDirty = true;
		return new PhaseTransferStats(transfers, precipitated, dissolved, signedSvq);
	}

	private boolean hasCompleteAqueousExtent(List<Integer> markers, SolidLiquidReaction rule) {
		for (Map.Entry<String, Integer> term : rule.aqueousStoichiometry().entrySet()) {
			int species = chemicalParcels.registry().speciesHandle(term.getKey());
			long available = 0L;
			for (int marker : markers) {
				available = Math.addExact(available, chemicalParcels.record(marker).amount(species));
			}
			if (available < term.getValue()) return false;
		}
		return true;
	}

	private AccessibleSolid accessibleSolid(int cell, SolidLiquidReaction rule) {
		long total = 0L; SolidEntity selected = null;
		int material = granularState.registry.materialHandle(rule.solidMaterialId());
		for (int parcelIndex = 0; parcelIndex < granularState.parcels.count(); parcelIndex++) {
			GranularParcelStore.Parcel parcel = granularState.parcels.parcel(parcelIndex);
			if (parcel.materialHandle() != material || granularParcelCell(parcel) != cell) continue;
			long extent = matchingSolidExtent(parcel.solidCompositionHandle(), parcel.solidVolumeSvq(), parcel.mass(), rule);
			if (extent <= 0L) continue;
			total = Math.addExact(total, extent);
			if (selected == null) selected = SolidEntity.parcel(parcelIndex, parcel, extent);
		}
		GranularDepositStore.Group exposed = granularState.deposits.dominantGroup(cell);
		if (exposed != null && exposed.materialHandle() == material) {
			long extent = matchingSolidExtent(exposed.solidCompositionHandle(), exposed.solidVolumeSvq(), exposed.mass(), rule);
			if (extent > 0L) {
				total = Math.addExact(total, extent);
				if (selected == null) selected = SolidEntity.deposit(cell, exposed, extent);
			}
		}
		return total == 0L ? null : new AccessibleSolid(total, selected);
	}

	private long matchingSolidExtent(int compositionHandle, long solidSvq, double mass, SolidLiquidReaction rule) {
		if (compositionHandle == 0 || !granularState.compositions.isLive(compositionHandle)) return 0L;
		SolidCompositionRecord composition = granularState.compositions.record(compositionHandle);
		if (composition.massResidual() != 0.0 || composition.entryCount() != rule.solidComposition().size()
				|| !massClose(composition.totalMass(chemicalParcels.registry()), mass)) return 0L;
		long extent = -1L;
		for (Map.Entry<String, Integer> term : rule.solidComposition().entrySet()) {
			long amount = composition.amount(chemicalParcels.registry().speciesHandle(term.getKey()));
			if (amount <= 0L || amount % term.getValue() != 0L) return 0L;
			long candidate = amount / term.getValue();
			if (extent < 0L) extent = candidate; else if (extent != candidate) return 0L;
		}
		return extent > 0L && extent % rule.solidExtentPerBatch() == 0L
				&& Math.multiplyExact(extent / rule.solidExtentPerBatch(), rule.solidVolumeSvqPerBatch()) == solidSvq
				? extent : 0L;
	}

	private void commitPrecipitation(
			int cell, SolidLiquidReaction rule, GranularPhaseTransferEngine.Plan plan
	) {
		SignedDyadic pendingVolume = planGranularPhaseVolumeEvent(cell, plan);
		int[] markers = updateMarkers(plan); ChemicalMixtureRecord[] mixtures = updateMixtures(plan);
		chemicalParcels.requireCanCommitPlannedReplacements(markers, mixtures);
		int carrierMarker = precipitationCarrierMarker(cell, plan);
		int x = cell % sizeX, yz = cell / sizeX, y = yz % sizeY, z = yz / sizeY;
		long spawnSequence = ((long) cell << 16) + granularState.parcels.count();
		GranularCouplingFields.Position position = granularCollision.spawnPosition(
				x, y, z, plan.signedSolidSvq(), spawnSequence,
				particles.positionX(carrierMarker), particles.positionY(carrierMarker), particles.positionZ(carrierMarker));
		if (position == null) {
			throw new GranularPhaseTransferEngine.BlockedException("Precipitation cell has no collision-valid spawn point");
		}
		granularCoupling.rebuild(granularState.parcels, granularState.deposits);
		long[] occupiedBefore = granularCoupling.copySolidSvq();
		int material = granularState.registry.materialHandle(rule.solidMaterialId());
		PrecipitationMerge merge = precipitationMerge(cell, material, plan, position, carrierMarker, occupiedBefore);
		if (merge != null) {
			ThermalDomainState.ReactionPlan thermalPlan = thermalState == null ? null : thermalState.requireGranularReaction(
					cell, thermalReactionMarkers(plan), new GranularEnergyState.GranularReactionEdit(
							GranularEnergyState.GranularReactionKind.REPLACE_ACTIVE, merge.parcelIndex(), null,
							material, merge.parcel().mass(),
							merge.composition().stableAmounts(chemicalParcels.registry())), thermalMarkerContext());
			chemicalParcels.commitPlannedReplacements(markers, mixtures); applyMarkerUpdates(plan);
			granularState.compositions.overwrite(merge.compositionHandle(), merge.composition());
			granularState.parcels.overwrite(merge.parcelIndex(), merge.parcel());
			if (thermalPlan != null) thermalState.commitGranularReaction(thermalPlan, thermalMarkerContext());
			commitPendingPhaseVolume(cell, pendingVolume);
			markPhaseTransferDirty(cell);
			return;
		}
		if (!granularState.compositions.hasCapacity(1) || !granularState.parcels.hasCapacity(1)) {
			throw new GranularPhaseTransferEngine.BlockedException("Precipitation parcel/composition budget exceeded");
		}
		if (!granularCoupling.tryPlanParcel(occupiedBefore, position, plan.signedSolidSvq(),
				this::granularGeometricOpenSvq)) {
			throw new GranularPhaseTransferEngine.BlockedException("Precipitation active parcel exceeds exact joint occupancy");
		}
		int composition = granularState.compositions.create(plan.transferredComposition());
		GranularParcelStore.Parcel parcel = new GranularParcelStore.Parcel(position.x(), position.y(), position.z(),
				particles.velocityX(carrierMarker), particles.velocityY(carrierMarker), particles.velocityZ(carrierMarker),
				plan.signedSolidSvq(), plan.signedSolidMass(), material,
				composition, 0, (byte) 0);
		if (!granularState.parcels.canAdd(parcel)) {
			granularState.compositions.release(composition);
			throw new GranularPhaseTransferEngine.BlockedException("Precipitation active parcel budget exceeded");
		}
		ThermalDomainState.ReactionPlan thermalPlan = thermalState == null ? null : thermalState.requireGranularReaction(
				cell, thermalReactionMarkers(plan), new GranularEnergyState.GranularReactionEdit(
						GranularEnergyState.GranularReactionKind.ADD_ACTIVE, -1, null, material, parcel.mass(),
						plan.transferredComposition().stableAmounts(chemicalParcels.registry())), thermalMarkerContext());
		try { chemicalParcels.commitPlannedReplacements(markers, mixtures); }
		catch (RuntimeException failure) { granularState.compositions.release(composition); throw failure; }
		applyMarkerUpdates(plan); granularState.parcels.add(parcel);
		if (thermalPlan != null) {
			thermalState.addGranularReactionActive();
			thermalState.commitGranularReaction(thermalPlan, thermalMarkerContext());
		}
		commitPendingPhaseVolume(cell, pendingVolume);
		markPhaseTransferDirty(cell);
	}

	private PrecipitationMerge precipitationMerge(
			int cell, int material, GranularPhaseTransferEngine.Plan plan,
			GranularCouplingFields.Position spawned, int carrierMarker, long[] occupiedBefore
	) {
		long maximum = granularState.registry.material(material).maximumActiveParcelSvq();
		float velocityX = particles.velocityX(carrierMarker), velocityY = particles.velocityY(carrierMarker);
		float velocityZ = particles.velocityZ(carrierMarker);
		float maximumVelocityDelta = granularMergeVelocityDelta();
		for (int parcelIndex = 0; parcelIndex < granularState.parcels.count(); parcelIndex++) {
			GranularParcelStore.Parcel existing = granularState.parcels.parcel(parcelIndex);
			if (existing.materialHandle() != material || existing.solidCompositionHandle() == 0
					|| granularParcelCell(existing) != cell) continue;
			long combinedSvq = Math.addExact(existing.solidVolumeSvq(), plan.signedSolidSvq());
			if (combinedSvq > maximum || !plan.transferredComposition().proportionalTo(
					granularState.compositions.record(existing.solidCompositionHandle()),
					plan.signedSolidSvq(), existing.solidVolumeSvq())) continue;
			float deltaX = existing.velocityX() - velocityX, deltaY = existing.velocityY() - velocityY;
			float deltaZ = existing.velocityZ() - velocityZ;
			if (deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ
					> maximumVelocityDelta * maximumVelocityDelta) continue;
			double combinedMass = existing.mass() + plan.signedSolidMass();
			float x = weighted(existing.x(), existing.solidVolumeSvq(), spawned.x(), plan.signedSolidSvq(), combinedSvq);
			float y = weighted(existing.y(), existing.solidVolumeSvq(), spawned.y(), plan.signedSolidSvq(), combinedSvq);
			float z = weighted(existing.z(), existing.solidVolumeSvq(), spawned.z(), plan.signedSolidSvq(), combinedSvq);
			if (!granularCollision.positionValid(x, y, z, combinedSvq)) continue;
			SolidCompositionRecord composition = granularState.compositions.record(existing.solidCompositionHandle())
					.plus(plan.transferredComposition());
			GranularParcelStore.Parcel merged = new GranularParcelStore.Parcel(x, y, z,
					weighted(existing.velocityX(), existing.mass(), velocityX, plan.signedSolidMass(), combinedMass),
					weighted(existing.velocityY(), existing.mass(), velocityY, plan.signedSolidMass(), combinedMass),
					weighted(existing.velocityZ(), existing.mass(), velocityZ, plan.signedSolidMass(), combinedMass),
					combinedSvq, combinedMass, material, existing.solidCompositionHandle(), 0, (byte) 0);
			long[] planned = occupiedBefore.clone();
			granularCoupling.removePlannedParcel(planned,
					new GranularCouplingFields.Position(existing.x(), existing.y(), existing.z()), existing.solidVolumeSvq());
			if (!granularCoupling.tryPlanParcel(planned, new GranularCouplingFields.Position(x, y, z), combinedSvq,
					this::granularGeometricOpenSvq)) continue;
			return new PrecipitationMerge(parcelIndex, existing.solidCompositionHandle(), composition, merged);
		}
		return null;
	}

	private int precipitationCarrierMarker(int cell, GranularPhaseTransferEngine.Plan plan) {
		int candidates = 0;
		for (GranularPhaseTransferEngine.MarkerUpdate update : plan.markerUpdates()) {
			if (particles.volume(update.marker()) > update.cellVolume()) candidates++;
		}
		if (candidates == 0) throw new GranularPhaseTransferEngine.BlockedException(
				"Precipitation did not remove volume from a carrier marker");
		int selected = Math.floorMod((long) cell * 31L + granularState.parcels.count(), candidates);
		for (GranularPhaseTransferEngine.MarkerUpdate update : plan.markerUpdates()) {
			if (particles.volume(update.marker()) <= update.cellVolume()) continue;
			if (selected-- == 0) return update.marker();
		}
		throw new IllegalStateException("Precipitation carrier selection changed during planning");
	}

	private void commitDissolution(
			int cell, SolidLiquidReaction rule, GranularPhaseTransferEngine.Plan plan, SolidEntity solid
	) {
		SignedDyadic pendingVolume = planGranularPhaseVolumeEvent(cell, plan);
		if (!rule.reversible()) throw new GranularPhaseTransferEngine.BlockedException("Solid-liquid rule is not reversible");
		long removedSvq = -plan.signedSolidSvq(); double removedMass = -plan.signedSolidMass();
		if (removedSvq <= 0L || removedSvq > solid.solidSvq()) {
			throw new GranularPhaseTransferEngine.BlockedException("Dissolution exceeds accessible solid inventory");
		}
		SolidCompositionRecord original = granularState.compositions.record(solid.compositionHandle());
		boolean full = removedSvq == solid.solidSvq();
		SolidCompositionRecord remaining = full ? null : subtractComposition(original, plan.transferredComposition());
		double remainingMass = solid.mass() - removedMass;
		if (!full && (!(remainingMass > 0.0) || !massClose(remaining.totalMass(chemicalParcels.registry()), remainingMass))) {
			throw new GranularPhaseTransferEngine.BlockedException("Dissolution solid mass split is not representable");
		}
		if (solid.deposit()) {
			GranularDepositStore.SolidPortion preview = granularState.deposits.peekTakeCompositionAware(cell,
					solid.materialHandle(), solid.compositionHandle(), removedSvq);
			if (!massClose(preview.mass(), removedMass)) throw new GranularPhaseTransferEngine.BlockedException(
					"Dissolution deposit mass split is not representable");
		}
		int[] markers = updateMarkers(plan); ChemicalMixtureRecord[] mixtures = updateMixtures(plan);
		chemicalParcels.requireCanCommitPlannedReplacements(markers, mixtures);
		GranularEnergyColumn.DepositKey thermalDepositKey = solid.deposit()
				? new GranularEnergyColumn.DepositKey(cell, solid.materialHandle(), solid.compositionHandle()) : null;
		GranularEnergyState.GranularReactionKind thermalKind = solid.deposit()
				? (full ? GranularEnergyState.GranularReactionKind.REMOVE_DEPOSIT
				: GranularEnergyState.GranularReactionKind.REPLACE_DEPOSIT)
				: (full ? GranularEnergyState.GranularReactionKind.REMOVE_ACTIVE
				: GranularEnergyState.GranularReactionKind.REPLACE_ACTIVE);
		Map<String, Long> finalComposition = full ? Map.of()
				: remaining.stableAmounts(chemicalParcels.registry());
		ThermalDomainState.ReactionPlan thermalPlan = thermalState == null ? null : thermalState.requireGranularReaction(
				cell, thermalReactionMarkers(plan), new GranularEnergyState.GranularReactionEdit(thermalKind,
						solid.deposit() ? -1 : solid.index(), thermalDepositKey,
						full ? -1 : solid.materialHandle(), full ? 0.0 : remainingMass,
						finalComposition), thermalMarkerContext());
		if (thermalState != null) {
			if (solid.deposit()) thermalState.previewDepositTake(thermalDepositKey, removedSvq, solid.solidSvq());
			else thermalState.previewActiveTake(solid.index(), removedSvq, solid.solidSvq());
		}
		chemicalParcels.commitPlannedReplacements(markers, mixtures); applyMarkerUpdates(plan);
		if (solid.deposit()) granularState.deposits.takeCompositionAware(cell, solid.materialHandle(),
				solid.compositionHandle(), removedSvq);
		else if (full) granularState.parcels.removeSwap(solid.index());
		else {
			GranularParcelStore.Parcel parcel = granularState.parcels.parcel(solid.index());
			granularState.parcels.overwrite(solid.index(), new GranularParcelStore.Parcel(parcel.x(), parcel.y(), parcel.z(),
					parcel.velocityX(), parcel.velocityY(), parcel.velocityZ(), parcel.solidVolumeSvq() - removedSvq,
					remainingMass, parcel.materialHandle(), parcel.solidCompositionHandle(), parcel.contactAge(), parcel.flags()));
		}
		if (thermalPlan != null) {
			if (solid.deposit()) thermalState.takeGranularReactionDeposit(
					thermalDepositKey, removedSvq, solid.solidSvq());
			else thermalState.takeGranularReactionActive(solid.index(), removedSvq, solid.solidSvq());
		}
		if (full) granularState.compositions.release(solid.compositionHandle()); else granularState.compositions.overwrite(
				solid.compositionHandle(), remaining);
		if (thermalPlan != null) thermalState.commitGranularReaction(thermalPlan, thermalMarkerContext());
		commitPendingPhaseVolume(cell, pendingVolume);
		markPhaseTransferDirty(cell);
	}

	private SignedDyadic planGranularPhaseVolumeEvent(int cell, GranularPhaseTransferEngine.Plan plan) {
		if (thermalState == null) return SignedDyadic.ZERO;
		SignedDyadic delta = cellVolumeDyadic(plan.signedSolidSvq());
		for (GranularPhaseTransferEngine.MarkerUpdate update : plan.markerUpdates()) {
			delta = delta.add(SignedDyadic.of(update.cellVolume()))
					.subtract(SignedDyadic.of(particles.volume(update.marker())));
		}
		return delta;
	}

	private void commitPendingPhaseVolume(int cell, long signedSvq) {
		commitPendingPhaseVolume(cell, cellVolumeDyadic(signedSvq));
	}

	private void commitPendingPhaseVolume(int cell, SignedDyadic signedCellVolume) {
		if (thermalState != null && !signedCellVolume.equals(SignedDyadic.ZERO)) {
			pendingPhaseCellVolume[cell] = pendingPhaseCellVolume[cell].add(signedCellVolume);
		}
	}

	private static SignedDyadic cellVolumeDyadic(long signedSvq) {
		return signedSvq == 0L ? SignedDyadic.ZERO
				: new SignedDyadic(java.math.BigInteger.valueOf(signedSvq), -24);
	}

	private SolidCompositionRecord subtractComposition(
			SolidCompositionRecord original, SolidCompositionRecord removed
	) {
		TreeMap<String, Long> remaining = new TreeMap<>();
		for (int entry = 0; entry < original.entryCount(); entry++) {
			int species = original.speciesHandle(entry);
			long amount = original.amountAt(entry) - removed.amount(species);
			if (amount < 0L) throw new GranularPhaseTransferEngine.BlockedException("Dissolution composition underflow");
			if (amount > 0L) remaining.put(chemicalParcels.registry().species(species).id(), amount);
		}
		if (remaining.isEmpty()) throw new GranularPhaseTransferEngine.BlockedException("Partial dissolution emptied solid composition");
		return SolidCompositionRecord.fromStableAmounts(chemicalParcels.registry(), remaining, original.massResidual());
	}

	private int[] updateMarkers(GranularPhaseTransferEngine.Plan plan) {
		int[] result = new int[plan.markerUpdates().size()];
		for (int index = 0; index < result.length; index++) result[index] = plan.markerUpdates().get(index).marker();
		return result;
	}

	private ChemicalMixtureRecord[] updateMixtures(GranularPhaseTransferEngine.Plan plan) {
		ChemicalMixtureRecord[] result = new ChemicalMixtureRecord[plan.markerUpdates().size()];
		for (int index = 0; index < result.length; index++) result[index] = plan.markerUpdates().get(index).mixture();
		return result;
	}

	private List<GranularEnergyState.ReactionMarker> thermalReactionMarkers(
			GranularPhaseTransferEngine.Plan plan
	) {
		ArrayList<GranularEnergyState.ReactionMarker> result = new ArrayList<>(plan.markerUpdates().size());
		for (GranularPhaseTransferEngine.MarkerUpdate update : plan.markerUpdates()) result.add(
				new GranularEnergyState.ReactionMarker(update.marker(), update.mass(), update.mixture()));
		return List.copyOf(result);
	}

	private void applyMarkerUpdates(GranularPhaseTransferEngine.Plan plan) {
		for (GranularPhaseTransferEngine.MarkerUpdate update : plan.markerUpdates()) {
			particles.setVolume(update.marker(), update.cellVolume());
			particles.setMass(update.marker(), update.mass());
		}
	}

	private void markPhaseTransferDirty(int cell) {
		chemicalParcels.markDirtyCell(cell); granularDepositDynamics.disturbCell(cell); gridDirty = true;
	}

	int granularParcelCell(GranularParcelStore.Parcel parcel) {
		int x = Math.min(sizeX - 1, Math.max(0, (int) Math.floor(parcel.x() / config.cellSize())));
		int y = Math.min(sizeY - 1, Math.max(0, (int) Math.floor(parcel.y() / config.cellSize())));
		int z = Math.min(sizeZ - 1, Math.max(0, (int) Math.floor(parcel.z() / config.cellSize())));
		return cellIndex(x, y, z);
	}

	static boolean massClose(double left, double right) {
		return Math.abs(left - right) <= Math.max(1.0E-9, Math.max(Math.ulp(left), Math.ulp(right)) * 8.0);
	}

	private float granularMergeVelocityDelta() {
		return Math.max(config.cellSize() * 0.5F, config.gravity() * config.timeStep() * 1.25F);
	}

	private static float weighted(float left, long leftWeight, float right, long rightWeight, long total) {
		return (float) (((double) left * leftWeight + (double) right * rightWeight) / total);
	}

	private static float weighted(float left, double leftWeight, float right, double rightWeight, double total) {
		return (float) ((left * leftWeight + right * rightWeight) / total);
	}

	private record AccessibleSolid(long totalExtent, SolidEntity selected) { }
	private record PrecipitationMerge(
			int parcelIndex, int compositionHandle, SolidCompositionRecord composition,
			GranularParcelStore.Parcel parcel
	) { }
	private record SolidEntity(
			boolean deposit, int index, int materialHandle, int compositionHandle,
			long solidSvq, double mass, long extent
	) {
		static SolidEntity parcel(int index, GranularParcelStore.Parcel parcel, long extent) {
			return new SolidEntity(false, index, parcel.materialHandle(), parcel.solidCompositionHandle(),
					parcel.solidVolumeSvq(), parcel.mass(), extent);
		}
		static SolidEntity deposit(int cell, GranularDepositStore.Group group, long extent) {
			return new SolidEntity(true, cell, group.materialHandle(), group.solidCompositionHandle(),
					group.solidVolumeSvq(), group.mass(), extent);
		}
	}
	public record PhaseTransferStats(int transfers, int precipitations, int dissolutions, long signedSolidSvq) { }

	float granularMovingSolidDivergence(int index, float dt) {
		return granularNextCoupling == null || pressureGranularCoupling != granularNextCoupling
				? 0.0F : granularCoupling.movingSolidTargetDivergence(index,
						granularNextCoupling.solidSvq(index), dt);
	}

	boolean granularOccupancyFitsGeometry(GranularCouplingFields coupling) {
		for (int cell = 0; cell < cellCount; cell++) {
			if (coupling.solidSvq(cell) > SolidVolume.fromCellVolumeExact(geometry.cutCells.openCellVolume(cell))) return false;
		}
		return true;
	}

	private boolean planGranularMerge(
			long[] planned, GranularParcelStore.Parcel first, GranularParcelStore.Parcel second,
			GranularParcelStore.Parcel merged
	) {
		if (!granularCollision.positionValid(merged.x(), merged.y(), merged.z(), merged.solidVolumeSvq())) return false;
		GranularCouplingFields.Position firstPosition = new GranularCouplingFields.Position(first.x(), first.y(), first.z());
		GranularCouplingFields.Position secondPosition = new GranularCouplingFields.Position(second.x(), second.y(), second.z());
		granularCoupling.removePlannedParcel(planned, firstPosition, first.solidVolumeSvq());
		granularCoupling.removePlannedParcel(planned, secondPosition, second.solidVolumeSvq());
		GranularCouplingFields.Position mergedPosition = new GranularCouplingFields.Position(
				merged.x(), merged.y(), merged.z());
		if (granularCoupling.tryPlanParcel(planned, mergedPosition, merged.solidVolumeSvq(),
				this::granularGeometricOpenSvq)) return true;
		if (!granularCoupling.tryPlanParcel(planned, firstPosition, first.solidVolumeSvq(),
				this::granularGeometricOpenSvq)
				|| !granularCoupling.tryPlanParcel(planned, secondPosition, second.solidVolumeSvq(),
				this::granularGeometricOpenSvq)) {
			throw new IllegalStateException("Rejected granular compaction could not restore exact occupancy");
		}
		return false;
	}

	boolean hasAnySurfaceTension() {
		if (materialRegistry == null) return false;
		for (FluidMaterial material : materialRegistry.materials()) {
			if (material.surfaceTension() > 0.0) return true;
		}
		return false;
	}

	boolean hasAnyLiquidPairTension() {
		if (materialRegistry == null) return false;
		for (double tension : materialRegistry.liquidPairTensions().values()) {
			if (tension > 0.0) return true;
		}
		return false;
	}

	/**
	 * Adds balanced-force CSF momentum for every immiscible liquid pair with a positive {@code sigma12}.
	 * The force uses the same face density scaling as the variable-density pressure projection, and the
	 * pressure RHS is built from the resulting velocity, so the pressure solve cancels the gradient part.
	 */
	/**
	 * Liquid-gas surface tension for a pressure cell. Immiscible phases use the first registry material
	 * of the dominant phase; miscible composition-dependent tension is not modelled yet.
	 */
	private double surfaceTensionAtCell(int cell) {
		if (materialRegistry == null) return 0.0;
		int phase = dominantPhase(cell);
		if (phase == NO_PHASE) return 0.0;
		for (int handle = 0; handle < materialRegistry.size(); handle++) {
			if (materialRegistry.phaseHandle(handle) == phase) {
				return materialRegistry.material(handle).surfaceTension();
			}
		}
		return 0.0;
	}

	/** Rebuilds the cell-centred surface-tension pressure {@code sigma * kappa} used as the free-surface jump. */
	void rebuildSurfaceTensionPressure() {
		Arrays.fill(tension.surfaceTensionPressure, 0.0F);
		if (!surfaceTensionActive) return;
		for (int index = 0; index < cellCount; index++) {
			if (geometry.solid[index]) {
				tension.surfaceTensionColor[index] = 0.0;
				continue;
			}
			double openVolume = effectiveOpenCellVolume(index);
			tension.surfaceTensionColor[index] = openVolume > 0.0
					? clamp((float) (markerGrid.exactCellMarkerVolume[index] / openVolume), 0.0F, 1.0F)
					: 0.0;
		}
		smoothSurfaceTensionColor();
		rebuildSurfaceTensionNormals();
		for (int z = 0; z < sizeZ; z++) for (int y = 0; y < sizeY; y++) for (int x = 0; x < sizeX; x++) {
			int index = cellIndex(x, y, z);
			if (geometry.solid[index] || markerGrid.markerCount[index] == 0) continue;
			double sigma = surfaceTensionAtCell(index);
			if (sigma <= 0.0) continue;
			double kappa = surfaceTensionMeanCurvature(x, y, z);
			tension.surfaceTensionPressure[index] = (float) (sigma * kappa);
		}
	}

	void smoothSurfaceTensionColor() {
		for (int z = 0; z < sizeZ; z++) for (int y = 0; y < sizeY; y++) for (int x = 0; x < sizeX; x++) {
			int index = cellIndex(x, y, z);
			double sum = 0.0;
			int count = 0;
			for (int dz = -1; dz <= 1; dz++) for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
				int sx = x + dx, sy = y + dy, sz = z + dz;
				if (!inside(sx, sy, sz)) continue;
				int neighbor = cellIndex(sx, sy, sz);
				if (geometry.solid[neighbor]) continue;
				sum += tension.surfaceTensionColor[neighbor];
				count++;
			}
			tension.surfaceTensionSmoothed[index] = count == 0 ? tension.surfaceTensionColor[index] : sum / count;
		}
	}

	private double surfaceTensionCentralDifference(int x, int y, int z, int dx, int dy, int dz) {
		int xPlus = Math.min(sizeX - 1, Math.max(0, x + dx));
		int xMinus = Math.min(sizeX - 1, Math.max(0, x - dx));
		int yPlus = Math.min(sizeY - 1, Math.max(0, y + dy));
		int yMinus = Math.min(sizeY - 1, Math.max(0, y - dy));
		int zPlus = Math.min(sizeZ - 1, Math.max(0, z + dz));
		int zMinus = Math.min(sizeZ - 1, Math.max(0, z - dz));
		return 0.5 * (tension.surfaceTensionSmoothed[cellIndex(xPlus, yPlus, zPlus)]
				- tension.surfaceTensionSmoothed[cellIndex(xMinus, yMinus, zMinus)]);
	}

	/** Unit interface normal at a cell centre, pointing from gas into liquid, computed once per cell into the cache. */
	void rebuildSurfaceTensionNormals() {
		double epsilon = 1.0E-6 / config.cellSize();
		for (int z = 0; z < sizeZ; z++) for (int y = 0; y < sizeY; y++) for (int x = 0; x < sizeX; x++) {
			int index = cellIndex(x, y, z);
			if (geometry.solid[index]) {
				tension.surfaceTensionNormalX[index] = 0.0;
				tension.surfaceTensionNormalY[index] = 0.0;
				tension.surfaceTensionNormalZ[index] = 1.0;
				continue;
			}
			double gradientX = surfaceTensionCentralDifference(x, y, z, 1, 0, 0);
			double gradientY = surfaceTensionCentralDifference(x, y, z, 0, 1, 0);
			double gradientZ = surfaceTensionCentralDifference(x, y, z, 0, 0, 1);
			double length = Math.sqrt(gradientX * gradientX + gradientY * gradientY + gradientZ * gradientZ);
			if (length <= epsilon) {
				tension.surfaceTensionNormalX[index] = 0.0;
				tension.surfaceTensionNormalY[index] = 0.0;
				tension.surfaceTensionNormalZ[index] = 1.0;
				continue;
			}
			double normalX = gradientX / length;
			double normalY = gradientY / length;
			double normalZ = gradientZ / length;
			double[] solidNormal = surfaceTensionSolidNormal(x, y, z);
			if (solidNormal == null) {
				tension.surfaceTensionNormalX[index] = normalX;
				tension.surfaceTensionNormalY[index] = normalY;
				tension.surfaceTensionNormalZ[index] = normalZ;
				continue;
			}
			double contactAngle = surfaceTensionContactAngleAtCell(index);
			double cosTheta = Math.cos(contactAngle);
			double sinTheta = Math.sin(contactAngle);
			double nsx = solidNormal[0], nsy = solidNormal[1], nsz = solidNormal[2];
			double dot = normalX * nsx + normalY * nsy + normalZ * nsz;
			double tangentX = normalX - dot * nsx;
			double tangentY = normalY - dot * nsy;
			double tangentZ = normalZ - dot * nsz;
			double tangentLength = Math.sqrt(tangentX * tangentX + tangentY * tangentY + tangentZ * tangentZ);
			if (tangentLength <= 1.0E-9) {
				tangentX = Math.abs(nsx) < 0.9 ? 1.0 : 0.0;
				tangentY = Math.abs(nsx) < 0.9 ? 0.0 : 1.0;
				tangentZ = 0.0;
				dot = tangentX * nsx + tangentY * nsy + tangentZ * nsz;
				tangentX -= dot * nsx;
				tangentY -= dot * nsy;
				tangentZ -= dot * nsz;
				tangentLength = Math.sqrt(tangentX * tangentX + tangentY * tangentY + tangentZ * tangentZ);
			}
			tension.surfaceTensionNormalX[index] = -cosTheta * nsx + sinTheta * tangentX / tangentLength;
			tension.surfaceTensionNormalY[index] = -cosTheta * nsy + sinTheta * tangentY / tangentLength;
			tension.surfaceTensionNormalZ[index] = -cosTheta * nsz + sinTheta * tangentZ / tangentLength;
		}
	}

	private double[] surfaceTensionSolidNormal(int x, int y, int z) {
		double normalX = 0.0, normalY = 0.0, normalZ = 0.0;
		int count = 0;
		for (int[] direction : SURFACE_TENSION_DIRECTIONS) {
			int dx = direction[0], dy = direction[1], dz = direction[2];
			int sx = x + dx, sy = y + dy, sz = z + dz;
			if (!inside(sx, sy, sz) || !geometry.solid[cellIndex(sx, sy, sz)]) continue;
			normalX -= dx;
			normalY -= dy;
			normalZ -= dz;
			count++;
		}
		if (count == 0) return null;
		double length = Math.sqrt(normalX * normalX + normalY * normalY + normalZ * normalZ);
		if (length <= 1.0E-9) return null;
		return new double[] {normalX / length, normalY / length, normalZ / length};
	}

	private double surfaceTensionContactAngleAtCell(int cell) {
		if (materialRegistry == null) return Math.PI / 2.0;
		int phase = dominantPhase(cell);
		if (phase == NO_PHASE) return Math.PI / 2.0;
		for (int handle = 0; handle < materialRegistry.size(); handle++) {
			if (materialRegistry.phaseHandle(handle) == phase) {
				return materialRegistry.material(handle).contactAngleRadians();
			}
		}
		return Math.PI / 2.0;
	}

	/** Mean curvature {@code kappa = -div(n)}; positive for a liquid sphere surrounded by gas. */
	double surfaceTensionMeanCurvature(int x, int y, int z) {
		double inverseTwoCell = 0.5 / config.cellSize();
		double nxPlus = surfaceTensionNeighborNormal(x, y, z, 1, 0, 0, 0);
		double nxMinus = surfaceTensionNeighborNormal(x, y, z, -1, 0, 0, 0);
		double nyPlus = surfaceTensionNeighborNormal(x, y, z, 0, 1, 0, 1);
		double nyMinus = surfaceTensionNeighborNormal(x, y, z, 0, -1, 0, 1);
		double nzPlus = surfaceTensionNeighborNormal(x, y, z, 0, 0, 1, 2);
		double nzMinus = surfaceTensionNeighborNormal(x, y, z, 0, 0, -1, 2);
		return -((nxPlus - nxMinus) + (nyPlus - nyMinus) + (nzPlus - nzMinus)) * inverseTwoCell;
	}

	/** Neighbor normal used in the divergence; a solid neighbour reuses the cell's own Young-adjusted normal. */
	private double surfaceTensionNeighborNormal(int x, int y, int z, int dx, int dy, int dz, int component) {
		int nx = x + dx, ny = y + dy, nz = z + dz;
		if (inside(nx, ny, nz) && geometry.solid[cellIndex(nx, ny, nz)]) {
			return cachedSurfaceTensionNormal(cellIndex(x, y, z), component);
		}
		return cachedSurfaceTensionNormal(cellIndex(Math.min(sizeX - 1, Math.max(0, nx)),
				Math.min(sizeY - 1, Math.max(0, ny)),
				Math.min(sizeZ - 1, Math.max(0, nz))), component);
	}

	private double cachedSurfaceTensionNormal(int cell, int component) {
		return switch (component) {
			case 0 -> tension.surfaceTensionNormalX[cell];
			case 1 -> tension.surfaceTensionNormalY[cell];
			default -> tension.surfaceTensionNormalZ[cell];
		};
	}

	/**
	 * Divergence of the free-surface pressure-jump boundary velocity. This is the RHS source that makes
	 * the ghost-fluid projection consistent with {@code p_interface = sigma * kappa + p_gas} at every
	 * open air face; the gas term reads the sealed cavity that owns the air cell and stays exactly
	 * zero for ambient air, so the liquid-only path is bit-identical whenever no sealed cavity exists.
	 */
	float surfaceTensionBoundaryDivergence(int index, float dt) {
		if (!surfaceTensionActive && !gasState.pressureActive) return 0.0F;
		int x = index % sizeX;
		int yz = index / sizeX;
		int y = yz % sizeY;
		int z = yz / sizeY;
		float inverseCell = 1.0F / config.cellSize();
		float scale = dt / (config.density() * config.cellSize());
		float result = 0.0F;
		if (x > 0) result += surfaceTensionBoundaryFace(index, cellIndex(x - 1, y, z), -1, 0, 0, scale, inverseCell);
		if (x + 1 < sizeX) result += surfaceTensionBoundaryFace(index, cellIndex(x + 1, y, z), 1, 0, 0, scale, inverseCell);
		if (y > 0) result += surfaceTensionBoundaryFace(index, cellIndex(x, y - 1, z), 0, -1, 0, scale, inverseCell);
		if (y + 1 < sizeY) result += surfaceTensionBoundaryFace(index, cellIndex(x, y + 1, z), 0, 1, 0, scale, inverseCell);
		if (z > 0) result += surfaceTensionBoundaryFace(index, cellIndex(x, y, z - 1), 0, 0, -1, scale, inverseCell);
		if (z + 1 < sizeZ) result += surfaceTensionBoundaryFace(index, cellIndex(x, y, z + 1), 0, 0, 1, scale, inverseCell);
		return result;
	}

	private float surfaceTensionBoundaryFace(int fluid, int neighbor, int directionX, int directionY, int directionZ,
	                                         float scale, float inverseCell) {
		if (!isAir(neighbor)) return 0.0F;
		float openArea = pressureOpenFaceFromCell(fluid, neighbor, directionX, directionY, directionZ);
		if (openArea <= 0.0F) return 0.0F;
		float theta = pressureInterfaceTheta(fluid, directionX, directionY, directionZ);
		if (theta <= 0.0F) return 0.0F;
		float jump = tension.surfaceTensionPressure[fluid] + gasPressureOfAirCell(neighbor) - pressureReference;
		float densityScale = variableDensityActive ? pressureDensityScale(fluid, neighbor) : 1.0F;
		return -openArea * scale * densityScale * jump / theta * inverseCell;
	}

	float pressureDensityScale(int first, int second) {
		if (isFluid(first) && isFluid(second)) {
			return (float) (config.density() / materialFaceEffectiveDensity(first, second));
		}
		int fluid = isFluid(first) ? first : second;
		return (float) (config.density() / markerGrid.cellDensity[fluid]);
	}

	private double materialFaceEffectiveDensity(int one, int two) {
		int first = Math.min(one, two), second = Math.max(one, two);
		double fraction = 0.5;
		int firstPhase = dominantPhase(first), secondPhase = dominantPhase(second);
		if (firstPhase != NO_PHASE && secondPhase != NO_PHASE && firstPhase != secondPhase) {
			double firstSigned = phaseFraction(first, firstPhase) - 0.5;
			double secondSigned = phaseFraction(second, firstPhase) - 0.5;
			double denominator = firstSigned - secondSigned;
			if (firstSigned > 0.0 && secondSigned < 0.0 && denominator > 1.0E-12) {
				fraction = Math.max(0.05, Math.min(0.95, firstSigned / denominator));
			}
		}
		return fraction * markerGrid.cellDensity[first] + (1.0 - fraction) * markerGrid.cellDensity[second];
	}

	private int dominantPhase(int cell) {
		int dominant = NO_PHASE;
		double dominantVolume = -1.0;
		for (int entry = markerGrid.cellPhaseHead[cell]; entry >= 0; entry = markerGrid.phaseEntryNext[entry]) {
			double candidateVolume = markerGrid.phaseEntryVolume[entry];
			int candidate = markerGrid.phaseEntryLabel[entry];
			if (candidateVolume > dominantVolume || (candidateVolume == dominantVolume && candidate < dominant)) {
				dominant = candidate;
				dominantVolume = candidateVolume;
			}
		}
		return dominant;
	}

	double phaseFraction(int cell, int phase) {
		if (markerGrid.exactCellMarkerVolume[cell] <= 0.0) return 0.0;
		for (int entry = markerGrid.cellPhaseHead[cell]; entry >= 0; entry = markerGrid.phaseEntryNext[entry]) {
			if (markerGrid.phaseEntryLabel[entry] == phase) return markerGrid.phaseEntryVolume[entry] / markerGrid.exactCellMarkerVolume[cell];
		}
		return 0.0;
	}

	float openFaceFromCell(int cell, int directionX, int directionY, int directionZ) {
		int x = cell % sizeX;
		int yz = cell / sizeX;
		int y = yz % sizeY;
		int z = yz / sizeY;
		if (directionX < 0) return geometry.cutCells.xFaceOpen(x, y, z);
		if (directionX > 0) return geometry.cutCells.xFaceOpen(x + 1, y, z);
		if (directionY < 0) return geometry.cutCells.yFaceOpen(x, y, z);
		if (directionY > 0) return geometry.cutCells.yFaceOpen(x, y + 1, z);
		if (directionZ < 0) return geometry.cutCells.zFaceOpen(x, y, z);
		if (directionZ > 0) return geometry.cutCells.zFaceOpen(x, y, z + 1);
		throw new IllegalArgumentException("Pressure face direction must be axial");
	}

	float pressureOpenFaceFromCell(
			int cell, int neighbor, int directionX, int directionY, int directionZ
	) {
		float geometric = openFaceFromCell(cell, directionX, directionY, directionZ);
		if (pressureGranularCoupling == null || geometric <= 0.0F) return geometric;
		return geometric * pressureGranularCoupling.faceVoidScale(
				cell, geometry.cutCells.openCellVolume(cell), neighbor, geometry.cutCells.openCellVolume(neighbor));
	}

	float pressureXFaceOpen(int x, int y, int z) {
		float geometric = geometry.cutCells.xFaceOpen(x, y, z);
		if (pressureGranularCoupling == null || geometric <= 0.0F || x <= 0 || x >= sizeX) return geometric;
		int left = cellIndex(x - 1, y, z), right = cellIndex(x, y, z);
		return geometric * pressureGranularCoupling.faceVoidScale(
				left, geometry.cutCells.openCellVolume(left), right, geometry.cutCells.openCellVolume(right));
	}

	float pressureYFaceOpen(int x, int y, int z) {
		float geometric = geometry.cutCells.yFaceOpen(x, y, z);
		if (pressureGranularCoupling == null || geometric <= 0.0F || y <= 0 || y >= sizeY) return geometric;
		int below = cellIndex(x, y - 1, z), above = cellIndex(x, y, z);
		return geometric * pressureGranularCoupling.faceVoidScale(
				below, geometry.cutCells.openCellVolume(below), above, geometry.cutCells.openCellVolume(above));
	}

	float pressureZFaceOpen(int x, int y, int z) {
		float geometric = geometry.cutCells.zFaceOpen(x, y, z);
		if (pressureGranularCoupling == null || geometric <= 0.0F || z <= 0 || z >= sizeZ) return geometric;
		int back = cellIndex(x, y, z - 1), front = cellIndex(x, y, z);
		return geometric * pressureGranularCoupling.faceVoidScale(
				back, geometry.cutCells.openCellVolume(back), front, geometry.cutCells.openCellVolume(front));
	}

	private void spawnCellVolume(int x, int y, int z, float amount) {
		int requiredParticles = particlesRequired(amount);
		if ((long) particles.count() + requiredParticles > maximumParticleCount) {
			throw new ParticleBudgetExceededException(requiredParticles, maximumParticleCount - particles.count());
		}
		float remaining = amount;
		int slot = firstOpenSlot(cellIndex(x, y, z), 0);
		while (remaining > 1.0E-6F) {
			if (slot < 0) throw new IllegalStateException("Cut-cell open volume was exhausted while spawning markers");
			float piece = Math.min(PARTICLE_VOLUME, remaining);
			spawnParticle(x, y, z, slot, piece);
			remaining -= piece;
			slot = firstOpenSlot(cellIndex(x, y, z), slot + 1);
		}
	}

	int firstOpenSlot(int cell, int first) {
		int mask = geometry.cutCells.mask(cell);
		for (int slot = first; slot < 8; slot++) if ((mask & 1 << slot) == 0) return slot;
		return -1;
	}

private void spawnParticle(int cellX, int cellY, int cellZ, int slot, float amount) {
spawnParticle(cellX, cellY, cellZ, slot, amount, 0);
}

private void spawnParticle(int cellX, int cellY, int cellZ, int slot, float amount, int materialHandle) {
double mass = materialRegistry == null
? (double) amount * config.density()
: (double) amount * materialRegistry.material(materialHandle).referenceDensity();
spawnParticle(cellX, cellY, cellZ, slot, amount, materialHandle, mass);
}

private void spawnParticle(
int cellX, int cellY, int cellZ, int slot, float amount, int materialHandle, double mass
) {
spawnParticle(cellX, cellY, cellZ, slot, amount, materialHandle, mass, null, false);
}

private void spawnParticle(
 int cellX, int cellY, int cellZ, int slot, float amount, int materialHandle, double mass,
 ChemicalMixtureRecord chemicalMixture, boolean indicator
 ) {
	spawnParticle(cellX, cellY, cellZ, slot, amount, materialHandle, mass, chemicalMixture, indicator, true);
}

void spawnParticle(
 int cellX, int cellY, int cellZ, int slot, float amount, int materialHandle, double mass,
 ChemicalMixtureRecord chemicalMixture, boolean indicator, boolean thermalExternal
 ) {
if (particles.count() >= maximumParticleCount) throw new ParticleBudgetExceededException(1, 0);
if (!(Float.isFinite(amount) && amount > 0.0F)) throw new IllegalArgumentException("Marker volume must be finite and positive");
if (!(Double.isFinite(mass) && mass > 0.0)) throw new IllegalArgumentException("Marker mass must be finite and positive");
if (materialRegistry != null) materialRegistry.material(materialHandle);
else if (materialHandle != 0) throw new IllegalArgumentException("Material handle requires a bound material registry");
if (chemicalParcels != null && chemicalMixture != null) {
chemicalParcels.requireCompatible(chemicalMixture, materialRegistry.material(materialHandle).id(), indicator);
}
float h = config.cellSize();
float radiusFraction = 0.45F;
float ox = (slot & 1) == 0 ? 0.25F : 0.75F;
float oy = (slot & 2) == 0 ? 0.25F : 0.75F;
float oz = (slot & 4) == 0 ? 0.25F : 0.75F;
if (cellX == 0 || geometry.solid[cellIndex(cellX - 1, cellY, cellZ)]) ox = Math.max(ox, radiusFraction);
if (cellX + 1 == sizeX || geometry.solid[cellIndex(cellX + 1, cellY, cellZ)]) ox = Math.min(ox, 1.0F - radiusFraction);
if (cellY == 0 || geometry.solid[cellIndex(cellX, cellY - 1, cellZ)]) oy = Math.max(oy, radiusFraction);
if (cellY + 1 == sizeY || geometry.solid[cellIndex(cellX, cellY + 1, cellZ)]) oy = Math.min(oy, 1.0F - radiusFraction);
if (cellZ == 0 || geometry.solid[cellIndex(cellX, cellY, cellZ - 1)]) oz = Math.max(oz, radiusFraction);
if (cellZ + 1 == sizeZ || geometry.solid[cellIndex(cellX, cellY, cellZ + 1)]) oz = Math.min(oz, 1.0F - radiusFraction);
coordinator.appendMarker(
CarrierMutationPlan.append(carrierMutationRevision(), particles.count()),
cellIndex(cellX, cellY, cellZ), (cellX + ox) * h, (cellY + oy) * h, (cellZ + oz) * h,
amount, materialHandle, mass, chemicalMixture, indicator, thermalExternal);
 }

private void spawnChemicalParticle(
int cellX, int cellY, int cellZ, int slot, float amount, int materialHandle, double mass,
ChemicalMixtureRecord mixture, boolean indicator
) {
spawnParticle(cellX, cellY, cellZ, slot, amount, materialHandle, mass, mixture, indicator);
}

	/** Planned per-marker truncation for one cell, applied later without re-deciding anything. */
	private record MarkerTruncation(
			int marker, float take, float newVolume, double newMass,
			ChemicalParcelStore.SplitPlan chemicalPlan, LiquidEnergyColumn.Truncation thermalPlan
	) { }

	private record CellRemovalPlan(float removedVolume, List<MarkerTruncation> markers) { }

	private CellRemovalPlan planRemoveParticlesInCell(
			int cellX, int cellY, int cellZ, float maximum,
			ChemicalTransferLedger.Builder removedChemistry,
			RemovedMaterialLedger removedMaterials, RemovedThermalEnergy removedThermal
	) {
		float h = config.cellSize(), removed = 0.0F;
		List<MarkerTruncation> plan = new ArrayList<>();
		for (int i = particles.count() - 1; i >= 0 && removed < maximum; i--) {
			if ((int) Math.floor(particles.positionX(i) / h) != cellX || (int) Math.floor(particles.positionY(i) / h) != cellY || (int) Math.floor(particles.positionZ(i) / h) != cellZ) continue;
			float previousVolume = particles.volume(i);
			float take = Math.min(previousVolume, maximum - removed);
			double previousMass = particles.mass(i);
			double previousTemperature = thermalState == null ? Double.NaN : thermalMarkerTemperature(i);
			float newVolume = previousVolume - take;
			if (newVolume <= 1.0E-6F) {
				take = previousVolume;
				newVolume = 0.0F;
			}
			double newMass = previousMass * newVolume / previousVolume;
			ChemicalParcelStore.SplitPlan chemicalPlan = null;
			if (chemicalParcels != null) {
				chemicalPlan = chemicalParcels.planTruncate(
						i, newVolume, take, newMass, previousMass - newMass);
				if (removedChemistry != null) removedChemistry.add(
						materialRegistry.material(particles.materialHandle(i)).id(), chemicalParcels.indicator(i),
						take, unitScale.kilograms(previousMass - newMass), chemicalPlan.removed());
			}
			LiquidEnergyColumn.Truncation thermalPlan = planThermalTruncate(i, newMass,
					chemicalPlan == null ? null : chemicalPlan.kept(), previousTemperature);
			if (removedMaterials != null) removedMaterials.add(
					particles.materialHandle(i), take, previousMass - newMass);
			if (removedThermal != null && thermalPlan != null) removedThermal.add(thermalPlan.removedEnergy());
			plan.add(new MarkerTruncation(i, take, newVolume, newMass, chemicalPlan, thermalPlan));
			removed += take;
		}
		return new CellRemovalPlan(removed, List.copyOf(plan));
	}

	private void commitRemoveParticlesInCell(int cellX, int cellY, int cellZ, CellRemovalPlan plan) {
		for (MarkerTruncation entry : plan.markers()) {
			int i = entry.marker();
			if (chemicalParcels != null) {
				chemicalParcels.markDirtyCell(cellIndex(cellX, cellY, cellZ));
				if (entry.newVolume() > 0.0F) chemicalParcels.commitTruncate(i, entry.chemicalPlan());
			}
			particles.setVolume(i, entry.newVolume());
			particles.setMass(i, entry.newMass());
			commitThermalTruncate(i, entry.thermalPlan());
			if (particles.volume(i) <= 1.0E-6F) removeParticle(i);
		}
	}

	/**
	 * Removes up to {@code amount} across the candidate cells as one joint audited removal: every
	 * cell is planned against the shared escaped-sink ledger first, the joint ledger is validated
	 * once, and only then are truncations applied and drained markers swept. A rejection on any
	 * cell therefore leaves every marker untouched.
	 */
	float removeVolumeAcrossCells(java.util.List<CellCandidate> candidates, float amount) {
		ChemicalTransferLedger.Builder removedChemistry = chemicalParcels == null ? null
				: ChemicalTransferLedger.builder(chemicalParcels.registry());
		record PlannedCell(int x, int y, int z, CellRemovalPlan plan) { }
		List<PlannedCell> planned = new ArrayList<>();
		float remaining = amount;
		for (CellCandidate candidate : candidates) {
			int index = candidate.index();
			int x = index % sizeX, yz = index / sizeX, y = yz % sizeY, z = yz / sizeY;
			CellRemovalPlan plan = planRemoveParticlesInCell(x, y, z, remaining, removedChemistry, null, null);
			if (!plan.markers().isEmpty()) planned.add(new PlannedCell(x, y, z, plan));
			remaining -= plan.removedVolume();
			if (remaining <= 1.0E-6F) break;
		}
		if (removedChemistry != null) chemicalParcels.requireCanRecordEscapedSink(removedChemistry.build());
		for (PlannedCell cell : planned) {
			int dirtyCell = cellIndex(cell.x(), cell.y(), cell.z());
			for (MarkerTruncation entry : cell.plan().markers()) {
				int i = entry.marker();
				if (chemicalParcels != null) {
					chemicalParcels.markDirtyCell(dirtyCell);
					if (entry.newVolume() > 0.0F) chemicalParcels.commitTruncate(i, entry.chemicalPlan());
				}
				particles.setVolume(i, entry.newVolume());
				particles.setMass(i, entry.newMass());
				commitThermalTruncate(i, entry.thermalPlan());
			}
		}
		for (int marker = particles.count() - 1; marker >= 0; marker--) {
			if (particles.volume(marker) == 0.0F) removeParticle(marker);
		}
		if (removedChemistry != null) chemicalParcels.recordEscapedSink(removedChemistry.build());
		return amount - remaining;
	}

	/**
	 * Removes up to {@code maximum} from one cell. Every fallible chemical-sink check runs on the
	 * planned ledger before the first marker changes; the commit loop itself has no rejections.
	 */
	float removeParticlesInCell(int cellX, int cellY, int cellZ, float maximum) {
		ChemicalTransferLedger.Builder removedChemistry = chemicalParcels == null ? null
				: ChemicalTransferLedger.builder(chemicalParcels.registry());
		CellRemovalPlan plan = planRemoveParticlesInCell(cellX, cellY, cellZ, maximum, removedChemistry, null, null);
		if (removedChemistry != null) chemicalParcels.requireCanRecordEscapedSink(removedChemistry.build());
		commitRemoveParticlesInCell(cellX, cellY, cellZ, plan);
		if (removedChemistry != null) chemicalParcels.recordEscapedSink(removedChemistry.build());
		return plan.removedVolume();
	}

	float removeParticlesInCell(
			int cellX, int cellY, int cellZ, float maximum,
			ChemicalTransferLedger.Builder removedChemistry,
			RemovedMaterialLedger removedMaterials, RemovedThermalEnergy removedThermal
	) {
		CellRemovalPlan plan = planRemoveParticlesInCell(cellX, cellY, cellZ, maximum,
				removedChemistry, removedMaterials, removedThermal);
		commitRemoveParticlesInCell(cellX, cellY, cellZ, plan);
		return plan.removedVolume();
	}

	private float removeParticlesInMicroMask(
			int cellX, int cellY, int cellZ, int microMask,
			ChemicalTransferLedger.Builder removedChemistry,
			RemovedMaterialLedger removedMaterials, RemovedThermalEnergy removedThermal
	) {
		float h = config.cellSize(), removed = 0.0F;
		for (int particle = particles.count() - 1; particle >= 0; particle--) {
			if ((int) Math.floor(particles.positionX(particle) / h) != cellX
					|| (int) Math.floor(particles.positionY(particle) / h) != cellY
					|| (int) Math.floor(particles.positionZ(particle) / h) != cellZ) continue;
			float localX = particles.positionX(particle) / h - cellX;
			float localY = particles.positionY(particle) / h - cellY;
			float localZ = particles.positionZ(particle) / h - cellZ;
			int microX = Math.min(1, Math.max(0, (int) Math.floor(localX * 2.0F)));
			int microY = Math.min(1, Math.max(0, (int) Math.floor(localY * 2.0F)));
			int microZ = Math.min(1, Math.max(0, (int) Math.floor(localZ * 2.0F)));
			int bit = 1 << (microX + 2 * (microY + 2 * microZ));
			if ((microMask & bit) == 0) continue;
			if (chemicalParcels != null && removedChemistry != null) removedChemistry.add(
					materialRegistry.material(particles.materialHandle(particle)).id(), chemicalParcels.indicator(particle),
					particles.volume(particle), unitScale.kilograms(particles.mass(particle)),
					chemicalParcels.record(particle));
			if (removedMaterials != null) removedMaterials.add(particles.materialHandle(particle),
					particles.volume(particle), particles.mass(particle));
			if (removedThermal != null && thermalState != null) {
				removedThermal.add(thermalState.liquidInternalEnergy(particle));
			}
			removed += particles.volume(particle);
			removeParticle(particle);
		}
		return removed;
	}

	private int particleCountInCell(int cellX, int cellY, int cellZ) {
		float h = config.cellSize();
		int count = 0;
		for (int particle = 0; particle < particles.count(); particle++) {
			if ((int) Math.floor(particles.positionX(particle) / h) == cellX
					&& (int) Math.floor(particles.positionY(particle) / h) == cellY
					&& (int) Math.floor(particles.positionZ(particle) / h) == cellZ) count++;
		}
		return count;
	}

	private float exactVolumeInCell(int cellX, int cellY, int cellZ) {
		float h = config.cellSize(), result = 0.0F;
		for (int particle = 0; particle < particles.count(); particle++) {
			if ((int) Math.floor(particles.positionX(particle) / h) == cellX
					&& (int) Math.floor(particles.positionY(particle) / h) == cellY
					&& (int) Math.floor(particles.positionZ(particle) / h) == cellZ) result += particles.volume(particle);
		}
		return result;
	}

	double[] exactCellVolumes() {
		double[] exact = new double[cellCount];
		float h = config.cellSize();
		for (int particle = 0; particle < particles.count(); particle++) {
			int x = (int) Math.floor(particles.positionX(particle) / h);
			int y = (int) Math.floor(particles.positionY(particle) / h);
			int z = (int) Math.floor(particles.positionZ(particle) / h);
			if (inside(x, y, z)) exact[cellIndex(x, y, z)] += particles.volume(particle);
		}
		return exact;
	}

	void removeParticle(int index) {
		coordinator.swapRemoveMarker(
				CarrierMutationPlan.swapRemove(carrierMutationRevision(), particles.count(), index), false);
	}

	private double removeParticleInternalPhase(int index) {
		return coordinator.swapRemoveMarker(
				CarrierMutationPlan.swapRemove(carrierMutationRevision(), particles.count(), index), true);
	}

	void ensureParticleCapacity(int required) {
		if (required > maximumParticleCount) {
			throw new ParticleBudgetExceededException(required - particles.count(), maximumParticleCount - particles.count());
		}
		if (required <= particles.capacity()) return;
		int capacity = particles.ensureCapacity(required);
		markerGrid.ensureMarkerCapacity(capacity);
		if (thermalState != null) thermalState.ensureCapacity(capacity);
	}

	private void setDefaultMaterialState(int particle) {
		particles.setMass(particle, (double) particles.volume(particle) * config.density());
		particles.setMaterialHandle(particle, 0);
		particles.setPhaseLabel(particle, 0);
	}

	List<CellCandidate> nearbyCells(int centerX, int centerY, int centerZ, int maxRadius) {
		ensureGrid();
		List<CellCandidate> result = new ArrayList<>();
		int radiusSquared = maxRadius * maxRadius;
		for (int z = Math.max(0, centerZ - maxRadius); z <= Math.min(sizeZ - 1, centerZ + maxRadius); z++)
			for (int y = Math.max(0, centerY - maxRadius); y <= Math.min(sizeY - 1, centerY + maxRadius); y++)
				for (int x = Math.max(0, centerX - maxRadius); x <= Math.min(sizeX - 1, centerX + maxRadius); x++) {
					int dx = x - centerX, dy = y - centerY, dz = z - centerZ, index = cellIndex(x, y, z);
					if (dx * dx + dy * dy + dz * dz <= radiusSquared && !geometry.solid[index]) result.add(new CellCandidate(index, dx * dx + dy * dy + dz * dz));
				}
		result.sort(Comparator.comparingInt(CellCandidate::distanceSquared));
		return result;
	}

	void ensureGrid() { if (gridDirty) particleToGridStage.rebuild(); }
	long carrierMutationRevision() { return carrierMutationRevision; }
	void bumpCarrierMutationRevision() { carrierMutationRevision++; }
	CarrierMutationCoordinator carrierMutations() { return coordinator; }
	private boolean markerPositionAllowed(float x, float y, float z) {
		return !particleCollision.centerInSolid(x, y, z);
	}
	boolean isFluid(int index) { return projection.pressureFluid[index]; }
	boolean isAir(int index) { return !geometry.solid[index] && !isFluid(index); }
	boolean carrierFaceActive(int first, int second, float openArea) {
		return openArea > 0.0F && !geometry.solid[first] && !geometry.solid[second]
				&& (markerGrid.markerCount[first] > 0 || markerGrid.markerCount[second] > 0);
	}
	float pressureInterfaceTheta(int fluid, int directionX, int directionY, int directionZ) {
		return markerGrid.markerCount[fluid] > 0
				? freeSurfaceGeometry.pressureTheta(fluid, directionX, directionY, directionZ)
				: pressurePhaseGeometry.interfaceTheta(fluid, directionX, directionY, directionZ);
	}
	boolean inside(int x, int y, int z) { return x >= 0 && x < sizeX && y >= 0 && y < sizeY && z >= 0 && z < sizeZ; }
	int cellIndex(int x, int y, int z) { return (z * sizeY + y) * sizeX + x; }
	int xFaceIndex(int x, int y, int z) { return (z * sizeY + y) * (sizeX + 1) + x; }
	int yFaceIndex(int x, int y, int z) { return (z * (sizeY + 1) + y) * sizeX + x; }
	int zFaceIndex(int x, int y, int z) { return (z * sizeY + y) * sizeX + x; }
	int cellIndexChecked(int x, int y, int z) { if (!inside(x, y, z)) throw new IndexOutOfBoundsException(); return cellIndex(x, y, z); }

	float sampleX(float[] field, float x, float y, float z) {
		float h = config.cellSize();
		return trilinearValid(field, mac.velocityValidX, sizeX + 1, sizeY, sizeZ,
				x / h, y / h - 0.5F, z / h - 0.5F);
	}
	float sampleY(float[] field, float x, float y, float z) {
		float h = config.cellSize();
		return trilinearValid(field, mac.velocityValidY, sizeX, sizeY + 1, sizeZ,
				x / h - 0.5F, y / h, z / h - 0.5F);
	}
	float sampleZ(float[] field, float x, float y, float z) {
		float h = config.cellSize();
		return trilinearValid(field, mac.velocityValidZ, sizeX, sizeY, sizeZ + 1,
				x / h - 0.5F, y / h - 0.5F, z / h);
	}

	private static float trilinearValid(
			float[] values, boolean[] valid, int nx, int ny, int nz, float x, float y, float z
	) {
		float cx = clamp(x, 0.0F, nx - 1.0F), cy = clamp(y, 0.0F, ny - 1.0F), cz = clamp(z, 0.0F, nz - 1.0F);
		int x0 = (int) Math.floor(cx), y0 = (int) Math.floor(cy), z0 = (int) Math.floor(cz);
		int x1 = Math.min(x0 + 1, nx - 1), y1 = Math.min(y0 + 1, ny - 1), z1 = Math.min(z0 + 1, nz - 1);
		float fx = cx - x0, fy = cy - y0, fz = cz - z0;
		float weighted = 0.0F, weightSum = 0.0F;
		for (int offsetZ = 0; offsetZ <= 1; offsetZ++) {
			int sampleZ = offsetZ == 0 ? z0 : z1;
			float weightZ = offsetZ == 0 ? 1.0F - fz : fz;
			for (int offsetY = 0; offsetY <= 1; offsetY++) {
				int sampleY = offsetY == 0 ? y0 : y1;
				float weightY = offsetY == 0 ? 1.0F - fy : fy;
				for (int offsetX = 0; offsetX <= 1; offsetX++) {
					int sampleX = offsetX == 0 ? x0 : x1;
					float weight = (offsetX == 0 ? 1.0F - fx : fx) * weightY * weightZ;
					int index = (sampleZ * ny + sampleY) * nx + sampleX;
					if (!valid[index] || weight <= 0.0F) continue;
					weighted += values[index] * weight;
					weightSum += weight;
				}
			}
		}
		return weightSum <= 1.0E-8F ? 0.0F : weighted / weightSum;
	}

	void validateVolumeEdit(float amount, int maxRadius) {
		if (!Float.isFinite(amount) || amount < 0.0F) throw new IllegalArgumentException("amount must be finite and non-negative");
		if (maxRadius < 0) throw new IllegalArgumentException("maxRadius cannot be negative");
	}
	void validateMaterialPortion(MaterialPortion portion) {
		if (portion == null || !(Double.isFinite(portion.volume()) && portion.volume() > 0.0
				&& portion.volume() <= Float.MAX_VALUE && (float) portion.volume() > 0.0F)
				|| !(Double.isFinite(portion.mass()) && portion.mass() > 0.0)) {
			throw new IllegalArgumentException("Material portion ledgers must be finite, positive and float-representable");
		}
		if (materialRegistry == null) {
			if (portion.materialHandle() != 0) {
				throw new IllegalArgumentException("Material handle requires a bound registry");
			}
		} else {
			materialRegistry.material(portion.materialHandle());
		}
	}
	private static int particlesRequired(float amount) {
		if (amount <= 1.0E-6F) return 0;
		return (int) Math.ceil((amount - 1.0E-6F) / PARTICLE_VOLUME);
	}
	static float clamp01(float value) { return clamp(value, 0.0F, 1.0F); }
	private static float clamp(float value, float min, float max) { return Math.max(min, Math.min(max, value)); }
	private static float lerp(float first, float second, float t) { return first + (second - first) * t; }

	record CellCandidate(int index, int distanceSquared) { }
	public record VelocitySample(float x, float y, float z) { private static final VelocitySample ZERO = new VelocitySample(0.0F, 0.0F, 0.0F); }
	public record BodyFluidSample(
			double displacedVolumeCubicMetres,
			double centerXMetres, double centerYMetres, double centerZMetres,
			double velocityXMetresPerSecond, double velocityYMetresPerSecond, double velocityZMetresPerSecond
	) {
		private static final BodyFluidSample EMPTY = new BodyFluidSample(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);

		public BodyFluidSample {
			if (!(Double.isFinite(displacedVolumeCubicMetres) && displacedVolumeCubicMetres >= 0.0
					&& Double.isFinite(centerXMetres) && Double.isFinite(centerYMetres) && Double.isFinite(centerZMetres)
					&& Double.isFinite(velocityXMetresPerSecond) && Double.isFinite(velocityYMetresPerSecond)
					&& Double.isFinite(velocityZMetresPerSecond))) {
				throw new IllegalArgumentException("Body fluid sample must be finite with non-negative volume");
			}
			if (displacedVolumeCubicMetres == 0.0 && (centerXMetres != 0.0 || centerYMetres != 0.0
					|| centerZMetres != 0.0 || velocityXMetresPerSecond != 0.0
					|| velocityYMetresPerSecond != 0.0 || velocityZMetresPerSecond != 0.0)) {
				throw new IllegalArgumentException("Dry body fluid sample cannot carry derived observations");
			}
		}
	}
	public record VolumeAddition(float addedVolume, boolean markerBudgetLimited) { }
	public record VolumeDistribution(int activeCells, int visibleCells, int denseCells) { }
	public record SolidDistribution(int partialCells, int fullCells) { }
	public record MechanicalStepAudit(
			boolean captured,
			double initialParticleKineticEnergyJoules,
			double finalParticleKineticEnergyJoules,
			double initialGravitationalPotentialEnergyJoules,
			double finalGravitationalPotentialEnergyJoules,
			double particleToGridTransferJoules,
			double granularCouplingWorkJoules,
			double porousBoundaryWorkJoules,
			double gravityWorkJoules,
			double thermalBuoyancyWorkJoules,
			double surfaceTensionWorkJoules,
			double fixedBoundaryWorkJoules,
			double pressureProjectionWorkJoules,
			double gridToParticleTransferJoules,
			double particleCollisionWorkJoules,
			double carrierInventoryTransferJoules,
			double reseedTransferJoules,
			SignedDyadic thermalInventoryMechanicalWork,
			double closureResidualJoules
	) {
		private static MechanicalStepAudit notCaptured() {
			return new MechanicalStepAudit(false, 0.0, 0.0, 0.0, 0.0,
					0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
					0.0, 0.0, 0.0, 0.0, SignedDyadic.ZERO, 0.0);
		}
	}
	record MaterialInventory(double volume, double mass, double centerY) { }
	public record StepStats(
			int substeps,
			int pressureIterations,
			float pressureResidual,
			float volumeBefore,
			float volumeAfter,
			double massBefore,
			double massAfter,
			int reseededMerges,
			int reseededSplits,
			int reseedBudgetSkipped,
			int reseedGeometrySkipped,
			double reseedLedgerError,
			double reseedMassLedgerError,
			int chemistryDirtyCells,
			int chemistryEvaluations,
			int chemistryChangedMarkers,
			ThermalPhaseStats condensedPhase,
			GasThermalPhaseStats gasPhase,
			MechanicalStepAudit mechanicalAudit
	) {
		public float relativeVolumeError() { return volumeBefore == 0.0F ? 0.0F : Math.abs(volumeAfter - volumeBefore) / volumeBefore; }
		public double relativeMassError() { return massBefore == 0.0 ? 0.0 : Math.abs(massAfter - massBefore) / massBefore; }
	}

	public static final class ParticleBudgetExceededException extends IllegalStateException {
		private final int requiredMarkers;
		private final int availableMarkers;

		private ParticleBudgetExceededException(int requiredMarkers, int availableMarkers) {
			super("FLIP marker budget exceeded: requires " + requiredMarkers + ", available " + Math.max(0, availableMarkers));
			this.requiredMarkers = requiredMarkers;
			this.availableMarkers = Math.max(0, availableMarkers);
		}

		public int requiredMarkers() { return requiredMarkers; }
		public int availableMarkers() { return availableMarkers; }
	}

	/** Rejects a complete mechanics candidate when its pressure projection is not converged. */
	public static final class PressureSolveBlockedException extends IllegalStateException {
		PressureSolveBlockedException(String message) { super(message); }
	}
}

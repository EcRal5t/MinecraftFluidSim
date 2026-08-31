package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.thermal.ThermalDomainState;
import cc.ecisr.mmm.thermal.ThermalCarrierContext;

import cc.ecisr.mmm.chemistry.ChemicalParcelStore;
import cc.ecisr.mmm.granular.GranularCouplingFields;
import cc.ecisr.mmm.granular.GranularDepositDynamics;
import cc.ecisr.mmm.granular.GranularPhaseTransferEngine;
import cc.ecisr.mmm.units.SignedDyadic;
import cc.ecisr.mmm.units.SolverUnitScale;

import java.util.Arrays;

/** Allocates the one mutable domain state and wires its single-purpose collaborators. */
abstract class FluidDomainStorage {
	final FluidDomain domain() { return (FluidDomain) this; }
	static final int PARTICLES_PER_FULL_CELL = 8;
	static final float PARTICLE_VOLUME = 1.0F / PARTICLES_PER_FULL_CELL;
	static final int DEFAULT_MAX_PARTICLES = 262_144;
	static final int NO_PHASE = Integer.MIN_VALUE;
	static final int INDICATOR_AIM_SNAP_CELLS = 2;

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
	final CondensedInventoryClearPlanner clearPlanner;
	final MaterialTransferPlanner transferPlanner;
	final GranularTransferPlanner granularTransferPlanner;
	final DomainInventoryLifecyclePlanner lifecyclePlanner;
	final GasTopologyCoordinator gasTopologyCoordinator;
	final GasLifecyclePlanner gasLifecyclePlanner;
	final PressureBoundaryView pressureBoundaryView;
	final PressureSystem pressureSystem;
	final PressureProjectionStage pressureStage;
	final GranularMechanicalStage granularMechanicalStage;
	final BodyForceStage bodyForceStage;
	final GridToParticleStage gridToParticleStage;
	final ParticleAdvectionStage particleAdvectionStage;
	final ParticleToGridStage particleToGridStage;
	final MechanicalStepPipeline mechanicalPipeline;
	final ThermalTransportStage thermalTransportStage;
	final CondensedPhaseChangeStage condensedPhaseChangeStage;
	final GasLiquidPhaseChangeStage gasLiquidPhaseChangeStage;
	final ThermochemistryPipeline thermochemistryPipeline;
	final GranularTransitionStage granularTransitionStage;
	final ParticleReseedStage particleReseedStage;
	final FlipPressurePhaseGeometry pressurePhaseGeometry;
	final FlipFreeSurfaceGeometry freeSurfaceGeometry;
	final FlipParticleReseeder particleReseeder;
	final FlipParticleCollision particleCollision;
	final FlipVelocityExtrapolator velocityExtrapolator;
	final float[] volume;
	boolean surfaceTensionActive;
	boolean liquidPairTensionActive;

	/** Frozen P2G face mass/volume weights; extrapolation reuses the live weight arrays as scratch. */

	final LiquidParticleStore particles;
	long carrierMutationRevision;
	final CarrierMutationCoordinator coordinator;
	FluidMaterialRegistry materialRegistry;
	ChemicalParcelStore chemicalParcels;
	int[] chemicalPreviousCells;
	int[] chemicalMovingMarkers;
	final GasDomainState gasState = new GasDomainState();
	final GasPressureBoundary gasBoundary;
	FluidDomain.CollisionEditPlan activeCollisionPlan;
	int activeCollisionEditIndex;
	GranularDomainState granularState;
	GranularPhaseTransferEngine granularPhaseTransfer;
	boolean precipitationEnabled;
	FluidDomain.PhaseTransferStats lastPhaseTransferStats = new FluidDomain.PhaseTransferStats(0, 0, 0, 0L);
	FluidDomain.ThermalPhaseStats lastThermalPhaseStats = new FluidDomain.ThermalPhaseStats(0, 0, 0, 0L);
	FluidDomain.GasThermalPhaseStats lastGasThermalPhaseStats = new FluidDomain.GasThermalPhaseStats(0, 0, 0);
	ChemicalParcelStore.ReactionStats lastChemicalReactionStats =
			new ChemicalParcelStore.ReactionStats(0, 0, 0);
	GranularDepositDynamics granularDepositDynamics;
	GranularDepositDynamics.Stats lastGranularDepositStats =
			new GranularDepositDynamics.Stats(0, 0, 0, 0, 0);
	double lastGranularBoundaryImpulseX, lastGranularBoundaryImpulseY, lastGranularBoundaryImpulseZ;
	int[] granularReactivePreviousCells;
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
	ThermalDomainState thermalState;
	final ThermalCarrierContext thermalMarkers;
	double[] thermalBuoyancySum;
	double[] thermalBuoyancyVolume;
	/** Runtime world-derived fixed-reservoir temperatures; NaN is the canonical adiabatic face. */
	double[] thermalBoundaryTemperatureK;
	final SignedDyadic[] pendingPhaseCellVolume;
	final DomainMetrics metrics;
	final CellInventoryReader cellInventory;
	final CarrierInventoryReader carriers;

	FluidDomainStorage(int sizeX, int sizeY, int sizeZ, FluidSolverConfig config, int maximumParticleCount) {
		if (sizeX < 2 || sizeY < 2 || sizeZ < 2) throw new IllegalArgumentException("All dimensions must be at least two cells");
		if (maximumParticleCount <= 0) throw new IllegalArgumentException("Maximum particle count must be positive");
		this.sizeX = sizeX;
		this.sizeY = sizeY;
		this.sizeZ = sizeZ;
		this.cellCount = Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ);
		this.config = config;
		this.unitScale = SolverUnitScale.forCellSize(config.cellSize());
		this.maximumParticleCount = maximumParticleCount;
		FluidDomain domain = (FluidDomain) this;
		this.gasBoundary = new GasPressureBoundary(domain);
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
		this.thermalMarkers = domain.createThermalMarkerContext();
		this.metrics = new DomainMetrics(domain);
		this.cellInventory = new CellInventoryReader(domain);
		this.carriers = new CarrierInventoryReader(domain);
		this.coordinator = new CarrierMutationCoordinator(domain);
		this.transferPlanner = new MaterialTransferPlanner(domain);
		this.clearPlanner = new CondensedInventoryClearPlanner(domain);
		this.granularTransferPlanner = new GranularTransferPlanner(domain);
		this.lifecyclePlanner = new DomainInventoryLifecyclePlanner(domain);
		this.gasTopologyCoordinator = new GasTopologyCoordinator(domain);
		this.gasLifecyclePlanner = new GasLifecyclePlanner(domain);
		this.pressureBoundaryView = new PressureBoundaryView(domain);
		this.pressureSystem = new PressureSystem(domain);
		MechanicalStageAssembly.Stages stages = MechanicalStageAssembly.create(
				domain, pressureSystem, pressureBoundaryView);
		this.pressureStage = stages.pressure();
		this.granularMechanicalStage = stages.granular();
		this.bodyForceStage = stages.bodyForce();
		this.gridToParticleStage = stages.gridToParticle();
		this.particleAdvectionStage = stages.advection();
		this.particleToGridStage = stages.particleToGrid();
		this.mechanicalPipeline = stages.pipeline();
		ThermochemistryStageAssembly.Stages thermalStages = ThermochemistryStageAssembly.create(domain);
		this.thermalTransportStage = thermalStages.transport();
		this.condensedPhaseChangeStage = thermalStages.condensedPhase();
		this.gasLiquidPhaseChangeStage = thermalStages.gasPhase();
		this.thermochemistryPipeline = thermalStages.pipeline();
		LogicalStepTailAssembly.Stages tailStages = LogicalStepTailAssembly.create(domain);
		this.granularTransitionStage = tailStages.granularTransition();
		this.particleReseedStage = tailStages.reseed();
	}
}

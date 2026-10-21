package mmm.fluid;

import mmm.inventory.LiquidCellInventory;
import mmm.inventory.PhaseOccupancyState;
import mmm.thermal.ThermalDomainState;
import mmm.thermal.ThermalCarrierContext;
import mmm.chemistry.ChemicalParcelStore;
import mmm.granular.GranularCouplingFields;
import mmm.granular.GranularDepositDynamics;
import mmm.granular.GranularPhaseTransferEngine;
import mmm.units.SolverUnitScale;
import java.util.List;
import mmm.units.SignedDyadic;
import mmm.chemistry.ChemicalTransferLedger;
import java.util.ArrayList;
import mmm.fluid.air.AirNetwork;
import mmm.granular.GranularParcelStore;
import mmm.thermal.ThermalTransport;
import mmm.world.MovingBodyFluidCoupling;
import mmm.thermal.GasEnergyStore;
import mmm.fluid.air.GasAuditLedger;
import mmm.granular.GranularAuditLedger;
import mmm.granular.GranularDepositStore;
import mmm.thermal.CoherentPhaseStore;
import mmm.chemistry.ChemicalRegistry;
import java.util.Map;
import mmm.thermal.FiniteDeviceExchangeCoordinator;
import mmm.thermal.ThermalOwnerSnapshot;
import mmm.chemistry.ChemicalDisplaySnapshot;
import mmm.granular.SolidCompositionPool;
import mmm.thermal.FiniteThermalExchange;
import mmm.thermal.ThermalRegistry;
import mmm.thermal.ThermalDisplaySnapshot;
import mmm.fluid.air.GasRegistry;
import mmm.fluid.air.GasStateSnapshot;
import mmm.granular.GranularDisplaySnapshot;
import mmm.granular.GranularRegistry;
import mmm.granular.GranularStateSnapshot;
import mmm.granular.GranularTransferLedger;
import mmm.thermal.ThermalAuditLedger;
import mmm.thermal.ThermalCellGrid;

/** Owns the domain state and exposes one-hop operations implemented by responsibility. */
public final class FluidDomain {
	// Authoritative state, derived workspaces and responsibility handlers.
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
	final CellLiquidGrid liquidGrid;
	final MacVelocityGrid mac;
	final CellMacStage cellMacStage;
	final PressureProjectionWorkspace projection;
	final SurfaceTensionFields tension;
	final CondensedInventoryClearPlanner clearPlanner;
	final MaterialTransferPlanner transferPlanner;
	final GranularTransferPlanner granularTransferPlanner;
	final DomainInventoryLifecyclePlanner lifecyclePlanner;
	final GasTopologyCoordinator gasTopologyCoordinator;
	final GasLifecyclePlanner gasLifecyclePlanner;
	final PressureBoundaryView pressureBoundaryView;
	final PressureSystem pressureSystem;
	final GranularMechanicalStage granularMechanicalStage;
	final BodyForceStage bodyForceStage;
	final MechanicalStepPipeline mechanicalPipeline;
	final CondensedPhaseChangeStage condensedPhaseChangeStage;
	final GasLiquidPhaseChangeStage gasLiquidPhaseChangeStage;
	final ThermochemistryPipeline thermochemistryPipeline;
	final GranularTransitionStage granularTransitionStage;
	FlipPressurePhaseGeometry pressurePhaseGeometry;
	final float[] volume;
	boolean surfaceTensionActive;
	boolean liquidPairTensionActive;
	LiquidCellInventory liquidCells;
	PhaseOccupancyState phaseOccupancy;
	boolean phaseOccupancyDirty = true;
	long carrierMutationRevision;
	FluidMaterialRegistry materialRegistry;
	MaterialRegistry materialDefinitions;
	final LiquidCarrierProperties carrierPropertiesWorkspace;
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
	double lastLiquidBoundaryImpulseX, lastLiquidBoundaryImpulseY, lastLiquidBoundaryImpulseZ;
	int[] granularReactivePreviousCells;
	GranularCouplingFields granularCoupling;
	GranularCouplingFields granularNextCoupling;
	GranularCouplingFields pressureGranularCoupling;
	FlipGranularCollision granularCollision;
	int[] liquidCarrierMaterialByCell;
	double[] liquidCarrierVolumeByCell;
	boolean gridDirty = true;
	boolean pressureGeometryDirty = true;
	boolean velocityGridDirty = true;
	int pressureAnchor = -1;
	float pressureReference;
	int lastPressureIterations;
	float lastPressureResidual;
	ThermalDomainState thermalState;
	final ThermalCarrierContext thermalMarkers;
	double[] thermalBoundaryTemperatureK;
	final PendingPhaseVolumeWork pendingPhaseVolume;
	final DomainMetrics metrics;
	final CellInventoryReader cellInventory;
	final CarrierInventoryReader carriers;
	final DomainFlowFields domainFlowFields = new DomainFlowFields(this);
	int minimumMechanicalSubsteps = 1;
	final DomainStep domainStep = new DomainStep(this);
	final DomainInventory domainInventory = new DomainInventory(this);
	final DomainCollision domainCollision = new DomainCollision(this);
	final DomainCapabilities domainCapabilities = new DomainCapabilities(this);
	final DomainPhaseInventory domainPhaseInventory = new DomainPhaseInventory(this);

	public FluidDomain(int sizeX, int sizeY, int sizeZ, FluidSolverConfig config, int maximumParticleCount) {
		if (sizeX < 2 || sizeY < 2 || sizeZ < 2) throw new IllegalArgumentException("All dimensions must be at least two cells");
		if (maximumParticleCount <= 0) throw new IllegalArgumentException("Maximum particle count must be positive");
		this.sizeX = sizeX;
		this.sizeY = sizeY;
		this.sizeZ = sizeZ;
		this.cellCount = Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ);
		this.config = config;
		this.unitScale = SolverUnitScale.forCellSize(config.cellSize());
		this.maximumParticleCount = maximumParticleCount;
		FluidDomain domain = this;
		this.gasBoundary = new GasPressureBoundary(domain);
		this.liquidCells = new LiquidCellInventory(cellCount, null);
		int initialParticleCapacity = Math.min(16_384, maximumParticleCount);
		this.geometry = new DomainGeometry(sizeX, sizeY, sizeZ, cellCount);
		this.carrierPropertiesWorkspace = new LiquidCarrierProperties(cellCount);
		this.liquidGrid = new CellLiquidGrid(cellCount, initialParticleCapacity);
		this.pressurePhaseGeometry = new FlipPressurePhaseGeometry(sizeX, sizeY, sizeZ);
		this.volume = new float[cellCount];
		this.mac = new MacVelocityGrid(sizeX, sizeY, sizeZ);
		this.cellMacStage = new CellMacStage(domain);
		this.projection = new PressureProjectionWorkspace(cellCount);
		this.tension = new SurfaceTensionFields(this);
		this.pendingPhaseVolume = new PendingPhaseVolumeWork(cellCount);
		this.thermalMarkers = domain.domainCapabilities.createThermalMarkerContext();
		this.metrics = new DomainMetrics(domain);
		this.cellInventory = new CellInventoryReader(domain);
		this.carriers = new CarrierInventoryReader(domain);
		this.transferPlanner = new MaterialTransferPlanner(domain);
		this.clearPlanner = new CondensedInventoryClearPlanner(domain);
		this.granularTransferPlanner = new GranularTransferPlanner(domain);
		this.lifecyclePlanner = new DomainInventoryLifecyclePlanner(domain);
		this.gasTopologyCoordinator = new GasTopologyCoordinator(domain);
		this.gasLifecyclePlanner = new GasLifecyclePlanner(domain);
		this.pressureBoundaryView = new PressureBoundaryView(domain);
		this.pressureSystem = new PressureSystem(domain);
		this.granularMechanicalStage = new GranularMechanicalStage(this);
		this.bodyForceStage = new BodyForceStage(this);
		this.mechanicalPipeline = new MechanicalStepPipeline(this);
		this.thermochemistryPipeline = new ThermochemistryPipeline(this);
		this.condensedPhaseChangeStage = thermochemistryPipeline.condensedPhase;
		this.gasLiquidPhaseChangeStage = thermochemistryPipeline.gasPhase;
		this.granularTransitionStage = new GranularTransitionStage(this);
	}

	public FluidDomain(int sizeX, int sizeY, int sizeZ, FluidSolverConfig config) {
		this(sizeX, sizeY, sizeZ, config, DEFAULT_MAX_PARTICLES);
	}

	// External observations and operations.
	public void bindMaterialDefinitions(MaterialRegistry definitions) { LiquidMaterialBinding.bind(this, definitions); }
	public LiquidCellInventory.Snapshot exportLiquidCellState() { return liquidCells.snapshot(); }
	/** Impulse exerted by fixed walls on liquid during the latest logical step, in kg*m/s. */
	public double lastLiquidBoundaryImpulseX() { return lastLiquidBoundaryImpulseX; }
	public double lastLiquidBoundaryImpulseY() { return lastLiquidBoundaryImpulseY; }
	public double lastLiquidBoundaryImpulseZ() { return lastLiquidBoundaryImpulseZ; }
	void ensurePhaseOccupancy() { PhaseOccupancyTransport.ensure(this); }
	public PhaseOccupancyState exportPhaseOccupancyState() {
		ensurePhaseOccupancy();
		return phaseOccupancy;
	}
	public void restorePhaseOccupancyState(PhaseOccupancyState state) {
		if(gasState.frame!=null)throw new IllegalStateException("Phase occupancy restore requires an uninitialized gas topology");
		PhaseOccupancyTransport.requireCurrentGeometry(this,state);
		PhaseOccupancyTransport.publish(this,state);
		gridDirty=pressureGeometryDirty=velocityGridDirty=true;
	}
	public void restoreLiquidCellState(LiquidCellInventory.Snapshot state, mmm.chemistry.ChemicalRegistry chemistry) {
		if(gasState.frame!=null)throw new IllegalStateException("Liquid inventory restore requires an uninitialized gas topology");
		if (state == null || state.cellCount() != cellCount) throw new IllegalArgumentException("Liquid inventory shape differs from domain");
		var candidate = new LiquidCellInventory(state, chemistry);
		for (int cell = 0; cell < cellCount; cell++) {
			int mask = geometry.cutCells.mask(cell);
			int[] components = CellRemapMicroTopology.components(mask);
			for (var stock : candidate.stocksInCell(cell)) {
				boolean found = false;
				for (int component : components) found |= component == stock.key().supportMask();
				if (!found) throw new IllegalArgumentException("Liquid stock crosses a fixed collision component");
				if (materialRegistry != null) materialRegistry.material(stock.key().material());
			}
			if (candidate.exactCellVolume(cell).compareTo(mmm.units.SignedDyadic.of(domainCollision.effectiveOpenCellVolume(cell))) > 0)
				throw new IllegalArgumentException("Liquid inventory exceeds joint cell capacity at " + cell);
		}
		liquidCells = candidate;
		phaseOccupancy=null;
		markCellInventoryChanged();
	}
	public double liquidKineticEnergyJoules() { domainFlowFields.ensureVelocityGrid(); return domainStep.gridKineticEnergyJoules(); }
	public MacMomentumState exportMacMomentum() { return mac.captureMomentum(); }
	public void restoreMacMomentum(MacMomentumState state) {
		mac.restoreMomentum(state);
		velocityGridDirty = true;
	}
	public void rebuildDerivedFromLiquidCells() {
		gridDirty = pressureGeometryDirty = velocityGridDirty = true;
		domainFlowFields.ensureGrid();
	}
	void markCellInventoryChanged() {
		carrierMutationRevision++;
		// Retain the source object until a prepared gas successor is committed.
		phaseOccupancyDirty = true;
		gridDirty = pressureGeometryDirty = velocityGridDirty = true;
		carrierPropertiesWorkspace.invalidate();
	}
	static float trilinearValid(
			float[] values, boolean[] valid, int nx, int ny, int nz, float x, float y, float z
	) { return DomainFlowFields.trilinearValid(values, valid, nx, ny, nz, x, y, z); }
	static void trilinearValidPair(
			float[] values, float[] previous, boolean[] valid, int nx, int ny, int nz, float x, float y, float z, float[] result
	) { DomainFlowFields.trilinearValidPair(values, previous, valid, nx, ny, nz, x, y, z, result); }
	static float clamp01(float value) { return DomainFlowFields.clamp01(value); }
	static float clamp(float value, float min, float max) { return DomainFlowFields.clamp(value, min, max); }
	static float lerp(float first, float second, float t) { return DomainFlowFields.lerp(first, second, t); }
	public StepStats step() { return domainStep.step(); }
	public StepStats step(boolean phaseChangeEnabled, int maximumPhaseOperations) { return domainStep.step(phaseChangeEnabled, maximumPhaseOperations); }
	public StepStats stepMechanicsOnly() { return domainStep.stepMechanicsOnly(); }
	public StepStats stepWithMechanicalAudit(boolean phaseChangeEnabled, int maximumPhaseOperations) { return domainStep.stepWithMechanicalAudit(phaseChangeEnabled, maximumPhaseOperations); }
	public ThermalAxisStats stepThermalAxis(boolean phaseChangeEnabled, int maximumPhaseOperations) { return domainStep.stepThermalAxis(phaseChangeEnabled, maximumPhaseOperations); }
	public void synchronizeGasTopologyForPersistence() { domainStep.synchronizeGasTopologyForPersistence(); }
	static GranularCouplingFields.Position parcelPosition(GranularParcelStore.Parcel parcel) { return DomainStep.parcelPosition(parcel); }
	static float trilinearWeight(int ox, int oy, int oz, float fx, float fy, float fz) { return DomainStep.trilinearWeight(ox, oy, oz, fx, fy, fz); }
	public MovingBodyFluidCoupling.Result applyMovingBodyCoupling(MovingBodyFluidCoupling.Request request) { return domainStep.applyMovingBodyCoupling(request); }
	static void requireFiniteMarkerVelocity(double velocity) { DomainStep.requireFiniteMarkerVelocity(velocity); }
	public BodyFluidSample sampleAxisAlignedBody(
			double minimumX, double minimumY, double minimumZ,
			double maximumX, double maximumY, double maximumZ
	) { return domainInventory.sampleAxisAlignedBody(minimumX, minimumY, minimumZ, maximumX, maximumY, maximumZ); }
	public VelocitySample sampleVelocity(float localX, float localY, float localZ) { return domainInventory.sampleVelocity(localX, localY, localZ); }
	public float centerOfMassX() { return domainInventory.centerOfMassX(); }
	public float addVolumeNear(int centerX, int centerY, int centerZ, float amount, int maxRadius) { return domainInventory.addVolumeNear(centerX, centerY, centerZ, amount, maxRadius); }
	public VolumeAddition addVolumeNearBudgeted(int centerX, int centerY, int centerZ, float amount, int maxRadius) { return domainInventory.addVolumeNearBudgeted(centerX, centerY, centerZ, amount, maxRadius); }
	public float removeVolumeNear(int centerX, int centerY, int centerZ, float amount, int maxRadius) { return domainInventory.removeVolumeNear(centerX, centerY, centerZ, amount, maxRadius); }
	public void removeAllWaterExact() { domainInventory.removeAllWaterExact(); }
	public WaterClearPlan planRemoveAllWaterExact() { return domainInventory.planRemoveAllWaterExact(); }
	public void commitRemoveAllWaterExact(WaterClearPlan plan) { domainInventory.commitRemoveAllWaterExact(plan); }
	public CondensedClearPlan planRemoveAllCondensedExact() { return domainInventory.planRemoveAllCondensedExact(); }
	public CondensedClear commitRemoveAllCondensedExact(CondensedClearPlan plan) { return domainInventory.commitRemoveAllCondensedExact(plan); }
	public OperatorClearPlan planOperatorClear() { return domainInventory.planOperatorClear(); }
	public OperatorClear commitOperatorClear(OperatorClearPlan plan) { return domainInventory.commitOperatorClear(plan); }
	public OperatorGasVentPlan planOperatorVentSealedGas() { return domainInventory.planOperatorVentSealedGas(); }
	public OperatorGasVent commitOperatorVentSealedGas(OperatorGasVentPlan plan) { return domainInventory.commitOperatorVentSealedGas(plan); }
	public ExactVolumeTransfer tryInsertExactVolume(int centerX, int centerY, int centerZ, float volume, int maxRadius) { return domainInventory.tryInsertExactVolume(centerX, centerY, centerZ, volume, maxRadius); }
	public ExactVolumeTransfer tryInsertExactVolume(int centerX, int centerY, int centerZ, float volume, int maxRadius, int materialHandle) { return domainInventory.tryInsertExactVolume(centerX, centerY, centerZ, volume, maxRadius, materialHandle); }
	public ExactVolumeTransfer tryInsertExactVolume(
			int centerX, int centerY, int centerZ, float volume, int maxRadius, int materialHandle,
			boolean externalSource
	) { return domainInventory.tryInsertExactVolume(centerX, centerY, centerZ, volume, maxRadius, materialHandle, externalSource); }
	public ExactVolumeTransfer tryInsertExactVolume(
			int centerX, int centerY, int centerZ, float volume, int maxRadius, int materialHandle,
			boolean externalSource, double internalEnergy
	) { return domainInventory.tryInsertExactVolume(centerX, centerY, centerZ, volume, maxRadius, materialHandle, externalSource, internalEnergy); }
	public ExactVolumeTransfer tryInsertExactMaterial(
			int centerX, int centerY, int centerZ, float volume, long massUnits, int maxRadius, int materialHandle
	) { return domainInventory.tryInsertExactMaterial(centerX, centerY, centerZ, volume, massUnits, maxRadius, materialHandle); }
	public ExactMixtureTransfer tryInsertExactMixture(
			int centerX, int centerY, int centerZ, List<MaterialPortion> portions, int maxRadius
	) { return domainInventory.tryInsertExactMixture(centerX, centerY, centerZ, portions, maxRadius); }
	public ExactMixtureTransfer tryInsertExactMixture(
			int centerX, int centerY, int centerZ, List<MaterialPortion> portions, int maxRadius,
			boolean externalSource
	) { return domainInventory.tryInsertExactMixture(centerX, centerY, centerZ, portions, maxRadius, externalSource); }
	public ExactMixtureTransfer tryInsertExactMixture(
			int centerX, int centerY, int centerZ, List<MaterialPortion> portions, int maxRadius,
			boolean externalSource, double internalEnergy
	) { return domainInventory.tryInsertExactMixture(centerX, centerY, centerZ, portions, maxRadius, externalSource, internalEnergy); }
	public ExactChemicalTransfer tryInsertExactChemical(
			int centerX, int centerY, int centerZ, ChemicalTransferLedger ledger, int maxRadius
	) { return domainInventory.tryInsertExactChemical(centerX, centerY, centerZ, ledger, maxRadius); }
	public ExactChemicalTransfer tryInsertExactChemical(
			int centerX, int centerY, int centerZ, ChemicalTransferLedger ledger, int maxRadius,
			boolean externalSource
	) { return domainInventory.tryInsertExactChemical(centerX, centerY, centerZ, ledger, maxRadius, externalSource); }
	public ExactChemicalTransfer tryInsertExactChemical(
			int centerX, int centerY, int centerZ, ChemicalTransferLedger ledger, int maxRadius,
			boolean externalSource, double internalEnergy
	) { return domainInventory.tryInsertExactChemical(centerX, centerY, centerZ, ledger, maxRadius, externalSource, internalEnergy); }
	public ExactChemicalTransfer tryExtractExactChemical(
			int centerX, int centerY, int centerZ, float volume, int maxRadius
	) { return domainInventory.tryExtractExactChemical(centerX, centerY, centerZ, volume, maxRadius); }
	public ExactChemicalTransfer tryExtractExactChemical(
			int centerX, int centerY, int centerZ, float volume, int maxRadius,
			java.util.function.Predicate<ChemicalTransferLedger> acceptsLedger
	) { return domainInventory.tryExtractExactChemical(centerX, centerY, centerZ, volume, maxRadius, acceptsLedger); }
	public ExactVolumeTransfer tryExtractExactVolume(int centerX, int centerY, int centerZ, float volume, int maxRadius) { return domainInventory.tryExtractExactVolume(centerX, centerY, centerZ, volume, maxRadius); }
	public ExactVolumeTransfer tryExtractExactVolume(int centerX, int centerY, int centerZ, float volume, int maxRadius, int materialHandle) { return domainInventory.tryExtractExactVolume(centerX, centerY, centerZ, volume, maxRadius, materialHandle); }
	public ExactMixtureTransfer tryExtractExactMixture(
			int centerX, int centerY, int centerZ, float volume, int maxRadius
	) { return domainInventory.tryExtractExactMixture(centerX, centerY, centerZ, volume, maxRadius); }
	public void setMarkerChemical(int ordinal, Map<String, Long> stableAmounts, boolean indicator) { domainCollision.setMarkerChemical(ordinal, stableAmounts, indicator); }
	public int materialHandle(String materialId) { return domainCollision.materialHandle(materialId); }
	public void restoreParticleState(FlipParticleState state) { domainCollision.restoreParticleState(state); }
	public void restoreMaterialParticleState(FlipMaterialParticleState state) { domainCollision.restoreMaterialParticleState(state); }
	public void restoreChemicalParticleState(FlipChemicalParticleState state, ChemicalRegistry registry) { domainCollision.restoreChemicalParticleState(state, registry); }
	public FlipParticleState exportParticleState() { return domainCollision.exportParticleState(); }
	public FlipMaterialParticleState exportMaterialParticleState() { return domainCollision.exportMaterialParticleState(); }
	public float setSolid(int x, int y, int z, boolean value) { return domainCollision.setSolid(x, y, z, value); }
	public float setSolidMicroMask(int x, int y, int z, int mask) { return domainCollision.setSolidMicroMask(x, y, z, mask); }
	public FlipChemicalParticleState exportChemicalParticleState() { return domainCollision.exportChemicalParticleState(); }
	public ChemicalDisplacement setSolidMicroMaskChemical(int x, int y, int z, int mask) { return domainCollision.setSolidMicroMaskChemical(x, y, z, mask); }
	public MaterialDisplacement setSolidMicroMaskMaterial(int x, int y, int z, int mask) { return domainCollision.setSolidMicroMaskMaterial(x, y, z, mask); }
	public CollisionEditPlan planCollisionEdits(List<CollisionEdit> edits) { return domainCollision.planCollisionEdits(edits); }
	public void beginCollisionEdits(CollisionEditPlan plan) { domainCollision.beginCollisionEdits(plan); }
	public boolean isSolid(int x, int y, int z) { return domainCollision.isSolid(x, y, z); }
	public int solidMicroMask(int x, int y, int z) { return domainCollision.solidMicroMask(x, y, z); }
	public float solidVolumeFraction(int x, int y, int z) { return domainCollision.solidVolumeFraction(x, y, z); }
	public float openFaceFractionX(int x, int y, int z) { return domainCollision.openFaceFractionX(x, y, z); }
	public float openFaceFractionY(int x, int y, int z) { return domainCollision.openFaceFractionY(x, y, z); }
	public float openFaceFractionZ(int x, int y, int z) { return domainCollision.openFaceFractionZ(x, y, z); }
	public void setVolume(int x, int y, int z, float value) { domainCollision.setVolume(x, y, z, value); }
	public void setVolumes(float[] cellVolumes) { domainCollision.setVolumes(cellVolumes); }
	public void setVolumes(float[] cellVolumes,int[] materialHandles) { domainCollision.setVolumes(cellVolumes, materialHandles); }
	public float volume(int x, int y, int z) { return domainCollision.volume(x, y, z); }
	public float pressure(int x, int y, int z) { return domainCollision.pressure(x, y, z); }
	public float totalVolume() { return domainCollision.totalVolume(); }
	public double totalVolumeExact() { return domainCollision.totalVolumeExact(); }
	public float totalOpenVolume() { return domainCollision.totalOpenVolume(); }
	public double totalOpenVolumeExact() { return domainCollision.totalOpenVolumeExact(); }
	public long totalMassUnits() { return domainCollision.totalMassUnits(); }
	public double totalMass() { return domainCollision.totalMass(); }
	public int dominantMaterialInCell(int x, int y, int z) { return domainCollision.dominantMaterialInCell(x, y, z); }
	public double exactVolumeNear(int centerX, int centerY, int centerZ, int maxRadius, int materialHandle) { return domainCollision.exactVolumeNear(centerX, centerY, centerZ, maxRadius, materialHandle); }
	public void bindMaterialRegistry(FluidMaterialRegistry registry) { domainCollision.bindMaterialRegistry(registry); }
	public float maximumParticleSpeed() { return domainCollision.maximumParticleSpeed(); }
	public float maximumPressure() { return domainCollision.maximumPressure(); }
	public byte[] copyQuantizedVolume() { return domainCollision.copyQuantizedVolume(); }
	public byte[] copyBinarySolidMask() { return domainCollision.copyBinarySolidMask(); }
	public byte[] copyCollisionMicroMasks() { return domainCollision.copyCollisionMicroMasks(); }
	public byte[] copyMarkerMask() { return domainCollision.copyMarkerMask(); }
	public FluidMaterialRenderSnapshot copyMaterialRenderSnapshot() { return domainCollision.copyMaterialRenderSnapshot(); }
	public VolumeDistribution volumeDistribution(float visibleThreshold) { return domainCollision.volumeDistribution(visibleThreshold); }
	public SolidDistribution solidDistribution() { return domainCollision.solidDistribution(); }
	public boolean containsFluidAt(float localX, float localY, float localZ) { return domainCollision.containsFluidAt(localX, localY, localZ); }
	public FluidMaterialRegistry materialRegistry() { return domainCapabilities.materialRegistry(); }
	public ChemicalParcelStore chemicalParcels() { return domainCapabilities.chemicalParcels(); }
	public ChemicalDisplaySnapshot copyChemicalDisplaySnapshot() { return domainCapabilities.copyChemicalDisplaySnapshot(); }
	public ChemicalTransferLedger chemicalLedgerForMarkerRange(int fromInclusive, int toExclusive) { return domainCapabilities.chemicalLedgerForMarkerRange(fromInclusive, toExclusive); }
	public IndicatorEditResult editIndicatorFloodFill(
			int startX, int startY, int startZ, boolean indicated, int maxScannedCells, double maximumAddedVolumeCubicMeters
	) { return domainCapabilities.editIndicatorFloodFill(startX, startY, startZ, indicated, maxScannedCells, maximumAddedVolumeCubicMeters); }
	public void bindChemistry(ChemicalRegistry registry) { domainCapabilities.bindChemistry(registry); }
	public void bindThermal(
			ThermalRegistry registry, FluidMaterialRegistry liquidRegistry, double initialTemperatureK
	) { domainCapabilities.bindThermal(registry, liquidRegistry, initialTemperatureK); }
	public void bindThermal(
			ThermalRegistry registry, FluidMaterialRegistry liquidRegistry, double initialTemperatureK,
			int maximumTransportSubsteps
	) { domainCapabilities.bindThermal(registry, liquidRegistry, initialTemperatureK, maximumTransportSubsteps); }
	public void restoreThermalParticleState(
			FlipThermalParticleState state, ThermalRegistry registry
	) { domainCapabilities.restoreThermalParticleState(state, registry); }
	public void restoreThermalParticleState(
			FlipThermalParticleState state, ThermalRegistry registry, int maximumTransportSubsteps
	) { domainCapabilities.restoreThermalParticleState(state, registry, maximumTransportSubsteps); }
	public void restoreThermalOwnerState(ThermalOwnerSnapshot state, ThermalRegistry registry, int maximumTransportSubsteps) { domainCapabilities.restoreThermalOwnerState(state, registry, maximumTransportSubsteps); }
	public void restoreThermalOwnerState(ThermalOwnerSnapshot state, ThermalRegistry registry) { domainCapabilities.restoreThermalOwnerState(state, registry); }
	public ThermalOwnerSnapshot exportThermalOwnerState() { return domainCapabilities.exportThermalOwnerState(); }
	public FlipThermalParticleState exportThermalParticleState() { return domainCapabilities.exportThermalParticleState(); }
	public void setThermalTemperatureInCell(int x, int y, int z, double temperatureK) { domainCapabilities.setThermalTemperatureInCell(x, y, z, temperatureK); }
	public double thermalTemperatureInCell(int x, int y, int z) { return domainCapabilities.thermalTemperatureInCell(x, y, z); }
	public List<FiniteThermalExchange.Contact> finiteThermalContacts(List<ThermalContactRequest> requests) { return domainCapabilities.finiteThermalContacts(requests); }
	public FiniteThermalCellPlan planFiniteThermalCellExchange(List<ThermalCellEnergyDelta> deltas) { return domainCapabilities.planFiniteThermalCellExchange(deltas); }
	public void commitFiniteThermalCellExchange(FiniteThermalCellPlan plan) { domainCapabilities.commitFiniteThermalCellExchange(plan); }
	public ThermalDisplaySnapshot copyThermalDisplaySnapshot() { return domainCapabilities.copyThermalDisplaySnapshot(); }
	public void addCoherentPhaseExternal(
			int cellX, int cellY, int cellZ, String phaseId, long occupiedVolumeSvq,
			long massUnits, CoherentPhaseStore.Composition composition, double internalEnergy
	) { domainCapabilities.addCoherentPhaseExternal(cellX, cellY, cellZ, phaseId, occupiedVolumeSvq, massUnits, composition, internalEnergy); }
	public long totalCoherentSolidVolumeSvq() { return domainCapabilities.totalCoherentSolidVolumeSvq(); }
	public double totalCoherentMass() { return domainCapabilities.totalCoherentMass(); }
	public boolean replaceThermalBoundaryTemperatures(double[] temperaturesK) { return domainCapabilities.replaceThermalBoundaryTemperatures(temperaturesK); }
	public ThermalTransport.Stats stepThermalOnly(double dtSeconds) { return domainCapabilities.stepThermalOnly(dtSeconds); }
	public ThermalPhaseStats stepInertPhaseChangeOnly(int maximumOperations) { return domainCapabilities.stepInertPhaseChangeOnly(maximumOperations); }
	public GasThermalPhaseStats stepGasPhaseChangeOnly(int maximumOperations) { return domainCapabilities.stepGasPhaseChangeOnly(maximumOperations); }
	static long minimumPhaseBatchesForCapacity(
			long occupiedSvq, long capacitySvq, long signedVolumeSvqPerBatch
	) { return DomainCapabilities.minimumPhaseBatchesForCapacity(occupiedSvq, capacitySvq, signedVolumeSvqPerBatch); }
	public int sizeX() { return domainPhaseInventory.sizeX(); }
	public int sizeY() { return domainPhaseInventory.sizeY(); }
	public int sizeZ() { return domainPhaseInventory.sizeZ(); }
	public int particleCount() { return domainPhaseInventory.particleCount(); }
	public FluidDomain.CarrierViscosityStats carrierViscosityStats() { return domainPhaseInventory.carrierViscosityStats(); }
	public Double carrierViscosityPascalSeconds(int cell) { return domainPhaseInventory.carrierViscosityPascalSeconds(cell); }
	public GasPressureBoundary gasBoundary() { return domainPhaseInventory.gasBoundary(); }
	public void setGasNodeStandardVolume(int nodeId, double standardVolume) { domainPhaseInventory.setGasNodeStandardVolume(nodeId, standardVolume); }
	public void sealDomainBoundary() { domainPhaseInventory.sealDomainBoundary(); }
	public void bindGasRegistry(GasRegistry registry) { domainPhaseInventory.bindGasRegistry(registry); }
	public GasStateSnapshot bootstrapGasState(String chemicalRegistryFingerprint) { return domainPhaseInventory.bootstrapGasState(chemicalRegistryFingerprint); }
	public GasStateSnapshot exportGasState(String chemicalRegistryFingerprint) { return domainPhaseInventory.exportGasState(chemicalRegistryFingerprint); }
	public void restoreGasState(GasStateSnapshot state, GasRegistry registry) { domainPhaseInventory.restoreGasState(state, registry); }
	public void restoreGasState(GasStateSnapshot state, GasRegistry registry, FlipThermalParticleState thermal) { domainPhaseInventory.restoreGasState(state, registry, thermal); }
	public void restoreGasState(GasStateSnapshot state, GasRegistry registry, mmm.thermal.ThermalOwnerSnapshot thermal) { domainPhaseInventory.restoreGasState(state, registry, thermal); }
	public boolean gasPressureActive() { return domainPhaseInventory.gasPressureActive(); }
	public double gasNodeTemperatureK(int nodeId) { return domainPhaseInventory.gasNodeTemperatureK(nodeId); }
	public double gasNodeGaugePressurePa(int nodeId) { return domainPhaseInventory.gasNodeGaugePressurePa(nodeId); }
	public int airNodeAt(int x, int y, int z) { return domainPhaseInventory.airNodeAt(x, y, z); }
	public byte[] copyGasNodeMask() { return domainPhaseInventory.copyGasNodeMask(); }
	public int maximumParticleCount() { return domainPhaseInventory.maximumParticleCount(); }
	public int markerCellCount() { return domainPhaseInventory.markerCellCount(); }
	public FluidSolverConfig config() { return domainPhaseInventory.config(); }
	public SolverUnitScale unitScale() { return domainPhaseInventory.unitScale(); }
	public double physicalVolumeCubicMetres(double normalizedVolume) { return domainPhaseInventory.physicalVolumeCubicMetres(normalizedVolume); }
	public boolean hasMaterialState() { return domainPhaseInventory.hasMaterialState(); }
	public boolean hasChemistryState() { return domainPhaseInventory.hasChemistryState(); }
	public boolean hasGranularState() { return domainPhaseInventory.hasGranularState(); }
	public boolean hasThermalState() { return domainPhaseInventory.hasThermalState(); }
	public boolean hasAmbientGasCells() { return domainPhaseInventory.hasAmbientGasCells(); }
	public ThermalCellGrid.TemperatureRange observedCarrierTemperatureRange() { return domainPhaseInventory.observedCarrierTemperatureRange(); }
	public int chemicalParcelCount() { return domainPhaseInventory.chemicalParcelCount(); }
	public long totalChemicalSpeciesQuanta() { return domainPhaseInventory.totalChemicalSpeciesQuanta(); }
	public int granularDepositGroupCount() { return domainPhaseInventory.granularDepositGroupCount(); }
	public int coherentPhaseGroupCount() { return domainPhaseInventory.coherentPhaseGroupCount(); }
	public boolean hasPendingPhaseVolumeWork() { return domainPhaseInventory.hasPendingPhaseVolumeWork(); }
	public double densityInCell(int x, int y, int z) { return domainPhaseInventory.densityInCell(x, y, z); }
	public FluidCellInventoryView observeCellInventory(int x, int y, int z) { return domainPhaseInventory.observeCellInventory(x, y, z); }
	public CellCarrierInventoryView observeCellCarriers(int x, int y, int z) { return domainPhaseInventory.observeCellCarriers(x, y, z); }
	public boolean hasCondensedInventoryInCell(int x, int y, int z) { return domainPhaseInventory.hasCondensedInventoryInCell(x, y, z); }
	public PendingPhaseVolumeState exportPendingPhaseVolumeState() { return domainPhaseInventory.exportPendingPhaseVolumeState(); }
	public FluidDomainState captureAuthoritativeState() { return domainPhaseInventory.captureAuthoritativeState(); }
	public FluidDomainState.Layout authoritativeLayout() { return domainPhaseInventory.authoritativeLayout(); }
	public static FluidDomain restoreAuthoritativeState(FluidDomainState state) { return DomainPhaseInventory.restoreAuthoritativeState(state); }
	public void restorePendingPhaseVolumeState(PendingPhaseVolumeState state) { domainPhaseInventory.restorePendingPhaseVolumeState(state); }
	public ThermalTransport.Stats lastThermalTransportStats() { return domainPhaseInventory.lastThermalTransportStats(); }
	public ThermalAuditLedger thermalAudit() { return domainPhaseInventory.thermalAudit(); }
	public void settleExternalContainerExtraction(double removedCarrierEnergy, double coupledItemEnergy) { domainPhaseInventory.settleExternalContainerExtraction(removedCarrierEnergy, coupledItemEnergy); }
	public void settleExternalContainerInsertion(double insertedCarrierEnergy, double coupledItemEnergy) { domainPhaseInventory.settleExternalContainerInsertion(insertedCarrierEnergy, coupledItemEnergy); }
	public double totalInternalEnergy() { return domainPhaseInventory.totalInternalEnergy(); }
	public SignedDyadic exactInternalEnergy() { return domainPhaseInventory.exactInternalEnergy(); }
	public SignedDyadic exactGasInternalEnergy() { return domainPhaseInventory.exactGasInternalEnergy(); }
	public int granularParcelCount() { return domainPhaseInventory.granularParcelCount(); }
	public int granularDepositCellCount() { return domainPhaseInventory.granularDepositCellCount(); }
	public int granularSleepingColumnCount() { return domainPhaseInventory.granularSleepingColumnCount(); }
	public boolean granularReadyForHydrostaticSleep() { return domainPhaseInventory.granularReadyForHydrostaticSleep(); }
	public GranularDepositDynamics.Stats lastGranularDepositStats() { return domainPhaseInventory.lastGranularDepositStats(); }
	public double lastGranularBoundaryImpulseX() { return domainPhaseInventory.lastGranularBoundaryImpulseX(); }
	public double lastGranularBoundaryImpulseY() { return domainPhaseInventory.lastGranularBoundaryImpulseY(); }
	public double lastGranularBoundaryImpulseZ() { return domainPhaseInventory.lastGranularBoundaryImpulseZ(); }
	public int lastGranularCapacityHeld() { return domainPhaseInventory.lastGranularCapacityHeld(); }
	public GranularStateSnapshot exportGranularState() { return domainPhaseInventory.exportGranularState(); }
	public GranularDisplaySnapshot copyGranularDisplaySnapshot() { return domainPhaseInventory.copyGranularDisplaySnapshot(); }
	public GranularAuditLedger granularExternalSource() { return domainPhaseInventory.granularExternalSource(); }
	public GranularAuditLedger granularEscapedSink() { return domainPhaseInventory.granularEscapedSink(); }
	public long totalGranularSolidVolumeSvq() { return domainPhaseInventory.totalGranularSolidVolumeSvq(); }
	public double totalGranularMass() { return domainPhaseInventory.totalGranularMass(); }
	public GranularClear removeAllGranularExact() { return domainPhaseInventory.removeAllGranularExact(); }
	public GranularClearPlan planRemoveAllGranularExact() { return domainPhaseInventory.planRemoveAllGranularExact(); }
	public GranularClear commitRemoveAllGranularExact(GranularClearPlan plan) { return domainPhaseInventory.commitRemoveAllGranularExact(plan); }
	public long granularSolidVolumeSvq(int cellX, int cellY, int cellZ) { return domainPhaseInventory.granularSolidVolumeSvq(cellX, cellY, cellZ); }
	public SolidCompositionPool solidCompositions() { return domainPhaseInventory.solidCompositions(); }
	public boolean precipitationEnabled() { return domainPhaseInventory.precipitationEnabled(); }
	public PhaseTransferStats lastPhaseTransferStats() { return domainPhaseInventory.lastPhaseTransferStats(); }
	public ThermalPhaseStats lastThermalPhaseStats() { return domainPhaseInventory.lastThermalPhaseStats(); }
	public GasThermalPhaseStats lastGasThermalPhaseStats() { return domainPhaseInventory.lastGasThermalPhaseStats(); }
	public ChemicalParcelStore.ReactionStats lastChemicalReactionStats() { return domainPhaseInventory.lastChemicalReactionStats(); }
	public void setPrecipitationEnabled(boolean enabled) { domainPhaseInventory.setPrecipitationEnabled(enabled); }
	public void bindGranular(GranularRegistry registry, FluidMaterialRegistry liquidRegistry,
			MaterialRegistry materialDefinitions) { domainPhaseInventory.bindGranular(registry, liquidRegistry, materialDefinitions); }
	public void restoreGranularState(
			GranularStateSnapshot snapshot, GranularRegistry registry, FluidMaterialRegistry liquidRegistry,
			MaterialRegistry materialDefinitions
	) { domainPhaseInventory.restoreGranularState(snapshot, registry, liquidRegistry, materialDefinitions); }
	public GranularInsertion tryInsertExactGranular(
			int cellX, int cellY, int cellZ, int materialHandle, int compositionHandle,
			long solidVolumeSvq, long massUnits
	) { return domainPhaseInventory.tryInsertExactGranular(cellX, cellY, cellZ, materialHandle, compositionHandle, solidVolumeSvq, massUnits); }
	public GranularInsertion tryInsertExactGranular(
			int cellX, int cellY, int cellZ, GranularTransferLedger ledger
	) { return domainPhaseInventory.tryInsertExactGranular(cellX, cellY, cellZ, ledger); }
	public GranularInsertion tryInsertExactGranular(
			int cellX, int cellY, int cellZ, GranularTransferLedger ledger, int maxRadius
	) { return domainPhaseInventory.tryInsertExactGranular(cellX, cellY, cellZ, ledger, maxRadius); }
	public GranularInsertion tryInsertExactGranular(
			int cellX, int cellY, int cellZ, GranularTransferLedger ledger, int maxRadius, boolean externalSource
	) { return domainPhaseInventory.tryInsertExactGranular(cellX, cellY, cellZ, ledger, maxRadius, externalSource); }
	public GranularExtraction tryExtractExactGranular(
			int cellX, int cellY, int cellZ, long maximumSolidVolumeSvq
	) { return domainPhaseInventory.tryExtractExactGranular(cellX, cellY, cellZ, maximumSolidVolumeSvq); }
	public GranularExtraction tryExtractExactGranular(
			int cellX, int cellY, int cellZ, long maximumSolidVolumeSvq, int maxRadius
	) { return domainPhaseInventory.tryExtractExactGranular(cellX, cellY, cellZ, maximumSolidVolumeSvq, maxRadius); }
	public GranularExtraction tryExtractExactGranular(
			int cellX, int cellY, int cellZ, long maximumSolidVolumeSvq, int maxRadius, boolean escapedSink
	) { return domainPhaseInventory.tryExtractExactGranular(cellX, cellY, cellZ, maximumSolidVolumeSvq, maxRadius, escapedSink); }
	public GranularExtraction tryExtractExactGranular(
			int cellX, int cellY, int cellZ, long maximumSolidVolumeSvq, int maxRadius, boolean escapedSink,
			java.util.function.Predicate<GranularTransferLedger> itemAcceptance
	) { return domainPhaseInventory.tryExtractExactGranular(cellX, cellY, cellZ, maximumSolidVolumeSvq, maxRadius, escapedSink, itemAcceptance); }

	// Immutable observations and prepared transaction contracts.
	record CellCandidate(int index, int distanceSquared) { }
	public record VelocitySample(float x, float y, float z) { static final VelocitySample ZERO = new VelocitySample(0.0F, 0.0F, 0.0F); }
	public record BodyFluidSample(
			double displacedVolumeCubicMetres,
			double centerXMetres, double centerYMetres, double centerZMetres,
			double velocityXMetresPerSecond, double velocityYMetresPerSecond, double velocityZMetresPerSecond
	) {
		static final BodyFluidSample EMPTY = new BodyFluidSample(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);

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
			double porousBoundaryWorkJoules,
			double gravityWorkJoules,
			double thermalBuoyancyWorkJoules,
			double surfaceTensionWorkJoules,
			double viscousWorkJoules,
			double fixedBoundaryWorkJoules,
			double pressureProjectionWorkJoules,
			double contactWorkJoules,
			double volumeTransportTransferJoules,
			double carrierInventoryTransferJoules,
			SignedDyadic thermalInventoryMechanicalWork,
			double closureResidualJoules
	) {
		static MechanicalStepAudit notCaptured() {
			return new MechanicalStepAudit(false,0,0,0,0,
					0,0,0,0,0,0,0,0,0,0,SignedDyadic.ZERO,0);
		}
	}
	record MaterialInventory(double volume, double mass, double centerY) { }
	public record CarrierViscosityStats(int extrapolatedCells, String sample) {
		public CarrierViscosityStats {
			if (extrapolatedCells < 0) throw new IllegalArgumentException("Extrapolated cell count is negative");
			if (extrapolatedCells == 0) sample = "";
			else java.util.Objects.requireNonNull(sample, "sample");
		}

		public boolean extrapolated() { return extrapolatedCells > 0; }
	}
	public record ViscousStepStats(int stressRows, double spectralBoundPerSecond,
			double boundaryImpulseX, double boundaryImpulseY, double boundaryImpulseZ, double dissipatedJoules,
			double roundoffImpulseX, double roundoffImpulseY, double roundoffImpulseZ) {
		static final ViscousStepStats INACTIVE = new ViscousStepStats(0, 0, 0, 0, 0, 0, 0, 0, 0);
		ViscousStepStats plus(ViscousStepStats other) {
			return new ViscousStepStats(Math.addExact(stressRows, other.stressRows),
					Math.max(spectralBoundPerSecond, other.spectralBoundPerSecond),
					boundaryImpulseX + other.boundaryImpulseX, boundaryImpulseY + other.boundaryImpulseY,
					boundaryImpulseZ + other.boundaryImpulseZ, dissipatedJoules + other.dissipatedJoules,
					roundoffImpulseX + other.roundoffImpulseX, roundoffImpulseY + other.roundoffImpulseY,
					roundoffImpulseZ + other.roundoffImpulseZ);
		}
	}
	public record StepStats(
			int substeps,
			int pressureIterations,
			float pressureResidual,
			float volumeBefore,
			float volumeAfter,
			double massBefore,
			double massAfter,
			int chemistryDirtyCells,
			int chemistryEvaluations,
			int chemistryChangedMarkers,
			FluidDomain.ThermalPhaseStats condensedPhase,
			FluidDomain.GasThermalPhaseStats gasPhase,
			MechanicalStepAudit mechanicalAudit,
			FluidDomain.ThermalAxisStats thermalAxis,
			GranularDepositDynamics.Stats granularTransition,
			CarrierViscosityStats carrierViscosity,
			ViscousStepStats viscosity
	) {
		public StepStats {
			java.util.Objects.requireNonNull(thermalAxis, "thermalAxis");
			java.util.Objects.requireNonNull(granularTransition, "granularTransition");
			java.util.Objects.requireNonNull(carrierViscosity, "carrierViscosity");
			java.util.Objects.requireNonNull(viscosity, "viscosity");
		}
		public float relativeVolumeError() { return volumeBefore == 0.0F ? 0.0F : Math.abs(volumeAfter - volumeBefore) / volumeBefore; }
		public double relativeMassError() { return massBefore == 0.0 ? 0.0 : Math.abs(massAfter - massBefore) / massBefore; }
	}
	public static final class PressureSolveBlockedException extends IllegalStateException {
		PressureSolveBlockedException(String message) { super(message); }
	}
	public record ThermalAxisStats(
			ThermalTransport.Stats transport,
			ChemicalParcelStore.ReactionStats chemistry,
			PhaseTransferStats granularPhase,
			FluidDomain.ThermalPhaseStats condensedPhase,
			FluidDomain.GasThermalPhaseStats gasPhase
	) { }
	public record PhaseTransferStats(int transfers, int precipitations, int dissolutions, long signedSolidSvq) { }
	public record WaterClearPlan(
			int markerCount, long totalVolumeBits, long totalMassUnits, ChemicalTransferLedger drained,
			SignedDyadic internalEnergy, long mutationRevision, long chemicalRevision, LiquidCellInventory.Plan inventory
	) { }
	public record CondensedClear(long coherentVolumeSvq, long coherentMassUnits, int coherentGroups) {
		public double coherentMass() { return mmm.units.Mass.kilograms(coherentMassUnits); }
	}
	public record CondensedClearPlan(
			WaterClearPlan liquid, List<CoherentPhaseStore.Group> coherentGroups,
			ChemicalTransferLedger drained, SignedDyadic coherentEnergy,
			long coherentVolumeSvq, long coherentMassUnits, long mutationRevision, long chemicalRevision
	) {
		public CondensedClearPlan { coherentGroups = List.copyOf(coherentGroups); }
	}
	public record OperatorClear(CondensedClear condensed, FluidDomain.GranularClear granular) { }
	public record OperatorGasVent(GasAuditLedger removed, SignedDyadic internalEnergy, int sealedNodes) { }
	public record OperatorGasVentPlan(
			int nodeCount, GasAuditLedger removed, AirNetwork.RemapPlan gasPlan,
			GasEnergyStore.GasVentPlan thermalPlan, ThermalDomainState.Revision thermalRevision,
			GasRuntimeGeometry sourceGeometry
	) { }
	public static final class OperatorClearPlan {
		final CondensedClearPlan condensed;
		final FluidDomain.GranularClearPlan granular;
		final GasTopologyCoordinator.GasTopologyPlan gasTopology;
		final ThermalDomainState.Revision thermalRevision;

		OperatorClearPlan(
				CondensedClearPlan condensed, FluidDomain.GranularClearPlan granular,
				GasTopologyCoordinator.GasTopologyPlan gasTopology, ThermalDomainState.Revision thermalRevision
		) {
			this.condensed = condensed;
			this.granular = granular;
			this.gasTopology = gasTopology;
			this.thermalRevision = thermalRevision;
		}

		public CondensedClearPlan condensed() { return condensed; }
		public FluidDomain.GranularClearPlan granular() { return granular; }
	}
	public record CollisionEdit(int x, int y, int z, int mask) { }
	public static final class CollisionEditPlan {
		final FluidDomain owner;
		final List<CollisionEdit> edits;
		final int[] previousMasks;
		final int planMarkerCount;
		final long totalVolumeBits;
		final long totalMassUnits;
		final GranularCollisionPlan granular;
		final LiquidCollisionEdit liquid;
		final ThermalDomainState.Revision thermalRevision;
		boolean consumed;

		CollisionEditPlan(
				FluidDomain owner, List<CollisionEdit> edits, int[] previousMasks,
				int planMarkerCount, long totalVolumeBits, long totalMassUnits, GranularCollisionPlan granular, LiquidCollisionEdit liquid,
				ThermalDomainState.Revision thermalRevision
		) {
			this.owner = owner;
			this.edits = edits;
			this.previousMasks = previousMasks.clone();
			this.planMarkerCount = planMarkerCount;
			this.totalVolumeBits = totalVolumeBits;
			this.totalMassUnits = totalMassUnits;
			this.granular = granular; this.liquid = liquid; this.thermalRevision = thermalRevision;
		}
	}
	record CollisionDeposit(int cell, GranularDepositStore.Group group) { }
	record GranularCollisionPlan(
			boolean[] selectedParcels, List<CollisionDeposit> depositGroups,
			GranularAuditLedger sinkAfter, boolean[] affectedCells,
			int parcelCount, long totalSvq, long totalMassUnits,
			SignedDyadic removedEnergy, ThermalDomainState.Revision thermalRevision
	) { }
	public static final class CollisionEditBlockedException extends IllegalStateException {
		public CollisionEditBlockedException(String message, Throwable cause) { super(message, cause); }
	}
	static final class RemovedMaterialLedger {
		final double[] volume;
		final long[] mass;
		RemovedMaterialLedger(int materialCount) {
			volume = new double[materialCount]; mass = new long[materialCount];
		}
		void add(int material, double addedVolume, long addedMass) {
			volume[material] += addedVolume; mass[material] = Math.addExact(mass[material], addedMass);
		}
		List<MaterialPortion> build() {
			ArrayList<MaterialPortion> result = new ArrayList<>();
			for (int material = 0; material < volume.length; material++) if (volume[material] > 0.0) {
				if (mass[material] < 0L) {
					throw new IllegalStateException("Displaced material has negative mass");
				}
				result.add(new MaterialPortion(material, volume[material], mass[material]));
			}
			return List.copyOf(result);
		}
	}
	static final class RemovedThermalEnergy {
		SignedDyadic value = SignedDyadic.ZERO;
		void add(double energy) { value = value.add(SignedDyadic.of(energy)); }
		SignedDyadic value() { return value; }
	}
	public record ExactVolumeTransfer(boolean transferred, double volume, long massUnits, double internalEnergy) {
		public ExactVolumeTransfer(boolean transferred, double volume, long massUnits) {
			this(transferred, volume, massUnits, 0.0);
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
	public record MaterialPortion(int materialHandle, double volume, long massUnits) { }
	record IndicatorAimSeed(int cell, int scannedCells, String failure) { }
	public record IndicatorEditResult(
			boolean accepted, int scannedCells, int changedMarkers, double affectedVolumeCubicMeters, String failure
	) {
		static IndicatorEditResult rejected(String failure) {
			return new IndicatorEditResult(false, 0, 0, 0.0, failure);
		}
	}
	public record ThermalContactRequest(
			int ordinal, int cellX, int cellY, int cellZ, double conductanceWPerK
	) {
		public ThermalContactRequest {
			if (ordinal < 0 || !(Double.isFinite(conductanceWPerK) && conductanceWPerK > 0.0)) {
				throw new IllegalArgumentException("Finite thermal contact request is invalid");
			}
		}
	}
	public record ThermalCellEnergyDelta(int cellX, int cellY, int cellZ, double internalEnergyDeltaJ) {
		public ThermalCellEnergyDelta {
			if (!Double.isFinite(internalEnergyDeltaJ)) {
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
	public record GranularClear(long solidVolumeSvq, long massUnits, int activeParcels, int depositGroups) {
		public double mass() { return mmm.units.Mass.kilograms(massUnits); }
	}
	public record GranularClearPlan(
			GranularAuditLedger removed, GranularAuditLedger sinkAfter,
			long solidVolumeSvq, long totalMassUnits, int activeParcels, int depositGroups,
			SignedDyadic internalEnergy, long granularRevision
	) { }
	public record GranularInsertion(boolean inserted, long solidVolumeSvq, long massUnits, String failure) {
		static GranularInsertion rejected(String reason) { return new GranularInsertion(false, 0L, 0L, reason); }
	}
	public record GranularExtraction(
			boolean transferred, GranularTransferLedger ledger, double internalEnergy, String failure
	) {
		static GranularExtraction rejected(String reason) { return new GranularExtraction(false, null, 0.0, reason); }
	}
}

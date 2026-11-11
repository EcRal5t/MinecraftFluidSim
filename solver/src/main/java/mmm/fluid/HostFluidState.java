package mmm.fluid;

import mmm.fluid.FluidDomainContracts.*;

import mmm.inventory.LiquidCellInventory;
import mmm.inventory.PhaseOccupancyState;
import mmm.thermal.ThermalDomainState;
import mmm.thermal.ThermalCarrierContext;
import mmm.chemistry.RegionChemistry;
import mmm.granular.GranularCouplingFields;
import mmm.granular.GranularDepositDynamics;
import mmm.granular.GranularPhaseTransferEngine;
import mmm.units.SolverUnitScale;
import java.util.List;
import mmm.spatial.SpatialGrid;
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
public final class HostFluidState {
	// Authoritative state, derived workspaces and responsibility handlers.
	static final int PARTICLES_PER_FULL_CELL = 8;
	static final float PARTICLE_VOLUME = 1.0F / PARTICLES_PER_FULL_CELL;
	static final int NO_PHASE = Integer.MIN_VALUE;
	final int sizeX;
	final int sizeY;
	final int sizeZ;
	final int cellCount;
	final SpatialGrid grid;
	final FluidDefinitions definitions;
	final FluidSolverConfig config;
	final SolverUnitScale unitScale;
	final DomainGeometry geometry;
	EntityBodyState entityBodies = EntityBodyState.EMPTY;
	final PhysicalActivityGroups activityGroups = new PhysicalActivityGroups(this);
	final EntityBodyCoupling entityBodyCoupling = new EntityBodyCoupling(this);
	long[] occupiedSolidSvq() {
		return entityBodyCoupling.addTo(granularCoupling == null ? new long[cellCount] : granularCoupling.copySolidSvq());
	}
	final CellLiquidGrid liquidGrid;
	final MacVelocityGrid mac;
	final CellMacStage cellMacStage;
	final SurfaceTensionFields tension;
	final CondensedInventoryClearPlanner clearPlanner;
	final MaterialTransferPlanner transferPlanner;
	final GranularTransferPlanner granularTransferPlanner;
	final DomainInventoryLifecyclePlanner lifecyclePlanner;
	final GasInitialization gasInitialization;
	final GasLifecyclePlanner gasLifecyclePlanner;
	final PressureSystem pressureSystem;
	final GranularMechanicalStage granularMechanicalStage;
	final float[] volume;
	LiquidCellInventory liquidCells;
	PhaseOccupancyState phaseOccupancy;
	boolean phaseOccupancyDirty = true;
	long carrierMutationRevision;
	FluidMaterialRegistry materialRegistry;
	MaterialRegistry materialDefinitions;
	final LiquidCarrierProperties carrierPropertiesWorkspace;
	RegionChemistry chemistry;
	final GasDomainState gasState = new GasDomainState();
	final GasPressureBoundary gasBoundary;
	FluidDomainContracts.CollisionEditPlan activeCollisionPlan;
	int activeCollisionEditIndex;
	GranularDomainState granularState;
	GranularPhaseTransferEngine granularPhaseTransfer;
	boolean precipitationEnabled;
	FluidDomainContracts.PhaseTransferStats lastPhaseTransferStats = new FluidDomainContracts.PhaseTransferStats(0, 0, 0, 0L);
	FluidDomainContracts.ThermalPhaseStats lastThermalPhaseStats = new FluidDomainContracts.ThermalPhaseStats(0, 0, 0, 0L);
	FluidDomainContracts.GasThermalPhaseStats lastGasThermalPhaseStats = new FluidDomainContracts.GasThermalPhaseStats(0, 0, 0);
	RegionChemistry.ReactionStats lastChemicalReactionStats =
			new RegionChemistry.ReactionStats(0, 0, 0);
	GranularDepositDynamics granularDepositDynamics;
	GranularDepositDynamics.Stats lastGranularDepositStats =
			new GranularDepositDynamics.Stats(0, 0, 0, 0, 0);
	double lastGranularBoundaryImpulseX, lastGranularBoundaryImpulseY, lastGranularBoundaryImpulseZ;
	double lastLiquidBoundaryImpulseX, lastLiquidBoundaryImpulseY, lastLiquidBoundaryImpulseZ;
	int[] granularReactivePreviousCells;
	GranularCouplingFields granularCoupling;
	GranularCouplingFields granularNextCoupling;
	FlipGranularCollision granularCollision;
	int[] liquidCarrierMaterialByCell;
	double[] liquidCarrierVolumeByCell;
	boolean gridDirty = true;
	boolean velocityGridDirty = true;
	ThermalDomainState thermalState;
	final ThermalCarrierContext thermalMarkers;
	double[] thermalBoundaryTemperatureK;
	final PendingPhaseMotionWork pendingPhaseMotion;
	final DomainMetrics metrics;
	final CellInventoryReader cellInventory;
	final CarrierInventoryReader carriers;
	final DomainFlowFields domainFlowFields = new DomainFlowFields(this);
	final DomainInventoryGeometry domainInventoryGeometry = new DomainInventoryGeometry(this);
	final DomainInventory domainInventory = new DomainInventory(this);
	final DomainCollision domainCollision = new DomainCollision(this);
	final DomainCapabilities domainCapabilities = new DomainCapabilities(this);
	final DomainPhaseInventory domainPhaseInventory = new DomainPhaseInventory(this);

	public HostFluidState(int sizeX, int sizeY, int sizeZ, FluidSolverConfig config,FluidDefinitions definitions) {
		this(SpatialGrid.rectangular(sizeX,sizeY,sizeZ),config,definitions,null);
	}
	HostFluidState(int sizeX, int sizeY, int sizeZ, FluidSolverConfig config,FluidDefinitions definitions, FixedGeometrySnapshot fixedGeometry) {
		this(SpatialGrid.rectangular(sizeX,sizeY,sizeZ),config,definitions,fixedGeometry);
	}
	public HostFluidState(SpatialGrid grid, FluidSolverConfig config,FluidDefinitions definitions) {
		this(grid,config,definitions,null);
	}
	HostFluidState(SpatialGrid grid, FluidSolverConfig config,FluidDefinitions definitions, FixedGeometrySnapshot fixedGeometry) {
		int sizeX=grid.sizeX(),sizeY=grid.sizeY(),sizeZ=grid.sizeZ();
		if (sizeX < 2 || sizeY < 2 || sizeZ < 2) throw new IllegalArgumentException("All dimensions must be at least two cells");
		this.grid=grid;
		this.definitions=java.util.Objects.requireNonNull(definitions);
		this.gasState.network=new AirNetwork(definitions.gas());
		this.sizeX = sizeX;
		this.sizeY = sizeY;
		this.sizeZ = sizeZ;
		this.cellCount = grid.cellCount();
		this.config = config;
		this.unitScale = SolverUnitScale.forCellSize(config.cellSize());
		HostFluidState domain = this;
		this.gasBoundary = new GasPressureBoundary(domain);
		this.liquidCells = new LiquidCellInventory(cellCount, null);

		this.geometry = new DomainGeometry(grid, fixedGeometry);
		this.carrierPropertiesWorkspace = new LiquidCarrierProperties(cellCount);
		this.liquidGrid = new CellLiquidGrid(cellCount);
		this.volume = new float[cellCount];
		this.mac = new MacVelocityGrid(grid);
		this.cellMacStage = new CellMacStage(domain);
		this.tension = new SurfaceTensionFields(this);
		this.pendingPhaseMotion = new PendingPhaseMotionWork(cellCount);
		this.thermalMarkers = domain.domainCapabilities.createThermalMarkerContext();
		this.metrics = new DomainMetrics(domain);
		this.cellInventory = new CellInventoryReader(domain);
		this.carriers = new CarrierInventoryReader(domain);
		this.transferPlanner = new MaterialTransferPlanner(domain);
		this.clearPlanner = new CondensedInventoryClearPlanner(domain);
		this.granularTransferPlanner = new GranularTransferPlanner(domain);
		this.lifecyclePlanner = new DomainInventoryLifecyclePlanner(domain);
		this.gasInitialization = new GasInitialization(domain);
		this.gasLifecyclePlanner = new GasLifecyclePlanner(domain);
		this.pressureSystem = new PressureSystem(domain);
		this.granularMechanicalStage = new GranularMechanicalStage(this);
						}

	// External observations and operations.
	public void bindMaterialDefinitions(MaterialRegistry definitions) { LiquidMaterialBinding.bind(this, this.definitions.material(definitions)); }
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
		gridDirty=velocityGridDirty=true;pressureSystem.invalidateGeometry();
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
			if (candidate.exactCellVolume(cell).signum()>0 && !(domainCollision.effectiveOpenCellVolume(cell)>0))
				throw new IllegalArgumentException("Liquid inventory has no open cell capacity at " + cell);
		}
		liquidCells = candidate;
		phaseOccupancy=null;
		markCellInventoryChanged();
	}
	public double liquidKineticEnergyJoules() { domainFlowFields.ensureVelocityGrid(); return domainInventoryGeometry.gridKineticEnergyJoules(); }
	public MacMomentumState exportMacMomentum() { return mac.captureMomentum(); }
	public void restoreMacMomentum(MacMomentumState state) {
		mac.restoreMomentum(state);
		velocityGridDirty = true;
	}
	public void rebuildDerivedFromLiquidCells() {
		gridDirty = velocityGridDirty = true;pressureSystem.invalidateGeometry();
		domainFlowFields.preparePhysicalState();
	}
	void markCellInventoryChanged() {
		carrierMutationRevision++;
		// Retain the source object until a prepared gas successor is committed.
		phaseOccupancyDirty = true;
		gridDirty = velocityGridDirty = true;pressureSystem.invalidateGeometry();
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
	public void synchronizeGasTopologyForPersistence() { gasInitialization.synchronizeForPersistence(); }
	static GranularCouplingFields.Position parcelPosition(GranularParcelStore.Parcel parcel) { return DomainInventoryGeometry.parcelPosition(parcel); }
	static float trilinearWeight(int ox, int oy, int oz, float fx, float fy, float fz) { return DomainInventoryGeometry.trilinearWeight(ox, oy, oz, fx, fy, fz); }
	public ActivityStats activityStats() { return activityGroups.stats(); }
	public void replaceActivityConnections(List<int[]> cellGroups) { activityGroups.replaceConnections(cellGroups); }
    /** Refreshes execution eligibility over the complete resident state. */
    public void prepareGroupAvailability(boolean[] worldCellAvailable) { activityGroups.prepareAvailability(worldCellAvailable); }
    public boolean worldCellAvailable(int cell) { return activityGroups.worldCellAvailable(cell); }
    public boolean groupAvailable(int cell) { return activityGroups.groupAvailable(cell); }
    public boolean hasAvailableGroups() { return activityGroups.hasAvailableGroups(); }
    public double[] thermalBoundaryTemperatures() { return thermalBoundaryTemperatureK==null?null:thermalBoundaryTemperatureK.clone(); }


	public EntityBodyState entityBodyState() { return entityBodies; }
	/** Restores accepted bodies before deriving capacity, pressure or gas geometry. */
	public void restoreEntityBodyState(EntityBodyState state) {
		entityBodies = java.util.Objects.requireNonNull(state, "Entity body state is missing");
		markCellInventoryChanged();
	}
	static void requireFiniteMarkerVelocity(double velocity) { DomainInventoryGeometry.requireFiniteMarkerVelocity(velocity); }
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
	public void setMarkerChemical(int ordinal, Map<String, Long> stableAmounts) { domainCollision.setMarkerChemical(ordinal, stableAmounts); }
	public int materialHandle(String materialId) { return domainCollision.materialHandle(materialId); }
	public void restoreParticleState(FlipParticleState state) { domainCollision.restoreParticleState(state); }
	public void restoreMaterialParticleState(FlipMaterialParticleState state) { domainCollision.restoreMaterialParticleState(state); }
	public void restoreChemicalParticleState(FlipChemicalParticleState state, ChemicalRegistry registry) { domainCollision.restoreChemicalParticleState(state, registry); }
	public FlipParticleState exportParticleState() { return domainCollision.exportParticleState(); }
	public FlipMaterialParticleState exportMaterialParticleState() { return domainCollision.exportMaterialParticleState(); }
	public float setSolid(int x, int y, int z, boolean value) { return domainCollision.setSolid(x, y, z, value); }
	public float setSolidMicroMask(int x, int y, int z, int mask) { return domainCollision.setSolidMicroMask(x, y, z, mask); }
	public FlipChemicalParticleState exportChemicalParticleState() { return domainCollision.exportChemicalParticleState(); }
	public void handoffCollisionBodies(List<CollisionEdit> edits, EntityBodyState bodies) { domainCollision.handoffCollisionBodies(edits, bodies); }
	public void replaceCollisionBodies(List<CollisionEdit> edits, EntityBodyState bodies) { domainCollision.replaceCollisionBodies(edits, bodies); }
	public CollisionEditPlan planCollisionEdits(List<CollisionEdit> edits) { return domainCollision.planCollisionEdits(edits); }
	public void beginCollisionEdits(CollisionEditPlan plan) { domainCollision.beginCollisionEdits(plan); }
	public float applyCollisionEdits(List<CollisionEdit> edits) { return domainCollision.applyCollisionEdits(edits); }
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
	/** Free normalized volume after fixed geometry, moving bodies, solids and liquid. */
	public double cellCondensedRoom(int x,int y,int z) {
		int cell=domainFlowFields.cellIndexChecked(x,y,z);
		domainFlowFields.preparePhysicalState();
		return Math.max(0,domainCollision.effectiveOpenCellVolume(cell)-liquidCells.exactCellVolume(cell).toDouble());
	}
	/** Physical gas space in a cell, independent of its current gas inventory. */
	public double cellGasVolume(int x,int y,int z) {
		int cell=domainFlowFields.cellIndexChecked(x,y,z);domainCapabilities.initializeSpatialGas();
		var state=gasState.spatial;
		return DomainGasInventory.mixture(this,cell,state.cells().get(cell),state.energyAt(cell)).gasVolumeM3();
	}
	public float pressure(int x, int y, int z) { return domainCollision.pressure(x, y, z); }
	public float totalVolume() { return domainCollision.totalVolume(); }
	public double totalVolumeExact() { return domainCollision.totalVolumeExact(); }
	public float totalOpenVolume() { return domainCollision.totalOpenVolume(); }
	/** Read-only fixed pore capacity, independent of entity and condensed-carrier occupation. */
	public double fixedOpenVolumeExact() { return geometry.cutCells.snapshot().totalOpenCellVolume(); }
	/** Configures or removes the screen on a shared face of the current grid. */
	public void setScreenFace(int face, double poreDiameterMeters, double openFraction) {
		geometry.cutCells.setScreen(face, new ScreenFace(face, poreDiameterMeters, openFraction));
	}
	public void removeScreenFace(int face) { geometry.cutCells.setScreen(face, null); }

	public double totalOpenVolumeExact() { return domainCollision.totalOpenVolumeExact(); }
	public long totalMassUnits() { return domainCollision.totalMassUnits(); }
	public double totalMass() { return domainCollision.totalMass(); }
	public int dominantMaterialInCell(int x, int y, int z) { return domainCollision.dominantMaterialInCell(x, y, z); }
	public double exactVolumeNear(int centerX, int centerY, int centerZ, int maxRadius, int materialHandle) { return domainCollision.exactVolumeNear(centerX, centerY, centerZ, maxRadius, materialHandle); }
	/** Nearby volume actually eligible for the corresponding container extraction. */
	public double containerExtractableVolumeNear(int x, int y, int z, int radius, int material) {
		return transferPlanner.extractableVolume(x, y, z, radius, material);
	}
	public void bindMaterialRegistry(FluidMaterialRegistry registry) {
		for (var material : registry.materials()) if (!definitions.material().entry(material.id()).material().equals(material))
			throw new IllegalArgumentException("Liquid material differs from world definition: " + material.id());
		domainCollision.bindMaterialRegistry(registry);
	}
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
	public RegionChemistry chemistry() { return domainCapabilities.chemistry(); }
	public ChemicalDisplaySnapshot copyChemicalDisplaySnapshot() { return domainCapabilities.copyChemicalDisplaySnapshot(); }
	public ChemicalTransferLedger chemicalLedgerForMarkerRange(int fromInclusive, int toExclusive) { return domainCapabilities.chemicalLedgerForMarkerRange(fromInclusive, toExclusive); }
	public void bindChemistry(ChemicalRegistry registry) { domainCapabilities.bindChemistry(definitions.chemistry(registry)); }
	public void bindThermal(
			ThermalRegistry registry, FluidMaterialRegistry liquidRegistry, double initialTemperatureK
	) { domainCapabilities.bindThermal(definitions.thermal(registry), liquidRegistry, initialTemperatureK); }
	public void bindThermal(
			ThermalRegistry registry, FluidMaterialRegistry liquidRegistry, double initialTemperatureK,
			int maximumTransportSubsteps
	) { domainCapabilities.bindThermal(definitions.thermal(registry), liquidRegistry, initialTemperatureK, maximumTransportSubsteps); }
	public void restoreThermalParticleState(
			FlipThermalParticleState state, ThermalRegistry registry
	) { domainCapabilities.restoreThermalParticleState(state, definitions.thermal(registry)); }
	public void restoreThermalParticleState(
			FlipThermalParticleState state, ThermalRegistry registry, int maximumTransportSubsteps
	) { domainCapabilities.restoreThermalParticleState(state, definitions.thermal(registry), maximumTransportSubsteps); }
	public void restoreThermalOwnerState(ThermalOwnerSnapshot state, ThermalRegistry registry, int maximumTransportSubsteps) { domainCapabilities.restoreThermalOwnerState(state, definitions.thermal(registry), maximumTransportSubsteps); }
	public void restoreThermalOwnerState(ThermalOwnerSnapshot state, ThermalRegistry registry) { domainCapabilities.restoreThermalOwnerState(state, definitions.thermal(registry)); }
	public ThermalOwnerSnapshot exportThermalOwnerState() { return domainCapabilities.exportThermalOwnerState(); }
	public FlipThermalParticleState exportThermalParticleState() { return domainCapabilities.exportThermalParticleState(); }
	public void setThermalTemperatureInCell(int x, int y, int z, double temperatureK) { domainCapabilities.setThermalTemperatureInCell(x, y, z, temperatureK); }
	public double thermalTemperatureInCell(int x, int y, int z) { return domainCapabilities.thermalTemperatureInCell(x, y, z); }
	public List<FiniteThermalExchange.Contact> finiteThermalContacts(List<ThermalContactRequest> requests) { return domainCapabilities.finiteThermalContacts(requests); }
	public List<FiniteThermalExchange.Contact> finiteThermalPortContacts(List<ThermalPortRequest> requests) {
		return CarrierThermalConduction.finitePorts(this, List.copyOf(requests));
	}
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
	public int sizeX() { return domainPhaseInventory.sizeX(); }
	public SpatialGrid grid() { return grid; }
	public FluidDefinitions definitions() { return definitions; }
	public int sizeY() { return domainPhaseInventory.sizeY(); }
	public int sizeZ() { return domainPhaseInventory.sizeZ(); }
	public int particleCount() { return domainPhaseInventory.particleCount(); }
	public FluidDomainContracts.CarrierViscosityStats carrierViscosityStats() { return domainPhaseInventory.carrierViscosityStats(); }
	public Double carrierViscosityPascalSeconds(int cell) { return domainPhaseInventory.carrierViscosityPascalSeconds(cell); }
	public GasPressureBoundary gasBoundary() { return domainPhaseInventory.gasBoundary(); }
	/** Read-only local-metre query on the committed PLIC; -1 means no editable gas at the point. */
	public int gasNodeAt(double x, double y, double z) { return domainPhaseInventory.gasNodeAt(x, y, z); }
	/** Null component selects the ambient insertion recipe or the existing extraction mixture. */
	public GasEdit editGas(int node, String componentId, long nanos, double temperatureK, boolean extract) {
		return domainPhaseInventory.editGas(node, componentId, nanos, temperatureK, extract);
	}
	public GasEdit insertGas(int node, mmm.fluid.air.GasInventory inventory, double internalEnergy) {
		return DomainGasInventory.insert(this, node, inventory, internalEnergy);
	}
	public void setGasNodeStandardVolume(int nodeId, double standardVolume) { domainPhaseInventory.setGasNodeStandardVolume(nodeId, standardVolume); }
	public void sealDomainBoundary() { domainPhaseInventory.sealDomainBoundary(); }
	public void bindGasRegistry(GasRegistry registry) { domainPhaseInventory.bindGasRegistry(definitions.gas(registry)); }
	public void resetAmbientGas() { DomainGasInventory.resetAmbient(this); }
	public void restoreGasState(GasStateSnapshot state, GasRegistry registry) { domainPhaseInventory.restoreGasState(state, definitions.gas(registry)); }
	public void restoreGasState(GasStateSnapshot state, GasRegistry registry, FlipThermalParticleState thermal) { domainPhaseInventory.restoreGasState(state, definitions.gas(registry), thermal); }
	public void restoreGasState(GasStateSnapshot state, GasRegistry registry, mmm.thermal.ThermalOwnerSnapshot thermal) { domainPhaseInventory.restoreGasState(state, definitions.gas(registry), thermal); }
	public boolean gasPressureActive() { return domainPhaseInventory.gasPressureActive(); }
	public double gasNodeTemperatureK(int nodeId) { return domainPhaseInventory.gasNodeTemperatureK(nodeId); }
	public double gasNodeGaugePressurePa(int nodeId) { return domainPhaseInventory.gasNodeGaugePressurePa(nodeId); }
	public int airNodeAt(int x, int y, int z) { return domainPhaseInventory.airNodeAt(x, y, z); }
	public byte[] copyGasNodeMask() { return domainPhaseInventory.copyGasNodeMask(); }
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
	public int chemicalStockCount() { return domainPhaseInventory.chemicalStockCount(); }
	public long totalChemicalSpeciesQuanta() { return domainPhaseInventory.totalChemicalSpeciesQuanta(); }
	public int granularDepositGroupCount() { return domainPhaseInventory.granularDepositGroupCount(); }
	public int coherentPhaseGroupCount() { return domainPhaseInventory.coherentPhaseGroupCount(); }
	public boolean hasPendingPhaseMotionWork() { return domainPhaseInventory.hasPendingPhaseMotionWork(); }
	public double densityInCell(int x, int y, int z) { return domainPhaseInventory.densityInCell(x, y, z); }
    public FluidComposition observeComposition() { return FluidCompositionProjector.observe(this); }
    public FluidComposition observeComposition(java.util.function.IntPredicate cellFilter) {
        return FluidCompositionProjector.observe(this, java.util.Objects.requireNonNull(cellFilter));
    }

	public FluidCellInventoryView observeCellInventory(int x, int y, int z) { return domainPhaseInventory.observeCellInventory(x, y, z); }
	public CellCarrierInventoryView observeCellCarriers(int x, int y, int z) { return domainPhaseInventory.observeCellCarriers(x, y, z); }
	public boolean hasCondensedInventoryInCell(int x, int y, int z) { return domainPhaseInventory.hasCondensedInventoryInCell(x, y, z); }
	public PendingPhaseMotionState exportPendingPhaseMotionState() { return domainPhaseInventory.exportPendingPhaseMotionState(); }
	public FluidDomainState captureAuthoritativeState() { return domainPhaseInventory.captureAuthoritativeState(); }
	public FluidDomainState.Layout authoritativeLayout() { return domainPhaseInventory.authoritativeLayout(); }
	public static HostFluidState restoreAuthoritativeState(FluidDomainState state) { return DomainPhaseInventory.restoreAuthoritativeState(state); }
	public void restorePendingPhaseMotionState(PendingPhaseMotionState state) { domainPhaseInventory.restorePendingPhaseMotionState(state); }
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
	public RegionChemistry.ReactionStats lastChemicalReactionStats() { return domainPhaseInventory.lastChemicalReactionStats(); }
	public void setPrecipitationEnabled(boolean enabled) { domainPhaseInventory.setPrecipitationEnabled(enabled); }
	public void bindGranular(GranularRegistry registry, FluidMaterialRegistry liquidRegistry,
			MaterialRegistry materialDefinitions) { domainPhaseInventory.bindGranular(definitions.granular(registry), liquidRegistry, definitions.material(materialDefinitions)); }
	public void restoreGranularState(
			GranularStateSnapshot snapshot, GranularRegistry registry, FluidMaterialRegistry liquidRegistry,
			MaterialRegistry materialDefinitions
	) { domainPhaseInventory.restoreGranularState(snapshot, definitions.granular(registry), liquidRegistry, definitions.material(materialDefinitions)); }
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
	public GranularInsertion tryInsertExactGranular(
			int cellX, int cellY, int cellZ, GranularTransferLedger ledger, int maxRadius,
			boolean externalSource, double carriedEnergy
	) {
		return domainPhaseInventory.tryInsertExactGranular(cellX, cellY, cellZ, ledger, maxRadius, externalSource, carriedEnergy);
	}

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

	/** Read-only physical water contacts; no liquid, thermal or audit inventory is changed. */
	public List<WaterContact> waterContacts(List<WaterContactFace> faces) {
		return WaterContactGeometry.capture(this, faces);
	}

	/** Full-dose contact-only extraction on the current domain, suitable for a private world-edit candidate. */
	public boolean returnNativeWaterCells(List<Integer> cells) { return transferPlanner.returnNativeWaterCells(List.copyOf(cells)); }

	public ContactWaterTransfer extractContactWater(List<WaterContactFace> faces, long requestedNanos) {
		return transferPlanner.extractContactWater(faces, requestedNanos);
	}

}

package mmm.fluid;

import mmm.chemistry.RegionChemistry;
import mmm.granular.GranularCouplingFields;
import mmm.granular.GranularDepositDynamics;
import mmm.granular.GranularPhaseTransferEngine;
import mmm.units.SolverUnitScale;
import mmm.units.SignedDyadic;
import mmm.fluid.air.AirNetwork;
import mmm.granular.GranularInventory;
import mmm.granular.SolidVolume;
import mmm.thermal.ThermalTransport;
import mmm.granular.GranularAuditLedger;
import mmm.thermal.ThermalOwnerSnapshot;
import mmm.granular.GranularLimits;
import mmm.granular.SolidCompositionPool;
import mmm.fluid.air.GasRegistry;
import mmm.fluid.air.GasStateSnapshot;
import mmm.granular.GranularDisplaySnapshot;
import mmm.granular.GranularRegistry;
import mmm.granular.GranularStateSnapshot;
import mmm.granular.GranularTransferLedger;
import mmm.thermal.ThermalAuditLedger;
import mmm.thermal.ThermalCellGrid;
import static mmm.fluid.FluidDomain.*;

/** Manages gas and granular inventories, common carrier properties and complete state handoff. */
final class DomainPhaseInventory {
	private record PreparedGranularBinding(GranularDomainState state, GranularDepositDynamics deposits,
			GranularCouplingFields coupling, GranularCouplingFields nextCoupling, FlipGranularCollision collision,
			GranularPhaseTransferEngine phaseTransfer, int[] previousCells) { }
	private final FluidDomain domain;
	DomainPhaseInventory(FluidDomain domain) { this.domain = domain; }
	public int sizeX() { return domain.sizeX; }
	public int sizeY() { return domain.sizeY; }
	public int sizeZ() { return domain.sizeZ; }
	public int particleCount() { return domain.liquidCells.physicalSize(); }
	LiquidCarrierProperties derivedCarrierState() {
		domain.domainFlowFields.ensureInventoryGrid();
		domain.carrierPropertiesWorkspace.refresh(domain, domain.domainCapabilities.thermalMarkerContext(), domain.materialRegistry, domain.materialDefinitions,
				domain.granularState, domain.thermalState, domain.carrierMutationRevision);
		return domain.carrierPropertiesWorkspace;
	}
	LiquidCarrierProperties thermalBuoyancyProperties() {
		LiquidCarrierProperties properties = derivedCarrierState();
		properties.prepareBuoyancy(domain.domainCapabilities.thermalMarkerContext(), domain.materialRegistry, domain.materialDefinitions, domain.thermalState);
		return properties;
	}
	public FluidDomain.CarrierViscosityStats carrierViscosityStats() { return domain.carrierPropertiesWorkspace.stats(); }
	public Double carrierViscosityPascalSeconds(int cell) {
		LiquidCarrierProperties properties = derivedCarrierState();
		return properties.hasLiquid(cell) ? properties.viscosityPascalSeconds(cell) : null;
	}
	public GasPressureBoundary gasBoundary() { return domain.gasBoundary; }
	public int gasNodeAt(double x, double y, double z) { return DomainGasInventory.nodeAt(domain, x, y, z); }
	public FluidDomain.GasEdit editGas(int node, String componentId, long nanos, double temperatureK, boolean extract) {
		return DomainGasInventory.edit(domain, node, componentId, nanos, temperatureK, extract);
	}
	public void setGasNodeStandardVolume(int nodeId, double standardVolume) {
		domain.gasState.network.setStandardVolume(nodeId, standardVolume);
	}
	public void sealDomainBoundary() {
		if (domain.gasState.sealedDomainBoundary) return;
		if (domain.gasState.frame != null || domain.gasState.network.nodeCount() != 0 || domain.gasState.network.drawnFromAmbientNanos() != 0L) {
			throw new IllegalStateException("Gas domain boundary cannot change after topology initialization");
		}
		domain.gasState.sealedDomainBoundary = true;
	}
	public void bindGasRegistry(GasRegistry registry) {
		if (registry == null) throw new IllegalArgumentException("Gas registry is missing");
		if (domain.gasState.frame != null || domain.gasState.network.nodeCount() != 0 || domain.gasState.network.drawnFromAmbientNanos() != 0L) {
			throw new IllegalStateException("Gas registry cannot change after topology initialization");
		}
		domain.gasState.network = new AirNetwork(registry);
	}
	public GasStateSnapshot bootstrapGasState(String chemicalRegistryFingerprint) {
		return domain.gasTopologyCoordinator.bootstrap(chemicalRegistryFingerprint);
	}
	public GasStateSnapshot exportGasState(String chemicalRegistryFingerprint) {
		return domain.gasTopologyCoordinator.export(chemicalRegistryFingerprint);
	}
	public void restoreGasState(GasStateSnapshot state, GasRegistry registry) {
		domain.gasTopologyCoordinator.restore(state, registry);
	}
	public void restoreGasState(GasStateSnapshot state, GasRegistry registry, FlipThermalParticleState thermal) {
		domain.gasTopologyCoordinator.restore(state, registry, thermal);
	}
	public void restoreGasState(GasStateSnapshot state, GasRegistry registry, mmm.thermal.ThermalOwnerSnapshot thermal) {
		domain.gasTopologyCoordinator.restore(state, registry, thermal);
	}
	public boolean gasPressureActive() { return domain.gasState.pressureActive; }
	public double gasNodeTemperatureK(int nodeId) {
		return domain.thermalState == null || !domain.thermalState.gasModelBound()
				? GasRegistry.STANDARD_TEMPERATURE_K : domain.thermalState.gasTemperature(nodeId, domain.gasState.network);
	}
	public double gasNodeGaugePressurePa(int nodeId) {
		return domain.gasBoundary.nodeGaugePressurePa(nodeId);
	}
	public int airNodeAt(int x, int y, int z) {
		return domain.gasBoundary.cellNode(domain.domainFlowFields.cellIndexChecked(x, y, z));
	}
	public byte[] copyGasNodeMask() {
		byte[] mask = new byte[domain.cellCount];
		if (domain.gasState.frame == null) return mask;
		for (int cell = 0; cell < domain.cellCount; cell++) {
			int node = domain.gasState.frame.commonCellNode(cell);
			mask[cell] = node >= 1 ? (byte) Math.min(255, node) : 0;
		}
		return mask;
	}
	public int markerCellCount() { return domain.metrics.markerCellCount(); }
	public FluidSolverConfig config() { return domain.config; }
	public SolverUnitScale unitScale() { return domain.unitScale; }
	public double physicalVolumeCubicMetres(double normalizedVolume) { return domain.unitScale.cubicMeters(normalizedVolume); }
	public boolean hasMaterialState() { return domain.materialRegistry != null; }
	public boolean hasChemistryState() { return domain.chemistry != null; }
	public boolean hasGranularState() { return domain.granularState != null; }
	public boolean hasThermalState() { return domain.thermalState != null; }
	public boolean hasAmbientGasCells() { return domain.metrics.hasAmbientGasCells(); }
	public ThermalCellGrid.TemperatureRange observedCarrierTemperatureRange() {
		return domain.thermalState == null ? null : domain.thermalState.observedCarrierTemperatureRange();
	}
	public int chemicalStockCount() { if(domain.chemistry==null)return 0;int count=0;for(var stock:domain.liquidCells.stocks())if(stock.mixture()!=null)count++;return count; }
	public long totalChemicalSpeciesQuanta() {
		long total=0;for(var stock:domain.liquidCells.stocks()) if(stock.mixture()!=null) total=Math.addExact(total,stock.mixture().totalQuanta());return total;
	}
	public int granularDepositGroupCount() { return domain.granularState == null ? 0 : domain.granularState.deposits.groupCount(); }
	public int coherentPhaseGroupCount() {
		return domain.thermalState == null ? 0 : domain.thermalState.coherentPhases().groupCount();
	}
	public boolean hasPendingPhaseMotionWork() { return domain.pendingPhaseMotion.hasWork(); }
	public double densityInCell(int x, int y, int z) { return domain.metrics.densityInCell(x, y, z); }
	public FluidCellInventoryView observeCellInventory(int x, int y, int z) { return domain.cellInventory.observeCellInventory(x, y, z); }
	public CellCarrierInventoryView observeCellCarriers(int x, int y, int z) { return domain.cellInventory.observeCellCarriers(x, y, z); }
	public boolean hasCondensedInventoryInCell(int x, int y, int z) { return domain.cellInventory.hasCondensedInventoryInCell(x, y, z); }
	public PendingPhaseMotionState exportPendingPhaseMotionState() {
		if (domain.thermalState == null) throw new IllegalStateException("Pending phase volume requires thermal state");
		return domain.pendingPhaseMotion.capture();
	}
	public FluidDomainState captureAuthoritativeState() {
		return FluidDomainStateMapper.capture((FluidDomain) domain);
	}
	public FluidDomainState.Layout authoritativeLayout() {
		return FluidDomainStateMapper.layout((FluidDomain) domain);
	}
	public static FluidDomain restoreAuthoritativeState(FluidDomainState state) {
		return FluidDomainStateMapper.restore(state);
	}
	boolean collisionTransactionActive() { return domain.activeCollisionPlan != null; }
	public void restorePendingPhaseMotionState(PendingPhaseMotionState state) {
		domain.pendingPhaseMotion.restore(state);
	}
	public ThermalTransport.Stats lastThermalTransportStats() {
		return domain.thermalState == null
				? new ThermalTransport.Stats(0, 0, 0.0, SignedDyadic.ZERO, SignedDyadic.ZERO)
				: domain.thermalState.lastTransport();
	}
	public ThermalAuditLedger thermalAudit() {
		if (domain.thermalState == null) throw new IllegalStateException("Thermal state is not bound");
		return domain.thermalState.auditLedger();
	}
	public void settleExternalContainerExtraction(double removedCarrierEnergy, double coupledItemEnergy) {
		if (domain.thermalState == null) throw new IllegalStateException("Thermal state is not bound");
		domain.thermalState.settleExternalExtraction(removedCarrierEnergy, coupledItemEnergy);
	}
	public void settleExternalContainerInsertion(double insertedCarrierEnergy, double coupledItemEnergy) {
		if (domain.thermalState == null) throw new IllegalStateException("Thermal state is not bound");
		domain.thermalState.settleExternalInsertion(insertedCarrierEnergy, coupledItemEnergy);
	}
	public double totalInternalEnergy() {
		return domain.thermalState == null ? 0.0 : domain.thermalState.totalEnergy(domain.liquidCells.physicalSize());
	}
	public SignedDyadic exactInternalEnergy() {
		return domain.thermalState == null ? SignedDyadic.ZERO : domain.thermalState.exactEnergy(domain.liquidCells.physicalSize());
	}
	public SignedDyadic exactGasInternalEnergy() {
		return domain.thermalState == null ? SignedDyadic.ZERO : domain.thermalState.exactGasEnergy();
	}
	public int granularParcelCount() { return domain.granularState == null ? 0 : domain.granularState.parcels.count(); }
	public int granularDepositCellCount() { return domain.granularState == null ? 0 : domain.granularState.deposits.occupiedCellCount(); }
	public int granularSleepingColumnCount() {
		return domain.granularDepositDynamics == null ? 0 : domain.granularDepositDynamics.sleepingColumnCount();
	}
	public boolean granularReadyForHydrostaticSleep() {
		if (domain.granularState == null) return true;
		if (domain.granularState.parcels.count() != 0 || domain.granularDepositDynamics.dirtyColumnCount() != 0) return false;
		return !domain.precipitationEnabled || domain.chemistry == null || !domain.chemistry.hasDirtyCells();
	}
	public GranularDepositDynamics.Stats lastGranularDepositStats() { return domain.lastGranularDepositStats; }
	public double lastGranularBoundaryImpulseX() { return domain.lastGranularBoundaryImpulseX; }
	public double lastGranularBoundaryImpulseY() { return domain.lastGranularBoundaryImpulseY; }
	public double lastGranularBoundaryImpulseZ() { return domain.lastGranularBoundaryImpulseZ; }
	public int lastGranularCapacityHeld() {
		return domain.granularCollision == null ? 0 : domain.granularCollision.lastCapacityHeld();
	}
	public GranularStateSnapshot exportGranularState() { return domain.carriers.exportGranularState(); }
	public GranularDisplaySnapshot copyGranularDisplaySnapshot() { return domain.carriers.copyGranularDisplaySnapshot(); }
	public GranularAuditLedger granularExternalSource() { return domain.granularState.externalSource; }
	public GranularAuditLedger granularEscapedSink() { return domain.granularState.escapedSink; }
	public long totalGranularSolidVolumeSvq() { return domain.granularState == null ? 0L : domain.granularState.inventory.totalSolidVolumeSvq(); }
	public double totalGranularMass() { return domain.granularState == null ? 0.0 : domain.granularState.inventory.totalMass(); }
	boolean hasGranularInventory() {
		return domain.granularState != null && (domain.granularState.parcels.count() != 0 || domain.granularState.deposits.groupCount() != 0);
	}
	public GranularClear removeAllGranularExact() {
		return commitRemoveAllGranularExact(planRemoveAllGranularExact());
	}
	public GranularClearPlan planRemoveAllGranularExact() {
		return domain.lifecyclePlanner.planGranularClear();
	}
	public GranularClear commitRemoveAllGranularExact(GranularClearPlan plan) {
		preflightGranularClear(plan);
		return applyGranularClear(plan);
	}
	GranularClear applyGranularClear(GranularClearPlan plan) {
		if (domain.granularState == null) return new GranularClear(0L, 0L, 0, 0);
		if (!hasGranularInventory()) {
			domain.granularDepositDynamics.clear(); domain.granularCoupling.clear(); domain.granularNextCoupling.clear();

			return new GranularClear(0L, 0L, 0, 0);
		}
		GranularInventory.ClearStats cleared = domain.granularState.inventory.clear();
		domain.granularState.escapedSink = plan.sinkAfter(); domain.granularDepositDynamics.clear();
		domain.granularCoupling.clear(); domain.granularNextCoupling.clear();
		domain.lastGranularDepositStats = new GranularDepositDynamics.Stats(0, 0, 0, 0, 0);
		domain.markCellInventoryChanged();
		return new GranularClear(cleared.solidVolumeSvq(), cleared.massUnits(),
				cleared.activeParcels(), cleared.depositGroups());
	}
	void preflightGranularClear(GranularClearPlan plan) {
		if (plan == null) throw new IllegalArgumentException("Granular clear plan is missing");
		if ((domain.granularState == null ? 0L : domain.granularState.mutationRevision()) != plan.granularRevision()) {
			throw new IllegalStateException("Granular inventory changed after clear planning");
		}
		if (domain.granularState == null) {
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
		domain.granularState.inventory.requireCanClear();
		if (domain.granularState.inventory.totalSolidVolumeSvq() != plan.solidVolumeSvq()
				|| domain.granularState.inventory.totalMassUnits() != plan.totalMassUnits()
				|| !(domain.thermalState == null ? SignedDyadic.ZERO : domain.thermalState.granularColumn().exactEnergy())
				.equals(plan.internalEnergy())
				|| domain.granularState.parcels.count() != plan.activeParcels()
				|| domain.granularState.deposits.groupCount() != plan.depositGroups()) {
			throw new IllegalStateException("Granular inventory changed after clear planning");
		}
		long auditSvq = 0L; long auditMass = 0L;
		for (GranularAuditLedger.Entry entry : plan.removed().entries()) {
			auditSvq = Math.addExact(auditSvq, entry.solidVolumeSvq()); auditMass = Math.addExact(auditMass, entry.massUnits());
		}
		if (auditSvq != plan.solidVolumeSvq() || auditMass != plan.totalMassUnits()) {
			throw new IllegalStateException("Granular clear audit differs from removed inventory");
		}
	}
	public long granularSolidVolumeSvq(int cellX, int cellY, int cellZ) { return domain.cellInventory.granularSolidVolumeSvq(cellX, cellY, cellZ); }
	public SolidCompositionPool solidCompositions() {
		if (domain.granularState == null || domain.granularState.compositions == null) {
			throw new IllegalStateException("Solid composition state is not bound");
		}
		return domain.granularState.compositions;
	}
	public boolean precipitationEnabled() { return domain.precipitationEnabled; }
	GranularPhaseTransferEngine granularPhaseTransfer() { return domain.granularPhaseTransfer; }
	public PhaseTransferStats lastPhaseTransferStats() { return domain.lastPhaseTransferStats; }
	public ThermalPhaseStats lastThermalPhaseStats() { return domain.lastThermalPhaseStats; }
	public GasThermalPhaseStats lastGasThermalPhaseStats() { return domain.lastGasThermalPhaseStats; }
	public RegionChemistry.ReactionStats lastChemicalReactionStats() { return domain.lastChemicalReactionStats; }
	public void setPrecipitationEnabled(boolean enabled) {
		if (enabled && (domain.chemistry == null || domain.granularState == null || domain.granularState.compositions == null
				|| !domain.granularState.registry.hasReactiveRules())) {
			throw new IllegalStateException("Precipitation requires bound chemistry, granular state and reactive rules");
		}
		domain.precipitationEnabled = enabled;
	}
	public void bindGranular(GranularRegistry registry, FluidMaterialRegistry liquidRegistry,
			MaterialRegistry materialDefinitions) {
		liquidRegistry = validateGranularBinding(registry, liquidRegistry, materialDefinitions);
		if (domain.granularState != null) return;
		installGranularBinding(prepareGranularBinding(registry), liquidRegistry, materialDefinitions);
	}
	private FluidMaterialRegistry validateGranularBinding(GranularRegistry registry,
			FluidMaterialRegistry liquidRegistry, MaterialRegistry materialDefinitions) {
		if (registry == null || liquidRegistry == null || materialDefinitions == null) {
			throw new IllegalArgumentException("Granular/liquid/material definition registry is missing");
		}
		if (domain.thermalState != null) {
			throw new IllegalStateException("Granular state must be bound before its joint thermal inventory");
		}
		if (Float.floatToRawIntBits(domain.config.cellSize()) != Float.floatToRawIntBits(0.5F)) {
			throw new IllegalArgumentException("Granular SVQ requires a 0.5 m solver cell");
		}
		// Every check runs before the domain is touched, so a rejected binding leaves no partial state.
		if (domain.materialRegistry != null) {
			if (!domain.materialRegistry.materials().equals(liquidRegistry.materials())) {
				throw new IllegalArgumentException("A domain cannot change its granular liquid palette");
			}
			liquidRegistry = domain.materialRegistry;
		}
		LiquidMaterialBinding.validate(domain, liquidRegistry, materialDefinitions);
		if (domain.granularState != null) {
			if (!domain.granularState.registry.fingerprint().equals(registry.fingerprint())) {
				throw new IllegalArgumentException("A domain cannot change its granular registry");
			}
			return liquidRegistry;
		}
		for (FluidMaterial material : liquidRegistry.materials()) {
			if (!registry.hasCarrier(material.id())) {
				throw new IllegalArgumentException("No granular carrier definition for liquid " + material.id());
			}
		}
		if (domain.chemistry != null && registry.hasReactiveRules()
				&& !registry.chemicalRegistryFingerprint().equals(domain.chemistry.registry().fingerprint())) {
			throw new IllegalArgumentException("Granular registry chemical dependency mismatch");
		}
		return liquidRegistry;
	}
	private PreparedGranularBinding prepareGranularBinding(GranularRegistry registry) {
		var state = new GranularDomainState(registry, domain.sizeX, domain.sizeY, domain.sizeZ, domain.config.cellSize());
		GranularPhaseTransferEngine phaseTransfer = null;
		if (domain.chemistry != null && registry.hasReactiveRules()) {
			int depositCapacity = Math.min(GranularLimits.MAX_DEPOSIT_GROUPS_PER_REGION,
					Math.multiplyExact(domain.cellCount, GranularLimits.MAX_DEPOSIT_GROUPS_PER_CELL));
			state.bindCompositions(new SolidCompositionPool(domain.chemistry.registry(),
					Math.addExact(GranularLimits.MAX_ACTIVE_PARCELS_PER_REGION, depositCapacity)));
			phaseTransfer = new GranularPhaseTransferEngine(domain.chemistry.registry(), domain.unitScale);
		}
		return new PreparedGranularBinding(state,
				new GranularDepositDynamics(registry, state.parcels, state.deposits, state.inventory,
						domain.sizeX, domain.sizeY, domain.sizeZ, domain.config.cellSize()),
				new GranularCouplingFields(domain.sizeX, domain.sizeY, domain.sizeZ, domain.config.cellSize()),
				new GranularCouplingFields(domain.sizeX, domain.sizeY, domain.sizeZ, domain.config.cellSize()),
				new FlipGranularCollision(domain.sizeX, domain.sizeY, domain.sizeZ, domain.config.cellSize(), domain.geometry.cutCells, state.deposits,
						GranularLimits.MAX_ACTIVE_PARCELS_PER_REGION),
				phaseTransfer, new int[GranularLimits.MAX_ACTIVE_PARCELS_PER_REGION]);
	}
	private void installGranularBinding(PreparedGranularBinding prepared, FluidMaterialRegistry liquidRegistry,
			MaterialRegistry materialDefinitions) {
		domain.bindMaterialRegistry(liquidRegistry);
		LiquidMaterialBinding.bind(domain, materialDefinitions);
		domain.granularState = prepared.state();
		domain.granularDepositDynamics = prepared.deposits();
		domain.granularCoupling = prepared.coupling();
		domain.granularNextCoupling = prepared.nextCoupling();
		domain.granularCollision = prepared.collision();
		domain.granularPhaseTransfer = prepared.phaseTransfer();
		domain.granularReactivePreviousCells = prepared.previousCells();
		domain.markCellInventoryChanged();
	}
	public void restoreGranularState(
			GranularStateSnapshot snapshot, GranularRegistry registry, FluidMaterialRegistry liquidRegistry,
			MaterialRegistry materialDefinitions
	) {
		if (snapshot == null) throw new IllegalArgumentException("Granular snapshot is missing");
		if (snapshot.sizeX() != domain.sizeX || snapshot.sizeY() != domain.sizeY || snapshot.sizeZ() != domain.sizeZ) {
			throw new IllegalArgumentException("Granular snapshot dimensions changed");
		}
		if (domain.granularState != null) throw new IllegalStateException("Granular state is already bound");
		liquidRegistry = validateGranularBinding(registry, liquidRegistry, materialDefinitions);
		var prepared = prepareGranularBinding(registry);
		var state = prepared.state();
		snapshot.restoreInto(registry, state.parcels, state.deposits, state.compositions,
				cell -> SolidVolume.fromCellVolumeExact(domain.geometry.cutCells.openCellVolume(cell)));
		var collisionGeometry = new GranularCollisionGeometry(domain.sizeX, domain.sizeY, domain.sizeZ, domain.config.cellSize(), domain.geometry.cutCells, state.deposits);
		state.deposits.forEachCell((cell, ignoredVolume, ignoredPorosity) -> {
			if (!collisionGeometry.bedGeometryFits(cell, state.deposits.cellBulkVolumeSvq(cell))) {
				throw new IllegalArgumentException("Granular snapshot bed intersects fixed geometry at cell " + cell);
			}
		});
		for (int index = 0; index < state.parcels.count(); index++) {
			var parcel = state.parcels.parcel(index);
			if (!prepared.collision().positionValid(parcel.x(), parcel.y(), parcel.z(), parcel.solidVolumeSvq())) {
				throw new IllegalArgumentException("Granular snapshot sphere has invalid geometry at parcel " + index);
			}
		}
		prepared.coupling().rebuild(state.parcels, state.deposits);
		var liquid = new SignedDyadic[domain.cellCount];
		for (int cell = 0; cell < domain.cellCount; cell++) liquid[cell] = domain.liquidCells.exactCellVolume(cell);
		for (int cell = 0; cell < domain.cellCount; cell++) {
			if (liquid[cell].add(SignedDyadic.of(prepared.coupling().solidSvq(cell), -24))
					.compareTo(SignedDyadic.of(domain.geometry.cutCells.openCellVolume(cell))) > 0) {
				throw new IllegalArgumentException("Granular snapshot exceeds joint phase capacity at cell " + cell);
			}
		}
		state.externalSource = snapshot.externalSource(); state.escapedSink = snapshot.escapedSink();
		state.deposits.forEachCell((cell, ignoredVolume, ignoredPorosity) -> prepared.deposits().disturbCell(cell));
		installGranularBinding(prepared, liquidRegistry, materialDefinitions);
	}
	public GranularInsertion tryInsertExactGranular(
			int cellX, int cellY, int cellZ, int materialHandle, int compositionHandle,
			long solidVolumeSvq, long massUnits
	) {
		return domain.granularTransferPlanner.insertExactInventory(cellX, cellY, cellZ, materialHandle,
				compositionHandle, solidVolumeSvq, massUnits);
	}
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
		return domain.granularTransferPlanner.insertExact(cellX, cellY, cellZ, ledger, maxRadius, externalSource);
	}
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
		return domain.granularTransferPlanner.extractExact(cellX, cellY, cellZ, maximumSolidVolumeSvq, maxRadius,
				escapedSink, itemAcceptance);
	}
	void markGranularExternalChange(int cell) {
		domain.granularDepositDynamics.disturbCell(cell);
		if (domain.precipitationEnabled) domain.chemistry.markDirtyCell(cell);
		domain.markCellInventoryChanged();
	}
}

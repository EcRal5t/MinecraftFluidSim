package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.chemistry.ChemicalParcelStore;
import cc.ecisr.mmm.fluid.air.AirNetwork;
import cc.ecisr.mmm.fluid.air.GasRegistry;
import cc.ecisr.mmm.fluid.air.GasStateSnapshot;
import cc.ecisr.mmm.granular.GranularAuditLedger;
import cc.ecisr.mmm.granular.GranularCouplingFields;
import cc.ecisr.mmm.granular.GranularDepositDynamics;
import cc.ecisr.mmm.granular.GranularDisplaySnapshot;
import cc.ecisr.mmm.granular.GranularInventory;
import cc.ecisr.mmm.granular.GranularLimits;
import cc.ecisr.mmm.granular.GranularPhaseTransferEngine;
import cc.ecisr.mmm.granular.GranularRegistry;
import cc.ecisr.mmm.granular.GranularStateSnapshot;
import cc.ecisr.mmm.granular.GranularTransferLedger;
import cc.ecisr.mmm.granular.SolidCompositionPool;
import cc.ecisr.mmm.granular.SolidVolume;
import cc.ecisr.mmm.units.SignedDyadic;
import cc.ecisr.mmm.thermal.ThermalAuditLedger;
import cc.ecisr.mmm.thermal.ThermalCellGrid;
import cc.ecisr.mmm.thermal.ThermalTransport;

/** Exposes gas, granular and committed-state lifecycle operations. */
abstract class FluidDomainGasAndGranular extends FluidDomainCapabilities {
	FluidDomainGasAndGranular(int sizeX, int sizeY, int sizeZ, FluidSolverConfig config, int maximumParticleCount) {
		super(sizeX, sizeY, sizeZ, config, maximumParticleCount);
	}

	public int sizeX() { return sizeX; }
	public int sizeY() { return sizeY; }
	public int sizeZ() { return sizeZ; }
	public int particleCount() { return particles.count(); }

	/**
	 * Current spatial carrier properties. Grid rebuilds and carrier/thermal edits invalidate the batch.
	 */
	LiquidCarrierProperties derivedCarrierState() {
		ensureInventoryGrid();
		carrierPropertiesWorkspace.refresh(this, thermalMarkerContext(), materialRegistry, materialDefinitions,
				granularState, thermalState,
				new LiquidCarrierProperties.Revision(carrierMutationRevision,
						thermalState == null ? null : thermalState.thermalRevision()));
		return carrierPropertiesWorkspace;
	}

	LiquidCarrierProperties thermalBuoyancyProperties() {
		LiquidCarrierProperties properties = derivedCarrierState();
		properties.prepareBuoyancy(thermalMarkerContext(), materialRegistry, materialDefinitions, thermalState);
		return properties;
	}

	/** Viscosity extrapolation summary from the last successful mechanical step. */
	public FluidDomain.CarrierViscosityStats carrierViscosityStats() { return carrierPropertiesWorkspace.stats(); }

	/** Derived liquid carrier dynamic viscosity in one cell, in Pa路s; null when the cell holds no liquid. */
	public Double carrierViscosityPaPerS(int cell) {
		LiquidCarrierProperties properties = derivedCarrierState();
		return properties.hasLiquid(cell) ? properties.viscosityPaPerS(cell) : null;
	}

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
		if (gasState.sealedDomainBoundary) return;
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

	/** Uses incoming coherent inventory for gas geometry before restoring gas-dependent thermal energy. */
	public void restoreGasState(GasStateSnapshot state, GasRegistry registry, FlipThermalParticleState thermal) {
		gasTopologyCoordinator.restore(state, registry, thermal);
	}

	/** True when the current grid contains at least one sealed gas cavity, including explicit vacuum. */
	public boolean gasPressureActive() { return gasState.pressureActive; }
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
		return FluidDomainStateMapper.capture((FluidDomain) this);
	}

	/** Cheap immutable identity used to reject backend feature/registry substitution before adoption. */
	public FluidDomainState.Layout authoritativeLayout() {
		return FluidDomainStateMapper.layout((FluidDomain) this);
	}

	/** Restores a private solver instance without consulting Minecraft/Fabric state. */
	public static FluidDomain restoreAuthoritativeState(FluidDomainState state) {
		return FluidDomainStateMapper.restore(state);
	}

	boolean collisionTransactionActive() { return activeCollisionPlan != null; }
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
		return thermalState.auditLedger();
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
	GranularClear applyGranularClear(GranularClearPlan plan) {
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
	void preflightGranularClear(GranularClearPlan plan) {
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
				|| !(thermalState == null ? SignedDyadic.ZERO : thermalState.granularColumn().exactEnergy())
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
	GranularPhaseTransferEngine granularPhaseTransfer() { return granularPhaseTransfer; }
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

	/**
	 * Binds the runtime granular registry without changing liquid or chemical carrier truth.
	 *
	 * <p>{@code materialDefinitions} carries the world's temperature-dependent material properties.
	 * Granular drag evaluates dynamic viscosity per cell from the liquid temperature, so every bound
	 * liquid must declare a viscosity domain; the check happens here rather than inside the step.
	 */
	public void bindGranular(GranularRegistry registry, FluidMaterialRegistry liquidRegistry,
			MaterialRegistry materialDefinitions) {
		if (registry == null || liquidRegistry == null || materialDefinitions == null) {
			throw new IllegalArgumentException("Granular/liquid/material definition registry is missing");
		}
		if (thermalState != null) {
			throw new IllegalStateException("Granular state must be bound before its joint thermal inventory");
		}
		if (Float.floatToRawIntBits(config.cellSize()) != Float.floatToRawIntBits(0.5F)) {
			throw new IllegalArgumentException("Granular SVQ requires a 0.5 m solver cell");
		}
		// Every check runs before the domain is touched, so a rejected binding leaves no partial state.
		if (materialRegistry != null) {
			if (!materialRegistry.materials().equals(liquidRegistry.materials())) {
				throw new IllegalArgumentException("A domain cannot change its granular liquid palette");
			}
			liquidRegistry = materialRegistry;
		}
		LiquidMaterialBinding.validate(domain(), liquidRegistry, materialDefinitions);
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
		bindMaterialRegistry(liquidRegistry);
		granularState = new GranularDomainState(registry, cellCount);
		granularDepositDynamics = new GranularDepositDynamics(registry, granularState.parcels, granularState.deposits,
				granularState.inventory, sizeX, sizeY, sizeZ, config.cellSize());
		granularCoupling = new GranularCouplingFields(sizeX, sizeY, sizeZ, config.cellSize());
		granularNextCoupling = new GranularCouplingFields(sizeX, sizeY, sizeZ, config.cellSize());
		granularCollision = new FlipGranularCollision(sizeX, sizeY, sizeZ, config.cellSize(), geometry.cutCells, granularState.deposits,
				GranularLimits.MAX_ACTIVE_PARCELS_PER_REGION);
		LiquidMaterialBinding.bind(domain(), materialDefinitions);
		granularReactivePreviousCells = new int[GranularLimits.MAX_ACTIVE_PARCELS_PER_REGION];
		bindSolidCompositionPoolIfReady();
		gridDirty = true;
	}

	public void restoreGranularState(
			GranularStateSnapshot snapshot, GranularRegistry registry, FluidMaterialRegistry liquidRegistry,
			MaterialRegistry materialDefinitions
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
		bindGranular(registry, liquidRegistry, materialDefinitions);
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
}

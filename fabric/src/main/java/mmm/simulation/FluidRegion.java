package mmm.simulation;

import mmm.fluid.FluidDomainContracts;

import mmm.api.RegionAvailabilityReason;
import mmm.fluid.HostFluidState;
import mmm.fluid.FluidDomainState;
import mmm.thermal.ThermalRegistry;
import mmm.thermal.FiniteThermalDeviceInventory;
import mmm.fluid.FluidSolverConfig;
import mmm.fluid.FluidStepCandidate;
import mmm.fluid.FluidThermalStepCandidate;
import mmm.simulation.controller.FluidRegionFootprint;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Objects;
import java.util.List;
import java.util.UUID;

/** Spatial identity, solver ownership and independent runtime state for one region. */
public final class FluidRegion {
	private final UUID id;
	private final ResourceKey<Level> dimension;
	private final BlockPos origin;
	private final int sizeX;
	private final int sizeY;
	private final int sizeZ;
    private final mmm.spatial.SpatialGrid grid;
	private final FluidSolverConfig solverConfig;
	private FluidRegionFootprint footprint;
	private final long networkGeneration;
	private final FluidRegionLifecycle lifecycle = new FluidRegionLifecycle();
	private HostFluidState domain;
	private mmm.fluid.FluidSolverBackend solverBackend;
	private mmm.fluid.FluidStateAccess residentState;
	private Runnable committedObservationListener = () -> { };
	/** Runtime-only owner revision for synchronous backend candidates; HEAD generation remains independent. */
	private long solverGeneration;
	private long checkpointGeneration;
	private int checkpointMarkerCount;
	private float checkpointVolume;
	private FluidRegionLifecycle.State unloadedResumeState = FluidRegionLifecycle.State.ACTIVE;
	private long snapshotSequence;
	private long tick;
	private double lastStepMilliseconds;
	private FluidDomainContracts.StepStats lastStats;
	private CollisionStats lastCollisionStats = CollisionStats.EMPTY;
	private boolean collisionRefreshRequired;
	private boolean entityPhysicsEnabled = true;
	private boolean debugOverlays;
	private EntityCouplingStats lastEntityCoupling = EntityCouplingStats.EMPTY;
	private int stepIntervalTicks = 1;
	private int hardOverrunStreak;
	private int recoveryStreak;
	private String chemistryBlockFailure;
	private String granularBlockFailure;
	private String thermalBlockFailure;
	private String numericalBlockFailure;
	private String performanceBlockFailure;
	private ThermalActivity thermalActivity;
	private FiniteThermalDeviceInventory finiteThermalDevices;
	private List<mmm.fluid.FluidStateAccess.DevicePort> finiteThermalPorts = List.of();
	private List<int[]> activityConnections = List.of();
	private boolean devicePortsDirty = true;
	private boolean[] worldAvailability;
	private boolean[] groupAvailability;
	private double[] boundaryTemperatures;
	private boolean boundaryTemperaturesKnown;

	void solverBackend(mmm.fluid.FluidSolverBackend backend) { solverBackend = Objects.requireNonNull(backend); }

    boolean pauseForUnavailableDevice() {
        String reason = solverBackend == null ? null : solverBackend.unavailableReason();
        if (reason == null) return false;
        if (residentState != null) {
            unloadedResumeState = lifecycle.state();
            try { releaseResidentState(); }
            catch (RuntimeException closeFailure) {
                mmm.MmmMod.LOGGER.warn("Failed to release unavailable device state for region {}", id, closeFailure);
            } finally {
                clearLoadedSolverState();
                lifecycle.requestUnload(true, true);
            }
        }
        lifecycle.blockAvailability(RegionAvailabilityReason.NUMERICAL_PAUSE, "Vulkan 设备不可用：" + reason);
        return true;
    }

	void ensureDeviceResident() {
		if (domain == null || solverBackend == null || solverBackend.unavailableReason() != null) return;
		try (var prepared = solverBackend.prepareState(null, domain.captureAuthoritativeState(), finiteThermalDevices, finiteThermalPorts)) {
			boundaryTemperatures = domain.thermalBoundaryTemperatures();
			boundaryTemperaturesKnown = true;
			residentState = prepared.commit();
			domain = null;
		}
	}

	void committedObservationListener(Runnable listener) { committedObservationListener = Objects.requireNonNull(listener); }
	void publishCommittedObservation() { committedObservationListener.run(); }

	public FluidRegion(UUID id, ResourceKey<Level> dimension, BlockPos origin, HostFluidState domain) {
		this(id, dimension, origin, domain, null);
	}

	public FluidRegion(
			UUID id,
			ResourceKey<Level> dimension,
			BlockPos origin,
			HostFluidState domain,
			FluidRegionFootprint footprint
	) {
		this(id, dimension, origin, Objects.requireNonNull(domain, "domain").sizeX(), domain.sizeY(), domain.sizeZ(),
				domain.config(), footprint, 1L, domain);
	}

	private FluidRegion(
			UUID id,
			ResourceKey<Level> dimension,
			BlockPos origin,
			int sizeX,
			int sizeY,
			int sizeZ,
			FluidSolverConfig solverConfig,
			FluidRegionFootprint footprint,
			long networkGeneration,
			HostFluidState domain
	) {
		this.id = Objects.requireNonNull(id, "id");
		this.dimension = Objects.requireNonNull(dimension, "dimension");
		this.origin = Objects.requireNonNull(origin, "origin").immutable();
		if (sizeX < 2 || sizeY < 2 || sizeZ < 2) throw new IllegalArgumentException("Region dimensions must be at least two solver cells");
		this.sizeX = sizeX;
		this.sizeY = sizeY;
		this.sizeZ = sizeZ;
        this.grid = domain != null ? domain.grid() : footprint != null ? FluidRegionFactory.grid(footprint) : mmm.spatial.SpatialGrid.rectangular(sizeX,sizeY,sizeZ);
		this.solverConfig = Objects.requireNonNull(solverConfig, "solverConfig");
		if (networkGeneration <= 0L) throw new IllegalArgumentException("Region network generation must be positive");
		this.footprint = footprint;
		this.networkGeneration = networkGeneration;
		this.domain = domain;
		if (domain != null && footprint != null && footprint.kind() == mmm.model.region.RegionGeometry.RegionKind.CLOSED) domain.sealDomainBoundary();
		if (footprint != null && !origin.equals(footprint.minimumInterior())) {
			throw new IllegalArgumentException("A controller region origin must equal its minimum interior block");
		}
	}

	/** Creates a metadata-only controller region. No solver or marker arrays are allocated. */
	public static FluidRegion unloaded(
			UUID id,
			ResourceKey<Level> dimension,
			BlockPos origin,
			int sizeX,
			int sizeY,
			int sizeZ,
			FluidSolverConfig solverConfig,
			FluidRegionFootprint footprint,
			long generation,
			long checkpointGeneration,
			int checkpointMarkerCount,
			float checkpointVolume,
			PersistedRuntime runtime
	) {
		Objects.requireNonNull(footprint, "footprint");
		Objects.requireNonNull(runtime, "runtime");
		if (checkpointGeneration <= 0L) throw new IllegalArgumentException("Checkpoint generation must be positive");
		FluidRegion region = new FluidRegion(id, dimension, origin, sizeX, sizeY, sizeZ, solverConfig,
				footprint, generation, null);
		region.checkpointGeneration = checkpointGeneration;
		if (checkpointMarkerCount < 0
				|| !Float.isFinite(checkpointVolume) || checkpointVolume < 0.0F) {
			throw new IllegalArgumentException("Invalid checkpoint marker ledger");
		}
		region.checkpointMarkerCount = checkpointMarkerCount;
		region.checkpointVolume = checkpointVolume;
		region.unloadedResumeState = runtime.resumeState();
		region.entityPhysicsEnabled = runtime.entityPhysicsEnabled();
		region.debugOverlays = runtime.debugOverlays();
		region.stepIntervalTicks = runtime.stepIntervalTicks();
		region.tick = runtime.tick();
		region.lifecycle.initializeUnloaded(runtime.operatorPaused());
		return region;
	}

	public UUID id() { return id; }
	public ResourceKey<Level> dimension() { return dimension; }
	public BlockPos origin() { return origin; }
	public int sizeX() { return sizeX; }
	public int sizeY() { return sizeY; }
	public int sizeZ() { return sizeZ; }
	public FluidSolverConfig solverConfig() { return solverConfig; }
    public mmm.spatial.SpatialGrid grid() { return grid; }

	/** Creates an independent host view of the committed device generation when needed. */
	public HostFluidState hostSnapshot() {
		var snapshot = HostFluidState.restoreAuthoritativeState(captureHostState());
		// Edits use the same effective availability as this region's current GPU request.
		// It includes device connections and body sweeps that the import template predates.
		if (groupAvailability != null) snapshot.prepareGroupAvailability(groupAvailability);
		else if (residentState != null) {
			boolean[] available = new boolean[grid().cellCount()];
			java.util.Arrays.fill(available, true);
			snapshot.prepareGroupAvailability(available);
		}
		return snapshot;
	}

	private FluidDomainState captureHostState() {
		if (residentState != null) return residentState.exportState();
		if (domain == null) throw new IllegalStateException("Region solver is unloaded");
		return domain.captureAuthoritativeState();
	}

	/** Returns the editable state of a private host candidate or a newly initialized region. */
	HostFluidState editableHost() {
		if (residentState != null || domain == null) throw new IllegalStateException("Region has no editable host state");
		return domain;
	}

	/** Creates a private editable successor; the committed generation remains available. */
	HostFluidState beginHostEdit() { return hostSnapshot(); }

	void commitHostEdit(long baseGeneration, HostFluidState successor) {
		commitHostEdit(baseGeneration, successor, currentFiniteDevices(), finiteThermalPorts);
	}

	private FiniteThermalDeviceInventory currentFiniteDevices() {
		return residentState == null ? finiteThermalDevices : residentState.finiteThermalDevices();
	}

	private void commitHostEdit(long baseGeneration, HostFluidState successor,
			FiniteThermalDeviceInventory devices, List<mmm.fluid.FluidStateAccess.DevicePort> ports) {
		if (!solverLoaded() || solverGeneration != baseGeneration || successor == domain)
			throw new IllegalStateException("Host edit generation is stale");
		long next = Math.addExact(baseGeneration, 1L);
		if (residentState != null) {
			if (solverBackend == null) throw new IllegalStateException("Region backend is unavailable");
			try (var prepared = solverBackend.prepareState(residentState, successor.captureAuthoritativeState(), devices, ports)) {
				var committed = prepared.commit();
				releaseResidentState();
				residentState = committed;
				domain = null;
			}
		} else {
			domain = successor;
		}
		finiteThermalDevices = devices;
		boundaryTemperatures = successor.thermalBoundaryTemperatures();
		boundaryTemperaturesKnown = true;
		solverGeneration = next;
		publishCommittedObservation();
	}

	mmm.fluid.FluidStateAccess captureSolverState() {
		if (residentState != null) return residentState.retain();
		var state = captureHostState();
		var devices = finiteThermalDevices;
		var ports = finiteThermalPorts;
		return new mmm.fluid.FluidStateAccess() {
			public FluidDomainState.Layout layout() { return state.layout(); }
			public FluidDomainState exportState() { return state; }
			public FiniteThermalDeviceInventory finiteThermalDevices() { return devices; }
			public List<DevicePort> finiteThermalPorts() { return ports; }
		};
	}
	mmm.fluid.FluidDomainState.Layout solverLayout() {
		return residentState == null ? editableHost().authoritativeLayout() : residentState.layout();
	}
	int solidMicroMask(int x,int y,int z) {
		var device=deviceState(); return device==null?editableHost().solidMicroMask(x,y,z):device.fixedGeometry().mask(grid().cell(x,y,z));
	}
	double fixedOpenVolume() { var device=deviceState(); return device==null?editableHost().fixedOpenVolumeExact():device.fixedGeometry().totalOpenCellVolume(); }
	boolean worldCellAvailable(int cell) { return worldAvailability==null ? deviceResident() || editableHost().worldCellAvailable(cell) : worldAvailability[cell]; }
	boolean allWorldCellsAvailable() {
		if (worldAvailability == null) return true;
		for (boolean available : worldAvailability) if (!available) return false;
		return true;
	}
	boolean groupAvailable(int cell) { return groupAvailability == null ? deviceResident() || editableHost().groupAvailable(cell) : groupAvailability[cell]; }
	boolean prepareGroupAvailability(boolean[] available) {
		if (available == null || available.length != grid().cellCount())
			throw new IllegalArgumentException("World availability has the wrong cell layout");
		boolean allAvailable = true;
		for (boolean cell : available) allAvailable &= cell;
		worldAvailability = available.clone();
		var device = deviceState();
		if (allAvailable) {
			groupAvailability = new boolean[available.length];
			java.util.Arrays.fill(groupAvailability, true);
		} else if (device != null) {
			groupAvailability = device.activityGraph().available(available);
		} else {
			groupAvailability = new boolean[available.length];
		}
		if (allAvailable) return true;
		if (device != null) for (int cell=0;cell<groupAvailability.length;cell++)
			if (groupAvailability[cell] && device.fixedGeometry().mask(cell)!=255) return true;
		return false;
	}
	boolean[] activityForBodies(List<mmm.world.MovingBodyFluidCoupling.Request> requested) {
		if (worldAvailability == null) return null;
		boolean all = true;
		for (boolean available : worldAvailability) all &= available;
		if (all) return null;
		var device = deviceState();
		if (device == null) return groupAvailability.clone();
		var graph = device.activityGraph();
		graph.joinBodyMotion(device.entityBodies(), requested, solverConfig().cellSize());
		groupAvailability = graph.available(worldAvailability);
		return groupAvailability.clone();
	}

	double[] thermalBoundaryTemperatures() {
		if (!boundaryTemperaturesKnown) {
			boundaryTemperatures = residentState == null ? editableHost().thermalBoundaryTemperatures() : hostSnapshot().thermalBoundaryTemperatures();
			boundaryTemperaturesKnown = true;
		}
		return boundaryTemperatures == null ? null : boundaryTemperatures.clone();
	}
	boolean replaceThermalBoundaryTemperatures(double[] temperatures) {
		if (java.util.Arrays.equals(thermalBoundaryTemperatures(), temperatures)) return false;
		long generation = solverGeneration;
		if (residentState instanceof mmm.fluid.ResidentFluidState device) {
			long next = Math.addExact(generation, 1L);
			try (var prepared = solverBackend.prepareThermalBoundary(device, temperatures)) {
				if (solverGeneration != generation || !prepared.layout().equals(solverLayout()))
					throw new IllegalStateException("Thermal boundary candidate is stale");
				var committed = prepared.commit();
				releaseResidentState();
				residentState = committed;
				solverGeneration = next;
			}
			boundaryTemperatures = temperatures.clone();
			boundaryTemperaturesKnown = true;
			publishCommittedObservation();
			return true;
		}
		var edit = beginHostEdit();
		boolean changed = edit.replaceThermalBoundaryTemperatures(temperatures);
		if (changed) commitHostEdit(generation, edit);
		boundaryTemperatures = temperatures == null ? null : temperatures.clone();
		boundaryTemperaturesKnown = true;
		return changed;
	}

	private mmm.fluid.ResidentFluidState deviceState() {
		return residentState instanceof mmm.fluid.ResidentFluidState device ? device : null;
	}
	mmm.fluid.ResidentFluidState.InventorySummary inventorySummary() { var device=deviceState(); return device==null?null:device.inventorySummary(); }
	boolean hasThermalState() { return solverLoaded() && !solverLayout().thermalRegistryFingerprint().isEmpty(); }
	boolean hasMaterialState() { return solverLoaded() && !solverLayout().materials().isEmpty(); }
	boolean hasChemistryState() { return solverLoaded() && !solverLayout().chemicalRegistryFingerprint().isEmpty(); }
	boolean hasGranularState() { return solverLoaded() && !solverLayout().granularRegistryFingerprint().isEmpty(); }
	int materialHandle(String id) {
		var materials = solverLayout().materials();
		for (int i = 0; i < materials.size(); i++) if (materials.get(i).id().equals(id)) return i;
		throw new IllegalArgumentException("Material is absent: " + id);
	}
	mmm.fluid.EntityBodyState entityBodyState() { var device=deviceState(); return device==null?editableHost().entityBodyState():device.entityBodies(); }
	mmm.thermal.ThermalAuditLedger thermalAudit() { var device=deviceState(); return device==null?editableHost().thermalAudit():device.thermalAudit(); }
	boolean thermalAuditClosed() { var device=deviceState(); return device==null?editableHost().thermalAudit().acceptsInventory(editableHost().exactInternalEnergy()):device.observation().thermalAuditClosed(); }
	boolean hasPendingPhaseMotionWork() { var device=deviceState(); return device==null?editableHost().hasPendingPhaseMotionWork():device.observation().pendingPhaseMotion(); }
	boolean hasPendingPhaseMotionWork(boolean[] active) {
		var device=deviceState();return device==null?editableHost().hasPendingPhaseMotionWork():device.hasPendingPhaseMotion(active);
	}
	float maximumSpeed() { var device=deviceState(); return device==null?editableHost().maximumParticleSpeed():device.observation().maximumSpeed(); }
	boolean gasPressureFlow() { var device=deviceState(); return device==null?editableHost().gasBoundary().hasPressureDrivenFlow():device.observation().gasPressureFlow(); }
	boolean granularQuiet() { var device=deviceState(); return device==null?editableHost().granularReadyForHydrostaticSleep():device.observation().granularQuiet(); }
	int liquidRowCount() { var device=deviceState(); return device==null?editableHost().particleCount():device.observation().liquidRows(); }
	double liquidVolume() { var device=deviceState(); return device==null?editableHost().totalVolumeExact():device.observation().liquidVolume(); }
	mmm.fluid.ResidentFluidState.Display display() {
		var device=deviceState(); if(device!=null)return device.display();
		var cpu=editableHost(); return new mmm.fluid.ResidentFluidState.Display(cpu.copyMaterialRenderSnapshot(),
				cpu.hasChemistryState()?cpu.copyChemicalDisplaySnapshot():null,
				cpu.hasGranularState()?cpu.copyGranularDisplaySnapshot():null,
				cpu.hasThermalState()?cpu.copyThermalDisplaySnapshot():null);
	}
	mmm.fluid.FluidDomainContracts.BodyFluidSample sampleBody(double x0,double y0,double z0,double x1,double y1,double z1) {
		var device=deviceState(); return device==null?editableHost().sampleAxisAlignedBody(x0,y0,z0,x1,y1,z1):device.sampleBody(x0,y0,z0,x1,y1,z1);
	}
	mmm.fluid.FluidCellInventoryView observeCellInventory(int x,int y,int z) {
        var device=deviceState();return device==null?editableHost().observeCellInventory(x,y,z):device.observeCellInventory(x,y,z);
    }
    mmm.fluid.CellCarrierInventoryView observeCellCarriers(int x, int y, int z) {
        var device = deviceState(); return device == null ? editableHost().observeCellCarriers(x, y, z) : device.observeCellCarriers(x, y, z);
    }
    boolean hasGranularOccupancy(int x, int y, int z) {
        var device = deviceState(); return device == null ? editableHost().granularSolidVolumeSvq(x, y, z) > 0 : device.hasGranularOccupancy(x, y, z);
    }
    boolean hasCondensedInventory(int x, int y, int z) {
        var device = deviceState();
        if (device == null) return editableHost().hasCondensedInventoryInCell(x, y, z);
        if (device.observeCellInventory(x, y, z) != null) return true;
        var carriers = device.observeCellCarriers(x, y, z);
        return carriers != null && !carriers.solids().isEmpty();
    }
    List<FluidDomainContracts.WaterContact> waterContacts(List<FluidDomainContracts.WaterContactFace> faces) {
        var device=deviceState();return device==null?editableHost().waterContacts(faces):device.waterContacts(faces);
    }
	boolean deviceResident() { return residentState != null; }
	public boolean solverLoaded() { return domain != null || residentState != null; }
	private void releaseResidentState() {
		var previous = residentState;
		residentState = null;
		if (previous != null) previous.close();
	}

	/** All data tied to a released generation is restored together from its checkpoint. */
	private void clearLoadedSolverState() {
		domain = null;
		finiteThermalDevices = null;
		finiteThermalPorts = List.of();
		devicePortsDirty = true;
		activityConnections = List.of();
		worldAvailability = null;
		groupAvailability = null;
		boundaryTemperatures = null;
		boundaryTemperaturesKnown = false;
	}

	/** Discards an unregistered transient region after its final removal has been published. */
	void discardTransient() {
		if (controllerManaged()) throw new IllegalStateException("Cannot discard a persistent controller solver");
		releaseResidentState();
		domain = null;
	}
	long solverGeneration() { return solverGeneration; }

	/**
	 * Atomically replaces the live solver with a complete backend candidate. The live domain is only
	 * touched after the candidate's backend identity, adjacency and layout have been validated, so a
	 * rejected step never leaves partially applied marker momentum behind.
	 */
	void adoptStepCandidate(FluidStepCandidate candidate, String expectedBackendId) {
		Objects.requireNonNull(candidate, "candidate");
		adoptDeviceState(candidate.backendId(), candidate.baseGeneration(), candidate.candidateGeneration(),
				candidate.layout(), Objects.requireNonNull(candidate.claimPreparedDeviceState()), expectedBackendId);
	}

	void adoptThermalStepCandidate(FluidThermalStepCandidate candidate, String expectedBackendId) {
		Objects.requireNonNull(candidate, "candidate");
		adoptDeviceState(candidate.backendId(), candidate.baseGeneration(), candidate.candidateGeneration(),
				candidate.layout(), Objects.requireNonNull(candidate.claimPreparedDeviceState()), expectedBackendId);
	}

	private void adoptDeviceState(String backendId, long baseGeneration, long candidateGeneration,
			FluidDomainState.Layout candidateLayout, mmm.fluid.PreparedFluidState prepared, String expectedBackendId) {
		try (prepared) {
			validateCandidateIdentity(backendId, baseGeneration, candidateGeneration, candidateLayout, expectedBackendId);
			if (!boundaryTemperaturesKnown && domain != null) {
				boundaryTemperatures = domain.thermalBoundaryTemperatures();
				boundaryTemperaturesKnown = true;
			}
			var committed = prepared.commit();
			releaseResidentState();
			residentState = committed;
			domain = null;
			solverGeneration = candidateGeneration;
		}
		publishCommittedObservation();
	}
	private void validateCandidateIdentity(String backendId, long baseGeneration, long candidateGeneration,
			FluidDomainState.Layout candidateLayout, String expectedBackendId) {
		if (!solverLoaded()) throw new IllegalStateException("Region solver is unloaded");
		if (!Objects.requireNonNull(expectedBackendId).equals(backendId) || baseGeneration != solverGeneration
				|| candidateGeneration != Math.addExact(solverGeneration, 1L))
			throw new IllegalStateException("Backend candidate identity or generation is stale");
		if (!candidateLayout.equals(solverLayout()))
			throw new IllegalStateException("Backend candidate changed the live solver layout");
	}

	void adoptCollisionCandidate(long baseGeneration, HostFluidState candidate) {
		if (!candidate.authoritativeLayout().equals(solverLayout()))
			throw new IllegalStateException("Collision candidate layout changed");
		commitHostEdit(baseGeneration, candidate);
	}

	public FluidRegionFootprint footprint() { return footprint; }
	public boolean controllerManaged() { return footprint != null; }
	public long networkGeneration() { return networkGeneration; }
	public long checkpointGeneration() { return checkpointGeneration; }
	public int checkpointMarkerCount() { return checkpointMarkerCount; }
	public float checkpointVolume() { return checkpointVolume; }
	public FluidRegionLifecycle lifecycle() { return lifecycle; }
	public long reservedCellCapacity() {
		return grid().cellCount();
	}

	boolean paused() { return lifecycle.operatorPaused(); }
	void setPaused(boolean value) { lifecycle.setOperatorPaused(value, stepIntervalTicks); }
	boolean canStep() { return solverLoaded() && lifecycle.canStep(); }
	void wakeForActivity() {
		lifecycle.wakeForActivity(stepIntervalTicks);
		wakeThermal(ThermalActivity.WakeReason.CARRIER_MOTION);
	}
	void enableThermalActivity() {
		if (thermalActivity == null) thermalActivity = new ThermalActivity();
	}
	boolean canStepThermal() {
		return solverLoaded() && !solverLayout().thermalRegistryFingerprint().isEmpty() && thermalActivity != null && !lifecycle.operatorPaused()
				&& lifecycle.availabilityFailure() == null
				&& lifecycle.state() != FluidRegionLifecycle.State.UNLOADED && thermalActivity.canStep();
	}
	ThermalActivity thermalActivity() { return thermalActivity; }
	void wakeThermal(ThermalActivity.WakeReason reason) {
		if (thermalActivity != null) thermalActivity.wake(reason);
	}
	boolean observeThermal(ThermalActivity.Observation observation, int quietSteps) {
		return thermalActivity != null && thermalActivity.observe(observation, quietSteps);
	}
	void blockThermal(String failure) {
		enableThermalActivity();
		thermalBlockFailure = Objects.requireNonNull(failure, "failure");
		thermalActivity.pause(thermalBlockFailure);
		setAvailabilityFailure(RegionAvailabilityReason.NUMERICAL_PAUSE, thermalBlockFailure);
	}
	long nextSnapshotSequence() {
		if (snapshotSequence == Long.MAX_VALUE) throw new IllegalStateException("Snapshot sequence exhausted");
		return ++snapshotSequence;
	}
	long incrementTick() { return ++tick; }
	long tick() { return tick; }
	double lastStepMilliseconds() { return lastStepMilliseconds; }
	FluidDomainContracts.StepStats lastStats() { return lastStats; }
	void recordStep(FluidDomainContracts.StepStats stats, double milliseconds) {
		lastStats = Objects.requireNonNull(stats, "stats");
		lastStepMilliseconds = milliseconds;
	}
	void recordThermalStep(double milliseconds) {
		if (!(Double.isFinite(milliseconds) && milliseconds >= 0.0)) {
			throw new IllegalArgumentException("Thermal step duration is invalid");
		}
		lastStepMilliseconds = milliseconds;
	}
	CollisionStats lastCollisionStats() { return lastCollisionStats; }
	void setLastCollisionStats(CollisionStats value) { lastCollisionStats = Objects.requireNonNull(value, "value"); }
	public boolean collisionRefreshRequired() { return collisionRefreshRequired; }
	void markCollisionRefreshed() { collisionRefreshRequired = false; }
	void requireCollisionRefresh() { collisionRefreshRequired = true; }
	boolean entityPhysicsEnabled() { return entityPhysicsEnabled; }
	void setEntityPhysicsEnabled(boolean enabled) {
		entityPhysicsEnabled = enabled;
		if (!enabled) lastEntityCoupling = EntityCouplingStats.EMPTY;
	}
	boolean debugOverlays() { return debugOverlays; }
	void setDebugOverlays(boolean enabled) { debugOverlays = enabled; }
	int lastAffectedEntities() { return lastEntityCoupling.affectedEntities(); }
	EntityCouplingStats lastEntityCoupling() { return lastEntityCoupling; }
	void setLastEntityCoupling(EntityCouplingStats value) {
		lastEntityCoupling = Objects.requireNonNull(value, "value");
	}
	int stepIntervalTicks() { return stepIntervalTicks; }
	void setStepIntervalTicks(int value) {
		if (value <= 0) throw new IllegalArgumentException("step interval must be positive");
		stepIntervalTicks = value;
		lifecycle.onStepIntervalChanged(value);
	}
	int hardOverrunStreak() { return hardOverrunStreak; }
	void setHardOverrunStreak(int value) { hardOverrunStreak = value; }
	int recoveryStreak() { return recoveryStreak; }
	void setRecoveryStreak(int value) { recoveryStreak = value; }
	String availabilityFailure() { return lifecycle.availabilityFailure(); }
	RegionAvailabilityReason availabilityReason() { return lifecycle.availabilityReason(); }
	String availabilityDescription() {
		return availabilityReason() == null ? "READY" : availabilityReason() + ": " + availabilityFailure();
	}
	void setAvailabilityFailure(String value) {
		if (value == null) lifecycle.clearAvailabilityBlock();
		else lifecycle.blockAvailability(value);
		publishCommittedObservation();
	}
	void setAvailabilityFailure(RegionAvailabilityReason reason, String value) {
		lifecycle.blockAvailability(reason, value);
		publishCommittedObservation();
	}
	String chemistryBlockFailure() { return chemistryBlockFailure; }
	void blockChemistry(String failure) {
		chemistryBlockFailure = Objects.requireNonNull(failure, "failure");
		setAvailabilityFailure(RegionAvailabilityReason.NUMERICAL_PAUSE, failure);
	}
	String granularBlockFailure() { return granularBlockFailure; }
	void blockGranular(String failure) {
		granularBlockFailure = Objects.requireNonNull(failure, "failure");
		setAvailabilityFailure(RegionAvailabilityReason.NUMERICAL_PAUSE, failure);
	}
	void blockCollision(String failure) {
		requireCollisionRefresh();
		setAvailabilityFailure(RegionAvailabilityReason.BODY_OVERLAP,
				Objects.requireNonNull(failure, "failure"));
	}
	String thermalBlockFailure() { return thermalBlockFailure; }
	String numericalBlockFailure() { return numericalBlockFailure; }
	void blockNumerical(String failure) {
		numericalBlockFailure = Objects.requireNonNull(failure, "failure");
		setAvailabilityFailure(RegionAvailabilityReason.NUMERICAL_PAUSE, failure);
	}
	String performanceBlockFailure() { return performanceBlockFailure; }
	void blockPerformance(String failure) {
		performanceBlockFailure = Objects.requireNonNull(failure, "failure");
		setAvailabilityFailure(RegionAvailabilityReason.PERFORMANCE_PAUSE, failure);
	}
	void clearPerformanceBlock() {
		performanceBlockFailure = null;
		hardOverrunStreak = 0;
		recoveryStreak = 0;
		if (availabilityReason() == RegionAvailabilityReason.PERFORMANCE_PAUSE) setAvailabilityFailure(null);
	}

	boolean devicePortsDirty() { return devicePortsDirty; }
	void markDevicePortsDirty() { devicePortsDirty = true; }

	void replaceDevicePorts(List<mmm.fluid.FluidStateAccess.DevicePort> ports, List<int[]> connections) {
		boolean changed = !finiteThermalPorts.equals(ports) || activityConnections.size() != connections.size();
		for (int i=0; !changed && i<connections.size(); i++) changed = !java.util.Arrays.equals(activityConnections.get(i),connections.get(i));
		if (!changed) { devicePortsDirty = false; return; }
		long generation = solverGeneration;
		var edit = beginHostEdit();
		edit.replaceActivityConnections(connections);
		commitHostEdit(generation, edit, currentFiniteDevices(), List.copyOf(ports));
		finiteThermalPorts = List.copyOf(ports);
		activityConnections = connections.stream().map(int[]::clone).toList();
		devicePortsDirty = false;
		worldAvailability = null;
		groupAvailability = null;
	}

	/** Fixed wall inventories remain in the final checkpoint and its removal quarantine. */
	boolean hasRemovableThermalDevices() {
		var devices = currentFiniteDevices();
		return devices != null && devices.devices().stream()
				.anyMatch(device -> !device.providerId().equals(OrdinaryThermalWalls.PROVIDER));
	}

	/** Returns immutable region-owned device truth, binding a new empty inventory to this layout. */
	public FiniteThermalDeviceInventory finiteThermalDevices(String thermalRegistryFingerprint) {
		FiniteThermalDeviceInventory current = residentState == null ? finiteThermalDevices : residentState.finiteThermalDevices();
		if (current == null) return FiniteThermalDeviceInventory.empty(thermalRegistryFingerprint);
		if (!current.thermalRegistryFingerprint().equals(thermalRegistryFingerprint)) {
			throw new IllegalStateException("Finite thermal device registry fingerprint changed");
		}
		return current;
	}

	/** Restores device truth only alongside the matching thermal region generation. */
	public void restoreFiniteThermalDevices(
			FiniteThermalDeviceInventory inventory, ThermalRegistry registry, long loadedCheckpointGeneration
	) {
		Objects.requireNonNull(inventory, "inventory");
		inventory.requireCompatible(Objects.requireNonNull(registry, "registry"));
		if (domain == null || !domain.hasThermalState() || loadedCheckpointGeneration != checkpointGeneration) {
			throw new IllegalStateException("Finite thermal devices require the matching loaded thermal generation");
		}
		if (finiteThermalDevices != null && !finiteThermalDevices.equals(inventory)) {
			throw new IllegalStateException("Finite thermal device truth changed while restoring a checkpoint");
		}
		finiteThermalDevices = inventory;
		devicePortsDirty = true;
	}



	void replaceOrdinaryWallInventory(FiniteThermalDeviceInventory inventory, ThermalRegistry registry) {
		inventory.requireCompatible(registry);
		var identities = new java.util.HashSet<String>();
		for (var device : inventory.devices()) identities.add(device.providerId()+"\0"+device.objectId());
		var ports = finiteThermalPorts.stream().filter(port -> identities.contains(port.providerId()+"\0"+port.objectId())).toList();
		commitHostEdit(solverGeneration, beginHostEdit(), inventory, ports);
		finiteThermalPorts = ports;
		devicePortsDirty = true;
		wakeForActivity();
	}

	FluidRegion finiteDeviceCandidate(FiniteThermalDeviceInventory inventory, ThermalRegistry registry) {
		inventory.requireCompatible(registry);
		var candidate = lifecycleCandidate(beginHostEdit(), paused());
		candidate.finiteThermalDevices = inventory;
		return candidate;
	}

	void adoptFiniteDeviceCandidate(FluidRegion candidate, long publishedGeneration) {
		if (!id.equals(candidate.id)) throw new IllegalArgumentException("Finite device candidate belongs to another region");
		var devices = candidate.finiteThermalDevices;
		var ports = finiteThermalPorts.stream().filter(port -> devices != null && devices.devices().stream()
				.anyMatch(device -> device.providerId().equals(port.providerId()) && device.objectId().equals(port.objectId())))
				.toList();
		adoptPublishedPrivateDomain(candidate.domain, publishedGeneration, candidate.paused(), devices, ports);
		finiteThermalPorts = ports;
		devicePortsDirty = true;
		activityConnections = List.of();
		worldAvailability = null;
		groupAvailability = null;
		footprint = candidate.footprint;
		wakeForActivity();
	}


	public PersistedRuntime persistedRuntime() {
		FluidRegionLifecycle.State resumeState = lifecycle.state() == FluidRegionLifecycle.State.UNLOADED
				? unloadedResumeState : lifecycle.state();
		return new PersistedRuntime(resumeState, lifecycle.operatorPaused(), entityPhysicsEnabled,
				debugOverlays, stepIntervalTicks, tick);
	}

	/** Creates an unregistered private candidate carrying the same persistent identity and runtime intent. */
	FluidRegion lifecycleCandidate(HostFluidState candidateDomain, boolean operatorPaused) {
		Objects.requireNonNull(candidateDomain, "candidateDomain");
		PersistedRuntime runtime = persistedRuntime();
		FluidRegion candidate = new FluidRegion(id, dimension, origin, sizeX, sizeY, sizeZ, solverConfig,
				footprint, networkGeneration, candidateDomain);
		candidate.checkpointGeneration = checkpointGeneration;
		candidate.checkpointMarkerCount = checkpointMarkerCount;
		candidate.checkpointVolume = checkpointVolume;
		candidate.unloadedResumeState = runtime.resumeState();
		candidate.entityPhysicsEnabled = runtime.entityPhysicsEnabled();
		candidate.debugOverlays = runtime.debugOverlays();
		candidate.stepIntervalTicks = runtime.stepIntervalTicks();
		candidate.tick = runtime.tick();
		candidate.finiteThermalDevices = currentFiniteDevices();
		candidate.finiteThermalPorts = finiteThermalPorts;
		candidate.lifecycle.initializeLoaded(runtime.resumeState(), operatorPaused, runtime.stepIntervalTicks());
		return candidate;
	}

	FluidRegion ownershipCandidate(HostFluidState domain, FluidRegionFootprint footprint) {
		FluidRegion candidate = lifecycleCandidate(domain, true);
		candidate.footprint = footprint;
		return candidate;
	}

	void adoptOwnershipCandidate(FluidRegion candidate, long generation) {
		adoptPublishedPrivateDomain(candidate.editableHost(), generation, true);
		footprint = candidate.footprint;
	}

	/** Adopts the exact solver which was already encoded and published as the lifecycle HEAD. */
	void adoptLifecycleCandidateDomain(
			HostFluidState candidateDomain, long publishedGeneration, boolean operatorPaused
	) {
		adoptPublishedPrivateDomain(candidateDomain, publishedGeneration, operatorPaused);
	}

	/** Adopts a HEAD-published private container successor and invalidates every older solver candidate. */
	void adoptContainerCandidateDomain(
			HostFluidState candidateDomain, long publishedGeneration, boolean operatorPaused
	) {
		adoptPublishedPrivateDomain(candidateDomain, publishedGeneration, operatorPaused);
	}

	private void adoptPublishedPrivateDomain(HostFluidState candidateDomain, long publishedGeneration, boolean operatorPaused) {
		adoptPublishedPrivateDomain(candidateDomain, publishedGeneration, operatorPaused, currentFiniteDevices(), finiteThermalPorts);
	}

	private void adoptPublishedPrivateDomain(
			HostFluidState candidateDomain, long publishedGeneration, boolean operatorPaused, FiniteThermalDeviceInventory devices,
			List<mmm.fluid.FluidStateAccess.DevicePort> ports
	) {
		Objects.requireNonNull(candidateDomain, "candidateDomain");
		try {
			if (!controllerManaged() || !solverLoaded() || publishedGeneration <= checkpointGeneration
					|| candidateDomain.sizeX() != sizeX || candidateDomain.sizeY() != sizeY
					|| candidateDomain.sizeZ() != sizeZ
					|| !candidateDomain.config().equals(solverConfig)) {
				throw new IllegalStateException("Published private candidate is incompatible with the live region");
			}
			commitHostEdit(solverGeneration, candidateDomain, devices, ports);
		} catch (RuntimeException | Error failure) {
			// HEAD is already durable. Discard the old live generation and reload that HEAD
			// before accepting another step or checkpoint.
			if (!controllerManaged() || publishedGeneration <= checkpointGeneration) throw failure;
			checkpointGeneration = publishedGeneration;
			checkpointMarkerCount = candidateDomain.particleCount();
			checkpointVolume = candidateDomain.totalVolume();
			unloadedResumeState = lifecycle.state() == FluidRegionLifecycle.State.UNLOADED
					? FluidRegionLifecycle.State.ACTIVE : lifecycle.state();
			lifecycle.setOperatorPaused(operatorPaused, stepIntervalTicks);
			try { releaseResidentState(); } catch (RuntimeException | Error closeFailure) { failure.addSuppressed(closeFailure); }
			clearLoadedSolverState();
			lifecycle.requestUnload(true, true);
			lifecycle.blockAvailability(RegionAvailabilityReason.SECTION_LOADING, "正在恢复已提交区域代次");
			try { publishCommittedObservation(); } catch (RuntimeException | Error notifyFailure) { failure.addSuppressed(notifyFailure); }
			throw failure;
		}
		checkpointGeneration = publishedGeneration;
		checkpointMarkerCount = candidateDomain.particleCount();
		checkpointVolume = candidateDomain.totalVolume();
		collisionRefreshRequired = true;
		lifecycle.setOperatorPaused(operatorPaused, stepIntervalTicks);
		publishCommittedObservation();
	}

	/** Records a complete durable generation while keeping the live solver allocated. */
	public boolean recordPublishedCheckpoint(long publishedGeneration) {
		if (!controllerManaged() || !solverLoaded() || publishedGeneration <= checkpointGeneration) return false;
		checkpointMarkerCount = liquidRowCount();
		checkpointVolume = (float)liquidVolume();
		checkpointGeneration = publishedGeneration;
		return true;
	}

	/** Releases the solver only after the caller has durably published the supplied generation. */
	public boolean releaseSolverAfterCheckpoint(long publishedGeneration) {
		if (!controllerManaged() || !solverLoaded() || publishedGeneration <= 0L || publishedGeneration != checkpointGeneration) return false;
		unloadedResumeState = lifecycle.state() == FluidRegionLifecycle.State.UNLOADED
				? FluidRegionLifecycle.State.ACTIVE : lifecycle.state();
		try {
			releaseResidentState();
		} finally {
			clearLoadedSolverState();
			lifecycle.requestUnload(true, true);
			publishCommittedObservation();
		}
		return true;
	}

	/** Restores the complete cell/MAC generation while retaining the persisted lifecycle intent. */
	public void restoreAuthoritativeSolver(mmm.fluid.FluidDomainState state,long generation) {
		if(solverLoaded()||lifecycle.state()!=FluidRegionLifecycle.State.UNLOADED)throw new IllegalStateException("Only an unloaded region can restore its solver");
		if(generation!=checkpointGeneration)throw new IllegalArgumentException("Checkpoint generation changed while restoring");
		if(!state.grid().equals(grid())||!state.config().equals(solverConfig()))throw new IllegalArgumentException("Checkpoint solver geometry changed");
		domain=HostFluidState.restoreAuthoritativeState(state);boundaryTemperatures=domain.thermalBoundaryTemperatures();boundaryTemperaturesKnown=true;collisionRefreshRequired=true;lifecycle.restoreFromUnloaded(unloadedResumeState,stepIntervalTicks);
		publishCommittedObservation();
	}

	public record PersistedRuntime(
			FluidRegionLifecycle.State resumeState,
			boolean operatorPaused,
			boolean entityPhysicsEnabled,
			boolean debugOverlays,
			int stepIntervalTicks,
			long tick
	) {
		public PersistedRuntime {
			Objects.requireNonNull(resumeState, "resumeState");
			if (resumeState == FluidRegionLifecycle.State.UNLOADED) {
				throw new IllegalArgumentException("Persisted resume state cannot be UNLOADED");
			}
			if (stepIntervalTicks <= 0 || tick < 0L) throw new IllegalArgumentException("Invalid persisted runtime");
		}
	}

	public record CollisionStats(int changedCells, float displacedCellVolume, float escapedCellVolume, boolean markerBudgetLimited) {
		static final CollisionStats EMPTY = new CollisionStats(0, 0.0F, 0.0F, false);
	}

	record EntityCouplingStats(
			int affectedEntities,
			double impulseXKilogramMetresPerSecond,
			double impulseYKilogramMetresPerSecond,
			double impulseZKilogramMetresPerSecond
	) {
		static final EntityCouplingStats EMPTY = new EntityCouplingStats(0, 0.0, 0.0, 0.0);

		EntityCouplingStats {
			if (affectedEntities < 0 || !(Double.isFinite(impulseXKilogramMetresPerSecond)
					&& Double.isFinite(impulseYKilogramMetresPerSecond)
					&& Double.isFinite(impulseZKilogramMetresPerSecond))) {
				throw new IllegalArgumentException("Entity coupling stats are invalid");
			}
		}
	}
}

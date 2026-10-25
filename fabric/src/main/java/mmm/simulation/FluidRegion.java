package mmm.simulation;

import mmm.api.RegionAvailabilityReason;
import mmm.fluid.FluidDomain;
import mmm.fluid.FluidDomainState;
import mmm.thermal.ThermalRegistry;
import mmm.thermal.FiniteThermalDeviceInventory;
import mmm.thermal.FiniteThermalExchange;
import mmm.fluid.FluidSolverConfig;
import mmm.fluid.FluidStepCandidate;
import mmm.fluid.FluidThermalStepCandidate;
import mmm.simulation.controller.FluidRegionFootprint;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Objects;
import java.util.ArrayList;
import java.util.Comparator;
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
	private final FluidSolverConfig solverConfig;
	private FluidRegionFootprint footprint;
	private final long networkGeneration;
	private final FluidRegionLifecycle lifecycle = new FluidRegionLifecycle();
	private FluidDomain domain;
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
	private FluidDomain.StepStats lastStats;
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
	private boolean[] worldAvailability;
	private boolean[] groupAvailability;
	private double[] boundaryTemperatures;

	void committedObservationListener(Runnable listener) { committedObservationListener = Objects.requireNonNull(listener); }
	void publishCommittedObservation() { committedObservationListener.run(); }

	public FluidRegion(UUID id, ResourceKey<Level> dimension, BlockPos origin, FluidDomain domain) {
		this(id, dimension, origin, domain, null);
	}

	public FluidRegion(
			UUID id,
			ResourceKey<Level> dimension,
			BlockPos origin,
			FluidDomain domain,
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
			FluidDomain domain
	) {
		this.id = Objects.requireNonNull(id, "id");
		this.dimension = Objects.requireNonNull(dimension, "dimension");
		this.origin = Objects.requireNonNull(origin, "origin").immutable();
		if (sizeX < 2 || sizeY < 2 || sizeZ < 2) throw new IllegalArgumentException("Region dimensions must be at least two solver cells");
		this.sizeX = sizeX;
		this.sizeY = sizeY;
		this.sizeZ = sizeZ;
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
	/** Explicit editable host handoff, used by world edits and persistence. */
	public FluidDomain domain() {
		if (residentState != null) {
			FluidDomain restored = FluidDomain.restoreAuthoritativeState(residentState.exportState());
			var devices = residentState.finiteThermalDevices();
			residentState.close(); residentState = null; domain = restored;
			if (devices != null) finiteThermalDevices = devices;
		}
		if (domain == null) throw new IllegalStateException("Region solver is unloaded");
		return domain;
	}
	mmm.fluid.FluidStateAccess captureSolverState() {
		if (residentState != null) return residentState.retain();
		var state = domain().captureAuthoritativeState();
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
		return residentState == null ? domain().authoritativeLayout() : residentState.layout();
	}
	int solidMicroMask(int x,int y,int z) {
		var device=deviceState(); return device==null?domain().solidMicroMask(x,y,z):device.fixedGeometry().mask((z*sizeY+y)*sizeX+x);
	}
	double fixedOpenVolume() { var device=deviceState(); return device==null?domain().fixedOpenVolumeExact():device.fixedGeometry().totalOpenCellVolume(); }
	boolean worldCellAvailable(int cell) { return worldAvailability==null ? deviceResident() || domain().worldCellAvailable(cell) : worldAvailability[cell]; }
	boolean groupAvailable(int cell) { return !deviceResident()?domain().groupAvailable(cell):groupAvailability==null || groupAvailability[cell]; }
	boolean prepareGroupAvailability(boolean[] available) {
		if (deviceResident() && java.util.Arrays.equals(worldAvailability,available)) {
			for(boolean value:groupAvailability) if(value)return true;
			return false;
		}
		var cpu=domain(); cpu.prepareGroupAvailability(available);
		worldAvailability=available.clone(); groupAvailability=new boolean[available.length];
		for(int cell=0;cell<available.length;cell++)groupAvailability[cell]=cpu.groupAvailable(cell);
		return cpu.hasAvailableGroups();
	}
	double[] thermalBoundaryTemperatures() {
		if(boundaryTemperatures==null && !deviceResident())boundaryTemperatures=domain().thermalBoundaryTemperatures();
		return boundaryTemperatures==null?null:boundaryTemperatures.clone();
	}
	boolean replaceThermalBoundaryTemperatures(double[] temperatures) {
		if(java.util.Arrays.equals(boundaryTemperatures,temperatures))return false;
		boolean changed=domain().replaceThermalBoundaryTemperatures(temperatures);
		boundaryTemperatures=temperatures.clone();return changed;
	}
	private mmm.fluid.ResidentFluidState deviceState() {
		return residentState instanceof mmm.fluid.ResidentFluidState device ? device : null;
	}
	mmm.fluid.ResidentFluidState.InventorySummary inventorySummary() { var device=deviceState(); return device==null?null:device.inventorySummary(); }
	boolean hasThermalState() { return solverLoaded() && !solverLayout().thermalRegistryFingerprint().isEmpty(); }
	mmm.fluid.EntityBodyState entityBodyState() { var device=deviceState(); return device==null?domain().entityBodyState():device.entityBodies(); }
	mmm.thermal.ThermalAuditLedger thermalAudit() { var device=deviceState(); return device==null?domain().thermalAudit():device.thermalAudit(); }
	boolean thermalAuditClosed() { var device=deviceState(); return device==null?domain().exactInternalEnergy().equals(domain().thermalAudit().expectedInventory()):device.observation().thermalAuditClosed(); }
	boolean hasPendingPhaseMotionWork() { var device=deviceState(); return device==null?domain().hasPendingPhaseMotionWork():device.observation().pendingPhaseMotion(); }
	float maximumSpeed() { var device=deviceState(); return device==null?domain().maximumParticleSpeed():device.observation().maximumSpeed(); }
	boolean gasPressureFlow() { var device=deviceState(); return device==null?domain().gasBoundary().hasPressureDrivenFlow():device.observation().gasPressureFlow(); }
	boolean granularQuiet() { var device=deviceState(); return device==null?domain().granularReadyForHydrostaticSleep():device.observation().granularQuiet(); }
	int liquidRowCount() { var device=deviceState(); return device==null?domain().particleCount():device.observation().liquidRows(); }
	double liquidVolume() { var device=deviceState(); return device==null?domain().totalVolumeExact():device.observation().liquidVolume(); }
	mmm.fluid.ResidentFluidState.Display display() {
		var device=deviceState(); if(device!=null)return device.display();
		var cpu=domain(); return new mmm.fluid.ResidentFluidState.Display(cpu.copyMaterialRenderSnapshot(),
				cpu.hasChemistryState()?cpu.copyChemicalDisplaySnapshot():null,
				cpu.hasGranularState()?cpu.copyGranularDisplaySnapshot():null,
				cpu.hasThermalState()?cpu.copyThermalDisplaySnapshot():null);
	}
	mmm.fluid.FluidDomain.BodyFluidSample sampleBody(double x0,double y0,double z0,double x1,double y1,double z1) {
		var device=deviceState(); return device==null?domain().sampleAxisAlignedBody(x0,y0,z0,x1,y1,z1):device.sampleBody(x0,y0,z0,x1,y1,z1);
	}
	mmm.fluid.FluidCellInventoryView observeCellInventory(int x,int y,int z) {
        var device=deviceState();return device==null?domain().observeCellInventory(x,y,z):device.observeCellInventory(x,y,z);
    }
    List<FluidDomain.WaterContact> waterContacts(List<FluidDomain.WaterContactFace> faces) {
        var device=deviceState();return device==null?domain().waterContacts(faces):device.waterContacts(faces);
    }
	boolean deviceResident() { return residentState != null; }
	public boolean solverLoaded() { return domain != null || residentState != null; }
	private void releaseResidentState() {
		if (residentState != null) { residentState.close(); residentState = null; }
	}
	long solverGeneration() { return solverGeneration; }

	/**
	 * Atomically replaces the live solver with a complete backend candidate. The live domain is only
	 * touched after the candidate's backend identity, adjacency and layout have been validated, so a
	 * rejected step never leaves partially applied marker momentum behind.
	 */
	void adoptStepCandidate(FluidStepCandidate candidate, String expectedBackendId) {
		Objects.requireNonNull(candidate, "candidate");
		var preparedDevice = candidate.claimPreparedDeviceState();
		if (preparedDevice != null) {
			adoptDeviceState(candidate.backendId(), candidate.baseGeneration(), candidate.candidateGeneration(),
					candidate.layout(), preparedDevice, expectedBackendId);
			return;
		}
		FluidDomain preparedCpuDomain = candidate.claimPreparedCpuDomain();
		adoptCandidateState(candidate.backendId(), candidate.baseGeneration(), candidate.candidateGeneration(),
				candidate.layout(), preparedCpuDomain,
				preparedCpuDomain == null ? candidate.state() : null, expectedBackendId);
	}

	/** Applies the same atomic identity/layout gate to a thermal-only private backend successor. */
	void adoptThermalStepCandidate(FluidThermalStepCandidate candidate, String expectedBackendId) {
		Objects.requireNonNull(candidate, "candidate");
		var preparedDevice = candidate.claimPreparedDeviceState();
		if (preparedDevice != null) {
			adoptDeviceState(candidate.backendId(), candidate.baseGeneration(), candidate.candidateGeneration(),
					candidate.layout(), preparedDevice, expectedBackendId);
			return;
		}
		FluidDomain preparedCpuDomain = candidate.claimPreparedCpuDomain();
		adoptCandidateState(candidate.backendId(), candidate.baseGeneration(), candidate.candidateGeneration(),
				candidate.layout(), preparedCpuDomain,
				preparedCpuDomain == null ? candidate.state() : null, expectedBackendId);
	}

	private void adoptDeviceState(String backendId, long baseGeneration, long candidateGeneration,
			FluidDomainState.Layout candidateLayout, mmm.fluid.PreparedFluidState prepared, String expectedBackendId) {
		try (prepared) {
			validateCandidateIdentity(backendId, baseGeneration, candidateGeneration, candidateLayout, expectedBackendId);
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

	private void adoptCandidateState(
			String backendId, long baseGeneration, long candidateGeneration,
			FluidDomainState.Layout candidateLayout, FluidDomain preparedCpuDomain,
			FluidDomainState materializedState, String expectedBackendId
	) {
		validateCandidateIdentity(backendId, baseGeneration, candidateGeneration, candidateLayout, expectedBackendId);
		FluidDomain replacement = preparedCpuDomain == null
				? FluidDomain.restoreAuthoritativeState(Objects.requireNonNull(materializedState, "materializedState"))
				: preparedCpuDomain;
		if (replacement.sizeX() != sizeX || replacement.sizeY() != sizeY || replacement.sizeZ() != sizeZ
				|| !replacement.config().equals(solverConfig)) {
			throw new IllegalStateException("Backend candidate layout is incompatible with the live region");
		}
		releaseResidentState();
		domain = replacement;
		solverGeneration = candidateGeneration;
		publishCommittedObservation();
	}


	/** Validates all carrier topology before publishing a synchronous world-collision successor. */
	void adoptCollisionCandidate(FluidDomain original, long baseGeneration, FluidDomain candidate) {
		Objects.requireNonNull(candidate, "candidate");
		if (domain != original || solverGeneration != baseGeneration || candidate == original
				|| !candidate.authoritativeLayout().equals(domain.authoritativeLayout())) {
			throw new IllegalStateException("Collision candidate identity, generation or layout changed");
		}
		long nextGeneration = Math.addExact(solverGeneration, 1L);
		candidate.captureAuthoritativeState();
		domain = candidate;
		solverGeneration = nextGeneration;
		publishCommittedObservation();
	}
	public FluidRegionFootprint footprint() { return footprint; }
	public boolean controllerManaged() { return footprint != null; }
	public long networkGeneration() { return networkGeneration; }
	public long checkpointGeneration() { return checkpointGeneration; }
	public int checkpointMarkerCount() { return checkpointMarkerCount; }
	public float checkpointVolume() { return checkpointVolume; }
	public FluidRegionLifecycle lifecycle() { return lifecycle; }
	public long reservedCellCapacity() {
		return Math.multiplyExact(Math.multiplyExact((long) sizeX, sizeY), sizeZ);
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
	FluidDomain.StepStats lastStats() { return lastStats; }
	void recordStep(FluidDomain.StepStats stats, double milliseconds) {
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

	void replaceDevicePorts(List<mmm.fluid.FluidStateAccess.DevicePort> ports, List<int[]> connections) {
		boolean changed = !finiteThermalPorts.equals(ports) || activityConnections.size() != connections.size();
		for (int i=0; !changed && i<connections.size(); i++) changed = !java.util.Arrays.equals(activityConnections.get(i),connections.get(i));
		if (!changed) return;
		domain().replaceActivityConnections(connections);
		finiteThermalPorts = List.copyOf(ports);
		activityConnections = connections.stream().map(int[]::clone).toList();
	}

	/** Returns immutable region-owned device truth, binding a new empty inventory to this layout. */
	boolean hasFiniteThermalDevices() { return finiteThermalDevices != null && !finiteThermalDevices.devices().isEmpty(); }

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
	}

	public record FiniteThermalCellBinding(int ordinal, int cellX, int cellY, int cellZ) {
		public FiniteThermalCellBinding {
			if (ordinal < 0) throw new IllegalArgumentException("Finite thermal cell binding ordinal is invalid");
		}
	}

	FluidRegion finiteDeviceCandidate(FiniteThermalDeviceInventory inventory, ThermalRegistry registry) {
		inventory.requireCompatible(registry);
		var candidate = lifecycleCandidate(FluidDomain.restoreAuthoritativeState(domain().captureAuthoritativeState()), paused());
		candidate.finiteThermalDevices = inventory;
		return candidate;
	}

	void adoptFiniteDeviceCandidate(FluidRegion candidate, long publishedGeneration) {
		if (!id.equals(candidate.id)) throw new IllegalArgumentException("Finite device candidate belongs to another region");
		adoptPublishedPrivateDomain(candidate.domain, publishedGeneration, candidate.paused());
		finiteThermalDevices = candidate.finiteThermalDevices;
		footprint = candidate.footprint;
		wakeForActivity();
	}

	/** Atomically applies one already-planned body/cell exchange inside this live region. */
	public boolean commitFiniteThermalExchange(
			String providerId, String objectId, FiniteThermalExchange.Plan exchange,
			List<FiniteThermalCellBinding> bindings, ThermalRegistry registry
	) {
		return commitFiniteThermalExchange(providerId, objectId, exchange, bindings, registry, null);
	}

	public boolean commitFiniteThermalPortExchange(String providerId, String objectId,
			FiniteThermalExchange.Plan exchange, List<FluidDomain.ThermalPortRequest> ports, ThermalRegistry registry) {
		var bindings = exchange.initialContacts().stream().map(contact -> {
			int cell = contact.ordinal();
			return new FiniteThermalCellBinding(cell, cell % sizeX, cell / sizeX % sizeY, cell / (sizeX * sizeY));
		}).toList();
		return commitFiniteThermalExchange(providerId, objectId, exchange, bindings, registry, List.copyOf(ports));
	}

	private boolean commitFiniteThermalExchange(
			String providerId, String objectId, FiniteThermalExchange.Plan exchange,
			List<FiniteThermalCellBinding> bindings, ThermalRegistry registry, List<FluidDomain.ThermalPortRequest> ports
	) {
		Objects.requireNonNull(exchange, "exchange");
		Objects.requireNonNull(bindings, "bindings");
		Objects.requireNonNull(registry, "registry");
		if (domain == null || !domain.hasThermalState()) {
			throw new IllegalStateException("Finite thermal exchange requires a loaded thermal region");
		}
		if (lifecycle.operatorPaused() || lifecycle.availabilityFailure() != null
				|| thermalActivity != null && thermalActivity.state() == ThermalActivity.State.PAUSED) {
			throw new IllegalStateException("Finite thermal exchange cannot modify a paused or locked region");
		}
		if (!exchange.changed()) return false;
		ArrayList<FiniteThermalCellBinding> canonical = new ArrayList<>(bindings);
		if (canonical.size() != exchange.contacts().size() || canonical.stream().anyMatch(Objects::isNull)) {
			throw new IllegalArgumentException("Finite thermal cell bindings do not match the exchange plan");
		}
		canonical.sort(Comparator.comparingInt(FiniteThermalCellBinding::ordinal));
		ArrayList<FluidDomain.ThermalCellEnergyDelta> deltas = new ArrayList<>(canonical.size());
		ArrayList<FluidDomain.ThermalContactRequest> contacts = new ArrayList<>(canonical.size());
		for (int index = 0; index < canonical.size(); index++) {
			FiniteThermalCellBinding binding = canonical.get(index);
			FiniteThermalExchange.ContactDelta contact = exchange.contacts().get(index);
			if (binding.ordinal() != contact.ordinal()) {
				throw new IllegalArgumentException("Finite thermal cell binding ordinal changed");
			}
			deltas.add(new FluidDomain.ThermalCellEnergyDelta(binding.cellX(), binding.cellY(), binding.cellZ(),
					contact.internalEnergyDeltaJ(),contact.carriers()));
			contacts.add(new FluidDomain.ThermalContactRequest(binding.ordinal(),
					binding.cellX(), binding.cellY(), binding.cellZ(),
					exchange.initialContacts().get(index).conductanceWPerK()));
		}
		if (!(ports == null ? domain.finiteThermalContacts(contacts) : domain.finiteThermalPortContacts(ports))
				.equals(exchange.initialContacts())) {
			throw new IllegalArgumentException("Finite thermal fluid contact plan is stale");
		}
		FiniteThermalDeviceInventory current = finiteThermalDevices(registry.fingerprint());
		FiniteThermalDeviceInventory candidate = current.applyExchange(providerId, objectId, exchange);
		candidate.requireCompatible(registry);
		FluidDomain.FiniteThermalCellPlan cellPlan = domain.planFiniteThermalCellExchange(deltas);
		long nextSolverGeneration = Math.addExact(solverGeneration, 1L);
		domain.commitFiniteThermalCellExchange(cellPlan);
		finiteThermalDevices = candidate;
		solverGeneration = nextSolverGeneration;
		wakeForActivity();
		wakeThermal(ThermalActivity.WakeReason.FINITE_DEVICE_EXCHANGE);
		return true;
	}

	public PersistedRuntime persistedRuntime() {
		FluidRegionLifecycle.State resumeState = lifecycle.state() == FluidRegionLifecycle.State.UNLOADED
				? unloadedResumeState : lifecycle.state();
		return new PersistedRuntime(resumeState, lifecycle.operatorPaused(), entityPhysicsEnabled,
				debugOverlays, stepIntervalTicks, tick);
	}

	/** Creates an unregistered private candidate carrying the same persistent identity and runtime intent. */
	FluidRegion lifecycleCandidate(FluidDomain candidateDomain, boolean operatorPaused) {
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
		candidate.finiteThermalDevices = finiteThermalDevices;
		candidate.lifecycle.initializeLoaded(runtime.resumeState(), operatorPaused, runtime.stepIntervalTicks());
		return candidate;
	}

	FluidRegion ownershipCandidate(FluidDomain domain, FluidRegionFootprint footprint) {
		FluidRegion candidate = lifecycleCandidate(domain, true);
		candidate.footprint = footprint;
		return candidate;
	}

	void adoptOwnershipCandidate(FluidRegion candidate, long generation) {
		adoptPublishedPrivateDomain(candidate.domain(), generation, true);
		footprint = candidate.footprint;
	}

	/** Adopts the exact solver which was already encoded and published as the lifecycle HEAD. */
	void adoptLifecycleCandidateDomain(
			FluidDomain candidateDomain, long publishedGeneration, boolean operatorPaused
	) {
		adoptPublishedPrivateDomain(candidateDomain, publishedGeneration, operatorPaused);
	}

	/** Adopts a HEAD-published private container successor and invalidates every older solver candidate. */
	void adoptContainerCandidateDomain(
			FluidDomain candidateDomain, long publishedGeneration, boolean operatorPaused
	) {
		adoptPublishedPrivateDomain(candidateDomain, publishedGeneration, operatorPaused);
	}

	private void adoptPublishedPrivateDomain(
			FluidDomain candidateDomain, long publishedGeneration, boolean operatorPaused
	) {
		Objects.requireNonNull(candidateDomain, "candidateDomain");
		long nextSolverGeneration = Math.addExact(solverGeneration, 1L);
		if (!controllerManaged() || !solverLoaded() || publishedGeneration <= checkpointGeneration
				|| candidateDomain.sizeX() != sizeX || candidateDomain.sizeY() != sizeY
				|| candidateDomain.sizeZ() != sizeZ
				|| !candidateDomain.config().equals(solverConfig)) {
			throw new IllegalStateException("Published private candidate is incompatible with the live region");
		}
		releaseResidentState();
		domain = candidateDomain;
		solverGeneration = nextSolverGeneration;
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
		releaseResidentState();
		domain = null;
		boolean unloaded = lifecycle.requestUnload(true, true);
		publishCommittedObservation();
		return unloaded;
	}

	/** Restores the complete cell/MAC generation while retaining the persisted lifecycle intent. */
	public void restoreAuthoritativeSolver(mmm.fluid.FluidDomainState state,long generation) {
		if(solverLoaded()||lifecycle.state()!=FluidRegionLifecycle.State.UNLOADED)throw new IllegalStateException("Only an unloaded region can restore its solver");
		if(generation!=checkpointGeneration)throw new IllegalArgumentException("Checkpoint generation changed while restoring");
		if(state.sizeX()!=sizeX()||state.sizeY()!=sizeY()||state.sizeZ()!=sizeZ()||!state.config().equals(solverConfig()))throw new IllegalArgumentException("Checkpoint solver geometry changed");
		domain=FluidDomain.restoreAuthoritativeState(state);collisionRefreshRequired=true;lifecycle.restoreFromUnloaded(unloadedResumeState,stepIntervalTicks);
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

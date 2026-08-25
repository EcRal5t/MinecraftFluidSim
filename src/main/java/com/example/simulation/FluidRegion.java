package com.example.simulation;

import com.example.fluid.FlipFluidDomain;
import com.example.fluid.FlipChemicalParticleState;
import com.example.granular.GranularRegistry;
import com.example.granular.GranularStateSnapshot;
import com.example.fluid.FlipThermalParticleState;
import com.example.fluid.air.GasRegistry;
import com.example.fluid.air.GasStateSnapshot;
import com.example.thermal.ThermalRegistry;
import com.example.fluid.FlipParticleState;
import com.example.fluid.FlipMaterialParticleState;
import com.example.fluid.FluidSolverConfig;
import com.example.chemistry.ChemicalRegistry;
import com.example.simulation.controller.FluidRegionFootprint;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Objects;
import java.util.UUID;

/** Spatial identity, solver ownership, hard marker budget and independent runtime state for one region. */
public final class FluidRegion {
	private final UUID id;
	private final ResourceKey<Level> dimension;
	private final BlockPos origin;
	private final int sizeX;
	private final int sizeY;
	private final int sizeZ;
	private final FluidSolverConfig solverConfig;
	private final long reservedMarkerCapacity;
	private final FluidRegionFootprint footprint;
	private final long generation;
	private final FluidRegionLifecycle lifecycle = new FluidRegionLifecycle();
	private FlipFluidDomain domain;
	private long checkpointGeneration;
	private int checkpointMarkerCount;
	private float checkpointVolume;
	private FluidRegionLifecycle.State unloadedResumeState = FluidRegionLifecycle.State.ACTIVE;
	private long snapshotSequence;
	private long tick;
	private double lastStepMilliseconds;
	private FlipFluidDomain.StepStats lastStats;
	private CollisionStats lastCollisionStats = CollisionStats.EMPTY;
	private boolean collisionRefreshRequired;
	private boolean entityPhysicsEnabled = true;
	private boolean debugOverlays;
	private int lastAffectedEntities;
	private int stepIntervalTicks = 1;
	private int hardOverrunStreak;
	private int recoveryStreak;
	private String chemistryBlockFailure;
	private String granularBlockFailure;
	private String thermalBlockFailure;
	private ThermalActivity thermalActivity;

	public FluidRegion(UUID id, ResourceKey<Level> dimension, BlockPos origin, FlipFluidDomain domain, long reservedMarkerCapacity) {
		this(id, dimension, origin, domain, reservedMarkerCapacity, null);
	}

	public FluidRegion(
			UUID id,
			ResourceKey<Level> dimension,
			BlockPos origin,
			FlipFluidDomain domain,
			long reservedMarkerCapacity,
			FluidRegionFootprint footprint
	) {
		this(id, dimension, origin, Objects.requireNonNull(domain, "domain").sizeX(), domain.sizeY(), domain.sizeZ(),
				domain.config(), reservedMarkerCapacity, footprint, 1L, domain);
	}

	private FluidRegion(
			UUID id,
			ResourceKey<Level> dimension,
			BlockPos origin,
			int sizeX,
			int sizeY,
			int sizeZ,
			FluidSolverConfig solverConfig,
			long reservedMarkerCapacity,
			FluidRegionFootprint footprint,
			long generation,
			FlipFluidDomain domain
	) {
		this.id = Objects.requireNonNull(id, "id");
		this.dimension = Objects.requireNonNull(dimension, "dimension");
		this.origin = Objects.requireNonNull(origin, "origin").immutable();
		if (sizeX < 2 || sizeY < 2 || sizeZ < 2) throw new IllegalArgumentException("Region dimensions must be at least two solver cells");
		this.sizeX = sizeX;
		this.sizeY = sizeY;
		this.sizeZ = sizeZ;
		this.solverConfig = Objects.requireNonNull(solverConfig, "solverConfig");
		if (reservedMarkerCapacity <= 0L || reservedMarkerCapacity > Integer.MAX_VALUE) {
			throw new IllegalArgumentException("Reserved marker capacity must fit a positive CPU marker budget");
		}
		if (domain != null && reservedMarkerCapacity != domain.maximumParticleCount()) {
			throw new IllegalArgumentException("Reserved marker capacity must equal the domain hard marker budget");
		}
		if (generation <= 0L) throw new IllegalArgumentException("Region generation must be positive");
		this.reservedMarkerCapacity = reservedMarkerCapacity;
		this.footprint = footprint;
		this.generation = generation;
		this.domain = domain;
		if (domain != null && footprint != null) domain.sealDomainBoundary();
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
			long reservedMarkerCapacity,
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
				reservedMarkerCapacity, footprint, generation, null);
		region.checkpointGeneration = checkpointGeneration;
		if (checkpointMarkerCount < 0 || checkpointMarkerCount > reservedMarkerCapacity
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
		region.lifecycle.initializeUnloaded();
		return region;
	}

	public UUID id() { return id; }
	public ResourceKey<Level> dimension() { return dimension; }
	public BlockPos origin() { return origin; }
	public int sizeX() { return sizeX; }
	public int sizeY() { return sizeY; }
	public int sizeZ() { return sizeZ; }
	public FluidSolverConfig solverConfig() { return solverConfig; }
	public FlipFluidDomain domain() {
		if (domain == null) throw new IllegalStateException("Region solver is unloaded");
		return domain;
	}
	public boolean solverLoaded() { return domain != null; }
	public long reservedMarkerCapacity() { return reservedMarkerCapacity; }
	public FluidRegionFootprint footprint() { return footprint; }
	public boolean controllerManaged() { return footprint != null; }
	public long generation() { return generation; }
	public long checkpointGeneration() { return checkpointGeneration; }
	public int checkpointMarkerCount() { return checkpointMarkerCount; }
	public float checkpointVolume() { return checkpointVolume; }
	public FluidRegionLifecycle lifecycle() { return lifecycle; }
	public long reservedCellCapacity() {
		return Math.multiplyExact(Math.multiplyExact((long) sizeX, sizeY), sizeZ);
	}

	boolean spatiallyOverlaps(FluidRegion other) {
		if (!dimension.equals(other.dimension)) return false;
		return overlaps(origin.getX(), maximumWorldX(), other.origin.getX(), other.maximumWorldX())
				&& overlaps(origin.getY(), maximumWorldY(), other.origin.getY(), other.maximumWorldY())
				&& overlaps(origin.getZ(), maximumWorldZ(), other.origin.getZ(), other.maximumWorldZ());
	}

	private double maximumWorldX() { return origin.getX() + sizeX * solverConfig.cellSize(); }
	private double maximumWorldY() { return origin.getY() + sizeY * solverConfig.cellSize(); }
	private double maximumWorldZ() { return origin.getZ() + sizeZ * solverConfig.cellSize(); }
	private static boolean overlaps(double firstMin, double firstMax, double secondMin, double secondMax) {
		return firstMin < secondMax && secondMin < firstMax;
	}

	boolean paused() { return lifecycle.operatorPaused(); }
	void setPaused(boolean value) { lifecycle.setOperatorPaused(value, stepIntervalTicks); }
	boolean canStep() { return domain != null && lifecycle.canStep(); }
	void wakeForActivity() {
		lifecycle.wakeForActivity(stepIntervalTicks);
		wakeThermal(ThermalActivity.WakeReason.CARRIER_MOTION);
	}
	void enableThermalActivity() {
		if (thermalActivity == null) thermalActivity = new ThermalActivity();
	}
	boolean canStepThermal() {
		return domain != null && domain.hasThermalState() && thermalActivity != null && !lifecycle.operatorPaused()
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
		setPaused(true);
		setAvailabilityFailure(thermalBlockFailure);
	}
	long nextSnapshotSequence() {
		if (snapshotSequence == Long.MAX_VALUE) throw new IllegalStateException("Snapshot sequence exhausted");
		return ++snapshotSequence;
	}
	long incrementTick() { return ++tick; }
	long tick() { return tick; }
	double lastStepMilliseconds() { return lastStepMilliseconds; }
	FlipFluidDomain.StepStats lastStats() { return lastStats; }
	void recordStep(FlipFluidDomain.StepStats stats, double milliseconds) {
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
	boolean entityPhysicsEnabled() { return entityPhysicsEnabled; }
	void setEntityPhysicsEnabled(boolean enabled) {
		entityPhysicsEnabled = enabled;
		if (!enabled) lastAffectedEntities = 0;
	}
	boolean debugOverlays() { return debugOverlays; }
	void setDebugOverlays(boolean enabled) { debugOverlays = enabled; }
	int lastAffectedEntities() { return lastAffectedEntities; }
	void setLastAffectedEntities(int value) { lastAffectedEntities = value; }
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
	void setAvailabilityFailure(String value) {
		if (value == null) lifecycle.clearAvailabilityBlock();
		else lifecycle.blockAvailability(value);
	}
	String chemistryBlockFailure() { return chemistryBlockFailure; }
	void blockChemistry(String failure) {
		chemistryBlockFailure = Objects.requireNonNull(failure, "failure");
		setPaused(true);
		setAvailabilityFailure(failure);
	}
	String granularBlockFailure() { return granularBlockFailure; }
	void blockGranular(String failure) {
		granularBlockFailure = Objects.requireNonNull(failure, "failure");
		setPaused(true);
		setAvailabilityFailure(failure);
	}
	String thermalBlockFailure() { return thermalBlockFailure; }

	public PersistedRuntime persistedRuntime() {
		FluidRegionLifecycle.State resumeState = lifecycle.state() == FluidRegionLifecycle.State.UNLOADED
				? unloadedResumeState : lifecycle.state();
		return new PersistedRuntime(resumeState, entityPhysicsEnabled, debugOverlays, stepIntervalTicks, tick);
	}

	/** Records a complete durable generation while keeping the live solver allocated. */
	public boolean recordPublishedCheckpoint(long publishedGeneration) {
		if (!controllerManaged() || domain == null || publishedGeneration <= checkpointGeneration) return false;
		checkpointMarkerCount = domain.particleCount();
		checkpointVolume = domain.totalVolume();
		checkpointGeneration = publishedGeneration;
		return true;
	}

	/** Releases the solver only after the caller has durably published the supplied generation. */
	public boolean releaseSolverAfterCheckpoint(long publishedGeneration) {
		if (!controllerManaged() || domain == null || publishedGeneration <= 0L || publishedGeneration != checkpointGeneration) return false;
		unloadedResumeState = lifecycle.state() == FluidRegionLifecycle.State.UNLOADED
				? FluidRegionLifecycle.State.ACTIVE : lifecycle.state();
		domain = null;
		return lifecycle.requestUnload(true, true);
	}

	/** Restores exact marker truth into a fresh solver. The region remains operator-paused. */
	public void restoreSolver(FlipParticleState state, long loadedCheckpointGeneration) {
		Objects.requireNonNull(state, "state");
		if (domain != null || lifecycle.state() != FluidRegionLifecycle.State.UNLOADED) {
			throw new IllegalStateException("Only an unloaded region can restore a solver");
		}
		if (loadedCheckpointGeneration != checkpointGeneration) {
			throw new IllegalArgumentException("Checkpoint generation changed while restoring");
		}
		FlipFluidDomain restored = freshDomain();
		restored.restoreParticleState(state);
		domain = restored;
		collisionRefreshRequired = true;
		lifecycle.restoreFromUnloaded(unloadedResumeState, stepIntervalTicks);
	}

	/** Restores exact multi-material marker truth into a fresh solver. The region remains operator-paused. */
	public void restoreMaterialSolver(FlipMaterialParticleState state, long loadedCheckpointGeneration) {
		Objects.requireNonNull(state, "state");
		if (domain != null || lifecycle.state() != FluidRegionLifecycle.State.UNLOADED) {
			throw new IllegalStateException("Only an unloaded region can restore a solver");
		}
		if (loadedCheckpointGeneration != checkpointGeneration) {
			throw new IllegalArgumentException("Checkpoint generation changed while restoring");
		}
		FlipFluidDomain restored = freshDomain();
		restored.restoreMaterialParticleState(state);
		domain = restored;
		collisionRefreshRequired = true;
		lifecycle.restoreFromUnloaded(unloadedResumeState, stepIntervalTicks);
	}

	/** Restores exact material/species/indicator truth into a fresh CPU solver. */
	public void restoreChemicalSolver(
			FlipChemicalParticleState state, ChemicalRegistry registry, long loadedCheckpointGeneration
	) {
		Objects.requireNonNull(state, "state");
		Objects.requireNonNull(registry, "registry");
		if (domain != null || lifecycle.state() != FluidRegionLifecycle.State.UNLOADED) {
			throw new IllegalStateException("Only an unloaded region can restore a solver");
		}
		if (loadedCheckpointGeneration != checkpointGeneration) {
			throw new IllegalArgumentException("Checkpoint generation changed while restoring");
		}
		FlipFluidDomain restored = freshDomain();
		restored.restoreChemicalParticleState(state, registry);
		domain = restored;
		collisionRefreshRequired = true;
		lifecycle.restoreFromUnloaded(unloadedResumeState, stepIntervalTicks);
	}

	/** Completes a v5 restore after the matching liquid/material generation has been reconstructed. */
	public void restoreGranularState(
			GranularStateSnapshot state, GranularRegistry registry, long loadedCheckpointGeneration
	) {
		Objects.requireNonNull(state, "state"); Objects.requireNonNull(registry, "registry");
		if (domain == null || loadedCheckpointGeneration != checkpointGeneration || !domain.hasMaterialState()) {
			throw new IllegalStateException("Granular restore requires the matching loaded material generation");
		}
		domain.restoreGranularState(state, registry, domain.materialRegistry());
	}

	/** Completes a v4/v6/v7 gas restore after the matching liquid/material generation. */
	public void restoreGasState(GasStateSnapshot state, GasRegistry registry, long loadedCheckpointGeneration) {
		Objects.requireNonNull(state, "state"); Objects.requireNonNull(registry, "registry");
		if (domain == null || loadedCheckpointGeneration != checkpointGeneration || !domain.hasMaterialState()) {
			throw new IllegalStateException("Gas restore requires the matching loaded material generation");
		}
		domain.restoreGasState(state, registry);
	}

	/** Completes a current thermal restore after the matching liquid/material generation has been reconstructed. */
	public void restoreThermalState(
			FlipThermalParticleState state, ThermalRegistry registry, long loadedCheckpointGeneration
	) {
		restoreThermalState(state, registry, loadedCheckpointGeneration,
				com.example.thermal.ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS);
	}

	public void restoreThermalState(
			FlipThermalParticleState state, ThermalRegistry registry, long loadedCheckpointGeneration,
			int maximumTransportSubsteps
	) {
		Objects.requireNonNull(state, "state"); Objects.requireNonNull(registry, "registry");
		if (domain == null || loadedCheckpointGeneration != checkpointGeneration || !domain.hasMaterialState()) {
			throw new IllegalStateException("Thermal restore requires the matching loaded material generation");
		}
		domain.restoreThermalParticleState(state, registry, maximumTransportSubsteps);
	}

	private FlipFluidDomain freshDomain() {
		FlipFluidDomain restored = new FlipFluidDomain(
				sizeX, sizeY, sizeZ, solverConfig, (int) reservedMarkerCapacity);
		if (footprint != null) restored.sealDomainBoundary();
		return restored;
	}

	public record PersistedRuntime(
			FluidRegionLifecycle.State resumeState,
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
}

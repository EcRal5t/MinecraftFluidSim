package com.example.simulation;

import com.example.fluid.FlipFluidDomain;
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
	private final FlipFluidDomain domain;
	private final long reservedMarkerCapacity;
	private final FluidRegionFootprint footprint;
	private final long generation = 1L;
	private final FluidRegionLifecycle lifecycle = new FluidRegionLifecycle();
	private long snapshotSequence;
	private long tick;
	private double lastStepMilliseconds;
	private FlipFluidDomain.StepStats lastStats;
	private CollisionStats lastCollisionStats = CollisionStats.EMPTY;
	private boolean entityPhysicsEnabled = true;
	private boolean debugOverlays;
	private int lastAffectedEntities;
	private int stepIntervalTicks = 1;
	private int hardOverrunStreak;
	private int recoveryStreak;

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
		this.id = Objects.requireNonNull(id, "id");
		this.dimension = Objects.requireNonNull(dimension, "dimension");
		this.origin = Objects.requireNonNull(origin, "origin").immutable();
		this.domain = Objects.requireNonNull(domain, "domain");
		if (reservedMarkerCapacity <= 0L || reservedMarkerCapacity > Integer.MAX_VALUE) {
			throw new IllegalArgumentException("Reserved marker capacity must fit a positive CPU marker budget");
		}
		if (reservedMarkerCapacity != domain.maximumParticleCount()) {
			throw new IllegalArgumentException("Reserved marker capacity must equal the domain hard marker budget");
		}
		this.reservedMarkerCapacity = reservedMarkerCapacity;
		this.footprint = footprint;
		if (footprint != null && !origin.equals(footprint.minimumInterior())) {
			throw new IllegalArgumentException("A controller region origin must equal its minimum interior block");
		}
	}

	public UUID id() { return id; }
	public ResourceKey<Level> dimension() { return dimension; }
	public BlockPos origin() { return origin; }
	public FlipFluidDomain domain() { return domain; }
	public long reservedMarkerCapacity() { return reservedMarkerCapacity; }
	public FluidRegionFootprint footprint() { return footprint; }
	public boolean controllerManaged() { return footprint != null; }
	public long generation() { return generation; }
	public FluidRegionLifecycle lifecycle() { return lifecycle; }
	public long reservedCellCapacity() {
		return Math.multiplyExact(Math.multiplyExact((long) domain.sizeX(), domain.sizeY()), domain.sizeZ());
	}

	boolean spatiallyOverlaps(FluidRegion other) {
		if (!dimension.equals(other.dimension)) return false;
		return overlaps(origin.getX(), maximumWorldX(), other.origin.getX(), other.maximumWorldX())
				&& overlaps(origin.getY(), maximumWorldY(), other.origin.getY(), other.maximumWorldY())
				&& overlaps(origin.getZ(), maximumWorldZ(), other.origin.getZ(), other.maximumWorldZ());
	}

	private double maximumWorldX() { return origin.getX() + domain.sizeX() * domain.config().cellSize(); }
	private double maximumWorldY() { return origin.getY() + domain.sizeY() * domain.config().cellSize(); }
	private double maximumWorldZ() { return origin.getZ() + domain.sizeZ() * domain.config().cellSize(); }
	private static boolean overlaps(double firstMin, double firstMax, double secondMin, double secondMax) {
		return firstMin < secondMax && secondMin < firstMax;
	}

	boolean paused() { return lifecycle.operatorPaused(); }
	void setPaused(boolean value) { lifecycle.setOperatorPaused(value, stepIntervalTicks); }
	boolean canStep() { return lifecycle.canStep(); }
	void wakeForActivity() { lifecycle.wakeForActivity(stepIntervalTicks); }
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
	CollisionStats lastCollisionStats() { return lastCollisionStats; }
	void setLastCollisionStats(CollisionStats value) { lastCollisionStats = Objects.requireNonNull(value, "value"); }
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

	public record CollisionStats(int changedCells, float displacedCellVolume, float escapedCellVolume, boolean markerBudgetLimited) {
		static final CollisionStats EMPTY = new CollisionStats(0, 0.0F, 0.0F, false);
	}
}

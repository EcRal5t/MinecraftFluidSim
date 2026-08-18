package com.example.simulation;

import com.example.fluid.FlipFluidDomain;
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
	private boolean paused = true;
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
	}

	public UUID id() { return id; }
	public ResourceKey<Level> dimension() { return dimension; }
	public BlockPos origin() { return origin; }
	public FlipFluidDomain domain() { return domain; }
	public long reservedMarkerCapacity() { return reservedMarkerCapacity; }
	public long reservedCellCapacity() {
		return Math.multiplyExact(Math.multiplyExact((long) domain.sizeX(), domain.sizeY()), domain.sizeZ());
	}

	boolean paused() { return paused; }
	void setPaused(boolean value) { paused = value; }
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
	void setStepIntervalTicks(int value) { stepIntervalTicks = value; }
	int hardOverrunStreak() { return hardOverrunStreak; }
	void setHardOverrunStreak(int value) { hardOverrunStreak = value; }
	int recoveryStreak() { return recoveryStreak; }
	void setRecoveryStreak(int value) { recoveryStreak = value; }

	public record CollisionStats(int changedCells, float displacedCellVolume, float escapedCellVolume, boolean markerBudgetLimited) {
		static final CollisionStats EMPTY = new CollisionStats(0, 0.0F, 0.0F, false);
	}
}

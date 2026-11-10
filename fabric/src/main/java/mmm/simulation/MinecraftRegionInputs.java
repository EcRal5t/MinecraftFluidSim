package mmm.simulation;

import mmm.application.RegionRuntime;
import mmm.thermal.ThermalRegistry;
import mmm.world.MovingBodyFluidCoupling;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Captures region geometry/entity observations and applies the accepted paired entity response. */
final class MinecraftRegionInputs {
	static final double NEUTRAL_SUBMERGENCE = 0.72;
	private static final double TICKS_PER_SECOND = 20.0;
	private static final int COLLISION_REFRESH_INTERVAL_TICKS = 5;

	private final int cellsPerBlock;
	private final double waterDensity;
	private final MotionHistory motion = new MotionHistory();
	private final Map<UUID, List<Interaction>> pendingResponses = new HashMap<>();
	private final ThermalBoundaryResolver thermalBoundaries;
	private WorldThermalEnvironment environment;
	void environment(WorldThermalEnvironment value) { environment = java.util.Objects.requireNonNull(value); }
	private MinecraftWorldUpdates worldUpdates;
	private ExternalMovingStructures externalStructures;
	void externalStructures(ExternalMovingStructures structures) { externalStructures = java.util.Objects.requireNonNull(structures); }
	boolean hasPendingWorldMotion(FluidRegion region) { return worldUpdates != null && worldUpdates.hasPendingMotion(region); }
	void worldUpdates(MinecraftWorldUpdates updates) {
		worldUpdates = java.util.Objects.requireNonNull(updates);
		if (environment != null) environment.worldUpdates(updates);
	}
	private java.util.function.BiFunction<FluidRegion,Long,List<MovingBodyFluidCoupling.Request>> prescribedBodies = (region,tick) -> List.of();
	void prescribedBodies(java.util.function.BiFunction<FluidRegion,Long,List<MovingBodyFluidCoupling.Request>> source) { prescribedBodies=java.util.Objects.requireNonNull(source); }
	private java.util.function.Function<FluidRegion,List<MovingBodyFluidCoupling.Request>> manualPrescribedBodies = region -> List.of();
	void manualPrescribedBodies(java.util.function.Function<FluidRegion,List<MovingBodyFluidCoupling.Request>> source) { manualPrescribedBodies=java.util.Objects.requireNonNull(source); }

	MinecraftRegionInputs(ThermalRegistry thermalRegistry, int cellsPerBlock, double waterDensity) {
		if (cellsPerBlock <= 0) throw new IllegalArgumentException("cells per block must be positive");
		this.cellsPerBlock = cellsPerBlock;
		this.waterDensity = waterDensity;
		this.thermalBoundaries = thermalRegistry == null ? null : new ThermalBoundaryResolver(thermalRegistry);
	}

	boolean boundariesDue(long tick) { return tick % COLLISION_REFRESH_INTERVAL_TICKS == 0L; }
	boolean externalHandoffsDue(FluidRegion region) { return externalStructures!=null && externalStructures.handoffsDue(region); }

	FluidRegion.CollisionStats refreshCollisions(ServerLevel level, FluidRegion region) {
		FluidRegion.CollisionStats collisions;
		try { collisions = FluidRegionWorldInteraction.refreshCollisions(level, region, cellsPerBlock, worldUpdates, externalStructures, environment); }
		catch(ExternalMovingStructures.InputRejectedException waiting) {
			region.requireCollisionRefresh();
			if(externalStructures!=null)externalStructures.rejected(region.id(),waiting.getMessage());
			throw waiting;
		}
		if (collisions.changedCells() > 0 || collisions.displacedCellVolume() > 0.0F
				|| collisions.escapedCellVolume() > 0.0F || collisions.markerBudgetLimited()) {
			region.setLastCollisionStats(collisions);
		}
		region.markCollisionRefreshed();
		if (collisions.changedCells() > 0) {
			region.markDevicePortsDirty();
			region.wakeForActivity();
			region.wakeThermal(ThermalActivity.WakeReason.BOUNDARY_CHANGE);
		}
		return collisions;
	}

	boolean refreshThermalBoundary(ServerLevel level, FluidRegion region) {
		if (thermalBoundaries == null) return false;
		boolean walls = environment != null && environment.refresh(level, region, cellsPerBlock);
		return thermalBoundaries.refresh(level, region, cellsPerBlock) || walls;
	}

	TickInputs captureTick(ServerLevel level, FluidRegion region, long tick, boolean thermalEnabled) {
		return capture(level, region, tick, thermalEnabled, false);
	}

	TickInputs captureManualStep(ServerLevel level, FluidRegion region, boolean thermalEnabled) {
		return capture(level, region, region.tick(), thermalEnabled, true);
	}

	private TickInputs capture(ServerLevel level, FluidRegion region, long tick, boolean thermalEnabled, boolean manual) {
		MinecraftGroupAvailability.prepare(level, region);
		var external = externalStructures == null ? null : externalStructures.capture(region);
		if (external != null && external.advancing()) region.wakeForActivity();
		List<Observation> observations = region.entityPhysicsEnabled() ? captureEntities(level, region, external == null ? java.util.Set.of() : external.excludedEntities()) : List.of();
		List<BodyMotion> motions = new ArrayList<>();
		for (var observation : observations) if (canAffect(observation.entity()))
			motions.add(new BodyMotion(observation.entity().getUUID(), observation.bounds(), 0, observation.displacement()));
		List<MovingBodyFluidCoupling.Request> requestedBodies = new ArrayList<>(manual
				? manualBodies(motions, manualPrescribedBodies.apply(region)) : bodyObservations(motions));
		if (!manual) requestedBodies.addAll(prescribedBodies.apply(region, tick));
		for(var body:region.entityBodyState().bodies())if(PipeCollisionBodies.isPipeBody(body.bodyId()))requestedBodies.add(body.stationary());
		if (worldUpdates != null) requestedBodies.addAll(worldUpdates.targetBodies(region));
		if (external != null) {
			try {
				integrateExternal(region, external, requestedBodies, bounds -> bodyAvailable(region, bounds));
			} catch (ExternalMovingStructures.InputRejectedException failure) {
				externalStructures.rejected(region.id(), failure.getMessage());
				throw failure;
			}
		}
		boolean[] activeCells = region.activityForBodies(requestedBodies);
		if (activeCells != null) requestedBodies = retainBlockedBodies(region, requestedBodies);
		checkBodyBudget(requestedBodies);
		int affectedEntities = captureResponses(region, observations);
		region.setLastEntityCoupling(new FluidRegion.EntityCouplingStats(affectedEntities, 0.0, 0.0, 0.0));
		if (affectedEntities > 0 || !new mmm.fluid.EntityBodyState(requestedBodies).equals(region.entityBodyState())) {
			region.wakeForActivity();
			region.wakeThermal(ThermalActivity.WakeReason.BOUNDARY_CHANGE);
		}
		RegionRuntime runtime = new RegionRuntime(tick, region.stepIntervalTicks(),
				region.hardOverrunStreak(), region.recoveryStreak(), region.canStep(),
				thermalEnabled && region.canStepThermal());
		return new TickInputs(runtime, requestedBodies, affectedEntities, activeCells);
	}

	/** Checks and seeds a whole structure before exposing its target to a motion step. */
	static void integrateExternal(FluidRegion region, ExternalMovingStructures.Capture external,
			List<MovingBodyFluidCoupling.Request> requestedBodies,
			java.util.function.Predicate<MovingBodyFluidCoupling.Bounds> available) {
		var sources = new HashMap<UUID, MovingBodyFluidCoupling.Request>();
		for (var body : external.source()) sources.put(body.bodyId(), body);
		for (var body : external.target()) {
			var before = sources.getOrDefault(body.bodyId(), body).bounds(); var after = body.bounds();
			var sweep = new MovingBodyFluidCoupling.Bounds(
					Math.min(before.minimumX(),after.minimumX()),Math.min(before.minimumY(),after.minimumY()),Math.min(before.minimumZ(),after.minimumZ()),
					Math.max(before.maximumX(),after.maximumX()),Math.max(before.maximumY(),after.maximumY()),Math.max(before.maximumZ(),after.maximumZ()));
			if (!available.test(sweep)) throw new ExternalMovingStructures.InputRejectedException("External structure crosses an unavailable activity group");
		}
		requestedBodies.addAll(external.target());
		if (requestedBodies.size() > MovingBodyFluidCoupling.MAXIMUM_REQUESTS_PER_STEP)
			throw new ExternalMovingStructures.InputRejectedException("External structure exceeds the complete region body budget");
		var existing = region.entityBodyState().bodies();
		var combined = new ArrayList<>(existing);
		var ids = new java.util.HashSet<UUID>();
		for (var body : existing) ids.add(body.bodyId());
		for (var body : external.source()) if (ids.add(body.bodyId())) combined.add(body);
		if (combined.size() > MovingBodyFluidCoupling.MAXIMUM_REQUESTS_PER_STEP)
			throw new ExternalMovingStructures.InputRejectedException("External source exceeds the complete region body budget");
		if (combined.size() != existing.size()) {
			long generation = region.solverGeneration();
			var edit = region.beginHostEdit();
			edit.handoffCollisionBodies(List.of(), new mmm.fluid.EntityBodyState(combined));
			region.adoptCollisionCandidate(generation, edit);
		}
	}

	static boolean bodyAvailable(FluidRegion region, MovingBodyFluidCoupling.Bounds bounds) {
		var origin = region.origin();
		return MinecraftGroupAvailability.boxAvailable(region, new net.minecraft.world.phys.AABB(
				bounds.minimumX()+origin.getX(), bounds.minimumY()+origin.getY(), bounds.minimumZ()+origin.getZ(),
				bounds.maximumX()+origin.getX(), bounds.maximumY()+origin.getY(), bounds.maximumZ()+origin.getZ()));
	}
	static List<MovingBodyFluidCoupling.Request> retainBlockedBodies(FluidRegion region, List<MovingBodyFluidCoupling.Request> requested) {
		return retainBlockedBodies(region.entityBodyState().bodies(), requested, bounds -> bodyAvailable(region, bounds));
	}

	static List<MovingBodyFluidCoupling.Request> retainBlockedBodies(
			List<MovingBodyFluidCoupling.Request> previous, List<MovingBodyFluidCoupling.Request> requested,
			java.util.function.Predicate<MovingBodyFluidCoupling.Bounds> available) {
		var accepted = new ArrayList<MovingBodyFluidCoupling.Request>();
		var ids = new java.util.HashSet<UUID>();
		for (var body : requested) if (available.test(body.bounds())) {
			accepted.add(body); ids.add(body.bodyId());
		}
		for (var body : previous) if (!ids.contains(body.bodyId()) && !available.test(body.bounds()))
			accepted.add(body.stationary());
		return List.copyOf(accepted);
	}

	void finishServerTick() { motion.rotate(); pendingResponses.clear(); }

	void commitEntityResponse(ServerLevel level, FluidRegion region, MovingBodyFluidCoupling.Result result) {
		if (externalStructures != null) externalStructures.accepted(region.id());
		if (worldUpdates != null) worldUpdates.completed(level, region);
		var responses = pendingResponses.remove(region.id());
		if (responses == null) return;
		for (var interaction : responses) {
			if (bodyAvailable(region, interaction.contact().bounds()) && matchesObservation(level, region, interaction) && interaction.entity().isOnFire()
					&& interaction.contact().waterContactVolumeCubicMetres() > 0)
				interaction.entity().clearFire();
		}
		var byId = new HashMap<UUID, Interaction>();
		for (var interaction : responses) byId.put(interaction.entity().getUUID(), interaction);
		for (var impulse : result.bodyImpulses()) {
			var interaction = byId.get(impulse.bodyId());
			if (interaction == null || !bodyAvailable(region, interaction.contact().bounds()) || !matchesObservation(level, region, interaction) || !canAffect(interaction.entity())) continue;
			double mass = interaction.contact().massKilograms();
			Vec3 delta = new Vec3(impulse.impulseXKilogramMetresPerSecond(),
					impulse.impulseYKilogramMetresPerSecond(), impulse.impulseZKilogramMetresPerSecond())
					.scale(1.0 / (mass * TICKS_PER_SECOND));
			interaction.entity().setDeltaMovement(interaction.entity().getDeltaMovement().add(delta));
			interaction.entity().hurtMarked = true;
			if (interaction.contact().submergedFraction() > .15) interaction.entity().resetFallDistance();
		}
	}

	private static boolean matchesObservation(ServerLevel level, FluidRegion region, Interaction interaction) {
		Entity entity = interaction.entity();
		if (entity.level() != level || !entity.isAlive() || entity.isRemoved()) return false;
		var box = entity.getBoundingBox();
		var origin = region.origin();
		var bounds = new MovingBodyFluidCoupling.Bounds(box.minX - origin.getX(), box.minY - origin.getY(),
				box.minZ - origin.getZ(), box.maxX - origin.getX(), box.maxY - origin.getY(), box.maxZ - origin.getZ());
		return bounds.equals(interaction.contact().bounds());
	}

	void discardEntityResponse(FluidRegion region) { pendingResponses.remove(region.id()); if (externalStructures != null) externalStructures.discarded(region.id()); }

	private List<Observation> captureEntities(ServerLevel level, FluidRegion region, java.util.Set<UUID> excluded) {
		pendingResponses.remove(region.id());
		List<Observation> observations = new ArrayList<>();
		for (Entity entity : level.getEntities((Entity) null, regionBounds(region),
				entity -> !excluded.contains(entity.getUUID()) && (canAffect(entity) || entity.isAlive() && !entity.isRemoved() && entity.isOnFire()))) {
			motion.observe(level.dimension(), entity.getUUID(), entity.position());
			var box = entity.getBoundingBox();
			var origin = region.origin();
			var bounds = new MovingBodyFluidCoupling.Bounds(box.minX - origin.getX(), box.minY - origin.getY(),
					box.minZ - origin.getZ(), box.maxX - origin.getX(), box.maxY - origin.getY(), box.maxZ - origin.getZ());
			observations.add(new Observation(entity, bounds,
					motion.displacementSincePreviousTick(level.dimension(), entity.getUUID(), entity.position())));
		}
		return observations;
	}

	private int captureResponses(FluidRegion region, List<Observation> observations) {
		List<Interaction> interactions = new ArrayList<>();
		int affected = 0;
		for (var observation : observations) {
			if (!bodyAvailable(region, observation.bounds())) continue;
			Contact contact = sampleContact(region, observation.entity());
			interactions.add(new Interaction(observation.entity(), contact));
			if (canAffect(observation.entity()) || observation.entity().isOnFire()
					&& contact.waterContactVolumeCubicMetres() > 0) affected++;
		}
		if (!interactions.isEmpty()) pendingResponses.put(region.id(), List.copyOf(interactions));
		return affected;
	}

	/**
	 * Converts frozen body observations into the loader-neutral requests a backend consumes.
	 * Bodies without a previous observation start at zero prescribed velocity; the documented
	 * per-step request budget rejects an oversized complete body set.
	 */
	static List<MovingBodyFluidCoupling.Request> movingBodyRequests(List<BodyMotion> motions) {
		var requests = bodyObservations(motions);
		checkBodyBudget(requests);
		return requests;
	}

	static void checkBodyBudget(List<MovingBodyFluidCoupling.Request> requests) {
		if (requests.size() > MovingBodyFluidCoupling.MAXIMUM_REQUESTS_PER_STEP)
			throw new IllegalArgumentException("Entity target exceeds the complete region body budget");
	}

	static List<MovingBodyFluidCoupling.Request> bodyObservations(List<BodyMotion> motions) {
		List<MovingBodyFluidCoupling.Request> requests = new ArrayList<>(motions.size());
		for (BodyMotion motion : motions) {
			Vec3 displacement = motion.displacementBlocksThisTick() == null ? Vec3.ZERO : motion.displacementBlocksThisTick();
			requests.add(new MovingBodyFluidCoupling.Request(motion.bodyId(), motion.bounds(),
					displacement.x * TICKS_PER_SECOND, displacement.y * TICKS_PER_SECOND,
					displacement.z * TICKS_PER_SECOND));
		}
		return List.copyOf(requests);
	}

	static List<MovingBodyFluidCoupling.Request> manualBodies(List<BodyMotion> observations,
			List<MovingBodyFluidCoupling.Request> prescribed) {
		var requests = new ArrayList<MovingBodyFluidCoupling.Request>();
		for (var observation : observations)
			requests.add(new MovingBodyFluidCoupling.Request(observation.bodyId(), observation.bounds(), 0, 0, 0));
		requests.addAll(prescribed);
		return List.copyOf(requests);
	}

	private static AABB regionBounds(FluidRegion region) {
		BlockPos origin = region.origin();
		float cellSize = region.solverConfig().cellSize();
		return new AABB(
				origin.getX(), origin.getY(), origin.getZ(),
				origin.getX() + region.sizeX() * cellSize,
				origin.getY() + region.sizeY() * cellSize,
				origin.getZ() + region.sizeZ() * cellSize
		);
	}

	private static boolean canAffect(Entity entity) {
		if (!entity.isAlive() || entity.isRemoved() || entity.noPhysics || entity.isPassenger() || entity.isInWater()) {
			return false;
		}
		return !(entity instanceof Player player) || (!player.isSpectator() && !player.getAbilities().flying);
	}

	private Contact sampleContact(FluidRegion region, Entity entity) {
		BlockPos origin = region.origin();
		AABB box = entity.getBoundingBox();
		var sample = region.sampleBody(
				box.minX - origin.getX(), box.minY - origin.getY(), box.minZ - origin.getZ(),
				box.maxX - origin.getX(), box.maxY - origin.getY(), box.maxZ - origin.getZ());
		double displacedVolume = sample.displacedVolumeCubicMetres();

		double bodyVolume = box.getXsize() * box.getYsize() * box.getZsize();
		double submergedFraction = displacedVolume / bodyVolume;
		if (!(Double.isFinite(submergedFraction) && submergedFraction >= 0.0 && submergedFraction <= 1.0 + 1.0E-9)) {
			throw new IllegalStateException("Fluid/body overlap exceeded finite AABB volume");
		}
		submergedFraction = Math.min(1.0, submergedFraction);
		return new Contact(submergedFraction, displacedVolume, sample.waterContactVolumeCubicMetres(),
				new MovingBodyFluidCoupling.Bounds(box.minX - origin.getX(), box.minY - origin.getY(),
						box.minZ - origin.getZ(), box.maxX - origin.getX(), box.maxY - origin.getY(), box.maxZ - origin.getZ()),
				effectiveBodyMass(bodyVolume, region.solverConfig().gravity(), entity.getGravity(), waterDensity));
	}

	/** Host gravity calibrates the effective mass of an externally controlled body. */
	static double effectiveBodyMass(double volume, double solverGravity, double hostGravityBlocksPerTickSquared, double waterDensity) {
		double hostGravity = hostGravityBlocksPerTickSquared * TICKS_PER_SECOND * TICKS_PER_SECOND;
		return volume * waterDensity * NEUTRAL_SUBMERGENCE * (solverGravity > 0 && hostGravity > 0 ? solverGravity / hostGravity : 1.0);
	}

	/** One frozen liquid observation; carries no Minecraft entity reference into core. */
	record BodyMotion(UUID bodyId, MovingBodyFluidCoupling.Bounds bounds, double displacedVolumeCubicMetres, Vec3 displacementBlocksThisTick) { }

	record TickInputs(
			RegionRuntime runtime,
			List<MovingBodyFluidCoupling.Request> movingBodies,
			int affectedEntities,
			boolean[] activeCells
	) { }

	private record Observation(Entity entity, MovingBodyFluidCoupling.Bounds bounds, Vec3 displacement) { }

	private record Interaction(Entity entity, Contact contact) { }

	private record Contact(
			double submergedFraction, double displacedVolumeCubicMetres, double waterContactVolumeCubicMetres,
			MovingBodyFluidCoupling.Bounds bounds, double massKilograms) { }

	/**
	 * Consecutive-tick position history; an entity missed for one tick restarts unobserved.
	 * Minecraft's old-position fields are rewritten before this adapter runs, so they cannot preserve
	 * the client-authoritative displacement of a walking player across server ticks.
	 */
	static final class MotionHistory {
		private record Address(ResourceKey<Level> dimension, UUID body) { }
		private final Map<Address, Vec3> committed = new HashMap<>();
		private final Map<Address, Vec3> observed = new HashMap<>();

		/** Returns null until the entity has been observed on the immediately preceding server tick. */
		Vec3 displacementSincePreviousTick(ResourceKey<Level> dimension, UUID id, Vec3 currentPosition) {
			Vec3 previous = committed.get(new Address(dimension, id));
			return previous == null ? null : currentPosition.subtract(previous);
		}

		void observe(ResourceKey<Level> dimension, UUID id, Vec3 currentPosition) {
			observed.put(new Address(dimension, id), currentPosition);
		}

		void rotate() {
			committed.clear();
			committed.putAll(observed);
			observed.clear();
		}
	}
}

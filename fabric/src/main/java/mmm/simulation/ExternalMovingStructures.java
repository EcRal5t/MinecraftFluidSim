package mmm.simulation;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import mmm.api.motion.MovingStructureApi;
import mmm.fluid.EntityBodyState;
import mmm.world.MovingBodyFluidCoupling;
import net.minecraft.server.MinecraftServer;

/** Registered prescribed structures and their per-region accepted segment progress. */
final class ExternalMovingStructures implements MovingStructureApi {
	static final class InputRejectedException extends IllegalStateException {
		InputRejectedException(String detail) { super(detail); }
		InputRejectedException(String detail, Throwable cause) { super(detail, cause); }
	}
	private static final long PREFIX = 0x4d4d4d5300000000L;
	private final java.util.function.BooleanSupplier serverThread;
	private final Map<String, Provider> providers = new LinkedHashMap<>();
	private final Map<Key, Segment> segments = new HashMap<>();
	private final Map<Address, Status> statuses = new HashMap<>();
	private final Map<UUID, Pending> pending = new HashMap<>();
	private final Map<Key, Long> retiredRevisions = new HashMap<>();
	private final Set<UUID> orphanRegions = new HashSet<>();

	ExternalMovingStructures(MinecraftServer server) { this(server::isSameThread); }
	ExternalMovingStructures(java.util.function.BooleanSupplier serverThread) { this.serverThread = serverThread; }
	@Override public void register(String id, Provider provider) {
		if (!serverThread.getAsBoolean()) throw new IllegalStateException("Structure registration requires the server thread");
		mmm.api.StableId.require(id, 128, "structure provider");
		if (provider == null || providers.putIfAbsent(id, provider) != null)
			throw new IllegalArgumentException("Structure provider is missing or already registered");
	}
	@Override public Status status(String id, UUID region) {
		if (!serverThread.getAsBoolean()) throw new IllegalStateException("Structure status requires the server thread");
		return statuses.getOrDefault(new Address(id, region), orphanRegions.contains(region)
				? new Status(State.REJECTED, "Saved external structure awaits its provider", Map.of())
				: new Status(State.WAITING, "Waiting for a region observation", Map.of()));
	}
	static boolean external(UUID id) { return (id.getMostSignificantBits() & 0xffffffff00000000L) == PREFIX; }
	private static UUID bodyId(String provider, UUID structure, String key) {
		UUID hash = UUID.nameUUIDFromBytes((provider + "\0" + structure + "\0" + key).getBytes(StandardCharsets.UTF_8));
		return new UUID(PREFIX | (hash.getMostSignificantBits() & 0xffffffffL), hash.getLeastSignificantBits());
	}
	private record Address(String provider, UUID region) { }
	private record Key(String provider, UUID region, UUID structure) { }
	private static final class Segment {
		Structure value;
		double fraction;
		Segment(Structure value, double fraction) { this.value = value; this.fraction = fraction; }
	}
	private record Advance(Key key, Segment segment, double fraction, boolean busy, boolean retiring) { }
	private record Pending(List<Advance> advances, Set<String> missingProviders) { }
	record Capture(List<MovingBodyFluidCoupling.Request> source, List<MovingBodyFluidCoupling.Request> target,
			Set<UUID> excludedEntities, boolean advancing) { }

	Capture capture(FluidRegion region) {
		UUID regionId = region.id();
		var candidates = new HashMap<>(segments);
		var accepted = new LinkedHashMap<UUID, MovingBodyFluidCoupling.Request>();
		for (var body : region.entityBodyState().bodies()) if (external(body.bodyId())) accepted.put(body.bodyId(), body.stationary());
		var source = new LinkedHashMap<>(accepted);
		var target = new LinkedHashMap<>(accepted);
		var excluded = new HashSet<UUID>();
		var seen = new HashSet<UUID>();
		var advances = new ArrayList<Advance>();
		var missingProviders = new HashSet<String>();
		var identified = new HashSet<UUID>();
		segments.forEach((key, value) -> { if (key.region.equals(regionId)) {
			if (value.value.entityId() != null) excluded.add(value.value.entityId());
			for (var piece : value.value.pieces()) identified.add(bodyId(key.provider, key.structure, piece.key()));
		} });
		pending.remove(regionId);
		for (var providerEntry : providers.entrySet()) {
			String providerId = providerEntry.getKey();
			Address address = new Address(providerId, regionId);
			List<Structure> frames;
			try { frames = List.copyOf(providerEntry.getValue().sample(region.dimension().identifier().toString())); }
			catch (RuntimeException failure) {
				statuses.put(address, new Status(State.REJECTED, "Provider failed: " + failure.getMessage(), progress(providerId, regionId)));
				continue;
			}
			var structures = new HashSet<UUID>();
			boolean busy = false;
			try {
				for (var frame : frames) {
					validate(frame);
					if (frame.retired() && !outside(region, frame, frame.target()))
						throw new IllegalArgumentException("Retired structure must end outside the region");
					if (!structures.add(frame.id())) throw new IllegalArgumentException("Duplicate structure identity");
					if (frame.entityId() != null) excluded.add(frame.entityId());
					UUID structureId = frame.id();
					for (var piece : frame.pieces()) identified.add(bodyId(providerId, structureId, piece.key()));
					Key key = new Key(providerId, regionId, frame.id());
					Segment segment = candidates.get(key);
					Long retiredRevision = retiredRevisions.get(key);
					if (segment == null && retiredRevision != null && frame.revision() <= retiredRevision) {
						if (frame.retired() && frame.revision() == retiredRevision) continue;
						throw new IllegalArgumentException("Stale retired structure revision");
					}
					if (segment == null && !sweeps(region, frame, frame.source(), frame.target())
							&& frame.pieces().stream().noneMatch(p -> accepted.containsKey(bodyId(providerId, structureId, p.key())))) continue;
					if (segment != null && segment.value.revision() != frame.revision()) {
						if (frame.revision() < segment.value.revision()) throw new IllegalArgumentException("Stale structure revision");
						if (segment.fraction < 1) { busy = true; frame = segment.value; }
						else {
							if (!samePose(segment.value.target(), frame.source()) || !samePieces(segment.value, frame))
								throw new IllegalArgumentException("Next segment is discontinuous or changed its geometry");
							segment = new Segment(frame, 0); candidates.put(key, segment);
						}
					} else if (segment != null && !sameSegment(segment.value, frame)) {
						throw new IllegalArgumentException("A segment revision changed its contents");
					}
					if (segment == null) {
						double restored = restoredFraction(providerId, frame, accepted, region);
						if (restored < 0) {
							if (!outside(region, frame, frame.source())) throw new IllegalArgumentException("Initial structure must start outside the region");
							restored = 0;
						}
						segment = new Segment(frame, restored); candidates.put(key, segment);
					}
					Pose from = interpolate(segment.value, segment.fraction);
					double next = Math.min(1, segment.fraction + region.solverConfig().timeStep() / segment.value.durationSeconds());
					Pose to = interpolate(segment.value, next);
					if (!sweeps(region, segment.value, from, to)
							&& segment.value.pieces().stream().noneMatch(p -> accepted.containsKey(bodyId(providerId, structureId, p.key())))) {
						advances.add(new Advance(key, segment, next, busy, frame.retired() && segment.fraction == 1));
						continue;
					}
					double dt = region.solverConfig().timeStep();
					for (var piece : segment.value.pieces()) {
						UUID id = bodyId(providerId, segment.value.id(), piece.key());
						if (!seen.add(id)) throw new IllegalArgumentException("Duplicate structure piece identity");
						var before = request(region, id, piece.bounds(), from, 0, 0, 0);
						if (!source.containsKey(id)) source.put(id, before);
						if (frame.retired() && segment.fraction == 1 && outside(region, frame, from)) target.remove(id);
						else target.put(id, request(region, id, piece.bounds(), to,
								(to.x()-from.x())/dt, (to.y()-from.y())/dt, (to.z()-from.z())/dt));
					}
					advances.add(new Advance(key, segment, next, busy,
							frame.retired() && segment.fraction == 1 && outside(region, frame, from)));
				}
				boolean missing = candidates.keySet().stream().anyMatch(k -> k.provider.equals(providerId)
						&& k.region.equals(regionId) && !structures.contains(k.structure));
				if (missing) missingProviders.add(providerId);
				var current = progress(providerId, regionId, candidates);
				boolean unfinished = current.values().stream().anyMatch(p -> p.fraction() < 1);
				statuses.put(address, new Status(busy ? State.BUSY : missing || unfinished ? State.LAGGING : State.ACCEPTED,
						busy ? "Earlier segment is still being consumed" : missing ? "Structure missing from provider snapshot" : unfinished ? "Awaiting an accepted motion step" : "Motion segment complete", current));
			} catch (RuntimeException invalid) {
				statuses.put(address, new Status(State.REJECTED, invalid.getMessage(), progress(providerId, regionId)));
				throw new InputRejectedException("Structure provider " + providerId + ": " + invalid.getMessage(), invalid);
			}
		}
		if (!identified.containsAll(accepted.keySet())) {
			orphanRegions.add(regionId);
			throw new InputRejectedException("Saved external structure awaits its provider");
		}
		orphanRegions.remove(regionId);
		pending.put(regionId, new Pending(List.copyOf(advances), Set.copyOf(missingProviders)));
		return new Capture(List.copyOf(source.values()), List.copyOf(target.values()), Set.copyOf(excluded),
				advances.stream().anyMatch(a -> a.fraction > a.segment.fraction));
	}

	void accepted(UUID region) {
		var captured = pending.remove(region);
		if (captured == null) return;
		var advances = captured.advances();
		var changed = new HashSet<String>();
		for (var advance : advances) {
			segments.put(advance.key, advance.segment);
			advance.segment.fraction = advance.fraction;
			if (advance.retiring) {
				segments.remove(advance.key);
				retiredRevisions.put(advance.key, advance.segment.value.revision());
			}
			changed.add(advance.key.provider);
		}
		for (String provider : changed) {
			Address address = new Address(provider, region);
			boolean busy = advances.stream().anyMatch(a -> a.key.provider.equals(provider) && a.busy);
			boolean missing = captured.missingProviders().contains(provider);
			var current = progress(provider, region);
			boolean unfinished = current.values().stream().anyMatch(p -> p.fraction() < 1);
			statuses.put(address, new Status(busy ? State.BUSY : missing || unfinished ? State.LAGGING : State.ACCEPTED,
					busy ? "Earlier segment is still being consumed" : missing ? "Structure missing from provider snapshot" : unfinished ? "Segment is being consumed" : "Motion step accepted", current));
		}
	}
	void discarded(UUID region) {
		if (pending.remove(region) == null) return;
		for (String provider : providers.keySet()) {
			Address address = new Address(provider, region);
			Status old = statuses.get(address);
			if (old != null) statuses.put(address, new Status(old.state(), old.detail(), progress(provider, region)));
		}
	}
	void rejected(UUID region, String detail) {
		pending.remove(region);
		for (String provider : providers.keySet()) statuses.put(new Address(provider, region),
				new Status(State.REJECTED, detail, progress(provider, region)));
	}
	void regionRemoved(FluidRegion region) {
		UUID id = region.id();
		pending.remove(id); orphanRegions.remove(id);
		segments.keySet().removeIf(key -> key.region.equals(id));
		statuses.keySet().removeIf(key -> key.region.equals(id));
		retiredRevisions.keySet().removeIf(key -> key.region.equals(id));
	}
	private Map<UUID, Progress> progress(String provider, UUID region) {
		return progress(provider, region, segments);
	}
	private Map<UUID, Progress> progress(String provider, UUID region, Map<Key, Segment> source) {
		var result = new HashMap<UUID, Progress>();
		source.forEach((key, value) -> { if (key.provider.equals(provider) && key.region.equals(region))
			result.put(key.structure, new Progress(value.value.revision(), interpolate(value.value, value.fraction), value.fraction)); });
		return result;
	}
	private static void validate(Structure frame) {
		if (frame == null || frame.id() == null || frame.source() == null || frame.target() == null
				|| frame.revision() < 0 || !(Double.isFinite(frame.durationSeconds()) && frame.durationSeconds() > 0)
				|| !frame.source().axisAligned() || !frame.target().axisAligned() || frame.pieces().isEmpty())
			throw new IllegalArgumentException("Invalid structure segment or unsupported rotation");
		for (double v : new double[] {frame.source().x(),frame.source().y(),frame.source().z(),frame.target().x(),frame.target().y(),frame.target().z()})
			if (!Double.isFinite(v)) throw new IllegalArgumentException("Non-finite structure position");
		var keys = new HashSet<String>();
		for (var piece : frame.pieces()) {
			if (piece == null || piece.key() == null || piece.key().isBlank() || !keys.add(piece.key()) || piece.bounds() == null)
				throw new IllegalArgumentException("Invalid or duplicate piece");
			var b = piece.bounds();
			new MovingBodyFluidCoupling.Bounds(b.minX(),b.minY(),b.minZ(),b.maxX(),b.maxY(),b.maxZ());
		}
	}
	private static boolean samePose(Pose a, Pose b) { return a.x()==b.x() && a.y()==b.y() && a.z()==b.z() && a.axisAligned()==b.axisAligned(); }
	private static boolean samePieces(Structure a, Structure b) {
		return a.pieces().size()==b.pieces().size() && new HashSet<>(a.pieces()).equals(new HashSet<>(b.pieces()));
	}
	private static boolean sameSegment(Structure a, Structure b) {
		return a.id().equals(b.id()) && java.util.Objects.equals(a.entityId(), b.entityId())
				&& a.revision()==b.revision() && samePose(a.source(),b.source()) && samePose(a.target(),b.target())
				&& a.durationSeconds()==b.durationSeconds() && samePieces(a,b);
	}
	private static Pose interpolate(Structure s, double t) {
		if (t <= 0) return s.source();
		if (t >= 1) return s.target();
		return new Pose(s.source().x()+(s.target().x()-s.source().x())*t,
				s.source().y()+(s.target().y()-s.source().y())*t,
				s.source().z()+(s.target().z()-s.source().z())*t,true);
	}
	private static MovingBodyFluidCoupling.Request request(FluidRegion r, UUID id, Box b, Pose p, double vx, double vy, double vz) {
		var o=r.origin();
		return new MovingBodyFluidCoupling.Request(id, new MovingBodyFluidCoupling.Bounds(
				p.x()+b.minX()-o.getX(),p.y()+b.minY()-o.getY(),p.z()+b.minZ()-o.getZ(),
				p.x()+b.maxX()-o.getX(),p.y()+b.maxY()-o.getY(),p.z()+b.maxZ()-o.getZ()),vx,vy,vz);
	}
	private static boolean outside(FluidRegion r, Structure s, Pose p) {
		var o=r.origin(); double h=r.solverConfig().cellSize();
		for (var piece:s.pieces()) { var b=piece.bounds();
			if (p.x()+b.maxX()>o.getX() && p.x()+b.minX()<o.getX()+r.sizeX()*h
					&& p.y()+b.maxY()>o.getY() && p.y()+b.minY()<o.getY()+r.sizeY()*h
					&& p.z()+b.maxZ()>o.getZ() && p.z()+b.minZ()<o.getZ()+r.sizeZ()*h) return false;
		} return true;
	}
	private static boolean sweeps(FluidRegion r, Structure s, Pose a, Pose b) {
		var o=r.origin(); double h=r.solverConfig().cellSize();
		for (var piece:s.pieces()) { var box=piece.bounds();
			if (Math.max(a.x(),b.x())+box.maxX()>o.getX() && Math.min(a.x(),b.x())+box.minX()<o.getX()+r.sizeX()*h
					&& Math.max(a.y(),b.y())+box.maxY()>o.getY() && Math.min(a.y(),b.y())+box.minY()<o.getY()+r.sizeY()*h
					&& Math.max(a.z(),b.z())+box.maxZ()>o.getZ() && Math.min(a.z(),b.z())+box.minZ()<o.getZ()+r.sizeZ()*h) return true;
		} return false;
	}
	private static double restoredFraction(String provider, Structure frame, Map<UUID, MovingBodyFluidCoupling.Request> accepted, FluidRegion region) {
		int found=0; double fraction=0; var s=frame.source(); var t=frame.target();
		double dx=t.x()-s.x(),dy=t.y()-s.y(),dz=t.z()-s.z(),length=dx*dx+dy*dy+dz*dz;
		for(var piece:frame.pieces()) {
			var body=accepted.get(bodyId(provider,frame.id(),piece.key())); if(body==null)continue;
			var b=piece.bounds(); var o=region.origin();
			double x=body.bounds().minimumX()+o.getX()-b.minX(),y=body.bounds().minimumY()+o.getY()-b.minY(),z=body.bounds().minimumZ()+o.getZ()-b.minZ();
			double f=length==0?1:((x-s.x())*dx+(y-s.y())*dy+(z-s.z())*dz)/length;
			if(f < -1e-8 || f > 1+1e-8 || !sameBounds(body.bounds(),request(region,body.bodyId(),b,interpolate(frame,Math.clamp(f,0,1)),0,0,0).bounds())
					|| found>0 && Math.abs(f-fraction)>1e-8) throw new IllegalArgumentException("Saved structure does not match its segment");
			fraction=f; found++;
		}
		if(found==0)return -1;
		if(found!=frame.pieces().size())throw new IllegalArgumentException("Saved structure is incomplete");
		return Math.clamp(fraction,0,1);
	}
	private static boolean sameBounds(MovingBodyFluidCoupling.Bounds a, MovingBodyFluidCoupling.Bounds b) {
		return Math.abs(a.minimumX()-b.minimumX())<1e-7 && Math.abs(a.minimumY()-b.minimumY())<1e-7 && Math.abs(a.minimumZ()-b.minimumZ())<1e-7
				&& Math.abs(a.maximumX()-b.maximumX())<1e-7 && Math.abs(a.maximumY()-b.maximumY())<1e-7 && Math.abs(a.maximumZ()-b.maximumZ())<1e-7;
	}
}

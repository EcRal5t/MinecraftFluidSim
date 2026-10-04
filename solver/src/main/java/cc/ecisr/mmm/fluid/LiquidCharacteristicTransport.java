package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.units.SignedDyadic;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import cc.ecisr.mmm.fluid.LiquidCharacteristicMap.Time;
import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;

/** Convex preimages of face events transport volume and first moments through one shared velocity field. */
final class LiquidCharacteristicTransport {
	record Fragment(LiquidPolyhedron geometry, SignedDyadic inventory) { }
	private record Flight(Time residual, Fraction speed) { Time remaining() { return residual.scaled(Fraction.of(1).divide(speed)); } }
	private record State(LiquidConvexRegion source, LiquidVelocityRegions.Region region, LiquidCharacteristicMap map, Time remaining, int events) { }
	private record Moved(LiquidConvexRegion source, LiquidCharacteristicMap transform) { }
	private final LiquidVelocityRegions field;
	LiquidCharacteristicTransport(LiquidVelocityTrajectory field) { this.field = new LiquidVelocityRegions(field); }

	List<Fragment> advect(int cell, LiquidPolyhedron source, SignedDyadic inventory, double dt, int maximumPieces, int maximumEvents) {
		if (inventory.signum() <= 0) throw new IllegalArgumentException("Empty characteristic inventory");
		var geometries = advectGeometry(cell, source, dt, maximumPieces, maximumEvents); var weights = new SignedDyadic[geometries.size()];
		for (int i = 0; i < weights.length; i++) weights[i] = SignedDyadic.of(geometries.get(i).moments().volume());
		var allocation = ExactVolumeAllocation.allocateContinuous(inventory, weights).values(); var result = new ArrayList<Fragment>();
		for (int i = 0; i < geometries.size(); i++) {
			if (allocation[i].signum() <= 0) throw new IllegalStateException("Characteristic inventory exceeds its fragment allocation resolution");
			result.add(new Fragment(geometries.get(i), allocation[i]));
		}
		return List.copyOf(result);
	}

	List<LiquidPolyhedron> advectGeometry(int cell, LiquidPolyhedron source, double dt, int maximumPieces, int maximumEvents) {
		if (!(dt >= 0) || !Double.isFinite(dt) || !(source.moments().volume() > 0) || maximumPieces <= 0 || maximumEvents <= 0)
			throw new IllegalArgumentException("Invalid liquid characteristic transport request");
		var queue = new ArrayDeque<State>(); var moved = new ArrayList<Moved>();
		var original = LiquidConvexRegion.of(source); var partition = LiquidConvexRegion.EMPTY.measure();
		for (var region : field.cells(cell)) {
			var local = original;
			for (var polygon : region.geometry.faces()) local = local.clip(polygon.plane());
			if (!local.hasVolume()) continue;
			partition = partition.add(local.measure());
			var velocity = region.velocity();
			queue.add(new State(local, region, LiquidCharacteristicMap.initial(velocity, dt), Time.constant(dt), 0));
			if (queue.size() > maximumPieces) throw new IllegalStateException("Liquid characteristic partition exceeds its work budget");
		}
		if (!partition.same(original.measure()))
			throw new IllegalArgumentException("Liquid geometry has incomplete characteristic support");
		int processed = 0;
		while (!queue.isEmpty()) {
			if (++processed > maximumPieces) throw new IllegalStateException("Liquid characteristic transport exceeds its work budget");
			var state = queue.removeFirst();
			var velocity = state.region.velocity(); int faces = state.region.faces.size(); var flights = new Flight[faces]; var completed = state.source;
			for (int f = 0; f < faces; f++) {
				var plane = state.region.faces.get(f).polygon().plane(); var speed = LiquidCharacteristicMap.speed(plane, velocity);
				var residual = state.map.residual(plane);
				completed = residual.belowZero(completed);
				if (speed.numerator().signum() > 0) {
					flights[f] = new Flight(residual, speed);
				}
			}
			if (completed.hasVolume()) {
				moved.add(new Moved(completed, state.map));
			}
			for (int exit = 0; exit < faces; exit++) if (flights[exit] != null) {
				var crossing = flights[exit]; var remaining = crossing.remaining();
				var outgoing = crossing.residual.scaled(Fraction.of(-1)).belowZero(state.source);
				for (int other = 0; other < faces; other++) if (other != exit && flights[other] != null)
					outgoing = flights[other].residual.scaled(crossing.speed).subtract(crossing.residual.scaled(flights[other].speed)).belowZero(outgoing);
				outgoing = remaining.subtract(state.remaining).belowZero(outgoing);
				var crossingMap = state.map.cross(velocity, new Fraction[] {Fraction.of(0), Fraction.of(0), Fraction.of(0)}, remaining);
				for (var plane : state.region.faces.get(exit).polygon().edgePlanes())
					outgoing = crossingMap.residual(LiquidGeometryPlane.exact(plane)).belowZero(outgoing);
				if (!outgoing.hasVolume()) continue;
				if (state.events >= maximumEvents) throw new IllegalStateException("Liquid characteristic exceeds its face-event budget");
				var next = state.region.faces.get(exit).neighbor();
				if (next == null) throw new IllegalStateException("Liquid geometry leaves its resolved velocity support");
				queue.addLast(new State(outgoing, next, state.map.cross(velocity, next.velocity(), remaining), remaining, state.events + 1));
				if (queue.size() + moved.size() > maximumPieces) throw new IllegalStateException("Liquid characteristic fragments exceed their work budget");
			}
		}
		var completedMeasure = LiquidConvexRegion.EMPTY.measure();
		for (var fragment : moved) completedMeasure = completedMeasure.add(fragment.source.measure());
		if (!completedMeasure.same(original.measure())) throw new IllegalStateException("Liquid characteristic preimages do not exactly partition volume and first moments");
		// Composition yields one affine image when all event preimages have the same map.
		// Preserve the original surface in that image; auxiliary velocity facets then cancel.
		var transforms = moved.stream().map(Moved::transform).distinct().toList();
		if (transforms.size() == 1) {
			var geometry = transforms.getFirst().apply(source);
			if (!LiquidConvexRegion.of(geometry).measure().isVolume(original.measure())) throw new IllegalStateException("Composed liquid characteristic changed exact volume");
			return List.of(geometry);
		}
		var groups = new java.util.LinkedHashMap<LiquidCharacteristicMap, ArrayList<LiquidConvexRegion>>();
		for (var fragment : moved) groups.computeIfAbsent(fragment.transform, ignored -> new ArrayList<>()).add(fragment.source);
		moved.clear();
		for (var group : groups.entrySet()) {
			var union = LiquidConvexRegion.joined(group.getValue(), original);
			if (union != null) moved.add(new Moved(union, group.getKey()));
			else for (var region : group.getValue()) moved.add(new Moved(region, group.getKey()));
		}
		boolean joined;
		do {
			joined = false;
			outer: for (int i = 0; i < moved.size(); i++) for (int j = i + 1; j < moved.size(); j++) {
				var a = moved.get(i); var b = moved.get(j); if (!a.transform.equals(b.transform)) continue;
				if (++processed > maximumPieces) throw new IllegalStateException("Liquid characteristic coalescing exceeds its work budget");
				var union = a.source.joined(b.source, original); if (union == null) continue;
				moved.set(i, new Moved(union, a.transform)); moved.remove(j); joined = true; break outer;
			}
		} while (joined);
		var geometries = new ArrayList<LiquidPolyhedron>(); var measure = LiquidConvexRegion.EMPTY.measure();
		for (var fragment : moved) {
			var geometry = fragment.transform.apply(fragment.source); geometries.add(geometry); measure = measure.add(LiquidConvexRegion.of(geometry).measure());
		}
		if (!measure.isVolume(original.measure())) throw new IllegalStateException("Liquid characteristic image changed exact volume");
		return List.copyOf(geometries);
	}
}
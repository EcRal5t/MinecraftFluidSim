package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Conservative P0 projection onto spatial leaves, independent of the previous pressure cells. */
final class LiquidGeometryVelocityProjection {
	record Source(int phase, int inventory, Fraction density, LiquidPolyhedron geometry, LiquidPolyhedron.Point velocity) {
		Source {
			if (phase < 0 || inventory < 0 || density == null || density.signum() <= 0 || geometry == null || velocity == null)
				throw new IllegalArgumentException("Invalid spatial inventory projection source");
		}
		Source(int phase, LiquidPolyhedron geometry, LiquidPolyhedron.Point velocity) { this(phase, phase, Fraction.of(1), geometry, velocity); }
	}
	/** Extensive moments retain their mass measure; inventory volumes use the same spatial weights. */
	record Value(Fraction volume, Fraction mass, Map<Integer, Fraction> inventoryVolumes, List<Fraction> momentum, Fraction originalKinetic, double[] velocity, double rmsError, Fraction kineticLoss,
			Fraction kineticRoundingChange, List<Fraction> momentumRoundingBounds) { }
	private record Address(int level, int x, int y, int z) {
		Address parent() { return new Address(level - 1, x >>> 1, y >>> 1, z >>> 1); }
		int coordinate(int axis) { return axis == 0 ? x : axis == 1 ? y : z; }
	}
	private record Key(int phase, int inventory, Fraction density, LiquidPolyhedron.Point velocity) { }
	private record Part(Key field, List<LiquidExactPolygon> faces, Fraction volume) { }
	private record Projection(List<Part> parts, Map<Integer, Value> values) { }
	private final Address root;
	private final List<Part> sources;
	private final Map<Address, Projection> cache = new HashMap<>();
	LiquidGeometryVelocityProjection(LiquidGeometryRefinement.Cell root, List<Source> sources) {
		this.root = new Address(root.level(), root.x(), root.y(), root.z());
		this.sources = sources.stream().map(source -> new Part(new Key(source.phase, source.inventory, source.density, source.velocity), LiquidExactPolygon.faces(source.geometry), source.geometry.measure().volume())).toList();
	}
	boolean accepts(LiquidGeometryRefinement.Cell cell, double maximumRmsError) {
		return values(cell).values().stream().allMatch(value -> value.rmsError <= maximumRmsError);
	}
	Map<Integer, Value> values(LiquidGeometryRefinement.Cell cell) {
		var key = new Address(cell.level(), cell.x(), cell.y(), cell.z());
		int levels = key.level - root.level;
		if (levels < 0 || key.x >>> levels != root.x || key.y >>> levels != root.y || key.z >>> levels != root.z)
			throw new IllegalArgumentException("Velocity projection leaf lies outside its initial support");
		return projection(key).values;
	}
	private Projection projection(Address cell) {
		var found = cache.get(cell); if (found != null) return found;
		boolean initial = cell.equals(root);
		var input = initial ? sources : projection(cell.parent()).parts;
		var planes = new ArrayList<LiquidGeometryPlane>(); double width = Math.scalb(1.0, -cell.level);
		for (int axis = 0; axis < 3; axis++) {
			double lo = cell.coordinate(axis) * width, hi = lo + width;
			if (initial || (cell.coordinate(axis) & 1) == 0) planes.add(new LiquidGeometryPlane(new LiquidPolyhedron.Point(axis == 0 ? 1 : 0, axis == 1 ? 1 : 0, axis == 2 ? 1 : 0), hi));
			if (initial || (cell.coordinate(axis) & 1) == 1) planes.add(new LiquidGeometryPlane(new LiquidPolyhedron.Point(axis == 0 ? -1 : 0, axis == 1 ? -1 : 0, axis == 2 ? -1 : 0), -lo));
		}
		var retained = new ArrayList<Part>();
		var volumes = new LinkedHashMap<Key, Fraction>();
		for (var source : input) {
			var faces = source.faces;
			for (var plane : planes) {
				faces = LiquidExactPolygon.clipVolume(faces, plane);
				if (faces.isEmpty()) break;
			}
			var volume = faces == source.faces ? source.volume : LiquidExactPolygon.volume(faces);
			if (volume.signum() > 0) { retained.add(new Part(source.field, faces, volume)); volumes.merge(source.field, volume, Fraction::add); }
		}
		var grouped = new LinkedHashMap<Integer, ArrayList<Map.Entry<Key, Fraction>>>();
		for (var entry : volumes.entrySet()) grouped.computeIfAbsent(entry.getKey().phase, ignored -> new ArrayList<>()).add(entry);
		var result = new LinkedHashMap<Integer, Value>();
		for (var group : grouped.entrySet()) {
			var volume = Fraction.ZERO; var mass = Fraction.ZERO; var before = Fraction.ZERO;
			var inventories = new java.util.TreeMap<Integer, Fraction>(); var momentum = new Fraction[]{Fraction.ZERO, Fraction.ZERO, Fraction.ZERO};
			for (var entry : group.getValue()) {
				var v = entry.getValue(); var m = v.multiply(entry.getKey().density); volume = volume.add(v); mass = mass.add(m);
				inventories.merge(entry.getKey().inventory, v, Fraction::add);
				for (int a = 0; a < 3; a++) {
					var u = Fraction.of(entry.getKey().velocity.coordinate(a)); momentum[a] = momentum[a].add(m.multiply(u));
					before = before.add(m.multiply(u).multiply(u).divide(Fraction.of(2)));
				}
			}
			result.put(group.getKey(), observe(volume, mass, inventories, List.of(momentum), before));
		}
		var value = new Projection(List.copyOf(retained), Map.copyOf(result)); cache.put(cell, value); return value;
	}
	static Value observe(Fraction volume, Fraction mass, Map<Integer, Fraction> inventories, List<Fraction> momentum, Fraction before) {
			if (volume == null || volume.signum() <= 0 || mass == null || mass.signum() <= 0 || inventories == null || inventories.isEmpty()
					|| momentum == null || momentum.size() != 3 || before == null || before.signum() < 0)
				throw new IllegalArgumentException("Invalid extensive spatial projection");
			var occupied = Fraction.ZERO;
			for (var entry : inventories.entrySet()) {
				if (entry.getKey() < 0 || entry.getValue().signum() <= 0) throw new IllegalArgumentException("Invalid spatial inventory share");
				occupied = occupied.add(entry.getValue());
			}
			if (!occupied.equals(volume)) throw new IllegalArgumentException("Spatial inventory shares differ from occupied volume");
			var velocity = new double[3]; var projected = Fraction.ZERO; var stored = Fraction.ZERO; var bounds = new ArrayList<Fraction>();
			for (int a = 0; a < 3; a++) {
				var mean = momentum.get(a).divide(mass); velocity[a] = mean.rounded(); var rounded = Fraction.of(velocity[a]);
				var bound = mass.multiply(Fraction.of(Math.ulp(velocity[a]))).divide(Fraction.of(2)); bounds.add(bound);
				if (mass.multiply(rounded).subtract(momentum.get(a)).absolute().subtract(bound).signum() > 0)
					throw new IllegalStateException("P0 projection exceeds its binary64 momentum rounding bound");
				projected = projected.add(mass.multiply(mean).multiply(mean).divide(Fraction.of(2)));
				stored = stored.add(mass.multiply(rounded).multiply(rounded).divide(Fraction.of(2)));
			}
			var loss = before.subtract(projected);
			if (loss.signum() < 0) throw new IllegalStateException("Conservative P0 projection increased exact kinetic energy");
			var squaredError = loss.multiply(Fraction.of(2)).divide(mass);
			double error = squaredError.signum() == 0 ? 0 : Math.nextUp(Math.sqrt(Math.nextUp(squaredError.rounded())));
			return new Value(volume, mass, Map.copyOf(inventories), List.copyOf(momentum), before, velocity, error, loss, stored.subtract(projected), List.copyOf(bounds));
	}
}

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
	record RmsTolerance(double absoluteMetresPerSecond, double relativePhaseFluctuation) {
		RmsTolerance {
			if (!(absoluteMetresPerSecond >= 0) || !Double.isFinite(absoluteMetresPerSecond)
					|| !(relativePhaseFluctuation >= 0 && relativePhaseFluctuation < 1)
					|| absoluteMetresPerSecond != 0 && relativePhaseFluctuation != 0)
				throw new IllegalArgumentException("Invalid reconstruction velocity accuracy");
		}
		Map<Integer, Fraction> squaredLimits(List<Source> sources) {
			var absolute = Fraction.of(absoluteMetresPerSecond);
			if (relativePhaseFluctuation == 0) return Map.of(-1, absolute.multiply(absolute));
			var limits = new LinkedHashMap<Integer, Fraction>(); var relative = Fraction.of(relativePhaseFluctuation);
			for (var entry : summarize(sources).entrySet()) {
				var field = entry.getValue(); limits.put(entry.getKey(), relative.multiply(relative).multiply(field.kineticLoss).multiply(Fraction.of(2)).divide(field.mass));
			}
			return Map.copyOf(limits);
		}
		boolean accepts(Map<Integer, Fraction> limits, int phase, Value field) {
			var limit = limits.getOrDefault(phase, limits.get(-1));
			if (limit == null) throw new IllegalArgumentException("Reconstruction field has no source phase accuracy");
			return field.kineticLoss.multiply(Fraction.of(2)).compareTo(field.mass.multiply(limit)) <= 0;
		}
		/** Mark the largest integrated defects until the unmarked loss fits each phase's complete budget. */
		java.util.Set<Integer> unresolvedLeaves(Map<Integer, Fraction> limits, List<Map<Integer, Value>> fields) {
			var selected = new java.util.HashSet<Integer>();
			if (relativePhaseFluctuation == 0) {
				for (int l = 0; l < fields.size(); l++) for (var entry : fields.get(l).entrySet())
					if (!accepts(limits, entry.getKey(), entry.getValue())) selected.add(l);
				return java.util.Set.copyOf(selected);
			}
			for (var phase : limits.entrySet()) {
				var mass = Fraction.ZERO; var loss = Fraction.ZERO; var candidates = new ArrayList<Integer>();
				for (int l = 0; l < fields.size(); l++) {
					var field = fields.get(l).get(phase.getKey()); if (field == null) continue;
					mass = mass.add(field.mass); loss = loss.add(field.kineticLoss);
					if (field.kineticLoss.signum() > 0) candidates.add(l);
				}
				var excess = loss.subtract(mass.multiply(phase.getValue()).divide(Fraction.of(2)));
				if (excess.signum() <= 0) continue;
				candidates.sort((a, b) -> {
					int order = fields.get(b).get(phase.getKey()).kineticLoss.compareTo(fields.get(a).get(phase.getKey()).kineticLoss);
					return order == 0 ? Integer.compare(a, b) : order;
				});
				for (int leaf : candidates) {
					selected.add(leaf); excess = excess.subtract(fields.get(leaf).get(phase.getKey()).kineticLoss);
					if (excess.signum() <= 0) break;
				}
			}
			return java.util.Set.copyOf(selected);
		}
	}
	private record Part(Key field, List<LiquidExactPolygon> faces, Fraction volume) { }
	static Map<Integer, Value> summarize(List<Source> sources) {
		var volumes = new LinkedHashMap<Key, Fraction>();
		for (var source : sources) volumes.merge(new Key(source.phase, source.inventory, source.density, source.velocity), source.geometry.volume(), Fraction::add);
		return moments(volumes);
	}
	private record Projection(List<Part> parts, Map<Integer, Value> values) { }
	private final Address root;
	private final List<Part> sources;
	private final Map<Address, Projection> cache = new HashMap<>();
	LiquidGeometryVelocityProjection(LiquidGeometryRefinement.Cell root, List<Source> sources) {
		this.root = new Address(root.level(), root.x(), root.y(), root.z());
		this.sources = sources.stream().map(source -> new Part(new Key(source.phase, source.inventory, source.density, source.velocity), LiquidExactPolygon.faces(source.geometry), source.geometry.volume())).toList();
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
		var value = new Projection(List.copyOf(retained), moments(volumes)); cache.put(cell, value); return value;
	}
	private static Map<Integer, Value> moments(Map<Key, Fraction> volumes) {
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
		return Map.copyOf(result);
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
			double error = squareRootUpper(squaredError);
			return new Value(volume, mass, Map.copyOf(inventories), List.copyOf(momentum), before, velocity, error, loss, stored.subtract(projected), List.copyOf(bounds));
	}
	/** Scale the squared quantity before binary64 observation, retaining subnormal velocity errors. */
	private static double squareRootUpper(Fraction squared) {
		if (squared.signum() == 0) return 0;
		int exponent = squared.numerator().bitLength() - squared.denominator().bitLength();
		if ((exponent >= 0 ? squared.numerator().compareTo(squared.denominator().shiftLeft(exponent))
				: squared.numerator().shiftLeft(-exponent).compareTo(squared.denominator())) < 0) exponent--;
		exponent = 2 * Math.floorDiv(exponent, 2);
		var scaled = exponent >= 0 ? new Fraction(squared.numerator(), squared.denominator().shiftLeft(exponent))
				: new Fraction(squared.numerator().shiftLeft(-exponent), squared.denominator());
		double result = Math.scalb(Math.sqrt(scaled.rounded()), exponent / 2);
		for (;;) {
			if (!Double.isFinite(result)) throw new IllegalStateException("Spatial velocity error exceeds its numerical range");
			var represented = Fraction.of(result);
			if (represented.multiply(represented).compareTo(squared) >= 0) return result;
			result = Math.nextUp(result);
		}
	}
}

package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Conservative P0 projection onto spatial leaves, independent of the previous pressure cells. */
final class LiquidGeometryVelocityProjection {
	record Source(int owner, LiquidPolyhedron geometry, LiquidPolyhedron.Point velocity) { }
	record Value(Fraction volume, List<Fraction> momentum, Fraction originalKinetic, double[] velocity, double rmsError, Fraction kineticLoss,
			Fraction kineticRoundingChange, List<Fraction> momentumRoundingBounds) { }
	private record Address(int level, int x, int y, int z) { }
	private record Key(int owner, LiquidPolyhedron.Point velocity) { }
	private final List<Source> sources;
	private final Map<Address, Map<Integer, Value>> cache = new HashMap<>();
	LiquidGeometryVelocityProjection(List<Source> sources) { this.sources = List.copyOf(sources); }
	boolean accepts(LiquidGeometryRefinement.Cell cell, double maximumRmsError) {
		return values(cell).values().stream().allMatch(value -> value.rmsError <= maximumRmsError);
	}
	Map<Integer, Value> values(LiquidGeometryRefinement.Cell cell) {
		var key = new Address(cell.level(), cell.x(), cell.y(), cell.z());
		return cache.computeIfAbsent(key, ignored -> project(cell));
	}
	private Map<Integer, Value> project(LiquidGeometryRefinement.Cell cell) {
		var volumes = new LinkedHashMap<Key, Fraction>();
		for (var source : sources) {
			var faces = LiquidExactPolygon.faces(source.geometry);
			for (int axis = 0; axis < 3; axis++) {
				double lo = cell.minimum(axis), hi = lo + cell.width();
				faces = LiquidExactPolygon.clipVolume(faces, new LiquidGeometryPlane(new LiquidPolyhedron.Point(axis == 0 ? 1 : 0, axis == 1 ? 1 : 0, axis == 2 ? 1 : 0), hi));
				faces = LiquidExactPolygon.clipVolume(faces, new LiquidGeometryPlane(new LiquidPolyhedron.Point(axis == 0 ? -1 : 0, axis == 1 ? -1 : 0, axis == 2 ? -1 : 0), -lo));
				if (faces.isEmpty()) break;
			}
			var volume = LiquidExactPolygon.volume(faces);
			if (volume.signum() > 0) volumes.merge(new Key(source.owner, source.velocity), volume, Fraction::add);
		}
		var grouped = new LinkedHashMap<Integer, ArrayList<Map.Entry<Key, Fraction>>>();
		for (var entry : volumes.entrySet()) grouped.computeIfAbsent(entry.getKey().owner, ignored -> new ArrayList<>()).add(entry);
		var result = new LinkedHashMap<Integer, Value>();
		for (var group : grouped.entrySet()) {
			var volume = Fraction.ZERO; var before = Fraction.ZERO; var momentum = new Fraction[]{Fraction.ZERO, Fraction.ZERO, Fraction.ZERO};
			for (var entry : group.getValue()) {
				var v = entry.getValue(); volume = volume.add(v);
				for (int a = 0; a < 3; a++) {
					var u = Fraction.of(entry.getKey().velocity.coordinate(a)); momentum[a] = momentum[a].add(v.multiply(u));
					before = before.add(v.multiply(u).multiply(u).divide(Fraction.of(2)));
				}
			}
			result.put(group.getKey(), observe(volume, List.of(momentum), before));
		}
		return Map.copyOf(result);
	}
	static Value observe(Fraction volume, List<Fraction> momentum, Fraction before) {
			var velocity = new double[3]; var projected = Fraction.ZERO; var stored = Fraction.ZERO; var bounds = new ArrayList<Fraction>();
			for (int a = 0; a < 3; a++) {
				var mean = momentum.get(a).divide(volume); velocity[a] = mean.rounded(); var rounded = Fraction.of(velocity[a]);
				var bound = volume.multiply(Fraction.of(Math.ulp(velocity[a]))).divide(Fraction.of(2)); bounds.add(bound);
				if (volume.multiply(rounded).subtract(momentum.get(a)).absolute().subtract(bound).signum() > 0)
					throw new IllegalStateException("P0 projection exceeds its binary64 momentum rounding bound");
				projected = projected.add(volume.multiply(mean).multiply(mean).divide(Fraction.of(2)));
				stored = stored.add(volume.multiply(rounded).multiply(rounded).divide(Fraction.of(2)));
			}
			var loss = before.subtract(projected);
			if (loss.signum() < 0) throw new IllegalStateException("Conservative P0 projection increased exact kinetic energy");
			var squaredError = loss.multiply(Fraction.of(2)).divide(volume);
			double error = squaredError.signum() == 0 ? 0 : Math.nextUp(Math.sqrt(Math.nextUp(squaredError.rounded())));
			return new Value(volume, List.copyOf(momentum), before, velocity, error, loss, stored.subtract(projected), List.copyOf(bounds));
	}
}

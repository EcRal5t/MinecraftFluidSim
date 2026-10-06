package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;
import cc.ecisr.mmm.fluid.LiquidExactPolygon.Vertex;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Polygon area and its negative vertex gradient on the actual material interface. */
final class LiquidAreaEnergy {
	final double joules;
	final Map<Vertex, List<Fraction>> force;
	LiquidAreaEnergy(List<LiquidExactPolygon> polygons, double[] coefficient) {
		if (coefficient.length != polygons.size()) throw new IllegalArgumentException("Incomplete surface energy coefficients");
		var forces = new LinkedHashMap<Vertex, Fraction[]>(); var energy = Fraction.ZERO;
		for (int f = 0; f < coefficient.length; f++) {
			double sigma = coefficient[f]; if (!Double.isFinite(sigma)) throw new IllegalArgumentException("Non-finite surface energy coefficient");
			if (sigma == 0) continue;
			var polygon = polygons.get(f); var points = polygon.vertices(); var unit = normal(polygon); var half = Fraction.of(sigma).divide(Fraction.of(2));
			energy = energy.add(Fraction.of(sigma).multiply(Fraction.of(polygon.surface().area())));
			for (int v = 0; v < points.size(); v++) {
				var before = points.get((v + points.size() - 1) % points.size()).coordinates(); var after = points.get((v + 1) % points.size()).coordinates();
				var difference = new Fraction[3]; for (int a = 0; a < 3; a++) difference[a] = after[a].subtract(before[a]);
				var force = forces.computeIfAbsent(points.get(v), ignored -> new Fraction[]{Fraction.ZERO, Fraction.ZERO, Fraction.ZERO});
				for (int a = 0; a < 3; a++) force[a] = force[a].subtract(difference[(a + 1) % 3].multiply(unit[(a + 2) % 3])
						.subtract(difference[(a + 2) % 3].multiply(unit[(a + 1) % 3])).multiply(half));
			}
		}
		joules = energy.rounded(); if (!Double.isFinite(joules)) throw new IllegalStateException("Surface energy exceeds numerical range");
		var result = new LinkedHashMap<Vertex, List<Fraction>>(); forces.forEach((point, value) -> result.put(point, List.of(value)));
		force = java.util.Collections.unmodifiableMap(result);
	}
	static Fraction[] normal(LiquidExactPolygon polygon) {
		var plane = polygon.coefficients(); int largest = 0;
		for (int a = 1; a < 3; a++) if (plane.get(a).abs().compareTo(plane.get(largest).abs()) > 0) largest = a;
		var direction = new Fraction[3]; double norm = 0;
		for (int a = 0; a < 3; a++) { direction[a] = new Fraction(plane.get(a), plane.get(largest).abs()); norm = Math.hypot(norm, direction[a].rounded()); }
		for (int a = 0; a < 3; a++) direction[a] = direction[a].multiply(Fraction.of(1 / norm));
		return direction;
	}
}

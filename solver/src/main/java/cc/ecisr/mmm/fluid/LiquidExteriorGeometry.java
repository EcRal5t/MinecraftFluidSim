package cc.ecisr.mmm.fluid;

import java.util.ArrayList;
import java.util.List;

/** Exact exterior of disjoint convex fragments, with complementary interior faces canceled. */
final class LiquidExteriorGeometry {
	private static final class Face {
		final LiquidExactPolygon polygon;
		List<LiquidExactPolygon> remaining;
		Face(LiquidExactPolygon polygon) { this.polygon = polygon; remaining = List.of(polygon); }
		void remove(LiquidExactPolygon covered) {
			var result = new ArrayList<LiquidExactPolygon>();
			for (var part : remaining) result.addAll(part.subtract(covered));
			remaining = result;
		}
	}
	private LiquidExteriorGeometry() { }
	static List<LiquidExactPolygon> of(List<LiquidGeometryRefinement.Cell> liquids) {
		var positive = new java.util.LinkedHashMap<LiquidGeometryPlane.Key, ArrayList<Face>>();
		var negative = new java.util.LinkedHashMap<LiquidGeometryPlane.Key, ArrayList<Face>>();
		var all = new ArrayList<Face>();
		for (var cell : liquids) for (var polygon : LiquidExactPolygon.faces(cell.geometry())) {
			var face = new Face(polygon); all.add(face); var plane = polygon.plane();
			(plane.positive() ? positive : negative).computeIfAbsent(plane.key(), ignored -> new ArrayList<>()).add(face);
		}
		for (var entry : positive.entrySet()) {
			var other = negative.get(entry.getKey()); if (other == null) continue;
			for (var a : entry.getValue()) for (var b : other) {
				if (a.remaining.isEmpty() || b.remaining.isEmpty()) continue;
				if (a.polygon.vertexKey().equals(b.polygon.vertexKey())) { a.remaining = List.of(); b.remaining = List.of(); continue; }
				var shared = a.polygon.intersection(b.polygon); if (shared.hasArea()) { a.remove(shared); b.remove(shared); }
			}
		}
		return all.stream().flatMap(face -> face.remaining.stream()).toList();
	}
}

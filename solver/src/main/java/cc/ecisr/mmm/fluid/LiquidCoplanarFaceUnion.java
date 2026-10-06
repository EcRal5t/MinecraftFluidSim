package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.LiquidExactPolygon.Vertex;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Exact convex unions remove auxiliary subdivisions of the same shared physical face. */
final class LiquidCoplanarFaceUnion {
	record Result(List<LiquidControlVolumeProjection.Face> faces, List<LiquidExactPolygon> polygons) { }
	private record Support(int first, int second, double prescribed, LiquidGeometryPlane.Key plane, boolean positive) { }
	private LiquidCoplanarFaceUnion() { }
	static Result build(List<LiquidControlVolumeProjection.Face> faces, List<LiquidExactPolygon> polygons) {
		var groups = new LinkedHashMap<Support, ArrayList<Integer>>();
		for (int f = 0; f < faces.size(); f++) {
			var face = faces.get(f);
			var plane = polygons.get(f).plane();
			groups.computeIfAbsent(new Support(face.first(), face.second(), face.prescribed(), plane.key(), plane.positive()),
					ignored -> new ArrayList<>()).add(f);
		}
		var result = new ArrayList<LiquidControlVolumeProjection.Face>(); var geometry = new ArrayList<LiquidExactPolygon>();
		for (var group : groups.values()) {
			var parts = group.stream().map(polygons::get).toList(); var union = group.size() == 1 ? null : union(parts);
			if (union == null) for (int f : group) { result.add(faces.get(f)); geometry.add(polygons.get(f)); }
			else {
				var original = faces.get(group.getFirst());
				result.add(new LiquidControlVolumeProjection.Face(original.first(), original.second(), union.surface(), original.prescribed(), union));
				geometry.add(union);
			}
		}
		return new Result(List.copyOf(result), List.copyOf(geometry));
	}
	private static LiquidExactPolygon union(List<LiquidExactPolygon> parts) {
		var plane = parts.getFirst().coefficients(); int axis = 0; while (plane.get(axis).signum() == 0) axis++;
		int first = (axis + 1) % 3, second = (axis + 2) % 3;
		var points = parts.stream().flatMap(part -> part.vertices().stream()).distinct().sorted((a, b) -> {
			int order = a.coordinate(first).multiply(b.w()).compareTo(b.coordinate(first).multiply(a.w()));
			return order != 0 ? order : a.coordinate(second).multiply(b.w()).compareTo(b.coordinate(second).multiply(a.w()));
		}).toList();
		var lower = new ArrayList<Vertex>(); var upper = new ArrayList<Vertex>();
		for (var point : points) append(lower, point, first, second);
		for (int i = points.size() - 1; i >= 0; i--) append(upper, points.get(i), first, second);
		lower.removeLast(); upper.removeLast(); lower.addAll(upper);
		if (plane.get(axis).signum() < 0) java.util.Collections.reverse(lower);
		var hull = LiquidExactPolygon.restored(lower, plane); var area = hull.areaVector();
		for (int a = 0; a < 3; a++) {
			var sum = LiquidExactPolygon.Fraction.ZERO; for (var part : parts) sum = sum.add(part.areaVector()[a]);
			if (!sum.equals(area[a])) return null;
		}
		// Coverage plus equal area proves that the convex union adds no gap and removes no overlap.
		var remaining = List.of(hull);
		for (var part : parts) {
			var next = new ArrayList<LiquidExactPolygon>(); for (var original : remaining) next.addAll(original.subtract(part));
			remaining = next; if (remaining.isEmpty()) return hull;
		}
		return null;
	}
	private static void append(ArrayList<Vertex> hull, Vertex point, int first, int second) {
		while (hull.size() >= 2 && turn(hull.get(hull.size() - 2), hull.getLast(), point, first, second) <= 0) hull.removeLast();
		hull.add(point);
	}
	private static int turn(Vertex a, Vertex b, Vertex c, int first, int second) {
		var bx = b.coordinate(first).multiply(a.w()).subtract(a.coordinate(first).multiply(b.w()));
		var by = b.coordinate(second).multiply(a.w()).subtract(a.coordinate(second).multiply(b.w()));
		var cx = c.coordinate(first).multiply(a.w()).subtract(a.coordinate(first).multiply(c.w()));
		var cy = c.coordinate(second).multiply(a.w()).subtract(a.coordinate(second).multiply(c.w()));
		return bx.multiply(cy).subtract(by.multiply(cx)).signum();
	}
}

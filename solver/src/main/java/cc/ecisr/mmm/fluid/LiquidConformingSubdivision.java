package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;
import cc.ecisr.mmm.fluid.LiquidExactPolygon.Vertex;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Positive volume fans restrict the same physical P1 surface trace across auxiliary cuts. */
final class LiquidConformingSubdivision {
	private record Patch(int face, LiquidExactPolygon polygon, List<Vertex> triangle) { }
	final List<LiquidControlVolumeSubdivision.Fan> fans;
	final Map<Vertex, Map<Vertex, Fraction>> weights;
	final Map<Vertex, List<Integer>> faces;

	LiquidConformingSubdivision(LiquidControlVolumeMesh mesh) {
		if (mesh.subdivision == null) { fans = List.of(); weights = Map.of(); faces = Map.of(); return; }
		var patches = new ArrayList<Patch>(); var ends = new ArrayList<Integer>();
		var primary = new LinkedHashSet<Vertex>();
		for (var polygon : mesh.exactPolygons) { primary.addAll(polygon.vertices()); primary.add(LiquidExactPolygon.vertex(polygon.centroidCoordinates())); }
		var work = new LiquidStarControlPartition.Work();
		for (var fan : mesh.subdivision) {
			for (int i = 0; i < fan.faces().length; i++) {
				int f = fan.faces()[i]; var polygon = fan.polygons().get(i);
				if (f < 0) { patches.add(new Patch(f, polygon, null)); continue; }
				var physical = mesh.faces.get(f).first() == fan.cell() ? mesh.exactPolygons.get(f) : mesh.exactPolygons.get(f).reversed();
				if (physical.vertexKey().equals(polygon.vertexKey())) { patches.add(new Patch(f, physical, null)); continue; }
				var center = LiquidExactPolygon.vertex(physical.centroidCoordinates()); var covered = zero();
				for (int e = 0; e < physical.vertices().size(); e++) {
					var triangle = List.of(center, physical.vertices().get(e), physical.vertices().get((e + 1) % physical.vertices().size()));
					var facet = LiquidExactPolygon.triangle(triangle.get(0), triangle.get(1), triangle.get(2)); if (!facet.hasArea()) continue;
					work.pair(); var overlap = polygon.intersection(facet); if (!overlap.hasArea()) continue;
					for (int a = 0; a < 3; a++) covered[a] = covered[a].add(overlap.areaVector()[a]);
					patches.add(new Patch(f, overlap, triangle));
				}
				if (!java.util.Arrays.equals(covered, polygon.areaVector())) throw new IllegalStateException("Auxiliary trace changed a physical face area");
			}
			ends.add(patches.size());
		}
		var polygons = LiquidExactPolygon.splitEdges(patches.stream().map(Patch::polygon).toList());
		var restriction = new LinkedHashMap<Vertex, Map<Vertex, Fraction>>(); var incident = new LinkedHashMap<Vertex, LinkedHashSet<Integer>>();
		for (int i = 0; i < patches.size(); i++) {
			var patch = patches.get(i); if (patch.face() < 0) continue;
			var points = new ArrayList<>(polygons.get(i).vertices()); points.add(LiquidExactPolygon.vertex(polygons.get(i).centroidCoordinates()));
			for (var point : points) if (!primary.contains(point)) {
				var row = patch.triangle() == null ? physicalWeights(point, mesh.exactPolygons.get(patch.face())) : barycentric(point, patch.triangle());
				if (row == null) throw new IllegalStateException("Auxiliary node lies outside its physical trace");
				var previous = restriction.putIfAbsent(point, row);
				if (previous != null && !previous.equals(row)) throw new IllegalStateException("Auxiliary surface node has incompatible physical traces");
				incident.computeIfAbsent(point, ignored -> new LinkedHashSet<>()).add(patch.face());
			}
		}
		var result = new ArrayList<LiquidControlVolumeSubdivision.Fan>(); int start = 0;
		for (int i = 0; i < mesh.subdivision.size(); i++) {
			var fan = mesh.subdivision.get(i); int end = ends.get(i); var ids = new int[end - start];
			for (int p = start; p < end; p++) ids[p - start] = patches.get(p).face();
			result.add(new LiquidControlVolumeSubdivision.Fan(fan.cell(), fan.geometry(), ids, List.copyOf(polygons.subList(start, end)))); start = end;
		}
		fans = List.copyOf(result); weights = java.util.Collections.unmodifiableMap(restriction);
		var memberships = new LinkedHashMap<Vertex, List<Integer>>(); incident.forEach((p, fs) -> memberships.put(p, List.copyOf(fs)));
		faces = java.util.Collections.unmodifiableMap(memberships);
	}
	private static Map<Vertex, Fraction> physicalWeights(Vertex point, LiquidExactPolygon polygon) {
		var center = LiquidExactPolygon.vertex(polygon.centroidCoordinates());
		for (int i = 0; i < polygon.vertices().size(); i++) {
			var triangle = List.of(center, polygon.vertices().get(i), polygon.vertices().get((i + 1) % polygon.vertices().size()));
			if (!LiquidExactPolygon.triangle(triangle.get(0), triangle.get(1), triangle.get(2)).hasArea()) continue;
			var weights = barycentric(point, triangle); if (weights != null) return weights;
		}
		return null;
	}
	private static Map<Vertex, Fraction> barycentric(Vertex point, List<Vertex> triangle) {
		var normal = LiquidExactPolygon.triangle(triangle.get(0), triangle.get(1), triangle.get(2)).coefficients(); int axis = 0;
		while (normal.get(axis).signum() == 0) axis++; int first = (axis + 1) % 3, second = (axis + 2) % 3;
		var denominator = determinant(triangle.get(0), triangle.get(1), triangle.get(2), first, second).multiply(point.w());
		var result = new LinkedHashMap<Vertex, Fraction>(); var total = Fraction.ZERO;
		for (int i = 0; i < 3; i++) {
			var weight = new Fraction(determinant(point, triangle.get((i + 1) % 3), triangle.get((i + 2) % 3), first, second).multiply(triangle.get(i).w()), denominator);
			if (weight.signum() < 0) return null;
			if (weight.signum() != 0) result.put(triangle.get(i), weight); total = total.add(weight);
		}
		if (!total.equals(Fraction.of(1))) throw new IllegalStateException("Auxiliary trace lost partition of unity");
		return java.util.Collections.unmodifiableMap(result);
	}
	private static BigInteger determinant(Vertex a, Vertex b, Vertex c, int i, int j) {
		return a.coordinate(i).multiply(b.coordinate(j).multiply(c.w()).subtract(b.w().multiply(c.coordinate(j))))
				.subtract(a.coordinate(j).multiply(b.coordinate(i).multiply(c.w()).subtract(b.w().multiply(c.coordinate(i)))))
				.add(a.w().multiply(b.coordinate(i).multiply(c.coordinate(j)).subtract(b.coordinate(j).multiply(c.coordinate(i)))));
	}
	private static Fraction[] zero() { return new Fraction[]{Fraction.ZERO, Fraction.ZERO, Fraction.ZERO}; }
}

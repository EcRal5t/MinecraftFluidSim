package cc.ecisr.mmm.fluid;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Exact gas partition from actual liquid exterior faces, independent of internal material cuts. */
final class LiquidGasPartition {
	private record Pending(List<LiquidExactPolygon> geometry, List<LiquidExactPolygon> surfaces) { }
	private LiquidGasPartition() { }
	static List<LiquidPolyhedron> build(LiquidGeometryRefinement.Cell leaf,
			List<LiquidGeometryRefinement.Cell> liquids, int maximumRegions) {
		var open = LiquidConvexRegion.of(leaf.geometry());
		if (liquids.isEmpty()) return List.of(leaf.geometry());
		var wallPlanes = open.faces().stream().map(face -> face.plane().key()).collect(java.util.stream.Collectors.toSet());
		var exterior = LiquidExteriorGeometry.of(liquids).stream().filter(face -> !wallPlanes.contains(face.plane().key())).toList();
		var occupancy = liquids.stream().map(cell -> LiquidExactPolygon.faces(cell.geometry()).stream().map(LiquidExactPolygon::coefficients).distinct().toList()).toList();
		var pending = new ArrayDeque<Pending>(); pending.add(new Pending(open.faces(), exterior));
		var gas = new ArrayList<LiquidPolyhedron>(); long work = 0;
		while (!pending.isEmpty()) {
			if (++work > 2L * maximumRegions) throw new IllegalStateException("Gas exterior partition exceeds its spatial work budget");
			var current = pending.removeLast();
			if (current.surfaces.isEmpty()) {
				// A convex region with no exterior face in its interior has constant material membership.
				var center = interiorPoint(current.geometry);
				boolean occupied = occupancy.stream().anyMatch(planes -> planes.stream().allMatch(plane -> center.distance(plane).signum() <= 0));
				if (!occupied) {
					if ((long) gas.size() + liquids.size() == maximumRegions) throw new IllegalStateException("Phase geometry exceeds its region budget");
					gas.add(LiquidPolyhedron.restoredExact(current.geometry));
				}
				continue;
			}
			var plane = partitionPlane(current.surfaces);
			for (var side : List.of(plane, plane.opposite())) {
				// Strictly retained and discarded vertices prove positive volume before any integration.
				var piece = LiquidExactPolygon.clipVolume(current.geometry, side); if (piece.isEmpty()) continue;
				var surfaces = new ArrayList<LiquidExactPolygon>();
				for (var surface : current.surfaces) if (!surface.plane().key().equals(plane.key())) {
					var clipped = surface.clippedTo(piece); if (clipped.hasArea()) surfaces.add(clipped);
				}
				pending.addLast(new Pending(piece, surfaces));
			}
		}
		var phases = new ArrayList<>(liquids); var address = LiquidPhasePartition.Address.of(leaf);
		for (var piece : gas) phases.add(address.cell(piece));
		var complete = LiquidExactPolygon.measure(LiquidExteriorGeometry.of(phases));
		if (!complete.same(leaf.geometry().measure())) throw new IllegalStateException("Gas complement lost exact occupied volume or first moments");
		return List.copyOf(gas);
	}
	private static LiquidGeometryPlane partitionPlane(List<LiquidExactPolygon> surfaces) {
		var planes = new java.util.LinkedHashMap<LiquidGeometryPlane.Key, LiquidGeometryPlane>();
		for (var surface : surfaces) { var plane = surface.plane(); planes.putIfAbsent(plane.key(), plane); }
		LiquidGeometryPlane best = null; int leastSplits = Integer.MAX_VALUE, leastSide = Integer.MAX_VALUE;
		for (var plane : planes.values()) {
			int splits = 0, front = 0, back = 0;
			for (var surface : surfaces) {
				boolean positive = false, negative = false;
				for (var point : surface.vertices()) {
					int sign = point.distance(plane.key().coefficients()).signum(); positive |= sign > 0; negative |= sign < 0;
				}
				if (positive) front++; if (negative) back++; if (positive && negative) splits++;
				if (splits > leastSplits) break;
			}
			int side = Math.max(front, back);
			if (splits < leastSplits || splits == leastSplits && side < leastSide) { best = plane; leastSplits = splits; leastSide = side; }
		}
		return best;
	}
	private static LiquidExactPolygon.Vertex interiorPoint(List<LiquidExactPolygon> faces) {
		var base = faces.getFirst(); var plane = base.coefficients();
		var point = faces.stream().flatMap(face -> face.vertices().stream()).filter(vertex -> vertex.distance(plane).signum() != 0).findFirst().orElseThrow();
		var x = point.x(); var y = point.y(); var z = point.z(); var w = point.w();
		for (var vertex : base.vertices()) { x = x.add(vertex.x()); y = y.add(vertex.y()); z = z.add(vertex.z()); w = w.add(vertex.w()); }
		// Positive homogeneous denominators give strictly positive convex weights. The full face
		// and an off-plane vertex span an interior point without expanding a common denominator.
		return new LiquidExactPolygon.Vertex(x, y, z, w);
	}
}

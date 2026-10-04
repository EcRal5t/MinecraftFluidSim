package cc.ecisr.mmm.fluid;

import java.util.ArrayList;
import java.util.List;
import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;

/** Selects an exact source volume within the disjoint gas space of a complete phase partition. */
final class LiquidGeometrySourcePlan {
	final List<LiquidPolyhedron> geometry;

	LiquidGeometrySourcePlan(List<LiquidPolyhedron> available, Fraction requested, int maximumFragments) {
		if (requested.signum() <= 0 || maximumFragments < 1) throw new IllegalArgumentException("Invalid liquid geometric source");
		var selected = new ArrayList<LiquidPolyhedron>(); var remaining = requested;
		for (var open : available) {
			if (remaining.signum() == 0) break;
			var volume = LiquidConvexRegion.of(open).measure().volume();
			if (volume.signum() <= 0) throw new IllegalArgumentException("Liquid source has empty open space");
			if (remaining.subtract(volume).signum() >= 0) { add(selected, open, maximumFragments); remaining = remaining.subtract(volume); continue; }
			var apex = open.centroidCoordinates();
			for (var face : LiquidExactPolygon.faces(open)) for (int edge = 0; edge < face.vertices().size() && remaining.signum() > 0; edge++) {
				var points = face.tetrahedronVertices(apex, edge); var tetra = LiquidPolyhedron.restoredExact(LiquidExactPolygon.tetrahedron(points));
				var v = LiquidConvexRegion.of(tetra).measure().volume();
				if (remaining.subtract(v).signum() < 0) {
					// Moving one tetrahedral vertex along its edge varies volume linearly and remains inside the source space.
					var ratio = remaining.divide(v); var moved = points.getFirst().coordinates(); var anchor = points.get(1).coordinates();
					for (int a = 0; a < 3; a++) moved[a] = anchor[a].add(moved[a].subtract(anchor[a]).multiply(ratio));
					var tail = new ArrayList<>(points); tail.set(0, LiquidExactPolygon.vertex(moved));
					tetra = LiquidPolyhedron.restoredExact(LiquidExactPolygon.tetrahedron(tail)); v = remaining;
				}
				add(selected, tetra, maximumFragments); remaining = remaining.subtract(v);
			}
		}
		if (remaining.signum() != 0) throw new IllegalStateException("Liquid source exceeds exact open geometric capacity");
		var measured = LiquidConvexRegion.EMPTY.measure(); for (var piece : selected) measured = measured.add(LiquidConvexRegion.of(piece).measure());
		if (!measured.volume().equals(requested)) throw new IllegalStateException("Liquid source changed exact requested volume");
		geometry = List.copyOf(selected);
	}
	private static void add(ArrayList<LiquidPolyhedron> selected, LiquidPolyhedron piece, int maximum) {
		if (selected.size() == maximum) throw new IllegalStateException("Liquid source exceeds its geometric fragment budget");
		selected.add(piece);
	}
}

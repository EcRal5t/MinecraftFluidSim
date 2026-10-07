package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.LiquidExactPolygon.Vertex;
import java.util.ArrayList;
import java.util.List;

/** Convex differences and additions express only the pyramids swept by moved shared face centers. */
final class LiquidSharedFaceVolumeGeometry {
	private LiquidSharedFaceVolumeGeometry() { }
	static List<LiquidPolyhedron> partition(LiquidPolyhedron original, List<LiquidExactPolygon> faces, List<Vertex> centers, int maximumRegions) {
		if (faces.size() != centers.size()) throw new IllegalArgumentException("Incomplete shared face volume coordinates");
		var retained = List.of(original); var additions = new ArrayList<LiquidPolyhedron>();
		for (int f = 0; f < faces.size(); f++) {
			var face = faces.get(f); var next = centers.get(f); int direction = next.distance(face.coefficients()).signum();
			if (direction == 0) continue;
			var surfaces = new ArrayList<LiquidExactPolygon>(); surfaces.add(direction > 0 ? face.reversed() : face);
			for (int e = 0; e < face.vertices().size(); e++) {
				var triangle = LiquidExactPolygon.triangle(next, face.vertices().get(e), face.vertices().get((e + 1) % face.vertices().size()));
				surfaces.add(direction > 0 ? triangle : triangle.reversed());
			}
			var pyramid = LiquidPolyhedron.restoredExact(surfaces);
			if (direction > 0) additions.add(pyramid);
			else {
				var result = new ArrayList<LiquidPolyhedron>();
				for (var piece : retained) {
					var inside = LiquidExactPolygon.faces(piece);
					for (var plane : LiquidExactPolygon.faces(pyramid)) {
						var outside = LiquidExactPolygon.clipVolume(inside, plane.coefficients(), -1);
						if (!outside.isEmpty()) result.add(LiquidPolyhedron.restoredExact(outside));
						inside = LiquidExactPolygon.clipVolume(inside, plane.coefficients(), 1); if (inside.isEmpty()) break;
					}
				}
				retained = List.copyOf(result);
			}
			if ((long) retained.size() + additions.size() > maximumRegions) throw new IllegalStateException("Shared phase correction exceeds its region budget");
		}
		var result = new ArrayList<>(retained); result.addAll(additions); return List.copyOf(result);
	}
}

package cc.ecisr.mmm.fluid;

import java.util.ArrayList;
import java.util.List;
import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;
import cc.ecisr.mmm.fluid.LiquidExactPolygon.Vertex;

/** A positive fan support is the intersection of the actual oriented surface half-spaces. */
final class LiquidStarKernel {
	private LiquidStarKernel() { }
	static Vertex find(LiquidPolyhedron geometry) {
		var faces = LiquidExactPolygon.faces(geometry);
		if (faces.isEmpty()) return null;
		var center = LiquidExactPolygon.vertex(geometry.centroidCoordinates());
		if (strictlyInside(center, faces)) return center;
		var low = new Fraction[3]; var high = new Fraction[3];
		for (var face : faces) for (var vertex : face.vertices()) {
			var point = vertex.coordinates();
			for (int a = 0; a < 3; a++) {
				if (low[a] == null || point[a].compareTo(low[a]) < 0) low[a] = point[a];
				if (high[a] == null || point[a].compareTo(high[a]) > 0) high[a] = point[a];
			}
		}
		var map = new ArrayList<Fraction>();
		for (int a = 0; a < 3; a++) {
			if (high[a].compareTo(low[a]) <= 0) return null;
			for (int b = 0; b < 3; b++) map.add(a == b ? high[a].subtract(low[a]) : Fraction.ZERO);
			map.add(low[a]);
		}
		var kernel = LiquidConvexRegion.of(LiquidPolyhedron.box(0, 0, 0, 1, 1, 1)).transformed(map).faces();
		for (var face : faces) {
			kernel = LiquidExactPolygon.clipVolume(kernel, face.coefficients(), 1);
			if (kernel.isEmpty()) return null;
		}
		var point = LiquidExactPolygon.interiorVertex(kernel.stream().flatMap(face -> face.vertices().stream()).distinct().toList());
		return strictlyInside(point, faces) ? point : null;
	}
	private static boolean strictlyInside(Vertex point, List<LiquidExactPolygon> faces) {
		return faces.stream().allMatch(face -> point.distance(face.coefficients()).signum() < 0);
	}
}

package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;
import cc.ecisr.mmm.fluid.LiquidExactPolygon.Vertex;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Signed wetted area on a stationary plane, including its exact bilinear displacement energy. */
final class LiquidWetWallEnergy {
	private record Face(List<Vertex> nodes, Fraction[] normal, Fraction half) { }
	private final List<Face> faces;
	LiquidWetWallEnergy(List<LiquidExactPolygon> polygons, double[] coefficient) {
		if (coefficient.length != polygons.size()) throw new IllegalArgumentException("Incomplete wet-wall energy coefficients");
		var result = new ArrayList<Face>();
		for (int f = 0; f < polygons.size(); f++) {
			if (!Double.isFinite(coefficient[f])) throw new IllegalArgumentException("Non-finite wet-wall energy");
			if (coefficient[f] != 0) result.add(new Face(polygons.get(f).vertices(), LiquidAreaEnergy.normal(polygons.get(f)), Fraction.of(coefficient[f]).divide(Fraction.of(2))));
		}
		faces = List.copyOf(result);
	}
	Map<Vertex, List<Fraction>> gradient(Map<Vertex, List<Fraction>> values) {
		var result = new LinkedHashMap<Vertex, Fraction[]>();
		for (var face : faces) for (int i = 0; i < face.nodes.size(); i++) {
			var before = values.get(face.nodes.get((i + face.nodes.size() - 1) % face.nodes.size())); var after = values.get(face.nodes.get((i + 1) % face.nodes.size()));
			if (before == null || after == null) throw new IllegalArgumentException("Incomplete wet-wall displacement");
			var force = result.computeIfAbsent(face.nodes.get(i), ignored -> new Fraction[]{Fraction.ZERO, Fraction.ZERO, Fraction.ZERO});
			for (int a = 0; a < 3; a++) force[a] = force[a].add(after.get((a + 1) % 3).subtract(before.get((a + 1) % 3)).multiply(face.normal[(a + 2) % 3])
					.subtract(after.get((a + 2) % 3).subtract(before.get((a + 2) % 3)).multiply(face.normal[(a + 1) % 3])).multiply(face.half));
		}
		var load = new LinkedHashMap<Vertex, List<Fraction>>(); result.forEach((point, force) -> load.put(point, List.of(force)));
		return java.util.Collections.unmodifiableMap(load);
	}
	Fraction energy(Map<Vertex, List<Fraction>> values) {
		var result = Fraction.ZERO;
		for (var entry : gradient(values).entrySet()) for (int a = 0; a < 3; a++) result = result.add(entry.getValue().get(a).multiply(values.get(entry.getKey()).get(a)));
		return result.divide(Fraction.of(2));
	}
	List<LiquidPressureFaceEnergy.Cross> pressureCrosses(LiquidSurfaceRateMap map) {
		var result = new ArrayList<LiquidPressureFaceEnergy.Cross>();
		for (var face : faces) for (int i = 0; i < face.nodes.size(); i++) for (int a = 0; a < 3; a++) for (int sign : new int[]{-1, 1}) {
			int b = (a + (sign > 0 ? 1 : 2)) % 3, c = 3 - a - b;
			var weight = face.half.multiply(face.normal[c]).multiply(Fraction.of(sign)); if (weight.signum() == 0) continue;
			var first = map.axis(face.nodes.get(i), a); var second = map.axis(face.nodes.get((i + 1) % face.nodes.size()), b);
			if (first.faces().length != 0 && second.faces().length != 0) result.add(new LiquidPressureFaceEnergy.Cross(first, second, weight.rounded()));
		}
		return List.copyOf(result);
	}
}

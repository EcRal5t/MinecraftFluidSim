package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;
import cc.ecisr.mmm.fluid.LiquidExactPolygon.Vertex;
import cc.ecisr.mmm.fluid.LiquidBoundedArithmetic.Value;
import static cc.ecisr.mmm.fluid.LiquidBoundedArithmetic.ZERO;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Signed wetted area on a stationary plane, including its exact bilinear displacement energy. */
final class LiquidWetWallEnergy {
	private record Edge(Vertex first, Vertex second) { }
	private record Term(Edge edge, Fraction[] normal) { }
	private final List<Term> terms;
	private List<Value[]> observedNormal;
	LiquidWetWallEnergy(List<LiquidExactPolygon> polygons, double[] coefficient) {
		if (coefficient.length != polygons.size()) throw new IllegalArgumentException("Incomplete wet-wall energy coefficients");
		var edges = new LinkedHashMap<Edge, Fraction[]>();
		for (int f = 0; f < polygons.size(); f++) {
			if (!Double.isFinite(coefficient[f])) throw new IllegalArgumentException("Non-finite wet-wall energy");
			if (coefficient[f] == 0) continue;
			var points = polygons.get(f).vertices(); var normal = LiquidAreaEnergy.normal(polygons.get(f));
			var half = Fraction.of(coefficient[f]).divide(Fraction.of(2));
			for (int i = 0; i < points.size(); i++) {
				var a = points.get(i); var b = points.get((i + 1) % points.size()); boolean forward = compare(a, b) < 0;
				var edge = forward ? new Edge(a, b) : new Edge(b, a); var factor = forward ? half : half.negate();
				var value = edges.computeIfAbsent(edge, ignored -> new Fraction[]{Fraction.ZERO, Fraction.ZERO, Fraction.ZERO});
				for (int axis = 0; axis < 3; axis++) value[axis] = value[axis].add(normal[axis].multiply(factor));
			}
		}
		terms = edges.entrySet().stream().filter(entry -> java.util.Arrays.stream(entry.getValue()).anyMatch(value -> value.signum() != 0))
				.sorted((a, b) -> { int order = compare(a.getKey().first, b.getKey().first); return order != 0 ? order : compare(a.getKey().second, b.getKey().second); })
				.map(entry -> new Term(entry.getKey(), entry.getValue())).toList();
	}
	private static int compare(Vertex a, Vertex b) {
		for (int i = 0; i < 3; i++) { int order = a.coordinate(i).compareTo(b.coordinate(i)); if (order != 0) return order; }
		return a.w().compareTo(b.w());
	}
	Map<Vertex, Value[]> observedGradient(Map<Vertex, Value[]> values) {
		if (observedNormal == null) observedNormal = terms.stream().map(term -> java.util.Arrays.stream(term.normal).map(Value::of).toArray(Value[]::new)).toList();
		var result = new LinkedHashMap<Vertex, Value[]>();
		for (int i = 0; i < terms.size(); i++) {
			var edge = terms.get(i).edge; var normal = observedNormal.get(i);
			var first = values.get(edge.first); var second = values.get(edge.second);
			if (first == null || second == null) throw new IllegalArgumentException("Incomplete wet-wall displacement");
			var aForce = result.computeIfAbsent(edge.first, ignored -> new Value[]{ZERO, ZERO, ZERO});
			var bForce = result.computeIfAbsent(edge.second, ignored -> new Value[]{ZERO, ZERO, ZERO});
			for (int a = 0; a < 3; a++) { int b = (a + 1) % 3, c = (a + 2) % 3;
				aForce[a] = aForce[a].add(second[b].multiply(normal[c]).subtract(second[c].multiply(normal[b])));
				bForce[a] = bForce[a].subtract(first[b].multiply(normal[c]).subtract(first[c].multiply(normal[b])));
			}
		}
		return result;
	}
	Map<Vertex, List<Fraction>> gradient(Map<Vertex, List<Fraction>> values) {
		var result = new LinkedHashMap<Vertex, Fraction[]>();
		for (var term : terms) {
			var edge = term.edge; var first = values.get(edge.first); var second = values.get(edge.second);
			if (first == null || second == null) throw new IllegalArgumentException("Incomplete wet-wall displacement");
			var aForce = result.computeIfAbsent(edge.first, ignored -> new Fraction[]{Fraction.ZERO, Fraction.ZERO, Fraction.ZERO});
			var bForce = result.computeIfAbsent(edge.second, ignored -> new Fraction[]{Fraction.ZERO, Fraction.ZERO, Fraction.ZERO});
			for (int a = 0; a < 3; a++) { int b = (a + 1) % 3, c = (a + 2) % 3;
				aForce[a] = aForce[a].add(second.get(b).multiply(term.normal[c]).subtract(second.get(c).multiply(term.normal[b])));
				bForce[a] = bForce[a].subtract(first.get(b).multiply(term.normal[c]).subtract(first.get(c).multiply(term.normal[b])));
			}
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
		for (var term : terms) for (int a = 0; a < 3; a++) for (int sign : new int[]{-1, 1}) {
			int b = (a + (sign > 0 ? 1 : 2)) % 3, c = 3 - a - b;
			var weight = term.normal[c].multiply(Fraction.of(sign)); if (weight.signum() == 0) continue;
			var first = map.axis(term.edge.first, a); var second = map.axis(term.edge.second, b);
			if (first.faces().length != 0 && second.faces().length != 0) result.add(new LiquidPressureFaceEnergy.Cross(first, second, LiquidBoundedArithmetic.Value.of(weight)));
		}
		return List.copyOf(result);
	}
}

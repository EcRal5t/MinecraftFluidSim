package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;
import cc.ecisr.mmm.fluid.LiquidExactPolygon.Vertex;
import cc.ecisr.mmm.fluid.LiquidBoundedArithmetic.Value;
import static cc.ecisr.mmm.fluid.LiquidBoundedArithmetic.ZERO;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reference-surface Dirichlet energy for implicit capillary work on the same triangular interface. */
final class LiquidSurfaceMetric {
	private record Triangle(List<Vertex> nodes, Fraction[][] matrix, Fraction[][] basis, Fraction weight) { }
	private final List<Triangle> triangles;
	private record ObservedTriangle(List<Vertex> nodes, Value[][] basis) { }
	private List<ObservedTriangle> observed;
	LiquidSurfaceMetric(List<LiquidExactPolygon> polygons, double[] coefficient) {
		if (coefficient.length != polygons.size()) throw new IllegalArgumentException("Incomplete surface metric coefficients");
		var result = new ArrayList<Triangle>();
		for (int f = 0; f < coefficient.length; f++) {
			double sigma = coefficient[f]; if (!(sigma >= 0) || !Double.isFinite(sigma)) throw new IllegalArgumentException("Surface metric requires nonnegative interface energy");
			if (sigma == 0) continue;
			var polygon = polygons.get(f); var points = polygon.vertices(); if (points.size() != 3) throw new IllegalArgumentException("Surface metric requires actual triangular faces");
			var e = new Fraction[2][3]; var origin = points.getFirst().coordinates();
			for (int i = 0; i < 2; i++) for (int a = 0; a < 3; a++) e[i][a] = points.get(i + 1).coordinates()[a].subtract(origin[a]);
			var g00 = dot(e[0], e[0]); var g01 = dot(e[0], e[1]); var g11 = dot(e[1], e[1]); var determinant = g00.multiply(g11).subtract(g01.multiply(g01));
			if (determinant.signum() <= 0) throw new IllegalArgumentException("Singular capillary reference triangle");
			var gradient = new Fraction[3][3];
			for (int a = 0; a < 3; a++) {
				gradient[1][a] = e[0][a].multiply(g11).subtract(e[1][a].multiply(g01)).divide(determinant);
				gradient[2][a] = e[1][a].multiply(g00).subtract(e[0][a].multiply(g01)).divide(determinant);
				gradient[0][a] = gradient[1][a].add(gradient[2][a]).negate();
			}
			var weight = Fraction.of(sigma).multiply(Fraction.of(polygon.surface().area())); var matrix = new Fraction[3][3];
			for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) matrix[i][j] = weight.multiply(dot(gradient[i], gradient[j]));
			result.add(new Triangle(points, matrix, gradient, weight));
		}
		triangles = List.copyOf(result);
	}
	/** Sparse Gram rows preserve the surface metric's positive energy after the pressure pullback. */
	List<LiquidPressureFaceEnergy.Row> pressureRows(LiquidSurfaceRateMap map) {
		var result = new ArrayList<LiquidPressureFaceEnergy.Row>();
		for (var triangle : triangles) for (int a = 0; a < 3; a++) for (int b = 0; b < 3; b++) {
			var forces = new LinkedHashMap<Vertex, List<Fraction>>();
			for (int i = 0; i < 3; i++) { var value = new Fraction[]{Fraction.ZERO, Fraction.ZERO, Fraction.ZERO}; value[a] = triangle.basis[i][b]; forces.put(triangle.nodes.get(i), List.of(value)); }
			var row = map.row(forces, LiquidBoundedArithmetic.Value.of(triangle.weight).sqrt()); if (row.faces().length != 0) result.add(row);
		}
		return List.copyOf(result);
	}
	/** Applies the nodal Gram factorization without expanding it into pressure-face rows. */
	Map<Vertex, Value[]> observedGradient(Map<Vertex, Value[]> values) {
		if (observed == null) observed = triangles.stream().map(triangle -> {
			var weight = Value.of(triangle.weight).sqrt(); var basis = new Value[3][3];
			for (int i = 0; i < 3; i++) for (int b = 0; b < 3; b++) basis[i][b] = weight.multiply(Value.of(triangle.basis[i][b]));
			return new ObservedTriangle(triangle.nodes, basis);
		}).toList();
		var result = new LinkedHashMap<Vertex, Value[]>();
		for (var triangle : observed) for (int a = 0; a < 3; a++) for (int b = 0; b < 3; b++) {
			var gradient = ZERO;
			for (int i = 0; i < 3; i++) gradient = gradient.add(triangle.basis[i][b].multiply(values.get(triangle.nodes.get(i))[a]));
			for (int i = 0; i < 3; i++) {
				var force = result.computeIfAbsent(triangle.nodes.get(i), ignored -> new Value[]{ZERO, ZERO, ZERO});
				force[a] = force[a].add(triangle.basis[i][b].multiply(gradient));
			}
		}
		return result;
	}
	/** Matrix action is the surface-energy gradient; an implicit force uses its negative. */
	Map<Vertex, List<Fraction>> gradient(Map<Vertex, List<Fraction>> values) {
		var result = new LinkedHashMap<Vertex, Fraction[]>();
		for (var triangle : triangles) for (int i = 0; i < 3; i++) {
			var gradient = result.computeIfAbsent(triangle.nodes.get(i), ignored -> new Fraction[]{Fraction.ZERO, Fraction.ZERO, Fraction.ZERO});
			for (int j = 0; j < 3; j++) {
				var value = values.get(triangle.nodes.get(j)); if (value == null || value.size() != 3) throw new IllegalArgumentException("Incomplete surface metric values");
				for (int a = 0; a < 3; a++) gradient[a] = gradient[a].add(triangle.matrix[i][j].multiply(value.get(a)));
			}
		}
		var load = new LinkedHashMap<Vertex, List<Fraction>>(); result.forEach((point, force) -> load.put(point, List.of(force)));
		return java.util.Collections.unmodifiableMap(load);
	}
	Fraction energy(Map<Vertex, List<Fraction>> positions) {
		var result = Fraction.ZERO;
		for (var entry : gradient(positions).entrySet()) result = result.add(dot(entry.getValue().toArray(Fraction[]::new), positions.get(entry.getKey()).toArray(Fraction[]::new)));
		return result.divide(Fraction.of(2));
	}
	private static Fraction dot(Fraction[] a, Fraction[] b) { var result = Fraction.ZERO; for (int i = 0; i < 3; i++) result = result.add(a[i].multiply(b[i])); return result; }
}

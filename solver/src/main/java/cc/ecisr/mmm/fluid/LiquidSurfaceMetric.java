package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;
import cc.ecisr.mmm.fluid.LiquidExactPolygon.Vertex;
import cc.ecisr.mmm.fluid.LiquidBoundedArithmetic.Value;
import static cc.ecisr.mmm.fluid.LiquidBoundedArithmetic.ZERO;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reference-surface Dirichlet energy for implicit capillary work on the same triangular interface. */
final class LiquidSurfaceMetric {
	private record Triangle(List<Vertex> nodes, BigInteger[][] basis, BigInteger denominator, Value[][] observedBasis, Fraction weight) { }
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
			var e = new BigInteger[2][3]; var scale = new BigInteger[2]; var origin = points.getFirst();
			for (int i = 0; i < 2; i++) {
				var point = points.get(i + 1); scale[i] = point.w().multiply(origin.w());
				for (int a = 0; a < 3; a++) e[i][a] = point.coordinate(a).multiply(origin.w()).subtract(origin.coordinate(a).multiply(point.w()));
			}
			// With e_i = E_i / s_i, the barycentric gradients share |E_0 x E_1|^2.
			var normal = cross(e[0], e[1]); var determinant = BigInteger.ZERO;
			for (var component : normal) determinant = determinant.add(component.multiply(component));
			if (determinant.signum() <= 0) throw new IllegalArgumentException("Singular capillary reference triangle");
			var gradient = new BigInteger[3][3]; var first = cross(e[1], normal); var second = cross(normal, e[0]);
			for (int a = 0; a < 3; a++) {
				gradient[1][a] = first[a].multiply(scale[0]);
				gradient[2][a] = second[a].multiply(scale[1]);
				gradient[0][a] = gradient[1][a].add(gradient[2][a]).negate();
			}
			var observedBasis = new Value[3][3];
			for (int i = 0; i < 3; i++) for (int a = 0; a < 3; a++) observedBasis[i][a] = Value.ratio(gradient[i][a], determinant);
			var weight = Fraction.of(sigma).multiply(Fraction.of(polygon.surface().area()));
			result.add(new Triangle(points, gradient, determinant, observedBasis, weight));
		}
		triangles = List.copyOf(result);
	}
	/** Sparse Gram rows preserve the surface metric's positive energy after the pressure pullback. */
	List<LiquidPressureFaceEnergy.Row> pressureRows(LiquidSurfaceRateMap map) {
		var result = new ArrayList<LiquidPressureFaceEnergy.Row>();
		for (var triangle : triangles) for (int a = 0; a < 3; a++) for (int b = 0; b < 3; b++) {
			var forces = new LinkedHashMap<Vertex, Value[]>();
			for (int i = 0; i < 3; i++) { var value = new Value[]{ZERO, ZERO, ZERO}; value[a] = triangle.observedBasis[i][b]; forces.put(triangle.nodes.get(i), value); }
			var row = map.observedRow(forces, Value.of(triangle.weight).sqrt()); if (row.faces().length != 0) result.add(row);
		}
		return List.copyOf(result);
	}
	/** Applies the nodal Gram factorization without expanding it into pressure-face rows. */
	Map<Vertex, Value[]> observedGradient(Map<Vertex, Value[]> values) {
		if (observed == null) observed = triangles.stream().map(triangle -> {
			var weight = Value.of(triangle.weight).sqrt(); var basis = new Value[3][3];
			for (int i = 0; i < 3; i++) for (int b = 0; b < 3; b++) basis[i][b] = weight.multiply(triangle.observedBasis[i][b]);
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
		for (var triangle : triangles) {
			var basis = new Fraction[3][3];
			for (int i = 0; i < 3; i++) for (int b = 0; b < 3; b++) basis[i][b] = new Fraction(triangle.basis[i][b], triangle.denominator);
			for (int a = 0; a < 3; a++) for (int b = 0; b < 3; b++) {
				var directional = Fraction.ZERO;
				for (int j = 0; j < 3; j++) {
					var value = values.get(triangle.nodes.get(j)); if (value == null || value.size() != 3) throw new IllegalArgumentException("Incomplete surface metric values");
					directional = directional.add(basis[j][b].multiply(value.get(a)));
				}
				for (int i = 0; i < 3; i++) {
					var gradient = result.computeIfAbsent(triangle.nodes.get(i), ignored -> new Fraction[]{Fraction.ZERO, Fraction.ZERO, Fraction.ZERO});
					gradient[a] = gradient[a].add(triangle.weight.multiply(basis[i][b]).multiply(directional));
				}
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
	private static BigInteger[] cross(BigInteger[] a, BigInteger[] b) {
		return new BigInteger[]{a[1].multiply(b[2]).subtract(a[2].multiply(b[1])), a[2].multiply(b[0]).subtract(a[0].multiply(b[2])), a[0].multiply(b[1]).subtract(a[1].multiply(b[0]))};
	}
}

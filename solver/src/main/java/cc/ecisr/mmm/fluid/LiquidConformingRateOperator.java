package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.LiquidBoundedArithmetic.Value;
import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;
import cc.ecisr.mmm.fluid.LiquidExactPolygon.Vertex;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import static cc.ecisr.mmm.fluid.LiquidBoundedArithmetic.*;

/** Continuous pressure-rate velocity and its transpose, with shared certified geometry coefficients. */
final class LiquidConformingRateOperator {
	private record Face(Value[] rate, Value[][] corner) { }
	private final LiquidConformingVelocity field;
	private final Map<Vertex, Map<Integer, Value>> corners;
	private final List<Map<Vertex, Value>> faceWeights;
	private final Map<Vertex, Value[][]> walls;
	private final List<Value> meanWeights;
	private final IntFunction<Fraction[][]> exactMeanBasis;
	private final Map<Integer, Value[][]> meanBasis = new java.util.HashMap<>();
	private final Map<Integer, Face> faces = new java.util.HashMap<>();

	LiquidConformingRateOperator(LiquidConformingVelocity field, Map<Vertex, Map<Integer, Fraction>> corners,
			List<Map<Vertex, Fraction>> faceWeights, Map<Vertex, List<Fraction[]>> walls,
			List<Fraction> meanWeights, IntFunction<Fraction[][]> exactMeanBasis) {
		this.field = field; this.exactMeanBasis = exactMeanBasis;
		var observedCorners = new LinkedHashMap<Vertex, Map<Integer, Value>>();
		corners.forEach((point, weights) -> { var observed = new LinkedHashMap<Integer, Value>(); weights.forEach((cell, weight) -> observed.put(cell, Value.of(weight))); observedCorners.put(point, java.util.Collections.unmodifiableMap(observed)); });
		this.corners = java.util.Collections.unmodifiableMap(observedCorners);
		this.faceWeights = faceWeights.stream().map(weights -> { var observed = new LinkedHashMap<Vertex, Value>(); weights.forEach((point, weight) -> observed.put(point, Value.of(weight))); return java.util.Collections.unmodifiableMap(observed); }).toList();
		var projections = new LinkedHashMap<Vertex, Value[][]>(); walls.forEach((point, normals) -> projections.put(point, wallProjection(normals))); this.walls = java.util.Collections.unmodifiableMap(projections);
		this.meanWeights = meanWeights.stream().map(Value::of).toList();
	}
	Value[] apply(Map<Vertex, List<Fraction>> forces) {
		var observed = new LinkedHashMap<Vertex, Value[]>(); forces.forEach((point, force) -> observed.put(point, force.stream().map(Value::of).toArray(Value[]::new)));
		return applyObserved(observed);
	}
	Value[] applyObserved(Map<Vertex, Value[]> forces) {
		var nodes = new LinkedHashMap<Vertex, Value[]>(); for (var point : field.velocity.keySet()) nodes.put(point, zero());
		for (var entry : forces.entrySet()) {
			if (!nodes.containsKey(entry.getKey()) || entry.getValue().length != 3) throw new IllegalArgumentException("Force has no continuous velocity support");
			add(nodes.get(entry.getKey()), entry.getValue());
		}
		var primary = new Value[field.mesh.cells.size()][3]; for (int c = 0; c < primary.length; c++) {
			primary[c] = zero(); var force = nodes.get(field.cellCenters.get(c));
			if (force != null) for (int a = 0; a < 3; a++) primary[c][a] = force[a].multiply(new Value(4, 0));
		}
		for (int i = 0; i < field.tetrahedra.size(); i++) {
			var tet = field.tetrahedra.get(i); var force = scale(nodes.get(field.cellCenters.get(tet.cell())), meanWeights.get(i));
			for (int v = 1; v < 4; v++) add(nodes.get(tet.vertices().get(v)), force);
		}
		var result = new Value[field.mesh.faces.size()]; java.util.Arrays.fill(result, ZERO);
		for (int f = 0; f < result.length; f++) {
			var input = nodes.get(field.faceCenters.get(f)); if (input == null) continue;
			var face = faces.computeIfAbsent(f, this::face);
			result[f] = dot(face.rate, input); var preferred = multiply(face.corner, input);
			for (var weight : faceWeights.get(f).entrySet()) add(nodes.get(weight.getKey()), scale(preferred, weight.getValue()));
		}
		for (var corner : corners.entrySet()) {
			var force = nodes.get(corner.getKey()); var projection = walls.get(corner.getKey());
			if (projection != null) force = multiply(projection, force);
			for (var weight : corner.getValue().entrySet()) add(primary[weight.getKey()], scale(force, weight.getValue()));
		}
		for (int c = 0; c < primary.length; c++) if (nodes.containsKey(field.cellCenters.get(c))) {
			var basis = meanBasis(c);
			for (int i = 0; i < basis.length; i++) { int f = field.mesh.cellFaces[c][i]; result[f] = result[f].add(dot(basis[i], primary[c])); }
		}
		return result;
	}
	/** Forward and transpose share every observed geometry coefficient. */
	Map<Vertex, Value[]> velocity(Value[] rate) {
		if (rate.length != field.mesh.faces.size()) throw new IllegalArgumentException("Incomplete virtual face rates");
		var primary = new Value[field.mesh.cells.size()][3];
		for (int c = 0; c < primary.length; c++) {
			primary[c] = zero(); if (!field.velocity.containsKey(field.cellCenters.get(c))) continue;
			var basis = meanBasis(c);
			for (int i = 0; i < basis.length; i++) add(primary[c], scale(basis[i], rate[field.mesh.cellFaces[c][i]]));
		}
		var nodes = new LinkedHashMap<Vertex, Value[]>();
		for (var corner : corners.entrySet()) {
			var value = zero(); for (var weight : corner.getValue().entrySet()) add(value, scale(primary[weight.getKey()], weight.getValue()));
			var projection = walls.get(corner.getKey()); nodes.put(corner.getKey(), projection == null ? value : multiply(projection, value));
		}
		for (int f = 0; f < rate.length; f++) if (!faceWeights.get(f).isEmpty()) {
			var preferred = zero(); for (var weight : faceWeights.get(f).entrySet()) add(preferred, scale(nodes.get(weight.getKey()), weight.getValue()));
			var face = faces.computeIfAbsent(f, this::face); var value = multiply(face.corner, preferred); add(value, scale(face.rate, rate[f]));
			nodes.put(field.faceCenters.get(f), value);
		}
		var apex = new Value[primary.length][]; for (int c = 0; c < apex.length; c++) apex[c] = scale(primary[c], new Value(4, 0));
		for (int i = 0; i < field.tetrahedra.size(); i++) {
			var tet = field.tetrahedra.get(i);
			for (int v = 1; v < 4; v++) add(apex[tet.cell()], scale(nodes.get(tet.vertices().get(v)), meanWeights.get(i)));
		}
		for (int c = 0; c < primary.length; c++) if (field.velocity.containsKey(field.cellCenters.get(c))) nodes.put(field.cellCenters.get(c), apex[c]);
		return java.util.Collections.unmodifiableMap(nodes);
	}
	private Value[][] meanBasis(int cell) {
		return meanBasis.computeIfAbsent(cell, c -> {
			var exact = exactMeanBasis.apply(c); var observed = new Value[exact.length][3];
			for (int i = 0; i < exact.length; i++) for (int a = 0; a < 3; a++) observed[i][a] = Value.of(exact[i][a]); return observed;
		});
	}
	private Face face(int face) {
		var polygon = field.mesh.exactPolygons.get(face); var plane = polygon.coefficients();
		var normal = new BigInteger[]{plane.get(0), plane.get(1), plane.get(2)}; var norm = dot(normal, normal);
		int axis = 0; while (normal[axis].signum() == 0) axis++;
		var area = polygon.areaVector()[axis]; var rate = new Value[3]; var corner = new Value[3][3];
		for (int a = 0; a < 3; a++) {
			rate[a] = Value.ratio(BigInteger.valueOf(3).multiply(normal[a]).multiply(normal[axis]).multiply(area.denominator()), norm.multiply(area.numerator()));
			for (int b = 0; b < 3; b++) {
				var numerator = (a == b ? norm : BigInteger.ZERO).subtract(BigInteger.valueOf(3).multiply(normal[a]).multiply(normal[b]));
				corner[a][b] = Value.ratio(numerator, norm);
			}
		}
		return new Face(rate, corner);
	}
	/** Orthogonal tangent projection depends on the exact normal span, independently of normal lengths. */
	private static Value[][] wallProjection(List<Fraction[]> normals) {
		var basis = new ArrayList<BigInteger[]>();
		for (var normal : normals) {
			var homogeneous = LiquidExactPolygon.vertex(normal); var vector = new BigInteger[]{homogeneous.x(), homogeneous.y(), homogeneous.z()};
			if (dot(vector, vector).signum() == 0) continue;
			boolean independent = basis.isEmpty();
			if (basis.size() == 1) independent = java.util.Arrays.stream(cross(basis.getFirst(), vector)).anyMatch(value -> value.signum() != 0);
			if (basis.size() == 2) independent = dot(cross(basis.getFirst(), basis.get(1)), vector).signum() != 0;
			if (independent) basis.add(vector); if (basis.size() == 3) break;
		}
		var result = new Value[3][3];
		var direction = basis.size() == 1 ? basis.getFirst() : basis.size() == 2 ? cross(basis.getFirst(), basis.get(1)) : null;
		var norm = direction == null ? BigInteger.ONE : dot(direction, direction);
		for (int a = 0; a < 3; a++) for (int b = 0; b < 3; b++) {
			var identity = Fraction.of(a == b ? 1 : 0);
			var projected = direction == null ? Fraction.ZERO : new Fraction(direction[a].multiply(direction[b]), norm);
			result[a][b] = Value.of(switch (basis.size()) { case 0 -> identity; case 1 -> identity.subtract(projected); case 2 -> projected; default -> Fraction.ZERO; });
		}
		return result;
	}
	private static Value[] zero() { return new Value[]{ZERO, ZERO, ZERO}; }
	private static void add(Value[] target, Value[] value) { for (int a = 0; a < 3; a++) target[a] = target[a].add(value[a]); }
	private static Value[] scale(Value[] value, Value weight) { var result = zero(); for (int a = 0; a < 3; a++) result[a] = value[a].multiply(weight); return result; }
	private static Value dot(Value[] first, Value[] second) { var result = ZERO; for (int a = 0; a < 3; a++) result = result.add(first[a].multiply(second[a])); return result; }
	private static Value[] multiply(Value[][] matrix, Value[] value) { var result = zero(); for (int a = 0; a < 3; a++) result[a] = dot(matrix[a], value); return result; }
	private static BigInteger dot(BigInteger[] first, BigInteger[] second) { var result = BigInteger.ZERO; for (int a = 0; a < 3; a++) result = result.add(first[a].multiply(second[a])); return result; }
	private static BigInteger[] cross(BigInteger[] first, BigInteger[] second) {
		return new BigInteger[]{first[1].multiply(second[2]).subtract(first[2].multiply(second[1])), first[2].multiply(second[0]).subtract(first[0].multiply(second[2])), first[0].multiply(second[1]).subtract(first[1].multiply(second[0]))};
	}
}

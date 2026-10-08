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
	private final Map<Vertex, Map<Vertex, Value>> surfaceWeights;
	private final Map<Vertex, Value[][]> walls;
	private final List<Value> meanWeights;
	private final IntFunction<Fraction[][]> exactMeanBasis;
	private final Map<Integer, Value[][]> meanBasis = new java.util.HashMap<>();
	private final Map<Integer, Face> faces = new java.util.HashMap<>();
	private final Map<Vertex, List<Integer>> apexCells = new LinkedHashMap<>(), centerFaces = new LinkedHashMap<>();
	private final Map<Integer, List<Integer>> cellTetrahedra = new LinkedHashMap<>();
	private final List<Vertex> surfaceNodes, cornerNodes;
	private final Map<Vertex, Integer> surfaceOrder = new LinkedHashMap<>(), cornerOrder = new LinkedHashMap<>();

	LiquidConformingRateOperator(LiquidConformingVelocity field, Map<Vertex, Map<Integer, Fraction>> corners,
			List<Map<Vertex, Fraction>> faceWeights, Map<Vertex, List<Fraction[]>> walls,
			List<Fraction> meanWeights, IntFunction<Fraction[][]> exactMeanBasis) {
		this.field = field; this.exactMeanBasis = exactMeanBasis;
		var observedCorners = new LinkedHashMap<Vertex, Map<Integer, Value>>();
		corners.forEach((point, weights) -> { var observed = new LinkedHashMap<Integer, Value>(); weights.forEach((cell, weight) -> observed.put(cell, Value.of(weight))); observedCorners.put(point, java.util.Collections.unmodifiableMap(observed)); });
		this.corners = java.util.Collections.unmodifiableMap(observedCorners);
		this.faceWeights = faceWeights.stream().map(weights -> { var observed = new LinkedHashMap<Vertex, Value>(); weights.forEach((point, weight) -> observed.put(point, Value.of(weight))); return java.util.Collections.unmodifiableMap(observed); }).toList();
		var restrictions = new LinkedHashMap<Vertex, Map<Vertex, Value>>();
		field.surfaceWeights.forEach((point, weights) -> { var observed = new LinkedHashMap<Vertex, Value>(); weights.forEach((parent, weight) -> observed.put(parent, Value.of(weight))); restrictions.put(point, java.util.Collections.unmodifiableMap(observed)); });
		surfaceWeights = java.util.Collections.unmodifiableMap(restrictions);
		var projections = new LinkedHashMap<Vertex, Value[][]>(); walls.forEach((point, normals) -> projections.put(point, wallProjection(normals))); this.walls = java.util.Collections.unmodifiableMap(projections);
		this.meanWeights = meanWeights.stream().map(Value::of).toList();
		for (int c = 0; c < field.cellApexes.size(); c++) for (var point : field.cellApexes.get(c)) apexCells.computeIfAbsent(point, ignored -> new ArrayList<>()).add(c);
		for (int f = 0; f < field.faceCenters.size(); f++) centerFaces.computeIfAbsent(field.faceCenters.get(f), ignored -> new ArrayList<>()).add(f);
		for (int i = 0; i < field.tetrahedra.size(); i++) cellTetrahedra.computeIfAbsent(field.tetrahedra.get(i).cell(), ignored -> new ArrayList<>()).add(i);
		surfaceNodes = List.copyOf(surfaceWeights.keySet()); cornerNodes = List.copyOf(this.corners.keySet());
		for (int i = 0; i < surfaceNodes.size(); i++) surfaceOrder.put(surfaceNodes.get(i), i);
		for (int i = 0; i < cornerNodes.size(); i++) cornerOrder.put(cornerNodes.get(i), i);
	}
	/** Visits active transpose dependencies in the forward operator's original reduction order. */
	Map<Integer, Value> applySparse(Map<Vertex, Value[]> forces) {
		var nodes = new LinkedHashMap<Vertex, Value[]>(); var primary = new java.util.TreeMap<Integer, Value[]>();
		for (var entry : forces.entrySet()) {
			if (!field.velocity.containsKey(entry.getKey()) || entry.getValue().length != 3) throw new IllegalArgumentException("Force has no continuous velocity support");
			nodes.put(entry.getKey(), entry.getValue().clone());
			for (int c : apexCells.getOrDefault(entry.getKey(), List.of())) primary.putIfAbsent(c, zero());
		}
		var apexForce = new java.util.TreeMap<Integer, Value[]>(); var tetrahedra = new java.util.TreeSet<Integer>();
		for (var entry : primary.entrySet()) {
			int c = entry.getKey(); var value = entry.getValue();
			for (var point : field.cellApexes.get(c)) if (nodes.containsKey(point)) add(value, nodes.get(point));
			apexForce.put(c, value.clone()); entry.setValue(scale(value, new Value(4, 0)));
			tetrahedra.addAll(cellTetrahedra.getOrDefault(c, List.of()));
		}
		for (int i : tetrahedra) {
			var tet = field.tetrahedra.get(i); var force = scale(apexForce.get(tet.cell()), meanWeights.get(i));
			for (int v = 1; v < 4; v++) add(nodes.computeIfAbsent(tet.vertices().get(v), ignored -> zero()), force);
		}
		for (var entry : apexForce.entrySet()) for (var point : field.cellApexes.get(entry.getKey()))
			add(nodes.computeIfAbsent(point, ignored -> zero()), scale(entry.getValue(), Value.of(field.apexWeights.get(point)).negate()));
		var pending = new java.util.TreeSet<Integer>();
		for (var point : nodes.keySet()) if (surfaceOrder.containsKey(point)) pending.add(surfaceOrder.get(point));
		while (!pending.isEmpty()) {
			int index = pending.pollFirst(); var point = surfaceNodes.get(index); var input = nodes.get(point);
			for (var weight : surfaceWeights.get(point).entrySet()) {
				add(nodes.computeIfAbsent(weight.getKey(), ignored -> zero()), scale(input, weight.getValue()));
				var next = surfaceOrder.get(weight.getKey()); if (next != null && next > index) pending.add(next);
			}
		}
		for (var point : nodes.keySet()) pending.addAll(centerFaces.getOrDefault(point, List.of()));
		var result = new java.util.TreeMap<Integer, Value>();
		while (!pending.isEmpty()) {
			int f = pending.pollFirst(); var input = nodes.get(field.faceCenters.get(f)); var face = faces.computeIfAbsent(f, this::face);
			result.put(f, dot(face.rate, input)); var preferred = multiply(face.corner, input);
			for (var weight : faceWeights.get(f).entrySet()) {
				add(nodes.computeIfAbsent(weight.getKey(), ignored -> zero()), scale(preferred, weight.getValue()));
				for (int next : centerFaces.getOrDefault(weight.getKey(), List.of())) if (next > f) pending.add(next);
			}
		}
		for (var point : nodes.keySet()) if (cornerOrder.containsKey(point)) pending.add(cornerOrder.get(point));
		while (!pending.isEmpty()) {
			var point = cornerNodes.get(pending.pollFirst()); var force = nodes.get(point); var projection = walls.get(point);
			if (projection != null) force = multiply(projection, force);
			for (var weight : corners.get(point).entrySet()) add(primary.computeIfAbsent(weight.getKey(), ignored -> zero()), scale(force, weight.getValue()));
		}
		for (var entry : primary.entrySet()) if (field.velocity.containsKey(field.cellCenters.get(entry.getKey()))) {
			int c = entry.getKey(); var basis = meanBasis(c);
			for (int i = 0; i < basis.length; i++) result.merge(field.mesh.cellFaces[c][i], dot(basis[i], entry.getValue()), Value::add);
		}
		result.entrySet().removeIf(entry -> entry.getValue().value() == 0 && entry.getValue().error() == 0);
		return result;
	}
	Value[] apply(Map<Vertex, List<Fraction>> forces) {
		var observed = new LinkedHashMap<Vertex, Value[]>(); forces.forEach((point, force) -> observed.put(point, force.stream().map(Value::of).toArray(Value[]::new)));
		return applyObserved(observed);
	}
	Value[] applyObserved(Map<Vertex, Value[]> forces) {
		var result = new Value[field.mesh.faces.size()]; java.util.Arrays.fill(result, ZERO);
		applySparse(forces).forEach((face, value) -> result[face] = value);
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
		for (var entry : surfaceWeights.entrySet()) if (field.velocity.containsKey(entry.getKey())) {
			var value = zero(); for (var weight : entry.getValue().entrySet()) add(value, scale(nodes.get(weight.getKey()), weight.getValue()));
			nodes.put(entry.getKey(), value);
		}
		for (int c = 0; c < primary.length; c++) for (var point : field.cellApexes.get(c)) nodes.putIfAbsent(point, primary[c].clone());
		var apex = new Value[primary.length][]; for (int c = 0; c < apex.length; c++) apex[c] = scale(primary[c], new Value(4, 0));
		for (int i = 0; i < field.tetrahedra.size(); i++) {
			var tet = field.tetrahedra.get(i);
			for (int v = 0; v < 4; v++) add(apex[tet.cell()], scale(nodes.get(tet.vertices().get(v)), meanWeights.get(i)));
		}
		for (int c = 0; c < primary.length; c++) for (var point : field.cellApexes.get(c)) add(nodes.get(point), apex[c]);
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

package cc.ecisr.mmm.fluid;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

/** Exact plane intersections and planar clipping; integration rounds each coordinate once. */
final class LiquidExactPolygon {
	private record Vertex(BigInteger x, BigInteger y, BigInteger z, BigInteger w) {
		Vertex {
			if (w.signum() == 0) throw new IllegalArgumentException("Singular geometric vertex");
			BigInteger gcd = x.abs().gcd(y.abs()).gcd(z.abs()).gcd(w.abs());
			if (w.signum() < 0) gcd = gcd.negate();
			x = x.divide(gcd); y = y.divide(gcd); z = z.divide(gcd); w = w.divide(gcd);
		}
		BigInteger coordinate(int axis) { return axis == 0 ? x : axis == 1 ? y : z; }
		BigInteger distance(List<BigInteger> plane) {
			return plane.get(0).multiply(x).add(plane.get(1).multiply(y)).add(plane.get(2).multiply(z)).subtract(plane.get(3).multiply(w));
		}
		LiquidPolyhedron.Point rounded() { return new LiquidPolyhedron.Point(ratio(x, w), ratio(y, w), ratio(z, w)); }
		private static double ratio(BigInteger numerator, BigInteger denominator) {
			if (numerator.signum() == 0) return 0;
			var magnitude = numerator.abs(); int exponent = magnitude.bitLength() - denominator.bitLength();
			if ((exponent >= 0 ? magnitude.compareTo(denominator.shiftLeft(exponent)) : magnitude.shiftLeft(-exponent).compareTo(denominator)) < 0) exponent--;
			int shift = Math.min(52 - exponent, 1074); // Binary64 significand, including subnormal rounding.
			var dividend = shift >= 0 ? magnitude.shiftLeft(shift) : magnitude;
			var divisor = shift >= 0 ? denominator : denominator.shiftLeft(-shift);
			var division = dividend.divideAndRemainder(divisor); int comparison = division[1].shiftLeft(1).compareTo(divisor);
			if (comparison > 0 || comparison == 0 && division[0].testBit(0)) division[0] = division[0].add(BigInteger.ONE);
			return Math.copySign(Math.scalb(division[0].doubleValue(), -shift), numerator.signum());
		}
	}
	private final List<Vertex> vertices;
	private final List<BigInteger> normal;
	private LiquidExactPolygon(List<Vertex> vertices, List<BigInteger> normal) { this.vertices = List.copyOf(vertices); this.normal = normal; }
	List<LiquidPolyhedron.Point> rounded() { return vertices.stream().map(Vertex::rounded).toList(); }
	boolean hasArea() {
		if (vertices.size() < 3) return false;
		var a = vertices.getFirst();
		for (int i = 1; i + 1 < vertices.size(); i++) {
			var b = difference(vertices.get(i), a); var c = difference(vertices.get(i + 1), a);
			for (var value : cross(b, c)) if (value.signum() != 0) return true;
		}
		return false;
	}

	/** Certify the existing surface incidence first; ambiguous geometry uses the complete plane arrangement. */
	static List<LiquidExactPolygon> faces(LiquidPolyhedron geometry) {
		var planes = geometry.planes(); var coefficients = planes.stream().map(p -> p.key().coefficients()).toList();
		var incidence = new java.util.LinkedHashMap<LiquidPolyhedron.Point, ArrayList<Integer>>();
		for (int f = 0; f < geometry.faces().size(); f++) for (var point : geometry.faces().get(f))
			incidence.computeIfAbsent(point, ignored -> new ArrayList<>()).add(f);
		var vertices = new java.util.HashMap<LiquidPolyhedron.Point, Vertex>(); var distinct = new java.util.HashSet<Vertex>();
		for (var entry : incidence.entrySet()) {
			var support = entry.getValue(); Vertex vertex = null;
			for (int a = 0; a < support.size() && vertex == null; a++) for (int b = 0; b < a && vertex == null; b++) for (int c = 0; c < b && vertex == null; c++)
				vertex = intersect(coefficients.get(support.get(a)), coefficients.get(support.get(b)), coefficients.get(support.get(c)));
			if (vertex == null) return completeFaces(geometry);
			for (int p = 0; p < planes.size(); p++) {
				int sign = vertex.distance(coefficients.get(p)).signum() * (planes.get(p).positive() ? 1 : -1);
				if (sign > 0 || sign != 0 && support.contains(p)) return completeFaces(geometry);
			}
			if (!distinct.add(vertex)) return completeFaces(geometry);
			vertices.put(entry.getKey(), vertex);
		}
		var result = new ArrayList<LiquidExactPolygon>(); var edges = new java.util.HashMap<List<Vertex>, Integer>();
		for (int f = 0; f < planes.size(); f++) {
			var face = geometry.faces().get(f).stream().map(vertices::get).toList(); var n = orientedNormal(planes.get(f)); boolean area = false;
			for (int v = 0; v < face.size(); v++) {
				var a = face.get(v); var b = face.get((v + 1) % face.size()); var c = face.get((v + 2) % face.size());
				var cross = cross(difference(b, a), difference(c, b)); var orientation = BigInteger.ZERO;
				for (int axis = 0; axis < 3; axis++) orientation = orientation.add(n.get(axis).multiply(cross.get(axis)));
				if (orientation.signum() < 0 || a.equals(b)) return completeFaces(geometry);
				area |= orientation.signum() > 0; edges.merge(List.of(a, b), 1, Math::addExact);
			}
			if (!area) return completeFaces(geometry);
			result.add(new LiquidExactPolygon(face, n));
		}
		for (var edge : edges.entrySet()) if (edge.getValue() != 1 || edges.getOrDefault(edge.getKey().reversed(), 0) != 1) return completeFaces(geometry);
		return List.copyOf(result);
	}
	private static List<LiquidExactPolygon> completeFaces(LiquidPolyhedron geometry) {
		var result = new ArrayList<LiquidExactPolygon>();
		for (int f = 0; f < geometry.faces().size(); f++) result.add(face(geometry, f));
		return List.copyOf(result);
	}
	private static List<BigInteger> orientedNormal(LiquidGeometryPlane plane) {
		var normal = new ArrayList<>(plane.key().coefficients().subList(0, 3));
		if (!plane.positive()) normal.replaceAll(BigInteger::negate);
		return List.copyOf(normal);
	}

	static LiquidExactPolygon face(LiquidPolyhedron geometry, int face) {
		var planes = geometry.planes(); var coefficients = planes.stream().map(p -> p.key().coefficients()).toList();
		var points = new LinkedHashSet<Vertex>(); var selected = coefficients.get(face);
		for (int a = 0; a < planes.size(); a++) if (a != face) for (int b = 0; b < a; b++) if (b != face) {
			var vertex = intersect(selected, coefficients.get(a), coefficients.get(b)); if (vertex == null) continue;
			boolean inside = true;
			for (int p = 0; p < planes.size() && inside; p++) inside = vertex.distance(coefficients.get(p)).signum() * (planes.get(p).positive() ? 1 : -1) <= 0;
			if (inside) points.add(vertex);
		}
		var normal = orientedNormal(planes.get(face));
		var ordered = new ArrayList<>(points);
		if (ordered.size() >= 3) {
			var center = LiquidPolyhedron.Moments.ZERO;
			for (var vertex : ordered) { var p = vertex.rounded(); center = center.add(new LiquidPolyhedron.Moments(1, p.x(), p.y(), p.z())); }
			var origin = new LiquidPolyhedron.Point(center.centroid(0), center.centroid(1), center.centroid(2)); var n = planes.get(face).normal();
			var tangent = Math.abs(n.x()) <= Math.abs(n.y()) && Math.abs(n.x()) <= Math.abs(n.z()) ? n.cross(new LiquidPolyhedron.Point(1, 0, 0))
					: Math.abs(n.y()) <= Math.abs(n.z()) ? n.cross(new LiquidPolyhedron.Point(0, 1, 0)) : n.cross(new LiquidPolyhedron.Point(0, 0, 1));
			var bitangent = n.cross(tangent);
			ordered.sort(Comparator.comparingDouble(v -> Math.atan2(v.rounded().subtract(origin).dot(bitangent), v.rounded().subtract(origin).dot(tangent))));
		}
		return new LiquidExactPolygon(ordered, List.copyOf(normal));
	}

	LiquidExactPolygon intersection(LiquidExactPolygon cutter) {
		if (sameSupport(cutter)) return this;
		var result = this;
		for (int edge = 0; edge < cutter.vertices.size() && result.hasArea(); edge++) result = result.clip(cutter.edgePlane(edge), false);
		return result;
	}
	List<LiquidExactPolygon> subtract(LiquidExactPolygon cutter) {
		if (sameSupport(cutter)) return List.of();
		var remaining = new ArrayList<LiquidExactPolygon>(); var inside = this;
		for (int edge = 0; edge < cutter.vertices.size() && inside.hasArea(); edge++) {
			var plane = cutter.edgePlane(edge); var outside = inside.clip(plane, true);
			if (outside.hasArea()) remaining.add(outside);
			inside = inside.clip(plane, false);
		}
		return remaining;
	}
	private boolean sameSupport(LiquidExactPolygon other) {
		return vertices.size() == other.vertices.size() && vertices.containsAll(other.vertices);
	}
	private List<BigInteger> edgePlane(int edge) {
		var a = vertices.get(edge); var b = vertices.get((edge + 1) % vertices.size());
		var outward = cross(difference(b, a), normal);
		return List.of(outward.get(0).multiply(a.w), outward.get(1).multiply(a.w), outward.get(2).multiply(a.w),
				outward.get(0).multiply(a.x).add(outward.get(1).multiply(a.y)).add(outward.get(2).multiply(a.z)));
	}
	private LiquidExactPolygon clip(List<BigInteger> plane, boolean reverse) {
		if (vertices.isEmpty()) return this;
		var output = new ArrayList<Vertex>(); var previous = vertices.getLast(); var before = previous.distance(plane);
		for (var current : vertices) {
			var after = current.distance(plane);
			if (before.signum() * after.signum() < 0) output.add(new Vertex(after.multiply(previous.x).subtract(before.multiply(current.x)),
					after.multiply(previous.y).subtract(before.multiply(current.y)), after.multiply(previous.z).subtract(before.multiply(current.z)),
					after.multiply(previous.w).subtract(before.multiply(current.w))));
			if (after.signum() * (reverse ? -1 : 1) <= 0) output.add(current);
			previous = current; before = after;
		}
		return new LiquidExactPolygon(output, normal);
	}
	private static Vertex intersect(List<BigInteger> a, List<BigInteger> b, List<BigInteger> c) {
		var bc = cross(b, c); var ca = cross(c, a); var ab = cross(a, b);
		var determinant = a.get(0).multiply(bc.get(0)).add(a.get(1).multiply(bc.get(1))).add(a.get(2).multiply(bc.get(2)));
		if (determinant.signum() == 0) return null;
		var coordinates = new BigInteger[3];
		for (int axis = 0; axis < 3; axis++) coordinates[axis] = a.get(3).multiply(bc.get(axis)).add(b.get(3).multiply(ca.get(axis))).add(c.get(3).multiply(ab.get(axis)));
		return new Vertex(coordinates[0], coordinates[1], coordinates[2], determinant);
	}
	private static List<BigInteger> difference(Vertex b, Vertex a) {
		return List.of(b.x.multiply(a.w).subtract(a.x.multiply(b.w)), b.y.multiply(a.w).subtract(a.y.multiply(b.w)), b.z.multiply(a.w).subtract(a.z.multiply(b.w)));
	}
	private static List<BigInteger> cross(List<BigInteger> a, List<BigInteger> b) {
		return List.of(a.get(1).multiply(b.get(2)).subtract(a.get(2).multiply(b.get(1))), a.get(2).multiply(b.get(0)).subtract(a.get(0).multiply(b.get(2))),
				a.get(0).multiply(b.get(1)).subtract(a.get(1).multiply(b.get(0))));
	}
}

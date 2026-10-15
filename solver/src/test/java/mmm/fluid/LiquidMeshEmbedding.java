package mmm.fluid;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import mmm.chemistry.ExactRational;
import mmm.fluid.GasGeometryOverlap.Point;

/** Exact reference certification of disjoint arrival interiors using homogeneous integer SAT. */
final class LiquidMeshEmbedding {
	private LiquidMeshEmbedding() { }
	private record Vertex(BigInteger x, BigInteger y, BigInteger z, BigInteger weight) { }
	private record Vector(BigInteger x, BigInteger y, BigInteger z) { }
	private record Projection(BigInteger value, BigInteger weight) { }
	private record Tetra(int index, Vertex[] vertices, Vector[] normals, Vector[] edges,
	                     ExactRational[] lower, ExactRational[] upper) { }

	static void requireDisjoint(List<LiquidMappedTetrahedron> maps, int maximumPairTests) {
		if (maps == null || maximumPairTests < 0)
			throw new IllegalArgumentException("Liquid mesh embedding requires maps and a nonnegative pair budget");
		var tetrahedra = new ArrayList<Tetra>(maps.size());
		for (int i = 0; i < maps.size(); i++) {
			var map = maps.get(i);
			if (map == null) throw new IllegalArgumentException("Liquid mesh embedding requires complete tetrahedra");
			tetrahedra.add(capture(i, map.arrivalVertices()));
		}
		tetrahedra.sort(Comparator.comparing((Tetra tetra) -> tetra.lower[0]).thenComparingInt(Tetra::index));
		int tested = 0;
		for (int i = 0; i < tetrahedra.size(); i++) {
			var first = tetrahedra.get(i);
			for (int j = i + 1; j < tetrahedra.size(); j++) {
				var second = tetrahedra.get(j);
				if (second.lower[0].compareTo(first.upper[0]) >= 0) break;
				if (separateBoxes(first, second)) continue;
				if (tested == maximumPairTests)
					throw new GasTopologyBlockedException("Liquid arrival mesh exceeds its exact intersection pair budget");
				tested++;
				if (overlap(first, second)) throw new GasTopologyBlockedException(
						"Liquid arrival tetrahedra overlap with positive volume: " + first.index + ", " + second.index);
			}
		}
	}

	private static Tetra capture(int index, List<Point> vertices) {
		var lower = new ExactRational[3]; var upper = new ExactRational[3];
		for (int a = 0; a < 3; a++) {
			lower[a] = vertices.getFirst().component(a); upper[a] = lower[a];
			for (var vertex : vertices) {
				if (vertex.component(a).compareTo(lower[a]) < 0) lower[a] = vertex.component(a);
				if (vertex.component(a).compareTo(upper[a]) > 0) upper[a] = vertex.component(a);
			}
		}
		// Positive homogeneous weights preserve projection order without rational reduction inside SAT.
		var homogeneous = new Vertex[4];
		for (int i = 0; i < 4; i++) homogeneous[i] = homogeneous(vertices.get(i));
		var edges = new Vector[6]; int next = 0;
		for (int i = 0; i < 4; i++) for (int j = i + 1; j < 4; j++) edges[next++] = subtract(homogeneous[j], homogeneous[i]);
		var normals = new Vector[4];
		for (int omitted = 0; omitted < 4; omitted++) {
			var face = new Vertex[3]; next = 0;
			for (int i = 0; i < 4; i++) if (i != omitted) face[next++] = homogeneous[i];
			normals[omitted] = cross(subtract(face[1], face[0]), subtract(face[2], face[0]));
		}
		return new Tetra(index, homogeneous, normals, edges, lower, upper);
	}
	private static boolean separateBoxes(Tetra first, Tetra second) {
		for (int a = 0; a < 3; a++) if (first.upper[a].compareTo(second.lower[a]) <= 0
				|| second.upper[a].compareTo(first.lower[a]) <= 0) return true;
		return false;
	}
	private static boolean overlap(Tetra first, Tetra second) {
		for (var normal : first.normals) if (separates(normal, first, second)) return false;
		for (var normal : second.normals) if (separates(normal, first, second)) return false;
		for (var edge : first.edges) for (var other : second.edges)
			if (separates(cross(edge, other), first, second)) return false;
		return true;
	}
	private static boolean separates(Vector axis, Tetra first, Tetra second) {
		if (axis.x.signum() == 0 && axis.y.signum() == 0 && axis.z.signum() == 0) return false;
		var firstLow = dot(axis, first.vertices[0]); var firstHigh = firstLow;
		var secondLow = dot(axis, second.vertices[0]); var secondHigh = secondLow;
		for (int i = 1; i < 4; i++) {
			var value = dot(axis, first.vertices[i]);
			if (compare(value, firstLow) < 0) firstLow = value;
			if (compare(value, firstHigh) > 0) firstHigh = value;
			value = dot(axis, second.vertices[i]);
			if (compare(value, secondLow) < 0) secondLow = value;
			if (compare(value, secondHigh) > 0) secondHigh = value;
		}
		return compare(firstHigh, secondLow) <= 0 || compare(secondHigh, firstLow) <= 0;
	}
	private static Vertex homogeneous(Point point) {
		var weight = BigInteger.ONE;
		for (int a = 0; a < 3; a++) {
			var denominator = point.component(a).denominator();
			weight = weight.divide(weight.gcd(denominator)).multiply(denominator);
		}
		return new Vertex(point.x().numerator().multiply(weight.divide(point.x().denominator())),
				point.y().numerator().multiply(weight.divide(point.y().denominator())),
				point.z().numerator().multiply(weight.divide(point.z().denominator())), weight);
	}
	private static Vector subtract(Vertex a, Vertex b) {
		return new Vector(a.x.multiply(b.weight).subtract(b.x.multiply(a.weight)),
				a.y.multiply(b.weight).subtract(b.y.multiply(a.weight)),
				a.z.multiply(b.weight).subtract(b.z.multiply(a.weight)));
	}
	private static Vector cross(Vector a, Vector b) {
		return new Vector(a.y.multiply(b.z).subtract(a.z.multiply(b.y)),
				a.z.multiply(b.x).subtract(a.x.multiply(b.z)), a.x.multiply(b.y).subtract(a.y.multiply(b.x)));
	}
	private static Projection dot(Vector axis, Vertex vertex) {
		return new Projection(axis.x.multiply(vertex.x).add(axis.y.multiply(vertex.y)).add(axis.z.multiply(vertex.z)), vertex.weight);
	}
	private static int compare(Projection a, Projection b) {
		if (a.weight.equals(b.weight)) return a.value.compareTo(b.value);
		return a.value.multiply(b.weight).compareTo(b.value.multiply(a.weight));
	}
}

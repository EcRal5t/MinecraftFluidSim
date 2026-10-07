package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;
import cc.ecisr.mmm.fluid.LiquidExactPolygon.Vertex;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Exact volume balance with face capacities certified by positive tetrahedra and the open shell. */
final class LiquidJointVolumeFlux {
	private LiquidJointVolumeFlux() { }
	private static final class Bounds {
		Fraction lower, upper;
		void positive(Fraction value, Fraction derivative) {
			if (value.signum() == 0) throw new IllegalArgumentException("Joint volume face has no positive tetrahedral support");
			var rate = derivative.divide(value);
			if (rate.signum() > 0) lower(Fraction.of(-1).divide(rate));
			if (rate.signum() < 0) upper(Fraction.of(-1).divide(rate));
		}
		void lower(Fraction value) { if (lower == null || value.compareTo(lower) > 0) lower = value; }
		void upper(Fraction value) { if (upper == null || value.compareTo(upper) < 0) upper = value; }
	}
	static Fraction[] solve(LiquidControlVolumeMesh mesh, List<LiquidGeometryRefinement.Cell> cells, List<Integer> owners,
			List<Integer> groups, List<LiquidMomentReconstruction.Leaf> leaves, List<Integer> selected, Fraction[] source, List<Vertex> centers) {
		int edges = selected.size(), vertices = source.length;
		int[] first = new int[edges], second = new int[edges];
		var forward = new Fraction[edges]; var reverse = forward.clone(); var result = forward.clone(); Arrays.fill(result, Fraction.ZERO);
		var cellCenters = cells.stream().map(cell -> LiquidExactPolygon.interiorVertex(LiquidExactPolygon.faces(cell.geometry()).stream()
				.flatMap(face -> face.vertices().stream()).distinct().toList()).coordinates()).toList();
		var incidence = new ArrayList<List<Integer>>(); for (int v = 0; v < vertices; v++) incidence.add(new ArrayList<>());
		for (int e = 0; e < edges; e++) {
			int f = selected.get(e); var face = mesh.faces.get(f); first[e] = groups.get(face.first()); second[e] = groups.get(face.second());
			incidence.get(first[e]).add(e); incidence.get(second[e]).add(e);
			var polygon = mesh.exactPolygons.get(f); var origin = centers.get(f).coordinates(); var area = polygon.areaVector();
			var squared = dot(area, area); var direction = new Fraction[3];
			for (int a = 0; a < 3; a++) direction[a] = area[a].multiply(Fraction.of(3)).divide(squared);
			var bounds = new Bounds();
			for (int cell : new int[]{face.first(), face.second()}) {
				var center = cellCenters.get(cell);
				for (int p = 0; p < polygon.vertices().size(); p++) {
					var a = subtract(polygon.vertices().get(p).coordinates(), center);
					var b = subtract(polygon.vertices().get((p + 1) % polygon.vertices().size()).coordinates(), center);
					var normal = cross(a, b);
					bounds.positive(dot(subtract(origin, center), normal), dot(direction, normal));
				}
			}
			for (var plane : LiquidExactPolygon.faces(leaves.get(owners.get(face.first())).open().geometry())) {
				var coefficients = plane.coefficients(); var distance = Fraction.ZERO; var rate = Fraction.ZERO;
				for (int a = 0; a < 3; a++) {
					var normal = new Fraction(coefficients.get(a), java.math.BigInteger.ONE);
					distance = distance.add(origin[a].multiply(normal)); rate = rate.add(direction[a].multiply(normal));
				}
				distance = distance.subtract(new Fraction(coefficients.get(3), java.math.BigInteger.ONE));
				if (distance.signum() > 0) throw new IllegalArgumentException("Joint volume face leaves its open shell");
				if (rate.signum() > 0) bounds.upper(distance.negate().divide(rate));
				if (rate.signum() < 0) bounds.lower(distance.negate().divide(rate));
			}
			if (bounds.lower == null || bounds.upper == null || bounds.lower.signum() > 0 || bounds.upper.signum() < 0)
				throw new IllegalArgumentException("Joint volume face has incomplete geometric capacity");
			// Half the distance to the first degeneracy preserves a strict interior certificate at saturation.
			forward[e] = bounds.upper.divide(Fraction.of(2)); reverse[e] = bounds.lower.negate().divide(Fraction.of(2));
		}
		for (int v = 0; v < vertices; v++) {
			final int node = v;
			incidence.get(v).sort((a, b) -> {
				var ca = first[a] == node ? forward[a] : reverse[a]; var cb = first[b] == node ? forward[b] : reverse[b];
				int compare = cb.compareTo(ca); return compare != 0 ? compare : Integer.compare(a, b);
			});
		}
		var remaining = source.clone(); var total = Fraction.ZERO; for (var value : source) total = total.add(value);
		if (total.signum() != 0) throw new IllegalArgumentException("Joint volume correction changes total capacity");
		int[] parent = new int[vertices], parentEdge = new int[vertices], queue = new int[vertices]; Arrays.fill(parent, -1);
		int start = 0, visited = 0;
		for (;;) {
			while (start < vertices && remaining[start].signum() <= 0) start++;
			if (start == vertices) break;
			for (int i = 0; i < visited; i++) parent[queue[i]] = -1;
			parent[start] = start; queue[0] = start; int head = 0, tail = 1, end = -1;
			while (head < tail && end < 0) {
				int v = queue[head++];
				for (int e : incidence.get(v)) {
					boolean positive = first[e] == v; int next = positive ? second[e] : first[e];
					var room = positive ? forward[e].subtract(result[e]) : reverse[e].add(result[e]);
					if (parent[next] >= 0 || room.signum() <= 0) continue;
					parent[next] = v; parentEdge[next] = e; queue[tail++] = next;
					if (remaining[next].signum() < 0) { end = next; break; }
				}
			}
			if (end < 0) {
				var failed = new java.util.TreeSet<Integer>();
				for (int c = 0; c < cells.size(); c++) if (parent[groups.get(c)] >= 0) failed.add(owners.get(c));
				throw new LiquidInterfaceResolutionRequired(failed.stream().mapToInt(Integer::intValue).toArray(), "Joint volume correction exceeds available shared-face capacity");
			}
			var amount = remaining[start]; if (remaining[end].negate().compareTo(amount) < 0) amount = remaining[end].negate();
			for (int v = end; v != start; v = parent[v]) {
				int e = parentEdge[v]; var room = first[e] == parent[v] ? forward[e].subtract(result[e]) : reverse[e].add(result[e]);
				if (room.compareTo(amount) < 0) amount = room;
			}
			for (int v = end; v != start; v = parent[v]) {
				int e = parentEdge[v]; result[e] = result[e].add(first[e] == parent[v] ? amount : amount.negate());
			}
			remaining[start] = remaining[start].subtract(amount); remaining[end] = remaining[end].add(amount);
			visited = tail;
		}
		for (var value : remaining) if (value.signum() != 0) throw new IllegalStateException("Joint volume correction left an unbalanced phase");
		return result;
	}
	private static Fraction[] subtract(Fraction[] a, Fraction[] b) { return new Fraction[]{a[0].subtract(b[0]), a[1].subtract(b[1]), a[2].subtract(b[2])}; }
	private static Fraction[] cross(Fraction[] a, Fraction[] b) { return new Fraction[]{a[1].multiply(b[2]).subtract(a[2].multiply(b[1])),
			a[2].multiply(b[0]).subtract(a[0].multiply(b[2])), a[0].multiply(b[1]).subtract(a[1].multiply(b[0]))}; }
	private static Fraction dot(Fraction[] a, Fraction[] b) { var value = Fraction.ZERO; for (int i = 0; i < 3; i++) value = value.add(a[i].multiply(b[i])); return value; }
}


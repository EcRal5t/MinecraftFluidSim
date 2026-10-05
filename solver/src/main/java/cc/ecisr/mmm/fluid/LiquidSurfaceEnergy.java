package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;
import cc.ecisr.mmm.fluid.LiquidExactPolygon.Vertex;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Polygon area energy and its variational force, without curvature sampling or color smoothing. */
final class LiquidSurfaceEnergy {
	private record Contact(int face, Fraction weight, Fraction[] normal) { }
	private static final class Node {
		final Fraction[] force = zeros();
		final List<Contact> contacts = new ArrayList<>();
	}
	final double joules;
	final Fraction[] pressureJump;
	final Map<Vertex, List<Fraction>> force, tangentialForce;
	private final Map<Vertex, Node> nodes;

	/** Coefficients have units J/m²; negative wet-wall energy is permitted. Zero coefficients omit the facet. */
	LiquidSurfaceEnergy(LiquidControlVolumeMesh mesh, double[] coefficient) {
		this(mesh.exactPolygons, coefficient);
	}
	LiquidSurfaceEnergy(List<LiquidExactPolygon> polygons, double[] coefficient) {
		if (coefficient.length != polygons.size()) throw new IllegalArgumentException("Incomplete surface energy coefficients");
		var vertices = new LinkedHashMap<Vertex, Node>(); var energy = Fraction.ZERO;
		for (int f = 0; f < coefficient.length; f++) {
			double sigma = coefficient[f]; if (!Double.isFinite(sigma)) throw new IllegalArgumentException("Non-finite surface energy coefficient");
			if (sigma == 0) continue;
			var polygon = polygons.get(f); var points = polygon.vertices(); var area = polygon.areaVector();
			var unit = normal(polygon); var center = polygon.centroidCoordinates();
			energy = energy.add(Fraction.of(sigma).multiply(Fraction.of(polygon.surface().area())));
			var edgePotential = new Fraction[points.size()][]; var weights = new Fraction[points.size()];
			int axis = 0; while (area[axis].signum() == 0) axis++;
			for (int v = 0; v < points.size(); v++) {
				var relative = subtract(points.get(v).coordinates(), center);
				edgePotential[v] = scale(cross(relative, unit), Fraction.of(sigma).divide(Fraction.of(-2)));
				var before = subtract(points.get((v + points.size() - 1) % points.size()).coordinates(), center);
				var after = subtract(points.get((v + 1) % points.size()).coordinates(), center);
				weights[v] = cross(before, relative)[axis].add(cross(relative, after)[axis]).divide(area[axis].multiply(Fraction.of(4)));
			}
			for (int v = 0; v < points.size(); v++) {
				var node = vertices.computeIfAbsent(points.get(v), ignored -> new Node());
				var gradient = subtract(edgePotential[(v + 1) % points.size()], edgePotential[(v + points.size() - 1) % points.size()]);
				for (int a = 0; a < 3; a++) node.force[a] = node.force[a].add(gradient[a]);
				node.contacts.add(new Contact(f, weights[v], scale(area, weights[v])));
			}
		}
		joules = energy.rounded(); if (!Double.isFinite(joules)) throw new IllegalStateException("Surface energy exceeds numerical range");
		pressureJump = new Fraction[coefficient.length]; Arrays.fill(pressureJump, Fraction.ZERO);
		var forces = new LinkedHashMap<Vertex, List<Fraction>>(); var residuals = new LinkedHashMap<Vertex, List<Fraction>>();
		for (var entry : vertices.entrySet()) {
			var node = entry.getValue(); var dual = dual(node, node.force); var projected = zeros();
			for (var contact : node.contacts) {
				var traction = dot(contact.normal, dual);
				pressureJump[contact.face] = pressureJump[contact.face].subtract(contact.weight.multiply(traction));
				for (int a = 0; a < 3; a++) projected[a] = projected[a].add(contact.normal[a].multiply(traction));
			}
			forces.put(entry.getKey(), List.of(node.force)); residuals.put(entry.getKey(), List.of(subtract(node.force, projected)));
		}
		force = java.util.Collections.unmodifiableMap(forces); tangentialForce = java.util.Collections.unmodifiableMap(residuals);
		nodes = java.util.Collections.unmodifiableMap(vertices);
	}
	/** Minimum-norm incident normal-trace interpolation, adjoint to the pressure energy load. */
	Map<Vertex, List<Fraction>> vertexVelocity(Fraction[] volumeRate) {
		if (volumeRate.length != pressureJump.length) throw new IllegalArgumentException("Incomplete surface velocity rates");
		var result = new LinkedHashMap<Vertex, List<Fraction>>();
		for (var entry : nodes.entrySet()) {
			var right = zeros();
			for (var contact : entry.getValue().contacts) for (int a = 0; a < 3; a++)
				right[a] = right[a].add(contact.normal[a].multiply(contact.weight).multiply(volumeRate[contact.face]));
			result.put(entry.getKey(), List.of(dual(entry.getValue(), right)));
		}
		return java.util.Collections.unmodifiableMap(result);
	}
	private static Fraction[] normal(LiquidExactPolygon polygon) {
		var area = polygon.areaVector(); int largest = 0;
		for (int a = 1; a < 3; a++) if (area[a].absolute().subtract(area[largest].absolute()).signum() > 0) largest = a;
		var direction = scale(area, Fraction.of(1).divide(area[largest].absolute())); double norm = 0;
		for (var coordinate : direction) norm = Math.hypot(norm, coordinate.rounded());
		return scale(direction, Fraction.of(1 / norm));
	}
	/** Solve only the exact span of the incident normals; the remaining force is retained explicitly. */
	private static Fraction[] dual(Node node, Fraction[] force) {
		var basis = new ArrayList<Fraction[]>();
		for (var contact : node.contacts) {
			var n = contact.normal;
			boolean independent = basis.isEmpty() || basis.size() == 1 && nonzero(cross(basis.getFirst(), n))
					|| basis.size() == 2 && dot(cross(basis.getFirst(), basis.get(1)), n).signum() != 0;
			if (independent) basis.add(n); if (basis.size() == 3) break;
		}
		int rank = basis.size(); var matrix = new Fraction[rank][rank + 1];
		for (int i = 0; i < rank; i++) {
			Arrays.fill(matrix[i], Fraction.ZERO); matrix[i][rank] = dot(basis.get(i), force);
			for (int j = 0; j < rank; j++) for (var contact : node.contacts)
				matrix[i][j] = matrix[i][j].add(dot(basis.get(i), contact.normal).multiply(dot(basis.get(j), contact.normal)));
		}
		for (int i = 0; i < rank; i++) {
			var divisor = matrix[i][i]; if (divisor.signum() <= 0) throw new IllegalStateException("Invalid surface normal metric");
			for (int j = i; j <= rank; j++) matrix[i][j] = matrix[i][j].divide(divisor);
			for (int row = 0; row < rank; row++) if (row != i) {
				var factor = matrix[row][i]; for (int j = i; j <= rank; j++) matrix[row][j] = matrix[row][j].subtract(factor.multiply(matrix[i][j]));
			}
		}
		var result = zeros();
		for (int i = 0; i < rank; i++) for (int a = 0; a < 3; a++) result[a] = result[a].add(basis.get(i)[a].multiply(matrix[i][rank]));
		return result;
	}
	private static boolean nonzero(Fraction[] vector) { for (var value : vector) if (value.signum() != 0) return true; return false; }
	private static Fraction[] zeros() { return new Fraction[]{Fraction.ZERO, Fraction.ZERO, Fraction.ZERO}; }
	private static Fraction[] subtract(Fraction[] a, Fraction[] b) { return new Fraction[]{a[0].subtract(b[0]), a[1].subtract(b[1]), a[2].subtract(b[2])}; }
	private static Fraction[] scale(Fraction[] a, Fraction b) { return new Fraction[]{a[0].multiply(b), a[1].multiply(b), a[2].multiply(b)}; }
	private static Fraction dot(Fraction[] a, Fraction[] b) { var result = Fraction.ZERO; for (int i = 0; i < 3; i++) result = result.add(a[i].multiply(b[i])); return result; }
	private static Fraction[] cross(Fraction[] a, Fraction[] b) { return new Fraction[]{a[1].multiply(b[2]).subtract(a[2].multiply(b[1])), a[2].multiply(b[0]).subtract(a[0].multiply(b[2])), a[0].multiply(b[1]).subtract(a[1].multiply(b[0]))}; }
}

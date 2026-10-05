package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;
import cc.ecisr.mmm.fluid.LiquidExactPolygon.Vertex;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Continuous P1 traces on the shared face fans, preserving cell means and integrated face rates. */
final class LiquidConformingVelocity {
	record Tetrahedron(int cell, int face, List<Vertex> vertices, Fraction volume) { }
	record Load(List<Fraction> cellForce, List<Fraction> faceForce, Fraction prescribedPower) { }
	private record Fit(Fraction[] value, Map<Integer, Fraction> weights) { }
	final LiquidControlVolumeMesh mesh;
	final Map<Vertex, List<Fraction>> velocity;
	final List<Tetrahedron> tetrahedra;
	final List<Vertex> faceCenters, cellCenters;
	private final Map<Vertex, Map<Integer, Fraction>> cornerWeights;
	private final Map<Vertex, List<Fraction[]>> walls;
	private final List<Map<Vertex, Fraction>> faceWeights;
	private final double[] mean;
	private final Fraction[] rates;

	LiquidConformingVelocity(LiquidControlVolumeMesh mesh, Fraction[] rate, double[] mean) {
		this(mesh, rate, mean, new int[mesh.cells.size()]);
	}
	LiquidConformingVelocity(LiquidControlVolumeMesh mesh, Fraction[] rate, double[] mean, int[] liquid) {
		this(mesh, rate, mean, liquid, FluidSolverConfig.WallCondition.FREE_SLIP);
	}
	LiquidConformingVelocity(LiquidControlVolumeMesh mesh, Fraction[] rate, double[] mean, int[] liquid, FluidSolverConfig.WallCondition wall) {
		if (wall == null || rate.length != mesh.faces.size() || mean.length != 3 * mesh.cells.size() || liquid.length != mesh.cells.size()) throw new IllegalArgumentException("Incomplete continuous velocity input");
		this.mesh = mesh; this.mean = mean.clone(); rates = rate.clone();
		for (double u : mean) if (!Double.isFinite(u)) throw new IllegalArgumentException("Non-finite continuous velocity input");
		for (var q : rate) if (q == null) throw new IllegalArgumentException("Absent continuous face rate");
		var incidence = new LinkedHashMap<Vertex, LinkedHashSet<Integer>>();
		for (int f = 0; f < mesh.faces.size(); f++) for (var p : mesh.exactPolygons.get(f).vertices()) {
			var cells = incidence.computeIfAbsent(p, ignored -> new LinkedHashSet<>()); var face = mesh.faces.get(f);
			cells.add(face.first()); if (face.second() >= 0) cells.add(face.second());
		}
		var nodes = new LinkedHashMap<Vertex, List<Fraction>>();
		var fits = new LinkedHashMap<Vertex, Map<Integer, Fraction>>(); var wallNormals = new LinkedHashMap<Vertex, List<Fraction[]>>();
		for (var entry : incidence.entrySet()) {
			if (entry.getValue().stream().noneMatch(c -> liquid[c] >= 0)) continue;
			var patch = new LinkedHashSet<>(entry.getValue());
			for (int c : entry.getValue()) for (int f : mesh.cellFaces[c]) {
				var face = mesh.faces.get(f); patch.add(face.first()); if (face.second() >= 0) patch.add(face.second());
			}
			// Gas extension carries no spatial momentum in this phase model. Liquid traces use physical liquid observations.
			patch.removeIf(c -> liquid[c] < 0);
			var fit = fit(mesh, patch, mean, entry.getKey().coordinates()); nodes.put(entry.getKey(), List.of(fit.value())); fits.put(entry.getKey(), fit.weights());
		}
		// The same point obeys every incident wall constraint, including edges and corners.
		for (var entry : incidence.entrySet()) {
			if (!nodes.containsKey(entry.getKey())) continue;
			var normals = new ArrayList<Fraction[]>(); var targets = new ArrayList<Fraction>(); var ids = new LinkedHashSet<Integer>();
			for (int c : entry.getValue()) for (int f : mesh.cellFaces[c]) if (mesh.faces.get(f).second() == -2
					&& mesh.exactPolygons.get(f).vertices().contains(entry.getKey()) && ids.add(f)) {
				var normal = mesh.faces.get(f).areaVector().toArray(Fraction[]::new);
				var flux = Fraction.of(mesh.faces.get(f).surface().area()).multiply(Fraction.of(mesh.faces.get(f).prescribed()));
				if (wall == FluidSolverConfig.WallCondition.NO_SLIP) {
					var fixed = scale(normal, flux.divide(dot(normal, normal)));
					for (int a = 0; a < 3; a++) { var axis = zero(); axis[a] = Fraction.of(1); normals.add(axis); targets.add(fixed[a]); }
				} else { normals.add(normal); targets.add(flux); }
			}
			if (normals.isEmpty()) continue;
			wallNormals.put(entry.getKey(), List.copyOf(normals));
			var u = nodes.get(entry.getKey()).toArray(Fraction[]::new); var rhs = zero(); var weights = new ArrayList<Fraction>();
			for (int i = 0; i < normals.size(); i++) {
				weights.add(Fraction.of(1)); add(rhs, scale(normals.get(i), targets.get(i).subtract(dot(normals.get(i), u))));
			}
			add(u, dual(normals, weights, rhs));
			for (int i = 0; i < normals.size(); i++) if (!dot(normals.get(i), u).equals(targets.get(i)))
				throw new IllegalArgumentException("Incompatible prescribed wall velocity at a shared vertex");
			nodes.put(entry.getKey(), List.of(u));
		}
		var centers = new ArrayList<Vertex>();
		var integration = new ArrayList<Map<Vertex, Fraction>>();
		for (int f = 0; f < mesh.faces.size(); f++) {
			var polygon = mesh.exactPolygons.get(f); var area = polygon.areaVector(); var center = polygon.centroidCoordinates();
			var point = LiquidExactPolygon.vertex(center); centers.add(point); var face = mesh.faces.get(f);
			if (liquid[face.first()] < 0 && (face.second() < 0 || liquid[face.second()] < 0)) { integration.add(Map.of()); continue; }
			int axis = 0; while (area[axis].signum() == 0) axis++;
			var trace = zero(); var points = polygon.vertices(); var weights = new LinkedHashMap<Vertex, Fraction>();
			for (int v = 0; v < points.size(); v++) {
				var relative = subtract(points.get(v).coordinates(), center);
				var before = subtract(points.get((v + points.size() - 1) % points.size()).coordinates(), center);
				var after = subtract(points.get((v + 1) % points.size()).coordinates(), center);
				var weight = cross(before, relative)[axis].add(cross(relative, after)[axis]).divide(area[axis].multiply(Fraction.of(4)));
				weights.put(points.get(v), weight);
				add(trace, scale(nodes.get(points.get(v)).toArray(Fraction[]::new), weight));
			}
			var correction = rate[f].subtract(dot(area, trace)).multiply(Fraction.of(3)).divide(dot(area, area));
			add(trace, scale(area, correction));
			nodes.put(point, List.of(trace)); integration.add(java.util.Collections.unmodifiableMap(weights));
		}
		var cells = new ArrayList<Vertex>(); var tets = new ArrayList<Tetrahedron>();
		for (int c = 0; c < mesh.cells.size(); c++) {
			var center = mesh.cells.get(c).geometry().centroidCoordinates(); var moment = zero(); var volume = Fraction.ZERO;
			var point = LiquidExactPolygon.vertex(center); cells.add(point); if (liquid[c] < 0) continue;
			for (int f : mesh.cellFaces[c]) {
				var polygon = mesh.exactPolygons.get(f); if (mesh.faces.get(f).first() != c) polygon = polygon.reversed();
				for (int e = 0; e < polygon.vertices().size(); e++) {
					var vertices = polygon.tetrahedronVertices(center, e);
					var measure = LiquidExactPolygon.tetrahedronMeasure(vertices); if (measure.signum() == 0) continue;
					var sum = zero(); for (int v = 1; v < 4; v++) add(sum, nodes.get(vertices.get(v)).toArray(Fraction[]::new));
					add(moment, scale(sum, measure.volume())); volume = volume.add(measure.volume());
					tets.add(new Tetrahedron(c, f, vertices, measure.volume()));
				}
			}
			if (!volume.equals(mesh.cells.get(c).geometry().measure().volume())) throw new IllegalStateException("Continuous velocity fan lost cell volume");
			var u = zero(); for (int a = 0; a < 3; a++) u[a] = Fraction.of(mean[3 * c + a]).multiply(Fraction.of(4)).subtract(moment[a].divide(volume));
			nodes.put(point, List.of(u));
		}
		velocity = java.util.Collections.unmodifiableMap(nodes); tetrahedra = List.copyOf(tets); faceCenters = List.copyOf(centers); cellCenters = List.copyOf(cells);
		cornerWeights = java.util.Collections.unmodifiableMap(fits); walls = java.util.Collections.unmodifiableMap(wallNormals); faceWeights = List.copyOf(integration);
	}
	/** Adjoint loads act on the same cell means and face rates that move the surface nodes. */
	Load load(Map<Vertex, List<Fraction>> forces) {
		var nodes = new LinkedHashMap<Vertex, Fraction[]>(); for (var p : velocity.keySet()) nodes.put(p, zero());
		var force = new Fraction[mean.length]; var trace = new Fraction[rates.length]; Arrays.fill(force, Fraction.ZERO); Arrays.fill(trace, Fraction.ZERO);
		var power = Fraction.ZERO;
		for (var entry : forces.entrySet()) {
			if (!nodes.containsKey(entry.getKey()) || entry.getValue().size() != 3) throw new IllegalArgumentException("Force has no continuous velocity support");
			add(nodes.get(entry.getKey()), entry.getValue().toArray(Fraction[]::new));
			power = power.add(dot(velocity.get(entry.getKey()).toArray(Fraction[]::new), entry.getValue().toArray(Fraction[]::new)));
		}
		for (int c = 0; c < mesh.cells.size(); c++) if (nodes.containsKey(cellCenters.get(c))) for (int a = 0; a < 3; a++) force[3 * c + a] = nodes.get(cellCenters.get(c))[a].multiply(Fraction.of(4));
		for (var tet : tetrahedra) {
			var weight = tet.volume().divide(mesh.cells.get(tet.cell()).geometry().measure().volume()).negate();
			var f = scale(nodes.get(cellCenters.get(tet.cell())), weight); for (int v = 1; v < 4; v++) add(nodes.get(tet.vertices().get(v)), f);
		}
		for (int f = 0; f < mesh.faces.size(); f++) {
			if (!nodes.containsKey(faceCenters.get(f))) continue;
			var area = mesh.faces.get(f).areaVector().toArray(Fraction[]::new); var input = nodes.get(faceCenters.get(f));
			trace[f] = dot(area, input).multiply(Fraction.of(3)).divide(dot(area, area)); var preferred = subtract(input, scale(area, trace[f]));
			for (var weight : faceWeights.get(f).entrySet()) add(nodes.get(weight.getKey()), scale(preferred, weight.getValue()));
		}
		for (var corner : cornerWeights.entrySet()) {
			var f = nodes.get(corner.getKey()); var normals = walls.get(corner.getKey());
			if (normals != null) {
				var rhs = zero(); var weights = new ArrayList<Fraction>();
				for (var n : normals) { weights.add(Fraction.of(1)); add(rhs, scale(n, dot(n, f))); }
				f = subtract(f, dual(normals, weights, rhs));
			}
			for (var weight : corner.getValue().entrySet()) for (int a = 0; a < 3; a++) force[3 * weight.getKey() + a] = force[3 * weight.getKey() + a].add(f[a].multiply(weight.getValue()));
		}
		for (int i = 0; i < force.length; i++) power = power.subtract(force[i].multiply(Fraction.of(mean[i])));
		for (int f = 0; f < trace.length; f++) power = power.subtract(trace[f].multiply(rates[f]));
		return new Load(List.of(force), List.of(trace), power);
	}
	/** Pressure-compatible virtual velocities: exact cell means follow the face first moments. */
	Map<Vertex, List<Fraction>> rateVelocity(Fraction[] virtualRate) {
		if (virtualRate.length != mesh.faces.size()) throw new IllegalArgumentException("Incomplete virtual face rates");
		var primary = new Fraction[mesh.cells.size()][3];
		for (int c = 0; c < primary.length; c++) {
			primary[c] = zero(); if (!velocity.containsKey(cellCenters.get(c))) continue;
			var center = mesh.cells.get(c).geometry().centroidCoordinates(); var volume = mesh.cells.get(c).geometry().measure().volume();
			for (int f : mesh.cellFaces[c]) {
				if (mesh.faces.get(f).second() == -2 && virtualRate[f].signum() != 0) throw new IllegalArgumentException("Virtual velocity changes prescribed wall motion");
				var sign = Fraction.of(mesh.faces.get(f).first() == c ? 1 : -1);
				add(primary[c], scale(subtract(mesh.faces.get(f).centroid().toArray(Fraction[]::new), center), virtualRate[f].multiply(sign).divide(volume)));
			}
		}
		var nodes = new LinkedHashMap<Vertex, Fraction[]>();
		for (var corner : cornerWeights.entrySet()) {
			var value = zero(); for (var weight : corner.getValue().entrySet()) add(value, scale(primary[weight.getKey()], weight.getValue()));
			var normals = walls.get(corner.getKey());
			if (normals != null) {
				var rhs = zero(); var weights = new ArrayList<Fraction>();
				for (var n : normals) { weights.add(Fraction.of(1)); add(rhs, scale(n, dot(n, value))); }
				value = subtract(value, dual(normals, weights, rhs));
			}
			nodes.put(corner.getKey(), value);
		}
		for (int f = 0; f < mesh.faces.size(); f++) if (!faceWeights.get(f).isEmpty()) {
			var value = zero(); for (var weight : faceWeights.get(f).entrySet()) add(value, scale(nodes.get(weight.getKey()), weight.getValue()));
			var area = mesh.faces.get(f).areaVector().toArray(Fraction[]::new);
			add(value, scale(area, virtualRate[f].subtract(dot(area, value)).multiply(Fraction.of(3)).divide(dot(area, area))));
			nodes.put(faceCenters.get(f), value);
		}
		var moment = new Fraction[primary.length][3]; for (int c = 0; c < primary.length; c++) moment[c] = zero();
		for (var tet : tetrahedra) for (int v = 1; v < 4; v++) add(moment[tet.cell()], scale(nodes.get(tet.vertices().get(v)), tet.volume()));
		for (int c = 0; c < primary.length; c++) if (velocity.containsKey(cellCenters.get(c)))
			nodes.put(cellCenters.get(c), subtract(scale(primary[c], Fraction.of(4)), scale(moment[c], Fraction.of(1).divide(mesh.cells.get(c).geometry().measure().volume()))));
		var result = new LinkedHashMap<Vertex, List<Fraction>>(); nodes.forEach((point, value) -> result.put(point, List.of(value)));
		return java.util.Collections.unmodifiableMap(result);
	}
	/** Adjoint of rateVelocity, including the pressure-compatible cell-mean dependence. */
	Fraction[] rateLoad(Map<Vertex, List<Fraction>> forces) {
		var load = load(forces); var result = load.faceForce().toArray(Fraction[]::new);
		for (int c = 0; c < mesh.cells.size(); c++) if (velocity.containsKey(cellCenters.get(c))) {
			var force = new Fraction[]{load.cellForce().get(3 * c), load.cellForce().get(3 * c + 1), load.cellForce().get(3 * c + 2)};
			var center = mesh.cells.get(c).geometry().centroidCoordinates(); var volume = mesh.cells.get(c).geometry().measure().volume();
			for (int f : mesh.cellFaces[c]) {
				var value = dot(subtract(mesh.faces.get(f).centroid().toArray(Fraction[]::new), center), force).divide(volume);
				result[f] = result[f].add(mesh.faces.get(f).first() == c ? value : value.negate());
			}
		}
		return result;
	}
	private static Fit fit(LiquidControlVolumeMesh mesh, LinkedHashSet<Integer> patch, double[] mean, Fraction[] point) {
		var center = zero(); var average = zero(); var total = Fraction.ZERO;
		for (int c : patch) {
			var volume = mesh.cells.get(c).geometry().measure().volume(); total = total.add(volume);
			add(center, scale(mesh.cells.get(c).geometry().centroidCoordinates(), volume));
		}
		for (int a = 0; a < 3; a++) center[a] = center[a].divide(total);
		var direction = new ArrayList<Fraction[]>(); var weights = new ArrayList<Fraction>();
		for (int c : patch) {
			var d = subtract(mesh.cells.get(c).geometry().centroidCoordinates(), center); var w = mesh.cells.get(c).geometry().measure().volume();
			direction.add(d); weights.add(w);
		}
		var dual = dual(direction, weights, subtract(point, center)); var coefficients = new LinkedHashMap<Integer, Fraction>(); int i = 0;
		Arrays.fill(average, Fraction.ZERO);
		for (int c : patch) {
			var coefficient = weights.get(i).divide(total).add(weights.get(i).multiply(dot(direction.get(i), dual))); coefficients.put(c, coefficient); i++;
			for (int a = 0; a < 3; a++) average[a] = average[a].add(coefficient.multiply(Fraction.of(mean[3 * c + a])));
		}
		return new Fit(average, java.util.Collections.unmodifiableMap(coefficients));
	}
	/** Solve the exactly supported span, retaining zero slope in unobserved directions. */
	private static Fraction[] dual(List<Fraction[]> vectors, List<Fraction> weight, Fraction[] rhs) {
		var basis = new ArrayList<Fraction[]>();
		for (var n : vectors) {
			if (dot(n, n).signum() == 0) continue;
			boolean independent = basis.isEmpty() || basis.size() == 1 && dot(cross(basis.getFirst(), n), cross(basis.getFirst(), n)).signum() != 0
					|| basis.size() == 2 && dot(cross(basis.getFirst(), basis.get(1)), n).signum() != 0;
			if (independent) basis.add(n); if (basis.size() == 3) break;
		}
		int rank = basis.size(); var matrix = new Fraction[rank][rank + 1];
		for (int i = 0; i < rank; i++) {
			Arrays.fill(matrix[i], Fraction.ZERO); matrix[i][rank] = dot(basis.get(i), rhs);
			for (int j = 0; j < rank; j++) for (int v = 0; v < vectors.size(); v++)
				matrix[i][j] = matrix[i][j].add(weight.get(v).multiply(dot(basis.get(i), vectors.get(v))).multiply(dot(basis.get(j), vectors.get(v))));
		}
		for (int i = 0; i < rank; i++) {
			var divisor = matrix[i][i]; if (divisor.signum() <= 0) throw new IllegalStateException("Invalid continuous velocity metric");
			for (int j = i; j <= rank; j++) matrix[i][j] = matrix[i][j].divide(divisor);
			for (int r = 0; r < rank; r++) if (r != i) {
				var factor = matrix[r][i]; for (int j = i; j <= rank; j++) matrix[r][j] = matrix[r][j].subtract(factor.multiply(matrix[i][j]));
			}
		}
		var result = zero(); for (int i = 0; i < rank; i++) add(result, scale(basis.get(i), matrix[i][rank])); return result;
	}
	private static Fraction[] zero() { return new Fraction[]{Fraction.ZERO, Fraction.ZERO, Fraction.ZERO}; }
	private static void add(Fraction[] a, Fraction[] b) { for (int i = 0; i < 3; i++) a[i] = a[i].add(b[i]); }
	private static Fraction[] subtract(Fraction[] a, Fraction[] b) { return new Fraction[]{a[0].subtract(b[0]), a[1].subtract(b[1]), a[2].subtract(b[2])}; }
	private static Fraction[] scale(Fraction[] a, Fraction b) { return new Fraction[]{a[0].multiply(b), a[1].multiply(b), a[2].multiply(b)}; }
	private static Fraction dot(Fraction[] a, Fraction[] b) { var value = Fraction.ZERO; for (int i = 0; i < 3; i++) value = value.add(a[i].multiply(b[i])); return value; }
	private static Fraction[] cross(Fraction[] a, Fraction[] b) { return new Fraction[]{a[1].multiply(b[2]).subtract(a[2].multiply(b[1])), a[2].multiply(b[0]).subtract(a[0].multiply(b[2])), a[0].multiply(b[1]).subtract(a[1].multiply(b[0]))}; }
}

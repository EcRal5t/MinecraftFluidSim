package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;
import cc.ecisr.mmm.fluid.LiquidExactPolygon.Vertex;
import java.math.BigInteger;
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
	record Position(Vertex point, Fraction roundoffSquared) { }
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
		var stencils = new java.util.HashMap<List<Integer>, FitStencil>();
		for (var entry : incidence.entrySet()) {
			if (entry.getValue().stream().noneMatch(c -> liquid[c] >= 0)) continue;
			var patch = new LinkedHashSet<>(entry.getValue());
			for (int c : entry.getValue()) for (int f : mesh.cellFaces[c]) {
				var face = mesh.faces.get(f); patch.add(face.first()); if (face.second() >= 0) patch.add(face.second());
			}
			// Gas extension carries no spatial momentum in this phase model. Liquid traces use physical liquid observations.
			patch.removeIf(c -> liquid[c] < 0);
			var key = patch.stream().sorted().toList();
			var stencil = stencils.computeIfAbsent(key, cells -> new FitStencil(mesh, cells, mean));
			var fit = stencil.fit(entry.getKey()); nodes.put(entry.getKey(), List.of(fit.value())); fits.put(entry.getKey(), fit.weights());
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
			if (face.second() == -2) {
				var normals = new ArrayList<Fraction[]>();
				if (wall == FluidSolverConfig.WallCondition.NO_SLIP) for (int a = 0; a < 3; a++) { var axis = zero(); axis[a] = Fraction.of(1); normals.add(axis); }
				else normals.add(area);
				wallNormals.put(point, List.copyOf(normals));
			}
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
	/** Binary64 velocity observations preserve rigid motion; solid-plane projection bounds position roundoff. */
	Position advectedPosition(Vertex point, Fraction dt) {
		var origin = point.coordinates(); var rounded = zero();
		var exact = origin.clone(); var u = velocity.get(point);
		for (int a = 0; a < 3; a++) {
			exact[a] = exact[a].add(u.get(a).multiply(dt));
			rounded[a] = origin[a].add(Fraction.of(u.get(a).rounded()).multiply(dt));
		}
		var originalError = subtract(rounded, exact); var bound = dot(originalError, originalError);
		var normals = walls.get(point);
		if (normals != null) {
			var rhs = zero(); var weights = new ArrayList<Fraction>();
			for (var normal : normals) { weights.add(Fraction.of(1)); add(rhs, scale(normal, dot(normal, subtract(exact, rounded)))); }
			add(rounded, dual(normals, weights, rhs));
			for (var normal : normals) if (!dot(normal, subtract(exact, rounded)).equals(Fraction.ZERO))
				throw new IllegalStateException("Position rounding changed prescribed solid motion");
		}
		var error = subtract(rounded, exact);
		if (dot(error, error).subtract(bound).signum() > 0) throw new IllegalStateException("Solid-plane position projection increased roundoff");
		return new Position(LiquidExactPolygon.vertex(rounded), bound);
	}
	Fraction[] meanPosition(int cell, Fraction dt) {
		var result = cellCenters.get(cell).coordinates();
		for (int a = 0; a < 3; a++) result[a] = result[a].add(Fraction.of(mean[3 * cell + a]).multiply(dt));
		return result;
	}
	/** A moving material interface needs an exactly affine-complete spatial velocity trace. */
	void requireSurfaceResolution(double[] coefficient) {
		if (coefficient.length != mesh.faces.size()) throw new IllegalArgumentException("Incomplete interface coefficients");
		var points = new LinkedHashSet<Vertex>(); var finer = new LinkedHashSet<Integer>();
		for (int f = 0; f < coefficient.length; f++) if (coefficient[f] != 0) points.addAll(mesh.exactPolygons.get(f).vertices());
		for (var point : points) {
			var weights = cornerWeights.get(point); if (weights == null) throw new IllegalArgumentException("Material interface has no liquid velocity support");
			var center = zero(); for (var entry : weights.entrySet()) add(center, scale(mesh.cells.get(entry.getKey()).geometry().centroidCoordinates(), entry.getValue()));
			if (!Arrays.equals(center, point.coordinates())) finer.addAll(weights.keySet());
		}
		if (!finer.isEmpty()) throw new LiquidInterfaceResolutionRequired(finer.stream().mapToInt(Integer::intValue).toArray(), "Material interface requires affine-complete liquid velocity support");
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
	private static final class FitStencil {
		private final List<Integer> patch;
		private final List<AffineFunctional> shape = new ArrayList<>();
		private final AffineFunctional[] velocity = new AffineFunctional[3];
		FitStencil(LiquidControlVolumeMesh mesh, List<Integer> patch, double[] mean) {
			this.patch = patch; var total = Fraction.ZERO; var center = zero();
			for (int c : patch) {
				var local = mesh.cells.get(c).geometry().measure().volume(); total = total.add(local);
				add(center, scale(mesh.cells.get(c).geometry().centroidCoordinates(), local));
			}
			for (int a = 0; a < 3; a++) center[a] = center[a].divide(total);
			var direction = new ArrayList<Fraction[]>(); var weights = new ArrayList<Fraction>();
			for (int c : patch) {
				direction.add(subtract(mesh.cells.get(c).geometry().centroidCoordinates(), center)); weights.add(mesh.cells.get(c).geometry().measure().volume());
			}
			var metric = new Dual(direction, weights); var coefficients = new Fraction[3][4];
			for (var row : coefficients) Arrays.fill(row, Fraction.ZERO);
			for (int i = 0; i < patch.size(); i++) {
				var gradient = scale(metric.solve(direction.get(i)), weights.get(i));
				var row = new Fraction[]{gradient[0], gradient[1], gradient[2], weights.get(i).divide(total).subtract(dot(gradient, center))};
				shape.add(new AffineFunctional(row));
				for (int a = 0; a < 3; a++) for (int b = 0; b < 4; b++) coefficients[a][b] = coefficients[a][b].add(row[b].multiply(Fraction.of(mean[3 * patch.get(i) + a])));
			}
			for (int a = 0; a < 3; a++) velocity[a] = new AffineFunctional(coefficients[a]);
		}
		Fit fit(Vertex point) {
			var coefficients = new LinkedHashMap<Integer, Fraction>(); var average = zero();
			for (int i = 0; i < patch.size(); i++) coefficients.put(patch.get(i), shape.get(i).evaluate(point));
			for (int a = 0; a < 3; a++) average[a] = velocity[a].evaluate(point);
			return new Fit(average, java.util.Collections.unmodifiableMap(coefficients));
		}
	}
	/** Evaluate the same rational affine form with one final reduction at a homogeneous point. */
	private static final class AffineFunctional {
		private final BigInteger[] numerator = new BigInteger[4];
		private final BigInteger denominator;
		AffineFunctional(Fraction[] coefficients) {
			var common = BigInteger.ONE; for (var value : coefficients) common = lcm(common, value.denominator()); denominator = common;
			for (int a = 0; a < 4; a++) numerator[a] = coefficients[a].numerator().multiply(common.divide(coefficients[a].denominator()));
		}
		Fraction evaluate(Vertex point) {
			var value = numerator[3].multiply(point.w()); for (int a = 0; a < 3; a++) value = value.add(numerator[a].multiply(point.coordinate(a)));
			return new Fraction(value, denominator.multiply(point.w()));
		}
	}
	/** Solve the exactly supported span, retaining zero slope in unobserved directions. */
	private static Fraction[] dual(List<Fraction[]> vectors, List<Fraction> weight, Fraction[] rhs) {
		return new Dual(vectors, weight).solve(rhs);
	}
	private static final class Dual {
		private final List<Fraction[]> basis = new ArrayList<>();
		private final Fraction[][] inverse;
		Dual(List<Fraction[]> vectors, List<Fraction> weight) {
			var independent = new ArrayList<BigInteger[]>();
			for (var n : vectors) {
				var integer = integerDirection(n); if (Arrays.stream(integer).allMatch(value -> value.signum() == 0)) continue;
				boolean addsDirection = independent.isEmpty();
				if (independent.size() == 1) addsDirection = Arrays.stream(integerCross(independent.getFirst(), integer)).anyMatch(value -> value.signum() != 0);
				if (independent.size() == 2) addsDirection = integerDot(integerCross(independent.getFirst(), independent.get(1)), integer).signum() != 0;
				if (addsDirection) { independent.add(integer); basis.add(Arrays.stream(integer).map(value -> new Fraction(value, BigInteger.ONE)).toArray(Fraction[]::new)); }
				if (basis.size() == 3) break;
			}
			if (basis.size() == 3) {
				// The supported span is all of R^3. Cartesian axes avoid redundant rational basis factors.
				basis.clear();
				for (int a = 0; a < 3; a++) { var axis = zero(); axis[a] = Fraction.of(1); basis.add(axis); }
			}
			int rank = basis.size(); var matrix = new Fraction[rank][rank];
			var observed = new Fraction[vectors.size()][rank];
			for (int v = 0; v < vectors.size(); v++) for (int i = 0; i < rank; i++) observed[v][i] = dot(basis.get(i), vectors.get(v));
			for (int i = 0; i < rank; i++) for (int j = 0; j <= i; j++) {
				var value = Fraction.ZERO;
				for (int v = 0; v < vectors.size(); v++) value = value.add(weight.get(v).multiply(observed[v][i]).multiply(observed[v][j]));
				matrix[i][j] = matrix[j][i] = value;
			}
			inverse = inverseMetric(matrix);
		}
		Fraction[] solve(Fraction[] rhs) {
			var result = zero();
			for (int i = 0; i < basis.size(); i++) {
				var coefficient = Fraction.ZERO;
				for (int j = 0; j < basis.size(); j++) coefficient = coefficient.add(inverse[i][j].multiply(dot(basis.get(j), rhs)));
				add(result, scale(basis.get(i), coefficient));
			}
			return result;
		}
		/** Common-denominator integer cofactors avoid intermediate rational elimination. */
		private static Fraction[][] inverseMetric(Fraction[][] matrix) {
			int rank = matrix.length; var denominator = BigInteger.ONE;
			for (var row : matrix) for (var value : row) denominator = lcm(denominator, value.denominator());
			var integer = new BigInteger[rank][rank];
			for (int i = 0; i < rank; i++) for (int j = 0; j < rank; j++) integer[i][j] = matrix[i][j].numerator().multiply(denominator.divide(matrix[i][j].denominator()));
			for (int size = 1; size <= rank; size++) {
				var leading = new BigInteger[size][size]; for (int i = 0; i < size; i++) System.arraycopy(integer[i], 0, leading[i], 0, size);
				if (determinant(leading).signum() <= 0) throw new IllegalStateException("Invalid continuous velocity metric");
			}
			var divisor = determinant(integer); var result = new Fraction[rank][rank];
			for (int i = 0; i < rank; i++) for (int j = 0; j <= i; j++) {
				var minor = new BigInteger[rank - 1][rank - 1];
				for (int r = 0, out = 0; r < rank; r++) if (r != i) {
					for (int c = 0, column = 0; c < rank; c++) if (c != j) minor[out][column++] = integer[r][c]; out++;
				}
				var cofactor = determinant(minor); if ((i + j) % 2 != 0) cofactor = cofactor.negate();
				result[i][j] = result[j][i] = new Fraction(denominator.multiply(cofactor), divisor);
			}
			return result;
		}
		private static BigInteger determinant(BigInteger[][] matrix) {
			return switch (matrix.length) {
				case 0 -> BigInteger.ONE;
				case 1 -> matrix[0][0];
				case 2 -> matrix[0][0].multiply(matrix[1][1]).subtract(matrix[0][1].multiply(matrix[1][0]));
				case 3 -> integerDot(matrix[0], integerCross(matrix[1], matrix[2]));
				default -> throw new IllegalArgumentException("Spatial metric exceeds three dimensions");
			};
		}
		private static BigInteger[] integerDirection(Fraction[] direction) {
			var denominator = BigInteger.ONE; for (var value : direction) denominator = lcm(denominator, value.denominator());
			var result = new BigInteger[3]; var common = BigInteger.ZERO;
			for (int a = 0; a < 3; a++) { result[a] = direction[a].numerator().multiply(denominator.divide(direction[a].denominator())); common = common.gcd(result[a].abs()); }
			if (common.signum() != 0) for (int a = 0; a < 3; a++) result[a] = result[a].divide(common);
			return result;
		}
		private static BigInteger integerDot(BigInteger[] first, BigInteger[] second) {
			var result = BigInteger.ZERO; for (int a = 0; a < 3; a++) result = result.add(first[a].multiply(second[a])); return result;
		}
		private static BigInteger[] integerCross(BigInteger[] first, BigInteger[] second) {
			return new BigInteger[]{first[1].multiply(second[2]).subtract(first[2].multiply(second[1])), first[2].multiply(second[0]).subtract(first[0].multiply(second[2])), first[0].multiply(second[1]).subtract(first[1].multiply(second[0]))};
		}
	}
	private static BigInteger lcm(BigInteger first, BigInteger second) { return first.divide(first.gcd(second)).multiply(second); }
	private static Fraction[] zero() { return new Fraction[]{Fraction.ZERO, Fraction.ZERO, Fraction.ZERO}; }
	private static void add(Fraction[] a, Fraction[] b) { for (int i = 0; i < 3; i++) a[i] = a[i].add(b[i]); }
	private static Fraction[] subtract(Fraction[] a, Fraction[] b) { return new Fraction[]{a[0].subtract(b[0]), a[1].subtract(b[1]), a[2].subtract(b[2])}; }
	private static Fraction[] scale(Fraction[] a, Fraction b) { return new Fraction[]{a[0].multiply(b), a[1].multiply(b), a[2].multiply(b)}; }
	private static Fraction dot(Fraction[] a, Fraction[] b) { var value = Fraction.ZERO; for (int i = 0; i < 3; i++) value = value.add(a[i].multiply(b[i])); return value; }
	private static Fraction[] cross(Fraction[] a, Fraction[] b) { return new Fraction[]{a[1].multiply(b[2]).subtract(a[2].multiply(b[1])), a[2].multiply(b[0]).subtract(a[0].multiply(b[2])), a[0].multiply(b[1]).subtract(a[1].multiply(b[0]))}; }
}

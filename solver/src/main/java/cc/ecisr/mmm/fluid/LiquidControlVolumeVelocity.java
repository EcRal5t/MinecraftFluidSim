package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.units.SignedDyadic;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/** Raviart-Thomas reconstruction with exact tetrahedral continuity and shared triangle rates. */
final class LiquidControlVolumeVelocity {
	record Tetrahedron(List<LiquidPolyhedron.Point> vertices, LiquidPolyhedron.Point velocity, double volume, double isotropicGradient,
			int boundaryFace, List<SignedDyadic> outwardFlux) {
		LiquidPolyhedron.Point sample(LiquidPolyhedron.Point point) {
			var d = point.subtract(mean(vertices));
			return new LiquidPolyhedron.Point(velocity.x() + isotropicGradient * d.x(), velocity.y() + isotropicGradient * d.y(), velocity.z() + isotropicGradient * d.z());
		}
	}
	record Result(List<Tetrahedron> tetrahedra, double squaredVelocityVariation, double divergenceResidual,
			double normalTraceQuantizationBound, double divergenceQuantizationBound) { }
	private record Triangle(List<LiquidPolyhedron.Point> vertices) {
		static Triangle key(List<LiquidPolyhedron.Point> vertices) {
			var sorted = new ArrayList<>(vertices);
			sorted.sort(java.util.Comparator.comparingDouble(LiquidPolyhedron.Point::x)
					.thenComparingDouble(LiquidPolyhedron.Point::y).thenComparingDouble(LiquidPolyhedron.Point::z));
			return new Triangle(List.copyOf(sorted));
		}
	}
	private static final class Tetra {
		final List<LiquidPolyhedron.Point> vertices;
		final List<LiquidPolyhedron.Point> physicalVertices;
		final int[] unknown = {-1, -1, -1, -1}, sign = {1, 1, 1, 1};
		final double volume;
		final int boundaryFace;
		final LiquidPolyhedron.Point center;
		Tetra(List<LiquidPolyhedron.Point> vertices, List<LiquidPolyhedron.Point> physicalVertices, int boundaryFace) {
			this.vertices = vertices; this.physicalVertices = physicalVertices; this.boundaryFace = boundaryFace;
			var origin = vertices.getFirst();
			volume = vertices.get(1).subtract(origin).dot(vertices.get(2).subtract(origin).cross(vertices.get(3).subtract(origin))) / 6;
			if (!(volume > 0)) throw new IllegalArgumentException("Velocity reconstruction has an inverted tetrahedron");
			center = mean(vertices);
		}
	}
	private record Side(Tetra tetra, int opposite) { }
	private LiquidControlVolumeVelocity() { }

	static Result[] reconstruct(LiquidControlVolumeMesh mesh, SignedDyadic[] volumeRate, double[] cellVelocity) {
		if (cellVelocity.length != 3 * mesh.cells.size()) throw new IllegalArgumentException("Invalid liquid momentum field");
		var result = new Result[mesh.cells.size()];
		cc.ecisr.mmm.compute.CpuWorkBatch.run(result.length, (from, until) -> {
			for (int c = from; c < until; c++) result[c] = reconstruct(mesh, c, volumeRate,
					new LiquidPolyhedron.Point(cellVelocity[3 * c], cellVelocity[3 * c + 1], cellVelocity[3 * c + 2]));
		});
		return result;
	}

	static Result reconstruct(LiquidControlVolumeMesh mesh, int cell, SignedDyadic[] volumeRate, LiquidPolyhedron.Point target) {
		if (volumeRate.length != mesh.faces.size()) throw new IllegalArgumentException("Invalid shared liquid velocity traces");
		var origin = mesh.cells.get(cell).centroid();
		var polygons = new ArrayList<List<LiquidPolyhedron.Point>>(); var faceIds = new ArrayList<Integer>();
		double width = 0;
		for (int f : mesh.cellFaces[cell]) {
			var face = mesh.faces.get(f);
			if (volumeRate[f] == null || !Double.isFinite(volumeRate[f].toDouble())) throw new IllegalArgumentException("Non-finite shared liquid velocity");
			var polygon = new ArrayList<>(mesh.polygons.get(f)); boolean first = face.first() == cell;
			if (!first) java.util.Collections.reverse(polygon);
			polygons.add(polygon); faceIds.add(f);
			for (var point : polygon) for (int a = 0; a < 3; a++) width = Math.max(width, Math.abs(point.coordinate(a) - origin.coordinate(a)));
		}
		if (!(width > 0)) throw new IllegalArgumentException("Empty liquid velocity geometry");
		var tetrahedra = new ArrayList<Tetra>(); var unmatched = new HashMap<Triangle, Side>(); int unknowns = 0;
		for (int f = 0; f < polygons.size(); f++) {
			var polygon = polygons.get(f); var faceCenter = mesh.faces.get(faceIds.get(f)).surface().centroid();
			for (int i = 0; i < polygon.size(); i++) {
				var a = polygon.get(i); var b = polygon.get((i + 1) % polygon.size());
				var physical = List.of(origin, faceCenter, a, b); var normalized = new ArrayList<LiquidPolyhedron.Point>();
				for (var point : physical) normalized.add(scale(point.subtract(origin), 1 / width));
				double area = LiquidPolyhedron.surface(normalized.subList(1, 4)).area(); if (area == 0) continue;
				var tetra = new Tetra(List.copyOf(normalized), physical, faceIds.get(f)); tetrahedra.add(tetra);
				for (int opposite = 1; opposite < 4; opposite++) {
					var triangle = new ArrayList<LiquidPolyhedron.Point>();
					for (int v = 0; v < 4; v++) if (v != opposite) triangle.add(physical.get(v));
					var key = Triangle.key(triangle); var other = unmatched.remove(key);
					if (other == null) unmatched.put(key, new Side(tetra, opposite));
					else {
						tetra.unknown[opposite] = unknowns; tetra.sign[opposite] = -1;
						other.tetra.unknown[other.opposite] = unknowns++;
					}
				}
			}
		}
		if (!unmatched.isEmpty()) throw new IllegalArgumentException("Liquid velocity tessellation has an open internal face");
		int tetraCount = tetrahedra.size(), faceCount = unknowns + tetraCount;
		int[] first = new int[faceCount], second = first.clone(); java.util.Arrays.fill(first, -1); java.util.Arrays.fill(second, -1);
		var fixed = new SignedDyadic[faceCount]; int[][] indices = new int[tetraCount][4], signs = new int[tetraCount][4];
		double[][][] mass = new double[tetraCount][4][4]; var preferred = new SignedDyadic[tetraCount][4];
		var volumeWeights = new SignedDyadic[tetraCount]; double widthSquared = width * width, widthCubed = widthSquared * width;
		var groups = new HashMap<Integer, ArrayList<Integer>>(); SignedDyadic totalRate = SignedDyadic.ZERO;
		for (int f : mesh.cellFaces[cell]) totalRate = totalRate.add(mesh.faces.get(f).first() == cell ? volumeRate[f] : volumeRate[f].negate());
		for (int t = 0; t < tetraCount; t++) {
			var tetra = tetrahedra.get(t); groups.computeIfAbsent(tetra.boundaryFace, ignored -> new ArrayList<>()).add(t);
			indices[t][0] = unknowns + t; signs[t][0] = 1; first[unknowns + t] = t; second[unknowns + t] = tetraCount;
			volumeWeights[t] = SignedDyadic.of(tetra.volume * widthCubed); double covarianceTrace = 0;
			for (var point : tetra.vertices) { var d = point.subtract(tetra.center); covarianceTrace += d.dot(d) / 20; }
			for (int i = 1; i < 4; i++) {
				int f = tetra.unknown[i]; indices[t][i] = f; signs[t][i] = tetra.sign[i];
				if (tetra.sign[i] > 0) first[f] = t; else second[f] = t;
			}
			for (int i = 0; i < 4; i++) {
				var polygon = new ArrayList<LiquidPolyhedron.Point>();
				for (int v = 0; v < 4; v++) if (v != i) polygon.add(tetra.physicalVertices.get(v));
				preferred[t][i] = exactTriangleFlux(polygon, tetra.physicalVertices.get(i), target); var a = tetra.center.subtract(tetra.vertices.get(i));
				for (int j = 0; j < 4; j++) {
					var b = tetra.center.subtract(tetra.vertices.get(j)); mass[t][i][j] = (a.dot(b) + covarianceTrace) / (9 * tetra.volume * widthSquared * widthSquared);
				}
			}
		}
		double traceQuantization = 0, divergenceQuantization = 0;
		for (var entry : groups.entrySet()) {
			var group = entry.getValue(); group.sort((a, b) -> compareTriangles(tetrahedra.get(a).physicalVertices.subList(1, 4), tetrahedra.get(b).physicalVertices.subList(1, 4)));
			var weights = new SignedDyadic[group.size()];
			for (int i = 0; i < group.size(); i++) weights[i] = SignedDyadic.of(LiquidPolyhedron.surface(tetrahedra.get(group.get(i)).physicalVertices.subList(1, 4)).area());
			int f = entry.getKey(); var outward = mesh.faces.get(f).first() == cell ? volumeRate[f] : volumeRate[f].negate();
			var allocation = ExactVolumeAllocation.allocateRate(outward, weights);
			for (int i = 0; i < group.size(); i++) {
				fixed[unknowns + group.get(i)] = allocation.values()[i];
				if (outward.signum() != 0) traceQuantization = Math.max(traceQuantization, quantumPerMeasure(allocation.quantumExponent(), weights[i].toDouble()));
			}
		}
		var allocation = ExactVolumeAllocation.allocateRate(totalRate, volumeWeights); var source = allocation.values();
		if (totalRate.signum() != 0) for (var volumeWeight : volumeWeights)
			divergenceQuantization = Math.max(divergenceQuantization, quantumPerMeasure(allocation.quantumExponent(), volumeWeight.toDouble()));
		var rates = new ConservativeTetrahedralFlux(indices, signs, mass, first, second, fixed, source).solve(preferred);
		var result = new ArrayList<Tetrahedron>(); double variation = 0, totalVolume = 0;
		for (int t = 0; t < tetraCount; t++) {
			var tetra = tetrahedra.get(t); double[] velocity = {target.x(), target.y(), target.z()}; var outwardFlux = new ArrayList<SignedDyadic>(); var balance = SignedDyadic.ZERO;
			for (int i = 0; i < 4; i++) {
				var rate = signs[t][i] > 0 ? rates[indices[t][i]] : rates[indices[t][i]].negate(); outwardFlux.add(rate); balance = balance.add(rate);
				double q = rate.subtract(preferred[t][i]).toDouble() / widthSquared; var a = tetra.center.subtract(tetra.vertices.get(i));
				for (int axis = 0; axis < 3; axis++) velocity[axis] += q * a.coordinate(axis) / (3 * tetra.volume);
			}
			if (!balance.equals(source[t])) throw new IllegalStateException("Tetrahedral velocity lost exact continuity");
			var value = new LiquidPolyhedron.Point(velocity[0], velocity[1], velocity[2]); var difference = value.subtract(target); double covarianceTrace = 0;
			for (var p : tetra.vertices) { var d = p.subtract(tetra.center); covarianceTrace += d.dot(d) / 20; }
			double gradient = source[t].toDouble() / (3 * tetra.volume * widthCubed);
			variation += tetra.volume * (difference.dot(difference) + gradient * gradient * widthSquared * covarianceTrace); totalVolume += tetra.volume;
			result.add(new Tetrahedron(tetra.physicalVertices, value, tetra.volume * widthCubed, gradient, tetra.boundaryFace, List.copyOf(outwardFlux)));
		}
		return new Result(List.copyOf(result), variation / totalVolume, totalRate.toDouble() / (totalVolume * widthCubed), traceQuantization, divergenceQuantization);
	}

	private static int compareTriangles(List<LiquidPolyhedron.Point> a, List<LiquidPolyhedron.Point> b) {
		var first = Triangle.key(a).vertices; var second = Triangle.key(b).vertices;
		for (int i = 0; i < 3; i++) for (int axis = 0; axis < 3; axis++) {
			int comparison = Double.compare(first.get(i).coordinate(axis), second.get(i).coordinate(axis)); if (comparison != 0) return comparison;
		}
		return 0;
	}
	private static SignedDyadic exactTriangleFlux(List<LiquidPolyhedron.Point> polygon, LiquidPolyhedron.Point opposite, LiquidPolyhedron.Point velocity) {
		SignedDyadic[][] direction = new SignedDyadic[3][3]; var origin = polygon.getFirst();
		for (int axis = 0; axis < 3; axis++) {
			direction[0][axis] = SignedDyadic.of(polygon.get(1).coordinate(axis)).subtract(SignedDyadic.of(origin.coordinate(axis)));
			direction[1][axis] = SignedDyadic.of(polygon.get(2).coordinate(axis)).subtract(SignedDyadic.of(origin.coordinate(axis)));
			direction[2][axis] = SignedDyadic.of(opposite.coordinate(axis)).subtract(SignedDyadic.of(origin.coordinate(axis)));
		}
		var orientation = SignedDyadic.ZERO; var flux = SignedDyadic.ZERO;
		for (int axis = 0; axis < 3; axis++) {
			int a = (axis + 1) % 3, b = (axis + 2) % 3;
			var vector = product(direction[0][a], direction[1][b]).subtract(product(direction[0][b], direction[1][a]));
			orientation = orientation.add(product(vector, direction[2][axis])); flux = flux.add(product(vector, SignedDyadic.of(velocity.coordinate(axis))));
		}
		if (orientation.signum() > 0) flux = flux.negate();
		return flux.signum() == 0 ? SignedDyadic.ZERO : new SignedDyadic(flux.significand(), Math.subtractExact(flux.exponent(), 1));
	}
	private static SignedDyadic product(SignedDyadic a, SignedDyadic b) {
		return a.signum() == 0 || b.signum() == 0 ? SignedDyadic.ZERO : new SignedDyadic(a.significand().multiply(b.significand()), Math.addExact(a.exponent(), b.exponent()));
	}
	private static double quantumPerMeasure(int quantum, double measure) {
		int exponent = Math.getExponent(measure); return Math.scalb(1 / Math.scalb(measure, -exponent), quantum - exponent);
	}
	private static LiquidPolyhedron.Point scale(LiquidPolyhedron.Point p, double scale) { return new LiquidPolyhedron.Point(p.x() * scale, p.y() * scale, p.z() * scale); }
	private static LiquidPolyhedron.Point mean(List<LiquidPolyhedron.Point> points) {
		double[] sum = new double[3]; for (var p : points) for (int a = 0; a < 3; a++) sum[a] += p.coordinate(a) / points.size();
		return new LiquidPolyhedron.Point(sum[0], sum[1], sum[2]);
	}
}

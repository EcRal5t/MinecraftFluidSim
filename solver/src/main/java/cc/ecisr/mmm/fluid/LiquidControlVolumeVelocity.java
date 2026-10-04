package cc.ecisr.mmm.fluid;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;

/** Raviart-Thomas reconstruction with shared normal traces and uniform control-volume divergence. */
final class LiquidControlVolumeVelocity {
	record Tetrahedron(List<LiquidPolyhedron.Point> vertices, LiquidPolyhedron.Point velocity, double volume, double isotropicGradient,
			int boundaryFace, List<Double> outwardFlux) {
		LiquidPolyhedron.Point sample(LiquidPolyhedron.Point point) {
			var d = point.subtract(mean(vertices));
			return new LiquidPolyhedron.Point(velocity.x() + isotropicGradient * d.x(), velocity.y() + isotropicGradient * d.y(), velocity.z() + isotropicGradient * d.z());
		}
	}
	record Result(List<Tetrahedron> tetrahedra, double squaredVelocityVariation, double divergenceResidual) { }
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
		final int[] unknown = {-1, -1, -1, -1}, sign = {1, 1, 1, 1};
		final double[] known = new double[4];
		final double volume;
		final int boundaryFace;
		final LiquidPolyhedron.Point center;
		Tetra(List<LiquidPolyhedron.Point> vertices, double outwardFlux, int boundaryFace) {
			this.vertices = vertices; known[0] = outwardFlux; this.boundaryFace = boundaryFace;
			var origin = vertices.getFirst();
			volume = vertices.get(1).subtract(origin).dot(vertices.get(2).subtract(origin).cross(vertices.get(3).subtract(origin))) / 6;
			if (!(volume > 0)) throw new IllegalArgumentException("Velocity reconstruction has an inverted tetrahedron");
			center = mean(vertices);
		}
	}
	private record Side(Tetra tetra, int opposite) { }
	private LiquidControlVolumeVelocity() { }

	static Result[] reconstruct(LiquidControlVolumeMesh mesh, double[] normalVelocity, double[] cellVelocity) {
		if (cellVelocity.length != 3 * mesh.cells.size()) throw new IllegalArgumentException("Invalid liquid momentum field");
		var result = new Result[mesh.cells.size()];
		cc.ecisr.mmm.compute.CpuWorkBatch.run(result.length, (from, until) -> {
			for (int c = from; c < until; c++) result[c] = reconstruct(mesh, c, normalVelocity,
					new LiquidPolyhedron.Point(cellVelocity[3 * c], cellVelocity[3 * c + 1], cellVelocity[3 * c + 2]));
		});
		return result;
	}

	static Result reconstruct(LiquidControlVolumeMesh mesh, int cell, double[] normalVelocity, LiquidPolyhedron.Point target) {
		if (normalVelocity.length != mesh.faces.size()) throw new IllegalArgumentException("Invalid shared liquid velocity traces");
		var origin = mesh.cells.get(cell).centroid();
		var polygons = new ArrayList<List<LiquidPolyhedron.Point>>(); var velocities = new ArrayList<Double>(); var faceIds = new ArrayList<Integer>();
		var vertices = new LinkedHashSet<LiquidPolyhedron.Point>(); double width = 0;
		for (int f : mesh.cellFaces[cell]) {
			var face = mesh.faces.get(f);
			if (!Double.isFinite(normalVelocity[f])) throw new IllegalArgumentException("Non-finite shared liquid velocity");
			var polygon = new ArrayList<>(mesh.polygons.get(f)); boolean first = face.first() == cell;
			if (!first) java.util.Collections.reverse(polygon);
			polygons.add(polygon); velocities.add(first ? normalVelocity[f] : -normalVelocity[f]); faceIds.add(f); vertices.addAll(polygon);
			for (var point : polygon) for (int a = 0; a < 3; a++) width = Math.max(width, Math.abs(point.coordinate(a) - origin.coordinate(a)));
		}
		if (!(width > 0)) throw new IllegalArgumentException("Empty liquid velocity geometry");
		var tetrahedra = new ArrayList<Tetra>(); var unmatched = new HashMap<Triangle, Side>(); int unknowns = 0;
		for (int f = 0; f < polygons.size(); f++) {
			var polygon = splitEdges(polygons.get(f), vertices); var faceCenter = LiquidPolyhedron.surface(polygon).centroid();
			for (int i = 0; i < polygon.size(); i++) {
				var a = polygon.get(i); var b = polygon.get((i + 1) % polygon.size());
				var physical = List.of(origin, faceCenter, a, b); var normalized = new ArrayList<LiquidPolyhedron.Point>();
				for (var point : physical) normalized.add(scale(point.subtract(origin), 1 / width));
				double area = LiquidPolyhedron.surface(normalized.subList(1, 4)).area(); if (area == 0) continue;
				var tetra = new Tetra(List.copyOf(normalized), area * velocities.get(f), faceIds.get(f)); tetrahedra.add(tetra);
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
		double boundaryFlux = 0, geometryVolume = 0;
		for (var tetra : tetrahedra) { boundaryFlux += tetra.known[0]; geometryVolume += tetra.volume; }
		double divergenceResidual = boundaryFlux / geometryVolume;
		int count = unknowns + tetrahedra.size() - 1; double[][] matrix = new double[count][count]; double[] rhs = new double[count];
		for (int t = 0; t < tetrahedra.size(); t++) {
			var tetra = tetrahedra.get(t); double covarianceTrace = 0;
			for (var point : tetra.vertices) { var d = point.subtract(tetra.center); covarianceTrace += d.dot(d) / 20; }
			for (int i = 0; i < 4; i++) if (tetra.unknown[i] >= 0) {
				int row = tetra.unknown[i]; var a = tetra.center.subtract(tetra.vertices.get(i));
				rhs[row] += tetra.sign[i] * a.dot(target) / 3;
				for (int j = 0; j < 4; j++) {
					var b = tetra.center.subtract(tetra.vertices.get(j)); double mass = (a.dot(b) + covarianceTrace) / (9 * tetra.volume);
					if (tetra.unknown[j] >= 0) matrix[row][tetra.unknown[j]] += tetra.sign[i] * tetra.sign[j] * mass;
					else rhs[row] -= tetra.sign[i] * mass * tetra.known[j];
				}
				if (t + 1 < tetrahedra.size()) matrix[row][unknowns + t] = matrix[unknowns + t][row] = tetra.sign[i];
			}
			if (t + 1 < tetrahedra.size()) {
				rhs[unknowns + t] += divergenceResidual * tetra.volume;
				for (double q : tetra.known) rhs[unknowns + t] -= q;
			}
		}
		double[] solution = solve(matrix, rhs); var result = new ArrayList<Tetrahedron>(); double variation = 0, totalVolume = 0;
		for (var tetra : tetrahedra) {
			double[] velocity = new double[3]; double divergence = 0, magnitude = 0; var outwardFlux = new ArrayList<Double>();
			for (int i = 0; i < 4; i++) {
				double q = tetra.unknown[i] < 0 ? tetra.known[i] : tetra.sign[i] * solution[tetra.unknown[i]];
				outwardFlux.add(q * width * width);
				divergence += q; magnitude += Math.abs(q); var a = tetra.center.subtract(tetra.vertices.get(i));
				for (int axis = 0; axis < 3; axis++) velocity[axis] += q * a.coordinate(axis) / (3 * tetra.volume);
			}
			if (Math.abs(divergence - divergenceResidual * tetra.volume) > 256 * count * Math.ulp(magnitude))
				throw new IllegalStateException("Liquid velocity tetrahedral flux does not close");
			var value = new LiquidPolyhedron.Point(velocity[0], velocity[1], velocity[2]); var difference = value.subtract(target);
			double covarianceTrace = 0;
			for (var p : tetra.vertices) { var d = p.subtract(tetra.center); covarianceTrace += d.dot(d) / 20; }
			double isotropicGradient = divergence / (3 * tetra.volume);
			variation += tetra.volume * (difference.dot(difference) + isotropicGradient * isotropicGradient * covarianceTrace); totalVolume += tetra.volume;
			var physical = new ArrayList<LiquidPolyhedron.Point>();
			for (var p : tetra.vertices) physical.add(new LiquidPolyhedron.Point(origin.x() + width * p.x(), origin.y() + width * p.y(), origin.z() + width * p.z()));
			result.add(new Tetrahedron(List.copyOf(physical), value, tetra.volume * width * width * width, isotropicGradient / width, tetra.boundaryFace,
					List.copyOf(outwardFlux)));
		}
		return new Result(List.copyOf(result), variation / totalVolume, divergenceResidual / width);
	}

	private static List<LiquidPolyhedron.Point> splitEdges(List<LiquidPolyhedron.Point> polygon, Iterable<LiquidPolyhedron.Point> candidates) {
		var result = new ArrayList<LiquidPolyhedron.Point>();
		for (int i = 0; i < polygon.size(); i++) {
			var a = polygon.get(i); var b = polygon.get((i + 1) % polygon.size()); var direction = b.subtract(a); double length = direction.dot(direction);
			var points = new ArrayList<LiquidPolyhedron.Point>(); points.add(a);
			for (var point : candidates) if (!point.equals(a) && !point.equals(b)) {
				var offset = point.subtract(a); double projection = direction.dot(offset); if (!(projection > 0 && projection < length)) continue;
				var cross = direction.cross(offset); double coordinateError = 0;
				for (int axis = 0; axis < 3; axis++) coordinateError = Math.max(coordinateError,
						Math.max(Math.ulp(a.coordinate(axis)), Math.max(Math.ulp(b.coordinate(axis)), Math.ulp(point.coordinate(axis)))));
				double error = 32 * coordinateError * Math.sqrt(length);
				if (cross.dot(cross) <= error * error) points.add(point);
			}
			points.sort(java.util.Comparator.comparingDouble(p -> direction.dot(p.subtract(a)))); result.addAll(points);
		}
		return result;
	}
	private static double[] solve(double[][] matrix, double[] rhs) {
		int count = rhs.length; double[][] factor = new double[count][]; for (int i = 0; i < count; i++) factor[i] = matrix[i].clone();
		double[] value = rhs.clone(); int[] rows = new int[count]; for (int i = 0; i < count; i++) rows[i] = i;
		for (int column = 0; column < count; column++) {
			int pivot = column; for (int row = column + 1; row < count; row++) if (Math.abs(factor[row][column]) > Math.abs(factor[pivot][column])) pivot = row;
			if (!(Math.abs(factor[pivot][column]) > 0)) throw new IllegalStateException("Singular liquid velocity reconstruction");
			var swap = factor[pivot]; factor[pivot] = factor[column]; factor[column] = swap;
			double scalar = value[pivot]; value[pivot] = value[column]; value[column] = scalar;
			int rowIndex = rows[pivot]; rows[pivot] = rows[column]; rows[column] = rowIndex;
			for (int row = column + 1; row < count; row++) {
				double scale = factor[row][column] / factor[column][column];
				for (int j = column + 1; j < count; j++) factor[row][j] -= scale * factor[column][j];
				value[row] -= scale * value[column]; factor[row][column] = scale;
			}
		}
		for (int row = count - 1; row >= 0; row--) {
			for (int j = row + 1; j < count; j++) value[row] -= factor[row][j] * value[j]; value[row] /= factor[row][row];
			if (!Double.isFinite(value[row])) throw new IllegalStateException("Liquid velocity reconstruction overflow");
		}
		double[] upperMagnitude = new double[count], factorMagnitude = new double[count]; double maximumValue = 0;
		for (int row = 0; row < count; row++) {
			maximumValue = Math.max(maximumValue, Math.abs(value[row]));
			for (int j = row; j < count; j++) upperMagnitude[row] += Math.abs(factor[row][j]);
		}
		for (int row = 0; row < count; row++) {
			double bound = upperMagnitude[row]; for (int j = 0; j < row; j++) bound += Math.abs(factor[row][j]) * upperMagnitude[j];
			factorMagnitude[rows[row]] = bound;
		}
		// Partial-pivoted LU has componentwise backward error bounded by gamma_n |L||U|.
		// Include triangular solve and residual evaluation, whose operation counts are each O(n).
		double epsilonOperations = Math.scalb(8.0 * count, -53), gamma = epsilonOperations / (1 - epsilonOperations);
		for (int row = 0; row < count; row++) {
			double actual = 0, magnitude = Math.abs(rhs[row]);
			for (int j = 0; j < count; j++) { double term = matrix[row][j] * value[j]; actual += term; magnitude += Math.abs(term); }
			if (Math.abs(actual - rhs[row]) > gamma * (magnitude + factorMagnitude[row] * maximumValue))
				throw new IllegalStateException("Liquid velocity reconstruction did not converge");
		}
		return value;
	}
	private static LiquidPolyhedron.Point scale(LiquidPolyhedron.Point p, double scale) { return new LiquidPolyhedron.Point(p.x() * scale, p.y() * scale, p.z() * scale); }
	private static LiquidPolyhedron.Point mean(List<LiquidPolyhedron.Point> points) {
		double[] sum = new double[3]; for (var p : points) for (int a = 0; a < 3; a++) sum[a] += p.coordinate(a) / points.size();
		return new LiquidPolyhedron.Point(sum[0], sum[1], sum[2]);
	}
}

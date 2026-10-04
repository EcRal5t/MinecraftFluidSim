package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/** Raviart-Thomas reconstruction with exact tetrahedral continuity and shared triangle rates. */
final class LiquidControlVolumeVelocity {
	record Tetrahedron(List<LiquidPolyhedron.Point> vertices, LiquidPolyhedron.Point velocity, double volume, double isotropicGradient,
			int boundaryFace, List<Fraction> outwardFlux, LiquidConvexRegion geometry) {
		LiquidPolyhedron.Point sample(LiquidPolyhedron.Point point) {
			var d = point.subtract(mean(vertices));
			return new LiquidPolyhedron.Point(velocity.x() + isotropicGradient * d.x(), velocity.y() + isotropicGradient * d.y(), velocity.z() + isotropicGradient * d.z());
		}
	}
	record Result(List<Tetrahedron> tetrahedra, double squaredVelocityVariation, double divergenceResidual) { }
	private static final class Tetra {
		final List<LiquidPolyhedron.Point> vertices;
		final List<LiquidPolyhedron.Point> physicalVertices;
		final LiquidConvexRegion geometry;
		final int[] unknown = {-1, -1, -1, -1}, sign = {1, 1, 1, 1};
		final double volume;
		final int boundaryFace;
		final LiquidPolyhedron.Point center;
		Tetra(List<LiquidPolyhedron.Point> vertices, List<LiquidPolyhedron.Point> physicalVertices, int boundaryFace, LiquidConvexRegion geometry, double width) {
			this.geometry = geometry;
			this.vertices = vertices; this.physicalVertices = physicalVertices; this.boundaryFace = boundaryFace;
			var scale = LiquidExactPolygon.Fraction.of(width);
			volume = geometry.measure().volume().divide(scale.multiply(scale).multiply(scale)).rounded();
			if (!(volume > 0)) throw new IllegalArgumentException("Velocity reconstruction has an inverted tetrahedron");
			center = mean(vertices);
		}
	}
	private record Side(Tetra tetra, int opposite) { }
	private LiquidControlVolumeVelocity() { }

	static Result[] reconstruct(LiquidControlVolumeMesh mesh, Fraction[] volumeRate, double[] cellVelocity) {
		if (cellVelocity.length != 3 * mesh.cells.size()) throw new IllegalArgumentException("Invalid liquid momentum field");
		var result = new Result[mesh.cells.size()];
		cc.ecisr.mmm.compute.CpuWorkBatch.run(result.length, (from, until) -> {
			for (int c = from; c < until; c++) result[c] = reconstruct(mesh, c, volumeRate,
					new LiquidPolyhedron.Point(cellVelocity[3 * c], cellVelocity[3 * c + 1], cellVelocity[3 * c + 2]));
		});
		return result;
	}

	static Result reconstruct(LiquidControlVolumeMesh mesh, int cell, Fraction[] volumeRate, LiquidPolyhedron.Point target) {
		if (volumeRate.length != mesh.faces.size()) throw new IllegalArgumentException("Invalid shared liquid velocity traces");
		var origin = mesh.cells.get(cell).geometry().centroidCoordinates();
		double width = 0;
		for (int f : mesh.cellFaces[cell]) {
			if (volumeRate[f] == null || !Double.isFinite(volumeRate[f].toDouble())) throw new IllegalArgumentException("Non-finite shared liquid velocity");
			for (var vertex : mesh.exactPolygons.get(f).vertices()) {
				var point = vertex.coordinates();
				for (int a = 0; a < 3; a++) width = Math.max(width, Math.abs(point[a].subtract(origin[a]).rounded()));
			}
		}
		if (!(width > 0)) throw new IllegalArgumentException("Empty liquid velocity geometry");
		var tetrahedra = new ArrayList<Tetra>(); var unmatched = new HashMap<List<LiquidExactPolygon.Vertex>, Side>(); int unknowns = 0;
		var exactWidth = LiquidExactPolygon.Fraction.of(width);
		for (int f : mesh.cellFaces[cell]) {
			var exact = mesh.exactPolygons.get(f); if (mesh.faces.get(f).first() != cell) exact = exact.reversed();
			for (int i = 0; i < exact.vertices().size(); i++) {
				var faces = exact.tetrahedron(origin, i);
				if (!faces.getFirst().hasArea()) continue;
				var geometry = LiquidConvexRegion.ofFaces(faces); var physical = new ArrayList<LiquidPolyhedron.Point>(); var normalized = new ArrayList<LiquidPolyhedron.Point>();
				for (var vertex : exact.tetrahedronVertices(origin, i)) {
					physical.add(vertex.rounded()); var point = vertex.coordinates(); double[] relative = new double[3];
					for (int a = 0; a < 3; a++) relative[a] = point[a].subtract(origin[a]).divide(exactWidth).rounded();
					normalized.add(new LiquidPolyhedron.Point(relative[0], relative[1], relative[2]));
				}
				var tetra = new Tetra(List.copyOf(normalized), List.copyOf(physical), f, geometry, width); tetrahedra.add(tetra);
				for (int opposite = 1; opposite < 4; opposite++) {
					var key = faces.get(opposite).vertexKey(); var other = unmatched.remove(key);
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
		var fixed = new Fraction[faceCount]; int[][] indices = new int[tetraCount][4], signs = new int[tetraCount][4];
		double[][][] mass = new double[tetraCount][4][4]; var preferred = new Fraction[tetraCount][4];
		var volumeWeights = new Fraction[tetraCount]; double widthSquared = width * width, widthCubed = widthSquared * width;
		var groups = new HashMap<Integer, ArrayList<Integer>>(); Fraction totalRate = Fraction.ZERO;
		for (int f : mesh.cellFaces[cell]) totalRate = totalRate.add(mesh.faces.get(f).first() == cell ? volumeRate[f] : volumeRate[f].negate());
		for (int t = 0; t < tetraCount; t++) {
			var tetra = tetrahedra.get(t); groups.computeIfAbsent(tetra.boundaryFace, ignored -> new ArrayList<>()).add(t);
			indices[t][0] = unknowns + t; signs[t][0] = 1; first[unknowns + t] = t; second[unknowns + t] = tetraCount;
			volumeWeights[t] = tetra.geometry.measure().volume(); double covarianceTrace = 0;
			for (var point : tetra.vertices) { var d = point.subtract(tetra.center); covarianceTrace += d.dot(d) / 20; }
			for (int i = 1; i < 4; i++) {
				int f = tetra.unknown[i]; indices[t][i] = f; signs[t][i] = tetra.sign[i];
				if (tetra.sign[i] > 0) first[f] = t; else second[f] = t;
			}
			for (int i = 0; i < 4; i++) {
				preferred[t][i] = exactTriangleFlux(tetra.geometry.faces().get(i), target); var a = tetra.center.subtract(tetra.vertices.get(i));
				for (int j = 0; j < 4; j++) {
					var b = tetra.center.subtract(tetra.vertices.get(j)); mass[t][i][j] = (a.dot(b) + covarianceTrace) / (9 * tetra.volume * widthSquared * widthSquared);
				}
			}
		}
		for (var entry : groups.entrySet()) {
			var group = entry.getValue(); group.sort((a, b) -> compareTriangles(tetrahedra.get(a).geometry.faces().getFirst(), tetrahedra.get(b).geometry.faces().getFirst()));
			var weights = new Fraction[group.size()];
			int axis = 0; var normal = mesh.faces.get(entry.getKey()).areaVector(); while (normal.get(axis).signum() == 0) axis++;
			for (int i = 0; i < group.size(); i++) weights[i] = tetrahedra.get(group.get(i)).geometry.faces().getFirst().areaVector()[axis].absolute();
			int f = entry.getKey(); var outward = mesh.faces.get(f).first() == cell ? volumeRate[f] : volumeRate[f].negate();
			var allocation = LiquidFluxAllocation.proportional(outward, weights);
			for (int i = 0; i < group.size(); i++) {
				fixed[unknowns + group.get(i)] = allocation[i];
			}
		}
		var source = LiquidFluxAllocation.proportional(totalRate, volumeWeights);
		var rates = new ConservativeTetrahedralFlux(indices, signs, mass, first, second, fixed, source).solve(preferred);
		var result = new ArrayList<Tetrahedron>(); double variation = 0, totalVolume = 0;
		for (int t = 0; t < tetraCount; t++) {
			var tetra = tetrahedra.get(t); double[] velocity = {target.x(), target.y(), target.z()}; var outwardFlux = new ArrayList<Fraction>(); var balance = Fraction.ZERO;
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
			result.add(new Tetrahedron(tetra.physicalVertices, value, tetra.volume * widthCubed, gradient, tetra.boundaryFace, List.copyOf(outwardFlux), tetra.geometry));
		}
		return new Result(List.copyOf(result), variation / totalVolume, totalRate.toDouble() / (totalVolume * widthCubed));
	}

	private static int compareTriangles(LiquidExactPolygon a, LiquidExactPolygon b) {
		var first = a.vertexKey(); var second = b.vertexKey();
		for (int i = 0; i < 3; i++) {
			var x = first.get(i); var y = second.get(i);
			for (int axis = 0; axis < 3; axis++) { int comparison = x.coordinate(axis).compareTo(y.coordinate(axis)); if (comparison != 0) return comparison; }
			int comparison = x.w().compareTo(y.w()); if (comparison != 0) return comparison;
		}
		return 0;
	}
	private static Fraction exactTriangleFlux(LiquidExactPolygon polygon, LiquidPolyhedron.Point velocity) {
		var area = polygon.areaVector(); var flux = LiquidExactPolygon.Fraction.of(0);
		for (int axis = 0; axis < 3; axis++) flux = flux.add(area[axis].multiply(LiquidExactPolygon.Fraction.of(velocity.coordinate(axis))));
		return flux;
	}
	private static LiquidPolyhedron.Point mean(List<LiquidPolyhedron.Point> points) {
		double[] sum = new double[3]; for (var p : points) for (int a = 0; a < 3; a++) sum[a] += p.coordinate(a) / points.size();
		return new LiquidPolyhedron.Point(sum[0], sum[1], sum[2]);
	}
}

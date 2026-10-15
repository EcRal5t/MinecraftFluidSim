package mmm.fluid;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.TreeSet;
import mmm.chemistry.ExactRational;
import mmm.fluid.GasGeometryOverlap.Point;

/** Exact reference for common wet-box meshes, affine face traces, and positive local remapping. */
final class LiquidMovingMesh {
	@FunctionalInterface interface Velocity { Point sample(int cell, Point position); }
	record SourceBox(int cell, LiquidCellShape.Box box) { }
	record Simplex(int sourceCell, LiquidMappedTetrahedron map) { }
	record Mesh(List<Simplex> simplices, ExactRational maximumFluxCorrection,
	            ExactRational maximumFaceDisplacement, ExactRational firstMomentErrorBound) {
		Mesh { simplices = List.copyOf(simplices); }
	}
	private record FaceKey(int axis, ExactRational plane, ExactRational low, ExactRational high,
	                       ExactRational otherLow, ExactRational otherHigh) { }
	private static final class Face {
		final FaceKey key;
		final List<Point> corners;
		final int[] owner = {-1, -1};
		ExactRational flux, originalFlux;
		Point movedCentre;
		final Point[] interior = new Point[3], movedInterior = new Point[3];
		Face(FaceKey key) {
			this.key = key; var points = new ArrayList<Point>(4);
			for (int corner : new int[]{0, 1, 3, 2}) {
				var coordinate = new ExactRational[3]; coordinate[key.axis] = key.plane;
				coordinate[(key.axis + 1) % 3] = (corner & 1) == 0 ? key.low : key.high;
				coordinate[(key.axis + 2) % 3] = (corner & 2) == 0 ? key.otherLow : key.otherHigh;
				points.add(new Point(coordinate[0], coordinate[1], coordinate[2]));
			}
			corners = List.copyOf(points);
		}
		ExactRational area() { return key.high.subtract(key.low).multiply(key.otherHigh.subtract(key.otherLow)); }
		int peer(int cube) { return owner[0] == cube ? owner[1] : owner[0]; }
		int sign(int cube) { return owner[0] == cube ? 1 : -1; }
	}
	private record Cube(int sourceCell, Point lower, Point upper, List<Face> faces) { }
	private LiquidMovingMesh() { }

	static Mesh prepare(List<SourceBox> source, int sizeX, int sizeY, int sizeZ, Velocity velocity,
	                    ExactRational time, int maximumSimplices, int maximumPairTests) {
		if (source == null || velocity == null || time == null || time.signum() <= 0 || maximumSimplices <= 0
				|| maximumPairTests < 0 || sizeX <= 0 || sizeY <= 0 || sizeZ <= 0) throw new IllegalArgumentException("Incomplete moving liquid mesh input");
		var cubes = refine(List.copyOf(source), sizeX, sizeY, sizeZ, maximumSimplices);
		var faces = new LinkedHashMap<FaceKey, Face>();
		var incident = new LinkedHashMap<Point, TreeSet<Integer>>();
		for (int i = 0; i < cubes.size(); i++) {
			var cube = cubes.get(i);
			for (int a = 0; a < 3; a++) for (int side = 0; side < 2; side++) {
				int b = (a + 1) % 3, c = (a + 2) % 3;
				var key = new FaceKey(a, (side == 0 ? cube.lower : cube.upper).component(a), cube.lower.component(b),
						cube.upper.component(b), cube.lower.component(c), cube.upper.component(c));
				var face = faces.computeIfAbsent(key, Face::new); int owner = side == 0 ? 1 : 0;
				if (face.owner[owner] >= 0) throw new GasTopologyBlockedException("Moving liquid source boxes overlap");
				face.owner[owner] = i; cube.faces.add(face);
				for (var point : face.corners) incident.computeIfAbsent(point, ignored -> new TreeSet<>()).add(cube.sourceCell);
			}
		}
		var moved = new HashMap<Point, Point>(); var speeds = new HashMap<Point, Point>();
		for (var entry : incident.entrySet()) {
			var speed = averageVelocity(velocity, entry.getValue(), entry.getKey()); speeds.put(entry.getKey(), speed);
			moved.put(entry.getKey(), entry.getKey().add(speed.multiply(time)));
		}
		for (var face : faces.values()) {
			var cells = new TreeSet<Integer>();
			for (int owner : face.owner) if (owner >= 0) cells.add(cubes.get(owner).sourceCell);
			face.flux = averageVelocity(velocity, cells, mean(face.corners)).component(face.key.axis).multiply(time).multiply(face.area());
			face.originalFlux = face.flux;
			matchTraceMoments(face, cells, velocity, speeds, moved, time);
		}
		balance(cubes, sizeX, sizeY, sizeZ);
		var maximumFlux = ExactRational.ZERO; var maximumDisplacement = ExactRational.ZERO;
		for (var face : faces.values()) {
			maximumFlux = max(maximumFlux, abs(face.flux.subtract(face.originalFlux)));
			var centre = face.movedCentre; var current = sweptVolume(face, moved); var unit = unit(face.key.axis);
			face.movedCentre = centre.add(unit); var derivative = sweptVolume(face, moved).subtract(current); face.movedCentre = centre;
			if (derivative.isZero()) throw new GasTopologyBlockedException("Moving liquid face has zero projected area");
			var correction = divide(face.flux.subtract(current), derivative);
			face.movedCentre = centre.add(unit.multiply(correction)); maximumDisplacement = max(maximumDisplacement, abs(correction));
			if (!sweptVolume(face, moved).equals(face.flux))
				throw new IllegalStateException("Shared liquid face sweep does not match its volume flux");
		}
		var result = new ArrayList<Simplex>(); var preferredMesh = new ArrayList<LiquidMappedTetrahedron>(); var momentBound = ExactRational.ZERO;
		for (int i = 0; i < cubes.size(); i++) {
			var cube = cubes.get(i); var anchor = cube.lower.interpolate(cube.upper, ExactRational.ONE.divide(2));
			var mappedAnchor = mean(cube.faces.stream().flatMap(face -> face.corners.stream()).distinct().map(moved::get).toList());
			var preferred = new ArrayList<LiquidMappedTetrahedron>(60);
			for (var face : cube.faces) {
				var before = triangles(face, null); var after = triangles(face, moved);
				for (int t = 0; t < before.size(); t++) {
					if (result.size() + preferred.size() >= maximumSimplices)
						throw new GasTopologyBlockedException("Moving liquid mesh exceeds its simplex budget");
					var original = before.get(t); var arrival = after.get(t); int first = face.sign(i) > 0 ? 1 : 2, second = 3 - first;
					preferred.add(new LiquidMappedTetrahedron(List.of(anchor, original.get(0), original.get(first), original.get(second)),
							List.of(mappedAnchor, arrival.get(0), arrival.get(first), arrival.get(second))));
				}
			}
			var partition = LiquidMeshVolumePartition.match(preferred, maximumSimplices - result.size());
			preferredMesh.addAll(preferred);
			for (var map : partition.maps()) result.add(new Simplex(cube.sourceCell, map));
			momentBound = momentBound.add(partition.firstMomentErrorBound());
		}
		LiquidMeshEmbedding.requireDisjoint(preferredMesh, maximumPairTests);
		return new Mesh(result, maximumFlux, maximumDisplacement, momentBound);
	}

	private static List<Cube> refine(List<SourceBox> source, int sizeX, int sizeY, int sizeZ, int maximumSimplices) {
		var coordinates = List.of(new TreeSet<ExactRational>(), new TreeSet<ExactRational>(), new TreeSet<ExactRational>());
		var lower = new ArrayList<Point>(); var upper = new ArrayList<Point>();
		int count = Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ);
		for (var box : source) {
			if (box.cell < 0 || box.cell >= count) throw new IllegalArgumentException("Moving liquid source cell is outside the grid");
			int[] cell = {box.cell % sizeX, box.cell / sizeX % sizeY, box.cell / (sizeX * sizeY)};
			var lo = new ExactRational[3]; var hi = new ExactRational[3];
			for (int a = 0; a < 3; a++) {
				lo[a] = ExactRational.of(cell[a]).add(exact(box.box.lower(a))); hi[a] = ExactRational.of(cell[a]).add(exact(box.box.upper(a)));
				coordinates.get(a).add(lo[a]); coordinates.get(a).add(hi[a]);
			}
			lower.add(new Point(lo[0], lo[1], lo[2])); upper.add(new Point(hi[0], hi[1], hi[2]));
		}
		var result = new ArrayList<Cube>();
		for (int i = 0; i < source.size(); i++) {
			var axis = new ArrayList<List<ExactRational>>(3); long cubes = 1;
			for (int a = 0; a < 3; a++) {
				axis.add(List.copyOf(coordinates.get(a).subSet(lower.get(i).component(a), true, upper.get(i).component(a), true)));
				cubes = Math.multiplyExact(cubes, axis.get(a).size() - 1);
			}
			if (cubes > maximumSimplices / 24 - result.size()) throw new GasTopologyBlockedException("Conforming liquid source refinement exceeds its simplex budget");
			for (int z = 0; z + 1 < axis.get(2).size(); z++) for (int y = 0; y + 1 < axis.get(1).size(); y++) for (int x = 0; x + 1 < axis.get(0).size(); x++)
				result.add(new Cube(source.get(i).cell, new Point(axis.get(0).get(x), axis.get(1).get(y), axis.get(2).get(z)),
						new Point(axis.get(0).get(x + 1), axis.get(1).get(y + 1), axis.get(2).get(z + 1)), new ArrayList<>(6)));
		}
		return result;
	}
	private static void balance(List<Cube> cubes, int sizeX, int sizeY, int sizeZ) {
		int[] size = {sizeX, sizeY, sizeZ}; var visited = new boolean[cubes.size()]; var parent = new int[cubes.size()];
		var edge = new Face[cubes.size()]; var residual = new ExactRational[cubes.size()];
		for (int i = 0; i < cubes.size(); i++) {
			residual[i] = ExactRational.ZERO;
			for (var face : cubes.get(i).faces) residual[i] = residual[i].add(face.flux.multiply(face.sign(i)));
		}
		for (int start = 0; start < cubes.size(); start++) if (!visited[start]) {
			var component = new ArrayList<Integer>(); component.add(start); visited[start] = true;
			int root = start; Face outlet = null;
			for (int p = 0; p < component.size(); p++) {
				int node = component.get(p);
				for (var face : cubes.get(node).faces) {
					int peer = face.peer(node);
					if (peer >= 0 && !visited[peer]) { component.add(peer); visited[peer] = true; }
					if (peer < 0 && face.key.plane.signum() > 0 && face.key.plane.compareTo(ExactRational.of(size[face.key.axis])) < 0
							&& (outlet == null || face.area().compareTo(outlet.area()) > 0)) { root = node; outlet = face; }
				}
			}
			for (int node : component) parent[node] = -1;
			var order = new ArrayList<Integer>(); order.add(root); parent[root] = root;
			for (int p = 0; p < order.size(); p++) for (var face : cubes.get(order.get(p)).faces) {
				int peer = face.peer(order.get(p));
				if (peer >= 0 && parent[peer] < 0) { parent[peer] = order.get(p); edge[peer] = face; order.add(peer); }
			}
			for (int p = order.size() - 1; p > 0; p--) {
				int node = order.get(p); edge[node].flux = edge[node].flux.subtract(residual[node].multiply(edge[node].sign(node)));
				residual[parent[node]] = residual[parent[node]].add(residual[node]);
			}
			if (outlet != null) outlet.flux = outlet.flux.subtract(residual[root].multiply(outlet.sign(root)));
			else if (!residual[root].isZero()) throw new GasTopologyBlockedException("Closed moving liquid component has incompatible volume flux");
		}
	}
	private static ExactRational sweptVolume(Face face, HashMap<Point, Point> moved) {
		var before = face.corners; var after = before.stream().map(moved::get).toList();
		var volume = ExactRational.ZERO;
		for (var triangle : triangles(face, moved)) volume = volume.add(GasGeometryOverlap.determinant(triangle.get(0), triangle.get(1), triangle.get(2)).divide(6));
		for (int i = 0; i < 4; i++) {
			int next = (i + 1) % 4;
			volume = volume.subtract(GasGeometryOverlap.determinant(mean(before), before.get(i), before.get(next)).divide(6));
			var side = List.of(before.get(i), before.get(next), after.get(next), after.get(i)); var anchor = mean(side);
			for (int e = 0; e < 4; e++) volume = volume.add(GasGeometryOverlap.determinant(anchor, side.get(e), side.get((e + 1) % 4)).divide(6));
		}
		return volume;
	}
	private static List<List<Point>> triangles(Face face, HashMap<Point, Point> moved) {
		boolean after = moved != null; var centre = after ? face.movedCentre : mean(face.corners); var result = new ArrayList<List<Point>>(10);
		for (int edge = 0; edge < 4; edge++) {
			var first = face.corners.get(edge); var second = face.corners.get((edge + 1) % 4);
			if (after) { first = moved.get(first); second = moved.get(second); }
			var inner = edge < 3 ? (after ? face.movedInterior[edge] : face.interior[edge]) : null;
			if (inner == null) result.add(List.of(centre, first, second));
			else { result.add(List.of(centre, first, inner)); result.add(List.of(first, second, inner)); result.add(List.of(second, centre, inner)); }
		}
		return result;
	}
	/** Three face-local bubbles restore the constant and both tangential normal-trace moments. */
	private static void matchTraceMoments(Face face, Iterable<Integer> cells, Velocity velocity,
	                                      HashMap<Point, Point> speeds, HashMap<Point, Point> moved, ExactRational time) {
		var desired = new ExactRational[]{divide(face.flux, time.multiply(face.area())), ExactRational.ZERO, ExactRational.ZERO};
		var values = new ExactRational[4]; var cornerCoordinates = new int[][]{{-1,-1},{1,-1},{1,1},{-1,1}};
		for (int k = 0; k < 4; k++) {
			values[k] = speeds.get(face.corners.get(k)).component(face.key.axis);
			var actual = averageVelocity(velocity, cells, face.corners.get(k)).component(face.key.axis);
			for (int a = 0; a < 2; a++) desired[a + 1] = desired[a + 1].add(actual.multiply(cornerCoordinates[k][a]).divide(24));
		}
		var centreValue = ExactRational.ZERO; for (var value : values) centreValue = centreValue.add(value.divide(4));
		var represented = new ExactRational[]{ExactRational.ZERO, ExactRational.ZERO, ExactRational.ZERO};
		for (int k = 0; k < 4; k++) {
			int next = (k + 1) % 4; var sum = centreValue.add(values[k]).add(values[next]); represented[0] = represented[0].add(sum.divide(12));
			for (int a = 0; a < 2; a++) represented[a + 1] = represented[a + 1].add(sum.multiply(cornerCoordinates[k][a] + cornerCoordinates[next][a])
					.add(values[k].multiply(cornerCoordinates[k][a])).add(values[next].multiply(cornerCoordinates[next][a])).divide(96));
		}
		for (int a = 0; a < 3; a++) desired[a] = desired[a].subtract(represented[a]);
		var middle = desired[1].multiply(36); var sum = desired[0].multiply(12).subtract(middle); var difference = desired[2].multiply(36);
		var corrections = new ExactRational[]{sum.subtract(difference).divide(2), middle, sum.add(difference).divide(2)};
		face.movedCentre = mean(face.corners.stream().map(moved::get).toList());
		var centre = mean(face.corners); var normal = unit(face.key.axis);
		for (int k = 0; k < 3; k++) if (!corrections[k].isZero()) {
			face.interior[k] = mean(List.of(centre, face.corners.get(k), face.corners.get(k + 1)));
			face.movedInterior[k] = mean(List.of(face.movedCentre, moved.get(face.corners.get(k)), moved.get(face.corners.get(k + 1))))
					.add(normal.multiply(time.multiply(corrections[k])));
		}
	}
	private static Point averageVelocity(Velocity velocity, Iterable<Integer> cells, Point point) {
		Point result = new Point(ExactRational.ZERO, ExactRational.ZERO, ExactRational.ZERO); int count = 0;
		for (int cell : cells) { result = result.add(velocity.sample(cell, point)); count++; }
		return result.multiply(ExactRational.ONE.divide(count));
	}
	private static Point mean(List<Point> points) {
		Point result = new Point(ExactRational.ZERO, ExactRational.ZERO, ExactRational.ZERO);
		for (var point : points) result = result.add(point); return result.multiply(ExactRational.ONE.divide(points.size()));
	}
	private static Point unit(int axis) { return new Point(axis == 0 ? ExactRational.ONE : ExactRational.ZERO, axis == 1 ? ExactRational.ONE : ExactRational.ZERO, axis == 2 ? ExactRational.ONE : ExactRational.ZERO); }
	private static ExactRational max(ExactRational a, ExactRational b) { return a.compareTo(b) >= 0 ? a : b; }
	private static ExactRational abs(ExactRational value) { return value.signum() < 0 ? value.multiply(-1) : value; }
	private static ExactRational divide(ExactRational a, ExactRational b) { return new ExactRational(a.numerator().multiply(b.denominator()), a.denominator().multiply(b.numerator())); }
	private static ExactRational exact(mmm.units.SignedDyadic value) { return PlicComponentGeometry.ratio(value, mmm.units.SignedDyadic.of(1)); }
}

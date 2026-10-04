package cc.ecisr.mmm.fluid;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Preserves the complete interface geometry across private backend candidates. */
final class LiquidGeometryStateMapper {
	private LiquidGeometryStateMapper() { }

	static LiquidGeometryState capture(List<LiquidGeometryRefinement.Cell> cells, double rootWidth, int[] markerCells) {
		return capture(cells, rootWidth, markerCells, java.util.stream.IntStream.range(0, cells.size()).toArray());
	}
	static LiquidGeometryState capture(List<LiquidGeometryRefinement.Cell> cells, double rootWidth, int[] markerCells, int[] fragmentOwners) {
		return capture(cells, rootWidth, markerCells, fragmentOwners, new double[3 * cells.size()]);
	}
	static LiquidGeometryState capture(List<LiquidGeometryRefinement.Cell> cells, double rootWidth, int[] markerCells, int[] fragmentOwners, double[] velocity) {
		if (fragmentOwners.length != cells.size()) throw new IllegalArgumentException("Incomplete liquid fragment ownership");
		if (velocity.length != 3 * cells.size()) throw new IllegalArgumentException("Incomplete liquid geometric velocity field");
		var packed = new ArrayList<LiquidGeometryState.ControlVolume>();
		for (int c = 0; c < cells.size(); c++) packed.add(pack(cells.get(c), java.util.Arrays.copyOfRange(velocity, 3 * c, 3 * c + 3)));
		var order = new ArrayList<Integer>(); for (int c = 0; c < cells.size(); c++) order.add(c);
		order.sort((a, b) -> LiquidGeometryState.compareGeometry(packed.get(a), packed.get(b)));
		int[] canonical = new int[cells.size()]; for (int c = 0; c < cells.size(); c++) canonical[order.get(c)] = c;
		var first = new java.util.HashMap<Integer, Integer>(); int[] groups = new int[cells.size()];
		for (int ordinal : order) {
			int owner = fragmentOwners[ordinal]; if (owner < 0 || owner >= cells.size()) throw new IllegalArgumentException("Liquid fragment has no inventory group");
			first.putIfAbsent(owner, canonical[ordinal]); groups[canonical[ordinal]] = first.get(owner);
		}
		var result = new ArrayList<LiquidGeometryState.ControlVolume>();
		for (int ordinal : order) result.add(packed.get(ordinal));
		int[] owners = markerCells.clone();
		for (int p = 0; p < owners.length; p++) {
			if (owners[p] < 0 || owners[p] >= cells.size()) throw new IllegalArgumentException("Liquid quadrature has no geometry");
			owners[p] = first.get(fragmentOwners[owners[p]]);
		}
		return new LiquidGeometryState(rootWidth, result, owners, groups);
	}

	private static LiquidGeometryState.ControlVolume pack(LiquidGeometryRefinement.Cell cell, double[] velocity) {
		var faces = LiquidExactPolygon.faces(cell.geometry());
		var points = new java.util.TreeSet<LiquidExactPolygon.Vertex>(java.util.Comparator.comparing(LiquidExactPolygon.Vertex::x)
				.thenComparing(LiquidExactPolygon.Vertex::y).thenComparing(LiquidExactPolygon.Vertex::z).thenComparing(LiquidExactPolygon.Vertex::w));
		for (var face : faces) points.addAll(face.vertices());
		var vertices = new LinkedHashMap<LiquidExactPolygon.Vertex, Integer>();
		for (var point : points) vertices.put(point, vertices.size());
		var cycles = new ArrayList<int[]>(); var order = new ArrayList<Integer>();
		for (var face : faces) {
			var pointsOnFace = face.vertices(); int start = 0;
			for (int v = 1; v < pointsOnFace.size(); v++) if (vertices.get(pointsOnFace.get(v)) < vertices.get(pointsOnFace.get(start))) start = v;
			int[] cycle = new int[pointsOnFace.size()];
			for (int v = 0; v < cycle.length; v++) cycle[v] = vertices.get(pointsOnFace.get((start + v) % cycle.length));
			order.add(cycles.size()); cycles.add(cycle);
		}
		order.sort((a, b) -> java.util.Arrays.compare(cycles.get(a), cycles.get(b)));
		var xyz = new java.math.BigInteger[4 * vertices.size()]; var planes = new java.math.BigInteger[4 * cycles.size()];
		vertices.forEach((p, v) -> { xyz[4 * v] = p.x(); xyz[4 * v + 1] = p.y(); xyz[4 * v + 2] = p.z(); xyz[4 * v + 3] = p.w(); });
		int[] offsets = new int[cycles.size() + 1];
		for (int f = 0; f < order.size(); f++) offsets[f + 1] = Math.addExact(offsets[f], cycles.get(order.get(f)).length);
		int[] indices = new int[offsets[offsets.length - 1]];
		for (int f = 0; f < order.size(); f++) {
			int original = order.get(f); System.arraycopy(cycles.get(original), 0, indices, offsets[f], offsets[f + 1] - offsets[f]);
			var plane = faces.get(original).coefficients(); for (int a = 0; a < 4; a++) planes[4 * f + a] = plane.get(a);
		}
		return new LiquidGeometryState.ControlVolume(cell.level(), cell.x(), cell.y(), cell.z(), xyz, offsets, indices, planes, velocity);
	}

	static List<LiquidGeometryRefinement.Cell> restore(LiquidGeometryState state) {
		var result = new ArrayList<LiquidGeometryRefinement.Cell>();
		for (var cell : state.cells()) {
			var xyz = cell.copyVertices(); int[] offsets = cell.copyFaceOffsets(), indices = cell.copyFaceVertices();
			var vertices = new ArrayList<LiquidExactPolygon.Vertex>();
			for (int v = 0; v < xyz.length; v += 4) vertices.add(new LiquidExactPolygon.Vertex(xyz[v], xyz[v + 1], xyz[v + 2], xyz[v + 3]));
			if (new java.util.HashSet<>(vertices).size() != vertices.size()) throw new IllegalArgumentException("Liquid geometry repeats a vertex");
			var faces = new ArrayList<LiquidExactPolygon>(); var coefficients = cell.copyPlanes();
			var edges = new java.util.HashMap<Long, Integer>();
			for (int f = 0; f + 1 < offsets.length; f++) {
				var polygon = new ArrayList<LiquidExactPolygon.Vertex>();
				for (int v = offsets[f]; v < offsets[f + 1]; v++) {
					int a = indices[v], b = indices[v + 1 < offsets[f + 1] ? v + 1 : offsets[f]];
					if (a == b) throw new IllegalArgumentException("Liquid geometry contains an empty edge");
					edges.merge(((long) a << 32) | b, 1, Math::addExact); polygon.add(vertices.get(a));
				}
				var plane = List.of(coefficients[4 * f], coefficients[4 * f + 1], coefficients[4 * f + 2], coefficients[4 * f + 3]);
				faces.add(LiquidExactPolygon.restored(polygon, plane));
				for (var vertex : vertices) if (vertex.distance(plane).signum() > 0) throw new IllegalArgumentException("Liquid geometry is non-convex");
			}
			for (var edge : edges.entrySet()) {
				long a = edge.getKey() >>> 32, b = edge.getKey() & 0xffffffffL;
				if (edge.getValue() != 1 || edges.getOrDefault(b << 32 | a, 0) != 1)
					throw new IllegalArgumentException("Liquid geometry surface is open or non-manifold");
			}
			var geometry = LiquidPolyhedron.restoredExact(faces);
			if (LiquidExactPolygon.measure(faces).signum() <= 0) throw new IllegalArgumentException("Liquid geometry has no volume");
			result.add(new LiquidGeometryRefinement.Cell(cell.level(), cell.x(), cell.y(), cell.z(), geometry));
		}
		var leaves = new java.util.LinkedHashMap<LiquidPhasePartition.Address, LiquidGeometryRefinement.Cell>();
		for (var cell : result) leaves.putIfAbsent(LiquidPhasePartition.Address.of(cell), cell);
		new LiquidGeometryRemap(new ArrayList<>(leaves.values())); LiquidPhasePartition.requireDisjoint(result); return List.copyOf(result);
	}

	static void requireCompatible(LiquidGeometryState state, FluidDomain domain) {
		var cells = restore(state); int[] owners = state.copyMarkerCells(), fragments = state.copyFragmentOwners();
		if (owners.length != domain.particles.count()) throw new IllegalArgumentException("Liquid geometry quadrature count differs from inventory");
		LiquidGeometryMomentumAudit.requireCompatible(state, cells, domain);
		int span = Integer.highestOneBit(Math.max(domain.sizeX, Math.max(domain.sizeY, domain.sizeZ)) - 1) << 1;
		double width = span * (double) domain.config.cellSize();
		if (state.rootWidthMetres() != width) throw new IllegalArgumentException("Liquid geometry root differs from the physical domain");
		var volumes = new cc.ecisr.mmm.units.SignedDyadic[cells.size()];
		java.util.Arrays.fill(volumes, cc.ecisr.mmm.units.SignedDyadic.ZERO);
		var moments = new cc.ecisr.mmm.units.SignedDyadic[3 * cells.size()];
		java.util.Arrays.fill(moments, cc.ecisr.mmm.units.SignedDyadic.ZERO);
		double[] positionErrors = new double[cells.size()];
		for (int p = 0; p < owners.length; p++) {
			int c = owners[p]; volumes[c] = volumes[c].add(cc.ecisr.mmm.units.SignedDyadic.of(domain.particles.volume(p)));
			double positionError = Math.max(Math.ulp(domain.particles.positionX(p)), Math.max(Math.ulp(domain.particles.positionY(p)),
					Math.ulp(domain.particles.positionZ(p)))) / width;
			positionErrors[c] = Math.max(positionErrors[c], positionError);
			for (int a = 0; a < 3; a++) {
				double position = a == 0 ? domain.particles.positionX(p) : a == 1 ? domain.particles.positionY(p) : domain.particles.positionZ(p);
				moments[3 * c + a] = moments[3 * c + a].add(cc.ecisr.mmm.units.SignedDyadic.of(position * domain.particles.volume(p)));
			}
		}
		var measures = new LiquidExactPolygon.Measure[cells.size()]; java.util.Arrays.fill(measures, LiquidConvexRegion.EMPTY.measure());
		var coordinateErrors = new double[cells.size()]; var areas = new double[cells.size()]; var vertexCounts = new int[cells.size()];
		for (int fragment = 0; fragment < cells.size(); fragment++) {
			int c = fragments[fragment]; var geometry = cells.get(fragment).geometry(); measures[c] = measures[c].add(LiquidConvexRegion.of(geometry).measure());
			for (var face : LiquidExactPolygon.faces(geometry)) {
				areas[c] += face.surface().area(); vertexCounts[c] += face.vertices().size();
				for (var vertex : face.vertices()) for (int a = 0; a < 3; a++) {
					int limit = a == 0 ? domain.sizeX : a == 1 ? domain.sizeY : domain.sizeZ;
					if (vertex.coordinate(a).multiply(java.math.BigInteger.valueOf(span)).compareTo(vertex.w().multiply(java.math.BigInteger.valueOf(limit))) > 0)
						throw new IllegalArgumentException("Liquid geometry leaves the physical domain");
					coordinateErrors[c] = Math.max(coordinateErrors[c], Math.ulp(vertex.rounded().coordinate(a)));
				}
			}
		}
		for (int c = 0; c < cells.size(); c++) {
			if (fragments[c] != c) continue;
			var geometric = measures[c].moments(); double coordinateError = coordinateErrors[c], area = areas[c]; int vertices = vertexCounts[c];
			double expected = volumes[c].toDouble() / ((double) span * span * span), actual = geometric.volume();
			double error = 64 * vertices * Math.ulp(expected) + 3 * area * coordinateError;
			if (Math.abs(actual - expected) > error)
				throw new IllegalArgumentException("Liquid interface volume differs from exact inventory");
			for (int a = 0; a < 3; a++) {
				double firstMoment = moments[3 * c + a].toDouble() / width / ((double) span * span * span);
				double geometricMoment = a == 0 ? geometric.x() : a == 1 ? geometric.y() : geometric.z();
				if (Math.abs(firstMoment - geometricMoment) > error + expected * positionErrors[c] + 64 * vertices * Math.ulp(firstMoment))
					throw new IllegalArgumentException("Liquid interface centroid differs from inventory quadrature");
			}
		}
	}
}

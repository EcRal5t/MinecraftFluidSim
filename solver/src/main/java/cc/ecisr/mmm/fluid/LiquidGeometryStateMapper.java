package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.geometry.ResolvedControlVolume;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Preserves the complete interface geometry across private backend candidates. */
final class LiquidGeometryStateMapper {
	private static final ThreadLocal<java.util.Map<ResolvedControlVolume, LiquidGeometryRefinement.Cell>> READ_CACHE = new ThreadLocal<>();
	/** Validated immutable cells are reused only while one private backend request owns this scope. */
	static final class ReadScope implements AutoCloseable {
		private final java.util.Map<ResolvedControlVolume, LiquidGeometryRefinement.Cell> previous = READ_CACHE.get();
		private final java.util.Map<ResolvedControlVolume, LiquidGeometryRefinement.Cell> cache = new LinkedHashMap<>(16, 0.75F, true) {
			@Override protected boolean removeEldestEntry(java.util.Map.Entry<ResolvedControlVolume, LiquidGeometryRefinement.Cell> eldest) { return size() > 65_536; }
		};
		private ReadScope() { READ_CACHE.set(cache); }
		@Override public void close() {
			if (READ_CACHE.get() != cache) throw new IllegalStateException("Geometric read scope closed out of order");
			if (previous == null) READ_CACHE.remove(); else READ_CACHE.set(previous);
		}
	}
	static ReadScope openReadScope() { return new ReadScope(); }
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
		var packed = new ArrayList<ResolvedControlVolume>();
		for (int c = 0; c < cells.size(); c++) packed.add(pack(cells.get(c), java.util.Arrays.copyOfRange(velocity, 3 * c, 3 * c + 3)));
		var order = new ArrayList<Integer>(); for (int c = 0; c < cells.size(); c++) order.add(c);
		order.sort((a, b) -> LiquidGeometryState.compareGeometry(packed.get(a), packed.get(b)));
		int[] canonical = new int[cells.size()]; for (int c = 0; c < cells.size(); c++) canonical[order.get(c)] = c;
		var first = new java.util.HashMap<Integer, Integer>(); int[] groups = new int[cells.size()];
		for (int ordinal : order) {
			int owner = fragmentOwners[ordinal]; if (owner < 0 || owner >= cells.size()) throw new IllegalArgumentException("Liquid fragment has no inventory group");
			first.putIfAbsent(owner, canonical[ordinal]); groups[canonical[ordinal]] = first.get(owner);
		}
		var result = new ArrayList<ResolvedControlVolume>();
		for (int ordinal : order) result.add(packed.get(ordinal));
		int[] owners = markerCells.clone();
		for (int p = 0; p < owners.length; p++) {
			if (owners[p] < 0 || owners[p] >= cells.size()) throw new IllegalArgumentException("Liquid quadrature has no geometry");
			owners[p] = first.get(fragmentOwners[owners[p]]);
		}
		return new LiquidGeometryState(rootWidth, result, owners, groups);
	}

	static ResolvedControlVolume pack(LiquidGeometryRefinement.Cell cell, double[] velocity) {
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
		return new ResolvedControlVolume(cell.level(), cell.x(), cell.y(), cell.z(), xyz, offsets, indices, planes, velocity);
	}

	static List<LiquidGeometryRefinement.Cell> restore(LiquidGeometryState state) {
		return restoreCells(state.cells());
	}
	static List<LiquidGeometryRefinement.Cell> restoreCells(List<ResolvedControlVolume> packed) {
		var result = new ArrayList<LiquidGeometryRefinement.Cell>(); var cache = READ_CACHE.get();
		for (var cell : packed) {
			var cached = cache == null ? null : cache.get(cell); if (cached != null) { result.add(cached); continue; }
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
			if (geometry.measure().signum() <= 0) throw new IllegalArgumentException("Liquid geometry has no volume");
			var restored = new LiquidGeometryRefinement.Cell(cell.level(), cell.x(), cell.y(), cell.z(), geometry);
			result.add(restored); if (cache != null) cache.put(cell, restored);
		}
		var leaves = new java.util.LinkedHashMap<LiquidPhasePartition.Address, LiquidGeometryRefinement.Cell>();
		for (var cell : result) leaves.putIfAbsent(LiquidPhasePartition.Address.of(cell), cell);
		new LiquidGeometryRemap(new ArrayList<>(leaves.values())); LiquidPhasePartition.requireDisjoint(result); return List.copyOf(result);
	}

	static void requireCompatible(LiquidGeometryState state, FluidDomain domain) {
		requireCompatible(state, LiquidGeometryInventoryView.of(domain.particles), domain.sizeX, domain.sizeY, domain.sizeZ,
				domain.config.cellSize(), domain.geometry.cutCells.copyMicroMasks());
	}
	static void requireCompatible(LiquidGeometryState state, LiquidGeometryInventoryView inventory,
			int sizeX, int sizeY, int sizeZ, float cellSize, byte[] collisionMasks) {
		var cells = restore(state); int[] owners = state.copyMarkerCells(), fragments = state.copyFragmentOwners();
		if (owners.length != inventory.count()) throw new IllegalArgumentException("Liquid geometry quadrature count differs from inventory");
		LiquidGeometryMomentumAudit.requireCompatible(state, cells, inventory);
		LiquidGeometryCollisionAudit.requireCompatible(state, cells, sizeX, sizeY, sizeZ, cellSize, collisionMasks);
		int extent = Math.max(sizeX, Math.max(sizeY, sizeZ)); int span = extent == 1 ? 1 : Integer.highestOneBit(extent - 1) << 1;
		double width = span * (double) cellSize;
		if (state.rootWidthMetres() != width) throw new IllegalArgumentException("Liquid geometry root differs from the physical domain");
		var rootScale = LiquidExactPolygon.Fraction.of(span); rootScale = rootScale.multiply(rootScale).multiply(rootScale);
		var rootWidth = LiquidExactPolygon.Fraction.of(width);
		var volumes = new cc.ecisr.mmm.units.SignedDyadic[cells.size()];
		java.util.Arrays.fill(volumes, cc.ecisr.mmm.units.SignedDyadic.ZERO);
		var moments = new cc.ecisr.mmm.units.SignedDyadic[3 * cells.size()];
		java.util.Arrays.fill(moments, cc.ecisr.mmm.units.SignedDyadic.ZERO);
		double[] positionErrors = new double[cells.size()];
		for (int p = 0; p < owners.length; p++) {
			int c = owners[p]; volumes[c] = volumes[c].add(cc.ecisr.mmm.units.SignedDyadic.of(inventory.volume(p)));
			double positionError = Math.max(Math.ulp((float) inventory.position(p, 0)), Math.max(Math.ulp((float) inventory.position(p, 1)),
					Math.ulp((float) inventory.position(p, 2))));
			positionErrors[c] = Math.max(positionErrors[c], positionError);
			for (int a = 0; a < 3; a++) {
				moments[3 * c + a] = moments[3 * c + a].add(cc.ecisr.mmm.units.SignedDyadic.of(inventory.position(p, a) * inventory.volume(p)));
			}
		}
		var measures = new LiquidExactPolygon.Measure[cells.size()]; java.util.Arrays.fill(measures, LiquidConvexRegion.EMPTY.measure());
		for (int fragment = 0; fragment < cells.size(); fragment++) {
			int c = fragments[fragment]; var geometry = cells.get(fragment).geometry(); measures[c] = measures[c].add(LiquidConvexRegion.of(geometry).measure());
			for (var face : LiquidExactPolygon.faces(geometry)) {
				for (var vertex : face.vertices()) for (int a = 0; a < 3; a++) {
					int limit = a == 0 ? sizeX : a == 1 ? sizeY : sizeZ;
					if (vertex.coordinate(a).multiply(java.math.BigInteger.valueOf(span)).compareTo(vertex.w().multiply(java.math.BigInteger.valueOf(limit))) > 0)
						throw new IllegalArgumentException("Liquid geometry leaves the physical domain");
				}
			}
		}
		for (int c = 0; c < cells.size(); c++) {
			if (fragments[c] != c) continue;
			var expectedVolume = LiquidExactPolygon.Fraction.of(volumes[c]).divide(rootScale);
			if (!measures[c].volume().equals(expectedVolume))
				throw new IllegalArgumentException("Liquid interface volume differs from exact inventory");
			for (int a = 0; a < 3; a++) {
				var firstMoment = LiquidExactPolygon.Fraction.of(moments[3 * c + a]).divide(rootWidth).divide(rootScale);
				var error = expectedVolume.multiply(LiquidExactPolygon.Fraction.of(positionErrors[c])).divide(rootWidth);
				if (firstMoment.subtract(measures[c].firstMoment(a)).absolute().subtract(error).signum() > 0)
					throw new IllegalArgumentException("Liquid interface centroid differs from inventory quadrature");
			}
		}
	}
}

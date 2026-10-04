package cc.ecisr.mmm.fluid;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Preserves the complete interface geometry across private backend candidates. */
final class LiquidGeometryStateMapper {
	private LiquidGeometryStateMapper() { }

	static LiquidGeometryState capture(List<LiquidGeometryRefinement.Cell> cells, double rootWidth, int[] markerCells) {
		var packed = new ArrayList<LiquidGeometryState.ControlVolume>();
		for (var cell : cells) packed.add(pack(cell));
		var order = new ArrayList<Integer>(); for (int c = 0; c < cells.size(); c++) order.add(c);
		order.sort((a, b) -> LiquidGeometryState.compare(packed.get(a), packed.get(b)));
		int[] canonical = new int[cells.size()]; for (int c = 0; c < cells.size(); c++) canonical[order.get(c)] = c;
		var result = new ArrayList<LiquidGeometryState.ControlVolume>();
		for (int ordinal : order) result.add(packed.get(ordinal));
		int[] owners = markerCells.clone();
		for (int p = 0; p < owners.length; p++) {
			if (owners[p] < 0 || owners[p] >= cells.size()) throw new IllegalArgumentException("Liquid quadrature has no geometry");
			owners[p] = canonical[owners[p]];
		}
		return new LiquidGeometryState(rootWidth, result, owners);
	}

	private static LiquidGeometryState.ControlVolume pack(LiquidGeometryRefinement.Cell cell) {
		var geometry = cell.geometry();
		var points = new java.util.TreeSet<LiquidPolyhedron.Point>(java.util.Comparator.comparingDouble(LiquidPolyhedron.Point::x)
				.thenComparingDouble(LiquidPolyhedron.Point::y).thenComparingDouble(LiquidPolyhedron.Point::z));
		for (var face : geometry.faces()) points.addAll(face);
		var vertices = new LinkedHashMap<LiquidPolyhedron.Point, Integer>();
		for (var point : points) vertices.put(point, vertices.size());
		var cycles = new ArrayList<int[]>(); var order = new ArrayList<Integer>();
		for (var face : geometry.faces()) {
			int start = 0; for (int v = 1; v < face.size(); v++) if (vertices.get(face.get(v)) < vertices.get(face.get(start))) start = v;
			int[] cycle = new int[face.size()]; for (int v = 0; v < cycle.length; v++) cycle[v] = vertices.get(face.get((start + v) % cycle.length));
			order.add(cycles.size()); cycles.add(cycle);
		}
		order.sort((a, b) -> java.util.Arrays.compare(cycles.get(a), cycles.get(b)));
		double[] xyz = new double[3 * vertices.size()]; var planes = new java.math.BigInteger[4 * cycles.size()];
		vertices.forEach((p, v) -> { xyz[3 * v] = p.x(); xyz[3 * v + 1] = p.y(); xyz[3 * v + 2] = p.z(); });
		int[] offsets = new int[cycles.size() + 1];
		for (int f = 0; f < order.size(); f++) offsets[f + 1] = Math.addExact(offsets[f], cycles.get(order.get(f)).length);
		int[] indices = new int[offsets[offsets.length - 1]];
		for (int f = 0; f < order.size(); f++) {
			int original = order.get(f); System.arraycopy(cycles.get(original), 0, indices, offsets[f], offsets[f + 1] - offsets[f]);
			var plane = geometry.planes().get(original);
			for (int a = 0; a < 4; a++) planes[4 * f + a] = plane.positive() ? plane.key().coefficients().get(a) : plane.key().coefficients().get(a).negate();
		}
		return new LiquidGeometryState.ControlVolume(cell.level(), cell.x(), cell.y(), cell.z(), xyz, offsets, indices, planes);
	}

	static List<LiquidGeometryRefinement.Cell> restore(LiquidGeometryState state) {
		var result = new ArrayList<LiquidGeometryRefinement.Cell>();
		for (var cell : state.cells()) {
			double[] xyz = cell.copyVertices(); int[] offsets = cell.copyFaceOffsets(), indices = cell.copyFaceVertices();
			var vertices = new ArrayList<LiquidPolyhedron.Point>();
			for (int v = 0; v < xyz.length; v += 3) vertices.add(new LiquidPolyhedron.Point(xyz[v], xyz[v + 1], xyz[v + 2]));
			if (new java.util.HashSet<>(vertices).size() != vertices.size()) throw new IllegalArgumentException("Liquid geometry repeats a vertex");
			var faces = new ArrayList<List<LiquidPolyhedron.Point>>();
			var planes = new ArrayList<LiquidGeometryPlane>(); var coefficients = cell.copyPlanes();
			var edges = new java.util.HashMap<Long, Integer>();
			for (int f = 0; f + 1 < offsets.length; f++) {
				var polygon = new ArrayList<LiquidPolyhedron.Point>();
				for (int v = offsets[f]; v < offsets[f + 1]; v++) {
					int a = indices[v], b = indices[v + 1 < offsets[f + 1] ? v + 1 : offsets[f]];
					if (a == b) throw new IllegalArgumentException("Liquid geometry contains an empty edge");
					edges.merge(((long) a << 32) | b, 1, Math::addExact); polygon.add(vertices.get(a));
				}
				if (!(LiquidPolyhedron.surface(polygon).area() > 0)) throw new IllegalArgumentException("Liquid geometry contains an empty face");
				faces.add(List.copyOf(polygon));
				planes.add(LiquidGeometryPlane.exact(List.of(coefficients[4 * f], coefficients[4 * f + 1], coefficients[4 * f + 2], coefficients[4 * f + 3])));
			}
			for (var edge : edges.entrySet()) {
				long a = edge.getKey() >>> 32, b = edge.getKey() & 0xffffffffL;
				if (edge.getValue() != 1 || edges.getOrDefault(b << 32 | a, 0) != 1)
					throw new IllegalArgumentException("Liquid geometry surface is open or non-manifold");
			}
			var geometry = LiquidPolyhedron.restored(faces, planes);
			var moment = geometry.moments();
			if (!(moment.volume() > 0)) throw new IllegalArgumentException("Liquid geometry has no volume");
			var centroid = new LiquidPolyhedron.Point(moment.centroid(0), moment.centroid(1), moment.centroid(2));
			for (var surface : geometry.surfaces()) if (!(surface.areaVector().dot(surface.centroid().subtract(centroid)) > 0))
				throw new IllegalArgumentException("Liquid geometry faces have inconsistent orientation");
			for (int f = 0; f < faces.size(); f++) {
				var face = faces.get(f); var plane = planes.get(f); var surface = LiquidPolyhedron.surface(face);
				if (!(plane.normal().dot(surface.areaVector()) > 0)) throw new IllegalArgumentException("Liquid supporting plane has reversed orientation");
				for (var point : vertices) if (plane.signedDistance(point) > plane.evaluationError(point))
					throw new IllegalArgumentException("Liquid geometry is non-convex");
				for (var point : face) if (Math.abs(plane.signedDistance(point)) > plane.evaluationError(point))
					throw new IllegalArgumentException("Liquid geometry face is non-planar");
			}
			result.add(new LiquidGeometryRefinement.Cell(cell.level(), cell.x(), cell.y(), cell.z(), geometry));
		}
		var leaves = new java.util.LinkedHashMap<LiquidPhasePartition.Address, LiquidGeometryRefinement.Cell>();
		for (var cell : result) leaves.putIfAbsent(LiquidPhasePartition.Address.of(cell), cell);
		new LiquidGeometryRemap(new ArrayList<>(leaves.values()));
		LiquidPhasePartition.requireDisjoint(result);
		return List.copyOf(result);
	}

	static void requireCompatible(LiquidGeometryState state, FluidDomain domain) {
		var cells = restore(state); int[] owners = state.copyMarkerCells();
		if (owners.length != domain.particles.count()) throw new IllegalArgumentException("Liquid geometry quadrature count differs from inventory");
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
			var point = new LiquidPolyhedron.Point(domain.particles.positionX(p) / width,
					domain.particles.positionY(p) / width, domain.particles.positionZ(p) / width);
			double positionError = Math.max(Math.ulp(domain.particles.positionX(p)), Math.max(Math.ulp(domain.particles.positionY(p)),
					Math.ulp(domain.particles.positionZ(p)))) / width;
			positionErrors[c] = Math.max(positionErrors[c], positionError);
			for (int a = 0; a < 3; a++) {
				double position = a == 0 ? domain.particles.positionX(p) : a == 1 ? domain.particles.positionY(p) : domain.particles.positionZ(p);
				moments[3 * c + a] = moments[3 * c + a].add(cc.ecisr.mmm.units.SignedDyadic.of(position * domain.particles.volume(p)));
			}
			for (var face : cells.get(c).geometry().faces()) {
				var surface = LiquidPolyhedron.surface(face);
				double error = surface.area() * (3 * positionError + 32 * Math.ulp(1.0));
				if (surface.areaVector().dot(point.subtract(face.getFirst())) > error)
					throw new IllegalArgumentException("Liquid quadrature leaves its interface geometry");
			}
		}
		for (int c = 0; c < cells.size(); c++) {
			var geometry = cells.get(c).geometry(); double coordinateError = 0, area = 0; int vertices = 0;
			for (var face : geometry.faces()) {
				area += LiquidPolyhedron.surface(face).area(); vertices += face.size();
				for (var point : face) for (int a = 0; a < 3; a++) {
					double limit = (a == 0 ? domain.sizeX : a == 1 ? domain.sizeY : domain.sizeZ) / (double) span;
					if (point.coordinate(a) > limit) throw new IllegalArgumentException("Liquid geometry leaves the physical domain");
					coordinateError = Math.max(coordinateError, Math.ulp(point.coordinate(a)));
				}
			}
			double expected = volumes[c].toDouble() / ((double) span * span * span), actual = geometry.moments().volume();
			double error = 64 * vertices * Math.ulp(expected) + 3 * area * coordinateError;
			if (Math.abs(actual - expected) > error)
				throw new IllegalArgumentException("Liquid interface volume differs from exact inventory");
			for (int a = 0; a < 3; a++) {
				double firstMoment = moments[3 * c + a].toDouble() / width / ((double) span * span * span);
				double geometricMoment = actual * geometry.moments().centroid(a);
				if (Math.abs(firstMoment - geometricMoment) > error + expected * positionErrors[c] + 64 * vertices * Math.ulp(firstMoment))
					throw new IllegalArgumentException("Liquid interface centroid differs from inventory quadrature");
			}
		}
	}
}

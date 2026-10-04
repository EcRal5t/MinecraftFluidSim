package cc.ecisr.mmm.fluid;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Preserves the complete interface geometry across private backend candidates. */
final class LiquidGeometryStateMapper {
	private LiquidGeometryStateMapper() { }

	static LiquidGeometryState capture(List<LiquidGeometryRefinement.Cell> cells, double rootWidth, int[] markerCells) {
		var order = new ArrayList<Integer>(); for (int c = 0; c < cells.size(); c++) order.add(c);
		order.sort(java.util.Comparator.<Integer>comparingInt(c -> cells.get(c).level()).thenComparingInt(c -> cells.get(c).x())
				.thenComparingInt(c -> cells.get(c).y()).thenComparingInt(c -> cells.get(c).z()));
		int[] canonical = new int[cells.size()]; for (int c = 0; c < cells.size(); c++) canonical[order.get(c)] = c;
		var result = new ArrayList<LiquidGeometryState.ControlVolume>();
		for (int ordinal : order) {
			var cell = cells.get(ordinal);
			var vertices = new LinkedHashMap<LiquidPolyhedron.Point, Integer>();
			var faces = cell.geometry().faces();
			int[] offsets = new int[faces.size() + 1];
			for (int f = 0; f < faces.size(); f++) {
				offsets[f + 1] = Math.addExact(offsets[f], faces.get(f).size());
				for (var point : faces.get(f)) vertices.computeIfAbsent(point, ignored -> vertices.size());
			}
			double[] xyz = new double[3 * vertices.size()];
			vertices.forEach((point, index) -> { xyz[3 * index] = point.x(); xyz[3 * index + 1] = point.y(); xyz[3 * index + 2] = point.z(); });
			int[] indices = new int[offsets[offsets.length - 1]];
			for (int f = 0; f < faces.size(); f++) for (int v = 0; v < faces.get(f).size(); v++) indices[offsets[f] + v] = vertices.get(faces.get(f).get(v));
			result.add(new LiquidGeometryState.ControlVolume(cell.level(), cell.x(), cell.y(), cell.z(), xyz, offsets, indices));
		}
		int[] owners = markerCells.clone();
		for (int p = 0; p < owners.length; p++) {
			if (owners[p] < 0 || owners[p] >= cells.size()) throw new IllegalArgumentException("Liquid quadrature has no geometry");
			owners[p] = canonical[owners[p]];
		}
		return new LiquidGeometryState(rootWidth, result, owners);
	}

	static List<LiquidGeometryRefinement.Cell> restore(LiquidGeometryState state) {
		var result = new ArrayList<LiquidGeometryRefinement.Cell>();
		for (var cell : state.cells()) {
			double[] xyz = cell.copyVertices(); int[] offsets = cell.copyFaceOffsets(), indices = cell.copyFaceVertices();
			var vertices = new ArrayList<LiquidPolyhedron.Point>();
			for (int v = 0; v < xyz.length; v += 3) vertices.add(new LiquidPolyhedron.Point(xyz[v], xyz[v + 1], xyz[v + 2]));
			if (new java.util.HashSet<>(vertices).size() != vertices.size()) throw new IllegalArgumentException("Liquid geometry repeats a vertex");
			var faces = new ArrayList<List<LiquidPolyhedron.Point>>();
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
			}
			for (var edge : edges.entrySet()) {
				long a = edge.getKey() >>> 32, b = edge.getKey() & 0xffffffffL;
				if (edge.getValue() != 1 || edges.getOrDefault(b << 32 | a, 0) != 1)
					throw new IllegalArgumentException("Liquid geometry surface is open or non-manifold");
			}
			var geometry = LiquidPolyhedron.restored(faces);
			var moment = geometry.moments();
			if (!(moment.volume() > 0)) throw new IllegalArgumentException("Liquid geometry has no volume");
			var centroid = new LiquidPolyhedron.Point(moment.centroid(0), moment.centroid(1), moment.centroid(2));
			for (var surface : geometry.surfaces()) if (!(surface.areaVector().dot(surface.centroid().subtract(centroid)) > 0))
				throw new IllegalArgumentException("Liquid geometry faces have inconsistent orientation");
			for (var face : faces) {
				var surface = LiquidPolyhedron.surface(face); var origin = face.getFirst();
				double coordinateError = 0;
				for (var point : vertices) for (int a = 0; a < 3; a++) coordinateError = Math.max(coordinateError, Math.ulp(point.coordinate(a)));
				double planeError = 32 * surface.area() * coordinateError;
				for (var point : vertices) if (surface.areaVector().dot(point.subtract(origin)) > planeError)
					throw new IllegalArgumentException("Liquid geometry is non-convex");
				for (var point : face) if (Math.abs(surface.areaVector().dot(point.subtract(origin))) > planeError)
					throw new IllegalArgumentException("Liquid geometry face is non-planar");
			}
			result.add(new LiquidGeometryRefinement.Cell(cell.level(), cell.x(), cell.y(), cell.z(), geometry));
		}
		new LiquidGeometryRemap(result); // Validate the non-overlapping dyadic partition.
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

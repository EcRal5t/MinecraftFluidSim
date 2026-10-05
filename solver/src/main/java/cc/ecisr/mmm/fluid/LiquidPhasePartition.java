package cc.ecisr.mmm.fluid;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Convex liquid fragments and the complete gas space left inside each dyadic leaf. */
final class LiquidPhasePartition {
	record Address(int level, int x, int y, int z) {
		static Address of(LiquidGeometryRefinement.Cell cell) { return new Address(cell.level(), cell.x(), cell.y(), cell.z()); }
		LiquidGeometryRefinement.Cell cell(LiquidPolyhedron geometry) { return new LiquidGeometryRefinement.Cell(level, x, y, z, geometry); }
	}
	record Region(LiquidGeometryRefinement.Cell cell, int liquid) { }
	private record Support(LiquidConvexRegion region, List<LiquidExactPolygon.Vertex> vertices,
			List<List<java.math.BigInteger>> planes, double[] minimum, double[] maximum) {
		static Support of(LiquidPolyhedron geometry) {
			var region = LiquidConvexRegion.of(geometry); var vertices = new java.util.LinkedHashSet<LiquidExactPolygon.Vertex>();
			for (var face : region.faces()) vertices.addAll(face.vertices());
			var minimum = new double[]{Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
			var maximum = new double[]{Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
			for (var vertex : vertices) {
				var point = vertex.rounded(); for (int a = 0; a < 3; a++) {
					minimum[a] = Math.min(minimum[a], Math.nextDown(point.coordinate(a))); maximum[a] = Math.max(maximum[a], Math.nextUp(point.coordinate(a)));
				}
			}
			return new Support(region, List.copyOf(vertices), region.faces().stream().map(LiquidExactPolygon::coefficients).distinct().toList(), minimum, maximum);
		}
		boolean separatedBox(Support other) {
			for (int a = 0; a < 3; a++) if (maximum[a] <= other.minimum[a] || other.maximum[a] <= minimum[a]) return true;
			return false;
		}
	}
	private LiquidPhasePartition() { }
	/** Containment, disjointness and exact volume prove that submitted phases cover the complete open leaf. */
	static void requireComplete(LiquidGeometryRefinement.Cell leaf, List<LiquidGeometryRefinement.Cell> phases) {
		var planes = LiquidExactPolygon.faces(leaf.geometry()).stream().map(LiquidExactPolygon::coefficients).toList();
		var measure = LiquidConvexRegion.EMPTY.measure();
		for (var phase : phases) {
			if (!Address.of(phase).equals(Address.of(leaf))) throw new IllegalArgumentException("Phase geometry has a different dyadic leaf");
			var geometry = LiquidConvexRegion.of(phase.geometry());
			if (!geometry.hasVolume() || planes.stream().anyMatch(p -> geometry.faces().stream().anyMatch(face -> !face.inside(p))))
				throw new IllegalArgumentException("Phase geometry leaves its open leaf");
			measure = measure.add(geometry.measure());
		}
		requireDisjoint(phases);
		if (!measure.same(LiquidConvexRegion.of(leaf.geometry()).measure())) throw new IllegalArgumentException("Submitted phases do not cover complete open geometry");
	}
	static int[] gasComponents(LiquidControlVolumeMesh mesh, int[] liquid) {
		if (liquid.length != mesh.cells.size()) throw new IllegalArgumentException("Incomplete gas phase labels");
		int[] roots = java.util.stream.IntStream.range(0, liquid.length).toArray();
		for (var face : mesh.faces) if (face.second() >= 0 && liquid[face.first()] < 0 && liquid[face.second()] < 0) {
			int a = root(roots, face.first()), b = root(roots, face.second()); roots[Math.max(a, b)] = Math.min(a, b);
		}
		for (int c = 0; c < roots.length; c++) roots[c] = root(roots, c); return roots;
	}
	private static int root(int[] roots, int cell) {
		while (roots[cell] != cell) { roots[cell] = roots[roots[cell]]; cell = roots[cell]; } return cell;
	}

	static List<Region> build(LiquidGeometryRefinement.Cell leaf, List<LiquidPolyhedron> liquids, int maximumRegions) {
		if (maximumRegions < 1) throw new IllegalArgumentException("Empty phase geometry budget");
		if (liquids.size() > maximumRegions) throw new IllegalStateException("Phase geometry exceeds its region budget");
		var address = Address.of(leaf); double w = leaf.width(); var open = LiquidConvexRegion.of(leaf.geometry());
		if (!open.hasVolume()) throw new IllegalArgumentException("Empty open phase geometry");
		for (var face : leaf.geometry().faces()) for (var point : face) for (int a = 0; a < 3; a++)
			if (point.coordinate(a) < leaf.minimum(a) || point.coordinate(a) > leaf.minimum(a) + w) throw new IllegalArgumentException("Open phase geometry leaves its dyadic leaf");
		var cells = new ArrayList<LiquidGeometryRefinement.Cell>();
		for (var liquid : liquids) {
			if (liquid.measure().signum() <= 0) throw new IllegalArgumentException("Empty phase geometry");
			for (var face : liquid.faces()) for (var point : face) for (int a = 0; a < 3; a++)
				if (point.coordinate(a) < leaf.minimum(a) || point.coordinate(a) > leaf.minimum(a) + w)
					throw new IllegalArgumentException("Liquid fragment leaves its dyadic leaf");
			var contained = LiquidConvexRegion.of(liquid);
			for (var plane : leaf.geometry().planes()) contained = contained.clip(plane);
			if (!contained.measure().same(LiquidConvexRegion.of(liquid).measure())) throw new IllegalArgumentException("Liquid fragment leaves its open phase geometry");
			cells.add(address.cell(liquid));
		}
		requireDisjoint(cells);
		var gas = List.of(Support.of(leaf.geometry()));
		for (var liquid : liquids) {
			var cut = Support.of(liquid); var planes = cut.planes.stream().map(LiquidGeometryPlane::exact).toList();
			var remaining = new ArrayList<Support>();
			for (var original : gas) {
				if (original.separatedBox(cut) || separated(original, cut) || separated(cut, original)) { remaining.add(original); continue; }
				var inside = original.region.geometry();
				for (var plane : planes) {
					if (inside.measure().signum() <= 0) break;
					var outside = plane.opposite().clip(inside);
					if (outside.measure().signum() > 0) remaining.add(Support.of(outside));
					if ((long) remaining.size() + liquids.size() > maximumRegions)
						throw new IllegalStateException("Phase geometry exceeds its region budget");
					inside = plane.clip(inside);
				}
			}
			gas = List.copyOf(remaining);
		}
		var regions = new ArrayList<Region>();
		for (int l = 0; l < cells.size(); l++) regions.add(new Region(cells.get(l), l));
		for (var geometry : gas) regions.add(new Region(address.cell(geometry.region.geometry()), -1));
		if (regions.size() > maximumRegions) throw new IllegalStateException("Phase geometry exceeds its region budget");
		var measure = LiquidConvexRegion.EMPTY.measure();
		for (var region : regions) measure = measure.add(LiquidConvexRegion.of(region.cell.geometry()).measure());
		if (!measure.same(open.measure())) throw new IllegalStateException("Phase geometry exceeds its spatial representation resolution");
		return List.copyOf(regions);
	}

	static void requireDisjoint(List<LiquidGeometryRefinement.Cell> cells) {
		var groups = new LinkedHashMap<Address, ArrayList<Support>>();
		for (var cell : cells) groups.computeIfAbsent(Address.of(cell), ignored -> new ArrayList<>()).add(Support.of(cell.geometry()));
		for (var group : groups.values()) for (int a = 0; a < group.size(); a++) for (int b = 0; b < a; b++) {
			var first = group.get(a); var second = group.get(b);
			if (first.separatedBox(second) || separated(first, second) || separated(second, first)) continue;
			var intersection = first.region;
			for (var face : second.region.faces()) intersection = intersection.clip(face.plane());
			if (intersection.hasVolume()) throw new IllegalArgumentException("Liquid fragments have overlapping exact volume: fragments=" + b + "," + a);
		}
	}
	private static boolean separated(Support first, Support second) {
		for (var plane : second.planes) {
			boolean outside = true;
			for (var vertex : first.vertices) if (vertex.distance(plane).signum() < 0) { outside = false; break; }
			if (outside) return true;
		}
		return false;
	}
}

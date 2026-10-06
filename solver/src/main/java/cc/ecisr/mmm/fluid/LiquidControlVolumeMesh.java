package cc.ecisr.mmm.fluid;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Builds each shared polygon once, including a coarse face meeting several refined faces. */
final class LiquidControlVolumeMesh {
	record Boundary(boolean solid, double prescribed) { }
	interface BoundaryCondition { Boundary at(int cell, LiquidPolyhedron.Surface surface); }
	private record Tile(int axis, long plane, int level, int u, int v) {
		Tile ancestor(int level) { int shift = this.level - level; return new Tile(axis, plane, level, u >>> shift, v >>> shift); }
	}
	private record Interior(LiquidPhasePartition.Address address, LiquidGeometryPlane.Key plane) { }
	private static final class Patch {
		final int cell;
		final List<LiquidPolyhedron.Point> polygon;
		final LiquidPolyhedron.Surface surface;
		final Tile tile;
		final LiquidExactPolygon exact;
		List<LiquidExactPolygon> uncovered;
		Patch(int cell, LiquidExactPolygon exact, List<LiquidPolyhedron.Point> polygon, LiquidPolyhedron.Surface surface, Tile tile) {
			this.cell = cell; this.polygon = polygon; this.surface = surface; this.tile = tile;
			this.exact = exact; uncovered = List.of(exact);
		}
		void covered(LiquidExactPolygon cutter) {
			var remaining = new ArrayList<LiquidExactPolygon>();
			for (var original : uncovered) remaining.addAll(original.subtract(cutter));
			uncovered = remaining;
		}
	}
	final List<LiquidControlVolumeProjection.Cell> cells;
	final List<LiquidControlVolumeProjection.Face> faces;
	final List<List<LiquidPolyhedron.Point>> polygons;
	final List<LiquidExactPolygon> exactPolygons;
	final int[][] cellFaces;

	LiquidControlVolumeMesh(List<LiquidGeometryRefinement.Cell> leaves, double physicalWidth, double density, BoundaryCondition boundary) {
		var cells = new ArrayList<LiquidControlVolumeProjection.Cell>();
		var patches = new ArrayList<Patch>();
		Map<Tile, List<Patch>> positive = new HashMap<>(), negative = new HashMap<>();
		Map<Interior, List<Patch>> interiorPositive = new java.util.LinkedHashMap<>(), interiorNegative = new java.util.LinkedHashMap<>();
		for (int c = 0; c < leaves.size(); c++) {
			var leaf = leaves.get(c);
			var geometry = leaf.geometry().scaled(physicalWidth);
			var exactFaces = LiquidExactPolygon.faces(geometry);
			geometry.centroid();
			cells.add(new LiquidControlVolumeProjection.Cell(geometry, density));
			for (int f = 0; f < geometry.faces().size(); f++) {
				var exact = exactFaces.get(f); if (!exact.hasArea()) continue;
				var polygon = exact.rounded();
				var surface = exact.surface();
				if (!(surface.area() > 0)) throw new IllegalStateException("Shared face exceeds binary64 spatial resolution");
				Tile tile = tile(leaf, exact, physicalWidth);
				var patch = new Patch(c, exact, polygon, surface, tile); patches.add(patch);
				if (tile != null) {
					boolean plus = surface.areaVector().coordinate(tile.axis) > 0;
					(plus ? positive : negative).computeIfAbsent(tile, ignored -> new ArrayList<>()).add(patch);
				} else {
					var plane = leaf.geometry().planes().get(f);
					var key = new Interior(LiquidPhasePartition.Address.of(leaf), plane.key());
					(plane.positive() ? interiorPositive : interiorNegative).computeIfAbsent(key, ignored -> new ArrayList<>()).add(patch);
				}
			}
		}
		var faces = new ArrayList<LiquidControlVolumeProjection.Face>();
		var polygons = new ArrayList<LiquidExactPolygon>();
		for (var entry : interiorPositive.entrySet()) {
			var opposite = interiorNegative.get(entry.getKey());
			if (opposite != null) for (var first : entry.getValue()) for (var second : opposite) connect(first, second, faces, polygons);
		}
		for (Patch patch : patches) if (patch.tile != null) {
			boolean plus = patch.surface.areaVector().coordinate(patch.tile.axis) > 0;
			Map<Tile, List<Patch>> opposite = plus ? negative : positive;
			// Equal-sized pairs are emitted by their positive side; unequal pairs by their finer side.
			for (int level = plus ? patch.tile.level : patch.tile.level - 1; level >= 0; level--) {
				var neighbors = opposite.get(patch.tile.ancestor(level));
				if (neighbors == null) continue;
				for (Patch other : neighbors) connect(plus ? patch : other, plus ? other : patch, faces, polygons);
			}
		}
		for (Patch patch : patches) for (var exact : patch.uncovered) {
			var polygon = exact.rounded();
			var residual = exact.surface();
			if (!(residual.area() > 0)) throw new IllegalStateException("Shared face exceeds binary64 spatial resolution");
			Boundary condition = boundary.at(patch.cell, residual);
			faces.add(new LiquidControlVolumeProjection.Face(patch.cell, condition.solid ? -2 : -1, residual, condition.prescribed, exact));
			polygons.add(exact);
		}
		var combined = LiquidCoplanarFaceUnion.build(faces, polygons);
		faces = new ArrayList<>(combined.faces()); polygons = new ArrayList<>(combined.polygons());
		this.cells = List.copyOf(cells); this.faces = List.copyOf(faces);
		this.exactPolygons = LiquidExactPolygon.splitEdges(polygons); this.polygons = exactPolygons.stream().map(LiquidExactPolygon::rounded).toList();
		var incidence = new ArrayList<ArrayList<Integer>>(); for (int c = 0; c < cells.size(); c++) incidence.add(new ArrayList<>());
		for (int f = 0; f < faces.size(); f++) {
			var face = faces.get(f); incidence.get(face.first()).add(f); if (face.second() >= 0) incidence.get(face.second()).add(f);
		}
		cellFaces = new int[cells.size()][]; for (int c = 0; c < cells.size(); c++) cellFaces[c] = incidence.get(c).stream().mapToInt(Integer::intValue).toArray();
	}

	private static Tile tile(LiquidGeometryRefinement.Cell leaf, LiquidExactPolygon polygon, double scale) {
		var coefficients = polygon.coefficients();
		for (int axis = 0; axis < 3; axis++) {
			if (coefficients.get(axis).signum() == 0 || coefficients.get((axis + 1) % 3).signum() != 0 || coefficients.get((axis + 2) % 3).signum() != 0) continue;
			boolean plus = coefficients.get(axis).signum() > 0;
			double coordinate = scale * (leaf.minimum(axis) + (plus ? leaf.width() : 0));
			var plane = new LiquidGeometryPlane(new LiquidPolyhedron.Point(axis == 0 ? 1 : 0, axis == 1 ? 1 : 0, axis == 2 ? 1 : 0), coordinate);
			if (!plane.key().equals(polygon.plane().key())) continue;
			int[] xyz = {leaf.x(), leaf.y(), leaf.z()};
			return new Tile(axis, (xyz[axis] + (plus ? 1L : 0L)) << (30 - leaf.level()), leaf.level(), xyz[(axis + 1) % 3], xyz[(axis + 2) % 3]);
		}
		return null;
	}

	private static void connect(Patch first, Patch second, ArrayList<LiquidControlVolumeProjection.Face> faces,
			ArrayList<LiquidExactPolygon> polygons) {
		var exact = first.exact.intersection(second.exact);
		if (!exact.hasArea()) return;
		var polygon = exact.rounded();
		var surface = exact.surface();
		if (!(surface.area() > 0)) throw new IllegalStateException("Shared face exceeds binary64 spatial resolution: cells=" + first.cell + "," + second.cell + ", vertices=" + polygon);
		first.covered(exact); second.covered(exact);
		faces.add(new LiquidControlVolumeProjection.Face(first.cell, second.cell, surface, 0, exact));
		polygons.add(exact);
	}

}

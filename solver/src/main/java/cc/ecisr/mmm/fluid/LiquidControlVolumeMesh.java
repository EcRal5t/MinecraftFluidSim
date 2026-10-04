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
	private static final class Patch {
		final int cell;
		final List<LiquidPolyhedron.Point> polygon;
		final LiquidPolyhedron.Surface surface;
		final Tile tile;
		List<List<LiquidPolyhedron.Point>> uncovered;
		Patch(int cell, List<LiquidPolyhedron.Point> polygon, LiquidPolyhedron.Surface surface, Tile tile) {
			this.cell = cell; this.polygon = polygon; this.surface = surface; this.tile = tile;
			uncovered = List.of(polygon);
		}
		void covered(List<LiquidPolyhedron.Point> cutter, LiquidPolyhedron.Point normal) {
			var remaining = new ArrayList<List<LiquidPolyhedron.Point>>();
			for (var original : uncovered) {
				var inside = original;
				for (int edge = 0; edge < cutter.size() && !inside.isEmpty(); edge++) {
					var a = cutter.get(edge); var b = cutter.get((edge + 1) % cutter.size());
					var outward = b.subtract(a).cross(normal);
					var outside = clip(inside, a, new LiquidPolyhedron.Point(-outward.x(), -outward.y(), -outward.z()));
					if (outside.size() >= 3 && LiquidPolyhedron.surface(outside).area() > 0) remaining.add(outside);
					inside = clip(inside, a, outward);
				}
			}
			uncovered = remaining;
		}
	}
	final List<LiquidControlVolumeProjection.Cell> cells;
	final List<LiquidControlVolumeProjection.Face> faces;

	LiquidControlVolumeMesh(List<LiquidGeometryRefinement.Cell> leaves, double physicalWidth, double density, BoundaryCondition boundary) {
		var cells = new ArrayList<LiquidControlVolumeProjection.Cell>();
		var patches = new ArrayList<Patch>();
		Map<Tile, List<Patch>> positive = new HashMap<>(), negative = new HashMap<>();
		for (int c = 0; c < leaves.size(); c++) {
			var leaf = leaves.get(c);
			var geometry = leaf.geometry().scaled(physicalWidth);
			cells.add(new LiquidControlVolumeProjection.Cell(geometry, density));
			for (var polygon : geometry.faces()) {
				var surface = LiquidPolyhedron.surface(polygon);
				if (surface.area() == 0) continue;
				Tile tile = tile(leaf, polygon, surface, physicalWidth);
				var patch = new Patch(c, polygon, surface, tile); patches.add(patch);
				if (tile != null) {
					boolean plus = surface.areaVector().coordinate(tile.axis) > 0;
					(plus ? positive : negative).computeIfAbsent(tile, ignored -> new ArrayList<>()).add(patch);
				}
			}
		}
		var faces = new ArrayList<LiquidControlVolumeProjection.Face>();
		for (Patch patch : patches) if (patch.tile != null) {
			boolean plus = patch.surface.areaVector().coordinate(patch.tile.axis) > 0;
			Map<Tile, List<Patch>> opposite = plus ? negative : positive;
			// Equal-sized pairs are emitted by their positive side; unequal pairs by their finer side.
			for (int level = plus ? patch.tile.level : patch.tile.level - 1; level >= 0; level--) {
				var neighbors = opposite.get(patch.tile.ancestor(level));
				if (neighbors == null) continue;
				for (Patch other : neighbors) connect(plus ? patch : other, plus ? other : patch, faces);
			}
		}
		for (Patch patch : patches) for (var polygon : patch.uncovered) {
			var residual = LiquidPolyhedron.surface(polygon);
			if (residual.area() == 0) continue;
			Boundary condition = boundary.at(patch.cell, residual);
			faces.add(new LiquidControlVolumeProjection.Face(patch.cell, condition.solid ? -2 : -1, residual, condition.prescribed));
		}
		this.cells = List.copyOf(cells); this.faces = List.copyOf(faces);
	}

	private static Tile tile(LiquidGeometryRefinement.Cell leaf, List<LiquidPolyhedron.Point> polygon,
			LiquidPolyhedron.Surface surface, double scale) {
		for (int axis = 0; axis < 3; axis++) {
			if (surface.areaVector().coordinate((axis + 1) % 3) != 0 || surface.areaVector().coordinate((axis + 2) % 3) != 0) continue;
			boolean plus = surface.areaVector().coordinate(axis) > 0;
			double coordinate = scale * (leaf.minimum(axis) + (plus ? leaf.width() : 0));
			boolean coincides = true; for (var point : polygon) coincides &= point.coordinate(axis) == coordinate;
			if (!coincides) continue;
			int[] xyz = {leaf.x(), leaf.y(), leaf.z()};
			return new Tile(axis, (xyz[axis] + (plus ? 1L : 0L)) << (30 - leaf.level()), leaf.level(), xyz[(axis + 1) % 3], xyz[(axis + 2) % 3]);
		}
		return null;
	}

	private static void connect(Patch first, Patch second, ArrayList<LiquidControlVolumeProjection.Face> faces) {
		var polygon = intersection(first.polygon, second.polygon, second.surface.areaVector());
		if (polygon.size() < 3) return;
		var surface = LiquidPolyhedron.surface(polygon);
		if (surface.area() == 0) return;
		first.covered(polygon, surface.areaVector()); second.covered(polygon, surface.areaVector());
		faces.add(new LiquidControlVolumeProjection.Face(first.cell, second.cell, surface, 0));
	}

	private static List<LiquidPolyhedron.Point> intersection(List<LiquidPolyhedron.Point> first,
			List<LiquidPolyhedron.Point> second, LiquidPolyhedron.Point normal) {
		List<LiquidPolyhedron.Point> polygon = first;
		for (int edge = 0; edge < second.size(); edge++) {
			if (polygon.isEmpty()) return polygon;
			var a = second.get(edge); var b = second.get((edge + 1) % second.size());
			var outward = b.subtract(a).cross(normal);
			polygon = clip(polygon, a, outward);
		}
		return polygon;
	}

	private static List<LiquidPolyhedron.Point> clip(List<LiquidPolyhedron.Point> polygon,
			LiquidPolyhedron.Point a, LiquidPolyhedron.Point outward) {
		if (polygon.isEmpty()) return polygon;
		var clipped = new ArrayList<LiquidPolyhedron.Point>();
		var previous = polygon.getLast(); double before = outward.dot(previous.subtract(a));
		for (var current : polygon) {
				double after = outward.dot(current.subtract(a));
				if (before < 0 && after > 0 || before > 0 && after < 0) {
					double t = before / (before - after);
					clipped.add(new LiquidPolyhedron.Point(Math.fma(t, current.x() - previous.x(), previous.x()),
							Math.fma(t, current.y() - previous.y(), previous.y()), Math.fma(t, current.z() - previous.z(), previous.z())));
				}
				if (after <= 0) clipped.add(current);
				previous = current; before = after;
		}
		return clipped;
	}
}

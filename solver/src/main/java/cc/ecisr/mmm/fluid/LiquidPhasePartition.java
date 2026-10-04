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
	private LiquidPhasePartition() { }

	static List<Region> build(LiquidGeometryRefinement.Cell leaf, List<LiquidPolyhedron> liquids, int maximumRegions) {
		if (maximumRegions < 1) throw new IllegalArgumentException("Empty phase geometry budget");
		if (liquids.size() > maximumRegions) throw new IllegalStateException("Phase geometry exceeds its region budget");
		var address = Address.of(leaf); double w = leaf.width();
		var box = LiquidPolyhedron.box(leaf.minimum(0), leaf.minimum(1), leaf.minimum(2),
				leaf.minimum(0) + w, leaf.minimum(1) + w, leaf.minimum(2) + w);
		var cells = new ArrayList<LiquidGeometryRefinement.Cell>();
		for (var liquid : liquids) {
			if (!(liquid.moments().volume() > 0)) throw new IllegalArgumentException("Empty phase geometry");
			for (var face : liquid.faces()) for (var point : face) for (int a = 0; a < 3; a++)
				if (point.coordinate(a) < leaf.minimum(a) || point.coordinate(a) > leaf.minimum(a) + w)
					throw new IllegalArgumentException("Liquid fragment leaves its dyadic leaf");
			cells.add(address.cell(liquid));
		}
		requireDisjoint(cells);
		var gas = List.of(box);
		for (var liquid : liquids) {
			var remaining = new ArrayList<LiquidPolyhedron>();
			for (var original : gas) {
				var inside = original;
				for (var plane : liquid.planes()) {
					if (!(inside.moments().volume() > 0)) break;
					var outside = plane.opposite().clip(inside);
					if (outside.moments().volume() > 0) remaining.add(outside);
					if ((long) remaining.size() + liquids.size() > maximumRegions)
						throw new IllegalStateException("Phase geometry exceeds its region budget");
					inside = plane.clip(inside);
				}
			}
			gas = List.copyOf(remaining);
		}
		var regions = new ArrayList<Region>();
		for (int l = 0; l < cells.size(); l++) regions.add(new Region(cells.get(l), l));
		for (var geometry : gas) regions.add(new Region(address.cell(geometry), -1));
		if (regions.size() > maximumRegions) throw new IllegalStateException("Phase geometry exceeds its region budget");
		return List.copyOf(regions);
	}

	static void requireDisjoint(List<LiquidGeometryRefinement.Cell> cells) {
		var groups = new LinkedHashMap<Address, ArrayList<LiquidPolyhedron>>();
		for (var cell : cells) groups.computeIfAbsent(Address.of(cell), ignored -> new ArrayList<>()).add(cell.geometry());
		for (var group : groups.values()) for (int a = 0; a < group.size(); a++) for (int b = 0; b < a; b++) {
			var intersection = group.get(a);
			for (var plane : group.get(b).planes()) intersection = plane.clip(intersection);
			double area = 0, coordinateError = 0; int operations = 0;
			for (var face : intersection.faces()) {
				area += LiquidPolyhedron.surface(face).area(); operations += face.size();
				for (var point : face) for (int axis = 0; axis < 3; axis++) coordinateError = Math.max(coordinateError, Math.ulp(point.coordinate(axis)));
			}
			double volume = intersection.moments().volume();
			double error = 64 * operations * Math.ulp(volume) + 16 * area * coordinateError;
			if (volume > error) throw new IllegalArgumentException("Liquid fragments have overlapping volume: fragments=" + b + "," + a
					+ ", overlap=" + volume + ", roundoff=" + error);
		}
	}
}

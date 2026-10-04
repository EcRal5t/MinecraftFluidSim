package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.units.SignedDyadic;
import java.util.ArrayList;
import java.util.List;

/** Conservative intersection of a moved liquid geometry with a dyadic control-volume partition. */
final class LiquidGeometryRemap {
	record Fragment(int cell, LiquidPolyhedron geometry, SignedDyadic inventory) { }
	private static final class Node {
		int cell = -1;
		Node[] children;
	}
	private final Node root = new Node();

	LiquidGeometryRemap(List<LiquidGeometryRefinement.Cell> partition) {
		for (int c = 0; c < partition.size(); c++) {
			var tile = partition.get(c); Node node = root;
			for (int level = 1; level <= tile.level(); level++) {
				if (node.cell >= 0) throw new IllegalArgumentException("Liquid remap cells overlap");
				if (node.children == null) node.children = new Node[8];
				int shift = tile.level() - level;
				int slot = (tile.x() >>> shift & 1) | (tile.y() >>> shift & 1) << 1 | (tile.z() >>> shift & 1) << 2;
				if (node.children[slot] == null) node.children[slot] = new Node();
				node = node.children[slot];
			}
			if (node.cell >= 0 || node.children != null) throw new IllegalArgumentException("Liquid remap cells overlap");
			node.cell = c;
		}
	}

	List<Fragment> translate(LiquidPolyhedron source, SignedDyadic inventory, double dx, double dy, double dz) {
		if (inventory.signum() <= 0 || !(source.moments().volume() > 0)) throw new IllegalArgumentException("Empty liquid remap source");
		var moved = source.translated(dx, dy, dz);
		for (var face : moved.faces()) for (var point : face)
			for (int axis = 0; axis < 3; axis++) if (point.coordinate(axis) < 0 || point.coordinate(axis) > 1)
				throw new IllegalStateException("Liquid motion leaves the control-volume partition");
		var fragments = new ArrayList<Fragment>();
		intersect(root, new LiquidGeometryRefinement.Cell(0, 0, 0, 0, moved), fragments);
		var weights = new SignedDyadic[fragments.size()];
		for (int i = 0; i < weights.length; i++) weights[i] = SignedDyadic.of(fragments.get(i).geometry.moments().volume());
		var allocated = ExactVolumeAllocation.allocate(inventory, weights);
		var result = new ArrayList<Fragment>(fragments.size());
		for (int i = 0; i < fragments.size(); i++) {
			var fragment = fragments.get(i);
			if (allocated[i].signum() > 0) result.add(new Fragment(fragment.cell, fragment.geometry, allocated[i]));
		}
		return List.copyOf(result);
	}

	private static void intersect(Node node, LiquidGeometryRefinement.Cell occupied, ArrayList<Fragment> fragments) {
		if (node == null) throw new IllegalStateException("Liquid enters an uncovered control volume");
		if (node.cell >= 0) { fragments.add(new Fragment(node.cell, occupied.geometry(), SignedDyadic.ZERO)); return; }
		if (node.children == null) throw new IllegalStateException("Liquid enters an uncovered control volume");
		for (var child : LiquidGeometryRefinement.children(occupied)) {
			int slot = (child.x() & 1) | (child.y() & 1) << 1 | (child.z() & 1) << 2;
			intersect(node.children[slot], child, fragments);
		}
	}
}

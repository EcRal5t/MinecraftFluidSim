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
		return partition(source.translated(dx, dy, dz), inventory);
	}
	List<Fragment> advect(LiquidPolyhedron source, SignedDyadic inventory, LiquidAffineMotion motion) {
		return partition(motion.map(source), inventory);
	}
	List<Fragment> partition(LiquidPolyhedron moved, SignedDyadic inventory) {
		if (inventory.signum() <= 0 || !(moved.moments().volume() > 0)) throw new IllegalArgumentException("Empty liquid remap source");
		for (var face : moved.faces()) for (var point : face)
			for (int axis = 0; axis < 3; axis++) if (point.coordinate(axis) < 0 || point.coordinate(axis) > 1)
				throw new IllegalStateException("Liquid motion leaves the control-volume partition");
		var fragments = new ArrayList<Fragment>();
		intersect(root, new LiquidGeometryRefinement.Cell(0, 0, 0, 0, moved), fragments);
		var weights = new SignedDyadic[fragments.size()];
		var measure = LiquidConvexRegion.EMPTY.measure();
		for (int i = 0; i < weights.length; i++) {
			var fragment = LiquidConvexRegion.of(fragments.get(i).geometry); measure = measure.add(fragment.measure());
			weights[i] = SignedDyadic.of(fragment.measure().moments().volume());
		}
		if (!measure.same(LiquidConvexRegion.of(moved).measure())) throw new IllegalStateException("Liquid remap lost exact geometric volume or first moments");
		var allocated = ExactVolumeAllocation.allocateContinuous(inventory, weights).values();
		var result = new ArrayList<Fragment>(fragments.size());
		for (int i = 0; i < fragments.size(); i++) {
			var fragment = fragments.get(i);
			if (allocated[i].signum() <= 0) throw new IllegalStateException("Liquid remap exceeds its fragment allocation resolution");
			result.add(new Fragment(fragment.cell, fragment.geometry, allocated[i]));
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

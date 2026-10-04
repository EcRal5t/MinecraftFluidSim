package cc.ecisr.mmm.fluid;

import java.util.ArrayList;
import java.util.List;

/** Sparse dyadic open space derived from collision masks and requested liquid resolution. */
final class LiquidGeometryDomainPartition {
	private static final class Node {
		boolean solid;
		Node[] children;
		void split() {
			if (solid) throw new IllegalArgumentException("Liquid resolution enters occupied solid space");
			if (children == null) { children = new Node[8]; for (int s = 0; s < 8; s++) children[s] = new Node(); }
		}
	}
	final double rootWidthMetres;
	final List<LiquidGeometryRefinement.Cell> leaves;
	private final int[] size;
	private final byte[] masks;
	private final double microWidth;

	LiquidGeometryDomainPartition(int sizeX, int sizeY, int sizeZ, float cellSize, FlipCutCellGeometry collision,
			List<LiquidGeometryRefinement.Cell> requested, int maximumLeaves) {
		this(sizeX, sizeY, sizeZ, cellSize, collision.copyMicroMasks(), requested, maximumLeaves);
	}
	LiquidGeometryDomainPartition(int sizeX, int sizeY, int sizeZ, float cellSize, byte[] collision,
			List<LiquidGeometryRefinement.Cell> requested, int maximumLeaves) {
		if (sizeX < 1 || sizeY < 1 || sizeZ < 1 || !(cellSize > 0) || !Float.isFinite(cellSize) || maximumLeaves < 1)
			throw new IllegalArgumentException("Invalid geometric domain partition");
		int extent = Math.max(sizeX, Math.max(sizeY, sizeZ)); int span = extent == 1 ? 1 : Integer.highestOneBit(extent - 1) << 1;
		if (span <= 0) throw new IllegalArgumentException("Geometric domain exceeds dyadic address range");
		int baseLevel = Integer.numberOfTrailingZeros(span); if (baseLevel >= 30) throw new IllegalArgumentException("Collision detail exceeds dyadic coordinate resolution");
		size = new int[] {sizeX, sizeY, sizeZ}; masks = collision.clone(); microWidth = 0.5 * cellSize;
		if (masks.length != (long) sizeX * sizeY * sizeZ) throw new IllegalArgumentException("Collision layout differs from geometric domain");
		rootWidthMetres = span * (double) cellSize; var tree = new Node();
		for (int z = 0; z < sizeZ; z++) for (int y = 0; y < sizeY; y++) for (int x = 0; x < sizeX; x++) {
			int mask = Byte.toUnsignedInt(masks[(z * sizeY + y) * sizeX + x]);
			if (mask == FlipCutCellGeometry.FULL_MASK) close(tree, baseLevel, x, y, z);
			else for (int s = 0; s < 8; s++) if ((mask & 1 << s) != 0)
				close(tree, baseLevel + 1, 2 * x + (s & 1), 2 * y + (s >>> 1 & 1), 2 * z + (s >>> 2 & 1));
		}
		compact(tree);
		for (var cell : requested) refine(tree, cell.level(), cell.x(), cell.y(), cell.z());
		var open = LiquidPolyhedron.box(0, 0, 0, sizeX / (double) span, sizeY / (double) span, sizeZ / (double) span);
		var result = new ArrayList<LiquidGeometryRefinement.Cell>(); collect(tree, new LiquidGeometryRefinement.Cell(0, 0, 0, 0, open), result, maximumLeaves);
		leaves = List.copyOf(result);
	}
	LiquidControlVolumeMesh.Boundary boundary(LiquidPolyhedron.Surface surface, boolean sealed) {
		int axis = -1; var n = surface.areaVector();
		for (int a = 0; a < 3; a++) if (n.coordinate(a) != 0) {
			if (axis >= 0) throw new IllegalStateException("Uncovered geometric face has no collision support"); axis = a;
		}
		if (axis < 0) throw new IllegalStateException("Empty uncovered geometric face");
		double coordinate = surface.centroid().coordinate(axis), normal = n.coordinate(axis);
		if (coordinate == 0 && normal < 0 || coordinate == 2 * size[axis] * microWidth && normal > 0)
			return new LiquidControlVolumeMesh.Boundary(sealed, 0);
		double position = coordinate / microWidth;
		if (position != Math.rint(position)) throw new IllegalStateException("Uncovered geometric face is inside open collision space");
		int[] micro = new int[3];
		for (int a = 0; a < 3; a++) micro[a] = a == axis ? (int) position + (normal > 0 ? 0 : -1) : (int) Math.floor(surface.centroid().coordinate(a) / microWidth);
		for (int a = 0; a < 3; a++) if (micro[a] < 0 || micro[a] >= 2 * size[a]) throw new IllegalStateException("Uncovered geometric face leaves physical domain");
		int cell = ((micro[2] / 2) * size[1] + micro[1] / 2) * size[0] + micro[0] / 2;
		int slot = (micro[0] & 1) | (micro[1] & 1) << 1 | (micro[2] & 1) << 2;
		if ((Byte.toUnsignedInt(masks[cell]) & 1 << slot) == 0) throw new IllegalStateException("Uncovered geometric face has no solid collision support");
		return new LiquidControlVolumeMesh.Boundary(true, 0);
	}
	private static Node at(Node root, int level, int x, int y, int z) {
		var node = root;
		for (int l = 1; l <= level; l++) {
			node.split(); int shift = level - l;
			int slot = (x >>> shift & 1) | (y >>> shift & 1) << 1 | (z >>> shift & 1) << 2; node = node.children[slot];
		}
		return node;
	}
	private static void close(Node root, int level, int x, int y, int z) {
		var node = at(root, level, x, y, z); node.solid = true; node.children = null;
	}
	private static void refine(Node root, int level, int x, int y, int z) {
		if (at(root, level, x, y, z).solid) throw new IllegalArgumentException("Liquid geometry is inside solid collision geometry");
	}
	private static void compact(Node node) {
		if (node.children == null) return; boolean solid = true;
		for (var child : node.children) { compact(child); solid &= child.solid; }
		if (solid) { node.solid = true; node.children = null; }
	}
	private static void collect(Node node, LiquidGeometryRefinement.Cell open, ArrayList<LiquidGeometryRefinement.Cell> leaves, int maximum) {
		if (node.solid) return;
		if (node.children == null) {
			if (leaves.size() == maximum) throw new IllegalStateException("Open geometric domain exceeds its leaf budget");
			leaves.add(open); return;
		}
		for (var child : LiquidGeometryRefinement.children(open)) {
			int s = (child.x() & 1) | (child.y() & 1) << 1 | (child.z() & 1) << 2; collect(node.children[s], child, leaves, maximum);
		}
	}
}

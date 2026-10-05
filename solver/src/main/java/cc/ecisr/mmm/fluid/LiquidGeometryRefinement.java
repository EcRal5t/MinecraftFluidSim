package cc.ecisr.mmm.fluid;

import java.util.ArrayList;
import java.util.List;

/** Local dyadic subdivisions of one liquid region; every leaf retains its clipped geometry. */
final class LiquidGeometryRefinement {
	record Cell(int level, int x, int y, int z, LiquidPolyhedron geometry) {
		Cell {
			if (level < 0 || level > 30 || x < 0 || y < 0 || z < 0
					|| x >= 1L << level || y >= 1L << level || z >= 1L << level || geometry == null)
				throw new IllegalArgumentException("Invalid local liquid cell");
		}
		double width() { return Math.scalb(1.0, -level); }
		double minimum(int axis) { return width() * switch (axis) { case 0 -> x; case 1 -> y; case 2 -> z; default -> throw new IllegalArgumentException("Invalid axis"); }; }
		LiquidPolyhedron.Moments moments() { return geometry.moments(); }
	}
	private LiquidGeometryRefinement() { }

	static List<Cell> children(Cell parent) {
		if (parent.level == 30) throw new IllegalStateException("Liquid geometry exceeds dyadic coordinate resolution");
		var result = new ArrayList<Cell>(8);
		double width = 0.5 * parent.width();
		for (int slot = 0; slot < 8; slot++) {
			int x = 2 * parent.x + (slot & 1), y = 2 * parent.y + (slot >>> 1 & 1), z = 2 * parent.z + (slot >>> 2 & 1);
			var faces = LiquidExactPolygon.faces(parent.geometry);
			int[] coordinate = {x, y, z};
			for (int axis = 0; axis < 3; axis++) {
				double lo = coordinate[axis] * width, hi = (coordinate[axis] + 1.0) * width;
				faces = LiquidExactPolygon.clipVolume(faces, new LiquidGeometryPlane(new LiquidPolyhedron.Point(axis == 0 ? 1 : 0, axis == 1 ? 1 : 0, axis == 2 ? 1 : 0), hi));
				faces = LiquidExactPolygon.clipVolume(faces, new LiquidGeometryPlane(new LiquidPolyhedron.Point(axis == 0 ? -1 : 0, axis == 1 ? -1 : 0, axis == 2 ? -1 : 0), -lo));
				if (faces.isEmpty()) break;
			}
			// Exact clipping proves strict positive volume; integrate only retained leaves when needed.
			if (!faces.isEmpty()) result.add(new Cell(parent.level + 1, x, y, z, LiquidPolyhedron.restoredExact(faces)));
		}
		return List.copyOf(result);
	}
}

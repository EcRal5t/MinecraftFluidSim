package cc.ecisr.mmm.fluid;

import java.util.List;

/** Actual liquid support is contained in the complete collision-open space. */
final class LiquidGeometryCollisionAudit {
	private LiquidGeometryCollisionAudit() { }
	static void requireCompatible(LiquidGeometryState state, List<LiquidGeometryRefinement.Cell> cells,
			int sizeX, int sizeY, int sizeZ, float cellSize, byte[] collisionMasks) {
		var open = new LiquidGeometryDomainPartition(sizeX, sizeY, sizeZ, cellSize, collisionMasks, cells, Integer.MAX_VALUE);
		if (open.rootWidthMetres != state.rootWidthMetres()) throw new IllegalArgumentException("Liquid geometry root differs from collision space");
		var remap = new LiquidGeometryRemap(open.leaves);
		for (var cell : cells) for (var piece : remap.partitionGeometry(cell.geometry())) {
			var measure = LiquidConvexRegion.of(piece.geometry()); var contained = measure;
			for (var plane : open.leaves.get(piece.cell()).geometry().planes()) contained = contained.clip(plane);
			if (!contained.measure().same(measure.measure())) throw new IllegalArgumentException("Liquid geometry leaves collision-open space");
		}
	}
}

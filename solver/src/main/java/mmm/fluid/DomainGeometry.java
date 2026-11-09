package mmm.fluid;

import mmm.spatial.SpatialGrid;

/** A domain's copy-on-write view of fixed collision geometry. */
final class DomainGeometry {
	final FlipCutCellGeometry cutCells;
	DomainGeometry(int sizeX,int sizeY,int sizeZ,FixedGeometrySnapshot snapshot) {
		this(SpatialGrid.rectangular(sizeX,sizeY,sizeZ),snapshot);
	}
	DomainGeometry(SpatialGrid grid,FixedGeometrySnapshot snapshot) {
		cutCells=new FlipCutCellGeometry(grid);
		if(snapshot!=null)cutCells.restoreSnapshot(snapshot);
	}
}

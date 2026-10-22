package mmm.fluid;

/** A domain's copy-on-write view of fixed collision geometry. */
final class DomainGeometry {
	final FlipCutCellGeometry cutCells;
	DomainGeometry(int sizeX,int sizeY,int sizeZ,FixedGeometrySnapshot snapshot) {
		cutCells=new FlipCutCellGeometry(sizeX,sizeY,sizeZ);
		if(snapshot!=null)cutCells.restoreSnapshot(snapshot);
	}
}

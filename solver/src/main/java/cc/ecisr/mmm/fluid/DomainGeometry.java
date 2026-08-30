package cc.ecisr.mmm.fluid;

/**
 * Authoritative collision geometry: the binary solid mask and the 2x2x2 cut-cell micro
 * geometry. Edits happen only through the collision-edit commit path and wholesale restore;
 * the arrays are shared by reference with the collision/scanner stages constructed once.
 * Cell dimensions stay on the domain as immutable identity.
 */
final class DomainGeometry {
	final boolean[] solid;
	final FlipCutCellGeometry cutCells;

	DomainGeometry(int sizeX, int sizeY, int sizeZ, int cellCount) {
		this.solid = new boolean[cellCount];
		this.cutCells = new FlipCutCellGeometry(sizeX, sizeY, sizeZ);
	}
}

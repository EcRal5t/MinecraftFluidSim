package mmm.fluid;

import java.util.Arrays;
import mmm.spatial.SpatialGrid;

/**
 * Copy-on-write editing view of immutable fixed collision geometry.
 *
 * <p>Each solver cell owns a 2x2x2 occupancy mask. Cell-open volume and the
 * shared 2x2 face apertures are numerical geometry; they never contribute to
 * the exact marker-volume ledger. Domain boundary faces are closed.</p>
 */
final class FlipCutCellGeometry {
	static final int MICRO_CELLS_PER_AXIS = 2;
	static final int FULL_MASK = 0xFF;
	private static final float MICRO_VOLUME = 1.0F / 8.0F;

	private final int sizeX;
	private final int sizeY;
	private final int sizeZ;
	private final int cellCount;
	private final SpatialGrid grid;
	private FixedGeometrySnapshot layout;
	private byte[] writableMasks;

	FlipCutCellGeometry(int sizeX,int sizeY,int sizeZ) {
		this(SpatialGrid.rectangular(sizeX,sizeY,sizeZ));
	}
	FlipCutCellGeometry(SpatialGrid grid) {
		this.grid=grid;this.sizeX=grid.sizeX();this.sizeY=grid.sizeY();this.sizeZ=grid.sizeZ();
		cellCount=grid.cellCount();
	}

	FixedGeometrySnapshot snapshot() {
		if(layout==null){
			layout=FixedGeometrySnapshot.takeMasks(grid,writableMasks==null?new byte[cellCount]:writableMasks);
			writableMasks=null;
		}
		return layout;
	}
	void restoreSnapshot(FixedGeometrySnapshot snapshot) {
		if(snapshot==null||!snapshot.grid().equals(grid))throw new IllegalArgumentException("Fixed collision geometry shape differs");
		layout=snapshot;writableMasks=null;
	}

	boolean matchesShape(int x, int y, int z) { return sizeX == x && sizeY == y && sizeZ == z; }

	int mask(int index) {
		if(writableMasks!=null)return Byte.toUnsignedInt(writableMasks[index]);
		if(layout!=null)return layout.mask(index);
		if(index<0||index>=cellCount)throw new IndexOutOfBoundsException(index);
		return 0;
	}
	boolean fullySolid(int index){return mask(index)==FULL_MASK;}
	float openCellVolume(int index){return layout!=null?layout.openCellVolume(index):1-Integer.bitCount(mask(index))*MICRO_VOLUME;}

	int replaceMask(int index,int requestedMask) {
		if((requestedMask&~FULL_MASK)!=0)throw new IllegalArgumentException("Cut-cell mask must fit eight bits");
		FixedGeometrySnapshot.requireConnectedOpenCell(index,requestedMask);
		int previous=mask(index);if(previous==requestedMask)return previous;
		if(writableMasks==null)writableMasks=layout==null?new byte[cellCount]:layout.copyMasks();
		layout=null;writableMasks[index]=(byte)requestedMask;
		return previous;
	}
	void restoreMicroMasks(byte[] microMasks) {
		if(microMasks==null||microMasks.length!=cellCount)throw new IllegalArgumentException("Collision mask length changed");
		if(Arrays.equals(microMasks,writableMasks)||layout!=null&&Arrays.equals(microMasks,layout.masksView()))return;
		for(int cell=0;cell<cellCount;cell++)FixedGeometrySnapshot.requireConnectedOpenCell(cell,Byte.toUnsignedInt(microMasks[cell]));
		writableMasks=microMasks.clone();layout=null;
	}
	byte[] copyMicroMasks(){return writableMasks!=null?writableMasks.clone():layout!=null?layout.copyMasks():new byte[cellCount];}

	byte[] copyBinaryFullMask() {
		byte[] result = new byte[cellCount];
		for (int index = 0; index < result.length; index++) result[index] = fullySolid(index) ? (byte) 1 : 0;
		return result;
	}

	float xFaceOpen(int x,int y,int z){return snapshot().faceOpen(0)[xFaceIndex(x,y,z)];}
	float yFaceOpen(int x,int y,int z){return snapshot().faceOpen(1)[yFaceIndex(x,y,z)];}
	float zFaceOpen(int x,int y,int z){return snapshot().faceOpen(2)[zFaceIndex(x,y,z)];}
	/** Borrowed immutable face apertures. Edits replace the snapshot. */
	float[] openFaceField(int axis){return snapshot().faceOpen(axis);}
	float solidWallArea(int cell,int axis){return snapshot().solidWallArea(cell,axis);}

	boolean pointInsideSolid(float x, float y, float z, float cellSize) {
		int microX = microCoordinate(x, cellSize, sizeX);
		int microY = microCoordinate(y, cellSize, sizeY);
		int microZ = microCoordinate(z, cellSize, sizeZ);
		return microSolid(microX, microY, microZ);
	}

	/** Closed sphere/box contact, including edges and corners between axis probes. */
	boolean sphereTouchesSolid(float x, float y, float z, float radius, float cellSize) {
		int minX = Math.max(0, (int) Math.floor(Math.nextDown((x - (double) radius) / cellSize)));
		int minY = Math.max(0, (int) Math.floor(Math.nextDown((y - (double) radius) / cellSize)));
		int minZ = Math.max(0, (int) Math.floor(Math.nextDown((z - (double) radius) / cellSize)));
		int maxX = Math.min(sizeX - 1, (int) Math.floor((x + (double) radius) / cellSize));
		int maxY = Math.min(sizeY - 1, (int) Math.floor((y + (double) radius) / cellSize));
		int maxZ = Math.min(sizeZ - 1, (int) Math.floor((z + (double) radius) / cellSize));
		double microSize = cellSize / (double) MICRO_CELLS_PER_AXIS;
		double radiusSquared = radius * (double) radius;
		for (int cz = minZ; cz <= maxZ; cz++) for (int cy = minY; cy <= maxY; cy++) {
			for (int cx = minX; cx <= maxX; cx++) {
				int cell=cellIndex(cx,cy,cz);int mask=cell<0?FULL_MASK:mask(cell);
				if (mask == 0) continue;
				for (int bit = 0; bit < 8; bit++) {
					if ((mask & 1 << bit) == 0) continue;
					double dx = intervalDistance(x, (2 * cx + (bit & 1)) * microSize, microSize);
					double dy = intervalDistance(y, (2 * cy + (bit >> 1 & 1)) * microSize, microSize);
					double dz = intervalDistance(z, (2 * cz + (bit >> 2)) * microSize, microSize);
					if (dx * dx + dy * dy + dz * dz <= radiusSquared) return true;
				}
			}
		}
		return false;
	}

	private static double intervalDistance(double coordinate, double minimum, double length) {
		return Math.max(0.0, Math.max(minimum - coordinate, coordinate - (minimum + length)));
	}

	/** Interior sphere/box overlap along a vertical segment; pure support tangency remains legal. */
	boolean verticalSpherePathTouchesSolid(float x, float fromY, float z, float toY, float radius, float cellSize) {
		double lowY = Math.min(fromY, toY), highY = Math.max(fromY, toY);
		int minX = Math.max(0, (int) Math.floor(Math.nextDown((x - (double) radius) / cellSize)));
		int minY = Math.max(0, (int) Math.floor(Math.nextDown((lowY - radius) / cellSize)));
		int minZ = Math.max(0, (int) Math.floor(Math.nextDown((z - (double) radius) / cellSize)));
		int maxX = Math.min(sizeX - 1, (int) Math.floor((x + (double) radius) / cellSize));
		int maxY = Math.min(sizeY - 1, (int) Math.floor((highY + radius) / cellSize));
		int maxZ = Math.min(sizeZ - 1, (int) Math.floor((z + (double) radius) / cellSize));
		double microSize = cellSize / (double) MICRO_CELLS_PER_AXIS, radiusSquared = radius * (double) radius;
		for (int cz = minZ; cz <= maxZ; cz++) for (int cy = minY; cy <= maxY; cy++) {
			for (int cx = minX; cx <= maxX; cx++) {
				int cell=cellIndex(cx,cy,cz);int mask=cell<0?FULL_MASK:mask(cell);
				if (mask == 0) continue;
				for (int bit = 0; bit < 8; bit++) {
					if ((mask & 1 << bit) == 0) continue;
					double dx = intervalDistance(x, (2 * cx + (bit & 1)) * microSize, microSize);
					double boxY = (2 * cy + (bit >> 1 & 1)) * microSize;
					double dy = Math.max(0, Math.max(boxY - highY, lowY - (boxY + microSize)));
					double dz = intervalDistance(z, (2 * cz + (bit >> 2)) * microSize, microSize);
					if (dx * dx + dy * dy + dz * dz < radiusSquared) return true;
				}
			}
		}
		return false;
	}

	boolean microCellOpen(int cell, int localX, int localY, int localZ) {
		if (cell < 0 || cell >= cellCount || localX < 0 || localX >= MICRO_CELLS_PER_AXIS
				|| localY < 0 || localY >= MICRO_CELLS_PER_AXIS
				|| localZ < 0 || localZ >= MICRO_CELLS_PER_AXIS) {
			throw new IllegalArgumentException("Cut-cell micro coordinate is invalid");
		}
		return !occupied(mask(cell), localX, localY, localZ);
	}

	private boolean microSolid(int microX, int microY, int microZ) {
		int cellX = microX / MICRO_CELLS_PER_AXIS;
		int cellY = microY / MICRO_CELLS_PER_AXIS;
		int cellZ = microZ / MICRO_CELLS_PER_AXIS;
		int localX = microX & 1;
		int localY = microY & 1;
		int localZ = microZ & 1;
		int cell=cellIndex(cellX,cellY,cellZ);
		return cell<0||occupied(mask(cell), localX, localY, localZ);
	}

	private static int microCoordinate(float coordinate, float cellSize, int cellCount) {
		float microSize = cellSize / MICRO_CELLS_PER_AXIS;
		return Math.min(cellCount * MICRO_CELLS_PER_AXIS - 1, Math.max(0, (int) Math.floor(coordinate / microSize)));
	}

	private static boolean occupied(int mask, int x, int y, int z) {
		return (mask & 1 << (x + 2 * (y + 2 * z))) != 0;
	}

	private int cellIndex(int x, int y, int z) { return grid.cell(x,y,z); }
	private int xFaceIndex(int x, int y, int z) { return grid.faceAt(x,y,z,0)-grid.faceOffset(0); }
	private int yFaceIndex(int x, int y, int z) { return grid.faceAt(x,y,z,1)-grid.faceOffset(1); }
	private int zFaceIndex(int x, int y, int z) { return grid.faceAt(x,y,z,2)-grid.faceOffset(2); }
}

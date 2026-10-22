package mmm.fluid;

/** Immutable fixed collision masks, cell capacities, face apertures and solid-wall areas. */
public final class FixedGeometrySnapshot {
	private static final int MICRO_CELLS_PER_AXIS = 2;
	private static final float MICRO_FACE_AREA = .25F;
	private final int sizeX,sizeY,sizeZ;
	private final byte[] occupancy;
	private volatile FaceIncidence faceIncidence;
	private final float[] openCellVolume,openFaceX,openFaceY,openFaceZ;
	private final float[] solidWallAreaX,solidWallAreaY,solidWallAreaZ;

	private FixedGeometrySnapshot(int sizeX,int sizeY,int sizeZ,byte[] ownedMasks) {
		if(sizeX<=0||sizeY<=0||sizeZ<=0||ownedMasks==null
				||ownedMasks.length!=Math.multiplyExact(Math.multiplyExact(sizeX,sizeY),sizeZ))
			throw new IllegalArgumentException("Fixed collision geometry shape differs");
		this.sizeX=sizeX;this.sizeY=sizeY;this.sizeZ=sizeZ;occupancy=ownedMasks;
		int count=ownedMasks.length;openCellVolume=new float[count];
		for(int cell=0;cell<count;cell++){requireConnectedOpenCell(cell,mask(cell));openCellVolume[cell]=1-Integer.bitCount(mask(cell))*.125F;}
		openFaceX=new float[(sizeX+1)*sizeY*sizeZ];openFaceY=new float[sizeX*(sizeY+1)*sizeZ];openFaceZ=new float[sizeX*sizeY*(sizeZ+1)];
		solidWallAreaX=new float[count];solidWallAreaY=new float[count];solidWallAreaZ=new float[count];
		buildFaces();
	}

	/** Candidate graphs borrow these shape-only columns; each graph owns its changing coefficients. */
	FaceIncidence faceIncidence() {
		var prepared=faceIncidence;
		if(prepared==null)synchronized(this){
			prepared=faceIncidence;
			if(prepared==null)faceIncidence=prepared=new FaceIncidence(sizeX,sizeY,sizeZ);
		}
		return prepared;
	}
	static final class FaceIncidence {
		final int[] offsets,firstCell,secondCell,axis,localFace;
		final int faceCount;
		private FaceIncidence(int sizeX,int sizeY,int sizeZ) {
			offsets=new int[]{0,(sizeX+1)*sizeY*sizeZ,(sizeX+1)*sizeY*sizeZ+sizeX*(sizeY+1)*sizeZ};
			faceCount=offsets[2]+sizeX*sizeY*(sizeZ+1);
			firstCell=new int[faceCount];secondCell=new int[faceCount];axis=new int[faceCount];localFace=new int[faceCount];
			java.util.Arrays.fill(firstCell,-1);java.util.Arrays.fill(secondCell,-1);
			for(int a=0;a<3;a++){
				int nx=sizeX+(a==0?1:0),ny=sizeY+(a==1?1:0),nz=sizeZ+(a==2?1:0);
				for(int z=0;z<nz;z++)for(int y=0;y<ny;y++)for(int x=0;x<nx;x++){
					int local=(z*ny+y)*nx+x,face=offsets[a]+local;
					axis[face]=a;localFace[face]=local;
					int coordinate=a==0?x:a==1?y:z,length=a==0?sizeX:a==1?sizeY:sizeZ;
					if(coordinate==0||coordinate==length)continue;
					secondCell[face]=(z*sizeY+y)*sizeX+x;
					firstCell[face]=((z-(a==2?1:0))*sizeY+y-(a==1?1:0))*sizeX+x-(a==0?1:0);
				}
			}
		}
	}

	/** A coarse MAC cell owns one pressure and inertia state, hence one fixed open component. */
	static void requireConnectedOpenCell(int cell,int mask){
		if(mmm.inventory.LiquidCellInventory.connectedSupports(mask).size()>1)
			throw new IllegalArgumentException("Fixed geometry requires one connected open space per solver cell: cell="+cell+", mask="+mask+"; resolve the separating wall on the solver grid");
	}

	/** Decodes mutable external masks into the same immutable representation used by live candidates. */
	public static FixedGeometrySnapshot fromMasks(int sizeX,int sizeY,int sizeZ,byte[] masks) {
		if(masks==null)throw new IllegalArgumentException("Fixed collision masks are missing");
		return new FixedGeometrySnapshot(sizeX,sizeY,sizeZ,masks.clone());
	}
	/** The editing view transfers its private buffer; the buffer is never written again. */
	static FixedGeometrySnapshot takeMasks(int sizeX,int sizeY,int sizeZ,byte[] ownedMasks) {
		return new FixedGeometrySnapshot(sizeX,sizeY,sizeZ,ownedMasks);
	}
	public boolean matchesShape(int x,int y,int z){return sizeX==x&&sizeY==y&&sizeZ==z;}
	public int cellCount(){return occupancy.length;}
	public int mask(int cell){return Byte.toUnsignedInt(occupancy[cell]);}
	public byte[] copyMasks(){return occupancy.clone();}
	/** Borrowed immutable masks for prepared geometry in this package. */
	byte[] masksView(){return occupancy;}
	float openCellVolume(int cell){return openCellVolume[cell];}
	/** Borrowed immutable face field for a numerical stage. */
	float[] faceOpen(int axis){
		return switch(axis){case 0->openFaceX;case 1->openFaceY;case 2->openFaceZ;default->throw new IllegalArgumentException("Face axis must be 0, 1 or 2");};
	}
	float solidWallArea(int cell,int axis){
		return switch(axis){case 0->solidWallAreaX[cell];case 1->solidWallAreaY[cell];case 2->solidWallAreaZ[cell];default->throw new IllegalArgumentException("Wall normal axis must be 0, 1 or 2");};
	}

	private void buildFaces() {
		for (int z = 0; z < sizeZ; z++) for (int y = 0; y < sizeY; y++) for (int x = 1; x < sizeX; x++) {
			int left = mask(cellIndex(x - 1, y, z));
			int right = mask(cellIndex(x, y, z));
			int open = 0;
			for (int microZ = 0; microZ < 2; microZ++) for (int microY = 0; microY < 2; microY++) {
				if (!occupied(left, 1, microY, microZ) && !occupied(right, 0, microY, microZ)) open++;
			}
			openFaceX[xFaceIndex(x, y, z)] = open * MICRO_FACE_AREA;
		}
		for (int z = 0; z < sizeZ; z++) for (int y = 1; y < sizeY; y++) for (int x = 0; x < sizeX; x++) {
			int below = mask(cellIndex(x, y - 1, z));
			int above = mask(cellIndex(x, y, z));
			int open = 0;
			for (int microZ = 0; microZ < 2; microZ++) for (int microX = 0; microX < 2; microX++) {
				if (!occupied(below, microX, 1, microZ) && !occupied(above, microX, 0, microZ)) open++;
			}
			openFaceY[yFaceIndex(x, y, z)] = open * MICRO_FACE_AREA;
		}
		for (int z = 1; z < sizeZ; z++) for (int y = 0; y < sizeY; y++) for (int x = 0; x < sizeX; x++) {
			int back = mask(cellIndex(x, y, z - 1));
			int front = mask(cellIndex(x, y, z));
			int open = 0;
			for (int microY = 0; microY < 2; microY++) for (int microX = 0; microX < 2; microX++) {
				if (!occupied(back, microX, microY, 1) && !occupied(front, microX, microY, 0)) open++;
			}
			openFaceZ[zFaceIndex(x, y, z)] = open * MICRO_FACE_AREA;
		}
		for (int z = 0; z < sizeZ; z++) for (int y = 0; y < sizeY; y++) for (int x = 0; x < sizeX; x++) {
			int cell = cellIndex(x, y, z);
			for (int localZ = 0; localZ < 2; localZ++) for (int localY = 0; localY < 2; localY++) for (int localX = 0; localX < 2; localX++) {
				if (occupied(mask(cell), localX, localY, localZ)) continue;
				int microX = 2 * x + localX, microY = 2 * y + localY, microZ = 2 * z + localZ;
				if (microNeighborSolid(microX - 1, microY, microZ)) solidWallAreaX[cell] += MICRO_FACE_AREA;
				if (microNeighborSolid(microX + 1, microY, microZ)) solidWallAreaX[cell] += MICRO_FACE_AREA;
				if (microNeighborSolid(microX, microY - 1, microZ)) solidWallAreaY[cell] += MICRO_FACE_AREA;
				if (microNeighborSolid(microX, microY + 1, microZ)) solidWallAreaY[cell] += MICRO_FACE_AREA;
				if (microNeighborSolid(microX, microY, microZ - 1)) solidWallAreaZ[cell] += MICRO_FACE_AREA;
				if (microNeighborSolid(microX, microY, microZ + 1)) solidWallAreaZ[cell] += MICRO_FACE_AREA;
			}
		}
	}

	private boolean microNeighborSolid(int microX, int microY, int microZ) {
		if (microX < 0 || microX >= 2 * sizeX || microY < 0 || microY >= 2 * sizeY
				|| microZ < 0 || microZ >= 2 * sizeZ) return true;
		return microSolid(microX, microY, microZ);
	}

	private boolean microSolid(int microX, int microY, int microZ) {
		int cellX = microX / MICRO_CELLS_PER_AXIS;
		int cellY = microY / MICRO_CELLS_PER_AXIS;
		int cellZ = microZ / MICRO_CELLS_PER_AXIS;
		int localX = microX & 1;
		int localY = microY & 1;
		int localZ = microZ & 1;
		return occupied(mask(cellIndex(cellX, cellY, cellZ)), localX, localY, localZ);
	}

	private static boolean occupied(int mask, int x, int y, int z) {
		return (mask & 1 << (x + 2 * (y + 2 * z))) != 0;
	}

	private int cellIndex(int x, int y, int z) { return (z * sizeY + y) * sizeX + x; }
	private int xFaceIndex(int x, int y, int z) { return (z * sizeY + y) * (sizeX + 1) + x; }
	private int yFaceIndex(int x, int y, int z) { return (z * (sizeY + 1) + y) * sizeX + x; }
	private int zFaceIndex(int x, int y, int z) { return (z * sizeY + y) * sizeX + x; }
}

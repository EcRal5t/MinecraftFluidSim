package mmm.fluid;

import mmm.spatial.SpatialGrid;
import java.util.List;

/** Immutable fixed collision masks, cell capacities, face apertures and solid-wall areas. */
public final class FixedGeometrySnapshot {
	private final SpatialGrid grid;
	private final byte[] occupancy;
	private final int[] exteriorMasks;
	private final double totalOpenCellVolume;
	private volatile FaceIncidence faceIncidence;
	private final float[] openCellVolume;
	private final float[][] openFaces, solidWallArea;
	private final List<ScreenFace> screens;

	private FixedGeometrySnapshot(SpatialGrid grid,byte[] masks,int[] exteriorMasks,List<ScreenFace> screens) {
		if(grid==null||masks==null||masks.length!=grid.cellCount())
			throw new IllegalArgumentException("Fixed collision geometry shape differs");
		if(exteriorMasks==null||exteriorMasks.length!=grid.cellCount())throw new IllegalArgumentException("Exterior collision geometry shape differs");
        for(int value:exteriorMasks)if((value&0xff000000)!=0)throw new IllegalArgumentException("Exterior collision mask exceeds 24 bits");
        this.grid=grid;occupancy=masks;this.exteriorMasks=exteriorMasks;
		var ordered = new java.util.TreeMap<Integer, ScreenFace>();
		for (var screen : screens) {
			if (screen.face() >= grid.faceCount() || grid.faceLeft(screen.face()) < 0 || grid.faceRight(screen.face()) < 0
					|| ordered.put(screen.face(), screen) != null)
				throw new IllegalArgumentException("Invalid or duplicate screen face");
		}
		this.screens = List.copyOf(ordered.values());
		openCellVolume=new float[grid.cellCount()];openFaces=new float[3][];solidWallArea=new float[3][];
		for(int axis=0;axis<3;axis++){openFaces[axis]=new float[grid.faceCount(axis)];solidWallArea[axis]=new float[grid.cellCount()];}
		double total=0;
		for(int cell=0;cell<grid.cellCount();cell++){
			requireConnectedOpenCell(cell,mask(cell));openCellVolume[cell]=1-Integer.bitCount(mask(cell))*.125F;total+=openCellVolume[cell];
		}
		totalOpenCellVolume=total;buildFaces();
	}
	public SpatialGrid grid(){return grid;}
	public List<ScreenFace> screens(){return screens;}
	public double totalOpenCellVolume(){return totalOpenCellVolume;}
	FaceIncidence faceIncidence(){
		var prepared=faceIncidence;
		if(prepared==null)synchronized(this){prepared=faceIncidence;if(prepared==null)faceIncidence=prepared=new FaceIncidence(grid);}
		return prepared;
	}
	static final class FaceIncidence {
		final int[] offsets,firstCell,secondCell,axis,localFace;
		final int faceCount;
		private FaceIncidence(SpatialGrid grid){
			offsets=new int[]{grid.faceOffset(0),grid.faceOffset(1),grid.faceOffset(2)};faceCount=grid.faceCount();
			firstCell=new int[faceCount];secondCell=new int[faceCount];axis=new int[faceCount];localFace=new int[faceCount];
			for(int face=0;face<faceCount;face++){
				int a=grid.faceAxis(face),left=grid.faceLeft(face),right=grid.faceRight(face);
				axis[face]=a;localFace[face]=face-grid.faceOffset(a);
				firstCell[face]=left>=0&&right>=0?left:-1;secondCell[face]=left>=0&&right>=0?right:-1;
			}
		}
	}
	static void requireConnectedOpenCell(int cell,int mask){
		if(mmm.inventory.LiquidCellInventory.connectedSupports(mask).size()>1)
			throw new IllegalArgumentException("Fixed geometry requires one connected open space per solver cell: cell="+cell+", mask="+mask+"; resolve the separating wall on the solver grid");
	}
	public static FixedGeometrySnapshot fromMasks(int x,int y,int z,byte[] masks){return fromMasks(SpatialGrid.rectangular(x,y,z),masks);}
	public static FixedGeometrySnapshot fromMasks(SpatialGrid grid,byte[] masks){
		return fromMasks(grid,masks,List.of());
	}
	public static FixedGeometrySnapshot fromMasks(SpatialGrid grid,byte[] masks,List<ScreenFace> screens){
		return fromMasks(grid,masks,new int[grid.cellCount()],screens);
	}
	public static FixedGeometrySnapshot fromMasks(SpatialGrid grid,byte[] masks,int[] exteriorMasks,List<ScreenFace> screens){
        if(masks==null)throw new IllegalArgumentException("Fixed collision masks are missing");return new FixedGeometrySnapshot(grid,masks.clone(),exteriorMasks.clone(),screens);
    }
    static FixedGeometrySnapshot takeMasks(SpatialGrid grid,byte[] masks,int[] exteriorMasks,List<ScreenFace> screens){return new FixedGeometrySnapshot(grid,masks,exteriorMasks.clone(),screens);}
    static FixedGeometrySnapshot takeMasks(SpatialGrid grid,byte[] masks,List<ScreenFace> screens){return takeMasks(grid,masks,new int[grid.cellCount()],screens);}
	static FixedGeometrySnapshot takeMasks(SpatialGrid grid,byte[] masks){return takeMasks(grid,masks,List.of());}
	static FixedGeometrySnapshot takeMasks(int x,int y,int z,byte[] masks){return takeMasks(SpatialGrid.rectangular(x,y,z),masks);}
	public boolean matchesShape(int x,int y,int z){return grid.sizeX()==x&&grid.sizeY()==y&&grid.sizeZ()==z;}
	public int cellCount(){return occupancy.length;}
	public int mask(int cell){return Byte.toUnsignedInt(occupancy[cell]);}
	public byte[] copyMasks(){return occupancy.clone();}
    public int[] copyExteriorMasks(){return exteriorMasks.clone();}
    public int exteriorMask(int cell,int side){return (exteriorMasks[cell] >>> (4*side)) & 15;}
	byte[] masksView(){return occupancy;}
	float openCellVolume(int cell){return openCellVolume[cell];}
	float[] faceOpen(int axis){return openFaces[axis];}
	float solidWallArea(int cell,int axis){return solidWallArea[axis][cell];}

	private void buildFaces(){
		for(int face=0;face<grid.faceCount();face++){
			int left=grid.faceLeft(face),right=grid.faceRight(face),axis=grid.faceAxis(face);
			if(left<0||right<0) {
				int cell=left<0?right:left,side=left<0?0:1,blocked=exteriorMask(cell,2*axis+side),open=0;
				for(int u=0;u<2;u++)for(int v=0;v<2;v++) {
					int bit=(side<<axis)|(u<<(axis==0?1:0))|(v<<(axis==2?1:2));
					if((mask(cell)&(1<<bit))==0&&(blocked&(1<<(u+2*v)))==0)open++;
				}
				openFaces[axis][face-grid.faceOffset(axis)]=open*.25F;
				continue;
			}
			int open=0;
			for(int u=0;u<2;u++)for(int v=0;v<2;v++){
				int lx=axis==0?1:u,ly=axis==1?1:(axis==0?u:v),lz=axis==2?1:v;
				int rx=axis==0?0:u,ry=axis==1?0:(axis==0?u:v),rz=axis==2?0:v;
				if(!occupied(mask(left),lx,ly,lz)&&!occupied(mask(right),rx,ry,rz))open++;
			}
			openFaces[axis][face-grid.faceOffset(axis)]=open*.25F;
		}
		for(int cell=0;cell<grid.cellCount();cell++){
			int x=grid.x(cell),y=grid.y(cell),z=grid.z(cell);
			for(int lz=0;lz<2;lz++)for(int ly=0;ly<2;ly++)for(int lx=0;lx<2;lx++){
				if(occupied(mask(cell),lx,ly,lz))continue;
				int mx=2*x+lx,my=2*y+ly,mz=2*z+lz;
				for(int axis=0;axis<3;axis++)for(int d=-1;d<=1;d+=2)
					if(microNeighborSolid(mx+(axis==0?d:0),my+(axis==1?d:0),mz+(axis==2?d:0),cell,axis,d))solidWallArea[axis][cell]+=.25F;
			}
		}
	}
	private boolean microNeighborSolid(int x,int y,int z,int source,int axis,int direction){
		int cell=x<0||y<0||z<0?-1:grid.cell(x/2,y/2,z/2);
		if(cell>=0)return occupied(mask(cell),x&1,y&1,z&1);
		int[] coordinate={x,y,z};
		int u=coordinate[axis==0?1:0]&1,v=coordinate[axis==2?1:2]&1;
		return (exteriorMask(source,2*axis+(direction>0?1:0))&(1<<(u+2*v)))!=0;
	}
	private static boolean occupied(int mask,int x,int y,int z){return (mask&1<<(x+2*(y+2*z)))!=0;}
}

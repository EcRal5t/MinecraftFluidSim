package mmm.fluid;

import mmm.spatial.SpatialGrid;

/** Immutable SI momentum on the staggered faces of a liquid control-volume grid. */
public final class MacMomentumState {
	private final SpatialGrid grid;
	private final double[] x, y, z;

	public MacMomentumState(int sizeX, int sizeY, int sizeZ, double[] x, double[] y, double[] z) {
		this(SpatialGrid.rectangular(sizeX,sizeY,sizeZ),x,y,z,false);
	}
	public MacMomentumState(SpatialGrid grid,double[] x,double[] y,double[] z) {
		this(grid,x,y,z,false);
	}
	/** Private solver outputs transfer ownership once; public construction still copies caller arrays. */
	static MacMomentumState takeOwned(int sizeX,int sizeY,int sizeZ,double[] x,double[] y,double[] z){
		return new MacMomentumState(SpatialGrid.rectangular(sizeX,sizeY,sizeZ),x,y,z,true);
	}
	private MacMomentumState(SpatialGrid grid,double[] x,double[] y,double[] z,boolean owned){
		if (grid == null || x == null || y == null || z == null
				|| x.length != grid.faceCount(0) || y.length != grid.faceCount(1) || z.length != grid.faceCount(2))
			throw new IllegalArgumentException("MAC momentum shape differs from its grid");
		this.grid = grid;
		this.x = checked(x,owned); this.y = checked(y,owned); this.z = checked(z,owned);
	}

	private static double[] checked(double[] values,boolean owned) {
		double[] result = owned?values:values.clone();
		for (double value : result) if (!Double.isFinite(value))
			throw new IllegalArgumentException("MAC momentum must be finite");
		return result;
	}

	public SpatialGrid grid() { return grid; }
	public int sizeX() { return grid.sizeX(); }
	public int sizeY() { return grid.sizeY(); }
	public int sizeZ() { return grid.sizeZ(); }
	public double[] copyX() { return x.clone(); }
	public double[] copyY() { return y.clone(); }
	public double[] copyZ() { return z.clone(); }

	void copyInto(double[] targetX, double[] targetY, double[] targetZ) {
		if (targetX.length != x.length || targetY.length != y.length || targetZ.length != z.length)
			throw new IllegalArgumentException("MAC momentum destination differs from its grid");
		System.arraycopy(x, 0, targetX, 0, x.length);
		System.arraycopy(y, 0, targetY, 0, y.length);
		System.arraycopy(z, 0, targetZ, 0, z.length);
	}
}

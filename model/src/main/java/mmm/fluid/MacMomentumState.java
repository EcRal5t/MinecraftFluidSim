package mmm.fluid;

/** Immutable SI momentum on the staggered faces of a liquid control-volume grid. */
public final class MacMomentumState {
	private final int sizeX, sizeY, sizeZ;
	private final double[] x, y, z;

	public MacMomentumState(int sizeX, int sizeY, int sizeZ, double[] x, double[] y, double[] z) {
		this(sizeX,sizeY,sizeZ,x,y,z,false);
	}
	/** Private solver outputs transfer ownership once; public construction still copies caller arrays. */
	static MacMomentumState takeOwned(int sizeX,int sizeY,int sizeZ,double[] x,double[] y,double[] z){
		return new MacMomentumState(sizeX,sizeY,sizeZ,x,y,z,true);
	}
	private MacMomentumState(int sizeX,int sizeY,int sizeZ,double[] x,double[] y,double[] z,boolean owned){
		if (sizeX < 2 || sizeY < 2 || sizeZ < 2 || x == null || y == null || z == null
				|| x.length != Math.multiplyExact(Math.multiplyExact(sizeX + 1, sizeY), sizeZ)
				|| y.length != Math.multiplyExact(Math.multiplyExact(sizeX, sizeY + 1), sizeZ)
				|| z.length != Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ + 1))
			throw new IllegalArgumentException("MAC momentum shape differs from its grid");
		this.sizeX = sizeX; this.sizeY = sizeY; this.sizeZ = sizeZ;
		this.x = checked(x,owned); this.y = checked(y,owned); this.z = checked(z,owned);
	}

	private static double[] checked(double[] values,boolean owned) {
		double[] result = owned?values:values.clone();
		for (double value : result) if (!Double.isFinite(value))
			throw new IllegalArgumentException("MAC momentum must be finite");
		return result;
	}

	public int sizeX() { return sizeX; }
	public int sizeY() { return sizeY; }
	public int sizeZ() { return sizeZ; }
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

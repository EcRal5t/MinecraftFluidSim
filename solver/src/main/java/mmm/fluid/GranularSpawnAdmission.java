package mmm.fluid;

import java.util.Arrays;
import mmm.granular.GranularMechanics;
import mmm.granular.GranularParcelStore;

/** Private center-cell index for live parcels and accepted dry-pour positions. */
final class GranularSpawnAdmission {
	private final int sizeX, sizeY, sizeZ;
	private final mmm.spatial.SpatialGrid grid;
	private final int divisions;
	private final float h;
	private final double cellSize;
	private final int[] head, next;
	private final float[] x, y, z, radius;
	private float largestRadius;
	private int count;

	GranularSpawnAdmission(int sizeX, int sizeY, int sizeZ, float h,
	                       GranularParcelStore parcels, int additional) {
		this(mmm.spatial.SpatialGrid.rectangular(sizeX,sizeY,sizeZ),h,parcels,additional);
	}
	GranularSpawnAdmission(mmm.spatial.SpatialGrid grid,float h,GranularParcelStore parcels,int additional) {
		this(grid,h,Math.addExact(parcels.count(), additional));
		for (int i = 0; i < parcels.count(); i++) {
			var parcel = parcels.parcel(i);
			append(parcel.x(), parcel.y(), parcel.z(), parcel.solidVolumeSvq());
		}
	}

	GranularSpawnAdmission(int sizeX, int sizeY, int sizeZ, float h, int capacity) {
		this(mmm.spatial.SpatialGrid.rectangular(sizeX,sizeY,sizeZ),h,capacity);
	}
	GranularSpawnAdmission(mmm.spatial.SpatialGrid grid,float h,int capacity) {
		this.grid=grid;int sizeX=grid.sizeX(),sizeY=grid.sizeY(),sizeZ=grid.sizeZ();
		int cells=grid.cellCount();divisions=FlipCutCellGeometry.MICRO_CELLS_PER_AXIS;
		int factor=divisions*divisions*divisions;
		this.sizeX = sizeX * divisions; this.sizeY = sizeY * divisions; this.sizeZ = sizeZ * divisions;
		this.h = h; cellSize = h / (double) divisions;
		head = new int[Math.multiplyExact(cells,factor)];
		Arrays.fill(head, -1);
		next = new int[capacity]; x = new float[capacity]; y = new float[capacity];
		z = new float[capacity]; radius = new float[capacity];
	}

	void clear() { Arrays.fill(head, -1); count = 0; largestRadius = 0; }

	boolean allows(float px, float py, float pz, long svq) {
		return firstOverlap(px, py, pz, svq, () -> { }) < 0;
	}

	int firstOverlap(float px, float py, float pz, long svq, Runnable visit) {
		float r = GranularMechanics.representativeRadius(svq, h);
		double reach = (double) r + largestRadius;
		int loX = coordinate(Math.nextDown(px - reach), sizeX), hiX = coordinate(Math.nextUp(px + reach), sizeX);
		int loY = coordinate(Math.nextDown(py - reach), sizeY), hiY = coordinate(Math.nextUp(py + reach), sizeY);
		int loZ = coordinate(Math.nextDown(pz - reach), sizeZ), hiZ = coordinate(Math.nextUp(pz + reach), sizeZ);
		for (int iz = loZ; iz <= hiZ; iz++)
			for (int iy = loY; iy <= hiY; iy++)
				for (int ix = loX; ix <= hiX; ix++)
					for (int i = bucket(ix,iy,iz)<0?-1:head[bucket(ix,iy,iz)]; i >= 0; i = next[i]) {
						visit.run();
						if (interiorsOverlap(px, py, pz, r, x[i], y[i], z[i], radius[i])) return i;
					}
		return -1;
	}

	void append(float px, float py, float pz, long svq) {
		int cell = bucket(coordinate(px,sizeX),coordinate(py,sizeY),coordinate(pz,sizeZ));
		if(cell<0)throw new IllegalArgumentException("Granular position is outside its spatial blocks");
		x[count] = px; y[count] = py; z[count] = pz;
		radius[count] = GranularMechanics.representativeRadius(svq, h);
		largestRadius = Math.max(largestRadius, radius[count]);
		next[count] = head[cell]; head[cell] = count++;
	}

	/** Coarse representative contacts resolve separation to one percent of the smaller radius. */
	static double positionTolerance(double radius, double otherRadius) { return .01 * Math.min(radius, otherRadius); }

	/** Stored-coordinate rounding and the shared representative contact resolution. */
	static boolean interiorsOverlap(double x, double y, double z, double r,
	                                double ox, double oy, double oz, double otherRadius) {
		double rounding = Math.hypot(Math.hypot((double) Math.ulp((float) x) + Math.ulp((float) ox),
				(double) Math.ulp((float) y) + Math.ulp((float) oy)),
				(double) Math.ulp((float) z) + Math.ulp((float) oz));
		return Math.hypot(Math.hypot(x - ox, y - oy), z - oz)
				< r + otherRadius - rounding - positionTolerance(r, otherRadius);
	}

	private int coordinate(double p, int size) {
		return Math.max(0, Math.min(size - 1, (int) Math.floor(p / cellSize)));
	}
	private int bucket(int x,int y,int z) {
		int cell=grid.cell(x/divisions,y/divisions,z/divisions);
		return cell<0?-1:cell*divisions*divisions*divisions+(x%divisions)+divisions*((y%divisions)+divisions*(z%divisions));
	}
}

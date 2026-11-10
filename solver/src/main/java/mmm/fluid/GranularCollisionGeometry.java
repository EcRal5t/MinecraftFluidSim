package mmm.fluid;

import mmm.granular.GranularDepositStore;
import mmm.granular.GranularSphereGeometry;
import mmm.granular.SolidVolume;
import java.util.function.BiConsumer;

/** Local geometric queries for fixed micro boxes and cell-local bulk beds. */
final class GranularCollisionGeometry {
	private final int[] sizes;
	private final double h;
	private final FlipCutCellGeometry cutCells;
	private final GranularDepositStore deposits;
	private final mmm.spatial.SpatialGrid grid;

	GranularCollisionGeometry(int x, int y, int z, float h, FlipCutCellGeometry cutCells, GranularDepositStore deposits) {
		this.sizes = new int[]{x, y, z}; this.h = h; this.cutCells = cutCells; this.deposits = deposits;
		this.grid=cutCells.snapshot().grid();
	}

	void forEachBox(double[] minimum, double[] maximum, BiConsumer<double[], double[]> action) {
		forEachBox(minimum, maximum, cell -> false, action);
	}

	private void forEachBox(double[] minimum, double[] maximum, java.util.function.IntPredicate skipBed,
			BiConsumer<double[], double[]> action) {
		double micro = h / 2; int[] lo = lower(minimum, micro), hi = upper(maximum, micro, 2);
		for (int z = lo[2]; z <= hi[2]; z++) for (int y = lo[1]; y <= hi[1]; y++) for (int x = lo[0]; x <= hi[0]; x++) {
			int cell=grid.cell(x/2,y/2,z/2);int mask=cell<0?255:cutCells.mask(cell);
			if ((mask & 1 << ((x & 1) + 2 * (y & 1) + 4 * (z & 1))) != 0)
				action.accept(new double[]{x * micro, y * micro, z * micro}, new double[]{(x + 1) * micro, (y + 1) * micro, (z + 1) * micro});
		}
		if (deposits == null) return;
		lo = lower(minimum, h); hi = upper(maximum, h, 1);
		for (int z = lo[2]; z <= hi[2]; z++) for (int y = lo[1]; y <= hi[1]; y++) for (int x = lo[0]; x <= hi[0]; x++) {
			int cell = grid.cell(x,y,z);if(cell<0)continue;
			if (skipBed.test(cell)) continue;
			long bulk = deposits.cellBulkVolumeSvq(cell);
			if (bulk <= 0) continue;
			double top=bedTop(cell,bulk); int mask=cutCells.mask(cell);
			for(int bit=0;bit<8;bit++)if((mask&(1<<bit))==0) {
				double bx=(x+.5*(bit&1))*h,by=(y+.5*((bit>>1)&1))*h,bz=(z+.5*((bit>>2)&1))*h;
				double upper=Math.min(by+h*.5,top);
				if(upper>by)action.accept(new double[]{bx,by,bz},new double[]{bx+h*.5,upper,bz+h*.5});
			}
		}
	}

	boolean sphereTouchesSolid(float x, float y, float z, float radius) {
		double[] p = {x, y, z}, lo = new double[3], hi = new double[3]; boolean[] touches = {false};
		for (int a = 0; a < 3; a++) { lo[a] = p[a] - radius; hi[a] = p[a] + radius; }
		forEachBox(lo, hi, (minimum, maximum) -> {
			if (GranularSphereGeometry.touchesBox(x, y, z, radius,
					minimum[0], minimum[1], minimum[2], maximum[0], maximum[1], maximum[2])) touches[0] = true;
		});
		return touches[0];
	}

	boolean positionValid(float x, float y, float z, float radius) {
		return positionValid(x, y, z, radius, cell -> false);
	}

	/** Pending phase geometry is reconciled by the next mechanical step. */
	boolean positionValid(float x, float y, float z, float radius, java.util.function.IntPredicate pendingBed) {
		double tolerance = 1e-5 * h, resolvedRadius = Math.max(0.0, radius - tolerance);
		if (x < resolvedRadius || x > sizes[0] * h - resolvedRadius
				|| y < resolvedRadius || y > sizes[1] * h - resolvedRadius
				|| z < resolvedRadius || z > sizes[2] * h - resolvedRadius) return false;
		double[] point = {x, y, z}, lo = new double[3], hi = new double[3];
		for (int axis = 0; axis < 3; axis++) { lo[axis] = point[axis] - resolvedRadius; hi[axis] = point[axis] + resolvedRadius; }
		boolean[] intersects = {false};
		forEachBox(lo, hi, pendingBed, (minimum, maximum) -> {
			if (intersects(point, resolvedRadius, minimum, maximum)) intersects[0] = true;
		});
		return !intersects[0];
	}

	private static boolean intersects(double[] point, double radius, double[] minimum, double[] maximum) {
		double squared = 0.0;
		for (int axis = 0; axis < 3; axis++) {
			double distance = point[axis] - Math.clamp(point[axis], minimum[axis], maximum[axis]);
			squared += distance * distance;
		}
		return squared < radius * radius;
	}

	/** The horizontal bed is clipped to open microboxes and must fit their combined volume. */
	boolean bedGeometryFits(int cell, long bulk) {
		if (bulk < 0) throw new IllegalArgumentException("Candidate bed bulk is negative");
		long capacity=(long)Integer.bitCount(~cutCells.mask(cell)&255)*SolidVolume.SVQ_PER_CELL/8;
		return bulk<=capacity;
	}
	double bedTop(int cell,long bulk) {
		if(!bedGeometryFits(cell,bulk))throw new IllegalArgumentException("Deposit exceeds open microboxes");
		int open=~cutCells.mask(cell)&255;
		double lower=Integer.bitCount(open&0x33)*(SolidVolume.SVQ_PER_CELL/8.0);
		double upper=Integer.bitCount(open&0xcc)*(SolidVolume.SVQ_PER_CELL/8.0);
		double height=bulk<=lower?(lower>0?.5*bulk/lower:0):.5+(upper>0?.5*(bulk-lower)/upper:0);
		return (grid.y(cell)+height)*h;
	}

	/** Number of candidate bed cells visited by the same vertical broad phase. */
	int verticalSpherePathWork(float x, float fromY, float z, float toY, float radius) {
		return spherePathWork(x, fromY, z, x, toY, z, radius);
	}

	int spherePathWork(float x, float y, float z, float toX, float toY, float toZ, float radius) {
		if (deposits == null) return 0;
		int[] lo = lower(new double[]{Math.min(x, toX) - (double) radius, Math.min(y, toY) - (double) radius, Math.min(z, toZ) - (double) radius}, h);
		int[] hi = upper(new double[]{Math.max(x, toX) + (double) radius, Math.max(y, toY) + (double) radius, Math.max(z, toZ) + (double) radius}, h, 1);
		int work = 1;
		for (int a = 0; a < 3; a++) work = Math.multiplyExact(work, Math.max(0, hi[a] - lo[a] + 1));
		return work;
	}

	/** Vertical swept-sphere interior overlap; endpoint support tangency remains admissible. */
	boolean verticalSpherePathTouchesSolid(float x, float fromY, float z, float toY, float radius) {
		return spherePathTouchesSolid(x, fromY, z, x, toY, z, radius);
	}

	boolean spherePathTouchesSolid(float x, float y, float z, float toX, float toY, float toZ, float radius) {
		double[] from = {x, y, z}, to = {toX, toY, toZ}, lo = new double[3], hi = new double[3];
		for (int a = 0; a < 3; a++) { lo[a] = Math.min(from[a], to[a]) - radius; hi[a] = Math.max(from[a], to[a]) + radius; }
		boolean[] crosses = {false};
		forEachBox(lo, hi, (minimum, maximum) -> {
			if (!crosses[0] && GranularSphereGeometry.pathEntersBox(from, to, radius, minimum, maximum)) crosses[0] = true;
		});
		return crosses[0];
	}

	private static int[] lower(double[] point, double length) {
		int[] result = new int[3];
		for (int a = 0; a < 3; a++) result[a] = Math.max(0, (int) Math.floor(Math.nextDown(point[a]) / length));
		return result;
	}
	private int[] upper(double[] point, double length, int divisions) {
		int[] result = new int[3];
		for (int a = 0; a < 3; a++) result[a] = Math.min(divisions * sizes[a] - 1, (int) Math.floor(point[a] / length));
		return result;
	}
}

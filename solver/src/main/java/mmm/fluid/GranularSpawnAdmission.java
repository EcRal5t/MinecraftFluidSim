package mmm.fluid;

import java.util.Arrays;
import mmm.granular.GranularMechanics;
import mmm.granular.GranularParcelStore;
import mmm.units.SignedDyadic;

/** Private center-cell index for live parcels and accepted dry-pour positions. */
final class GranularSpawnAdmission {
	private final int sizeX, sizeY, sizeZ;
	private final float h;
	private final int[] head, next;
	private final float[] x, y, z, radius;
	private float largestRadius;
	private int count;

	GranularSpawnAdmission(int sizeX, int sizeY, int sizeZ, float h,
	                       GranularParcelStore parcels, int additional) {
		this(sizeX, sizeY, sizeZ, h, Math.addExact(parcels.count(), additional));
		for (int i = 0; i < parcels.count(); i++) {
			var parcel = parcels.parcel(i);
			append(parcel.x(), parcel.y(), parcel.z(), parcel.solidVolumeSvq());
		}
	}

	GranularSpawnAdmission(int sizeX, int sizeY, int sizeZ, float h, int capacity) {
		this.sizeX = sizeX; this.sizeY = sizeY; this.sizeZ = sizeZ; this.h = h;
		head = new int[Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ)];
		Arrays.fill(head, -1);
		next = new int[capacity]; x = new float[capacity]; y = new float[capacity];
		z = new float[capacity]; radius = new float[capacity];
	}

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
					for (int i = head[(iz * sizeY + iy) * sizeX + ix]; i >= 0; i = next[i]) {
						visit.run();
						if (interiorsOverlap(px, py, pz, r, x[i], y[i], z[i], radius[i])) return i;
					}
		return -1;
	}

	void append(float px, float py, float pz, long svq) {
		int cell = (coordinate(pz, sizeZ) * sizeY + coordinate(py, sizeY)) * sizeX + coordinate(px, sizeX);
		x[count] = px; y[count] = py; z[count] = pz;
		radius[count] = GranularMechanics.representativeRadius(svq, h);
		largestRadius = Math.max(largestRadius, radius[count]);
		next[count] = head[cell]; head[cell] = count++;
	}

	/** Strict sphere intersection; outward arithmetic intervals defer only ambiguous cases to exact dyadics. */
	static boolean interiorsOverlap(double x, double y, double z, double r,
	                                double ox, double oy, double oz, double otherRadius) {
		double dx = Math.abs(x - ox), dy = Math.abs(y - oy), dz = Math.abs(z - oz), sum = r + otherRadius;
		double distanceLower = Math.nextDown(Math.nextDown(squareLower(dx) + squareLower(dy)) + squareLower(dz));
		double distanceUpper = Math.nextUp(Math.nextUp(squareUpper(dx) + squareUpper(dy)) + squareUpper(dz));
		if (distanceLower >= squareUpper(sum)) return false;
		if (distanceUpper < squareLower(sum)) return true;
		var ex = SignedDyadic.of(x).subtract(SignedDyadic.of(ox));
		var ey = SignedDyadic.of(y).subtract(SignedDyadic.of(oy));
		var ez = SignedDyadic.of(z).subtract(SignedDyadic.of(oz));
		var er = SignedDyadic.of(r).add(SignedDyadic.of(otherRadius));
		return ex.multiply(ex).add(ey.multiply(ey)).add(ez.multiply(ez)).compareTo(er.multiply(er)) < 0;
	}

	private static double squareLower(double rounded) {
		double lower = Math.max(0, Math.nextDown(rounded));
		return Math.max(0, Math.nextDown(lower * lower));
	}
	private static double squareUpper(double rounded) {
		double upper = Math.nextUp(rounded);
		return Math.nextUp(upper * upper);
	}
	private int coordinate(double p, int size) {
		return Math.max(0, Math.min(size - 1, (int) Math.floor(p / (double) h)));
	}
}

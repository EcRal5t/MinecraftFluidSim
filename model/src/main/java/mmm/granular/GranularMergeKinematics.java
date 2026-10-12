package mmm.granular;

import mmm.units.SignedDyadic;

/** A single point retains a pair's angular momentum when their relative orbital motion is zero. */
public final class GranularMergeKinematics {
	private GranularMergeKinematics() { }

	public static boolean preservesAngularMomentum(GranularParcelStore.Parcel first, GranularParcelStore.Parcel second) {
		return preservesAngularMomentum(first, second.x(), second.y(), second.z(),
				second.velocityX(), second.velocityY(), second.velocityZ());
	}

	public static boolean preservesAngularMomentum(GranularParcelStore.Parcel first,
			float x, float y, float z, float vx, float vy, float vz) {
		if (first.x() == x && first.y() == y && first.z() == z
				|| first.velocityX() == vx && first.velocityY() == vy && first.velocityZ() == vz) return true;
		SignedDyadic dx = difference(first.x(), x), dy = difference(first.y(), y), dz = difference(first.z(), z);
		SignedDyadic dvx = difference(first.velocityX(), vx);
		SignedDyadic dvy = difference(first.velocityY(), vy);
		SignedDyadic dvz = difference(first.velocityZ(), vz);
		// The lost term is reducedMass * (deltaPosition cross deltaVelocity); both masses are positive.
		return dy.multiply(dvz).subtract(dz.multiply(dvy)).signum() == 0
				&& dz.multiply(dvx).subtract(dx.multiply(dvz)).signum() == 0
				&& dx.multiply(dvy).subtract(dy.multiply(dvx)).signum() == 0;
	}

	private static SignedDyadic difference(float first, float second) {
		return SignedDyadic.of(first).subtract(SignedDyadic.of(second));
	}
}

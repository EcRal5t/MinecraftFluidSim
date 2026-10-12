package mmm.fluid;

import mmm.units.SignedDyadic;
import java.math.BigInteger;
import java.util.Arrays;

/** Exact local capacity error from float motion endpoints and integer allocation of the solid kernel. */
final class GranularQuantizationError {
	private GranularQuantizationError() { }

	/** Exact signed kernel error in cell-volume units; the joint representation budget rounds once. */
	static SignedDyadic[] budget(FluidDomain domain, float dt) {
		SignedDyadic[] result = new SignedDyadic[domain.cellCount];
		Arrays.fill(result, SignedDyadic.ZERO);
		if (domain.granularState == null || domain.granularState.parcels.count() == 0) return result;
		SignedDyadic[] delta = result.clone();
		for (int index = 0; index < domain.granularState.parcels.count(); index++) {
			var parcel = domain.granularState.parcels.parcel(index);
			var next = domain.granularCollision.previewPosition(index, parcel);
			SignedDyadic[] old = {SignedDyadic.of(parcel.x()), SignedDyadic.of(parcel.y()), SignedDyadic.of(parcel.z())};
			SignedDyadic[] reference = {reference(parcel.x(), parcel.velocityX(), dt, next.x()),
					reference(parcel.y(), parcel.velocityY(), dt, next.y()), reference(parcel.z(), parcel.velocityZ(), dt, next.z())};
			if (Arrays.equals(old, reference)) continue;
			accumulate(domain, old, -parcel.solidVolumeSvq(), delta);
			accumulate(domain, reference, parcel.solidVolumeSvq(), delta);
		}
		long totalDelta = 0; SignedDyadic referenceTotal = SignedDyadic.ZERO;
		for (int cell = 0; cell < domain.cellCount; cell++) {
			long actual = domain.granularNextCoupling.solidSvq(cell) - domain.granularCoupling.solidSvq(cell);
			totalDelta = Math.addExact(totalDelta, actual); referenceTotal = referenceTotal.add(delta[cell]);
			SignedDyadic residual = SignedDyadic.of(actual, 0).subtract(delta[cell]);
			result[cell] = new SignedDyadic(residual.significand(), residual.exponent() - 24);
		}
		if (totalDelta != 0 || !referenceTotal.equals(SignedDyadic.ZERO)) {
			throw new IllegalStateException("Granular motion changed total skeleton occupancy");
		}
		return result;
	}

	private static SignedDyadic reference(float position, float velocity, float dt, float next) {
		float rounded = position + velocity * dt;
		// Collision and capacity responses change physical motion; their displacement stays outside this budget.
		return next == rounded ? SignedDyadic.of(position).add(multiply(SignedDyadic.of(velocity), SignedDyadic.of(dt)))
				: SignedDyadic.of(next);
	}

	private static void accumulate(FluidDomain domain, SignedDyadic[] position, long signedSvq, SignedDyadic[] delta) {
		// Granular binding fixes h=0.5 m. Domain-edge normalization cancels the single surviving factor on each clipped axis.
		int[] base = new int[3], size = {domain.sizeX, domain.sizeY, domain.sizeZ};
		SignedDyadic[][] weight = new SignedDyadic[3][2];
		for (int axis = 0; axis < 3; axis++) {
			SignedDyadic grid = new SignedDyadic(position[axis].significand(), position[axis].exponent() + 1)
					.subtract(SignedDyadic.of(.5));
			BigInteger integer = grid.exponent() >= 0 ? grid.significand().shiftLeft(grid.exponent())
					: grid.significand().shiftRight(-grid.exponent());
			base[axis] = integer.intValueExact();
			SignedDyadic fraction = grid.subtract(SignedDyadic.of(base[axis], 0));
			weight[axis][0] = base[axis] + 1 >= size[axis] ? SignedDyadic.of(1, 0) : SignedDyadic.of(1, 0).subtract(fraction);
			weight[axis][1] = base[axis] < 0 ? SignedDyadic.of(1, 0) : fraction;
		}
		SignedDyadic sum = SignedDyadic.ZERO;
		for (int oz = 0; oz <= 1; oz++) for (int oy = 0; oy <= 1; oy++) for (int ox = 0; ox <= 1; ox++) {
			int x = base[0] + ox, y = base[1] + oy, z = base[2] + oz;
			if (x < 0 || y < 0 || z < 0 || x >= domain.sizeX || y >= domain.sizeY || z >= domain.sizeZ) continue;
			SignedDyadic share = multiply(multiply(weight[0][ox], weight[1][oy]), weight[2][oz]);
			share = multiply(share, SignedDyadic.of(signedSvq, 0)); sum = sum.add(share);
			int cell = domain.cellIndex(x, y, z); delta[cell] = delta[cell].add(share);
		}
		if (!sum.equals(SignedDyadic.of(signedSvq, 0))) throw new IllegalStateException("Granular reference endpoint has no normalized occupancy support");
	}

	private static SignedDyadic multiply(SignedDyadic a, SignedDyadic b) {
		return new SignedDyadic(a.significand().multiply(b.significand()), a.exponent() + b.exponent());
	}
}

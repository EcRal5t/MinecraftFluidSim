package mmm.fluid;

import mmm.units.SignedDyadic;
import mmm.granular.GranularKernel;
import java.util.Arrays;

/** Exact local capacity error from float motion endpoints and integer allocation of the solid kernel. */
final class GranularQuantizationError {
	private GranularQuantizationError() { }

	/** Exact signed kernel error in cell-volume units; the joint representation budget rounds once. */
	static SignedDyadic[] budget(FluidDomain domain, float dt) {
		SignedDyadic[] result = new SignedDyadic[domain.cellCount];
		Arrays.fill(result, SignedDyadic.ZERO);
		if (domain.granularState == null || domain.granularState.parcels.count() == 0) return result;
		var kernel = new GranularKernel(domain.sizeX, domain.sizeY, domain.sizeZ, domain.config.cellSize());
		SignedDyadic[] delta = result.clone();
		for (int index = 0; index < domain.granularState.parcels.count(); index++) {
			var parcel = domain.granularState.parcels.parcel(index);
			var next = domain.granularCollision.previewPosition(index, parcel);
			SignedDyadic[] old = {SignedDyadic.of(parcel.x()), SignedDyadic.of(parcel.y()), SignedDyadic.of(parcel.z())};
			SignedDyadic[] reference = {reference(parcel.x(), parcel.velocityX(), dt, next.x()),
					reference(parcel.y(), parcel.velocityY(), dt, next.y()), reference(parcel.z(), parcel.velocityZ(), dt, next.z())};
			if (Arrays.equals(old, reference)) continue;
			accumulate(kernel, old, -parcel.solidVolumeSvq(), delta);
			accumulate(kernel, reference, parcel.solidVolumeSvq(), delta);
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
		return next == rounded ? SignedDyadic.of(position).add(SignedDyadic.of(velocity).multiply(SignedDyadic.of(dt)))
				: SignedDyadic.of(next);
	}

	private static void accumulate(GranularKernel kernel, SignedDyadic[] position, long signedSvq, SignedDyadic[] delta) {
		kernel.capture(position[0], position[1], position[2], signedSvq);
		for (int slot = 0; slot < kernel.size(); slot++) {
			int cell = kernel.cell(slot); delta[cell] = delta[cell].add(kernel.dyadicQuota(slot));
		}
	}
}

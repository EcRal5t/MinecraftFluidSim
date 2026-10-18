package mmm.fluid;

import mmm.units.SignedDyadic;
import java.util.Arrays;

/** Local volume splitting error between finite-volume pressure flux and the actual integer motion endpoint. */
final class GranularQuantizationError {
	private GranularQuantizationError() { }

	static SignedDyadic[] budget(FluidDomain domain, float dt) {
		SignedDyadic[] result = new SignedDyadic[domain.cellCount];
		Arrays.fill(result, SignedDyadic.ZERO);
		if (domain.granularState == null || domain.granularState.parcels.count() == 0) return result;
		double[] predicted = new double[domain.cellCount];
		int[] cells = new int[32]; double[] gx = new double[32], gy = new double[32], gz = new double[32];
		for (int index = 0; index < domain.granularState.parcels.count(); index++) {
			var parcel = domain.granularState.parcels.parcel(index);
			int count = domain.pressureSystem.solidFaces.gradient(index, cells, gx, gy, gz);
			for (int slot = 0; slot < count; slot++) predicted[cells[slot]] += dt * (gx[slot] * parcel.velocityX()
					+ gy[slot] * parcel.velocityY() + gz[slot] * parcel.velocityZ());
		}
		long totalDelta = 0;
		for (int cell = 0; cell < domain.cellCount; cell++) {
			long actual = domain.granularNextCoupling.solidSvq(cell) - domain.granularCoupling.solidSvq(cell);
			totalDelta = Math.addExact(totalDelta, actual);
			// Capacity is authoritative. Only the measured difference between the numerical face flux
			// and its actual endpoint may be redistributed, with every transferred inventory retained.
			result[cell] = SignedDyadic.of(actual, -24).subtract(SignedDyadic.of(predicted[cell]));
		}
		if (totalDelta != 0) throw new IllegalStateException("Granular motion changed total skeleton occupancy");
		return result;
	}
}

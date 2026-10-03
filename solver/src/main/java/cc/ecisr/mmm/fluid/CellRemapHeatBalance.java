package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.units.SignedDyadic;
import java.util.ArrayList;

/** Completes a caloric reconstruction inside its transported temperature range. */
final class CellRemapHeatBalance {
	private CellRemapHeatBalance() { }

	static void complete(ArrayList<CellCarrierRemapPlan.Marker> markers, int start,
			cc.ecisr.mmm.thermal.LiquidEnergyColumn.CaloricInventory[] caloric,
			double minimum, double maximum, SignedDyadic residual, double inheritedRounding) {
		int largest = start;
		for (int p = start + 1; p < markers.size(); p++) if (markers.get(p).mass() > markers.get(largest).mass()) largest = p;
		var marker = markers.get(largest);
		double corrected = SignedDyadic.of(marker.energy()).add(residual).toDouble();
		double roundingBound = inheritedRounding;
		for (int p = start; p < markers.size(); p++) roundingBound += Math.ulp(markers.get(p).energy());
		double lower = caloric[largest - start].energy(minimum), upper = caloric[largest - start].energy(maximum);
		if (corrected < lower - roundingBound || corrected > upper + roundingBound)
			roundingBound += caloric[largest - start].roundoff(corrected < lower ? minimum : maximum);
		if (corrected >= lower - roundingBound && corrected <= upper + roundingBound) {
			markers.set(largest, marker.withEnergy(corrected));
		} else {
			boolean positive = residual.signum() > 0;
			SignedDyadic amount = positive ? residual : SignedDyadic.ZERO.subtract(residual);
			SignedDyadic[] capacities = new SignedDyadic[markers.size() - start];
			double[] weights = new double[capacities.length]; SignedDyadic available = SignedDyadic.ZERO;
			double rounding = inheritedRounding;
			for (int p = 0; p < capacities.length; p++) {
				var candidate = markers.get(start + p);
				double limit = caloric[p].energy(positive ? maximum : minimum);
				SignedDyadic gap = positive ? SignedDyadic.of(limit).subtract(SignedDyadic.of(candidate.energy()))
						: SignedDyadic.of(candidate.energy()).subtract(SignedDyadic.of(limit));
				capacities[p] = gap.signum() > 0 ? gap : SignedDyadic.ZERO;
				available = available.add(capacities[p]); weights[p] = candidate.mass();
				rounding += Math.ulp(limit) + Math.ulp(candidate.energy());
				rounding += caloric[p].roundoff(positive ? maximum : minimum);
			}
			if (amount.compareTo(available) > 0) {
				if (amount.subtract(available).toDouble() > rounding)
					throw new IllegalStateException("Caloric remap energy exceeds its transported temperature range: cell=" + marker.cell()
							+ " excess=" + amount.subtract(available).toDouble() + " rounding=" + rounding + " temperature=" + minimum + ".." + maximum);
				amount = available;
			}
			var allocated = ExactVolumeAllocation.allocateCapped(amount, capacities, weights);
			for (int p = 0; p < capacities.length; p++) {
				var candidate = markers.get(start + p); SignedDyadic initial = SignedDyadic.of(candidate.energy());
				double value = (positive ? initial.add(allocated[p]) : initial.subtract(allocated[p])).toDouble();
				markers.set(start + p, candidate.withEnergy(value));
			}
		}
		for (int p = start; p < markers.size(); p++) {
			var candidate = markers.get(p);
			caloric[p - start].temperature(candidate.energy());
		}
	}

}

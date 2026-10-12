package mmm.fluid;

import mmm.units.SignedDyadic;

/** Completes a caloric reconstruction inside its transported temperature range. */
final class CellRemapHeatBalance {
	private CellRemapHeatBalance() { }

	static void complete(CellRemapMarkerColumns markers, int start,
			mmm.thermal.LiquidEnergyColumn.CaloricInventory[] caloric,
			double minimum, double maximum, SignedDyadic residual, double inheritedRounding) {
		int largest = start;
		int end = start + caloric.length;
		for (int p = start + 1; p < end; p++) if (markers.mass[p] > markers.mass[largest]) largest = p;
		double corrected = SignedDyadic.of(markers.energy[largest]).add(residual).toDouble();
		double roundingBound = inheritedRounding;
		for (int p = start; p < end; p++) roundingBound += Math.ulp(markers.energy[p]);
		double lower = caloric[largest - start].energy(minimum), upper = caloric[largest - start].energy(maximum);
		if (corrected < lower - roundingBound || corrected > upper + roundingBound)
			roundingBound += caloric[largest - start].roundoff(corrected < lower ? minimum : maximum);
		if (corrected >= lower - roundingBound && corrected <= upper + roundingBound) {
			markers.energy[largest] = corrected;
		} else {
			boolean positive = residual.signum() > 0;
			SignedDyadic amount = positive ? residual : SignedDyadic.ZERO.subtract(residual);
			SignedDyadic[] capacities = new SignedDyadic[caloric.length];
			double[] weights = new double[capacities.length]; SignedDyadic available = SignedDyadic.ZERO;
			double rounding = inheritedRounding;
			for (int p = 0; p < capacities.length; p++) {
				int candidate = start + p;
				double limit = caloric[p].energy(positive ? maximum : minimum);
				SignedDyadic gap = positive ? SignedDyadic.of(limit).subtract(SignedDyadic.of(markers.energy[candidate]))
						: SignedDyadic.of(markers.energy[candidate]).subtract(SignedDyadic.of(limit));
				capacities[p] = gap.signum() > 0 ? gap : SignedDyadic.ZERO;
				available = available.add(capacities[p]); weights[p] = markers.mass[candidate];
				rounding += Math.ulp(limit) + Math.ulp(markers.energy[candidate]);
				rounding += caloric[p].roundoff(positive ? maximum : minimum);
			}
			if (amount.compareTo(available) > 0) {
				if (amount.subtract(available).toDouble() > rounding)
					throw new IllegalStateException("Caloric remap energy exceeds its transported temperature range: cell=" + markers.cell[largest]
							+ " excess=" + amount.subtract(available).toDouble() + " rounding=" + rounding + " temperature=" + minimum + ".." + maximum);
				amount = available;
			}
			var allocated = ExactVolumeAllocation.allocateCapped(amount, capacities, weights);
			for (int p = 0; p < capacities.length; p++) {
				int candidate = start + p; SignedDyadic initial = SignedDyadic.of(markers.energy[candidate]);
				double value = (positive ? initial.add(allocated[p]) : initial.subtract(allocated[p])).toDouble();
				markers.energy[candidate] = value;
			}
		}
		for (int p = start; p < end; p++) {
			caloric[p - start].temperature(markers.energy[p]);
		}
	}

}

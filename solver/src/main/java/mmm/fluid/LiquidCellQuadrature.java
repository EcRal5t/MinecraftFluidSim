package mmm.fluid;

import mmm.units.SignedDyadic;
import java.util.ArrayList;

/** Positive binary32 weights on a common storage lattice, with an exact expansion tail. */
final class LiquidCellQuadrature {
	private LiquidCellQuadrature() { }

	static ArrayList<Float> volumes(SignedDyadic volume, int count) {
		if (count <= 0 || volume.signum() <= 0) throw new IllegalArgumentException("Cell quadrature requires positive volume and support");
		float mean = (float) (volume.toDouble() / count);
		if (!(mean > 0)) throw new IllegalArgumentException("Cell quadrature is below binary32 storage");
		var lattice = SignedDyadic.of(Math.ulp(Math.nextUp(mean)));
		int shift = volume.exponent() - lattice.exponent();
		long units = volume.fitsLongUnits(lattice.exponent()) ? volume.longUnitsExact(lattice.exponent())
				: (shift >= 0 ? volume.significand().shiftLeft(shift) : volume.significand().shiftRight(-shift)).longValueExact();
		long base = units / count, extra = units % count;
		var result = new ArrayList<Float>(count + 1);
		for (int p = 0; p < count; p++) {
			long weight = base + (p < extra ? 1 : 0);
			if (weight == 0) continue;
			float stored = (float) Math.scalb((double) weight, lattice.exponent());
			if (weight > 0x1000000L)
				throw new IllegalStateException("Common cell quadrature lattice exceeds binary32 precision");
			result.add(stored);
		}
		SignedDyadic remaining = volume.subtract(SignedDyadic.of(units, lattice.exponent()));
		while (remaining.signum() > 0) {
			float stored = (float) remaining.toDouble();
			while (SignedDyadic.of(stored).compareTo(remaining) > 0) stored = Math.nextDown(stored);
			if (!(stored > 0)) throw new IllegalStateException("Cell quadrature tail is below binary32 storage");
			result.add(stored); remaining = remaining.subtract(SignedDyadic.of(stored));
		}
		return result;
	}
}

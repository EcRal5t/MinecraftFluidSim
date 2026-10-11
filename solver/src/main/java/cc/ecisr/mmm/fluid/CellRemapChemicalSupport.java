package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.units.SignedDyadic;
import java.util.ArrayList;

/** Positive binary32 volume carriers required by exact dyadic chemical shares. */
final class CellRemapChemicalSupport {
	// Volumes are <=1 and lie on the input binary32 lattice (smallest quantum 2^-149).
	// Each normal extraction clears its leading 24-bit window; a subnormal remainder is exact.
	// Thus seven positive carriers suffice for this 149-bit input range.
	static final int MAX_EXPANSION = 7;
	// A component can meet at most four micro patches on each of six faces, plus retention.
	static final int MAX_COMPONENT_SHARES = 25;
	private CellRemapChemicalSupport() { }

	static ArrayList<Float> expansion(SignedDyadic volume) {
		var result = new ArrayList<Float>(MAX_EXPANSION);
		count(volume, result); return result;
	}
	private static int count(SignedDyadic volume, ArrayList<Float> expansion) {
		if (volume.signum() < 0) throw new IllegalArgumentException("Negative chemical volume share");
		int count = 0;
		while (volume.signum() > 0) {
			float value = (float) volume.toDouble();
			if (SignedDyadic.of(value).compareTo(volume) > 0) value = Math.nextDown(value);
			if (!(Float.isFinite(value) && value > 0)) throw new IllegalStateException("Chemical volume has no positive binary32 carrier");
			if (expansion != null) expansion.add(value); volume = volume.subtract(SignedDyadic.of(value));
			if (++count > MAX_EXPANSION) throw new IllegalStateException("Chemical volume left its input binary32 lattice");
		}
		return count;
	}
	static int[] required(SignedDyadic[] volumes) {
		int[] result = new int[volumes.length];
		for (int i = 0; i < result.length; i++) result[i] = count(volumes[i], null);
		return result;
	}
	static boolean fits(long quanta, SignedDyadic[] volumes) {
		long required = 0;
		for (SignedDyadic volume : volumes) required += count(volume, null);
		return required <= quanta;
	}
}

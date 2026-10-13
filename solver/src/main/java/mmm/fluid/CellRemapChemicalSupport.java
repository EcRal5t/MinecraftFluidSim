package mmm.fluid;

import mmm.units.SignedDyadic;
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
	/** Positive storage nodes with the actual geometric minimum and a finite species budget. */
	static ArrayList<Float> quadrature(SignedDyadic volume, int preferredCount, int minimumCount, long quanta) {
		requireMinimum(volume, minimumCount);
		if (preferredCount < 1 || quanta < 0) throw new IllegalArgumentException("Invalid chemical quadrature budget");
		if (quanta < minimumCount) throw new Unavailable("Chemical quanta cannot support the required positive nodes");
		int count = (int) Math.min(Math.max(preferredCount, minimumCount), quanta);
		for (; count >= minimumCount; count--) {
			ArrayList<Float> result;
			try { result = LiquidCellQuadrature.volumes(volume, count); }
			catch (IllegalArgumentException | IllegalStateException unrepresentable) { continue; }
			if (result.size() >= minimumCount && result.size() <= quanta && exactPositive(volume, result)) return result;
		}
		var result = positiveMinimum(volume, minimumCount);
		if (result.size() > quanta) throw new Unavailable("Chemical quadrature expansion exceeds its species budget");
		return result;
	}
	static int minimumCarriers(SignedDyadic volume, int minimumCount) {
		requireMinimum(volume, minimumCount);
		return positiveMinimum(volume, minimumCount).size();
	}
	private static ArrayList<Float> positiveMinimum(SignedDyadic volume, int minimumCount) {
		ArrayList<Float> result;
		try { result = expansion(volume); }
		catch (IllegalStateException unrepresentable) { throw new Unavailable(unrepresentable.getMessage()); }
		while (result.size() < minimumCount) {
			int largest = 0;
			for (int i = 1; i < result.size(); i++) if (result.get(i) > result.get(largest)) largest = i;
			float value = result.get(largest), first = (float) ((double) value * .5), second = value - first;
			if (!(first > 0 && second > 0)
					|| !SignedDyadic.of(first).add(SignedDyadic.of(second)).equals(SignedDyadic.of(value)))
				throw new Unavailable("Required positive nodes exceed the binary32 volume lattice");
			result.set(largest, first); result.add(second);
		}
		if (!exactPositive(volume, result)) throw new Unavailable("Chemical positive sampling changed its exact volume");
		return result;
	}
	private static boolean exactPositive(SignedDyadic volume, ArrayList<Float> nodes) {
		SignedDyadic total = SignedDyadic.ZERO;
		for (float node : nodes) {
			if (!(node > 0 && Float.isFinite(node))) return false;
			total = total.add(SignedDyadic.of(node));
		}
		return total.equals(volume);
	}
	private static void requireMinimum(SignedDyadic volume, int minimumCount) {
		if (volume == null || volume.signum() <= 0 || minimumCount < 1)
			throw new IllegalArgumentException("Chemical sampling requires positive volume and support");
	}
	static final class Unavailable extends RuntimeException {
		Unavailable(String message) { super(message); }
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

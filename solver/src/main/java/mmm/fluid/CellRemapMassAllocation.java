package mmm.fluid;

import mmm.units.Mass;
import mmm.units.SignedDyadic;

/** Allocates integer mass stock; the largest volume retains integer division remainders. */
final class CellRemapMassAllocation {
	private CellRemapMassAllocation() { }
	static long[] allocate(long massUnits, SignedDyadic volume, SignedDyadic[] weights) {
		long[] result = new long[weights.length];
		int largest = largest(weights);
		long assigned = 0;
		for (int i = 0; i < result.length; i++) if (i != largest && weights[i].signum() > 0) {
			result[i] = Mass.portion(massUnits, weights[i], volume);
			assigned = Math.addExact(assigned, result[i]);
		}
		result[largest] = Math.subtractExact(massUnits, assigned);
		for (int i = 0; i < result.length; i++) if (weights[i].signum() > 0 && result[i] <= 0)
			throw new IllegalArgumentException("Positive liquid share is below mass resolution");
		return result;
	}
	static int largest(SignedDyadic[] weights) {
		int largest = 0;
		for (int i = 1; i < weights.length; i++) if (weights[i].compareTo(weights[largest]) > 0) largest = i;
		return largest;
	}
}

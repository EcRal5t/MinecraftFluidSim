package cc.ecisr.mmm.thermal;

import cc.ecisr.mmm.chemistry.ChemicalLimits;

import java.util.concurrent.atomic.AtomicReferenceArray;

/** Bounded derived species terms shared by all callers of one thermal state function. */
final class ThermalReferenceEnergyCache {
	private static final int CAPACITY = 16_384;
	private final AtomicReferenceArray<Entry> entries = new AtomicReferenceArray<>(CAPACITY);
	private record Entry(ThermalSpeciesOffset offset, long quanta, double energy) { }

	double energy(ThermalSpeciesOffset offset, long quanta) {
		if (offset.referenceInternalEnergyEuPerAu().isZero()) return 0;
		long hash = quanta ^ ((long) System.identityHashCode(offset) << 32);
		hash = (hash ^ (hash >>> 30)) * 0xbf58476d1ce4e5b9L;
		hash = (hash ^ (hash >>> 27)) * 0x94d049bb133111ebL;
		int slot = (int) (hash ^ (hash >>> 31)) & (CAPACITY - 1);
		Entry entry = entries.get(slot);
		if (entry != null && entry.offset() == offset && entry.quanta() == quanta) return entry.energy();
		double energy = exactEnergy(offset, quanta);
		entries.set(slot, new Entry(offset, quanta, energy));
		return energy;
	}
	private static double exactEnergy(ThermalSpeciesOffset offset, long quanta) {
		var rational = offset.referenceInternalEnergyEuPerAu();
		if (rational.numerator().bitLength() < 64 && rational.denominator().bitLength() < 64) {
			try {
				long numerator = Math.multiplyExact(rational.numerator().longValueExact(), quanta);
				long denominator = Math.multiplyExact(rational.denominator().longValueExact(), ChemicalLimits.QUANTA_PER_AU);
				if (numerator != Long.MIN_VALUE) {
					long a = Math.abs(numerator), b = denominator;
					while (b != 0) { long remainder = a % b; a = b; b = remainder; }
					return (double) (numerator / a) / (double) (denominator / a);
				}
			} catch (ArithmeticException wide) { /* Retain the bounded exact-rational calculation below. */ }
		}
		return rational.multiply(quanta).divide(ChemicalLimits.QUANTA_PER_AU).toDouble();
	}

}

package cc.ecisr.mmm.thermal;

import cc.ecisr.mmm.chemistry.ChemicalLimits;

import java.util.concurrent.atomic.AtomicReferenceArray;

/** Bounded derived species terms shared by all callers of one thermal state function. */
final class ThermalReferenceEnergyCache {
	private static final int CAPACITY = 16_384;
	private final AtomicReferenceArray<Entry> entries = new AtomicReferenceArray<>(CAPACITY);
	private record Entry(ThermalSpeciesOffset offset, long quanta, double energy) { }

	double energy(ThermalSpeciesOffset offset, long quanta) {
		long hash = quanta ^ ((long) offset.id().hashCode() << 32);
		hash = (hash ^ (hash >>> 30)) * 0xbf58476d1ce4e5b9L;
		hash = (hash ^ (hash >>> 27)) * 0x94d049bb133111ebL;
		int slot = (int) (hash ^ (hash >>> 31)) & (CAPACITY - 1);
		Entry entry = entries.get(slot);
		if (entry != null && entry.offset() == offset && entry.quanta() == quanta) return entry.energy();
		double energy = offset.referenceInternalEnergyEuPerAu()
				.multiply(quanta).divide(ChemicalLimits.QUANTA_PER_AU).toDouble();
		entries.set(slot, new Entry(offset, quanta, energy));
		return energy;
	}
}

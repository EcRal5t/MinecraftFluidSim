package com.example.granular;

import com.example.chemistry.ChemicalRegistry;

import java.util.Arrays;

/** Fixed-budget primitive pool. Every reactive parcel/deposit group owns one unique live handle. */
public final class SolidCompositionPool {
	private final ChemicalRegistry registry;
	private final byte[] entryCount;
	private final short[] species;
	private final long[] amounts, residualBits;
	private final int[] freeHandles;
	private int freeCount, liveCount;

	public SolidCompositionPool(ChemicalRegistry registry, int capacity) {
		if (registry == null) throw new IllegalArgumentException("Chemical registry is missing");
		if (capacity <= 0 || capacity > GranularLimits.MAX_ACTIVE_PARCELS_PER_REGION
				+ GranularLimits.MAX_DEPOSIT_GROUPS_PER_REGION) {
			throw new IllegalArgumentException("Solid composition capacity is invalid");
		}
		this.registry = registry;
		int handleCapacity = Math.addExact(capacity, 1);
		entryCount = new byte[handleCapacity];
		species = new short[Math.multiplyExact(handleCapacity, GranularLimits.MAX_SOLID_COMPOSITION_ENTRIES)];
		amounts = new long[species.length]; residualBits = new long[handleCapacity]; freeHandles = new int[capacity];
		for (int handle = capacity; handle >= 1; handle--) freeHandles[freeCount++] = handle;
	}

	public ChemicalRegistry registry() { return registry; }
	public int liveCount() { return liveCount; }
	public boolean hasCapacity(int additional) { return additional >= 0 && additional <= freeCount; }
	public boolean isLive(int handle) { return handle > 0 && handle < entryCount.length && entryCount[handle] != 0; }

	public int create(SolidCompositionRecord record) {
		validate(record);
		if (freeCount == 0) throw new BudgetExceededException("Solid composition handle budget exceeded");
		int handle = freeHandles[--freeCount]; write(handle, record); liveCount++; return handle;
	}

	public SolidCompositionRecord record(int handle) {
		requireLive(handle);
		int count = Byte.toUnsignedInt(entryCount[handle]), base = handle * GranularLimits.MAX_SOLID_COMPOSITION_ENTRIES;
		int[] recordSpecies = new int[count]; long[] recordAmounts = new long[count];
		for (int entry = 0; entry < count; entry++) {
			recordSpecies[entry] = Short.toUnsignedInt(species[base + entry]); recordAmounts[entry] = amounts[base + entry];
		}
		return new SolidCompositionRecord(recordSpecies, recordAmounts, residualBits[handle]);
	}

	public void overwrite(int handle, SolidCompositionRecord record) {
		requireLive(handle); validate(record);
		int base = handle * GranularLimits.MAX_SOLID_COMPOSITION_ENTRIES;
		int previous = Byte.toUnsignedInt(entryCount[handle]);
		if (previous > record.entryCount()) {
			Arrays.fill(species, base + record.entryCount(), base + previous, (short) 0);
			Arrays.fill(amounts, base + record.entryCount(), base + previous, 0L);
		}
		write(handle, record);
	}

	public void release(int handle) {
		requireLive(handle);
		int count = Byte.toUnsignedInt(entryCount[handle]), base = handle * GranularLimits.MAX_SOLID_COMPOSITION_ENTRIES;
		Arrays.fill(species, base, base + count, (short) 0); Arrays.fill(amounts, base, base + count, 0L);
		entryCount[handle] = 0; residualBits[handle] = 0L; freeHandles[freeCount++] = handle; liveCount--;
	}

	public long[] speciesTotals() {
		long[] totals = new long[registry.species().size()];
		for (int handle = 1; handle < entryCount.length; handle++) if (isLive(handle)) {
			SolidCompositionRecord record = record(handle);
			for (int entry = 0; entry < record.entryCount(); entry++) totals[record.speciesHandle(entry)] = Math.addExact(
					totals[record.speciesHandle(entry)], record.amountAt(entry));
		}
		return totals;
	}

	private void validate(SolidCompositionRecord record) {
		if (record == null) throw new IllegalArgumentException("Solid composition is missing");
		for (int entry = 0; entry < record.entryCount(); entry++) registry.species(record.speciesHandle(entry));
		if (!(Double.isFinite(record.totalMass(registry)) && record.totalMass(registry) > 0.0)) {
			throw new IllegalArgumentException("Solid composition mass is non-positive");
		}
	}

	private void write(int handle, SolidCompositionRecord record) {
		int base = handle * GranularLimits.MAX_SOLID_COMPOSITION_ENTRIES;
		for (int entry = 0; entry < record.entryCount(); entry++) {
			species[base + entry] = (short) record.speciesHandle(entry); amounts[base + entry] = record.amountAt(entry);
		}
		residualBits[handle] = record.massResidualBits(); entryCount[handle] = (byte) record.entryCount();
	}

	private void requireLive(int handle) {
		if (!isLive(handle)) throw new IllegalArgumentException("Unknown or released solid composition handle " + handle);
	}

	public static final class BudgetExceededException extends IllegalStateException {
		public BudgetExceededException(String message) { super(message); }
	}
}

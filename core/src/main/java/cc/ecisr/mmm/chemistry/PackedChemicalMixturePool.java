package cc.ecisr.mmm.chemistry;

import java.util.Arrays;

/** Primitive fixed-stride packed records. Every live marker owns one unique nonzero handle. */
public final class PackedChemicalMixturePool {
	private final int handleCapacity;
	private final byte[] entryCount;
	private final short[] species;
	private final long[] amounts;
	private final long[] residualBits;
	private final int[] freeHandles;
	private int freeCount;
	private int liveCount;

	public PackedChemicalMixturePool(int reservedMarkerCapacity) {
		if (reservedMarkerCapacity <= 0) throw new IllegalArgumentException("Marker capacity must be positive");
		this.handleCapacity = Math.addExact(reservedMarkerCapacity, 1);
		this.entryCount = new byte[handleCapacity];
		this.species = new short[Math.multiplyExact(handleCapacity, ChemicalLimits.MAX_SPECIES_PER_PARCEL)];
		this.amounts = new long[species.length];
		this.residualBits = new long[handleCapacity];
		this.freeHandles = new int[reservedMarkerCapacity];
		for (int handle = reservedMarkerCapacity; handle >= 1; handle--) freeHandles[freeCount++] = handle;
	}

	public int create(ChemicalMixtureRecord record) {
		if (freeCount == 0) throw new PoolBudgetExceededException("Live mixture handle budget exceeded");
		int handle = freeHandles[--freeCount];
		write(handle, record);
		liveCount++;
		return handle;
	}

	public void overwrite(int handle, ChemicalMixtureRecord record) {
		requireLive(handle);
		write(handle, record);
	}

	public void release(int handle) {
		requireLive(handle);
		int base = handle * ChemicalLimits.MAX_SPECIES_PER_PARCEL;
		int count = Byte.toUnsignedInt(entryCount[handle]);
		Arrays.fill(species, base, base + count, (short) 0);
		Arrays.fill(amounts, base, base + count, 0L);
		entryCount[handle] = 0;
		residualBits[handle] = 0L;
		freeHandles[freeCount++] = handle;
		liveCount--;
	}

	public ChemicalMixtureRecord record(int handle) {
		requireLive(handle);
		int count = Byte.toUnsignedInt(entryCount[handle]);
		int[] recordSpecies = new int[count];
		long[] recordAmounts = new long[count];
		int base = handle * ChemicalLimits.MAX_SPECIES_PER_PARCEL;
		for (int entry = 0; entry < count; entry++) {
			recordSpecies[entry] = Short.toUnsignedInt(species[base + entry]);
			recordAmounts[entry] = amounts[base + entry];
		}
		return new ChemicalMixtureRecord(recordSpecies, recordAmounts, residualBits[handle]);
	}

	public boolean isLive(int handle) {
		return handle > 0 && handle < handleCapacity && entryCount[handle] != 0;
	}
	public int liveCount() { return liveCount; }
	public int handleCapacity() { return handleCapacity; }
	public int packedEntryCapacity() { return species.length; }
	public long cpuBytes() {
		return entryCount.length + (long) species.length * Short.BYTES + (long) amounts.length * Long.BYTES
				+ (long) residualBits.length * Long.BYTES + (long) freeHandles.length * Integer.BYTES;
	}

	private void write(int handle, ChemicalMixtureRecord record) {
		if (record == null) throw new IllegalArgumentException("Chemical mixture is missing");
		int base = handle * ChemicalLimits.MAX_SPECIES_PER_PARCEL;
		int previous = Byte.toUnsignedInt(entryCount[handle]);
		for (int entry = 0; entry < record.entryCount(); entry++) {
			int speciesHandle = record.speciesHandle(entry);
			if (speciesHandle >= ChemicalLimits.MAX_SPECIES) throw new IllegalArgumentException("Species handle is over budget");
			species[base + entry] = (short) speciesHandle;
			amounts[base + entry] = record.amountAt(entry);
		}
		if (previous > record.entryCount()) {
			Arrays.fill(species, base + record.entryCount(), base + previous, (short) 0);
			Arrays.fill(amounts, base + record.entryCount(), base + previous, 0L);
		}
		residualBits[handle] = record.massResidualBits();
		entryCount[handle] = (byte) record.entryCount();
	}

	private void requireLive(int handle) {
		if (!isLive(handle)) throw new IllegalArgumentException("Unknown or released mixture handle " + handle);
	}

	public static final class PoolBudgetExceededException extends IllegalStateException {
		public PoolBudgetExceededException(String message) { super(message); }
	}
}

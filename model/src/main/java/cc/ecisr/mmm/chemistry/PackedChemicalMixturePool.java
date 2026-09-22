package cc.ecisr.mmm.chemistry;

import java.util.Arrays;

/** Primitive fixed-stride packed records. Every live marker owns one unique nonzero handle. */
public final class PackedChemicalMixturePool {
	private final int handleCapacity;
	private byte[] entryCount;
	private short[] species;
	private long[] amounts;
	private long[] residualBits;
	private ChemicalMixtureRecord[] records;
	private int[] freeHandles;
	private int nextHandle = 1;
	private int freeCount;
	private int liveCount;

	public PackedChemicalMixturePool(int reservedMarkerCapacity) {
		if (reservedMarkerCapacity <= 0) throw new IllegalArgumentException("Marker capacity must be positive");
		this.handleCapacity = Math.addExact(reservedMarkerCapacity, 1);
		Math.multiplyExact(handleCapacity, ChemicalLimits.MAX_SPECIES_PER_PARCEL);
		int allocated = Math.min(handleCapacity, 65);
		this.entryCount = new byte[allocated];
		this.species = new short[allocated * ChemicalLimits.MAX_SPECIES_PER_PARCEL];
		this.amounts = new long[species.length];
		this.residualBits = new long[allocated];
		this.records = new ChemicalMixtureRecord[allocated];
		this.freeHandles = new int[allocated - 1];
	}

	public int create(ChemicalMixtureRecord record) {
		validate(record);
		if (liveCount == handleCapacity - 1) throw new PoolBudgetExceededException("Live mixture handle budget exceeded");
		int handle = freeCount > 0 ? freeHandles[--freeCount] : nextHandle++;
		ensureAllocated(handle);
		write(handle, record);
		liveCount++;
		return handle;
	}

	public void overwrite(int handle, ChemicalMixtureRecord record) {
		requireLive(handle);
		validate(record);
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
		records[handle] = null;
		freeHandles[freeCount++] = handle;
		liveCount--;
	}

	public ChemicalMixtureRecord record(int handle) {
		requireLive(handle);
		ChemicalMixtureRecord cached = records[handle];
		if (cached != null) return cached;
		int count = Byte.toUnsignedInt(entryCount[handle]);
		int[] recordSpecies = new int[count];
		long[] recordAmounts = new long[count];
		int base = handle * ChemicalLimits.MAX_SPECIES_PER_PARCEL;
		for (int entry = 0; entry < count; entry++) {
			recordSpecies[entry] = Short.toUnsignedInt(species[base + entry]);
			recordAmounts[entry] = amounts[base + entry];
		}
		return records[handle] = new ChemicalMixtureRecord(recordSpecies, recordAmounts, residualBits[handle]);
	}

	public boolean isLive(int handle) {
		return handle > 0 && handle < entryCount.length && entryCount[handle] != 0;
	}
	public int liveCount() { return liveCount; }
	public int handleCapacity() { return handleCapacity; }
	public int packedEntryCapacity() { return handleCapacity * ChemicalLimits.MAX_SPECIES_PER_PARCEL; }
	public long cpuBytes() {
		return entryCount.length + (long) species.length * Short.BYTES + (long) amounts.length * Long.BYTES
				+ (long) residualBits.length * Long.BYTES + (long) records.length * Long.BYTES
				+ (long) freeHandles.length * Integer.BYTES;
	}

	private static void validate(ChemicalMixtureRecord record) {
		if (record == null) throw new IllegalArgumentException("Chemical mixture is missing");
		for (int entry = 0; entry < record.entryCount(); entry++) {
			if (record.speciesHandle(entry) >= ChemicalLimits.MAX_SPECIES) {
				throw new IllegalArgumentException("Species handle is over budget");
			}
		}
	}

	private void ensureAllocated(int handle) {
		if (handle < entryCount.length) return;
		int capacity = (int) Math.min(handleCapacity, (long) entryCount.length * 2);
		entryCount = Arrays.copyOf(entryCount, capacity);
		species = Arrays.copyOf(species, capacity * ChemicalLimits.MAX_SPECIES_PER_PARCEL);
		amounts = Arrays.copyOf(amounts, species.length);
		residualBits = Arrays.copyOf(residualBits, capacity);
		records = Arrays.copyOf(records, capacity);
		freeHandles = Arrays.copyOf(freeHandles, capacity - 1);
	}

	private void write(int handle, ChemicalMixtureRecord record) {
		int base = handle * ChemicalLimits.MAX_SPECIES_PER_PARCEL;
		int previous = Byte.toUnsignedInt(entryCount[handle]);
		for (int entry = 0; entry < record.entryCount(); entry++) {
			int speciesHandle = record.speciesHandle(entry);
			species[base + entry] = (short) speciesHandle;
			amounts[base + entry] = record.amountAt(entry);
		}
		if (previous > record.entryCount()) {
			Arrays.fill(species, base + record.entryCount(), base + previous, (short) 0);
			Arrays.fill(amounts, base + record.entryCount(), base + previous, 0L);
		}
		residualBits[handle] = record.massResidualKilogramsBits();
		entryCount[handle] = (byte) record.entryCount();
		records[handle] = record;
	}

	private void requireLive(int handle) {
		if (!isLive(handle)) throw new IllegalArgumentException("Unknown or released mixture handle " + handle);
	}

	public static final class PoolBudgetExceededException extends IllegalStateException {
		public PoolBudgetExceededException(String message) { super(message); }
	}
}

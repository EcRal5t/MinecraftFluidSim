package cc.ecisr.mmm.chemistry;

import java.util.Arrays;

/** Bounded marker ownership of immutable compositions; every live marker has a unique handle. */
public final class ChemicalMixturePool {
	private final int handleCapacity;
	private ChemicalMixtureRecord[] records;
	private int[] freeHandles;
	private int nextHandle = 1;
	private int freeCount;
	private int liveCount;

	public ChemicalMixturePool(int reservedMarkerCapacity) {
		if (reservedMarkerCapacity <= 0) throw new IllegalArgumentException("Marker capacity must be positive");
		this.handleCapacity = Math.addExact(reservedMarkerCapacity, 1);
		Math.multiplyExact(handleCapacity, ChemicalLimits.MAX_SPECIES_PER_PARCEL);
		int allocated = Math.min(handleCapacity, 65);
		this.records = new ChemicalMixtureRecord[allocated];
		this.freeHandles = new int[allocated - 1];
	}

	/** Reserve a known restore inventory once, retaining the same hard handle budget. */
	public void reserveRecords(int count) {
		if (count < 0 || count >= handleCapacity) throw new IllegalArgumentException("Mixture reserve exceeds budget");
		ensureAllocated(count);
	}

	public int create(ChemicalMixtureRecord record) {
		validate(record);
		if (liveCount == handleCapacity - 1) throw new PoolBudgetExceededException("Live mixture handle budget exceeded");
		int handle = freeCount > 0 ? freeHandles[--freeCount] : nextHandle++;
		ensureAllocated(handle);
		records[handle] = record;
		liveCount++;
		return handle;
	}

	public void overwrite(int handle, ChemicalMixtureRecord record) {
		requireLive(handle);
		validate(record);
		records[handle] = record;
	}

	public void release(int handle) {
		requireLive(handle);
		records[handle] = null;
		freeHandles[freeCount++] = handle;
		liveCount--;
	}

	public ChemicalMixtureRecord record(int handle) {
		requireLive(handle);
		return records[handle];
	}

	public boolean isLive(int handle) {
		return handle > 0 && handle < records.length && records[handle] != null;
	}
	public int liveCount() { return liveCount; }
	public int handleCapacity() { return handleCapacity; }
	public int speciesEntryCapacity() { return handleCapacity * ChemicalLimits.MAX_SPECIES_PER_PARCEL; }
	/** Array payload estimate; counts shared immutable composition payload once per live handle. */
	public long cpuBytes() {
		long result = (long) records.length * Long.BYTES + (long) freeHandles.length * Integer.BYTES;
		for (ChemicalMixtureRecord record : records) if (record != null)
			result += (long) record.entryCount() * (Integer.BYTES + Long.BYTES) + Long.BYTES;
		return result;
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
		if (handle < records.length) return;
		int capacity = (int) Math.min(handleCapacity, Math.max((long) handle + 1, (long) records.length * 2));
		records = Arrays.copyOf(records, capacity);
		freeHandles = Arrays.copyOf(freeHandles, capacity - 1);
	}

	private void requireLive(int handle) {
		if (!isLive(handle)) throw new IllegalArgumentException("Unknown or released mixture handle " + handle);
	}

	public static final class PoolBudgetExceededException extends IllegalStateException {
		public PoolBudgetExceededException(String message) { super(message); }
	}
}

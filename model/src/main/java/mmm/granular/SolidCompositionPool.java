package mmm.granular;

import mmm.chemistry.ChemicalRegistry;

import java.util.Arrays;

/** Fixed-budget primitive pool. Every reactive parcel/deposit group owns one unique live handle. */
public final class SolidCompositionPool {
	private final ChemicalRegistry registry;
	private final int capacity;
	private byte[] entryCount;
	private short[] species;
	private long[] amounts, residualUnits;
	private int[] freeHandles;
	private int nextHandle = 1;
	private int freeCount, liveCount;
	private long mutationRevision;

	private SolidCompositionPool(SolidCompositionPool source) {
		this.registry=source.registry;
		this.capacity=source.capacity;
		this.entryCount=source.entryCount.clone();
		this.species=source.species.clone();
		this.amounts=source.amounts.clone();
		this.residualUnits=source.residualUnits.clone();
		this.freeHandles=source.freeHandles.clone();
		this.nextHandle=source.nextHandle;
		this.freeCount=source.freeCount;
		this.liveCount=source.liveCount;
		mutationRevision=source.mutationRevision;
	}
	/** One private candidate copy for a complete inventory transaction. */
	public SolidCompositionPool copy() { return new SolidCompositionPool(this); }
	public void requirePrepared(SolidCompositionPool prepared,long sourceRevision) {
		if(prepared==null||prepared==this||mutationRevision!=sourceRevision||prepared.registry!=registry||prepared.capacity!=capacity)
			throw new IllegalStateException("Prepared SolidCompositionPool differs from its source generation");
	}
	/** Exchanges validated columns; the candidate receives the detached old inventory. */
	public void adoptPrepared(SolidCompositionPool prepared,long sourceRevision) {
		requirePrepared(prepared,sourceRevision);
		var oldentryCount=entryCount;entryCount=prepared.entryCount;prepared.entryCount=oldentryCount;
		var oldspecies=species;species=prepared.species;prepared.species=oldspecies;
		var oldamounts=amounts;amounts=prepared.amounts;prepared.amounts=oldamounts;
		var oldresidualUnits=residualUnits;residualUnits=prepared.residualUnits;prepared.residualUnits=oldresidualUnits;
		var oldfreeHandles=freeHandles;freeHandles=prepared.freeHandles;prepared.freeHandles=oldfreeHandles;
		var oldnextHandle=nextHandle;nextHandle=prepared.nextHandle;prepared.nextHandle=oldnextHandle;
		var oldfreeCount=freeCount;freeCount=prepared.freeCount;prepared.freeCount=oldfreeCount;
		var oldliveCount=liveCount;liveCount=prepared.liveCount;prepared.liveCount=oldliveCount;
		mutationRevision++;
	}

	public SolidCompositionPool(ChemicalRegistry registry, int capacity) {
		if (registry == null) throw new IllegalArgumentException("Chemical registry is missing");
		if (capacity <= 0 || capacity > GranularLimits.MAX_ACTIVE_PARCELS_PER_REGION
				+ GranularLimits.MAX_DEPOSIT_GROUPS_PER_REGION) {
			throw new IllegalArgumentException("Solid composition capacity is invalid");
		}
		this.registry = registry;
		this.capacity = capacity;
		int handleCapacity = Math.min(capacity + 1, 65);
		entryCount = new byte[handleCapacity];
		species = new short[Math.multiplyExact(handleCapacity, GranularLimits.MAX_SOLID_COMPOSITION_ENTRIES)];
		amounts = new long[species.length]; residualUnits = new long[handleCapacity]; freeHandles = new int[handleCapacity - 1];
	}

	public ChemicalRegistry registry() { return registry; }
	public int liveCount() { return liveCount; }
	public long mutationRevision() { return mutationRevision; }
	public boolean hasCapacity(int additional) { return additional >= 0 && additional <= capacity - liveCount; }
	public boolean isLive(int handle) { return handle > 0 && handle < entryCount.length && entryCount[handle] != 0; }

	public int create(SolidCompositionRecord record) {
		validate(record);
		if (!hasCapacity(1)) throw new BudgetExceededException("Solid composition handle budget exceeded");
		int handle = freeCount > 0 ? freeHandles[--freeCount] : nextHandle++;
		ensureAllocated(handle);
		write(handle, record); liveCount++; mutationRevision++; return handle;
	}

	public SolidCompositionRecord record(int handle) {
		requireLive(handle);
		int count = Byte.toUnsignedInt(entryCount[handle]), base = handle * GranularLimits.MAX_SOLID_COMPOSITION_ENTRIES;
		int[] recordSpecies = new int[count]; long[] recordAmounts = new long[count];
		for (int entry = 0; entry < count; entry++) {
			recordSpecies[entry] = Short.toUnsignedInt(species[base + entry]); recordAmounts[entry] = amounts[base + entry];
		}
		return new SolidCompositionRecord(recordSpecies, recordAmounts, residualUnits[handle]);
	}

	public void overwrite(int handle, SolidCompositionRecord record) {
		requireOverwrite(handle, record);
		int base = handle * GranularLimits.MAX_SOLID_COMPOSITION_ENTRIES;
		int previous = Byte.toUnsignedInt(entryCount[handle]);
		if (previous > record.entryCount()) {
			Arrays.fill(species, base + record.entryCount(), base + previous, (short) 0);
			Arrays.fill(amounts, base + record.entryCount(), base + previous, 0L);
		}
		write(handle, record); mutationRevision++;
	}

	/** Pure preflight for an ownership-preserving composition overwrite. */
	public void requireOverwrite(int handle, SolidCompositionRecord record) {
		requireLive(handle); validate(record);
	}

	public void release(int handle) {
		requireLive(handle);
		int count = Byte.toUnsignedInt(entryCount[handle]), base = handle * GranularLimits.MAX_SOLID_COMPOSITION_ENTRIES;
		Arrays.fill(species, base, base + count, (short) 0); Arrays.fill(amounts, base, base + count, 0L);
		entryCount[handle] = 0; residualUnits[handle] = 0L; freeHandles[freeCount++] = handle; liveCount--; mutationRevision++;
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
		if (record.massUnits(registry) <= 0L) {
			throw new IllegalArgumentException("Solid composition mass is non-positive");
		}
	}

	private void ensureAllocated(int handle) {
		if (handle < entryCount.length) return;
		int allocated = Math.min(capacity + 1, entryCount.length * 2);
		entryCount = Arrays.copyOf(entryCount, allocated);
		species = Arrays.copyOf(species, allocated * GranularLimits.MAX_SOLID_COMPOSITION_ENTRIES);
		amounts = Arrays.copyOf(amounts, species.length);
		residualUnits = Arrays.copyOf(residualUnits, allocated);
		freeHandles = Arrays.copyOf(freeHandles, allocated - 1);
	}

	private void write(int handle, SolidCompositionRecord record) {
		int base = handle * GranularLimits.MAX_SOLID_COMPOSITION_ENTRIES;
		for (int entry = 0; entry < record.entryCount(); entry++) {
			species[base + entry] = (short) record.speciesHandle(entry); amounts[base + entry] = record.amountAt(entry);
		}
		residualUnits[handle] = record.massResidualUnits(); entryCount[handle] = (byte) record.entryCount();
	}

	private void requireLive(int handle) {
		if (!isLive(handle)) throw new IllegalArgumentException("Unknown or released solid composition handle " + handle);
	}

	public static final class BudgetExceededException extends IllegalStateException {
		public BudgetExceededException(String message) { super(message); }
	}
}

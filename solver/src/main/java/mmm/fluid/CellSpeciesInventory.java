package mmm.fluid;

import mmm.chemistry.ChemicalLimits;
import mmm.chemistry.ChemicalMixtureRecord;
import java.util.Arrays;

/** Bounded canonical species accumulation during one conservative cell remap. */
final class CellSpeciesInventory {
	private int[] handles = new int[0];
	private long[] amounts = new long[0];
	private int count;
	private long total;
	private ChemicalMixtureRecord record;

	int size() { return count; }
	boolean isEmpty() { return count == 0; }
	int handleAt(int entry) { return handles[entry]; }
	long amountAt(int entry) { return amounts[entry]; }
	long totalQuanta() { return total; }

	boolean compatible(ChemicalMixtureRecord incoming) {
		if (incoming == null) return true;
		int union = count;
		for (int entry = 0; entry < incoming.entryCount(); entry++)
			if (Arrays.binarySearch(handles, 0, count, incoming.speciesHandle(entry)) < 0) union++;
		return union <= ChemicalLimits.MAX_SPECIES_PER_PARCEL;
	}
	void add(ChemicalMixtureRecord incoming) {
		for (int entry = 0; entry < incoming.entryCount(); entry++) add(incoming.speciesHandle(entry), incoming.amountAt(entry));
	}
	private void add(int handle, long amount) {
		int entry = Arrays.binarySearch(handles, 0, count, handle);
		if (entry >= 0) amounts[entry] = Math.addExact(amounts[entry], amount);
		else {
			if (count == ChemicalLimits.MAX_SPECIES_PER_PARCEL) throw new IllegalStateException("Cell remap species union exceeds its parcel representation");
			if (count == handles.length) {
				int capacity = Math.min(ChemicalLimits.MAX_SPECIES_PER_PARCEL, Math.max(4, 2 * count));
				handles = Arrays.copyOf(handles, capacity); amounts = Arrays.copyOf(amounts, capacity);
			}
			entry = -entry - 1;
			System.arraycopy(handles, entry, handles, entry + 1, count - entry);
			System.arraycopy(amounts, entry, amounts, entry + 1, count - entry);
			handles[entry] = handle; amounts[entry] = amount; count++;
		}
		total = Math.addExact(total, amount); record = null;
	}
	boolean sameAmounts(CellSpeciesInventory other) {
		if (count != other.count || total != other.total) return false;
		for (int entry = 0; entry < count; entry++)
			if (handles[entry] != other.handles[entry] || amounts[entry] != other.amounts[entry]) return false;
		return true;
	}
	ChemicalMixtureRecord record() {
		if (count == 0) throw new IllegalStateException("Carrier remap is below the chemical quantum resolution");
		if (record == null) record = new ChemicalMixtureRecord(Arrays.copyOf(handles, count), Arrays.copyOf(amounts, count), 0);
		return record;
	}
}

package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.thermal.SignedDyadic;

import java.util.ArrayList;
import java.util.List;

/** Sparse, canonical exactly-once phase-volume events awaiting the next mechanical projection. */
public final class PendingPhaseVolumeState {
	public record Entry(int cell, SignedDyadic signedCellVolume) {
		public Entry {
			if (cell < 0 || signedCellVolume == null || signedCellVolume.equals(SignedDyadic.ZERO)) {
				throw new IllegalArgumentException("Pending phase-volume entry is invalid");
			}
		}
	}

	private final int cellCount;
	private final List<Entry> entries;

	public PendingPhaseVolumeState(int cellCount, List<Entry> entries) {
		if (cellCount <= 0 || entries == null || entries.size() > cellCount) {
			throw new IllegalArgumentException("Pending phase-volume state shape is invalid");
		}
		ArrayList<Entry> canonical = new ArrayList<>(entries);
		int previous = -1;
		for (Entry entry : canonical) {
			if (entry == null || entry.cell() >= cellCount || entry.cell() <= previous) {
				throw new IllegalArgumentException("Pending phase-volume entries are not canonical");
			}
			previous = entry.cell();
		}
		this.cellCount = cellCount;
		this.entries = List.copyOf(canonical);
	}

	public static PendingPhaseVolumeState capture(SignedDyadic[] dense) {
		if (dense == null || dense.length == 0) {
			throw new IllegalArgumentException("Pending phase-volume source is missing");
		}
		ArrayList<Entry> entries = new ArrayList<>();
		for (int cell = 0; cell < dense.length; cell++) if (!SignedDyadic.ZERO.equals(dense[cell])) {
			entries.add(new Entry(cell, dense[cell]));
		}
		return new PendingPhaseVolumeState(dense.length, entries);
	}

	public int cellCount() { return cellCount; }
	public List<Entry> entries() { return entries; }
	public boolean isEmpty() { return entries.isEmpty(); }

	public SignedDyadic[] toDense() {
		SignedDyadic[] dense = new SignedDyadic[cellCount];
		java.util.Arrays.fill(dense, SignedDyadic.ZERO);
		for (Entry entry : entries) dense[entry.cell()] = entry.signedCellVolume();
		return dense;
	}
}

package mmm.fluid;

import java.util.Arrays;

/**
 * Derived liquid-cell quantities, density and material-phase entries rebuilt from inventory.
 */
final class CellLiquidGrid {
	final int cellCount;
	final int[] stockCount;
	final double[] cellStockVolume;
	mmm.units.SignedDyadic[] exactCellVolumeInventory;
	final double[] cellStockMass;
	final double[] cellDensity;
	final int[] cellPhaseHead;
	int[] phaseEntryLabel;
	int[] phaseEntryNext;
	double[] phaseEntryVolume;
	int phaseEntryCount;
	long inventoryRevision;

	CellLiquidGrid(int cellCount, int stockCapacity) {
		this.cellCount = cellCount;
		this.stockCount = new int[cellCount];
		this.cellStockVolume = new double[cellCount];
		this.cellStockMass = new double[cellCount];
		this.cellDensity = new double[cellCount];
		this.cellPhaseHead = new int[cellCount];
		this.phaseEntryLabel = new int[stockCapacity];
		this.phaseEntryNext = new int[stockCapacity];
		this.phaseEntryVolume = new double[stockCapacity];
	}

	/** Reserves material-phase entries for the current liquid stock count. */
	void ensureStockCapacity(int capacity) {
		if (capacity <= phaseEntryLabel.length) return;
		phaseEntryLabel = Arrays.copyOf(phaseEntryLabel, capacity);
		phaseEntryNext = Arrays.copyOf(phaseEntryNext, capacity);
		phaseEntryVolume = Arrays.copyOf(phaseEntryVolume, capacity);
	}

	void addCellPhaseVolume(int cell, int phase, float amount) {
		for (int entry = cellPhaseHead[cell]; entry >= 0; entry = phaseEntryNext[entry]) {
			if (phaseEntryLabel[entry] != phase) continue;
			phaseEntryVolume[entry] += amount;
			return;
		}
		if (phaseEntryCount >= phaseEntryLabel.length) {
			throw new IllegalStateException("Phase entries exceeded the liquid stock capacity");
		}
		int entry = phaseEntryCount++;
		phaseEntryLabel[entry] = phase;
		phaseEntryVolume[entry] = amount;
		phaseEntryNext[entry] = cellPhaseHead[cell];
		cellPhaseHead[cell] = entry;
	}
}

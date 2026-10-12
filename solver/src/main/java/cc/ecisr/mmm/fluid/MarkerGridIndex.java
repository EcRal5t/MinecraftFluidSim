package cc.ecisr.mmm.fluid;

import java.util.Arrays;

/**
 * Derived per-cell marker index rebuilt from the authoritative marker columns: cell membership
 * links and counts, exact cell volume/mass, reconstructed density and the pressure-phase
 * occupancy entries. Nothing here is checkpoint truth; the whole index is discardable and is
 * rebuilt by {@link MarkerGridBuilder}. Solver-internal package-private access only.
 */
final class MarkerGridIndex {
	final int cellCount;
	final int[] markerCount;
	final int[] cellMarkerHead;
	final int[] cellMarkerTail;
	final double[] exactCellMarkerVolume;
	cc.ecisr.mmm.units.SignedDyadic[] exactCellVolumeInventory;
	final double[] exactCellMarkerMass;
	final double[] cellDensity;
	final int[] cellPhaseHead;
	int[] particleCellNext;
	int[] particleCellPrevious;
	int[] phaseEntryLabel;
	int[] phaseEntryNext;
	double[] phaseEntryVolume;
	int phaseEntryCount;
	long inventoryRevision;

	MarkerGridIndex(int cellCount, int markerCapacity) {
		this.cellCount = cellCount;
		this.markerCount = new int[cellCount];
		this.cellMarkerHead = new int[cellCount];
		this.cellMarkerTail = new int[cellCount];
		this.exactCellMarkerVolume = new double[cellCount];
		this.exactCellMarkerMass = new double[cellCount];
		this.cellDensity = new double[cellCount];
		this.cellPhaseHead = new int[cellCount];
		this.particleCellNext = new int[markerCapacity];
		this.particleCellPrevious = new int[markerCapacity];
		this.phaseEntryLabel = new int[markerCapacity];
		this.phaseEntryNext = new int[markerCapacity];
		this.phaseEntryVolume = new double[markerCapacity];
	}

	/** Grows the per-marker index columns with the same capacity formula as the marker store. */
	void ensureMarkerCapacity(int capacity) {
		if (capacity <= particleCellNext.length) return;
		particleCellNext = Arrays.copyOf(particleCellNext, capacity);
		particleCellPrevious = Arrays.copyOf(particleCellPrevious, capacity);
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
			throw new IllegalStateException("Phase entries exceeded the marker capacity");
		}
		int entry = phaseEntryCount++;
		phaseEntryLabel[entry] = phase;
		phaseEntryVolume[entry] = amount;
		phaseEntryNext[entry] = cellPhaseHead[cell];
		cellPhaseHead[cell] = entry;
	}
}

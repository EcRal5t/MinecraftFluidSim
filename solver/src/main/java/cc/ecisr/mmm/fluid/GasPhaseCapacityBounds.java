package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.granular.SolidVolume;
import cc.ecisr.mmm.units.SignedDyadic;

/** Future volume bounds for the single sealed cavity whose full-air cells remain unchanged. */
final class GasPhaseCapacityBounds {
	record Volume(double low, double high) { }
	private final PhaseChangeStageSupport.Workspace state;
	private final int cell;
	private final boolean singleCavity;
	private final double open, prefixLiquid, cubicMeters;
	private final SignedDyadic oldCellVolume;

	GasPhaseCapacityBounds(PhaseChangeStageSupport support, int cell) {
		this.state = support.state(); this.cell = cell;
		boolean airOwner = false;
		var gas = support.gasState();
		if (gas.sealedDomainBoundary && gas.network.nodeCount() == 1 && gas.cellNodeId != null)
			for (int c = 0; c < state.cellCount(); c++)
				if (gas.cellNodeId[c] == 1 && state.markerGrid().markerCount[c] == 0) { airOwner = true; break; }
		this.singleCavity = airOwner && state.markerGrid().markerCount[cell] > 0;
		if (!singleCavity) {
			this.open = this.prefixLiquid = this.cubicMeters = 0;
			this.oldCellVolume = SignedDyadic.ZERO;
			return;
		}
		double available = 0, prefix = 0;
		for (int c = 0; c < state.cellCount(); c++) {
			if (!state.geometry().solid[c]) available += state.geometry().cutCells.openCellVolume(c);
			if (c < cell) prefix += state.markerGrid().exactCellMarkerVolume[c];
		}
		this.open = available - support.gasGeometrySolidSvq() / (double) SolidVolume.SVQ_PER_CELL;
		this.prefixLiquid = prefix;
		this.cubicMeters = (double) state.cellSize() * state.cellSize() * state.cellSize();
		SignedDyadic volume = SignedDyadic.ZERO;
		for (int marker = state.markerGrid().cellMarkerHead[cell]; marker >= 0;
				marker = state.markerGrid().particleCellNext[marker])
			volume = volume.add(SignedDyadic.of(state.particles().volume(marker)));
		this.oldCellVolume = volume;
	}

	/** The caller proves at least one retained marker throughout this interval. */
	Volume volume(long liquidDeltaLow, long liquidDeltaHigh) {
		if (!singleCavity) return null;
		if (liquidDeltaLow > liquidDeltaHigh) throw new IllegalArgumentException("Phase volume interval is reversed");
		double low = oldCellVolume.add(SignedDyadic.of(liquidDeltaLow, -24)).toDouble();
		double high = oldCellVolume.add(SignedDyadic.of(liquidDeltaHigh, -24)).toDouble();
		if (!(Double.isFinite(low) && Double.isFinite(high) && low >= 0 && high >= low)) return null;
		// Match planGasGeometry's cell RN and normalizeClosedCapacity's ordered regional RN sum.
		double liquidLow = prefixLiquid + low, liquidHigh = prefixLiquid + high;
		for (int c = cell + 1; c < state.cellCount(); c++) {
			liquidLow += state.markerGrid().exactCellMarkerVolume[c];
			liquidHigh += state.markerGrid().exactCellMarkerVolume[c];
		}
		double lower = (open - liquidHigh) * cubicMeters, upper = (open - liquidLow) * cubicMeters;
		if (!(Double.isFinite(lower) && Double.isFinite(upper) && upper > 0)) return null;
		// For one cavity the largest-slot remainder restores the computed global complement.
		// Outward neighbours also cover the divide/multiply/remainder normalization operations.
		return new Volume(Math.max(0, Math.nextDown(Math.nextDown(lower))), Math.nextUp(Math.nextUp(upper)));
	}
}

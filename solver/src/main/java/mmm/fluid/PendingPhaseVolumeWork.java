package mmm.fluid;

import mmm.thermal.ThermalCarrierContext;
import mmm.thermal.ThermalDomainState;
import mmm.units.SignedDyadic;

import java.util.Arrays;

/** Exact phase-volume changes awaiting the next successful mechanical projection. */
final class PendingPhaseVolumeWork {
	private final SignedDyadic[] volume;
	private int pendingCells;

	PendingPhaseVolumeWork(int cellCount) {
		volume = new SignedDyadic[cellCount];
		Arrays.fill(volume, SignedDyadic.ZERO);
	}

	void add(int cell, SignedDyadic delta) {
		SignedDyadic before = volume[cell];
		SignedDyadic after = before.add(delta);
		if (before.signum() == 0 && after.signum() != 0) pendingCells++;
		else if (before.signum() != 0 && after.signum() == 0) pendingCells--;
		volume[cell] = after;
	}

	boolean hasWork() { return pendingCells != 0; }
	boolean isPending(int cell) { return volume[cell].signum() != 0; }
	PendingPhaseVolumeState capture() { return PendingPhaseVolumeState.capture(volume); }

	void clear() {
		if (!hasWork()) return;
		Arrays.fill(volume, SignedDyadic.ZERO);
		pendingCells = 0;
	}

	void restore(PendingPhaseVolumeState state, ThermalDomainState thermal,
			ThermalCarrierContext markers, GasDomainState gas) {
		if (thermal == null || state == null || state.cellCount() != volume.length || hasWork()) {
			throw new IllegalArgumentException("Pending phase-volume restore is incompatible");
		}
		SignedDyadic[] restored = state.toDense();
		thermal.requirePendingPhaseCells(restored, markers);
		for (PendingPhaseVolumeState.Entry entry : state.entries()) {
			int cell = entry.cell();
			if (entry.signedCellVolume().signum() >= 0
					|| !Double.isNaN(thermal.phaseScanTemperatureInCell(cell))) continue;
			boolean gasStock = false;
			if (gas.frame != null) for (int support : CellRemapMicroTopology.components(gas.frame.geometry().collisionMask(cell))) {
				int node = gas.frame.topology().nodeOfComponent(cell,support);
				if (node > 0 && !gas.network.inventory(node).isEmpty()) { gasStock = true; break; }
			}
			if (!gasStock) throw new IllegalArgumentException("Depleted phase cell has no finite gas successor inventory");
		}
		System.arraycopy(restored, 0, volume, 0, volume.length);
		pendingCells = state.entries().size();
	}
}

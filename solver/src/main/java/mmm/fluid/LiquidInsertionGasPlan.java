package mmm.fluid;

import mmm.thermal.LiquidEnergyColumn;
import mmm.thermal.ThermalDomainState;
import java.util.ArrayList;
import java.util.TreeMap;

/** Prepares external liquid insertion and gas capacity work before the first carrier write. */
final class LiquidInsertionGasPlan {
	private final FluidDomain domain;
	private final TreeMap<Integer, ArrayList<PhaseLiquidGeometryEdit.InsertedMarker>> markers = new TreeMap<>();
	private GasTopologyCoordinator.GasTopologyPlan gas;

	LiquidInsertionGasPlan(FluidDomain domain) { this.domain = domain; }

	void add(int x, int y, int z, int slot, float volume) {
		float[] position = domain.markerSlotPosition(x, y, z, slot);
		if (domain.gasState.frame == null) return;
		markers.computeIfAbsent(domain.cellIndex(x, y, z), cell -> new ArrayList<>()).add(
				new PhaseLiquidGeometryEdit.InsertedMarker(volume, position[0], position[1], position[2]));
	}

	void prepare(ThermalDomainState.LiquidReplacementPlan liquid,
	             LiquidEnergyColumn.ExternalLiquidInsertionPlan explicit) {
		if (domain.gasState.frame != null && !markers.isEmpty()) {
			domain.ensureInventoryGrid();
			var edits = new ArrayList<PhaseLiquidGeometryEdit>(markers.size());
			markers.forEach((cell, appended) -> edits.add(PhaseLiquidGeometryEdit.afterInsertion(domain, cell, appended)));
			gas = domain.gasTopologyCoordinator.planAfterLiquidEdits(edits);
			domain.gasTopologyCoordinator.requireBeforeCarrierCommit(gas);
		}
		if (gas != null && gas.thermalPlan() != null) {
			if (liquid != null) domain.thermalState.requireGasRemapAfterLiquidReplacement(liquid, gas.thermalPlan());
			if (explicit != null) domain.thermalState.requireGasRemapAfterExternalLiquidInsertion(explicit, gas.thermalPlan());
		}
	}

	void commit() {
		domain.gridDirty = true;
		if (gas != null) domain.gasTopologyCoordinator.commit(gas);
	}
}

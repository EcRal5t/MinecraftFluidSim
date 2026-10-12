package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.thermal.LiquidEnergyColumn;
import cc.ecisr.mmm.units.SignedDyadic;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/** Prepares the gas successor of one external extraction before any liquid column changes. */
final class LiquidExtractionGasPlan {
	private final FluidDomain domain;
	private final float[] futureVolumes;
	private final BitSet cells = new BitSet();
	private final List<SignedDyadic> removedEnergy = new ArrayList<>();
	private GasTopologyCoordinator.GasTopologyPlan gas;
	private boolean prepared;

	LiquidExtractionGasPlan(FluidDomain domain) {
		this.domain = domain;
		futureVolumes = domain.gasState.frame == null ? null : new float[domain.particles.count()];
		if (futureVolumes != null) for (int marker = 0; marker < futureVolumes.length; marker++)
			futureVolumes[marker] = domain.particles.volume(marker);
	}

	void truncate(int marker, float volume, LiquidEnergyColumn.Truncation thermal) {
		if (prepared || !(Float.isFinite(volume) && volume >= 0 && volume < domain.particles.volume(marker)))
			throw new IllegalArgumentException("Invalid planned external liquid extraction");
		if (futureVolumes != null) {
			if (futureVolumes[marker] != domain.particles.volume(marker))
				throw new IllegalArgumentException("Repeated external liquid donor");
			futureVolumes[marker] = volume; cells.set(domain.particleCellIndex(marker));
		}
		if (thermal != null) removedEnergy.add(SignedDyadic.of(domain.thermalState.liquidInternalEnergy(marker))
				.subtract(SignedDyadic.of(thermal.newEnergy())));
	}

	/** The operator clear books one aggregate liquid sink and removes every positive carrier. */
	void clearLiquid() {
		if (prepared || !cells.isEmpty() || !removedEnergy.isEmpty())
			throw new IllegalStateException("Liquid clear contains another extraction");
		if (futureVolumes != null) for (int marker = 0; marker < futureVolumes.length; marker++) {
			futureVolumes[marker] = 0; cells.set(domain.particleCellIndex(marker));
		}
		if (domain.thermalState != null)
			removedEnergy.add(domain.thermalState.exactLiquidEnergy(domain.particles.count()));
	}

	/** Null item energy selects an operator removal; finite item energy settles a container extraction. */
	void prepare(Double itemEnergy) {
		if (prepared) throw new IllegalStateException("Liquid extraction is already prepared");
		if (futureVolumes != null && !cells.isEmpty()) {
			domain.ensureInventoryGrid();
			var edits = new ArrayList<PhaseLiquidGeometryEdit>(cells.cardinality());
			for (int cell = cells.nextSetBit(0); cell >= 0; cell = cells.nextSetBit(cell + 1))
				edits.add(PhaseLiquidGeometryEdit.current(cell, domain.geometry.cutCells.mask(cell),
						domain.config.cellSize(), domain.markerGrid, domain.particles, futureVolumes));
			gas = domain.gasTopologyCoordinator.planAfterLiquidEdits(edits);
			domain.gasTopologyCoordinator.requireBeforeCarrierCommit(gas);
		}
		if (domain.thermalState != null) domain.thermalState.requireCanCommitLiquidExtraction(
				removedEnergy, itemEnergy, gas == null ? null : gas.thermalPlan());
		prepared = true;
	}

	/** The prepared gas stock and volume work follow the already-published liquid successor. */
	void commit() {
		if (!prepared) throw new IllegalStateException("Liquid extraction has no prepared successor");
		domain.gridDirty = true;
		if (gas != null) domain.gasTopologyCoordinator.commit(gas);
		prepared = false;
	}
}

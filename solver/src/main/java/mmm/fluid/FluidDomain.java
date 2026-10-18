package mmm.fluid;

import mmm.inventory.LiquidCellInventory;
import mmm.inventory.PhaseOccupancyState;

/**
 * Public bounded-domain aggregate. Implementation ownership is partitioned by the superclass chain.
 */
public final class FluidDomain extends FluidDomainGasAndGranular {
	public FluidDomain(int sizeX, int sizeY, int sizeZ, FluidSolverConfig config) {
		this(sizeX, sizeY, sizeZ, config, DEFAULT_MAX_PARTICLES);
	}

	public FluidDomain(int sizeX, int sizeY, int sizeZ, FluidSolverConfig config, int maximumParticleCount) {
		super(sizeX, sizeY, sizeZ, config, maximumParticleCount);
	}

	/** Binds immutable liquid constitutive definitions independently of granular capability. */
	public void bindMaterialDefinitions(MaterialRegistry definitions) { LiquidMaterialBinding.bind(this, definitions); }

	public LiquidCellInventory.Snapshot exportLiquidCellState() { return liquidCells.snapshot(); }

	void ensurePhaseOccupancy() { PhaseOccupancyTransport.ensure(this); }

	public PhaseOccupancyState exportPhaseOccupancyState() {
		ensurePhaseOccupancy();
		return phaseOccupancy;
	}

	/** Restores saved spatial occupancy against the already restored inventory and solid pore. */
	public void restorePhaseOccupancyState(PhaseOccupancyState state) {
		if(gasState.frame!=null)throw new IllegalStateException("Phase occupancy restore requires an uninitialized gas topology");
		PhaseOccupancyTransport.requireCurrentGeometry(this,state);
		phaseOccupancy=state;
		gridDirty=pressureGeometryDirty=velocityGridDirty=true;
	}

	/** Cold inventory restore validates actual collision support and joint capacity before adoption. */
	public void restoreLiquidCellState(LiquidCellInventory.Snapshot state, mmm.chemistry.ChemicalRegistry chemistry) {
		if(gasState.frame!=null)throw new IllegalStateException("Liquid inventory restore requires an uninitialized gas topology");
		if (state == null || state.cellCount() != cellCount) throw new IllegalArgumentException("Liquid inventory shape differs from domain");
		var candidate = new LiquidCellInventory(state, chemistry);
		for (int cell = 0; cell < cellCount; cell++) {
			int mask = geometry.cutCells.mask(cell);
			int[] components = CellRemapMicroTopology.components(mask);
			for (var stock : candidate.stocksInCell(cell)) {
				boolean found = false;
				for (int component : components) found |= component == stock.key().supportMask();
				if (!found) throw new IllegalArgumentException("Liquid stock crosses a fixed collision component");
				if (materialRegistry != null) materialRegistry.material(stock.key().material());
			}
			if (candidate.exactCellVolume(cell).compareTo(mmm.units.SignedDyadic.of(effectiveOpenCellVolume(cell))) > 0)
				throw new IllegalArgumentException("Liquid inventory exceeds joint cell capacity at " + cell);
		}
		liquidCells = candidate;
		phaseOccupancy=null;
		markCellInventoryChanged();
	}

	/** Liquid kinetic energy from the common SI face inertia and current MAC velocity. */
	public double liquidKineticEnergyJoules() { ensureGrid(); return gridKineticEnergyJoules(); }

	public MacMomentumState exportMacMomentum() { return mac.captureMomentum(); }

	public void restoreMacMomentum(MacMomentumState state) {
		mac.restoreMomentum(state);
		velocityGridDirty = true;
	}

	/** Rebuilds disposable geometry and velocity from the current inventory and face momentum. */
	public void rebuildDerivedFromLiquidCells() {
		gridDirty = pressureGeometryDirty = velocityGridDirty = true;
		ensureGrid();
	}

	void markCellInventoryChanged() {
		carrierMutationRevision++;
		gridDirty = pressureGeometryDirty = velocityGridDirty = true;
		carrierPropertiesWorkspace.invalidate();
	}

	/**
	 * Derived per-cell liquid carrier properties for the current carrier and thermal revision.
	 *
	 * <p>Declared here so every collaborator that holds a {@link FluidDomain} reaches the one entry
	 * point instead of re-deriving density, temperature or viscosity per support point.
	 */
	LiquidCarrierProperties derivedCarrierState() { return super.derivedCarrierState(); }
}

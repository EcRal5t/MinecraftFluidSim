package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.chemistry.ChemicalParcelStore;
import cc.ecisr.mmm.thermal.ThermalCarrierContext;

/** Applies the established same-cell chemical reaction pass after thermal transport. */
final class ChemicalReactionStage {
	record Workspace(
			int sizeX, int sizeY, int sizeZ, float cellSize,
			LiquidParticleStore particles, ThermalCarrierContext thermalMarkers
	) { }

	interface Access {
		ChemicalParcelStore chemicalParcels();
		FluidMaterialRegistry materialRegistry();
		FlipThermalState thermalState();
	}

	private final Workspace state;
	private final Access access;

	ChemicalReactionStage(Workspace state, Access access) {
		this.state = state;
		this.access = access;
	}

	int[] captureDirtyCells() {
		ChemicalParcelStore chemistry = access.chemicalParcels();
		return chemistry == null ? new int[0] : chemistry.dirtyCells();
	}

	ChemicalParcelStore.ReactionStats step() {
		ChemicalParcelStore chemistry = access.chemicalParcels();
		if (chemistry == null) return new ChemicalParcelStore.ReactionStats(0, 0, 0);
		LiquidParticleStore.DirectArrays arrays = state.particles().directArrays();
		FlipThermalState thermal = access.thermalState();
		return chemistry.reactDirtyCells(
				state.sizeX(), state.sizeY(), state.sizeZ(), state.cellSize(),
				arrays.positionX, arrays.positionY, arrays.positionZ,
				arrays.mass, arrays.materialHandle,
				handle -> access.materialRegistry().material(handle).id(), state.particles().count(),
				thermal == null ? (marker, mixture, mass) -> { }
						: (marker, mixture, mass) -> thermal.requireReactionState(
								marker, mixture, mass, state.thermalMarkers()));
	}
}

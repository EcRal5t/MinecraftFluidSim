package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.thermal.ThermalDomainState;

import cc.ecisr.mmm.chemistry.ChemicalParcelStore;
import cc.ecisr.mmm.chemistry.ChemicalMixtureRecord;
import cc.ecisr.mmm.chemistry.ChemicalReactionEngine;
import cc.ecisr.mmm.thermal.ThermalCarrierContext;
import cc.ecisr.mmm.thermal.ThermalStateFunction;
import cc.ecisr.mmm.units.SignedDyadic;

/** Applies local-control chemical reactions after thermal transport. */
final class ChemicalReactionStage {
	record Workspace(
			int sizeX, int sizeY, int sizeZ, float cellSize,
			LiquidParticleStore particles, ThermalCarrierContext thermalMarkers
	) { }

	interface Access {
		ResolvedLiquidReactionPlan resolvedReaction();
		ChemicalParcelStore chemicalParcels();
		FluidMaterialRegistry materialRegistry();
		ThermalDomainState thermalState();
		void markGridDirty();
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
		var resolved = access.resolvedReaction(); if (resolved != null) return resolved.publish();
		LiquidParticleStore.DirectArrays arrays = state.particles().directArrays();
		ThermalDomainState thermal = access.thermalState();
		var result = chemistry.reactDirtyCells(
				state.sizeX(), state.sizeY(), state.sizeZ(), state.cellSize(),
				arrays.positionX, arrays.positionY, arrays.positionZ,
				arrays.mass, arrays.materialHandle,
				handle -> access.materialRegistry().material(handle).id(), state.particles().count(),
				thermal == null ? (marker, mixture, mass) -> { }
						: (marker, mixture, mass) -> requireThermalCandidate(thermal, marker, mixture, mass, state.thermalMarkers()));
		if (result.changedMarkers() != 0) access.markGridDirty();
		return result;
	}

	static void requireThermalCandidate(ThermalDomainState thermal, int marker,
	                                   ChemicalMixtureRecord mixture,
	                                   double mass, ThermalCarrierContext markers) {
		requireThermalCandidate(thermal, marker, mixture, SignedDyadic.of(mass), markers);
	}
	static void requireThermalCandidate(ThermalDomainState thermal, int marker,
	                                   ChemicalMixtureRecord mixture,
	                                   SignedDyadic mass, ThermalCarrierContext markers) {
		try {
			thermal.requireReactionState(marker, mixture, mass, markers);
		} catch (ThermalStateFunction.EnergyOutsideValidityException unavailable) {
			throw new ChemicalReactionEngine.BudgetExceededException(
					"Reaction candidate requires additional thermal energy");
		}
	}
}

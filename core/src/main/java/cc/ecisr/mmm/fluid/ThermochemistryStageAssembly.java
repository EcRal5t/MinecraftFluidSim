package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.thermal.ThermalTransport;

/** Constructs the thermochemistry pipeline from stage-specific state and capability seams. */
final class ThermochemistryStageAssembly {
	record Stages(
			ThermalTransportStage transport, ChemicalReactionStage chemistry,
			GranularReactionStage granularReaction, CondensedPhaseChangeStage condensedPhase,
			GasLiquidPhaseChangeStage gasPhase, ThermochemistryPipeline pipeline
	) { }

	private ThermochemistryStageAssembly() { }

	static Stages create(FlipFluidDomain domain) {
		ThermalTransportStage transport = new ThermalTransportStage(
				new ThermalTransportStage.Workspace(domain.thermalMarkerContext()),
				new ThermalTransportStage.Access() {
					@Override public void ensureGrid() { domain.ensureGrid(); }
					@Override public FlipThermalState thermalState() { return domain.thermalState; }
					@Override public double faceArea(int x, int y, int z, int direction) {
						return domain.thermalFaceArea(x, y, z, direction);
					}
					@Override public ThermalTransport.FixedBoundary fixedBoundary(int x, int y, int z, int direction) {
						return domain.thermalFixedBoundary(x, y, z, direction);
					}
				});
		ChemicalReactionStage chemistry = new ChemicalReactionStage(
				new ChemicalReactionStage.Workspace(
						domain.sizeX, domain.sizeY, domain.sizeZ, domain.config.cellSize(),
						domain.particles, domain.thermalMarkerContext()),
				new ChemicalReactionStage.Access() {
					@Override public cc.ecisr.mmm.chemistry.ChemicalParcelStore chemicalParcels() {
						return domain.chemicalParcels;
					}
					@Override public FluidMaterialRegistry materialRegistry() { return domain.materialRegistry; }
					@Override public FlipThermalState thermalState() { return domain.thermalState; }
				});
		GranularReactionStage granular = new GranularReactionStage(new GranularReactionStage.Access() {
			@Override public boolean enabled() { return domain.precipitationEnabled(); }
			@Override public FlipFluidDomain.PhaseTransferStats transfer(int[] dirtyCells) {
				return domain.transferGranularPhases(dirtyCells);
			}
		});
		CondensedPhaseChangeStage condensed = new CondensedPhaseChangeStage(
				(maximumOperations, phaseGridPrepared) ->
						domain.stepInertPhaseChangeOnly(maximumOperations, phaseGridPrepared));
		GasLiquidPhaseChangeStage gas = new GasLiquidPhaseChangeStage(
				(maximumOperations, phaseGridPrepared) ->
						domain.stepGasPhaseChangeOnly(maximumOperations, phaseGridPrepared));
		return new Stages(transport, chemistry, granular, condensed, gas,
				new ThermochemistryPipeline(transport, chemistry, granular, condensed, gas));
	}
}

package mmm.fluid;

import mmm.chemistry.ChemicalParcelStore;
import mmm.thermal.ThermalTransport;
import mmm.units.SignedDyadic;

/**
 * The single production ordering authority for conduction, liquid chemistry, granular
 * reaction and condensed/gas phase change. Granular mechanics transitions and liquid
 * reseeding deliberately remain the following logical-axis stages.
 */
final class ThermochemistryPipeline {
	private final ThermalTransportStage transport;
	private final ChemicalReactionStage chemistry;
	private final GranularReactionStage granularReaction;
	private final CondensedPhaseChangeStage condensedPhase;
	private final GasLiquidPhaseChangeStage gasPhase;

	ThermochemistryPipeline(
			ThermalTransportStage transport, ChemicalReactionStage chemistry,
			GranularReactionStage granularReaction, CondensedPhaseChangeStage condensedPhase,
			GasLiquidPhaseChangeStage gasPhase
	) {
		this.transport = transport;
		this.chemistry = chemistry;
		this.granularReaction = granularReaction;
		this.condensedPhase = condensedPhase;
		this.gasPhase = gasPhase;
	}

	FluidDomain.ThermalAxisStats step(
			boolean thermalBound, boolean granularReactionEnabled, double dtSeconds,
			boolean phaseChangeEnabled, int maximumPhaseOperations
	) {
		int[] granularPhaseCells = granularReactionEnabled ? chemistry.captureDirtyCells() : new int[0];
		ThermalTransport.Stats transportStats;
		try {
			transportStats = thermalBound ? transport.step(dtSeconds)
					: new ThermalTransport.Stats(0, 0, 0.0, SignedDyadic.ZERO, SignedDyadic.ZERO);
		} catch (IllegalArgumentException invalid) {
			throw new FluidDomain.ThermalTransportBlockedException(invalid.getMessage(), invalid);
		}
		ChemicalParcelStore.ReactionStats chemicalStats = chemistry.step();
		FluidDomain.PhaseTransferStats granularStats = granularReaction.step(granularPhaseCells);
		FluidDomain.ThermalPhaseStats condensedStats = new FluidDomain.ThermalPhaseStats(0, 0, 0, 0L);
		FluidDomain.GasThermalPhaseStats gasStats = new FluidDomain.GasThermalPhaseStats(0, 0, 0);
		if (phaseChangeEnabled) try {
			boolean phaseGridPrepared = chemicalStats.changedMarkers() == 0 && granularStats.transfers() == 0;
			condensedStats = condensedPhase.step(maximumPhaseOperations, phaseGridPrepared);
			gasStats = gasPhase.step(maximumPhaseOperations - condensedStats.operations(), true);
		} catch (FluidDomain.ThermalPhaseBlockedException blocked) {
			throw blocked;
		} catch (IllegalArgumentException invalid) {
			throw new FluidDomain.ThermalPhaseBlockedException(invalid.getMessage(), invalid);
		}
		return new FluidDomain.ThermalAxisStats(
				transportStats, chemicalStats, granularStats, condensedStats, gasStats);
	}
}

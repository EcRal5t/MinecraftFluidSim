package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.chemistry.ChemicalParcelStore;
import cc.ecisr.mmm.thermal.ThermalTransport;
import cc.ecisr.mmm.units.SignedDyadic;

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

	FlipFluidDomain.ThermalAxisStats step(
			boolean thermalBound, boolean granularReactionEnabled, double dtSeconds,
			boolean phaseChangeEnabled, int maximumPhaseOperations
	) {
		int[] granularPhaseCells = granularReactionEnabled ? chemistry.captureDirtyCells() : new int[0];
		ThermalTransport.Stats transportStats;
		try {
			transportStats = thermalBound ? transport.step(dtSeconds)
					: new ThermalTransport.Stats(0, 0, 0.0, SignedDyadic.ZERO, SignedDyadic.ZERO);
		} catch (IllegalArgumentException invalid) {
			throw new FlipFluidDomain.ThermalTransportBlockedException(invalid.getMessage(), invalid);
		}
		ChemicalParcelStore.ReactionStats chemicalStats = chemistry.step();
		FlipFluidDomain.PhaseTransferStats granularStats = granularReaction.step(granularPhaseCells);
		FlipFluidDomain.ThermalPhaseStats condensedStats = new FlipFluidDomain.ThermalPhaseStats(0, 0, 0, 0L);
		FlipFluidDomain.GasThermalPhaseStats gasStats = new FlipFluidDomain.GasThermalPhaseStats(0, 0, 0);
		if (phaseChangeEnabled) try {
			boolean phaseGridPrepared = chemicalStats.changedMarkers() == 0 && granularStats.transfers() == 0;
			condensedStats = condensedPhase.step(maximumPhaseOperations, phaseGridPrepared);
			gasStats = gasPhase.step(maximumPhaseOperations - condensedStats.operations(), true);
		} catch (FlipFluidDomain.ThermalPhaseBlockedException blocked) {
			throw blocked;
		} catch (IllegalArgumentException invalid) {
			throw new FlipFluidDomain.ThermalPhaseBlockedException(invalid.getMessage(), invalid);
		}
		return new FlipFluidDomain.ThermalAxisStats(
				transportStats, chemicalStats, granularStats, condensedStats, gasStats);
	}
}

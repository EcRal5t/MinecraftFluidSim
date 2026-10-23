package mmm.fluid;

import mmm.chemistry.RegionChemistry;
import mmm.thermal.ThermalTransport;
import mmm.units.SignedDyadic;

/**
 * The single production ordering authority for conduction, liquid chemistry, granular
 * reaction and condensed/gas phase change. Granular deposit transitions follow this axis.
 */
final class ThermochemistryPipeline {
	private final FluidDomain domain;
	private final ChemicalReactionStage chemistry;
	private final GranularReactionStage granularReaction;
	final CondensedPhaseChangeStage condensedPhase;
	final GasLiquidPhaseChangeStage gasPhase;
	private final GasLiquidMassTransferStage massTransfer;
	private String stage = "IDLE";
	private mmm.thermal.ThermalCarrierTransport.Batch caloricSample;
	private mmm.thermal.ThermalDomainState.Revision caloricRevision;

	ThermochemistryPipeline(FluidDomain domain) {
		this.domain=domain;
		chemistry=new ChemicalReactionStage(domain);
		granularReaction=new GranularReactionStage(domain);
		condensedPhase=new CondensedPhaseChangeStage(domain);
		gasPhase=new GasLiquidPhaseChangeStage(domain);
		massTransfer=new GasLiquidMassTransferStage(domain);
	}
	ThermalTransport.Stats stepTransport(double dtSeconds){
		var thermal=domain.thermalState;
		if(thermal==null)throw new IllegalStateException("Thermal state is not bound");
		var batch=mmm.thermal.ThermalCarrierTransport.prepare(thermal.exchangeCarrierAccess(domain.domainCapabilities.thermalMarkerContext()));
		var result=thermal.stepCarrierConduction(batch,CarrierThermalConduction.contacts(domain,batch),dtSeconds);
		caloricSample=thermal.gasModelBound()?null:batch;caloricRevision=thermal.thermalRevision();
		return result;
	}

	FluidDomain.ThermalAxisStats step(
			boolean thermalBound, boolean granularReactionEnabled, double dtSeconds,
			boolean phaseChangeEnabled, int maximumPhaseOperations
	) {
		try {
			return runStep(thermalBound, granularReactionEnabled, dtSeconds, phaseChangeEnabled, maximumPhaseOperations);
		} catch (RuntimeException failure) {
			failure.addSuppressed(new IllegalStateException("Thermochemistry stage=" + stage + ", dt=" + dtSeconds
					+ ", phaseChange=" + phaseChangeEnabled));
			throw failure;
		}
	}

	private FluidDomain.ThermalAxisStats runStep(boolean thermalBound, boolean granularReactionEnabled,
			double dtSeconds, boolean phaseChangeEnabled, int maximumPhaseOperations) {
		stage = "DIRTY_CELLS";
		caloricSample=null;caloricRevision=null;
		int[] granularPhaseCells = granularReactionEnabled ? chemistry.captureDirtyCells() : new int[0];
		ThermalTransport.Stats transportStats;
		stage = "CONDUCTION";
		try {
			transportStats = thermalBound ? stepTransport(dtSeconds)
					: new ThermalTransport.Stats(0, 0, 0.0, SignedDyadic.ZERO, SignedDyadic.ZERO);
		} catch (IllegalArgumentException invalid) {
			throw new FluidDomain.ThermalTransportBlockedException(invalid.getMessage(), invalid);
		}
		stage = "LIQUID_REACTION";
		RegionChemistry.ReactionStats chemicalStats = chemistry.step();
		stage = "GRANULAR_REACTION";
		FluidDomain.PhaseTransferStats granularStats = granularReaction.step(granularPhaseCells);
		FluidDomain.ThermalPhaseStats condensedStats = new FluidDomain.ThermalPhaseStats(0, 0, 0, 0L);
		FluidDomain.GasThermalPhaseStats gasStats = new FluidDomain.GasThermalPhaseStats(0, 0, 0);
		if (phaseChangeEnabled) {
			if(maximumPhaseOperations>0)domain.thermalState.preparePhaseScanAfterConduction(
					domain.domainCapabilities.thermalMarkerContext(),caloricSample,caloricRevision);
			stage = "CONDENSED_PHASE_CHANGE";
			condensedStats = condensedPhase.step(maximumPhaseOperations);
			stage = "GAS_LIQUID_PHASE_CHANGE";
			gasStats = gasPhase.step(maximumPhaseOperations - condensedStats.operations());
		}
		stage = "GAS_LIQUID_MASS_TRANSFER";
		var massTransferStats = massTransfer.step(dtSeconds);
		if(domain.chemistry!=null) for(int cell:domain.chemistry.dirtyCells())
			if(domain.activityGroups.thermalActive(cell)) domain.activityGroups.keepThermalAwake(cell);
		stage = "COMPLETE";
		return new FluidDomain.ThermalAxisStats(
				transportStats, chemicalStats, granularStats, condensedStats, gasStats, massTransferStats);
	}
}

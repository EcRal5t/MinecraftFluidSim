package mmm.fluid;

import mmm.chemistry.ChemicalTransferLedger;
import mmm.granular.SolidVolume;
import mmm.thermal.CoherentPhaseStore;
import mmm.units.SignedDyadic;
import mmm.thermal.ThermalMaterialDefinition;
import mmm.thermal.ThermalPhaseTransition;

import java.util.List;

/**
 * Plans the operator-level liquid/coherent condensed clears against the live inventory
 * without mutating any authoritative column: the drained chemical ledger and coherent phase
 * snapshot are built here and committed later on the domain through the carrier mutation
 * coordinator. Every fallible audit check happens during planning.
 */
final class CondensedInventoryClearPlanner {
	private final FluidDomain domain;

	CondensedInventoryClearPlanner(FluidDomain domain) {
		this.domain = domain;
	}

	/** Completes every fallible liquid-clear audit check without changing marker truth. */
	FluidDomain.WaterClearPlan planWater() {
		ChemicalTransferLedger drained = ChemicalTransferLedger.EMPTY;
		if (domain.chemistry != null) {
			ChemicalTransferLedger.Builder builder = ChemicalTransferLedger.builder(domain.chemistry.registry());
			for (var stock : domain.liquidCells.stocks()) builder.add(
					domain.materialRegistry.material(stock.key().material()).id(),
					stock.volumeFraction(), stock.massUnits(), stock.mixture());
			drained = builder.build();
		}
		return new FluidDomain.WaterClearPlan(domain.liquidCells.size(),
				Double.doubleToRawLongBits(domain.totalVolumeExact()),
				domain.totalMassUnits(), drained,
				domain.thermalState == null ? SignedDyadic.ZERO
						: domain.liquidCells.exactInternalEnergy(),
				domain.domainFlowFields.carrierMutationRevision(),
				domain.chemistry == null ? null : domain.chemistry.planAuditExchange(ChemicalTransferLedger.EMPTY,drained),domain.transferPlanner.planLiquidClear());
	}

	/** Plans one operator clear across liquid markers and every coherent condensed phase. */
	FluidDomain.CondensedClearPlan planCondensed() {
		FluidDomain.WaterClearPlan liquid = planWater();
		List<CoherentPhaseStore.Group> groups = domain.thermalState == null
				? List.of() : domain.thermalState.coherentPhases().snapshot();
		ChemicalTransferLedger coherentChemistry = ChemicalTransferLedger.EMPTY;
		if (!groups.isEmpty()) {
			ChemicalTransferLedger.Builder builder = domain.chemistry == null
					? null : ChemicalTransferLedger.builder(domain.chemistry.registry());
			for (CoherentPhaseStore.Group group : groups) {
				if (group.composition() == null) continue;
				if (builder == null) {
					throw new IllegalStateException("Chemical coherent phase exists without chemistry state");
				}
				builder.add(new ChemicalTransferLedger.Group(
						coherentLiquidMaterialId(group.phaseId()),
						domain.unitScale.normalizedVolume((double) group.occupiedVolumeSvq() / SolidVolume.SVQ_PER_CUBIC_METER),
						group.massUnits(),
						group.composition().massResidualUnits(), group.composition().stableAmounts()));
			}
			if (builder != null) coherentChemistry = builder.build();
		}
		ChemicalTransferLedger drained = liquid.drained().plus(coherentChemistry);
		SignedDyadic coherentEnergy = domain.thermalState == null
				? SignedDyadic.ZERO : domain.thermalState.coherentPhases().exactEnergy();
		long coherentSvq = domain.thermalState == null ? 0L : domain.thermalState.coherentPhases().totalOccupiedSvq();
		long coherentMass = domain.thermalState == null ? 0L : domain.thermalState.coherentPhases().totalMassUnits();
		return new FluidDomain.CondensedClearPlan(liquid, groups, drained, coherentEnergy, coherentSvq,
				coherentMass,
				domain.domainFlowFields.carrierMutationRevision(),
				domain.chemistry == null ? null : domain.chemistry.planAuditExchange(ChemicalTransferLedger.EMPTY,drained));
	}

	private String coherentLiquidMaterialId(String coherentPhaseId) {
		String found = null;
		for (ThermalPhaseTransition transition : domain.thermalState.registry().transitions()) {
			if (!transition.lowerEnergyMaterialId().equals(coherentPhaseId)) continue;
			ThermalMaterialDefinition upper = domain.thermalState.registry().material(
					domain.thermalState.registry().materialHandle(transition.upperEnergyMaterialId()));
			if (upper.carrierKind() != ThermalMaterialDefinition.CarrierKind.LIQUID) continue;
			domain.materialRegistry.handle(upper.carrierId());
			if (found != null && !found.equals(upper.carrierId())) {
				throw new IllegalStateException("Coherent phase has ambiguous liquid chemical ownership");
			}
			found = upper.carrierId();
		}
		if (found == null) {
			throw new IllegalStateException("Chemical coherent phase has no unique liquid ownership");
		}
		return found;
	}
}

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
		if (domain.chemicalParcels != null) {
			ChemicalTransferLedger.Builder builder = ChemicalTransferLedger.builder(domain.chemicalParcels.registry());
			for (int marker = 0; marker < domain.particles.count(); marker++) builder.add(
					domain.materialRegistry.material(domain.particles.materialHandle(marker)).id(),
					domain.chemicalParcels.indicator(marker),
					domain.particles.volume(marker), domain.unitScale.kilograms(domain.particles.mass(marker)),
					domain.chemicalParcels.record(marker));
			drained = builder.build();
			domain.chemicalParcels.requireCanRecordEscapedSink(drained);
		}
		return new FluidDomain.WaterClearPlan(domain.particles.count(),
				Double.doubleToRawLongBits(domain.totalVolumeExact()),
				Double.doubleToRawLongBits(domain.totalMass()), drained,
				domain.thermalState == null ? SignedDyadic.ZERO
						: domain.thermalState.exactLiquidEnergy(domain.particles.count()),
				domain.carrierMutationRevision(),
				domain.chemicalParcels == null ? 0L : domain.chemicalParcels.mutationRevision());
	}

	/** Plans one operator clear across liquid markers and every coherent condensed phase. */
	FluidDomain.CondensedClearPlan planCondensed() {
		FluidDomain.WaterClearPlan liquid = planWater();
		List<CoherentPhaseStore.Group> groups = domain.thermalState == null
				? List.of() : domain.thermalState.coherentPhases().snapshot();
		ChemicalTransferLedger coherentChemistry = ChemicalTransferLedger.EMPTY;
		if (!groups.isEmpty()) {
			ChemicalTransferLedger.Builder builder = domain.chemicalParcels == null
					? null : ChemicalTransferLedger.builder(domain.chemicalParcels.registry());
			for (CoherentPhaseStore.Group group : groups) {
				if (group.composition() == null) continue;
				if (builder == null) {
					throw new IllegalStateException("Chemical coherent phase exists without chemistry state");
				}
				builder.add(new ChemicalTransferLedger.Group(
						coherentLiquidMaterialId(group.phaseId()), false,
						SolidVolume.toCellVolume(group.occupiedVolumeSvq()),
						group.mass(),
						group.composition().massResidualBits(), group.composition().stableAmounts()));
			}
			if (builder != null) coherentChemistry = builder.build();
		}
		ChemicalTransferLedger drained = liquid.drained().plus(coherentChemistry);
		if (domain.chemicalParcels != null) domain.chemicalParcels.requireCanRecordEscapedSink(drained);
		SignedDyadic coherentEnergy = domain.thermalState == null
				? SignedDyadic.ZERO : domain.thermalState.coherentPhases().exactEnergy();
		long coherentSvq = domain.thermalState == null ? 0L : domain.thermalState.coherentPhases().totalOccupiedSvq();
		double coherentMass = domain.thermalState == null ? 0.0 : domain.thermalState.coherentPhases().totalMass();
		return new FluidDomain.CondensedClearPlan(liquid, groups, drained, coherentEnergy, coherentSvq,
				Double.doubleToRawLongBits(coherentMass),
				domain.carrierMutationRevision(),
				domain.chemicalParcels == null ? 0L : domain.chemicalParcels.mutationRevision());
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

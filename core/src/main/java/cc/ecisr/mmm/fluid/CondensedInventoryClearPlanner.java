package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.chemistry.ChemicalTransferLedger;
import cc.ecisr.mmm.fluid.air.AirNetwork;
import cc.ecisr.mmm.fluid.air.GasInventory;
import cc.ecisr.mmm.granular.SolidVolume;
import cc.ecisr.mmm.thermal.CoherentPhaseStore;
import cc.ecisr.mmm.thermal.SignedDyadic;
import cc.ecisr.mmm.thermal.ThermalMaterialDefinition;
import cc.ecisr.mmm.thermal.ThermalPhaseTransition;

import java.util.List;

/**
 * Plans the operator-level lifecycle clears against the live inventory without mutating any
 * authoritative column: the drained chemical ledger, coherent phase snapshot, granular clear
 * and sealed-gas vent plans are built here and committed later on the domain through the
 * carrier mutation coordinator. Every fallible audit check happens during planning.
 */
final class CondensedInventoryClearPlanner {
	private final FlipFluidDomain domain;

	CondensedInventoryClearPlanner(FlipFluidDomain domain) {
		this.domain = domain;
	}

	/** Completes every fallible liquid-clear audit check without changing marker truth. */
	FlipFluidDomain.WaterClearPlan planWater() {
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
		return new FlipFluidDomain.WaterClearPlan(domain.particles.count(),
				Double.doubleToRawLongBits(domain.totalVolumeExact()),
				Double.doubleToRawLongBits(domain.totalMass()), drained,
				domain.thermalState == null ? SignedDyadic.ZERO
						: domain.thermalState.exactLiquidEnergy(domain.particles.count()),
				domain.carrierMutationRevision(),
				domain.chemicalParcels == null ? 0L : domain.chemicalParcels.mutationRevision());
	}

	/** Plans one operator clear across liquid markers and every coherent condensed phase. */
	FlipFluidDomain.CondensedClearPlan planCondensed() {
		FlipFluidDomain.WaterClearPlan liquid = planWater();
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
		return new FlipFluidDomain.CondensedClearPlan(liquid, groups, drained, coherentEnergy, coherentSvq,
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

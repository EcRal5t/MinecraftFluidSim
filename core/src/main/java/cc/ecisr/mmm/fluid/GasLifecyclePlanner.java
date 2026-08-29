package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.air.AirNetwork;
import cc.ecisr.mmm.fluid.air.GasInventory;
import cc.ecisr.mmm.units.SignedDyadic;

import java.util.List;

/**
 * Plans and commits the operator sealed-gas vent as one joint gas-inventory, gas-energy and
 * audit operation: every fallible check (pending projection work, audit closure, stale-plan
 * identity) runs before the first truth change, and the commit converts every sealed cell to
 * inventory-free ambient topology in one pass. Thermal gas energy is planned and settled by
 * the thermal state owner; no energy truth is copied into the gas side.
 */
final class GasLifecyclePlanner {
	private final FlipFluidDomain domain;

	GasLifecyclePlanner(FlipFluidDomain domain) {
		this.domain = domain;
	}

	/** Prevalidates an operator vent without treating ambient as inventory. */
	FlipFluidDomain.OperatorGasVentPlan planVent() {
		if (domain.hasPendingPhaseVolumeWork()) {
			throw new IllegalStateException("Pending phase volume/work must be projected before operator vent");
		}
		if (!domain.gasState.network.auditClosed()) throw new IllegalStateException("Gas inventory audit is open");
		GasInventory removed = domain.gasState.network.sealedInventory();
		AirNetwork.RemapPlan gasPlan = domain.gasState.network.planRemapNodes(
				new double[0], List.of(), GasInventory.EMPTY, removed, List.of());
		FlipThermalState.GasVentPlan thermalPlan = domain.thermalState == null ? null
				: domain.thermalState.planGasVent(domain.gasState.network, domain.particles.count());
		return new FlipFluidDomain.OperatorGasVentPlan(domain.gasState.network.nodeCount(), removed, gasPlan, thermalPlan);
	}

	/** Converts every sealed cell to inventory-free ambient topology and books gas plus U to sinks. */
	FlipFluidDomain.OperatorGasVent commitVent(FlipFluidDomain.OperatorGasVentPlan plan) {
		if (plan == null || domain.gasState.network.nodeCount() != plan.nodeCount()
				|| !domain.gasState.network.sealedInventory().equals(plan.removed())) {
			throw new IllegalStateException("Sealed gas inventory changed after vent planning");
		}
		if (plan.thermalPlan() != null) domain.thermalState.commitGasVent(plan.thermalPlan());
		domain.gasState.network.commitRemapNodes(plan.gasPlan());
		if (domain.gasState.cellNodeId != null) for (int cell = 0; cell < domain.gasState.cellNodeId.length; cell++) {
			if (domain.gasState.cellNodeId[cell] > 0) domain.gasState.cellNodeId[cell] = AirNetwork.AMBIENT_NODE;
		}
		if (domain.gasState.cellLineageNodeId != null) for (int cell = 0; cell < domain.gasState.cellLineageNodeId.length; cell++) {
			if (domain.gasState.cellLineageNodeId[cell] > 0) domain.gasState.cellLineageNodeId[cell] = AirNetwork.AMBIENT_NODE;
		}
		domain.gasState.previousSealedNodeCount = 0;
		domain.gasState.pressureActive = false;
		domain.gasState.operatorVentedTopology = true;
		return new FlipFluidDomain.OperatorGasVent(plan.removed(), plan.thermalPlan() == null
				? SignedDyadic.ZERO : plan.thermalPlan().removedEnergy(), plan.nodeCount());
	}
}

package mmm.fluid;

import mmm.thermal.GasEnergyStore;

import mmm.fluid.air.AirNetwork;
import mmm.fluid.air.GasInventory;
import mmm.fluid.air.GasAuditLedger;
import mmm.units.SignedDyadic;


/**
 * Plans and commits the operator sealed-gas vent as one joint gas-inventory, gas-energy and
 * audit operation: every fallible check (pending projection work, audit closure, stale-plan
 * identity) runs before the first truth change, and the commit retains the closed topology while
 * replacing every node inventory with explicit vacuum. Thermal gas energy is planned and settled by
 * the thermal state owner; no energy truth is copied into the gas side.
 */
final class GasLifecyclePlanner {
	private final FluidDomain domain;

	GasLifecyclePlanner(FluidDomain domain) {
		this.domain = domain;
	}

	/** Prevalidates an operator vent without treating ambient as inventory. */
	FluidDomain.OperatorGasVentPlan planVent() {
		if (domain.hasPendingPhaseVolumeWork()) {
			throw new IllegalStateException("Pending phase volume/work must be projected before operator vent");
		}
		domain.ensureInventoryGrid();
		GasRuntimeGeometry sourceGeometry = domain.gasState.frame == null ? null : GasRuntimeGeometry.capture(domain, null,
				domain.granularCoupling == null ? new long[domain.cellCount] : domain.granularCoupling.copySolidSvq(),
				domain.gasState.sealedDomainBoundary);
		if (!domain.gasState.network.auditClosed()) throw new IllegalStateException("Gas inventory audit is open");
		GasAuditLedger removed = domain.gasState.network.sealedInventory();
		int nodeCount = domain.gasState.network.nodeCount();
		double[] availableVolumes = new double[nodeCount];
		java.util.ArrayList<GasInventory> vacuum = new java.util.ArrayList<>(nodeCount);
		for (int node = 1; node <= nodeCount; node++) {
			availableVolumes[node - 1] = domain.gasState.network.availableVolume(node);
			vacuum.add(GasInventory.EMPTY);
		}
		AirNetwork.RemapPlan gasPlan = domain.gasState.network.planRemapNodes(
				availableVolumes, vacuum, GasAuditLedger.EMPTY, removed, domain.gasState.network.edges());
		GasEnergyStore.GasVentPlan thermalPlan = domain.thermalState == null ? null
				: domain.thermalState.planGasVent(domain.gasState.network, domain.particles.count());
		return new FluidDomain.OperatorGasVentPlan(nodeCount, removed, gasPlan, thermalPlan,
				domain.thermalState == null ? null : domain.thermalState.thermalRevision(),
				sourceGeometry);
	}

	/** Leaves a complete sealed vacuum topology and books the removed gas plus U to sinks. */
	FluidDomain.OperatorGasVent commitVent(FluidDomain.OperatorGasVentPlan plan) {
		domain.ensureInventoryGrid();
		if (plan == null || domain.gasState.network.nodeCount() != plan.nodeCount()
				|| (domain.gasState.frame == null) != (plan.sourceGeometry() == null)
				|| plan.sourceGeometry() != null && !plan.sourceGeometry().matchesCarrierGeometry(domain,
						domain.granularCoupling == null ? new long[domain.cellCount] : domain.granularCoupling.copySolidSvq())
				|| !domain.gasState.network.sealedInventory().equals(plan.removed())) {
			throw new IllegalStateException("Sealed gas inventory changed after vent planning");
		}
		domain.gasState.network.requireCanCommitRemap(plan.gasPlan());
		if (domain.thermalState == null ? plan.thermalRevision() != null
				: !domain.thermalState.thermalRevision().equals(plan.thermalRevision())) {
			throw new IllegalStateException("Thermal inventory changed after vent planning");
		}
		if (plan.thermalPlan() != null) domain.thermalState.requireCanCommitGasVent(plan.thermalPlan());
		if (plan.thermalPlan() != null) domain.thermalState.commitGasVent(plan.thermalPlan());
		domain.gasState.network.commitRemapNodes(plan.gasPlan());
		domain.gasState.pressureActive = domain.gasBoundary.nodeCount() > 0;
		return new FluidDomain.OperatorGasVent(plan.removed(), plan.thermalPlan() == null
				? SignedDyadic.ZERO : plan.thermalPlan().removedEnergy(), plan.nodeCount());
	}
}

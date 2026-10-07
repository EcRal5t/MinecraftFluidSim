package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.granular.GranularAuditLedger;
import cc.ecisr.mmm.thermal.ThermalDomainState;
import cc.ecisr.mmm.units.SignedDyadic;

/**
 * Plans the domain-wide inventory lifecycle: the granular active/deposit clear (jointly
 * listing the removed SVQ, mass, species and the thermal U that will sink) and the joint
 * operator clear across condensed liquid, coherent phases, granular inventory and the
 * retained-gas topology. Plans bind the granular structural revision and raw-bit SVQ/mass/U
 * totals; the domain commits re-verify them before any inventory or audit mutation. The
 * thermal side is bound by its owner generation and exact dyadic inventory.
 */
final class DomainInventoryLifecyclePlanner {
	private final FluidDomain domain;

	DomainInventoryLifecyclePlanner(FluidDomain domain) {
		this.domain = domain;
	}

	/**
	 * Completes every fallible granular-clear check without changing inventory or audit truth.
	 * The plan binds the granular structural revision, the exact SVQ/mass/U totals and the
	 * escaped-sink successor; the liquid carrier revision is deliberately not bound because
	 * granular truth is independent of marker structure and the joint operator clear commits
	 * the condensed side first.
	 */
	FluidDomain.GranularClearPlan planGranularClear() {
		long granularRevision = domain.granularState == null ? 0L : domain.granularState.mutationRevision();
		if (domain.granularState == null || !domain.hasGranularInventory()) {
			return new FluidDomain.GranularClearPlan(GranularAuditLedger.EMPTY,
					domain.granularState == null ? GranularAuditLedger.EMPTY : domain.granularState.escapedSink,
					0L, Double.doubleToRawLongBits(0.0), 0, 0,
					SignedDyadic.ZERO, granularRevision);
		}
		// Export validates unique runtime composition ownership before either inventory or audit mutates.
		domain.exportGranularState();
		GranularDomainState granular = domain.granularState;
		GranularAuditLedger removed = GranularAuditLedger.captureInventory(
				granular.registry, granular.parcels, granular.deposits, granular.compositions);
		GranularAuditLedger sinkAfter = granular.escapedSink.plus(removed);
		return new FluidDomain.GranularClearPlan(removed, sinkAfter, granular.inventory.totalSolidVolumeSvq(),
				Double.doubleToRawLongBits(granular.inventory.totalMass()), granular.parcels.count(),
				granular.deposits.groupCount(), domain.thermalState == null
						? SignedDyadic.ZERO : domain.thermalState.granularColumn().exactEnergy(),
				granularRevision);
	}

	/** Prevalidates every truth removed by the operator-level condensed inventory reset. */
	FluidDomain.OperatorClearPlan planOperatorClear() {
		if (domain.hasPendingPhaseVolumeWork()) {
			throw new IllegalStateException("Pending phase volume/work must be projected before operator clear");
		}
		FluidDomain.CondensedClearPlan condensed = domain.planRemoveAllCondensedExact();
		FluidDomain.GranularClearPlan granular = planGranularClear();
		GasTopologyCoordinator.GasTopologyPlan gasTopology = null;
		if (domain.gasState.cellNodeId != null && domain.gasState.geometry == null) {
			domain.ensureGrid();
			gasTopology = domain.gasTopologyCoordinator.planFromFreshScan();
		}
		ThermalDomainState.Revision thermalRevision = domain.thermalState == null
				? null : domain.thermalState.thermalRevision();
		if (domain.thermalState != null) domain.thermalState.requireCanCommitOperatorClear(
				thermalRevision, condensed.liquid().internalEnergy(), condensed.coherentEnergy(),
				granular.internalEnergy(), condensed.liquid().gasExpansion() == null
				? gasTopology == null ? null : gasTopology.thermalPlan() : condensed.liquid().gasExpansion().energy);
		return new FluidDomain.OperatorClearPlan(condensed, granular, gasTopology, thermalRevision);
	}
}

package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.granular.GranularAuditLedger;

/**
 * Plans the domain-wide inventory lifecycle: the granular active/deposit clear (jointly
 * listing the removed SVQ, mass, species and the thermal U that will sink) and the joint
 * operator clear across condensed liquid, coherent phases, granular inventory and the
 * retained-gas topology. Plans bind the granular structural revision and raw-bit SVQ/mass/U
 * totals; the domain commits re-verify them before any inventory or audit mutation. The
 * thermal side is bound through raw-bit U totals until S10 introduces its own revision
 * counter.
 */
final class DomainInventoryLifecyclePlanner {
	private final FlipFluidDomain domain;

	DomainInventoryLifecyclePlanner(FlipFluidDomain domain) {
		this.domain = domain;
	}

	/**
	 * Completes every fallible granular-clear check without changing inventory or audit truth.
	 * The plan binds the granular structural revision, the exact SVQ/mass/U totals and the
	 * escaped-sink successor; the liquid carrier revision is deliberately not bound because
	 * granular truth is independent of marker structure and the joint operator clear commits
	 * the condensed side first.
	 */
	FlipFluidDomain.GranularClearPlan planGranularClear() {
		long granularRevision = domain.granularState == null ? 0L : domain.granularState.mutationRevision();
		if (domain.granularState == null || !domain.hasGranularInventory()) {
			return new FlipFluidDomain.GranularClearPlan(GranularAuditLedger.EMPTY,
					domain.granularState == null ? GranularAuditLedger.EMPTY : domain.granularState.escapedSink,
					0L, Double.doubleToRawLongBits(0.0), 0, 0,
					Double.doubleToRawLongBits(0.0), granularRevision);
		}
		// Export validates unique runtime composition ownership before either inventory or audit mutates.
		domain.exportGranularState();
		GranularDomainState granular = domain.granularState;
		GranularAuditLedger removed = GranularAuditLedger.captureInventory(
				granular.registry, granular.parcels, granular.deposits, granular.compositions);
		GranularAuditLedger sinkAfter = granular.escapedSink.plus(removed);
		return new FlipFluidDomain.GranularClearPlan(removed, sinkAfter, granular.inventory.totalSolidVolumeSvq(),
				Double.doubleToRawLongBits(granular.inventory.totalMass()), granular.parcels.count(),
				granular.deposits.groupCount(), Double.doubleToRawLongBits(domain.granularInternalEnergy()),
				granularRevision);
	}

	/** Prevalidates every truth removed by the operator-level condensed inventory reset. */
	FlipFluidDomain.OperatorClearPlan planOperatorClear() {
		if (domain.hasPendingPhaseVolumeWork()) {
			throw new IllegalStateException("Pending phase volume/work must be projected before operator clear");
		}
		FlipFluidDomain.CondensedClearPlan condensed = domain.planRemoveAllCondensedExact();
		FlipFluidDomain.GranularClearPlan granular = planGranularClear();
		FlipFluidDomain.GasTopologyPlan gasTopology = null;
		if (domain.airCellNodeId != null && !domain.operatorVentedGasTopology) {
			domain.ensureGrid();
			gasTopology = domain.planGasTopology(domain.airScanner.scan(
					domain.geometry.solid, new int[domain.cellCount], new double[domain.cellCount],
					domain.geometry.cutCells, null, domain.sealedDomainBoundary));
		}
		return new FlipFluidDomain.OperatorClearPlan(condensed, granular, gasTopology);
	}
}

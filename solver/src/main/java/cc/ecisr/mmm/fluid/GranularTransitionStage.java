package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.granular.GranularCouplingFields;
import cc.ecisr.mmm.granular.GranularDepositDynamics;
import cc.ecisr.mmm.granular.GranularLimits;
import cc.ecisr.mmm.granular.GranularParcelStore;

/** Performs active compaction followed by deposit settlement/relaxation/wake transitions. */
final class GranularTransitionStage {
	record Workspace(FluidSolverConfig config) { }
	record Outcome(GranularDepositDynamics.Stats depositStats, FluidDomain.LiquidTransportStats liquidTransport) { }

	interface Access {
		long[] beginGeometryTransition();
		FluidDomain.LiquidTransportStats commitGeometryTransition(long[] previous);
		GranularDomainState granularState();
		GranularCouplingFields coupling();
		GranularDepositDynamics depositDynamics();
		boolean hasInventory();
		int parcelCell(GranularParcelStore.Parcel parcel);
		boolean planMerge(
				long[] planned, GranularParcelStore.Parcel first,
				GranularParcelStore.Parcel second, GranularParcelStore.Parcel merged);
		float mergeVelocityDelta();
		GranularDepositDynamics.Environment depositEnvironment(long[] plannedSolid);
		boolean occupancyFitsGeometry(GranularCouplingFields coupling);
		void pressureCoupling(GranularCouplingFields coupling);
	}

	private static final GranularDepositDynamics.Stats EMPTY_STATS =
			new GranularDepositDynamics.Stats(0, 0, 0, 0, 0);

	private final Workspace state;
	private final Access access;

	GranularTransitionStage(Workspace state, Access access) {
		this.state = state;
		this.access = access;
	}

	Outcome step() {
		long[] previousSolid = access.beginGeometryTransition();
		GranularDomainState granular = access.granularState();
		GranularCouplingFields coupling = access.coupling();
		if (granular != null && granular.parcels.count() > 1) {
			coupling.rebuild(granular.parcels, granular.deposits);
			long[] compactedSolid = coupling.copySolidSvq();
			granular.inventory.compactActive(
					index -> access.parcelCell(granular.parcels.parcel(index)),
					(first, second, merged) -> access.planMerge(compactedSolid, first, second, merged),
					access.mergeVelocityDelta(), GranularLimits.MAX_OPERATIONS_PER_LOGICAL_STEP);
			coupling.rebuild(granular.parcels, granular.deposits);
			if (!coupling.matchesSolidSvq(compactedSolid)) {
				throw new IllegalStateException("Granular active compaction differs from its exact occupancy plan");
			}
		}
		GranularDepositDynamics dynamics = access.depositDynamics();
		if (dynamics == null) return new Outcome(EMPTY_STATS, FluidDomain.LiquidTransportStats.INACTIVE);
		long[] dynamicsSolid = null;
		GranularDepositDynamics.Stats stats;
		if (access.hasInventory() || dynamics.dirtyColumnCount() != 0) {
			coupling.rebuild(granular.parcels, granular.deposits);
			dynamicsSolid = coupling.copySolidSvq();
			stats = dynamics.step(access.depositEnvironment(dynamicsSolid), state.config().timeStep());
		} else {
			stats = EMPTY_STATS;
		}
		if (access.hasInventory()) {
			coupling.rebuild(granular.parcels, granular.deposits);
			if (!coupling.matchesSolidSvq(dynamicsSolid)) {
				throw new IllegalStateException("Granular deposit transition differs from its exact occupancy plan");
			}
			if (!access.occupancyFitsGeometry(coupling)) {
				throw new IllegalStateException("Granular deposit transition exceeds geometry");
			}
			access.pressureCoupling(coupling);
		} else {
			coupling.clear();
			access.pressureCoupling(null);
		}
		return new Outcome(stats, access.commitGeometryTransition(previousSolid));
	}
}

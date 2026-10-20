package mmm.fluid;

import mmm.granular.GranularCouplingFields;
import mmm.granular.GranularDepositDynamics;
import mmm.granular.GranularLimits;
import mmm.granular.GranularParcelStore;

/** Performs active compaction followed by deposit settlement/relaxation/wake transitions. */
final class GranularTransitionStage {
	record Outcome(GranularDepositDynamics.Stats depositStats) { }

	private static final GranularDepositDynamics.Stats EMPTY_STATS =
			new GranularDepositDynamics.Stats(0, 0, 0, 0, 0);

	private final FluidDomain domain;
	GranularTransitionStage(FluidDomain domain) { this.domain = domain; }

	Outcome step() {
		long[] previousSolid = GranularLiquidDisplacement.begin(domain);
		GranularDomainState granular = domain.granularState;
		GranularCouplingFields coupling = domain.granularCoupling;
		GranularDepositDynamics dynamics = domain.granularDepositDynamics;
		// Liquid observations belong to the source inventory until the joint displacement commits.
		var flow = dynamics == null ? null : GranularBedShear.prepareFlow(domain, previousSolid);
		var compaction = new mmm.granular.GranularInventory.CompactionStats(0, 0);
		if (granular != null && granular.parcels.count() > 1) {
			coupling.rebuild(granular.parcels, granular.deposits);
			long[] compactedSolid = coupling.copySolidSvq();
			int count = granular.parcels.count();
			var future = new GranularParcelStore.Parcel[count];
			for (int i = 0; i < count; i++) future[i] = granular.parcels.parcel(i);
			// Charge the source capture and bound every pair visit plus its full future-sphere scan.
			int candidateBudget = (GranularLimits.MAX_OPERATIONS_PER_LOGICAL_STEP - count) / (count + 1);
			compaction = granular.inventory.compactActive(
					index -> domain.domainStep.granularParcelCell(granular.parcels.parcel(index)),
					(firstIndex, secondIndex, first, second, merged) -> {
						if (!mergeFitsActiveSpheres(future, firstIndex, secondIndex, merged)
								|| !domain.domainStep.planGranularMerge(compactedSolid, first, second, merged)) return false;
						future[firstIndex] = merged; future[secondIndex] = null;
						return true;
					}, domain.domainStep.granularMergeVelocityDelta(), candidateBudget, domain.config.gravity());
			coupling.rebuild(granular.parcels, granular.deposits);
			if (!coupling.matchesSolidSvq(compactedSolid)) {
				throw new IllegalStateException("Granular active compaction differs from its exact occupancy plan");
			}
		}
		if (dynamics == null) return new Outcome(EMPTY_STATS.withCompaction(compaction));
		long[] dynamicsSolid = null;
		GranularDepositDynamics.Stats stats;
		if (domain.domainPhaseInventory.hasGranularInventory() || dynamics.dirtyColumnCount() != 0) {
			coupling.rebuild(granular.parcels, granular.deposits);
			dynamicsSolid = coupling.copySolidSvq();
			stats = dynamics.step(domain.domainStep.granularDepositEnvironment(dynamicsSolid, flow), domain.config.timeStep());
		} else {
			stats = EMPTY_STATS;
		}
		if (domain.domainPhaseInventory.hasGranularInventory()) {
			coupling.rebuild(granular.parcels, granular.deposits);
			if (!coupling.matchesSolidSvq(dynamicsSolid)) {
				throw new IllegalStateException("Granular deposit transition differs from its exact occupancy plan");
			}
			if (!domain.domainStep.granularOccupancyFitsGeometry(coupling)) {
				throw new IllegalStateException("Granular deposit transition exceeds geometry");
			}
			domain.pressureGranularCoupling = coupling;
		} else {
			coupling.clear();
			domain.pressureGranularCoupling = null;
		}
		GranularLiquidDisplacement.commit(domain, previousSolid);
		return new Outcome(stats.withCompaction(compaction));
	}

	private boolean mergeFitsActiveSpheres(GranularParcelStore.Parcel[] future, int first, int second,
	                                      GranularParcelStore.Parcel merged) {
		float h = domain.config.cellSize();
		float radius = mmm.granular.GranularMechanics.representativeRadius(merged.solidVolumeSvq(), h);
		for (int i = 0; i < future.length; i++) {
			var other = future[i]; if (i == first || i == second || other == null) continue;
			if (GranularSpawnAdmission.interiorsOverlap(merged.x(), merged.y(), merged.z(), radius,
					other.x(), other.y(), other.z(), mmm.granular.GranularMechanics.representativeRadius(other.solidVolumeSvq(), h)))
				return false;
		}
		return true;
	}
}

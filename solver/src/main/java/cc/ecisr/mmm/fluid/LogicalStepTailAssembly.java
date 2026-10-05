package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.thermal.ThermalDomainState;

import cc.ecisr.mmm.granular.GranularCouplingFields;
import cc.ecisr.mmm.granular.GranularDepositDynamics;
import cc.ecisr.mmm.granular.GranularParcelStore;
import cc.ecisr.mmm.units.SignedDyadic;

/** Constructs the granular-transition and liquid-reseed stages that follow thermochemistry. */
final class LogicalStepTailAssembly {
	record Stages(GranularTransitionStage granularTransition, ParticleReseedStage reseed) { }

	private LogicalStepTailAssembly() { }

	static Stages create(FluidDomain domain) {
		GranularTransitionStage transition = new GranularTransitionStage(
				new GranularTransitionStage.Workspace(domain.config),
				new GranularTransitionStage.Access() {
					@Override public long[] beginGeometryTransition() { return GranularLiquidDisplacement.begin(domain); }
					@Override public void commitGeometryTransition(long[] previous) { GranularLiquidDisplacement.commit(domain, previous); }
					@Override public GranularDomainState granularState() { return domain.granularState; }
					@Override public GranularCouplingFields coupling() { return domain.granularCoupling; }
					@Override public GranularDepositDynamics depositDynamics() { return domain.granularDepositDynamics(); }
					@Override public boolean hasInventory() { return domain.hasGranularInventory(); }
					@Override public int parcelCell(GranularParcelStore.Parcel parcel) {
						return domain.granularParcelCell(parcel);
					}
					@Override public boolean planMerge(
							long[] planned, GranularParcelStore.Parcel first,
							GranularParcelStore.Parcel second, GranularParcelStore.Parcel merged
					) {
						return domain.planGranularMerge(planned, first, second, merged);
					}
					@Override public float mergeVelocityDelta() { return domain.granularMergeVelocityDelta(); }
					@Override public GranularDepositDynamics.Environment depositEnvironment(long[] plannedSolid) {
						return domain.granularDepositEnvironment(plannedSolid);
					}
					@Override public boolean occupancyFitsGeometry(GranularCouplingFields coupling) {
						return domain.granularOccupancyFitsGeometry(coupling);
					}
					@Override public void pressureCoupling(GranularCouplingFields coupling) {
						domain.pressureGranularCoupling = coupling;
					}
				});
		ParticleReseedStage reseed = new ParticleReseedStage(
				new ParticleReseedStage.Workspace(domain.geometry, domain.particles, domain.particleReseeder),
				new ParticleReseedStage.Access() {
					@Override public boolean hasResolvedGeometry() { return domain.liquidGeometryState != null; }
					@Override public boolean hasConservativeCellQuadrature() {
						return CellLiquidVolumeTransport.enabled() && domain.cellLiquidVolumeTransport != null
								&& domain.cellLiquidVolumeTransport.publishedQuadrature;
					}
					@Override public void ensureParticleCapacity(int required) { domain.ensureParticleCapacity(required); }
					@Override public cc.ecisr.mmm.chemistry.ChemicalParcelStore chemicalParcels() {
						return domain.chemicalParcels;
					}
					@Override public ThermalDomainState thermalState() { return domain.thermalState; }
					@Override public FlipParticleReseeder.ThermalAdapter thermalAdapter() {
						return domain.thermalReseedAdapter();
					}
					@Override public boolean massWeightedDynamics() { return domain.usesMassWeightedP2G(); }
					@Override public boolean markerPositionAllowed(float x, float y, float z) {
						return domain.markerPositionAllowed(x, y, z);
					}
					@Override public long carrierMutationRevision() { return domain.carrierMutationRevision(); }
					@Override public CarrierMutationCoordinator carrierMutations() { return domain.carrierMutations(); }
					@Override public double particleKineticEnergyJoules() { return domain.particleKineticEnergyJoules(); }
					@Override public SignedDyadic exactInternalEnergy() { return domain.exactInternalEnergy(); }
					@Override public void markGridDirty() { domain.gridDirty = true; }
					@Override public void rebuildGrid() { domain.rebuildGridFromParticles(); }
					@Override public void ensureGrid() { domain.ensureInventoryGrid(); }
				});
		return new Stages(transition, reseed);
	}
}

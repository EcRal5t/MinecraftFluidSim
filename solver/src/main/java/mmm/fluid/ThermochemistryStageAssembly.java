package mmm.fluid;

import mmm.thermal.ThermalDomainState;

import mmm.chemistry.ChemicalParcelStore;
import mmm.chemistry.ChemicalMixtureRecord;
import mmm.granular.GranularCouplingFields;
import mmm.granular.GranularDepositDynamics;
import mmm.granular.GranularParcelStore;
import mmm.granular.GranularPhaseTransferEngine;
import mmm.thermal.ThermalTransport;
import mmm.units.SignedDyadic;

/** Constructs the thermochemistry pipeline from stage-specific state and capability seams. */
final class ThermochemistryStageAssembly {
	record Stages(
			ThermalTransportStage transport, ChemicalReactionStage chemistry,
			GranularReactionStage granularReaction, CondensedPhaseChangeStage condensedPhase,
			GasLiquidPhaseChangeStage gasPhase, ThermochemistryPipeline pipeline
	) { }

	private ThermochemistryStageAssembly() { }

	static Stages create(FluidDomain domain) {
		ThermalTransportStage transport = new ThermalTransportStage(
				new ThermalTransportStage.Workspace(domain.thermalMarkerContext()),
				new ThermalTransportStage.Access() {
					@Override public void ensureGrid() { domain.ensureInventoryGrid(); }
					@Override public java.util.List<mmm.thermal.ThermalCarrierTransport.Contact> carrierContacts() {
						return LiquidThermalConduction.contacts(domain);
					}
					@Override public ThermalDomainState thermalState() { return domain.thermalState; }
					@Override public double faceArea(int x, int y, int z, int direction) {
						return domain.thermalFaceArea(x, y, z, direction);
					}
					@Override public ThermalTransport.FixedBoundary fixedBoundary(int x, int y, int z, int direction) {
						return domain.thermalFixedBoundary(x, y, z, direction);
					}
				});
		ChemicalReactionStage chemistry = new ChemicalReactionStage(
				new ChemicalReactionStage.Workspace(
						domain.sizeX, domain.sizeY, domain.sizeZ, domain.config.cellSize(),
						domain.particles, domain.thermalMarkerContext()),
				new ChemicalReactionStage.Access() {
					@Override public void markGridDirty() { domain.gridDirty = true; }
					@Override public mmm.chemistry.ChemicalParcelStore chemicalParcels() {
						return domain.chemicalParcels;
					}
					@Override public FluidMaterialRegistry materialRegistry() { return domain.materialRegistry; }
					@Override public ThermalDomainState thermalState() { return domain.thermalState; }
				});
		GranularReactionStage granular = new GranularReactionStage(
				new GranularReactionStage.Workspace(
						domain.sizeX, domain.sizeY, domain.cellCount, domain.config.cellSize(),
						domain.particles, domain.pendingPhaseCellVolume, domain.thermalMarkerContext()),
				new GranularReactionStage.Access() {
					@Override public boolean enabled() { return domain.precipitationEnabled(); }
					@Override public ChemicalParcelStore chemistry() { return domain.chemicalParcels; }
					@Override public FluidMaterialRegistry materialRegistry() { return domain.materialRegistry; }
					@Override public GranularDomainState granularState() { return domain.granularState; }
					@Override public GranularPhaseTransferEngine transferEngine() {
						return domain.granularPhaseTransfer();
					}
					@Override public FlipGranularCollision collision() { return domain.granularCollision; }
					@Override public GranularCouplingFields coupling() { return domain.granularCoupling; }
					@Override public GranularDepositDynamics depositDynamics() {
						return domain.granularDepositDynamics();
					}
					@Override public ThermalDomainState thermalState() { return domain.thermalState; }
					@Override public int particleCell(int marker) { return domain.particleCellIndex(marker); }
					@Override public int parcelCell(GranularParcelStore.Parcel parcel) {
						return domain.granularParcelCell(parcel);
					}
					@Override public long geometricOpenSvq(int cell) { return domain.granularGeometricOpenSvq(cell); }
					@Override public float mergeVelocityDelta() { return domain.granularMergeVelocityDelta(); }
					@Override public void markGridDirty() { domain.gridDirty = true; }
				});
		PhaseChangeStageSupport phaseSupport = new PhaseChangeStageSupport(
				new PhaseChangeStageSupport.Workspace(
						domain.sizeX, domain.sizeY, domain.sizeZ, domain.cellCount,
						domain.maximumParticleCount, domain.config.cellSize(), domain.unitScale, domain.geometry,
						domain.markerGrid, domain.projection, domain.particles, domain.gasState,
						domain.pendingPhaseCellVolume, domain.thermalMarkerContext()),
				new PhaseChangeStageSupport.Access() {
					@Override public ThermalDomainState thermalState() { return domain.thermalState; }
					@Override public ChemicalParcelStore chemistry() { return domain.chemicalParcels; }
					@Override public FluidMaterialRegistry materialRegistry() { return domain.materialRegistry; }
					@Override public GranularDomainState granularState() { return domain.granularState; }
					@Override public float pressureReference() { return domain.pressureReference; }
					@Override public int particleCell(int marker) { return domain.particleCellIndex(marker); }
					@Override public int firstOpenSlot(int cell, int first) {
						return domain.firstOpenSlot(cell, first);
					}
					@Override public float[] markerSlotPosition(int cell, int slot) {
						return domain.markerSlotPosition(cell % domain.sizeX, cell / domain.sizeX % domain.sizeY,
								cell / (domain.sizeX * domain.sizeY), slot);
					}
					@Override public void removeParticle(int marker) { domain.removeParticleInternalPhase(marker); }
					@Override public void spawnParticle(
							int cellX, int cellY, int cellZ, int slot, float volume, int materialHandle,
							double mass, ChemicalMixtureRecord mixture
					) {
						domain.spawnParticle(
								cellX, cellY, cellZ, slot, volume, materialHandle, mass,
								mixture, false, false);
					}
					@Override public SignedDyadic exactInternalEnergy() {
						return domain.exactInternalEnergy();
					}
					@Override public void ensureGrid() { domain.ensureInventoryGrid(); }
					@Override public void refreshGasTopology() { domain.gasTopologyCoordinator.refresh(); }
					@Override public long gasGeometrySolidSvq() {
						return domain.pressureGranularCoupling == null ? 0L : domain.pressureGranularCoupling.totalSolidSvq();
					}
					@Override public double effectiveOpenCellVolume(int cell) { return domain.effectiveOpenCellVolume(cell); }
					@Override public GasTopologyCoordinator.GasTopologyPlan planGasGeometry(PhaseLiquidGeometryEdit edit, int node,
							mmm.fluid.air.AirNetwork.PhaseMutationPlan gas, double energy, ThermalDomainState.PhaseCellEnergyPlan thermal) {
						return domain.gasTopologyCoordinator.planAfterPhase(edit, node, gas, energy, thermal);
					}
					@Override public void requireGasGeometryCurrent(GasTopologyCoordinator.GasTopologyPlan topology,
							mmm.fluid.air.AirNetwork.PhaseMutationPlan gas, ThermalDomainState.PhaseCellEnergyPlan thermal) {
						domain.gasTopologyCoordinator.requireAfterPhaseCurrent(topology, gas, thermal);
					}
					@Override public void commitGasGeometry(GasTopologyCoordinator.GasTopologyPlan topology) {
						domain.rebuildGasPhaseGeometryFromParticles();
						domain.gasTopologyCoordinator.commit(topology);
					}
					@Override public void refreshCoherentOccupancy() { domain.refreshCoherentOccupancy(); }
					@Override public boolean canAddCarrierVolume(double volume) { return domain.canAddCarrierVolume(volume); }
					@Override public void markGridDirty() { domain.gridDirty = true; }
				});
		CondensedPhaseChangeStage condensed = new CondensedPhaseChangeStage(phaseSupport);
		GasLiquidPhaseChangeStage gas = new GasLiquidPhaseChangeStage(phaseSupport);
		return new Stages(transport, chemistry, granular, condensed, gas,
				new ThermochemistryPipeline(transport, chemistry, granular, condensed, gas));
	}
}

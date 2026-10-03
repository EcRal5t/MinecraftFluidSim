package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.thermal.ThermalDomainState;

import cc.ecisr.mmm.chemistry.ChemicalParcelStore;
import cc.ecisr.mmm.chemistry.ChemicalMixtureRecord;
import cc.ecisr.mmm.granular.GranularCouplingFields;
import cc.ecisr.mmm.granular.GranularDepositDynamics;
import cc.ecisr.mmm.granular.GranularParcelStore;
import cc.ecisr.mmm.granular.GranularPhaseTransferEngine;
import cc.ecisr.mmm.thermal.ThermalTransport;
import cc.ecisr.mmm.units.SignedDyadic;

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
					@Override public void ensureGrid() { domain.ensureGrid(); }
					@Override public java.util.List<cc.ecisr.mmm.thermal.ThermalCarrierTransport.Contact> carrierContacts() {
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
					@Override public cc.ecisr.mmm.chemistry.ChemicalParcelStore chemicalParcels() {
						return domain.chemicalParcels;
					}
					@Override public FluidMaterialRegistry materialRegistry() { return domain.materialRegistry; }
					@Override public ThermalDomainState thermalState() { return domain.thermalState; }
				});
		GranularReactionStage granular = new GranularReactionStage(
				new GranularReactionStage.Workspace(
						domain.sizeX, domain.sizeY, domain.cellCount,
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
					@Override public void ensureGrid() { domain.ensureGrid(); }
					@Override public void refreshGasTopology() { domain.gasTopologyCoordinator.refresh(); }
					@Override public void refreshGasGeometry() { domain.refreshGasPhaseGeometry(); }
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

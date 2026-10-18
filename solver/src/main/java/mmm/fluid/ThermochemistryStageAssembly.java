package mmm.fluid;

import mmm.inventory.LiquidCellInventory;

import mmm.thermal.ThermalDomainState;

import mmm.chemistry.ChemicalParcelStore;
import mmm.chemistry.ChemicalMixtureRecord;
import mmm.granular.GranularCouplingFields;
import mmm.granular.GranularDepositDynamics;
import mmm.granular.GranularParcelStore;
import mmm.granular.GranularPhaseTransferEngine;
import mmm.thermal.ThermalTransport;

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
				new ChemicalReactionStage.Workspace(domain.cellCount,domain.thermalMarkerContext()),
				new ChemicalReactionStage.Access() {
					@Override public void markGridDirty() { domain.gridDirty = true; }
					@Override public mmm.chemistry.ChemicalParcelStore chemicalParcels() {
						return domain.chemicalParcels;
					}
					@Override public LiquidCellInventory liquidCells() { return domain.liquidCells; }
					@Override public FluidMaterialRegistry materialRegistry() { return domain.materialRegistry; }
					@Override public ThermalDomainState thermalState() { return domain.thermalState; }
				});
		GranularReactionStage granular = new GranularReactionStage(domain);
		PhaseChangeStageSupport phaseSupport = new PhaseChangeStageSupport(
				new PhaseChangeStageSupport.Workspace(
						domain.sizeX, domain.sizeY, domain.sizeZ, domain.cellCount,
						domain.config.cellSize(), domain.unitScale, domain.geometry,
						domain.projection, domain.gasState,
						domain.pendingPhaseVolume, domain.thermalMarkerContext()),
				new PhaseChangeStageSupport.Access() {
					@Override public ThermalDomainState thermalState() { return domain.thermalState; }
					@Override public ChemicalParcelStore chemistry() { return domain.chemicalParcels; }
					@Override public FluidMaterialRegistry materialRegistry() { return domain.materialRegistry; }
					@Override public GranularDomainState granularState() { return domain.granularState; }
					@Override public float pressureReference() { return domain.pressureReference; }
					@Override public double liquidPressureAt(int cell,int support) { return domain.pressureSystem.liquidPressureAt(cell,support); }
					@Override public LiquidCellInventory liquidCells() { return domain.liquidCells; }
					@Override public CellMacStage.ExternalMomentum preparePhaseMomentum(LiquidCellInventory.Plan plan) { return domain.cellMacStage.prepareExternalMomentum(plan); }
					@Override public void commitPhaseMomentum(MacMomentumState momentum) { domain.mac.restoreMomentum(momentum); }
					@Override public void refreshGasTopology() { domain.gasTopologyCoordinator.refresh(); }
					@Override public GasTopologyCoordinator.GasTopologyPlan planGasBatchGeometry(LiquidCellInventory future,
							GasTopologyCoordinator.PhaseBatch phase) {
						return domain.gasTopologyCoordinator.planAfterPhaseBatch(future, phase);
					}
					@Override public void requireGasBatchGeometryCurrent(GasTopologyCoordinator.GasTopologyPlan topology,
							GasTopologyCoordinator.PhaseBatch phase) {
						domain.gasTopologyCoordinator.requireAfterPhaseBatchCurrent(topology, phase);
					}
					@Override public void commitGasGeometry(GasTopologyCoordinator.GasTopologyPlan topology) {
						domain.gasTopologyCoordinator.commit(topology);
					}
					@Override public GasTopologyCoordinator.GasTopologyPlan planCondensedGeometry(LiquidCellInventory future,java.util.List<mmm.thermal.CoherentPhaseStore.Group> coherent) {
						var occupancy=new GranularCouplingFields(domain.sizeX,domain.sizeY,domain.sizeZ,domain.config.cellSize());
						long[] fixed=new long[domain.cellCount];for(var group:coherent) fixed[group.cell()]=Math.addExact(fixed[group.cell()],group.occupiedVolumeSvq());
						occupancy.setFixedCellSvq(fixed);
						if(domain.granularState!=null) occupancy.rebuild(domain.granularState.parcels,domain.granularState.deposits);
						return domain.gasTopologyCoordinator.planAfterLiquidCells(future,occupancy.copySolidSvq(),false);
					}
					@Override public void requireGeometryCurrent(GasTopologyCoordinator.GasTopologyPlan plan) { domain.gasTopologyCoordinator.requireBeforeCarrierCommit(plan); }
					@Override public void refreshCoherentOccupancy() { domain.refreshCoherentOccupancy(); }
					@Override public void markGridDirty() { domain.markCellInventoryChanged(); }
				});
		CondensedPhaseChangeStage condensed = new CondensedPhaseChangeStage(phaseSupport);
		GasLiquidPhaseChangeStage gas = new GasLiquidPhaseChangeStage(phaseSupport);
		return new Stages(transport, chemistry, granular, condensed, gas,
				new ThermochemistryPipeline(transport, chemistry, granular, condensed, gas));
	}
}

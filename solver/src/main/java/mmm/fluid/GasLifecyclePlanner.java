package mmm.fluid;

import java.util.Collections;
import java.util.List;
import mmm.fluid.air.GasInventory;
import mmm.fluid.air.GasRegionAudit;
import mmm.fluid.air.SpatialGasState;
import mmm.units.SignedDyadic;

/** Removes local gas and its carried energy and momentum in one operator transaction. */
final class GasLifecyclePlanner {
    private final HostFluidState domain;
    GasLifecyclePlanner(HostFluidState domain){this.domain=domain;}
    FluidDomainContracts.OperatorGasVentPlan planVent(){
        if(domain.hasPendingPhaseMotionWork())throw new IllegalStateException("Pending phase motion must complete before gas vent");
        domain.domainCapabilities.initializeSpatialGas();
        var before=domain.gasState.spatial;var removed=GasRegionAudit.aggregate(before.cells());
        var energy=SignedDyadic.sum(before.internalEnergy());
        var after=new SpatialGasState(before.grid(),before.registryFingerprint(),before.chemicalRegistryFingerprint(),
                Collections.nCopies(domain.cellCount,GasInventory.EMPTY),new double[domain.cellCount],before.externalSource(),before.escapedSink().add(removed),before.phaseSource(),before.phaseSink());
        var gas=new SpatialGasEdits.Plan(before,after,SignedDyadic.ZERO,energy,SignedDyadic.ZERO,SpatialGasEdits.ventAll(before));
        var edit=HostMixtureEdit.prepare(domain,domain.liquidCells.planEdits(List.of()),gas,null);
        int count=(int)before.cells().stream().filter(stock->!stock.isEmpty()).count();
        return new FluidDomainContracts.OperatorGasVentPlan(count,removed,energy,edit,
                domain.thermalState==null?null:domain.thermalState.thermalRevision(),domain.mac.momentumRevision());
    }
    FluidDomainContracts.OperatorGasVent commitVent(FluidDomainContracts.OperatorGasVentPlan plan){
        if(plan==null)throw new IllegalArgumentException("Gas vent plan is missing");
        var edit=plan.edit();edit.inventory().requireCurrent();edit.gas().requireCurrent(domain);
        if(domain.mac.momentumRevision()!=plan.momentumRevision())throw new IllegalStateException("Momentum changed after gas vent preparation");
        if(domain.thermalState==null?plan.thermalRevision()!=null:!domain.thermalState.thermalRevision().equals(plan.thermalRevision()))
            throw new IllegalStateException("Thermal state changed after gas vent preparation");
        var audit=domain.thermalState==null?null:edit.gas().applyAudit(domain.thermalState.auditLedger())
                .withMechanicalWork(edit.inventory().mechanicalHeat()).withRoundoff(edit.inventory().energyRoundoff());
        if(audit!=null)audit.expectedInventory();
        edit.inventory().commit();edit.gas().commit(domain);domain.cellMacStage.commitMomentum(edit.momentum());
        if(audit!=null)domain.thermalState.restoreAuditLedger(audit);
        domain.markCellInventoryChanged();domain.gasState.pressureActive=true;
        return new FluidDomainContracts.OperatorGasVent(plan.removed(),plan.internalEnergy(),plan.nodeCount());
    }
}

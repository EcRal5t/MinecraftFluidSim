package mmm.fluid;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import mmm.inventory.LiquidCellInventory;
import mmm.units.SignedDyadic;

/** Solid edits share the local mixture equation, gas relocation and face momentum transaction. */
final class GranularLiquidDisplacement {
    private GranularLiquidDisplacement() { }
    static final class CapacityUnavailable extends IllegalStateException {
        CapacityUnavailable(RuntimeException cause){super(cause.getMessage(),cause);}
    }
    static final class Prepared {
        final LiquidCellInventory.Plan inventory;
        final SpatialGasEdits.Plan localGas;
        final CellMacStage.ExternalMomentum momentum;
        final SignedDyadic energyRoundoff;
        Prepared(HostMixtureEdit edit){inventory=edit.inventory();localGas=edit.gas();momentum=edit.momentum();energyRoundoff=inventory.energyRoundoff();}
    }
    static long[] begin(HostFluidState domain){
        if(!domain.domainPhaseInventory.hasGranularInventory())return null;
        domain.domainFlowFields.ensureInventoryGrid();
        domain.granularCoupling.rebuild(domain.granularState.parcels,domain.granularState.deposits);
        return domain.entityBodyCoupling.addTo(domain.granularCoupling.copySolidSvq());
    }
    static void commit(HostFluidState domain,long[] previous){
        if(previous==null)return;
        var edit=prepare(domain,previous,domain.entityBodyCoupling.addTo(domain.granularCoupling.copySolidSvq()));
        if(edit!=null)publish(domain,edit,()->{});
    }
    static void publish(HostFluidState domain,Prepared edit,Runnable solidPublication){
        edit.inventory.requireCurrent();if(edit.localGas!=null)edit.localGas.requireCurrent(domain);
        if(domain.thermalState!=null){
            var audit=domain.thermalState.auditLedger().withMechanicalWork(edit.inventory.mechanicalHeat()).withRoundoff(edit.energyRoundoff);
            if(edit.localGas!=null)audit=edit.localGas.applyAudit(audit);
            audit.expectedInventory();
        }
        edit.inventory.commit();domain.cellMacStage.commitMomentum(edit.momentum);solidPublication.run();
        if(edit.localGas!=null)edit.localGas.commit(domain);
        if(domain.thermalState!=null){
            var audit=domain.thermalState.auditLedger().withMechanicalWork(edit.inventory.mechanicalHeat()).withRoundoff(edit.energyRoundoff);
            if(edit.localGas!=null)audit=edit.localGas.applyAudit(audit);
            domain.thermalState.restoreAuditLedger(audit);
        }
        domain.markCellInventoryChanged();
    }
    static Prepared prepare(HostFluidState domain,long[] previous,long[] next){
        if(previous==null||next==null||previous.length!=domain.cellCount||next.length!=domain.cellCount)
            throw new IllegalArgumentException("Solid edit requires source and future occupancy");
        if(Arrays.equals(previous,next))return null;
        var changed=new ArrayList<Integer>();
        for(int cell=0;cell<domain.cellCount;cell++){
            if(next[cell]<0||Math.scalb((double)next[cell],-24)>domain.geometry.cutCells.openCellVolume(cell))
                throw new CapacityUnavailable(new IllegalArgumentException("Solid inventory exceeds fixed capacity at cell "+cell));
            if(previous[cell]!=next[cell])changed.add(cell);
        }
        try{return new Prepared(HostMixtureEdit.prepare(domain,domain.liquidCells.planEdits(List.of()),next,null,changed));}
        catch(GasTopologyBlockedException unavailable){throw new CapacityUnavailable(unavailable);}
    }
    static Prepared prepareInsertion(HostFluidState domain,long[] previous,long[] next){return prepare(domain,previous,next);}
    static Prepared prepareExternal(HostFluidState domain,long[] previous,long[] next){return prepare(domain,previous,next);}
}

package mmm.fluid;

import java.util.*;
import mmm.thermal.ThermalMaterialDefinition;
import mmm.thermal.ThermalStateFunction;
import mmm.units.SignedDyadic;

/** Finite-volume acquisition of real region solids by a newly installed pipe cavity. */
public final class PipeRegionGranular {
    private PipeRegionGranular() { }
    /** Reads the same exposed owners as exact extraction without modifying the region. */
    public static List<mmm.granular.GranularTransferLedger.Portion> capture(HostFluidState host,int cell,FluidDefinitions definitions) {
        var granular=host.granularState;if(granular==null)return List.of();
        var ledger=new GranularTransferPlanner.GranularLedgerBuilder();var energy=SignedDyadic.ZERO;boolean found=false;
        var column=host.thermalState.granularColumn();
        for(int i=0;i<granular.parcels.count();i++) {
            var parcel=granular.parcels.parcel(i);if(host.domainInventoryGeometry.granularParcelCell(parcel)!=cell)continue;
            String id=granular.registry.material(parcel.materialHandle()).id();if(!ledger.canAccept(id))continue;
            var composition=parcel.solidCompositionHandle()==0?null:granular.compositions.record(parcel.solidCompositionHandle());
            ledger.add(id,parcel.solidVolumeSvq(),parcel.massUnits(),composition);found=true;
            double vx=parcel.velocityX(),vy=parcel.velocityY(),vz=parcel.velocityZ();
            energy=energy.add(SignedDyadic.of(column.activeEnergy(i))).add(SignedDyadic.of(.5*parcel.mass()*(vx*vx+vy*vy+vz*vz)));
        }
        var group=granular.deposits.dominantGroup(cell);
        if(group!=null) {
            String id=granular.registry.material(group.materialHandle()).id();
            if(ledger.canAccept(id)) {
                var composition=group.solidCompositionHandle()==0?null:granular.compositions.record(group.solidCompositionHandle());
                ledger.add(id,group.solidVolumeSvq(),group.massUnits(),composition);found=true;
                energy=energy.add(SignedDyadic.of(column.depositEnergy(new mmm.thermal.GranularEnergyColumn.DepositKey(cell,group.materialHandle(),group.solidCompositionHandle()))));
            }
        }
        return found?portions(ledger.build(),energy,definitions):List.of();
    }
    public static void insert(HostFluidState host,int cell,List<mmm.granular.GranularTransferLedger.Portion> deposits,FluidDefinitions definitions) {
        if(deposits.isEmpty())return;
        var grid=host.grid;int x=grid.x(cell),y=grid.y(cell),z=grid.z(cell);
        for(var deposit:deposits) {
            definitions.granular().materialHandle(deposit.inventory().materialId());
            var result=host.granularTransferPlanner.insertExact(x,y,z,new mmm.granular.GranularTransferLedger(List.of(deposit.inventory())),
                    0,true,deposit.internalEnergy(),false);
            if(!result.inserted())throw new IllegalStateException("Pipe solid return: "+result.failure());
        }
        host.granularCoupling.rebuild(host.granularState.parcels,host.granularState.deposits);
    }
    public static List<mmm.granular.GranularTransferLedger.Portion> extract(HostFluidState host,int cell,double fraction,FluidDefinitions definitions) {
        if(!(Double.isFinite(fraction)&&fraction>=0&&fraction<=1))throw new IllegalArgumentException("Invalid pipe solid fraction");
        if(host.granularState==null||fraction==0)return List.of();
        var grid=host.grid;int x=grid.x(cell),y=grid.y(cell),z=grid.z(cell);
        long available=host.granularSolidVolumeSvq(x,y,z);
        long requested=Math.min(available,(long)Math.floor(available*fraction));
        if(requested==0)return List.of();
        var kineticBefore=kinetic(host);
        var extracted=host.granularTransferPlanner.extractExact(x,y,z,requested,0,true,ignored->true,false);
        if(!extracted.transferred())throw new IllegalStateException("Pipe solid acquisition: "+extracted.failure());
        host.granularCoupling.rebuild(host.granularState.parcels,host.granularState.deposits);
        var energy=SignedDyadic.of(extracted.internalEnergy()).add(kineticBefore.subtract(kinetic(host)));
        return portions(extracted.ledger(),energy,definitions);
    }
    /** A stationary pipe retains extracted parcel kinetic energy as heat in its solid inventory. */
    private static SignedDyadic kinetic(HostFluidState host) {
        var result=SignedDyadic.ZERO;var parcels=host.granularState.parcels;
        for(int i=0;i<parcels.count();i++) {
            var p=parcels.parcel(i);double vx=p.velocityX(),vy=p.velocityY(),vz=p.velocityZ();
            result=result.add(SignedDyadic.of(.5*p.mass()*(vx*vx+vy*vy+vz*vz)));
        }
        return result;
    }
    public static List<mmm.granular.GranularTransferLedger.Portion> portions(mmm.granular.GranularTransferLedger ledger,SignedDyadic energy,FluidDefinitions definitions) {
        var function=new ThermalStateFunction(definitions.thermal());var owners=new ArrayList<ThermalStateFunction.InventoryPortion>();
        for(var group:ledger.groups()) {
            int handle=definitions.thermal().materialHandle(ThermalMaterialDefinition.CarrierKind.GRANULAR,group.materialId());
            var species=group.composition()==null?Map.<String,Long>of():group.composition().stableAmounts(definitions.chemistry());
            owners.add(new ThermalStateFunction.InventoryPortion(handle,group.mass(),species));
        }
        double temperature=function.stateForInventory(owners,energy).temperatureK();
        int pivot=0;for(int i=1;i<owners.size();i++)if(owners.get(i).massKg()>owners.get(pivot).massKg())pivot=i;
        double[] energies=new double[owners.size()];var rest=SignedDyadic.ZERO;
        for(int i=0;i<owners.size();i++)if(i!=pivot) {
            var owner=owners.get(i);energies[i]=function.internalEnergyAtTemperature(owner.materialHandle(),owner.massKg(),owner.speciesQuanta(),temperature);
            rest=rest.add(SignedDyadic.of(energies[i]));
        }
        energies[pivot]=energy.subtract(rest).toDouble();var result=new ArrayList<mmm.granular.GranularTransferLedger.Portion>();
        for(int i=0;i<owners.size();i++) {
            var owner=owners.get(i);function.stateForInternalEnergy(owner.materialHandle(),owner.massKg(),owner.speciesQuanta(),energies[i]);
            result.add(new mmm.granular.GranularTransferLedger.Portion(ledger.groups().get(i),energies[i]));
        }
        return List.copyOf(result);
    }
}

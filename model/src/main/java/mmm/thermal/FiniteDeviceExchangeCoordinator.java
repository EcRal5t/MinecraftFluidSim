package mmm.thermal;

import mmm.units.SignedDyadic;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Plans and publishes finite device heat directly on the contacted caloric owners. */
public final class FiniteDeviceExchangeCoordinator {
    /** Explicit heat input or the per-owner result of a finite contact solve. */
    public record CellEnergyDelta(int cell,double internalEnergyDeltaJ,List<FiniteThermalExchange.CarrierDelta> carriers){
        public CellEnergyDelta { if(cell<0||!Double.isFinite(internalEnergyDeltaJ))throw new IllegalArgumentException("Invalid cell heat input");carriers=List.copyOf(carriers); }
        public CellEnergyDelta(int cell,double energy){this(cell,energy,List.of());}
    }
    public record CellContactRequest(int cell,int ordinal,double conductanceWPerK){
        public CellContactRequest { if(cell<0||ordinal<0||!(conductanceWPerK>0&&Double.isFinite(conductanceWPerK)))throw new IllegalArgumentException("Invalid device contact"); }
    }
    public record CarrierCaloricState(int cell,ThermalMaterialDefinition material,double mass,double baseEnergy,double energy) { }
    public record FiniteDeviceCellPlan(List<CarrierCaloricState> before,double[] after,
            ThermalDomainState.Revision revisionBefore,SignedDyadic inventoryBefore,SignedDyadic requestedDelta){
        public FiniteDeviceCellPlan { before=List.copyOf(before);after=after.clone(); }
        @Override public double[] after(){return after.clone();}
    }
    private final ThermalDomainState domain;
    FiniteDeviceExchangeCoordinator(ThermalDomainState domain){this.domain=domain;}
    private static List<CarrierCaloricState> snapshot(ThermalCellGrid.CarrierAccess access){
        var rows=new ArrayList<CarrierCaloricState>();
        for(int owner=0;owner<access.carrierCount();owner++)rows.add(new CarrierCaloricState(access.cell(owner),access.material(owner),access.mass(owner),access.baseEnergy(owner),access.internalEnergy(owner)));
        return List.copyOf(rows);
    }
    public List<FiniteThermalExchange.Contact> finiteDeviceContacts(List<CellContactRequest> requests,ThermalCarrierContext markers){
        if(requests==null||requests.isEmpty()||requests.size()>ThermalLimits.MAX_FINITE_DEVICE_CONTACTS)throw new IllegalArgumentException("Invalid finite contacts");
        var access=domain.exchangeCarrierAccess(markers);var canonical=new ArrayList<>(requests);
        canonical.sort(java.util.Comparator.comparingInt(CellContactRequest::ordinal));
        var seen=new java.util.HashSet<Integer>();var cells=new java.util.HashSet<Integer>();var result=new ArrayList<FiniteThermalExchange.Contact>();
        for(var request:canonical){
            if(!seen.add(request.ordinal())||!cells.add(request.cell()))throw new IllegalArgumentException("Repeated finite contact");
            double volume=0;for(int owner=0;owner<access.carrierCount();owner++)if(access.cell(owner)==request.cell())volume+=access.volume(owner);
            var carriers=new ArrayList<FiniteThermalExchange.Carrier>();
            for(int owner=0;owner<access.carrierCount();owner++)if(access.cell(owner)==request.cell()&&access.volume(owner)>0)
                carriers.add(new FiniteThermalExchange.Carrier(owner,access.material(owner),access.mass(owner),access.baseEnergy(owner),access.internalEnergy(owner),access.material(owner).conductivityWPerMeterK()==0?0:access.volume(owner)/volume));
            if(carriers.isEmpty())throw new IllegalArgumentException("Device contact has no conducting owner");
            result.add(new FiniteThermalExchange.Contact(request.ordinal(),request.conductanceWPerK(),carriers));
        }
        return List.copyOf(result);
    }
    public FiniteDeviceCellPlan planFiniteDeviceCellExchange(List<CellEnergyDelta> deltas,ThermalCarrierContext markers){
        if(deltas==null||deltas.isEmpty()||deltas.size()>ThermalLimits.MAX_FINITE_DEVICE_CONTACTS)throw new IllegalArgumentException("Invalid finite heat changes");
        var access=domain.exchangeCarrierAccess(markers);var before=snapshot(access);
        double[] after=before.stream().mapToDouble(CarrierCaloricState::energy).toArray();
        var cells=new java.util.HashSet<Integer>();var requested=SignedDyadic.ZERO;
        for(var delta:deltas){
            if(!cells.add(delta.cell()))throw new IllegalArgumentException("Repeated heat change cell");
            requested=requested.add(SignedDyadic.of(delta.internalEnergyDeltaJ()));
            if(!delta.carriers().isEmpty()){
                var sum=SignedDyadic.ZERO;
                for(var write:delta.carriers()){
                    int owner=write.owner();
                    if(owner<0||owner>=before.size()||before.get(owner).cell()!=delta.cell()||write.beforeEnergy()!=after[owner])throw new IllegalArgumentException("Device caloric owner changed");
                    sum=sum.add(SignedDyadic.of(write.afterEnergy()).subtract(SignedDyadic.of(write.beforeEnergy())));after[owner]=write.afterEnergy();
                }
                if(sum.toDouble()!=delta.internalEnergyDeltaJ())throw new IllegalArgumentException("Device owner heat differs from its contact total");
            }else{
                // Explicit source heat changes each existing owner's U; it preserves every initial temperature difference.
                double capacity=0;int pivot=-1;double largest=0;
                for(int owner=0;owner<before.size();owner++)if(before.get(owner).cell()==delta.cell()){
                    var row=before.get(owner);double c=new ThermalContactExchange.Carrier(row.material(),row.mass(),row.baseEnergy(),row.energy()).heatCapacityJPerK();capacity+=c;
                    if(c>largest){largest=c;pivot=owner;}
                }
                if(pivot<0)throw new IllegalArgumentException("Heat input names an empty cell");
                double remaining=delta.internalEnergyDeltaJ();
                for(int owner=0;owner<before.size();owner++)if(owner!=pivot&&before.get(owner).cell()==delta.cell()){
                    var row=before.get(owner);double c=new ThermalContactExchange.Carrier(row.material(),row.mass(),row.baseEnergy(),row.energy()).heatCapacityJPerK();
                    double heat=delta.internalEnergyDeltaJ()*c/capacity;after[owner]+=heat;remaining-=heat;
                }
                after[pivot]+=remaining;
            }
        }
        for(int owner=0;owner<after.length;owner++){var row=before.get(owner);new ThermalContactExchange.Carrier(row.material(),row.mass(),row.baseEnergy(),after[owner]);}
        return new FiniteDeviceCellPlan(before,after,domain.thermalRevision(),domain.exactEnergy(markers.markerCount()),requested);
    }
    public void commitFiniteDeviceCellExchange(FiniteDeviceCellPlan plan,ThermalCarrierContext markers){
        var access=domain.exchangeCarrierAccess(markers);
        if(!domain.matchesThermalRevision(plan.revisionBefore())||!plan.before().equals(snapshot(access))||!plan.inventoryBefore().equals(domain.exactEnergy(markers.markerCount())))throw new IllegalArgumentException("Finite thermal plan is stale");
        access.setInternalEnergies(plan.after());
        var actual=domain.exactEnergy(markers.markerCount()).subtract(plan.inventoryBefore());
        domain.audit().update(ledger->ledger.withFiniteDeviceExchange(plan.requestedDelta()).withRoundoff(actual.subtract(plan.requestedDelta())));
    }
}

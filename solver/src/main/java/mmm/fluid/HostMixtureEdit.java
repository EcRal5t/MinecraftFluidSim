package mmm.fluid;

import java.util.List;
import java.util.TreeMap;
import mmm.inventory.LiquidCellInventory;
import mmm.units.SignedDyadic;

/** One prepared liquid/gas successor, face momentum and mixing heat for host inventory edits. */
record HostMixtureEdit(LiquidCellInventory.Plan inventory, SpatialGasEdits.Plan gas,
                       CellMacStage.ExternalMomentum momentum) {
    static HostMixtureEdit prepare(HostFluidState domain,LiquidCellInventory.Plan liquid,
                                  long[] occupied,byte[] masks,List<Integer> changed) {
        if(domain.gasState.frame!=null)domain.domainCapabilities.initializeSpatialGas();
        var gas=SpatialGasEdits.prepare(domain,liquid.future(),occupied,masks,changed);
        return prepare(domain,liquid,gas,masks);
    }
    static HostMixtureEdit prepare(HostFluidState domain,LiquidCellInventory.Plan liquid,
                                  SpatialGasEdits.Plan gas,byte[] masks) {
        var momentum=domain.cellMacStage.prepareExternalMomentum(liquid,masks,gas);
        var liquidHeat=new TreeMap<Integer,SignedDyadic>();var gasHeat=new TreeMap<Integer,SignedDyadic>();
        for(var entry:momentum.heatByCell().entrySet()){
            int cell=entry.getKey();long liquidMass=0;
            for(var stock:liquid.future().physicalStocksInCell(cell))liquidMass=Math.addExact(liquidMass,stock.massUnits());
            long gasMass=gas==null?0:gas.after().cells().get(cell).totalMassUnits();
            var heat=entry.getValue();double liquidCapacity=liquidMass,gasCapacity=gasMass;
            if(domain.thermalState!=null){
                liquidCapacity=0;
                for(var stock:liquid.future().physicalStocksInCell(cell)){
                    var composition=stock.mixture()==null?java.util.Map.<String,Long>of():stock.mixture().stableAmounts(domain.liquidCells.chemicalRegistry());
                    liquidCapacity+=domain.thermalState.stateFunction().stateForInternalEnergy(
                            domain.thermalState.thermalMaterialHandleForFluid(stock.key().material()),stock.massKilograms(),composition,stock.internalEnergy()).heatCapacityJPerK();
                }
                if(gasMass>0)gasCapacity=domain.domainCapabilities.spatialGasModel().state(gas.after().cells().get(cell),gas.after().energyAt(cell)).heatCapacityJPerK();
            }
            var first=gasMass==0?heat:liquidMass==0?SignedDyadic.ZERO:
                    SignedDyadic.of(heat.toDouble()*liquidCapacity/(liquidCapacity+gasCapacity));
            if(first.signum()!=0)liquidHeat.put(cell,first);
            var second=heat.subtract(first);if(second.signum()!=0)gasHeat.put(cell,second);
        }
        return new HostMixtureEdit(liquid.withMechanicalHeat(liquidHeat),gas==null?null:gas.withHeat(domain,gasHeat),momentum);
    }
}

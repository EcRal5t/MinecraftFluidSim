package mmm.thermal;

import mmm.fluid.air.AirNetwork;
import mmm.units.SignedDyadic;
import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;

/** Spatial samples of a well-mixed chamber. Publication sums heat back into its sole node U. */
final class ThermalGasCarriers implements ThermalCellGrid.CarrierAccess {
    private record Portion(int cell,int node,int support,double volume,ThermalMaterialDefinition material,
                           double mass,double base,double energy,double heatCapacity) { }
    private final ThermalCellGrid.CarrierAccess condensed;
    private final GasEnergyStore gas;
    private final AirNetwork network;
    private final ArrayList<Portion> portions=new ArrayList<>();
    private final double[] energy;
    ThermalGasCarriers(ThermalCellGrid.CarrierAccess condensed,GasEnergyStore gas,
                       ThermalRegistry registry,ThermalCarrierContext context) {
        this.condensed=condensed;this.gas=gas;
        var supports=context.gasThermalSupports();network=context.gasNetwork();
        double[] volume=new double[network.nodeCount()+1];
        for(var support:supports)volume[support.node()]+=support.normalizedVolume();
        var function=new ThermalStateFunction(registry);
        record Component(ThermalMaterialDefinition material,double mass,double base,double energy,double fraction,double heatCapacity) { }
        var components=new ArrayList<java.util.List<Component>>(network.nodeCount()+1);components.add(java.util.List.of());
        for(int node=1;node<=network.nodeCount();node++) {
            var inventory=network.inventory(node);var entries=new ArrayList<Component>();
            if(!inventory.isEmpty()) {
                double temperature=gas.temperature(node,network);
                for(var component:inventory.components()) {
                    int handle=registry.materialHandle(ThermalMaterialDefinition.CarrierKind.GAS,component.componentId());
                    var species=new TreeMap<String,Long>();
                    for(var amount:component.species())species.put(amount.speciesId(),amount.amountQuanta());
                    var amounts=Map.copyOf(species);
                    entries.add(new Component(registry.material(handle),component.mass(),
                            function.baseEnergy(handle,component.mass(),amounts),
                            function.internalEnergyAtTemperature(handle,component.mass(),amounts,temperature),
                            (double)component.standardVolumeNanos()/inventory.standardVolumeNanos(),
                            component.mass()*registry.material(handle).specificHeatAt(temperature)));
                }
            }
            components.add(java.util.List.copyOf(entries));
        }
        for(var support:supports) {
            double share=support.normalizedVolume()/volume[support.node()];
            for(var component:components.get(support.node()))portions.add(new Portion(support.cell(),support.node(),
                    support.supportMask(),support.normalizedVolume()*component.fraction(),component.material(),
                    component.mass()*share,component.base()*share,component.energy()*share,component.heatCapacity()*share));
        }
        energy=portions.stream().mapToDouble(Portion::energy).toArray();
        // The largest heat capacity carries the stored-node rounding remainder.
        int[] pivot=new int[network.nodeCount()+1];java.util.Arrays.fill(pivot,-1);
        var sum=new SignedDyadic[pivot.length];java.util.Arrays.fill(sum,SignedDyadic.ZERO);
        for(int i=0;i<portions.size();i++) {
            int node=portions.get(i).node();
            if(pivot[node]<0||portions.get(i).heatCapacity()>portions.get(pivot[node]).heatCapacity())pivot[node]=i;
            sum[node]=sum[node].add(SignedDyadic.of(energy[i]));
        }
        for(int node=1;node<pivot.length;node++)if(pivot[node]>=0) {
            int i=pivot[node];energy[i]=SignedDyadic.of(gas.nodeEnergy(node,network))
                    .subtract(sum[node].subtract(SignedDyadic.of(energy[i]))).toDouble();
        }
    }
    private int index(int carrier){return carrier-condensed.carrierCount();}
    private Portion portion(int carrier){return portions.get(index(carrier));}
    @Override public int carrierCount(){return condensed.carrierCount()+portions.size();}
    @Override public int cell(int carrier){return index(carrier)<0?condensed.cell(carrier):portion(carrier).cell();}
    @Override public int supportMask(int carrier){return index(carrier)<0?condensed.supportMask(carrier):portion(carrier).support();}
    @Override public ThermalMaterialDefinition material(int carrier){return index(carrier)<0?condensed.material(carrier):portion(carrier).material();}
    @Override public double mass(int carrier){return index(carrier)<0?condensed.mass(carrier):portion(carrier).mass();}
    @Override public double volume(int carrier){return index(carrier)<0?condensed.volume(carrier):portion(carrier).volume();}
    @Override public double baseEnergy(int carrier){return index(carrier)<0?condensed.baseEnergy(carrier):portion(carrier).base();}
    @Override public double internalEnergy(int carrier){return index(carrier)<0?condensed.internalEnergy(carrier):energy[index(carrier)];}
    @Override public SignedDyadic exactStoredEnergy(){return condensed.exactStoredEnergy().add(gas.exactGasEnergy());}
    @Override public void setInternalEnergy(int carrier,double value){
        if(index(carrier)<0){condensed.setInternalEnergy(carrier,value);return;}
        double[] nodes=gas.nodeEnergies();int i=index(carrier),node=portion(carrier).node()-1;
        nodes[node]=SignedDyadic.of(nodes[node]).add(SignedDyadic.of(value)).subtract(SignedDyadic.of(energy[i])).toDouble();energy[i]=value;
        gas.validateRestoredNodeEnergies(network,nodes);gas.replaceNodeEnergies(nodes);
    }
    @Override public void setInternalEnergies(double[] values){
        if(values.length!=carrierCount())throw new IllegalArgumentException("Thermal exchange layout differs");
        double[] nodes=gas.nodeEnergies();var sums=new SignedDyadic[nodes.length];java.util.Arrays.fill(sums,SignedDyadic.ZERO);
        for(int i=0;i<portions.size();i++)sums[portions.get(i).node()-1]=sums[portions.get(i).node()-1].add(SignedDyadic.of(values[condensed.carrierCount()+i]));
        for(int node=0;node<nodes.length;node++)if(!network.inventory(node+1).isEmpty())nodes[node]=sums[node].toDouble();
        gas.validateRestoredNodeEnergies(network,nodes);
        condensed.setInternalEnergies(java.util.Arrays.copyOf(values,condensed.carrierCount()));
        System.arraycopy(values,condensed.carrierCount(),energy,0,energy.length);gas.replaceNodeEnergies(nodes);
    }
}

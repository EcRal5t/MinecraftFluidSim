package mmm.thermal;

import mmm.fluid.air.AirNetwork;
import mmm.units.SignedDyadic;
import java.util.ArrayList;

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
        record Mixture(ThermalMaterialDefinition material,double mass,double base,double energy,double capacity) { }
        var mixtures=new Mixture[network.nodeCount()+1];
        var model=new GasThermalModel(registry,network.registry());
        for(int node=1;node<=network.nodeCount();node++) {
            var inventory=network.inventory(node);if(inventory.isEmpty())continue;
            var materials=new ArrayList<ThermalMaterialDefinition>();var masses=new ArrayList<Double>();
            var cuts=new java.util.TreeSet<Double>();double conductivity=0;
            for(var component:inventory.components()) {
                var material=registry.material(registry.materialHandle(ThermalMaterialDefinition.CarrierKind.GAS,component.componentId()));
                materials.add(material);masses.add(component.mass());
                for(var segment:material.caloricSegments()){cuts.add(segment.minimumTemperatureK());cuts.add(segment.maximumTemperatureK());}
                conductivity+=material.conductivityWPerMeterK()*(double)component.standardVolumeNanos()/inventory.standardVolumeNanos();
            }
            double mass=inventory.totalMass(),reference=materials.getFirst().referenceTemperatureK();
            var segments=new ArrayList<ThermalMaterialDefinition.CaloricSegment>();
            var points=new ArrayList<>(cuts);
            for(int i=1;i<points.size();i++){
                double low=points.get(i-1),high=points.get(i),cv=0;
                for(int c=0;c<materials.size();c++)cv+=masses.get(c)*materials.get(c).specificHeatAt(low+(high-low)*.5);
                segments.add(new ThermalMaterialDefinition.CaloricSegment(low,high,cv/mass));
            }
            var material=new ThermalMaterialDefinition("mmm:thermal/gas_mixture",ThermalMaterialDefinition.CarrierKind.GAS,
                    "mmm:gas_mixture",reference,0,conductivity,segments);
            double temperature=gas.temperature(node,network);
            mixtures[node]=new Mixture(material,mass,model.internalEnergyAtTemperature(inventory,reference),
                    gas.nodeEnergy(node,network),mass*material.specificHeatAt(temperature));
        }
        for(var support:supports) {
            var mixture=mixtures[support.node()];if(mixture==null)continue;
            double share=support.normalizedVolume()/volume[support.node()];
            portions.add(new Portion(support.cell(),support.node(),support.supportMask(),support.normalizedVolume(),
                    mixture.material(),mixture.mass()*share,mixture.base()*share,mixture.energy()*share,mixture.capacity()*share));
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

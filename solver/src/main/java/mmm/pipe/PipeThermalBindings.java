package mmm.pipe;

import java.util.HashMap;
import mmm.fluid.FluidDefinitions;
import mmm.inventory.LiquidCellInventory;
import mmm.thermal.ThermalMaterialDefinition;
import mmm.thermal.ThermalStateFunction;
import mmm.units.Mass;

/** Pipe liquid species retain their caloric reference energy during routing. */
public final class PipeThermalBindings {
    private PipeThermalBindings() { }
    public static LiquidCellInventory.EnergyPartitioner liquid(PipeNetworkState state,FluidDefinitions definitions) {
        var function=new ThermalStateFunction(definitions.thermal());
        return (source,masses,species,largest,energies) -> {
            var id=state.materialIds().get(source.key().material());
            int material=definitions.thermal().materialHandle(ThermalMaterialDefinition.CarrierKind.LIQUID,id);
            var mixture=source.mixture();
            double reference=function.baseEnergy(material,source.massKilograms(),mixture,definitions.chemistry());
            double sensible=source.internalEnergy()-reference;
            for(int i=0;i<masses.length;i++)if(i!=largest && masses[i]>0) {
                var amounts=new HashMap<String,Long>();
                for(int j=0;j<species.length;j++)if(species[j][i]>0)
                    amounts.put(definitions.chemistry().species(mixture.speciesHandle(j)).id(),species[j][i]);
                energies[i]=function.baseEnergy(material,Mass.kilograms(masses[i]),amounts)
                        +sensible*masses[i]/(double)source.massUnits();
            }
        };
    }
}

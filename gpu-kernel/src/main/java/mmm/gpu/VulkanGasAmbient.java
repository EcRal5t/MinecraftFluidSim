package mmm.gpu;

import java.math.BigInteger;
import java.util.ArrayList;
import mmm.chemistry.ChemicalLimits;
import mmm.chemistry.ExactRational;
import mmm.fluid.air.GasRegistry;

/** Ambient composition tables shared by local gas transport and initialization. */
final class VulkanGasAmbient {
    private VulkanGasAmbient() {}
    static long cellMassUnits(AmbientTables ambient,double cellSize,boolean sealedExterior) {
        if(sealedExterior || ambient==null)return 0;
        double density=0;
        for(int i=0;i<ambient.handles().length;i++)
            density+=(double)ambient.weights()[i]/ambient.totalWeight()*((double)ambient.massNumerators()[i]/ambient.massDenominators()[i])/10.0;
        return mmm.units.Mass.fromKilograms(density*GasRegistry.STANDARD_TEMPERATURE_K/ambient.temperature()*cellSize*cellSize*cellSize);
    }
    record AmbientTables(int[] handles,long[] weights,long totalWeight,long[] massNumerators,long[] massDenominators,
                         int[] speciesOffsets,int[] speciesHandles,long[] speciesNumerators,long[] speciesDenominators,
                         long[] speciesMass,double temperature) { }
    /** Registry ratios are uploaded exactly as positive signed-64-bit coefficients. */
    static AmbientTables ambientTables(GasRegistry gas,VulkanGasStateLayout.Definitions definitions,double temperature) {
        if(!(Double.isFinite(temperature)&&temperature>0))throw new IllegalArgumentException("Invalid ambient temperature");
        int count=gas.ambient().size();int[] handles=new int[count],offsets=new int[count+1];
        long[] weights=new long[count],mn=new long[count],md=new long[count];BigInteger denominator=BigInteger.ONE;
        for(var entry:gas.ambient())denominator=denominator.divide(denominator.gcd(entry.fraction().denominator())).multiply(entry.fraction().denominator());
        var sh=new ArrayList<Integer>();var sn=new ArrayList<Long>();var sd=new ArrayList<Long>();var sm=new ArrayList<Long>();
        for(int i=0;i<count;i++) {
            var entry=gas.ambient().get(i);var component=gas.component(entry.componentId());
            handles[i]=definitions.componentIds().indexOf(entry.componentId());if(handles[i]<0)throw new IllegalArgumentException("Ambient component absent from device definitions");
            weights[i]=entry.fraction().numerator().multiply(denominator.divide(entry.fraction().denominator())).longValueExact();
            var density=ExactRational.parse(Double.toString(component.standardDensityKgPerCubicMeter()));
            var massRatio=new ExactRational(density.numerator().multiply(BigInteger.TEN),density.denominator());
            mn[i]=massRatio.numerator().longValueExact();md[i]=massRatio.denominator().longValueExact();
            for(var species:component.defaultSpeciesConcentrations()) {
                int handle=definitions.speciesIds().indexOf(species.speciesId());if(handle<0)throw new IllegalArgumentException("Ambient species absent from device definitions");
                var c=species.concentrationMolPerStandardCubicMeter();
                var ratio=new ExactRational(c.numerator().multiply(BigInteger.valueOf(ChemicalLimits.QUANTA_PER_MOL)),c.denominator().multiply(BigInteger.valueOf(1_000_000_000L)));
                sh.add(handle);sn.add(ratio.numerator().longValueExact());sd.add(ratio.denominator().longValueExact());sm.add(species.massUnitsPerQuantum());
            }
            offsets[i+1]=sh.size();
        }
        return new AmbientTables(handles,weights,denominator.longValueExact(),mn,md,offsets,sh.stream().mapToInt(Integer::intValue).toArray(),
                sn.stream().mapToLong(Long::longValue).toArray(),sd.stream().mapToLong(Long::longValue).toArray(),sm.stream().mapToLong(Long::longValue).toArray(),temperature);
    }
}

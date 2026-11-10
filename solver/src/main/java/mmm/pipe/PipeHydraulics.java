package mmm.pipe;

import java.util.List;
import mmm.fluid.air.AirNetwork;
import mmm.fluid.air.GasRegistry;
import mmm.granular.GranularLimits;
import mmm.granular.GranularRegistry;
import mmm.granular.SolidVolume;
import mmm.inventory.LiquidCellInventory;
import mmm.thermal.GasThermalModel;
import mmm.thermal.MixtureVolumeModel;

/** Physical closure for pipe pressure and laminar resistance. */
public final class PipeHydraulics {
    private PipeHydraulics() { }

    public static MixtureVolumeModel.State mixture(PipeSegmentState segment, List<LiquidCellInventory.Stock> liquids,
                                                   GasThermalModel gasThermal) {
        double volume = 0, mass = 0, solid = 0;
        for (var stock : liquids) { volume += stock.volume().toDouble(); mass += stock.massKilograms(); }
        for (var deposit : segment.deposits()) solid += deposit.inventory().solidVolumeSvq() / (double)SolidVolume.SVQ_PER_CUBIC_METER;
        double capacity = segment.capacityM3() - solid;
        if (capacity < 0) throw new IllegalArgumentException("Deposits exceed pipe capacity");
        var gas = segment.gas();
        double temperature = gas.isEmpty() ? GasRegistry.STANDARD_TEMPERATURE_K : gasThermal.state(gas,segment.gasInternalEnergy()).temperatureK();
        double gasConstant = GasRegistry.STANDARD_PRESSURE_PA * AirNetwork.cubicMeters(gas.standardVolumeNanos())
                / GasRegistry.STANDARD_TEMPERATURE_K;
        return MixtureVolumeModel.evaluate(capacity, volume, mass, gasConstant, temperature, 1, 1, 1);
    }

    /** Signed first-to-second laminar flow from the pressure and gravitational head. */
    public static double liquidFlow(PipeConnection edge, PipeSegmentState first, PipeSegmentState second,
                                    MixtureVolumeModel.State firstMixture, MixtureVolumeModel.State secondMixture,
                                    double viscosityPaSeconds, double gravity, GranularRegistry granular) {
        if (!(Double.isFinite(gravity) && gravity >= 0)) throw new IllegalArgumentException("Invalid gravity");
        double firstDensity=firstMixture.liquidDensityKgPerM3(),secondDensity=secondMixture.liquidDensityKgPerM3();
        double density=firstDensity>0 && secondDensity>0 ? .5*(firstDensity+secondDensity) : Math.max(firstDensity,secondDensity);
        double head=firstMixture.absolutePressurePa()-secondMixture.absolutePressurePa()-density*gravity*edge.riseM();
        double result=head/resistance(edge,first,second,viscosityPaSeconds,granular);
        if (!Double.isFinite(result)) throw new IllegalArgumentException("Pipe flow exceeds its numerical representation");
        return result;
    }

    /** Poiseuille resistance with a homogeneous porous-bed contribution, in Pa s / m³. */
    public static double resistance(PipeConnection edge, PipeSegmentState first, PipeSegmentState second,
                                    double viscosityPaSeconds, GranularRegistry granular) {
        if (!(Double.isFinite(viscosityPaSeconds) && viscosityPaSeconds > 0))
            throw new IllegalArgumentException("Invalid pipe viscosity");
        if (edge.opening() == 0) return Double.POSITIVE_INFINITY;
        double area = edge.areaM2() * edge.opening();
        double base = 8 * Math.PI * viscosityPaSeconds * edge.lengthM() / (area * area);
        return base + .5 * (bedResistance(first,edge,viscosityPaSeconds,granular)
                + bedResistance(second,edge,viscosityPaSeconds,granular));
    }
    private static double bedResistance(PipeSegmentState segment, PipeConnection edge, double viscosity,
                                        GranularRegistry granular) {
        double bulk = 0, weightedResistance = 0;
        for (var deposit : segment.deposits()) {
            var group = deposit.inventory();
            var material = granular.material(granular.materialHandle(group.materialId()));
            double porosity = material.restPorosityQ() / (double)GranularLimits.POROSITY_SCALE;
            double groupBulk = group.solidVolumeSvq() / (double)SolidVolume.SVQ_PER_CUBIC_METER / (1 - porosity);
            double diameter = material.primaryGrainDiameterMeters();
            double permeability = diameter * diameter * porosity * porosity * porosity / (180 * (1-porosity) * (1-porosity));
            bulk += groupBulk; weightedResistance += groupBulk / permeability;
        }
        if (bulk == 0) return 0;
        double fraction = Math.min(1,bulk / segment.capacityM3()), gap = 1-fraction;
        double freeK = edge.areaM2() * edge.opening() / (8 * Math.PI);
        double bedK = bulk / weightedResistance;
        double effectiveK = Math.abs(edge.riseM()) > 0
                ? 1 / (gap / freeK + fraction / bedK)
                : gap * gap * gap * freeK + fraction * bedK;
        return viscosity * edge.lengthM() * Math.max(0,1 / effectiveK - 1 / freeK)
                / (edge.areaM2() * edge.opening());
    }
}

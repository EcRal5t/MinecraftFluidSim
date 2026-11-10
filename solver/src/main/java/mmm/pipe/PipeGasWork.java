package mmm.pipe;

import java.util.ArrayList;
import mmm.fluid.air.AirNetwork;
import mmm.fluid.air.GasRegistry;
import mmm.granular.SolidVolume;
import mmm.thermal.GasThermalModel;
import mmm.thermal.MixtureVolumeModel;
import mmm.units.SignedDyadic;

/** Gas compression after advection, using the common finite-mixture equation of state. */
public final class PipeGasWork {
    private PipeGasWork() { }
    public record Result(PipeNetworkState state, SignedDyadic gasWork) { }
    public static Result apply(PipeNetworkState state,double[] advectedGasVolume,GasThermalModel thermal) {
        boolean[] active=new boolean[state.segments().size()];java.util.Arrays.fill(active,true);
        return apply(state,advectedGasVolume,thermal,active);
    }
    public static Result apply(PipeNetworkState state,double[] advectedGasVolume,GasThermalModel thermal,boolean[] active) {
        if (active.length!=state.segments().size())throw new IllegalArgumentException("Gas activity count differs from pipe segments");
        if (advectedGasVolume.length!=state.segments().size()) throw new IllegalArgumentException("Gas volume count differs from pipe segments");
        var segments=new ArrayList<PipeSegmentState>(state.segments().size());
        SignedDyadic work=SignedDyadic.ZERO;
        for(int i=0;i<state.segments().size();i++) {
            var segment=state.segments().get(i);var gas=segment.gas();
            if(!active[i] || gas.isEmpty()) { segments.add(segment);continue; }
            double solid=0,liquid=0;
            for(var d:segment.deposits())solid+=d.inventory().solidVolumeSvq()/(double)SolidVolume.SVQ_PER_CUBIC_METER;
            for(var stock:state.liquids().rows().get(i))liquid+=stock.volume().toDouble();
            var gasState=thermal.state(gas,segment.gasInternalEnergy());
            double constant=GasRegistry.STANDARD_PRESSURE_PA*AirNetwork.cubicMeters(gas.standardVolumeNanos())
                    /GasRegistry.STANDARD_TEMPERATURE_K;
            var result=MixtureVolumeModel.gasWorkState(segment.capacityM3()-solid,liquid,constant,
                    gasState.heatCapacityJPerK(),gasState.temperatureK(),advectedGasVolume[i]);
            double energy=thermal.internalEnergyAtTemperature(gas,result.temperatureK());
            thermal.requireInventoryEnergy(gas,energy);
            work=work.add(SignedDyadic.of(energy).subtract(SignedDyadic.of(segment.gasInternalEnergy())));
            segments.add(new PipeSegmentState(segment.id(),segment.elevationM(),segment.capacityM3(),gas,energy,segment.deposits()));
        }
        return new Result(new PipeNetworkState(segments,state.connections(),state.materialIds(),state.liquids()),work);
    }
}

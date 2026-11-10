package mmm.pipe;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import mmm.fluid.TestFluidDefinitions;
import mmm.inventory.LiquidCellInventory;
import mmm.thermal.GasThermalModel;

/** CPU pipe network timing on equilibrated and pressure-driven chains. */
public final class PipeNetworkBenchmark {
    public static void main(String[] args) {
        var defs=TestFluidDefinitions.load();var thermal=new GasThermalModel(defs.thermal(),defs.gas());
        for(int size:new int[]{100,1000})for(String mode:List.of("balanced","chain","branch","liquid")) {
            var segments=new ArrayList<PipeSegmentState>();var edges=new ArrayList<PipeConnection>();
            var rows=new ArrayList<List<LiquidCellInventory.Stock>>();
            var low=thermal.inventoryAtPressure(1,100000,300,defs.gas().ambient());
            double lowU=thermal.internalEnergyAtTemperature(low,300);
            var caloric=new mmm.thermal.ThermalStateFunction(defs.thermal());
            int water=defs.thermal().materialHandle(mmm.thermal.ThermalMaterialDefinition.CarrierKind.LIQUID,"mmm:water");
            double liquidU=caloric.internalEnergyAtTemperature(water,500,java.util.Map.of(),300);
            for(int i=0;i<size;i++) {
                double pressure=mode.equals("chain")?100000+10000*(size-1-i)/(double)(size-1)
                        :mode.equals("branch") && i==0?110000:100000;
                var gas=pressure==100000?low:thermal.inventoryAtPressure(1,pressure,300,defs.gas().ambient());
                double u=gas==low?lowU:thermal.internalEnergyAtTemperature(gas,300);
                segments.add(new PipeSegmentState("segment"+i,0,1,gas,u,List.of()));
                rows.add(mode.equals("liquid")?List.of(new LiquidCellInventory.Stock(new LiquidCellInventory.Key(i,0,255),
                        mmm.units.SignedDyadic.of(.5),mmm.units.Mass.fromKilograms(500),null,liquidU)):List.of());if(i>0)edges.add(new PipeConnection(mode.equals("branch")?0:i-1,i,1,.1,0,1,0));
            }
            var state=new PipeNetworkState(segments,edges,mode.equals("liquid")?List.of("mmm:water"):List.of(),new LiquidCellInventory.Snapshot(rows));
            for(int i=0;i<4;i++)state=PipeNetworkSolver.step(state,.2,defs,9.81).state();
            long[] samples=new long[10];
            for(int i=0;i<samples.length;i++) {
                long start=System.nanoTime();state=PipeNetworkSolver.step(state,.2,defs,9.81).state();samples[i]=System.nanoTime()-start;
            }
            Arrays.sort(samples);
            System.out.printf("pipe cpu segments=%d mode=%s p50_ms=%.3f p95_ms=%.3f%n",size,mode,
                    samples[samples.length/2]*1e-6,samples[(int)Math.ceil(samples.length*.95)-1]*1e-6);
        }
    }
}

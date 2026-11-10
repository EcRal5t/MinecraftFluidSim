package mmm.simulation;

import java.util.UUID;
import mmm.api.sensor.ProcessSensorSnapshot;
import mmm.api.sensor.ProcessSensorSnapshot.Measure;
import mmm.api.sensor.ProcessSensorSnapshot.Status;
import mmm.block.MmmBlocks;
import mmm.block.ProcessSensorBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueInput;

/** Test-only consumers shaped like a display source and a client goggle overlay. */
public final class FakeCreateDisplayValidation {
    private record Line(Status status, Double value, String unit) { }
    private static final class FakeDisplaySource {
        Line read(ProcessSensorSnapshot snapshot) {
            return new Line(snapshot.status(),snapshot.value(),snapshot.measure().unit());
        }
    }
    private static final class FakeGoggles {
        Line lookAt(ProcessSensorBlockEntity client) { return new FakeDisplaySource().read(client.snapshot()); }
    }
    private FakeCreateDisplayValidation() { }
    public static void main(String[] args) {
        GpuControllerFixture.initializeItems(); GpuControllerFixture.initializeIndustrialBlocks();
        var registry=RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var server=new ProcessSensorBlockEntity(BlockPos.ZERO,MmmBlocks.PROCESS_SENSOR.defaultBlockState());
        var client=new ProcessSensorBlockEntity(BlockPos.ZERO,MmmBlocks.PROCESS_SENSOR.defaultBlockState());
        var display=new FakeDisplaySource(); var goggles=new FakeGoggles(); var region=UUID.randomUUID();
        for(var measure:Measure.values()) {
            server.configure(measure,0,400,false,false,BlockPos.ZERO,BlockPos.ZERO);
            var waiting=display.read(server.snapshot());
            check(waiting.status()==Status.WAITING && waiting.value()==null,"waiting sample was presented as zero");
            double value=switch(measure) {
                case LEVEL -> .25;
                case LIQUID_TEMPERATURE -> 302.5;
                case GAS_TEMPERATURE -> 315.5;
                case PRESSURE -> 125000;
                case FLOW -> -2.75;
            };
            String unit=switch(measure) {
                case LEVEL -> "1";
                case LIQUID_TEMPERATURE, GAS_TEMPERATURE -> "K";
                case PRESSURE -> "Pa";
                case FLOW -> "L/s";
            };
            server.observe(value,region,2,3,.2,true);
            var expected=new Line(Status.RUNNING,value,unit);
            int signal=server.output(), history=server.history().points().size();
            var first=server.snapshot();
            check(display.read(first).equals(expected),"display changed sample or unit: "+measure);
            sync(server,client,registry);
            check(goggles.lookAt(client).equals(expected),"goggles received different sample: "+measure);
            check(server.snapshot()==first && server.output()==signal && server.history().points().size()==history,
                    "display observation changed sensor state");
            server.observe(value,region,2,3,.2,false); sync(server,client,registry);
            check(goggles.lookAt(client).equals(new Line(Status.PAUSED,value,unit)),"paused value lost");
            server.unavailable(Status.NO_SAMPLE); sync(server,client,registry);
            check(goggles.lookAt(client).equals(new Line(Status.NO_SAMPLE,null,unit)),"missing sample was shown as zero");
            server.unavailable(Status.UNAVAILABLE); sync(server,client,registry);
            check(goggles.lookAt(client).equals(new Line(Status.UNAVAILABLE,null,unit)),"unavailable value was retained");
        }
        System.out.println("FakeCreateDisplayValidation passed");
    }
    private static void sync(ProcessSensorBlockEntity server, ProcessSensorBlockEntity client,RegistryAccess registry) {
        client.loadCustomOnly(TagValueInput.create(ProblemReporter.DISCARDING,registry,server.getUpdateTag(registry)));
    }
    private static void check(boolean condition,String message) { if(!condition)throw new AssertionError(message); }
}

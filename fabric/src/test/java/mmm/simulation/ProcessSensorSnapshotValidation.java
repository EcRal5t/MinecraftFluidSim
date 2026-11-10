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

/** Committed sensor state and the exact block-entity update/save payload. */
public final class ProcessSensorSnapshotValidation {
    public static void main(String[] args) {
        GpuControllerFixture.initializeItems(); GpuControllerFixture.initializeIndustrialBlocks();
        var registry=RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var sensor=create(); var first=new BlockPos(1,2,3); var last=new BlockPos(2,3,4);
        check(sensor.snapshot().status()==Status.WAITING && sensor.snapshot().value()==null,"initial reading");
        check(!sensor.trendObserved(10), "trend sampling started without a viewer");
        sensor.refreshTrendLease(10);
        check(sensor.trendObserved(50) && !sensor.trendObserved(51), "trend sampling lease expiry");
        check(!sensor.snapshot().display().contains(": 0"),"initial display presented zero as a sample");
        sensor.configure(Measure.LEVEL,.2,.8,true,false,first,last);
        var region=UUID.randomUUID(); sensor.observe(.15,region,7,11,1.5,true);
        var reading=sensor.snapshot();
        check(reading.status()==Status.RUNNING && reading.value()==.15 && reading.measure().unit().equals("1")
                && reading.regionId().equals(region) && reading.regionIncarnation()==7 && reading.solverGeneration()==11
                && reading.simulationSeconds()==1.5 && sensor.output()==15,"committed observation");
        var update=sensor.getUpdateTag(registry); var client=create();
        client.loadCustomOnly(TagValueInput.create(ProblemReporter.DISCARDING,registry,update));
        check(client.snapshot().equals(reading) && client.output()==15,"initial/update packet snapshot");
        sensor.observe(.15,region,7,12,1.55,false);
        check(sensor.snapshot().status()==Status.PAUSED && sensor.snapshot().value()==.15
                && sensor.snapshot().solverGeneration()==12,"paused observation");
        var paused=sensor.getUpdateTag(registry);
        client.loadCustomOnly(TagValueInput.create(ProblemReporter.DISCARDING,registry,paused));
        check(client.snapshot().equals(sensor.snapshot()),"paused update");
        sensor.unavailable(Status.UNAVAILABLE); sensor.signal(0);
        client.loadCustomOnly(TagValueInput.create(ProblemReporter.DISCARDING,registry,sensor.getUpdateTag(registry)));
        check(client.snapshot().status()==Status.UNAVAILABLE && client.snapshot().value()==null
                && client.snapshot().regionId()==null,"unavailable update retained old reading");
        sensor.unavailable(Status.NO_SAMPLE);
        check(sensor.snapshot().status()==Status.NO_SAMPLE && sensor.snapshot().value()==null,"empty carrier");
        var replacement=UUID.randomUUID(); sensor.observe(295.25,replacement,8,1,.05,false);
        sensor.history().region(replacement,8); sensor.history().samplePaused(.05,295.25);
        sensor.history(Measure.PRESSURE).region(replacement,8);
        sensor.history(Measure.PRESSURE).sample(.05,101325);
        sensor.unavailable(Status.NO_SAMPLE);
        check(sensor.history(Measure.PRESSURE).points().size()==1, "one unavailable metric discarded another history");
        sensor.configure(Measure.LIQUID_TEMPERATURE,270,350,true,false,first,last);
        check(sensor.snapshot().status()==Status.WAITING && sensor.snapshot().measure()==Measure.LIQUID_TEMPERATURE
                && sensor.snapshot().value()==null && sensor.snapshot().regionId()==null && sensor.output()==0
                && sensor.history().points().isEmpty() && sensor.history(Measure.PRESSURE).points().isEmpty(),"reconfiguration retained old data");
        check(Measure.LIQUID_TEMPERATURE.unit().equals("K") && Measure.GAS_TEMPERATURE.unit().equals("K")
                && Measure.PRESSURE.unit().equals("Pa") && Measure.FLOW.unit().equals("L/s"),"measurement units");
        sensor.observe(260,replacement,8,3,.1,true);
        var save=sensor.saveCustomOnly(registry); var restored=create();
        restored.loadCustomOnly(TagValueInput.create(ProblemReporter.DISCARDING,registry,save));
        check(restored.snapshot().status()==Status.WAITING && restored.snapshot().value()==null
                && restored.snapshot().measure()==Measure.LIQUID_TEMPERATURE && restored.output()==15,
                "save restored an obsolete live observation or lost the persisted signal");
        restored.unavailable(Status.UNAVAILABLE);
        check(restored.output()==0 && restored.snapshot().value()==null,"unavailable observation did not clear persisted signal");
        System.out.println("ProcessSensorSnapshotValidation passed");
    }
    private static ProcessSensorBlockEntity create() {
        return new ProcessSensorBlockEntity(BlockPos.ZERO,MmmBlocks.PROCESS_SENSOR.defaultBlockState());
    }
    private static void check(boolean value,String message) { if(!value)throw new AssertionError(message); }
}
